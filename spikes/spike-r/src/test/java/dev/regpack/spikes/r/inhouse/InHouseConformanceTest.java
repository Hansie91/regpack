package dev.regpack.spikes.r.inhouse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.spikes.r.vectors.ConformanceHarness;
import dev.regpack.spikes.r.vectors.VectorFile;
import dev.regpack.spikes.r.vectors.VectorResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class InHouseConformanceTest {
  @Test
  void allMustVectorsPass() {
    List<VectorResult> results = ConformanceHarness.run(new InHouseLayer(), VectorFile.load());
    assertThat(results).hasSize(47);
    // SHOULD and DECIDE outcomes are data for the SP-R report, not assertions.
    for (VectorResult r : results) {
      if (!r.vectorClass().equals("MUST")) {
        System.out.println(
            "in-house " + r.vectorClass() + " " + r.id() + " " + r.result() + " " + r.actual());
      }
    }
    assertThat(results)
        .filteredOn(r -> r.vectorClass().equals("MUST"))
        .filteredOn(r -> r.result().equals("FAIL") || r.result().equals("UNSUPPORTED"))
        .as("MUST vectors failed or unsupported")
        .isEmpty();
    assertThat(ConformanceHarness.failures(results, "MUST")).isZero();
  }

  @Test
  void theLayerNeverThrowsAndNamesItself() {
    InHouseLayer layer = new InHouseLayer();
    assertThat(layer.name()).isEqualTo("inhouse");
    assertThat(layer.rewriteFor("D01")).isEmpty();
    assertThat(layer.evaluate("))((", java.util.Map.of(), 100, 100))
        .isInstanceOf(dev.regpack.spikes.r.vectors.Outcome.CompileError.class);
  }
}
