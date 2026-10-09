package dev.regpack.testkit.contract;

import static dev.regpack.testkit.contract.LedgerFixtures.bodyAt;
import static dev.regpack.testkit.contract.LedgerFixtures.checkpoint;
import static dev.regpack.testkit.contract.LedgerFixtures.commit;
import static dev.regpack.testkit.contract.LedgerFixtures.decision;
import static dev.regpack.testkit.contract.LedgerFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.Outcome;
import dev.regpack.model.Regime;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionRecord;
import dev.regpack.ports.ledger.DecisionHeadConflictException;
import dev.regpack.ports.ledger.DecisionLedger.BatchCommit;
import dev.regpack.ports.ledger.DecisionLedger.CommitStatus;
import dev.regpack.ports.ledger.DecisionLedger.Committed;
import dev.regpack.ports.ledger.DecisionLedger.NewDecision;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.OutboxLedger.OutboxId;
import dev.regpack.ports.ledger.OutboxLedger.OutboxKind;
import dev.regpack.ports.ledger.OutboxLedger.OutboxState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Contract every {@link LedgerStore} adapter must satisfy for decisions (spec 6.3). */
public interface DecisionLedgerContract {
  String MIFIR = "mifir.eu.rts22";
  String EMIR = "emir.eu";
  Instant EVAL = Instant.parse("2026-10-09T10:00:00Z");
  Instant TRADE_DATE_1 = Instant.parse("2026-10-09T09:30:00Z");
  Instant TRADE_DATE_2 = Instant.parse("2026-10-10T09:30:00Z");

  /** A fresh, empty ledger for each test. */
  LedgerStore ledger();

  private static DecisionBody body(String event, String ctx, String digest, Outcome outcome) {
    return LedgerFixtures.body(event, MIFIR, ctx, digest, outcome, EVAL);
  }

  private static BatchCommit batch(NewDecision... decisions) {
    return new BatchCommit(List.of(decisions), Optional.empty());
  }

  private Committed commitOne(DecisionBody body, long expectedHeadSeq) {
    List<Committed> committed = ledger().commitDecisions(batch(decision(body, expectedHeadSeq)));
    assertThat(committed).hasSize(1);
    return committed.get(0);
  }

  private static EventIdentity event(String name) {
    return EventIdentity.of("LEI1", "XPAR", name);
  }

  @Test
  default void firstDecisionHasSeq1AndNoSupersedes() {
    Committed c = commitOne(body("E1", "ctx", "d1", Outcome.REPORT), 0);
    assertThat(c.status()).isEqualTo(CommitStatus.INSERTED);
    assertThat(c.record().decisionSeq()).isEqualTo(1);
    assertThat(c.record().supersedesDecisionId()).isEmpty();
    assertThat(ledger().currentDecision(new Regime(MIFIR), event("E1"))).contains(c.record());
  }

  @Test
  default void redeliveryOfSameKeyIsNoopAndReturnsStoredRecord() {
    DecisionBody b = body("E1", "ctx", "d1", Outcome.REPORT);
    Committed first = commitOne(b, 0);
    Committed again = commitOne(b, 0);
    assertThat(again.status()).isEqualTo(CommitStatus.NOOP_REDELIVERY);
    assertThat(again.record().decisionId()).isEqualTo(first.record().decisionId());
    assertThat(ledger().decisionChain(new Regime(MIFIR), event("E1"))).hasSize(1);
  }

  @Test
  default void newInputDigestSupersedesWithNextSeq() {
    Committed first = commitOne(body("E1", "ctx", "d1", Outcome.REPORT), 0);
    Committed second = commitOne(body("E1", "ctx", "d2", Outcome.DO_NOT_REPORT), 1);
    assertThat(second.status()).isEqualTo(CommitStatus.INSERTED);
    assertThat(second.record().decisionSeq()).isEqualTo(2);
    assertThat(second.record().supersedesDecisionId()).contains(first.record().decisionId());
    assertThat(second.record().decisionId().value())
        .isGreaterThan(first.record().decisionId().value());
    assertThat(ledger().currentDecision(new Regime(MIFIR), event("E1"))).contains(second.record());
    assertThat(ledger().decisionChain(new Regime(MIFIR), event("E1")))
        .extracting(DecisionRecord::decisionSeq)
        .containsExactly(1L, 2L);
  }

  @Test
  default void staleExpectedHeadSeqIsRejectedAndRollsBackWholeBatch() {
    commitOne(body("E1", "ctx", "d1", Outcome.REPORT), 0);
    BatchCommit bad =
        batch(
            decision(body("E2", "ctx", "d1", Outcome.REPORT), 0),
            decision(body("E1", "ctx", "d2", Outcome.REPORT), 0));
    assertThatThrownBy(() -> ledger().commitDecisions(bad))
        .isInstanceOfSatisfying(
            DecisionHeadConflictException.class,
            e -> {
              assertThat(e.eventIdentity()).isEqualTo(event("E1"));
              assertThat(e.currentHeadSeq()).isEqualTo(1);
            });
    assertThat(ledger().currentDecision(new Regime(MIFIR), event("E2"))).isEmpty();
    assertThat(ledger().decisionChain(new Regime(MIFIR), event("E1"))).hasSize(1);
  }

  @Test
  default void abaSameOutcomeThirdDecisionIsNewRow() {
    Committed a = commitOne(body("E1", "ctx", "dA", Outcome.REPORT), 0);
    Committed b = commitOne(body("E1", "ctx", "dB", Outcome.DO_NOT_REPORT), 1);
    Committed a2 = commitOne(body("E1", "ctx", "dA2", Outcome.REPORT), 2);
    assertThat(a.record().decisionSeq()).isEqualTo(1);
    assertThat(b.record().decisionSeq()).isEqualTo(2);
    assertThat(a2.record().decisionSeq()).isEqualTo(3);
    assertThat(a2.status()).isEqualTo(CommitStatus.INSERTED);
    assertThat(a2.record().body().outcome()).isEqualTo(Outcome.REPORT);
    assertThat(ledger().decisionChain(new Regime(MIFIR), event("E1"))).hasSize(3);
  }

  @Test
  default void regimesAreIndependentChains() {
    DecisionBody mifir = LedgerFixtures.body("E1", MIFIR, "ctx-mifir", "d1", Outcome.REPORT, EVAL);
    DecisionBody emir = LedgerFixtures.body("E1", EMIR, "ctx-emir", "d1", Outcome.REPORT, EVAL);
    Committed m = commitOne(mifir, 0);
    Committed e = commitOne(emir, 0);
    assertThat(m.status()).isEqualTo(CommitStatus.INSERTED);
    assertThat(e.status()).isEqualTo(CommitStatus.INSERTED);
    assertThat(m.record().decisionSeq()).isEqualTo(1);
    assertThat(e.record().decisionSeq()).isEqualTo(1);
    assertThat(ledger().decisionChain(new Regime(MIFIR), event("E1"))).hasSize(1);
    assertThat(ledger().decisionChain(new Regime(EMIR), event("E1"))).hasSize(1);
  }

  @Test
  default void sameEventSameDigestNewContextSupersedes() {
    Committed first = commitOne(body("E1", "c1", "d1", Outcome.REPORT), 0);
    Committed second = commitOne(body("E1", "c2", "d1", Outcome.REPORT), 1);
    assertThat(second.status()).isEqualTo(CommitStatus.INSERTED);
    assertThat(second.record().decisionSeq()).isEqualTo(2);
    assertThat(second.record().supersedesDecisionId()).contains(first.record().decisionId());
    Committed again = commitOne(body("E1", "c1", "d1", Outcome.REPORT), 2);
    assertThat(again.status()).isEqualTo(CommitStatus.NOOP_REDELIVERY);
    assertThat(again.record()).isEqualTo(first.record());
  }

  /**
   * The real-store run proves the cross-partition case (spec 6.3); the single-lock in-memory
   * adapter keeps one chain per regime and event.
   */
  @Test
  default void supersedingDecisionWithDifferentTradeDateKeepsChainLinear() {
    Committed first =
        commitOne(bodyAt("E1", MIFIR, "ctx", "d1", Outcome.REPORT, EVAL, TRADE_DATE_1), 0);
    Committed second =
        commitOne(bodyAt("E1", MIFIR, "ctx", "d2", Outcome.REPORT, EVAL, TRADE_DATE_2), 1);
    assertThat(second.record().decisionSeq()).isEqualTo(2);
    assertThat(second.record().supersedesDecisionId()).contains(first.record().decisionId());
    DecisionBody third = bodyAt("E1", MIFIR, "ctx", "d3", Outcome.REPORT, EVAL, TRADE_DATE_2);
    assertThatThrownBy(() -> commitOne(third, 1))
        .isInstanceOfSatisfying(
            DecisionHeadConflictException.class, e -> assertThat(e.currentHeadSeq()).isEqualTo(2));
    assertThat(ledger().decisionChain(new Regime(MIFIR), event("E1")))
        .extracting(DecisionRecord::decisionSeq)
        .containsExactly(1L, 2L);
  }

  @Test
  default void redeliveryOfTheFirstDecisionAfterATradeDateChangeIsNoop() {
    DecisionBody original = bodyAt("E1", MIFIR, "ctx", "d1", Outcome.REPORT, EVAL, TRADE_DATE_1);
    Committed first = commitOne(original, 0);
    commitOne(bodyAt("E1", MIFIR, "ctx", "d2", Outcome.REPORT, EVAL, TRADE_DATE_2), 1);
    Committed again = commitOne(original, 2);
    assertThat(again.status()).isEqualTo(CommitStatus.NOOP_REDELIVERY);
    assertThat(again.record()).isEqualTo(first.record());
    assertThat(ledger().decisionChain(new Regime(MIFIR), event("E1"))).hasSize(2);
  }

  /**
   * Meaningful only against a real store; the single-lock in-memory adapter passes trivially (spec
   * 14.1).
   */
  @Test
  default void concurrentDecisionCommitsWithTheSameExpectedHeadHaveExactlyOneWinner()
      throws Exception {
    int threads = 8;
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<Committed>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        BatchCommit b = batch(decision(body("E1", "ctx", "d" + i, Outcome.REPORT), 0));
        futures.add(
            pool.submit(
                () -> {
                  release.await();
                  return ledger().commitDecisions(b).get(0);
                }));
      }
      release.countDown();
      int winners = 0;
      int conflicts = 0;
      for (Future<Committed> f : futures) {
        try {
          Committed c = f.get();
          assertThat(c.status()).isEqualTo(CommitStatus.INSERTED);
          assertThat(c.record().decisionSeq()).isEqualTo(1);
          winners++;
        } catch (ExecutionException e) {
          assertThat(e.getCause()).isInstanceOf(DecisionHeadConflictException.class);
          conflicts++;
        }
      }
      assertThat(winners).isEqualTo(1);
      assertThat(conflicts).isEqualTo(threads - 1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(ledger().decisionChain(new Regime(MIFIR), event("E1"))).hasSize(1);
  }

  @Test
  default void completingOutboxIsPartOfTheSameUnit() {
    OutboxId id =
        ledger()
            .commitIngest(commit("B1", "srcA", checkpoint(1), entry("srcA", "r1", 1, "A")))
            .outboxId()
            .orElseThrow();
    assertThat(ledger().pendingOutbox(OutboxKind.DECIDE_BATCH, 10)).hasSize(1);
    commitOne(body("E1", "ctx", "d1", Outcome.REPORT), 0);

    BatchCommit conflicting =
        new BatchCommit(
            List.of(decision(body("E1", "ctx", "d2", Outcome.REPORT), 0)), Optional.of(id));
    assertThatThrownBy(() -> ledger().commitDecisions(conflicting))
        .isInstanceOf(DecisionHeadConflictException.class);
    assertThat(ledger().outboxItem(id).orElseThrow().state()).isEqualTo(OutboxState.PENDING);

    BatchCommit good =
        new BatchCommit(
            List.of(decision(body("E1", "ctx", "d2", Outcome.REPORT), 1)), Optional.of(id));
    ledger().commitDecisions(good);
    assertThat(ledger().outboxItem(id).orElseThrow().state()).isEqualTo(OutboxState.DONE);
    assertThat(ledger().pendingOutbox(OutboxKind.DECIDE_BATCH, 10)).isEmpty();
  }

  @Test
  default void decisionIdsAreUniqueAndIncreasing() {
    List<Long> ids = new ArrayList<>();
    ids.add(commitOne(body("E1", "ctx", "d1", Outcome.REPORT), 0).record().decisionId().value());
    ids.add(commitOne(body("E2", "ctx", "d1", Outcome.REPORT), 0).record().decisionId().value());
    ids.add(commitOne(body("E1", "ctx", "d2", Outcome.REPORT), 1).record().decisionId().value());
    ids.add(commitOne(body("E2", "ctx", "d2", Outcome.REPORT), 1).record().decisionId().value());
    ids.add(commitOne(body("E1", "ctx", "d3", Outcome.REPORT), 2).record().decisionId().value());
    for (int i = 1; i < ids.size(); i++) {
      assertThat(ids.get(i)).isGreaterThan(ids.get(i - 1));
    }
  }

  @Test
  default void expectedHeadSeqZeroOnExistingChainConflicts() {
    commitOne(body("E1", "ctx", "d1", Outcome.REPORT), 0);
    DecisionBody next = body("E1", "ctx", "d2", Outcome.REPORT);
    assertThatThrownBy(() -> commitOne(next, 0))
        .isInstanceOfSatisfying(
            DecisionHeadConflictException.class, e -> assertThat(e.currentHeadSeq()).isEqualTo(1));
  }
}
