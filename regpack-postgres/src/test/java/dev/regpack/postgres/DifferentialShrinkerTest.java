package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * The shrinker of the differential test needs no server: it is a pure function of a list and a test
 * (the generator has its own class, {@link DifferentialGeneratorTest}).
 */
class DifferentialShrinkerTest {
  private static List<Integer> numbers(int count) {
    List<Integer> items = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      items.add(i);
    }
    return items;
  }

  @Test
  void aSingleStepThatFailsIsFound() {
    List<Integer> found = DifferentialShrinker.minimize(numbers(60), items -> items.contains(41));

    assertThat(found).containsExactly(41);
  }

  @Test
  void twoStepsThatFailTogetherAreBothKeptAndInTheirOrder() {
    Predicate<List<Integer>> bothInOrder =
        items -> {
          int first = items.indexOf(7);
          int second = items.indexOf(52);
          return first >= 0 && second > first;
        };

    assertThat(DifferentialShrinker.minimize(numbers(60), bothInOrder)).containsExactly(7, 52);
  }

  @Test
  void aResultFromWhichNoSingleStepCanBeRemovedIsReturnedWhateverTheTest() {
    // fails when at least three multiples of five are present: the shortest are three of them
    Predicate<List<Integer>> threeMultiples =
        items -> items.stream().filter(n -> n % 5 == 0).count() >= 3;

    List<Integer> found = DifferentialShrinker.minimize(numbers(60), threeMultiples);

    assertThat(found).hasSize(3).allMatch(n -> n % 5 == 0);
  }

  @Test
  void aListOfOneStepIsKeptAndTheTestIsNotAskedAboutTheEmptyList() {
    List<Integer> found =
        DifferentialShrinker.minimize(
            List.of(3),
            items -> {
              assertThat(items).isNotEmpty();
              return true;
            });

    assertThat(found).containsExactly(3);
  }

  @Test
  void eachStepIsReplacedBySimplerOnesWhileTheTestStillFails() {
    // a step is a number; the simpler steps are n - 1 and n / 2; the test fails while the sum is
    // at least 10
    List<Integer> found =
        DifferentialShrinker.simplify(
            List.of(40, 3),
            n -> n > 0 ? List.of(n / 2, n - 1) : List.of(),
            items -> items.stream().mapToInt(Integer::intValue).sum() >= 10);

    assertThat(found.stream().mapToInt(Integer::intValue).sum()).isGreaterThanOrEqualTo(10);
    // no step can be made simpler without the sum falling below 10
    for (int i = 0; i < found.size(); i++) {
      int step = found.get(i);
      for (int simpler : List.of(step / 2, step - 1)) {
        List<Integer> trial = new ArrayList<>(found);
        trial.set(i, simpler);
        assertThat(trial.stream().mapToInt(Integer::intValue).sum())
            .as("step %d replaced by %d", step, simpler)
            .isLessThan(10);
      }
    }
  }
}
