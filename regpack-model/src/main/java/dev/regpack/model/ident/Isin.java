package dev.regpack.model.ident;

import java.util.Objects;

/**
 * The shape and the check digit of an international securities identification number: two
 * upper-case letters, nine upper-case letters or digits and one check digit that makes the Luhn
 * test pass over the expanded digit string (each letter two digits; vector V2). A null or a wrong
 * shape is invalid, never an exception. The messages never echo a value.
 */
public final class Isin {
  /** The length of an ISIN. */
  public static final int LENGTH = 12;

  private static final int BASE_LENGTH = 11;

  private Isin() {}

  /** Whether {@code text} is two letters, nine letters or digits and one digit. */
  public static boolean hasShape(String text) {
    if (text == null || text.length() != LENGTH) {
      return false;
    }
    return hasBaseShape(text) && CheckDigits.isDigit(text.charAt(BASE_LENGTH));
  }

  /** Whether {@code text} has the shape and its check digit is right. */
  public static boolean isValid(String text) {
    return isValid(text, Flaw.NONE);
  }

  /**
   * The 12 characters made of the 11-character base and its check digit.
   *
   * @throws NullPointerException if {@code base11} is null
   * @throws IllegalArgumentException if {@code base11} is not two upper-case ASCII letters and nine
   *     upper-case ASCII letters or digits
   */
  public static String withCheckDigit(String base11) {
    return withCheckDigit(base11, Flaw.NONE);
  }

  /** The first eleven characters: two letters, then nine letters or digits. */
  private static boolean hasBaseShape(String text) {
    for (int i = 0; i < BASE_LENGTH; i++) {
      char c = text.charAt(i);
      if (i < 2 ? !CheckDigits.isUpper(c) : !CheckDigits.isUpperOrDigit(c)) {
        return false;
      }
    }
    return true;
  }

  static boolean isValid(String text, Flaw flaw) {
    return hasShape(text) && CheckDigits.luhn(CheckDigits.expand(text, flaw), flaw);
  }

  static String withCheckDigit(String base11, Flaw flaw) {
    Objects.requireNonNull(base11, "base11");
    if (base11.length() != BASE_LENGTH) {
      throw new IllegalArgumentException("an ISIN base must be 11 characters");
    }
    if (!hasBaseShape(base11)) {
      throw new IllegalArgumentException(
          "an ISIN base must be two upper-case ASCII letters and nine letters or digits");
    }
    String digits = CheckDigits.expand(base11, flaw);
    // the one digit that passes the test (under a flaw there can be none: the last is returned)
    char check = '0';
    for (char candidate = '0'; candidate <= '9'; candidate++) {
      check = candidate;
      if (CheckDigits.luhn(digits + candidate, flaw)) {
        break;
      }
    }
    return base11 + check;
  }
}
