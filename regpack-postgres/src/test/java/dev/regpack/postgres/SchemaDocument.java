package dev.regpack.postgres;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the approved schema document and the decisions record (test support). The two machine-read
 * blocks of {@code docs/ledger/schema-design.md} are the contract of {@code V001}.
 */
public final class SchemaDocument {
  /** One row of the constraint block: a constraint, index or trigger, its table and its kind. */
  public record Entry(String name, String table, String kind) {}

  private SchemaDocument() {}

  public static Path repoRoot() {
    return Path.of(System.getProperty("regpack.repo.root")).toAbsolutePath().normalize();
  }

  public static String read(String repoRelativePath) {
    try {
      return Files.readString(repoRoot().resolve(repoRelativePath), StandardCharsets.UTF_8)
          .replace("\r\n", "\n");
    } catch (IOException e) {
      throw new UncheckedIOException(repoRelativePath, e);
    }
  }

  public static String documentText() {
    return read("docs/ledger/schema-design.md");
  }

  public static String decisionsText() {
    return read("docs/ledger/decisions.md");
  }

  /** SHA-256 of the document with line ends normalised, as {@code sed 's/\r$//' | sha256sum}. */
  public static String documentSha256() {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(documentText().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every Java platform", e);
    }
  }

  private static List<String> block(String name) {
    String text = documentText();
    String open = "<!-- schema:" + name + " -->";
    String close = "<!-- /schema:" + name + " -->";
    int from = text.indexOf(open);
    int to = text.indexOf(close);
    if (from < 0 || to < from) {
      throw new IllegalStateException("block not found: " + name);
    }
    List<String> rows = new ArrayList<>();
    for (String line : text.substring(from + open.length(), to).split("\n")) {
      if (line.startsWith("|")) {
        rows.add(line);
      }
    }
    // the first row is the header and the second the separator
    return List.copyOf(rows.subList(2, rows.size()));
  }

  private static List<String> cells(String row) {
    List<String> cells = new ArrayList<>();
    String[] parts = row.split("\\|", -1);
    // a row starts and ends with a pipe, so the first and the last part are empty
    for (int i = 1; i < parts.length - 1; i++) {
      cells.add(parts[i].strip());
    }
    return cells;
  }

  private static String unquote(String cell) {
    if (cell.length() >= 2 && cell.startsWith("`") && cell.endsWith("`")) {
      return cell.substring(1, cell.length() - 1);
    }
    throw new IllegalStateException("first cell is not backticked: " + cell);
  }

  /** The table names of the table block, in document order. */
  public static List<String> tableNames() {
    List<String> names = new ArrayList<>();
    for (String row : block("tables")) {
      names.add(unquote(cells(row).get(0)));
    }
    return List.copyOf(names);
  }

  /** The rows of the constraint block, in document order. */
  public static List<Entry> constraintEntries() {
    List<Entry> entries = new ArrayList<>();
    for (String row : block("constraints")) {
      List<String> cells = cells(row);
      entries.add(new Entry(unquote(cells.get(0)), unquote(cells.get(1)), cells.get(2)));
    }
    return List.copyOf(entries);
  }

  /** The value of the line {@code approved-schema-sha256:} of the decisions record, or empty. */
  public static String approvedSha256() {
    Matcher m =
        Pattern.compile("(?m)^approved-schema-sha256:[ \\t]*([0-9a-f]*)[ \\t]*$")
            .matcher(decisionsText());
    return m.find() ? m.group(1) : "";
  }

  /** The rows of the table "Pins created" of the decisions record: pin name to value. */
  public static Map<String, String> pins() {
    String text = decisionsText();
    int from = text.indexOf("\n## Pins created");
    if (from < 0) {
      throw new IllegalStateException("section Pins created not found");
    }
    int to = text.indexOf("\n## ", from + 1);
    String section = to < 0 ? text.substring(from) : text.substring(from, to);
    Map<String, String> pins = new TreeMap<>();
    List<String> rows = new ArrayList<>();
    for (String line : section.split("\n")) {
      if (line.startsWith("|")) {
        rows.add(line);
      }
    }
    for (String row : rows.subList(Math.min(2, rows.size()), rows.size())) {
      List<String> cells = cells(row);
      if (cells.size() >= 2) {
        pins.put(cells.get(0).replace("`", ""), cells.get(1).replace("`", ""));
      }
    }
    return pins;
  }
}
