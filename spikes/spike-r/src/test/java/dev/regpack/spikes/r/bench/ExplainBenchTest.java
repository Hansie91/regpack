package dev.regpack.spikes.r.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.engine.explain.Tier1Explainer;
import dev.regpack.model.decision.DecisionRecord;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExplainBenchTest {
  @Test
  void decisionRecordsCoverPathLengthsOneToTwoHundred() {
    List<DecisionRecord> records = ExplainBench.decisionRecords();
    Set<Integer> lengths = new HashSet<>();
    for (DecisionRecord r : records) {
      lengths.add(r.body().path().size());
      assertThat(Tier1Explainer.explain(r)).contains("Path:").contains("Outcome: ");
    }
    assertThat(lengths).hasSize(200);
    assertThat(lengths).contains(1, 100, 200).doesNotContain(0, 201);
  }

  @Test
  void theLongestRecordRendersEveryStep() {
    DecisionRecord longest =
        ExplainBench.decisionRecords().stream()
            .filter(r -> r.body().path().size() == 200)
            .findFirst()
            .orElseThrow();
    String text = Tier1Explainer.explain(longest);
    assertThat(text).contains("\n  200. ").doesNotContain("\n  201. ");
  }

  @Test
  void oneNanosecondSamplePerRequestedRender() {
    long[] samples = ExplainBench.tier1RenderNanos(300, 7L);
    assertThat(samples).hasSize(300);
    assertThat(java.util.Arrays.stream(samples).allMatch(n -> n > 0)).isTrue();
    assertThatThrownBy(() -> ExplainBench.tier1RenderNanos(0, 7L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theSampleOrderIsDeterministicInTheSeed() {
    assertThat(ExplainBench.sampleOrder(50, 3L)).isEqualTo(ExplainBench.sampleOrder(50, 3L));
    assertThat(ExplainBench.sampleOrder(50, 3L)).isNotEqualTo(ExplainBench.sampleOrder(50, 4L));
  }
}
