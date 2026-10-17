package dev.regpack.tools;

/**
 * The hidden-character policy of the repository gate: the character policy of the Regime Pack
 * ({@code dev.regpack.pack.PackText}) minus the tab and the carriage return, which text files may
 * hold (a carriage return is governed by the line-ending rules). {@code regpack-tools} depends on
 * no other module, so the code point sets are repeated here; a test of {@code regpack-pack}
 * compares the two over every code point and fails when they drift apart.
 *
 * <p>Refused, by code point and independent of any locale: the general categories Cc (except LF, CR
 * and TAB), Cf, Cs, Co, Zl and Zp; the noncharacters U+FDD0 to U+FDEF and U+xFFFE, U+xFFFF of every
 * plane; and the default-ignorable code points that are not Cf: U+034F, U+115F to U+1160, U+17B4 to
 * U+17B5, U+180B to U+180F, U+3164, U+FE00 to U+FE0F, U+FFA0, U+E0000 to U+E0FFF, and the
 * unassigned default-ignorables U+2065 and U+FFF0 to U+FFF8.
 */
public final class HiddenCharacters {
  /** How a refused code point is named in a problem. */
  public enum Kind {
    /** A bidirectional control (Trojan Source): U+202A to U+202E, U+2066 to U+2069, U+061C. */
    BIDI_CONTROL,
    /** A zero-width character or byte order mark: U+200B to U+200F, U+2060, U+FEFF. */
    ZERO_WIDTH,
    /** A Unicode line or paragraph separator: U+2028, U+2029, U+0085. */
    UNICODE_LINE_SEPARATOR,
    /** Any other refused code point (control, format, private use, tag, ...). */
    HIDDEN_CHARACTER
  }

  private HiddenCharacters() {}

  /** Whether the policy refuses one code point in a tracked text file. */
  public static boolean isHidden(int cp) {
    if (cp == '\n' || cp == '\r' || cp == '\t') {
      return false;
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

  /** The kind of a code point for which {@link #isHidden} is true. */
  public static Kind kindOf(int cp) {
    if ((cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069) || cp == 0x061C) {
      return Kind.BIDI_CONTROL;
    }
    if ((cp >= 0x200B && cp <= 0x200F) || cp == 0x2060 || cp == 0xFEFF) {
      return Kind.ZERO_WIDTH;
    }
    if (cp == 0x2028 || cp == 0x2029 || cp == 0x0085) {
      return Kind.UNICODE_LINE_SEPARATOR;
    }
    return Kind.HIDDEN_CHARACTER;
  }
}
