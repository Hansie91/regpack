package dev.regpack.bench.report;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.bench.eval.Criteria;
import dev.regpack.bench.eval.MeasurementSet;
import dev.regpack.bench.eval.MeasurementSet.CountValue;
import dev.regpack.bench.eval.MeasurementSet.ForkStat;
import dev.regpack.bench.eval.MeasurementSet.ForkValues;
import dev.regpack.bench.eval.MeasurementSet.RunInfo;
import dev.regpack.bench.eval.Resource;
import dev.regpack.model.json.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishTest {
  private static final Criteria CRITERIA = Criteria.load();

  @TempDir Path repo;

  private String head;
  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

  @BeforeEach
  void gitRepository() throws Exception {
    git("init", "-q");
    Files.writeString(repo.resolve("tracked.txt"), "one\n", StandardCharsets.UTF_8);
    Files.writeString(repo.resolve(".gitignore"), "results/\n", StandardCharsets.UTF_8);
    git("add", "tracked.txt", ".gitignore");
    git("commit", "-q", "-m", "one");
    head = git("rev-parse", "HEAD").trim();
  }

  private String git(String... args) throws Exception {
    List<String> command = new ArrayList<>();
    command.addAll(
        List.of(
            "git",
            "-C",
            repo.toString(),
            "-c",
            "user.name=Test",
            "-c",
            "user.email=test@example.invalid",
            "-c",
            "commit.gpgsign=false"));
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0) {
      throw new IllegalStateException("git " + List.of(args) + " failed: " + output);
    }
    return output;
  }

  private int publish(Path dir, Map<String, String> env) {
    outBytes.reset();
    errBytes.reset();
    return Publish.run(
        dir,
        "linux",
        env,
        repo,
        new PrintStream(outBytes, true, StandardCharsets.UTF_8),
        new PrintStream(errBytes, true, StandardCharsets.UTF_8));
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

  private Path measurements(String name, MeasurementSet m) throws IOException {
    Path dir = repo.resolve("results").resolve(name);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("measurements.json"), m.toJson() + "\n", StandardCharsets.UTF_8);
    return dir;
  }

  private MeasurementSet harnessSet() {
    return new MeasurementSet(
        "HARNESS",
        "c".repeat(64),
        "FULL",
        List.of(new CountValue("ST-1", 0, 1), forks("ST-2", 5, 1)),
        canary(),
        List.of(new RunInfo("100", 1, "all", head, "AMD EPYC 7763", "ubuntu24/1")),
        Map.of(),
        Map.of("cpu.model", "AMD EPYC 7763"),
        Map.of(),
        List.of());
  }

  @Test
  void localPublishRefusesADirtyWorkingTree() throws Exception {
    Path dir = measurements("selftest", harnessSet());
    Files.writeString(repo.resolve("tracked.txt"), "two\n", StandardCharsets.UTF_8);

    assertThat(publish(dir, Map.of())).isEqualTo(1);
    assertThat(err()).contains("DIRTY_TREE");
    assertThat(dir.resolve("manifest.sha256")).doesNotExist();
    assertThat(dir.resolve("summary.json")).doesNotExist();
  }

  @Test
  void aCleanLocalTreeIsPublishedWithItsCommit() throws Exception {
    Path dir = measurements("selftest", harnessSet());

    assertThat(publish(dir, Map.of())).as(err()).isEqualTo(0);
    assertThat(ResultSeal.verify(dir)).isEmpty();
    @SuppressWarnings("unchecked")
    Map<String, Object> summary =
        (Map<String, Object>)
            Json.parse(Files.readString(dir.resolve("summary.json"), StandardCharsets.UTF_8));
    @SuppressWarnings("unchecked")
    Map<String, Object> source = (Map<String, Object>) summary.get("source");
    assertThat(source.get("gitSha")).isEqualTo(head);
    assertThat(summary.get("status")).isEqualTo("PASS");
    assertThat(summary.get("evidenceLevel")).isEqualTo("RAW");
    assertThat(Files.readString(dir.resolve("report.md"), StandardCharsets.UTF_8))
        .startsWith("# HARNESS report\n");
    assertThat(out()).contains("publish: sealed").contains("status=PASS");
  }

  @Test
  void aSealedDirectoryIsNotPublishedTwice() throws Exception {
    Path dir = measurements("selftest", harnessSet());
    assertThat(publish(dir, Map.of())).isEqualTo(0);
    assertThat(publish(dir, Map.of())).isEqualTo(1);
    assertThat(err()).contains("publish:");
  }

  @Test
  void partialDirectoryIsSealedWithNotRunCriteria() throws Exception {
    MeasurementSet partial =
        new MeasurementSet(
            "SP-R",
            "d".repeat(64),
            "PARTIAL",
            List.of(new CountValue("R-1a", 0, 1), forks("R-2a", 12_000, 100)),
            canary(),
            List.of(new RunInfo("100", 1, "all", head, "AMD EPYC 7763", "ubuntu24/1")),
            Map.of(),
            Map.of(),
            Map.of(),
            List.of());
    Path dir = measurements("partial", partial);

    assertThat(publish(dir, Map.of())).as(err()).isEqualTo(0);
    assertThat(dir.resolve("manifest.sha256")).exists();
    String summary = Files.readString(dir.resolve("summary.json"), StandardCharsets.UTF_8);
    assertThat(summary).contains("\"status\":\"INCONCLUSIVE\"").contains("\"verdict\":\"NOT_RUN\"");
    assertThat(ResultSeal.verify(dir)).isEmpty();
  }

  @Test
  void aDirectoryWithoutMeasurementsExitsWithOneProblem() throws Exception {
    Path dir = repo.resolve("results/empty");
    Files.createDirectories(dir);
    assertThat(publish(dir, Map.of())).isEqualTo(1);
    assertThat(err().strip()).isEqualTo("publish: no measurements.json");
    assertThat(dir.resolve("manifest.sha256")).doesNotExist();
  }

  @Test
  void onActionsTheCommitIsGithubShaAndTheStepSummaryGetsTheResult() throws Exception {
    Path dir = measurements("selftest", harnessSet());
    // a dirty tree does not matter on a runner: the checkout is the commit
    Files.writeString(repo.resolve("tracked.txt"), "two\n", StandardCharsets.UTF_8);
    Path stepSummary = repo.resolve("step-summary.md");
    Files.writeString(stepSummary, "# earlier step\n", StandardCharsets.UTF_8);
    String sha = "1234567890abcdef1234567890abcdef12345678";
    Map<String, String> env =
        Map.of(
            "GITHUB_ACTIONS",
            "true",
            "GITHUB_SHA",
            sha,
            "GITHUB_STEP_SUMMARY",
            stepSummary.toString());

    assertThat(publish(dir, env)).as(err()).isEqualTo(0);
    String summaryJson = Files.readString(dir.resolve("summary.json"), StandardCharsets.UTF_8);
    assertThat(summaryJson).contains("\"gitSha\":\"" + sha + "\"");
    String step = Files.readString(stepSummary, StandardCharsets.UTF_8);
    assertThat(step).startsWith("# earlier step\n").contains("## Result HARNESS (linux)");
    assertThat(step).contains("```json\n" + summaryJson.strip() + "\n```");
    assertThat(step).contains("status: PASS");
  }

  @Test
  void aMalformedGithubShaIsRefused() throws Exception {
    Path dir = measurements("selftest", harnessSet());
    assertThat(publish(dir, Map.of("GITHUB_ACTIONS", "true", "GITHUB_SHA", "--help"))).isEqualTo(1);
    assertThat(err()).contains("BAD_GIT_SHA");
    assertThat(dir.resolve("manifest.sha256")).doesNotExist();
  }
}
