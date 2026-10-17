package dev.regpack.pack.yaml;

import java.util.Objects;

/**
 * A pack file is not PackYaml/1. Carries an upper-case ASCII code (for example {@code
 * YAML_DUPLICATE_KEY}), the 1-based line and a short excerpt of the offending text.
 *
 * <p>The excerpt echoes untrusted text, so it is bounded and escaped here, whatever the caller
 * passes: at most {@link #MAX_EXCERPT_CHARS} characters, of which every character outside printable
 * ASCII is written as an escape ({@code \}{@code uXXXX}, or {@code \}{@code u{X...}} above U+FFFF)
 * and counts as the length of its escape. The message is therefore at most {@code "line " + line +
 * ": " + code + ": "} plus 24 printable characters.
 */
public final class PackSyntaxException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** The bound on the excerpt, after escaping. */
  public static final int MAX_EXCERPT_CHARS = 24;

  private final String code;
  private final int line;
  private final String excerpt;

  public PackSyntaxException(String code, int line, String excerpt) {
    String safe = bounded(Objects.requireNonNull(excerpt, "excerpt"));
    super("line " + line + ": " + Objects.requireNonNull(code, "code") + ": " + safe);
    this.code = code;
    this.line = line;
    this.excerpt = safe;
  }

  public String code() {
    return code;
  }

  public int line() {
    return line;
  }

  /** The bounded, escaped excerpt (printable ASCII only, at most 24 characters). */
  public String excerpt() {
    return excerpt;
  }

  private static String bounded(String text) {
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < text.length()) {
      int cp = text.codePointAt(i);
      String piece = cp >= 0x20 && cp <= 0x7E ? String.valueOf((char) cp) : escape(cp);
      if (out.length() + piece.length() > MAX_EXCERPT_CHARS) {
        break;
      }
      out.append(piece);
      i += Character.charCount(cp);
    }
    return out.toString();
  }

  private static String escape(int cp) {
    String hex = Integer.toHexString(cp);
    if (cp > 0xFFFF) {
      return "\\u{" + hex + "}";
    }
    return "\\u" + "0".repeat(4 - hex.length()) + hex;
  }
}
