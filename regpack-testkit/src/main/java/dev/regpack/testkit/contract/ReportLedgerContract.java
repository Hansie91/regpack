package dev.regpack.testkit.contract;

import static dev.regpack.model.Leg.ALLOCATION;
import static dev.regpack.model.Leg.CLIENT;
import static dev.regpack.model.Leg.MARKET;
import static dev.regpack.testkit.contract.LedgerFixtures.EMIR_REGIME;
import static dev.regpack.testkit.contract.LedgerFixtures.MIFIR_REGIME;
import static dev.regpack.testkit.contract.LedgerFixtures.endpoint;
import static dev.regpack.testkit.contract.LedgerFixtures.newVersion;
import static dev.regpack.testkit.contract.LedgerFixtures.reportBody;
import static dev.regpack.testkit.contract.LedgerFixtures.reportDecision;
import static dev.regpack.testkit.contract.LedgerFixtures.subjectKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.Regime;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionRecord;
import dev.regpack.ports.ledger.DecisionHeadConflictException;
import dev.regpack.ports.ledger.DecisionLedger.BatchCommit;
import dev.regpack.ports.ledger.DecisionLedger.CommitStatus;
import dev.regpack.ports.ledger.DecisionLedger.NewDecision;
import dev.regpack.ports.ledger.DeliveryLedger.Delivery;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.DeliveryLedger.NewDelivery;
import dev.regpack.ports.ledger.DeliveryLedger.TransitionStatus;
import dev.regpack.ports.ledger.DuplicateKeyException;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.OutboxLedger.OutboxId;
import dev.regpack.ports.ledger.OutboxLedger.OutboxItem;
import dev.regpack.ports.ledger.OutboxLedger.OutboxKind;
import dev.regpack.ports.ledger.OutboxLedger.OutboxState;
import dev.regpack.ports.ledger.ReportLedger.NewVersion;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommit;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommitted;
import dev.regpack.ports.ledger.ReportLedger.ReportableDecision;
import dev.regpack.ports.ledger.ReportLedger.StoredVersion;
import dev.regpack.ports.ledger.ReportLedger.Subject;
import dev.regpack.ports.ledger.ReportLedger.SubjectId;
import dev.regpack.ports.ledger.ReportLedger.SubjectKey;
import dev.regpack.ports.ledger.ReportLedger.TrnProposal;
import dev.regpack.ports.ledger.ReportLedger.VersionResult;
import dev.regpack.ports.ledger.ReportLedger.VersionStatus;
import dev.regpack.ports.ledger.TrnConflictException;
import dev.regpack.ports.ledger.WithdrawalRequiredException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Contract every {@link LedgerStore} adapter must satisfy for the report side of the ledger (spec
 * 10 and 11.1): subjects, the TRN, versions and the delivery in PREPARED, committed in the unit of
 * the decision. Unless stated, a unit is committed with {@code commitReportable}.
 */
public interface ReportLedgerContract {
  /** A fresh, empty ledger for each test. */
  LedgerStore ledger();

  private static EventIdentity event(String name) {
    return EventIdentity.of("LEI1", "XPAR", name);
  }

  private static Regime mifir() {
    return new Regime(MIFIR_REGIME);
  }

  private static SubjectKey key(String event, Leg leg) {
    return subjectKey(MIFIR_REGIME, event, leg);
  }

  private long headSeq(String event) {
    return ledger()
        .currentDecision(mifir(), event(event))
        .map(DecisionRecord::decisionSeq)
        .orElse(0L);
  }

  /** A REPORT decision on top of the current head of {@code event}, with the given versions. */
  private ReportableDecision next(
      String event, String digestTag, List<Leg> legs, NewVersion... versions) {
    NewDecision decision =
        reportDecision(headSeq(event), event, "ctx", digestTag, legs.toArray(new Leg[0]));
    return new ReportableDecision(decision, List.of(versions));
  }

  private List<ReportableCommitted> commitUnit(ReportableDecision... decisions) {
    return ledger()
        .commitReportable(
            new ReportableCommit(List.of(decisions), Optional.empty(), Optional.empty()));
  }

  /** One decision with the given versions, committed on top of the current head. */
  private VersionResult commitVersion(
      String event, String digestTag, List<Leg> legs, NewVersion version) {
    return commitUnit(next(event, digestTag, legs, version)).get(0).versions().get(0);
  }

  private void assertNothingStored(String event, Leg... legs) {
    assertThat(ledger().currentDecision(mifir(), event(event))).isEmpty();
    assertThat(ledger().decisionChain(mifir(), event(event))).isEmpty();
    for (Leg leg : legs) {
      assertThat(ledger().subject(key(event, leg))).isEmpty();
    }
  }

  @Test
  default void firstVersionCreatesSubjectTrnVersionOneAndAPreparedDelivery() {
    NewVersion v = newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1");
    ReportableCommitted c = commitUnit(next("E1", "d1", List.of(MARKET), v)).get(0);

    assertThat(c.decision().status()).isEqualTo(CommitStatus.INSERTED);
    VersionResult r = c.versions().get(0);
    assertThat(r.status()).isEqualTo(VersionStatus.CREATED);
    assertThat(r.versionSeq()).isEqualTo(1);
    assertThat(r.trn()).isEqualTo("TRN1");
    Subject s = ledger().subject(key("E1", MARKET)).orElseThrow();
    assertThat(s.id()).isEqualTo(r.subject());
    assertThat(s.key()).isEqualTo(key("E1", MARKET));
    assertThat(s.executingLei()).contains("EXEC1");
    assertThat(s.trn()).contains("TRN1");
    assertThat(s.trnKeyId()).contains("KEY-1");
    assertThat(s.lastVersionSeq()).isEqualTo(1);
    Delivery d = ledger().delivery(r.delivery().orElseThrow()).orElseThrow();
    assertThat(d.state()).isEqualTo(DeliveryState.PREPARED);
    assertThat(d.key()).isEqualTo(new NewDelivery(endpoint("EP1"), 1, "EXEC1|TRN1", 1));
  }

  @Test
  default void trnIsAssignedOnceAndSharedByNewtCancAndRenewt() {
    SubjectKey subject = key("E1", MARKET);
    VersionResult newt =
        commitVersion(
            "E1",
            "d1",
            List.of(MARKET),
            newVersion(
                subject, "EXEC1", Optional.of(new TrnProposal("TRN1", "KEY-1")), "NEWT", "h1"));
    VersionResult canc =
        commitVersion(
            "E1",
            "d2",
            List.of(MARKET),
            newVersion(
                subject, "EXEC1", Optional.of(new TrnProposal("TRN1", "KEY-2")), "CANC", "h2"));
    VersionResult renewt =
        commitVersion(
            "E1",
            "d3",
            List.of(MARKET),
            newVersion(
                subject, "EXEC1", Optional.of(new TrnProposal("TRN1", "KEY-3")), "NEWT", "h3"));

    assertThat(List.of(newt, canc, renewt))
        .extracting(VersionResult::versionSeq)
        .containsExactly(1L, 2L, 3L);
    assertThat(List.of(newt, canc, renewt)).extracting(VersionResult::trn).containsOnly("TRN1");
    assertThat(List.of(newt, canc, renewt))
        .extracting(VersionResult::subject)
        .containsOnly(newt.subject());
    assertThat(List.of(newt, canc, renewt))
        .extracting(VersionResult::status)
        .containsOnly(VersionStatus.CREATED);
    List<Delivery> deliveries = new ArrayList<>();
    for (VersionResult r : List.of(newt, canc, renewt)) {
      deliveries.add(ledger().delivery(r.delivery().orElseThrow()).orElseThrow());
    }
    assertThat(deliveries).extracting(Delivery::id).doesNotHaveDuplicates();
    assertThat(deliveries).extracting(d -> d.key().businessKey()).containsOnly("EXEC1|TRN1");
    assertThat(deliveries).extracting(d -> d.key().versionSeq()).containsExactly(1L, 2L, 3L);
    // the first key id is kept
    assertThat(ledger().subject(subject).orElseThrow().trnKeyId()).contains("KEY-1");
  }

  @Test
  default void aSecondSubjectWithTheSameTrnAndLeiIsRejectedAtomically() {
    commitVersion(
        "E1", "d1", List.of(MARKET), newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1"));

    // another endpoint, so that the delivery of the rejected version could be probed afterwards
    NewVersion clash =
        newVersion(
            key("E2", MARKET),
            "EXEC1",
            Optional.of(new TrnProposal("TRN1", "KEY-1")),
            "NEWT",
            "h2",
            endpoint("EP2"),
            1);
    ReportableDecision unit = next("E2", "d2", List.of(MARKET), clash);
    assertThatThrownBy(() -> commitUnit(unit))
        .isInstanceOfSatisfying(
            TrnConflictException.class,
            e -> {
              assertThat(e.executingLei()).isEqualTo("EXEC1");
              assertThat(e.trn()).isEqualTo("TRN1");
            });

    assertNothingStored("E2", MARKET);
    assertThat(ledger().subjectByTrn("EXEC1", "TRN1").orElseThrow().key())
        .isEqualTo(key("E1", MARKET));
    // no delivery of the rejected unit exists: creating that key succeeds
    assertThatCode(
            () -> ledger().createDelivery(new NewDelivery(endpoint("EP2"), 1, "EXEC1|TRN1", 1)))
        .doesNotThrowAnyException();
  }

  @Test
  default void theSameTrnUnderAnotherExecutingLeiIsAccepted() {
    VersionResult first =
        commitVersion(
            "E1", "d1", List.of(MARKET), newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1"));
    VersionResult second =
        commitVersion(
            "E2", "d2", List.of(MARKET), newVersion("E2", MARKET, "EXEC2", "TRN1", "NEWT", "h2"));

    assertThat(second.status()).isEqualTo(VersionStatus.CREATED);
    assertThat(second.subject()).isNotEqualTo(first.subject());
    assertThat(ledger().subjectByTrn("EXEC1", "TRN1").orElseThrow().id())
        .isEqualTo(first.subject());
    assertThat(ledger().subjectByTrn("EXEC2", "TRN1").orElseThrow().id())
        .isEqualTo(second.subject());
  }

  @Test
  default void aStoredTrnCannotBeReplacedByAProposal() {
    SubjectKey subject = key("E1", MARKET);
    commitVersion(
        "E1", "d1", List.of(MARKET), newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1"));

    ReportableDecision other =
        next(
            "E1",
            "d2",
            List.of(MARKET),
            newVersion(
                subject, "EXEC1", Optional.of(new TrnProposal("TRN9", "KEY-1")), "CANC", "h2"));
    assertThatThrownBy(() -> commitUnit(other)).isInstanceOf(IllegalArgumentException.class);
    assertThat(ledger().decisionChain(mifir(), event("E1"))).hasSize(1);
    assertThat(ledger().subject(subject).orElseThrow().trn()).contains("TRN1");
    assertThat(ledger().subject(subject).orElseThrow().lastVersionSeq()).isEqualTo(1);

    VersionResult sameString =
        commitVersion(
            "E1",
            "d3",
            List.of(MARKET),
            newVersion(
                subject, "EXEC1", Optional.of(new TrnProposal("TRN1", "KEY-7")), "CANC", "h3"));
    assertThat(sameString.status()).isEqualTo(VersionStatus.CREATED);
    assertThat(sameString.trn()).isEqualTo("TRN1");
    VersionResult empty =
        commitVersion(
            "E1",
            "d4",
            List.of(MARKET),
            newVersion(subject, "EXEC1", Optional.empty(), "NEWT", "h4"));
    assertThat(empty.status()).isEqualTo(VersionStatus.CREATED);
    assertThat(empty.trn()).isEqualTo("TRN1");
    assertThat(empty.versionSeq()).isEqualTo(3);
    assertThat(ledger().subject(subject).orElseThrow().trnKeyId()).contains("KEY-1");
  }

  @Test
  default void aStoredExecutingLeiCannotBeChangedByALaterVersion() {
    SubjectKey subject = key("E1", MARKET);
    commitVersion(
        "E1", "d1", List.of(MARKET), newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1"));

    ReportableDecision other =
        next(
            "E1",
            "d2",
            List.of(MARKET),
            newVersion(subject, "EXEC2", Optional.empty(), "CANC", "h2"));
    assertThatThrownBy(() -> commitUnit(other)).isInstanceOf(IllegalArgumentException.class);
    assertThat(ledger().decisionChain(mifir(), event("E1"))).hasSize(1);
  }

  @Test
  default void aNewSubjectNeedsATrnProposal() {
    ReportableDecision unit =
        next(
            "E1",
            "d1",
            List.of(MARKET),
            newVersion(key("E1", MARKET), "EXEC1", Optional.empty(), "NEWT", "h1"));
    assertThatThrownBy(() -> commitUnit(unit)).isInstanceOf(IllegalArgumentException.class);
    assertNothingStored("E1", MARKET);
  }

  @Test
  default void subjectKeyIsScopedByRegime() {
    NewVersion mifirVersion = newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1");
    NewVersion emirVersion =
        newVersion(
            subjectKey(EMIR_REGIME, "E1", MARKET),
            "EXEC1",
            Optional.of(new TrnProposal("TRN2", "KEY-1")),
            "NEWT",
            "h1");
    DecisionBody emirBody = reportBody(EMIR_REGIME, "E1", "ctx", "d1", MARKET);

    VersionResult a =
        commitUnit(next("E1", "d1", List.of(MARKET), mifirVersion)).get(0).versions().get(0);
    VersionResult b =
        ledger()
            .commitReportable(
                new ReportableCommit(
                    List.of(
                        new ReportableDecision(new NewDecision(emirBody, 0), List.of(emirVersion))),
                    Optional.empty(),
                    Optional.empty()))
            .get(0)
            .versions()
            .get(0);

    assertThat(a.subject()).isNotEqualTo(b.subject());
    assertThat(a.versionSeq()).isEqualTo(1);
    assertThat(b.versionSeq()).isEqualTo(1);
    assertThat(ledger().subject(key("E1", MARKET)).orElseThrow().id()).isEqualTo(a.subject());
    assertThat(ledger().subject(subjectKey(EMIR_REGIME, "E1", MARKET)).orElseThrow().id())
        .isEqualTo(b.subject());
  }

  @Test
  default void legsGetTheirOwnSubjectsAndChains() {
    List<Leg> legs = List.of(MARKET, CLIENT);
    List<VersionResult> first =
        commitUnit(
                next(
                    "E1",
                    "d1",
                    legs,
                    newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "m1"),
                    newVersion("E1", CLIENT, "EXEC1", "TRN2", "NEWT", "c1")))
            .get(0)
            .versions();
    assertThat(first).extracting(VersionResult::versionSeq).containsExactly(1L, 1L);
    assertThat(first.get(0).subject()).isNotEqualTo(first.get(1).subject());

    // only the market leg changes: its chain moves, the client chain does not
    VersionResult second =
        commitVersion("E1", "d2", legs, newVersion("E1", MARKET, "EXEC1", "TRN1", "CANC", "m2"));
    assertThat(second.subject()).isEqualTo(first.get(0).subject());
    assertThat(second.versionSeq()).isEqualTo(2);
    assertThat(ledger().subject(key("E1", MARKET)).orElseThrow().lastVersionSeq()).isEqualTo(2);
    assertThat(ledger().subject(key("E1", CLIENT)).orElseThrow().lastVersionSeq()).isEqualTo(1);
    assertThat(ledger().versions(first.get(0).subject())).hasSize(2);
    assertThat(ledger().versions(first.get(1).subject())).hasSize(1);
    assertThat(ledger().subject(key("E1", CLIENT)).orElseThrow().trn()).contains("TRN2");
  }

  @Test
  default void aVersionNeedsAReportDecisionAndAKnownLeg() {
    // a version on a DO_NOT_REPORT decision
    DecisionBody doNotReport =
        LedgerFixtures.body(
            "E1",
            MIFIR_REGIME,
            "ctx",
            "d1",
            Outcome.DO_NOT_REPORT,
            Instant.parse("2026-10-09T10:00:00Z"));
    ReportableDecision onDoNotReport =
        new ReportableDecision(
            new NewDecision(doNotReport, 0),
            List.of(newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1")));
    assertThatThrownBy(() -> commitUnit(onDoNotReport))
        .isInstanceOf(IllegalArgumentException.class);
    assertNothingStored("E1", MARKET);

    // a leg outside the legs of the decision
    ReportableDecision wrongLeg =
        next("E2", "d1", List.of(MARKET), newVersion("E2", CLIENT, "EXEC1", "TRN2", "NEWT", "h2"));
    assertThatThrownBy(() -> commitUnit(wrongLeg)).isInstanceOf(IllegalArgumentException.class);
    assertNothingStored("E2", MARKET, CLIENT);

    // two versions for one (decision, leg)
    ReportableDecision twice =
        next(
            "E3",
            "d1",
            List.of(MARKET),
            newVersion("E3", MARKET, "EXEC1", "TRN3", "NEWT", "h3"),
            newVersion("E3", MARKET, "EXEC1", "TRN3", "CANC", "h4"));
    assertThatThrownBy(() -> commitUnit(twice)).isInstanceOf(IllegalArgumentException.class);
    assertNothingStored("E3", MARKET);

    // a subject of another event
    ReportableDecision otherEvent =
        next("E4", "d1", List.of(MARKET), newVersion("E5", MARKET, "EXEC1", "TRN4", "NEWT", "h5"));
    assertThatThrownBy(() -> commitUnit(otherEvent)).isInstanceOf(IllegalArgumentException.class);
    assertNothingStored("E4", MARKET);
    assertNothingStored("E5", MARKET);

    // a subject of another regime
    ReportableDecision otherRegime =
        next(
            "E6",
            "d1",
            List.of(MARKET),
            newVersion(
                subjectKey(EMIR_REGIME, "E6", MARKET),
                "EXEC1",
                Optional.of(new TrnProposal("TRN5", "KEY-1")),
                "NEWT",
                "h6"));
    assertThatThrownBy(() -> commitUnit(otherRegime)).isInstanceOf(IllegalArgumentException.class);
    assertNothingStored("E6", MARKET);
    assertThat(ledger().subject(subjectKey(EMIR_REGIME, "E6", MARKET))).isEmpty();
    // and the TRNs of the rejected units were never taken
    assertThat(ledger().subjectByTrn("EXEC1", "TRN1")).isEmpty();
    assertThat(ledger().subjectByTrn("EXEC1", "TRN5")).isEmpty();
  }

  @Test
  default void redeliveryOfTheUnitIsANoopAndReturnsExistingVersions() {
    ReportableCommit commit =
        new ReportableCommit(
            List.of(
                next(
                    "E1",
                    "d1",
                    List.of(MARKET),
                    newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1"))),
            Optional.empty(),
            Optional.empty());
    ReportableCommitted first = ledger().commitReportable(commit).get(0);
    ReportableCommitted again = ledger().commitReportable(commit).get(0);

    assertThat(first.decision().status()).isEqualTo(CommitStatus.INSERTED);
    assertThat(again.decision().status()).isEqualTo(CommitStatus.NOOP_REDELIVERY);
    assertThat(again.decision().record()).isEqualTo(first.decision().record());
    VersionResult a = first.versions().get(0);
    VersionResult b = again.versions().get(0);
    assertThat(a.status()).isEqualTo(VersionStatus.CREATED);
    assertThat(b.status()).isEqualTo(VersionStatus.EXISTING);
    assertThat(b.subject()).isEqualTo(a.subject());
    assertThat(b.versionSeq()).isEqualTo(a.versionSeq());
    assertThat(b.trn()).isEqualTo(a.trn());
    assertThat(b.delivery()).isEqualTo(a.delivery());
    // row counts are unchanged
    assertThat(ledger().decisionChain(mifir(), event("E1"))).hasSize(1);
    assertThat(ledger().subject(key("E1", MARKET)).orElseThrow().lastVersionSeq()).isEqualTo(1);
    assertThat(ledger().versions(a.subject())).hasSize(1);
    assertThatThrownBy(
            () -> ledger().createDelivery(new NewDelivery(endpoint("EP1"), 1, "EXEC1|TRN1", 1)))
        .isInstanceOf(DuplicateKeyException.class);
    assertThatCode(
            () -> ledger().createDelivery(new NewDelivery(endpoint("EP1"), 1, "EXEC1|TRN1", 2)))
        .doesNotThrowAnyException();
  }

  @Test
  default void aRedeliveryOfASupersededDecisionCreatesNoVersion() {
    // decision 1 is committed without a version, then superseded by decision 2
    NewDecision first = reportDecision(0, "E1", "ctx", "d1", MARKET);
    ledger().commitDecisions(new BatchCommit(List.of(first), Optional.empty()));
    ledger()
        .commitDecisions(
            new BatchCommit(
                List.of(reportDecision(1, "E1", "ctx", "d2", MARKET)), Optional.empty()));

    ReportableCommit redelivery =
        new ReportableCommit(
            List.of(
                new ReportableDecision(
                    first, List.of(newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1")))),
            Optional.empty(),
            Optional.empty());
    assertThatThrownBy(() -> ledger().commitReportable(redelivery))
        .isInstanceOfSatisfying(
            DecisionHeadConflictException.class, e -> assertThat(e.currentHeadSeq()).isEqualTo(2));
    assertThat(ledger().subject(key("E1", MARKET))).isEmpty();
    assertThat(ledger().subjectByTrn("EXEC1", "TRN1")).isEmpty();
    assertThat(ledger().decisionChain(mifir(), event("E1"))).hasSize(2);
  }

  @Test
  default void logicalHashSuppressionComparesOnlyWithTheLatestNonRejectedVersion() {
    // A then A: the second is suppressed; the decision itself is still inserted
    VersionResult a1 =
        commitVersion(
            "E1", "d1", List.of(MARKET), newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "A"));
    List<ReportableCommitted> sameAgain =
        commitUnit(
            next(
                "E1",
                "d2",
                List.of(MARKET),
                newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "A")));
    assertThat(sameAgain.get(0).decision().status()).isEqualTo(CommitStatus.INSERTED);
    assertThat(sameAgain.get(0).decision().record().decisionSeq()).isEqualTo(2);
    VersionResult suppressed = sameAgain.get(0).versions().get(0);
    assertThat(suppressed.status()).isEqualTo(VersionStatus.SUPPRESSED_UNCHANGED);
    assertThat(suppressed.subject()).isEqualTo(a1.subject());
    assertThat(suppressed.versionSeq()).isEqualTo(1);
    assertThat(suppressed.delivery()).isEmpty();
    assertThat(ledger().versions(a1.subject())).hasSize(1);
    assertThat(ledger().subject(key("E1", MARKET)).orElseThrow().lastVersionSeq()).isEqualTo(1);

    // A, B, A: the third differs from the latest version and is created
    commitVersion(
        "E2", "d1", List.of(MARKET), newVersion("E2", MARKET, "EXEC1", "TRN2", "NEWT", "A"));
    VersionResult b =
        commitVersion(
            "E2", "d2", List.of(MARKET), newVersion("E2", MARKET, "EXEC1", "TRN2", "NEWT", "B"));
    VersionResult a2 =
        commitVersion(
            "E2", "d3", List.of(MARKET), newVersion("E2", MARKET, "EXEC1", "TRN2", "NEWT", "A"));
    assertThat(b.status()).isEqualTo(VersionStatus.CREATED);
    assertThat(a2.status()).isEqualTo(VersionStatus.CREATED);
    assertThat(a2.versionSeq()).isEqualTo(3);

    // the latest delivery is REJECTED: an equal hash is created
    VersionResult rejected =
        commitVersion(
            "E3", "d1", List.of(MARKET), newVersion("E3", MARKET, "EXEC1", "TRN3", "NEWT", "A"));
    assertThat(
            ledger()
                .transition(
                    rejected.delivery().orElseThrow(),
                    DeliveryState.PREPARED,
                    DeliveryState.REJECTED))
        .isEqualTo(TransitionStatus.APPLIED);
    VersionResult afterRejected =
        commitVersion(
            "E3", "d2", List.of(MARKET), newVersion("E3", MARKET, "EXEC1", "TRN3", "NEWT", "A"));
    assertThat(afterRejected.status()).isEqualTo(VersionStatus.CREATED);
    assertThat(afterRejected.versionSeq()).isEqualTo(2);

    // the latest delivery is IN_REPAIR (QUEUED, REJECTED, IN_REPAIR): an equal hash is created
    VersionResult repaired =
        commitVersion(
            "E4", "d1", List.of(MARKET), newVersion("E4", MARKET, "EXEC1", "TRN4", "NEWT", "A"));
    DeliveryId delivery = repaired.delivery().orElseThrow();
    assertThat(ledger().transition(delivery, DeliveryState.PREPARED, DeliveryState.QUEUED))
        .isEqualTo(TransitionStatus.APPLIED);
    assertThat(ledger().transition(delivery, DeliveryState.QUEUED, DeliveryState.REJECTED))
        .isEqualTo(TransitionStatus.APPLIED);
    assertThat(ledger().transition(delivery, DeliveryState.REJECTED, DeliveryState.IN_REPAIR))
        .isEqualTo(TransitionStatus.APPLIED);
    VersionResult afterRepair =
        commitVersion(
            "E4", "d2", List.of(MARKET), newVersion("E4", MARKET, "EXEC1", "TRN4", "NEWT", "A"));
    assertThat(afterRepair.status()).isEqualTo(VersionStatus.CREATED);
    assertThat(afterRepair.versionSeq()).isEqualTo(2);

    // the comparison includes the action: a CANC with the hash of the latest NEWT is a new version
    commitVersion(
        "E6", "d1", List.of(MARKET), newVersion("E6", MARKET, "EXEC1", "TRN6", "NEWT", "A"));
    VersionResult cancSameHash =
        commitVersion(
            "E6", "d2", List.of(MARKET), newVersion("E6", MARKET, "EXEC1", "TRN6", "CANC", "A"));
    assertThat(cancSameHash.status()).isEqualTo(VersionStatus.CREATED);
    assertThat(cancSameHash.versionSeq()).isEqualTo(2);
    assertThat(cancSameHash.delivery()).isPresent();

    // a live version is compared, not the dead one before it: B, then A rejected, then B again
    // is suppressed against B
    commitVersion(
        "E5", "d1", List.of(MARKET), newVersion("E5", MARKET, "EXEC1", "TRN5", "NEWT", "B"));
    VersionResult deadA =
        commitVersion(
            "E5", "d2", List.of(MARKET), newVersion("E5", MARKET, "EXEC1", "TRN5", "NEWT", "A"));
    assertThat(
            ledger()
                .transition(
                    deadA.delivery().orElseThrow(), DeliveryState.PREPARED, DeliveryState.REJECTED))
        .isEqualTo(TransitionStatus.APPLIED);
    VersionResult againB =
        commitVersion(
            "E5", "d3", List.of(MARKET), newVersion("E5", MARKET, "EXEC1", "TRN5", "NEWT", "B"));
    assertThat(againB.status()).isEqualTo(VersionStatus.SUPPRESSED_UNCHANGED);
    assertThat(againB.versionSeq()).isEqualTo(1);
  }

  /** An outbox item of kind DECIDE_BATCH, as a decision batch would complete it. */
  private OutboxId decideBatchItem() {
    return ledger()
        .commitIngest(
            LedgerFixtures.commit(
                "B1",
                "srcA",
                LedgerFixtures.checkpoint(1),
                LedgerFixtures.entry("srcA", "r1", 1, "A")))
        .outboxId()
        .orElseThrow();
  }

  private OutboxState outboxState(OutboxId id) {
    return ledger().outboxItem(id).orElseThrow().state();
  }

  @Test
  default void completesTheOutboxItemInTheSameUnit() {
    OutboxId id = decideBatchItem();
    assertThat(outboxState(id)).isEqualTo(OutboxState.PENDING);
    ReportableCommit commit =
        new ReportableCommit(
            List.of(
                next(
                    "E1",
                    "d1",
                    List.of(MARKET),
                    newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1"))),
            Optional.of(id),
            Optional.empty());

    ReportableCommitted first = ledger().commitReportable(commit).get(0);
    assertThat(first.decision().status()).isEqualTo(CommitStatus.INSERTED);
    assertThat(first.versions().get(0).status()).isEqualTo(VersionStatus.CREATED);
    assertThat(outboxState(id)).isEqualTo(OutboxState.DONE);
    assertThat(ledger().pendingOutbox(OutboxKind.DECIDE_BATCH, 10)).isEmpty();

    // a redelivery of the unit is a no-op and the item stays DONE
    ReportableCommitted again = ledger().commitReportable(commit).get(0);
    assertThat(again.decision().status()).isEqualTo(CommitStatus.NOOP_REDELIVERY);
    assertThat(again.versions().get(0).status()).isEqualTo(VersionStatus.EXISTING);
    assertThat(outboxState(id)).isEqualTo(OutboxState.DONE);
    assertThat(ledger().pendingOutbox(OutboxKind.DECIDE_BATCH, 10)).isEmpty();
  }

  @Test
  default void aFailedUnitLeavesTheOutboxItemPending() {
    OutboxId id = decideBatchItem();
    commitVersion(
        "E1", "d1", List.of(MARKET), newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1"));

    // a TRN conflict
    ReportableCommit clash =
        new ReportableCommit(
            List.of(
                next(
                    "E2",
                    "d1",
                    List.of(MARKET),
                    newVersion("E2", MARKET, "EXEC1", "TRN1", "NEWT", "h2"))),
            Optional.of(id),
            Optional.empty());
    assertThatThrownBy(() -> ledger().commitReportable(clash))
        .isInstanceOf(TrnConflictException.class);
    assertThat(outboxState(id)).isEqualTo(OutboxState.PENDING);
    assertNothingStored("E2", MARKET);

    // an invalid version request
    ReportableCommit wrongLeg =
        new ReportableCommit(
            List.of(
                next(
                    "E3",
                    "d1",
                    List.of(MARKET),
                    newVersion("E3", CLIENT, "EXEC1", "TRN3", "NEWT", "h3"))),
            Optional.of(id),
            Optional.empty());
    assertThatThrownBy(() -> ledger().commitReportable(wrongLeg))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(outboxState(id)).isEqualTo(OutboxState.PENDING);
    assertNothingStored("E3", MARKET, CLIENT);

    // an unknown outbox item writes nothing
    ReportableCommit unknown =
        new ReportableCommit(
            List.of(
                next(
                    "E4",
                    "d1",
                    List.of(MARKET),
                    newVersion("E4", MARKET, "EXEC1", "TRN4", "NEWT", "h4"))),
            Optional.of(new OutboxId(9_999)),
            Optional.empty());
    assertThatThrownBy(() -> ledger().commitReportable(unknown))
        .isInstanceOf(IllegalArgumentException.class);
    assertNothingStored("E4", MARKET);
    assertThat(ledger().subjectByTrn("EXEC1", "TRN4")).isEmpty();
    assertThat(outboxState(id)).isEqualTo(OutboxState.PENDING);
  }

  @Test
  default void aStaleExpectedHeadIsAConflictAndWritesNothing() {
    OutboxId id = decideBatchItem();
    // expected head 5 on an empty chain
    NewDecision stale = reportDecision(5, "E1", "ctx", "d1", MARKET);
    ReportableCommit onEmpty =
        new ReportableCommit(
            List.of(
                new ReportableDecision(
                    stale, List.of(newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1")))),
            Optional.of(id),
            Optional.empty());
    assertThatThrownBy(() -> ledger().commitReportable(onEmpty))
        .isInstanceOfSatisfying(
            DecisionHeadConflictException.class, e -> assertThat(e.currentHeadSeq()).isZero());
    assertNothingStored("E1", MARKET);
    assertThat(ledger().subjectByTrn("EXEC1", "TRN1")).isEmpty();
    assertThatCode(
            () -> ledger().createDelivery(new NewDelivery(endpoint("EP1"), 1, "EXEC1|TRN1", 1)))
        .doesNotThrowAnyException();
    assertThat(outboxState(id)).isEqualTo(OutboxState.PENDING);

    // expected head 0 on a chain at 1: a new decision, not a redelivery
    commitVersion(
        "E2", "d1", List.of(MARKET), newVersion("E2", MARKET, "EXEC1", "TRN2", "NEWT", "h2"));
    ReportableCommit behind =
        new ReportableCommit(
            List.of(
                new ReportableDecision(
                    reportDecision(0, "E2", "ctx", "d2", MARKET),
                    List.of(newVersion("E2", MARKET, "EXEC1", "TRN2", "CANC", "h3")))),
            Optional.of(id),
            Optional.empty());
    assertThatThrownBy(() -> ledger().commitReportable(behind))
        .isInstanceOfSatisfying(
            DecisionHeadConflictException.class, e -> assertThat(e.currentHeadSeq()).isEqualTo(1));
    assertThat(ledger().decisionChain(mifir(), event("E2"))).hasSize(1);
    SubjectId subject = ledger().subject(key("E2", MARKET)).orElseThrow().id();
    assertThat(ledger().versions(subject)).hasSize(1);
    assertThat(ledger().subject(key("E2", MARKET)).orElseThrow().lastVersionSeq()).isEqualTo(1);
    assertThat(outboxState(id)).isEqualTo(OutboxState.PENDING);
  }

  @Test
  default void decisionAndVersionsCommitInOneUnit() {
    commitVersion(
        "E1", "d1", List.of(MARKET), newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1"));

    // a TRN conflict on the second leg leaves no decision and no subject for the first leg
    ReportableDecision twoLegs =
        next(
            "E2",
            "d1",
            List.of(MARKET, CLIENT),
            newVersion("E2", MARKET, "EXEC1", "TRN2", "NEWT", "m1"),
            newVersion("E2", CLIENT, "EXEC1", "TRN1", "NEWT", "c1"));
    assertThatThrownBy(() -> commitUnit(twoLegs)).isInstanceOf(TrnConflictException.class);
    assertNothingStored("E2", MARKET, CLIENT);
    assertThat(ledger().subjectByTrn("EXEC1", "TRN2")).isEmpty();

    // a conflict in the second decision of a unit leaves the first decision uncommitted too
    ReportableDecision good =
        next("E3", "d1", List.of(MARKET), newVersion("E3", MARKET, "EXEC1", "TRN3", "NEWT", "h3"));
    ReportableDecision clash =
        next("E4", "d1", List.of(MARKET), newVersion("E4", MARKET, "EXEC1", "TRN1", "NEWT", "h4"));
    assertThatThrownBy(() -> commitUnit(good, clash)).isInstanceOf(TrnConflictException.class);
    assertNothingStored("E3", MARKET);
    assertNothingStored("E4", MARKET);
    assertThat(ledger().subjectByTrn("EXEC1", "TRN3")).isEmpty();

    // the same two without the clash commit together
    ReportableDecision fine =
        next("E4", "d1", List.of(MARKET), newVersion("E4", MARKET, "EXEC1", "TRN4", "NEWT", "h4"));
    List<ReportableCommitted> both = commitUnit(good, fine);
    assertThat(both).hasSize(2);
    assertThat(ledger().subject(key("E3", MARKET))).isPresent();
    assertThat(ledger().subject(key("E4", MARKET))).isPresent();
  }

  @Test
  default void aSupersedingNonReportDecisionFailsClosedWhileVersionsExist() {
    SubjectId market =
        commitVersion(
                "E1",
                "d1",
                List.of(MARKET, CLIENT),
                newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "m1"))
            .subject();

    // DO_NOT_REPORT after REPORT with a version
    DecisionBody doNotReport =
        LedgerFixtures.body(
            "E1",
            MIFIR_REGIME,
            "ctx",
            "d2",
            Outcome.DO_NOT_REPORT,
            Instant.parse("2026-10-09T10:00:00Z"));
    ReportableCommit withdrawal =
        new ReportableCommit(
            List.of(new ReportableDecision(new NewDecision(doNotReport, 1), List.of())),
            Optional.empty(),
            Optional.empty());
    assertThatThrownBy(() -> ledger().commitReportable(withdrawal))
        .isInstanceOf(WithdrawalRequiredException.class);
    assertChainUnchanged(market);

    // a leg that has a version is dropped
    ReportableDecision dropsLeg =
        new ReportableDecision(
            reportDecision(1, "E1", "ctx", "d3", CLIENT),
            List.of(newVersion("E1", CLIENT, "EXEC1", "TRN2", "NEWT", "c1")));
    assertThatThrownBy(() -> commitUnit(dropsLeg)).isInstanceOf(WithdrawalRequiredException.class);
    assertChainUnchanged(market);
    assertThat(ledger().subject(key("E1", CLIENT))).isEmpty();

    // keeping the leg is a normal supersession
    VersionResult kept =
        commitVersion(
            "E1",
            "d4",
            List.of(MARKET, CLIENT),
            newVersion("E1", MARKET, "EXEC1", "TRN1", "CANC", "m2"));
    assertThat(kept.status()).isEqualTo(VersionStatus.CREATED);
    assertThat(kept.versionSeq()).isEqualTo(2);
  }

  private void assertChainUnchanged(SubjectId market) {
    assertThat(ledger().decisionChain(mifir(), event("E1"))).hasSize(1);
    assertThat(ledger().subject(key("E1", MARKET)).orElseThrow().lastVersionSeq()).isEqualTo(1);
    assertThat(ledger().versions(market)).hasSize(1);
  }

  @Test
  default void aSupersedingNonReportDecisionFailsClosedThroughCommitDecisionsToo() {
    SubjectId market =
        commitVersion(
                "E1",
                "d1",
                List.of(MARKET, CLIENT),
                newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "m1"))
            .subject();

    DecisionBody doNotReport =
        LedgerFixtures.body(
            "E1",
            MIFIR_REGIME,
            "ctx",
            "d2",
            Outcome.DO_NOT_REPORT,
            Instant.parse("2026-10-09T10:00:00Z"));
    assertThatThrownBy(
            () ->
                ledger()
                    .commitDecisions(
                        new BatchCommit(
                            List.of(new NewDecision(doNotReport, 1)), Optional.empty())))
        .isInstanceOf(WithdrawalRequiredException.class);
    assertChainUnchanged(market);

    // a REPORT that drops the leg with a version
    assertThatThrownBy(
            () ->
                ledger()
                    .commitDecisions(
                        new BatchCommit(
                            List.of(reportDecision(1, "E1", "ctx", "d3", CLIENT)),
                            Optional.empty())))
        .isInstanceOf(WithdrawalRequiredException.class);
    assertChainUnchanged(market);

    // a REPORT that keeps the leg, and any decision on a chain without versions, is accepted
    assertThat(
            ledger()
                .commitDecisions(
                    new BatchCommit(
                        List.of(reportDecision(1, "E1", "ctx", "d4", MARKET, CLIENT)),
                        Optional.empty()))
                .get(0)
                .status())
        .isEqualTo(CommitStatus.INSERTED);
    DecisionBody noVersions =
        LedgerFixtures.body(
            "E2", MIFIR_REGIME, "ctx", "d1", Outcome.REPORT, Instant.parse("2026-10-09T10:00:00Z"));
    ledger()
        .commitDecisions(
            new BatchCommit(List.of(new NewDecision(noVersions, 0)), Optional.empty()));
    DecisionBody noVersionsDnr =
        LedgerFixtures.body(
            "E2",
            MIFIR_REGIME,
            "ctx",
            "d2",
            Outcome.DO_NOT_REPORT,
            Instant.parse("2026-10-09T10:00:00Z"));
    assertThat(
            ledger()
                .commitDecisions(
                    new BatchCommit(List.of(new NewDecision(noVersionsDnr, 1)), Optional.empty()))
                .get(0)
                .status())
        .isEqualTo(CommitStatus.INSERTED);
  }

  private static List<Leg> allLegs() {
    return List.of(MARKET, CLIENT, ALLOCATION);
  }

  /** Decision 1 of {@code event}: REPORT on all three legs, with a version on each of them. */
  private List<SubjectId> allLegsReported(String event) {
    NewVersion[] versions = new NewVersion[allLegs().size()];
    for (int i = 0; i < versions.length; i++) {
      Leg leg = allLegs().get(i);
      versions[i] = newVersion(event, leg, "EXEC1", "TRN" + event + leg.name(), "NEWT", "h" + leg);
    }
    List<VersionResult> created =
        commitUnit(next(event, "d1", allLegs(), versions)).get(0).versions();
    assertThat(created).extracting(VersionResult::status).containsOnly(VersionStatus.CREATED);
    return created.stream().map(VersionResult::subject).toList();
  }

  /** Nothing of a refused supersession is stored: one decision, and one version on every leg. */
  private void assertAllLegsUnchanged(String event, List<SubjectId> subjects) {
    assertThat(ledger().decisionChain(mifir(), event(event))).hasSize(1);
    for (int i = 0; i < allLegs().size(); i++) {
      assertThat(ledger().subject(key(event, allLegs().get(i))).orElseThrow().lastVersionSeq())
          .as(allLegs().get(i).name())
          .isEqualTo(1);
      assertThat(ledger().versions(subjects.get(i))).hasSize(1);
    }
  }

  private List<CommitStatus> commitDecisionsOnly(NewDecision decision) {
    return ledger().commitDecisions(new BatchCommit(List.of(decision), Optional.empty())).stream()
        .map(c -> c.status())
        .toList();
  }

  /**
   * Rule 8 (D-L4) for every leg, not only the first: versions exist on MARKET, CLIENT and
   * ALLOCATION, and a REPORT that leaves out any one of them fails closed, whichever of the two
   * entry points carries it. A leg without a version can be left out.
   */
  @Test
  default void aSupersedingDecisionThatDropsALaterLegFailsClosed() {
    for (Leg dropped : allLegs()) {
      String event = "W" + dropped.name();
      List<SubjectId> subjects = allLegsReported(event);
      Leg[] kept = allLegs().stream().filter(leg -> leg != dropped).toArray(Leg[]::new);

      NewDecision drops = reportDecision(1, event, "ctx", "d2", kept);
      assertThatThrownBy(() -> commitUnit(new ReportableDecision(drops, List.of())))
          .as("commitReportable drops " + dropped)
          .isInstanceOf(WithdrawalRequiredException.class);
      assertAllLegsUnchanged(event, subjects);
      assertThatThrownBy(() -> commitDecisionsOnly(drops))
          .as("commitDecisions drops " + dropped)
          .isInstanceOf(WithdrawalRequiredException.class);
      assertAllLegsUnchanged(event, subjects);

      // keeping every leg is a normal supersession
      assertThat(
              commitDecisionsOnly(
                  reportDecision(1, event, "ctx", "d3", allLegs().toArray(Leg[]::new))))
          .containsExactly(CommitStatus.INSERTED);
    }

    // a leg without a version can be left out: only the leg with a version is protected
    commitVersion(
        "WN",
        "d1",
        List.of(MARKET, CLIENT),
        newVersion("WN", MARKET, "EXEC1", "TRNWN", "NEWT", "m"));
    assertThat(commitDecisionsOnly(reportDecision(1, "WN", "ctx", "d2", MARKET)))
        .containsExactly(CommitStatus.INSERTED);
    assertThat(ledger().subject(key("WN", CLIENT))).isEmpty();
  }

  /**
   * A superseding decision that is not REPORT fails closed even when it still names the legs that
   * have versions: the outcome decides, not the leg list.
   */
  @Test
  default void aNonReportDecisionThatStillNamesLegsFailsClosed() {
    for (Outcome outcome :
        List.of(Outcome.DO_NOT_REPORT, Outcome.UNDETERMINED, Outcome.REPORT_BY_OTHER)) {
      String event = "N" + outcome.ordinal();
      List<SubjectId> subjects = allLegsReported(event);
      NewDecision names =
          LedgerFixtures.decision(
              LedgerFixtures.bodyWithLegs(
                  MIFIR_REGIME, event, "ctx", "d2", outcome, allLegs().toArray(Leg[]::new)),
              1);
      assertThatThrownBy(() -> commitUnit(new ReportableDecision(names, List.of())))
          .as("commitReportable " + outcome)
          .isInstanceOf(WithdrawalRequiredException.class);
      assertAllLegsUnchanged(event, subjects);
      assertThatThrownBy(() -> commitDecisionsOnly(names))
          .as("commitDecisions " + outcome)
          .isInstanceOf(WithdrawalRequiredException.class);
      assertAllLegsUnchanged(event, subjects);
    }
  }

  /**
   * Rule 2, the outcome half: a version on a decision that is not REPORT is refused although the
   * decision names the leg (the leg-membership check alone would accept it).
   */
  @Test
  default void aVersionOnANonReportDecisionThatNamesLegsIsRefused() {
    for (Outcome outcome :
        List.of(Outcome.DO_NOT_REPORT, Outcome.UNDETERMINED, Outcome.REPORT_BY_OTHER)) {
      String event = "V" + outcome.ordinal();
      NewDecision names =
          LedgerFixtures.decision(
              LedgerFixtures.bodyWithLegs(MIFIR_REGIME, event, "ctx", "d1", outcome, MARKET), 0);
      ReportableDecision unit =
          new ReportableDecision(
              names, List.of(newVersion(event, MARKET, "EXEC1", "TRN" + event, "NEWT", "h1")));
      assertThatThrownBy(() -> commitUnit(unit))
          .as(outcome.name())
          .isInstanceOf(IllegalArgumentException.class);
      assertNothingStored(event, MARKET);
      assertThat(ledger().subjectByTrn("EXEC1", "TRN" + event)).isEmpty();
    }
  }

  /**
   * A redelivery of a decision that is not the head is not a new decision: it never runs the
   * withdrawal guard, however many versions the chain has gained since.
   */
  @Test
  default void aRedeliveryOfAnEarlierNonReportDecisionIsANoopWhenLaterVersionsExist() {
    NewDecision earlier =
        LedgerFixtures.decision(
            LedgerFixtures.body(
                "E1",
                MIFIR_REGIME,
                "ctx",
                "d1",
                Outcome.DO_NOT_REPORT,
                Instant.parse("2026-10-09T10:00:00Z")),
            0);
    assertThat(commitDecisionsOnly(earlier)).containsExactly(CommitStatus.INSERTED);
    VersionResult created =
        commitVersion(
            "E1", "d2", List.of(MARKET), newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1"));
    assertThat(created.status()).isEqualTo(VersionStatus.CREATED);

    assertThat(commitDecisionsOnly(earlier)).containsExactly(CommitStatus.NOOP_REDELIVERY);
    List<ReportableCommitted> again = commitUnit(new ReportableDecision(earlier, List.of()));
    assertThat(again.get(0).decision().status()).isEqualTo(CommitStatus.NOOP_REDELIVERY);
    assertThat(again.get(0).versions()).isEmpty();
    assertThat(ledger().decisionChain(mifir(), event("E1"))).hasSize(2);
    assertThat(ledger().versions(created.subject())).hasSize(1);
  }

  /**
   * Two decisions of one chain in one unit (the case the wave split of the real store exists for):
   * the second sees the first one's version, numbers its own after it, and shares the TRN.
   */
  @Test
  default void twoDecisionsOfOneChainWithVersionsInOneUnitChainInOrder() {
    ReportableDecision d1 =
        new ReportableDecision(
            reportDecision(0, "E1", "ctx", "d1", MARKET),
            List.of(newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "A")));
    ReportableDecision d2 =
        new ReportableDecision(
            reportDecision(1, "E1", "ctx", "d2", MARKET),
            List.of(newVersion("E1", MARKET, "EXEC1", "TRN1", "CANC", "B")));
    List<ReportableCommitted> committed = commitUnit(d1, d2);

    assertThat(committed)
        .extracting(c -> c.decision().status())
        .containsExactly(CommitStatus.INSERTED, CommitStatus.INSERTED);
    VersionResult first = committed.get(0).versions().get(0);
    VersionResult second = committed.get(1).versions().get(0);
    assertThat(first.status()).isEqualTo(VersionStatus.CREATED);
    assertThat(second.status()).isEqualTo(VersionStatus.CREATED);
    assertThat(first.versionSeq()).isEqualTo(1);
    assertThat(second.versionSeq()).isEqualTo(2);
    assertThat(second.subject()).isEqualTo(first.subject());
    assertThat(List.of(first.trn(), second.trn())).containsOnly("TRN1");
    assertThat(first.delivery()).isPresent();
    assertThat(second.delivery()).isPresent().isNotEqualTo(first.delivery());

    List<DecisionRecord> chain = ledger().decisionChain(mifir(), event("E1"));
    assertThat(chain).extracting(DecisionRecord::decisionSeq).containsExactly(1L, 2L);
    assertThat(chain.get(0).supersedesDecisionId()).isEmpty();
    assertThat(chain.get(1).supersedesDecisionId()).contains(chain.get(0).decisionId());
    List<StoredVersion> versions = ledger().versions(first.subject());
    assertThat(versions).extracting(StoredVersion::versionSeq).containsExactly(1L, 2L);
    assertThat(versions)
        .extracting(StoredVersion::decision)
        .containsExactly(chain.get(0).decisionId(), chain.get(1).decisionId());
    assertThat(versions).extracting(StoredVersion::action).containsExactly("NEWT", "CANC");
    assertThat(ledger().subjectByTrn("EXEC1", "TRN1").orElseThrow().id())
        .isEqualTo(first.subject());
    assertThat(ledger().subject(key("E1", MARKET)).orElseThrow().lastVersionSeq()).isEqualTo(2);
  }

  /** The second decision's suppression read sees the version that the first one just wrote. */
  @Test
  default void aSecondDecisionOfTheUnitWithTheSameContentIsSuppressedAgainstTheFirst() {
    ReportableDecision d1 =
        new ReportableDecision(
            reportDecision(0, "E1", "ctx", "d1", MARKET),
            List.of(newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "A")));
    ReportableDecision d2 =
        new ReportableDecision(
            reportDecision(1, "E1", "ctx", "d2", MARKET),
            List.of(newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "A")));
    List<ReportableCommitted> committed = commitUnit(d1, d2);

    VersionResult created = committed.get(0).versions().get(0);
    VersionResult suppressed = committed.get(1).versions().get(0);
    assertThat(created.status()).isEqualTo(VersionStatus.CREATED);
    assertThat(suppressed.status()).isEqualTo(VersionStatus.SUPPRESSED_UNCHANGED);
    assertThat(suppressed.subject()).isEqualTo(created.subject());
    assertThat(suppressed.versionSeq()).isEqualTo(1);
    assertThat(suppressed.delivery()).isEmpty();
    assertThat(committed.get(1).decision().status()).isEqualTo(CommitStatus.INSERTED);
    assertThat(committed.get(1).decision().record().decisionSeq()).isEqualTo(2);
    assertThat(ledger().versions(created.subject())).hasSize(1);
    assertThat(ledger().decisionChain(mifir(), event("E1"))).hasSize(2);
  }

  /** Rule 8 on the second decision of the unit sees the version of the first one. */
  @Test
  default void aNonReportSecondDecisionOfTheUnitFailsClosedAndWritesNothing() {
    ReportableDecision d1 =
        new ReportableDecision(
            reportDecision(0, "E1", "ctx", "d1", MARKET),
            List.of(newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "A")));
    ReportableDecision d2 =
        new ReportableDecision(
            LedgerFixtures.decision(
                LedgerFixtures.body(
                    "E1",
                    MIFIR_REGIME,
                    "ctx",
                    "d2",
                    Outcome.DO_NOT_REPORT,
                    Instant.parse("2026-10-09T10:00:00Z")),
                1),
            List.of());

    assertThatThrownBy(() -> commitUnit(d1, d2)).isInstanceOf(WithdrawalRequiredException.class);
    assertNothingStored("E1", MARKET);
    assertThat(ledger().subjectByTrn("EXEC1", "TRN1")).isEmpty();
    assertThatCode(
            () -> ledger().createDelivery(new NewDelivery(endpoint("EP1"), 1, "EXEC1|TRN1", 1)))
        .doesNotThrowAnyException();
  }

  /**
   * A redelivered earlier decision listed after a new decision of its chain is no longer the head:
   * the unit is a head conflict and nothing of it is stored.
   */
  @Test
  default void aRedeliveredDecisionListedAfterALaterOneOfItsChainIsAHeadConflict() {
    NewDecision first = reportDecision(0, "E1", "ctx", "d1", MARKET);
    ledger().commitDecisions(new BatchCommit(List.of(first), Optional.empty()));
    ReportableDecision later =
        new ReportableDecision(
            reportDecision(1, "E1", "ctx", "d2", MARKET),
            List.of(newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h2")));
    ReportableDecision redelivered =
        new ReportableDecision(
            first, List.of(newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1")));

    assertThatThrownBy(() -> commitUnit(later, redelivered))
        .isInstanceOf(DecisionHeadConflictException.class);
    assertThat(ledger().decisionChain(mifir(), event("E1"))).hasSize(1);
    assertThat(ledger().subject(key("E1", MARKET))).isEmpty();
    assertThat(ledger().subjectByTrn("EXEC1", "TRN1")).isEmpty();
  }

  /**
   * Meaningful only against a real store; the single-lock in-memory adapter passes trivially (spec
   * 14.1).
   */
  @Test
  default void versionSeqIsGaplessPerSubjectUnderConcurrentUnits() throws Exception {
    int threads = 8;
    int unitsPerThread = 5;
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<Integer>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        int thread = t;
        futures.add(
            pool.submit(
                () -> {
                  release.await();
                  int succeeded = 0;
                  while (succeeded < unitsPerThread) {
                    String tag = thread + "-" + succeeded;
                    ReportableDecision unit =
                        next(
                            "E1",
                            "d" + tag,
                            List.of(MARKET),
                            newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h" + tag));
                    try {
                      commitUnit(unit);
                      succeeded++;
                    } catch (DecisionHeadConflictException e) {
                      // lost the race for the head: read it again and retry
                    }
                  }
                  return succeeded;
                }));
      }
      release.countDown();
      for (Future<Integer> f : futures) {
        assertThat(f.get()).isEqualTo(unitsPerThread);
      }
    } finally {
      pool.shutdownNow();
    }
    int total = threads * unitsPerThread;
    Subject subject = ledger().subject(key("E1", MARKET)).orElseThrow();
    assertThat(subject.lastVersionSeq()).isEqualTo(total);
    List<StoredVersion> versions = ledger().versions(subject.id());
    assertThat(versions)
        .extracting(StoredVersion::versionSeq)
        .containsExactlyElementsOf(range(total));
    assertThat(versions).extracting(StoredVersion::decision).doesNotHaveDuplicates();
    assertThat(ledger().decisionChain(mifir(), event("E1")))
        .extracting(DecisionRecord::decisionSeq)
        .containsExactlyElementsOf(range(total));
  }

  /**
   * A decision committed without versions (a {@code commitDecisions} unit) is redelivered to two
   * workers at once with the same version request. Whatever the interleaving, one unit creates the
   * version and the other answers {@code EXISTING} with the same subject, version and delivery, and
   * one version exists. Meaningful against a real store, where both units can miss the version
   * before either has committed; the single-lock in-memory adapter passes trivially (spec 14.1).
   */
  @Test
  default void twoConcurrentRedeliveriesOfOneDecisionYieldOneCreatedAndOneExisting()
      throws Exception {
    for (int round = 0; round < 8; round++) {
      redeliverAtOnce("RA" + round, "h1", "h1");
    }
  }

  /** The same with a redelivery whose content differs: the decision already has its version. */
  @Test
  default void aConcurrentRedeliveryWithOtherContentIsExistingNotASecondVersion() throws Exception {
    for (int round = 0; round < 8; round++) {
      redeliverAtOnce("RB" + round, "h1", "h2");
    }
  }

  private void redeliverAtOnce(String event, String firstHash, String secondHash) throws Exception {
    NewDecision decision = reportDecision(0, event, "ctx", "d1", MARKET);
    ledger().commitDecisions(new BatchCommit(List.of(decision), Optional.empty()));
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<VersionResult>> futures = new ArrayList<>();
      for (String hash : List.of(firstHash, secondHash)) {
        ReportableCommit redelivery =
            new ReportableCommit(
                List.of(
                    new ReportableDecision(
                        decision,
                        List.of(newVersion(event, MARKET, "EXEC1", "TRN" + event, "NEWT", hash)))),
                Optional.empty(),
                Optional.empty());
        futures.add(
            pool.submit(
                () -> {
                  barrier.await(30, TimeUnit.SECONDS);
                  ReportableCommitted committed = ledger().commitReportable(redelivery).get(0);
                  assertThat(committed.decision().status()).isEqualTo(CommitStatus.NOOP_REDELIVERY);
                  return committed.versions().get(0);
                }));
      }
      List<VersionResult> results = new ArrayList<>();
      for (Future<VersionResult> future : futures) {
        results.add(future.get(60, TimeUnit.SECONDS));
      }
      assertThat(results)
          .extracting(VersionResult::status)
          .as(event)
          .containsExactlyInAnyOrder(VersionStatus.CREATED, VersionStatus.EXISTING);
      VersionResult first = results.get(0);
      VersionResult second = results.get(1);
      assertThat(second.subject()).isEqualTo(first.subject());
      assertThat(second.versionSeq()).isEqualTo(first.versionSeq()).isEqualTo(1);
      assertThat(second.trn()).isEqualTo(first.trn());
      assertThat(first.delivery()).isPresent();
      assertThat(second.delivery()).isEqualTo(first.delivery());
      assertThat(ledger().versions(first.subject())).hasSize(1);
      assertThat(ledger().subject(key(event, MARKET)).orElseThrow().lastVersionSeq()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  private static List<Long> range(int count) {
    List<Long> values = new ArrayList<>();
    for (long i = 1; i <= count; i++) {
      values.add(i);
    }
    return values;
  }

  @Test
  default void subjectByTrnFindsTheSubjectOnlyUnderItsLei() {
    VersionResult v =
        commitVersion(
            "E1", "d1", List.of(MARKET), newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1"));

    Subject found = ledger().subjectByTrn("EXEC1", "TRN1").orElseThrow();
    assertThat(found.id()).isEqualTo(v.subject());
    assertThat(found.key()).isEqualTo(key("E1", MARKET));
    assertThat(ledger().subjectByTrn("EXEC2", "TRN1")).isEmpty();
    assertThat(ledger().subjectByTrn("EXEC1", "TRN2")).isEmpty();
    assertThat(ledger().subject(key("E9", MARKET))).isEmpty();
  }

  @Test
  default void versionsAreListedAscendingWithBodiesAsCopies() {
    byte[] input = "body-h1".getBytes(StandardCharsets.UTF_8);
    NewVersion first = newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h1");
    VersionResult r1 = commitVersion("E1", "d1", List.of(MARKET), first);
    commitVersion(
        "E1", "d2", List.of(MARKET), newVersion("E1", MARKET, "EXEC1", "TRN1", "CANC", "h2"));
    commitVersion(
        "E1", "d3", List.of(MARKET), newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "h3"));

    List<StoredVersion> versions = ledger().versions(r1.subject());
    assertThat(versions).extracting(StoredVersion::versionSeq).containsExactly(1L, 2L, 3L);
    assertThat(versions).extracting(StoredVersion::action).containsExactly("NEWT", "CANC", "NEWT");
    assertThat(versions.get(0).subject()).isEqualTo(r1.subject());
    assertThat(versions.get(0).logicalHash()).isEqualTo(first.logicalHash());
    assertThat(versions.get(0).body()).isEqualTo(input);
    assertThat(versions.get(0).origin()).isEqualTo(first.origin());
    assertThat(versions.get(0).delivery()).isEqualTo(r1.delivery());
    assertThat(versions.get(1).body()).isEqualTo("body-h2".getBytes(StandardCharsets.UTF_8));
    assertThat(versions.get(0).decision()).isNotEqualTo(versions.get(1).decision());

    // mutating a returned body changes nothing that is stored
    versions.get(0).body()[0] ^= 1;
    assertThat(ledger().versions(r1.subject()).get(0).body()).isEqualTo(input);

    // nor does mutating the array that was passed in
    byte[] given = "body-x".getBytes(StandardCharsets.UTF_8);
    NewVersion request =
        new NewVersion(
            key("E2", MARKET),
            "EXEC1",
            Optional.of(new TrnProposal("TRN2", "KEY-1")),
            "NEWT",
            first.logicalHash(),
            given,
            first.origin(),
            endpoint("EP1"),
            1);
    given[0] = 'Z';
    VersionResult r2 = commitVersion("E2", "d1", List.of(MARKET), request);
    assertThat(ledger().versions(r2.subject()).get(0).body())
        .isEqualTo("body-x".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  default void oneSequenceOutboxItemPerBatchEvenOnRedelivery() {
    // a pending DECIDE_BATCH item next to the SEQUENCE item: each kind lists only its own
    OutboxId decideItem = decideBatchItem();
    var batch = LedgerFixtures.batchId("b1");
    ReportableCommit commit =
        new ReportableCommit(
            List.of(
                next(
                    "E1",
                    "d1",
                    List.of(MARKET),
                    newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "A"))),
            Optional.empty(),
            Optional.of(batch));
    ledger().commitReportable(commit);

    List<OutboxItem> pending = ledger().pendingOutbox(OutboxKind.SEQUENCE, 10);
    assertThat(pending).hasSize(1);
    assertThat(pending.get(0).kind()).isEqualTo(OutboxKind.SEQUENCE);
    assertThat(pending.get(0).subjectId()).isEqualTo(batch.value());
    assertThat(pending.get(0).state()).isEqualTo(OutboxState.PENDING);
    List<OutboxItem> decide = ledger().pendingOutbox(OutboxKind.DECIDE_BATCH, 10);
    assertThat(decide).extracting(OutboxItem::id).containsExactly(decideItem);
    assertThat(decide).extracting(OutboxItem::kind).containsOnly(OutboxKind.DECIDE_BATCH);
    assertThat(decide.get(0).subjectId()).isNotEqualTo(batch.value());

    // a redelivery of the unit, and another unit of the same batch, leave the one item
    ledger().commitReportable(commit);
    ledger()
        .commitReportable(
            new ReportableCommit(
                List.of(
                    next(
                        "E2",
                        "d1",
                        List.of(MARKET),
                        newVersion("E2", MARKET, "EXEC1", "TRN2", "NEWT", "A"))),
                Optional.empty(),
                Optional.of(batch)));
    assertThat(ledger().pendingOutbox(OutboxKind.SEQUENCE, 10)).containsExactly(pending.get(0));

    // a batch whose versions are all suppressed or existing creates no item
    var other = LedgerFixtures.batchId("b2");
    ledger()
        .commitReportable(
            new ReportableCommit(
                List.of(
                    next(
                        "E1",
                        "d2",
                        List.of(MARKET),
                        newVersion("E1", MARKET, "EXEC1", "TRN1", "NEWT", "A"))),
                Optional.empty(),
                Optional.of(other)));
    ledger()
        .commitReportable(
            new ReportableCommit(commit.decisions(), Optional.empty(), Optional.of(other)));
    assertThat(ledger().pendingOutbox(OutboxKind.SEQUENCE, 10)).containsExactly(pending.get(0));

    // without a sequence batch no item is created
    ledger()
        .commitReportable(
            new ReportableCommit(
                List.of(
                    next(
                        "E3",
                        "d1",
                        List.of(MARKET),
                        newVersion("E3", MARKET, "EXEC1", "TRN3", "NEWT", "A"))),
                Optional.empty(),
                Optional.empty()));
    assertThat(ledger().pendingOutbox(OutboxKind.SEQUENCE, 10)).hasSize(1);
  }
}
