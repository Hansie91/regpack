package dev.regpack.pack.yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** PackYaml/1, accepting side (companion contracts section 5.2). */
class StrictYamlTest {
  private static YamlScalar s(String value, int line) {
    return new YamlScalar(value, line);
  }

  private static YamlList l(int line, YamlNode... items) {
    return new YamlList(List.of(items), line);
  }

  /** Entries are given as alternating key and node; the order is the file order. */
  private static YamlMap m(int line, Object... keysAndNodes) {
    Map<String, YamlNode> entries = new LinkedHashMap<>();
    for (int i = 0; i < keysAndNodes.length; i += 2) {
      entries.put((String) keysAndNodes[i], (YamlNode) keysAndNodes[i + 1]);
    }
    return new YamlMap(entries, line);
  }

  private static YamlMap map(String text) {
    return (YamlMap) StrictYaml.parse(text);
  }

  private static List<String> keys(YamlNode node) {
    return new ArrayList<>(((YamlMap) node).entries().keySet());
  }

  @Test
  void parsesBlockMappingsSequencesAndFlowCollections() {
    String text =
        String.join(
            "\n",
            "# header comment", // 1
            "id: sample          # trailing comment", // 2
            "name: \"Sample: pack\"", // 3
            "tags: [a, 'b c', \"d\"]", // 4
            "limits: {rule_nodes: 512, deep: {x: y}}", // 5
            "rows:", // 6
            "  - id: R1", // 7
            "    cite: [{source_id: s, role: BASIS}]", // 8
            "  - plain item", // 9
            "  -", // 10
            "    k: v", // 11
            "empty_list: []", // 12
            "nested:", // 13
            "  inner:", // 14
            "    leaf: 1", // 15
            "");
    YamlNode expected =
        m(
            2,
            "id",
            s("sample", 2),
            "name",
            s("Sample: pack", 3),
            "tags",
            l(4, s("a", 4), s("b c", 4), s("d", 4)),
            "limits",
            m(5, "rule_nodes", s("512", 5), "deep", m(5, "x", s("y", 5))),
            "rows",
            l(
                7,
                m(
                    7,
                    "id",
                    s("R1", 7),
                    "cite",
                    l(8, m(8, "source_id", s("s", 8), "role", s("BASIS", 8)))),
                s("plain item", 9),
                m(11, "k", s("v", 11))),
            "empty_list",
            l(12),
            "nested",
            m(14, "inner", m(15, "leaf", s("1", 15))));
    assertThat(StrictYaml.parse(text)).isEqualTo(expected);
  }

  @Test
  void everyScalarIsAString() {
    String text =
        String.join(
            "\n",
            "- NO",
            "- yes",
            "- null",
            "- ~",
            "- 1e3",
            "- 0o17",
            "- 2018-01-03",
            "- .inf",
            "- 0x1F",
            "- y",
            "- true",
            "- 007",
            "");
    YamlList list = (YamlList) StrictYaml.parse(text);
    List<String> values = new ArrayList<>();
    for (YamlNode item : list.items()) {
      assertThat(item).isInstanceOf(YamlScalar.class);
      values.add(((YamlScalar) item).value());
    }
    assertThat(values)
        .containsExactly(
            "NO",
            "yes",
            "null",
            "~",
            "1e3",
            "0o17",
            "2018-01-03",
            ".inf",
            "0x1F",
            "y",
            "true",
            "007");
    YamlMap viaMapping = map("a: NO\nb: null\nc: 1e3\nd: 2018-01-03\ne: [NO, 007]\n");
    assertThat(viaMapping.entries().get("a")).isEqualTo(s("NO", 1));
    assertThat(viaMapping.entries().get("b")).isEqualTo(s("null", 2));
    assertThat(viaMapping.entries().get("c")).isEqualTo(s("1e3", 3));
    assertThat(viaMapping.entries().get("d")).isEqualTo(s("2018-01-03", 4));
    assertThat(viaMapping.entries().get("e")).isEqualTo(l(5, s("NO", 5), s("007", 5)));
  }

  @Test
  void quotedScalarsKeepTheirTextAndEscapes() {
    String text =
        String.join(
            "\n",
            "a: \"a: b # c\"",
            "b: 'it''s'",
            "c: \"q\\\"uote\\\\\"",
            "d: \"line\\nbreak\"",
            "e: ''",
            "f: \"\"",
            "g: 'x # y'   # real comment",
            "h: [\"a, b\", 'c]d']",
            "");
    YamlMap parsed = map(text);
    assertThat(parsed.entries().get("a")).isEqualTo(s("a: b # c", 1));
    assertThat(parsed.entries().get("b")).isEqualTo(s("it's", 2));
    assertThat(parsed.entries().get("c")).isEqualTo(s("q\"uote\\", 3));
    assertThat(parsed.entries().get("d")).isEqualTo(s("line\nbreak", 4));
    assertThat(parsed.entries().get("e")).isEqualTo(s("", 5));
    assertThat(parsed.entries().get("f")).isEqualTo(s("", 6));
    assertThat(parsed.entries().get("g")).isEqualTo(s("x # y", 7));
    assertThat(parsed.entries().get("h")).isEqualTo(l(8, s("a, b", 8), s("c]d", 8)));
  }

  @Test
  void commentsAreCutOutsideQuotesOnly() {
    YamlMap parsed =
        map("a: b # c\nb: b#c\nc: \"b # c\" # d\n# whole line\n  # indented comment\nd: e\n");
    assertThat(parsed.entries().get("a")).isEqualTo(s("b", 1));
    assertThat(parsed.entries().get("b")).isEqualTo(s("b#c", 2));
    assertThat(parsed.entries().get("c")).isEqualTo(s("b # c", 3));
    assertThat(parsed.entries().get("d")).isEqualTo(s("e", 6));
    // a comment may stand where a value would start: the value is then a nested block
    YamlMap nested = map("a: # why\n  b: c\n");
    assertThat(nested.entries().get("a")).isEqualTo(m(2, "b", s("c", 2)));
  }

  @Test
  void fileOrderIsKeptAndTheTreeIsImmutable() {
    YamlMap parsed = map("z: 1\na: 2\nm: {y: 1, b: 2, q: {k2: 1, k1: 2}}\nl: [3, 1, 2]\n");
    assertThat(keys(parsed)).containsExactly("z", "a", "m", "l");
    YamlMap flow = (YamlMap) parsed.entries().get("m");
    assertThat(keys(flow)).containsExactly("y", "b", "q");
    assertThat(keys(flow.entries().get("q"))).containsExactly("k2", "k1");
    YamlList list = (YamlList) parsed.entries().get("l");
    assertThat(list.items()).containsExactly(s("3", 4), s("1", 4), s("2", 4));

    assertThatThrownBy(() -> parsed.entries().put("x", s("x", 1)))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> flow.entries().remove("y"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> list.items().add(s("x", 1)))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ((YamlList) StrictYaml.parse("- a\n- b\n")).items().clear())
        .isInstanceOf(UnsupportedOperationException.class);

    // the records copy their input: later changes to the caller's collections do not leak in
    List<YamlNode> source = new ArrayList<>(List.of(s("a", 1)));
    YamlList copied = new YamlList(source, 1);
    source.add(s("b", 1));
    assertThat(copied.items()).hasSize(1);
    Map<String, YamlNode> sourceMap = new LinkedHashMap<>();
    sourceMap.put("k", s("v", 1));
    YamlMap copiedMap = new YamlMap(sourceMap, 1);
    sourceMap.put("k2", s("v", 1));
    assertThat(copiedMap.entries()).hasSize(1);
  }

  @Test
  void crlfAndLfParseToEqualTrees() {
    String lf =
        "# c\nid: sample\nrows:\n  - id: R1\n    cite: [a, b]\n  - id: R2\n\nname: \"x\"\ntags: {a: b}\n";
    String crlf = lf.replace("\n", "\r\n");
    assertThat(crlf).contains("\r\n");
    assertThat(StrictYaml.parse(crlf)).isEqualTo(StrictYaml.parse(lf));
  }

  @Test
  void sequenceItemMappingMayStartOnTheDashLine() {
    String text =
        String.join(
            "\n",
            "items:", // 1
            "  - a: 1", // 2
            "    b: 2", // 3
            "  -", // 4
            "    a: 3", // 5
            "    b:", // 6
            "      c: 4", // 7
            "  -   a: 5", // 8
            "      b: 6", // 9
            "after: x", // 10
            "");
    // line() of a collection is the line of its first key or dash
    YamlNode expected =
        m(
            1,
            "items",
            l(
                2,
                m(2, "a", s("1", 2), "b", s("2", 3)),
                m(5, "a", s("3", 5), "b", m(7, "c", s("4", 7))),
                m(8, "a", s("5", 8), "b", s("6", 9))),
            "after",
            s("x", 10));
    assertThat(StrictYaml.parse(text)).isEqualTo(expected);
    // a top-level sequence is a document too
    assertThat(StrictYaml.parse("- k: v\n  w: x\n- y\n"))
        .isEqualTo(l(1, m(1, "k", s("v", 1), "w", s("x", 2)), s("y", 3)));
  }

  @Test
  void flowCollectionsNestOnOneLine() {
    YamlNode parsed = StrictYaml.parse("x: {a: [1, {b: c}], d: [], e: {}}\n");
    YamlNode expected =
        m(1, "x", m(1, "a", l(1, s("1", 1), m(1, "b", s("c", 1))), "d", l(1), "e", m(1)));
    assertThat(parsed).isEqualTo(expected);
    assertThat(StrictYaml.parse("k: [ [a, b] , [ ] , { } ]\n"))
        .isEqualTo(m(1, "k", l(1, l(1, s("a", 1), s("b", 1)), l(1), m(1))));
    assertThat(StrictYaml.parse("k: { 'q key': \"v\", z: [ x ] }  # c\n"))
        .isEqualTo(m(1, "k", m(1, "q key", s("v", 1), "z", l(1, s("x", 1)))));
  }

  @Test
  void plainScalarsMayContainBracketsQuotesAndCommasInBlockContext() {
    YamlMap parsed =
        map(
            String.join(
                "\n",
                "when: event.kind in ['A','B']",
                "note: it's fine, really [ok] {yes}",
                "path: a/b:c",
                "list:",
                "  - x in ['NOVATION','ASSIGNMENT']",
                "  - 1, 2, 3",
                ""));
    assertThat(parsed.entries().get("when")).isEqualTo(s("event.kind in ['A','B']", 1));
    assertThat(parsed.entries().get("note")).isEqualTo(s("it's fine, really [ok] {yes}", 2));
    assertThat(parsed.entries().get("path")).isEqualTo(s("a/b:c", 3));
    assertThat(parsed.entries().get("list"))
        .isEqualTo(l(5, s("x in ['NOVATION','ASSIGNMENT']", 5), s("1, 2, 3", 6)));
  }

  @Test
  void keysMayBeDottedOrQuoted() {
    YamlMap parsed =
        map(
            String.join(
                "\n",
                "a.b-c: 1",
                "\"quoted key\": 2",
                "'single: key': 3",
                "_x: 4",
                "\"ke\\\"y\": 5",
                "plain_1.2: 6",
                ""));
    assertThat(keys(parsed))
        .containsExactly("a.b-c", "quoted key", "single: key", "_x", "ke\"y", "plain_1.2");
    assertThat(parsed.entries().get("single: key")).isEqualTo(s("3", 3));
  }

  @Test
  void emptyAndCommentOnlyDocumentsAreEmptyMaps() {
    for (String text : List.of("", "\n", "# c\n\n  # d\n", "   \n\n", "# only", "\r\n\r\n")) {
      assertThat(StrictYaml.parse(text)).as(text).isEqualTo(new YamlMap(Map.of(), 1));
      assertThat(((YamlMap) StrictYaml.parse(text)).entries()).isEmpty();
    }
  }

  @Test
  void lineNumbersAreOneBased() {
    YamlMap parsed = map("\n\n# comment\na: 1\n\n  \nb:\n  - x\n  -\n    y: z\n");
    assertThat(parsed.line()).isEqualTo(4);
    assertThat(parsed.entries().get("a").line()).isEqualTo(4);
    YamlList list = (YamlList) parsed.entries().get("b");
    assertThat(list.line()).isEqualTo(8);
    assertThat(list.items().get(0).line()).isEqualTo(8);
    assertThat(list.items().get(1).line()).isEqualTo(10);
    assertThat(new YamlScalar("x", 1).line()).isEqualTo(1);
  }

  @Test
  void theSyntaxCodesOfThisTaskAreRaisedWithTheirLine() {
    assertCode("a: 1\n  b: 2\n", "YAML_BAD_INDENT", 2);
    assertCode("a:\n  b: 1\n c: 2\n", "YAML_BAD_INDENT", 3);
    assertCode("a: 1\na: 2\n", "YAML_DUPLICATE_KEY", 2);
    assertCode("a: {b: 1, b: 2}\n", "YAML_DUPLICATE_KEY", 1);
    assertCode("a: \"open\n", "YAML_UNTERMINATED", 1);
    assertCode("a: [1, 2\n", "YAML_UNTERMINATED", 1);
    assertCode("a:\nb: 1\n", "YAML_EMPTY_VALUE", 1);
    assertCode("a: {b:}\n", "YAML_EMPTY_VALUE", 1);
    assertCode("bad key: 1\n", "YAML_BAD_KEY", 1);
    assertCode("1a: 1\n", "YAML_BAD_KEY", 1);
    assertCode("a: 1\njust text\n", "YAML_SYNTAX", 2);
    assertCode("a: \"x\" y\n", "YAML_SYNTAX", 1);
    assertCode("a: [1,,2]\n", "YAML_SYNTAX", 1);
    assertThat(new PackSyntaxException("YAML_SYNTAX", 7, "x").getMessage())
        .isEqualTo("line 7: YAML_SYNTAX: x");
  }

  private static void assertCode(String text, String code, int line) {
    assertThatThrownBy(() -> StrictYaml.parse(text))
        .isInstanceOfSatisfying(
            PackSyntaxException.class,
            e -> {
              assertThat(e.code()).as(text).isEqualTo(code);
              assertThat(e.line()).as(text).isEqualTo(line);
            });
  }
}
