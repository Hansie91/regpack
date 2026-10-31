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
 * several decisions (D33), what a unit of two decisions of one chain answers on the in-memory
 * ledger, which is what the comparison is fed with, and the length of the first line of a failure.
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
                several, headConflict("E2", 0), DifferentialOps.REFUSED_WHILE_PROCESSING))
        .isTrue();
    // two stale heads on two event identities: the ledgers may name either one
    Commit twoStale =
        unit(decision("E1", HeadMode.AHEAD, "d1"), decision("E2", HeadMode.AHEAD, "d1"));
    assertThat(DifferentialOps.agree(twoStale, headConflict("E1", 1), headConflict("E2", 0)))
        .isTrue();
  }

  @Test
  void twoHeadConflictsOnTheSameEventIdentityMustNameTheSameHead() {
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

  @Test
  void aUnitOfTwoDecisionsOfOneChainNamesTheStoredHeadAlsoWhenItIsNotZero() {
    ManualClock clock = new ManualClock(Instant.parse("2026-10-09T08:00:00Z"));
    InMemoryLedgerStore ledger = new InMemoryLedgerStore(clock);
    Side side = new Side(strict -> ledger, clock, (subject, versionSeq, body) -> 0, "Nt");

    // the chain has one decision, so the stored head is 1
    assertThat(DifferentialOps.outcome(unit(decision("E1", HeadMode.CURRENT, "d0")), side))
        .startsWith("INSERTED");
    // both decisions expect that head; the first would make it 2, and the answer names the stored 1
    String answer =
        DifferentialOps.outcome(
            unit(decision("E1", HeadMode.CURRENT, "d1"), decision("E1", HeadMode.CURRENT, "d2")),
            side);

    assertThat(answer).isEqualTo(headConflict("E1", 1));
  }

  @Test
  void theFirstLineOfAFailureFitsTheAnnotationInBothModes() {
    String inMemory = "abcdefghij".repeat(20);
    String postgres = "ABCDEFGHIJ".repeat(20);
    String option = " -Dregpack.differential.exactUnits=true";

    for (boolean exactUnits : new boolean[] {false, true}) {
      for (long seed : new long[] {1, Long.MAX_VALUE, Long.MIN_VALUE}) {
        for (int sequence : new int[] {0, 38, 599, 9999}) {
          for (int operations : new int[] {60, 999}) {
            String line =
                PgInMemoryDifferentialIT.firstLine(
                    seed, sequence, operations, exactUnits, inMemory, postgres);

            String context =
                "exactUnits %s, seed %d, sequence %d, operations %d"
                    .formatted(exactUnits, seed, sequence, operations);
            // 300 is the recollected cut of the check-run annotation [R], not the constant under
            // test
            assertThat(line.length()).as(context).isLessThanOrEqualTo(300);
            assertThat(line.contains(option)).as(context).isEqualTo(exactUnits);
            assertThat(line).as(context).startsWith("seed " + seed + ", sequence " + sequence);
            assertThat(line).as(context).contains(": in-memory abcdefghij");
            assertThat(line).as(context).contains(" / postgres ABCDEFGHIJ");
          }
        }
      }
    }

    String runAgain =
        "-Dregpack.differential.seeds=1 -Dregpack.differential.sequences=39"
            + " -Dregpack.differential.operations=60";
    // the default mode keeps the cut of 60 characters: the report of the default run did not change
    assertThat(PgInMemoryDifferentialIT.firstLine(1, 38, 60, false, inMemory, postgres))
        .isEqualTo(
            "seed 1, sequence 38, run again: "
                + runAgain
                + ": in-memory "
                + inMemory.substring(0, 60)
                + "... / postgres "
                + postgres.substring(0, 60)
                + "...");
    // the longer option of exactUnits takes room from the answers: they are cut at 48
    assertThat(PgInMemoryDifferentialIT.firstLine(1, 38, 60, true, inMemory, postgres))
        .isEqualTo(
            "seed 1, sequence 38, run again: "
                + runAgain
                + option
                + ": in-memory "
                + inMemory.substring(0, 48)
                + "... / postgres "
                + postgres.substring(0, 48)
                + "...");
    // an answer that fits is not cut and gets no dots
    assertThat(PgInMemoryDifferentialIT.firstLine(1, 38, 60, true, "ok", "throws x"))
        .endsWith(": in-memory ok / postgres throws x");
    // only absurd values (a seed of 20 characters and a sequence and an operations count of 10
    // digits, with exactUnits) leave less than 20 characters for an answer, and 20 are kept then
    assertThat(
            PgInMemoryDifferentialIT.firstLine(
                Long.MIN_VALUE, Integer.MAX_VALUE - 1, Integer.MAX_VALUE, true, inMemory, postgres))
        .endsWith(
            ": in-memory "
                + inMemory.substring(0, 20)
                + "... / postgres "
                + postgres.substring(0, 20)
                + "...");
  }
}
