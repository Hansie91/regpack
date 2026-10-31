package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.postgres.DifferentialOps.Commit;
import dev.regpack.postgres.DifferentialOps.HeadMode;
import dev.regpack.postgres.DifferentialOps.Hold;
import dev.regpack.postgres.DifferentialOps.Op;
import dev.regpack.postgres.DifferentialOps.RecordClaims;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The generator of the differential test needs no server: it must give the same sequences for the
 * same seed on every machine, or a reported seed could not be run again, and a long run must reach
 * every kind of operation (the shrinker has its own class, {@link DifferentialShrinkerTest}).
 */
class DifferentialGeneratorTest {
  @Test
  void theSameSeedGivesTheSameSequencesAndAnotherSeedGivesOthers() {
    List<String> first = describe(new DifferentialGenerator(7).sequence(200));
    List<String> again = describe(new DifferentialGenerator(7).sequence(200));
    List<String> other = describe(new DifferentialGenerator(8).sequence(200));

    assertThat(again).isEqualTo(first);
    assertThat(other).isNotEqualTo(first);
  }

  @Test
  void aLongRunReachesEveryKindOfOperation() {
    List<Op> ops = new DifferentialGenerator(1).sequence(2_000);

    assertThat(ops.stream().map(op -> op.getClass().getSimpleName()).distinct())
        .contains(
            "RecordClaims",
            "Hold",
            "Rows",
            "WalkRows",
            "Expired",
            "WalkExpired",
            "SetClock",
            "Index",
            "Refs",
            "WalkRefs",
            "Remove",
            "Commit",
            "ReadEvent");
    // a null list, a list with a null element and an empty list are steps as well
    assertThat(ops.stream().filter(RecordClaims.class::isInstance).map(RecordClaims.class::cast))
        .anyMatch(op -> op.claims() == null)
        .anyMatch(op -> op.claims() != null && op.claims().isEmpty())
        .anyMatch(op -> op.claims() != null && op.claims().contains(null));
    assertThat(ops.stream().filter(Hold.class::isInstance).map(Hold.class::cast))
        .anyMatch(Hold::hold)
        .anyMatch(op -> !op.hold());
  }

  /**
   * The units that T13b (D35) exists for: two decisions of one chain, both expecting the head that
   * the chain has before the unit. If a change of the generator stopped producing them, every other
   * test would stay green and the fuzz would no longer reach the code that names the stored head.
   * The default run of {@link PgInMemoryDifferentialIT} is the seed 1 and 600 sequences of 60
   * operations; the figure is a margin and not an exact stream, so it holds for another JDK
   * release.
   */
  @Test
  void theDefaultRunHasUnitsOfTwoDecisionsOfOneChain() {
    DifferentialGenerator generator = new DifferentialGenerator(1);
    long units = 0;
    for (int sequence = 0; sequence < 600; sequence++) {
      for (Op op : generator.sequence(60)) {
        if (op instanceof Commit commit
            && commit.decisions().size() == 2
            && commit.decisions().get(0).regime().equals(commit.decisions().get(1).regime())
            && commit.decisions().get(0).event().equals(commit.decisions().get(1).event())
            && commit.decisions().get(0).head() == HeadMode.CURRENT
            && commit.decisions().get(1).head() == HeadMode.CURRENT) {
          units++;
        }
      }
    }

    // 141 at the time of writing
    assertThat(units).isGreaterThanOrEqualTo(50);
  }

  private static List<String> describe(List<Op> ops) {
    return ops.stream().map(Op::describe).toList();
  }
}
