package dev.regpack.engine.pack;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.engine.expr.ErrorKind;
import dev.regpack.engine.expr.Rulings;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.ExecutionResult;
import dev.regpack.ports.exec.ExecutionResult.BudgetExhausted;
import dev.regpack.ports.exec.ExecutionResult.Completed;
import dev.regpack.ports.exec.ExecutionResult.Failed;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The derive program: rows to text values (plan W1a, task T5, decision D-W5). */
class DeriveProgramTest {
  private static final MeteredExecutor EXECUTOR = new MeteredExecutor();

  private static CompiledPack compiled(LoadedPack pack) {
    return CompiledPack.compile(pack, DeriveFixture.REPORT, Rulings.load()).pack().orElseThrow();
  }

  private static DeriveProgram program(LoadedPack pack) {
    return new DeriveProgram(compiled(pack));
  }

  private static ExecutionResult<List<DeriveProgram.Derived>> run(
      DeriveProgram program, CanonicalExecution execution, long units) {
    return EXECUTOR.execute(program, new CanonicalBindings(execution), new CostBudget(units));
  }

  private static Map<String, String> valuesOf(ExecutionResult<List<DeriveProgram.Derived>> result) {
    assertThat(result).isInstanceOf(Completed.class);
    Map<String, String> values = new LinkedHashMap<>();
    for (DeriveProgram.Derived d : ((Completed<List<DeriveProgram.Derived>>) result).value()) {
      values.put(d.field(), d.value());
    }
    return values;
  }

  /**
   * A derive file with the given rows (id, field, when, value); the cite and rule_ref are fixed.
   */
  private static Map<String, String> derive(String... idFieldWhenValue) {
    StringBuilder text = new StringBuilder("table: {id: derive.fixture.report}\nrows:\n");
    for (int i = 0; i < idFieldWhenValue.length; i += 4) {
      text.append("  - {id: ")
          .append(idFieldWhenValue[i])
          .append(", field: ")
          .append(idFieldWhenValue[i + 1])
          .append(", when: \"")
          .append(idFieldWhenValue[i + 2])
          .append("\", value: \"")
          .append(idFieldWhenValue[i + 3])
          .append("\", rule_ref: DR-")
          .append(i / 4 + 1)
          .append(", cite: [{source_id: fixture-law, provision_path: fixture/1, role: BASIS}]}\n");
    }
    return Map.of(DeriveFixture.DERIVE, text.toString());
  }

  @Test
  void rendersDecimalInstantAndEnumAsStrings() {
    // HALF_EVEN: 12.345 at two places is 12.34 (the tie goes to the even digit), 12.355 is 12.36
    DeriveProgram program =
        program(
            DeriveFixture.loadSealed(
                derive(
                    "D-PRICE",
                    "price",
                    "true",
                    "string(round(trade.price, 2, 'HALF_EVEN'))",
                    "D-RAW",
                    "raw_price",
                    "true",
                    "trade.price",
                    "D-QTY",
                    "quantity",
                    "true",
                    "trade.quantity",
                    "D-TIME",
                    "time",
                    "true",
                    "isoMicros(event.tradedAt)",
                    "D-TIMEBARE",
                    "time_bare",
                    "true",
                    "event.tradedAt",
                    "D-SIDE",
                    "side",
                    "true",
                    "trade.side",
                    "D-KIND",
                    "kind",
                    "true",
                    "event.kind",
                    "D-BOOL",
                    "on_venue",
                    "true",
                    "instrument.tradedOnVenue",
                    "D-LIT",
                    "literal",
                    "true",
                    "'false'")));

    Map<String, String> low =
        valuesOf(
            run(
                program,
                DeriveFixture.execution(
                    LifecycleKind.NEW, new BigDecimal("12.345"), Optional.empty()),
                10_000));
    Map<String, String> high =
        valuesOf(
            run(
                program,
                DeriveFixture.execution(
                    LifecycleKind.NEW, new BigDecimal("12.355"), Optional.empty()),
                10_000));

    assertThat(low.get("price")).isEqualTo("12.34");
    assertThat(high.get("price")).isEqualTo("12.36");
    assertThat(low.get("raw_price")).as("a decimal is plain text").isEqualTo("12.345");
    assertThat(low.get("quantity")).as("trailing zeros are dropped").isEqualTo("100.5");
    assertThat(low.get("time")).isEqualTo("2026-10-30T09:30:00.123456Z");
    assertThat(low.get("time_bare"))
        .as("an instant keeps six digits")
        .isEqualTo("2026-10-30T09:30:00.123456Z");
    assertThat(low.get("side")).as("an enumeration by name").isEqualTo("BUY");
    assertThat(low.get("kind")).isEqualTo("NEW");
    assertThat(low.get("on_venue")).as("a bool is true or false").isEqualTo("true");
    assertThat(low.get("literal")).isEqualTo("false");
    assertThat(low.keySet())
        .as("one value per row, in row order")
        .containsExactly(
            "price",
            "raw_price",
            "quantity",
            "time",
            "time_bare",
            "side",
            "kind",
            "on_venue",
            "literal");
  }

  @Test
  void aTimeOfWholeSecondsStillKeepsSixDigits() {
    DeriveProgram program =
        program(
            DeriveFixture.loadSealed(
                derive("D-TIME", "time", "true", "isoMicros(event.tradedAt)")));
    CanonicalExecution base = DeriveFixture.newExecution();
    CanonicalExecution wholeSeconds =
        new CanonicalExecution(
            base.firmLei(),
            base.venueMic(),
            base.venueExecutionId(),
            base.eventKind(),
            java.time.Instant.parse("2026-10-30T09:30:00Z"),
            base.assetClass(),
            base.isin(),
            base.tradedOnVenue(),
            base.clientKind(),
            base.clientLei(),
            base.quantity(),
            base.price(),
            base.currency());

    assertThat(valuesOf(run(program, wholeSeconds, 10_000)).get("time"))
        .isEqualTo("2026-10-30T09:30:00.000000Z");
  }

  @Test
  void aFalseWhenOmitsTheField() {
    DeriveProgram program = program(DeriveFixture.load());

    Map<String, String> onNew = valuesOf(run(program, DeriveFixture.newExecution(), 10_000));
    Map<String, String> onCancel =
        valuesOf(
            run(
                program,
                DeriveFixture.execution(
                    LifecycleKind.CANCEL, new BigDecimal("12.25"), Optional.of("ALGO-1")),
                10_000));
    Map<String, String> noAlgorithm =
        valuesOf(
            run(
                program,
                DeriveFixture.execution(
                    LifecycleKind.NEW, new BigDecimal("12.25"), Optional.empty()),
                10_000));

    assertThat(onNew.keySet())
        .containsExactly(
            "executing_lei",
            "buyer",
            "seller",
            "trading_datetime",
            "quantity",
            "price",
            "venue",
            "on_venue",
            "side",
            "execution_within_firm",
            "transmission");
    assertThat(onNew)
        .containsEntry("executing_lei", "LEI-FIRM-0001")
        .containsEntry("buyer", "LEI-CLIENT-0001")
        .containsEntry("seller", "LEI-CCP-0001")
        .containsEntry("quantity", "100.5")
        .containsEntry("price", "12.25")
        .containsEntry("venue", "XPAR")
        .containsEntry("execution_within_firm", "ALGO-1")
        .containsEntry("transmission", "false");
    assertThat(onCancel.keySet())
        .as("the field list lacks the field of every row whose when is false")
        .containsExactly("executing_lei");
    assertThat(noAlgorithm.keySet())
        .doesNotContain("execution_within_firm")
        .contains("transmission");
  }

  @Test
  void anAbsentOptionalPathWithoutHasIsAnError() {
    LoadedPack pack =
        DeriveFixture.loadSealed(
            derive(
                "D-OK",
                "executing_lei",
                "true",
                "firm.lei",
                "D-CP",
                "counterparty",
                "true",
                "trade.counterparty"));
    DeriveProgram program = program(pack);

    ExecutionResult<List<DeriveProgram.Derived>> present =
        run(program, DeriveFixture.newExecution(), 10_000);
    DeriveProgram failing = program(pack);
    ExecutionResult<List<DeriveProgram.Derived>> absent =
        run(failing, DeriveFixture.withoutCounterparty(), 10_000);

    assertThat(valuesOf(present)).containsEntry("counterparty", "LEI-CCP-0001");
    assertThat(absent).isInstanceOf(Failed.class);
    Failed<List<DeriveProgram.Derived>> failed = (Failed<List<DeriveProgram.Derived>>) absent;
    assertThat(failed.ruleId()).as("the row that failed").isEqualTo("D-CP");
    assertThat(failing.lastErrorKind()).contains(ErrorKind.ABSENT);
  }

  @Test
  void aWhenThatIsNotABoolIsAnErrorAndNotAQuietMiss() {
    DeriveProgram program =
        program(DeriveFixture.loadSealed(derive("D-BAD", "a_field", "firm.lei", "firm.lei")));

    ExecutionResult<List<DeriveProgram.Derived>> result =
        run(program, DeriveFixture.newExecution(), 10_000);

    assertThat(result).isInstanceOf(Failed.class);
    assertThat(((Failed<List<DeriveProgram.Derived>>) result).ruleId()).isEqualTo("D-BAD");
    assertThat(program.lastErrorKind()).contains(ErrorKind.TYPE);
  }

  @Test
  void aValueThatIsAListHasNoStringFormAndFails() {
    DeriveProgram program =
        program(DeriveFixture.loadSealed(derive("D-LIST", "a_field", "true", "['a', 'b']")));

    ExecutionResult<List<DeriveProgram.Derived>> result =
        run(program, DeriveFixture.newExecution(), 10_000);

    assertThat(result).isInstanceOf(Failed.class);
    assertThat(program.lastErrorKind()).contains(ErrorKind.TYPE);
  }

  @Test
  void theCostMeterIsCharged() {
    DeriveProgram program = program(DeriveFixture.load());
    CanonicalExecution event = DeriveFixture.newExecution();

    ExecutionResult<List<DeriveProgram.Derived>> first = run(program, event, 10_000);
    ExecutionResult<List<DeriveProgram.Derived>> again = run(program, event, 10_000);
    ExecutionResult<List<DeriveProgram.Derived>> tiny = run(program, event, 1);

    long units = ((Completed<List<DeriveProgram.Derived>>) first).costUnits();
    assertThat(units).as("every row charges at least its own unit").isGreaterThan(11);
    assertThat(((Completed<List<DeriveProgram.Derived>>) again).costUnits())
        .as("cost units are deterministic")
        .isEqualTo(units);
    assertThat(run(program, event, units))
        .as("exactly the cost fits")
        .isInstanceOf(Completed.class);
    ExecutionResult<List<DeriveProgram.Derived>> short1 = run(program, event, units - 1);
    assertThat(short1).isInstanceOf(BudgetExhausted.class);
    assertThat(tiny).isInstanceOf(BudgetExhausted.class);
    assertThat(((BudgetExhausted<List<DeriveProgram.Derived>>) tiny).ruleId()).isEqualTo("D-EXEC");
  }
}
