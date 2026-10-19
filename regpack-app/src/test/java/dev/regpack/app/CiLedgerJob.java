package dev.regpack.app;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the job {@code ledger-pg} of {@code ci.yml} by its meaning, as text (no YAML library): the
 * steps that run the real-store suites and gate on them. {@link #problems} is the one checker;
 * {@code PackagingFilesTest} runs it on the real workflow and on deliberately broken copies of it,
 * in the style of {@link CiCompareStep}. The job is an allow-list: the install, the one verify
 * step, the traceability step in {@code --realstore only} mode and the annotation step are given
 * word for word, and nothing in the job may skip, ignore or tolerate a failure.
 */
final class CiLedgerJob {
  static final String JOB = "ledger-pg";
  static final String INSTALL =
      "      - run: ./mvnw -DskipTests -Dspotless.check.skip=true install -pl regpack-postgres -am";
  static final String VERIFY = "      - run: ./mvnw verify -pl regpack-postgres";
  static final String TRACEABILITY_NAME = "      - name: Real-store traceability";
  static final String TRACEABILITY_RUN =
      "        run: java -cp regpack-tools/target/classes dev.regpack.tools.Gates traceability"
          + " --file docs/traceability.md --root . --scan regpack-postgres/target"
          + " --realstore only";
  static final String ANNOTATE_NAME = "      - name: Test tallies and failure annotations";
  static final String ANNOTATE_RUN =
      "        run: java -cp regpack-tools/target/classes dev.regpack.tools.Gates annotate"
          + " --scan regpack-postgres/target";

  /** A -D flag that switches tests off or lets a failing test pass. */
  private static final Pattern TOLERANT_FLAG =
      Pattern.compile("-D\\S*(?i:skip|ignore|failure|fail)\\S*");

  private CiLedgerJob() {}

  /** Everything that lets the job pass without its real-store suites; empty when it is sound. */
  static List<String> problems(String ci) {
    List<String> problems = new ArrayList<>();
    List<String> job = CiCompareStep.jobLines(ci, JOB);
    if (job.isEmpty()) {
      problems.add("no job " + JOB);
      return problems;
    }
    jobProblems(job, problems);
    List<List<String>> steps = steps(job);
    installAndVerifyProblems(job, steps, problems);
    gateProblems(steps, TRACEABILITY_NAME, TRACEABILITY_RUN, problems);
    gateProblems(steps, ANNOTATE_NAME, ANNOTATE_RUN, problems);
    return problems;
  }

  private static void jobProblems(List<String> job, List<String> problems) {
    for (String line : job) {
      if (line.contains("continue-on-error")) {
        problems.add("a failure may be tolerated: " + line.strip());
      }
      if (line.startsWith("    if:")) {
        problems.add("the job may be skipped: " + line.strip());
      }
    }
    if (!job.contains("    timeout-minutes: 40")) {
      problems.add("the job is not bounded by timeout-minutes: 40");
    }
    if (!job.contains("    runs-on: ubuntu-24.04")) {
      problems.add("the job does not run on ubuntu-24.04");
    }
    if (!job.contains("      REGPACK_PG_REQUIRED: 'true'")) {
      problems.add("a missing server does not fail the job (REGPACK_PG_REQUIRED)");
    }
    boolean health =
        job.stream()
            .anyMatch(
                l ->
                    l.contains("--health-cmd \"pg_isready -U postgres\"")
                        && l.contains("--health-retries 12"));
    if (!health) {
      problems.add("the service has no health check of 12 retries on pg_isready");
    }
  }

  /** The steps of the job: each from its {@code - } line (six spaces) to the next one. */
  private static List<List<String>> steps(List<String> job) {
    int steps = job.indexOf("    steps:");
    List<List<String>> result = new ArrayList<>();
    if (steps < 0) {
      return result;
    }
    List<String> current = null;
    for (int i = steps + 1; i < job.size(); i++) {
      String line = job.get(i);
      if (line.startsWith("      - ")) {
        current = new ArrayList<>();
        result.add(current);
      }
      if (current != null && !line.isBlank()) {
        current.add(line);
      }
    }
    return result;
  }

  private static void installAndVerifyProblems(
      List<String> job, List<List<String>> steps, List<String> problems) {
    int install = -1;
    int verify = -1;
    int verifySteps = 0;
    for (int i = 0; i < steps.size(); i++) {
      List<String> step = steps.get(i);
      String first = step.get(0);
      if (first.contains("./mvnw") && first.contains(" install")) {
        install = i;
        if (!step.equals(List.of(INSTALL))) {
          problems.add("the install step is not exactly: " + INSTALL.strip());
        }
      } else if (first.contains("./mvnw") || step.stream().anyMatch(l -> l.contains("verify"))) {
        verifySteps++;
        verify = i;
        if (!step.equals(List.of(VERIFY))) {
          problems.add("the verify step is not exactly: " + VERIFY.strip() + " (" + step + ")");
        }
      }
    }
    if (install < 0) {
      problems.add("no install step with -pl regpack-postgres -am");
    }
    if (verifySteps != 1) {
      problems.add("expected exactly one verify step, found " + verifySteps);
    }
    if (install >= 0 && verify >= 0 && install > verify) {
      problems.add("the install step runs after the verify step");
    }
    for (String line : job) {
      if (!line.equals(INSTALL) && TOLERANT_FLAG.matcher(line).find()) {
        problems.add("a flag skips or ignores tests: " + line.strip());
      }
    }
  }

  /** A step that runs on every outcome, in the given name and command, after the verify step. */
  private static void gateProblems(
      List<List<String>> steps, String name, String run, List<String> problems) {
    List<List<String>> found = steps.stream().filter(s -> s.get(0).equals(name)).toList();
    if (found.size() != 1) {
      problems.add("expected exactly one step '" + name.substring(8) + "', found " + found.size());
      return;
    }
    List<String> step = found.get(0);
    if (!step.contains("        if: always()")) {
      problems.add("the step '" + name.substring(8) + "' does not run when the tests fail");
    }
    if (!step.contains("        shell: bash")) {
      problems.add("the step '" + name.substring(8) + "' does not run under bash");
    }
    if (!step.contains(run)) {
      problems.add("the step '" + name.substring(8) + "' does not run exactly: " + run.strip());
    }
    if (steps.indexOf(found.get(0)) < indexOfVerify(steps)) {
      problems.add("the step '" + name.substring(8) + "' runs before the verify step");
    }
  }

  private static int indexOfVerify(List<List<String>> steps) {
    for (int i = 0; i < steps.size(); i++) {
      if (steps.get(i).equals(List.of(VERIFY))) {
        return i;
      }
    }
    return Integer.MAX_VALUE;
  }
}
