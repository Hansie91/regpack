package dev.regpack.evidence.sealed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** D-E5: the file-system name of an EvidenceKey segment (review focus EF9). */
class SealedNamesTest {
  private static final String ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._=,+-";
  private static final List<UnaryOperator<String>> FORMS =
      List.of(SealedNames::directory, SealedNames::object, SealedNames::meta);

  private static String randomSegment(SplittableRandom random) {
    int length = 1 + random.nextInt(SealedNames.MAX_SEGMENT);
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return sb.toString();
  }

  private static String flipSomeCase(String segment, SplittableRandom random) {
    StringBuilder sb = new StringBuilder(segment);
    for (int i = 0; i < sb.length(); i++) {
      char c = sb.charAt(i);
      if (random.nextBoolean()) {
        if (c >= 'a' && c <= 'z') {
          sb.setCharAt(i, (char) (c - 'a' + 'A'));
        } else if (c >= 'A' && c <= 'Z') {
          sb.setCharAt(i, (char) (c - 'A' + 'a'));
        }
      }
    }
    return sb.toString();
  }

  private static boolean isWindowsReserved(String name) {
    String base = name;
    int dot = base.indexOf('.');
    if (dot >= 0) {
      base = base.substring(0, dot);
    }
    String folded = base.stripTrailing().toLowerCase(Locale.ROOT);
    if (Set.of("con", "prn", "aux", "nul").contains(folded)) {
      return true;
    }
    return folded.length() == 4
        && (folded.startsWith("com") || folded.startsWith("lpt"))
        && folded.charAt(3) >= '1'
        && folded.charAt(3) <= '9';
  }

  @Test
  void knownVectors() {
    assertThat(SealedNames.directory("A.b")).isEqualTo("d-^a.b");
    assertThat(SealedNames.object("abc.")).isEqualTo("o-abc^0");
    assertThat(SealedNames.object("con")).isEqualTo("o-con");
    assertThat(SealedNames.meta("K1")).isEqualTo("m-^k1");
    assertThat(SealedNames.object("Landing")).isEqualTo("o-^landing");
    assertThat(SealedNames.object("a=b,c+d_e-f.g")).isEqualTo("o-a=b,c+d_e-f.g");
    assertThat(SealedNames.object("a..")).isEqualTo("o-a.^0");
  }

  @Test
  void decodeIsTheInverseOfEncodeForTwoThousandRandomSegments() {
    SplittableRandom random = new SplittableRandom(20261019L);
    for (int i = 0; i < 2000; i++) {
      String segment = randomSegment(random);
      assertThat(SealedNames.decode(SealedNames.directory(segment))).isEqualTo(segment);
      assertThat(SealedNames.decode(SealedNames.object(segment))).isEqualTo(segment);
      assertThat(SealedNames.decode(SealedNames.meta(segment))).isEqualTo(segment);
    }
    for (String edge : List.of("a", "A", ".", "..", "a.", "A.", "Z.", "0", "a.b.")) {
      assertThat(SealedNames.decode(SealedNames.object(edge))).isEqualTo(edge);
    }
  }

  @Test
  void noTwoDifferentSegmentsCollideWhenFoldedToLowerCase() {
    SplittableRandom random = new SplittableRandom(7L);
    Map<String, String> byFoldedName = new HashMap<>();
    for (UnaryOperator<String> form : FORMS) {
      byFoldedName.clear();
      for (int i = 0; i < 20_000; i++) {
        String a = randomSegment(random);
        String b = i % 2 == 0 ? flipSomeCase(a, random) : randomSegment(random);
        for (String segment : List.of(a, b)) {
          String folded = form.apply(segment).toLowerCase(Locale.ROOT);
          String earlier = byFoldedName.putIfAbsent(folded, segment);
          assertThat(earlier == null || earlier.equals(segment))
              .as("two different segments fold to one name")
              .isTrue();
        }
      }
      // every pair of the 3-character segments over {a, A, b}
      byFoldedName.clear();
      List<String> small = new ArrayList<>();
      String letters = "aAb";
      for (char x : letters.toCharArray()) {
        for (char y : letters.toCharArray()) {
          for (char z : letters.toCharArray()) {
            small.add("" + x + y + z);
          }
        }
      }
      assertThat(small).hasSize(27);
      for (String segment : small) {
        String folded = form.apply(segment).toLowerCase(Locale.ROOT);
        assertThat(byFoldedName.put(folded, segment))
            .as("the segment %s folds to a name of another segment", segment)
            .isNull();
      }
    }
  }

  @Test
  void encodedNamesNeverEndInADotOrASpaceNorAreAWindowsReservedName() {
    List<String> segments = new ArrayList<>();
    List<String> reserved = new ArrayList<>(List.of("con", "prn", "aux", "nul"));
    for (int i = 1; i <= 9; i++) {
      reserved.add("com" + i);
      reserved.add("lpt" + i);
    }
    for (String base : reserved) {
      for (String variant :
          List.of(base, base.toUpperCase(Locale.ROOT), base + ".txt", base + ".", base + ".a.b")) {
        segments.add(variant);
        segments.add(flipSomeCase(variant, new SplittableRandom(variant.hashCode())));
      }
    }
    segments.addAll(List.of(".", "..", "a.", "a..", "A.", "...", "a.b.", "-", "+", "=", ","));
    SplittableRandom random = new SplittableRandom(99L);
    for (int i = 0; i < 2000; i++) {
      segments.add(randomSegment(random) + ".".repeat(1 + random.nextInt(3)));
    }
    for (String segment : segments) {
      if (segment.length() > SealedNames.MAX_SEGMENT) {
        continue;
      }
      for (UnaryOperator<String> form : FORMS) {
        String name = form.apply(segment);
        assertThat(name).doesNotEndWith(".").doesNotEndWith(" ");
        assertThat(isWindowsReserved(name)).as("reserved: %s", name).isFalse();
        assertThat(name).matches("[a-z0-9._=,+^-]+");
        assertThat(name.length()).isLessThanOrEqualTo(255);
      }
    }
  }

  @Test
  void theLongestSegmentFitsTheFileNameLimit() {
    String capitals = "Q".repeat(SealedNames.MAX_SEGMENT);
    assertThat(SealedNames.object(capitals)).hasSize(202);
    assertThat(SealedNames.directory(capitals)).hasSize(202);
    assertThat(SealedNames.meta(capitals)).hasSize(202);
    String capitalsAndDot = "Q".repeat(SealedNames.MAX_SEGMENT - 1) + ".";
    assertThat(SealedNames.object(capitalsAndDot)).hasSize(2 + 2 * 99 + 2);
    String tooLong = "a".repeat(SealedNames.MAX_SEGMENT + 1);
    for (UnaryOperator<String> form : FORMS) {
      assertThatIllegalArgumentException().isThrownBy(() -> form.apply(tooLong));
    }
  }

  @Test
  void aSegmentOutsideTheEvidenceKeyAlphabetIsRefused() {
    for (String bad : List.of("", "a/b", "a b", "a~b", "a^b", "é", "a\u0000", "a*", "a\\b")) {
      for (UnaryOperator<String> form : FORMS) {
        assertThatIllegalArgumentException().isThrownBy(() -> form.apply(bad));
      }
    }
  }

  @Test
  void temporaryNamesAreRecognisedAndNeverDecode() {
    String temp = SealedNames.temporary("o-abc", 12L, 3L);
    assertThat(temp).isEqualTo("o-abc~tmp~12~3");
    assertThat(SealedNames.isTemporary(temp)).isTrue();
    assertThat(SealedNames.isTemporary("o-abc")).isFalse();
    assertThat(SealedNames.isTemporary(".selftest~tmp~7~1")).isTrue();
    assertThatIllegalArgumentException().isThrownBy(() -> SealedNames.decode(temp));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SealedNames.decode(SealedNames.temporary("m-^k1", 1L, 1L)));
    // no encoded name contains the marker
    SplittableRandom random = new SplittableRandom(5L);
    for (int i = 0; i < 500; i++) {
      assertThat(SealedNames.isTemporary(SealedNames.object(randomSegment(random)))).isFalse();
    }
  }

  @Test
  void decodeRefusesEveryNameThatEncodeCannotProduce() {
    for (String bad :
        List.of(
            "",
            "o-",
            "x-abc",
            "abc",
            "o-A",
            "o-^",
            "o-^1",
            "o-^A",
            "o-^^a",
            "o-a^0b",
            "o-a.",
            "o-a b",
            "O-abc",
            "o-abc/def",
            "d-" + "a".repeat(SealedNames.MAX_SEGMENT + 1))) {
      assertThatIllegalArgumentException()
          .as("name %s", bad)
          .isThrownBy(() -> SealedNames.decode(bad));
    }
  }
}
