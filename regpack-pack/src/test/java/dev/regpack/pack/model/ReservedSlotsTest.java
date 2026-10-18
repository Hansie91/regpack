package dev.regpack.pack.model;

import static dev.regpack.pack.PackFixtures.PACK;
import static dev.regpack.pack.PackFixtures.TABLE_A;
import static dev.regpack.pack.PackFixtures.codes;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.pack.PackFixtures;
import dev.regpack.pack.check.PackProblem;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Reserved slots are accepted only when empty and listed as not interpreted (PF6; plan T9). */
class ReservedSlotsTest {
  private static void assertOnly(LoadResult result, String code) {
    assertThat(new TreeSet<>(codes(result))).containsExactly(code);
    assertThat(result.pack()).isEmpty();
  }

  @Test
  void importsMustBeEmpty() { // BRK-13
    assertThat(
            PackFixtures.valid()
                .replace(PACK, "reports:", "imports: []\nreports:")
                .load()
                .problems())
        .isEmpty();
    assertOnly(
        PackFixtures.valid().replace(PACK, "reports:", "imports: [common]\nreports:").load(),
        "IMPORTS_RESERVED");
    assertOnly(
        PackFixtures.valid().replace(PACK, "reports:", "imports: common\nreports:").load(),
        "IMPORTS_RESERVED");
  }

  @Test
  void interpretationsMustBeEmpty() {
    assertThat(PackFixtures.valid().load().problems()).isEmpty(); // absent
    assertThat(PackFixtures.valid().put("interpretations.yaml", "pins: []\n").load().problems())
        .isEmpty();
    assertThat(
            PackFixtures.valid()
                .put("interpretations.yaml", "# none yet\npins: []   # empty\n")
                .load()
                .problems())
        .isEmpty();
    for (String text :
        List.of(
            "pins: [x]\n", "pins:\n  - id: IP-1\n", "other: x\n", "pins: []\nextra: []\n", "")) {
      assertOnly(
          PackFixtures.valid().put("interpretations.yaml", text).load(),
          "INTERPRETATIONS_RESERVED");
    }
  }

  @Test
  void reportSchemaKeyIsReserved() {
    assertOnly(
        PackFixtures.valid()
            .replace(PACK, "legs: [MARKET, CLIENT]", "legs: [MARKET, CLIENT]\n    schema: auth.xsd")
            .load(),
        "REPORT_KEY_RESERVED");
  }

  @Test
  void legVocabularyBeyondMifirIsReserved() { // BRK-12
    assertOnly(
        PackFixtures.valid().replace(PACK, "legs: [MARKET, CLIENT]", "legs: [MARKET, SIDE]").load(),
        "LEG_VOCABULARY_RESERVED");
    assertOnly(
        PackFixtures.valid().replace(TABLE_A, "legs: [MARKET]", "legs: [SIDE]").load(),
        "LEG_VOCABULARY_RESERVED");
  }

  @Test
  void validityWindowsOtherThanLegalAreReserved() { // RSV-6
    assertOnly(
        PackFixtures.valid()
            .replace(
                PACK,
                "valid: {legal: {from: 2018-01-03}}",
                "valid: {legal: {from: 2018-01-03}, supervisory: {from: 2018-01-03}}")
            .load(),
        "VALIDITY_WINDOW_RESERVED");
    assertOnly(
        PackFixtures.valid()
            .replace(
                TABLE_A, "valid: {legal: {from: 2018-01-03}}", "valid: {event: {from: 2018-01-03}}")
            .load(),
        "VALIDITY_WINDOW_RESERVED");
    assertOnly(
        PackFixtures.valid()
            .replace(
                TABLE_A,
                "valid: {legal: {from: 2018-01-03}}",
                "valid: {legal: {from: 2018-01-03}, supervisory: {from: 2018-01-03}}")
            .load(),
        "VALIDITY_WINDOW_RESERVED");
  }

  @Test
  void propertyTestsAreReserved() {
    for (String path :
        List.of(
            "tests/properties/p.yaml", "tests/properties/q.json", "tests/properties/a/b.yaml")) {
      LoadResult result = PackFixtures.valid().put(path, "a: b\n").load();
      assertOnly(result, "PROPERTY_TESTS_RESERVED"); // and not FILE_UNKNOWN
      assertThat(result.problems().get(0).path()).isEqualTo(path);
    }
  }

  @Test
  void lifecycleKeysAreChecked() { // BRK-16
    String accepted =
        """
        business_key: [a, b]
        record_key: {fields: [x]}
        trn: {policy: none}
        identifiers: []
        status_model: {states: [OPEN]}
        corrections: {}
        successor_gate: {pairs: []}
        due_at: {anchor: event.tradedAt}
        provenance: {kind: CONSISTENCY_ONLY}
        examples: []
        t_plus_1:
          anchor: event.tradedAt
          offset: P1D
        t_plus_2: {anchor: event.tradedAt, offset: P2D, on_expiry: ALERT}
        """;
    assertThat(PackFixtures.valid().put("lifecycle.yaml", accepted).load().problems()).isEmpty();

    LoadResult unknown =
        PackFixtures.valid().put("lifecycle.yaml", "business_key: [a]\nbogus: x\n").load();
    assertOnly(unknown, "LIFECYCLE_KEY_UNKNOWN");
    assertThat(unknown.problems().get(0).path()).isEqualTo("lifecycle.yaml");
    assertThat(unknown.problems().get(0).line()).isEqualTo(2);

    for (String clock :
        List.of(
            "c: {anchor: x}\n",
            "c: {offset: P1D}\n",
            "c: {anchor: x, offset: P1D, extra: y}\n",
            "c: [x]\n",
            "c: x\n")) {
      assertOnly(PackFixtures.valid().put("lifecycle.yaml", clock).load(), "LIFECYCLE_KEY_UNKNOWN");
    }
    assertOnly(PackFixtures.valid().put("lifecycle.yaml", "- a\n- b\n").load(), "LIFECYCLE_SHAPE");
  }

  @Test
  void opaqueSectionsAreStrictParsed() {
    LoadResult tab =
        PackFixtures.valid().put("lifecycle.yaml", "business_key: [a]\ndue_at:\t{x: y}\n").load();
    assertOnly(tab, "YAML_TAB");
    PackProblem problem = tab.problems().get(0);
    assertThat(problem.path()).isEqualTo("lifecycle.yaml");
    assertThat(problem.line()).isEqualTo(2);

    for (String path :
        List.of(
            "derive/d.yaml",
            "route/r.yaml",
            "enrich/e.yaml",
            "validate/v.yaml",
            "endpoints/p.yaml",
            "refdata.yaml")) {
      LoadResult anchor = PackFixtures.valid().put(path, "a: &x b\n").load();
      assertOnly(anchor, "YAML_ANCHOR");
      assertThat(anchor.problems().get(0).path()).isEqualTo(path);
      assertThat(anchor.problems().get(0).line()).isEqualTo(1);
    }
  }

  @Test
  void notInterpretedSectionsAreListed() {
    PackFixtures fixture =
        PackFixtures.valid()
            .put("derive/d.yaml", "tables: []\n")
            .put("route/r.yaml", "authority: x\n")
            .put("enrich/e.yaml", "plans: []\n")
            .put("validate/v.yaml", "rules: []\n")
            .put("endpoints/p.yaml", "profile: x\n")
            .put("lifecycle.yaml", "business_key: [a]\n")
            .put("refdata.yaml", "lists: []\n");
    LoadResult result = fixture.load();
    assertThat(result.problems()).isEmpty();
    List<String> expected = new ArrayList<>();
    for (String path :
        new TreeSet<>(
            List.of(
                "derive/d.yaml",
                "route/r.yaml",
                "enrich/e.yaml",
                "validate/v.yaml",
                "endpoints/p.yaml",
                "lifecycle.yaml",
                "refdata.yaml"))) {
      expected.add("not interpreted by this engine build: " + path);
    }
    assertThat(result.pack().orElseThrow().notes()).containsExactlyElementsOf(expected);
    // interpretations.yaml is reserved, not "not interpreted"; golden and tests are not either
    LoadResult reserved = PackFixtures.valid().put("interpretations.yaml", "pins: []\n").load();
    assertThat(reserved.pack().orElseThrow().notes()).isEmpty();
    assertThat(PackFixtures.valid().load().pack().orElseThrow().notes()).isEmpty();
  }
}
