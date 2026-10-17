package dev.regpack.pack;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The one character policy (review focus PF1): its boundaries, code point by code point. */
class PackTextTest {
  private static String of(int cp) {
    return new String(Character.toChars(cp));
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        0x0000,
        0x0007,
        0x0009,
        0x000D,
        0x001F,
        0x007F,
        0x0080,
        0x0085,
        0x009F, // Cc
        0x00AD,
        0x0600,
        0x200B,
        0x200D,
        0x200E,
        0x202A,
        0x202E,
        0x2060,
        0x2066,
        0x206A,
        0xFEFF, // Cf
        0xFFF9,
        0xFFFB,
        0xE0001,
        0xE0041,
        0xE007F, // Cf
        0xD800,
        0xDBFF,
        0xDC00,
        0xDFFF, // Cs, lone
        0xE000,
        0xF8FF,
        0xF0000,
        0x10FFFD, // Co
        0x2028,
        0x2029, // Zl, Zp
        0xFDD0,
        0xFDEF,
        0xFFFE,
        0xFFFF,
        0x1FFFE,
        0x1FFFF,
        0x10FFFE,
        0x10FFFF, // noncharacters
        0x034F,
        0x115F,
        0x1160,
        0x17B4,
        0x17B5,
        0x180B,
        0x180C,
        0x180D,
        0x180F, // ignorables
        0x3164,
        0xFE00,
        0xFE0F,
        0xFFA0,
        0xE0000,
        0xE0100,
        0xE01EF,
        0xE0FFF // ignorables
      })
  void refusedCodePoints(int cp) {
    String text = "ab" + of(cp) + "cd";
    assertThat(PackText.isBad(cp, false)).as("U+%04X", cp).isTrue();
    assertThat(PackText.firstBad(text, false)).isPresent();
    assertThat(PackText.firstBad(text, false).get()).isEqualTo(new PackText.Bad(cp, 2));
    assertThat(PackText.firstBad(text, true).get()).isEqualTo(new PackText.Bad(cp, 2));
    assertThat(PackText.firstBad(of(cp), true)).contains(new PackText.Bad(cp, 0));
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        0x0020,
        0x0021,
        0x007E,
        0x00A0,
        0x00A1,
        0x00E9,
        0x034E,
        0x0350,
        0x0627,
        0x0905, // text
        0x115E,
        0x1161,
        0x17B3,
        0x17B6,
        0x180A,
        0x1810,
        0x2000,
        0x200A,
        0x2027,
        0x202F,
        0x3000,
        0x3163,
        0x3165,
        0xFDCF,
        0xFDF0,
        0xFE10,
        0xFFA1,
        0xFFFD, // neighbours of the refused ones
        0x4E2D,
        0xAC00,
        0x1F600,
        0x1D11E,
        0x20000,
        0xE1000,
        0xE2000 // scripts, emoji
      })
  void acceptedCodePoints(int cp) {
    assertThat(PackText.isBad(cp, false)).as("U+%04X", cp).isFalse();
    assertThat(PackText.firstBad("ab" + of(cp) + "cd", false)).isEmpty();
  }

  @Test
  void lfIsAllowedOnlyWhenAskedForAndTabAndCrNever() {
    assertThat(PackText.firstBad("a\nb", true)).isEmpty();
    assertThat(PackText.firstBad("a\nb", false)).contains(new PackText.Bad('\n', 1));
    assertThat(PackText.firstBad("a\tb", true)).contains(new PackText.Bad('\t', 1));
    assertThat(PackText.firstBad("a\rb", true)).contains(new PackText.Bad('\r', 1));
    assertThat(PackText.firstBad("a\r\nb", true)).contains(new PackText.Bad('\r', 1));
  }

  @Test
  void aValidSurrogatePairIsOneCodePointAndALoneSurrogateIsRefused() {
    assertThat(PackText.firstBad("x" + of(0x1F600) + "y", false)).isEmpty();
    // the index is in UTF-16 units: a pair takes two
    assertThat(PackText.firstBad("x" + of(0x1F600) + of(0x202E), false))
        .contains(new PackText.Bad(0x202E, 3));
    assertThat(PackText.firstBad("x\ud83d", false)).contains(new PackText.Bad(0xD83D, 1));
    assertThat(PackText.firstBad("\ude00\ud83d", false)).contains(new PackText.Bad(0xDE00, 0));
    assertThat(PackText.firstBad("\ud83dx", false)).contains(new PackText.Bad(0xD83D, 0));
  }

  @Test
  void theFirstOffenderIsReportedAndTheEmptyTextIsClean() {
    assertThat(PackText.firstBad("", false)).isEmpty();
    assertThat(PackText.firstBad("ok " + of(0x200B) + " then " + of(0x202E), false))
        .contains(new PackText.Bad(0x200B, 3));
  }

  @Test
  void theVerdictDoesNotDependOnTheDefaultLocale() {
    Locale saved = Locale.getDefault();
    try {
      for (String tag : new String[] {"tr-TR", "ar-SA", "th-TH-u-nu-thai", "hi-IN-u-nu-deva"}) {
        Locale.setDefault(Locale.forLanguageTag(tag));
        String clean = "I" + of(0x130) + of(0x131) + "i 123 " + of(0xE51) + of(0xE52);
        assertThat(PackText.firstBad(clean, false)).as(tag).isEmpty();
        assertThat(PackText.firstBad("x" + of(0x202E) + "y", false))
            .as(tag)
            .contains(new PackText.Bad(0x202E, 1));
      }
    } finally {
      Locale.setDefault(saved);
    }
  }
}
