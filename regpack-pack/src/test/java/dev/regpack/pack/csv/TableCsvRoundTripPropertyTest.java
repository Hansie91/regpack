package dev.regpack.pack.csv;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.check.TableChecks;
import dev.regpack.pack.ir.PackIr;
import dev.regpack.pack.model.DecisionTable;
import dev.regpack.pack.model.TableParser;
import dev.regpack.pack.yaml.StrictYaml;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** AT-8: a decision table survives a CSV export and re-import unchanged (plan task T21). */
class TableCsvRoundTripPropertyTest {
  @Test
  void randomTablesSurviveExportAndImportUnchanged() {
    SplittableRandom rnd = new SplittableRandom(7);
    for (int n = 0; n < 500; n++) {
      DecisionTable table = RandomTables.table(rnd);
      String label = "table " + n;
      assertThat(TableChecks.check(table)).as(label + " is valid").isEmpty();
      DecisionTable header =
          new DecisionTable(
              table.path(), table.id(), table.inputs(), table.catchAllRowId(), List.of());

      List<PackProblem> problems = new ArrayList<>();
      Optional<DecisionTable> back = TableCsv.importRows(TableCsv.export(table), header, problems);

      assertThat(problems).as(label).isEmpty();
      assertThat(back).as(label).contains(table);
      assertThat(TableYamlWriter.write(back.orElseThrow()))
          .as(label)
          .isEqualTo(TableYamlWriter.write(table));
      assertThat(PackIr.table(back.orElseThrow())).as(label).isEqualTo(PackIr.table(table));

      // and the canonical YAML reads back to the same table as well
      List<PackProblem> yamlProblems = new ArrayList<>();
      Optional<DecisionTable> fromYaml =
          TableParser.parse(
              table.path(), StrictYaml.parse(TableYamlWriter.write(table)), yamlProblems);
      assertThat(yamlProblems).as(label).isEmpty();
      assertThat(fromYaml).as(label).contains(table);
    }
  }
}
