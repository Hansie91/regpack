package dev.regpack.engine.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.engine.expr.Rulings;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.model.LoadedPack;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CompiledPackTest {
  private static final String REPORT = "sample.s0";
  private static final String SCOPE = "scope/sample-scope.yaml";
  private static final String HEADER_INPUTS =
      "inputs: [client.kind, event.kind, instrument.assetClass, instrument.tradedOnVenue]";

  private static CompiledPack.CompileResult compile(Map<String, String> overrides) {
    return CompiledPack.compile(SamplePacks.loadSealed(overrides), REPORT, Rulings.provisional());
  }

  private static CompiledPack.CompileResult compile(LoadedPack pack, String reportId) {
    return CompiledPack.compile(pack, reportId, Rulings.provisional());
  }

  private static Map<String, String> scope(String from, String to) {
    return Map.of(SCOPE, SamplePacks.replaced(SCOPE, from, to));
  }

  private static List<String> codes(CompiledPack.CompileResult result) {
    return result.problems().stream().map(PackProblem::code).toList();
  }

  @Test
  void compilesTheSamplePack() {
    CompiledPack.CompileResult result = compile(Map.of());
    assertThat(result.problems()).isEmpty();
    CompiledPack pack = result.pack().orElseThrow();
    assertThat(pack.regime().id()).isEqualTo("sample.s0");
    assertThat(pack.reportId()).isEqualTo(REPORT);
    assertThat(pack.asAtField()).isEqualTo("event.tradedAt");
    assertThat(pack.tables()).hasSize(1);
    CompiledPack.Table table = pack.tables().get(0);
    assertThat(table.id()).isEqualTo("sample.s0.scope");
    assertThat(table.rows())
        .extracting(CompiledPack.Row::id)
        .containsExactly(
            "S-N03-novation",
            "S-D14-natural-person",
            "S-N08-equity-on-venue",
            "S-DEFAULT-catch-all");
    assertThat(table.rows())
        .extracting(CompiledPack.Row::catchAll)
        .containsExactly(false, false, false, true);
    assertThat(pack.lastTable()).isSameAs(table);
    assertThat(table.rows().get(2).when().paths())
        .containsExactly("instrument.assetClass", "instrument.tradedOnVenue");
    assertThat(table.rows().get(0).source().outcome()).isNotNull();
  }

  @Test
  void anUnknownReportIsAProblem() {
    LoadedPack pack = SamplePacks.load();
    CompiledPack.CompileResult result = compile(pack, "sample.other");
    assertThat(result.pack()).isEmpty();
    assertThat(codes(result)).containsExactly("REPORT_UNKNOWN");
  }

  @Test
  void aWhenThatDoesNotCompileNamesTheRow() {
    CompiledPack.CompileResult result =
        compile(scope("client.kind == 'NATURAL_PERSON'", "client.kind =="));
    assertThat(result.pack()).isEmpty();
    assertThat(codes(result)).containsExactly("WHEN_DOES_NOT_COMPILE");
    assertThat(result.problems().get(0).message()).contains("S-D14-natural-person");
    assertThat(result.problems().get(0).path()).isEqualTo(SCOPE);
  }

  @Test
  void anExpressionPathThatIsNotACanonicalVariableIsRejectedOnce() {
    // the row reads a path the canonical input does not declare: one problem naming the row, and
    // no follow-up INPUT_* problems about the same table
    CompiledPack.CompileResult result =
        compile(scope("client.kind == 'NATURAL_PERSON'", "client.nosuch == 'NATURAL_PERSON'"));
    assertThat(result.pack()).isEmpty();
    assertThat(codes(result)).containsExactly("WHEN_DOES_NOT_COMPILE");
    assertThat(result.problems().get(0).message())
        .contains("S-D14-natural-person")
        .contains("undeclared");
  }

  @Test
  void aTableInputThatIsNotACanonicalVariableIsRejectedOnce() {
    CompiledPack.CompileResult result =
        compile(
            scope(
                HEADER_INPUTS,
                "inputs: [client.kind, event.kind, instrument.assetClass, instrument.tradedOnVenue, no.such]"));
    assertThat(result.pack()).isEmpty();
    assertThat(codes(result)).containsExactly("TABLE_INPUT_UNDECLARED");
    assertThat(result.problems().get(0).message()).contains("no.such");
  }

  @Test
  void inputsHeaderMustEqualTheReadPaths() {
    // a declared variable that no row reads
    CompiledPack.CompileResult unused =
        compile(
            scope(
                HEADER_INPUTS,
                "inputs: [client.kind, event.kind, instrument.assetClass, instrument.tradedOnVenue, venue.mic]"));
    assertThat(codes(unused)).containsExactly("INPUT_UNUSED");
    assertThat(unused.problems().get(0).message()).contains("venue.mic");

    // a path that a row reads and the header does not list
    CompiledPack.CompileResult undeclared =
        compile(
            scope(
                HEADER_INPUTS,
                "inputs: [client.kind, instrument.assetClass, instrument.tradedOnVenue]"));
    assertThat(codes(undeclared)).containsExactly("INPUT_UNDECLARED");
    assertThat(undeclared.problems().get(0).message()).contains("event.kind");
    assertThat(undeclared.pack()).isEmpty();
  }

  @Test
  void ruleOverCeilingWhenLimitsAreLow() {
    Map<String, String> low =
        Map.of(
            "pack.yaml",
            SamplePacks.replaced(
                "pack.yaml",
                "engine_min: 1.0.0+lib1\n",
                "engine_min: 1.0.0+lib1\nlimits: {rule_nodes: 3}\n"));
    CompiledPack.CompileResult result = compile(low);
    assertThat(result.pack()).isEmpty();
    assertThat(codes(result)).containsExactly("RULE_OVER_CEILING", "RULE_OVER_CEILING");
    assertThat(result.problems())
        .extracting(PackProblem::message)
        .anyMatch(m -> m.contains("S-N03-novation"))
        .anyMatch(m -> m.contains("S-N08-equity-on-venue"));

    // a rule far beyond 16 x rule_nodes (59 nodes against a parse cap of 48) does not compile at
    // all
    String huge = "true" + " && true".repeat(29);
    Map<String, String> beyond = new java.util.HashMap<>(low);
    beyond.put(SCOPE, SamplePacks.replaced(SCOPE, "event.kind in ['NOVATION','ASSIGNMENT']", huge));
    CompiledPack.CompileResult far = compile(beyond);
    assertThat(codes(far)).containsExactlyInAnyOrder("WHEN_DOES_NOT_COMPILE", "RULE_OVER_CEILING");
    assertThat(far.problems())
        .filteredOn(p -> p.code().equals("WHEN_DOES_NOT_COMPILE"))
        .singleElement()
        .satisfies(p -> assertThat(p.message()).contains("S-N03-novation"));
    assertThat(far.problems())
        .filteredOn(p -> p.code().equals("RULE_OVER_CEILING"))
        .singleElement()
        .satisfies(p -> assertThat(p.message()).contains("S-N08-equity-on-venue"));
  }

  @Test
  void aCompileErrorMessageIsBoundedAndEscaped() {
    String longName = "é" + "a".repeat(5_000);
    CompiledPack.CompileResult result =
        compile(scope("client.kind == 'NATURAL_PERSON'", longName + " == 1"));
    assertThat(codes(result)).containsExactly("WHEN_DOES_NOT_COMPILE");
    PackProblem problem = result.problems().get(0);
    assertThat(problem.message().length()).isLessThanOrEqualTo(PackProblem.MAX_MESSAGE_CHARS);
    assertThat(problem.message()).matches("[\\x20-\\x7E]*");
    assertThat(problem.message()).doesNotContain("a".repeat(PackProblem.MAX_MESSAGE_CHARS));
  }

  @Test
  void aReportWhoseTableIsMissingFromThePackIsAProblem() {
    LoadedPack pack = SamplePacks.load();
    LoadedPack hollow =
        new LoadedPack(
            pack.manifest(),
            pack.input(),
            Collections.emptyMap(),
            pack.reasons(),
            pack.sources(),
            pack.rationales(),
            pack.opaque(),
            pack.goldenJson(),
            Optional.empty(),
            pack.source(),
            pack.digest(),
            pack.notes());
    CompiledPack.CompileResult result = compile(hollow, REPORT);
    assertThat(codes(result)).containsExactly("REPORT_TABLE_MISSING");
  }

  @Test
  void compilationIsDeterministic() {
    CompiledPack first = compile(Map.of()).pack().orElseThrow();
    CompiledPack second = compile(Map.of()).pack().orElseThrow();
    assertThat(second.budget()).isEqualTo(first.budget());
    assertThat(second.reportId()).isEqualTo(first.reportId());
    for (int t = 0; t < first.tables().size(); t++) {
      List<CompiledPack.Row> a = first.tables().get(t).rows();
      List<CompiledPack.Row> b = second.tables().get(t).rows();
      assertThat(b)
          .extracting(CompiledPack.Row::id)
          .containsExactlyElementsOf(a.stream().map(CompiledPack.Row::id).toList());
      for (int i = 0; i < a.size(); i++) {
        assertThat(b.get(i).when().nodeCount()).isEqualTo(a.get(i).when().nodeCount());
        assertThat(b.get(i).when().staticWorstCaseUnits())
            .isEqualTo(a.get(i).when().staticWorstCaseUnits());
        assertThat(b.get(i).when().paths()).isEqualTo(a.get(i).when().paths());
      }
    }
    Map<String, String> broken = scope("client.kind == 'NATURAL_PERSON'", "client.kind ==");
    assertThat(compile(broken).problems()).isEqualTo(compile(broken).problems());
  }

  @Test
  void theCompiledPackIsImmutable() {
    CompiledPack pack = compile(Map.of()).pack().orElseThrow();
    assertThatThrownBy(() -> pack.tables().add(pack.lastTable()))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> pack.tables().get(0).rows().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
