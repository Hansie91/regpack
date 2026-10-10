package dev.regpack.bench.data;

import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A generated rule pack: equal decision tables, each ending in a catch-all row. */
public record GeneratedPack(
    String id, long seed, PackShape shape, int ruleCount, List<List<GeneratedRule>> tables) {

  public GeneratedPack {
    List<List<GeneratedRule>> copy = new ArrayList<>(tables.size());
    for (List<GeneratedRule> table : tables) {
      copy.add(List.copyOf(table));
    }
    tables = List.copyOf(copy);
  }

  /** The canonical JSON of the tables: a list of tables, each a list of rule objects. */
  public String irJson() {
    List<Object> out = new ArrayList<>(tables.size());
    for (List<GeneratedRule> table : tables) {
      List<Object> rows = new ArrayList<>(table.size());
      for (GeneratedRule rule : table) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rule.id());
        row.put("table", rule.table());
        row.put("priority", rule.priority());
        row.put("when", rule.when());
        row.put("outcome", rule.outcome());
        row.put("reason", rule.reason());
        rows.add(row);
      }
      out.add(rows);
    }
    return Json.canonical(out);
  }

  /** SHA-256 of {@link #irJson()}. */
  public Sha256 digest() {
    return Sha256.ofUtf8(irJson());
  }
}
