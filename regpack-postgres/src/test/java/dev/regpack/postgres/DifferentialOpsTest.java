package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.model.Leg;
import dev.regpack.postgres.DifferentialOps.Commit;
import dev.regpack.postgres.DifferentialOps.HeadMode;
import dev.regpack.postgres.DifferentialOps.Hold;
import dev.regpack.postgres.DifferentialOps.Side;
import dev.regpack.postgres.DifferentialOps.UnitDecision;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The comparison of the differential test needs no server: how it reads the refusal of a unit of
 * several decisions (D33), and what a unit of two decisions of one chain answers on the in-memory
 * ledger, which is what the comparison is fed with.
 */
class DifferentialOpsTest {
  private static final String REGIME = "mifir.eu.rts22";

  private static UnitDecision decision(String event, HeadMode head, String digest) {
    return new UnitDecision(REGIME, event, head, digest, List.of(Leg.MARKET), List.of());
  }

  private static Commit unit(UnitDecision... decisions) {
    return new Commit(false, List.of(decisions));
  }

  private static String headConflict(String event, int head) {
    return DifferentialOps.HEAD_CONFLICT + "LEI1|XPAR|Nt" + event + " head " + head;
  }

  @Test
  void equalAnswersAgreeAndOthersOfASingleOperationDoNot() {
    Commit single = unit(decision("E1", HeadMode.CURRENT, "d1"));

    assertThat(DifferentialOps.agree(single, "ok", "ok")).isTrue();
    assertThat(DifferentialOps.agree(single, "ok", "throws x")).isFalse();
    assertThat(DifferentialOps.agree(new Hold(null, true), "ok", "throws x")).isFalse();
    // a refusal of the course of a unit is reduced only in a unit of several decisions
    assertThat(
            DifferentialOps.agree(
                single, DifferentialOps.REFUSED_WHILE_PROCESSING, headConflict("E1", 0)))
        .isFalse();
  }

  @Test
  void aUnitWithSeveralDefectsAgreesWhicheverRefusalEachLedgerReportsFirst() {
    Commit several =
        unit(decision("E1", HeadMode.CURRENT, "d1"), decision("E2", HeadMode.AHEAD, "d1"));

    // a withdrawal in one ledger and a stale head in the other: D33 (2)
    assertThat(
            DifferentialOps.agree(
                several, DifferentialOps.REFUSED_WHILE_PROCESSING, headConflict("E2", 0)))
        .isTrue();
    assertThat(
            DifferentialOps.agree(
                several, headConflict("E1", 1), DifferentialOps.REFUSED_WHILE_PROCESSING))
        .isTrue();
    // two stale heads on two chains: the ledgers may name either chain
    assertThat(DifferentialOps.agree(several, headConflict("E1", 1), headConflict("E2", 0)))
        .isTrue();
  }

  @Test
  void twoHeadConflictsOnOneChainMustNameTheSameHead() {
    Commit several =
        unit(decision("E1", HeadMode.CURRENT, "d1"), decision("E1", HeadMode.CURRENT, "d2"));

    assertThat(DifferentialOps.agree(several, headConflict("E1", 0), headConflict("E1", 0)))
        .isTrue();
    assertThat(DifferentialOps.agree(several, headConflict("E1", 1), headConflict("E1", 0)))
        .isFalse();
    // the head 10 is not the head 1 whatever the text before it says
    assertThat(DifferentialOps.agree(several, headConflict("E1", 10), headConflict("E1", 1)))
        .isFalse();
  }

  @Test
  void aRefusalIsNeverTheSameAnswerAsASuccessOrAnEarlierRefusal() {
    Commit several =
        unit(decision("E1", HeadMode.CURRENT, "d1"), decision("E2", HeadMode.CURRENT, "d1"));

    assertThat(
            DifferentialOps.agree(
                several,
                DifferentialOps.REFUSED_WHILE_PROCESSING,
                "INSERTED d1 seq=1 supersedes=-; "))
        .isFalse();
    assertThat(DifferentialOps.agree(several, headConflict("E1", 0), "INSERTED d1 seq=1; "))
        .isFalse();
    // a refusal before the first write is compared in full, in a unit of any size
    assertThat(
            DifferentialOps.agree(
                several,
                "throws java.lang.IllegalArgumentException: a report body must be an envelope",
                DifferentialOps.REFUSED_WHILE_PROCESSING))
        .isFalse();
  }

  @Test
  void aUnitOfTwoDecisionsOfOneChainAnswersWithTheStoredHeadOnTheInMemoryLedger() {
    ManualClock clock = new ManualClock(Instant.parse("2026-10-09T08:00:00Z"));
    InMemoryLedgerStore ledger = new InMemoryLedgerStore(clock);
    Side side = new Side(strict -> ledger, clock, (subject, versionSeq, body) -> 0, "Nt");

    // both decisions expect the head from before the unit (0); the first would make it 1
    String answer =
        DifferentialOps.outcome(
            unit(decision("E1", HeadMode.CURRENT, "d1"), decision("E1", HeadMode.CURRENT, "d2")),
            side);

    assertThat(answer).isEqualTo(headConflict("E1", 0));
  }
}
