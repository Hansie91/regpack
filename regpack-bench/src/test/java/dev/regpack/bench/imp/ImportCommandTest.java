package dev.regpack.bench.imp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.bench.eval.Criteria;
import dev.regpack.bench.eval.CriterionResult;
import dev.regpack.bench.eval.MeasurementSet;
import dev.regpack.bench.eval.MeasurementSet.CountValue;
import dev.regpack.bench.eval.MeasurementSet.ForkStat;
import dev.regpack.bench.eval.MeasurementSet.ForkValues;
import dev.regpack.bench.eval.MeasurementSet.RunInfo;
import dev.regpack.bench.eval.MeasurementSet.Series;
import dev.regpack.bench.eval.Resource;
import dev.regpack.bench.eval.ResultsEvaluator;
import dev.regpack.bench.eval.SpikeResult;
import dev.regpack.bench.eval.Verdict;
import dev.regpack.bench.imp.ImportCommand.Options;
import dev.regpack.bench.report.ReportWriter;
import dev.regpack.bench.report.ResultSeal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportCommandTest {
  private static final Criteria CRITERIA = Criteria.load();
  private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");
  private static final String CONFIG_A = "a".repeat(64);
  private static final String CONFIG_B = "b".repeat(64);

  @TempDir Path repo;

  private String sha1;
  private String sha2;
  private String sideSha;
  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

  @BeforeEach
  void gitRepositoryWithTwoCommitsASideBranchAndALock() throws Exception {
    git("init", "-q");
    git("commit", "-q", "--allow-empty", "-m", "one");
    sha1 = git("rev-parse", "HEAD").trim();
    git("commit", "-q", "--allow-empty", "-m", "two");
    sha2 = git("rev-parse", "HEAD").trim();
    // a commit that exists in the repository but is not an ancestor of HEAD
    git("checkout", "-q", "-b", "side");
    git("commit", "-q", "--allow-empty", "-m", "side");
    sideSha = git("rev-parse", "HEAD").trim();
    git("checkout", "-q", "-");
    writeLock(CRITERIA.digest().hex());
  }

  // ------------------------------------------------------------------ helpers

  private String git(String... args) throws Exception {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.add("-C");
    command.add(repo.toString());
    command.add("-c");
    command.add("user.name=Test");
    command.add("-c");
    command.add("user.email=test@example.invalid");
    command.add("-c");
    command.add("commit.gpgsign=false");
    command.addAll(List.of(args));
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0) {
      throw new IllegalStateException("git " + List.of(args) + " failed: " + output);
    }
    return output;
  }

  private void writeLock(String digest) throws IOException {
    Path lock = repo.resolve("docs/spikes/criteria.lock");
    Files.createDirectories(lock.getParent());
    Files.writeString(
        lock,
        "digest: " + digest + "\nconfirmed_by: Test Owner\nconfirmed_on: 2026-10-10\n",
        StandardCharsets.UTF_8);
  }

  private int run(Options options) {
    outBytes.reset();
    errBytes.reset();
    return ImportCommand.run(
        options,
        repo,
        NOW,
        new PrintStream(outBytes, true, StandardCharsets.UTF_8),
        new PrintStream(errBytes, true, StandardCharsets.UTF_8));
  }

  private int importDirs(String spike, boolean dryRun, Path... dirs) {
    return run(new Options(spike, List.of(dirs), Optional.empty(), false, dryRun));
  }

  private String out() {
    return outBytes.toString(StandardCharsets.UTF_8);
  }

  private String err() {
    return errBytes.toString(StandardCharsets.UTF_8);
  }

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

  /** A marginal fork series: INCONCLUSIVE, so a configuration is not yet settled. */
  private static Series marginal() {
    return forks("R-2a", 10_700, 400);
  }

  private static MeasurementSet set(
      String spike, String config, String runId, String gitSha, Series... series) {
    return new MeasurementSet(
        spike,
        config,
        "FULL",
        List.of(series),
        canary(),
        List.of(new RunInfo(runId, 1, "all", gitSha, "AMD EPYC 7763", "ubuntu24/20260901.1")),
        Map.of("gitSha", gitSha),
        Map.of("cpu.model", "AMD EPYC 7763", "steal.observable", Boolean.TRUE),
        Map.of(),
        List.of());
  }

  /** Writes and seals an artefact directory the way a CI job does. */
  private Path artefact(String name, MeasurementSet m) throws IOException {
    Path dir = repo.resolve("spikes/inbox").resolve(name);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("measurements.json"), m.toJson() + "\n", StandardCharsets.UTF_8);
    SpikeResult r =
        ResultsEvaluator.evaluate(
            m, CRITERIA, "RAW", Map.of("usedMinutes", 0L, "boxMinutes", 480L));
    ResultSeal.seal(dir, r, ReportWriter.render(r, CRITERIA));
    return dir;
  }

  private Path artefact(String name, String config, String runId, String gitSha, Series... series)
      throws IOException {
    return artefact(name, set("SP-R", config, runId, gitSha, series));
  }

  /** Seals a directory whose summary.json was replaced by the given result. */
  private void resealWith(Path dir, SpikeResult tampered) throws IOException {
    String report = Files.readString(dir.resolve("report.md"), StandardCharsets.UTF_8);
    Files.delete(dir.resolve("manifest.sha256"));
    ResultSeal.seal(dir, tampered, report);
  }

  private static SpikeResult withVerdict(SpikeResult r, String id, Verdict verdict) {
    List<CriterionResult> rows = new ArrayList<>();
    for (CriterionResult c : r.criteria()) {
      rows.add(
          c.id().equals(id)
              ? new CriterionResult(
                  c.id(), verdict, c.basis(), c.estimate(), c.threshold(), c.margin(), c.reason())
              : c);
    }
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

  private static SpikeResult withDigest(SpikeResult r, String digest) {
    return new SpikeResult(
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
        digest,
        r.vmCount(),
        r.mixedHardware(),
        r.runs());
  }

  private SpikeResult summaryOf(Path dir) throws IOException {
    return SummaryJson.parse(Files.readString(dir.resolve("summary.json"), StandardCharsets.UTF_8));
  }

  private Path docs(String file) {
    return repo.resolve("docs/spikes").resolve(file);
  }

  private void assertNothingWritten() throws IOException {
    assertThat(docs("raw")).doesNotExist();
    assertThat(docs("SP-R.md")).doesNotExist();
    assertThat(docs("SP-R.summary.json")).doesNotExist();
  }

  private static List<String> fileNames(Path dir) throws IOException {
    try (Stream<Path> list = Files.list(dir)) {
      return list.map(p -> p.getFileName().toString()).sorted().toList();
    }
  }

  // ------------------------------------------------------------------ the checks

  @Test
  void verifiesTheManifest() throws Exception {
    Path dir = artefact("one", CONFIG_A, "100", sha1, new CountValue("R-1a", 0, 1));
    Path measurements = dir.resolve("measurements.json");
    String text = Files.readString(measurements, StandardCharsets.UTF_8);
    Files.writeString(measurements, text.replace("R-1a", "R-1b"), StandardCharsets.UTF_8);

    assertThat(importDirs("SP-R", false, dir)).isEqualTo(1);
    assertThat(err()).contains("HASH_MISMATCH measurements.json");
    assertThat(out()).doesNotContain("OK");
    assertNothingWritten();
  }

  @Test
  void aMissingDirectoryIsOneProblemLine() throws Exception {
    assertThat(importDirs("SP-R", false, repo.resolve("spikes/inbox/none"))).isEqualTo(1);
    assertThat(err().strip().split("\n")).hasSize(1);
    assertThat(err()).contains("MISSING manifest.sha256");
    assertNothingWritten();
  }

  @Test
  void refusesTamperedSummary() throws Exception {
    Path dir = artefact("one", CONFIG_A, "100", sha1, new CountValue("R-1a", 2, 1));
    SpikeResult honest = summaryOf(dir);
    assertThat(honest.criteria().stream().filter(c -> c.id().equals("R-1a")).findFirst())
        .map(CriterionResult::verdict)
        .contains(Verdict.FAIL);
    // the verdict is edited FAIL -> PASS and the manifest is regenerated, so the seal is clean
    resealWith(dir, withVerdict(honest, "R-1a", Verdict.PASS));
    assertThat(ResultSeal.verify(dir)).isEmpty();

    assertThat(importDirs("SP-R", false, dir)).isEqualTo(1);
    assertThat(err()).contains("MISMATCH R-1a artefact=PASS recomputed=FAIL");
    assertNothingWritten();
  }

  @Test
  void refusesCriteriaDigestMismatch() throws Exception {
    Path dir = artefact("one", CONFIG_A, "100", sha1, new CountValue("R-1a", 0, 1));
    resealWith(dir, withDigest(summaryOf(dir), "0".repeat(64)));

    assertThat(importDirs("SP-R", false, dir)).isEqualTo(1);
    assertThat(err()).contains("CRITERIA_DIGEST_MISMATCH");
    assertNothingWritten();
  }

  @Test
  void refusesALockOfOtherCriteriaAndAMissingLock() throws Exception {
    Path dir = artefact("one", CONFIG_A, "100", sha1, new CountValue("R-1a", 0, 1));

    writeLock("1".repeat(64));
    assertThat(importDirs("SP-R", true, dir)).isEqualTo(1);
    assertThat(err()).contains("CRITERIA_DIGEST_MISMATCH");

    Files.delete(docs("criteria.lock"));
    assertThat(importDirs("SP-R", true, dir)).isEqualTo(1);
    assertThat(err()).contains("NO_CRITERIA_LOCK");

    // without a lock only the explicit flag lets a run be read
    assertThat(run(new Options("SP-R", List.of(dir), Optional.empty(), true, true))).isEqualTo(0);
    assertThat(out()).contains("import: OK (dry run) SP-R");
    assertNothingWritten();
  }

  @Test
  void refusesUnknownGitSha() throws Exception {
    Path dir = artefact("one", CONFIG_A, "100", "f".repeat(40), new CountValue("R-1a", 0, 1));
    assertThat(importDirs("SP-R", false, dir)).isEqualTo(1);
    assertThat(err()).contains("UNKNOWN_GIT_SHA");
    assertNothingWritten();
  }

  @Test
  void refusesMalformedGitSha() throws Exception {
    List<String> bad =
        List.of("", "--help", "a".repeat(39), "a".repeat(41), "A".repeat(40), "HEAD");
    int n = 0;
    for (String sha : bad) {
      Path dir = artefact("bad" + n++, CONFIG_A, "100", sha, new CountValue("R-1a", 0, 1));
      assertThat(importDirs("SP-R", false, dir)).as("sha '%s'", sha).isEqualTo(1);
      // the format is refused on its own: no git process ran, so no repository verdict follows
      assertThat(err()).contains("BAD_GIT_SHA").doesNotContain("UNKNOWN_GIT_SHA");
    }
    assertNothingWritten();
  }

  @Test
  void aSpikeIdThatIsNotOneOfOursIsRefused() throws Exception {
    Path dir = artefact("one", CONFIG_A, "100", sha1, new CountValue("R-1a", 0, 1));
    for (String id : List.of("../x", "SP-r", "SP-RR", "sp-r", "HARNESS", "")) {
      assertThat(importDirs(id, false, dir)).as("id '%s'", id).isEqualTo(1);
      assertThat(err()).contains("BAD_SPIKE_ID");
    }
    // the artefact is a result of another spike
    assertThat(importDirs("SP-L", false, dir)).isEqualTo(1);
    assertThat(err()).contains("BAD_SPIKE");
    assertNothingWritten();
  }

  @Test
  void aRunIdThatIsNotAPlainTokenIsNeverUsedAsAPath() throws Exception {
    Path dir = artefact("one", CONFIG_A, "../../escape", sha1, new CountValue("R-1a", 0, 1));
    assertThat(importDirs("SP-R", false, dir)).isEqualTo(1);
    assertThat(err()).contains("BAD_RUN_ID");
    assertNothingWritten();
  }

  // ------------------------------------------------------------------ copy, pool

  @Test
  void copiesOnlyTheSmallRawFiles() throws Exception {
    Path dir = artefact("one", CONFIG_A, "100", sha1, new CountValue("R-1a", 0, 1));
    Files.writeString(dir.resolve("runner.txt"), "ImageVersion=1\n", StandardCharsets.UTF_8);
    Files.writeString(dir.resolve("big.log"), "x".repeat(10_000), StandardCharsets.UTF_8);
    Files.delete(dir.resolve("manifest.sha256"));
    ResultSeal.seal(dir, summaryOf(dir), Files.readString(dir.resolve("report.md")));

    assertThat(importDirs("SP-R", false, dir)).isEqualTo(0);
    Path copied = docs("raw/SP-R/" + CONFIG_A + "/100-1-all");
    assertThat(fileNames(copied)).containsExactly("measurements.json", "runner.txt");
    assertThat(docs("SP-R.md")).exists();
    assertThat(docs("SP-R.summary.json")).exists();
    assertThat(out()).contains("import: OK SP-R status=");
  }

  @Test
  void dryRunWritesNothingAndPrintsTheVerdict() throws Exception {
    Path dir = artefact("one", CONFIG_A, "100", sha1, new CountValue("R-1a", 0, 1));
    assertThat(importDirs("SP-R", true, dir)).isEqualTo(0);
    assertThat(out()).contains("import: OK (dry run) SP-R status=INCONCLUSIVE evidence=RAW");
    assertNothingWritten();
  }

  @Test
  void poolsEveryDispatchOfOneConfiguration() throws Exception {
    Path first = artefact("one", CONFIG_A, "100", sha1, marginal());
    Path second = artefact("two", CONFIG_A, "200", sha1, marginal());
    assertThat(importDirs("SP-R", false, first)).isEqualTo(0);
    assertThat(summaryOfCommitted().criteria().stream().filter(c -> c.id().equals("R-2a")))
        .allSatisfy(c -> assertThat(c.estimate().orElseThrow().n()).isEqualTo(15));

    assertThat(importDirs("SP-R", false, second)).isEqualTo(0);
    SpikeResult pooled = summaryOfCommitted();
    assertThat(pooled.criteria().stream().filter(c -> c.id().equals("R-2a")))
        .allSatisfy(c -> assertThat(c.estimate().orElseThrow().n()).isEqualTo(30));
    assertThat(pooled.vmCount()).isEqualTo(2);
    String report = Files.readString(docs("SP-R.md"), StandardCharsets.UTF_8);
    assertThat(report).contains("| 100 |").contains("| 200 |");
    assertThat(fileNames(docs("raw/SP-R/" + CONFIG_A))).containsExactly("100-1-all", "200-1-all");

    // a different configuration does not pool: its own 15 forks
    Path other = artefact("three", CONFIG_B, "300", sha1, marginal());
    assertThat(importDirs("SP-R", false, other)).isEqualTo(0);
    assertThat(summaryOfCommitted().criteria().stream().filter(c -> c.id().equals("R-2a")))
        .allSatisfy(c -> assertThat(c.estimate().orElseThrow().n()).isEqualTo(15));
    assertThat(fileNames(docs("raw/SP-R"))).containsExactly(CONFIG_A, CONFIG_B);
  }

  private SpikeResult summaryOfCommitted() throws IOException {
    return SummaryJson.parse(Files.readString(docs("SP-R.summary.json"), StandardCharsets.UTF_8));
  }

  @Test
  void poolingAcrossTwoGitShasIsListedAndWarned() throws Exception {
    Path first = artefact("one", CONFIG_A, "100", sha1, marginal());
    Path second = artefact("two", CONFIG_A, "200", sha2, marginal());
    assertThat(importDirs("SP-R", false, first, second)).isEqualTo(0);
    String report = Files.readString(docs("SP-R.md"), StandardCharsets.UTF_8);
    assertThat(report).contains(sha1).contains(sha2).contains("2 different git SHAs");
    assertThat(report).contains("source_git_sha: mixed");
  }

  @Test
  void differentConfigurationsInOneImportAreRefused() throws Exception {
    Path first = artefact("one", CONFIG_A, "100", sha1, marginal());
    Path second = artefact("two", CONFIG_B, "200", sha1, marginal());
    assertThat(importDirs("SP-R", false, first, second)).isEqualTo(1);
    assertThat(err()).contains("MIXED_CONFIG");
    assertNothingWritten();
  }

  @Test
  void theSameRunIsNotImportedTwice() throws Exception {
    Path dir = artefact("one", CONFIG_A, "100", sha1, marginal());
    assertThat(importDirs("SP-R", false, dir)).isEqualTo(0);
    assertThat(importDirs("SP-R", false, dir)).isEqualTo(1);
    assertThat(err()).contains("ALREADY_IMPORTED 100-1-all");
  }

  @Test
  void rerunOfAPassingConfigurationIsRefused() throws Exception {
    Path first = selftestArtefact("one", "100");
    assertThat(importDirs("SELFTEST", false, first)).isEqualTo(0);
    assertThat(summaryOfSelftest().status()).isEqualTo(Verdict.PASS);

    // a PASS is never re-run: another dispatch of the same configuration is not accepted
    Path second = selftestArtefact("two", "200");
    assertThat(importDirs("SELFTEST", false, second)).isEqualTo(1);
    assertThat(err()).contains("RERUN_NOT_JUSTIFIED");
    assertThat(fileNames(docs("raw/SELFTEST/" + CONFIG_A))).containsExactly("100-1-all");
    // the dry run applies the same rule
    assertThat(importDirs("SELFTEST", true, second)).isEqualTo(1);
    assertThat(err()).contains("RERUN_NOT_JUSTIFIED");
  }

  private Path selftestArtefact(String name, String runId) throws IOException {
    return artefact(
        name,
        set("HARNESS", CONFIG_A, runId, sha1, new CountValue("ST-1", 0, 1), forks("ST-2", 5, 1)));
  }

  private SpikeResult summaryOfSelftest() throws IOException {
    return SummaryJson.parse(
        Files.readString(docs("SELFTEST.summary.json"), StandardCharsets.UTF_8));
  }

  // ------------------------------------------------------------------ summary text

  @Test
  void summaryTextImportIsPendingAndNeverPass() throws Exception {
    Path dir = selftestArtefact("one", "100");
    String json = Files.readString(dir.resolve("summary.json"), StandardCharsets.UTF_8).strip();
    assertThat(summaryOf(dir).status()).isEqualTo(Verdict.PASS);
    Path pasted = repo.resolve("job-summary.txt");
    Files.writeString(
        pasted,
        "## Result SELFTEST (ubuntu-24.04)\n\nstatus: PASS\n\n### summary.json\n\n```json\n"
            + json
            + "\n```\n",
        StandardCharsets.UTF_8);

    int code = run(new Options("SELFTEST", List.of(), Optional.of(pasted), true, false));
    assertThat(code).isEqualTo(0);
    assertThat(out()).contains("status=PENDING").contains("evidence=SUMMARY");

    String report = Files.readString(docs("SELFTEST.md"), StandardCharsets.UTF_8);
    assertThat(report).contains("status: PENDING").contains("evidence_level: SUMMARY");
    assertThat(List.of(report.split("\n"))).doesNotContain("status: PASS");
    SpikeResult stored = summaryOfSelftest();
    assertThat(stored.evidenceLevel()).isEqualTo("SUMMARY");
    // nothing was recomputed or copied
    assertThat(docs("raw")).doesNotExist();
  }

  @Test
  void summaryTextImportRefusesWhatIsNotOneSummaryOfThisSpike() throws Exception {
    Path pasted = repo.resolve("job-summary.txt");
    Files.writeString(pasted, "no json here\n", StandardCharsets.UTF_8);
    assertThat(run(new Options("SELFTEST", List.of(), Optional.of(pasted), true, false)))
        .isEqualTo(1);
    assertThat(err()).contains("NO_SUMMARY_JSON");

    Path dir = artefact("one", CONFIG_A, "100", sha1, new CountValue("R-1a", 0, 1));
    String json = Files.readString(dir.resolve("summary.json"), StandardCharsets.UTF_8).strip();
    Files.writeString(pasted, "```json\n" + json + "\n```\n", StandardCharsets.UTF_8);
    assertThat(run(new Options("SELFTEST", List.of(), Optional.of(pasted), true, false)))
        .isEqualTo(1);
    assertThat(err()).contains("BAD_SPIKE");
    assertThat(docs("SELFTEST.md")).doesNotExist();
  }

  @Test
  void aPendingReportNeverReplacesARawReport() throws Exception {
    Path dir = selftestArtefact("one", "100");
    assertThat(importDirs("SELFTEST", false, dir)).isEqualTo(0);
    String json = Files.readString(dir.resolve("summary.json"), StandardCharsets.UTF_8).strip();
    Path pasted = repo.resolve("job-summary.txt");
    Files.writeString(pasted, "```json\n" + json + "\n```\n", StandardCharsets.UTF_8);
    assertThat(run(new Options("SELFTEST", List.of(), Optional.of(pasted), true, false)))
        .isEqualTo(1);
    assertThat(err()).contains("RAW_REPORT_EXISTS");
    assertThat(summaryOfSelftest().evidenceLevel()).isEqualTo("RAW");
  }

  // ------------------------------------------------------------------ the report

  @Test
  void handWrittenInterpretationBelowTheTripleDashIsKept() throws Exception {
    Path first = artefact("one", CONFIG_A, "100", sha1, marginal());
    assertThat(importDirs("SP-R", false, first)).isEqualTo(0);
    Path report = docs("SP-R.md");
    String generated = Files.readString(report, StandardCharsets.UTF_8);
    assertThat(generated).endsWith("\n---\n");
    String tail = "\n## Interpretation\n\nWritten by hand: the lower bound is below the limit.\n";
    Files.writeString(report, generated + tail, StandardCharsets.UTF_8);

    Path second = artefact("two", CONFIG_A, "200", sha1, marginal());
    assertThat(importDirs("SP-R", false, second)).isEqualTo(0);
    String after = Files.readString(report, StandardCharsets.UTF_8);
    assertThat(after).endsWith("\n---\n" + tail);
    assertThat(after).contains("| 200 |");
    // exactly one generated part
    assertThat(after.split("\n---\n", -1)).hasSize(2);
  }

  // ------------------------------------------------------------------ verify-committed

  @Test
  void verifyCommittedAcceptsWhatImportWrote() throws Exception {
    Path dir = artefact("one", CONFIG_A, "100", sha1, new CountValue("R-1a", 2, 1), marginal());
    assertThat(importDirs("SP-R", false, dir)).isEqualTo(0);
    int code =
        ImportCommand.verifyCommitted(
            repo,
            new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    assertThat(code).as(err()).isEqualTo(0);
    assertThat(out()).contains("verify-committed: OK 1 report");
  }

  @Test
  void verifyCommittedFailsWhenAVerdictWasEdited() throws Exception {
    Path dir = artefact("one", CONFIG_A, "100", sha1, new CountValue("R-1a", 2, 1), marginal());
    assertThat(importDirs("SP-R", false, dir)).isEqualTo(0);
    Path report = docs("SP-R.md");
    String text = Files.readString(report, StandardCharsets.UTF_8);
    assertThat(text).contains("| R-2a | INCONCLUSIVE |");
    Files.writeString(
        report, text.replace("| R-2a | INCONCLUSIVE |", "| R-2a | PASS |"), StandardCharsets.UTF_8);

    assertThat(verify()).isEqualTo(1);
    assertThat(err()).contains("SP-R.md");
  }

  @Test
  void verifyCommittedFailsWhenTheSummaryVerdictWasEdited() throws Exception {
    Path dir = artefact("one", CONFIG_A, "100", sha1, new CountValue("R-1a", 2, 1), marginal());
    assertThat(importDirs("SP-R", false, dir)).isEqualTo(0);
    Path summary = docs("SP-R.summary.json");
    SpikeResult committed = summaryOfCommitted();
    Files.writeString(
        summary,
        withVerdict(committed, "R-1a", Verdict.PASS).toJson() + "\n",
        StandardCharsets.UTF_8);
    assertThat(verify()).isEqualTo(1);
    assertThat(err()).contains("MISMATCH R-1a committed=PASS recomputed=FAIL");
  }

  @Test
  void verifyCommittedRecomputesFromTheCommittedRawFiles() throws Exception {
    Path dir = artefact("one", CONFIG_A, "100", sha1, new CountValue("R-1a", 2, 1), marginal());
    assertThat(importDirs("SP-R", false, dir)).isEqualTo(0);
    assertThat(verify()).isEqualTo(0);

    Path raw = docs("raw/SP-R/" + CONFIG_A + "/100-1-all/measurements.json");
    String text = Files.readString(raw, StandardCharsets.UTF_8);
    assertThat(text).contains("\"observed\":2");
    Files.writeString(
        raw, text.replace("\"observed\":2", "\"observed\":0"), StandardCharsets.UTF_8);

    assertThat(verify()).isEqualTo(1);
    assertThat(err()).contains("MISMATCH R-1a committed=FAIL recomputed=PASS");
  }

  @Test
  void verifyCommittedWithNothingCommittedIsClean() {
    assertThat(verify()).isEqualTo(0);
    assertThat(out()).contains("verify-committed: OK 0 report");
  }

  @Test
  void verifyCommittedChecksAPendingReportToo() throws Exception {
    Path dir = selftestArtefact("one", "100");
    String json = Files.readString(dir.resolve("summary.json"), StandardCharsets.UTF_8).strip();
    Path pasted = repo.resolve("job-summary.txt");
    Files.writeString(pasted, "```json\n" + json + "\n```\n", StandardCharsets.UTF_8);
    assertThat(run(new Options("SELFTEST", List.of(), Optional.of(pasted), true, false)))
        .isEqualTo(0);
    assertThat(verify()).isEqualTo(0);

    Path report = docs("SELFTEST.md");
    String text = Files.readString(report, StandardCharsets.UTF_8);
    Files.writeString(
        report, text.replace("status: PENDING", "status: PASS"), StandardCharsets.UTF_8);
    assertThat(verify()).isEqualTo(1);
  }

  // ------------------------------------------------------------------ fix round 1

  private static final int MAX = 2 * 1024 * 1024;

  private void writeLedger(String id, String tsv) throws IOException {
    Path file = docs("timebox/" + id + ".tsv");
    Files.createDirectories(file.getParent());
    Files.writeString(file, tsv, StandardCharsets.UTF_8);
  }

  private static String ago(String duration) {
    return NOW.minus(Duration.parse("PT" + duration)).toString();
  }

  /** The in-house layer passes, the cel layer is not run: PASS until a time-box caps it. */
  private static MeasurementSet inhouseLayerOnly(String config, String runId, String gitSha) {
    return set(
        "SP-R",
        config,
        runId,
        gitSha,
        new CountValue("R-1a", 0, 1),
        forks("R-2a", 12_000, 400),
        new CountValue("R-6a", 0, 1),
        forks("R-4", 50, 5),
        new CountValue("R-5", 0, 1),
        new CountValue("R-7", 0, 1));
  }

  private static void deleteTree(Path root) throws IOException {
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
  }

  private static void padTo(Path file, int bytes) throws IOException {
    String text = Files.readString(file, StandardCharsets.UTF_8).stripTrailing();
    int have = text.getBytes(StandardCharsets.UTF_8).length;
    assertThat(have).isLessThanOrEqualTo(bytes);
    Files.writeString(file, text + " ".repeat(bytes - have), StandardCharsets.UTF_8);
    assertThat(Files.size(file)).isEqualTo(bytes);
  }

  // the time ledger reaches the report (RF6: a time-box silently exceeded)

  @Test
  void aRunningLedgerPastTheBoxCapsThePassAndTheReportSaysHit() throws Exception {
    Path dir = artefact("one", inhouseLayerOnly(CONFIG_A, "100", sha1));
    assertThat(importDirs("SP-R", true, dir)).isEqualTo(0);
    assertThat(out()).contains("status=PASS");

    // ten hours running against a box of eight; the ledger of another spike is never read
    writeLedger("SP-R", ago("10H") + "\tSTART\t\n");
    writeLedger("SP-L", ago("40H") + "\tSTART\t\n");
    assertThat(importDirs("SP-R", true, dir)).isEqualTo(0);
    assertThat(out()).contains("status=INCONCLUSIVE");

    assertThat(importDirs("SP-R", false, dir)).isEqualTo(0);
    assertThat(out()).contains("import: OK SP-R status=INCONCLUSIVE");
    SpikeResult stored = summaryOfCommitted();
    assertThat(stored.status()).isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(stored.usedMinutes()).isEqualByComparingTo("600");
    assertThat(stored.boxMinutes()).isEqualByComparingTo("480");
    assertThat(stored.timeBox().get("hit")).isEqualTo(Boolean.TRUE);
    String report = Files.readString(docs("SP-R.md"), StandardCharsets.UTF_8);
    assertThat(report).contains("time_box: used 600 of 480 minutes, hit: yes");
    assertThat(report).contains("status: INCONCLUSIVE");
    // the committed report is what the evidence and the ledger numbers give
    assertThat(verify()).as(err()).isEqualTo(0);
  }

  @Test
  void aLedgerAtExactlyTheBoxIsHit() throws Exception {
    writeLedger("SP-R", ago("8H") + "\tSTART\t\n");
    Path dir = artefact("one", inhouseLayerOnly(CONFIG_A, "100", sha1));
    assertThat(importDirs("SP-R", false, dir)).isEqualTo(0);
    assertThat(Files.readString(docs("SP-R.md"), StandardCharsets.UTF_8))
        .contains("time_box: used 480 of 480 minutes, hit: yes")
        .contains("status: INCONCLUSIVE");
  }

  @Test
  void aPausedLedgerCountsOnlyTheActiveMinutes() throws Exception {
    // 60 minutes, a wait of eight hours, 60 minutes and still running
    writeLedger(
        "SP-R",
        ago("10H")
            + "\tSTART\t\n"
            + ago("9H")
            + "\tPAUSE\twaiting for the owner\n"
            + ago("1H")
            + "\tRESUME\t\n");
    Path dir = artefact("one", inhouseLayerOnly(CONFIG_A, "100", sha1));
    assertThat(importDirs("SP-R", false, dir)).isEqualTo(0);
    assertThat(summaryOfCommitted().usedMinutes()).isEqualByComparingTo("120");
    String report = Files.readString(docs("SP-R.md"), StandardCharsets.UTF_8);
    assertThat(report).contains("time_box: used 120 of 480 minutes, hit: no");
    assertThat(report).contains("status: PASS");
  }

  @Test
  void theLedgerOfAnotherIdIsNotTheLedgerOfTheSelftest() throws Exception {
    writeLedger("SP-R", ago("10H") + "\tSTART\t\n");
    Path dir = selftestArtefact("one", "100");
    assertThat(importDirs("SELFTEST", false, dir)).isEqualTo(0);
    assertThat(summaryOfSelftest().usedMinutes()).isEqualByComparingTo("0");

    writeLedger("SELFTEST", ago("2H") + "\tSTART\t\n");
    Path two = artefact("two", set("HARNESS", CONFIG_B, "200", sha1, new CountValue("ST-1", 0, 1)));
    assertThat(importDirs("SELFTEST", false, two)).isEqualTo(0);
    assertThat(summaryOfSelftest().usedMinutes()).isEqualByComparingTo("120");
    assertThat(summaryOfSelftest().boxMinutes()).isEqualByComparingTo("480");
  }

  @Test
  void aCorruptLedgerIsOneProblemLineAndNothingIsWritten() throws Exception {
    Path dir = artefact("one", inhouseLayerOnly(CONFIG_A, "100", sha1));
    writeLedger("SP-R", ago("1H") + "\tSTART\t\n" + ago("2H") + "\tPAUSE\t\n");
    for (boolean dryRun : new boolean[] {true, false}) {
      assertThat(importDirs("SP-R", dryRun, dir)).isEqualTo(1);
      assertThat(err().strip().split("\n")).hasSize(1);
      assertThat(err()).contains("BAD_TIME_LEDGER SP-R").contains("line 2");
      assertThat(out()).doesNotContain("OK");
      assertNothingWritten();
    }
    writeLedger("SP-R", "yesterday\tSTART\t\n");
    assertThat(importDirs("SP-R", false, dir)).isEqualTo(1);
    assertThat(err()).contains("BAD_TIME_LEDGER SP-R");
    assertNothingWritten();
  }

  // a committed report without its evidence (RF4: a hand-edited verdict)

  @Test
  void verifyCommittedFailsForAReportWithoutASummary() throws Exception {
    Files.createDirectories(docs("."));
    Files.writeString(
        docs("SP-R.md"),
        "# SP-R\n\nstatus: PASS\nevidence_level: RAW\n\n---\n",
        StandardCharsets.UTF_8);
    assertThat(verify()).isEqualTo(1);
    assertThat(err()).contains("MISSING SP-R.summary.json");
    assertThat(out()).doesNotContain("OK");
  }

  @Test
  void verifyCommittedFailsWhenTheSummaryOrTheRawFilesWereDeleted() throws Exception {
    Path dir = artefact("one", CONFIG_A, "100", sha1, new CountValue("R-1a", 2, 1), marginal());
    assertThat(importDirs("SP-R", false, dir)).isEqualTo(0);
    assertThat(verify()).isEqualTo(0);

    deleteTree(docs("raw"));
    assertThat(verify()).isEqualTo(1);
    assertThat(err()).contains("MISSING_RAW SP-R");

    Files.delete(docs("SP-R.summary.json"));
    assertThat(verify()).isEqualTo(1);
    assertThat(err()).contains("MISSING SP-R.summary.json");
  }

  @Test
  void otherMarkdownFilesAreNotReports() throws Exception {
    Files.createDirectories(docs("."));
    for (String name : List.of("README.md", "criteria.md", "decisions.md", "sp-r.md", "SP-RR.md")) {
      Files.writeString(docs(name), "status: PASS\n", StandardCharsets.UTF_8);
    }
    assertThat(verify()).as(err()).isEqualTo(0);
    assertThat(out()).contains("verify-committed: OK 0 report");
  }

  // the commit of the run (RF4)

  @Test
  void theCommitOfTheRunMustBeTheCommitOfTheSummary() throws Exception {
    // run sha, summary sha
    List<List<String>> cases =
        List.of(
            List.of("--help", sha1),
            List.of("not a sha", sha1),
            List.of(sha2, sha1),
            List.of(sideSha, sha1),
            List.of(sha1, "--help"));
    int n = 0;
    for (List<String> c : cases) {
      MeasurementSet m =
          new MeasurementSet(
              "SP-R",
              CONFIG_A,
              "FULL",
              List.of(new CountValue("R-1a", 0, 1)),
              canary(),
              List.of(new RunInfo("100", 1, "all", c.get(0), "AMD EPYC 7763", "ubuntu24/1")),
              Map.of("gitSha", c.get(1)),
              Map.of("cpu.model", "AMD EPYC 7763", "steal.observable", Boolean.TRUE),
              Map.of(),
              List.of());
      Path dir = artefact("case" + n++, m);
      assertThat(importDirs("SP-R", false, dir)).as("run sha and summary sha %s", c).isEqualTo(1);
      // the format and the equality are decided before any process is started
      assertThat(err()).contains("BAD_GIT_SHA").doesNotContain("UNKNOWN_GIT_SHA");
    }
    assertNothingWritten();
  }

  @Test
  void ancestryIsNotJustTheExistenceOfTheCommit() throws Exception {
    Path side = artefact("side", CONFIG_A, "100", sideSha, new CountValue("R-1a", 0, 1));
    assertThat(importDirs("SP-R", true, side)).isEqualTo(1);
    assertThat(err()).contains("UNKNOWN_GIT_SHA " + sideSha);

    for (String sha : List.of(sha1, sha2)) {
      Path ok = artefact("ok" + sha, CONFIG_A, "100", sha, new CountValue("R-1a", 0, 1));
      assertThat(importDirs("SP-R", true, ok)).as(sha).isEqualTo(0);
    }
    assertNothingWritten();
  }

  // the size limits on data from outside (Convention 8)

  @Test
  void aMeasurementsFileOverTwoMegabytesIsRefusedAndExactlyTwoIsNot() throws Exception {
    Path dir = artefact("one", CONFIG_A, "100", sha1, new CountValue("R-1a", 0, 1));
    Path measurements = dir.resolve("measurements.json");

    padTo(measurements, MAX);
    resealWith(dir, summaryOf(dir));
    assertThat(ResultSeal.verify(dir)).isEmpty();
    assertThat(importDirs("SP-R", true, dir)).as(err()).isEqualTo(0);

    padTo(measurements, MAX + 1);
    resealWith(dir, summaryOf(dir));
    assertThat(ResultSeal.verify(dir)).isEmpty();
    assertThat(importDirs("SP-R", false, dir)).isEqualTo(1);
    assertThat(err().strip().split("\n")).hasSize(1);
    assertThat(err()).contains("TOO_LARGE measurements.json");
    assertNothingWritten();
  }

  @Test
  void aRunnerFileOverTwoMegabytesIsRefused() throws Exception {
    Path dir = artefact("one", CONFIG_A, "100", sha1, new CountValue("R-1a", 0, 1));
    Files.writeString(dir.resolve("runner.txt"), "x".repeat(MAX), StandardCharsets.UTF_8);
    resealWith(dir, summaryOf(dir));
    assertThat(importDirs("SP-R", true, dir)).as(err()).isEqualTo(0);

    Files.writeString(dir.resolve("runner.txt"), "x".repeat(MAX + 1), StandardCharsets.UTF_8);
    resealWith(dir, summaryOf(dir));
    assertThat(importDirs("SP-R", false, dir)).isEqualTo(1);
    assertThat(err()).contains("TOO_LARGE runner.txt");
    assertNothingWritten();
  }

  @Test
  void aPastedSummaryOrALockOverTwoMegabytesIsRefused() throws Exception {
    Path big = repo.resolve("job-summary.txt");
    Files.writeString(big, "x".repeat(MAX + 1), StandardCharsets.UTF_8);
    assertThat(run(new Options("SELFTEST", List.of(), Optional.of(big), true, false))).isEqualTo(1);
    assertThat(err()).contains("TOO_LARGE the summary text");
    assertThat(docs("SELFTEST.md")).doesNotExist();

    Path dir = artefact("one", CONFIG_A, "100", sha1, new CountValue("R-1a", 0, 1));
    Files.writeString(
        docs("criteria.lock"),
        "digest: " + CRITERIA.digest().hex() + "\n" + " ".repeat(MAX),
        StandardCharsets.UTF_8);
    assertThat(importDirs("SP-R", true, dir)).isEqualTo(1);
    assertThat(err()).contains("TOO_LARGE criteria.lock");
    assertNothingWritten();
  }

  // pooling is decided before anything is copied

  @Test
  void theDryRunPoolsWithTheRunsAlreadyImported() throws Exception {
    Path first =
        artefact(
            "one",
            set(
                "SP-R",
                CONFIG_A,
                "100",
                sha1,
                new CountValue("R-1a", 0, 1),
                new CountValue("R-6a", 0, 1),
                forks("R-4", 50, 5),
                new CountValue("R-5", 0, 1),
                new CountValue("R-7", 0, 1)));
    Path second = artefact("two", set("SP-R", CONFIG_A, "200", sha1, forks("R-2a", 12_000, 400)));
    assertThat(importDirs("SP-R", false, first)).isEqualTo(0);
    assertThat(out()).contains("status=INCONCLUSIVE");

    // alone the second dispatch has one series; pooled with the first it settles the spike
    assertThat(importDirs("SP-R", true, second)).as(err()).isEqualTo(0);
    assertThat(out()).contains("import: OK (dry run) SP-R status=PASS evidence=RAW");
    assertThat(importDirs("SP-R", false, second)).isEqualTo(0);
    assertThat(out()).contains("import: OK SP-R status=PASS evidence=RAW runs=2");
    assertThat(summaryOfCommitted().status()).isEqualTo(Verdict.PASS);
  }

  @Test
  void aFailedPoolingLeavesNoRawDirectoryBehind() throws Exception {
    Path first = artefact("one", CONFIG_A, "100", sha1, marginal());
    assertThat(importDirs("SP-R", false, first)).isEqualTo(0);
    String reportBefore = Files.readString(docs("SP-R.md"), StandardCharsets.UTF_8);

    // the same configuration, but the series differ in scale: the sets cannot be pooled
    ForkValues marginal = (ForkValues) marginal();
    Series rescaled =
        new ForkValues(marginal.criterionId(), marginal.unit(), BigDecimal.TEN, marginal.forks());
    Path second = artefact("two", CONFIG_A, "200", sha1, rescaled);
    assertThat(importDirs("SP-R", true, second)).isEqualTo(1);
    assertThat(err()).contains("CANNOT_POOL");
    assertThat(importDirs("SP-R", false, second)).isEqualTo(1);
    assertThat(err().strip().split("\n")).hasSize(1);
    assertThat(err()).contains("CANNOT_POOL R-2a");

    assertThat(fileNames(docs("raw/SP-R/" + CONFIG_A))).containsExactly("100-1-all");
    assertThat(Files.readString(docs("SP-R.md"), StandardCharsets.UTF_8)).isEqualTo(reportBefore);
    // nothing is stuck: the committed state still verifies and a good dispatch is accepted
    assertThat(verify()).as(err()).isEqualTo(0);
    Path third = artefact("three", CONFIG_A, "300", sha1, marginal());
    assertThat(importDirs("SP-R", false, third)).as(err()).isEqualTo(0);
  }

  @Test
  void theReportDoesNotDependOnTheOrderOfTheImports() throws Exception {
    Path later = artefact("one", CONFIG_A, "200", sha1, marginal());
    Path earlier = artefact("two", CONFIG_A, "100", sha1, marginal());
    assertThat(importDirs("SP-R", false, later)).isEqualTo(0);
    assertThat(importDirs("SP-R", false, earlier)).isEqualTo(0);
    // verify-committed recomputes from the raw directories in name order, byte for byte
    assertThat(verify()).as(err()).isEqualTo(0);
  }

  // the lock cannot be skipped by a real import (RF6: thresholds tuned after the results)

  @Test
  void aRealImportNeedsTheLockExceptForTheSelftest() throws Exception {
    Files.delete(docs("criteria.lock"));
    Path dir = artefact("one", CONFIG_A, "100", sha1, new CountValue("R-1a", 0, 1));

    assertThat(run(new Options("SP-R", List.of(dir), Optional.empty(), true, false))).isEqualTo(1);
    assertThat(err()).contains("NO_CRITERIA_LOCK");
    assertNothingWritten();
    assertThat(run(new Options("SP-R", List.of(dir), Optional.empty(), false, false))).isEqualTo(1);
    assertThat(err()).contains("NO_CRITERIA_LOCK");
    assertNothingWritten();

    // the dry run may look without a lock, and the selftest has no thresholds to tune
    assertThat(run(new Options("SP-R", List.of(dir), Optional.empty(), true, true))).isEqualTo(0);
    Path self = selftestArtefact("self", "100");
    assertThat(run(new Options("SELFTEST", List.of(self), Optional.empty(), true, false)))
        .as(err())
        .isEqualTo(0);
    assertThat(docs("SELFTEST.md")).exists();
  }

  @Test
  void aPastedSummaryIsHeldToTheLockToo() throws Exception {
    Path dir = artefact("one", CONFIG_A, "100", sha1, new CountValue("R-1a", 0, 1));
    String json = Files.readString(dir.resolve("summary.json"), StandardCharsets.UTF_8).strip();
    Path pasted = repo.resolve("job-summary.txt");
    Files.writeString(pasted, "```json\n" + json + "\n```\n", StandardCharsets.UTF_8);

    writeLock("1".repeat(64));
    assertThat(run(new Options("SP-R", List.of(), Optional.of(pasted), false, false))).isEqualTo(1);
    assertThat(err()).contains("CRITERIA_DIGEST_MISMATCH");
    assertThat(docs("SP-R.md")).doesNotExist();

    Files.delete(docs("criteria.lock"));
    assertThat(run(new Options("SP-R", List.of(), Optional.of(pasted), false, false))).isEqualTo(1);
    assertThat(err()).contains("NO_CRITERIA_LOCK");
    assertThat(run(new Options("SP-R", List.of(), Optional.of(pasted), true, false))).isEqualTo(1);
    assertThat(err()).contains("NO_CRITERIA_LOCK");
    assertThat(docs("SP-R.md")).doesNotExist();

    assertThat(run(new Options("SP-R", List.of(), Optional.of(pasted), true, true))).isEqualTo(0);
    assertThat(out()).contains("(dry run)");
    assertThat(docs("SP-R.md")).doesNotExist();

    writeLock(CRITERIA.digest().hex());
    assertThat(run(new Options("SP-R", List.of(), Optional.of(pasted), false, false)))
        .as(err())
        .isEqualTo(0);
    assertThat(docs("SP-R.md")).exists();
  }

  private int verify() {
    outBytes.reset();
    errBytes.reset();
    return ImportCommand.verifyCommitted(
        repo,
        new PrintStream(outBytes, true, StandardCharsets.UTF_8),
        new PrintStream(errBytes, true, StandardCharsets.UTF_8));
  }
}
