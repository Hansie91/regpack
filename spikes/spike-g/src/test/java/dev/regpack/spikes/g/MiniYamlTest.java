package dev.regpack.spikes.g;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MiniYamlTest {
  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    assertThat(value).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    assertThat(value).isInstanceOf(List.class);
    return (List<Object>) value;
  }

  @Test
  void parsesNestedMappingsSequencesAndFlowLists() {
    String text =
        """
        # a comment line
        id: emir.eu   # a trailing comment
        valid: {legal: {from: 2018-01-03, to: null}}
        parts: [a, b, 'c d']
        empty_list: []
        empty_map: {}
        rows:
          - id: R1
            priority: 100
            cite: [{source_id: x, role: BASIS}]
          - id: R2
            when: "a: b"
          - plain
          -
            nested: yes
        deeper:
          level2:
            - - inner1
              - inner2
            - key: v
              other: w
        bare:
        """;
    Map<String, Object> doc = map(MiniYaml.parse(text));

    assertThat(doc.keySet())
        .containsExactly(
            "id", "valid", "parts", "empty_list", "empty_map", "rows", "deeper", "bare");
    assertThat(doc.get("id")).isEqualTo("emir.eu");
    assertThat(map(map(doc.get("valid")).get("legal")))
        .containsEntry("from", "2018-01-03")
        .containsEntry("to", "null");
    assertThat(list(doc.get("parts"))).containsExactly("a", "b", "c d");
    assertThat(list(doc.get("empty_list"))).isEmpty();
    assertThat(map(doc.get("empty_map"))).isEmpty();

    List<Object> rows = list(doc.get("rows"));
    assertThat(rows).hasSize(4);
    Map<String, Object> first = map(rows.get(0));
    assertThat(first).containsEntry("id", "R1").containsEntry("priority", "100");
    assertThat(map(list(first.get("cite")).get(0)))
        .containsEntry("source_id", "x")
        .containsEntry("role", "BASIS");
    assertThat(map(rows.get(1))).containsEntry("when", "a: b");
    assertThat(rows.get(2)).isEqualTo("plain");
    assertThat(map(rows.get(3))).containsEntry("nested", "yes");

    List<Object> level2 = list(map(doc.get("deeper")).get("level2"));
    assertThat(list(level2.get(0))).containsExactly("inner1", "inner2");
    assertThat(map(level2.get(1))).containsEntry("key", "v").containsEntry("other", "w");
    assertThat(doc.get("bare")).isEqualTo("");
  }

  @Test
  void parsedStructuresCannotBeModified() {
    Object doc = MiniYaml.parse("a:\n  - b: 1\n    c: [x, y]\n  - {d: e}\nf: g\n");

    Map<String, Object> top = map(doc);
    assertThatThrownBy(() -> top.put("z", "1")).isInstanceOf(UnsupportedOperationException.class);
    List<Object> a = list(top.get("a"));
    assertThatThrownBy(() -> a.add("1")).isInstanceOf(UnsupportedOperationException.class);
    Map<String, Object> first = map(a.get(0));
    assertThatThrownBy(() -> first.put("z", "1")).isInstanceOf(UnsupportedOperationException.class);
    List<Object> c = list(first.get("c"));
    assertThatThrownBy(() -> c.set(0, "1")).isInstanceOf(UnsupportedOperationException.class);
    Map<String, Object> flow = map(a.get(1));
    assertThatThrownBy(() -> flow.clear()).isInstanceOf(UnsupportedOperationException.class);
    // the empty document too
    Map<String, Object> empty = map(MiniYaml.parse(""));
    assertThatThrownBy(() -> empty.put("z", "1")).isInstanceOf(UnsupportedOperationException.class);
    // file order is kept
    assertThat(top.keySet()).containsExactly("a", "f");
  }

  @Test
  void rejectsTabs() {
    assertThatThrownBy(() -> MiniYaml.parse("a:\n\tb: 1\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("line 2")
        .hasMessageContaining("tab");
    assertThatThrownBy(() -> MiniYaml.parse("a: 'x\ty'\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tab");
  }

  @Test
  void keepsQuotedScalarsVerbatim() {
    String text =
        """
        a: "x # y"
        b: '10:00:00Z'
        c: "a: b, [c] {d}"
        d: ""
        e: 'it''s'
        f: "q\\"uote"
        g: [ "p, q", 'r ]' ]
        h: not # a comment
        """;
    Map<String, Object> doc = map(MiniYaml.parse(text));

    assertThat(doc.get("a")).isEqualTo("x # y");
    assertThat(doc.get("b")).isEqualTo("10:00:00Z");
    assertThat(doc.get("c")).isEqualTo("a: b, [c] {d}");
    assertThat(doc.get("d")).isEqualTo("");
    assertThat(doc.get("e")).isEqualTo("it's");
    assertThat(doc.get("f")).isEqualTo("q\"uote");
    assertThat(list(doc.get("g"))).containsExactly("p, q", "r ]");
    assertThat(doc.get("h")).isEqualTo("not");
  }

  @Test
  void rejectsDuplicateKeysBadIndentationAndUnsupportedSyntax() {
    assertThatThrownBy(() -> MiniYaml.parse("a: 1\na: 2\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");
    assertThatThrownBy(() -> MiniYaml.parse("a: 1\n  b: 2\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("indent");
    assertThatThrownBy(() -> MiniYaml.parse("a: |\n  text\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("block scalar");
    assertThatThrownBy(() -> MiniYaml.parse("a: [1, 2\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("line 1");
    assertThatThrownBy(() -> MiniYaml.parse("a: \"open\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unterminated");
    assertThatThrownBy(() -> MiniYaml.parse("a: b: c\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("quote");
  }
}
