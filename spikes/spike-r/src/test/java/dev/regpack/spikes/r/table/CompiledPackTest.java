package dev.regpack.spikes.r.table;

import static dev.regpack.spikes.r.table.TestRecords.pack;
import static dev.regpack.spikes.r.table.TestRecords.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.PackShape;
import dev.regpack.bench.data.RulePackGen;
import dev.regpack.spikes.r.inhouse.InHouseExpression;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompiledPackTest {
  private static CompiledPack compile(GeneratedPack pack) {
    return CompiledPack.compile(pack, new InHouseExpression());
  }

  @Test
  void compilesAGeneratedPackAndReportsItsDigestAndSize() {
    GeneratedPack generated = RulePackGen.generate(3L, 200, PackShape.S2_FULL_SCAN_WORST_CASE);
    CompiledPack compiled = compile(generated);
    assertThat(compiled.ruleCount()).isEqualTo(200);
    assertThat(compiled.digest()).isEqualTo(generated.digest());
    assertThat(compiled.tables()).hasSize(10);
    assertThat(compiled.tables().get(0)).hasSize(20);
    assertThat(compiled.tables().get(0).get(19).catchAll()).isTrue();
    assertThat(compiled.tables().get(0).get(0).catchAll()).isFalse();
  }

  @Test
  void rowsAreOrderedByAscendingPriority() {
    CompiledPack compiled =
        compile(
            pack(
                "order",
                List.of(
                    List.of(
                        rule("LATE", 1, 500, "event.kind == 'NEW'", "REPORT"),
                        rule("EARLY", 1, 100, "event.kind == 'NEW'", "REPORT"),
                        rule("CATCH", 1, 900, "true", "UNDETERMINED")))));
    assertThat(compiled.tables().get(0).stream().map(CompiledPack.Row::id))
        .containsExactly("EARLY", "LATE", "CATCH");
  }

  @Test
  void rejectsDuplicatePriorities() {
    GeneratedPack duplicated =
        pack(
            "dup",
            List.of(
                List.of(
                    rule("A", 1, 100, "event.kind == 'NEW'", "REPORT"),
                    rule("B", 1, 100, "event.kind == 'AMEND'", "REPORT"),
                    rule("CATCH", 1, 900, "true", "UNDETERMINED"))));
    assertThatThrownBy(() -> compile(duplicated))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("priority");
  }

  @Test
  void rejectsATableWithoutACatchAllAsItsLastRow() {
    GeneratedPack missing =
        pack(
            "no-catch-all",
            List.of(
                List.of(
                    rule("A", 1, 100, "event.kind == 'NEW'", "REPORT"),
                    rule("B", 1, 200, "event.kind == 'AMEND'", "REPORT"))));
    assertThatThrownBy(() -> compile(missing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("catch-all");
    GeneratedPack early =
        pack(
            "early-catch-all",
            List.of(
                List.of(
                    rule("A", 1, 100, "true", "REPORT"),
                    rule("B", 1, 200, "true", "UNDETERMINED"))));
    assertThatThrownBy(() -> compile(early))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("catch-all");
  }

  @Test
  void rejectsAnUnknownOutcomeName() {
    GeneratedPack bad =
        pack(
            "bad-outcome",
            List.of(
                List.of(
                    rule("A", 1, 100, "event.kind == 'NEW'", "MAYBE"),
                    rule("CATCH", 1, 900, "true", "UNDETERMINED"))));
    assertThatThrownBy(() -> compile(bad)).isInstanceOf(IllegalArgumentException.class);
  }
}
