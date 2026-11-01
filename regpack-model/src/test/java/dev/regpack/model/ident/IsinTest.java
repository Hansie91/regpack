package dev.regpack.model.ident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The ISIN check (plan T3, vector V2). The three examples are published ones recalled from the
 * research record; they may appear in unit tests only.
 */
class IsinTest {
  static final List<String> PUBLISHED = List.of("US0378331005", "GB0002634946", "DE000CV26046");

  @Test
  void publishedExamplesAreValid() {
    for (String isin : PUBLISHED) {
      assertThat(Isin.hasShape(isin)).as(isin).isTrue();
      assertThat(Isin.isValid(isin)).as(isin).isTrue();
    }
  }

  @Test
  void aChangedLastDigitIsInvalid() {
    for (String isin : PUBLISHED) {
      char last = isin.charAt(11);
      char raised = (char) ('0' + (last - '0' + 1) % 10);
      String changed = isin.substring(0, 11) + raised;
      assertThat(changed).isNotEqualTo(isin);
      assertThat(Isin.hasShape(changed)).as(changed).isTrue();
      assertThat(Isin.isValid(changed)).as(changed).isFalse();
    }
  }

  @Test
  void everySingleDigitSubstitutionOfAPublishedExampleIsInvalid() {
    List<String> candidates = new ArrayList<>();
    for (String isin : PUBLISHED) {
      for (int i = 0; i < isin.length(); i++) {
        char original = isin.charAt(i);
        if (original >= '0' && original <= '9') {
          for (char c = '0'; c <= '9'; c++) {
            if (c != original) {
              candidates.add(isin.substring(0, i) + c + isin.substring(i + 1));
            }
          }
        }
      }
    }
    // 10 and 10 digit positions in the first two examples, 8 in the third: 90 + 90 + 72 [V]
    assertThat(candidates).hasSize(252);
    assertThat(candidates).doesNotHaveDuplicates();
    for (String candidate : candidates) {
      assertThat(Isin.hasShape(candidate)).as(candidate).isTrue();
      assertThat(Isin.isValid(candidate)).as(candidate).isFalse();
    }
    // no ISIN letter substitution is asserted: a letter changes the expanded digit string and
    // can still validate (V2)
  }

  @Test
  void shapeRefusesWhatIsNotTwoLettersNineAlphanumericsAndADigit() {
    String good = "US0378331005";
    assertThat(Isin.hasShape(good)).isTrue();
    assertThat(Isin.hasShape(good.toLowerCase(java.util.Locale.ROOT))).isFalse();
    assertThat(Isin.hasShape(good.substring(0, 11))).isFalse();
    assertThat(Isin.hasShape(good + "0")).isFalse();
    assertThat(Isin.hasShape("")).isFalse();
    assertThat(Isin.hasShape(null)).isFalse();
    assertThat(Isin.hasShape("0S0378331005")).isFalse();
    assertThat(Isin.hasShape("U10378331005")).isFalse();
    assertThat(Isin.hasShape(good.substring(0, 11) + "A")).isFalse();
    assertThat(Isin.hasShape("ＵS0378331005")).isFalse();
    assertThat(Isin.hasShape(good.substring(0, 11) + "５")).isFalse();
    assertThat(Isin.isValid(null)).isFalse();
    assertThat(Isin.isValid("")).isFalse();
    assertThat(Isin.isValid(good.toLowerCase(java.util.Locale.ROOT))).isFalse();
  }

  @Test
  void withCheckDigitGivesZZ0000000016() {
    assertThat(Isin.withCheckDigit("ZZ000000001")).isEqualTo("ZZ0000000016");
    assertThat(Isin.withCheckDigit("ZZ000000002")).isEqualTo("ZZ0000000024");
    assertThat(Isin.withCheckDigit("ZZ000000003")).isEqualTo("ZZ0000000032");
    assertThat(Isin.withCheckDigit("ZZ000000004")).isEqualTo("ZZ0000000040");
    assertThat(Isin.withCheckDigit("ZZ000000005")).isEqualTo("ZZ0000000057");
    assertThat(Isin.withCheckDigit("ZZ000000006")).isEqualTo("ZZ0000000065");
    for (String isin : PUBLISHED) {
      assertThat(Isin.withCheckDigit(isin.substring(0, 11))).isEqualTo(isin);
    }
  }

  /** The check digit by an independent Luhn: letters expand to two digits, A is 10 and Z is 35. */
  private static char luhnCheckDigit(String base11) {
    StringBuilder digits = new StringBuilder();
    for (char c : base11.toCharArray()) {
      digits.append(c >= 'A' ? Integer.toString(c - 'A' + 10) : Character.toString(c));
    }
    int sum = 0;
    boolean doubling = true; // the digit next to the check digit is doubled
    for (int i = digits.length() - 1; i >= 0; i--) {
      int d = digits.charAt(i) - '0';
      if (doubling) {
        d *= 2;
        if (d > 9) {
          d -= 9;
        }
      }
      sum += d;
      doubling = !doubling;
    }
    return (char) ('0' + (10 - sum % 10) % 10);
  }

  @Test
  void withCheckDigitFindsEveryCheckDigitFromZeroToNine() {
    String[] found = new String[10];
    for (int i = 0; i < 1000 && java.util.Arrays.asList(found).contains(null); i++) {
      String base = "ZZ00000" + String.format(java.util.Locale.ROOT, "%04d", i);
      char expected = luhnCheckDigit(base);
      if (found[expected - '0'] == null) {
        found[expected - '0'] = base;
      }
    }
    assertThat(found).as("a base for each of the ten check digits").doesNotContainNull();
    for (int digit = 0; digit <= 9; digit++) {
      String base = found[digit];
      String isin = Isin.withCheckDigit(base);
      assertThat(isin).as(base).hasSize(12).startsWith(base);
      assertThat(isin.charAt(11)).as("check digit of " + base).isEqualTo((char) ('0' + digit));
      assertThat(Isin.isValid(isin)).as(isin).isTrue();
    }
    // the independent computation agrees with the published examples
    for (String isin : PUBLISHED) {
      assertThat(luhnCheckDigit(isin.substring(0, 11))).isEqualTo(isin.charAt(11));
    }
  }

  @Test
  void withCheckDigitRefusesABaseThatIsNot11Characters() {
    assertThatThrownBy(() -> Isin.withCheckDigit("ZZ00000001"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Isin.withCheckDigit("ZZ0000000011"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Isin.withCheckDigit("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Isin.withCheckDigit("zz000000001"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Isin.withCheckDigit("00000000001"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Isin.withCheckDigit("ZZ00000000-"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Isin.withCheckDigit("ZZ00000000-")).hasMessageNotContaining("ZZ0");
    assertThatThrownBy(() -> Isin.withCheckDigit(null)).isInstanceOf(NullPointerException.class);
  }
}
