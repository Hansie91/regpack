package dev.regpack.bench.consolidate;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.bench.eval.Basis;
import dev.regpack.bench.eval.Criteria;
import dev.regpack.bench.eval.CriterionResult;
import dev.regpack.bench.eval.MeasurementSet.RunInfo;
import dev.regpack.bench.eval.SpikeResult;
import dev.regpack.bench.eval.Verdict;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResultsTableTest {
  private static final Criteria CRITERIA = Criteria.load();

  private static CriterionResult row(String id, Verdict verdict, String reason) {
    Basis basis = CRITERIA.byId(id).orElseThrow().basis();
    return new CriterionResult(
        id, verdict, basis, Optional.empty(), Optional.empty(), BigDecimal.ZERO, reason);
  }

  private static SpikeResult spike(
      String id,
      Verdict status,
      int vmCount,
      Map<String, Object> decisions,
      CriterionResult... rows) {
    return new SpikeResult(
        id,
        status,
        "FULL",
        "RAW",
        List.of(rows),
        decisions,
        List.of(),
        Map.of(),
        Map.of(),
        Map.of("usedMinutes", 105L, "boxMinutes", 480L),
        CRITERIA.digest().hex(),
        vmCount,
        false,
        List.<RunInfo>of());
  }

  private static SpikeResult sampleSpL() {
    return spike(
        "SP-L",
        Verdict.FAIL,
        1,
        Map.of("ledger.commitP99Ms", new BigDecimal("9582.423")),
        row("L-1", Verdict.PASS, "observed 0"),
        row("L-2", Verdict.INCONCLUSIVE, "noise margin 27.7 is above the maximum; PASS needs less"),
        row("L-4", Verdict.FAIL, "observed 2, at most 0 required"),
        row("L-6", Verdict.PASS, "projected 0.01 h"),
        row("L-9", Verdict.INCONCLUSIVE, "noise margin 27.7 is above the maximum"),
        row("L-15", Verdict.NOT_RUN, "no measurements"));
  }

  private static SpikeResult sampleSpR() {
    Map<String, Object> decisions = new LinkedHashMap<>();
    decisions.put("expressionLayer", "INHOUSE");
    decisions.put("executorGate", "PASS");
    Map<String, Object> readings = new LinkedHashMap<>();
    readings.put("t4S2", new BigDecimal("27795.749"));
    decisions.put("executorGateReadings", readings);
    return spike(
        "SP-R",
        Verdict.PASS,
        1,
        decisions,
        row("R-1a", Verdict.PASS, "observed 0"),
        row("R-1b", Verdict.PASS, "observed 0"),
        row("R-2a", Verdict.PASS, "lower bound is at least"),
        row("R-2b", Verdict.FAIL, "upper bound is below"),
        row("R-3", Verdict.PASS, "recorded"),
        row("R-6a", Verdict.PASS, "observed 0"),
        row("R-6b", Verdict.PASS, "observed 0"));
  }

  private static String render(SpikeResult... results) {
    Map<String, SpikeResult> by = new LinkedHashMap<>();
    for (SpikeResult r : results) {
      by.put(r.spike(), r);
    }
    return ResultsTable.render(CRITERIA, by);
  }

  private static String line(String text, String start) {
    return text.lines()
        .filter(l -> l.startsWith(start))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no line starting with " + start));
  }

  @Test
  void inconclusiveNeverRendersAsPass() {
    String text = render(sampleSpL());
    // every line about a criterion that is not PASS, in both tables, is free of the word PASS
    for (String id : List.of("L-2", "L-4", "L-9", "L-15")) {
      List<String> lines = text.lines().filter(l -> l.startsWith("| " + id + " |")).toList();
      assertThat(lines).as(id).isNotEmpty();
      for (String l : lines) {
        assertThat(l).as(l).doesNotContain("PASS");
      }
    }
    assertThat(line(text, "| L-2 |")).contains("INCONCLUSIVE").contains("pass needs less");
    assertThat(line(text, "| L-15 |")).contains("NOT_RUN");
    assertThat(line(text, "| SP-L |")).contains("FAIL").doesNotContain("PASS");

    // and an INCONCLUSIVE spike never reads PASS either
    SpikeResult inconclusive =
        spike(
            "SP-X",
            Verdict.INCONCLUSIVE,
            1,
            Map.of(),
            row("X-1a", Verdict.INCONCLUSIVE, "noise"),
            row("X-1b", Verdict.PASS, "ok"));
    String x = render(inconclusive);
    assertThat(line(x, "| SP-X |")).contains("INCONCLUSIVE").doesNotContain("PASS");
    assertThat(line(x, "| X-1a |")).doesNotContain("PASS");
  }

  @Test
  void extrapolatedPassIsLabelled() {
    String text = render(sampleSpL());
    assertThat(line(text, "| L-6 |"))
        .contains("PASS (EXTRAPOLATED) (single VM)")
        .contains("EXTRAPOLATED");
    // a PASS that is neither extrapolated nor noisy carries no label
    assertThat(line(text, "| L-1 |")).contains("| PASS |").doesNotContain("single VM");
    // the acknowledgement list names the EXTRAPOLATED pass, the single-VM pass and the non-passes
    assertThat(text).contains("## Needs an acknowledgement in the freeze record");
    assertThat(text.lines().filter(l -> l.startsWith("| L-6 |")).count()).isEqualTo(2);
    assertThat(text).contains("| L-6 | SP-L | EXTRAPOLATED, single VM | yes |");
    assertThat(text).contains("| L-2 | SP-L | verdict INCONCLUSIVE | yes |");
    assertThat(text).contains("| L-15 | SP-L | verdict NOT_RUN | no |");

    SpikeResult manyVms =
        spike("SP-L", Verdict.PASS, 3, Map.of(), row("L-6", Verdict.PASS, "projected"));
    assertThat(line(render(manyVms), "| L-6 |")).contains("PASS (EXTRAPOLATED) |");
  }

  @Test
  void aPassInAnAlternativeThatDidNotPassSaysSo() {
    String text = render(sampleSpR());
    assertThat(line(text, "| R-1a |")).contains("| PASS |");
    assertThat(line(text, "| R-1b |"))
        .contains("PASS (alternative group layer:cel did not pass)")
        .contains("layer:cel (failed)");
    assertThat(line(text, "| R-2a |"))
        .contains("PASS (single VM)")
        .contains("layer:inhouse (passed)");
    assertThat(line(text, "| R-2b |")).contains("FAIL").doesNotContain("PASS");
    assertThat(line(text, "| SP-R |"))
        .contains("PASS (alternative group did not pass: layer:cel)")
        .contains("layer:inhouse passed; layer:cel failed");
    assertThat(text).contains("- executorGate: \"PASS\"");
    assertThat(text).contains("t4S2");
  }

  @Test
  void deferredSpikesAreListedAsDeferred() {
    String text = render(sampleSpR());
    for (String id : List.of("SP-M", "SP-S", "SP-W", "SP-N", "SP-E", "SP-O", "SP-V", "SP-D")) {
      assertThat(line(text, "| " + id + " |")).contains("DEFERRED");
    }
    assertThat(line(text, "| SP-E |")).contains("G3");
    assertThat(line(text, "| SP-M |")).contains("P1b");
  }

  @Test
  void aSpikeWithoutASummaryIsListedAsHavingNoReport() {
    String text = render(sampleSpR());
    assertThat(line(text, "| SP-G |")).contains("NO REPORT");
    assertThat(text).contains("SP-G has no committed summary");
  }

  @Test
  void theTextIsReproducibleWithLfLineEndsAndNoTimestamp() {
    String first = render(sampleSpR(), sampleSpL());
    String second = render(sampleSpL(), sampleSpR());
    assertThat(first).isEqualTo(second);
    assertThat(first).doesNotContain("\r").endsWith("\n");
    assertThat(first).doesNotContain("2026-").doesNotContainPattern("\\d{2}:\\d{2}:\\d{2}");
    assertThat(first.indexOf("## SP-R")).isLessThan(first.indexOf("## SP-L"));
  }

  @Test
  void aSummaryOfAnotherCriteriaDigestIsWarnedAbout() {
    SpikeResult r = sampleSpR();
    SpikeResult other =
        new SpikeResult(
            r.spike(),
            r.status(),
            r.scope(),
            r.evidenceLevel(),
            new ArrayList<>(r.criteria()),
            r.decisions(),
            r.open(),
            r.source(),
            r.env(),
            r.timeBox(),
            "f".repeat(64),
            r.vmCount(),
            r.mixedHardware(),
            r.runs());
    assertThat(render(other)).contains("SP-R was evaluated against another criteria digest");
  }

  private static SpikeResult withEvidence(SpikeResult r, String evidenceLevel) {
    return new SpikeResult(
        r.spike(),
        r.status(),
        r.scope(),
        evidenceLevel,
        r.criteria(),
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
  void summaryEvidenceIsNeverRenderedAsPass() {
    SpikeResult pasted = withEvidence(sampleSpR(), "SUMMARY");
    String text = render(pasted);
    // the Spikes table and the per-spike section both say PENDING with the computed status
    assertThat(line(text, "| SP-R |"))
        .contains("| PENDING (computed PASS (alternative group did not pass: layer:cel)) |")
        .contains("| SUMMARY |");
    assertThat(line(text, "- status:"))
        .isEqualTo("- status: PENDING (computed PASS (alternative group did not pass: layer:cel))");
    // no criterion verdict cell of the result is a plain PASS
    for (String id : List.of("R-1a", "R-1b", "R-2a", "R-3", "R-6a", "R-6b")) {
      assertThat(line(text, "| " + id + " |")).as(id).contains("| PENDING (computed PASS");
    }
    assertThat(line(text, "| R-1a |")).doesNotContain("| PASS");
    assertThat(line(text, "| R-2b |")).contains("| PENDING (computed FAIL) |");
    assertThat(text).contains("rests on SUMMARY evidence: it cannot satisfy a gate");
    // the same result with RAW evidence still reads PASS
    String raw = render(sampleSpR());
    assertThat(line(raw, "| R-1a |")).contains("| PASS |");
  }

  @Test
  void theLegendStatesWhatTheProjectionBoundsCover() {
    assertThat(render(sampleSpR()))
        .contains(
            "Projection bounds (Theil-Sen, label theil-sen-projection) cover the slope of the part"
                + " that grows with size only, not the intercept or any modelled term; they are not"
                + " 95% order-statistic bounds");
  }
}
