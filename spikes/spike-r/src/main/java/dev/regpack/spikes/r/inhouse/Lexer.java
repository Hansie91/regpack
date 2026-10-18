package dev.regpack.spikes.r.inhouse;

import dev.regpack.ports.expr.ExpressionException;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written lexer of the in-house expression subset. Numbers are ASCII digits only: an integer
 * is 64-bit, a decimal keeps the scale of its text (12.50 has scale 2), an exponent is an error.
 * Strings use single or double quotes with the escapes \\ \' \" \n \r \t. Nothing here depends on
 * the default locale.
 */
public final class Lexer {
  /** Token kinds. Keywords ({@code true}, {@code false}, {@code null}, {@code in}) are IDENT. */
  public enum Kind {
    INT,
    DECIMAL,
    STRING,
    IDENT,
    OP,
    EOF
  }

  /** A token; {@code text} of a STRING is the decoded value, of anything else the source text. */
  public record Token(Kind kind, String text, int pos) {}

  private Lexer() {}

  public static List<Token> tokenize(String src) {
    List<Token> tokens = new ArrayList<>();
    int i = 0;
    int n = src.length();
    while (i < n) {
      char c = src.charAt(i);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        i++;
      } else if (isDigit(c)) {
        i = number(src, i, tokens);
      } else if (isIdentStart(c)) {
        int start = i;
        while (i < n && isIdentPart(src.charAt(i))) {
          i++;
        }
        tokens.add(new Token(Kind.IDENT, src.substring(start, i), start));
      } else if (c == '\'' || c == '"') {
        i = string(src, i, tokens);
      } else {
        i = operator(src, i, tokens);
      }
    }
    tokens.add(new Token(Kind.EOF, "", n));
    return List.copyOf(tokens);
  }

  private static int number(String src, int start, List<Token> tokens) {
    int n = src.length();
    int i = start;
    while (i < n && isDigit(src.charAt(i))) {
      i++;
    }
    boolean decimal = false;
    if (i + 1 < n && src.charAt(i) == '.' && isDigit(src.charAt(i + 1))) {
      decimal = true;
      i++;
      while (i < n && isDigit(src.charAt(i))) {
        i++;
      }
    }
    if (i < n && (src.charAt(i) == 'e' || src.charAt(i) == 'E')) {
      throw new ExpressionException("exponent literals are not supported at " + start);
    }
    if (i < n && isIdentPart(src.charAt(i))) {
      throw new ExpressionException("malformed number at " + start);
    }
    String text = src.substring(start, i);
    if (!decimal) {
      try {
        Long.parseLong(text);
      } catch (NumberFormatException e) {
        throw new ExpressionException("integer literal out of range at " + start);
      }
    }
    tokens.add(new Token(decimal ? Kind.DECIMAL : Kind.INT, text, start));
    return i;
  }

  private static int string(String src, int start, List<Token> tokens) {
    char quote = src.charAt(start);
    StringBuilder out = new StringBuilder();
    int n = src.length();
    int i = start + 1;
    while (i < n) {
      char c = src.charAt(i);
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
        char e = src.charAt(i + 1);
        switch (e) {
          case '\\', '\'', '"' -> out.append(e);
          case 'n' -> out.append('\n');
          case 'r' -> out.append('\r');
          case 't' -> out.append('\t');
          default -> throw new ExpressionException("unknown escape \\" + e + " at " + i);
        }
        i += 2;
      } else {
        out.append(c);
        i++;
      }
    }
    throw new ExpressionException("unterminated string literal at " + start);
  }

  private static int operator(String src, int i, List<Token> tokens) {
    char c = src.charAt(i);
    char next = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
    String op;
    switch (c) {
      case '+', '-', '*', '/', '(', ')', '[', ']', ',', '.', '?', ':' -> op = String.valueOf(c);
      case '!' -> op = next == '=' ? "!=" : "!";
      case '=' -> {
        if (next != '=') {
          throw new ExpressionException("assignment is not supported at " + i);
        }
        op = "==";
      }
      case '<' -> op = next == '=' ? "<=" : "<";
      case '>' -> op = next == '=' ? ">=" : ">";
      case '&' -> {
        if (next != '&') {
          throw new ExpressionException("single & is not supported at " + i);
        }
        op = "&&";
      }
      case '|' -> {
        if (next != '|') {
          throw new ExpressionException("single | is not supported at " + i);
        }
        op = "||";
      }
      default -> throw new ExpressionException("unexpected character at " + i);
    }
    tokens.add(new Token(Kind.OP, op, i));
    return i + op.length();
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
