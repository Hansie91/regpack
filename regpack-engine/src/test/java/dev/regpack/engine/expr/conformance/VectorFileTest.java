package dev.regpack.engine.expr.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.regpack.engine.expr.conformance.RulingsFile.Row;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The shape of {@code vectors.tsv} (companion contracts section 5.5). */
class VectorFileTest {
  private static final String VALID = "X1\tMUST\t1 == 1\t\tT\t\t\tspec 4 I3: a basis\n";

  @Test
  void fileHasUniqueIdsKnownClassesAndPinnedCounts() {
    List<Vector> vectors = VectorFile.load();
    assertThat(vectors).hasSize(46);
    Set<String> ids = new HashSet<>();
    vectors.forEach(v -> assertThat(ids.add(v.id())).as("unique " + v.id()).isTrue());
    Map<String, Long> counts =
        vectors.stream()
            .collect(
                Collectors.groupingBy(Vector::vectorClass, TreeMap::new, Collectors.counting()));
    assertThat(counts)
        .containsExactly(
            Map.entry("DECIDE", 5L),
            Map.entry("MUST", 12L),
            Map.entry("SHOULD", 19L),
            Map.entry("SPIKE-DESIGN", 10L));
    // L01 is not a vector (spec 6.2: no loops)
    assertThat(ids).doesNotContain("L01");
    assertThat(vectors.stream().map(Vector::expression)).noneMatch(e -> e.contains(".all("));
  }

  @Test
  void fileParserRefusesMalformedRows() {
    assertThat(VectorFile.parse(VALID)).hasSize(1);
    assertThatThrownBy(() -> VectorFile.parse(VALID + VALID)).hasMessageContaining("duplicate");
    assertThatThrownBy(() -> VectorFile.parse(VALID.replace("MUST", "MAY")))
        .hasMessageContaining("unknown class");
    assertThatThrownBy(() -> VectorFile.parse("X1\tMUST\t1 == 1\n")).hasMessageContaining("eight");
    assertThatThrownBy(() -> VectorFile.parse(VALID.replace("\t\t\tspec", "\t5x\t\tspec")))
        .hasMessageContaining("not a number");
    assertThatThrownBy(() -> VectorFile.parse(VALID + "\n"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void mustAndShouldRowsUseNoErrorKinds() {
    for (Vector v : VectorFile.load()) {
      if (v.vectorClass().equals("MUST") || v.vectorClass().equals("SHOULD")) {
        assertThat(v.expected()).as(v.id()).doesNotContain("ERR:");
      }
    }
  }

  @Test
  void decideRowsPointAtRulings() {
    List<Vector> vectors = VectorFile.load();
    Map<String, Vector> byId = vectors.stream().collect(Collectors.toMap(Vector::id, v -> v));
    List<Row> rows = RulingsFile.load();
    assertThat(rows)
        .extracting(Row::id)
        .containsExactlyInAnyOrder("D13", "A04", "A07", "E06", "S07");
    for (Row row : rows) {
      Vector v = byId.get(row.id());
      assertThat(v).as("vector for ruling " + row.id()).isNotNull();
      if (row.status().equals("PROVISIONAL")) {
        assertThat(v.vectorClass()).as(row.id()).isEqualTo("DECIDE");
        assertThat(v.expected()).as(row.id()).isEqualTo(Vector.RULING);
      } else {
        // a ruled DECIDE row changes class to MUST in the same commit
        assertThat(v.vectorClass()).as(row.id()).isEqualTo("MUST");
        assertThat(v.expected()).as(row.id()).isNotEqualTo(Vector.RULING);
      }
    }
    for (Vector v : vectors) {
      boolean decide = v.vectorClass().equals("DECIDE");
      assertThat(v.expected().equals(Vector.RULING)).as(v.id()).isEqualTo(decide);
    }
  }

  @Test
  void everyMustRowCitesItsBasis() {
    for (Vector v : VectorFile.load()) {
      assertThat(v.basis()).as(v.id()).isNotBlank();
      if (v.vectorClass().equals("MUST")) {
        assertThat(v.basis()).as(v.id()).matches("spec [0-9]+(\\.[0-9]+)?\\b.*");
      }
    }
  }

  @Test
  void costFixturesHaveAtLeastTwelveRowsAndTheDocumentedStart() {
    List<CostFixtures.Fixture> fixtures = CostFixtures.load();
    assertThat(fixtures.size()).isGreaterThanOrEqualTo(12);
    assertThat(fixtures.subList(0, 5))
        .extracting(CostFixtures.Fixture::expression, CostFixtures.Fixture::units)
        .containsExactly(
            tuple("1 + 2", 3L),
            tuple("true", 1L),
            tuple("has(a.b)", 1L),
            tuple("x in [1, 2, 3]", 9L),
            tuple("size('abc')", 2L));
    assertThat(fixtures).anyMatch(f -> f.expression().contains("contains"));
  }
}
