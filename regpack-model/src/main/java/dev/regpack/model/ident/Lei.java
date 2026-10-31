package dev.regpack.model.ident;

import java.util.Objects;

/**
 * The shape and the check digits of a legal entity identifier: 18 upper-case ASCII letters and
 * digits followed by two check digits, which make the expanded number leave the remainder 1 modulo
 * 97 (ISO 7064 MOD 97-10, recalled and recomputed in the plan, vector V1). A null or a wrong shape
 * is invalid, never an exception. The messages never echo a value.
 */
public final class Lei {
  /** The length of a LEI. */
  public static final int LENGTH = 20;

  private static final int BASE_LENGTH = 18;

  private Lei() {}

  /** Whether {@code text} is 18 upper-case letters or digits and two digits. */
  public static boolean hasShape(String text) {
    if (text == null || text.length() != LENGTH) {
      return false;
    }
    for (int i = 0; i < BASE_LENGTH; i++) {
      if (!CheckDigits.isUpperOrDigit(text.charAt(i))) {
        return false;
      }
    }
    return CheckDigits.isDigit(text.charAt(BASE_LENGTH))
        && CheckDigits.isDigit(text.charAt(BASE_LENGTH + 1));
  }

  /** Whether {@code text} has the shape and its check digits are right. */
  public static boolean isValid(String text) {
    return isValid(text, Flaw.NONE);
  }

  /**
   * The 20 characters made of the 18-character base and its two check digits.
   *
   * @throws NullPointerException if {@code base18} is null
   * @throws IllegalArgumentException if {@code base18} is not 18 upper-case ASCII letters and
   *     digits
   */
  public static String withCheckDigits(String base18) {
    return withCheckDigits(base18, Flaw.NONE);
  }

  static boolean isValid(String text, Flaw flaw) {
    return hasShape(text) && CheckDigits.mod97(text, flaw) == 1;
  }

  static String withCheckDigits(String base18, Flaw flaw) {
    Objects.requireNonNull(base18, "base18");
    if (base18.length() != BASE_LENGTH) {
      throw new IllegalArgumentException("a LEI base must be 18 characters");
    }
    for (int i = 0; i < BASE_LENGTH; i++) {
      if (!CheckDigits.isUpperOrDigit(base18.charAt(i))) {
        throw new IllegalArgumentException(
            "a LEI base must be upper-case ASCII letters and digits only");
      }
    }
    int check = 98 - CheckDigits.mod97(base18 + "00", flaw);
    return base18 + (char) ('0' + check / 10) + (char) ('0' + check % 10);
  }
}
