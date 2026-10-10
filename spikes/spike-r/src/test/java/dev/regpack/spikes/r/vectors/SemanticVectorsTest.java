package dev.regpack.spikes.r.vectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SemanticVectorsTest {
  private static String rawFile() throws IOException {
    try (InputStream in = SemanticVectorsTest.class.getResourceAsStream("/semantic-vectors.tsv")) {
      assertThat(in).as("resource semantic-vectors.tsv").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void fileHasUniqueIdsAndKnownClasses() throws IOException {
    String raw = rawFile();
    assertThat(raw).doesNotContain("\r").endsWith("\n");
    String[] lines = raw.substring(0, raw.length() - 1).split("\n", -1);
    assertThat(lines).hasSize(47);
    for (String line : lines) {
      assertThat(line.split("\t", -1)).as("columns of: " + line).hasSize(7);
    }

    List<Vector> vectors = VectorFile.load();
    assertThat(vectors).hasSize(47);
    Set<String> ids = new HashSet<>();
    for (Vector v : vectors) {
      assertThat(ids.add(v.id())).as("duplicate id " + v.id()).isTrue();
      assertThat(v.vectorClass()).isIn("MUST", "SHOULD", "DECIDE");
      assertThat(v.expression()).isNotBlank();
      assertThat(v.expected()).isNotBlank();
    }
    assertThat(vectors.get(0).id()).isEqualTo("D01");
    assertThat(vectors.get(46).id()).isEqualTo("L01");
  }

  @Test
  void classCountsArePinned() {
    List<Vector> vectors = VectorFile.load();
    assertThat(vectors.stream().filter(v -> v.vectorClass().equals("MUST")).count()).isEqualTo(38);
    assertThat(vectors.stream().filter(v -> v.vectorClass().equals("SHOULD")).count()).isEqualTo(3);
    assertThat(vectors.stream().filter(v -> v.vectorClass().equals("DECIDE")).count()).isEqualTo(6);
  }

  @Test
  void budgetsAndCeilingsAreOnlyOnTheCostVectors() {
    List<Vector> vectors = VectorFile.load();
    for (Vector v : vectors) {
      boolean cost = v.id().startsWith("C0");
      assertThat(v.budget().isPresent() || v.ceiling().isPresent()).as(v.id()).isEqualTo(cost);
    }
    Vector c01 = vectors.stream().filter(v -> v.id().equals("C01")).findFirst().orElseThrow();
    assertThat(c01.budget().getAsLong()).isEqualTo(10_000L);
    assertThat(c01.bindings()).containsEntry("items", "l:@gen(10001)");
    Vector c03 = vectors.stream().filter(v -> v.id().equals("C03")).findFirst().orElseThrow();
    assertThat(c03.ceiling().getAsLong()).isEqualTo(1_000L);
  }

  @Test
  void parseRejectsMalformedRows() {
    assertThatThrownBy(() -> VectorFile.parse("X01\tMUST\ttrue\t{}\tT\t\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("seven");
    assertThatThrownBy(() -> VectorFile.parse("X01\tWISH\ttrue\t{}\tT\t\t\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("class");
    assertThatThrownBy(
            () -> VectorFile.parse("X01\tMUST\ttrue\t{}\tT\t\t\nX01\tMUST\ttrue\t{}\tT\t\t\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");
  }
}
