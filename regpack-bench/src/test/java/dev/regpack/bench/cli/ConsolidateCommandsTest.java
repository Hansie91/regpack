package dev.regpack.bench.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.bench.eval.Criteria;
import dev.regpack.bench.eval.CriterionResult;
import dev.regpack.bench.eval.MeasurementSet.RunInfo;
import dev.regpack.bench.eval.SpikeResult;
import dev.regpack.bench.eval.Verdict;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsolidateCommandsTest {
  private static final Criteria CRITERIA = Criteria.load();
  private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");

  @TempDir Path repo;

  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

  private static CriterionResult row(String id, Verdict verdict) {
    return new CriterionResult(
        id,
        verdict,
        CRITERIA.byId(id).orElseThrow().basis(),
        Optional.empty(),
        Optional.empty(),
        BigDecimal.ZERO,
        "reason of " + id);
  }

  private static SpikeResult spike(
      String id, Verdict status, Map<String, Object> decisions, CriterionResult... rows) {
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
        Map.of("usedMinutes", 60L, "boxMinutes", 480L),
        CRITERIA.digest().hex(),
        1,
        false,
        List.<RunInfo>of());
  }

  private static Map<String, Object> decisionsR() {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("decide.usPerRecordWorst", new BigDecimal("17.902"));
    Map<String, Object> readings = new LinkedHashMap<>();
    readings.put("t4S2", new BigDecimal("27795.748757954"));
    d.put("executorGateReadings", readings);
    return d;
  }

  private static Map<String, Object> decisionsL() {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("ledger.ingestCommitP99Ms", new BigDecimal("9582.423"));
    d.put("ledger.commitP99Ms", new BigDecimal("9582.423"));
    d.put("ledger.peakBacklogSeconds", new BigDecimal("30.259"));
    d.put("ledger.openLoopP99Ms", new BigDecimal("24183.532"));
    return d;
  }

  private static Map<String, Object> decisionsX() {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("assemble.latencyMs.5000", new BigDecimal("401.137"));
    d.put("assemble.latencyMs.25000", new BigDecimal("1576.724"));
    return d;
  }

  private void write(SpikeResult result) throws IOException {
    Path dir = repo.resolve("docs/spikes");
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve(result.spike() + ".summary.json"),
        result.toJson() + "\n",
        StandardCharsets.UTF_8);
  }

  @BeforeEach
  void summaries() throws IOException {
    write(spike("SP-R", Verdict.PASS, decisionsR(), row("R-1a", Verdict.PASS)));
    write(spike("SP-G", Verdict.PASS, Map.of(), row("G-1", Verdict.PASS)));
    write(spike("SP-L", Verdict.FAIL, decisionsL(), row("L-2", Verdict.INCONCLUSIVE)));
    write(spike("SP-X", Verdict.PASS, decisionsX(), row("X-1a", Verdict.PASS)));
  }

  private int bench(String... args) {
    outBytes.reset();
    errBytes.reset();
    return Bench.run(
        args,
        new PrintStream(outBytes, true, StandardCharsets.UTF_8),
        new PrintStream(errBytes, true, StandardCharsets.UTF_8),
        Map.of(),
        repo,
        NOW);
  }

  private String err() {
    return errBytes.toString(StandardCharsets.UTF_8);
  }

  private String read(String relative) throws IOException {
    return Files.readString(repo.resolve(relative), StandardCharsets.UTF_8);
  }

  @Test
  void resultsWriteIsByteReproducibleLfOnlyAndShowsTheDeferredSpikes() throws IOException {
    assertThat(bench("results", "--write", "docs/spikes/results.md")).isZero();
    byte[] first = Files.readAllBytes(repo.resolve("docs/spikes/results.md"));
    assertThat(bench("results", "--write", "docs/spikes/results.md")).isZero();
    byte[] second = Files.readAllBytes(repo.resolve("docs/spikes/results.md"));
    assertThat(second).isEqualTo(first);
    String text = new String(first, StandardCharsets.UTF_8);
    assertThat(text).doesNotContain("\r").contains("| SP-M | DEFERRED");
    assertThat(text).contains("| L-2 | INCONCLUSIVE |");
  }

  @Test
  void budgetWriteIsByteReproducibleAndPartial() throws IOException {
    assertThat(bench("budget", "--write", "docs/spikes/latency-budget.md")).isZero();
    byte[] first = Files.readAllBytes(repo.resolve("docs/spikes/latency-budget.md"));
    assertThat(bench("budget", "--write", "docs/spikes/latency-budget.md")).isZero();
    assertThat(Files.readAllBytes(repo.resolve("docs/spikes/latency-budget.md"))).isEqualTo(first);
    String text = new String(first, StandardCharsets.UTF_8);
    assertThat(text).contains("verdict: PARTIAL").doesNotContain("\r").doesNotContain("FITS");
    assertThat(text).contains("margin: 4 h = 14400000 ms");
  }

  @Test
  void budgetWithAMissingKeyPrintsMissingAndWritesNothing() throws IOException {
    Map<String, Object> noCommit = decisionsL();
    noCommit.put("ledger.commitP99Ms", "NOT_MEASURED");
    write(spike("SP-L", Verdict.FAIL, noCommit, row("L-2", Verdict.INCONCLUSIVE)));
    assertThat(bench("budget", "--write", "docs/spikes/latency-budget.md")).isEqualTo(1);
    assertThat(err()).contains("budget: MISSING SP-L ledger.commitP99Ms");
    assertThat(repo.resolve("docs/spikes/latency-budget.md")).doesNotExist();
  }

  @Test
  void budgetWithAMissingSummaryPrintsMissingAndWritesNothing() throws IOException {
    Files.delete(repo.resolve("docs/spikes/SP-X.summary.json"));
    assertThat(bench("budget", "--write", "docs/spikes/latency-budget.md")).isEqualTo(1);
    assertThat(err()).contains("budget: MISSING SP-X summary.json");
    assertThat(repo.resolve("docs/spikes/latency-budget.md")).doesNotExist();
  }

  @Test
  void aSummaryThatDoesNotParseIsRefusedForBothCommands() throws IOException {
    Files.writeString(repo.resolve("docs/spikes/SP-R.summary.json"), "{\"schema\":\"x\"}\n");
    assertThat(bench("results", "--write", "docs/spikes/results.md")).isEqualTo(1);
    assertThat(err()).contains("results: SP-R.summary.json");
    assertThat(repo.resolve("docs/spikes/results.md")).doesNotExist();
    assertThat(bench("budget", "--write", "docs/spikes/latency-budget.md")).isEqualTo(1);
    assertThat(repo.resolve("docs/spikes/latency-budget.md")).doesNotExist();
  }

  @Test
  void aSummaryOfAnotherSpikeUnderTheWrongNameIsRefused() throws IOException {
    Files.copy(
        repo.resolve("docs/spikes/SP-X.summary.json"),
        repo.resolve("docs/spikes/SP-G.summary.json"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    assertThat(bench("results", "--write", "docs/spikes/results.md")).isEqualTo(1);
    assertThat(err()).contains("holds the summary of SP-X");
  }

  @Test
  void usageErrorsExitWithTwo() {
    assertThat(bench("results")).isEqualTo(2);
    assertThat(bench("budget", "--wrte", "x")).isEqualTo(2);
  }

  @Test
  void resultsWithoutAnySummaryStillListsEverySpikeAsNoReport() throws IOException {
    for (String id : List.of("SP-R", "SP-G", "SP-L", "SP-X")) {
      Files.delete(repo.resolve("docs/spikes/" + id + ".summary.json"));
    }
    assertThat(bench("results", "--write", "docs/spikes/results.md")).isZero();
    String text = read("docs/spikes/results.md");
    assertThat(text).contains("| SP-R | NO REPORT").contains("| SP-X | NO REPORT");
  }
}
