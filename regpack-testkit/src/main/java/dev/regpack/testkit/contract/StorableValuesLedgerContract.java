package dev.regpack.testkit.contract;

import static dev.regpack.testkit.contract.LedgerFixtures.batchId;
import static dev.regpack.testkit.contract.LedgerFixtures.bodyAt;
import static dev.regpack.testkit.contract.LedgerFixtures.checkpoint;
import static dev.regpack.testkit.contract.LedgerFixtures.commit;
import static dev.regpack.testkit.contract.LedgerFixtures.decision;
import static dev.regpack.testkit.contract.LedgerFixtures.endpoint;
import static dev.regpack.testkit.contract.LedgerFixtures.entry;
import static dev.regpack.testkit.contract.LedgerFixtures.newVersion;
import static dev.regpack.testkit.contract.LedgerFixtures.reportDecision;
import static dev.regpack.testkit.contract.LedgerFixtures.subjectKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.BatchId;
import dev.regpack.model.Checkpoint;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.ports.ledger.DecisionHeadConflictException;
import dev.regpack.ports.ledger.DecisionLedger.BatchCommit;
import dev.regpack.ports.ledger.DecisionLedger.CommitStatus;
import dev.regpack.ports.ledger.DecisionLedger.Committed;
import dev.regpack.ports.ledger.DeliveryLedger;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptIntent;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.DeliveryLedger.NewDelivery;
import dev.regpack.ports.ledger.IngestLedger.Commit;
import dev.regpack.ports.ledger.IngestLedger.Disposition;
import dev.regpack.ports.ledger.IngestLedger.Entry;
import dev.regpack.ports.ledger.IngestLedger.EntryResult;
import dev.regpack.ports.ledger.IngestLedger.Result;
import dev.regpack.ports.ledger.LeaseLedger;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseGrant;
import dev.regpack.ports.ledger.LeaseLedger.LeaseResult;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.ManifestLedger.ManifestRow;
import dev.regpack.ports.ledger.ManifestLedger.ObjectClass;
import dev.regpack.ports.ledger.ManifestLedger.ObjectEntry;
import dev.regpack.ports.ledger.OutboxLedger.OutboxKind;
import dev.regpack.ports.ledger.ReportLedger.NewVersion;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommit;
import dev.regpack.ports.ledger.ReportLedger.ReportableDecision;
import dev.regpack.ports.ledger.ReportLedger.TrnProposal;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * Contract every {@link LedgerStore} adapter must satisfy for the values that the real store cannot
 * hold or would silently change (FP2). Both adapters refuse them the same way, with an {@link
 * IllegalArgumentException} that does not echo the value, before anything is written:
 *
 * <ul>
 *   <li>a control character (U+0000 included), an unpaired surrogate or an over-long text in any
 *       text that becomes a key, an identity, a name, a holder, a business key, an action or a file
 *       name (the rule is {@code KeyText} of the model; the value types apply it);
 *   <li>a decision whose {@code asAt} lies outside [2000-01-01, 2200-01-01) UTC;
 *   <li>a lease ttl above {@link LeaseLedger#MAX_TTL}.
 * </ul>
 *
 * It also pins that the UTC day of {@code asAt} is part of the idempotency key of a decision (the
 * schema keeps it: D-S3).
 */
public interface StorableValuesLedgerContract {
  String SRC = "srcA";
  String MIFIR = "mifir.eu.rts22";
  Instant EVAL = Instant.parse("2026-10-09T10:00:00Z");

  /** A fresh, empty ledger for each test. */
  LedgerStore ledger();

  /** The clock the ledger reads. */
  ManualClock clock();

  /** One call that must be refused, with a name for the failure message. */
  record Probe(String name, ThrowingCallable call) {}

  private static String chars(int... codeUnits) {
    StringBuilder text = new StringBuilder();
    for (int unit : codeUnits) {
      text.append((char) unit);
    }
    return text.toString();
  }

  /** Every text of the ledger API, hostile in turn, as calls that must throw. */
  private List<Probe> probes(String h) {
    EndpointKey ep = endpoint("1");
    List<Probe> p = new ArrayList<>();
    // ingest
    p.add(new Probe("ingest key source id", () -> new IngestKey(h, "r1", 1)));
    p.add(new Probe("ingest key record id", () -> new IngestKey(SRC, h, 1)));
    p.add(new Probe("event identity part 1", () -> EventIdentity.of(h, "XPAR", "e1")));
    p.add(new Probe("event identity part 3", () -> EventIdentity.of("LEI1", "XPAR", h)));
    p.add(new Probe("event identity value", () -> new EventIdentity(h)));
    p.add(new Probe("batch id", () -> new BatchId(h)));
    p.add(new Probe("checkpoint stream", () -> new Checkpoint(h, new byte[] {1})));
    p.add(
        new Probe(
            "commit source id",
            () -> new Commit(batchId("B1"), h, List.of(), Optional.of(checkpoint(1)))));
    p.add(
        new Probe(
            "commitIngest with a hostile record id after a good one",
            () ->
                ledger()
                    .commitIngest(
                        commit(
                            "B1",
                            SRC,
                            checkpoint(1),
                            entry(SRC, "r0", 1, "A"),
                            entry(SRC, h, 1, "B")))));
    p.add(new Probe("latestCheckpoint source", () -> ledger().latestCheckpoint(h, "trades")));
    p.add(new Probe("latestCheckpoint stream", () -> ledger().latestCheckpoint(SRC, h)));
    // decisions
    p.add(
        new Probe(
            "commitDecisions with a hostile event after a good one",
            () ->
                ledger()
                    .commitDecisions(
                        new BatchCommit(
                            List.of(
                                decision(
                                    LedgerFixtures.body(
                                        "E2", MIFIR, "ctx", "d1", Outcome.REPORT, EVAL),
                                    0),
                                decision(
                                    LedgerFixtures.body(
                                        h, MIFIR, "ctx", "d1", Outcome.REPORT, EVAL),
                                    0)),
                            Optional.empty()))));
    p.add(new Probe("context engine build", () -> LedgerFixtures.context(h)));
    // reports
    p.add(new Probe("version executing LEI", () -> reportOf(version(h, "NEWT", "KEY-1", ep))));
    p.add(new Probe("version action", () -> reportOf(version("LEI-X", h, "KEY-1", ep))));
    p.add(new Probe("TRN key id", () -> reportOf(version("LEI-X", "NEWT", h, ep))));
    p.add(
        new Probe(
            "endpoint environment",
            () ->
                reportOf(version("LEI-X", "NEWT", "KEY-1", new EndpointKey(h, "ARM", "L", "N")))));
    p.add(
        new Probe(
            "endpoint route",
            () -> reportOf(version("LEI-X", "NEWT", "KEY-1", new EndpointKey("E", h, "L", "N")))));
    p.add(
        new Probe(
            "endpoint LEI",
            () ->
                reportOf(version("LEI-X", "NEWT", "KEY-1", new EndpointKey("E", "ARM", h, "N")))));
    p.add(
        new Probe(
            "endpoint authority",
            () ->
                reportOf(version("LEI-X", "NEWT", "KEY-1", new EndpointKey("E", "ARM", "L", h)))));
    p.add(new Probe("subjectByTrn LEI", () -> ledger().subjectByTrn(h, "TRN1")));
    p.add(new Probe("subjectByTrn TRN", () -> ledger().subjectByTrn("LEI-X", h)));
    // leases and deliveries
    p.add(new Probe("lease holder", () -> ledger().acquire(ep, h, Duration.ofSeconds(60))));
    p.add(
        new Probe(
            "delivery business key", () -> ledger().createDelivery(new NewDelivery(ep, 1, h, 1))));
    p.add(new Probe("attempt file name", () -> new AttemptIntent(Sha256.ofUtf8("a"), h)));
    return p;
  }

  private static NewVersion version(
      String executingLei, String action, String keyId, EndpointKey endpoint) {
    return newVersion(
        subjectKey(LedgerFixtures.MIFIR_REGIME, "E1", Leg.MARKET),
        executingLei,
        Optional.of(new TrnProposal("TRN1", keyId)),
        action,
        "h1",
        endpoint,
        1);
  }

  /** A reportable unit of a good REPORT decision for E1 and the given version. */
  private List<?> reportOf(NewVersion version) {
    return ledger()
        .commitReportable(
            new ReportableCommit(
                List.of(
                    new ReportableDecision(
                        reportDecision("E1", "ctx", "d1", Leg.MARKET), List.of(version))),
                Optional.empty(),
                Optional.empty()));
  }

  /** What a refused call must not have changed: the ledger is as fresh as before. */
  private void assertNothingWasWritten() {
    LedgerStore ledger = ledger();
    assertThat(ledger.ingestResult(batchId("B1"))).isEmpty();
    assertThat(ledger.pendingOutbox(OutboxKind.DECIDE_BATCH, 10)).isEmpty();
    assertThat(ledger.findIngest(new IngestKey(SRC, "r0", 1))).isEmpty();
    assertThat(ledger.latestCheckpoint(SRC, "trades")).isEmpty();
    for (String event : new String[] {"E1", "E2"}) {
      assertThat(ledger.decisionChain(new Regime(MIFIR), EventIdentity.of("LEI1", "XPAR", event)))
          .as("chain of " + event)
          .isEmpty();
    }
    assertThat(ledger.subjectByTrn("LEI-X", "TRN1")).isEmpty();
    assertThat(ledger.currentLease(endpoint("1"))).isEmpty();
    // and the counters did not move: the first real use of each thing is the first
    Result first = ledger.commitIngest(commit("B1", SRC, checkpoint(1), entry(SRC, "r0", 1, "A")));
    assertThat(first.redelivery()).isFalse();
    assertThat(first.entries().get(0).disposition()).isEqualTo(Disposition.NEW_VERSION);
    LeaseResult lease = ledger.acquire(endpoint("1"), "A", Duration.ofSeconds(60));
    assertThat(lease).isInstanceOf(LeaseResult.Granted.class);
    assertThat(((LeaseResult.Granted) lease).grant().token()).isEqualTo(new LeaseToken(0, 1));
  }

  private void assertRefusedEverywhere(String... hostile) {
    for (String h : hostile) {
      for (Probe probe : probes(h)) {
        assertThatThrownBy(probe.call())
            .as(probe.name() + " with a hostile text of " + h.length() + " chars")
            .isInstanceOf(IllegalArgumentException.class)
            .message()
            .doesNotContain(h);
      }
    }
    assertNothingWasWritten();
  }

  /**
   * U+0000 cannot be stored in a PostgreSQL text (SQLSTATE 22021) and would fail the batch on every
   * redelivery; the other C0 controls and DEL are refused with it (decision FP2).
   */
  @Test
  default void aNulInAnyKeyTextIsRejectedBeforeAnyWrite() {
    assertRefusedEverywhere(
        "a" + chars(0) + "b",
        chars(0) + "lead",
        "trail" + chars(0),
        "a" + chars(9) + "b",
        "a" + chars(0x1B) + "b",
        "a" + chars(0x7F) + "b");
  }

  /** The driver would send an unpaired surrogate as {@code ?}: two ids would become one key. */
  @Test
  default void aLoneSurrogateIsRejectedBeforeAnyWrite() {
    assertRefusedEverywhere(
        "a" + chars(0xD800) + "b",
        "a" + chars(0xDC00) + "b",
        "end" + chars(0xD83D),
        chars(0xDE00, 0xD83D) + "reversed");
  }

  /** A btree entry of the real store is limited to about 2.7 KB; every text is bounded. */
  @Test
  default void anOverLongKeyIsRejectedBeforeAnyWrite() {
    assertRefusedEverywhere("x".repeat(1000), "€".repeat(1000));
  }

  /** A well-formed supplementary character is kept as it is and never collapses with {@code ?}. */
  @Test
  default void aSupplementaryCharacterIsStoredAsItIsAndNeverCollapsesWithAQuestionMark() {
    String emoji = new String(Character.toChars(0x1F600));
    IngestKey withEmoji = new IngestKey(SRC, "a" + emoji + "b", 1);
    IngestKey withQuestionMark = new IngestKey(SRC, "a?b", 1);
    Result result =
        ledger()
            .commitIngest(
                commit(
                    "B1",
                    SRC,
                    checkpoint(1),
                    entry(SRC, withEmoji.sourceRecordId(), 1, "A"),
                    entry(SRC, withQuestionMark.sourceRecordId(), 1, "B")));
    assertThat(result.entries())
        .extracting(EntryResult::disposition)
        .containsExactly(Disposition.NEW_VERSION, Disposition.NEW_VERSION);
    assertThat(ledger().findIngest(withEmoji).orElseThrow().contentHash())
        .isEqualTo(Sha256.ofUtf8("A"));
    assertThat(ledger().findIngest(withEmoji).orElseThrow().key()).isEqualTo(withEmoji);
    assertThat(ledger().findIngest(withQuestionMark).orElseThrow().contentHash())
        .isEqualTo(Sha256.ofUtf8("B"));
  }

  /**
   * The longest values the model accepts are stored and read back, so the bounds sit below the
   * index-row limit of the real store (three bytes per UTF-16 char at most).
   */
  @Test
  default void theLongestAcceptedKeysAreStoredAndReadBack() {
    String euro = "€";
    // ingest: a 256-char record id and a 512-char event identity ("LEI1|XPAR|" is 10 chars)
    IngestKey key = new IngestKey(SRC, euro.repeat(256), 1);
    EventIdentity longEvent = EventIdentity.of("LEI1", "XPAR", euro.repeat(502));
    assertThat(longEvent.value()).hasSize(512);
    Result ingested =
        ledger()
            .commitIngest(
                new Commit(
                    batchId("B1"),
                    SRC,
                    List.of(new Entry(key, longEvent, Sha256.ofUtf8("A"))),
                    Optional.of(checkpoint(1))));
    assertThat(ingested.entries().get(0).disposition()).isEqualTo(Disposition.NEW_VERSION);
    assertThat(ledger().findIngest(key).orElseThrow().eventIdentity()).isEqualTo(longEvent);

    // decisions: the widest composite row is the idempotency key of a decision
    DecisionBody body = bodyAt(euro.repeat(502), MIFIR, "ctx", "d1", Outcome.REPORT, EVAL, EVAL);
    Committed committed =
        ledger()
            .commitDecisions(new BatchCommit(List.of(decision(body, 0)), Optional.empty()))
            .get(0);
    assertThat(committed.status()).isEqualTo(CommitStatus.INSERTED);
    assertThat(ledger().decisionChain(new Regime(MIFIR), longEvent))
        .containsExactly(committed.record());
    assertThat(
            ledger()
                .commitDecisions(new BatchCommit(List.of(decision(body, 1)), Optional.empty()))
                .get(0)
                .status())
        .isEqualTo(CommitStatus.NOOP_REDELIVERY);

    // endpoint (four texts of 128 chars in one index row), holder and business key
    EndpointKey wide =
        new EndpointKey(euro.repeat(128), euro.repeat(128), euro.repeat(128), euro.repeat(128));
    LeaseGrant grant =
        ((LeaseResult.Granted) ledger().acquire(wide, euro.repeat(256), Duration.ofSeconds(60)))
            .grant();
    assertThat(ledger().currentLease(wide)).contains(grant);
    String businessKey = euro.repeat(512);
    DeliveryId id = ledger().createDelivery(new NewDelivery(wide, 1, businessKey, 1));
    assertThat(ledger().delivery(id).orElseThrow().key().businessKey()).isEqualTo(businessKey);
  }

  /**
   * EF22: the widest manifest values go through the sending unit as well: an attempt intent with
   * the most objects it may name, one of them under the longest key and with the largest size, and
   * every class of object.
   */
  @Test
  default void aSendingUnitStoresEightManifestObjectsUnderTheLongestKeyAndTheLargestSize() {
    EndpointKey ep = endpoint("W");
    LeaseToken token =
        ((LeaseResult.Granted) ledger().acquire(ep, "holder", Duration.ofSeconds(60)))
            .grant()
            .token();
    DeliveryId delivery = ledger().createDelivery(new NewDelivery(ep, 1, "bk-wide", 1));
    ledger().transition(delivery, DeliveryState.PREPARED, DeliveryState.QUEUED);
    List<ObjectEntry> objects = new ArrayList<>();
    objects.add(
        new ObjectEntry(
            LedgerFixtures.maximumLengthKey(),
            ObjectClass.JOURNAL_A,
            Sha256.ofUtf8("wide"),
            1_000_000_000_000L));
    ObjectClass[] classes = ObjectClass.values();
    for (int i = 1; i < DeliveryLedger.MAX_INTENT_OBJECTS; i++) {
      objects.add(LedgerFixtures.objectEntry("wide/" + i, classes[i % classes.length], "h" + i, i));
    }
    assertThat(objects).hasSize(8);
    var attempt = ledger().allocateAttemptId();

    assertThat(
            ledger()
                .beginSending(
                    delivery, attempt, token, new AttemptIntent(Sha256.ofUtf8("a"), "f", objects))
                .attempt())
        .contains(attempt);

    for (ObjectEntry object : objects) {
      ManifestRow row = ledger().manifestRow(object.key()).orElseThrow();
      assertThat(row.entry()).isEqualTo(object);
      assertThat(row.attempt()).contains(attempt);
    }
  }

  /** A lone surrogate in a text of the body has no canonical JSON: refused by both adapters. */
  @Test
  default void aLoneSurrogateInABodyTextIsRejectedBeforeAnyWrite() {
    DecisionBody good = bodyAt("E1", MIFIR, "ctx", "d1", Outcome.REPORT, EVAL, EVAL);
    DecisionBody bad =
        new DecisionBody(
            good.eventIdentity(),
            good.regime(),
            good.decisionContextId(),
            good.asAt(),
            good.evaluatedAt(),
            good.outcome(),
            good.flags(),
            good.reasonCode(),
            "N" + chars(0xD800) + "08",
            good.ruleIdsHit(),
            good.path(),
            good.citations(),
            good.legs(),
            good.inputDigest(),
            good.attributes());
    DecisionBody other = bodyAt("E2", MIFIR, "ctx", "d1", Outcome.REPORT, EVAL, EVAL);
    assertThatThrownBy(
            () ->
                ledger()
                    .commitDecisions(
                        new BatchCommit(
                            List.of(decision(other, 0), decision(bad, 0)), Optional.empty())))
        .isInstanceOf(IllegalArgumentException.class)
        .message()
        .doesNotContain(chars(0xD800));
    assertThat(ledger().decisionChain(new Regime(MIFIR), EventIdentity.of("LEI1", "XPAR", "E1")))
        .isEmpty();
    assertThat(ledger().decisionChain(new Regime(MIFIR), EventIdentity.of("LEI1", "XPAR", "E2")))
        .isEmpty();
  }

  /**
   * The real store keeps one partition per UTC day between 2000-01-01 and 2199-12-31; every adapter
   * refuses an {@code asAt} outside that range with the same exception, for the whole commit,
   * before any write.
   */
  @Test
  default void anAsAtOutsideTheSupportedRangeIsRejectedBeforeAnyWrite() {
    Instant[] outside = {
      Instant.parse("1970-01-01T00:00:00Z"),
      Instant.parse("1999-12-31T23:59:59.999999Z"),
      Instant.parse("2200-01-01T00:00:00Z"),
      Instant.parse("9999-12-31T00:00:00Z")
    };
    DecisionBody good = bodyAt("E2", MIFIR, "ctx", "d1", Outcome.REPORT, EVAL, EVAL);
    for (Instant asAt : outside) {
      DecisionBody bad = bodyAt("E1", MIFIR, "ctx", "d1", Outcome.REPORT, EVAL, asAt);
      assertThatThrownBy(
              () ->
                  ledger()
                      .commitDecisions(
                          new BatchCommit(
                              List.of(decision(good, 0), decision(bad, 0)), Optional.empty())))
          .as("commitDecisions " + asAt)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("supported range");
      assertThatThrownBy(
              () ->
                  ledger()
                      .commitReportable(
                          new ReportableCommit(
                              List.of(
                                  new ReportableDecision(decision(good, 0), List.of()),
                                  new ReportableDecision(decision(bad, 0), List.of())),
                              Optional.empty(),
                              Optional.empty())))
          .as("commitReportable " + asAt)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("supported range");
    }
    assertThat(ledger().decisionChain(new Regime(MIFIR), EventIdentity.of("LEI1", "XPAR", "E1")))
        .isEmpty();
    assertThat(ledger().decisionChain(new Regime(MIFIR), EventIdentity.of("LEI1", "XPAR", "E2")))
        .isEmpty();
    // the first and the last microsecond of the range are inside it
    DecisionBody first =
        bodyAt(
            "E3", MIFIR, "ctx", "d1", Outcome.REPORT, EVAL, Instant.parse("2000-01-01T00:00:00Z"));
    DecisionBody last =
        bodyAt(
            "E4",
            MIFIR,
            "ctx",
            "d1",
            Outcome.REPORT,
            EVAL,
            Instant.parse("2199-12-31T23:59:59.999999Z"));
    List<Committed> committed =
        ledger()
            .commitDecisions(
                new BatchCommit(List.of(decision(first, 0), decision(last, 0)), Optional.empty()));
    assertThat(committed).extracting(Committed::status).containsOnly(CommitStatus.INSERTED);
  }

  /**
   * MODEL-2 / D-S3: the idempotency key holds the UTC day of {@code asAt}. The same event, context
   * and input digest on the next day is a new decision that needs the current head (the first
   * instant of the day and the last microsecond of the day before are two days).
   */
  @Test
  default void aSameKeyOnAnotherUtcDayIsANewDecisionNotARedelivery() {
    DecisionBody lastMicro =
        bodyAt(
            "E1",
            MIFIR,
            "ctx",
            "d1",
            Outcome.REPORT,
            EVAL,
            Instant.parse("2026-10-09T23:59:59.999999Z"));
    DecisionBody nextDay =
        bodyAt(
            "E1", MIFIR, "ctx", "d1", Outcome.REPORT, EVAL, Instant.parse("2026-10-10T00:00:00Z"));
    Committed first = commitOne(lastMicro, 0);
    Committed second = commitOne(nextDay, 1);
    assertThat(second.status()).isEqualTo(CommitStatus.INSERTED);
    assertThat(second.record().decisionSeq()).isEqualTo(2);
    assertThat(second.record().supersedesDecisionId()).contains(first.record().decisionId());
    EventIdentity event = EventIdentity.of("LEI1", "XPAR", "E1");
    assertThat(ledger().decisionChain(new Regime(MIFIR), event)).hasSize(2);

    // a third day with a stale expected head is a conflict, not a redelivery of either
    DecisionBody thirdDay =
        bodyAt(
            "E1", MIFIR, "ctx", "d1", Outcome.REPORT, EVAL, Instant.parse("2026-10-11T00:00:00Z"));
    assertThatThrownBy(() -> commitOne(thirdDay, 0))
        .isInstanceOfSatisfying(
            DecisionHeadConflictException.class, e -> assertThat(e.currentHeadSeq()).isEqualTo(2));
    assertThat(ledger().decisionChain(new Regime(MIFIR), event)).hasSize(2);
  }

  /** The other half of the key: two instants of one UTC day are one decision, a redelivery. */
  @Test
  default void aSameKeyOnTheSameUtcDayIsARedeliveryWhateverTheInstant() {
    DecisionBody early =
        bodyAt(
            "E1", MIFIR, "ctx", "d1", Outcome.REPORT, EVAL, Instant.parse("2026-10-09T00:00:00Z"));
    DecisionBody late =
        bodyAt(
            "E1",
            MIFIR,
            "ctx",
            "d1",
            Outcome.REPORT,
            EVAL,
            Instant.parse("2026-10-09T23:59:59.999999Z"));
    Committed first = commitOne(early, 0);
    Committed again = commitOne(late, 1);
    assertThat(again.status()).isEqualTo(CommitStatus.NOOP_REDELIVERY);
    assertThat(again.record()).isEqualTo(first.record());
    assertThat(ledger().decisionChain(new Regime(MIFIR), EventIdentity.of("LEI1", "XPAR", "E1")))
        .hasSize(1);
  }

  private Committed commitOne(DecisionBody body, long expectedHeadSeq) {
    List<Committed> committed =
        ledger()
            .commitDecisions(
                new BatchCommit(List.of(decision(body, expectedHeadSeq)), Optional.empty()));
    assertThat(committed).hasSize(1);
    return committed.get(0);
  }

  /**
   * A ttl above {@link LeaseLedger#MAX_TTL} would put the expiry outside the instants of the real
   * store; it is refused by {@code acquire} and {@code renew}, the maximum itself is granted.
   */
  @Test
  default void aLeaseTtlBeyondTheMaximumIsRejectedAndTheMaximumIsGranted() {
    EndpointKey ep = endpoint("1");
    Duration[] tooLong = {
      LeaseLedger.MAX_TTL.plus(Duration.ofNanos(1000)),
      Duration.ofDays(200_000_000L),
      Duration.ofSeconds(Long.MAX_VALUE)
    };
    for (Duration ttl : tooLong) {
      assertThatThrownBy(() -> ledger().acquire(ep, "A", ttl))
          .as("acquire " + ttl)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(ledger().currentLease(ep)).isEmpty();

    Instant start = clock().now();
    LeaseGrant grant =
        ((LeaseResult.Granted) ledger().acquire(ep, "A", LeaseLedger.MAX_TTL)).grant();
    assertThat(grant.token()).isEqualTo(new LeaseToken(0, 1));
    assertThat(grant.expiresAt()).isEqualTo(start.plus(LeaseLedger.MAX_TTL));
    for (Duration ttl : tooLong) {
      assertThatThrownBy(() -> ledger().renew(ep, grant.token(), ttl))
          .as("renew " + ttl)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(ledger().currentLease(ep)).contains(grant);
    assertThat(ledger().renew(ep, grant.token(), LeaseLedger.MAX_TTL)).isTrue();
  }
}
