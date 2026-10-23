package dev.regpack.app;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the test methods of a JUnit 5 source file without a compiler: every method that carries
 * {@code @Test}, {@code @ParameterizedTest}, {@code @RepeatedTest}, {@code @TestFactory} or
 * {@code @TestTemplate}, whatever other annotation lines, modifiers or return type stand between
 * the annotation and the name (CI-4: a method with {@code @Disabled} or {@code @Timeout} after
 * {@code @Test} is a test method too). Comments and literals are blanked first, so a mention of the
 * annotation in a comment or a string is not a method. An annotation that is not followed by a
 * method declaration is an {@link IllegalStateException}: the checker never skips what it cannot
 * read.
 */
final class TestMethods {
  private static final Pattern TEST_ANNOTATION =
      Pattern.compile(
          "(?<![\\w.@])@(?:\\w+\\.)*"
              + "(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");
  private static final Pattern LAST_WORD = Pattern.compile("(\\w+)\\s*$");

  private TestMethods() {}

  /** The names of the test methods in source order; a name that occurs twice occurs twice. */
  static List<String> names(String source) {
    String code = blanked(source);
    List<String> names = new ArrayList<>();
    Matcher annotation = TEST_ANNOTATION.matcher(code);
    while (annotation.find()) {
      int at = afterAnnotations(code, annotation.end());
      int open = code.indexOf('(', at);
      if (open < 0) {
        throw unreadable(code, annotation.start());
      }
      String head = code.substring(at, open);
      Matcher name = LAST_WORD.matcher(head);
      if (head.matches("(?s).*[;{}=].*") || !name.find()) {
        throw unreadable(code, annotation.start());
      }
      names.add(name.group(1));
    }
    return names;
  }

  /** The index after the annotation arguments and after every further annotation. */
  private static int afterAnnotations(String code, int from) {
    int i = from;
    while (true) {
      while (i < code.length() && Character.isWhitespace(code.charAt(i))) {
        i++;
      }
      if (i >= code.length()) {
        return i;
      }
      char c = code.charAt(i);
      if (c == '(') {
        i = afterParentheses(code, i);
      } else if (c == '@') {
        i++;
        while (i < code.length()
            && (Character.isJavaIdentifierPart(code.charAt(i)) || code.charAt(i) == '.')) {
          i++;
        }
      } else {
        return i;
      }
    }
  }

  private static int afterParentheses(String code, int open) {
    int depth = 0;
    for (int i = open; i < code.length(); i++) {
      if (code.charAt(i) == '(') {
        depth++;
      } else if (code.charAt(i) == ')' && --depth == 0) {
        return i + 1;
      }
    }
    throw new IllegalStateException("unbalanced parentheses after a test annotation");
  }

  private static IllegalStateException unreadable(String code, int at) {
    int line = 1;
    for (int i = 0; i < at; i++) {
      if (code.charAt(i) == '\n') {
        line++;
      }
    }
    return new IllegalStateException(
        "a test annotation at line " + line + " is not followed by a method declaration");
  }

  /**
   * The source with comments, string literals, text blocks and character literals replaced by
   * spaces (newlines kept), so that only code is left to scan.
   */
  static String blanked(String source) {
    StringBuilder out = new StringBuilder(source.length());
    int i = 0;
    int n = source.length();
    while (i < n) {
      char c = source.charAt(i);
      if (source.startsWith("//", i)) {
        while (i < n && source.charAt(i) != '\n') {
          out.append(' ');
          i++;
        }
      } else if (source.startsWith("/*", i)) {
        int end = source.indexOf("*/", i + 2);
        int stop = end < 0 ? n : end + 2;
        blank(source, i, stop, out);
        i = stop;
      } else if (source.startsWith("\"\"\"", i)) {
        int end = source.indexOf("\"\"\"", i + 3);
        int stop = end < 0 ? n : end + 3;
        blank(source, i, stop, out);
        i = stop;
      } else if (c == '"' || c == '\'') {
        int j = i + 1;
        while (j < n && source.charAt(j) != c && source.charAt(j) != '\n') {
          j += source.charAt(j) == '\\' ? 2 : 1;
        }
        int stop = Math.min(n, j + 1);
        blank(source, i, stop, out);
        i = stop;
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }

  private static void blank(String source, int from, int to, StringBuilder out) {
    for (int i = from; i < to; i++) {
      out.append(source.charAt(i) == '\n' ? '\n' : ' ');
    }
  }
}
