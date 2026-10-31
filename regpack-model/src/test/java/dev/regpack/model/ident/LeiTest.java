package dev.regpack.model.ident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The LEI check (plan T3, vector V1). The three examples are published ones recalled from the
 * research record; they may appear in unit tests only, never in a sample, seed, pack or golden.
 */
class LeiTest {
  static final List<String> PUBLISHED =
      List.of("529900T8BM49AURSDO55", "506700GE1G29325QX363", "5493001KJTIIGC8Y1R12");

  @Test
  void publishedExamplesAreValid() {
    for (String lei : PUBLISHED) {
      assertThat(Lei.hasShape(lei)).as(lei).isTrue();
      assertThat(Lei.isValid(lei)).as(lei).isTrue();
    }
  }

  @Test
  void aChangedLastDigitIsInvalid() {
    for (String lei : PUBLISHED) {
      char last = lei.charAt(19);
      char raised = (char) ('0' + (last - '0' + 1) % 10);
      String changed = lei.substring(0, 19) + raised;
      assertThat(changed).isNotEqualTo(lei);
      assertThat(Lei.hasShape(changed)).as(changed).isTrue();
      assertThat(Lei.isValid(changed)).as(changed).isFalse();
    }
  }

  @Test
  void everySameClassSubstitutionOfAPublishedExampleIsInvalid() {
    List<String> candidates = new ArrayList<>();
    for (String lei : PUBLISHED) {
      for (int i = 0; i < lei.length(); i++) {
        char original = lei.charAt(i);
        boolean digit = original >= '0' && original <= '9';
        char from = digit ? '0' : 'A';
        char to = digit ? '9' : 'Z';
        for (char c = from; c <= to; c++) {
          if (c != original) {
            candidates.add(lei.substring(0, i) + c + lei.substring(i + 1));
          }
        }
      }
    }
    // 11 digits and 9 letters in the first and third example, 15 and 5 in the second [V]
    assertThat(candidates).hasSize(908);
    assertThat(candidates).doesNotHaveDuplicates();
    for (String candidate : candidates) {
      assertThat(Lei.hasShape(candidate)).as(candidate).isTrue();
      assertThat(Lei.isValid(candidate)).as(candidate).isFalse();
    }
  }

  @Test
  void shapeRefusesLowerCaseShortLongFullWidthDigitsAndNull() {
    String good = "529900T8BM49AURSDO55";
    assertThat(Lei.hasShape(good)).isTrue();
    assertThat(Lei.hasShape(good.toLowerCase(java.util.Locale.ROOT))).isFalse();
    assertThat(Lei.hasShape(good.substring(0, 19))).isFalse();
    assertThat(Lei.hasShape(good + "0")).isFalse();
    assertThat(Lei.hasShape("")).isFalse();
    assertThat(Lei.hasShape(null)).isFalse();
    // full-width digit five (U+FF15) in a digit position and in the check digits
    assertThat(Lei.hasShape("５" + good.substring(1))).isFalse();
    assertThat(Lei.hasShape(good.substring(0, 18) + "５５")).isFalse();
    // Arabic-Indic digit (U+0665), a letter in a check digit position, a blank, a hyphen
    assertThat(Lei.hasShape(good.substring(0, 19) + "٥")).isFalse();
    assertThat(Lei.hasShape(good.substring(0, 19) + "A")).isFalse();
    assertThat(Lei.hasShape(good.substring(0, 5) + " " + good.substring(6))).isFalse();
    assertThat(Lei.hasShape(good.substring(0, 5) + "-" + good.substring(6))).isFalse();
    // a wrong shape is invalid, never an exception
    assertThat(Lei.isValid(null)).isFalse();
    assertThat(Lei.isValid("")).isFalse();
    assertThat(Lei.isValid(good.toLowerCase(java.util.Locale.ROOT))).isFalse();
    assertThat(Lei.isValid("５" + good.substring(1))).isFalse();
  }

  @Test
  void checkDigitsOfTheSyntheticBases() {
    assertThat(Lei.withCheckDigits("SYNT00FIRM00000001")).isEqualTo("SYNT00FIRM0000000152");
    assertThat(Lei.withCheckDigits("SYNT00CCP000000001")).isEqualTo("SYNT00CCP00000000194");
    assertThat(Lei.withCheckDigits("SYNT00CLIENT000001")).isEqualTo("SYNT00CLIENT00000167");
    assertThat(Lei.withCheckDigits("SYNT00CLIENT000002")).isEqualTo("SYNT00CLIENT00000264");
    assertThat(Lei.withCheckDigits("SYNT00LAPSED000001")).isEqualTo("SYNT00LAPSED00000132");
    // the check digits of the published examples are recovered from their first 18 characters
    for (String lei : PUBLISHED) {
      assertThat(Lei.withCheckDigits(lei.substring(0, 18))).isEqualTo(lei);
    }
    // a base 00 .. 99 gives a check digit pair from 02 to 98 (never 00, 01 or 99)
    assertThat(Lei.withCheckDigits("000000000000000000")).matches("[0-9]{20}");
  }

  @Test
  void withCheckDigitsRefusesABaseThatIsNot18Characters() {
    assertThatThrownBy(() -> Lei.withCheckDigits("SYNT00FIRM0000000"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Lei.withCheckDigits("SYNT00FIRM000000001"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Lei.withCheckDigits("")).isInstanceOf(IllegalArgumentException.class);
    // 18 characters that are not upper-case letters and digits
    assertThatThrownBy(() -> Lei.withCheckDigits("synt00firm00000001"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Lei.withCheckDigits("SYNT00FIRM0000000-"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Lei.withCheckDigits("５YNT00FIRM00000001"))
        .isInstanceOf(IllegalArgumentException.class);
    // the message names the rule and never echoes the value
    assertThatThrownBy(() -> Lei.withCheckDigits("SYNT00FIRM0000000"))
        .hasMessageNotContaining("SYNT00");
    assertThatThrownBy(() -> Lei.withCheckDigits(null)).isInstanceOf(NullPointerException.class);
    assertThatCode(() -> Lei.withCheckDigits("ZZZZZZZZZZZZZZZZZZ")).doesNotThrowAnyException();
  }
}
