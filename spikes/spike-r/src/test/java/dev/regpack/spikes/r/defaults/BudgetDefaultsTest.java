package dev.regpack.spikes.r.defaults;

import static dev.regpack.spikes.r.table.TestRecords.pack;
import static dev.regpack.spikes.r.table.TestRecords.rule;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.GeneratedRule;
import dev.regpack.bench.data.PackShape;
import dev.regpack.bench.data.RulePackGen;
import dev.regpack.bench.data.TradeRecord;
import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.ExecutionResult;
import dev.regpack.ports.exec.ExecutionResult.Completed;
import dev.regpack.spikes.r.defaults.BudgetDefaults.Result;
import dev.regpack.spikes.r.inhouse.InHouseExpression;
import dev.regpack.spikes.r.table.CompiledPack;
import dev.regpack.spikes.r.table.RuleTableProgram;
import dev.regpack.spikes.r.table.TableDecision;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BudgetDefaultsTest {
  private static final int MAX_LIST = 256;
  private static final int MAX_STRING = 4_096;

  private static CompiledPack compile(int rules, PackShape shape) {
    return CompiledPack.compile(RulePackGen.generate(7L, rules, shape), new InHouseExpression());
  }

  private static List<TradeRecord> clean(int count) {
    TradeRecordGen gen = new TradeRecordGen(1L);
    List<TradeRecord> out = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      out.add(gen.next());
    }
    return out;
  }

  /** A record at the declared limits: 256 underlyings and an ISIN of 4,096 code points. */
  private static TradeRecord heavy() {
    List<String> underlyings = new ArrayList<>();
    for (int i = 0; i < MAX_LIST; i++) {
      underlyings.add("U" + i);
    }
    return new TradeRecord(
        1L,
        "EX-1",
        1L,
        "SYNFIRM0000000000001",
        "XPAR",
        "NEW",
        Instant.parse("2026-10-09T09:30:00.123456Z"),
        LocalDate.of(2026, 10, 9),
        LocalDate.of(2026, 10, 11),
        "DERIVATIVE",
        "Z".repeat(MAX_STRING),
        "FFICSX",
        true,
        "LEGAL_ENTITY",
        Optional.of("C0000000000000000001"),
        new BigDecimal("100"),
        new BigDecimal("12.25"),
        "EUR",
        "BUY",
        "PRINCIPAL",
        Optional.of("TRD001"),
        underlyings);
  }

  @Test
  void budgetIsTwiceTheStaticWorstCaseAndCoversLegitimatePaths() {
    CompiledPack pack = compile(200, PackShape.S2_FULL_SCAN_WORST_CASE);
    Result result = BudgetDefaults.derive(pack, clean(2_000), MAX_LIST, MAX_STRING);

    assertThat(result.perRecordBudgetUnits()).isEqualTo(2 * result.staticWorstCaseUnits());
    assertThat(result.staticWorstCaseUnits())
        .isGreaterThanOrEqualTo(result.maxObservedCleanUnits());
    assertThat(result.maxObservedCleanUnits()).isPositive();
    assertThat(result.violations()).isZero();

    long largestNodeCount = 0;
    for (List<GeneratedRule> table :
        RulePackGen.generate(7L, 200, PackShape.S2_FULL_SCAN_WORST_CASE).tables()) {
      for (GeneratedRule rule : table) {
        InHouseExpression.Compiled compiled =
            new InHouseExpression().compile(rule.when(), Map.of());
        largestNodeCount = Math.max(largestNodeCount, compiled.nodeCount());
      }
    }
    assertThat(result.perRuleCeilingUnits()).isEqualTo(Math.max(64, 4 * largestNodeCount));

    // the budget grows with the rule count
    long at50 =
        BudgetDefaults.derive(
                compile(50, PackShape.S2_FULL_SCAN_WORST_CASE), clean(100), MAX_LIST, MAX_STRING)
            .perRecordBudgetUnits();
    long at500 =
        BudgetDefaults.derive(
                compile(500, PackShape.S2_FULL_SCAN_WORST_CASE), clean(100), MAX_LIST, MAX_STRING)
            .perRecordBudgetUnits();
    assertThat(at50).isLessThan(result.perRecordBudgetUnits());
    assertThat(result.perRecordBudgetUnits()).isLessThan(at500);
  }

  @Test
  void aRecordAtTheDeclaredLimitsStaysUnderTheDerivedBudget() {
    List<GeneratedRule> rows =
        List.of(
            rule("L1", 1, 100, "contains(instrument.underlyings, 'NOPE')", "REPORT"),
            rule("L2", 1, 200, "'NOPE' in instrument.underlyings", "REPORT"),
            rule("L3", 1, 300, "instrument.underlyings + instrument.underlyings == []", "REPORT"),
            rule("S1", 1, 400, "upper(instrument.isin) == 'Q'", "REPORT"),
            rule("S2", 1, 500, "instrument.isin + instrument.isin == 'Q'", "REPORT"),
            rule("S3", 1, 600, "instrument.isin.startsWith(instrument.isin) == false", "REPORT"),
            rule("S4", 1, 700, "size(instrument.isin) == 1", "REPORT"),
            rule("S5", 1, 800, "contains(instrument.isin, 'QQ')", "REPORT"),
            rule("CATCH", 1, 900, "true", "UNDETERMINED"));
    CompiledPack compiled =
        CompiledPack.compile(pack("limits", List.of(rows)), new InHouseExpression());

    Result result = BudgetDefaults.derive(compiled, List.of(heavy()), MAX_LIST, MAX_STRING);

    ExecutionResult<TableDecision> run =
        new MeteredExecutor()
            .execute(
                new RuleTableProgram(compiled),
                heavy(),
                new CostBudget(result.perRecordBudgetUnits()));
    assertThat(run)
        .as("the derived budget covers a legitimate worst path")
        .isInstanceOf(Completed.class);
    long used = ((Completed<TableDecision>) run).costUnits();
    assertThat(used).as("the path really pays the surcharges").isGreaterThan(256);
    assertThat(used).isLessThanOrEqualTo(result.staticWorstCaseUnits());
    assertThat(used * 2).isLessThanOrEqualTo(result.perRecordBudgetUnits());
    assertThat(result.maxObservedCleanUnits()).isEqualTo(used);
    assertThat(result.violations()).isZero();
  }

  @Test
  void cleanCorpusNeverExceedsHalfTheBudget() {
    CompiledPack pack = compile(200, PackShape.S2_FULL_SCAN_WORST_CASE);
    List<TradeRecord> records = clean(50_000);

    Result good = BudgetDefaults.derive(pack, records, MAX_LIST, MAX_STRING);
    assertThat(good.violations()).isZero();
    assertThat(good.maxObservedCleanUnits() * 2).isLessThanOrEqualTo(good.perRecordBudgetUnits());

    Result tiny =
        BudgetDefaults.deriveForBudget(pack, records.subList(0, 100), MAX_LIST, MAX_STRING, 5L);
    assertThat(tiny.perRecordBudgetUnits()).isEqualTo(5L);
    assertThat(tiny.violations()).isGreaterThanOrEqualTo(1);

    Result belowStatic =
        BudgetDefaults.deriveForBudget(
            pack, records.subList(0, 100), MAX_LIST, MAX_STRING, good.staticWorstCaseUnits() - 1);
    assertThat(belowStatic.violations()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void aCleanRecordThatFailsIsAViolation() {
    GeneratedPack failing =
        pack(
            "failing",
            List.of(
                List.of(
                    rule("BAD", 1, 100, "trade.price / dec('0') > dec('1')", "REPORT"),
                    rule("CATCH", 1, 900, "true", "UNDETERMINED"))));
    Result result =
        BudgetDefaults.derive(
            CompiledPack.compile(failing, new InHouseExpression()),
            clean(10),
            MAX_LIST,
            MAX_STRING);
    assertThat(result.violations()).isGreaterThanOrEqualTo(1);
  }
}
