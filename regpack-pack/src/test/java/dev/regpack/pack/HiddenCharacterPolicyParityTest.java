package dev.regpack.pack;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.tools.HiddenCharacters;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The repository gate ({@code EolCheck} in regpack-tools, which depends on no other module) refuses
 * hidden characters by the same policy as {@link PackText}. The two code point sets are written out
 * twice, so this test compares them over every code point and fails when they drift apart.
 */
class HiddenCharacterPolicyParityTest {
  @Test
  void theGateAndPackTextRefuseExactlyTheSameCodePointsExceptTabAndCarriageReturn() {
    List<String> differences = new ArrayList<>();
    for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
      boolean packText = PackText.isBad(cp, true);
      boolean gate = HiddenCharacters.isHidden(cp);
      boolean textFileMayHoldIt = cp == '\t' || cp == '\r';
      boolean expectedGate = packText && !textFileMayHoldIt;
      if (gate != expectedGate) {
        differences.add(String.format("U+%04X packText=%b gate=%b", cp, packText, gate));
      }
    }
    assertThat(differences).as("code points on which the two policies differ").isEmpty();
  }

  @Test
  void theCodePointsNamedByTheReviewAreOnBothSides() {
    int[] named = {
      0x061C, 0x180E, 0x115F, 0x3164, 0x206A, 0x206F, 0x2061, 0x2064, 0x00AD, 0xFE0F, 0xE0041,
      0xE0000, 0xE007F, 0x2065, 0xFFF0, 0xFFF8, 0xFFF9, 0xFFFB
    };
    for (int cp : named) {
      assertThat(PackText.isBad(cp, true)).as("PackText U+%04X", cp).isTrue();
      assertThat(HiddenCharacters.isHidden(cp)).as("gate U+%04X", cp).isTrue();
    }
  }
}
