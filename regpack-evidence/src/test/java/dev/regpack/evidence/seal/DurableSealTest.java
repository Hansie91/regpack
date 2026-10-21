package dev.regpack.evidence.seal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.model.Sha256;
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
import dev.regpack.ports.evidence.seal.Sealing.DeliveryIntentPayload;
import dev.regpack.ports.evidence.seal.Sealing.PreSendStatus;
import dev.regpack.ports.evidence.seal.Sealing.SealOutcome;
import dev.regpack.ports.evidence.seal.Sealing.SealRequest;
import dev.regpack.ports.evidence.seal.Sealing.SealStatus;
import dev.regpack.ports.ledger.DeliveryLedger;
import dev.regpack.ports.ledger.DeliveryLedger.Attempt;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.DeliveryLedger.NewDelivery;
import dev.regpack.ports.ledger.DeliveryLedger.TransitionStatus;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseResult;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.ports.ledger.LedgerDurability;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.ManifestConflictException;
import dev.regpack.ports.ledger.ManifestLedger.ManifestRow;
import dev.regpack.ports.ledger.ManifestLedger.ObjectClass;
import dev.regpack.ports.ledger.ManifestLedger.ObjectEntry;
import dev.regpack.ports.ledger.ManifestLedger.Provenance;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.LedgerFixtures;
import dev.regpack.testkit.fault.FaultInjector;
import dev.regpack.testkit.fault.FaultPlan;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Steps 2 to 4 of spec 11.2 as a library, on an in-memory ledger and an in-memory evidence store:
 * what a successful seal leaves behind, what is refused before any effect, what a refusal leaves
 * behind, and the gate in front of the first byte. The order, the outage and the crash scenarios
 * are the shared suite of T14a.
 */
class DurableSealTest {
  private static final Instant START = Instant.parse("2026-10-20T08:00:00Z");
  private static final Duration TTL = Duration.ofSeconds(60);
  private static final RetentionPolicy FIVE_YEARS = RetentionPolicy.fixedDays(1826);
  private static final EndpointKey EP = LedgerFixtures.endpoint("SEAL1");
  private static final byte[] BYTES = "<report>trade one</report>".getBytes(UTF_8);

  private ManualClock clock;
  private InMemoryLedgerStore ledger;
  private InMemoryEvidenceStore evidence;
  private DurableSeal sealing;

  @BeforeEach
  void setUp() {
    clock = new ManualClock(START);
    ledger = new InMemoryLedgerStore(clock);
    evidence = new InMemoryEvidenceStore(clock);
    sealing =
        new DurableSeal(
            ledger, evidence, clock, FIVE_YEARS, new SendGate(SendGate.Requirement.none()));
  }

  private LeaseToken lease(EndpointKey endpoint, String holder) {
    LeaseResult result = ledger.acquire(endpoint, holder, TTL);
    if (!(result instanceof LeaseResult.Granted granted)) {
      throw new AssertionError("the lease was not granted: " + result);
    }
    return granted.grant().token();
  }

  private DeliveryId queued(EndpointKey endpoint, String businessKey) {
    return LedgerFixtures.queuedDelivery(ledger, endpoint, businessKey, 1);
  }

  private static DeliveryIntentPayload intent(
      DeliveryId delivery, String businessKey, byte[] bytes, String fileName) {
    return new DeliveryIntentPayload(
        delivery,
        businessKey,
        "TRN-" + businessKey,
        1,
        "NEWT",
        Sha256.ofUtf8("logical-" + businessKey),
        Sha256.of(bytes),
        fileName,
        List.of(Sha256.ofUtf8("ctx-1"), Sha256.ofUtf8("ctx-2")));
  }

  private static SealRequest request(
      EndpointKey endpoint,
      LeaseToken token,
      DeliveryId delivery,
      String businessKey,
      byte[] bytes) {
    String fileName = "file-" + businessKey + ".xml";
    return new SealRequest(
        endpoint,
        token,
        delivery,
        fileName,
        bytes,
        "application/xml",
        intent(delivery, businessKey, bytes, fileName));
  }

  private static JournalKey intentKey(EndpointKey endpoint, long epoch, long attempt) {
    return new JournalKey.ClassA(
        JournalScope.of(endpoint), epoch, JournalType.DELIVERY_INTENT, Long.toString(attempt), 1);
  }

  private List<String> keys() {
    List<String> paths = new ArrayList<>();
    evidence.list("", Optional.empty(), 1_000).forEach(k -> paths.add(k.path()));
    return paths;
  }

  private List<ManifestRow> manifest() {
    return ledger.manifestRows("", Optional.empty(), 1_000);
  }

  /** Nothing of a refused seal is left: no object, no manifest row, no attempt id consumed. */
  private void assertNoEffect(DeliveryId delivery, DeliveryState state) {
    assertThat(keys()).isEmpty();
    assertThat(manifest()).isEmpty();
    assertThat(ledger.delivery(delivery).orElseThrow().state()).isEqualTo(state);
    assertThat(ledger.allocateAttemptId()).isEqualTo(new AttemptId(1));
  }

  @Test
  void aSealedDeliveryIsSendingWithBothObjectsAndTheirManifestRows() {
    LeaseToken token = lease(EP, "A");
    DeliveryId delivery = queued(EP, "BK-1");

    SealOutcome outcome = sealing.seal(request(EP, token, delivery, "BK-1", BYTES));

    assertThat(outcome.status()).isEqualTo(SealStatus.SEALED);
    AttemptId attempt = outcome.attempt().orElseThrow();
    assertThat(attempt).isEqualTo(new AttemptId(1));
    assertThat(ledger.delivery(delivery).orElseThrow().state()).isEqualTo(DeliveryState.SENDING);
    Attempt row = ledger.attempt(attempt).orElseThrow();
    assertThat(row.delivery()).isEqualTo(delivery);
    assertThat(row.token()).isEqualTo(token);
    assertThat(row.artifactSha256()).isEqualTo(Sha256.of(BYTES));
    assertThat(row.fileName()).isEqualTo("file-BK-1.xml");
    assertThat(row.commitIssued()).isFalse();

    EvidenceKey sealedKey = SealedKeys.of(Sha256.of(BYTES));
    EvidenceKey intentKey = intentKey(EP, 0, 1).evidenceKey();
    assertThat(keys()).containsExactly(intentKey.path(), sealedKey.path());

    EvidenceStore.StoredObject sealed = evidence.get(sealedKey).orElseThrow();
    assertThat(sealed.bytes()).isEqualTo(BYTES);
    assertThat(sealed.mediaType()).isEqualTo("application/xml");
    assertThat(sealed.retention().retentionClass().name()).isEqualTo("sealed");
    assertThat(sealed.retention().retainUntil()).isEqualTo(START.plus(Duration.ofDays(1826)));

    EvidenceStore.StoredObject journal = evidence.get(intentKey).orElseThrow();
    assertThat(journal.mediaType()).isEqualTo(JournalWriter.MEDIA_TYPE);
    assertThat(journal.retention().retentionClass().name()).isEqualTo("journal");

    assertThat(manifest()).hasSize(2);
    ManifestRow sealedRow = ledger.manifestRow(sealedKey).orElseThrow();
    assertThat(sealedRow.entry().objectClass()).isEqualTo(ObjectClass.SEALED_FILE);
    assertThat(sealedRow.entry().sha256()).isEqualTo(sealed.sha256()).isEqualTo(Sha256.of(BYTES));
    assertThat(sealedRow.entry().sizeBytes()).isEqualTo(BYTES.length);
    assertThat(sealedRow.provenance()).isEqualTo(Provenance.WRITTEN);
    assertThat(sealedRow.attempt()).contains(attempt);
    assertThat(sealedRow.writtenAt()).isEqualTo(START);
    ManifestRow intentRow = ledger.manifestRow(intentKey).orElseThrow();
    assertThat(intentRow.entry().objectClass()).isEqualTo(ObjectClass.JOURNAL_A);
    assertThat(intentRow.entry().sha256())
        .isEqualTo(journal.sha256())
        .isEqualTo(Sha256.of(journal.bytes()));
    assertThat(intentRow.entry().sizeBytes()).isEqualTo(journal.bytes().length);
    assertThat(intentRow.provenance()).isEqualTo(Provenance.WRITTEN);
    assertThat(intentRow.attempt()).contains(attempt);
  }

  /** A ledger that delegates everything and records the intent that beginSending receives. */
  private LedgerStore ledgerRecordingTheIntents(List<DeliveryLedger.AttemptIntent> intents) {
    return (LedgerStore)
        Proxy.newProxyInstance(
            LedgerStore.class.getClassLoader(),
            new Class<?>[] {LedgerStore.class},
            (proxy, method, args) -> {
              if (method.getName().equals("beginSending")) {
                intents.add((DeliveryLedger.AttemptIntent) args[3]);
              }
              try {
                return method.invoke(ledger, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  @Test
  void theAttemptIntentNamesTheSealedFileThenTheIntentObjectWithTheHashesOfThePlannedBytes() {
    List<DeliveryLedger.AttemptIntent> intents = new ArrayList<>();
    DurableSeal observed =
        new DurableSeal(
            ledgerRecordingTheIntents(intents),
            evidence,
            clock,
            FIVE_YEARS,
            new SendGate(SendGate.Requirement.none()));
    LeaseToken token = lease(EP, "A");
    DeliveryId delivery = queued(EP, "BK-1");

    SealOutcome outcome = observed.seal(request(EP, token, delivery, "BK-1", BYTES));

    assertThat(outcome.status()).isEqualTo(SealStatus.SEALED);
    assertThat(intents).hasSize(1);
    DeliveryLedger.AttemptIntent intent = intents.get(0);
    assertThat(intent.artifactSha256()).isEqualTo(Sha256.of(BYTES));
    assertThat(intent.fileName()).isEqualTo("file-BK-1.xml");
    // the order of the manifest entries is part of the contract with the ledger: the sealed file
    // first, then the intent object
    assertThat(intent.objects())
        .extracting(ObjectEntry::objectClass)
        .containsExactly(ObjectClass.SEALED_FILE, ObjectClass.JOURNAL_A);
    ObjectEntry sealedEntry = intent.objects().get(0);
    assertThat(sealedEntry.key()).isEqualTo(SealedKeys.of(Sha256.of(BYTES)));
    assertThat(sealedEntry.sha256()).isEqualTo(Sha256.of(BYTES));
    assertThat(sealedEntry.sizeBytes()).isEqualTo(BYTES.length);
    ObjectEntry intentEntry = intent.objects().get(1);
    EvidenceStore.StoredObject storedIntent =
        evidence.get(intentKey(EP, 0, 1).evidenceKey()).orElseThrow();
    assertThat(intentEntry.key()).isEqualTo(intentKey(EP, 0, 1).evidenceKey());
    assertThat(intentEntry.sha256()).isEqualTo(Sha256.of(storedIntent.bytes()));
    assertThat(intentEntry.sizeBytes()).isEqualTo(storedIntent.bytes().length);
  }

  @Test
  void theIntentObjectCarriesTheAttemptIdAndTheArtifactHash() {
    LeaseToken token = lease(EP, "A");
    DeliveryId delivery = queued(EP, "BK-1");
    clock.advance(Duration.ofMinutes(3));

    AttemptId attempt = sealing.seal(request(EP, token, delivery, "BK-1", BYTES)).attempt().get();

    JournalKey key = intentKey(EP, 0, attempt.value());
    JournalObject intent = new JournalWriter(evidence, FIVE_YEARS).read(key).orElseThrow();
    assertThat(intent.type()).isEqualTo(JournalType.DELIVERY_INTENT);
    assertThat(intent.schemaVersion()).isEqualTo(1);
    assertThat(intent.clockInstant()).isEqualTo(START.plus(Duration.ofMinutes(3)));
    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("attempt_id", attempt.value());
    expected.put("delivery_id", delivery.value());
    expected.put("business_key", "BK-1");
    expected.put("trn", "TRN-BK-1");
    expected.put("version_seq", 1L);
    expected.put("action", "NEWT");
    expected.put("logical_hash", Sha256.ofUtf8("logical-BK-1").hex());
    expected.put("artifact_sha256", Sha256.of(BYTES).hex());
    expected.put("file_name", "file-BK-1.xml");
    expected.put(
        "context_ids", List.of(Sha256.ofUtf8("ctx-1").hex(), Sha256.ofUtf8("ctx-2").hex()));
    assertThat(intent.payload()).isEqualTo(expected);
    // the journal retention of the object follows the clock instant of the object
    assertThat(evidence.get(key.evidenceKey()).orElseThrow().retention().retainUntil())
        .isEqualTo(START.plus(Duration.ofMinutes(3)).plus(Duration.ofDays(1826)));
  }

  @Test
  void theKeyAndThePayloadOfEveryIntentAgreeOnOneAttemptId() {
    // hand-off of T3: JournalSchemas does not cross-check payload and key; the producer must
    LeaseToken token = lease(EP, "A");
    List<Long> attempts = new ArrayList<>();
    for (int i = 1; i <= 3; i++) {
      String businessKey = "BK-" + i;
      DeliveryId delivery = queued(EP, businessKey);
      // an id taken and not used between two seals: the ids need not be consecutive
      ledger.allocateAttemptId();
      byte[] bytes = ("report " + i).getBytes(UTF_8);
      SealOutcome outcome = sealing.seal(request(EP, token, delivery, businessKey, bytes));
      assertThat(outcome.status()).isEqualTo(SealStatus.SEALED);
      attempts.add(outcome.attempt().orElseThrow().value());
    }
    assertThat(attempts).doesNotHaveDuplicates().hasSize(3);
    JournalWriter writer = new JournalWriter(evidence, FIVE_YEARS);
    for (long attempt : attempts) {
      JournalObject intent = writer.read(intentKey(EP, 0, attempt)).orElseThrow();
      JournalKey.ClassA key = (JournalKey.ClassA) intent.key();
      assertThat(key.subjectId()).isEqualTo(Long.toString(attempt));
      assertThat(intent.payload().get("attempt_id")).isEqualTo(attempt);
      assertThat(key.ordinal()).isEqualTo(1);
    }
  }

  @Test
  void theIntentKeyCarriesTheRestoreEpochOfTheLeaseToken() {
    ledger.declareRestoreEpoch(7);
    LeaseToken token = lease(EP, "A");
    assertThat(token.restoreEpoch()).isEqualTo(7);
    DeliveryId delivery = queued(EP, "BK-1");

    SealOutcome outcome = sealing.seal(request(EP, token, delivery, "BK-1", BYTES));

    assertThat(outcome.status()).isEqualTo(SealStatus.SEALED);
    assertThat(keys())
        .contains(
            "journal/SIMULATOR,ARM,LEI-SEAL1,NCA-SEAL1/e000000000007/a/DeliveryIntent/"
                + "n00000000/000000000001/000000000001");
  }

  @Test
  void aRequestWhoseHashDoesNotMatchItsBytesIsRefusedBeforeAnyEffect() {
    LeaseToken token = lease(EP, "A");
    DeliveryId delivery = queued(EP, "BK-SECRET");
    byte[] other = "other bytes".getBytes(UTF_8);
    SealRequest request =
        new SealRequest(
            EP,
            token,
            delivery,
            "file.xml",
            BYTES,
            "application/xml",
            intent(delivery, "BK-SECRET", other, "file.xml"));

    Throwable thrown = catchThrowable(() -> sealing.seal(request));

    assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    assertThat(thrown.getMessage()).doesNotContain("BK-SECRET").doesNotContain("file.xml");
    assertNoEffect(delivery, DeliveryState.QUEUED);
  }

  @Test
  void aRequestWhoseIntentNamesAnotherDeliveryOrFileIsRefusedBeforeAnyEffect() {
    LeaseToken token = lease(EP, "A");
    DeliveryId delivery = queued(EP, "BK-1");
    SealRequest otherDelivery =
        new SealRequest(
            EP,
            token,
            delivery,
            "file-BK-1.xml",
            BYTES,
            "application/xml",
            intent(new DeliveryId(delivery.value() + 1), "BK-1", BYTES, "file-BK-1.xml"));
    SealRequest otherFile =
        new SealRequest(
            EP,
            token,
            delivery,
            "file-BK-1.xml",
            BYTES,
            "application/xml",
            intent(delivery, "BK-1", BYTES, "another.xml"));

    assertThatThrownBy(() -> sealing.seal(otherDelivery))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> sealing.seal(otherFile)).isInstanceOf(IllegalArgumentException.class);
    assertNoEffect(delivery, DeliveryState.QUEUED);
  }

  @Test
  void anEndpointOutsideTheScopeAlphabetIsRefusedBeforeAnyEffect() {
    EndpointKey odd = new EndpointKey("TEST ENV", "ARM", "LEI-ODD", "NCA-ODD");
    LeaseToken token = lease(odd, "A");
    DeliveryId delivery = queued(odd, "BK-1");

    Throwable thrown =
        catchThrowable(() -> sealing.seal(request(odd, token, delivery, "BK-1", BYTES)));

    assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    assertThat(thrown.getMessage()).doesNotContain("TEST ENV");
    assertNoEffect(delivery, DeliveryState.QUEUED);
  }

  @Test
  void aRequestForAnotherEndpointThanTheDeliverysIsRefusedBeforeAnyEffect() {
    EndpointKey other = LedgerFixtures.endpoint("SEAL2");
    LeaseToken token = lease(other, "A");
    DeliveryId delivery = queued(EP, "BK-1");

    assertThatThrownBy(() -> sealing.seal(request(other, token, delivery, "BK-1", BYTES)))
        .isInstanceOf(IllegalArgumentException.class);
    assertNoEffect(delivery, DeliveryState.QUEUED);
  }

  @Test
  void anUnknownDeliveryIsRefusedBeforeAnyEffect() {
    LeaseToken token = lease(EP, "A");
    DeliveryId unknown = new DeliveryId(4_711);

    assertThatThrownBy(() -> sealing.seal(request(EP, token, unknown, "BK-1", BYTES)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(keys()).isEmpty();
    assertThat(manifest()).isEmpty();
    assertThat(ledger.allocateAttemptId()).isEqualTo(new AttemptId(1));
  }

  @Test
  void aPayloadTheJournalCannotHoldIsRefusedBeforeAnyEffect() {
    LeaseToken token = lease(EP, "A");
    DeliveryId delivery = queued(EP, "BK-1");
    // a business key longer than a journal text may be
    SealRequest longKey =
        new SealRequest(
            EP,
            token,
            delivery,
            "file.xml",
            BYTES,
            "application/xml",
            intent(delivery, "k".repeat(1_025), BYTES, "file.xml"));
    // a file name that is a journal text but no key text of the ledger (a control character)
    SealRequest controlName =
        new SealRequest(
            EP,
            token,
            delivery,
            "a\tb.xml",
            BYTES,
            "application/xml",
            intent(delivery, "BK-1", BYTES, "a\tb.xml"));

    assertThatThrownBy(() -> sealing.seal(longKey)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> sealing.seal(controlName))
        .isInstanceOf(IllegalArgumentException.class);
    assertNoEffect(delivery, DeliveryState.QUEUED);
  }

  private static SealRequest requestWithMediaType(
      EndpointKey endpoint, LeaseToken token, DeliveryId delivery, String mediaType) {
    return new SealRequest(
        endpoint,
        token,
        delivery,
        "file-BK-1.xml",
        BYTES,
        mediaType,
        intent(delivery, "BK-1", BYTES, "file-BK-1.xml"));
  }

  @Test
  void aMediaTypeThatTheEvidenceStoreCannotHoldIsRefusedBeforeAnyEffect() {
    LeaseToken token = lease(EP, "A");
    DeliveryId delivery = queued(EP, "BK-1");
    String lead = "application/secret-";
    List<String> refused =
        List.of(
            "",
            "   ",
            "a".repeat(256),
            lead + "x\ty",
            lead + "x\ny",
            lead + "x" + (char) 0 + "y",
            lead + "x" + (char) 0x7f + "y",
            lead + "x" + (char) 0x85 + "y",
            lead + "x" + (char) 0xD800,
            lead + "x" + (char) 0xDC00 + "y",
            lead + (char) 0xDE00 + (char) 0xD800 + "y");

    for (String mediaType : refused) {
      Throwable thrown =
          catchThrowable(() -> sealing.seal(requestWithMediaType(EP, token, delivery, mediaType)));

      assertThat(thrown)
          .as("media type of %d characters, first 12: %s", mediaType.length(), preview(mediaType))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(thrown.getMessage()).contains("media type").doesNotContain("secret");
    }
    // no put, no manifest row, and no attempt id consumed by any of the refused requests
    assertNoEffect(delivery, DeliveryState.QUEUED);
  }

  /** A readable form of a text with control characters and lone surrogates, for messages. */
  private static String preview(String text) {
    StringBuilder out = new StringBuilder();
    text.chars()
        .limit(12)
        .forEach(
            c -> out.append(c < 0x20 || c > 0x7e ? String.format("<%04x>", c) : "" + (char) c));
    return out.toString();
  }

  @Test
  void aMediaTypeAtTheLimitOrWithAPairedSurrogateIsAccepted() {
    LeaseToken token = lease(EP, "A");
    DeliveryId at255 = queued(EP, "BK-1");
    DeliveryId paired = queued(EP, "BK-2");
    String pair = new String(Character.toChars(0x1F600));
    byte[] other = "<report>trade two</report>".getBytes(UTF_8);

    SealOutcome atTheLimit = sealing.seal(requestWithMediaType(EP, token, at255, "a".repeat(255)));
    SealOutcome withPair =
        sealing.seal(
            new SealRequest(
                EP,
                token,
                paired,
                "file-BK-2.xml",
                other,
                "application/x-" + pair,
                intent(paired, "BK-2", other, "file-BK-2.xml")));

    assertThat(atTheLimit.status()).isEqualTo(SealStatus.SEALED);
    assertThat(withPair.status()).isEqualTo(SealStatus.SEALED);
  }

  @Test
  void aDeliveryThatIsNotQueuedIsRefusedWithoutOrphans() {
    LeaseToken token = lease(EP, "A");
    DeliveryId prepared = ledger.createDelivery(new NewDelivery(EP, 1, "BK-PREPARED", 1));

    SealOutcome outcome = sealing.seal(request(EP, token, prepared, "BK-PREPARED", BYTES));

    assertThat(outcome.status()).isEqualTo(SealStatus.STATE_MISMATCH);
    assertThat(outcome.attempt()).isEmpty();
    assertThat(outcome.detail()).isNotBlank().doesNotContain("BK-PREPARED");
    // the evidence store is empty and the attempt sequence did not move
    assertNoEffect(prepared, DeliveryState.PREPARED);
  }

  @Test
  void aDeliveryThatIsAlreadySendingIsRefusedAndAddsNothing() {
    LeaseToken token = lease(EP, "A");
    DeliveryId delivery = queued(EP, "BK-1");
    assertThat(sealing.seal(request(EP, token, delivery, "BK-1", BYTES)).status())
        .isEqualTo(SealStatus.SEALED);
    List<String> keysBefore = keys();
    List<ManifestRow> manifestBefore = manifest();

    SealOutcome again =
        sealing.seal(request(EP, token, delivery, "BK-1", "second try".getBytes(UTF_8)));

    assertThat(again.status()).isEqualTo(SealStatus.STATE_MISMATCH);
    assertThat(keys()).isEqualTo(keysBefore);
    assertThat(manifest()).isEqualTo(manifestBefore);
    assertThat(ledger.allocateAttemptId()).isEqualTo(new AttemptId(2));
  }

  @Test
  void anotherDeliveryOfTheSameBusinessKeyInFlightIsAnInFlightConflict() {
    LeaseToken token = lease(EP, "A");
    DeliveryId first = queued(EP, "BK-1");
    // a second version of the same business key, queued as well
    DeliveryId second = LedgerFixtures.queuedDelivery(ledger, EP, "BK-1", 2);
    assertThat(sealing.seal(request(EP, token, first, "BK-1", BYTES)).status())
        .isEqualTo(SealStatus.SEALED);

    SealOutcome outcome =
        sealing.seal(request(EP, token, second, "BK-1", "version two".getBytes(UTF_8)));

    assertThat(outcome.status()).isEqualTo(SealStatus.IN_FLIGHT_CONFLICT);
    assertThat(outcome.attempt()).isEmpty();
    assertThat(ledger.delivery(second).orElseThrow().state()).isEqualTo(DeliveryState.QUEUED);
    // the two objects of the refused attempt are orphans by design; only the first has rows
    assertThat(manifest()).hasSize(2);
  }

  @Test
  void aClosedGateStopsBeforeAnyPutAndAnyAttemptId() {
    DurableSeal production =
        new DurableSeal(
            ledger, evidence, clock, FIVE_YEARS, new SendGate(SendGate.Requirement.production()));
    LeaseToken token = lease(EP, "A");
    DeliveryId delivery = queued(EP, "BK-1");

    SealOutcome outcome = production.seal(request(EP, token, delivery, "BK-1", BYTES));

    assertThat(outcome.status()).isEqualTo(SealStatus.GATE_CLOSED);
    assertThat(outcome.attempt()).isEmpty();
    // the pinned detail: the reasons joined by "; " in the order ledger, acknowledgement, domains
    assertThat(outcome.detail())
        .isEqualTo(
            String.join(
                "; ",
                List.of(
                    SendGate.LEDGER_NOT_SYNCHRONOUS,
                    SendGate.EVIDENCE_NOT_SYNCHRONOUS,
                    SendGate.tooFewDomains(2))));
    assertNoEffect(delivery, DeliveryState.QUEUED);
  }

  @Test
  void anOpenProductionGateLetsTheSealThrough() {
    // a ledger that says it is synchronous; the in-memory evidence store cannot say so, so the
    // requirement names only the ledger side here
    ledger.setDurability(new LedgerDurability.Status(true, 1, "test"));
    DurableSeal gated =
        new DurableSeal(
            ledger,
            evidence,
            clock,
            FIVE_YEARS,
            new SendGate(new SendGate.Requirement(true, false, 0)));
    LeaseToken token = lease(EP, "A");
    DeliveryId delivery = queued(EP, "BK-1");

    assertThat(gated.seal(request(EP, token, delivery, "BK-1", BYTES)).status())
        .isEqualTo(SealStatus.SEALED);
  }

  @Test
  void anEvidenceOutageIsReportedAndLeavesTheLedgerQueued() {
    LeaseToken token = lease(EP, "A");
    DeliveryId delivery = queued(EP, "BK-1");
    evidence.simulateOutage(true);

    SealOutcome outcome = sealing.seal(request(EP, token, delivery, "BK-1", BYTES));

    evidence.simulateOutage(false);
    assertThat(outcome.status()).isEqualTo(SealStatus.EVIDENCE_UNAVAILABLE);
    assertThat(outcome.attempt()).isEmpty();
    assertThat(outcome.detail()).isNotBlank();
    assertThat(keys()).isEmpty();
    assertThat(manifest()).isEmpty();
    assertThat(ledger.delivery(delivery).orElseThrow().state()).isEqualTo(DeliveryState.QUEUED);
  }

  @Test
  void anOutageAfterTheSealedBytesLeavesTheSealedObjectAsTheOnlyOrphan() {
    FaultInjector faults =
        new FaultInjector(
            FaultPlan.failAt(
                "EvidenceStore.put:BEFORE", 2, new EvidenceUnavailableException("secret-detail")));
    DurableSeal seal =
        new DurableSeal(
            ledger,
            faults.wrap(EvidenceStore.class, evidence),
            clock,
            FIVE_YEARS,
            new SendGate(SendGate.Requirement.none()));
    LeaseToken token = lease(EP, "A");
    DeliveryId delivery = queued(EP, "BK-1");

    SealOutcome outcome = seal.seal(request(EP, token, delivery, "BK-1", BYTES));

    assertThat(outcome.status()).isEqualTo(SealStatus.EVIDENCE_UNAVAILABLE);
    assertThat(outcome.detail()).doesNotContain("secret-detail");
    assertThat(keys()).containsExactly(SealedKeys.of(Sha256.of(BYTES)).path());
    assertThat(manifest()).isEmpty();
    assertThat(ledger.delivery(delivery).orElseThrow().state()).isEqualTo(DeliveryState.QUEUED);
    assertThat(ledger.attempt(new AttemptId(1))).isEmpty();
  }

  @Test
  void aStaleLeaseAfterThePutsLeavesTwoOrphansAndRecordsNothing() {
    LeaseToken stale = lease(EP, "A");
    DeliveryId delivery = queued(EP, "BK-1");
    clock.advance(TTL);
    assertThat(lease(EP, "B")).isNotEqualTo(stale);

    SealOutcome outcome = sealing.seal(request(EP, stale, delivery, "BK-1", BYTES));

    assertThat(outcome.status()).isEqualTo(SealStatus.STALE_LEASE);
    assertThat(outcome.attempt()).isEmpty();
    assertThat(keys()).hasSize(2);
    assertThat(manifest()).isEmpty();
    assertThat(ledger.delivery(delivery).orElseThrow().state()).isEqualTo(DeliveryState.QUEUED);
    assertThat(ledger.attempt(new AttemptId(1))).isEmpty();
  }

  @Test
  void aManifestConflictFailsTheSealClosed() {
    LeaseToken token = lease(EP, "A");
    DeliveryId delivery = queued(EP, "BK-1");
    EvidenceKey sealedKey = SealedKeys.of(Sha256.of(BYTES));
    ledger.recordObjects(
        List.of(new ObjectEntry(sealedKey, ObjectClass.SEALED_FILE, Sha256.ofUtf8("other"), 3)),
        Provenance.WRITTEN,
        Optional.empty());

    assertThatThrownBy(() -> sealing.seal(request(EP, token, delivery, "BK-1", BYTES)))
        .isInstanceOf(ManifestConflictException.class);

    assertThat(ledger.delivery(delivery).orElseThrow().state()).isEqualTo(DeliveryState.QUEUED);
    assertThat(ledger.attempt(new AttemptId(1))).isEmpty();
    assertThat(manifest()).hasSize(1);
  }

  @Test
  void anIntegrityAlarmOfTheStoreIsNotCaught() {
    LeaseToken token = lease(EP, "A");
    DeliveryId delivery = queued(EP, "BK-1");
    // the key of the sealed bytes holds other bytes
    evidence.put(
        new EvidenceStore.PutRequest(
            SealedKeys.of(Sha256.of(BYTES)),
            "not the sealed bytes".getBytes(UTF_8),
            "application/xml",
            FIVE_YEARS.retentionFor("sealed", START)));

    assertThatThrownBy(() -> sealing.seal(request(EP, token, delivery, "BK-1", BYTES)))
        .isInstanceOf(EvidenceIntegrityException.class);

    assertThat(ledger.delivery(delivery).orElseThrow().state()).isEqualTo(DeliveryState.QUEUED);
    assertThat(manifest()).isEmpty();
  }

  private JournalObject intentObjectAt(EndpointKey endpoint, long attempt, Instant at, String bk) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("attempt_id", attempt);
    payload.put("delivery_id", 1L);
    payload.put("business_key", bk);
    payload.put("trn", "TRN-" + bk);
    payload.put("version_seq", 1L);
    payload.put("action", "NEWT");
    payload.put("logical_hash", Sha256.ofUtf8("logical-" + bk).hex());
    payload.put("artifact_sha256", Sha256.of(BYTES).hex());
    payload.put("file_name", "file-" + bk + ".xml");
    payload.put("context_ids", List.of(Sha256.ofUtf8("ctx-1").hex(), Sha256.ofUtf8("ctx-2").hex()));
    return new JournalObject(
        intentKey(endpoint, 0, attempt), JournalType.DELIVERY_INTENT, 1, at, payload);
  }

  @Test
  void anIntentObjectThatExistsUnderTheKeyWithOtherBytesIsAnAlarm() {
    LeaseToken token = lease(EP, "A");
    DeliveryId delivery = queued(EP, "BK-1");
    // the same payload under the key of the next attempt id, written at another time: the writer
    // calls that success, the manifest could only name bytes that are not the stored ones
    new JournalWriter(evidence, FIVE_YEARS)
        .write(intentObjectAt(EP, 1, START.minusSeconds(60), "BK-1"));

    assertThatThrownBy(() -> sealing.seal(request(EP, token, delivery, "BK-1", BYTES)))
        .isInstanceOf(EvidenceIntegrityException.class);

    assertThat(ledger.delivery(delivery).orElseThrow().state()).isEqualTo(DeliveryState.QUEUED);
    assertThat(manifest()).isEmpty();
  }

  @Test
  void anIntentObjectThatExistsUnderTheKeyWithTheSameBytesIsAcceptedAndRecorded() {
    LeaseToken token = lease(EP, "A");
    DeliveryId delivery = queued(EP, "BK-1");
    new JournalWriter(evidence, FIVE_YEARS).write(intentObjectAt(EP, 1, START, "BK-1"));

    SealOutcome outcome = sealing.seal(request(EP, token, delivery, "BK-1", BYTES));

    assertThat(outcome.status()).isEqualTo(SealStatus.SEALED);
    EvidenceKey key = intentKey(EP, 0, 1).evidenceKey();
    assertThat(ledger.manifestRow(key).orElseThrow().entry().sha256())
        .isEqualTo(evidence.get(key).orElseThrow().sha256());
  }

  @Test
  void theSameBytesSealedForTwoAttemptsShareOneSealedObject() {
    LeaseToken token = lease(EP, "A");
    DeliveryId a = queued(EP, "BK-A");
    DeliveryId b = queued(EP, "BK-B");

    SealOutcome first = sealing.seal(request(EP, token, a, "BK-A", BYTES));
    SealOutcome second = sealing.seal(request(EP, token, b, "BK-B", BYTES));

    assertThat(first.status()).isEqualTo(SealStatus.SEALED);
    assertThat(second.status()).isEqualTo(SealStatus.SEALED);
    EvidenceKey sealedKey = SealedKeys.of(Sha256.of(BYTES));
    assertThat(keys()).hasSize(3).contains(sealedKey.path());
    assertThat(manifest()).hasSize(3);
    // the first row of the object wins: it names the first attempt
    assertThat(ledger.manifestRow(sealedKey).orElseThrow().attempt()).isEqualTo(first.attempt());
    assertThat(first.attempt().orElseThrow()).isNotEqualTo(second.attempt().orElseThrow());
  }

  @Test
  void sealCallsTheLedgerAndTheStoreInTheDocumentedOrder() {
    FaultInjector faults = new FaultInjector(FaultPlan.none());
    LedgerStore tracedLedger = faults.wrap(LedgerStore.class, ledger);
    EvidenceStore tracedEvidence = faults.wrap(EvidenceStore.class, evidence);
    DurableSeal seal =
        new DurableSeal(
            tracedLedger,
            tracedEvidence,
            clock,
            FIVE_YEARS,
            new SendGate(SendGate.Requirement.none()));
    LeaseToken token = lease(EP, "A");
    DeliveryId delivery = queued(EP, "BK-1");

    assertThat(seal.seal(request(EP, token, delivery, "BK-1", BYTES)).status())
        .isEqualTo(SealStatus.SEALED);

    assertThat(faults.trace().stream().filter(line -> line.contains(":BEFORE")).toList())
        .containsExactly(
            "LedgerStore.delivery:BEFORE#1",
            "LedgerStore.allocateAttemptId:BEFORE#1",
            "EvidenceStore.put:BEFORE#1",
            "EvidenceStore.put:BEFORE#2",
            "LedgerStore.beginSending:BEFORE#1");
  }

  // ---------------------------------------------------------------------------------------------
  // the gate in front of the first byte

  private AttemptId sealed(LeaseToken token) {
    DeliveryId delivery = queued(EP, "BK-1");
    return sealing.seal(request(EP, token, delivery, "BK-1", BYTES)).attempt().orElseThrow();
  }

  private boolean commitIssued(AttemptId attempt) {
    return ledger.attempt(attempt).orElseThrow().commitIssued();
  }

  @Test
  void beforeFirstByteIsClearOnlyAfterTheMarkerCas() {
    LeaseToken token = lease(EP, "A");
    AttemptId attempt = sealed(token);
    assertThat(commitIssued(attempt)).isFalse();

    assertThat(sealing.beforeFirstByte(EP, attempt, token)).isEqualTo(PreSendStatus.CLEAR);

    assertThat(commitIssued(attempt)).isTrue();
  }

  @Test
  void aSecondGateForTheSameAttemptIsRefused() {
    FaultInjector faults = new FaultInjector(FaultPlan.none());
    DurableSeal traced =
        new DurableSeal(
            faults.wrap(LedgerStore.class, ledger),
            evidence,
            clock,
            FIVE_YEARS,
            new SendGate(SendGate.Requirement.none()));
    LeaseToken token = lease(EP, "A");
    AttemptId attempt = sealed(token);

    assertThat(traced.beforeFirstByte(EP, attempt, token)).isEqualTo(PreSendStatus.CLEAR);
    int callsAfterTheFirst = faults.trace().size();
    assertThat(traced.beforeFirstByte(EP, attempt, token)).isEqualTo(PreSendStatus.MARKER_REFUSED);

    // the second gate read the attempt and touched nothing else of the ledger
    List<String> second = faults.trace().subList(callsAfterTheFirst, faults.trace().size());
    assertThat(second)
        .extracting(line -> line.substring(0, line.indexOf(':')))
        .containsOnly("LedgerStore.currentLease", "LedgerStore.attempt");
    assertThat(commitIssued(attempt)).isTrue();
  }

  @Test
  void anUnknownAttemptIsRefusedAtTheMarker() {
    LeaseToken token = lease(EP, "A");
    sealed(token);

    assertThat(sealing.beforeFirstByte(EP, new AttemptId(999), token))
        .isEqualTo(PreSendStatus.MARKER_REFUSED);
  }

  @Test
  void aDeliveryThatLeftSendingRefusesTheMarker() {
    // the lease, the epoch and the gate are all fine; the fenced compare-and-set is the judge
    LeaseToken token = lease(EP, "A");
    DeliveryId delivery = queued(EP, "BK-1");
    AttemptId attempt =
        sealing.seal(request(EP, token, delivery, "BK-1", BYTES)).attempt().orElseThrow();
    assertThat(ledger.transition(delivery, DeliveryState.SENDING, DeliveryState.IN_DOUBT))
        .isEqualTo(TransitionStatus.APPLIED);

    assertThat(sealing.beforeFirstByte(EP, attempt, token)).isEqualTo(PreSendStatus.MARKER_REFUSED);

    assertThat(commitIssued(attempt)).isFalse();
  }

  @Test
  void aLeaseTakenOverAfterTheSealRefusesTheMarker() {
    LeaseToken token = lease(EP, "A");
    AttemptId attempt = sealed(token);
    clock.advance(TTL);
    assertThat(lease(EP, "B")).isNotEqualTo(token);

    assertThat(sealing.beforeFirstByte(EP, attempt, token)).isEqualTo(PreSendStatus.STALE_LEASE);

    assertThat(commitIssued(attempt)).isFalse();
  }

  @Test
  void aReleasedLeaseRefusesTheMarker() {
    LeaseToken token = lease(EP, "A");
    AttemptId attempt = sealed(token);
    ledger.release(EP, token);

    assertThat(sealing.beforeFirstByte(EP, attempt, token)).isEqualTo(PreSendStatus.STALE_LEASE);

    assertThat(commitIssued(attempt)).isFalse();
  }

  @Test
  void aMarkerOfAnotherEpochRefusesTheMarkerOfTheAttempt() {
    LeaseToken token = lease(EP, "A");
    AttemptId attempt = sealed(token);
    new EpochMarker(evidence, FIVE_YEARS, clock).declare(7);

    assertThat(sealing.beforeFirstByte(EP, attempt, token))
        .isEqualTo(PreSendStatus.EPOCH_MARKER_DIFFERS);

    assertThat(commitIssued(attempt)).isFalse();
  }

  @Test
  void aMarkerOfTheTokensEpochLetsTheSendThrough() {
    ledger.declareRestoreEpoch(2);
    new EpochMarker(evidence, FIVE_YEARS, clock).declare(2);
    LeaseToken token = lease(EP, "A");
    assertThat(token.restoreEpoch()).isEqualTo(2);
    AttemptId attempt = sealed(token);

    assertThat(sealing.beforeFirstByte(EP, attempt, token)).isEqualTo(PreSendStatus.CLEAR);

    assertThat(commitIssued(attempt)).isTrue();
  }

  @Test
  void aLowerMarkerThanTheTokensEpochRefusesTheSend() {
    ledger.declareRestoreEpoch(2);
    new EpochMarker(evidence, FIVE_YEARS, clock).declare(1);
    LeaseToken token = lease(EP, "A");
    AttemptId attempt = sealed(token);

    assertThat(sealing.beforeFirstByte(EP, attempt, token))
        .isEqualTo(PreSendStatus.EPOCH_MARKER_DIFFERS);

    assertThat(commitIssued(attempt)).isFalse();
  }

  @Test
  void anUnreadableMarkerOrAnUnreachableStoreFailsClosed() {
    LeaseToken token = lease(EP, "A");
    AttemptId attempt = sealed(token);
    // a marker object that names another epoch than its key
    evidence.put(
        new EvidenceStore.PutRequest(
            EpochMarker.key(5),
            "{\"epoch\":6}".getBytes(UTF_8),
            "text/plain",
            FIVE_YEARS.retentionFor("epoch", START)));
    assertThat(sealing.beforeFirstByte(EP, attempt, token))
        .isEqualTo(PreSendStatus.EPOCH_MARKER_UNREADABLE);
    assertThat(commitIssued(attempt)).isFalse();

    // an outage is not "no marker": with the epoch of the token at 0, "no marker" would be CLEAR
    InMemoryEvidenceStore fresh = new InMemoryEvidenceStore(clock);
    DurableSeal onFresh =
        new DurableSeal(
            ledger, fresh, clock, FIVE_YEARS, new SendGate(SendGate.Requirement.none()));
    fresh.simulateOutage(true);
    assertThat(onFresh.beforeFirstByte(EP, attempt, token))
        .isEqualTo(PreSendStatus.EPOCH_MARKER_UNREADABLE);
    assertThat(commitIssued(attempt)).isFalse();
  }

  @Test
  void aClosedGateRefusesTheMarkerToo() {
    LeaseToken token = lease(EP, "A");
    AttemptId attempt = sealed(token);
    DurableSeal production =
        new DurableSeal(
            ledger, evidence, clock, FIVE_YEARS, new SendGate(SendGate.Requirement.production()));

    assertThat(production.beforeFirstByte(EP, attempt, token)).isEqualTo(PreSendStatus.GATE_CLOSED);

    assertThat(commitIssued(attempt)).isFalse();
  }

  @Test
  void aNullRetentionPolicyOrGateIsRefused() {
    SendGate gate = new SendGate(SendGate.Requirement.none());
    assertThatThrownBy(() -> new DurableSeal(ledger, evidence, clock, null, gate))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new DurableSeal(ledger, evidence, clock, FIVE_YEARS, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new DurableSeal(null, evidence, clock, FIVE_YEARS, gate))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new DurableSeal(ledger, null, clock, FIVE_YEARS, gate))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new DurableSeal(ledger, evidence, null, FIVE_YEARS, gate))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void nullArgumentsOfTheTwoCallsAreRefused() {
    LeaseToken token = lease(EP, "A");
    assertThatThrownBy(() -> sealing.seal(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> sealing.beforeFirstByte(null, new AttemptId(1), token))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> sealing.beforeFirstByte(EP, null, token))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> sealing.beforeFirstByte(EP, new AttemptId(1), null))
        .isInstanceOf(NullPointerException.class);
  }
}
