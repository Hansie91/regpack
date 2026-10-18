package dev.regpack.pack.yaml;

import dev.regpack.pack.PackFormat;
import dev.regpack.pack.PackText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The strict YAML subset parser of the Regime Pack (PackYaml/1, companion contracts section 5.2;
 * decision D-P1: in-house, no library).
 *
 * <p>One pass normalises line ends and records (line, indent, text) per content line; a recursive
 * descent by indentation then builds the tree. There is no backtracking and no regular expression.
 * Every scalar is a string. Nothing is typed here: the consumer types explicitly.
 *
 * <p>Order of checks (plan tasks T4 and T5): the file size first; then the character policy of
 * {@link PackText} over the whole text (a tab is {@code YAML_TAB}, any other offending character
 * {@code YAML_BAD_CHARACTER}); then the line length; then the structure, where every construct
 * outside the subset has its own code (anchor, alias, tag, merge key, directive, document marker,
 * block scalar, complex key, a sequence directly in a sequence, a scalar over several lines, a bad
 * escape) and the other limits are enforced while the tree is built: scalar and key length, nesting
 * depth ({@code YAML_TOO_DEEP}, before the recursion can grow) and node count ({@code
 * YAML_TOO_MANY_NODES}). Whatever the input, a parse returns a tree or raises a {@link
 * PackSyntaxException}.
 */
public final class StrictYaml {
  /**
   * Limits of one parse. Defaults come from {@link PackFormat}. {@code maxChars} is the raw length
   * of the text (a CRLF counts two); {@code maxLineChars} the length of one line without its line
   * end, comments and indentation included; {@code maxScalarChars} and {@code maxKeyChars} the
   * length of the parsed value (escapes resolved, quotes not counted); depth counts nested
   * collections (the outermost collection has depth 1); {@code maxNodes} counts scalars, lists and
   * maps. A value equal to the limit is accepted, one more is refused.
   */
  public record Limits(
      int maxChars,
      int maxLineChars,
      int maxScalarChars,
      int maxKeyChars,
      int maxDepth,
      int maxNodes) {
    public static Limits defaults() {
      return new Limits(
          PackFormat.MAX_FILE_BYTES,
          PackFormat.MAX_LINE_CHARS,
          PackFormat.MAX_SCALAR_CHARS,
          PackFormat.MAX_KEY_CHARS,
          PackFormat.MAX_DEPTH,
          PackFormat.MAX_NODES);
    }
  }

  private StrictYaml() {}

  public static YamlNode parse(String text) {
    return parse(text, Limits.defaults());
  }

  public static YamlNode parse(String text, Limits limits) {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(limits, "limits");
    if (text.length() > limits.maxChars()) {
      throw error("YAML_TOO_LARGE", 1, "file over " + limits.maxChars() + " chars");
    }
    String normal = text.replace("\r\n", "\n");
    checkCharacters(normal);
    return new BlockParser(prepare(normal, limits), new Build(limits)).document();
  }

  // ---------------------------------------------------------------------------------------------
  // Character policy and line preparation

  /** The whole text, first: the one {@link PackText} policy, with the tab told apart. */
  private static void checkCharacters(String normal) {
    PackText.Bad bad = PackText.firstBad(normal, true).orElse(null);
    if (bad == null) {
      return;
    }
    int line = 1;
    for (int i = 0; i < bad.index(); i++) {
      if (normal.charAt(i) == '\n') {
        line++;
      }
    }
    String code = bad.codePoint() == '\t' ? "YAML_TAB" : "YAML_BAD_CHARACTER";
    throw error(
        code, line, normal.substring(bad.index(), Math.min(normal.length(), bad.index() + 32)));
  }

  private record Line(int no, int indent, String text) {}

  private static List<Line> prepare(String normal, Limits limits) {
    String[] raw = normal.split("\n", -1);
    List<Line> lines = new ArrayList<>();
    for (int i = 0; i < raw.length; i++) {
      String s = raw[i];
      if (s.length() > limits.maxLineChars()) {
        throw error("YAML_TOO_LARGE", i + 1, "line over " + limits.maxLineChars() + " chars");
      }
      int indent = 0;
      while (indent < s.length() && s.charAt(indent) == ' ') {
        indent++;
      }
      int end = s.length();
      while (end > indent && s.charAt(end - 1) == ' ') {
        end--;
      }
      if (end == indent || s.charAt(indent) == '#') {
        continue;
      }
      String text = s.substring(indent, end);
      if (indent == 0 && isDocumentMarker(text)) {
        throw error("YAML_DOCUMENT_MARKER", i + 1, text);
      }
      lines.add(new Line(i + 1, indent, text));
    }
    return lines;
  }

  private static boolean isDocumentMarker(String text) {
    return text.equals("---")
        || text.startsWith("--- ")
        || text.equals("...")
        || text.startsWith("... ");
  }

  // ---------------------------------------------------------------------------------------------
  // The tree under construction: limits that are enforced while it is built

  private static final class Build {
    private final Limits limits;
    private int nodes;

    Build(Limits limits) {
      this.limits = limits;
    }

    /** Counts one node; the {@code maxNodes}+1th is refused. */
    void count(int line) {
      nodes++;
      if (nodes > limits.maxNodes()) {
        throw error("YAML_TOO_MANY_NODES", line, "more than " + limits.maxNodes() + " nodes");
      }
    }

    void checkDepth(int depth, int line) {
      if (depth > limits.maxDepth()) {
        throw error("YAML_TOO_DEEP", line, "deeper than " + limits.maxDepth());
      }
    }

    YamlScalar scalar(String value, int line) {
      if (value.length() > limits.maxScalarChars()) {
        throw error("YAML_TOO_LARGE", line, "scalar over " + limits.maxScalarChars() + " chars");
      }
      count(line);
      return new YamlScalar(value, line);
    }

    void checkKey(String key, int line) {
      if (key.length() > limits.maxKeyChars()) {
        throw error("YAML_TOO_LARGE", line, "key over " + limits.maxKeyChars() + " chars");
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Small text helpers shared by the block and flow parsers

  /** The excerpt is bounded and escaped by {@link PackSyntaxException} itself. */
  private static PackSyntaxException error(String code, int line, String text) {
    return new PackSyntaxException(code, line, text);
  }

  /**
   * Refuses a key or value that starts with a YAML indicator outside the subset: anchor, alias,
   * tag, block scalar, directive and complex key. A quoted scalar is never checked here, so the
   * same characters inside quotes are plain text. (A sequence dash is the caller's business.)
   */
  private static void rejectIndicator(String text, int pos, int line) {
    if (pos >= text.length()) {
      return;
    }
    String code =
        switch (text.charAt(pos)) {
          case '&' -> "YAML_ANCHOR";
          case '*' -> "YAML_ALIAS";
          case '!' -> "YAML_TAG";
          case '|', '>' -> "YAML_BLOCK_SCALAR";
          case '%' -> "YAML_DIRECTIVE";
          case '?' ->
              pos + 1 == text.length() || text.charAt(pos + 1) == ' ' ? "YAML_COMPLEX_KEY" : null;
          default -> null;
        };
    if (code != null) {
      throw error(code, line, text.substring(pos));
    }
  }

  private static boolean isDash(String text) {
    return text.equals("-") || text.startsWith("- ");
  }

  private static int skipSpaces(String text, int from) {
    int i = from;
    while (i < text.length() && text.charAt(i) == ' ') {
      i++;
    }
    return i;
  }

  /** Index of the first {@code #} that starts a comment (at 0 or after a space), else length. */
  private static int commentStart(String text) {
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '#' && (i == 0 || text.charAt(i - 1) == ' ')) {
        return i;
      }
    }
    return text.length();
  }

  private static String trimRight(String text) {
    int end = text.length();
    while (end > 0 && text.charAt(end - 1) == ' ') {
      end--;
    }
    return text.substring(0, end);
  }

  private static boolean isKeyStart(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
  }

  private static boolean isKeyPart(char c) {
    return isKeyStart(c) || (c >= '0' && c <= '9') || c == '.' || c == '-';
  }

  private static boolean isPlainKey(String key) {
    if (key.isEmpty() || !isKeyStart(key.charAt(0))) {
      return false;
    }
    for (int i = 1; i < key.length(); i++) {
      if (!isKeyPart(key.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /** A quoted scalar: its text and the index just past the closing quote. */
  private record Quoted(String value, int end) {}

  private static Quoted readQuoted(String text, int start, int line) {
    char quote = text.charAt(start);
    StringBuilder out = new StringBuilder();
    int i = start + 1;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (quote == '"' && c == '\\') {
        if (i + 1 >= text.length()) {
          break;
        }
        char e = text.charAt(i + 1);
        switch (e) {
          case '\\' -> out.append('\\');
          case '"' -> out.append('"');
          case 'n' -> out.append('\n');
          default -> throw error("YAML_BAD_ESCAPE", line, text.substring(i));
        }
        i += 2;
      } else if (c == quote) {
        if (quote == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
          out.append('\'');
          i += 2;
        } else {
          return new Quoted(out.toString(), i + 1);
        }
      } else {
        out.append(c);
        i++;
      }
    }
    throw error("YAML_UNTERMINATED", line, text.substring(start));
  }

  /** After a value only spaces and a comment (after a space) may follow on the line. */
  private static void expectEnd(String text, int from, int line) {
    int i = skipSpaces(text, from);
    if (i == text.length()) {
      return;
    }
    if (text.charAt(i) == '#' && i > from) {
      return;
    }
    throw error("YAML_SYNTAX", line, text.substring(i));
  }

  // ---------------------------------------------------------------------------------------------
  // Block structure

  /** The key of a mapping line and the text after its colon (leading spaces removed). */
  private record KeySplit(String key, String rest) {}

  private static final class BlockParser {
    private final List<Line> lines;
    private final Build build;
    private int pos;

    BlockParser(List<Line> lines, Build build) {
      this.lines = new ArrayList<>(lines);
      this.build = build;
    }

    YamlNode document() {
      if (lines.isEmpty()) {
        build.count(1);
        return new YamlMap(Map.of(), 1);
      }
      Line first = lines.get(0);
      if (first.indent() != 0) {
        throw error("YAML_BAD_INDENT", first.no(), first.text());
      }
      YamlNode root = isDash(first.text()) ? sequence(0, 1) : mapping(0, 1);
      if (pos < lines.size()) {
        Line stray = lines.get(pos);
        throw error("YAML_BAD_INDENT", stray.no(), stray.text());
      }
      return root;
    }

    private YamlNode block(int indent, int depth) {
      return isDash(lines.get(pos).text()) ? sequence(indent, depth) : mapping(indent, depth);
    }

    /**
     * The block below a key or dash that has no inline value; it must be indented further. A line
     * that is neither a sequence item nor a key line is a value on a line of its own, which the
     * subset does not have ({@code YAML_MULTILINE_SCALAR}).
     */
    private YamlNode nested(int parentIndent, int depth, Line owner) {
      if (pos < lines.size() && lines.get(pos).indent() > parentIndent) {
        Line next = lines.get(pos);
        rejectIndicator(next.text(), 0, next.no());
        if (!isDash(next.text()) && !isKeyLine(next)) {
          throw error("YAML_MULTILINE_SCALAR", next.no(), next.text());
        }
        return block(next.indent(), depth + 1);
      }
      throw error("YAML_EMPTY_VALUE", owner.no(), owner.text());
    }

    private static boolean isKeyLine(Line line) {
      try {
        return splitKey(line.text(), line.no()).isPresent();
      } catch (PackSyntaxException e) {
        return true; // it has the shape of a key line; its own error is reported when it is parsed
      }
    }

    /**
     * After a value on the key's or dash's line no deeper line may follow. After a scalar, a deeper
     * line that is not a key line or a sequence item continues the scalar over several lines.
     */
    private void noMoreIndented(int indent, boolean afterScalar) {
      if (pos < lines.size() && lines.get(pos).indent() > indent) {
        Line l = lines.get(pos);
        if (afterScalar && !isDash(l.text()) && !isKeyLine(l)) {
          throw error("YAML_MULTILINE_SCALAR", l.no(), l.text());
        }
        throw error("YAML_BAD_INDENT", l.no(), l.text());
      }
    }

    private YamlMap mapping(int indent, int depth) {
      int firstLine = lines.get(pos).no();
      build.checkDepth(depth, firstLine);
      build.count(firstLine);
      Map<String, YamlNode> entries = new LinkedHashMap<>();
      while (pos < lines.size()) {
        Line l = lines.get(pos);
        if (l.indent() < indent) {
          break;
        }
        if (l.indent() > indent) {
          throw error("YAML_BAD_INDENT", l.no(), l.text());
        }
        if (isDash(l.text())) {
          throw error("YAML_SYNTAX", l.no(), l.text());
        }
        rejectIndicator(l.text(), 0, l.no());
        KeySplit split =
            splitKey(l.text(), l.no()).orElseThrow(() -> error("YAML_SYNTAX", l.no(), l.text()));
        build.checkKey(split.key(), l.no());
        pos++;
        YamlNode value;
        if (isEmptyRest(split.rest())) {
          value = nested(indent, depth, l);
        } else {
          value = inline(split.rest(), l.no(), depth + 1);
          noMoreIndented(indent, value instanceof YamlScalar);
        }
        if (entries.putIfAbsent(split.key(), value) != null) {
          throw error("YAML_DUPLICATE_KEY", l.no(), split.key());
        }
      }
      return new YamlMap(entries, firstLine);
    }

    private YamlList sequence(int indent, int depth) {
      int firstLine = lines.get(pos).no();
      build.checkDepth(depth, firstLine);
      build.count(firstLine);
      List<YamlNode> items = new ArrayList<>();
      while (pos < lines.size()) {
        Line l = lines.get(pos);
        if (l.indent() < indent) {
          break;
        }
        if (l.indent() > indent) {
          throw error("YAML_BAD_INDENT", l.no(), l.text());
        }
        if (!isDash(l.text())) {
          throw error("YAML_SYNTAX", l.no(), l.text());
        }
        int restStart = skipSpaces(l.text(), 1);
        String rest = l.text().substring(restStart);
        if (isEmptyRest(rest)) {
          pos++;
          if (pos < lines.size()
              && lines.get(pos).indent() > indent
              && isDash(lines.get(pos).text())) {
            throw error("YAML_NESTED_SEQUENCE", lines.get(pos).no(), lines.get(pos).text());
          }
          items.add(nested(indent, depth, l));
        } else if (isDash(rest)) {
          throw error("YAML_NESTED_SEQUENCE", l.no(), rest);
        } else {
          rejectIndicator(rest, 0, l.no());
          if (splitKey(rest, l.no()).isPresent()) {
            // a block mapping that starts on the dash line: its indent is the column of its key
            lines.set(pos, new Line(l.no(), indent + restStart, rest));
            items.add(mapping(indent + restStart, depth + 1));
          } else {
            pos++;
            YamlNode item = inline(rest, l.no(), depth + 1);
            items.add(item);
            noMoreIndented(indent, item instanceof YamlScalar);
          }
        }
      }
      return new YamlList(items, firstLine);
    }

    private static boolean isEmptyRest(String rest) {
      return rest.isEmpty() || rest.charAt(0) == '#';
    }

    /**
     * Splits {@code key: rest}. Empty when the text is not a key line (a scalar or flow value); a
     * line that has the shape of a key but an invalid key is {@code YAML_BAD_KEY}, and the plain
     * key {@code <<} is {@code YAML_MERGE_KEY}.
     */
    private static Optional<KeySplit> splitKey(String text, int line) {
      char first = text.charAt(0);
      if (first == '"' || first == '\'') {
        Quoted q = readQuoted(text, 0, line);
        int e = q.end();
        boolean colon =
            e < text.length()
                && text.charAt(e) == ':'
                && (e + 1 == text.length() || text.charAt(e + 1) == ' ');
        if (!colon) {
          return Optional.empty();
        }
        if (q.value().isEmpty()) {
          throw error("YAML_BAD_KEY", line, text);
        }
        return Optional.of(new KeySplit(q.value(), text.substring(skipSpaces(text, e + 1))));
      }
      if (first == '[' || first == '{') {
        return Optional.empty();
      }
      String body = trimRight(text.substring(0, commentStart(text)));
      for (int i = 0; i < body.length(); i++) {
        if (body.charAt(i) == ':' && (i + 1 == body.length() || body.charAt(i + 1) == ' ')) {
          String key = body.substring(0, i);
          if (key.equals("<<")) {
            throw error("YAML_MERGE_KEY", line, text);
          }
          if (!isPlainKey(key)) {
            throw error("YAML_BAD_KEY", line, key);
          }
          return Optional.of(new KeySplit(key, text.substring(skipSpaces(text, i + 1))));
        }
      }
      return Optional.empty();
    }

    /** A value on the same line as its key or dash: quoted, flow or plain. */
    private YamlNode inline(String rest, int line, int depth) {
      rejectIndicator(rest, 0, line);
      if (isDash(rest)) {
        throw error("YAML_NESTED_SEQUENCE", line, rest);
      }
      char c = rest.charAt(0);
      if (c == '"' || c == '\'') {
        Quoted q = readQuoted(rest, 0, line);
        expectEnd(rest, q.end(), line);
        return build.scalar(q.value(), line);
      }
      if (c == '[' || c == '{') {
        FlowReader flow = new FlowReader(rest, line, build);
        YamlNode node = flow.readValue(depth);
        expectEnd(rest, flow.pos, line);
        return node;
      }
      String value = trimRight(rest.substring(0, commentStart(rest)));
      if (hasMappingIndicator(value)) {
        throw error("YAML_SYNTAX", line, value);
      }
      return build.scalar(value, line);
    }

    /** A plain scalar may not contain a colon followed by a space or ending the scalar. */
    private static boolean hasMappingIndicator(String value) {
      for (int i = 0; i < value.length(); i++) {
        if (value.charAt(i) == ':' && (i + 1 == value.length() || value.charAt(i + 1) == ' ')) {
          return true;
        }
      }
      return false;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Flow collections (one line)

  private static final class FlowReader {
    private final String text;
    private final int line;
    private final Build build;
    private int pos;

    FlowReader(String text, int line, Build build) {
      this.text = text;
      this.line = line;
      this.build = build;
    }

    private void skip() {
      pos = skipSpaces(text, pos);
    }

    private PackSyntaxException unterminated() {
      return error("YAML_UNTERMINATED", line, text);
    }

    private PackSyntaxException syntax() {
      return error("YAML_SYNTAX", line, text.substring(Math.min(pos, text.length())));
    }

    YamlNode readValue(int depth) {
      skip();
      if (pos >= text.length()) {
        throw unterminated();
      }
      char c = text.charAt(pos);
      if (c == '[') {
        return readList(depth);
      }
      if (c == '{') {
        return readMap(depth);
      }
      if (c == '"' || c == '\'') {
        Quoted q = readQuoted(text, pos, line);
        pos = q.end();
        return build.scalar(q.value(), line);
      }
      rejectIndicator(text, pos, line);
      return readPlain();
    }

    /** A plain scalar inside a flow collection ends at a comma, a bracket or a brace. */
    private YamlNode readPlain() {
      int start = pos;
      while (pos < text.length()) {
        char c = text.charAt(pos);
        if (c == ',' || c == ']' || c == '}' || c == '[' || c == '{') {
          break;
        }
        if (c == '#' && pos > start && text.charAt(pos - 1) == ' ') {
          break;
        }
        if (c == ':' && (pos + 1 == text.length() || isFlowEnd(text.charAt(pos + 1)))) {
          throw syntax();
        }
        pos++;
      }
      String value = trimRight(text.substring(start, pos));
      if (value.isEmpty()) {
        throw syntax();
      }
      return build.scalar(value, line);
    }

    private static boolean isFlowEnd(char c) {
      return c == ' ' || c == ',' || c == ']' || c == '}';
    }

    private YamlList readList(int depth) {
      build.checkDepth(depth, line);
      build.count(line);
      pos++;
      List<YamlNode> items = new ArrayList<>();
      skip();
      if (pos < text.length() && text.charAt(pos) == ']') {
        pos++;
        return new YamlList(items, line);
      }
      while (true) {
        items.add(readValue(depth + 1));
        skip();
        if (pos >= text.length()) {
          throw unterminated();
        }
        char c = text.charAt(pos);
        pos++;
        if (c == ']') {
          return new YamlList(items, line);
        }
        if (c != ',') {
          pos--;
          throw syntax();
        }
      }
    }

    private YamlMap readMap(int depth) {
      build.checkDepth(depth, line);
      build.count(line);
      pos++;
      Map<String, YamlNode> entries = new LinkedHashMap<>();
      skip();
      if (pos < text.length() && text.charAt(pos) == '}') {
        pos++;
        return new YamlMap(entries, line);
      }
      while (true) {
        String key = readKey();
        skip();
        if (pos >= text.length()) {
          throw unterminated();
        }
        char c = text.charAt(pos);
        if (c == ',' || c == '}') {
          throw error("YAML_EMPTY_VALUE", line, key);
        }
        YamlNode value = readValue(depth + 1);
        if (entries.putIfAbsent(key, value) != null) {
          throw error("YAML_DUPLICATE_KEY", line, key);
        }
        skip();
        if (pos >= text.length()) {
          throw unterminated();
        }
        c = text.charAt(pos);
        pos++;
        if (c == '}') {
          return new YamlMap(entries, line);
        }
        if (c != ',') {
          pos--;
          throw syntax();
        }
      }
    }

    /** Reads {@code key:} and leaves the position after the colon. */
    private String readKey() {
      skip();
      if (pos >= text.length()) {
        throw unterminated();
      }
      String key;
      char c = text.charAt(pos);
      if (c == '"' || c == '\'') {
        Quoted q = readQuoted(text, pos, line);
        pos = q.end();
        key = q.value();
        if (key.isEmpty()) {
          throw error("YAML_BAD_KEY", line, text);
        }
      } else {
        rejectIndicator(text, pos, line);
        if (text.startsWith("<<:", pos) || text.startsWith("<< ", pos)) {
          throw error("YAML_MERGE_KEY", line, text.substring(pos));
        }
        int start = pos;
        while (pos < text.length() && isKeyPart(text.charAt(pos))) {
          pos++;
        }
        key = text.substring(start, pos);
        if (!isPlainKey(key)) {
          throw error("YAML_BAD_KEY", line, text.substring(start));
        }
      }
      build.checkKey(key, line);
      if (pos >= text.length()) {
        throw unterminated();
      }
      if (text.charAt(pos) != ':') {
        throw syntax();
      }
      pos++;
      if (pos < text.length()
          && text.charAt(pos) != ' '
          && text.charAt(pos) != ','
          && text.charAt(pos) != '}') {
        throw syntax();
      }
      return key;
    }
  }
}
