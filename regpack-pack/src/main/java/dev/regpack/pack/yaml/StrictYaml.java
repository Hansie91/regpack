package dev.regpack.pack.yaml;

import dev.regpack.pack.PackFormat;
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
 * <p>This is the accepting side (plan task T4). The refusal of every construct outside the subset,
 * the input limits and the character policy are task T5; until then only the syntax codes {@code
 * YAML_SYNTAX}, {@code YAML_BAD_INDENT}, {@code YAML_DUPLICATE_KEY}, {@code YAML_UNTERMINATED},
 * {@code YAML_EMPTY_VALUE} and {@code YAML_BAD_KEY} are raised, and the nesting depth is guarded
 * (by {@code YAML_SYNTAX}) only so that the recursion cannot overflow the stack.
 */
public final class StrictYaml {
  /**
   * Limits of one parse. Defaults come from {@link PackFormat}. Depth counts nested collections:
   * the outermost collection has depth 1.
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

  private static final int EXCERPT_CHARS = 24;

  private StrictYaml() {}

  public static YamlNode parse(String text) {
    return parse(text, Limits.defaults());
  }

  public static YamlNode parse(String text, Limits limits) {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(limits, "limits");
    return new BlockParser(prepare(text), limits).document();
  }

  // ---------------------------------------------------------------------------------------------
  // Line preparation

  private record Line(int no, int indent, String text) {}

  private static List<Line> prepare(String text) {
    String[] raw = text.replace("\r\n", "\n").split("\n", -1);
    List<Line> lines = new ArrayList<>();
    for (int i = 0; i < raw.length; i++) {
      String s = raw[i];
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
      lines.add(new Line(i + 1, indent, s.substring(indent, end)));
    }
    return lines;
  }

  // ---------------------------------------------------------------------------------------------
  // Small text helpers shared by the block and flow parsers

  private static PackSyntaxException error(String code, int line, String text) {
    String excerpt = text.length() <= EXCERPT_CHARS ? text : text.substring(0, EXCERPT_CHARS);
    return new PackSyntaxException(code, line, excerpt);
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
          default -> throw error("YAML_SYNTAX", line, text.substring(i));
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
    private final Limits limits;
    private int pos;

    BlockParser(List<Line> lines, Limits limits) {
      this.lines = new ArrayList<>(lines);
      this.limits = limits;
    }

    YamlNode document() {
      if (lines.isEmpty()) {
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

    private void checkDepth(int depth, int line) {
      if (depth > limits.maxDepth()) {
        throw error("YAML_SYNTAX", line, "nesting too deep");
      }
    }

    private YamlNode block(int indent, int depth) {
      return isDash(lines.get(pos).text()) ? sequence(indent, depth) : mapping(indent, depth);
    }

    /** The block below a key or dash that has no inline value; it must be indented further. */
    private YamlNode nested(int parentIndent, int depth, Line owner) {
      if (pos < lines.size() && lines.get(pos).indent() > parentIndent) {
        return block(lines.get(pos).indent(), depth + 1);
      }
      throw error("YAML_EMPTY_VALUE", owner.no(), owner.text());
    }

    private void noMoreIndented(int indent) {
      if (pos < lines.size() && lines.get(pos).indent() > indent) {
        Line l = lines.get(pos);
        throw error("YAML_BAD_INDENT", l.no(), l.text());
      }
    }

    private YamlMap mapping(int indent, int depth) {
      checkDepth(depth, lines.get(pos).no());
      int firstLine = lines.get(pos).no();
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
        KeySplit split =
            splitKey(l.text(), l.no()).orElseThrow(() -> error("YAML_SYNTAX", l.no(), l.text()));
        pos++;
        YamlNode value;
        if (isEmptyRest(split.rest())) {
          value = nested(indent, depth, l);
        } else {
          value = inline(split.rest(), l.no(), depth + 1);
          noMoreIndented(indent);
        }
        if (entries.putIfAbsent(split.key(), value) != null) {
          throw error("YAML_DUPLICATE_KEY", l.no(), split.key());
        }
      }
      return new YamlMap(entries, firstLine);
    }

    private YamlList sequence(int indent, int depth) {
      checkDepth(depth, lines.get(pos).no());
      int firstLine = lines.get(pos).no();
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
          items.add(nested(indent, depth, l));
        } else if (isDash(rest)) {
          throw error("YAML_SYNTAX", l.no(), rest);
        } else if (splitKey(rest, l.no()).isPresent()) {
          // a block mapping that starts on the dash line: its indent is the column of its first key
          lines.set(pos, new Line(l.no(), indent + restStart, rest));
          items.add(mapping(indent + restStart, depth + 1));
        } else {
          pos++;
          items.add(inline(rest, l.no(), depth + 1));
          noMoreIndented(indent);
        }
      }
      return new YamlList(items, firstLine);
    }

    private static boolean isEmptyRest(String rest) {
      return rest.isEmpty() || rest.charAt(0) == '#';
    }

    /**
     * Splits {@code key: rest}. Empty when the text is not a key line (a scalar or flow value); a
     * line that has the shape of a key but an invalid key is {@code YAML_BAD_KEY}.
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
      char c = rest.charAt(0);
      if (c == '"' || c == '\'') {
        Quoted q = readQuoted(rest, 0, line);
        expectEnd(rest, q.end(), line);
        return new YamlScalar(q.value(), line);
      }
      if (c == '[' || c == '{') {
        FlowReader flow = new FlowReader(rest, line, limits);
        YamlNode node = flow.readValue(depth);
        expectEnd(rest, flow.pos, line);
        return node;
      }
      return new YamlScalar(trimRight(rest.substring(0, commentStart(rest))), line);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Flow collections (one line)

  private static final class FlowReader {
    private final String text;
    private final int line;
    private final Limits limits;
    private int pos;

    FlowReader(String text, int line, Limits limits) {
      this.text = text;
      this.line = line;
      this.limits = limits;
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
        return new YamlScalar(q.value(), line);
      }
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
      return new YamlScalar(value, line);
    }

    private static boolean isFlowEnd(char c) {
      return c == ' ' || c == ',' || c == ']' || c == '}';
    }

    private YamlList readList(int depth) {
      if (depth > limits.maxDepth()) {
        throw error("YAML_SYNTAX", line, "nesting too deep");
      }
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
      if (depth > limits.maxDepth()) {
        throw error("YAML_SYNTAX", line, "nesting too deep");
      }
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
        int start = pos;
        while (pos < text.length() && isKeyPart(text.charAt(pos))) {
          pos++;
        }
        key = text.substring(start, pos);
        if (!isPlainKey(key)) {
          throw error("YAML_BAD_KEY", line, text.substring(start));
        }
      }
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
