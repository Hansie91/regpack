package dev.regpack.engine.pack;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.engine.expr.ErrorKind;
import dev.regpack.engine.expr.Rulings;
import dev.regpack.model.canonical.AssetClass;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.model.canonical.PartyKind;
import dev.regpack.model.decision.PathStep;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.ExecutionResult;
import dev.regpack.ports.exec.ExecutionResult.Completed;
import dev.regpack.ports.exec.ExecutionResult.Failed;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.Value.StringValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PackProgramTest {
  private static final String REPORT = "sample.s0";
  private static final Instant TRADED_AT = Instant.parse("2026-10-09T09:30:00.123456Z");

  private static CanonicalExecution execution(
      Instant tradedAt, AssetClass assetClass, PartyKind clientKind) {
    return new CanonicalExecution(
        "LEI-FIRM-0001",
        "XPAR",
        "EX-1",
        LifecycleKind.NEW,
        tradedAt,
        assetClass,
        "ZZ0000000001",
        true,
        clientKind,
        clientKind == PartyKind.LEGAL_ENTITY ? Optional.of("LEI-CLIENT-0001") : Optional.empty(),
        new BigDecimal("100"),
        new BigDecimal("12.25"),
        "EUR");
  }

  private static CanonicalExecution equity(Instant tradedAt) {
    return execution(tradedAt, AssetClass.EQUITY, PartyKind.LEGAL_ENTITY);
  }

  private static CompiledPack compile(LoadedPack pack) {
    return CompiledPack.compile(pack, REPORT, Rulings.provisional()).pack().orElseThrow();
  }

  private static ExecutionResult<PackProgram.Decision> run(
      PackProgram program, Bindings input, long budget) {
    return new MeteredExecutor().execute(program, input, new CostBudget(budget));
  }

  private static ExecutionResult<PackProgram.Decision> run(
      LoadedPack pack, CanonicalExecution event) {
    return run(new PackProgram(compile(pack), false), new CanonicalBindings(event), 1_000);
  }

  private static PackProgram.Decision decision(ExecutionResult<PackProgram.Decision> result) {
    assertThat(result).isInstanceOf(Completed.class);
    return ((Completed<PackProgram.Decision>) result).value();
  }

  private static List<PathStep> path(ExecutionResult<PackProgram.Decision> result) {
    return ((Completed<PackProgram.Decision>) result).path();
  }

  @Test
  void validityWindowSelectsRowsByAsAt() {
    Instant lastBefore = Instant.parse("2026-10-09T23:59:59.999999Z");
    Instant firstDay = Instant.parse("2026-10-10T00:00:00Z");

    LoadedPack opens =
        PackBuilder.sample()
            .scope(PackBuilder.Rule.of("R-OPENS", "event.kind == 'NEW'").validFrom("2026-10-10"))
            .load();
    ExecutionResult<PackProgram.Decision> before = run(opens, equity(lastBefore));
    assertThat(decision(before).row().id()).isEqualTo("S-DEFAULT-catch-all");
    // a skipped row leaves no path step and costs nothing: only the catch-all (1 + `true`) is paid
    assertThat(path(before)).containsExactly(new PathStep("S-DEFAULT-catch-all", true, 2));
    assertThat(((Completed<PackProgram.Decision>) before).costUnits()).isEqualTo(2);
    ExecutionResult<PackProgram.Decision> from = run(opens, equity(firstDay));
    assertThat(decision(from).row().id()).isEqualTo("R-OPENS");

    LoadedPack closes =
        PackBuilder.sample()
            .scope(PackBuilder.Rule.of("R-CLOSES", "event.kind == 'NEW'").validTo("2026-10-10"))
            .load();
    assertThat(decision(run(closes, equity(lastBefore))).row().id()).isEqualTo("R-CLOSES");
    ExecutionResult<PackProgram.Decision> after = run(closes, equity(firstDay));
    assertThat(decision(after).row().id()).isEqualTo("S-DEFAULT-catch-all");
    assertThat(path(after)).containsExactly(new PathStep("S-DEFAULT-catch-all", true, 2));
  }

  @Test
  void aMissingAsAtFieldIsExecError() {
    PackProgram absent = new PackProgram(compile(SamplePacks.load()), false);
    ExecutionResult<PackProgram.Decision> result = run(absent, path -> Optional.empty(), 100);

    assertThat(result).isInstanceOf(Failed.class);
    assertThat(((Failed<PackProgram.Decision>) result).errorClass()).isEqualTo("ExprException");
    assertThat(absent.lastErrorKind()).contains(ErrorKind.ABSENT);
    assertThat(((Failed<PackProgram.Decision>) result).path()).isEmpty();

    PackProgram wrongType = new PackProgram(compile(SamplePacks.load()), false);
    ExecutionResult<PackProgram.Decision> typed =
        run(wrongType, path -> Optional.of(new StringValue("2026-10-09")), 100);
    assertThat(typed).isInstanceOf(Failed.class);
    assertThat(wrongType.lastErrorKind()).contains(ErrorKind.TYPE);
  }

  @Test
  void tableChainRule() {
    PackBuilder.Rule debt = PackBuilder.Rule.of("R-FIRST", "instrument.assetClass == 'DEBT'");
    PackBuilder.Rule firstEquity =
        PackBuilder.Rule.of("R-ONE", "instrument.assetClass == 'EQUITY'");
    PackBuilder.Rule equityRule =
        PackBuilder.Rule.of("R-SECOND", "instrument.assetClass == 'EQUITY'");
    PackBuilder.Rule alsoDebt = PackBuilder.Rule.of("R-SECOND", "instrument.assetClass == 'DEBT'");

    // a first-table hit decides
    LoadedPack firstHits = PackBuilder.sample().chain(List.of(firstEquity), List.of(debt)).load();
    PackProgram.Decision first = decision(run(firstHits, equity(TRADED_AT)));
    assertThat(first.row().id()).isEqualTo("R-ONE");
    assertThat(first.tableId()).isEqualTo("sample.s0.scope");

    // a first-table catch-all falls through to the second table
    LoadedPack falls = PackBuilder.sample().chain(List.of(debt), List.of(equityRule)).load();
    ExecutionResult<PackProgram.Decision> second = run(falls, equity(TRADED_AT));
    assertThat(decision(second).row().id()).isEqualTo("R-SECOND");
    assertThat(decision(second).tableId()).isEqualTo("sample.s0.scope2");
    assertThat(path(second))
        .extracting(PathStep::ruleId)
        .containsExactly("R-FIRST", "S-DEFAULT-catch-all", "R-SECOND");

    // both end in their catch-all: UNDETERMINED from the last catch-all's row
    LoadedPack both = PackBuilder.sample().chain(List.of(debt), List.of(alsoDebt)).load();
    ExecutionResult<PackProgram.Decision> none = run(both, equity(TRADED_AT));
    assertThat(decision(none).row().id()).isEqualTo("sample.s0.scope2-catch-all");
    assertThat(decision(none).tableId()).isEqualTo("sample.s0.scope2");
    assertThat(decision(none).row().outcome().name()).isEqualTo("UNDETERMINED");
  }

  @Test
  void anErrorNamesTheRowAndRemembersItsKind() {
    LoadedPack pack =
        PackBuilder.sample().scope(PackBuilder.Rule.of("R-LEI", "client.lei == 'X'")).load();
    PackProgram program = new PackProgram(compile(pack), false);

    ExecutionResult<PackProgram.Decision> result =
        run(
            program,
            new CanonicalBindings(
                execution(TRADED_AT, AssetClass.EQUITY, PartyKind.NATURAL_PERSON)),
            100);

    assertThat(result).isInstanceOf(Failed.class);
    Failed<PackProgram.Decision> failed = (Failed<PackProgram.Decision>) result;
    assertThat(failed.ruleId()).isEqualTo("R-LEI");
    assertThat(failed.errorClass()).isEqualTo("ExprException");
    assertThat(program.lastErrorKind()).contains(ErrorKind.ABSENT);
    assertThat(failed.path()).isEmpty();
  }

  @Test
  void fullTraceListsEveryEvaluatedRow() {
    LoadedPack pack = SamplePacks.load();
    PackProgram traced = new PackProgram(compile(pack), true);
    run(traced, new CanonicalBindings(equity(TRADED_AT)), 100);

    assertThat(traced.rowTraces())
        .extracting(PackProgram.RowTrace::ruleId)
        .containsExactly("S-N03-novation", "S-D14-natural-person", "S-N08-equity-on-venue");
    assertThat(traced.rowTraces())
        .extracting(PackProgram.RowTrace::matched)
        .containsExactly(false, false, true);
    assertThat(traced.rowTraces())
        .extracting(PackProgram.RowTrace::units)
        .containsExactly(8L, 4L, 6L);
    assertThat(traced.rowTraces().get(1).when()).isEqualTo("client.kind == 'NATURAL_PERSON'");
    assertThat(traced.rowTraces()).allMatch(t -> t.error().isEmpty());

    PackProgram plain = new PackProgram(compile(pack), false);
    run(plain, new CanonicalBindings(equity(TRADED_AT)), 100);
    assertThat(plain.rowTraces()).isEmpty();

    LoadedPack failing =
        PackBuilder.sample().scope(PackBuilder.Rule.of("R-LEI", "client.lei == 'X'")).load();
    PackProgram erroring = new PackProgram(compile(failing), true);
    run(
        erroring,
        new CanonicalBindings(execution(TRADED_AT, AssetClass.EQUITY, PartyKind.NATURAL_PERSON)),
        100);
    assertThat(erroring.rowTraces()).hasSize(1);
    assertThat(erroring.rowTraces().get(0).error()).contains(ErrorKind.ABSENT);
    assertThat(erroring.rowTraces().get(0).matched()).isFalse();
  }
}
