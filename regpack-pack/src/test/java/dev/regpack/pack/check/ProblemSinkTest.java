package dev.regpack.pack.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.pack.PackFormat;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The bounded problem sink: the caps, the single marker and the order (plan finding PACKMOD-2). */
class ProblemSinkTest {
  private static PackProblem problem(int n) {
    return new PackProblem("TABLE_SHAPE", "scope/a.yaml", n, "problem " + n);
  }

  @Test
  void theCapsArePinned() {
    assertThat(PackFormat.MAX_PROBLEMS_PER_FILE).isEqualTo(100);
    assertThat(PackFormat.MAX_PROBLEMS_PER_LOAD).isEqualTo(1000);
    assertThat(ProblemSink.forFile().cap()).isEqualTo(100);
    assertThat(ProblemSink.forLoad().cap()).isEqualTo(1000);
  }

  @Test
  void theCapItselfFitsAndTheNextProblemBecomesTheMarker() {
    ProblemSink sink = new ProblemSink(100);
    for (int i = 1; i <= 100; i++) {
      sink.add(problem(i));
    }
    assertThat(sink.truncated()).isFalse();
    List<PackProblem> atCap = sink.sorted("scope/a.yaml");
    assertThat(atCap).hasSize(100);
    assertThat(atCap).noneMatch(p -> p.code().equals(ProblemSink.TRUNCATED));

    sink.add(problem(101));
    assertThat(sink.truncated()).isTrue();
    List<PackProblem> over = sink.sorted("scope/a.yaml");
    assertThat(over).hasSize(101);
    PackProblem marker = over.get(100);
    assertThat(marker.code()).isEqualTo("PROBLEMS_TRUNCATED");
    assertThat(marker.path()).isEqualTo("scope/a.yaml");
    assertThat(marker.line()).isZero();
    assertThat(marker.message()).isEqualTo("1 further problems not shown");
    assertThat(over.subList(0, 100)).isEqualTo(atCap);
  }

  @Test
  void addsAfterTheMarkerAreIgnoredButCounted() {
    ProblemSink sink = new ProblemSink(3);
    for (int i = 1; i <= 10; i++) {
      sink.add(problem(i));
    }
    List<PackProblem> shown = sink.sorted("p");
    assertThat(shown).hasSize(4);
    assertThat(shown.subList(0, 3)).extracting(PackProblem::line).containsExactly(1, 2, 3);
    assertThat(shown.get(3).message()).isEqualTo("7 further problems not shown");
    assertThat(sink.count()).isEqualTo(10);
    assertThat(sink.isEmpty()).isFalse();
  }

  @Test
  void aBelowCapInputIsUnchangedAndSorted() {
    ProblemSink sink = ProblemSink.forFile();
    sink.add(problem(9));
    sink.add(problem(2));
    sink.add(problem(5));
    assertThat(sink.sorted("scope/a.yaml")).extracting(PackProblem::line).containsExactly(2, 5, 9);
    assertThat(sink.truncated()).isFalse();
    assertThat(new ProblemSink(5).isEmpty()).isTrue();
  }

  @Test
  void theCountSeesEveryProblemSoThatBeforeAndAfterComparisonsKeepWorking() {
    ProblemSink sink = new ProblemSink(2);
    sink.add(problem(1));
    sink.add(problem(2));
    int before = sink.count();
    sink.add(problem(3));
    assertThat(sink.count()).isGreaterThan(before);
  }

  @Test
  void theLoadCapHoldsAcrossFiles() {
    ProblemSink load = ProblemSink.forLoad();
    for (int file = 0; file < 12; file++) {
      ProblemSink one = ProblemSink.forFile();
      for (int i = 1; i <= 250; i++) {
        one.add(new PackProblem("TABLE_SHAPE", "scope/f" + file + ".yaml", i, "p"));
      }
      load.addAll(one.sorted("scope/f" + file + ".yaml"));
    }
    List<PackProblem> shown = load.sorted("pack.yaml");
    assertThat(shown).hasSize(1001);
    assertThat(shown.get(1000).code()).isEqualTo("PROBLEMS_TRUNCATED");
    assertThat(shown.get(1000).path()).isEqualTo("pack.yaml");
    assertThat(load.truncated()).isTrue();
  }

  @Test
  void markersOrderAfterEveryOtherProblemWhateverThePath() {
    PackProblem late = new PackProblem("TABLE_SHAPE", "zzz.yaml", 99, "last real problem");
    PackProblem marker = new PackProblem("PROBLEMS_TRUNCATED", "aaa.yaml", 0, "3 more");
    PackProblem otherMarker = new PackProblem("PROBLEMS_TRUNCATED", "bbb.yaml", 0, "3 more");
    List<PackProblem> list = new ArrayList<>(List.of(otherMarker, marker, late));
    list.sort(null);
    assertThat(list).containsExactly(late, marker, otherMarker);
  }

  @Test
  void capKeepsTheFirstSortedProblemsAndAddsOneMarker() {
    List<PackProblem> many = new ArrayList<>();
    for (int i = 50; i >= 1; i--) {
      many.add(problem(i));
    }
    List<PackProblem> capped = ProblemSink.cap(many, 10, "pack.yaml");
    assertThat(capped).hasSize(11);
    assertThat(capped.subList(0, 10))
        .extracting(PackProblem::line)
        .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    assertThat(capped.get(10).code()).isEqualTo("PROBLEMS_TRUNCATED");
    assertThat(capped.get(10).message()).isEqualTo("40 further problems not shown");

    assertThat(ProblemSink.cap(many.subList(0, 10), 10, "pack.yaml")).hasSize(10);
    assertThat(ProblemSink.cap(List.of(), 10, "pack.yaml")).isEmpty();
  }

  @Test
  void theCapMustBePositive() {
    assertThatThrownBy(() -> new ProblemSink(0)).isInstanceOf(IllegalArgumentException.class);
  }
}
