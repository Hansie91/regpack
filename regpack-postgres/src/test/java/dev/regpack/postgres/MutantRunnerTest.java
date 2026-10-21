package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The launcher helper of {@link PgSchemaTeethIT} runs without a server, so its mechanics are proven
 * here and not only at CP-3: a named test method that is a default method of a contract interface
 * is found and run, and a test that fails, by assertion or by exception, is reported by its method
 * name; a test that passes is not.
 */
class MutantRunnerTest {
  /** A stand-in for the contract interfaces of the testkit (test methods are default methods). */
  interface Probe {
    @Test
    default void passes() {}

    @Test
    default void failsByAssertion() {
      fail("this test fails by design");
    }

    @Test
    default void failsByCause() {
      throw new IllegalStateException("outer", new IllegalArgumentException("inner"));
    }

    @Test
    default void failsByException() {
      throw new IllegalStateException("this test fails by design");
    }
  }

  /** Never run by the build: tagged like the mutant classes, and nested. */
  @Tag("mutant")
  static class Binding implements Probe {}

  @Test
  void aNamedDefaultMethodThatFailsByAssertionIsReportedByName() {
    MutantRunner.Outcome outcome = MutantRunner.run(Binding.class, Set.of("failsByAssertion"));

    assertThat(outcome.found()).isEqualTo(1);
    assertThat(outcome.failed()).containsExactly("failsByAssertion");
  }

  @Test
  void aNamedDefaultMethodThatThrowsIsReportedByName() {
    MutantRunner.Outcome outcome = MutantRunner.run(Binding.class, Set.of("failsByException"));

    assertThat(outcome.found()).isEqualTo(1);
    assertThat(outcome.failed()).containsExactly("failsByException");
  }

  @Test
  void theFailureTextCarriesTheClassAndMessageOfTheExceptionAndOfItsCauses() {
    MutantRunner.Outcome outcome = MutantRunner.run(Binding.class, Set.of("failsByCause"));

    assertThat(outcome.failures()).containsOnlyKeys("failsByCause");
    assertThat(outcome.failures().get("failsByCause").split("\n"))
        .containsExactly(
            "java.lang.IllegalStateException: outer", "java.lang.IllegalArgumentException: inner");
    // a test that fails by assertion carries the assertion text
    assertThat(MutantRunner.run(Binding.class, Set.of("failsByAssertion")).failures())
        .containsOnlyKeys("failsByAssertion")
        .extractingByKey("failsByAssertion")
        .asString()
        .contains("this test fails by design");
  }

  @Test
  void aNamedMethodThatPassesIsNotReportedAndMethodsThatAreNotNamedAreNotRun() {
    MutantRunner.Outcome outcome = MutantRunner.run(Binding.class, Set.of("passes"));

    assertThat(outcome.found()).isEqualTo(1);
    assertThat(outcome.failed()).isEmpty();
  }

  @Test
  void severalNamesGiveOneRunWithOneFindingEach() {
    MutantRunner.Outcome outcome =
        MutantRunner.run(Binding.class, Set.of("passes", "failsByAssertion", "failsByException"));

    assertThat(outcome.found()).isEqualTo(3);
    assertThat(outcome.failed()).containsExactlyInAnyOrder("failsByAssertion", "failsByException");
  }
}
