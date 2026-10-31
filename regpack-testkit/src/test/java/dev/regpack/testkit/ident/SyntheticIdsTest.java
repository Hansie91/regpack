package dev.regpack.testkit.ident;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.ident.Isin;
import dev.regpack.model.ident.Lei;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyntheticIdsTest {
  @Test
  void everyConstantValidatesAndBadIsinDoesNot() {
    List<String> leis =
        List.of(
            SyntheticIds.FIRM_LEI,
            SyntheticIds.CCP_LEI,
            SyntheticIds.CLIENT1_LEI,
            SyntheticIds.CLIENT2_LEI,
            SyntheticIds.LAPSED_LEI);
    List<String> isins =
        List.of(
            SyntheticIds.ISIN_1,
            SyntheticIds.ISIN_2,
            SyntheticIds.ISIN_3,
            SyntheticIds.ISIN_4,
            SyntheticIds.ISIN_5,
            SyntheticIds.ISIN_6);
    assertThat(leis)
        .containsExactly(
            "SYNT00FIRM0000000152",
            "SYNT00CCP00000000194",
            "SYNT00CLIENT00000167",
            "SYNT00CLIENT00000264",
            "SYNT00LAPSED00000132");
    assertThat(isins)
        .containsExactly(
            "ZZ0000000016",
            "ZZ0000000024",
            "ZZ0000000032",
            "ZZ0000000040",
            "ZZ0000000057",
            "ZZ0000000065");
    assertThat(SyntheticIds.BAD_ISIN).isEqualTo("ZZ0000000017");
    for (String lei : leis) {
      assertThat(Lei.isValid(lei)).as(lei).isTrue();
    }
    for (String isin : isins) {
      assertThat(Isin.isValid(isin)).as(isin).isTrue();
    }
    assertThat(Isin.hasShape(SyntheticIds.BAD_ISIN)).isTrue();
    assertThat(Isin.isValid(SyntheticIds.BAD_ISIN)).isFalse();
    assertThat(leis).doesNotHaveDuplicates();
    assertThat(isins).doesNotHaveDuplicates();
  }

  @Test
  void theCollectionsHoldExactlyTheConstants() {
    assertThat(SyntheticIds.LEIS)
        .containsExactly(
            SyntheticIds.FIRM_LEI,
            SyntheticIds.CCP_LEI,
            SyntheticIds.CLIENT1_LEI,
            SyntheticIds.CLIENT2_LEI,
            SyntheticIds.LAPSED_LEI);
    assertThat(SyntheticIds.ISINS)
        .containsExactly(
            SyntheticIds.ISIN_1,
            SyntheticIds.ISIN_2,
            SyntheticIds.ISIN_3,
            SyntheticIds.ISIN_4,
            SyntheticIds.ISIN_5,
            SyntheticIds.ISIN_6);
  }

  @Test
  void theConstantsAreBuiltFromTheirBases() {
    assertThat(SyntheticIds.FIRM_LEI).isEqualTo(Lei.withCheckDigits("SYNT00FIRM00000001"));
    assertThat(SyntheticIds.ISIN_1).isEqualTo(Isin.withCheckDigit("ZZ000000001"));
  }
}
