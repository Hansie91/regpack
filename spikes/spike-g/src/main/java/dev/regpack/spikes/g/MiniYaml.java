package dev.regpack.spikes.g;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A parser for the YAML subset that the SP-G example files use: block mappings and sequences (a
 * sequence item may be a mapping or another sequence), flow lists and flow mappings on one line,
 * plain and quoted scalars, and comments. Every scalar is a {@link String} (plain scalars are
 * trimmed, quoted ones are verbatim); a mapping is an unmodifiable {@link Map} in file order, a
 * sequence an unmodifiable {@link List}, an empty value the empty string.
 *
 * <p>Anything else is an error with a line number: a tab character anywhere, a duplicate key, a
 * block scalar, an unexpected indentation, an unterminated quote or flow collection, and a plain
 * scalar that contains a colon followed by a space (it must be quoted). Disposable spike code.
 */
public final class MiniYaml {
  private MiniYaml() {}

  /**
   * Parses a document; the result is unmodifiable.
   *
   * @param text the document
   * @return a {@code Map<String, Object>}, a {@code List<Object>} or a {@code String}
   * @throws IllegalArgumentException with the line number when the text is outside the subset
   */
  public static Object parse(String text) {
    String[] raw = text.split("\r?\n", -1);
    List<Line> lines = new ArrayList<>();
    for (int i = 0; i < raw.length; i++) {
      int no = i + 1;
      if (raw[i].indexOf('\t') >= 0) {
        throw error(no, "tab character (indent with spaces)");
      }
      String content = stripComment(raw[i], no);
      if (content.isBlank()) {
        continue;
      }
      int indent = 0;
      while (content.charAt(indent) == ' ') {
        indent++;
      }
      lines.add(new Line(no, indent, content.substring(indent).stripTrailing()));
    }
    if (lines.isEmpty()) {
      return Collections.unmodifiableMap(new LinkedHashMap<String, Object>());
    }
    Parser parser = new Parser(lines);
    Object value = parser.block(lines.get(0).indent());
    if (!parser.atEnd()) {
      throw error(parser.current().no(), "unexpected indentation");
    }
    return value;
  }

  private record Line(int no, int indent, String text) {}

  private record KeyValue(String key, String rest) {}

  private static IllegalArgumentException error(int line, String message) {
    return new IllegalArgumentException("line " + line + ": " + message);
  }

  private static boolean isSequenceItem(String text) {
    return text.equals("-") || text.startsWith("- ");
  }

  private static boolean startsFlow(String text) {
    return text.startsWith("[") || text.startsWith("{");
  }

  /** Cuts a comment: a hash at the start or after a space, outside quotes. */
  private static String stripComment(String line, int no) {
    char quote = 0;
    char previousSignificant = 0;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (quote == '"') {
        if (c == '\\') {
          i++;
        } else if (c == '"') {
          quote = 0;
        }
      } else if (quote == '\'') {
        if (c == '\'') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '\'') {
            i++;
          } else {
            quote = 0;
          }
        }
      } else if ((c == '"' || c == '\'') && opensQuote(previousSignificant)) {
        quote = c;
      } else if (c == '#' && (i == 0 || line.charAt(i - 1) == ' ')) {
        return line.substring(0, i);
      }
      if (c != ' ') {
        previousSignificant = c;
      }
    }
    if (quote != 0) {
      throw error(no, "unterminated quoted scalar");
    }
    return line;
  }

  private static boolean opensQuote(char previousSignificant) {
    return previousSignificant == 0
        || previousSignificant == ':'
        || previousSignificant == '-'
        || previousSignificant == ','
        || previousSignificant == '['
        || previousSignificant == '{';
  }

  /** Splits {@code key: rest}; empty when the text is not a mapping entry. */
  private static Optional<KeyValue> splitKey(String text, int no) {
    if (text.isEmpty() || startsFlow(text)) {
      return Optional.empty();
    }
    char first = text.charAt(0);
    if (first == '"' || first == '\'') {
      Quoted quoted = readQuoted(text, 0, no);
      int after = quoted.end;
      while (after < text.length() && text.charAt(after) == ' ') {
        after++;
      }
      if (after < text.length()
          && text.charAt(after) == ':'
          && (after + 1 == text.length() || text.charAt(after + 1) == ' ')) {
        return Optional.of(new KeyValue(quoted.value, text.substring(after + 1).strip()));
      }
      return Optional.empty();
    }
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == ':' && (i + 1 == text.length() || text.charAt(i + 1) == ' ')) {
        return Optional.of(
            new KeyValue(text.substring(0, i).strip(), text.substring(i + 1).strip()));
      }
    }
    return Optional.empty();
  }

  private record Quoted(String value, int end) {}

  /** Reads a quoted scalar that starts at {@code start}; {@code end} is the index after it. */
  private static Quoted readQuoted(String text, int start, int no) {
    char quote = text.charAt(start);
    StringBuilder out = new StringBuilder();
    int i = start + 1;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (quote == '"' && c == '\\' && i + 1 < text.length()) {
        char next = text.charAt(i + 1);
        if (next == '"' || next == '\\') {
          out.append(next);
        } else {
          out.append(c).append(next);
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
    throw error(no, "unterminated quoted scalar");
  }

  private static final class Parser {
    private final List<Line> lines;
    private int pos;
    // the rest of a sequence item that starts on its dash line, read as a line of its own
    private Line override;

    Parser(List<Line> lines) {
      this.lines = List.copyOf(lines);
    }

    boolean atEnd() {
      return pos >= lines.size();
    }

    Line current() {
      return override != null ? override : lines.get(pos);
    }

    private void advance() {
      pos++;
      override = null;
    }

    Object block(int indent) {
      return isSequenceItem(current().text()) ? sequence(indent) : mapping(indent);
    }

    private Map<String, Object> mapping(int indent) {
      Map<String, Object> map = new LinkedHashMap<>();
      while (!atEnd() && current().indent() == indent && !isSequenceItem(current().text())) {
        Line line = current();
        KeyValue kv =
            splitKey(line.text(), line.no())
                .orElseThrow(() -> error(line.no(), "expected 'key: value'"));
        if (map.containsKey(kv.key())) {
          throw error(line.no(), "duplicate key '" + kv.key() + "'");
        }
        advance();
        Object value;
        if (kv.rest().isEmpty()) {
          value = !atEnd() && current().indent() > indent ? block(current().indent()) : "";
        } else {
          value = inline(kv.rest(), line.no());
        }
        map.put(kv.key(), value);
      }
      if (!atEnd() && current().indent() == indent) {
        throw error(current().no(), "sequence item where a mapping key was expected");
      }
      if (!atEnd() && current().indent() > indent) {
        throw error(current().no(), "unexpected indentation");
      }
      return Collections.unmodifiableMap(map);
    }

    private List<Object> sequence(int indent) {
      List<Object> list = new ArrayList<>();
      while (!atEnd() && current().indent() == indent && isSequenceItem(current().text())) {
        Line line = current();
        String afterDash = line.text().length() == 1 ? "" : line.text().substring(2);
        String rest = afterDash.strip();
        if (rest.isEmpty()) {
          advance();
          list.add(!atEnd() && current().indent() > indent ? block(current().indent()) : "");
          continue;
        }
        int offset = 2 + (afterDash.length() - afterDash.stripLeading().length());
        if (isSequenceItem(rest) || splitKey(rest, line.no()).isPresent()) {
          // the item starts on the dash line: continue it as a block at the column of its content
          override = new Line(line.no(), indent + offset, rest);
          list.add(isSequenceItem(rest) ? sequence(indent + offset) : mapping(indent + offset));
        } else {
          advance();
          list.add(inline(rest, line.no()));
        }
      }
      if (!atEnd() && current().indent() > indent) {
        throw error(current().no(), "unexpected indentation");
      }
      return Collections.unmodifiableList(list);
    }

    private Object inline(String rest, int no) {
      char first = rest.charAt(0);
      if (first == '"' || first == '\'') {
        Quoted quoted = readQuoted(rest, 0, no);
        if (!rest.substring(quoted.end).isBlank()) {
          throw error(no, "text after a quoted scalar");
        }
        return quoted.value;
      }
      if (first == '[' || first == '{') {
        Flow flow = new Flow(rest, no);
        Object value = flow.value();
        flow.expectEnd();
        return value;
      }
      if (first == '|' || first == '>') {
        throw error(no, "block scalar is not supported");
      }
      if (rest.contains(": ") || rest.endsWith(":")) {
        throw error(no, "a plain scalar must not contain ': ' (quote it)");
      }
      return rest;
    }
  }

  /** A flow collection on one line. */
  private static final class Flow {
    private final String text;
    private final int no;
    private int pos;

    Flow(String text, int no) {
      this.text = text;
      this.no = no;
    }

    void expectEnd() {
      skipSpaces();
      if (pos < text.length()) {
        throw error(no, "unexpected text after the flow collection");
      }
    }

    private void skipSpaces() {
      while (pos < text.length() && text.charAt(pos) == ' ') {
        pos++;
      }
    }

    Object value() {
      skipSpaces();
      if (pos >= text.length()) {
        throw error(no, "unterminated flow collection");
      }
      char c = text.charAt(pos);
      if (c == '[') {
        return list();
      }
      if (c == '{') {
        return map();
      }
      if (c == '"' || c == '\'') {
        Quoted quoted = readQuoted(text, pos, no);
        pos = quoted.end;
        return quoted.value;
      }
      int start = pos;
      while (pos < text.length() && ",]}".indexOf(text.charAt(pos)) < 0) {
        pos++;
      }
      String plain = text.substring(start, pos).strip();
      if (plain.contains(": ") || plain.endsWith(":")) {
        throw error(no, "a plain scalar must not contain ': ' (quote it)");
      }
      return plain;
    }

    private List<Object> list() {
      List<Object> out = new ArrayList<>();
      pos++;
      skipSpaces();
      if (pos < text.length() && text.charAt(pos) == ']') {
        pos++;
        return Collections.unmodifiableList(out);
      }
      while (true) {
        out.add(value());
        skipSpaces();
        if (pos >= text.length()) {
          throw error(no, "unterminated flow list");
        }
        char c = text.charAt(pos++);
        if (c == ']') {
          return Collections.unmodifiableList(out);
        }
        if (c != ',') {
          throw error(no, "expected ',' or ']' in a flow list");
        }
      }
    }

    private Map<String, Object> map() {
      Map<String, Object> out = new LinkedHashMap<>();
      pos++;
      skipSpaces();
      if (pos < text.length() && text.charAt(pos) == '}') {
        pos++;
        return Collections.unmodifiableMap(out);
      }
      while (true) {
        skipSpaces();
        String key = key();
        if (out.containsKey(key)) {
          throw error(no, "duplicate key '" + key + "'");
        }
        skipSpaces();
        Object value;
        if (pos < text.length() && (text.charAt(pos) == ',' || text.charAt(pos) == '}')) {
          value = "";
        } else {
          value = value();
        }
        out.put(key, value);
        skipSpaces();
        if (pos >= text.length()) {
          throw error(no, "unterminated flow mapping");
        }
        char c = text.charAt(pos++);
        if (c == '}') {
          return Collections.unmodifiableMap(out);
        }
        if (c != ',') {
          throw error(no, "expected ',' or '}' in a flow mapping");
        }
      }
    }

    private String key() {
      if (pos >= text.length()) {
        throw error(no, "unterminated flow mapping");
      }
      char c = text.charAt(pos);
      String key;
      if (c == '"' || c == '\'') {
        Quoted quoted = readQuoted(text, pos, no);
        pos = quoted.end;
        key = quoted.value;
        skipSpaces();
        if (pos >= text.length() || text.charAt(pos) != ':') {
          throw error(no, "expected ':' after a flow mapping key");
        }
        pos++;
      } else {
        int colon = text.indexOf(':', pos);
        if (colon < 0) {
          throw error(no, "expected ':' after a flow mapping key");
        }
        key = text.substring(pos, colon).strip();
        pos = colon + 1;
      }
      return key;
    }
  }
}
