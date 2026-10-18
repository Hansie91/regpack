package dev.regpack.pack.yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.pack.PackFormat;
import dev.regpack.pack.yaml.StrictYaml.Limits;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Hostile input (review focus PF1): seeded mutations, deep nesting and byte soup. A parse either
 * returns a tree or raises a {@link PackSyntaxException}; nothing else may escape.
 */
class StrictYamlHostileInputTest {
  private static final List<String> VALID_DOCUMENTS =
      List.of(
          String.join(
              "\n",
              "# a pack manifest",
              "id: sample.s0",
              "semver: 1.0.0",
              "valid: {legal: {from: 2018-01-03}}",
              "limits: {rule_nodes: 512, list_items: 256}",
              "reports:",
              "  - id: sample.s0.report",
              "    input: model/canonical-input.yaml",
              "    scope_tables: [scope/a.yaml, scope/b.yaml]",
              ""),
          String.join(
              "\n",
              "table: {id: sample.s0.scope, hit_policy: FIRST, priority: explicit, inputs: [event.kind], catch_all: S-DEFAULT}",
              "rows:",
              "  - id: S-N03-novation              # a comment",
              "    node: N03",
              "    priority: 300",
              "    when: \"event.kind in ['NOVATION','ASSIGNMENT']\"",
              "    outcome: DO_NOT_REPORT",
              "    reason: SAMPLE.N03.EXCLUDED",
              "    cite: [{source_id: sample-law, provision_path: sample/1, role: BASIS}]",
              "    set: {action: NEWT}",
              "  - id: S-DEFAULT",
              "    node: N99",
              "    priority: 999",
              "    when: \"true\"",
              "    outcome: UNDETERMINED",
              ""),
          String.join(
              "\n",
              "sources:",
              "  - source_id: sample-law",
              "    version: '1'",
              "    provisions:",
              "      - {path: sample/1, role: BASIS, status: IN_FORCE}",
              "      - path: sample/2",
              "        quote: \"it's \\\"quoted\\\" \\\\ text\"",
              "rationales:",
              "  - id: RAT-0001",
              "    text: 'plain, with ''quotes'' and a # not a comment'",
              ""),
          String.join(
              "\n",
              "variables:",
              "  event.kind: {type: ENUM, enum: EventKind}",
              "  client.lei: {type: STRING, optional: true}",
              "  state.status: {type: STRING, source: STATE}",
              ""),
          String.join("\r\n", "- a", "- b: 1", "  c: [1, 2, 3]", "-", "  d: {e: f}", ""),
          "x: [1, [2, [3, {k: v}]]]\ny: {a: [], b: {}}\n\n# end\n");

  private static final String[] ALPHABET = {
    "&",
    "*",
    "!",
    "|",
    ">",
    "%",
    "?",
    "-",
    ":",
    "[",
    "]",
    "{",
    "}",
    "\"",
    "'",
    "#",
    "\\",
    ",",
    " ",
    "\t",
    "\n",
    "\r",
    "\ufeff",
    "\u00e9",
    "\u65e5",
    "\ud83d\ude00",
    "a",
    "0",
    "<<",
    "---",
    "...",
    ": ",
    "- ",
    "\\n",
    "\u202e",
    "\u0000"
  };

  @Test
  void randomMutationsOnlyEverRaisePackSyntaxException() {
    SplittableRandom random = new SplittableRandom(20261016L);
    int accepted = 0;
    int rejected = 0;
    for (int i = 0; i < 20_000; i++) {
      StringBuilder text = new StringBuilder(VALID_DOCUMENTS.get(random.nextInt(6)));
      int mutations = 1 + random.nextInt(3);
      for (int m = 0; m < mutations; m++) {
        mutate(text, random);
      }
      String input = text.toString();
      try {
        StrictYaml.parse(input);
        accepted++;
      } catch (PackSyntaxException e) {
        rejected++;
        assertThat(e.code()).as(escaped(input)).matches("YAML_[A-Z_]+");
        assertThat(e.line()).as(escaped(input)).isPositive();
        assertThat(e.getMessage()).as(escaped(input)).hasSizeLessThanOrEqualTo(160);
      } catch (RuntimeException | StackOverflowError e) {
        throw new AssertionError("unexpected " + e + " for input " + escaped(input), e);
      }
    }
    // the test is not vacuous: both outcomes occur
    assertThat(accepted).isGreaterThan(100);
    assertThat(rejected).isGreaterThan(100);
  }

  private static void mutate(StringBuilder text, SplittableRandom random) {
    int kind = random.nextInt(3);
    if (text.length() == 0) {
      kind = 0;
    }
    switch (kind) {
      case 0 -> {
        int at = random.nextInt(text.length() + 1);
        text.insert(at, ALPHABET[random.nextInt(ALPHABET.length)]);
      }
      case 1 -> {
        int at = random.nextInt(text.length());
        int len = 1 + random.nextInt(Math.min(3, text.length() - at));
        text.delete(at, at + len);
      }
      default -> {
        int a = random.nextInt(text.length());
        int b = random.nextInt(text.length());
        char ca = text.charAt(a);
        text.setCharAt(a, text.charAt(b));
        text.setCharAt(b, ca);
      }
    }
  }

  private static String escaped(String text) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c >= 0x20 && c <= 0x7E) {
        out.append(c);
      } else {
        out.append("\\u").append(String.format("%04x", (int) c));
      }
    }
    return out.toString();
  }

  @Test
  void theValidDocumentsAreValid() {
    for (String document : VALID_DOCUMENTS) {
      assertThat(StrictYaml.parse(document)).isNotNull();
    }
  }

  @Test
  void deeplyNestedFlowFailsFastWithTooDeep() {
    String text = "a: " + "[".repeat(10_000) + "\n";
    // the shipped limits stop the line itself
    assertThatThrownBy(() -> StrictYaml.parse(text))
        .isInstanceOfSatisfying(
            PackSyntaxException.class, e -> assertThat(e.code()).isEqualTo("YAML_TOO_LARGE"));
    // with the line limit lifted, the depth limit stops it long before the stack is at risk
    Limits lifted =
        new Limits(
            PackFormat.MAX_FILE_BYTES,
            100_000,
            PackFormat.MAX_SCALAR_CHARS,
            PackFormat.MAX_KEY_CHARS,
            PackFormat.MAX_DEPTH,
            PackFormat.MAX_NODES);
    assertThatThrownBy(() -> StrictYaml.parse(text, lifted))
        .isInstanceOfSatisfying(
            PackSyntaxException.class,
            e -> {
              assertThat(e.code()).isEqualTo("YAML_TOO_DEEP");
              assertThat(e.line()).isEqualTo(1);
            });
    String braces = "a: " + "{k: ".repeat(10_000) + "\n";
    assertThatThrownBy(() -> StrictYaml.parse(braces, lifted))
        .isInstanceOfSatisfying(
            PackSyntaxException.class, e -> assertThat(e.code()).isEqualTo("YAML_TOO_DEEP"));
    // exactly the shipped depth passes, one more fails
    String atLimit =
        "a: " + "[".repeat(PackFormat.MAX_DEPTH - 1) + "]".repeat(PackFormat.MAX_DEPTH - 1);
    assertThat(StrictYaml.parse(atLimit)).isNotNull();
    String over = "a: " + "[".repeat(PackFormat.MAX_DEPTH) + "]".repeat(PackFormat.MAX_DEPTH);
    assertThatThrownBy(() -> StrictYaml.parse(over))
        .isInstanceOfSatisfying(
            PackSyntaxException.class, e -> assertThat(e.code()).isEqualTo("YAML_TOO_DEEP"));
  }

  @Test
  void deeplyIndentedBlocksFailAtTheDepthLimit() {
    StringBuilder text = new StringBuilder();
    for (int level = 0; level < 400; level++) {
      text.append(" ".repeat(level * 2)).append('k').append(level).append(":\n");
    }
    text.append(" ".repeat(800)).append("leaf: x\n");
    assertThatThrownBy(() -> StrictYaml.parse(text.toString(), relaxedExceptDepth()))
        .isInstanceOfSatisfying(
            PackSyntaxException.class,
            e -> {
              assertThat(e.code()).isEqualTo("YAML_TOO_DEEP");
              // the root mapping is depth 1 on line 1, so depth 33 starts on line 33
              assertThat(e.line()).isEqualTo(PackFormat.MAX_DEPTH + 1);
            });
  }

  private static Limits relaxedExceptDepth() {
    return new Limits(
        PackFormat.MAX_FILE_BYTES,
        100_000,
        PackFormat.MAX_SCALAR_CHARS,
        PackFormat.MAX_KEY_CHARS,
        PackFormat.MAX_DEPTH,
        PackFormat.MAX_NODES);
  }

  @Test
  void tenThousandShortKeysStayWithinTheNodeLimit() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 10_000; i++) {
      text.append('k').append(i).append(": v\n");
    }
    YamlMap parsed = (YamlMap) StrictYaml.parse(text.toString());
    assertThat(parsed.entries()).hasSize(10_000);
    assertThat(parsed.entries().get("k9999")).isEqualTo(new YamlScalar("v", 10_000));
    // one mapping and 10,000 scalars: the node limit is met exactly
    Limits exact =
        new Limits(
            PackFormat.MAX_FILE_BYTES,
            PackFormat.MAX_LINE_CHARS,
            PackFormat.MAX_SCALAR_CHARS,
            PackFormat.MAX_KEY_CHARS,
            PackFormat.MAX_DEPTH,
            10_001);
    assertThat(StrictYaml.parse(text.toString(), exact)).isEqualTo(parsed);
    Limits oneShort =
        new Limits(
            PackFormat.MAX_FILE_BYTES,
            PackFormat.MAX_LINE_CHARS,
            PackFormat.MAX_SCALAR_CHARS,
            PackFormat.MAX_KEY_CHARS,
            PackFormat.MAX_DEPTH,
            10_000);
    assertThatThrownBy(() -> StrictYaml.parse(text.toString(), oneShort))
        .isInstanceOfSatisfying(
            PackSyntaxException.class,
            e -> {
              assertThat(e.code()).isEqualTo("YAML_TOO_MANY_NODES");
              assertThat(e.line()).isEqualTo(10_000);
            });
  }

  @Test
  void pathologicalLinesAreRefusedWithAParserError() {
    String[] shapes = {
      "a: " + "\"".repeat(8000),
      "a: " + "'".repeat(8000),
      "a: '" + "''".repeat(3000) + "'",
      "a: \"" + "\\\\".repeat(3000) + "\"",
      "a: \"" + "\\".repeat(3001),
      "a: " + "&".repeat(8000),
      "a: " + "*".repeat(8000),
      "- ".repeat(4000),
      "- ".repeat(4000) + "x",
      "a: " + "{a:".repeat(2000),
      "a: " + "[a,".repeat(2000),
      "a: [" + ",".repeat(8000) + "]",
      "a: {" + ", ".repeat(4000) + "}",
      ": ".repeat(4000),
      "a: " + "#".repeat(8000),
      "a: " + "x #".repeat(2000),
      "a: " + ": ".repeat(4000),
      "\"".repeat(8000) + ": x",
      "a:\n".repeat(2000),
      "---\n".repeat(2000),
      "? ".repeat(4000),
      "a: " + "<<".repeat(4000),
      "<<: " + "[".repeat(4000)
    };
    for (String shape : shapes) {
      try {
        StrictYaml.parse(shape + "\n");
      } catch (PackSyntaxException expected) {
        assertThat(expected.getMessage()).hasSizeLessThanOrEqualTo(160);
      }
    }
    // the ones that must fail do so with the code that names the problem
    assertThatThrownBy(() -> StrictYaml.parse("- ".repeat(4000) + "x\n"))
        .isInstanceOfSatisfying(
            PackSyntaxException.class, e -> assertThat(e.code()).isEqualTo("YAML_NESTED_SEQUENCE"));
    assertThatThrownBy(() -> StrictYaml.parse("---\n".repeat(2000)))
        .isInstanceOfSatisfying(
            PackSyntaxException.class,
            e -> {
              assertThat(e.code()).isEqualTo("YAML_DOCUMENT_MARKER");
              assertThat(e.line()).isEqualTo(1);
            });
  }

  @Test
  void byteSoupIsRejectedWithoutAnyOtherException() {
    SplittableRandom random = new SplittableRandom(20261016L);
    for (int i = 0; i < 2_000; i++) {
      int length = 1 + random.nextInt(300);
      StringBuilder soup = new StringBuilder();
      for (int j = 0; j < length; j++) {
        soup.append((char) random.nextInt(0x3000));
      }
      String input = soup.toString();
      try {
        StrictYaml.parse(input);
      } catch (PackSyntaxException expected) {
        assertThat(expected.getMessage()).as(escaped(input)).hasSizeLessThanOrEqualTo(160);
      } catch (RuntimeException | StackOverflowError e) {
        throw new AssertionError("unexpected " + e + " for input " + escaped(input), e);
      }
    }
  }
}
