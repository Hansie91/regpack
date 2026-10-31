package dev.regpack.model.ident;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * The shape of a currency code (three upper-case ASCII letters) and the DEMO list of the three
 * codes that name no currency used in a trade (plan V08, a recollection of ISO 4217 [R], not a
 * regulator-published list).
 */
public final class Currency {
  /** The DEMO list of codes that are not a trade currency, in ascending order. */
  public static final Set<String> NON_CURRENCY =
      Collections.unmodifiableSet(new TreeSet<>(Set.of("XAU", "XDR", "XXX")));

  private Currency() {}

  /** Whether {@code text} is three upper-case ASCII letters; null is not. */
  public static boolean hasShape(String text) {
    if (text == null || text.length() != 3) {
      return false;
    }
    for (int i = 0; i < 3; i++) {
      if (!CheckDigits.isUpper(text.charAt(i))) {
        return false;
      }
    }
    return true;
  }
}
