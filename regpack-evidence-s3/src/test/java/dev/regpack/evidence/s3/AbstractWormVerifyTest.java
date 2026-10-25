package dev.regpack.evidence.s3;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.regpack.evidence.s3.S3TestService.S3Settings;
import dev.regpack.evidence.s3.client.JdkTransport;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.evidence.s3.client.S3Client.VersionEntry;
import dev.regpack.evidence.s3.client.S3Transport;
import dev.regpack.evidence.s3.client.SigV4.Param;
import dev.regpack.evidence.seal.DurableSeal;
import dev.regpack.evidence.seal.EpochMarker;
import dev.regpack.evidence.seal.SealedKeys;
import dev.regpack.evidence.seal.SendGate;
import dev.regpack.evidence.verify.EvidenceVerifier;
import dev.regpack.evidence.verify.EvidenceVerifier.Finding;
import dev.regpack.evidence.verify.EvidenceVerifier.Kind;
import dev.regpack.evidence.verify.EvidenceVerifier.Report;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.model.Sha256;
import dev.regpack.ports.Clock;
import dev.regpack.ports.connector.Transport.OutboundFile;
import dev.regpack.ports.connector.Transport.SendResult;
import dev.regpack.ports.connector.Transport.SendStatus;
import dev.regpack.ports.evidence.EvidenceCorruptionException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore.Durability;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.evidence.journal.JournalKey;
import dev.regpack.ports.evidence.journal.JournalObject;
import dev.regpack.ports.evidence.journal.JournalScope;
import dev.regpack.ports.evidence.journal.JournalType;
import dev.regpack.ports.evidence.journal.JournalWriter;
import dev.regpack.ports.evidence.seal.Sealing.PreSendStatus;
import dev.regpack.ports.evidence.seal.Sealing.SealOutcome;
import dev.regpack.ports.evidence.seal.Sealing.SealRequest;
import dev.regpack.ports.evidence.seal.Sealing.SealStatus;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptId;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptIntent;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.DeliveryLedger.SendingResult;
import dev.regpack.ports.ledger.DeliveryLedger.SendingStatus;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseResult;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.ports.ledger.ManifestLedger.ObjectClass;
import dev.regpack.ports.ledger.ManifestLedger.ObjectEntry;
import dev.regpack.ports.ledger.ManifestLedger.Provenance;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.LedgerFixtures;
import dev.regpack.testkit.contract.SealScenarioContract;
import dev.regpack.testkit.sim.DestinationSimulator;
import dev.regpack.testkit.sim.Scenarios;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * T9: {@code verify} over the evidence tier and the ledger's manifest, and the storage half of I10,
 * on P-WORM: a real {@link WormEvidenceStore} on a fresh object-lock bucket and an in-memory
 * ledger; the deliveries are sealed by {@link DurableSeal}; the faults are seeded behind the
 * store's back through the raw client ({@link #rawClient}) or by hand, and every one must be found
 * with its own class and nothing else. The backend comes from the seam of T8a; a {@code *Test}
 * subclass runs over the fake, a {@code *IT} subclass over the real service.
 *
 * <p>The test classes of {@code regpack-evidence} are not visible from this module, so the sealing
 * fixtures that are needed are copied from {@code EvidenceVerifierTest} (T15 of P3b1) and the
 * P-SEALED seeders are replaced by P-WORM ones. Nothing is deleted from a locked bucket: a fault
 * that deletes a file on P-SEALED is, on P-WORM, a manifest row without an object (MISSING) or a
 * delete marker that hides the object (HASH_MISMATCH {@code store-integrity}).
 *
 * <p>The bypass header is sent here, by the raw client of a test, to shorten a governance lock
 * behind the adapter's back, and nowhere in {@code src/main}.
 */
abstract class AbstractWormVerifyTest {
  private static final Duration TTL = Duration.ofSeconds(60);
  private static final RetentionPolicy RETENTION = RetentionPolicy.fixedDays(1826);
  private static final String ABSENT = "the object is absent from the evidence tier";
  private static final String XMLNS = "http://s3.amazonaws.com/doc/2006-03-01/";
  private static final String BYPASS_HEADER = "x-amz-bypass-" + "governance-retention";
  private static final byte[] NO_BODY = new byte[0];

  /** The raw client of the service, with the test credentials. */
  abstract S3Client rawClient();

  /** A fresh bucket with object lock on. */
  abstract String newBucket();

  /** The clock of {@code X-Amz-Date}: real time against a real service, manual on the fake. */
  abstract Clock signingClock();

  /** The endpoint, region and credentials of the service. */
  abstract S3Settings settings();

  // -------------------------------------------------------------------------------------------
  // fixtures

  /** What a seal left: the request, the attempt and the token it was sealed under. */
  private record Sealed(SealRequest request, AttemptId attempt, LeaseToken token) {
    EvidenceKey sealedKey() {
      return SealedKeys.of(request.intent().artifactSha256());
    }

    EvidenceKey intentKey() {
      return new JournalKey.ClassA(
              JournalScope.of(request.endpoint()),
              token.restoreEpoch(),
              JournalType.DELIVERY_INTENT,
              Long.toString(attempt.value()),
              1)
          .evidenceKey();
    }

    OutboundFile file() {
      return new OutboundFile(
          "file-" + request.intent().businessKey(),
          request.fileName(),
          request.bytes(),
          request.intent().artifactSha256(),
          request.mediaType());
    }
  }

  /** A delivery that has an attempt row and nothing else (the intent object is put by hand). */
  private record Started(
      EndpointKey endpoint,
      LeaseToken token,
      DeliveryId delivery,
      String businessKey,
      AttemptId attempt,
      Sha256 artifact,
      String fileName) {
    /** The bytes whose hash is {@link #artifact()}. */
    byte[] artifactBytes() {
      return ("artifact-" + fileName).getBytes(UTF_8);
    }
  }

  /**
   * An in-memory ledger, a P-WORM store on a fresh bucket, a sealer and a verifier. Every instance
   * is a tier of its own (a fresh bucket), so that one test can seed several faults one by one.
   */
  private final class Tier {
    final ManualClock clock = new ManualClock(AbstractWormContractTest.START);
    final InMemoryLedgerStore ledger = new InMemoryLedgerStore(clock);
    final OutageTransport transport = new OutageTransport(new JdkTransport(Duration.ofSeconds(60)));
    final WormConfig config;
    final WormEvidenceStore store;
    final JournalWriter journal;
    final DurableSeal sealing;
    final EvidenceVerifier verifier;

    Tier() {
      S3Settings settings = settings();
      config =
          WormConfig.governance(
              settings.endpoint(),
              settings.region(),
              newBucket(),
              settings.credentials(),
              new Durability(true, 1));
      store = WormEvidenceStore.open(config, clock, signingClock(), transport);
      journal = new JournalWriter(store, RETENTION);
      sealing =
          new DurableSeal(
              ledger, store, clock, RETENTION, new SendGate(SendGate.Requirement.none()));
      verifier = new EvidenceVerifier(store, ledger);
    }

    String bucket() {
      return config.bucket();
    }

    LeaseToken lease(EndpointKey endpoint) {
      LeaseResult result = ledger.acquire(endpoint, "A", TTL);
      if (!(result instanceof LeaseResult.Granted granted)) {
        throw new AssertionError("the lease was not granted: " + result);
      }
      return granted.grant().token();
    }

    Sealed seal(EndpointKey endpoint, LeaseToken token, String businessKey) {
      DeliveryId delivery = SealScenarioContract.queued(ledger, endpoint, businessKey);
      SealRequest request =
          SealScenarioContract.request(
              endpoint, token, delivery, businessKey, reportOf(businessKey));
      SealOutcome outcome = sealing.seal(request);
      assertThat(outcome.status()).as("the seal: " + outcome.detail()).isEqualTo(SealStatus.SEALED);
      return new Sealed(request, outcome.attempt().orElseThrow(), token);
    }

    /** Two sealed deliveries of one endpoint: four objects, four manifest rows. */
    List<Sealed> baseline() {
      EndpointKey endpoint = LedgerFixtures.endpoint("VER1");
      LeaseToken token = lease(endpoint);
      return List.of(seal(endpoint, token, "BK-A"), seal(endpoint, token, "BK-B"));
    }

    /** A delivery with an allocated attempt id, not yet sending. */
    Started start(String name) {
      EndpointKey endpoint = LedgerFixtures.endpoint(name);
      LeaseToken token = lease(endpoint);
      DeliveryId delivery = LedgerFixtures.queuedDelivery(ledger, endpoint, "BK-" + name, 1);
      String fileName = "file-" + name + ".xml";
      return new Started(
          endpoint,
          token,
          delivery,
          "BK-" + name,
          ledger.allocateAttemptId(),
          Sha256.ofUtf8("artifact-" + fileName),
          fileName);
    }

    /** The payload of an intent object that agrees with the attempt row of {@code started}. */
    Map<String, Object> intentPayload(Started started) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("attempt_id", started.attempt().value());
      payload.put("delivery_id", started.delivery().value());
      payload.put("business_key", started.businessKey());
      payload.put("trn", "TRN-1");
      payload.put("version_seq", 1L);
      payload.put("action", "NEWT");
      payload.put("logical_hash", Sha256.ofUtf8("logical").hex());
      payload.put("artifact_sha256", started.artifact().hex());
      payload.put("file_name", started.fileName());
      payload.put("context_ids", List.of(Sha256.ofUtf8("ctx").hex()));
      return payload;
    }

    /**
     * Writes an intent object with the edited payload through the journal writer and starts the
     * attempt with a manifest row that names the object exactly as it was written.
     */
    EvidenceKey handWrittenIntent(String name, UnaryOperator<Map<String, Object>> edit) {
      Started started = start(name);
      Map<String, Object> payload = edit.apply(intentPayload(started));
      JournalObject object =
          new JournalObject(
              new JournalKey.ClassA(
                  JournalScope.of(started.endpoint()),
                  started.token().restoreEpoch(),
                  JournalType.DELIVERY_INTENT,
                  Long.toString(started.attempt().value()),
                  1),
              JournalType.DELIVERY_INTENT,
              1,
              clock.now(),
              payload);
      journal.write(object);
      byte[] bytes = object.toBytes();
      begin(started, object.key().evidenceKey(), bytes);
      return object.key().evidenceKey();
    }

    /** Puts {@code bytes} as a sealed file with its manifest row, as a seal would have. */
    void addSealedFile(byte[] bytes) {
      Sha256 sha = Sha256.of(bytes);
      store.put(
          new EvidenceStore.PutRequest(
              SealedKeys.of(sha),
              bytes,
              "application/xml",
              RETENTION.retentionFor(DurableSeal.SEALED_RETENTION_CLASS, clock.now())));
      ledger.recordObjects(
          List.of(new ObjectEntry(SealedKeys.of(sha), ObjectClass.SEALED_FILE, sha, bytes.length)),
          Provenance.WRITTEN,
          Optional.empty());
    }

    /** Starts the attempt; the sealed file is in the tier and the same unit names it. */
    private void begin(Started started, EvidenceKey key, byte[] bytes) {
      List<ObjectEntry> objects = new ArrayList<>();
      byte[] artifact = started.artifactBytes();
      if (store.get(SealedKeys.of(Sha256.of(artifact))).isEmpty()) {
        addSealedFile(artifact);
      }
      objects.add(new ObjectEntry(key, ObjectClass.JOURNAL_A, Sha256.of(bytes), bytes.length));
      SendingResult result =
          ledger.beginSending(
              started.delivery(),
              started.attempt(),
              started.token(),
              new AttemptIntent(started.artifact(), started.fileName(), objects));
      assertThat(result.status()).isEqualTo(SendingStatus.APPLIED);
    }

    /** A class (b) object with a manifest row. */
    void classB(JournalScope scope, long epoch, long seq) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("attempt_id", 7L);
      payload.put("status", "ACCEPTED");
      payload.put("detail", "http 202");
      payload.put("observed_at", clock.now());
      JournalObject object =
          new JournalObject(
              new JournalKey.ClassB(scope, epoch, seq),
              JournalType.TRANSPORT_RESULT,
              1,
              clock.now(),
              payload);
      journal.write(object);
      byte[] bytes = object.toBytes();
      ledger.recordObjects(
          List.of(
              new ObjectEntry(
                  object.key().evidenceKey(),
                  ObjectClass.JOURNAL_B,
                  Sha256.of(bytes),
                  bytes.length)),
          Provenance.WRITTEN,
          Optional.empty());
    }

    void putPlain(String path, String text) {
      store.put(
          new EvidenceStore.PutRequest(
              new EvidenceKey(path),
              text.getBytes(UTF_8),
              "text/plain",
              new EvidenceStore.Retention(
                  new EvidenceStore.RetentionClass("landing"),
                  clock.now().plus(Duration.ofDays(30)))));
    }

    /** A manifest row for {@code path}, whatever the tier holds under it. */
    void recordRow(String path, Sha256 sha, long size) {
      ledger.recordObjects(
          List.of(new ObjectEntry(new EvidenceKey(path), ObjectClass.OTHER, sha, size)),
          Provenance.WRITTEN,
          Optional.empty());
    }

    // ---- faults behind the store's back, through the raw client

    /**
     * A delete marker over the key: a DELETE without a version id, which a locked bucket accepts
     * and which hides the object from a plain read but deletes nothing.
     */
    void hide(EvidenceKey key) {
      rawClient().send("DELETE", bucket(), Optional.of(key.path()), List.of(), Map.of(), NO_BODY);
    }

    /**
     * An object put by another writer whose recorded SHA-256 is not the hash of its bytes (the lock
     * and every other record are as the adapter writes them), with a manifest row that names the
     * bytes as they are.
     */
    void putDamaged(String path, String text) {
      byte[] bytes = text.getBytes(UTF_8);
      Instant retainUntil = clock.now().plus(Duration.ofDays(30));
      Map<String, String> headers = new TreeMap<>();
      headers.put("content-type", "application/octet-stream");
      headers.put("x-amz-meta-regpack-sha256", Sha256.ofUtf8("some other bytes").hex());
      headers.put("x-amz-meta-regpack-written-at", clock.now().toString());
      headers.put("x-amz-meta-regpack-retain-until", retainUntil.toString());
      headers.put("x-amz-meta-regpack-class", base64Url("landing"));
      headers.put("x-amz-meta-regpack-media-type", base64Url("text/plain"));
      headers.put("x-amz-object-lock-mode", "GOVERNANCE");
      headers.put("x-amz-object-lock-retain-until-date", retainUntil.toString());
      rawClient().putObject(bucket(), path, bytes, headers);
      recordRow(path, Sha256.of(bytes), bytes.length);
    }

    /**
     * Shortens the governance lock of the oldest data version of {@code key} to {@code to}, with
     * the bypass header, as a principal that holds the bypass permission could.
     */
    void shortenLock(EvidenceKey key, Instant to) {
      String versionId = oldestVersion(key);
      byte[] body =
          ("<Retention xmlns=\""
                  + XMLNS
                  + "\"><Mode>GOVERNANCE</Mode><RetainUntilDate>"
                  + to
                  + "</RetainUntilDate></Retention>")
              .getBytes(UTF_8);
      Map<String, String> headers = new TreeMap<>();
      headers.put("content-md5", md5Base64(body));
      headers.put(BYPASS_HEADER, "true");
      rawClient()
          .send(
              "PUT",
              bucket(),
              Optional.of(key.path()),
              List.of(new Param("retention", ""), new Param("versionId", versionId)),
              headers,
              body);
    }

    private String oldestVersion(EvidenceKey key) {
      List<VersionEntry> entries =
          rawClient()
              .listVersions(bucket(), key.path(), Optional.empty(), Optional.empty(), 1_000)
              .entries()
              .stream()
              .filter(e -> e.key().equals(key.path()) && !e.deleteMarker())
              .toList();
      assertThat(entries).as("a data version of " + key.path()).isNotEmpty();
      // newest first (probe P7): the oldest data version is the last
      return entries.get(entries.size() - 1).versionId();
    }
  }

  private static byte[] reportOf(String businessKey) {
    return ("<report>" + businessKey + "</report>").getBytes(UTF_8);
  }

  private static Finding finding(Kind kind, EvidenceKey key, String detail) {
    return new Finding(kind, key.path(), detail);
  }

  private static EvidenceKey classBKey(JournalScope scope, long epoch, long seq) {
    return new JournalKey.ClassB(scope, epoch, seq).evidenceKey();
  }

  private static String base64Url(String text) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(UTF_8));
  }

  private static String md5Base64(byte[] body) {
    try {
      return Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(body));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  // -------------------------------------------------------------------------------------------
  // a clean tier

  @Test
  void aCleanTierHasNoFinding() {
    Tier tier = new Tier();
    assertThat(tier.verifier.verify("")).isEqualTo(new Report(List.of(), 0, 0));

    tier.baseline();
    Report afterSealing = tier.verifier.verify("");
    assertThat(afterSealing.findings()).isEmpty();
    assertThat(afterSealing.clean()).isTrue();
    assertThat(afterSealing.objectsChecked())
        .as("two sealed files and two intent objects")
        .isEqualTo(4);
    assertThat(afterSealing.manifestRowsChecked()).isEqualTo(4);

    // a restore-epoch marker is written before the ledger knows the epoch: it is no orphan
    new EpochMarker(tier.store, RETENTION, tier.clock).declare(1);
    Report withMarker = tier.verifier.verify("");
    assertThat(withMarker.findings()).isEmpty();
    assertThat(withMarker.objectsChecked()).isEqualTo(5);
    assertThat(withMarker.manifestRowsChecked()).isEqualTo(4);

    // the prefix narrows both sides alike
    Report journal = tier.verifier.verify("journal/");
    assertThat(journal.findings()).isEmpty();
    assertThat(journal.objectsChecked()).isEqualTo(2);
    assertThat(journal.manifestRowsChecked()).isEqualTo(2);
    Report sealedFiles = tier.verifier.verify("sealed/");
    assertThat(sealedFiles.findings()).isEmpty();
    assertThat(sealedFiles.objectsChecked()).isEqualTo(2);
    assertThat(sealedFiles.manifestRowsChecked()).isEqualTo(2);
    assertThat(tier.verifier.verify("landing/")).isEqualTo(new Report(List.of(), 0, 0));
  }

  // -------------------------------------------------------------------------------------------
  // I10, storage half

  @Test
  void theIdentityOfEverySentDeliveryIsReconstructableFromTheEvidenceTierAlone() {
    Tier tier = new Tier();
    EndpointKey endpoint = LedgerFixtures.endpoint("I10");
    LeaseToken token = tier.lease(endpoint);
    DestinationSimulator destination =
        DestinationSimulator.withScenario(tier.clock, Scenarios.acpt(Duration.ofSeconds(1)));
    List<Sealed> sent = new ArrayList<>();
    for (String businessKey : List.of("BK-1", "BK-2", "BK-3")) {
      Sealed one = tier.seal(endpoint, token, businessKey);
      SendResult result =
          destination.send(
              one.file(),
              () ->
                  tier.sealing.beforeFirstByte(endpoint, one.attempt(), token)
                      == PreSendStatus.CLEAR);
      assertThat(result.status()).isEqualTo(SendStatus.DELIVERED);
      sent.add(one);
    }
    assertThat(destination.receivedFiles()).hasSize(3);
    assertThat(tier.verifier.verify("").clean()).isTrue();

    // the database is gone: a fresh ledger knows nothing, and only the evidence store is read
    InMemoryLedgerStore freshLedger = new InMemoryLedgerStore(tier.clock);
    assertThat(freshLedger.attempt(new AttemptId(1))).isEmpty();
    assertThat(freshLedger.manifestRows("", Optional.empty(), 100)).isEmpty();
    JournalWriter reader = new JournalWriter(tier.store, RETENTION);
    Map<Long, JournalObject> intents = new LinkedHashMap<>();
    Optional<EvidenceKey> after = Optional.empty();
    while (true) {
      List<EvidenceKey> page = tier.store.list("journal/", after, 2);
      for (EvidenceKey key : page) {
        if (JournalKey.parse(key) instanceof JournalKey.ClassA a
            && a.type() == JournalType.DELIVERY_INTENT) {
          intents.put(Long.parseLong(a.subjectId()), reader.read(a).orElseThrow());
        }
      }
      if (page.isEmpty()) {
        break;
      }
      after = Optional.of(page.get(page.size() - 1));
    }
    assertThat(intents).hasSize(3);
    // the fixtures give the three deliveries three versions and three actions: a tier whose intent
    // objects say version 1 and NEWT for all of them is not the identity that was sent
    assertThat(intents.values())
        .extracting(intent -> intent.payload().get("version_seq"))
        .containsExactlyInAnyOrder(2L, 3L, 4L);
    assertThat(intents.values())
        .extracting(intent -> intent.payload().get("action"))
        .containsExactlyInAnyOrder("CANC", "REPL", "NEWT");

    for (Sealed s : sent) {
      JournalObject intent = intents.get(s.attempt().value());
      assertThat(intent).as("the intent object of attempt " + s.attempt().value()).isNotNull();
      var expected = s.request().intent();
      Map<String, Object> payload = intent.payload();
      assertThat(payload.get("attempt_id")).isEqualTo(s.attempt().value());
      assertThat(payload.get("delivery_id")).isEqualTo(expected.delivery().value());
      assertThat(payload.get("business_key")).isEqualTo(expected.businessKey());
      assertThat(payload.get("trn")).isEqualTo(expected.trn());
      assertThat(payload.get("version_seq")).isEqualTo(expected.versionSeq());
      assertThat(payload.get("action")).isEqualTo(expected.action());
      assertThat(payload.get("logical_hash")).isEqualTo(expected.logicalHash().hex());
      assertThat(payload.get("artifact_sha256")).isEqualTo(expected.artifactSha256().hex());
      assertThat(payload.get("file_name")).isEqualTo(expected.fileName());
      assertThat(payload.get("context_ids"))
          .isEqualTo(expected.contextIds().stream().map(Sha256::hex).toList());
      // the sealed bytes are in the tier under the hash that the intent names
      EvidenceStore.StoredObject file =
          tier.store
              .get(SealedKeys.of(new Sha256((String) payload.get("artifact_sha256"))))
              .orElseThrow();
      assertThat(Sha256.of(file.bytes()).hex()).isEqualTo(payload.get("artifact_sha256"));
      assertThat(file.bytes()).isEqualTo(s.request().bytes());
    }
    assertThat(destination.receivedFiles())
        .extracting(f -> f.sha256().hex())
        .containsExactlyInAnyOrderElementsOf(
            sent.stream().map(s -> s.request().intent().artifactSha256().hex()).toList());

    // verify against the fresh ledger sees the situation of a restore: every object is evidence
    // the database does not know, and the intents are the in-doubt input of 11.5 B.5
    Report restore = new EvidenceVerifier(tier.store, freshLedger).verify("");
    assertThat(restore.findings()).hasSize(6).allMatch(f -> f.kind() == Kind.ORPHAN);
    assertThat(
            restore.findings().stream().filter(f -> f.detail().equals(EvidenceVerifier.IN_DOUBT)))
        .as("one in-doubt input per attempt")
        .hasSize(3);
  }

  // -------------------------------------------------------------------------------------------
  // seeded faults

  /**
   * Seeds one fault into a tier that holds two sealed deliveries and returns what must be found.
   */
  private interface Seeder {
    List<Finding> seed(Tier tier, List<Sealed> seals) throws Exception;
  }

  /**
   * The faults of P-WORM: eight entries, seven of which have a twin among the 20 faults of {@code
   * EvidenceVerifierTest#eachSeededFaultIsReportedWithItsOwnClassAndNothingElse} on P-SEALED (the
   * twin is named in each entry); the delete marker has none, because P-SEALED has no versions. The
   * other thirteen of the 20 are verifier logic that does not depend on the store (INTENT_MISMATCH
   * of the key scope, epoch, file name and so on) and stay shown on P-SEALED and the in-memory
   * store.
   */
  private static Map<String, Seeder> seededFaults() {
    Map<String, Seeder> faults = new LinkedHashMap<>();
    faults.put(
        // twin: one byte flipped in both copies of a sealed object
        "a damaged object: the recorded SHA-256 is not the hash of its bytes",
        (tier, seals) -> {
          tier.putDamaged("landing/damaged", "damaged");
          return List.of(
              finding(
                  Kind.HASH_MISMATCH,
                  new EvidenceKey("landing/damaged"),
                  EvidenceVerifier.STORE_INTEGRITY));
        });
    faults.put(
        // twin: the retention record damaged on both volumes
        "a lock shortened behind the back",
        (tier, seals) -> {
          EvidenceKey key = seals.get(1).sealedKey();
          tier.shortenLock(key, Instant.parse("2041-01-01T00:00:00Z"));
          return List.of(finding(Kind.HASH_MISMATCH, key, EvidenceVerifier.STORE_INTEGRITY));
        });
    faults.put(
        // no twin: a delete marker over a locked object
        "a delete marker over a sealed object",
        (tier, seals) -> {
          EvidenceKey key = seals.get(0).sealedKey();
          tier.hide(key);
          return List.of(finding(Kind.HASH_MISMATCH, key, EvidenceVerifier.STORE_INTEGRITY));
        });
    faults.put(
        // twin: a manifest row with the right hash and another length / other bytes under a header
        "a manifest row with another hash than the object",
        (tier, seals) -> {
          tier.putPlain("landing/hashed", "hashed");
          tier.recordRow("landing/hashed", Sha256.ofUtf8("something else"), "hashed".length());
          return List.of(
              finding(
                  Kind.HASH_MISMATCH,
                  new EvidenceKey("landing/hashed"),
                  EvidenceVerifier.MANIFEST_HASH));
        });
    faults.put(
        // twin: both copies of a sealed object deleted (nothing is deleted from a locked bucket)
        "a manifest row for an object that was never put",
        (tier, seals) -> {
          tier.recordRow("landing/ghost", Sha256.ofUtf8("ghost"), "ghost".length());
          return List.of(finding(Kind.MISSING, new EvidenceKey("landing/ghost"), ABSENT));
        });
    faults.put(
        // twin: an object put directly, with no manifest row
        "an object put directly, with no manifest row",
        (tier, seals) -> {
          tier.putPlain("landing/extra", "extra");
          return List.of(
              finding(
                  Kind.ORPHAN,
                  new EvidenceKey("landing/extra"),
                  "sha256=" + Sha256.ofUtf8("extra").hex()));
        });
    faults.put(
        // twin: object 3 of 5 of a class b sequence deleted (here the object was never written)
        "a class b sequence of 1, 2 and 4",
        (tier, seals) -> {
          JournalScope scope = JournalScope.of(LedgerFixtures.endpoint("SEQ"));
          for (long seq : new long[] {1, 2, 4}) {
            tier.classB(scope, 0, seq);
          }
          return List.of(
              finding(Kind.SEQUENCE_GAP, classBKey(scope, 0, 3), "expected seq 3, found seq 4"));
        });
    faults.put(
        // twin: an intent object with another artifact hash than its attempt row
        "an intent object with another artifact hash than its attempt row",
        (tier, seals) -> {
          // the other file is in the tier and the manifest, so that nothing else is wrong
          tier.addSealedFile("another".getBytes(UTF_8));
          EvidenceKey key =
              tier.handWrittenIntent(
                  "HAND1",
                  payload -> {
                    payload.put("artifact_sha256", Sha256.ofUtf8("another").hex());
                    return payload;
                  });
          return List.of(
              finding(Kind.INTENT_MISMATCH, key, "artifact_sha256 differs from the attempt row"));
        });
    return faults;
  }

  @Test
  void eachSeededFaultIsReportedWithItsOwnClassAndNothingElse() throws Exception {
    Comparator<Finding> order =
        Comparator.comparing(Finding::key)
            .thenComparing(Finding::kind)
            .thenComparing(Finding::detail);
    List<String> wrong = new ArrayList<>();
    for (Map.Entry<String, Seeder> fault : seededFaults().entrySet()) {
      Tier tier = new Tier();
      List<Sealed> seals = tier.baseline();
      assertThat(tier.verifier.verify("").clean())
          .as("the tier is clean before: " + fault.getKey())
          .isTrue();

      List<Finding> expected = fault.getValue().seed(tier, seals).stream().sorted(order).toList();

      // every fault is judged: the failure names all that were not found with their own class
      List<Finding> actual = tier.verifier.verify("").findings();
      if (!actual.equals(expected)) {
        wrong.add(fault.getKey() + ": expected " + expected + " but found " + actual);
      }
      for (Finding f : actual) {
        String detail = f.detail();
        if (detail.contains("BK-")
            || detail.contains(".xml")
            || detail.contains(tier.bucket())
            || detail.contains("127.0.0.1")) {
          wrong.add(fault.getKey() + ": the detail carries more than hashes and fixed texts");
        }
      }
    }
    assertThat(wrong).as("faults not found with their own class and nothing else").isEmpty();
  }

  @Test
  void aHiddenObjectIsReportedAsStoreIntegrityHashMismatchAndTheRunContinues() {
    Tier tier = new Tier();
    List<Sealed> seals = tier.baseline();
    // the first key of the tier, so that the walk has all the others still to do
    EvidenceKey hidden = seals.get(0).intentKey();
    tier.hide(hidden);
    tier.putPlain("landing/after", "after");

    // the key is still listed, and a plain read of it is an alarm, never an absence
    assertThat(tier.store.list("journal/", Optional.empty(), 10)).contains(hidden);
    assertThatThrownBy(() -> tier.store.get(hidden))
        .isInstanceOf(EvidenceCorruptionException.class);

    Report report = tier.verifier.verify("");

    assertThat(report.findings())
        .containsExactly(
            finding(Kind.HASH_MISMATCH, hidden, EvidenceVerifier.STORE_INTEGRITY),
            finding(
                Kind.ORPHAN,
                new EvidenceKey("landing/after"),
                "sha256=" + Sha256.ofUtf8("after").hex()));
    assertThat(report.findings()).noneMatch(f -> f.kind() == Kind.MISSING);
    assertThat(report.objectsChecked()).as("the whole tier was walked").isEqualTo(5);
    assertThat(report.manifestRowsChecked()).isEqualTo(4);
  }

  @Test
  void aDamagedObjectIsStoreIntegrityAndNotMissing() {
    Tier tier = new Tier();
    tier.baseline();
    // an object put through the raw client with a wrong regpack-sha256 value, and a manifest row
    tier.putDamaged("landing/damaged", "damaged");
    tier.putPlain("landing/later", "later");
    EvidenceKey damaged = new EvidenceKey("landing/damaged");

    Report report = tier.verifier.verify("");

    assertThat(report.findings())
        .containsExactly(
            finding(Kind.HASH_MISMATCH, damaged, EvidenceVerifier.STORE_INTEGRITY),
            finding(
                Kind.ORPHAN,
                new EvidenceKey("landing/later"),
                "sha256=" + Sha256.ofUtf8("later").hex()));
    assertThat(report.findings()).noneMatch(f -> f.kind() == Kind.MISSING);
    assertThat(report.objectsChecked()).isEqualTo(6);
    assertThat(report.manifestRowsChecked()).isEqualTo(5);
  }

  @Test
  void aLockShortenedBehindTheBackIsStoreIntegrity() {
    Tier tier = new Tier();
    List<Sealed> seals = tier.baseline();
    EvidenceKey key = seals.get(1).sealedKey();
    tier.putPlain("landing/after", "after");

    // a governance shorten with the bypass header, sent by the raw client of this test only
    tier.shortenLock(key, Instant.parse("2041-01-01T00:00:00Z"));

    assertThatThrownBy(() -> tier.store.get(key)).isInstanceOf(EvidenceCorruptionException.class);
    Report report = tier.verifier.verify("");
    assertThat(report.findings())
        .containsExactly(
            finding(
                Kind.ORPHAN,
                new EvidenceKey("landing/after"),
                "sha256=" + Sha256.ofUtf8("after").hex()),
            finding(Kind.HASH_MISMATCH, key, EvidenceVerifier.STORE_INTEGRITY));
    assertThat(report.objectsChecked()).isEqualTo(5);
  }

  // -------------------------------------------------------------------------------------------
  // outages

  @Test
  void anOutageAbortsVerifyInsteadOfReportingMissing() {
    Tier tier = new Tier();
    tier.baseline();

    // the listing works and every read of an object is refused at the transport: the objects are
    // not missing, the tier is out
    S3Transport delegate = new JdkTransport(Duration.ofSeconds(60));
    S3Transport readsDown =
        request -> {
          String query = request.uri().getRawQuery();
          if (request.method().equals("GET") && query != null && query.contains("versionId=")) {
            throw new IOException("scripted");
          }
          return delegate.send(request);
        };
    WormEvidenceStore readOutage =
        WormEvidenceStore.open(tier.config, tier.clock, signingClock(), readsDown);
    Throwable duringTheWalk =
        catchThrowable(() -> new EvidenceVerifier(readOutage, tier.ledger).verify(""));
    assertThat(duringTheWalk).isInstanceOf(EvidenceUnavailableException.class);

    // the service cannot be reached at all: not even the listing
    tier.transport.setDown(true);
    Throwable duringTheListing = catchThrowable(() -> tier.verifier.verify(""));
    assertThat(duringTheListing).isInstanceOf(EvidenceUnavailableException.class);

    // and the tier is as clean as before once the outage is over
    tier.transport.setDown(false);
    assertThat(tier.verifier.verify("").clean()).isTrue();
  }
}
