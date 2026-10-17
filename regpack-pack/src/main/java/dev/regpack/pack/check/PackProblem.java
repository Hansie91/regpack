package dev.regpack.pack.check;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One finding of a pack check: an upper-case ASCII token ({@code code}), the pack path and 1-based
 * line it concerns (line 0: none) and a short message. Tests assert the code (plan Convention 6).
 *
 * <p>Path and message may echo untrusted text, so the constructor bounds and escapes them whatever
 * the caller passes: the path is cut to {@value #MAX_PATH_CHARS} characters, the message to {@value
 * #MAX_MESSAGE_CHARS}, and every UTF-16 character outside printable ASCII is written as {@code
 * \}{@code uXXXX} (an escape is never cut in the middle). Callers wrap untrusted text in {@link
 * #excerpt(String)}. Problems order by path, line, code and message.
 */
public record PackProblem(String code, String path, int line, String message)
    implements Comparable<PackProblem> {
  /** Longest path after escaping. */
  public static final int MAX_PATH_CHARS = 128;

  /** Longest message after escaping. */
  public static final int MAX_MESSAGE_CHARS = 160;

  /** Longest excerpt of untrusted text after escaping. */
  public static final int MAX_EXCERPT_CHARS = 24;

  private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,47}");
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  public PackProblem {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(message, "message");
    if (!CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("a problem code must match [A-Z][A-Z0-9_]{2,47}");
    }
    path = escapeAndCut(path, MAX_PATH_CHARS);
    message = escapeAndCut(message, MAX_MESSAGE_CHARS);
    line = Math.max(0, line);
  }

  /** Untrusted text made safe to put into a message: escaped and cut to 24 characters. */
  public static String excerpt(String untrusted) {
    Objects.requireNonNull(untrusted, "untrusted");
    return escapeAndCut(untrusted, MAX_EXCERPT_CHARS);
  }

  @Override
  public int compareTo(PackProblem other) {
    int c = path.compareTo(other.path);
    if (c != 0) {
      return c;
    }
    c = Integer.compare(line, other.line);
    if (c != 0) {
      return c;
    }
    c = code.compareTo(other.code);
    return c != 0 ? c : message.compareTo(other.message);
  }

  private static String escapeAndCut(String text, int max) {
    StringBuilder out = new StringBuilder(Math.min(text.length(), max));
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      int add = c >= 0x20 && c <= 0x7E ? 1 : 6;
      if (out.length() + add > max) {
        break;
      }
      if (add == 1) {
        out.append(c);
      } else {
        out.append("\\u")
            .append(HEX[(c >> 12) & 0xF])
            .append(HEX[(c >> 8) & 0xF])
            .append(HEX[(c >> 4) & 0xF])
            .append(HEX[c & 0xF]);
      }
    }
    return out.toString();
  }
}
