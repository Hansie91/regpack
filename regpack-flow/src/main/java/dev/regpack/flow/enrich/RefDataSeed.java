package dev.regpack.flow.enrich;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.flow.ingest.CsvReader;
import dev.regpack.ports.refdata.ReferenceData.Entry;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads a reference seed: a CSV file of reference rows, each valid on a half-open interval of dates
 * (plan W1a, task T8). The header is {@code key}, one or more attribute columns, {@code valid_from}
 * and {@code valid_to}, in that order. {@code valid_from} is an ISO date; {@code valid_to} is an
 * ISO date after it, or empty for a row without an end. An empty attribute cell leaves the
 * attribute out. Two rows of one key whose intervals overlap are refused, in any order of the rows.
 *
 * <p>A refusal is an {@link IllegalArgumentException} that names the line and a fixed reason and
 * never a cell; a file that is no CSV is the reader's {@link dev.regpack.flow.ingest.CsvException}.
 * The seeds of the slice are synthetic and closed: an identifier that is not in them is unknown.
 */
public final class RefDataSeed {
  private static final int MAX_CELL_CHARS = 256;
  private static final int MAX_ROWS = 10_000;
  private static final int MAX_COLUMNS = 64;
  private static final Pattern ATTRIBUTE = Pattern.compile("[a-z][a-z0-9_]{0,63}");

  private RefDataSeed() {}

  /**
   * The rows of a seed in file order.
   *
   * @param csv the text of the seed
   * @throws IllegalArgumentException the header, a date or an interval is wrong, or two intervals
   *     of one key overlap
   * @throws dev.regpack.flow.ingest.CsvException the text is not a CSV table
   */
  public static List<Entry> parse(String csv) {
    Objects.requireNonNull(csv, "csv");
    CsvReader.Table table =
        CsvReader.read(csv.getBytes(UTF_8), MAX_CELL_CHARS, MAX_ROWS, MAX_COLUMNS);
    List<String> header = table.header();
    int n = header.size();
    if (n < 4
        || !header.get(0).equals("key")
        || !header.get(n - 2).equals("valid_from")
        || !header.get(n - 1).equals("valid_to")) {
      throw new IllegalArgumentException(
          "reference seed header must be key, attribute columns, valid_from, valid_to");
    }
    for (int i = 1; i < n - 2; i++) {
      if (!ATTRIBUTE.matcher(header.get(i)).matches()) {
        throw new IllegalArgumentException("reference seed attribute names must be lower case");
      }
    }
    List<Entry> entries = new ArrayList<>();
    Map<String, List<Entry>> byKey = new HashMap<>();
    for (CsvReader.Row row : table.rows()) {
      Entry entry = entry(header, row);
      entries.add(entry);
      byKey.computeIfAbsent(entry.key(), k -> new ArrayList<>()).add(entry);
    }
    for (List<Entry> group : byKey.values()) {
      requireNoOverlap(group);
    }
    return List.copyOf(entries);
  }

  private static Entry entry(List<String> header, CsvReader.Row row) {
    int n = header.size();
    List<String> cells = row.cells();
    String key = cells.get(0);
    if (key.isBlank()) {
      throw refused(row, "the key is empty");
    }
    Map<String, String> attributes = new LinkedHashMap<>();
    for (int i = 1; i < n - 2; i++) {
      if (!cells.get(i).isEmpty()) {
        attributes.put(header.get(i), cells.get(i));
      }
    }
    LocalDate from = date(row, cells.get(n - 2), "valid_from is not an ISO date");
    Optional<LocalDate> to =
        cells.get(n - 1).isEmpty()
            ? Optional.empty()
            : Optional.of(date(row, cells.get(n - 1), "valid_to is not an ISO date"));
    if (to.isPresent() && !to.get().isAfter(from)) {
      throw refused(row, "valid_to is not after valid_from");
    }
    return new Entry(key, attributes, from, to);
  }

  private static LocalDate date(CsvReader.Row row, String cell, String reason) {
    try {
      return LocalDate.parse(cell);
    } catch (DateTimeParseException e) {
      // the parser's text names the cell: it is not passed on
      throw refused(row, reason);
    }
  }

  private static IllegalArgumentException refused(CsvReader.Row row, String reason) {
    return new IllegalArgumentException("reference seed line " + row.line() + ": " + reason);
  }

  private static void requireNoOverlap(List<Entry> group) {
    List<Entry> sorted = new ArrayList<>(group);
    sorted.sort(Comparator.comparing(Entry::validFrom));
    for (int i = 1; i < sorted.size(); i++) {
      Optional<LocalDate> previousEnd = sorted.get(i - 1).validTo();
      if (previousEnd.isEmpty() || previousEnd.get().isAfter(sorted.get(i).validFrom())) {
        throw new IllegalArgumentException("reference seed has two rows of one key that overlap");
      }
    }
  }
}
