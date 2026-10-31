package dev.regpack.model.ident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CurrencyTest {
  @Test
  void theThreeDemoNonCurrencyCodes() {
    assertThat(Currency.NON_CURRENCY).containsExactly("XAU", "XDR", "XXX");
    assertThat(Currency.NON_CURRENCY).hasSize(3);
    for (String code : Currency.NON_CURRENCY) {
      assertThat(Currency.hasShape(code)).isTrue();
    }
    assertThat(Currency.hasShape("EUR")).isTrue();
    assertThat(Currency.NON_CURRENCY).doesNotContain("EUR");
    assertThatThrownBy(() -> Currency.NON_CURRENCY.add("EUR"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void shapeIsThreeUpperCaseAsciiLetters() {
    assertThat(Currency.hasShape("USD")).isTrue();
    assertThat(Currency.hasShape("usd")).isFalse();
    assertThat(Currency.hasShape("US")).isFalse();
    assertThat(Currency.hasShape("USDD")).isFalse();
    assertThat(Currency.hasShape("U5D")).isFalse();
    assertThat(Currency.hasShape("")).isFalse();
    assertThat(Currency.hasShape(null)).isFalse();
    assertThat(Currency.hasShape("ＵSD")).isFalse();
    assertThat(Currency.hasShape("US ")).isFalse();
  }
}
