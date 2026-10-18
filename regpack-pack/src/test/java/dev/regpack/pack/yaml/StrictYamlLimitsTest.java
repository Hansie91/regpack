package dev.regpack.pack.yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.pack.PackFormat;
import dev.regpack.pack.yaml.StrictYaml.Limits;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The input limits of PackYaml/1 (review focus PF1): each limit rejects one past it and accepts
 * exactly at it. Every test relaxes all limits but the one under test.
 */
class StrictYamlLimitsTest {
  private static final int BIG = 1_000_000;

  private static Limits file(int n) {
    return new Limits(n, BIG, BIG, BIG, 100, BIG);
  }

  private static Limits line(int n) {
    return new Limits(BIG, n, BIG, BIG, 100, BIG);
  }

  private static Limits scalar(int n) {
    return new Limits(BIG, BIG, n, BIG, 100, BIG);
  }

  private static Limits key(int n) {
    return new Limits(BIG, BIG, BIG, n, 100, BIG);
  }

  private static Limits depth(int n) {
    return new Limits(BIG, BIG, BIG, BIG, n, BIG);
  }

  private static Limits nodes(int n) {
    return new Limits(BIG, BIG, BIG, BIG, 100, n);
  }

  /** Accepted at {@code atLimit}, rejected with {@code code} on {@code line} one below it. */
  private static void exactlyAt(
      String text, Limits atLimit, Limits oneBelow, String code, int line) {
    assertThatCode(() -> StrictYaml.parse(text, atLimit))
        .as("at the limit")
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> StrictYaml.parse(text, oneBelow))
        .as("one past the limit")
        .isInstanceOfSatisfying(
            PackSyntaxException.class,
            e -> {
              assertThat(e.code()).isEqualTo(code);
              assertThat(e.line()).isEqualTo(line);
            });
  }

  @Test
  void defaultsComeFromPackFormat() {
    assertThat(Limits.defaults())
        .isEqualTo(
            new Limits(
                PackFormat.MAX_FILE_BYTES,
                PackFormat.MAX_LINE_CHARS,
                PackFormat.MAX_SCALAR_CHARS,
                PackFormat.MAX_KEY_CHARS,
                PackFormat.MAX_DEPTH,
                PackFormat.MAX_NODES));
  }

  @Test
  void fileSize() {
    String text = "a: bb\n"; // 6 characters
    exactlyAt(text, file(6), file(5), "YAML_TOO_LARGE", 1);
    // the size is the raw size, so CRLF counts two characters
    String crlf = "a: bb\r\n";
    exactlyAt(crlf, file(7), file(6), "YAML_TOO_LARGE", 1);
    // an empty file is within any limit of at least zero characters
    assertThatCode(() -> StrictYaml.parse("", file(0))).doesNotThrowAnyException();
  }

  @Test
  void theDefaultFileLimitIsEnforcedBeforeAnyParsing() {
    // 16,384 comment lines of 64 characters are exactly 1 MiB of characters
    String commentLine = "# " + "x".repeat(61) + "\n";
    assertThat(commentLine).hasSize(64);
    String atLimit = commentLine.repeat(PackFormat.MAX_FILE_BYTES / 64);
    assertThat(atLimit).hasSize(PackFormat.MAX_FILE_BYTES);
    assertThat(StrictYaml.parse(atLimit)).isEqualTo(new YamlMap(Map.of(), 1));
    assertThatThrownBy(() -> StrictYaml.parse(atLimit + "\n"))
        .isInstanceOfSatisfying(
            PackSyntaxException.class,
            e -> {
              assertThat(e.code()).isEqualTo("YAML_TOO_LARGE");
              assertThat(e.line()).isEqualTo(1);
            });
  }

  @Test
  void lineLength() {
    String text = "a: 1\nb: " + "x".repeat(17) + "\n"; // second line has 20 characters
    exactlyAt(text, line(20), line(19), "YAML_TOO_LARGE", 2);
    // comments, indentation and blank-looking lines count
    exactlyAt("a: 1\n# " + "c".repeat(8) + "\n", line(10), line(9), "YAML_TOO_LARGE", 2);
    exactlyAt("a:\n" + " ".repeat(5) + "b: 1\n", line(9), line(8), "YAML_TOO_LARGE", 2);
    exactlyAt("a: 1\n" + " ".repeat(9) + "\n", line(9), line(8), "YAML_TOO_LARGE", 2);
    // the CR of a CRLF is not part of the line
    exactlyAt("a: 12345\r\nb: 1\r\n", line(8), line(7), "YAML_TOO_LARGE", 1);
  }

  @Test
  void scalarLength() {
    exactlyAt("a: xxxxx\n", scalar(5), scalar(4), "YAML_TOO_LARGE", 1);
    exactlyAt("a: \"xxxxx\"\n", scalar(5), scalar(4), "YAML_TOO_LARGE", 1); // quotes do not count
    exactlyAt("a: 'xxxxx'\n", scalar(5), scalar(4), "YAML_TOO_LARGE", 1);
    exactlyAt("a: 1\nb: xxxxx\n", scalar(5), scalar(4), "YAML_TOO_LARGE", 2);
    exactlyAt("- xxxxx\n", scalar(5), scalar(4), "YAML_TOO_LARGE", 1);
    exactlyAt("a: [x, xxxxx]\n", scalar(5), scalar(4), "YAML_TOO_LARGE", 1);
    exactlyAt("a: {b: xxxxx}\n", scalar(5), scalar(4), "YAML_TOO_LARGE", 1);
    // the length is that of the parsed value: an escape is one character
    exactlyAt("a: \"x\\ny\\\\z\"\n", scalar(5), scalar(4), "YAML_TOO_LARGE", 1);
    exactlyAt("a: 'it''s'\n", scalar(4), scalar(3), "YAML_TOO_LARGE", 1);
    // a supplementary character counts as the characters of its UTF-16 form
    exactlyAt("a: \ud83d\ude00\n", scalar(2), scalar(1), "YAML_TOO_LARGE", 1);
  }

  @Test
  void keyLength() {
    exactlyAt("kkkkk: v\n", key(5), key(4), "YAML_TOO_LARGE", 1);
    exactlyAt("a: 1\n\"kkkkk\": v\n", key(5), key(4), "YAML_TOO_LARGE", 2);
    exactlyAt("a: {kkkkk: v}\n", key(5), key(4), "YAML_TOO_LARGE", 1);
    exactlyAt("a: {'kkkkk': v}\n", key(5), key(4), "YAML_TOO_LARGE", 1);
    exactlyAt("- kkkkk: v\n", key(5), key(4), "YAML_TOO_LARGE", 1);
    // the shipped limit: a key of 128 characters is fine, 129 is not
    String k128 = "k".repeat(PackFormat.MAX_KEY_CHARS);
    assertThatCode(() -> StrictYaml.parse(k128 + ": v\n")).doesNotThrowAnyException();
    assertThatThrownBy(() -> StrictYaml.parse(k128 + "k: v\n"))
        .isInstanceOfSatisfying(
            PackSyntaxException.class, e -> assertThat(e.code()).isEqualTo("YAML_TOO_LARGE"));
  }

  @Test
  void depthInBlockCollections() {
    // maps nested three deep: depth 3 (the outermost collection has depth 1)
    exactlyAt("a:\n  b:\n    c: x\n", depth(3), depth(2), "YAML_TOO_DEEP", 3);
    // a sequence of a mapping of a sequence
    exactlyAt("- a:\n    - x\n", depth(3), depth(2), "YAML_TOO_DEEP", 2);
    // a single level
    exactlyAt("a: x\n", depth(1), depth(0), "YAML_TOO_DEEP", 1);
    // a sibling after a deep branch is not deeper
    exactlyAt("a:\n  b: x\nc:\n  d: y\n", depth(2), depth(1), "YAML_TOO_DEEP", 2);
  }

  @Test
  void depthInFlowCollections() {
    // the root mapping is depth 1, the flow collections below it 2 and 3
    exactlyAt("a: [[x]]\n", depth(3), depth(2), "YAML_TOO_DEEP", 1);
    exactlyAt("a: {b: {c: d}}\n", depth(3), depth(2), "YAML_TOO_DEEP", 1);
    exactlyAt("a: [{b: [x]}]\n", depth(4), depth(3), "YAML_TOO_DEEP", 1);
    exactlyAt("- [[x]]\n", depth(3), depth(2), "YAML_TOO_DEEP", 1);
    // empty collections still count
    exactlyAt("a: [[[]]]\n", depth(4), depth(3), "YAML_TOO_DEEP", 1);
  }

  @Test
  void nodeCount() {
    // a mapping and two scalars
    exactlyAt("a: b\nc: d\n", nodes(3), nodes(2), "YAML_TOO_MANY_NODES", 2);
    // a mapping, a list and two scalars
    exactlyAt("a: [x, y]\n", nodes(4), nodes(3), "YAML_TOO_MANY_NODES", 1);
    // a sequence of two mappings with one scalar each: 1 + 2 + 2
    exactlyAt("- a: 1\n- b: 2\n", nodes(5), nodes(4), "YAML_TOO_MANY_NODES", 2);
    // flow collections count their scalars and themselves
    exactlyAt("a: {b: [c, d], e: {}}\n", nodes(6), nodes(5), "YAML_TOO_MANY_NODES", 1);
    // the empty document is one node
    assertThatCode(() -> StrictYaml.parse("", nodes(1))).doesNotThrowAnyException();
  }

  @Test
  void theNodeLimitStopsTheParseWhileBuilding() {
    // 20,000 entries against a limit of 100: the error names a line near the limit, not the end
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 20_000; i++) {
      text.append('k').append(i).append(": v\n");
    }
    assertThatThrownBy(() -> StrictYaml.parse(text.toString(), nodes(100)))
        .isInstanceOfSatisfying(
            PackSyntaxException.class,
            e -> {
              assertThat(e.code()).isEqualTo("YAML_TOO_MANY_NODES");
              assertThat(e.line()).isLessThanOrEqualTo(100);
            });
  }
}
