package dev.regpack.evidence.verify;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.regpack.evidence.seal.DurableSeal;
import dev.regpack.evidence.seal.EpochMarker;
import dev.regpack.evidence.seal.SealedKeys;
import dev.regpack.evidence.seal.SendGate;
import dev.regpack.evidence.sealed.SealedEvidenceStore;
import dev.regpack.evidence.sealed.SealedTamper;
import dev.regpack.evidence.verify.EvidenceVerifier.Finding;
import dev.regpack.evidence.verify.EvidenceVerifier.Kind;
import dev.regpack.evidence.verify.EvidenceVerifier.Report;
import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.model.Sha256;
import dev.regpack.ports.connector.Transport.OutboundFile;
import dev.regpack.ports.connector.Transport.SendResult;
import dev.regpack.ports.connector.Transport.SendStatus;
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
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
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.ManifestLedger.ObjectClass;
import dev.regpack.ports.ledger.ManifestLedger.ObjectEntry;
import dev.regpack.ports.ledger.ManifestLedger.Provenance;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.CrashPoints;
import dev.regpack.testkit.contract.LedgerFixtures;
import dev.regpack.testkit.contract.SealScenarioContract;
import dev.regpack.testkit.fault.FaultInjector;
import dev.regpack.testkit.fault.FaultPlan;
import dev.regpack.testkit.fault.SimulatedCrash;
import dev.regpack.testkit.sim.DestinationSimulator;
import dev.regpack.testkit.sim.Scenarios;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T15: {@code verify} over the evidence tier and the ledger's manifest, and the storage half of
 * I10. The tier is a real P-SEALED store over two directories of a {@code @TempDir} and an
 * in-memory ledger; the deliveries are sealed by {@link DurableSeal}; the faults are seeded on disk
 * with {@link SealedTamper} or by hand, and every one must be found with its own class and nothing
 * else.
 */
class EvidenceVerifierTest {
  private static final Instant START = Instant.parse("2026-10-20T08:00:00Z");
  private static final Duration TTL = Duration.ofSeconds(60);
  private static final RetentionPolicy RETENTION = RetentionPolicy.fixedDays(1826);
  private static final String ABSENT = "the object is absent from the evidence tier";
  private static final String IN_DOUBT = "in-doubt input (11.5 B.5)";

  @TempDir Path root;

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

  /** An in-memory ledger, a P-SEALED store below {@code base}, a sealer and a verifier. */
  private static final class Tier {
    final ManualClock clock = new ManualClock(START);
    final InMemoryLedgerStore ledger = new InMemoryLedgerStore(clock);
    final Path volumeA;
    final Path volumeB;
    final SealedEvidenceStore store;
    final JournalWriter journal;
    final DurableSeal sealing;
    final EvidenceVerifier verifier;

    Tier(Path base) throws IOException {
      Path real = Files.createDirectories(base).toRealPath();
      volumeA = real.resolve("vol-a");
      volumeB = real.resolve("vol-b");
      store =
          SealedEvidenceStore.open(
              new SealedEvidenceStore.Config(volumeA, volumeB, "vol-a", "vol-b", clock));
      journal = new JournalWriter(store, RETENTION);
      sealing =
          new DurableSeal(
              ledger, store, clock, RETENTION, new SendGate(SendGate.Requirement.none()));
      verifier = new EvidenceVerifier(store, ledger);
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

    EvidenceKey intentKeyOf(Started started) {
      return new JournalKey.ClassA(
              JournalScope.of(started.endpoint()),
              started.token().restoreEpoch(),
              JournalType.DELIVERY_INTENT,
              Long.toString(started.attempt().value()),
              1)
          .evidenceKey();
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

    /**
     * Writes an intent object that agrees with its attempt row and the delivery, under the key that
     * {@code keyOf} gives (the key may name another scope or epoch than the attempt's), and starts
     * the attempt with a manifest row for it and for its sealed file.
     */
    EvidenceKey handWrittenIntentUnder(String name, Function<Started, JournalKey.ClassA> keyOf) {
      Started started = start(name);
      JournalObject object =
          new JournalObject(
              keyOf.apply(started),
              JournalType.DELIVERY_INTENT,
              1,
              clock.now(),
              intentPayload(started));
      journal.write(object);
      begin(started, object.key().evidenceKey(), object.toBytes());
      return object.key().evidenceKey();
    }

    /**
     * An intent object that agrees with everything, whose sealed file is not in the tier and has no
     * manifest row (the database is older than the seal, and the file is lost). Returns the key of
     * the sealed file that the intent names.
     */
    EvidenceKey intentWhoseSealedFileIsNowhere(String name) {
      Started started = start(name);
      JournalObject object =
          new JournalObject(
              intentKeyUnder(started),
              JournalType.DELIVERY_INTENT,
              1,
              clock.now(),
              intentPayload(started));
      journal.write(object);
      begin(started, object.key().evidenceKey(), object.toBytes(), false);
      return SealedKeys.of(started.artifact());
    }

    JournalKey.ClassA intentKeyUnder(Started started) {
      return new JournalKey.ClassA(
          JournalScope.of(started.endpoint()),
          started.token().restoreEpoch(),
          JournalType.DELIVERY_INTENT,
          Long.toString(started.attempt().value()),
          1);
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

    /** Puts {@code bytes} under the intent key of a new attempt, whatever they are. */
    EvidenceKey rawIntent(String name, byte[] bytes) {
      return rawIntent(name, started -> bytes);
    }

    /** The same, with bytes that are made from the attempt (for example a foreign envelope). */
    EvidenceKey rawIntent(String name, Function<Started, byte[]> bytesOf) {
      Started started = start(name);
      byte[] bytes = bytesOf.apply(started);
      EvidenceKey key = intentKeyOf(started);
      store.put(
          new EvidenceStore.PutRequest(
              key,
              bytes,
              JournalWriter.MEDIA_TYPE,
              RETENTION.retentionFor(JournalWriter.RETENTION_CLASS, clock.now())));
      begin(started, key, bytes);
      return key;
    }

    private void begin(Started started, EvidenceKey key, byte[] bytes) {
      begin(started, key, bytes, true);
    }

    /** Starts the attempt; with a sealed file, it is in the tier and the same unit names it. */
    private void begin(Started started, EvidenceKey key, byte[] bytes, boolean withSealedFile) {
      List<ObjectEntry> objects = new ArrayList<>();
      if (withSealedFile) {
        addSealedFileIfNew(started.artifactBytes());
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

    private void addSealedFileIfNew(byte[] bytes) {
      if (store.get(SealedKeys.of(Sha256.of(bytes))).isEmpty()) {
        addSealedFile(bytes);
      }
    }

    /** A class (b) object with a manifest row. */
    void classB(JournalScope scope, long epoch, long seq) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("attempt_id", 7L);
      payload.put("status", "ACCEPTED");
      payload.put("detail", "http 202");
      payload.put("observed_at", START);
      JournalObject object =
          new JournalObject(
              new JournalKey.ClassB(scope, epoch, seq),
              JournalType.TRANSPORT_RESULT,
              1,
              START,
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

    void deleteOnBothVolumes(EvidenceKey key) throws IOException {
      SealedTamper.deleteObject(volumeA, key);
      SealedTamper.deleteObject(volumeB, key);
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

  private static void flipLastByte(Path file) throws IOException {
    byte[] bytes = Files.readAllBytes(file);
    bytes[bytes.length - 1] ^= 0x01;
    Files.write(file, bytes);
  }

  /** An evidence store that delegates and can be told to fail, to lie or to count. */
  private static final class ScriptedStore implements EvidenceStore {
    private final EvidenceStore delegate;
    final Map<String, Supplier<RuntimeException>> getFailures = new HashMap<>();
    final Set<String> wrongHash = new HashSet<>();
    final Set<String> vanished = new HashSet<>();
    final List<Integer> listLimits = new ArrayList<>();
    Supplier<RuntimeException> listFailure;
    boolean descending;

    ScriptedStore(EvidenceStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public Grade grade() {
      return delegate.grade();
    }

    @Override
    public Durability durability() {
      return delegate.durability();
    }

    @Override
    public PutOutcome put(PutRequest request) {
      return delegate.put(request);
    }

    @Override
    public Optional<StoredObject> get(EvidenceKey key) {
      Supplier<RuntimeException> failure = getFailures.get(key.path());
      if (failure != null) {
        throw failure.get();
      }
      if (vanished.contains(key.path())) {
        return Optional.empty();
      }
      Optional<StoredObject> stored = delegate.get(key);
      if (stored.isPresent() && wrongHash.contains(key.path())) {
        StoredObject s = stored.get();
        return Optional.of(
            new StoredObject(
                s.key(),
                s.bytes(),
                s.mediaType(),
                Sha256.ofUtf8("another hash"),
                s.writtenAt(),
                s.retention(),
                s.legalHold()));
      }
      return stored;
    }

    @Override
    public List<EvidenceKey> list(String prefix, Optional<EvidenceKey> after, int limit) {
      listLimits.add(limit);
      if (listFailure != null) {
        throw listFailure.get();
      }
      List<EvidenceKey> page = new ArrayList<>(delegate.list(prefix, after, limit));
      if (descending) {
        Collections.reverse(page);
      }
      return page;
    }

    @Override
    public void extendRetention(EvidenceKey key, Instant newRetainUntil) {
      delegate.extendRetention(key, newRetainUntil);
    }

    @Override
    public void setLegalHold(EvidenceKey key, boolean hold) {
      delegate.setLegalHold(key, hold);
    }
  }

  /**
   * A ledger that delegates every call to {@code ledger} after {@code beforeCall} has seen the name
   * and the arguments of the call (the hook may throw).
   */
  private static LedgerStore ledgerWithHook(
      LedgerStore ledger, BiConsumer<String, Object[]> beforeCall) {
    return (LedgerStore)
        Proxy.newProxyInstance(
            LedgerStore.class.getClassLoader(),
            new Class<?>[] {LedgerStore.class},
            (proxy, method, args) -> {
              beforeCall.accept(method.getName(), args);
              try {
                return method.invoke(ledger, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  private static LedgerStore ledgerThatFailsOn(LedgerStore ledger, String methodName) {
    return ledgerWithHook(
        ledger,
        (name, args) -> {
          if (name.equals(methodName)) {
            throw new LedgerException("scripted");
          }
        });
  }

  // -------------------------------------------------------------------------------------------
  // a clean tier

  @Test
  void aCleanTierHasNoFinding() throws IOException {
    Tier tier = new Tier(root.resolve("clean"));
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
    Report nothing = tier.verifier.verify("landing/");
    assertThat(nothing).isEqualTo(new Report(List.of(), 0, 0));
  }

  @Test
  void theEpochMarkersAreNeitherOrphansNorMissing() throws IOException {
    Tier tier = new Tier(root.resolve("markers"));
    EpochMarker marker = new EpochMarker(tier.store, RETENTION, tier.clock);
    marker.declare(1);
    marker.declare(2);

    assertThat(tier.verifier.verify("").findings()).isEmpty();

    // a row for a marker whose object is not there is not missing either
    byte[] bytes = "{\"epoch\":9}".getBytes(UTF_8);
    tier.ledger.recordObjects(
        List.of(
            new ObjectEntry(EpochMarker.key(9), ObjectClass.OTHER, Sha256.of(bytes), bytes.length)),
        Provenance.WRITTEN,
        Optional.empty());
    assertThat(tier.verifier.verify("").findings()).isEmpty();
  }

  // -------------------------------------------------------------------------------------------
  // seeded faults

  /**
   * Seeds one fault into a tier that holds two sealed deliveries and returns what must be found.
   */
  private interface Seeder {
    List<Finding> seed(Tier tier, List<Sealed> seals) throws Exception;
  }

  private static Map<String, Seeder> seededFaults() {
    Map<String, Seeder> faults = new LinkedHashMap<>();
    faults.put(
        "one byte flipped in both copies of a sealed object",
        (tier, seals) -> {
          EvidenceKey key = seals.get(0).sealedKey();
          SealedTamper.flipByte(tier.volumeA, key);
          SealedTamper.flipByte(tier.volumeB, key);
          return List.of(finding(Kind.HASH_MISMATCH, key, "store-integrity"));
        });
    faults.put(
        "the retention record damaged on both volumes",
        (tier, seals) -> {
          EvidenceKey key = seals.get(1).sealedKey();
          // a meta file exists once the retention has been extended or a hold has been set
          tier.store.extendRetention(key, tier.clock.now().plus(Duration.ofDays(4000)));
          flipLastByte(SealedTamper.metaPath(tier.volumeA, key));
          flipLastByte(SealedTamper.metaPath(tier.volumeB, key));
          return List.of(finding(Kind.HASH_MISMATCH, key, "store-integrity"));
        });
    faults.put(
        "other bytes under a valid header on both volumes",
        (tier, seals) -> {
          EvidenceKey key = seals.get(0).sealedKey();
          byte[] other =
              SealedTamper.fileWithOtherPayload(
                  tier.volumeA, key, "<report>forged</report>".getBytes(UTF_8));
          SealedTamper.replaceObject(tier.volumeA, key, other);
          SealedTamper.replaceObject(tier.volumeB, key, other);
          return List.of(finding(Kind.HASH_MISMATCH, key, "manifest-hash"));
        });
    faults.put(
        "both copies of a sealed object deleted",
        (tier, seals) -> {
          EvidenceKey key = seals.get(1).sealedKey();
          tier.deleteOnBothVolumes(key);
          return List.of(finding(Kind.MISSING, key, ABSENT));
        });
    faults.put(
        "both copies of an intent object deleted",
        (tier, seals) -> {
          EvidenceKey key = seals.get(0).intentKey();
          tier.deleteOnBothVolumes(key);
          return List.of(finding(Kind.MISSING, key, ABSENT));
        });
    faults.put(
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
        "a manifest row with the right hash and another length",
        (tier, seals) -> {
          tier.putPlain("landing/sized", "sized");
          byte[] bytes = "sized".getBytes(UTF_8);
          EvidenceKey key = new EvidenceKey("landing/sized");
          tier.ledger.recordObjects(
              List.of(new ObjectEntry(key, ObjectClass.OTHER, Sha256.of(bytes), bytes.length + 1L)),
              Provenance.WRITTEN,
              Optional.empty());
          return List.of(finding(Kind.HASH_MISMATCH, key, "manifest-hash"));
        });
    faults.put(
        "object 3 of 5 of a class b sequence deleted",
        (tier, seals) -> {
          JournalScope scope = JournalScope.of(LedgerFixtures.endpoint("SEQ"));
          for (long seq = 1; seq <= 5; seq++) {
            tier.classB(scope, 0, seq);
          }
          assertThat(tier.verifier.verify("").clean()).as("before the fault").isTrue();
          EvidenceKey third = classBKey(scope, 0, 3);
          tier.deleteOnBothVolumes(third);
          return List.of(
              finding(Kind.MISSING, third, ABSENT),
              finding(Kind.SEQUENCE_GAP, third, "expected seq 3, found seq 4"));
        });
    faults.put(
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
    faults.put(
        "an intent object with another file name than its attempt row",
        (tier, seals) -> {
          EvidenceKey key =
              tier.handWrittenIntent(
                  "HAND2",
                  payload -> {
                    payload.put("file_name", "other.xml");
                    return payload;
                  });
          return List.of(
              finding(Kind.INTENT_MISMATCH, key, "file_name differs from the attempt row"));
        });
    faults.put(
        "an intent object that names another delivery than its attempt row",
        (tier, seals) -> {
          EvidenceKey key =
              tier.handWrittenIntent(
                  "HAND3",
                  payload -> {
                    payload.put("delivery_id", 9_999L);
                    return payload;
                  });
          return List.of(
              finding(Kind.INTENT_MISMATCH, key, "delivery_id differs from the attempt row"));
        });
    faults.put(
        "an intent object whose key subject is not its payload attempt_id",
        (tier, seals) -> {
          EvidenceKey key =
              tier.handWrittenIntent(
                  "HAND4",
                  payload -> {
                    payload.put("attempt_id", 4_242L);
                    return payload;
                  });
          return List.of(
              finding(Kind.INTENT_MISMATCH, key, "attempt_id differs from the key subject"));
        });
    faults.put(
        "an intent object with another business key than the delivery row of its attempt",
        (tier, seals) -> {
          EvidenceKey key =
              tier.handWrittenIntent(
                  "HAND7",
                  payload -> {
                    payload.put("business_key", "BK-SOMEONE-ELSE");
                    return payload;
                  });
          return List.of(
              finding(Kind.INTENT_MISMATCH, key, "business_key differs from the delivery row"));
        });
    faults.put(
        "an intent object with another version than the delivery row of its attempt",
        (tier, seals) -> {
          EvidenceKey key =
              tier.handWrittenIntent(
                  "HAND8",
                  payload -> {
                    payload.put("version_seq", 9L);
                    return payload;
                  });
          return List.of(
              finding(Kind.INTENT_MISMATCH, key, "version_seq differs from the delivery row"));
        });
    faults.put(
        "an intent object stored under the scope of another endpoint than its attempt",
        (tier, seals) -> {
          EvidenceKey key =
              tier.handWrittenIntentUnder(
                  "HAND9",
                  started ->
                      new JournalKey.ClassA(
                          JournalScope.of(LedgerFixtures.endpoint("ELSEWHERE")),
                          started.token().restoreEpoch(),
                          JournalType.DELIVERY_INTENT,
                          Long.toString(started.attempt().value()),
                          1));
          return List.of(
              finding(
                  Kind.INTENT_MISMATCH, key, "the key scope is not the endpoint of the attempt"));
        });
    faults.put(
        "an intent object stored under another restore epoch than the token of its attempt",
        (tier, seals) -> {
          EvidenceKey key =
              tier.handWrittenIntentUnder(
                  "HAND10",
                  started ->
                      new JournalKey.ClassA(
                          JournalScope.of(started.endpoint()),
                          started.token().restoreEpoch() + 1,
                          JournalType.DELIVERY_INTENT,
                          Long.toString(started.attempt().value()),
                          1));
          return List.of(
              finding(
                  Kind.INTENT_MISMATCH,
                  key,
                  "the key restore epoch is not the token epoch of the attempt"));
        });
    faults.put(
        "a sealed file that an intent names, absent from the tier and from the manifest",
        (tier, seals) -> {
          EvidenceKey sealedKey = tier.intentWhoseSealedFileIsNowhere("HAND11");
          return List.of(finding(Kind.MISSING, sealedKey, "named by an intent, absent"));
        });
    faults.put(
        "bytes under an intent key that are no journal object",
        (tier, seals) -> {
          EvidenceKey key = tier.rawIntent("HAND5", "not a journal object".getBytes(UTF_8));
          return List.of(finding(Kind.INTENT_MISMATCH, key, "the object is not a journal object"));
        });
    faults.put(
        "an intent object whose envelope names another key than the one it is stored under",
        (tier, seals) -> {
          EvidenceKey key =
              tier.rawIntent(
                  "HAND6",
                  started -> {
                    // everything agrees with the attempt row, except the subject of the envelope
                    Map<String, Object> payload = tier.intentPayload(started);
                    return new JournalObject(
                            new JournalKey.ClassA(
                                JournalScope.of(started.endpoint()),
                                started.token().restoreEpoch(),
                                JournalType.DELIVERY_INTENT,
                                "9999",
                                1),
                            JournalType.DELIVERY_INTENT,
                            1,
                            START,
                            payload)
                        .toBytes();
                  });
          return List.of(finding(Kind.INTENT_MISMATCH, key, "the envelope names another key"));
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
    int index = 0;
    for (Map.Entry<String, Seeder> fault : seededFaults().entrySet()) {
      Tier tier = new Tier(root.resolve("fault-" + index++));
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
            || detail.contains("vol-a")
            || detail.contains("vol-b")) {
          wrong.add(fault.getKey() + ": the detail carries more than hashes and fixed texts");
        }
      }
    }
    assertThat(wrong).as("faults not found with their own class and nothing else").isEmpty();
  }

  @Test
  void theKindsAreTheFiveOfTheSpecAndNoneForTheUnexplained() {
    assertThat(Arrays.stream(Kind.values()).map(Enum::name).collect(Collectors.toSet()))
        .containsExactlyInAnyOrder(
            "HASH_MISMATCH", "MISSING", "ORPHAN", "SEQUENCE_GAP", "INTENT_MISMATCH");
  }

  @Test
  void aCorruptCopyThatTheOtherVolumeMasksIsForScrubToFindNotForVerify() throws IOException {
    Tier tier = new Tier(root.resolve("masked"));
    List<Sealed> seals = tier.baseline();
    EvidenceKey key = seals.get(0).sealedKey();

    SealedTamper.flipByte(tier.volumeA, key);

    // verify sees the store through the port, and the port answers from the good copy
    assertThat(tier.verifier.verify("").findings()).isEmpty();
    assertThat(tier.store.scrub()).containsExactly("vol-a " + key.path() + " CORRUPT");
  }

  @Test
  void aPlainIntegrityAlarmOfTheStoreIsStoreIntegrityToo() throws IOException {
    Tier tier = new Tier(root.resolve("plain"));
    List<Sealed> seals = tier.baseline();
    EvidenceKey key = seals.get(0).sealedKey();
    ScriptedStore scripted = new ScriptedStore(tier.store);
    scripted.getFailures.put(key.path(), () -> new EvidenceIntegrityException("scripted alarm"));

    Report report = new EvidenceVerifier(scripted, tier.ledger).verify("");

    assertThat(report.findings())
        .containsExactly(finding(Kind.HASH_MISMATCH, key, "store-integrity"));
  }

  @Test
  void aStoreThatAnswersWithAnotherHashThanItsBytesIsStoreIntegrity() throws IOException {
    Tier tier = new Tier(root.resolve("lying"));
    List<Sealed> seals = tier.baseline();
    EvidenceKey key = seals.get(1).sealedKey();
    ScriptedStore scripted = new ScriptedStore(tier.store);
    scripted.wrongHash.add(key.path());

    Report report = new EvidenceVerifier(scripted, tier.ledger).verify("");

    assertThat(report.findings())
        .containsExactly(finding(Kind.HASH_MISMATCH, key, "store-integrity"));
  }

  @Test
  void anObjectThatIsListedAndGoneWhenItIsReadIsMissingOnlyWhereTheManifestKnowsIt()
      throws IOException {
    Tier tier = new Tier(root.resolve("vanished"));
    List<Sealed> seals = tier.baseline();
    tier.putPlain("landing/unrecorded", "unrecorded");
    ScriptedStore scripted = new ScriptedStore(tier.store);
    scripted.vanished.add(seals.get(0).sealedKey().path());
    scripted.vanished.add("landing/unrecorded");

    Report report = new EvidenceVerifier(scripted, tier.ledger).verify("");

    assertThat(report.findings())
        .as("a vanished object that the ledger never recorded is no fact")
        .containsExactly(finding(Kind.MISSING, seals.get(0).sealedKey(), ABSENT));
  }

  @Test
  void anOrphanThatCannotBeReadIsAlsoStoreIntegrity() throws IOException {
    Tier tier = new Tier(root.resolve("orphan-damaged"));
    tier.baseline();
    tier.putPlain("landing/damaged", "damaged");
    EvidenceKey key = new EvidenceKey("landing/damaged");
    SealedTamper.flipByte(tier.volumeA, key);
    SealedTamper.flipByte(tier.volumeB, key);

    // sorted by key, kind and detail: HASH_MISMATCH sorts before ORPHAN, though the walk finds the
    // orphan first
    assertThat(tier.verifier.verify("").findings())
        .containsExactly(
            finding(Kind.HASH_MISMATCH, key, "store-integrity"),
            finding(Kind.ORPHAN, key, "sha256 unknown"));
  }

  @Test
  void theFindingsAreSortedByKeyThenKindThenDetailAndTwoRunsAgree() throws Exception {
    Tier tier = new Tier(root.resolve("sorted"));
    List<Sealed> seals = tier.baseline();
    // two findings of one kind on one key; the walk finds file_name before delivery_id
    EvidenceKey twice =
        tier.handWrittenIntent(
            "SORT",
            payload -> {
              payload.put("file_name", "other.xml");
              payload.put("delivery_id", 9_999L);
              return payload;
            });
    EvidenceKey orphan = new EvidenceKey("landing/extra");
    tier.putPlain(orphan.path(), "extra");
    EvidenceKey gone = seals.get(1).sealedKey();
    tier.deleteOnBothVolumes(gone);

    Report first = tier.verifier.verify("");

    // key order: journal/ < landing/ < sealed/; by kind alone the missing object would come first
    assertThat(first.findings())
        .containsExactly(
            finding(Kind.INTENT_MISMATCH, twice, "delivery_id differs from the attempt row"),
            finding(Kind.INTENT_MISMATCH, twice, "file_name differs from the attempt row"),
            finding(Kind.ORPHAN, orphan, "sha256=" + Sha256.ofUtf8("extra").hex()),
            finding(Kind.MISSING, gone, ABSENT));
    assertThat(tier.verifier.verify("")).as("a second run of the same tier").isEqualTo(first);
  }

  @Test
  void aStrayKeyAndAnIntentKeyWithANonNumericSubjectAreOrphansAndNeverAbortTheRun()
      throws IOException {
    Tier tier = new Tier(root.resolve("hostile"));
    tier.baseline();
    JournalScope scope = JournalScope.of(LedgerFixtures.endpoint("HOSTILE"));
    String classB = classBKey(scope, 0, 1).path();
    EvidenceKey stray = new EvidenceKey("journal/stray");
    EvidenceKey trailing = new EvidenceKey(classB + "x");
    EvidenceKey tooLong = new EvidenceKey(classB + "/extra");
    EvidenceKey namedAbc =
        new JournalKey.ClassA(scope, 0, JournalType.DELIVERY_INTENT, "abc", 1).evidenceKey();
    // none of the four has a manifest row
    tier.putPlain(stray.path(), "stray");
    tier.putPlain(trailing.path(), "trailing");
    tier.putPlain(tooLong.path(), "too long");
    tier.putPlain(namedAbc.path(), "not json");

    Report report = tier.verifier.verify("");

    assertThat(report.findings())
        .containsExactlyInAnyOrder(
            finding(Kind.ORPHAN, stray, "sha256=" + Sha256.ofUtf8("stray").hex()),
            finding(Kind.ORPHAN, trailing, "sha256=" + Sha256.ofUtf8("trailing").hex()),
            finding(Kind.ORPHAN, tooLong, "sha256=" + Sha256.ofUtf8("too long").hex()),
            finding(Kind.ORPHAN, namedAbc, IN_DOUBT),
            finding(Kind.INTENT_MISMATCH, namedAbc, "the object is not a journal object"));
    assertThat(report.objectsChecked()).isEqualTo(8);
    assertThat(report.manifestRowsChecked()).isEqualTo(4);
  }

  @Test
  void anUnrecordedEpochMarkerIsNotReadByVerify() throws IOException {
    Tier tier = new Tier(root.resolve("marker-unread"));
    new EpochMarker(tier.store, RETENTION, tier.clock).declare(1);
    ScriptedStore scripted = new ScriptedStore(tier.store);
    scripted.getFailures.put(
        EpochMarker.key(1).path(), () -> new EvidenceUnavailableException("scripted"));

    // the marker coverage of verify is P4: the listing counts the marker and nothing reads it
    Report report = new EvidenceVerifier(scripted, tier.ledger).verify("");

    assertThat(report.findings()).isEmpty();
    assertThat(report.objectsChecked()).isEqualTo(1);
  }

  // -------------------------------------------------------------------------------------------
  // outages

  @Test
  void anOutageAbortsVerifyInsteadOfReportingMissing() throws IOException {
    Tier tier = new Tier(root.resolve("outage"));
    List<Sealed> seals = tier.baseline();
    EvidenceKey key = seals.get(1).sealedKey();

    // the listing works and one read cannot be served: the object is not missing, the tier is out
    ScriptedStore readOutage = new ScriptedStore(tier.store);
    readOutage.getFailures.put(key.path(), () -> new EvidenceUnavailableException("scripted"));
    Throwable duringTheWalk =
        catchThrowable(() -> new EvidenceVerifier(readOutage, tier.ledger).verify(""));
    assertThat(duringTheWalk).isInstanceOf(EvidenceUnavailableException.class);

    // the listing itself cannot be served
    ScriptedStore listOutage = new ScriptedStore(tier.store);
    listOutage.listFailure = () -> new EvidenceUnavailableException("scripted");
    Throwable duringTheListing =
        catchThrowable(() -> new EvidenceVerifier(listOutage, tier.ledger).verify(""));
    assertThat(duringTheListing).isInstanceOf(EvidenceUnavailableException.class);

    // a store that is down from the start (the in-memory double): no report at all
    InMemoryEvidenceStore down = new InMemoryEvidenceStore(tier.clock);
    down.simulateOutage(true);
    assertThatThrownBy(() -> new EvidenceVerifier(down, tier.ledger).verify(""))
        .isInstanceOf(EvidenceUnavailableException.class);

    // and the tier is as clean as before once the outage is over
    assertThat(tier.verifier.verify("").clean()).isTrue();
  }

  @Test
  void anUnreadableLedgerAbortsVerifyAsAnOutageDoes() throws IOException {
    Tier tier = new Tier(root.resolve("ledger-down"));
    tier.baseline();

    // the manifest cannot be listed
    LedgerStore manifestDown = ledgerThatFailsOn(tier.ledger, "manifestRows");
    assertThatThrownBy(() -> new EvidenceVerifier(tier.store, manifestDown).verify(""))
        .isInstanceOf(LedgerException.class)
        .hasMessageContaining("scripted");

    // an attempt row cannot be read (the intent objects of the baseline need it)
    LedgerStore attemptDown = ledgerThatFailsOn(tier.ledger, "attempt");
    assertThatThrownBy(() -> new EvidenceVerifier(tier.store, attemptDown).verify(""))
        .isInstanceOf(LedgerException.class)
        .hasMessageContaining("scripted");

    assertThat(tier.verifier.verify("").clean()).isTrue();
  }

  // -------------------------------------------------------------------------------------------
  // the class (b) sequence

  @Test
  void aClassBSequenceMustStartAtOne() throws IOException {
    Tier tier = new Tier(root.resolve("seq-start"));
    JournalScope scope = JournalScope.of(LedgerFixtures.endpoint("SEQ"));
    tier.classB(scope, 0, 2);
    tier.classB(scope, 0, 3);

    assertThat(tier.verifier.verify("").findings())
        .containsExactly(
            finding(Kind.SEQUENCE_GAP, classBKey(scope, 0, 1), "expected seq 1, found seq 2"));
  }

  @Test
  void onlyTheFirstHoleOfEachScopeAndEpochIsReported() throws IOException {
    Tier tier = new Tier(root.resolve("seq-groups"));
    JournalScope scope = JournalScope.of(LedgerFixtures.endpoint("SEQ"));
    JournalScope other = JournalScope.of(LedgerFixtures.endpoint("SEQ2"));
    for (long seq : new long[] {1, 2, 4, 7}) {
      tier.classB(scope, 0, seq); // holes at 3, 5 and 6: only 3 is reported
    }
    for (long seq : new long[] {1, 2, 3}) {
      tier.classB(scope, 1, seq); // the next epoch of the same scope is whole
    }
    for (long seq : new long[] {1, 3}) {
      tier.classB(other, 0, seq); // another scope has its own hole
    }

    assertThat(tier.verifier.verify("").findings())
        .containsExactlyInAnyOrder(
            finding(Kind.SEQUENCE_GAP, classBKey(scope, 0, 3), "expected seq 3, found seq 4"),
            finding(Kind.SEQUENCE_GAP, classBKey(other, 0, 2), "expected seq 2, found seq 3"));
  }

  @Test
  void aPrefixThatCutsASequenceDoesNotJudgeIt() throws IOException {
    Tier tier = new Tier(root.resolve("seq-prefix"));
    JournalScope scope = JournalScope.of(LedgerFixtures.endpoint("SEQ"));
    tier.classB(scope, 0, 10_000); // bucket 00000001 only: 1 to 9,999 are not there
    tier.classB(scope, 0, 10_001);

    assertThat(tier.verifier.verify("").findings())
        .containsExactly(
            finding(Kind.SEQUENCE_GAP, classBKey(scope, 0, 1), "expected seq 1, found seq 10000"));
    String bucket = classBKey(scope, 0, 10_000).path();
    String insideTheBucket = bucket.substring(0, bucket.lastIndexOf('/') + 1);
    assertThat(tier.verifier.verify(insideTheBucket).findings())
        .as("the prefix covers a part of the sequence only")
        .isEmpty();
    String wholeSequence = insideTheBucket.substring(0, insideTheBucket.indexOf("/b/") + 3);
    assertThat(tier.verifier.verify(wholeSequence).findings())
        .as("the prefix covers the sequence")
        .hasSize(1);
  }

  // -------------------------------------------------------------------------------------------
  // intents and attempt rows

  @Test
  void aMissingAttemptRowOrManifestRowIsNeverAnIntentMismatch() throws IOException {
    Tier tier = new Tier(root.resolve("never-mismatch"));
    List<Sealed> seals = tier.baseline();

    // the database is behind the evidence tier: a manifest row, no attempt row
    JournalScope scope = JournalScope.of(LedgerFixtures.endpoint("RESTORED"));
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("attempt_id", 500L);
    payload.put("delivery_id", 77L);
    payload.put("business_key", "BK-R");
    payload.put("trn", "TRN-R");
    payload.put("version_seq", 1L);
    payload.put("action", "NEWT");
    payload.put("logical_hash", Sha256.ofUtf8("l").hex());
    payload.put("artifact_sha256", Sha256.ofUtf8("a").hex());
    payload.put("file_name", "r.xml");
    payload.put("context_ids", List.of());
    JournalObject restored =
        new JournalObject(
            new JournalKey.ClassA(scope, 0, JournalType.DELIVERY_INTENT, "500", 1),
            JournalType.DELIVERY_INTENT,
            1,
            START,
            payload);
    tier.journal.write(restored);
    tier.addSealedFile("a".getBytes(UTF_8)); // the file that the restored intent names
    byte[] bytes = restored.toBytes();
    tier.ledger.recordObjects(
        List.of(
            new ObjectEntry(
                restored.key().evidenceKey(),
                ObjectClass.JOURNAL_A,
                Sha256.of(bytes),
                bytes.length)),
        Provenance.WRITTEN,
        Optional.empty());
    assertThat(tier.verifier.verify("").findings()).isEmpty();

    // the attempt row exists and the intent object is gone: missing, and nothing else
    tier.deleteOnBothVolumes(seals.get(0).intentKey());
    assertThat(tier.verifier.verify("").findings())
        .containsExactly(finding(Kind.MISSING, seals.get(0).intentKey(), ABSENT));
  }

  @Test
  void anIntentOfAnAttemptWithoutAManifestRowIsAnOrphanAndStillCompared() throws IOException {
    Tier tier = new Tier(root.resolve("intent-orphan"));
    Started started = tier.start("ORPH");
    Map<String, Object> payload = tier.intentPayload(started);
    payload.put("file_name", "other.xml");
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
            START,
            payload);
    tier.journal.write(object);
    tier.addSealedFile(started.artifactBytes());
    // the attempt row exists, its unit named no object: no manifest row for the intent object
    SendingResult result =
        tier.ledger.beginSending(
            started.delivery(),
            started.attempt(),
            started.token(),
            new AttemptIntent(started.artifact(), started.fileName()));
    assertThat(result.status()).isEqualTo(SendingStatus.APPLIED);
    EvidenceKey key = object.key().evidenceKey();

    assertThat(tier.verifier.verify("").findings())
        .containsExactlyInAnyOrder(
            finding(Kind.ORPHAN, key, "sha256=" + Sha256.of(object.toBytes()).hex()),
            finding(Kind.INTENT_MISMATCH, key, "file_name differs from the attempt row"));
  }

  // -------------------------------------------------------------------------------------------
  // a crash between the put and the commit

  @Test
  void aCrashBetweenPutAndCommitIsAnOrphanAndTheEnumHasNoUnexplained() throws IOException {
    Tier tier = new Tier(root.resolve("crash"));
    EndpointKey endpoint = LedgerFixtures.endpoint("CRASH");
    LeaseToken token = tier.lease(endpoint);
    DeliveryId delivery = SealScenarioContract.queued(tier.ledger, endpoint, "BK-CRASH");
    SealRequest request =
        SealScenarioContract.request(endpoint, token, delivery, "BK-CRASH", reportOf("BK-CRASH"));
    FaultInjector faults =
        new FaultInjector(FaultPlan.crashAt(CrashPoints.AFTER_INTENT_PUT, 2), tier.clock);
    EvidenceStore crashing = faults.wrap(EvidenceStore.class, tier.store);
    DurableSeal sealing =
        new DurableSeal(
            tier.ledger,
            crashing,
            tier.clock,
            RETENTION,
            new SendGate(SendGate.Requirement.none()));

    Throwable crash = catchThrowable(() -> sealing.seal(request));

    assertThat(crash).isInstanceOf(SimulatedCrash.class);
    Sealed seen = new Sealed(request, new AttemptId(1), token);
    assertThat(tier.verifier.verify("").findings())
        .containsExactlyInAnyOrder(
            finding(
                Kind.ORPHAN, seen.sealedKey(), "sha256=" + request.intent().artifactSha256().hex()),
            finding(Kind.ORPHAN, seen.intentKey(), IN_DOUBT));
    assertThat(Arrays.stream(Kind.values()).map(Enum::name)).doesNotContain("UNEXPLAINED");
  }

  @Test
  void aCrashAfterTheSealedPutLeavesOneOrphanOfTheSealedBytes() throws IOException {
    Tier tier = new Tier(root.resolve("crash-first"));
    EndpointKey endpoint = LedgerFixtures.endpoint("CRASH1");
    LeaseToken token = tier.lease(endpoint);
    DeliveryId delivery = SealScenarioContract.queued(tier.ledger, endpoint, "BK-CRASH1");
    SealRequest request =
        SealScenarioContract.request(endpoint, token, delivery, "BK-CRASH1", reportOf("BK-CRASH1"));
    FaultInjector faults =
        new FaultInjector(FaultPlan.crashAt(CrashPoints.AFTER_SEALED_PUT, 1), tier.clock);
    DurableSeal sealing =
        new DurableSeal(
            tier.ledger,
            faults.wrap(EvidenceStore.class, tier.store),
            tier.clock,
            RETENTION,
            new SendGate(SendGate.Requirement.none()));

    Throwable crash = catchThrowable(() -> sealing.seal(request));

    assertThat(crash).isInstanceOf(SimulatedCrash.class);
    assertThat(tier.verifier.verify("").findings())
        .containsExactly(
            finding(
                Kind.ORPHAN,
                SealedKeys.of(request.intent().artifactSha256()),
                "sha256=" + request.intent().artifactSha256().hex()));
  }

  @Test
  void aSealedFileThatAnIntentNamesAndTheTierHasLostIsMissingEvenWhenTheLedgerIsBehind()
      throws IOException {
    Tier tier = new Tier(root.resolve("lost-file"));
    List<Sealed> seals = tier.baseline();
    EvidenceKey lost = seals.get(1).sealedKey();
    tier.deleteOnBothVolumes(lost);
    // the database is restored from a backup older than the seal: no attempt row, no manifest row
    InMemoryLedgerStore behind = new InMemoryLedgerStore(tier.clock);

    Report report = new EvidenceVerifier(tier.store, behind).verify("");

    assertThat(report.findings())
        .as("the lost artifact is named, not only the in-doubt input of its intent")
        .contains(finding(Kind.MISSING, lost, "named by an intent, absent"))
        .contains(finding(Kind.ORPHAN, seals.get(1).intentKey(), IN_DOUBT));
    assertThat(report.findings().stream().filter(f -> f.kind() == Kind.MISSING)).hasSize(1);
    // with the original ledger the manifest row says it: one finding, not two
    assertThat(tier.verifier.verify("").findings())
        .containsExactly(finding(Kind.MISSING, lost, ABSENT));
  }

  @Test
  void aSealedFileNamedByTwoIntentsIsReportedOnceAndOnlyWhereTheTierLacksIt() throws IOException {
    Tier tier = new Tier(root.resolve("lost-shared"));
    EndpointKey endpoint = LedgerFixtures.endpoint("SHARE");
    LeaseToken token = tier.lease(endpoint);
    // two deliveries, one report: one sealed file, two intent objects that name it
    DeliveryId first = SealScenarioContract.queued(tier.ledger, endpoint, "BK-S1");
    DeliveryId second = SealScenarioContract.queued(tier.ledger, endpoint, "BK-S2");
    byte[] bytes = reportOf("one report");
    assertThat(
            tier.sealing
                .seal(SealScenarioContract.request(endpoint, token, first, "BK-S1", bytes))
                .status())
        .isEqualTo(SealStatus.SEALED);
    assertThat(
            tier.sealing
                .seal(SealScenarioContract.request(endpoint, token, second, "BK-S2", bytes))
                .status())
        .isEqualTo(SealStatus.SEALED);
    assertThat(tier.verifier.verify("").clean()).isTrue();
    EvidenceKey shared = SealedKeys.of(Sha256.of(bytes));
    tier.deleteOnBothVolumes(shared);
    InMemoryLedgerStore behind = new InMemoryLedgerStore(tier.clock);

    Report report = new EvidenceVerifier(tier.store, behind).verify("");

    assertThat(report.findings())
        .filteredOn(f -> f.kind() == Kind.MISSING)
        .containsExactly(finding(Kind.MISSING, shared, "named by an intent, absent"));
  }

  // -------------------------------------------------------------------------------------------
  // I10, storage half

  @Test
  void theIdentityOfEverySentDeliveryIsReconstructableFromTheEvidenceTierAlone()
      throws IOException {
    Tier tier = new Tier(root.resolve("i10"));
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
    assertThat(restore.findings().stream().filter(f -> f.detail().equals(IN_DOUBT)))
        .as("one in-doubt input per attempt")
        .hasSize(3);
  }

  // -------------------------------------------------------------------------------------------
  // pages, order and the records

  @Test
  void pagesOfFiveHundredAreWalkedAcrossTheirBoundaries() {
    ManualClock clock = new ManualClock(START);
    InMemoryLedgerStore ledger = new InMemoryLedgerStore(clock);
    InMemoryEvidenceStore evidence = new InMemoryEvidenceStore(clock);
    EvidenceStore.Retention retention =
        new EvidenceStore.Retention(
            new EvidenceStore.RetentionClass("landing"), START.plus(Duration.ofDays(30)));
    Set<Integer> orphans = Set.of(499, 500, 1_000);
    List<ObjectEntry> rows = new ArrayList<>();
    for (int i = 0; i < 1_203; i++) {
      String path = "landing/k" + String.valueOf(10_000 + i).substring(1);
      byte[] bytes = path.getBytes(UTF_8);
      evidence.put(
          new EvidenceStore.PutRequest(new EvidenceKey(path), bytes, "text/plain", retention));
      if (!orphans.contains(i)) {
        rows.add(
            new ObjectEntry(
                new EvidenceKey(path), ObjectClass.OTHER, Sha256.of(bytes), bytes.length));
      }
    }
    for (String ghost : List.of("landing/a-first", "landing/k0500a", "landing/k1202z")) {
      rows.add(
          new ObjectEntry(
              new EvidenceKey(ghost), ObjectClass.OTHER, Sha256.ofUtf8(ghost), ghost.length()));
    }
    ledger.recordObjects(rows, Provenance.WRITTEN, Optional.empty());
    List<Integer> manifestLimits = new ArrayList<>();
    LedgerStore spied =
        ledgerWithHook(
            ledger,
            (name, args) -> {
              if (name.equals("manifestRows")) {
                manifestLimits.add((Integer) args[2]);
              }
            });
    ScriptedStore scripted = new ScriptedStore(evidence);

    Report report = new EvidenceVerifier(scripted, spied).verify("");

    assertThat(report.findings())
        .containsExactlyInAnyOrder(
            orphan("landing/k0499"),
            orphan("landing/k0500"),
            orphan("landing/k1000"),
            new Finding(Kind.MISSING, "landing/a-first", ABSENT),
            new Finding(Kind.MISSING, "landing/k0500a", ABSENT),
            new Finding(Kind.MISSING, "landing/k1202z", ABSENT));
    assertThat(report.objectsChecked()).isEqualTo(1_203);
    assertThat(report.manifestRowsChecked()).isEqualTo(1_200 + 3);
    assertThat(scripted.listLimits).hasSizeGreaterThanOrEqualTo(3).allMatch(limit -> limit == 500);
    assertThat(manifestLimits).hasSizeGreaterThanOrEqualTo(3).allMatch(limit -> limit == 500);
  }

  private static Finding orphan(String path) {
    return new Finding(Kind.ORPHAN, path, "sha256=" + Sha256.ofUtf8(path).hex());
  }

  @Test
  void aListingOutOfKeyOrderIsAnErrorAndNotAGuess() {
    ManualClock clock = new ManualClock(START);
    InMemoryLedgerStore ledger = new InMemoryLedgerStore(clock);
    InMemoryEvidenceStore evidence = new InMemoryEvidenceStore(clock);
    EvidenceStore.Retention retention =
        new EvidenceStore.Retention(
            new EvidenceStore.RetentionClass("landing"), START.plus(Duration.ofDays(30)));
    for (String path : List.of("landing/a", "landing/b", "landing/c")) {
      evidence.put(
          new EvidenceStore.PutRequest(
              new EvidenceKey(path), path.getBytes(UTF_8), "text/plain", retention));
    }
    ScriptedStore scripted = new ScriptedStore(evidence);
    scripted.descending = true;

    assertThatThrownBy(() -> new EvidenceVerifier(scripted, ledger).verify(""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("key order");
  }

  @Test
  void theRecordsRefuseNullsAndTheReportCopiesItsFindings() {
    List<Finding> list = new ArrayList<>(List.of(new Finding(Kind.ORPHAN, "k", "d")));
    Report report = new Report(list, 1, 0);
    list.clear();

    assertThat(report.findings()).hasSize(1);
    assertThat(report.clean()).isFalse();
    assertThat(new Report(List.of(), 0, 0).clean()).isTrue();
    assertThatThrownBy(() -> new Report(List.of(), -1, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Report(List.of(), 0, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Finding(null, "k", "d")).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Finding(Kind.ORPHAN, null, "d"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Finding(Kind.ORPHAN, "k", null))
        .isInstanceOf(NullPointerException.class);
    ManualClock clock = new ManualClock(START);
    InMemoryLedgerStore ledger = new InMemoryLedgerStore(clock);
    InMemoryEvidenceStore evidence = new InMemoryEvidenceStore(clock);
    assertThatThrownBy(() -> new EvidenceVerifier(null, ledger))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new EvidenceVerifier(evidence, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new EvidenceVerifier(evidence, ledger).verify(null))
        .isInstanceOf(NullPointerException.class);
  }
}
