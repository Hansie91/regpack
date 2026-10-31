package dev.regpack.model.ident;

/**
 * The two check digit algorithms behind the identifier classes: ISO 7064 MOD 97-10 (LEI) and Luhn
 * over the expanded digit string (ISIN). Letters are A to Z by explicit ASCII range, never by
 * {@code Character.isLetter}, and no locale is involved. Package-private: the {@link Flaw} seam is
 * for the mutant tests.
 */
final class CheckDigits {
  private static final int LETTER_BASE = 10;

  private CheckDigits() {}

  static boolean isUpper(char c) {
    return c >= 'A' && c <= 'Z';
  }

  static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  static boolean isUpperOrDigit(char c) {
    return isUpper(c) || isDigit(c);
  }

  /** The value of an upper-case letter in the expansion: A is 10 and Z is 35. */
  private static int letterValue(char c, Flaw flaw) {
    int value = c - 'A' + LETTER_BASE;
    return flaw == Flaw.LETTER_EXPANSION_SHIFTED ? value + 1 : value;
  }

  /**
   * The remainder of the expanded digit string of {@code text} (letters as two digits, digits as
   * they are) modulo 97 (96 under {@link Flaw#WRONG_MODULUS}). The caller has checked the text is
   * made of upper-case letters and digits.
   */
  static int mod97(String text, Flaw flaw) {
    int modulus = flaw == Flaw.WRONG_MODULUS ? 96 : 97;
    int remainder = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (isDigit(c)) {
        remainder = (remainder * 10 + (c - '0')) % modulus;
      } else {
        remainder = (remainder * 100 + letterValue(c, flaw)) % modulus;
      }
    }
    return remainder;
  }

  /** The expanded digit string of {@code text}: a letter becomes its two-digit value. */
  static String expand(String text, Flaw flaw) {
    StringBuilder digits = new StringBuilder(text.length() * 2);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (isDigit(c)) {
        digits.append(c);
      } else {
        digits.append(letterValue(c, flaw));
      }
    }
    return digits.toString();
  }

  /**
   * Whether the digit string passes the Luhn test: from the right, the check digit is position 0
   * and every second digit from position 1 is doubled (the other positions under {@link
   * Flaw#LUHN_DOUBLING_FLIPPED}); the digit sum must be a multiple of 10 (11 under {@link
   * Flaw#WRONG_MODULUS}).
   */
  static boolean luhn(String digits, Flaw flaw) {
    int modulus = flaw == Flaw.WRONG_MODULUS ? 11 : 10;
    boolean flipped = flaw == Flaw.LUHN_DOUBLING_FLIPPED;
    int sum = 0;
    for (int position = 0; position < digits.length(); position++) {
      int digit = digits.charAt(digits.length() - 1 - position) - '0';
      boolean doubled = (position % 2 == 1) != flipped;
      if (doubled) {
        digit *= 2;
        if (digit > 9) {
          digit -= 9;
        }
      }
      sum += digit;
    }
    return sum % modulus == 0;
  }
}
