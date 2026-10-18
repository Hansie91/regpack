package dev.regpack.engine.expr.conformance;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code cost-fixtures.tsv}: three tab-separated columns {@code expression bindings units},
 * the documented cost of one evaluation of cost model v1 (companion contracts section 3).
 */
final class CostFixtures {
  static final String RESOURCE = "/conformance/cost-fixtures.tsv";

  /** One fixture. */
  record Fixture(String expression, String bindings, long units) {}

  private CostFixtures() {}

  static List<Fixture> load() {
    return parse(VectorFile.resourceText(RESOURCE));
  }

  static List<Fixture> parse(String text) {
    List<Fixture> fixtures = new ArrayList<>();
    String[] lines = text.replace("\r\n", "\n").split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (i == lines.length - 1 && lines[i].isEmpty()) {
        continue;
      }
      String[] cols = lines[i].split("\t", -1);
      if (cols.length != 3 || cols[0].isEmpty() || !cols[2].matches("[0-9]{1,9}")) {
        throw new IllegalArgumentException(
            "line " + (i + 1) + ": need expression, bindings, units");
      }
      fixtures.add(new Fixture(cols[0], cols[1], Long.parseLong(cols[2])));
    }
    return List.copyOf(fixtures);
  }
}
