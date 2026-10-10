package dev.regpack.spikes.r;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.bench.eval.Criteria;
import dev.regpack.bench.eval.Criterion;
import dev.regpack.bench.eval.MeasurementSet;
import dev.regpack.bench.eval.ResultsEvaluator;
import dev.regpack.bench.eval.SpikeResult;
import dev.regpack.bench.eval.Verdict;
import dev.regpack.bench.report.Publish;
import dev.regpack.spikes.r.SpRRunner.Plan;
import dev.regpack.spikes.r.SpRRunner.Profile;
import dev.regpack.spikes.r.SpRRunner.Step;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class SpRRunnerTest {
  private static final Map<String, String> RUNNER_ENV =
      Map.of("GITHUB_ACTIONS", "true", "GITHUB_SHA", "0123456789abcdef0123456789abcdef01234567");

  private static String text(ByteArrayOutputStream bytes) {
    return bytes.toString(StandardCharsets.UTF_8);
  }

  private static SpikeResult evaluate(Path dir) throws IOException {
    MeasurementSet set = MeasurementSet.parse(Files.readString(dir.resolve("measurements.json")));
    Criteria criteria = Criteria.load();
    return ResultsEvaluator.evaluate(
        set, criteria, "RAW", Map.of("usedMinutes", 0L, "boxMinutes", 480L));
  }

  @Test
  @Timeout(900)
  void smokeProfileProducesEverySeriesAndNoVerdict(@TempDir Path tmp) throws Exception {
    Path dir = tmp.resolve("results-local");
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    int exit =
        SpRRunner.run(
            new SpRRunner.Options(Profile.SMOKE, false, 30, dir, RUNNER_ENV, tmp),
            new PrintStream(stdout, true, StandardCharsets.UTF_8));
    assertThat(exit).isZero();

    MeasurementSet set = MeasurementSet.parse(Files.readString(dir.resolve("measurements.json")));
    Set<String> measured = new TreeSet<>();
    set.series().forEach(s -> measured.add(s.criterionId()));
    Set<String> expected = new TreeSet<>();
    for (Criterion c : Criteria.load().forSpike("SP-R")) {
      expected.add(c.id());
    }
    assertThat(measured).as("a series for every criterion of SP-R").isEqualTo(expected);
    assertThat(set.scope()).as("a smoke run is never FULL").isEqualTo("PARTIAL");
    assertThat(set.decisions()).containsKeys("expressionLayer", "executorGate");

    SpikeResult result = evaluate(dir);
    assertThat(result.status())
        .as("two forks can never give a verdict")
        .isEqualTo(Verdict.INCONCLUSIVE);

    assertThat(text(stdout)).contains("projected full duration:");

    ByteArrayOutputStream published = new ByteArrayOutputStream();
    ByteArrayOutputStream problems = new ByteArrayOutputStream();
    int sealed =
        Publish.run(
            dir,
            "local",
            RUNNER_ENV,
            tmp,
            new PrintStream(published, true, StandardCharsets.UTF_8),
            new PrintStream(problems, true, StandardCharsets.UTF_8));
    assertThat(text(problems)).isEmpty();
    assertThat(sealed).isZero();
    assertThat(text(published)).contains("status=INCONCLUSIVE");
    assertThat(dir.resolve("manifest.sha256")).exists();
  }

  @Test
  void aDeadlineThatHasPassedLeavesAPartialFileAndSaysSo(@TempDir Path tmp) throws Exception {
    Path dir = tmp.resolve("results-late");
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    int exit =
        SpRRunner.run(
            new SpRRunner.Options(Profile.SMOKE, false, 0, dir, RUNNER_ENV, tmp),
            new PrintStream(stdout, true, StandardCharsets.UTF_8));
    assertThat(exit).isZero();

    MeasurementSet set = MeasurementSet.parse(Files.readString(dir.resolve("measurements.json")));
    assertThat(set.series()).isEmpty();
    assertThat(set.scope()).isEqualTo("PARTIAL");
    assertThat(String.join("\n", set.open())).contains("deadline");
    assertThat(evaluate(dir).status()).isEqualTo(Verdict.INCONCLUSIVE);
  }

  @Test
  void everyRequiredCriterionHasASeriesInTheFullPlan() {
    Plan plan = SpRRunner.plan(Profile.FULL, false);
    assertThat(plan.criteria())
        .as("the plan produces every criterion of the spike, required or not")
        .containsExactlyInAnyOrderElementsOf(
            Criteria.load().forSpike("SP-R").stream().map(Criterion::id).toList());

    for (Criterion c : Criteria.load().forSpike("SP-R")) {
      if (c.required()) {
        assertThat(plan.criteria()).as("required " + c.id()).contains(c.id());
      }
    }
  }

  @Test
  void theFullPlanUsesTheProtocolsTheCriteriaNeed() {
    Plan plan = SpRRunner.plan(Profile.FULL, false);
    for (Step step : plan.steps()) {
      if (!step.forked()) {
        continue;
      }
      assertThat(step.forks()).as(step.id() + " forks").isGreaterThanOrEqualTo(7);
      boolean gated = step.role() == SpRRunner.Role.GATE || step.role() == SpRRunner.Role.EXPLAIN;
      assertThat(step.forks()).as(step.id()).isEqualTo(gated ? 15 : 7);
    }
    assertThat(plan.steps().stream().filter(s -> s.role() == SpRRunner.Role.GATE))
        .allSatisfy(
            s -> {
              assertThat(s.rules()).isEqualTo(200);
              assertThat(s.threads()).isEqualTo(4);
              assertThat(s.shape()).isEqualTo("S2");
            });
    assertThat(SpRRunner.plan(Profile.FULL, true).steps())
        .as("the stretch adds the S1 series")
        .hasSizeGreaterThan(plan.steps().size());
    List<Integer> sweeps =
        plan.steps().stream()
            .filter(s -> s.role() == SpRRunner.Role.SWEEP)
            .map(Step::rules)
            .distinct()
            .toList();
    assertThat(sweeps).containsExactlyInAnyOrder(50, 500, 5_000);
  }
}
