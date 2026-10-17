package dev.regpack.pack.model;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A decision table (hit policy FIRST, explicit priority). The constructor copies its input and
 * keeps the rows ascending by priority (a stable sort: rows of equal priority keep their file
 * order, which the table checks report as {@code PRIORITY_DUPLICATE}).
 *
 * @param path the pack path of the file, for example {@code scope/scope.yaml}
 * @param id the table id
 * @param inputs the input paths the table declares it reads
 * @param catchAllRowId the id of the mandatory catch-all row
 * @param rows the rows, ascending by priority
 */
public record DecisionTable(
    String path, String id, List<String> inputs, String catchAllRowId, List<TableRow> rows) {
  public DecisionTable {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(catchAllRowId, "catchAllRowId");
    inputs = List.copyOf(inputs);
    rows = rows.stream().sorted(Comparator.comparingInt(TableRow::priority)).toList();
  }
}
