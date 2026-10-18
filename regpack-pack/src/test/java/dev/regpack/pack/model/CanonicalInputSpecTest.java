package dev.regpack.pack.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.json.Json;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.yaml.StrictYaml;
import dev.regpack.ports.expr.ValueType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** model/canonical-input.yaml: the typed input model (companion section 2; plan task T9). */
class CanonicalInputSpecTest {
  private static final class Result {
    final Optional<CanonicalInputSpec> spec;
    final List<PackProblem> problems = new ArrayList<>();

    Result(String yaml) {
      this.spec = CanonicalInputSpec.parse(StrictYaml.parse(yaml), problems);
    }

    List<String> codes() {
      return problems.stream().map(PackProblem::code).toList();
    }
  }

  private static Result parse(String yaml) {
    return new Result(yaml);
  }

  private static String variables(String... lines) {
    return "variables:\n" + String.join("\n", lines) + "\n";
  }

  @Test
  void parsesTypesEnumsSourcesAndOptional() {
    Result r =
        parse(
            variables(
                "  event.kind: {type: ENUM, enum: LifecycleKind}",
                "  event.tradedAt: {type: TIMESTAMP}",
                "  client.lei: {type: STRING, optional: \"true\"}",
                "  trade.price: {type: DECIMAL, source: EVENT, optional: \"false\"}",
                "  flags: {type: LIST}",
                "  on: {type: BOOL}",
                "  qty: {type: INT}",
                "  day: {type: DATE}"));
    assertThat(r.problems).isEmpty();
    CanonicalInputSpec spec = r.spec.orElseThrow();
    assertThat(spec.variables()).hasSize(8);
    CanonicalInputSpec.Variable kind = spec.variables().get("event.kind");
    assertThat(kind.type()).isEqualTo(ValueType.ENUM);
    assertThat(kind.enumName()).contains("LifecycleKind");
    assertThat(kind.source()).isEqualTo(CanonicalInputSpec.Variable.Source.EVENT);
    assertThat(kind.optional()).isFalse();
    assertThat(spec.variables().get("event.tradedAt").type()).isEqualTo(ValueType.TIMESTAMP);
    assertThat(spec.variables().get("event.tradedAt").enumName()).isEmpty();
    assertThat(spec.variables().get("client.lei").optional()).isTrue();
    assertThat(spec.variables().get("trade.price").optional()).isFalse();
    assertThat(spec.variables().get("flags").type()).isEqualTo(ValueType.LIST);
    assertThat(new ArrayList<>(spec.variables().keySet())).isSorted();
  }

  @Test
  void unknownTypeIsRejected() {
    for (String type : List.of("string", "String", "FLOAT", "NULL", "ANY", "")) {
      Result r = parse(variables("  a.b: {type: '" + type + "'}"));
      assertThat(r.codes()).as(type).containsExactly("INPUT_TYPE_UNKNOWN");
      assertThat(r.spec).isEmpty();
    }
  }

  @Test
  void stateVariablesAreAccepted() {
    Result r =
        parse(
            variables(
                "  state.status: {type: STRING, source: STATE}",
                "  event.kind: {type: STRING, source: EVENT}"));
    assertThat(r.problems).isEmpty();
    assertThat(r.spec.orElseThrow().variables().get("state.status").source())
        .isEqualTo(CanonicalInputSpec.Variable.Source.STATE);
    assertThat(parse(variables("  state.status: {type: STRING, source: state}")).codes())
        .containsExactly("INPUT_SOURCE_INVALID");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"Event.kind", "a..b", "a.", ".a", "1a", "a-b", "a b", "a.B-c", "_a", "١a", "café"})
  void pathsMustBeDottedIdentifiers(String path) {
    Result r = parse(variables("  '" + path + "': {type: STRING}"));
    assertThat(r.codes()).containsExactly("INPUT_PATH_INVALID");
    assertThat(r.spec).isEmpty();
  }

  @Test
  void enumNameIsRequiredForEnumAndRefusedOtherwise() {
    assertThat(parse(variables("  a: {type: ENUM}")).codes()).containsExactly("INPUT_ENUM_INVALID");
    assertThat(parse(variables("  a: {type: STRING, enum: Kind}")).codes())
        .containsExactly("INPUT_ENUM_INVALID");
    assertThat(parse(variables("  a: {type: ENUM, enum: kind}")).codes())
        .containsExactly("INPUT_ENUM_INVALID");
  }

  @Test
  void optionalIsAnExactBooleanName() {
    assertThat(parse(variables("  a: {type: STRING, optional: yes}")).codes())
        .containsExactly("INPUT_OPTIONAL_INVALID");
    assertThat(parse(variables("  a: {type: STRING, optional: TRUE}")).codes())
        .containsExactly("INPUT_OPTIONAL_INVALID");
  }

  @Test
  void shapeAndKeysAreChecked() {
    assertThat(parse("rows: []\n").codes())
        .containsExactlyInAnyOrder("INPUT_KEY_UNKNOWN", "INPUT_KEY_MISSING");
    assertThat(parse(variables("  a: {kind: STRING}")).codes())
        .containsExactlyInAnyOrder("INPUT_KEY_UNKNOWN", "INPUT_KEY_MISSING");
    assertThat(parse(variables("  a: STRING")).codes()).containsExactly("INPUT_SHAPE");
    assertThat(parse("variables: [a]\n").codes()).containsExactly("INPUT_SHAPE");
    assertThat(parse("- a\n").codes()).containsExactly("INPUT_SHAPE");
  }

  @Test
  void problemsNameTheFileAndTheLine() {
    Result r = parse(variables("  a: {type: STRING}", "  b: {type: FLOAT}"));
    assertThat(r.problems).hasSize(1);
    assertThat(r.problems.get(0).path()).isEqualTo("model/canonical-input.yaml");
    assertThat(r.problems.get(0).line()).isEqualTo(3);
  }

  @Test
  void theTypedIrDigestIgnoresOrderAndCommentsAndChangesWithAType() {
    String one = variables("  a.b: {type: STRING}", "  c: {type: INT, optional: \"true\"}");
    String two =
        "# a comment\nvariables:\n  c: {optional: \"true\", type: INT, source: EVENT}\n"
            + "  a.b: {type: STRING}\n";
    String three = variables("  a.b: {type: STRING}", "  c: {type: DECIMAL, optional: \"true\"}");
    String four = variables("  a.b: {type: STRING}", "  c: {type: INT}");
    CanonicalInputSpec s1 = parse(one).spec.orElseThrow();
    assertThat(parse(two).spec.orElseThrow().digest()).isEqualTo(s1.digest());
    assertThat(parse(three).spec.orElseThrow().digest()).isNotEqualTo(s1.digest());
    assertThat(parse(four).spec.orElseThrow().digest()).isNotEqualTo(s1.digest());
    assertThat(Json.canonical(s1.toIr()))
        .isEqualTo(
            "{\"formatVersion\":1,\"variables\":{\"a.b\":{\"optional\":false,\"source\":\"EVENT\","
                + "\"type\":\"STRING\"},\"c\":{\"optional\":true,\"source\":\"EVENT\","
                + "\"type\":\"INT\"}}}");
  }
}
