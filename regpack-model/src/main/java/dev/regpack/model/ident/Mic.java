package dev.regpack.model.ident;

/** The shape of a market identifier code: four upper-case ASCII letters or digits. */
public final class Mic {
  /** The length of a MIC. */
  public static final int LENGTH = 4;

  private Mic() {}

  /** Whether {@code text} is four upper-case ASCII letters or digits; null is not. */
  public static boolean hasShape(String text) {
    if (text == null || text.length() != LENGTH) {
      return false;
    }
    for (int i = 0; i < LENGTH; i++) {
      if (!CheckDigits.isUpperOrDigit(text.charAt(i))) {
        return false;
      }
    }
    return true;
  }
}
