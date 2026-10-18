package dev.regpack.engine.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.engine.expr.Rulings;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.Regime;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.model.CanonicalInputSpec;
import dev.regpack.pack.model.LoadResult;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.pack.model.PackLoader;
import dev.regpack.pack.model.TableRow;
import dev.regpack.pack.source.PackSource;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.ExecutionResult;
import dev.regpack.ports.exec.ExecutionResult.Completed;
import dev.regpack.ports.exec.ExecutionResult.Failed;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.StringValue;
import dev.regpack.ports.expr.Value.TimestampValue;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Evidence for review focus R7 and invariant I4 (regulation is data): a second regime, written as
 * data only, runs through the same engine classes without a change to them. The pack {@code
 * emir.eu} is synthetic. It is written by hand from the SP-G paper exercise (the action-type table
 * and its seven examples) and carries no regulatory text: the citations name a synthetic {@code
 * fixture-law}, and every case is consistency-only while gate G1 is open.
 *
 * <p>The fixture needs three things the first regime does not: a ledger-state input ({@code
 * state.status}, BRK-04), a value stored on the decision ({@code set}, BRK-10) and its own reason
 * prefix and node ({@code EMIR}, {@code ACT}; BRK-11). REPORT rows use the default leg {@code
 * MARKET} as a stand-in for the EMIR {@code SIDE} leg, which is reserved (BRK-12; P3 and P12).
 */
class SecondRegimeFixtureTest {
  private static final String REPORT = "emir.eu";
  private static final Instant OCCURRED_AT = Instant.parse("2026-10-09T09:30:00Z");

  /** A map-based binding of the paths the action-type table reads. */
  private static Bindings bindings(String kind, String status) {
    Map<String, Value> values = new HashMap<>();
    values.put("event.kind", new StringValue(kind));
    values.put("event.occurredAt", new TimestampValue(OCCURRED_AT));
    if (status != null) {
      values.put("state.status", new StringValue(status));
    }
    return path -> Optional.ofNullable(values.get(path));
  }

  private static PackSource source() {
    URL url = SecondRegimeFixtureTest.class.getResource("/packs/second-regime");
    assertThat(url).as("the fixture pack is on the test class path").isNotNull();
    try {
      return PackSource.fromDirectory(Path.of(url.toURI()));
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  private static LoadedPack load() {
    LoadResult result = PackLoader.load(source());
    List<String> problems = new ArrayList<>();
    for (PackProblem p : result.problems()) {
      problems.add(p.code() + " " + p.path() + ": " + p.message());
    }
    assertThat(problems).as("problems of the second-regime pack").isEmpty();
    return result.pack().orElseThrow();
  }

  private static CompiledPack compile(LoadedPack pack) {
    return CompiledPack.compile(pack, REPORT, Rulings.load()).pack().orElseThrow();
  }

  private static ExecutionResult<PackProgram.Decision> run(Bindings input) {
    return new MeteredExecutor()
        .execute(new PackProgram(compile(load()), false), input, new CostBudget(1_000));
  }

  private static TableRow decided(ExecutionResult<PackProgram.Decision> result) {
    assertThat(result).isInstanceOf(Completed.class);
    return ((Completed<PackProgram.Decision>) result).value().row();
  }

  /** One SP-G example: the input, the deciding row, the outcome and the stored action type. */
  private record Example(
      String id, String kind, String status, String row, Outcome outcome, String actionType) {}

  private static final List<Example> EXAMPLES =
      List.of(
          new Example("ACT-EX-01", "NEW", "NOT_REPORTED", "D-ACT-01", Outcome.REPORT, "NEWT"),
          new Example("ACT-EX-02", "AMEND", "OUTSTANDING", "D-ACT-02", Outcome.REPORT, "MODI"),
          new Example("ACT-EX-03", "TERMINATE", "OUTSTANDING", "D-ACT-03", Outcome.REPORT, "TERM"),
          new Example("ACT-EX-04", "CANCEL", "TERMINATED", "D-ACT-04", Outcome.REPORT, "EROR"),
          new Example("ACT-EX-05", "REVIVE", "ERRORED", "D-ACT-05", Outcome.REPORT, "REVI"),
          new Example("ACT-EX-06", "VALUATION", "OUTSTANDING", "D-ACT-06", Outcome.REPORT, "VALU"),
          new Example("ACT-EX-07", "AMEND", "ERRORED", "D-ACT-99", Outcome.UNDETERMINED, null));

  @Test
  void theEmirActionTypeTableRunsOnTheSameEngine() {
    LoadedPack pack = load();
    CompiledPack compiled = compile(pack);
    assertThat(compiled.regime()).isEqualTo(new Regime(REPORT));
    assertThat(compiled.tables()).hasSize(1);
    for (Example example : EXAMPLES) {
      ExecutionResult<PackProgram.Decision> result =
          new MeteredExecutor()
              .execute(
                  new PackProgram(compiled, false),
                  bindings(example.kind(), example.status()),
                  new CostBudget(1_000));
      TableRow row = decided(result);
      assertThat(row.id()).as(example.id()).isEqualTo(example.row());
      assertThat(row.outcome()).as(example.id()).isEqualTo(example.outcome());
    }
  }

  @Test
  void attributesCarryTheActionType() {
    for (Example example : EXAMPLES) {
      TableRow row = decided(run(bindings(example.kind(), example.status())));
      if (example.actionType() == null) {
        assertThat(row.set()).as(example.id()).isEmpty();
      } else {
        assertThat(row.set())
            .as(example.id())
            .isEqualTo(Map.of("action_type", example.actionType()));
        assertThat(row.legs())
            .as("default leg MARKET stands in for the reserved SIDE leg")
            .containsExactly(Leg.MARKET);
      }
    }
  }

  @Test
  void stateInputIsReadThroughBindings() {
    LoadedPack pack = load();
    assertThat(pack.input().variables().get("state.status").source())
        .isEqualTo(CanonicalInputSpec.Variable.Source.STATE);

    // The same event kind decides differently with another status: the status is a real input.
    assertThat(decided(run(bindings("NEW", "NOT_REPORTED"))).id()).isEqualTo("D-ACT-01");
    TableRow duplicate = decided(run(bindings("NEW", "OUTSTANDING")));
    assertThat(duplicate.id()).isEqualTo("D-ACT-08");
    assertThat(duplicate.outcome()).isEqualTo(Outcome.UNDETERMINED);
    assertThat(decided(run(bindings("VALUATION", "TERMINATED"))).outcome())
        .isEqualTo(Outcome.DO_NOT_REPORT);

    // A status the ledger did not supply is explicit absence, never a quiet miss (I5).
    ExecutionResult<PackProgram.Decision> missing = run(bindings("NEW", null));
    assertThat(missing).isInstanceOf(Failed.class);
    assertThat(((Failed<PackProgram.Decision>) missing).ruleId()).isEqualTo("D-ACT-01");
  }

  @Test
  void theRegimeProviderBindsOnlyTheInputOfThisEngineBuild() {
    // PackRegime reads the flattened execution of P0 through CanonicalBindings; a ledger-state
    // input
    // stays reserved until P3 and P12 (BRK-04), so the provider refuses the pack instead of
    // guessing. The tables themselves run unchanged, as the tests above show.
    assertThatThrownBy(
            () -> PackRegime.create(load(), REPORT, new MeteredExecutor(), Rulings.load()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("INPUT_PATH_UNSUPPORTED");
  }

  @Test
  void reasonPrefixAndNodesComeFromThePack() {
    LoadedPack pack = load();
    assertThat(pack.reasons().prefix()).isEqualTo("EMIR");
    assertThat(pack.reasons().nodes()).containsExactly("ACT");
    for (Example example : EXAMPLES) {
      TableRow row = decided(run(bindings(example.kind(), example.status())));
      assertThat(row.node()).isEqualTo("ACT");
      assertThat(row.reason().value()).startsWith("EMIR.ACT.");
      assertThat(pack.reasons().entries()).containsKey(row.reason().value());
    }
  }

  @Test
  void theCatchAllIsUndeterminedWithAReason() {
    TableRow row = decided(run(bindings("AMEND", "ERRORED")));
    assertThat(row.id()).isEqualTo("D-ACT-99");
    assertThat(row.outcome()).isEqualTo(Outcome.UNDETERMINED);
    assertThat(row.reason()).isEqualTo(new ReasonCode("EMIR.ACT.NO_MATCH"));
    assertThat(row.cite()).isNotEmpty();
    // A negative is still a decision with a reason (I5): a valuation of a derivative that is not
    // outstanding is DO_NOT_REPORT by its own row, and a second NEW is a data-quality question.
    assertThat(decided(run(bindings("VALUATION", "ERRORED"))).outcome())
        .isEqualTo(Outcome.DO_NOT_REPORT);
    assertThat(decided(run(bindings("NEW", "TERMINATED"))).outcome())
        .isEqualTo(Outcome.UNDETERMINED);
  }
}
