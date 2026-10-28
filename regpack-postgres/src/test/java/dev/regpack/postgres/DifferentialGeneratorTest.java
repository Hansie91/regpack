package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;

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

  private static List<String> describe(List<Op> ops) {
    return ops.stream().map(Op::describe).toList();
  }
}
