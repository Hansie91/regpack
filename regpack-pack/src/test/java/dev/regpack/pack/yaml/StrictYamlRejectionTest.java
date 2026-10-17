package dev.regpack.pack.yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * PackYaml/1, refusing side (review focus PF1; companion contracts section 5.2 and plan task T5):
 * every construct outside the subset is a {@link PackSyntaxException} with its code and its line.
 */
class StrictYamlRejectionTest {
  private static void assertCode(String text, String code, int line) {
    assertThatThrownBy(() -> StrictYaml.parse(text))
        .as(printable(text))
        .isInstanceOfSatisfying(
            PackSyntaxException.class,
            e -> {
              assertThat(e.code()).as(printable(text)).isEqualTo(code);
              assertThat(e.line()).as(printable(text)).isEqualTo(line);
            });
  }

  private static String printable(String text) {
    StringBuilder out = new StringBuilder();
    text.codePoints()
        .forEach(
            cp -> {
              if (cp >= 0x20 && cp <= 0x7E) {
                out.append((char) cp);
              } else if (cp == '\n') {
                out.append("\\n");
              } else {
                out.append("<U+").append(Integer.toHexString(cp)).append('>');
              }
            });
    return out.toString();
  }

  private static Arguments c(String text, String code, int line) {
    return Arguments.of(text, code, line);
  }

  // ---------------------------------------------------------------------------------------------

  static Stream<Arguments> tabsAnywhere() {
    return Stream.of(
        c("a:\n\tb: 1\n", "YAML_TAB", 2), // in the indentation
        c("a: x\ty\n", "YAML_TAB", 1), // in a plain scalar
        c("a: \"x\ty\"\n", "YAML_TAB", 1), // in a quoted scalar
        c("a:\t1\n", "YAML_TAB", 1), // after the colon
        c("a: 1\n\n# c\tx\nb: 2\n", "YAML_TAB", 3), // in a comment
        c("a: [x,\ty]\n", "YAML_TAB", 1), // in a flow collection
        c("\t\n", "YAML_TAB", 1), // on a line of its own
        c("a: 1\r\n\tb: 2\r\n", "YAML_TAB", 2));
  }

  @ParameterizedTest(name = "[{index}] {2}")
  @MethodSource("tabsAnywhere")
  void tabsAnywhere(String text, String code, int line) {
    assertCode(text, code, line);
  }

  static Stream<Arguments> badCharacters() {
    int[] codePoints = {
      0xFEFF, // BOM / zero width no-break space
      0x0085, // NEL
      0x2028, // line separator
      0x2029, // paragraph separator
      0x202E, // right-to-left override
      0x200B, // zero width space
      0x200D, // zero width joiner
      0x2060, // word joiner
      0xD800, // lone high surrogate
      0xDC00, // lone low surrogate
      0x0007, // BEL
      0x007F, // DEL
      0x0000, // NUL
      0x001B, // ESC
      0x0080, // first C1 control
      0x009F, // last C1 control
      0xE0041, // tag character
      0xE0100, // variation selector supplement
      0xFE0F, // variation selector 16
      0xFE00, // variation selector 1
      0x00AD, // soft hyphen
      0x034F, // combining grapheme joiner
      0x115F, // hangul choseong filler
      0x1160, // hangul jungseong filler
      0x17B4, // khmer vowel inherent aq
      0x180B, // mongolian free variation selector one
      0x180F, // mongolian free variation selector four
      0x3164, // hangul filler
      0xFFA0, // halfwidth hangul filler
      0x206A, // inhibit symmetric swapping
      0xFFF9, // interlinear annotation anchor
      0xE000, // private use (BMP)
      0xF0000, // private use (plane 15)
      0xFDD0, // noncharacter
      0xFDEF, // noncharacter
      0xFFFE, // noncharacter
      0xFFFF, // noncharacter
      0x1FFFE, // noncharacter (plane 1)
      0x10FFFF, // noncharacter (last code point)
    };
    Stream.Builder<Arguments> out = Stream.builder();
    for (int cp : codePoints) {
      String ch = new String(Character.toChars(cp));
      out.add(c("a: x" + ch + "y\n", "YAML_BAD_CHARACTER", 1)); // in a plain scalar
      out.add(c("a: 1\nb: 2\nc: \"q" + ch + "\"\n", "YAML_BAD_CHARACTER", 3)); // in a quoted one
      out.add(c("a: 1\n# note" + ch + "\nb: 2\n", "YAML_BAD_CHARACTER", 2)); // in a comment
      out.add(c("k" + ch + ": 1\n", "YAML_BAD_CHARACTER", 1)); // in a key
      out.add(c(ch, "YAML_BAD_CHARACTER", 1)); // alone
    }
    // a CR that is not part of a CRLF
    out.add(c("a: x\ry\n", "YAML_BAD_CHARACTER", 1));
    out.add(c("a: 1\nb: x\r", "YAML_BAD_CHARACTER", 2));
    out.add(c("a: 1\r\r\nb: 2\n", "YAML_BAD_CHARACTER", 1));
    out.add(c("\r", "YAML_BAD_CHARACTER", 1));
    return out.build();
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("badCharacters")
  void badCharacters(String text, String code, int line) {
    assertCode(text, code, line);
  }

  @Test
  void ordinaryUnicodeAndCrlfAreAccepted() {
    String text =
        "name: \"caf\u00e9 \u65e5\u672c\u8a9e \ud83d\ude00 \u00df\"\r\nnote: \u0627\u0644\u0639\u0631\u0628\u064a\u0629 \u03a9\r\n";
    YamlMap parsed = (YamlMap) StrictYaml.parse(text);
    assertThat(parsed.entries().get("name"))
        .isEqualTo(new YamlScalar("caf\u00e9 \u65e5\u672c\u8a9e \ud83d\ude00 \u00df", 1));
    assertThat(parsed.entries().get("note"))
        .isEqualTo(new YamlScalar("\u0627\u0644\u0639\u0631\u0628\u064a\u0629 \u03a9", 2));
  }

  // ---------------------------------------------------------------------------------------------

  static Stream<Arguments> forbiddenConstructs() {
    return Stream.of(
        // anchors
        c("&a x\n", "YAML_ANCHOR", 1),
        c("a: &a x\n", "YAML_ANCHOR", 1),
        c("a: 1\nb: &a\n", "YAML_ANCHOR", 2),
        c("- &a x\n", "YAML_ANCHOR", 1),
        c("a: [&a x]\n", "YAML_ANCHOR", 1),
        c("&a k: 1\n", "YAML_ANCHOR", 1),
        // aliases
        c("*a\n", "YAML_ALIAS", 1),
        c("a: *a\n", "YAML_ALIAS", 1),
        c("- *a\n", "YAML_ALIAS", 1),
        c("a: [*a]\n", "YAML_ALIAS", 1),
        c("a: {b: *c}\n", "YAML_ALIAS", 1),
        // tags
        c("!!str x\n", "YAML_TAG", 1),
        c("a: !!str x\n", "YAML_TAG", 1),
        c("!x\n", "YAML_TAG", 1),
        c("a: !x\n", "YAML_TAG", 1),
        c("a: !<tag:yaml.org,2002:str> x\n", "YAML_TAG", 1),
        c("!<t> x\n", "YAML_TAG", 1),
        c("- !!int 3\n", "YAML_TAG", 1),
        c("a: [!x y]\n", "YAML_TAG", 1),
        // merge keys
        c("<<: {a: b}\n", "YAML_MERGE_KEY", 1),
        c("a: 1\n<<: {b: c}\n", "YAML_MERGE_KEY", 2),
        c("- <<: x\n", "YAML_MERGE_KEY", 1),
        c("a: {<<: x}\n", "YAML_MERGE_KEY", 1),
        c("a: {b: c, <<: x}\n", "YAML_MERGE_KEY", 1),
        // directives
        c("%YAML 1.2\n", "YAML_DIRECTIVE", 1),
        c("%TAG ! tag:example.com,2000:\n", "YAML_DIRECTIVE", 1),
        c("a: 1\n%YAML 1.2\n", "YAML_DIRECTIVE", 2),
        c("a: %x\n", "YAML_DIRECTIVE", 1),
        // document markers
        c("---\n", "YAML_DOCUMENT_MARKER", 1),
        c("--- x\n", "YAML_DOCUMENT_MARKER", 1),
        c("...\n", "YAML_DOCUMENT_MARKER", 1),
        c("a: 1\n---\nb: 2\n", "YAML_DOCUMENT_MARKER", 2),
        c("a: 1\n...\n", "YAML_DOCUMENT_MARKER", 2),
        c("# c\n---   \n", "YAML_DOCUMENT_MARKER", 2),
        // block scalars
        c("a: |\n  x\n", "YAML_BLOCK_SCALAR", 1),
        c("a: >\n  x\n", "YAML_BLOCK_SCALAR", 1),
        c("a: |-\n  x\n", "YAML_BLOCK_SCALAR", 1),
        c("a: >2\n  x\n", "YAML_BLOCK_SCALAR", 1),
        c("a: 1\nb: |  # c\n  x\n", "YAML_BLOCK_SCALAR", 2),
        c("- |\n  x\n", "YAML_BLOCK_SCALAR", 1),
        c("a: [|x]\n", "YAML_BLOCK_SCALAR", 1),
        // complex keys
        c("? a\n", "YAML_COMPLEX_KEY", 1),
        c("? a\n: b\n", "YAML_COMPLEX_KEY", 1),
        c("a: 1\n? b\n", "YAML_COMPLEX_KEY", 2),
        c("- ? a\n", "YAML_COMPLEX_KEY", 1),
        c("?\n", "YAML_COMPLEX_KEY", 1),
        c("a: {? b: c}\n", "YAML_COMPLEX_KEY", 1),
        // a sequence directly in a sequence
        c("- - x\n", "YAML_NESTED_SEQUENCE", 1),
        c("- a\n- - b\n", "YAML_NESTED_SEQUENCE", 2),
        c("-\n  - x\n", "YAML_NESTED_SEQUENCE", 2),
        c("a: - x\n", "YAML_NESTED_SEQUENCE", 1),
        c("- -\n", "YAML_NESTED_SEQUENCE", 1),
        // a mapping indicator inside a plain scalar
        c("a: b: c\n", "YAML_SYNTAX", 1),
        c("a: b:\n", "YAML_SYNTAX", 1),
        c("a:\n  - x: y: z\n", "YAML_SYNTAX", 2),
        c("- a: b: c\n", "YAML_SYNTAX", 1));
  }

  @ParameterizedTest(name = "[{index}] {1} <- {0}")
  @MethodSource("forbiddenConstructs")
  void forbiddenConstructs(String text, String code, int line) {
    assertCode(text, code, line);
  }

  @Test
  void aQuotedIndicatorIsOnlyText() {
    YamlMap parsed =
        (YamlMap)
            StrictYaml.parse(
                "a: '&x'\nb: \"*y\"\nc: '!z'\nd: '|'\ne: \"%w\"\nf: '? q'\ng: '<<'\nh: '- x'\n"
                    + "i: x&y\nj: x*y\nk: x!y\nl: a|b\nm: 50%\nn: a>b\no: a?b\np: -x\n");
    assertThat(((YamlScalar) parsed.entries().get("a")).value()).isEqualTo("&x");
    assertThat(((YamlScalar) parsed.entries().get("b")).value()).isEqualTo("*y");
    assertThat(((YamlScalar) parsed.entries().get("g")).value()).isEqualTo("<<");
    assertThat(((YamlScalar) parsed.entries().get("h")).value()).isEqualTo("- x");
    assertThat(((YamlScalar) parsed.entries().get("m")).value()).isEqualTo("50%");
    assertThat(((YamlScalar) parsed.entries().get("p")).value()).isEqualTo("-x");
  }

  // ---------------------------------------------------------------------------------------------

  static Stream<Arguments> duplicateKeys() {
    return Stream.of(
        c("a: 1\nb: 2\na: 3\n", "YAML_DUPLICATE_KEY", 3),
        c("a: {b: 1, b: 2}\n", "YAML_DUPLICATE_KEY", 1),
        c("a:\n  b: 1\n  b: 2\n", "YAML_DUPLICATE_KEY", 3),
        c("a: [{k: 1, k: 2}]\n", "YAML_DUPLICATE_KEY", 1),
        c("a: {x: {k: 1, k: 2}}\n", "YAML_DUPLICATE_KEY", 1),
        c("- k: 1\n  k: 2\n", "YAML_DUPLICATE_KEY", 2),
        c("\"a\": 1\na: 2\n", "YAML_DUPLICATE_KEY", 2),
        c("a: 1\n'a': 2\n", "YAML_DUPLICATE_KEY", 2),
        c("rows:\n  - id: R1\n    id: R2\n", "YAML_DUPLICATE_KEY", 3),
        c("a: {\"k\": 1, k: 2}\n", "YAML_DUPLICATE_KEY", 1));
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("duplicateKeys")
  void duplicateKeysInBlockFlowAndNestedMaps(String text, String code, int line) {
    assertCode(text, code, line);
  }

  @Test
  void keysDifferingOnlyInCaseAreAllowed() {
    YamlMap parsed = (YamlMap) StrictYaml.parse("a: 1\nA: 2\nb: {k: 1, K: 2}\n");
    assertThat(parsed.entries()).containsOnlyKeys("a", "A", "b");
    assertThat(((YamlMap) parsed.entries().get("b")).entries()).containsOnlyKeys("k", "K");
  }

  static Stream<Arguments> emptyValues() {
    return Stream.of(
        c("a:\n", "YAML_EMPTY_VALUE", 1),
        c("a:\nb: 1\n", "YAML_EMPTY_VALUE", 1),
        c("a: # c\n", "YAML_EMPTY_VALUE", 1),
        c("a: {b:}\n", "YAML_EMPTY_VALUE", 1),
        c("a: {b: , c: d}\n", "YAML_EMPTY_VALUE", 1),
        c("-\n", "YAML_EMPTY_VALUE", 1),
        c("- a:\n- b\n", "YAML_EMPTY_VALUE", 1),
        c("x:\n  y:\n", "YAML_EMPTY_VALUE", 2),
        c("x:\n  y:\nz: 1\n", "YAML_EMPTY_VALUE", 2));
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("emptyValues")
  void emptyValueIsRejected(String text, String code, int line) {
    assertCode(text, code, line);
  }

  static Stream<Arguments> multiLineScalars() {
    return Stream.of(
        c("a: foo\n  bar\n", "YAML_MULTILINE_SCALAR", 2),
        c("a: \"foo\"\n  bar\n", "YAML_MULTILINE_SCALAR", 2),
        c("a: 'x'\n  'y'\n", "YAML_MULTILINE_SCALAR", 2),
        c("- foo\n  bar\n", "YAML_MULTILINE_SCALAR", 2),
        c("a:\n  just text\n", "YAML_MULTILINE_SCALAR", 2),
        c("-\n  just text\n", "YAML_MULTILINE_SCALAR", 2),
        c("a:\n  [1, 2]\n", "YAML_MULTILINE_SCALAR", 2),
        c("a: foo\n  bar\n  baz\n", "YAML_MULTILINE_SCALAR", 2),
        c("x:\n  a: foo\n    bar\n", "YAML_MULTILINE_SCALAR", 3),
        // a quoted scalar that runs over a line break has an open quote on its first line
        c("a: \"foo\n  bar\"\n", "YAML_UNTERMINATED", 1),
        // a more indented line that is itself a key line is an indentation error, as in T4
        c("a: 1\n  b: 2\n", "YAML_BAD_INDENT", 2),
        // a flow collection followed by deeper text
        c("a: [1, 2]\n  bar\n", "YAML_BAD_INDENT", 2));
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("multiLineScalars")
  void multiLineScalarsAreRejected(String text, String code, int line) {
    assertCode(text, code, line);
  }

  static Stream<Arguments> badEscapes() {
    return Stream.of(
        c("a: \"\\x41\"\n", "YAML_BAD_ESCAPE", 1),
        c("a: \"\\u0041\"\n", "YAML_BAD_ESCAPE", 1),
        c("a: \"\\t\"\n", "YAML_BAD_ESCAPE", 1),
        c("a: \"\\q\"\n", "YAML_BAD_ESCAPE", 1),
        c("a: \"\\r\"\n", "YAML_BAD_ESCAPE", 1),
        c("a: \"\\0\"\n", "YAML_BAD_ESCAPE", 1),
        c("a: \"\\/\"\n", "YAML_BAD_ESCAPE", 1),
        c("a: 1\nb: [\"ok\", \"\\e\"]\n", "YAML_BAD_ESCAPE", 2),
        c("\"k\\u0041\": 1\n", "YAML_BAD_ESCAPE", 1),
        c("a: {\"k\\x\": 1}\n", "YAML_BAD_ESCAPE", 1));
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("badEscapes")
  void badEscapes(String text, String code, int line) {
    assertCode(text, code, line);
  }

  @Test
  void aBackslashIsLiteralInSingleQuotesAndPlainScalars() {
    YamlMap parsed = (YamlMap) StrictYaml.parse("a: 'x\\ty\\u0041'\nb: x\\ty\nc: \"\\\\t\"\n");
    assertThat(((YamlScalar) parsed.entries().get("a")).value()).isEqualTo("x\\ty\\u0041");
    assertThat(((YamlScalar) parsed.entries().get("b")).value()).isEqualTo("x\\ty");
    assertThat(((YamlScalar) parsed.entries().get("c")).value()).isEqualTo("\\t");
  }

  static Stream<Arguments> unterminated() {
    return Stream.of(
        c("a: \"x\n", "YAML_UNTERMINATED", 1),
        c("a: 'x\n", "YAML_UNTERMINATED", 1),
        c("a: [1\n", "YAML_UNTERMINATED", 1),
        c("a: {b: c\n", "YAML_UNTERMINATED", 1),
        c("a: [\n", "YAML_UNTERMINATED", 1),
        c("a: {\n", "YAML_UNTERMINATED", 1),
        c("\"k: 1\n", "YAML_UNTERMINATED", 1),
        c("a: [[1, 2]\n", "YAML_UNTERMINATED", 1),
        c("a: [\"x]\n", "YAML_UNTERMINATED", 1),
        c("a: 1\nb: \"x\\\n", "YAML_UNTERMINATED", 2),
        c("- 'it''\n", "YAML_UNTERMINATED", 1));
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("unterminated")
  void unterminatedQuoteAndFlow(String text, String code, int line) {
    assertCode(text, code, line);
  }
}
