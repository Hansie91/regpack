package dev.regpack.spikes.r.cel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.spikes.r.vectors.ConformanceHarness;
import dev.regpack.spikes.r.vectors.Vector;
import dev.regpack.spikes.r.vectors.VectorFile;
import dev.regpack.spikes.r.vectors.VectorResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CelJavaConformanceTest {
  @Test
  void everyVectorHasARecordedOutcome() throws IOException {
    List<Vector> vectors = VectorFile.load();
    List<VectorResult> results = ConformanceHarness.run(new CelJavaLayer(), vectors);

    assertThat(results).hasSize(47);
    assertThat(results.stream().map(VectorResult::id).toList())
        .isEqualTo(vectors.stream().map(Vector::id).toList());
    assertThat(results)
        .allSatisfy(
            r ->
                assertThat(Set.of("PASS", "FAIL", "UNSUPPORTED", "FUNCTIONAL"))
                    .contains(r.result()));

    // semantic failures are data, not test failures: they are the first look at R-1b
    Path out = Path.of(System.getProperty("regpack.build.dir", "target")).resolve("cel-matrix.tsv");
    Files.createDirectories(out.getParent());
    String matrix = CelJavaLayer.matrixTsv(results);
    Files.writeString(out, matrix, StandardCharsets.UTF_8);
    System.out.println("cel-java decimalSupport " + CelJavaLayer.decimalSupport(results));
    for (VectorResult r : results) {
      if (!r.result().equals("PASS")) {
        System.out.println(
            "cel-java " + r.vectorClass() + " " + r.id() + " " + r.result() + " " + r.actual());
      }
    }
    assertThat(Files.readAllLines(out, StandardCharsets.UTF_8)).hasSize(47 + 1);
    assertThat(matrix).contains("decimalSupport\t");
    assertThat(matrix).doesNotContain("\r");
  }
}
