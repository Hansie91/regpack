package dev.regpack.bench.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.bench.eval.Basis;
import dev.regpack.bench.eval.Criteria;
import dev.regpack.bench.eval.CriterionResult;
import dev.regpack.bench.eval.MeasurementSet;
import dev.regpack.bench.eval.MeasurementSet.CountValue;
import dev.regpack.bench.eval.MeasurementSet.ForkStat;
import dev.regpack.bench.eval.MeasurementSet.ForkValues;
import dev.regpack.bench.eval.MeasurementSet.Projection;
import dev.regpack.bench.eval.MeasurementSet.RunInfo;
import dev.regpack.bench.eval.MeasurementSet.Series;
import dev.regpack.bench.eval.Resource;
import dev.regpack.bench.eval.ResultsEvaluator;
import dev.regpack.bench.eval.SpikeResult;
import dev.regpack.bench.eval.Verdict;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReportWriterTest {
  private static final Criteria CRITERIA = Criteria.load();
  private static final String DIGEST = "e".repeat(64);
  private static final Map<String, Object> BOX = Map.of("usedMinutes", 120L, "boxMinutes", 480L);

  private static Map<Resource, List<BigDecimal>> canary() {
    Map<Resource, List<BigDecimal>> canary = new EnumMap<>(Resource.class);
    for (Resource r : Resource.values()) {
      List<BigDecimal> samples = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        samples.add(new BigDecimal(1000 + (i % 3)));
      }
      canary.put(r, samples);
    }
    return canary;
  }

  private static ForkValues forks(String id, double centre, double half) {
    List<ForkStat> list = new ArrayList<>();
    for (int i = 0; i < 15; i++) {
      list.add(
          new ForkStat(
              i, BigDecimal.valueOf(centre - half + i * (2 * half / 14)), false, "", false));
    }
    return new ForkValues(id, CRITERIA.byId(id).orElseThrow().unit(), BigDecimal.ONE, list);
  }

  private static RunInfo run(String id, String sha, String cpu) {
    return new RunInfo(id, 1, "a", sha, cpu, "ubuntu24/20260901.1");
  }

  private static MeasurementSet set(
      String spike, List<RunInfo> runs, Map<String, Object> env, Series... series) {
    return new MeasurementSet(
        spike,
        DIGEST,
        "FULL",
        List.of(series),
        canary(),
        runs,
        Map.of("gitSha", "abc1234"),
        env,
        Map.of("executorGate", "PASS", "budget", Map.of("perRecordUnits", 1200L)),
        List.of("S1 shape not run"));
  }

  private static Map<String, Object> env() {
    return Map.of(
        "cpu.model", "AMD EPYC 7763",
        "cpu.count", 4L,
        "cpu.physicalCores", 2L,
        "mem.totalBytes", 17179869184L,
        "steal.observable", Boolean.TRUE);
  }

  private static List<RunInfo> twoRuns() {
    return List.of(run("1", "abc1234", "AMD EPYC 7763"), run("2", "abc1234", "AMD EPYC 7763"));
  }

  /** Mixed verdicts: FAIL (count), INCONCLUSIVE (marginal), PASS, and the rest NOT_RUN. */
  private static SpikeResult mixed(int runCount) {
    List<RunInfo> runs = runCount == 1 ? List.of(run("1", "abc1234", "AMD EPYC 7763")) : twoRuns();
    return ResultsEvaluator.evaluate(
        set(
            "SP-R",
            runs,
            env(),
            new CountValue("R-1a", 2, 1),
            forks("R-2a", 10_700, 400),
            forks("R-4", 50, 50),
            new CountValue("R-5", 0, 1)),
        CRITERIA,
        "RAW",
        BOX);
  }

  private static List<String> lines(String report) {
    return List.of(report.split("\n", -1));
  }

  private static String row(String report, String id) {
    return lines(report).stream()
        .filter(l -> l.startsWith("| " + id + " |"))
        .findFirst()
        .orElseThrow();
  }

  private static SpikeResult withCriteria(SpikeResult r, List<CriterionResult> rows) {
    return new SpikeResult(
        r.spike(),
        r.status(),
        r.scope(),
        r.evidenceLevel(),
        rows,
        r.decisions(),
        r.open(),
        r.source(),
        r.env(),
        r.timeBox(),
        r.criteriaDigest(),
        r.vmCount(),
        r.mixedHardware(),
        r.runs());
  }

  @Test
  void reportStartsWithTheNineHeaderLines() {
    SpikeResult r = mixed(2);
    List<String> lines = lines(ReportWriter.render(r, CRITERIA));
    assertThat(lines.subList(0, 9))
        .containsExactly(
            "# SP-R report",
            "spike: SP-R",
            "status: INCONCLUSIVE",
            "scope: FULL",
            "evidence_level: RAW",
            "criteria_digest: " + CRITERIA.digest().hex(),
            "source_git_sha: abc1234",
            "runs: 2 (vmCount 2)",
            "time_box: used 120 of 480 minutes, hit: no");
    assertThat(lines.get(9)).isEmpty();
  }

  @Test
  void timeBoxHitIsStated() {
    SpikeResult r =
        ResultsEvaluator.evaluate(
            set("SP-R", twoRuns(), env(), new CountValue("R-1a", 0, 1)),
            CRITERIA,
            "RAW",
            Map.of("usedMinutes", 500L, "boxMinutes", 480L));
    assertThat(lines(ReportWriter.render(r, CRITERIA)).get(8))
        .isEqualTo("time_box: used 500 of 480 minutes, hit: yes");
  }

  @Test
  void neverRendersInconclusiveAsPass() {
    SpikeResult r = mixed(2);
    assertThat(r.criteria())
        .extracting(CriterionResult::verdict)
        .contains(Verdict.FAIL, Verdict.INCONCLUSIVE, Verdict.NOT_RUN, Verdict.PASS);
    // a result whose reason text itself says PASS must not leak it into a non-PASS row
    List<CriterionResult> rows = new ArrayList<>(r.criteria());
    rows.set(
        0,
        new CriterionResult(
            "R-1a",
            Verdict.FAIL,
            Basis.DERIVED,
            Optional.empty(),
            Optional.of(BigDecimal.ZERO),
            BigDecimal.ZERO,
            "PASS PASS and again PASS"));
    SpikeResult hostile = withCriteria(r, rows);
    for (SpikeResult candidate : List.of(r, hostile)) {
      String report = ReportWriter.render(candidate, CRITERIA);
      for (CriterionResult c : candidate.criteria()) {
        String row = row(report, c.id());
        if (c.verdict() == Verdict.PASS) {
          assertThat(row).contains("PASS");
        } else {
          assertThat(row).as(row).doesNotContain("PASS");
          assertThat(row).contains(c.verdict().name());
        }
      }
      assertThat(lines(report).get(2)).doesNotContain("PASS");
    }
  }

  @Test
  void extrapolatedPassReadsPassExtrapolated() {
    List<BigDecimal> xs =
        List.of(new BigDecimal("1000000"), new BigDecimal("2000000"), new BigDecimal("3000000"));
    List<BigDecimal> ys =
        List.of(new BigDecimal("60"), new BigDecimal("120"), new BigDecimal("180"));
    Projection p =
        new Projection(
            "L-6",
            "hours",
            new BigDecimal("0.000277777778"),
            xs,
            ys,
            new BigDecimal("10000000"),
            BigDecimal.ZERO);
    SpikeResult r =
        ResultsEvaluator.evaluate(set("SP-L", twoRuns(), env(), p), CRITERIA, "RAW", BOX);
    String report = ReportWriter.render(r, CRITERIA);
    assertThat(row(report, "L-6")).contains("PASS (EXTRAPOLATED)");
    assertThat(report).contains("ack:");
    // a plain measured pass does not carry the label
    String plain = row(ReportWriter.render(mixed(2), CRITERIA), "R-5");
    assertThat(plain).contains("PASS").doesNotContain("EXTRAPOLATED").doesNotContain("single VM");
  }

  @Test
  void singleVmIsStated() {
    String report = ReportWriter.render(mixed(1), CRITERIA);
    assertThat(report).contains("forks share one VM, bounds are optimistic");
    assertThat(row(report, "R-4")).contains("PASS (single VM)");
    // an exact count is not noisy: no label
    assertThat(row(report, "R-5")).doesNotContain("single VM");
    String twoVms = ReportWriter.render(mixed(2), CRITERIA);
    assertThat(twoVms).doesNotContain("single VM").doesNotContain("forks share one VM");
  }

  @Test
  void aNoisyPassWithoutRunProvenanceIsNotACleanPass() {
    SpikeResult r =
        ResultsEvaluator.evaluate(
            set("SP-R", List.of(), env(), forks("R-4", 50, 50)), CRITERIA, "RAW", BOX);
    assertThat(r.vmCount()).isZero();
    String report = ReportWriter.render(r, CRITERIA);
    assertThat(report).contains("the number of virtual machines is unknown");
    assertThat(row(report, "R-4")).contains("PASS (VMs unknown)").doesNotContain("single VM");
  }

  @Test
  void mixedHardwareIsFlagged() {
    SpikeResult r =
        ResultsEvaluator.evaluate(
            set(
                "SP-R",
                List.of(
                    run("1", "abc1234", "AMD EPYC 7763"), run("2", "abc1234", "Intel Xeon 8370C")),
                env(),
                new CountValue("R-1a", 0, 1)),
            CRITERIA,
            "RAW",
            BOX);
    assertThat(ReportWriter.render(r, CRITERIA)).containsIgnoringCase("mixed hardware");
    assertThat(ReportWriter.render(mixed(2), CRITERIA))
        .doesNotContainIgnoringCase("mixed hardware");
  }

  @Test
  void severalGitShasArePrintedWithAWarning() {
    SpikeResult r =
        ResultsEvaluator.evaluate(
            set(
                "SP-R",
                List.of(run("1", "aaa1111", "cpu"), run("2", "bbb2222", "cpu")),
                env(),
                new CountValue("R-1a", 0, 1)),
            CRITERIA,
            "RAW",
            BOX);
    String report = ReportWriter.render(r, CRITERIA);
    assertThat(report)
        .contains("aaa1111")
        .contains("bbb2222")
        .containsIgnoringCase("different git");
    assertThat(ReportWriter.render(mixed(2), CRITERIA)).doesNotContainIgnoringCase("different git");
  }

  @Test
  void summaryEvidenceWritesPendingAndComputedStatus() {
    MeasurementSet m =
        set("HARNESS", twoRuns(), env(), new CountValue("ST-1", 0, 1), forks("ST-2", 1000, 100));
    SpikeResult raw = ResultsEvaluator.evaluate(m, CRITERIA, "RAW", BOX);
    assertThat(raw.status()).isEqualTo(Verdict.PASS);
    List<String> rawLines = lines(ReportWriter.render(raw, CRITERIA));
    assertThat(rawLines.get(2)).isEqualTo("status: PASS");
    assertThat(rawLines).noneMatch(l -> l.startsWith("computed_status:"));

    SpikeResult summary = ResultsEvaluator.evaluate(m, CRITERIA, "SUMMARY", BOX);
    List<String> summaryLines = lines(ReportWriter.render(summary, CRITERIA));
    assertThat(summaryLines.get(2)).isEqualTo("status: PENDING");
    assertThat(summaryLines.get(4)).isEqualTo("evidence_level: SUMMARY");
    assertThat(summaryLines).contains("computed_status: PASS");
    assertThat(summaryLines.stream().filter(l -> l.startsWith("status:")).toList())
        .containsExactly("status: PENDING");
  }

  @Test
  void generatedPartEndsWithTripleDash() {
    String report = ReportWriter.render(mixed(2), CRITERIA);
    assertThat(report).endsWith("\n---\n");
    assertThat(lines(report).stream().filter(l -> l.equals("---")).count()).isEqualTo(1);
    assertThat(report).doesNotContain("\r").doesNotContain("Interpretation");
  }

  @Test
  void environmentDecisionsOpenAndRunsAreListed() {
    String report = ReportWriter.render(mixed(2), CRITERIA);
    assertThat(report)
        .contains("AMD EPYC 7763")
        .contains("ubuntu24/20260901.1")
        .contains("P-5 steal discard observable: yes")
        .contains("executorGate")
        .contains("perRecordUnits")
        .contains("S1 shape not run");
    SpikeResult noEnv =
        ResultsEvaluator.evaluate(
            set("SP-R", twoRuns(), Map.of(), new CountValue("R-1a", 0, 1)), CRITERIA, "RAW", BOX);
    assertThat(ReportWriter.render(noEnv, CRITERIA)).contains("P-5 steal discard observable: no");
  }

  @Test
  void textFromResultFieldsIsEscapedForTableCells() {
    SpikeResult r = mixed(2);
    List<CriterionResult> rows = new ArrayList<>(r.criteria());
    rows.set(
        0,
        new CriterionResult(
            "R-1a",
            Verdict.FAIL,
            Basis.DERIVED,
            Optional.empty(),
            Optional.of(BigDecimal.ZERO),
            BigDecimal.ZERO,
            "a|b `code`\nsecond <b>line</b>"));
    String row = row(ReportWriter.render(withCriteria(r, rows), CRITERIA), "R-1a");
    assertThat(row).contains("a\\|b").doesNotContain("`").doesNotContain("<b>");

    // links, images, emphasis and headings from CI cannot become Markdown
    List<CriterionResult> markup = new ArrayList<>(r.criteria());
    markup.set(
        0,
        new CriterionResult(
            "R-1a",
            Verdict.FAIL,
            Basis.DERIVED,
            Optional.empty(),
            Optional.of(BigDecimal.ZERO),
            BigDecimal.ZERO,
            "[x](http://evil.example) ![i](http://evil.example/p.png) *b* _i_ # h"));
    String marked = row(ReportWriter.render(withCriteria(r, markup), CRITERIA), "R-1a");
    assertThat(marked)
        .contains("\\[x\\]")
        .contains("\\!\\[i\\]")
        .contains("\\*b\\*")
        .contains("\\_i\\_")
        .doesNotContain(" [x]")
        .doesNotContain(" ![i]")
        .doesNotContain(" *b*")
        .doesNotContain(" _i_");
    // a heading marker at the start of a list item or a cell
    assertThat(ReportWriter.cell("# heading")).isEqualTo("\\# heading");
    assertThat(ReportWriter.cell("a # b")).isEqualTo("a # b");
    // nine columns have ten separators once the escaped pipe is not counted
    assertThat(row.replace("\\|", "").chars().filter(ch -> ch == '|').count()).isEqualTo(10L);
  }

  @Test
  void aReportOfOtherCriteriaIsRefused() {
    SpikeResult r = mixed(2);
    SpikeResult other =
        new SpikeResult(
            r.spike(),
            r.status(),
            r.scope(),
            r.evidenceLevel(),
            r.criteria(),
            r.decisions(),
            r.open(),
            r.source(),
            r.env(),
            r.timeBox(),
            "f".repeat(64),
            r.vmCount(),
            r.mixedHardware(),
            r.runs());
    assertThatThrownBy(() -> ReportWriter.render(other, CRITERIA))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("criteria");
  }
}
