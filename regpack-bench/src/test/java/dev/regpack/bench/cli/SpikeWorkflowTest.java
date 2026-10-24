package dev.regpack.bench.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.regpack.tools.PinCheck;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The on-demand workflow is a text file; these tests read it as text (no YAML library) and guard
 * the failure modes of RF4 (the result directory is sealed last), RF8 (no expression inside a run
 * block, pinned actions, read-only permissions) and RF11 (the run receives the PostgreSQL URL).
 */
class SpikeWorkflowTest {
  private static final String RUN_JOB = "run";
  private static final String PLAN_JOB = "plan";
  private static final String SEAL = "Seal the result directory (last write)";
  private static final String STOP_PG = "Stop PostgreSQL and collect its logs";
  private static final String START_PG = "Start PostgreSQL";
  private static final String IT = "Integration tests";
  private static final String RUN_SPIKE = "Run the spike";
  private static final Pattern MATRIX_ENTRY =
      Pattern.compile(
          "\\{\"spike\":\"([a-z-]+)\",\"part\":\"([a-z]+)\",\"timeout\":(\\d+),"
              + "\"deadline\":(\\d+),\"pg\":\"(true|false)\"\\}");

  /** One step of a job: its raw text, its name, its {@code if}, and the text of its run block. */
  record Step(String text, String name, String condition, String run) {
    boolean writes() {
      return !run.isEmpty();
    }
  }

  private static Path repo() {
    return Path.of(System.getProperty("regpack.repo.root"));
  }

  private static String read(String relative) throws IOException {
    return Files.readString(repo().resolve(relative), StandardCharsets.UTF_8);
  }

  private static String spikesRun() throws IOException {
    return read(".github/workflows/spikes-run.yml");
  }

  private static String ci() throws IOException {
    return read(".github/workflows/ci.yml");
  }

  private static int indent(String line) {
    return line.length() - line.stripLeading().length();
  }

  /** The lines below the first line equal to {@code key} (trailing blanks ignored), deeper in. */
  static List<String> block(String yaml, String key) {
    List<String> lines = yaml.lines().toList();
    List<String> out = new ArrayList<>();
    int start = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).stripTrailing().equals(key)) {
        start = i;
        break;
      }
    }
    if (start < 0) {
      return out;
    }
    int keyIndent = indent(lines.get(start));
    for (int i = start + 1; i < lines.size(); i++) {
      String line = lines.get(i);
      if (!line.isBlank() && indent(line) <= keyIndent) {
        break;
      }
      out.add(line);
    }
    return out;
  }

  /** The steps of the job named {@code job}, in file order. */
  static List<Step> steps(String yaml, String job) {
    List<String> jobBlock = block(yaml, "  " + job + ":");
    List<String> stepsBlock = new ArrayList<>();
    boolean in = false;
    for (String line : jobBlock) {
      if (line.stripTrailing().equals("    steps:")) {
        in = true;
        continue;
      }
      if (in && !line.isBlank() && indent(line) <= 4) {
        break;
      }
      if (in) {
        stepsBlock.add(line);
      }
    }
    List<List<String>> groups = new ArrayList<>();
    for (String line : stepsBlock) {
      if (line.startsWith("      - ")) {
        groups.add(new ArrayList<>());
      }
      if (!groups.isEmpty()) {
        groups.get(groups.size() - 1).add(line);
      }
    }
    List<Step> out = new ArrayList<>();
    for (List<String> group : groups) {
      out.add(toStep(group));
    }
    return out;
  }

  private static Step toStep(List<String> group) {
    String name = "";
    String condition = "";
    StringBuilder run = new StringBuilder();
    Pattern key = Pattern.compile("^\\s*(?:-\\s+)?([a-z-]+):\\s*(.*)$");
    for (int i = 0; i < group.size(); i++) {
      Matcher m = key.matcher(group.get(i));
      if (!m.matches() || indent(group.get(i).replaceFirst("^(\\s*)- ", "$1  ")) != 8) {
        continue;
      }
      switch (m.group(1)) {
        case "name" -> name = m.group(2).trim();
        case "if" -> condition = m.group(2).trim();
        case "run" -> {
          String value = m.group(2).trim();
          if (value.startsWith("|") || value.startsWith(">")) {
            for (int j = i + 1; j < group.size(); j++) {
              String line = group.get(j);
              if (!line.isBlank() && indent(line) <= 8) {
                break;
              }
              run.append(line.strip()).append('\n');
            }
          } else {
            run.append(value).append('\n');
          }
        }
        default -> {}
      }
    }
    return new Step(String.join("\n", group), name, condition, run.toString());
  }

  /** The job-level {@code env:} block of the run job, as one string. */
  private static String jobEnv(String yaml) {
    return String.join("\n", block(yaml, "    env:"));
  }

  private static int indexOf(List<Step> steps, String name) {
    for (int i = 0; i < steps.size(); i++) {
      if (steps.get(i).name().equals(name)) {
        return i;
      }
    }
    return -1;
  }

  private static Step named(List<Step> steps, String name) {
    int i = indexOf(steps, name);
    assertThat(i).as("step named " + name).isNotNegative();
    return steps.get(i);
  }

  /** Every {@code ${{} found inside a run block of any job, as "job/step: line". */
  static List<String> expressionsInRunBlocks(String yaml) {
    List<String> found = new ArrayList<>();
    for (String job : List.of(PLAN_JOB, RUN_JOB)) {
      for (Step step : steps(yaml, job)) {
        for (String line : step.run().split("\n")) {
          if (line.contains("${{")) {
            found.add(job + "/" + step.name() + ": " + line);
          }
        }
      }
    }
    return found;
  }

  /**
   * The problems with the order around the seal: it must come after the PostgreSQL stop, run
   * always, and be followed only by steps that write nothing.
   */
  static List<String> sealProblems(String yaml) {
    List<Step> steps = steps(yaml, RUN_JOB);
    List<String> problems = new ArrayList<>();
    int seal = indexOf(steps, SEAL);
    int stop = indexOf(steps, STOP_PG);
    if (seal < 0) {
      return List.of("no seal step");
    }
    if (stop < 0 || stop > seal) {
      problems.add("the seal does not come after the PostgreSQL stop step");
    }
    if (!steps.get(seal).condition().equals("always()")
        && !steps.get(seal).condition().equals("${{ always() }}")) {
      problems.add("the seal step does not run always()");
    }
    if (!steps.get(seal).run().contains("publish --dir")) {
      problems.add("the seal step does not run bench publish");
    }
    for (int i = seal + 1; i < steps.size(); i++) {
      Step later = steps.get(i);
      if (later.writes()) {
        problems.add("step after the seal has a run block: " + later.name());
      }
      if (later.text().contains("results/") && !later.text().contains("path: results/")) {
        problems.add("step after the seal mentions results/: " + later.name());
      }
      if (!later.text().contains("actions/upload-artifact@")) {
        problems.add("step after the seal is not the upload: " + later.name());
      }
    }
    if (steps.size() - seal != 2) {
      problems.add("the seal must be followed by exactly one step (the upload)");
    }
    return problems;
  }

  @Test
  void onlyWorkflowDispatchWithChoiceAndBooleanInputs() throws IOException {
    String yaml = spikesRun();
    List<String> on = block(yaml, "on:");
    long triggers = on.stream().filter(l -> indent(l) == 2 && !l.isBlank()).count();
    assertThat(triggers).as("one trigger only").isEqualTo(1);
    assertThat(on).anyMatch(l -> l.stripTrailing().equals("  workflow_dispatch:"));
    assertThat(yaml).doesNotContain("pull_request").doesNotContain("workflow_run");

    List<String> spike = block(yaml, "      spike:");
    assertThat(String.join("\n", spike)).contains("type: choice").contains("required: true");
    Matcher options =
        Pattern.compile("options:\\s*\\[([^\\]]*)\\]").matcher(String.join("\n", spike));
    assertThat(options.find()).isTrue();
    assertThat(options.group(1).split("\\s*,\\s*"))
        .containsExactly("selftest", "sp-r", "sp-l", "sp-x");

    String profile = String.join("\n", block(yaml, "      profile:"));
    assertThat(profile).contains("type: choice").contains("default: smoke");
    assertThat(profile).containsPattern("options:\\s*\\[smoke,\\s*full\\]");
    for (String name : List.of("confirm_downloads", "stretch")) {
      String input = String.join("\n", block(yaml, "      " + name + ":"));
      assertThat(input).as(name).contains("type: boolean").contains("default: false");
    }
  }

  @Test
  void pinGatePassesOnBothWorkflows() throws IOException {
    assertThat(PinCheck.checkWorkflow("ci.yml", ci())).isEmpty();
    String yaml = spikesRun();
    assertThat(PinCheck.checkWorkflow("spikes-run.yml", yaml)).isEmpty();
    for (String text : List.of(ci(), yaml)) {
      assertThat(text).containsPattern("(?m)^permissions:");
      assertThat(text).doesNotContain("-latest");
    }
    assertThat(yaml).containsPattern("(?m)^permissions:\\n  contents: read$");
    assertThat(yaml).doesNotContainPattern("(?m)^\\s*[a-z-]+:\\s*write(-all)?\\s*$");
    for (Step checkout : steps(yaml, RUN_JOB).subList(0, 1)) {
      assertThat(checkout.text())
          .contains("actions/checkout@")
          .contains("persist-credentials: false");
    }
  }

  @Test
  void noInputExpressionInsideRunBlocks() throws IOException {
    assertThat(expressionsInRunBlocks(spikesRun())).isEmpty();
  }

  @Test
  void theExpressionDetectorFlagsAnInjectionWhenOneIsPlanted() {
    String bad =
        """
        jobs:
          run:
            steps:
              - name: Bad
                run: |
                  echo "${{ inputs.spike }}"
        """;
    assertThat(expressionsInRunBlocks(bad)).hasSize(1);
    String good =
        """
        jobs:
          run:
            steps:
              - name: Good
                env:
                  SPIKE: ${{ inputs.spike }}
                run: |
                  echo "$SPIKE"
        """;
    assertThat(expressionsInRunBlocks(good)).isEmpty();
  }

  @Test
  void downloadApprovalGuardsThePgJob() throws IOException {
    Step plan = steps(spikesRun(), PLAN_JOB).get(0);
    assertThat(plan.text()).contains("SPIKE: ${{ inputs.spike }}");
    assertThat(plan.text()).contains("CONFIRM: ${{ inputs.confirm_downloads }}");
    assertThat(plan.run()).contains("\"pg\":\"true\"").contains("CONFIRM").contains("exit 1");
  }

  @Test
  void thePlanScriptRefusesAPgSpikeWithoutApprovalWhenExecuted(@TempDir Path dir) throws Exception {
    String bash = TestBash.find().orElse(null);
    assumeTrue(bash != null, "no usable bash on this machine");
    Step plan = steps(spikesRun(), PLAN_JOB).get(0);
    Files.writeString(dir.resolve("plan.sh"), plan.run(), StandardCharsets.UTF_8);
    assertThat(exitCode(bash, dir, "sp-l", "false")).isEqualTo(1);
    assertThat(exitCode(bash, dir, "sp-l", "true")).isZero();
    assertThat(exitCode(bash, dir, "sp-r", "false")).isZero();
    assertThat(exitCode(bash, dir, "sp-x", "false")).isZero();
    assertThat(exitCode(bash, dir, "selftest", "false")).isZero();
    assertThat(exitCode(bash, dir, "nonsense", "true")).isEqualTo(1);
  }

  private static int exitCode(String bash, Path dir, String spike, String confirm)
      throws Exception {
    return TestBash.run(
        bash,
        dir,
        Map.of("SPIKE", spike, "CONFIRM", confirm, "GITHUB_OUTPUT", dir.resolve("out").toString()),
        "plan.sh");
  }

  @Test
  void runStepPassesThePgUrl() throws IOException {
    Step run = named(steps(spikesRun(), RUN_JOB), RUN_SPIKE);
    assertThat(jobEnv(spikesRun()))
        .contains("PG: ${{ matrix.pg }}")
        .contains("PROFILE: ${{ inputs.profile }}")
        .contains("STRETCH: ${{ inputs.stretch }}")
        .contains("PART: ${{ matrix.part }}");
    assertThat(run.run()).contains("set -euo pipefail");
    String[] lines = run.run().split("\n");
    int guard = -1;
    int url = -1;
    int java = -1;
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].matches("if \\[ \"\\$PG\" = \"?true\"? \\]; then")) {
        guard = i;
      }
      if (lines[i].contains(
          "-Dspike.pg.url=jdbc:postgresql://127.0.0.1:5432/postgres?user=postgres")) {
        url = i;
      }
      if (lines[i].startsWith("java ")) {
        java = i;
      }
    }
    assertThat(guard).as("PG guard").isNotNegative();
    assertThat(url).as("URL line").isGreaterThan(guard);
    assertThat(java).as("java line").isGreaterThan(url);
    assertThat(lines[java])
        .contains("${JOPTS[@]+\"${JOPTS[@]}\"}")
        .contains("--stretch \"$STRETCH\"")
        .contains("--deadline-minutes \"$DEADLINE\"")
        .contains("--profile \"$PROFILE\"")
        .contains("--part \"$PART\"");
    assertThat(run.run()).contains("bash scripts/bench.sh selftest --out");
  }

  @Test
  void sealIsTheLastStepThatWritesTheResultDirectory() throws IOException {
    assertThat(sealProblems(spikesRun())).isEmpty();
    List<Step> steps = steps(spikesRun(), RUN_JOB);
    assertThat(steps.get(steps.size() - 1).text()).contains("actions/upload-artifact@");
  }

  @Test
  void theSealDetectorFlagsAStepThatWritesAfterTheSeal() throws IOException {
    String yaml = spikesRun();
    String late =
        yaml.replaceFirst(
            "(?m)^(      - uses: actions/upload-artifact@)",
            "      - name: Late edit\n        run: echo tampered >> results/x/summary.json\n$1");
    assertThat(late).isNotEqualTo(yaml);
    assertThat(sealProblems(late)).isNotEmpty();
    String early = yaml.replace(SEAL, "Seal early").replace(STOP_PG, SEAL);
    assertThat(sealProblems(early)).isNotEmpty();
  }

  @Test
  void everyMatrixEntryHasADeadlineBelowItsTimeout() throws IOException {
    String yaml = spikesRun();
    Step plan = steps(yaml, PLAN_JOB).get(0);
    Matcher m = MATRIX_ENTRY.matcher(plan.run());
    List<String> seen = new ArrayList<>();
    while (m.find()) {
      long timeout = Long.parseLong(m.group(3));
      long deadline = Long.parseLong(m.group(4));
      assertThat(deadline).as(m.group(1) + "/" + m.group(2)).isEqualTo(timeout - 25);
      assertThat(m.group(5).equals("true"))
          .as("pg flag of " + m.group(1))
          .isEqualTo(m.group(1).equals("sp-l"));
      seen.add(m.group(1) + "/" + m.group(2) + "/" + timeout);
    }
    assertThat(seen)
        .containsExactlyInAnyOrder(
            "selftest/all/30",
            "sp-r/all/345",
            "sp-x/all/300",
            "sp-l/ingest/330",
            "sp-l/restore/330",
            "sp-l/misc/240");
    Step run = named(steps(yaml, RUN_JOB), RUN_SPIKE);
    assertThat(jobEnv(yaml)).contains("DEADLINE: ${{ matrix.deadline }}");
    assertThat(String.join("\n", block(yaml, "  run:")))
        .contains("timeout-minutes: ${{ matrix.timeout }}")
        .contains("fail-fast: false")
        .contains("matrix: ${{ fromJSON(needs.plan.outputs.matrix) }}");
  }

  @Test
  void artifactNameHasSpikePartRunAndAttempt() throws IOException {
    List<Step> steps = steps(spikesRun(), RUN_JOB);
    Step upload = steps.get(steps.size() - 1);
    assertThat(upload.text())
        .contains(
            "name: spike-${{ matrix.spike }}-${{ matrix.part }}-${{ github.run_id }}-${{"
                + " github.run_attempt }}")
        .contains("path: results/${{ matrix.spike }}-${{ matrix.part }}")
        .contains("retention-days: 30")
        .contains("if-no-files-found: error");
    assertThat(upload.condition()).isIn("always()", "${{ always() }}");
  }

  @Test
  void concurrencyGroupIsPerSpike() throws IOException {
    String yaml = spikesRun();
    assertThat(yaml).contains("group: 'spikes-run-${{ inputs.spike }}'");
    assertThat(yaml).contains("cancel-in-progress: false");
  }

  @Test
  void ciPushFilterNamesTheWorkBranches() throws IOException {
    List<String> push = block(ci(), "  push:");
    assertThat(push)
        .anyMatch(
            l ->
                l.strip()
                    .equals(
                        "branches: [main, 'p0/**', 'p1a/**', 'p2a/**', 'p3a/**', 'p3b/**', 'p3b2/**']"));
    List<String> pullRequest = block(ci(), "  pull_request:");
    assertThat(pullRequest).anyMatch(l -> l.strip().equals("branches: [main]"));
  }

  @Test
  void runNameCarriesSpikeAndProfile() throws IOException {
    assertThat(spikesRun())
        .contains("run-name: spikes-run ${{ inputs.spike }} ${{ inputs.profile }}")
        .contains("name: spikes-run");
  }

  @Test
  void integrationTestStepRunsAfterPgUpAndBeforeTheRun() throws IOException {
    List<Step> steps = steps(spikesRun(), RUN_JOB);
    int start = indexOf(steps, START_PG);
    int it = indexOf(steps, IT);
    int run = indexOf(steps, RUN_SPIKE);
    int stop = indexOf(steps, STOP_PG);
    assertThat(start).isNotNegative();
    assertThat(it).isGreaterThan(start);
    assertThat(run).isGreaterThan(it);
    assertThat(stop).isGreaterThan(run);
    assertThat(steps.get(it).condition())
        .contains("!cancelled()")
        .contains("matrix.pg == 'true'")
        .contains("inputs.profile == 'smoke'");
    assertThat(steps.get(it).run()).contains("it-pg.sh \"$PART\"");
    assertThat(steps.get(start).condition()).contains("matrix.pg == 'true'");
    assertThat(steps.get(start).run()).contains("pg-up.sh \"sp-l-$PART\"");
    assertThat(steps.get(run).condition()).contains("!cancelled()");
    assertThat(steps.get(stop).condition()).contains("always()").contains("matrix.pg == 'true'");
    assertThat(steps.get(stop).run()).contains("pg-down.sh \"results/$SPIKE-$PART\"");
  }

  @Test
  void theRunJobStepsAreInTheSpecifiedOrder() throws IOException {
    List<String> names = steps(spikesRun(), RUN_JOB).stream().map(Step::name).toList();
    assertThat(names.stream().filter(n -> !n.isEmpty()).toList())
        .containsSubsequence(
            "Capture the runner",
            "Build the harness and the spike, run their tests",
            START_PG,
            IT,
            RUN_SPIKE,
            STOP_PG,
            SEAL);
    List<Step> steps = steps(spikesRun(), RUN_JOB);
    assertThat(steps.get(0).text()).contains("actions/checkout@");
    assertThat(steps.get(1).text()).contains("actions/setup-java@").contains("cache: maven");
    assertThat(named(steps, "Capture the runner").run())
        .contains("bash spikes/scripts/runner-capture.sh \"results/$SPIKE-$PART\"");
    assertThat(named(steps, SEAL).run())
        .contains("bash scripts/bench.sh publish --dir \"results/$SPIKE-$PART\" --os ubuntu-24.04");
  }

  @Test
  void ciHasTheSpikesJobOnceR1IsDone() throws IOException {
    assumeTrue(
        Files.exists(repo().resolve("spikes/spike-r/pom.xml")), "R1 has not created spike-r yet");
    String yaml = ci();
    assertThat(yaml).containsPattern("(?m)^  spikes:$");
    assertThat(PinCheck.checkWorkflow("ci.yml", yaml)).isEmpty();
  }
}
