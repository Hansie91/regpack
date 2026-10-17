package dev.regpack.engine.expr;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written lexer of the expression subset. Numbers are ASCII digits only: an integer is 64-bit,
 * a decimal keeps the scale of its text (12.50 has scale 2) and has at most 38 digits, an exponent
 * or a hex literal is an error. Strings use single or double quotes with the escapes {@code \\ \'
 * \" \n \r \t}. Whitespace is space, tab, CR and LF only. Nothing here depends on the default
 * locale, and no message echoes the offending text.
 */
final class Lexer {
  /** Token kinds. Keywords ({@code true}, {@code false}, {@code null}, {@code in}) are IDENT. */
  enum Kind {
    INT,
    DECIMAL,
    STRING,
    IDENT,
    OP,
    EOF
  }

  /** A token; {@code text} of a STRING is the decoded value, of anything else the source text. */
  record Token(Kind kind, String text, int pos) {}

  private Lexer() {}

  static List<Token> tokenize(String source) {
    List<Token> tokens = new ArrayList<>();
    int i = 0;
    int n = source.length();
    while (i < n) {
      char c = source.charAt(i);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        i++;
      } else if (isDigit(c)) {
        i = number(source, i, tokens);
      } else if (isIdentStart(c)) {
        int start = i;
        while (i < n && isIdentPart(source.charAt(i))) {
          i++;
        }
        tokens.add(new Token(Kind.IDENT, source.substring(start, i), start));
      } else if (c == '\'' || c == '"') {
        i = string(source, i, tokens);
      } else {
        i = operator(source, i, tokens);
      }
    }
    tokens.add(new Token(Kind.EOF, "", n));
    return List.copyOf(tokens);
  }

  private static int number(String source, int start, List<Token> tokens) {
    int n = source.length();
    int i = start;
    while (i < n && isDigit(source.charAt(i))) {
      i++;
    }
    int fractionStart = -1;
    if (i + 1 < n && source.charAt(i) == '.' && isDigit(source.charAt(i + 1))) {
      i++;
      fractionStart = i;
      while (i < n && isDigit(source.charAt(i))) {
        i++;
      }
    }
    boolean decimal = fractionStart >= 0;
    if (i < n && (source.charAt(i) == 'e' || source.charAt(i) == 'E')) {
      throw parseError("exponent literals are not supported", start);
    }
    if (i < n && isIdentPart(source.charAt(i))) {
      throw parseError("malformed number", start);
    }
    String text = source.substring(start, i);
    if (decimal) {
      if (!decimalFits(source, start, fractionStart, i)) {
        throw parseError(
            "decimal literal exceeds " + Library.MAX_DECIMAL_DIGITS + " digits", start);
      }
    } else if (!fitsLong(text)) {
      throw parseError("integer literal out of range", start);
    }
    tokens.add(new Token(decimal ? Kind.DECIMAL : Kind.INT, text, start));
    return i;
  }

  /**
   * Whether a decimal literal has a scale and a precision of at most 38, as {@code BigDecimal}
   * would measure them (the precision ignores leading zeros), counted from the source indices in
   * linear time: constructing the {@code BigDecimal} of a hostile literal first costs quadratic
   * time.
   */
  private static boolean decimalFits(String source, int start, int fractionStart, int end) {
    if (end - fractionStart > Library.MAX_DECIMAL_DIGITS) {
      return false;
    }
    int significantFrom = start;
    while (significantFrom < end
        && (source.charAt(significantFrom) == '0' || source.charAt(significantFrom) == '.')) {
      significantFrom++;
    }
    int digits = end - significantFrom;
    if (significantFrom < fractionStart) {
      digits--; // the dot lies inside the significant digits
    }
    return Math.max(digits, 1) <= Library.MAX_DECIMAL_DIGITS;
  }

  private static boolean fitsLong(String digits) {
    try {
      Long.parseLong(digits);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static int string(String source, int start, List<Token> tokens) {
    char quote = source.charAt(start);
    StringBuilder out = new StringBuilder();
    int n = source.length();
    int i = start + 1;
    while (i < n) {
      char c = source.charAt(i);
      if (c == quote) {
        tokens.add(new Token(Kind.STRING, out.toString(), start));
        return i + 1;
      }
      if (c == '\n' || c == '\r') {
        break;
      }
      if (c == '\\') {
        if (i + 1 >= n) {
          break;
        }
        char escaped = source.charAt(i + 1);
        switch (escaped) {
          case '\\', '\'', '"' -> out.append(escaped);
          case 'n' -> out.append('\n');
          case 'r' -> out.append('\r');
          case 't' -> out.append('\t');
          default -> throw parseError("unknown escape sequence", i);
        }
        i += 2;
      } else {
        out.append(c);
        i++;
      }
    }
    throw parseError("unterminated string literal", start);
  }

  private static int operator(String source, int i, List<Token> tokens) {
    char c = source.charAt(i);
    char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
    String op;
    switch (c) {
      case '+', '-', '*', '/', '(', ')', '[', ']', ',', '.', '?', ':' -> op = String.valueOf(c);
      case '!' -> op = next == '=' ? "!=" : "!";
      case '=' -> {
        if (next != '=') {
          throw parseError("assignment is not supported", i);
        }
        op = "==";
      }
      case '<' -> op = next == '=' ? "<=" : "<";
      case '>' -> op = next == '=' ? ">=" : ">";
      case '&' -> {
        if (next != '&') {
          throw parseError("a single & is not supported", i);
        }
        op = "&&";
      }
      case '|' -> {
        if (next != '|') {
          throw parseError("a single | is not supported", i);
        }
        op = "||";
      }
      default -> throw parseError("unexpected character", i);
    }
    tokens.add(new Token(Kind.OP, op, i));
    return i + op.length();
  }

  private static ExprException parseError(String what, int pos) {
    return new ExprException(ErrorKind.PARSE, what + " at " + pos);
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isIdentStart(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
  }

  private static boolean isIdentPart(char c) {
    return isIdentStart(c) || isDigit(c);
  }
}
