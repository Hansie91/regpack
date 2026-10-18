package dev.regpack.pack.yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.pack.yaml.StrictYaml.Limits;
import org.junit.jupiter.api.Test;

/**
 * Review focus PF1, "echoes untrusted text without bound": a syntax error carries a bounded,
 * escaped excerpt, never raw control or bidirectional characters.
 */
class PackSyntaxExceptionTest {
  /** The text that a person or a log sees: only printable ASCII. */
  private static void assertPlain(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      assertThat(c >= 0x20 && c <= 0x7E)
          .as("character %d (U+%04X) of the message is printable ASCII", i, (int) c)
          .isTrue();
    }
  }

  @Test
  void messagesAreBoundedAndEscaped() {
    // a 10,000-character line made of control, bidirectional and default-ignorable characters
    String hostile = "\u202e\u0007\u200b\u2066\u0000\u0085\u2028x".repeat(1_250);
    assertThat(hostile).hasSize(10_000);

    PackSyntaxException direct = new PackSyntaxException("YAML_SYNTAX", 7, hostile);
    assertThat(direct.getMessage()).hasSizeLessThanOrEqualTo(160);
    assertThat(direct.excerpt()).hasSizeLessThanOrEqualTo(24);
    assertPlain(direct.getMessage());
    assertThat(direct.getMessage()).startsWith("line 7: YAML_SYNTAX: \\u202e");

    // the same line inside a document: the character pass names the first offending character
    String document = "a: 1\nb: " + hostile + "\n";
    assertThatThrownBy(() -> StrictYaml.parse(document))
        .isInstanceOfSatisfying(
            PackSyntaxException.class,
            e -> {
              assertThat(e.code()).isEqualTo("YAML_BAD_CHARACTER");
              assertThat(e.line()).isEqualTo(2);
              assertThat(e.getMessage()).hasSizeLessThanOrEqualTo(160);
              assertThat(e.excerpt()).hasSizeLessThanOrEqualTo(24);
              assertPlain(e.getMessage());
            });

    // a long clean line over the limit and a long unterminated quote
    String longLine = "a: " + "y".repeat(10_000) + "\n";
    assertThatThrownBy(() -> StrictYaml.parse(longLine))
        .isInstanceOfSatisfying(
            PackSyntaxException.class,
            e -> {
              assertThat(e.code()).isEqualTo("YAML_TOO_LARGE");
              assertThat(e.getMessage()).hasSizeLessThanOrEqualTo(160);
              assertThat(e.excerpt()).hasSizeLessThanOrEqualTo(24);
            });
    Limits wide = new Limits(1 << 20, 100_000, 100_000, 128, 32, 100_000);
    String unterminated = "a: \"" + "z".repeat(9_000) + "\n";
    assertThatThrownBy(() -> StrictYaml.parse(unterminated, wide))
        .isInstanceOfSatisfying(
            PackSyntaxException.class,
            e -> {
              assertThat(e.code()).isEqualTo("YAML_UNTERMINATED");
              assertThat(e.getMessage()).hasSizeLessThanOrEqualTo(160);
              assertThat(e.excerpt()).hasSizeLessThanOrEqualTo(24);
              assertThat(e.excerpt()).isEqualTo("\"" + "z".repeat(23));
            });
    // a long bad key
    String longKey = "k".repeat(200) + ": v\n";
    assertThatThrownBy(() -> StrictYaml.parse(longKey))
        .isInstanceOfSatisfying(
            PackSyntaxException.class, e -> assertThat(e.excerpt()).hasSizeLessThanOrEqualTo(24));
    // text with spaces in a key is echoed, bounded
    assertThatThrownBy(() -> StrictYaml.parse("this is not a key and it is long: v\n"))
        .isInstanceOfSatisfying(
            PackSyntaxException.class,
            e -> {
              assertThat(e.code()).isEqualTo("YAML_BAD_KEY");
              assertThat(e.excerpt()).isEqualTo("this is not a key and it");
            });
  }

  @Test
  void theExcerptIsCutByCodePointNotInTheMiddleOfAnEscape() {
    // supplementary characters are escaped as one unit
    String emoji = "\ud83d\ude00".repeat(30);
    PackSyntaxException e = new PackSyntaxException("YAML_SYNTAX", 1, emoji);
    assertThat(e.excerpt()).hasSizeLessThanOrEqualTo(24);
    assertPlain(e.getMessage());
    assertThat(e.excerpt()).isEqualTo("\\u{1f600}\\u{1f600}");
    // a lone surrogate is one unit too
    assertThat(new PackSyntaxException("YAML_SYNTAX", 1, "\ud800x").excerpt())
        .isEqualTo("\\ud800x");
    // a BMP character costs six characters
    assertThat(new PackSyntaxException("YAML_SYNTAX", 1, "\u00e9".repeat(10)).excerpt())
        .isEqualTo("\\u00e9".repeat(4));
  }

  @Test
  void anExcerptOfTheBoundIsKeptWholeAndALongerOneIsCut() {
    String twentyFour = "abcdefghijklmnopqrstuvwx";
    assertThat(twentyFour).hasSize(24);
    assertThat(new PackSyntaxException("YAML_SYNTAX", 1, twentyFour).excerpt())
        .isEqualTo(twentyFour);
    assertThat(new PackSyntaxException("YAML_SYNTAX", 1, twentyFour + "y").excerpt())
        .isEqualTo(twentyFour);
    assertThat(new PackSyntaxException("YAML_SYNTAX", 1, "").excerpt()).isEmpty();
    assertThat(new PackSyntaxException("YAML_SYNTAX", 1, "a b").getMessage())
        .isEqualTo("line 1: YAML_SYNTAX: a b");
  }

  @Test
  void theLargestLineNumberStillFitsTheBound() {
    PackSyntaxException e =
        new PackSyntaxException("YAML_UNTERMINATED", Integer.MAX_VALUE, "\u202e".repeat(100));
    assertThat(e.getMessage()).hasSizeLessThanOrEqualTo(160);
    assertThat(e.line()).isEqualTo(Integer.MAX_VALUE);
    assertThat(e.code()).isEqualTo("YAML_UNTERMINATED");
  }

  @Test
  void aBackslashInTheExcerptIsKeptAsText() {
    assertThat(new PackSyntaxException("YAML_BAD_ESCAPE", 1, "\"\\x41\"").excerpt())
        .isEqualTo("\"\\x41\"");
  }
}
