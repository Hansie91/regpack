package dev.regpack.pack;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one character policy of the Regime Pack (review focus PF1). It is used for whole YAML files
 * ({@code StrictYaml}), for every cell of a decision table and for every decoded string of a golden
 * file, so that invisible, control and direction-changing characters cannot hide in pack content.
 *
 * <p>The policy refuses by code point, independent of any locale, using {@link
 * Character#getType(int)} and explicit ranges:
 *
 * <ul>
 *   <li>the general categories Cc (control), Cf (format), Cs (surrogate: a lone surrogate, as a
 *       valid pair is one supplementary code point), Co (private use), Zl and Zp (line and
 *       paragraph separators);
 *   <li>LF is allowed only when asked for; TAB and CR are never allowed (whole-file YAML text has
 *       its CRLF line ends normalised to LF by its caller before it is checked, so a CR that
 *       remains is a lone CR);
 *   <li>the noncharacters U+FDD0 to U+FDEF and U+xFFFE, U+xFFFF of every plane;
 *   <li>the default-ignorable code points that are not Cf: U+034F, U+115F to U+1160, U+17B4 to
 *       U+17B5, U+180B to U+180F, U+3164, U+FE00 to U+FE0F, U+FFA0 and U+E0000 to U+E0FFF, and the
 *       unassigned (Cn) default-ignorable U+2065 and U+FFF0 to U+FFF8.
 * </ul>
 *
 * Other unassigned code points (Cn) and everything else, including the letters, digits, punctuation
 * and symbols of every script, are allowed.
 */
public final class PackText {
  /** The first offending code point of a text and its index (in UTF-16 units). */
  public record Bad(int codePoint, int index) {}

  private PackText() {}

  /**
   * The first code point of {@code text} that the policy refuses.
   *
   * @param allowLf whether LF (U+000A) is an acceptable character
   */
  public static Optional<Bad> firstBad(String text, boolean allowLf) {
    int i = 0;
    while (i < text.length()) {
      int cp = text.codePointAt(i);
      if (isBad(cp, allowLf)) {
        return Optional.of(new Bad(cp, i));
      }
      i += Character.charCount(cp);
    }
    return Optional.empty();
  }

  /**
   * The first refused code point of a decoded JSON tree (strings, map keys and map values, lists,
   * in iteration order; numbers, booleans and null are not text). The index is within the string
   * that holds the code point.
   */
  public static Optional<Bad> firstBadInTree(Object tree) {
    return switch (tree) {
      case String text -> firstBad(text, false);
      case Map<?, ?> map -> {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          Optional<Bad> bad = firstBadInTree(entry.getKey());
          if (bad.isEmpty()) {
            bad = firstBadInTree(entry.getValue());
          }
          if (bad.isPresent()) {
            yield bad;
          }
        }
        yield Optional.empty();
      }
      case List<?> list -> {
        for (Object element : list) {
          Optional<Bad> bad = firstBadInTree(element);
          if (bad.isPresent()) {
            yield bad;
          }
        }
        yield Optional.empty();
      }
      default -> Optional.empty();
    };
  }

  /** Whether the policy refuses one code point. */
  public static boolean isBad(int cp, boolean allowLf) {
    if (cp == '\n') {
      return !allowLf;
    }
    switch (Character.getType(cp)) {
      case Character.CONTROL,
          Character.FORMAT,
          Character.SURROGATE,
          Character.PRIVATE_USE,
          Character.LINE_SEPARATOR,
          Character.PARAGRAPH_SEPARATOR -> {
        return true;
      }
      default -> {
        // fall through to the explicit ranges
      }
    }
    if ((cp >= 0xFDD0 && cp <= 0xFDEF) || (cp & 0xFFFE) == 0xFFFE) {
      return true; // noncharacters
    }
    return cp == 0x034F
        || (cp >= 0x115F && cp <= 0x1160)
        || (cp >= 0x17B4 && cp <= 0x17B5)
        || (cp >= 0x180B && cp <= 0x180F)
        || cp == 0x3164
        || (cp >= 0xFE00 && cp <= 0xFE0F)
        || cp == 0xFFA0
        || cp == 0x2065
        || (cp >= 0xFFF0 && cp <= 0xFFF8)
        || (cp >= 0xE0000 && cp <= 0xE0FFF);
  }
}
