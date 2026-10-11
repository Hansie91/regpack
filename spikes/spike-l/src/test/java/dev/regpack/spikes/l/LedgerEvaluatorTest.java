package dev.regpack.spikes.l;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.bench.eval.Criteria;
import dev.regpack.bench.eval.CriterionResult;
import dev.regpack.bench.eval.MeasurementSet;
import dev.regpack.bench.eval.MeasurementSet.CountValue;
import dev.regpack.bench.eval.MeasurementSet.ForkValues;
import dev.regpack.bench.eval.MeasurementSet.Projection;
import dev.regpack.bench.eval.MeasurementSet.RunInfo;
import dev.regpack.bench.eval.MeasurementSet.Series;
import dev.regpack.bench.eval.ResultsEvaluator;
import dev.regpack.bench.eval.SpikeResult;
import dev.regpack.bench.eval.Verdict;
import dev.regpack.spikes.l.LedgerEvaluator.FetchData;
import dev.regpack.spikes.l.LedgerEvaluator.IngestRuns;
import dev.regpack.spikes.l.LedgerEvaluator.MemoryPlan;
import dev.regpack.spikes.l.LedgerEvaluator.MiscData;
import dev.regpack.spikes.l.LedgerEvaluator.NamedRun;
import dev.regpack.spikes.l.LedgerEvaluator.Output;
import dev.regpack.spikes.l.LedgerEvaluator.RebuildEntry;
import dev.regpack.spikes.l.LedgerEvaluator.RestoreData;
import dev.regpack.spikes.l.LedgerEvaluator.RowSizeData;
import dev.regpack.spikes.l.load.BurstRunner.Stop;
import dev.regpack.spikes.l.load.MicroBatchWriter;
import dev.regpack.spikes.l.load.RunResult;
import dev.regpack.spikes.l.matrix.CommitMatrix;
import dev.regpack.spikes.l.matrix.HotRowCounter;
import dev.regpack.spikes.l.partition.PartitionPlan.Granularity;
import dev.regpack.spikes.l.partition.PartitionStudy;
import dev.regpack.spikes.l.restore.RebuildRun;
import dev.regpack.spikes.l.restore.RestoreRun;
import dev.regpack.spikes.l.schema.ConstraintChecks;
import dev.regpack.spikes.l.schema.ConstraintChecks.CheckResult;
import dev.regpack.spikes.l.volume.EvidenceVolumeModel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The turning of study outputs into measurement series, on scripted run results: nothing here needs
 * PostgreSQL. The review focus is RF14 (an extrapolation without a stated basis, a cache-hot run
 * that hides the cost of a larger table) and RF13 (a restore that never ran cold).
 */
class LedgerEvaluatorTest {
  private static final String DIGEST = "ab".repeat(32);
  private static final long SECOND = 1_000_000_000L;
  private static final long INTERVAL = 1_440_900_000L; // 5,000 records at 3,470 records/s

  /** A run that covered {@code seconds} of load and sustained it; its commits take 100 ms. */
  private static RunResult validRun(long seconds) {
    int batches = (int) (seconds * SECOND / INTERVAL);
    long[] ends = new long[batches];
    long[] commits = new long[batches];
    long[] latency = new long[batches];
    for (int i = 0; i < batches; i++) {
      ends[i] = (i + 1L) * INTERVAL + 30_000_000L;
      commits[i] = 100_000_000L;
      latency[i] = 130_000_000L;
    }
    double[] windows = new double[(int) (seconds / 60)];
    java.util.Arrays.fill(windows, 3_470.0);
    return new RunResult(
        true,
        batches * 5_000L,
        commits,
        latency,
        0.0,
        0.1,
        windows,
        Stop.DURATION,
        batches * 5_000L,
        seconds * SECOND,
        0,
        ends,
        List.of());
  }

  /** A run that could not keep up: not valid, not an environment fault. */
  private static RunResult failedRun(long seconds) {
    RunResult ok = validRun(seconds);
    return new RunResult(
        false,
        ok.records(),
        ok.commitNanosSync(),
        ok.openLoopLatencyNanos(),
        40.0,
        12.0,
        ok.windowRates(),
        Stop.DURATION,
        ok.scheduledRecords(),
        ok.coveredNanos(),
        30,
        ok.batchEndOffsetNanos(),
        List.of("the backlog grew by 40.0 records per second"));
  }

  /** A run that the disk stopped: neither failed nor valid. */
  private static RunResult diskRun() {
    RunResult ok = validRun(300);
    return new RunResult(
        false,
        ok.records(),
        ok.commitNanosSync(),
        ok.openLoopLatencyNanos(),
        0.0,
        0.0,
        new double[0],
        Stop.DISK,
        ok.scheduledRecords(),
        ok.coveredNanos(),
        0,
        ok.batchEndOffsetNanos(),
        List.of("the disk ran low"));
  }

  private static List<NamedRun> named(String prefix, RunResult... runs) {
    List<NamedRun> out = new ArrayList<>();
    for (int i = 0; i < runs.length; i++) {
      out.add(new NamedRun(prefix + (i + 1), runs[i]));
    }
    return out;
  }

  private static IngestRuns ingestRuns(List<NamedRun> a, List<NamedRun> b) {
    return new IngestRuns(a, b, Optional.empty(), 0, MemoryPlan.of(4_164_000L, 1_500L));
  }

  private static MeasurementSet set(Output out, String part) {
    return LedgerEvaluator.set(
        DIGEST,
        "PARTIAL",
        out,
        Map.of(),
        List.of(new RunInfo("1", 1, part, "0".repeat(40), "cpu", "img")),
        Map.of("part", part),
        Map.of());
  }

  private static SpikeResult evaluate(MeasurementSet set) {
    return ResultsEvaluator.evaluate(
        set, Criteria.load(), "RAW", Map.of("usedMinutes", 0L, "boxMinutes", 960L));
  }

  private static CriterionResult result(SpikeResult r, String id) {
    return r.criteria().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
  }

  private static Series series(Output out, String id) {
    return out.series().stream()
        .filter(s -> s.criterionId().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no series " + id));
  }

  // ------------------------------------------------------------------ RF14

  @Test
  void extrapolationNeedsAStatedBasis() {
    MeasurementSet bare =
        new MeasurementSet(
            "SP-L",
            DIGEST,
            "PARTIAL",
            List.of(new CountValue("L-4", 0, 2)),
            Map.of(),
            List.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            List.of());
    assertThatThrownBy(() -> LedgerEvaluator.requireExtrapolationNote(bare, Criteria.load()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("extrapolationNote")
        .hasMessageContaining("L-4");

    MeasurementSet blank =
        new MeasurementSet(
            "SP-L",
            DIGEST,
            "PARTIAL",
            List.of(new CountValue("L-4", 0, 2)),
            Map.of(),
            List.of(),
            Map.of(),
            Map.of(),
            Map.of("extrapolationNote", "  "),
            List.of());
    assertThatThrownBy(() -> LedgerEvaluator.requireExtrapolationNote(blank, Criteria.load()))
        .isInstanceOf(IllegalStateException.class);

    // a set without an extrapolated criterion needs no note
    MeasurementSet measured =
        new MeasurementSet(
            "SP-L",
            DIGEST,
            "PARTIAL",
            List.of(new CountValue("L-5", 400, 1)),
            Map.of(),
            List.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            List.of());
    LedgerEvaluator.requireExtrapolationNote(measured, Criteria.load());

    // every set that the evaluator builds carries the note, so the self-check passes
    Output ingest =
        LedgerEvaluator.ingest(ingestRuns(named("A", validRun(1_200), validRun(1_200)), List.of()));
    assertThat(ingest.decisions().get("extrapolationNote")).isInstanceOf(String.class);
    assertThat((String) ingest.decisions().get("extrapolationNote"))
        .contains("20-minute")
        .contains("table-to-memory")
        .contains("Theil-Sen");
    LedgerEvaluator.requireExtrapolationNote(set(ingest, "ingest"), Criteria.load());
  }

  @Test
  void cachePressuredRunMustAlsoSustain() {
    IngestRuns in =
        ingestRuns(
            named("A", validRun(1_200), validRun(1_200)),
            named("B", failedRun(1_200), failedRun(1_200)));
    Output out = LedgerEvaluator.ingest(in);
    SpikeResult result = evaluate(set(out, "ingest"));

    assertThat(result(result, "L-1").verdict()).isEqualTo(Verdict.PASS);
    CriterionResult l4 = result(result, "L-4");
    assertThat(l4.verdict()).isEqualTo(Verdict.FAIL);
    assertThat(result.status())
        .as("a pass in a cache-hot slice does not rescue a failure under memory pressure")
        .isEqualTo(Verdict.FAIL);
    assertThat(String.join("\n", out.open()))
        .as("the reason names the memory ratio of the day")
        .contains("table-to-memory ratio 4.37")
        .contains("L-4");
    assertThat(out.decisions()).containsEntry("memory.ratioDay", new BigDecimal("4.366"));
  }

  @Test
  void bothSlicesPassingGivePassForL1AndL4() {
    Output out =
        LedgerEvaluator.ingest(
            ingestRuns(
                named("A", validRun(1_200), validRun(1_200)),
                named("B", validRun(1_200), validRun(1_200))));
    SpikeResult result = evaluate(set(out, "ingest"));
    assertThat(result(result, "L-1").verdict()).isEqualTo(Verdict.PASS);
    assertThat(result(result, "L-4").verdict()).isEqualTo(Verdict.PASS);
    assertThat(out.decisions()).containsKeys("ledger.peakBacklogSeconds", "ledger.openLoopP99Ms");
    assertThat(out.decisions().get("ledger.openLoopP99Ms")).isEqualTo(new BigDecimal("130.000"));
    // 20 windows per run, pooled over the two A runs: the L-2 series
    ForkValues l2 = (ForkValues) series(out, "L-2");
    assertThat(l2.forks()).hasSize(40);
    assertThat(l2.unit()).isEqualTo("ms");
    assertThat(l2.forks().get(0).statistic().doubleValue()).isEqualTo(100.0);
  }

  @Test
  void diskStoppedRunIsNotFailedAndNotValid() {
    Output out =
        LedgerEvaluator.ingest(ingestRuns(named("A", validRun(1_200), diskRun()), List.of()));
    CountValue l1 = (CountValue) series(out, "L-1");
    assertThat(l1.observed()).as("a disk stop is not a failed run").isZero();
    assertThat(l1.validRuns()).as("and it is not a valid run either").isEqualTo(1);
    CriterionResult verdict = result(evaluate(set(out, "ingest")), "L-1");
    assertThat(verdict.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(verdict.reason()).contains("1 valid runs, 2 needed");
    assertThat(String.join("\n", out.open())).contains("A2").contains("the disk ran low");
  }

  @Test
  void aFailedRunCountsAsFailedAndValid() {
    Output out =
        LedgerEvaluator.ingest(
            ingestRuns(named("A", validRun(1_200), failedRun(1_200)), List.of()));
    CountValue l1 = (CountValue) series(out, "L-1");
    assertThat(l1.observed()).isEqualTo(1);
    assertThat(l1.validRuns()).isEqualTo(2);
    assertThat(result(evaluate(set(out, "ingest")), "L-1").verdict()).isEqualTo(Verdict.FAIL);
  }

  @Test
  void theReasonPrintsTheCoveredLoadAgainstTheDesign() {
    Output out =
        LedgerEvaluator.ingest(ingestRuns(named("A", validRun(480), validRun(1_200)), List.of()));
    String open = String.join("\n", out.open());
    assertThat(open).contains("A1").contains("480 s").contains("1200 s design");
  }

  // ------------------------------------------------------------------ L-5

  @Test
  void rowSizeUsesTotalBytesWithIndexes() {
    // heap 300 plus indexes 250 per row is 550 bytes: over the 500 of the spec
    CountValue fail = (CountValue) LedgerEvaluator.rowSize(1_000, 300_000, 250_000);
    assertThat(fail.observed()).isEqualTo(550);
    assertThat(evaluateCount(fail).verdict()).isEqualTo(Verdict.FAIL);
    CountValue pass = (CountValue) LedgerEvaluator.rowSize(1_000, 300_000, 150_000);
    assertThat(pass.observed()).isEqualTo(450);
    assertThat(evaluateCount(pass).verdict()).isEqualTo(Verdict.PASS);
    // a fraction of a byte rounds up: never under-report the size
    assertThat(((CountValue) LedgerEvaluator.rowSize(3, 1_000, 1)).observed()).isEqualTo(334);
    assertThatThrownBy(() -> LedgerEvaluator.rowSize(0, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static CriterionResult evaluateCount(CountValue count) {
    MeasurementSet one =
        new MeasurementSet(
            "SP-L",
            DIGEST,
            "PARTIAL",
            List.of(count),
            Map.of(),
            List.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            List.of());
    return result(evaluate(one), count.criterionId());
  }

  // ------------------------------------------------------------------ restore

  private static RestoreRun.Outcome outcome(long keys, boolean cold, boolean fsync) {
    RestoreRun.Point point =
        new RestoreRun.Point(
            keys,
            cold,
            fsync,
            10 * SECOND,
            2 * SECOND,
            50 * SECOND,
            2 * SECOND,
            3 * SECOND,
            SECOND);
    return new RestoreRun.Outcome(point, 1_000_000L, 800L, 500L);
  }

  private static RebuildEntry rebuild(long keys, boolean cold, boolean contentEqual) {
    return new RebuildEntry(
        new RebuildRun.Result(
            keys, 4, cold, 100 * SECOND, 20 * SECOND, 60 * SECOND, 20 * SECOND, "rows=" + keys),
        contentEqual);
  }

  private static RestoreData restoreData(
      List<RestoreRun.Outcome> points, List<RebuildEntry> rebuilds) {
    return new RestoreData(points, 500L, rebuilds, 50_000_000L, new BigDecimal("24"));
  }

  @Test
  void restoreWithoutColdCacheCannotPass() {
    Output out =
        LedgerEvaluator.restore(
            restoreData(
                List.of(
                    outcome(500_000L, false, true),
                    outcome(1_000_000L, false, true),
                    outcome(2_000_000L, false, true)),
                List.of(rebuild(500_000L, false, true), rebuild(1_000_000L, false, true))));
    SpikeResult result = evaluate(set(out, "restore"));
    for (String id : List.of("L-6", "L-7", "L-8")) {
      assertThat(result(result, id).verdict()).as(id).isEqualTo(Verdict.INCONCLUSIVE);
    }
    assertThat(String.join("\n", out.open())).contains("cache");
  }

  @Test
  void fsyncOffPointsNeverFeedTheProjection() {
    Output out =
        LedgerEvaluator.restore(
            restoreData(
                List.of(
                    outcome(500_000L, true, true),
                    outcome(1_000_000L, true, false),
                    outcome(2_000_000L, true, true)),
                List.of()));
    Projection l6 = (Projection) series(out, "L-6");
    assertThat(l6.xs()).hasSize(2);
    assertThat(result(evaluate(set(out, "restore")), "L-6").verdict())
        .isEqualTo(Verdict.INCONCLUSIVE);
  }

  @Test
  void fewerThanThreeRestoreSizesMakeL7NotRun() {
    Output out =
        LedgerEvaluator.restore(
            restoreData(
                List.of(outcome(500_000L, true, true), outcome(1_000_000L, true, true)),
                List.of()));
    assertThat(out.series()).extracting(Series::criterionId).doesNotContain("L-6", "L-7");
    SpikeResult result = evaluate(set(out, "restore"));
    assertThat(result(result, "L-6").verdict()).isEqualTo(Verdict.NOT_RUN);
    assertThat(result(result, "L-7").verdict()).isEqualTo(Verdict.NOT_RUN);
    assertThat(String.join("\n", out.open())).contains("fewer than three");
  }

  @Test
  void threeColdPointsProjectToTenAndHundredMillionKeys() {
    Output out =
        LedgerEvaluator.restore(
            restoreData(
                List.of(
                    outcome(500_000L, true, true),
                    outcome(1_000_000L, true, true),
                    outcome(2_000_000L, true, true)),
                List.of(rebuild(500_000L, true, true), rebuild(1_000_000L, true, true))));
    Projection l6 = (Projection) series(out, "L-6");
    Projection l7 = (Projection) series(out, "L-7");
    assertThat(l6.target()).isEqualByComparingTo("10000000");
    assertThat(l7.target()).isEqualByComparingTo("100000000");
    assertThat(l6.unit()).isEqualTo("hours");
    assertThat(l6.xs())
        .extracting(BigDecimal::longValue)
        .containsExactly(500_000L, 1_000_000L, 2_000_000L);
    // the replay is one measured rate (50 s for 1,000,000 records) scaled to a day of 50M records
    // and a 24 hour backup interval: 2,500 s; the time that grows with the size is
    // 10 + 2 + 3 + 1 = 16 s, so every point is 2,516 s
    for (BigDecimal y : l6.ys()) {
      assertThat(y).isEqualByComparingTo("2516");
    }
    assertThat(l6.scale().doubleValue())
        .isEqualTo(1.0 / 3600.0, org.assertj.core.data.Offset.offset(1e-12));
    // the physical floor: the fastest copy (2,000,000 keys x 500 bytes x 2 in 10 s, 2e8 bytes/s)
    // sets the rate, so 10M keys take 2 x 5 GB / 2e8 = 50 s and 100M keys 500 s; the replay of
    // 2,500 s is added to each
    assertThat(out.decisions().get("restore.floorRateBytesPerSecond")).isEqualTo(200_000_000L);
    assertThat((BigDecimal) out.decisions().get("restore.floorSeconds.10M"))
        .isEqualByComparingTo("50");
    assertThat((BigDecimal) out.decisions().get("restore.floorSeconds.100M"))
        .isEqualByComparingTo("500");
    assertThat((BigDecimal) out.decisions().get("restore.replaySeconds"))
        .isEqualByComparingTo("2500");
    assertThat(l6.floor()).as("(50 + 2,500) / 3,600 hours").isEqualByComparingTo("0.708333333");
    assertThat(l7.floor()).as("(500 + 2,500) / 3,600 hours").isEqualByComparingTo("0.833333333");

    Series l8 = series(out, "L-8");
    assertThat(l8).isInstanceOf(MeasurementSet.MeasuredOnly.class);
    MeasurementSet.MeasuredOnly rebuilds = (MeasurementSet.MeasuredOnly) l8;
    assertThat(rebuilds.invariantsHold()).isTrue();
    assertThat(rebuilds.n()).isEqualTo(2);
    // 100 s for 500,000 keys is 200 s per million keys
    assertThat(rebuilds.value()).isEqualByComparingTo("200");
    assertThat(out.decisions().get("rebuild.projectedHours.10M")).isNotNull();
    assertThat(out.decisions().get("rebuild.projectedHours.100M")).isNotNull();
  }

  @Test
  void aRebuildWithDifferentDecisionsFailsL8() {
    Output out =
        LedgerEvaluator.restore(
            restoreData(
                List.of(
                    outcome(500_000L, true, true),
                    outcome(1_000_000L, true, true),
                    outcome(2_000_000L, true, true)),
                List.of(rebuild(500_000L, true, false))));
    assertThat(result(evaluate(set(out, "restore")), "L-8").verdict()).isEqualTo(Verdict.FAIL);
  }

  // ------------------------------------------------------------------ the parts

  @Test
  void partsMergeIntoOneSet() {
    Output ingest =
        LedgerEvaluator.ingest(
            ingestRuns(
                named("A", validRun(1_200), validRun(1_200)),
                named("B", validRun(1_200), validRun(1_200))));
    Output restore =
        LedgerEvaluator.restore(
            restoreData(
                List.of(
                    outcome(500_000L, true, true),
                    outcome(1_000_000L, true, true),
                    outcome(2_000_000L, true, true)),
                List.of(rebuild(500_000L, true, true))));
    Output misc = LedgerEvaluator.misc(smallMisc());

    MeasurementSet merged =
        set(ingest, "ingest").merge(set(restore, "restore")).merge(set(misc, "misc"));
    TreeSet<String> ids = new TreeSet<>();
    merged.series().forEach(s -> ids.add(s.criterionId()));
    assertThat(ids).contains("L-1", "L-2", "L-4", "L-6", "L-7", "L-8", "L-5", "L-12", "L-13");
    assertThat(merged.runs())
        .extracting(RunInfo::part)
        .containsExactly("ingest", "restore", "misc");
    assertThat(merged.decisions()).containsKey("extrapolationNote");
    assertThat(String.join("\n", merged.open()))
        .as("the shared note is one decision, not a conflict between parts")
        .doesNotContain("differs between the pooled sets");
    LedgerEvaluator.requireExtrapolationNote(merged, Criteria.load());
    // every part of one dispatch has the one digest, so the merge is allowed; a different digest is
    // not
    MeasurementSet other =
        new MeasurementSet(
            "SP-L",
            "cd".repeat(32),
            "PARTIAL",
            List.of(),
            Map.of(),
            List.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            List.of());
    assertThatThrownBy(() -> merged.merge(other)).isInstanceOf(IllegalArgumentException.class);
  }

  // ------------------------------------------------------------------ the misc part

  private static List<CheckResult> checks(int failing, int missing) {
    List<CheckResult> out = new ArrayList<>();
    List<String> names = ConstraintChecks.NAMES;
    for (int i = 0; i < names.size() - missing; i++) {
      out.add(new CheckResult(names.get(i), i >= failing, "detail"));
    }
    return out;
  }

  private static MiscData smallMisc() {
    return new MiscData(
        checks(0, 0),
        Optional.of(
            new RowSizeData(
                "E1_COMPACT",
                1_000,
                300_000,
                100_000,
                480,
                Map.of("decision", 400_000L, "decision_head", 60_000L))),
        Optional.empty(),
        List.of(),
        Optional.empty(),
        Optional.empty(),
        List.of(),
        Optional.of(
            EvidenceVolumeModel.compute(
                new EvidenceVolumeModel.Inputs(
                    50_000_000L,
                    5_000,
                    BigDecimal.valueOf(4),
                    400L,
                    500,
                    new BigDecimal("0.01"),
                    3,
                    20_000L))),
        0,
        Optional.empty());
  }

  private static MiscData with(MiscData base, List<CheckResult> checks) {
    return new MiscData(
        checks,
        base.rowSizeE1(),
        base.rowSizeE3(),
        base.partitions(),
        base.fetch(),
        base.hotRow(),
        base.cells(),
        base.volume(),
        base.violatingSamples(),
        base.console());
  }

  @Test
  void constraintChecksThatFailOrAreMissingCountAsFailed() {
    MiscData base = smallMisc();
    CountValue none = (CountValue) series(LedgerEvaluator.misc(base), "L-12");
    assertThat(none.observed()).isZero();
    assertThat(result(evaluate(set(LedgerEvaluator.misc(base), "misc")), "L-12").verdict())
        .isEqualTo(Verdict.PASS);

    CountValue one = (CountValue) series(LedgerEvaluator.misc(with(base, checks(1, 0))), "L-12");
    assertThat(one.observed()).isEqualTo(1);
    // a check that did not run cannot be assumed to pass
    CountValue missing =
        (CountValue) series(LedgerEvaluator.misc(with(base, checks(0, 2))), "L-12");
    assertThat(missing.observed()).isEqualTo(2);
    // no checks at all: not run, so no series and the criterion stays NOT_RUN
    assertThat(LedgerEvaluator.misc(with(base, List.of())).series())
        .extracting(Series::criterionId)
        .doesNotContain("L-12");
  }

  private static PartitionStudy.Point point(Granularity g, int partitions, double lookupNanos) {
    double[] lookups = new double[15];
    double[] fetches = new double[15];
    java.util.Arrays.fill(lookups, lookupNanos);
    java.util.Arrays.fill(fetches, lookupNanos / 10);
    return new PartitionStudy.Point(g, partitions, lookups, fetches, 2_000_000L, 3_000_000L);
  }

  @Test
  void lookupSeriesAreForkLikeBlocksInMilliseconds() {
    MiscData base = smallMisc();
    MiscData data =
        new MiscData(
            base.checks(),
            base.rowSizeE1(),
            base.rowSizeE3(),
            List.of(
                point(Granularity.DAILY, 30, 2_000_000),
                point(Granularity.DAILY, 1_826, 8_000_000),
                point(Granularity.MONTHLY, 60, 3_000_000)),
            Optional.of(new FetchData(new double[] {5_000_000, 6_000_000, 7_000_000}, true, 10)),
            Optional.of(
                new HotRowCounter.Result(
                    List.of(new HotRowCounter.Point(4, 4_000, 10_000_000_000L)),
                    4,
                    new double[] {400, 410, 420},
                    List.of())),
            List.of(),
            base.volume(),
            0,
            Optional.empty());
    Output out = LedgerEvaluator.misc(data);
    ForkValues l10 = (ForkValues) series(out, "L-10");
    assertThat(l10.unit()).isEqualTo("ms");
    assertThat(l10.forks()).hasSize(15);
    assertThat(l10.forks().get(0).statistic().doubleValue())
        .as("the point with the most daily partitions, 8 ms")
        .isEqualTo(8.0);
    ForkValues l9 = (ForkValues) series(out, "L-9");
    assertThat(l9.forks())
        .extracting(f -> f.statistic().doubleValue())
        .containsExactly(5.0, 6.0, 7.0);
    ForkValues l11 = (ForkValues) series(out, "L-11");
    assertThat(l11.unit()).isEqualTo("events/s");
    assertThat(l11.forks()).hasSize(3);
    assertThat(out.decisions())
        .containsKeys(
            "partition.daily1826.lookupP99Ms",
            "partition.monthly60.lookupP99Ms",
            "partition.daily30.lookupP99Ms",
            "partition.daily1826.headThenFetchP99Ms");
  }

  @Test
  void aFetchWhoseBurstEndedEarlyIsNotL9() {
    MiscData base = smallMisc();
    MiscData data =
        new MiscData(
            base.checks(),
            base.rowSizeE1(),
            base.rowSizeE3(),
            base.partitions(),
            Optional.of(new FetchData(new double[] {5_000_000, 6_000_000}, false, 10)),
            base.hotRow(),
            base.cells(),
            base.volume(),
            0,
            Optional.empty());
    Output out = LedgerEvaluator.misc(data);
    assertThat(out.series()).extracting(Series::criterionId).doesNotContain("L-9");
    assertThat(String.join("\n", out.open())).contains("L-9").contains("burst");
  }

  @Test
  void l14NeedsTheByteTablesAndTheEvidenceModel() {
    Output out = LedgerEvaluator.misc(smallMisc());
    MeasurementSet.MeasuredOnly l14 = (MeasurementSet.MeasuredOnly) series(out, "L-14");
    assertThat(l14.invariantsHold()).isTrue();
    assertThat(l14.n()).isGreaterThanOrEqualTo(2);
    // 400,000 bytes of decision for 1,000 decision rows is 400 bytes a row; the day has 50M
    // candidates
    assertThat(out.decisions()).containsKey("bytes.perDay.decision");
    assertThat(out.decisions()).containsKey("volume.landingObjectsPerDay");
    assertThat(out.decisions()).containsKey("decisionHead.rowsPerDay");
    assertThat(result(evaluate(set(out, "misc")), "L-14").verdict()).isEqualTo(Verdict.PASS);
    MiscData base = smallMisc();
    MiscData without =
        new MiscData(
            base.checks(),
            Optional.empty(),
            Optional.empty(),
            List.of(),
            Optional.empty(),
            Optional.empty(),
            List.of(),
            Optional.empty(),
            0,
            Optional.empty());
    assertThat(LedgerEvaluator.misc(without).series())
        .extracting(Series::criterionId)
        .doesNotContain("L-14", "L-5");
  }

  @Test
  void l14WithoutByteTablesSaysSoInsteadOfPrintingZeroBytes() {
    MiscData base = smallMisc();
    MiscData noRows =
        new MiscData(
            base.checks(),
            Optional.empty(),
            Optional.empty(),
            List.of(),
            Optional.empty(),
            Optional.empty(),
            List.of(),
            base.volume(),
            0,
            Optional.empty());
    Output out = LedgerEvaluator.misc(noRows);
    MeasurementSet.MeasuredOnly l14 = (MeasurementSet.MeasuredOnly) series(out, "L-14");
    assertThat(l14.unit()).containsIgnoringCase("not measured");
    assertThat(String.join("\n", out.open())).contains("L-14").contains("bytes per day");
    // with the byte tables the unit is the plain one
    MeasurementSet.MeasuredOnly full =
        (MeasurementSet.MeasuredOnly) series(LedgerEvaluator.misc(base), "L-14");
    assertThat(full.unit()).doesNotContainIgnoringCase("not measured");
  }

  @Test
  void theKneeComesFromTheCommitMatrix() {
    List<CommitMatrix.Cell> cells = new ArrayList<>();
    long[] sizes = {500, 2_000, 5_000, 10_000, 25_000};
    for (long n : sizes) {
      // 20 ms fixed plus 0.02 ms per record: the knee is 10,000
      long mean = 20_000_000L + 20_000L * n;
      cells.add(
          new CommitMatrix.Cell(
              (int) n,
              4,
              MicroBatchWriter.Method.COPY,
              true,
              100,
              mean,
              mean,
              mean,
              3_000,
              200_000,
              Stop.DURATION));
    }
    MiscData base = smallMisc();
    MiscData data =
        new MiscData(
            base.checks(),
            base.rowSizeE1(),
            base.rowSizeE3(),
            base.partitions(),
            base.fetch(),
            base.hotRow(),
            cells,
            base.volume(),
            0,
            Optional.empty());
    Output out = LedgerEvaluator.misc(data);
    assertThat(out.decisions()).containsEntry("ledger.kneeN", 10_000L);
    assertThat(out.decisions()).containsKey("ledger.kneeCommitP99Ms");
  }

  @Test
  void l13CountsTheViolatingSamples() {
    IngestRuns in =
        new IngestRuns(
            named("A", validRun(1_200), validRun(1_200)),
            List.of(),
            Optional.empty(),
            3,
            MemoryPlan.of(4_164_000L, 1_500L));
    Output out = LedgerEvaluator.ingest(in);
    CountValue l13 = (CountValue) series(out, "L-13");
    assertThat(l13.observed()).isEqualTo(3);
    assertThat(result(evaluate(set(out, "ingest")), "L-13").verdict()).isEqualTo(Verdict.FAIL);
  }

  @Test
  void theMemoryOfRunBFollowsTheDayRatio() {
    MemoryPlan plan = MemoryPlan.of(4_164_000L, 1_500L);
    // 16 GiB for 50M records: 4.164M records get 1.33 GiB
    assertThat(plan.memoryBytes()).isBetween(1_400_000_000L, 1_450_000_000L);
    assertThat(plan.sharedBuffersBytes()).isEqualTo(plan.memoryBytes() / 4);
    assertThat(plan.dockerMemory()).isEqualTo(plan.memoryBytes() / (1L << 20) + "m");
    assertThat(plan.sharedBuffers()).isEqualTo(plan.sharedBuffersBytes() / (1L << 20) + "MB");
    assertThat(plan.floored()).isFalse();
    // a tiny slice is raised to the floor and says so
    MemoryPlan tiny = MemoryPlan.of(100_000L, 1_500L);
    assertThat(tiny.floored()).isTrue();
    assertThat(tiny.memoryBytes()).isEqualTo(512L << 20);
    assertThat(tiny.describe()).contains("raised");
  }
}
