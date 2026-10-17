package dev.regpack.pack.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan Convention 6: problems are tokens with bounded, escaped text. */
class PackProblemTest {
  private static boolean printableAscii(String text) {
    return text.chars().allMatch(c -> c >= 0x20 && c <= 0x7E);
  }

  @Test
  void messageAndPathAreBoundedAndEscapedWhateverTheCaller() {
    String hostile = "a‮b\n\t\u0000​" + "x".repeat(5_000) + "😀";
    PackProblem p = new PackProblem("CHECKSUM_MISMATCH", hostile, 7, hostile);

    assertThat(p.path().length()).isLessThanOrEqualTo(128);
    assertThat(p.message().length()).isLessThanOrEqualTo(160);
    assertThat(printableAscii(p.path())).isTrue();
    assertThat(printableAscii(p.message())).isTrue();
    assertThat(p.message()).startsWith("a\\u202eb\\u000a\\u0009\\u0000\\u200b");
    assertThat(p.line()).isEqualTo(7);

    // a lone surrogate and a supplementary character are escaped per UTF-16 unit
    PackProblem q = new PackProblem("ROW_ID_DUPLICATE", "p", 1, "😀\udc00");
    assertThat(q.message()).isEqualTo("\\ud83d\\ude00\\udc00");

    // an escape is never cut in the middle
    PackProblem r = new PackProblem("ROW_ID_DUPLICATE", "p", 1, "x".repeat(158) + "é");
    assertThat(r.message()).isEqualTo("x".repeat(158));
    PackProblem s = new PackProblem("ROW_ID_DUPLICATE", "p", 1, "x".repeat(154) + "é");
    assertThat(s.message()).isEqualTo("x".repeat(154) + "\\u00e9");
  }

  @Test
  void excerptIsEscapedAndCutToTwentyFourCharacters() {
    assertThat(PackProblem.excerpt("short")).isEqualTo("short");
    assertThat(PackProblem.excerpt("y".repeat(100))).isEqualTo("y".repeat(24));
    String hostile = "‮\u0007" + "z".repeat(50);
    String shown = PackProblem.excerpt(hostile);
    assertThat(shown).hasSizeLessThanOrEqualTo(24);
    assertThat(printableAscii(shown)).isTrue();
    assertThat(shown).startsWith("\\u202e\\u0007");
  }

  @Test
  void theCodeMustBeAnUpperCaseToken() {
    for (String bad :
        List.of("", "ab", "lower_case", "1ABC", "A B C", "A-BC", "ÄBC", "A".repeat(49))) {
      assertThatThrownBy(() -> new PackProblem(bad, "p", 1, "m"))
          .as(bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(new PackProblem("ABC", "p", 1, "m").code()).isEqualTo("ABC");
    assertThat(new PackProblem("A" + "B".repeat(47), "p", 1, "m").code()).hasSize(48);
  }

  @Test
  void nullsAreRefusedAndANegativeLineIsZero() {
    assertThatThrownBy(() -> new PackProblem(null, "p", 1, "m"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new PackProblem("ABC", null, 1, "m"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new PackProblem("ABC", "p", 1, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> PackProblem.excerpt(null)).isInstanceOf(NullPointerException.class);
    assertThat(new PackProblem("ABC", "p", -5, "m").line()).isZero();
  }

  @Test
  void problemsSortByPathThenLineThenCode() {
    PackProblem a = new PackProblem("ZZZ", "a.yaml", 9, "m");
    PackProblem b = new PackProblem("AAA", "b.yaml", 1, "m");
    PackProblem c = new PackProblem("BBB", "b.yaml", 2, "m");
    PackProblem d = new PackProblem("AAA", "b.yaml", 2, "m");
    List<PackProblem> list = new ArrayList<>(List.of(d, c, b, a));
    Collections.sort(list);
    assertThat(list).containsExactly(a, b, d, c);
  }
}
