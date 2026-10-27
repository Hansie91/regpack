package dev.regpack.postgres;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the approved schema documents and the decisions records (test support). The two
 * machine-read blocks of a schema document are the contract of its migration script: {@code
 * docs/ledger/schema-design.md} for {@code V001}, {@code docs/ledger/schema-design-p3b.md} for
 * {@code V002}, {@code docs/ledger/schema-design-p3c.md} for {@code V003}. Every script has its own
 * document (the approved one is frozen; a change is a new script and a new document).
 */
public final class SchemaDocument {
  /** One row of the constraint block: a constraint, index or trigger, its table and its kind. */
  public record Entry(String name, String table, String kind) {}

  /** The record that keeps the pins and the approval of the P3a document (V001). */
  public static final String LEDGER_DECISIONS = "docs/ledger/decisions.md";

  /** The record that keeps the pins and the approval of the P3b document (V002). */
  public static final String EVIDENCE_DECISIONS = "docs/evidence/decisions.md";

  /** The record that keeps the pins and the approval of the P3c document (V003). */
  public static final String P3C_DECISIONS = "docs/evidence/p3c-decisions.md";

  /** The document of each script, in script order: name of the script to repository path. */
  public static final Map<String, String> DOCUMENT_OF_SCRIPT = documents();

  private SchemaDocument() {}

  private static Map<String, String> documents() {
    Map<String, String> documents = new LinkedHashMap<>();
    documents.put("V001__core.sql", "docs/ledger/schema-design.md");
    documents.put("V002__evidence_manifest.sql", "docs/ledger/schema-design-p3b.md");
    documents.put("V003__retention_and_subject_index.sql", "docs/ledger/schema-design-p3c.md");
    return Collections.unmodifiableMap(documents);
  }

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

  /**
   * SHA-256 of a document with line ends normalised, as {@code sed 's/\r$//' | sha256sum}.
   *
   * @param documentPath repository-relative path of the document
   */
  public static String documentSha256(String documentPath) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(read(documentPath).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every Java platform", e);
    }
  }

  /**
   * The table rows between {@code <!-- schema:NAME -->} and {@code <!-- /schema:NAME -->}, without
   * the header and the separator. Each marker must occur exactly once: the reader takes the first
   * occurrence, so a second mention (a prose example, a copy) would silently change what is read.
   */
  static List<String> block(String text, String name) {
    String open = "<!-- schema:" + name + " -->";
    String close = "<!-- /schema:" + name + " -->";
    int from = text.indexOf(open);
    int to = text.indexOf(close);
    if (from < 0 || to < from) {
      throw new IllegalStateException("block not found: " + name);
    }
    if (text.indexOf(open, from + 1) >= 0 || text.indexOf(close, to + 1) >= 0) {
      throw new IllegalStateException("marker of block " + name + " occurs more than once");
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

  /** The table names of the table block of one document, in document order. */
  public static List<String> tableNames(String documentPath) {
    List<String> names = new ArrayList<>();
    for (String row : block(read(documentPath), "tables")) {
      names.add(unquote(cells(row).get(0)));
    }
    return List.copyOf(names);
  }

  /** The rows of the constraint block of one document, in document order. */
  public static List<Entry> constraintEntries(String documentPath) {
    List<Entry> entries = new ArrayList<>();
    for (String row : block(read(documentPath), "constraints")) {
      List<String> cells = cells(row);
      entries.add(new Entry(unquote(cells.get(0)), unquote(cells.get(1)), cells.get(2)));
    }
    return List.copyOf(entries);
  }

  /** The table names of every document, in script order. */
  public static List<String> allTableNames() {
    List<String> names = new ArrayList<>();
    for (String document : DOCUMENT_OF_SCRIPT.values()) {
      names.addAll(tableNames(document));
    }
    return List.copyOf(names);
  }

  /** The constraint rows of every document, in script order. */
  public static List<Entry> allConstraintEntries() {
    List<Entry> entries = new ArrayList<>();
    for (String document : DOCUMENT_OF_SCRIPT.values()) {
      entries.addAll(constraintEntries(document));
    }
    return List.copyOf(entries);
  }

  /**
   * The value of the line {@code <label>:} of a decisions record, or empty.
   *
   * @param decisionsPath repository-relative path of the record
   * @param label for example {@code approved-schema-sha256}
   */
  public static String approvedSha256(String decisionsPath, String label) {
    Matcher m =
        Pattern.compile("(?m)^" + Pattern.quote(label) + ":[ \\t]*([0-9a-f]*)[ \\t]*$")
            .matcher(read(decisionsPath));
    return m.find() ? m.group(1) : "";
  }

  /** The rows of the table "Pins created" of a decisions record: pin name to value. */
  public static Map<String, String> pins(String decisionsPath) {
    String text = read(decisionsPath);
    int from = text.indexOf("\n## Pins created");
    if (from < 0) {
      throw new IllegalStateException("section Pins created not found in " + decisionsPath);
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
