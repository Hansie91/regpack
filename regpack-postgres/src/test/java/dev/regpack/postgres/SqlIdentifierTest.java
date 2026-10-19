package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SqlIdentifierTest {
  @Test
  void acceptsLowercaseIdentifiersAndRejectsEverythingElse() {
    assertThat(SqlIdentifier.require("a")).isEqualTo("a");
    assertThat(SqlIdentifier.require("decision_p20261009")).isEqualTo("decision_p20261009");
    assertThat(SqlIdentifier.require("t123_4")).isEqualTo("t123_4");
    String longest = "a" + "b".repeat(62);
    assertThat(longest).hasSize(63);
    assertThat(SqlIdentifier.require(longest)).isEqualTo(longest);

    String tooLong = "a" + "b".repeat(63);
    assertThat(tooLong).hasSize(64);
    for (String bad :
        new String[] {
          "",
          " ",
          "A",
          "aB",
          "1a",
          "_a",
          "a-b",
          "a b",
          "a.b",
          "a;drop table x",
          "a\"b",
          "a'b",
          "a\n",
          "\na",
          "aé",
          "ı",
          "İ",
          "a\u0000",
          tooLong
        }) {
      assertThatThrownBy(() -> SqlIdentifier.require(bad))
          .as("identifier %s", bad.length())
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void aNullIdentifierIsRejectedLikeAnyOtherBadOne() {
    assertThatThrownBy(() -> SqlIdentifier.require(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theMessageNeverEchoesTheRejectedText() {
    assertThatThrownBy(() -> SqlIdentifier.require("x;select secret"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("secret");
  }
}
