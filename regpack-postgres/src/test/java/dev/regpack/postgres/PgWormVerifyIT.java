package dev.regpack.postgres;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.evidence.s3.WormEvidenceStore;
import dev.regpack.evidence.seal.DurableSeal;
import dev.regpack.evidence.seal.EpochMarker;
import dev.regpack.evidence.seal.SealedKeys;
import dev.regpack.evidence.seal.SendGate;
import dev.regpack.evidence.verify.EvidenceVerifier;
import dev.regpack.evidence.verify.EvidenceVerifier.Finding;
import dev.regpack.evidence.verify.EvidenceVerifier.Kind;
import dev.regpack.evidence.verify.EvidenceVerifier.Report;
import dev.regpack.model.Sha256;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.evidence.journal.JournalKey;
import dev.regpack.ports.evidence.journal.JournalObject;
import dev.regpack.ports.evidence.journal.JournalScope;
import dev.regpack.ports.evidence.journal.JournalType;
import dev.regpack.ports.evidence.journal.JournalWriter;
import dev.regpack.ports.evidence.seal.Sealing.SealOutcome;
import dev.regpack.ports.evidence.seal.Sealing.SealRequest;
import dev.regpack.ports.evidence.seal.Sealing.SealStatus;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptId;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptIntent;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.DeliveryLedger.SendingResult;
import dev.regpack.ports.ledger.DeliveryLedger.SendingStatus;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.ports.ledger.ManifestLedger.ObjectClass;
import dev.regpack.ports.ledger.ManifestLedger.ObjectEntry;
import dev.regpack.ports.ledger.ManifestLedger.Provenance;
import dev.regpack.postgres.PgWormSupport.S3Settings;
import dev.regpack.testkit.contract.LedgerFixtures;
import dev.regpack.testkit.contract.SealScenarioContract;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * P3b2 T11: {@code verify} over P-WORM on a real S3-compatible object-lock service and the manifest
 * of a real PostgreSQL ledger: the storage half of I10 with both real stores at once. A real-store
 * test of the job {@code evidence-worm} (group {@code worm}); it is skipped without {@code
 * REGPACK_PG_URL} and {@code REGPACK_S3_ENDPOINT}. Its twin over an in-memory ledger is {@code
 * WormVerifyIT} of the module {@code regpack-evidence-s3}.
 *
 * <p><b>What is seeded.</b> Four of the 20 faults of {@code EvidenceVerifierTest}: ORPHAN (an
 * object with no manifest row), MISSING (a manifest row for an object that was never put),
 * HASH_MISMATCH {@code manifest-hash} (a row with another hash than the object, recorded through
 * {@code recordObjects}) and INTENT_MISMATCH (an intent object with another artifact hash than its
 * attempt row). SEQUENCE_GAP needs the class (b) counter of P3d and is not seeded here; the
 * store-integrity faults (a hidden object, a damaged object, a shortened lock) are shown on the
 * service with the in-memory ledger by {@code WormVerifyIT}. The record names these four and does
 * not say "each" beyond them.
 *
 * <p>The clock, the bucket and the schema are fresh for every test (see {@link
 * PgWormSealScenarioIT}).
 */
@Tag("worm")
class PgWormVerifyIT extends AbstractPgStoreTest {
  private static final Instant START = Instant.parse("2040-03-01T00:00:00Z");
  private static final Duration TTL = Duration.ofSeconds(60);
  private static final RetentionPolicy RETENTION = RetentionPolicy.fixedDays(1826);
  private static final String ABSENT = "the object is absent from the evidence tier";

  private WormEvidenceStore evidence;
  private JournalWriter journal;
  private DurableSeal sealing;
  private EvidenceVerifier verifier;

  @BeforeEach
  void openTheEvidenceStore() {
    clock().set(START);
    S3Settings settings = PgWormSupport.settings();
    String bucket = PgWormSupport.newBucket(PgWormSupport.rawClient(settings));
    evidence = PgWormSupport.openStore(settings, bucket, clock());
    journal = new JournalWriter(evidence, RETENTION);
    sealing =
        new DurableSeal(
            store(), evidence, clock(), RETENTION, new SendGate(SendGate.Requirement.none()));
    verifier = new EvidenceVerifier(evidence, store());
  }

  // ---------------------------------------------------------------------------------------------
  // fixtures

  /** What a seal left: the request and the attempt it was sealed under. */
  private record Sealed(SealRequest request, AttemptId attempt) {}

  /** A delivery that has an attempt id and nothing else (the intent object is put by hand). */
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

  private Sealed seal(EndpointKey endpoint, LeaseToken token, String businessKey) {
    DeliveryId delivery = SealScenarioContract.queued(store(), endpoint, businessKey);
    SealRequest request =
        SealScenarioContract.request(
            endpoint, token, delivery, businessKey, SealScenarioContract.reportOf(businessKey));
    SealOutcome outcome = sealing.seal(request);
    assertThat(outcome.status()).as("the seal: " + outcome.detail()).isEqualTo(SealStatus.SEALED);
    return new Sealed(request, outcome.attempt().orElseThrow());
  }

  /** Two sealed deliveries of one endpoint: four objects, four manifest rows. */
  private List<Sealed> baseline() {
    EndpointKey endpoint = LedgerFixtures.endpoint("VER1");
    LeaseToken token = SealScenarioContract.lease(store(), endpoint, "A");
    return List.of(seal(endpoint, token, "BK-A"), seal(endpoint, token, "BK-B"));
  }

  private Started start(String name) {
    EndpointKey endpoint = LedgerFixtures.endpoint(name);
    LeaseToken token = SealScenarioContract.lease(store(), endpoint, "A");
    DeliveryId delivery = LedgerFixtures.queuedDelivery(store(), endpoint, "BK-" + name, 1);
    String fileName = "file-" + name + ".xml";
    return new Started(
        endpoint,
        token,
        delivery,
        "BK-" + name,
        store().allocateAttemptId(),
        Sha256.ofUtf8("artifact-" + fileName),
        fileName);
  }

  private Map<String, Object> intentPayload(Started started) {
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

  /** Puts {@code bytes} as a sealed file with its manifest row, as a seal would have. */
  private void addSealedFile(byte[] bytes) {
    Sha256 sha = Sha256.of(bytes);
    evidence.put(
        new EvidenceStore.PutRequest(
            SealedKeys.of(sha),
            bytes,
            "application/xml",
            RETENTION.retentionFor(DurableSeal.SEALED_RETENTION_CLASS, clock().now())));
    store()
        .recordObjects(
            List.of(
                new ObjectEntry(SealedKeys.of(sha), ObjectClass.SEALED_FILE, sha, bytes.length)),
            Provenance.WRITTEN,
            Optional.empty());
  }

  /**
   * Writes an intent object with the edited payload through the journal writer and starts the
   * attempt with a manifest row that names the object exactly as it was written; the sealed file
   * the payload names (the attempt row's artifact) is put and recorded first.
   */
  private EvidenceKey handWrittenIntent(String name, UnaryOperator<Map<String, Object>> edit) {
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
            clock().now(),
            payload);
    journal.write(object);
    byte[] bytes = object.toBytes();
    addSealedFile(started.artifactBytes());
    List<ObjectEntry> objects =
        List.of(
            new ObjectEntry(
                object.key().evidenceKey(), ObjectClass.JOURNAL_A, Sha256.of(bytes), bytes.length));
    SendingResult result =
        store()
            .beginSending(
                started.delivery(),
                started.attempt(),
                started.token(),
                new AttemptIntent(started.artifact(), started.fileName(), objects));
    assertThat(result.status()).isEqualTo(SendingStatus.APPLIED);
    return object.key().evidenceKey();
  }

  private void putPlain(String path, String text) {
    evidence.put(
        new EvidenceStore.PutRequest(
            new EvidenceKey(path),
            text.getBytes(UTF_8),
            "text/plain",
            new EvidenceStore.Retention(
                new EvidenceStore.RetentionClass("landing"),
                clock().now().plus(Duration.ofDays(30)))));
  }

  /** A manifest row for {@code path}, whatever the tier holds under it. */
  private void recordRow(String path, Sha256 sha, long size) {
    store()
        .recordObjects(
            List.of(new ObjectEntry(new EvidenceKey(path), ObjectClass.OTHER, sha, size)),
            Provenance.WRITTEN,
            Optional.empty());
  }

  private static Finding finding(Kind kind, EvidenceKey key, String detail) {
    return new Finding(kind, key.path(), detail);
  }

  private static final Comparator<Finding> ORDER =
      Comparator.comparing(Finding::key)
          .thenComparing(Finding::kind)
          .thenComparing(Finding::detail);

  // ---------------------------------------------------------------------------------------------
  // a clean tier

  @Test
  void aCleanTierHasNoFinding() {
    assertThat(verifier.verify("")).isEqualTo(new Report(List.of(), 0, 0));

    baseline();
    Report afterSealing = verifier.verify("");
    assertThat(afterSealing.findings()).isEmpty();
    assertThat(afterSealing.clean()).isTrue();
    assertThat(afterSealing.objectsChecked())
        .as("two sealed files and two intent objects")
        .isEqualTo(4);
    assertThat(afterSealing.manifestRowsChecked()).isEqualTo(4);

    // a restore-epoch marker is written before the ledger knows the epoch: it is no orphan
    new EpochMarker(evidence, RETENTION, clock()).declare(1);
    Report withMarker = verifier.verify("");
    assertThat(withMarker.findings()).isEmpty();
    assertThat(withMarker.objectsChecked()).isEqualTo(5);
    assertThat(withMarker.manifestRowsChecked()).isEqualTo(4);

    // the prefix narrows both sides alike
    Report journalOnly = verifier.verify("journal/");
    assertThat(journalOnly.findings()).isEmpty();
    assertThat(journalOnly.objectsChecked()).isEqualTo(2);
    assertThat(journalOnly.manifestRowsChecked()).isEqualTo(2);
    Report sealedFiles = verifier.verify("sealed/");
    assertThat(sealedFiles.findings()).isEmpty();
    assertThat(sealedFiles.objectsChecked()).isEqualTo(2);
    assertThat(sealedFiles.manifestRowsChecked()).isEqualTo(2);
    assertThat(verifier.verify("landing/")).isEqualTo(new Report(List.of(), 0, 0));
  }

  // ---------------------------------------------------------------------------------------------
  // seeded faults on the PostgreSQL ledger

  /**
   * Four faults, one after the other on a tier that was clean: after each, {@code verify} reports
   * the faults seeded so far, each with its own class and nothing else (so a fault is found by what
   * it adds, and none hides or changes another). The manifest side is the real server's: the rows
   * are read back by its range query, and the wrong row of the third fault is written through
   * {@code recordObjects}.
   */
  @Test
  void aSeededFaultOnThePostgreSqlLedgerIsReportedWithItsOwnClass() {
    baseline();
    assertThat(verifier.verify("").clean()).as("the tier is clean before").isTrue();
    List<Finding> expected = new ArrayList<>();

    // ORPHAN: an object put directly, with no manifest row
    putPlain("landing/extra", "extra");
    expected.add(
        finding(
            Kind.ORPHAN,
            new EvidenceKey("landing/extra"),
            "sha256=" + Sha256.ofUtf8("extra").hex()));
    assertFindings("ORPHAN", expected);

    // MISSING: a manifest row for an object that was never put
    recordRow("landing/ghost", Sha256.ofUtf8("ghost"), "ghost".length());
    expected.add(finding(Kind.MISSING, new EvidenceKey("landing/ghost"), ABSENT));
    assertFindings("MISSING", expected);

    // HASH_MISMATCH manifest-hash: the object is there and well formed, the row says another hash
    putPlain("landing/hashed", "hashed");
    recordRow("landing/hashed", Sha256.ofUtf8("something else"), "hashed".length());
    expected.add(
        finding(
            Kind.HASH_MISMATCH, new EvidenceKey("landing/hashed"), EvidenceVerifier.MANIFEST_HASH));
    assertFindings("HASH_MISMATCH", expected);

    // INTENT_MISMATCH: an intent object with another artifact hash than its attempt row (the other
    // file is in the tier and the manifest, so that nothing else is wrong)
    addSealedFile("another".getBytes(UTF_8));
    EvidenceKey intent =
        handWrittenIntent(
            "HAND1",
            payload -> {
              payload.put("artifact_sha256", Sha256.ofUtf8("another").hex());
              return payload;
            });
    expected.add(
        finding(Kind.INTENT_MISMATCH, intent, "artifact_sha256 differs from the attempt row"));
    assertFindings("INTENT_MISMATCH", expected);

    // the four classes are four, and the rest of the tier is as clean as before
    assertThat(verifier.verify("").findings())
        .extracting(Finding::kind)
        .containsExactlyInAnyOrder(
            Kind.ORPHAN, Kind.MISSING, Kind.HASH_MISMATCH, Kind.INTENT_MISMATCH);
    assertThat(verifier.verify("journal/").findings())
        .as("the sealed deliveries of the baseline are untouched")
        .extracting(Finding::key)
        .containsExactly(intent.path());
  }

  private void assertFindings(String fault, List<Finding> expected) {
    Report report = verifier.verify("");
    assertThat(report.findings())
        .as("after the fault " + fault)
        .containsExactlyElementsOf(expected.stream().sorted(ORDER).toList());
    for (Finding f : report.findings()) {
      assertThat(f.detail())
          .as("the detail of a finding carries hashes and fixed texts only")
          .doesNotContain("BK-")
          .doesNotContain(".xml")
          .doesNotContain("127.0.0.1");
    }
  }
}
