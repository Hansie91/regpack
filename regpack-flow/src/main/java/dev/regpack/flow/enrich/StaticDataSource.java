package dev.regpack.flow.enrich;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.flow.ingest.CsvReader;
import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import dev.regpack.ports.Clock;
import dev.regpack.ports.connector.CheckResult;
import dev.regpack.ports.connector.ConnectorKind;
import dev.regpack.ports.connector.ConnectorSpec;
import dev.regpack.ports.connector.EnrichmentSource;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A file-backed enrichment source (plan W1a, task T8): the account-to-client table of a firm, read
 * once from a CSV text. The header is {@code key,lei} with an optional third column {@code as_of}.
 * A key with a row is a hit whose canonical bytes are {@code {"lei":"<value>"}} and whose as-of is
 * the as-of of the row, or, when the row has none, the as-of of the request; a key without a row is
 * a miss whose canonical bytes are {@code {"key":"<key>","outcome":"MISS"}}. The source never fails
 * and never reads time: a failure belongs to a source that can fail, and a test that needs one
 * wraps this class.
 *
 * <p>Like every lookup source it refuses a request with a duplicate key or with more keys than its
 * {@link Limits}, with an {@link IllegalArgumentException} that names no key. Immutable after
 * construction, so thread-safe.
 */
public final class StaticDataSource implements EnrichmentSource {
  private static final String NAME = "static-data";
  private static final int MAX_CELL_CHARS = 256;
  private static final int MAX_ROWS = 100_000;
  private static final int MAX_COLUMNS = 16;

  private record Row(String lei, Optional<Instant> asOf) {}

  private final Map<String, Row> rows;
  private final Limits limits;

  /**
   * @param csv the text of the table, header {@code key,lei[,as_of]}; {@code as_of} is an ISO
   *     instant with at most six fraction digits, or empty
   * @param clock accepted for the connector contract: a lookup is answered as of the request, so
   *     the source itself reads no time
   * @param limits the limits of a request
   * @throws IllegalArgumentException the header is wrong, a key repeats, or a cell is empty or not
   *     an instant (the message names the line and never a cell)
   * @throws dev.regpack.flow.ingest.CsvException the text is not a CSV table
   */
  public StaticDataSource(String csv, Clock clock, Limits limits) {
    Objects.requireNonNull(csv, "csv");
    Objects.requireNonNull(clock, "clock");
    this.limits = Objects.requireNonNull(limits, "limits");
    CsvReader.Table table =
        CsvReader.read(csv.getBytes(UTF_8), MAX_CELL_CHARS, MAX_ROWS, MAX_COLUMNS);
    List<String> header = table.header();
    boolean withAsOf = header.size() == 3;
    boolean shaped =
        (header.size() == 2 || withAsOf)
            && header.get(0).equals("key")
            && header.get(1).equals("lei")
            && (!withAsOf || header.get(2).equals("as_of"));
    if (!shaped) {
      throw new IllegalArgumentException("static data header must be key,lei or key,lei,as_of");
    }
    Map<String, Row> read = new HashMap<>();
    for (CsvReader.Row row : table.rows()) {
      String key = row.cells().get(0);
      String lei = row.cells().get(1);
      if (key.isBlank() || lei.isBlank()) {
        throw refused(row, "the key or the lei is empty");
      }
      Optional<Instant> asOf = Optional.empty();
      if (withAsOf && !row.cells().get(2).isEmpty()) {
        asOf = Optional.of(instant(row, row.cells().get(2)));
      }
      if (read.put(key, new Row(lei, asOf)) != null) {
        throw refused(row, "the key occurs twice");
      }
    }
    this.rows = Map.copyOf(read);
  }

  private static Instant instant(CsvReader.Row row, String cell) {
    Instant parsed;
    try {
      parsed = Instant.parse(cell);
    } catch (DateTimeParseException e) {
      // the parser's text names the cell: it is not passed on
      throw refused(row, "as_of is not an ISO instant");
    }
    if (parsed.getNano() % 1000 != 0) {
      throw refused(row, "as_of has more than six fraction digits");
    }
    return parsed;
  }

  private static IllegalArgumentException refused(CsvReader.Row row, String reason) {
    return new IllegalArgumentException("static data line " + row.line() + ": " + reason);
  }

  @Override
  public ConnectorSpec spec() {
    return new ConnectorSpec(NAME, "0.1.0", ConnectorKind.LOOKUP, Map.of());
  }

  @Override
  public CheckResult check() {
    return new CheckResult(true, "static data source with " + rows.size() + " rows");
  }

  @Override
  public Limits limits() {
    return limits;
  }

  @Override
  public Result lookup(Request request) {
    List<String> keys = request.keys();
    if (keys.size() > limits.maxKeysPerRequest()) {
      throw new IllegalArgumentException("a lookup request has more keys than the limit");
    }
    if (new HashSet<>(keys).size() != keys.size()) {
      throw new IllegalArgumentException("the keys of a lookup request must be distinct");
    }
    Instant contextAsOf = request.context().asOf();
    List<KeyOutcome> outcomes = new ArrayList<>(keys.size());
    for (String key : keys) {
      Row row = rows.get(key);
      if (row == null) {
        byte[] canonical = Json.canonical(Map.of("key", key, "outcome", "MISS")).getBytes(UTF_8);
        outcomes.add(new KeyOutcome.Miss(key, contextAsOf, canonical, Sha256.of(canonical)));
      } else {
        byte[] canonical = Json.canonical(Map.of("lei", row.lei())).getBytes(UTF_8);
        outcomes.add(
            new KeyOutcome.Hit(
                key, row.asOf().orElse(contextAsOf), canonical, Sha256.of(canonical)));
      }
    }
    return new Result(outcomes);
  }
}
