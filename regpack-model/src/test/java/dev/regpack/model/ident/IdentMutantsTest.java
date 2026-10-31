package dev.regpack.model.ident;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The teeth of the identifier checks (plan Convention 3, SF6): each one-line flaw of the algorithm,
 * switched on through the package-private seam, must make the published examples fail. The same
 * tests with {@link Flaw#NONE} pass, so the flaw and nothing else is what they catch.
 */
class IdentMutantsTest {
  @Test
  void theUnflawedSeamAcceptsThePublishedExamples() {
    for (String lei : LeiTest.PUBLISHED) {
      assertThat(Lei.isValid(lei, Flaw.NONE)).as(lei).isTrue();
    }
    for (String isin : IsinTest.PUBLISHED) {
      assertThat(Isin.isValid(isin, Flaw.NONE)).as(isin).isTrue();
    }
  }

  @Test
  void flippingTheLuhnDoublingMakesPublishedIsinsFail() {
    List<String> accepted =
        IsinTest.PUBLISHED.stream()
            .filter(isin -> Isin.isValid(isin, Flaw.LUHN_DOUBLING_FLIPPED))
            .toList();
    assertThat(accepted).isEmpty();
  }

  @Test
  void shiftingTheLetterExpansionMakesPublishedExamplesFail() {
    // every published ISIN and the LEIs that hold a letter: a one-off letter value is caught
    for (String isin : IsinTest.PUBLISHED) {
      assertThat(Isin.isValid(isin, Flaw.LETTER_EXPANSION_SHIFTED))
          .as(isin + " (the country letters are expanded)")
          .isFalse();
    }
    for (String lei : LeiTest.PUBLISHED) {
      assertThat(Lei.isValid(lei, Flaw.LETTER_EXPANSION_SHIFTED)).as(lei).isFalse();
    }
  }

  @Test
  void aWrongModulusMakesPublishedExamplesFail() {
    for (String lei : LeiTest.PUBLISHED) {
      assertThat(Lei.isValid(lei, Flaw.WRONG_MODULUS)).as(lei).isFalse();
    }
    for (String isin : IsinTest.PUBLISHED) {
      assertThat(Isin.isValid(isin, Flaw.WRONG_MODULUS)).as(isin).isFalse();
    }
  }

  @Test
  void aFlawedSeamAlsoComputesAnotherCheckDigitSoTheCheckDigitTestsAlsoCatchIt() {
    // the check digit builders share the flawed code: a flaw changes at least one of the
    // synthetic check digits, so the vectors of V3 would fail too
    assertThat(Lei.withCheckDigits("SYNT00FIRM00000001", Flaw.WRONG_MODULUS))
        .isNotEqualTo("SYNT00FIRM0000000152");
    assertThat(Lei.withCheckDigits("SYNT00FIRM00000001", Flaw.LETTER_EXPANSION_SHIFTED))
        .isNotEqualTo("SYNT00FIRM0000000152");
    assertThat(Isin.withCheckDigit("ZZ000000001", Flaw.LETTER_EXPANSION_SHIFTED))
        .isNotEqualTo("ZZ0000000016");
    assertThat(Isin.withCheckDigit("ZZ000000001", Flaw.LUHN_DOUBLING_FLIPPED))
        .isNotEqualTo("ZZ0000000016");
  }
}
