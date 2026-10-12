package dev.regpack.bench.consolidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.regpack.bench.consolidate.LatencyBudget.Basis;
import dev.regpack.bench.consolidate.LatencyBudget.Interval;
import dev.regpack.bench.consolidate.LatencyBudget.Result;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LatencyBudgetTest {
  private static final int N = 5000;
  private static final double BURST = 3470;
  private static final double MARGIN_HOURS = 4;
  private static final int THREADS = 4;

  /**
   * What the shared ledger figure is: pooled over the burst runs, and L-2 is INCONCLUSIVE on it.
   */
  private static final String POOLED_CLAUSE =
      "pooled over all batches of the cache-hot runs under the burst (SP-L runs A1 and A2);"
          + " L-2 on this quantity is INCONCLUSIVE (see results.md)";

  /** The loaded-lane remark: a figure derived from the SP-X report, not quoted from it. */
  private static final String DERIVED_REMARK =
      "about 1.9 times as long with four lanes busy (derived from the SP-X report: about 1.3 s"
          + " against about 0.7 s for a file of 10,000 records)";

  private static Map<String, Map<String, Object>> decisions() {
    Map<String, Object> sr = new LinkedHashMap<>();
    sr.put("decide.usPerRecordWorst", new BigDecimal("17.902"));
    Map<String, Object> readings = new LinkedHashMap<>();
    readings.put("t4S2", new BigDecimal("27795.748757954"));
    readings.put("t1S2", new BigDecimal("55859.969050994"));
    sr.put("executorGateReadings", readings);
    Map<String, Object> sl = new LinkedHashMap<>();
    sl.put("ledger.ingestCommitP99Ms", new BigDecimal("9582.423"));
    sl.put("ledger.commitP99Ms", new BigDecimal("9582.423"));
    sl.put("ledger.peakBacklogSeconds", new BigDecimal("30.259"));
    sl.put("ledger.openLoopP99Ms", new BigDecimal("24183.532"));
    Map<String, Object> sx = new LinkedHashMap<>();
    sx.put("assemble.latencyMs.1000", new BigDecimal("104.02"));
    sx.put("assemble.latencyMs.5000", new BigDecimal("401.137"));
    sx.put("assemble.latencyMs.25000", new BigDecimal("1576.724"));
    sx.put("assemble.latencyMs.100000", new BigDecimal("5866.03"));
    sx.put("assemble.latencyMs.ALL.5000", new BigDecimal("401.137"));
    sx.put("assemble.latencyMs.SAMPLED_10.5000", new BigDecimal("324.974"));
    Map<String, Map<String, Object>> all = new LinkedHashMap<>();
    all.put("SP-R", sr);
    all.put("SP-L", sl);
    all.put("SP-X", sx);
    return all;
  }

  private static Result budget(Map<String, Map<String, Object>> d) {
    return LatencyBudget.assemble(d, N, BURST, MARGIN_HOURS, THREADS);
  }

  private static double fill() {
    return N / BURST * 1000.0;
  }

  @Test
  void everyOfTheNineStagePointsIsPresent() {
    Result result = budget(decisions());
    assertThat(result.intervals()).hasSize(8);
    List<String> points =
        List.of(
            "arrival",
            "landed",
            "enriched",
            "decided",
            "validated",
            "versioned",
            "assembled",
            "sealed",
            "sent");
    for (int i = 0; i < 8; i++) {
      assertThat(result.intervals().get(i).from()).isEqualTo(points.get(i));
      assertThat(result.intervals().get(i).to()).isEqualTo(points.get(i + 1));
    }
  }

  @Test
  void unmeasuredStagesArePendingAndTheVerdictIsNeverFits() {
    Result result = budget(decisions());
    assertThat(result.pending()).hasSize(4);
    assertThat(result.pending()).allMatch(i -> i.basis() == Basis.PENDING);
    assertThat(result.pending()).allMatch(i -> i.p99Millis().isEmpty());
    assertThat(result.pending())
        .extracting(i -> i.from() + " to " + i.to())
        .containsExactly(
            "landed to enriched", "decided to validated", "assembled to sealed", "sealed to sent");
    assertThat(result.pending())
        .extracting(Interval::owner)
        .containsExactly(
            "SP-N (P1b)", "P5 (validation layers; no spike)", "SP-W (P1b)", "SP-S (P1b)");
    assertThat(result.verdict()).isEqualTo(LatencyBudget.Verdict.PARTIAL);
    assertThat(LatencyBudget.Verdict.values()).containsExactly(LatencyBudget.Verdict.PARTIAL);

    // however small the measured numbers are, the verdict stays PARTIAL
    Map<String, Map<String, Object>> tiny = decisions();
    tiny.get("SP-L").put("ledger.ingestCommitP99Ms", new BigDecimal("0.001"));
    tiny.get("SP-L").put("ledger.commitP99Ms", new BigDecimal("0.001"));
    Result small = LatencyBudget.assemble(tiny, N, BURST, 1000, THREADS);
    assertThat(small.measuredP99SumMillis()).isLessThan(small.marginMillis());
    assertThat(small.verdict()).isEqualTo(LatencyBudget.Verdict.PARTIAL);
    assertThat(small.pending()).hasSize(4);
  }

  @Test
  void missingOrNotMeasuredKeyBecomesPendingNotZero() {
    Map<String, Map<String, Object>> d = decisions();
    d.get("SP-R").remove("decide.usPerRecordWorst");
    d.get("SP-L").put("ledger.commitP99Ms", "NOT_MEASURED");
    d.get("SP-X").keySet().removeIf(k -> k.startsWith("assemble.latencyMs."));
    Result result = budget(d);
    Interval decided = result.intervals().get(2);
    Interval versioned = result.intervals().get(4);
    Interval assembled = result.intervals().get(5);
    for (Interval i : List.of(decided, versioned, assembled)) {
      assertThat(i.basis()).isEqualTo(Basis.PENDING);
      assertThat(i.p99Millis()).isEmpty();
    }
    assertThat(decided.owner()).isEqualTo("SP-R");
    assertThat(versioned.owner()).isEqualTo("SP-L");
    assertThat(assembled.owner()).isEqualTo("SP-X");
    assertThat(result.pending()).hasSize(7);
    // only the arrival-to-landed interval has a figure now
    assertThat(result.measuredP99SumMillis()).isCloseTo(fill() + 9582.423, within(1e-6));
    assertThat(result.verdict()).isEqualTo(LatencyBudget.Verdict.PARTIAL);

    Result empty = LatencyBudget.assemble(Map.of(), N, BURST, MARGIN_HOURS, THREADS);
    assertThat(empty.pending()).hasSize(8);
    assertThat(empty.measuredP99SumMillis()).isZero();
  }

  @Test
  void sumsOnlyTheMeasuredIntervals() {
    Result result = budget(decisions());
    double decide = N * 17.902 / THREADS / 1000.0;
    double expected = fill() + 9582.423 + decide + 401.137;
    assertThat(result.measuredP99SumMillis()).isCloseTo(expected, within(1e-6));
    double sumOfFigures =
        result.intervals().stream().mapToDouble(i -> i.p99Millis().orElse(0.0)).sum();
    // the shared ledger measurement is shown twice and counted once
    assertThat(sumOfFigures - result.measuredP99SumMillis()).isCloseTo(9582.423, within(1e-6));
    assertThat(result.marginMillis()).isEqualTo(4 * 3_600_000.0);
  }

  @Test
  void sumSkipsTheSharedIntervalInsteadOfSubtractingItSoItIsExact() {
    // a figure far larger than the others: adding it twice and taking it away again would lose the
    // low bits of the small intervals, skipping the duplicate leaves them intact
    double huge = 1e16;
    Map<String, Map<String, Object>> d = decisions();
    d.get("SP-L").put("ledger.ingestCommitP99Ms", new BigDecimal("1E+16"));
    d.get("SP-L").put("ledger.commitP99Ms", new BigDecimal("1E+16"));
    Result result = budget(d);
    double decide = N * 17.902 / THREADS / 1000.0;
    double expected = 0.0 + (fill() + huge) + decide + 401.137;
    assertThat(result.measuredP99SumMillis()).isEqualTo(expected);

    // and with the real figure: the same exact sum, in the order of the intervals
    Result real = budget(decisions());
    assertThat(real.measuredP99SumMillis()).isEqualTo(0.0 + (fill() + 9582.423) + decide + 401.137);
  }

  @Test
  void sharedLedgerMeasurementIsShownTwiceAndCountedOnce() {
    Result result = budget(decisions());
    Interval landed = result.intervals().get(0);
    Interval versioned = result.intervals().get(4);
    assertThat(versioned.p99Millis()).contains(9582.423);
    assertThat(landed.p99Millis().orElseThrow()).isCloseTo(fill() + 9582.423, within(1e-6));
    assertThat(landed.composition()).contains("shared measurement");
    assertThat(versioned.composition()).contains("shared measurement");
    assertThat(landed.composition()).contains("pending SP-W");
    assertThat(versioned.composition())
        .contains("ledger.peakBacklogSeconds 30.259 s")
        .contains("ledger.openLoopP99Ms 24183.532 ms");
  }

  @Test
  void bothLedgerIntervalsSayTheFigureIsPooledOverTheBurstRunsAndL2IsInconclusive() {
    Result result = budget(decisions());
    assertThat(result.intervals().get(0).composition()).contains(POOLED_CLAUSE);
    assertThat(result.intervals().get(4).composition()).contains(POOLED_CLAUSE);
    // the figure is the ledger commit p99 under either key, shared or not
    Map<String, Map<String, Object>> d = decisions();
    d.get("SP-L").put("ledger.ingestCommitP99Ms", new BigDecimal("100"));
    d.get("SP-L").put("ledger.commitP99Ms", new BigDecimal("200"));
    Result differing = budget(d);
    assertThat(differing.intervals().get(0).composition()).contains(POOLED_CLAUSE);
    assertThat(differing.intervals().get(4).composition()).contains(POOLED_CLAUSE);
    // the other measured intervals do not carry it, and a pending one has no figure to qualify
    assertThat(result.intervals().get(2).composition()).doesNotContain("INCONCLUSIVE");
    assertThat(result.intervals().get(5).composition()).doesNotContain("INCONCLUSIVE");
    Map<String, Map<String, Object>> missing = decisions();
    missing.get("SP-L").put("ledger.commitP99Ms", "NOT_MEASURED");
    assertThat(budget(missing).intervals().get(4).composition()).doesNotContain("INCONCLUSIVE");
  }

  @Test
  void differingLedgerKeysAreNotTreatedAsOneMeasurement() {
    Map<String, Map<String, Object>> d = decisions();
    d.get("SP-L").put("ledger.ingestCommitP99Ms", new BigDecimal("100"));
    d.get("SP-L").put("ledger.commitP99Ms", new BigDecimal("200"));
    Result result = budget(d);
    assertThat(result.intervals().get(0).composition()).doesNotContain("shared measurement");
    assertThat(result.intervals().get(4).composition()).doesNotContain("shared measurement");
    double expected = fill() + 100 + N * 17.902 / THREADS / 1000.0 + 200 + 401.137;
    assertThat(result.measuredP99SumMillis()).isCloseTo(expected, within(1e-6));
  }

  @Test
  void decideIntervalIsTheUnloadedLowerBoundWithTheLoadedFigureBesideIt() {
    Result result = budget(decisions());
    Interval decided = result.intervals().get(2);
    assertThat(decided.basis()).isEqualTo(Basis.DERIVED);
    assertThat(decided.p99Millis().orElseThrow()).isCloseTo(22.3775, within(1e-9));
    // N / (threads x t4S2) = 5000 / (4 x 27795.748757954) seconds
    assertThat(decided.composition())
        .contains("22.37")
        .contains("unloaded lower bound")
        .contains("loaded figure")
        .contains("44.971 ms");

    Map<String, Map<String, Object>> d = decisions();
    d.get("SP-R").remove("executorGateReadings");
    Interval without = budget(d).intervals().get(2);
    assertThat(without.p99Millis().orElseThrow()).isCloseTo(22.3775, within(1e-9));
    assertThat(without.composition()).contains("loaded figure is not available");
  }

  @Test
  void assembledIntervalUsesTheNearestSizeAndSaysItIsUnloaded() {
    Interval assembled = budget(decisions()).intervals().get(5);
    assertThat(assembled.basis()).isEqualTo(Basis.MEASURED);
    assertThat(assembled.p99Millis()).contains(401.137);
    assertThat(assembled.composition())
        .contains("assemble.latencyMs.5000")
        .contains(DERIVED_REMARK)
        .doesNotContain("(SP-X report)")
        .contains("not a p99");

    // 22,000 x 0.5 x 1.5 = 16,500 is nearer to 25,000 than to 5,000
    Result large = LatencyBudget.assemble(decisions(), 22_000, BURST, MARGIN_HOURS, THREADS);
    assertThat(large.intervals().get(5).p99Millis()).contains(1576.724);
    // 600 x 0.75 = 450 is nearest to 1,000
    Result small = LatencyBudget.assemble(decisions(), 600, BURST, MARGIN_HOURS, THREADS);
    assertThat(small.intervals().get(5).p99Millis()).contains(104.02);
    // 20,000 x 0.75 = 15,000 is equally far from 5,000 and 25,000: the smaller size wins
    Result tie = LatencyBudget.assemble(decisions(), 20_000, BURST, MARGIN_HOURS, THREADS);
    assertThat(tie.intervals().get(5).p99Millis()).contains(401.137);
  }

  @Test
  void firstMissingNamesTheSpikeAndTheKey() {
    assertThat(LatencyBudget.firstMissing(decisions())).isEmpty();
    Map<String, Map<String, Object>> d = decisions();
    d.get("SP-L").put("ledger.commitP99Ms", "NOT_MEASURED");
    assertThat(LatencyBudget.firstMissing(d)).contains("SP-L ledger.commitP99Ms");
    d.get("SP-R").remove("decide.usPerRecordWorst");
    assertThat(LatencyBudget.firstMissing(d)).contains("SP-R decide.usPerRecordWorst");
    Map<String, Map<String, Object>> noAssemble = decisions();
    noAssemble.get("SP-X").clear();
    assertThat(LatencyBudget.firstMissing(noAssemble)).contains("SP-X assemble.latencyMs.<size>");
    // the loaded readings and the listings beside the commit figure are optional
    Map<String, Map<String, Object>> optional = decisions();
    optional.get("SP-R").remove("executorGateReadings");
    optional.get("SP-L").remove("ledger.peakBacklogSeconds");
    optional.get("SP-L").remove("ledger.openLoopP99Ms");
    assertThat(LatencyBudget.firstMissing(optional)).isEmpty();
    assertThat(budget(optional).intervals().get(4).composition())
        .contains("ledger.peakBacklogSeconds not measured");
  }

  @Test
  void renderedBudgetStatesPartialListsThePendingOwnersAndIsReproducible() {
    String text = LatencyBudget.render(budget(decisions()), N, BURST, MARGIN_HOURS, THREADS);
    assertThat(text)
        .isEqualTo(LatencyBudget.render(budget(decisions()), N, BURST, MARGIN_HOURS, THREADS));
    assertThat(text).contains("verdict: PARTIAL");
    assertThat(text).doesNotContain("FITS").doesNotContain("\r");
    assertThat(text).endsWith("\n");
    assertThat(text).contains("SP-N (P1b)", "SP-S (P1b)", "SP-W (P1b)", "P5 (validation layers");
    assertThat(text).contains("14400000 ms");
    assertThat(text).contains("counted once");
    assertThat(text).contains(POOLED_CLAUSE).contains(DERIVED_REMARK);
    assertThat(text).doesNotContain("T0").doesNotContain("2026-");
  }

  @Test
  void rejectsNonsenseParameters() {
    assertThatThrownBy(() -> LatencyBudget.assemble(decisions(), 0, BURST, MARGIN_HOURS, THREADS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LatencyBudget.assemble(decisions(), N, 0, MARGIN_HOURS, THREADS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Interval("a", "b", "c", Optional.of(1.0), Basis.PENDING, "x"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
