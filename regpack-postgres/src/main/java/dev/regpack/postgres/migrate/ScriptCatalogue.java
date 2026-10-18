package dev.regpack.postgres.migrate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ordered migration scripts of the code (D-L7). {@code db/migration/index.txt} lists the script
 * files one per line, in order; only listed scripts are read. A script name is {@code V<three
 * digits>__<lowercase words>.sql}, the versions are 1, 2, 3 ... without a hole.
 *
 * <p>Rules for the text of a script (the splitter below depends on them): a line that starts with
 * two hyphens is a comment and is dropped; a statement ends at a line whose last character is a
 * semicolon outside a dollar-quoted block; {@code $$} appears only as the pair that delimits a
 * function body; no string literal spans a line end that is followed by a semicolon.
 */
public final class ScriptCatalogue {
  private static final String BASE = "/db/migration/";
  private static final String INDEX = BASE + "index.txt";
  private static final Pattern NAME = Pattern.compile("V([0-9]{3})__([a-z0-9_]+)\\.sql");

  /** One script: its version, file name, text with line ends normalised to LF, and checksum. */
  public record Script(int version, String name, String sql, String sha256) {
    public Script {
      Matcher m = NAME.matcher(name);
      if (!m.matches() || Integer.parseInt(m.group(1)) != version || version < 1) {
        throw new IllegalArgumentException("script name does not match its version: " + name);
      }
      if (!sha256.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("not a SHA-256: " + name);
      }
    }

    /** A script whose text is normalised and hashed here. */
    public static Script of(int version, String name, String sql) {
      String normalised = normalise(sql);
      return new Script(version, name, normalised, checksum(normalised));
    }

    /** The statements of the script, see the class comment for the rules. */
    public List<String> statements() {
      return ScriptCatalogue.statements(sql);
    }
  }

  private final List<Script> scripts;

  private ScriptCatalogue(List<Script> scripts) {
    this.scripts = List.copyOf(scripts);
  }

  /** The catalogue of the class path: {@code db/migration/index.txt} and the scripts it lists. */
  public static ScriptCatalogue fromClasspath() {
    List<String> names = new ArrayList<>();
    for (String line : readResource(INDEX).split("\n", -1)) {
      String name = line.strip();
      if (!name.isEmpty()) {
        names.add(name);
      }
    }
    return parse(names, name -> readResource(BASE + name));
  }

  /**
   * Builds a catalogue from index lines and a reader of script text.
   *
   * @throws IllegalArgumentException for an empty index, a name that is not a script name, or
   *     versions that are not 1, 2, 3 ... in the order listed
   */
  static ScriptCatalogue parse(List<String> indexLines, Function<String, String> reader) {
    if (indexLines.isEmpty()) {
      throw new IllegalArgumentException("the migration index lists no script");
    }
    List<Script> scripts = new ArrayList<>();
    for (String name : indexLines) {
      Matcher m = NAME.matcher(name);
      if (!m.matches()) {
        throw new IllegalArgumentException("not a script name: " + name);
      }
      scripts.add(Script.of(Integer.parseInt(m.group(1)), name, reader.apply(name)));
    }
    return of(scripts);
  }

  /** A catalogue of the given scripts, which must be versions 1, 2, 3 ... in order. */
  static ScriptCatalogue of(List<Script> scripts) {
    if (scripts.isEmpty()) {
      throw new IllegalArgumentException("a catalogue needs at least one script");
    }
    for (int i = 0; i < scripts.size(); i++) {
      if (scripts.get(i).version() != i + 1) {
        throw new IllegalArgumentException(
            "script versions must be contiguous from 1 and listed in order: "
                + scripts.get(i).name());
      }
    }
    return new ScriptCatalogue(scripts);
  }

  public List<Script> scripts() {
    return scripts;
  }

  /** The highest version of the catalogue. */
  public int latestVersion() {
    return scripts.get(scripts.size() - 1).version();
  }

  /** The script of a version, if the catalogue has it. */
  public Optional<Script> find(int version) {
    return version >= 1 && version <= scripts.size()
        ? Optional.of(scripts.get(version - 1))
        : Optional.empty();
  }

  /** SHA-256 (lowercase hex) of the UTF-8 text with CRLF replaced by LF. */
  public static String checksum(String sql) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(normalise(sql).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every Java platform", e);
    }
  }

  private static String normalise(String sql) {
    return sql.replace("\r\n", "\n");
  }

  /**
   * Splits script text into statements (rules in the class comment).
   *
   * @throws IllegalArgumentException for text after the last terminated statement or an open
   *     dollar-quoted block
   */
  public static List<String> statements(String sql) {
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inDollar = false;
    for (String line : normalise(sql).split("\n", -1)) {
      String trimmed = line.strip();
      if (!inDollar && trimmed.startsWith("--")) {
        continue;
      }
      if (current.length() == 0 && trimmed.isEmpty()) {
        continue;
      }
      if (current.length() > 0) {
        current.append('\n');
      }
      current.append(line.stripTrailing());
      if (occurrences(line, "$$") % 2 == 1) {
        inDollar = !inDollar;
      }
      if (!inDollar && trimmed.endsWith(";")) {
        statements.add(current.toString().strip());
        current.setLength(0);
      }
    }
    if (inDollar) {
      throw new IllegalArgumentException("script ends inside a dollar-quoted block");
    }
    if (!current.toString().isBlank()) {
      throw new IllegalArgumentException("script ends with an unterminated statement");
    }
    return List.copyOf(statements);
  }

  private static int occurrences(String text, String part) {
    int count = 0;
    int from = text.indexOf(part);
    while (from >= 0) {
      count++;
      from = text.indexOf(part, from + part.length());
    }
    return count;
  }

  private static String readResource(String path) {
    try (InputStream in = ScriptCatalogue.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("migration resource not found: " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(path, e);
    }
  }
}
