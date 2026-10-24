package dev.regpack.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads the job {@code evidence-worm} of {@code ci.yml} by its meaning, as text (no YAML library,
 * no shell parser): the job that runs the S3 service probe (version 1) against one digest-pinned
 * S3-compatible service container on the loopback of the runner. {@link #problems} is the one
 * checker; {@code PackagingFilesTest} runs it on the real workflow and on deliberately broken
 * copies of it, in the style of {@link CiLedgerJob} and {@link CiDurabilityJob}.
 *
 * <p>The text is owner-approved (AP-3, AP-6), so the job is an allow-list: every line of the job is
 * given word for word in this class, the SHA-256 of the whole text is pinned, and, as a second
 * line, the rules below name why a deviation matters: no secret or token, no tolerated failure, no
 * job-level condition, no docker verb, no {@code sudo}, no privileged container, an image only with
 * a digest, a port only on {@code 127.0.0.1}, no flag that skips tests. A message names the rule
 * that is broken, so that a test can show that the rule, not only the hash, found the edit.
 *
 * <p>Version 1 is the probe only (no PostgreSQL service, no {@code REGPACK_PG_*}). What the service
 * image needs (its listen port, the variables that give it fixed credentials and an on-disk
 * backend, the readiness of its port) is recollection [R] until the probe has run.
 */
final class CiWormJob {
  static final String JOB = "evidence-worm";

  /**
   * The service image: name, tag and digest. Proposed by the controller; approved with the text.
   */
  static final String IMAGE =
      "ghcr.io/scality/cloudserver:9.5.0@sha256:"
          + "652d50d0d92f5ef948b8c3aae92251b5707a70892d886835f904a069f60a0727";

  static final String ALWAYS = "        if: always()";
  static final String NOT_APPROVED = "is not the approved text";

  private static final String RUNS_ON = "    runs-on: ubuntu-24.04";

  /** The job lines above the services, word for word. */
  static final List<String> HEADER =
      List.of(
          "  evidence-worm:",
          "    name: evidence P-WORM probe (S3-compatible object-lock service)",
          RUNS_ON,
          "    timeout-minutes: 40");

  static final List<String> SERVICES =
      List.of(
          "    services:",
          "      s3:",
          "        image: " + IMAGE,
          "        env:",
          "          S3BACKEND: file",
          "          SCALITY_ACCESS_KEY_ID: REGPACKCIACCESSKEY1",
          "          SCALITY_SECRET_ACCESS_KEY: regpack-ci-secret-key-000000000000000001",
          "          REMOTE_MANAGEMENT_DISABLE: '1'",
          "        ports:",
          "          - 127.0.0.1:8000:8000");

  static final List<String> ENV =
      List.of(
          "    env:",
          "      REGPACK_S3_ENDPOINT: http://127.0.0.1:8000",
          "      REGPACK_S3_REGION: us-east-1",
          "      REGPACK_S3_ACCESS_KEY: REGPACKCIACCESSKEY1",
          "      REGPACK_S3_SECRET_KEY: regpack-ci-secret-key-000000000000000001",
          "      REGPACK_S3_REQUIRED: 'true'");

  static final String STEPS_LINE = "    steps:";

  /**
   * As the jobs {@code ledger-pg} and {@code ledger-durability}: same SHAs and version comments.
   */
  static final List<String> CHECKOUT = CiDurabilityJob.CHECKOUT;

  static final List<String> SETUP_JAVA = CiDurabilityJob.SETUP_JAVA;

  static final String INSTALL_LINE =
      "      - run: ./mvnw -DskipTests -DskipITs -Dspotless.check.skip=true install"
          + " -pl regpack-evidence-s3 -am";
  static final List<String> INSTALL = List.of(INSTALL_LINE);

  static final List<String> WAIT =
      List.of(
          "      - name: Wait for the S3 service",
          "        timeout-minutes: 3",
          "        shell: bash",
          "        run: |",
          "          for i in $(seq 1 40); do",
          "            if curl -s -o /dev/null --max-time 2 http://127.0.0.1:8000/; then exit 0; fi",
          "            sleep 2",
          "          done",
          "          echo \"::error::the S3 service did not answer an HTTP request on"
              + " 127.0.0.1:8000 after 40 attempts\"",
          "          exit 1");

  static final String PROBE_RUN =
      "        run: ./mvnw verify -pl regpack-evidence-s3 -Dspotless.check.skip=true"
          + " -Dgroups=probe -DexcludedGroups=none -Dit.test=S3ServiceProbeIT"
          + " -Dtest=NoUnitTestsInThisRun -Dsurefire.failIfNoSpecifiedTests=false";

  static final List<String> PROBE =
      List.of("      - name: S3 service probe", "        timeout-minutes: 15", PROBE_RUN);

  static final List<String> ANNOTATE =
      List.of(
          "      - name: Test tallies and failure annotations",
          ALWAYS,
          "        shell: bash",
          "        run: java -cp regpack-tools/target/classes dev.regpack.tools.Gates annotate"
              + " --scan regpack-evidence-s3/target");

  static final List<String> UPLOAD =
      List.of(
          "      - name: Upload P-WORM test reports",
          ALWAYS,
          "        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1",
          "        with:",
          "          name: evidence-worm-reports",
          "          path: regpack-evidence-s3/target/failsafe-reports/TEST-*.xml",
          "          if-no-files-found: warn");

  /** The steps of the job, in the order they must run. */
  private static final List<List<String>> APPROVED =
      List.of(CHECKOUT, SETUP_JAVA, INSTALL, WAIT, PROBE, ANNOTATE, UPLOAD);

  /** The SHA-256 of {@link #text()}: the approved job text, version 1. */
  static final String APPROVED_SHA256 =
      "9099263b3f3afdd76b635f54a79b567fe4403fb8e091b977de9bdfd71c3ce06c";

  private static final Set<String> ALLOWED_PROBE_PROPERTIES =
      Set.of(
          "-Dspotless.check.skip=true",
          "-Dgroups=probe",
          "-DexcludedGroups=none",
          "-Dit.test=S3ServiceProbeIT",
          "-Dtest=NoUnitTestsInThisRun",
          "-Dsurefire.failIfNoSpecifiedTests=false");

  /** The properties of the install step, which skips the tests of the modules it builds. */
  private static final Set<String> INSTALL_PROPERTIES =
      Set.of("-DskipTests", "-DskipITs", "-Dspotless.check.skip=true");

  /** A -D flag that switches tests off or lets a failing test pass. */
  private static final Pattern TOLERANT_FLAG =
      Pattern.compile("-D\\S*(?i:skip|ignore|failure|fail)\\S*");

  private static final Pattern DOCKER = Pattern.compile("(?:^|[\\s(;&|`\"'])docker(?![\\w./-])");
  private static final Pattern DOCKER_BY_PATH =
      Pattern.compile("(?:^|[\\s(;&|`\"'])/(?:usr/)?(?:local/)?(?:snap/)?s?bin/docker(?![\\w./-])");
  private static final Pattern SUDO = Pattern.compile("(?<![\\w.-])sudo(?![\\w.-])");
  private static final Pattern SWALLOWED =
      Pattern.compile("\\|\\|\\s*(?:true|:)(?!\\w)|set\\s+\\+e|;\\s*true\\b");
  private static final Pattern DIGEST = Pattern.compile("@sha256:[0-9a-f]{64}$");

  private CiWormJob() {}

  /** The approved job text: every line, each ending in a newline. */
  static String text() {
    List<String> lines = new ArrayList<>();
    lines.addAll(HEADER);
    lines.addAll(SERVICES);
    lines.addAll(ENV);
    lines.add(STEPS_LINE);
    for (List<String> step : APPROVED) {
      lines.addAll(step);
    }
    return String.join("\n", lines) + "\n";
  }

  /**
   * The text of the job in the workflow: its lines from {@code evidence-worm:} to the line before
   * the next job, blank lines dropped, each ending in a newline. Empty when there is no such job.
   */
  static String jobText(String ci) {
    List<String> job = jobLines(ci);
    return job.isEmpty() ? "" : String.join("\n", job) + "\n";
  }

  static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static List<String> jobLines(String ci) {
    return CiCompareStep.jobLines(ci, JOB).stream().filter(l -> !l.isBlank()).toList();
  }

  /** Everything that lets the job pass without its probe, or lets it do more than approved. */
  static List<String> problems(String ci) {
    List<String> problems = new ArrayList<>();
    if (!ci.contains("\n  MAVEN_ARGS: -B -ntp --strict-checksums\n")) {
      problems.add(
          "the workflow MAVEN_ARGS is not -B -ntp --strict-checksums (an option such as -fn would"
              + " apply to the job)");
    }
    List<String> job = jobLines(ci);
    if (job.isEmpty()) {
      problems.add("no job " + JOB);
      return problems;
    }
    lineProblems(job, problems);
    blockProblems(job, problems);
    stepProblems(steps(job), problems);
    mavenProblems(job, problems);
    if (!sha256(jobText(ci)).equals(APPROVED_SHA256)) {
      problems.add("the job text " + NOT_APPROVED + " (its SHA-256 differs from the pinned one)");
    }
    return problems;
  }

  // ---------------------------------------------------------------- rules on every line

  private static void lineProblems(List<String> job, List<String> problems) {
    boolean inPorts = false;
    boolean ports = false;
    for (String line : job) {
      String stripped = line.strip();
      if (line.contains("continue-on-error")) {
        problems.add("a failure may be tolerated: " + stripped);
      }
      if (line.startsWith("    if:")) {
        problems.add("the job may be skipped: " + stripped);
      }
      if (line.startsWith("        if:") && !line.equals(ALWAYS)) {
        problems.add("a step may be skipped: " + stripped);
      }
      if (line.contains("secrets.")
          || line.contains("GITHUB_TOKEN")
          || line.contains("github.token")) {
        problems.add("the job reads a secret or the token: " + stripped);
      }
      if (line.toLowerCase(Locale.ROOT).contains("password")) {
        problems.add("the job names a password: " + stripped);
      }
      if (SWALLOWED.matcher(line).find()) {
        problems.add("a failure is swallowed: " + stripped);
      }
      if (line.contains("--privileged")) {
        problems.add("a privileged container: " + stripped);
      }
      if (SUDO.matcher(line).find()) {
        problems.add("sudo in the job: " + stripped);
      }
      if (DOCKER.matcher(line).find() || DOCKER_BY_PATH.matcher(line).find()) {
        problems.add("a docker command in the job: " + stripped);
      }
      if (stripped.startsWith("image:") && !DIGEST.matcher(stripped).find()) {
        problems.add("an image without a digest: " + stripped);
      }
      if (stripped.equals("ports:")) {
        inPorts = true;
        ports = true;
        continue;
      }
      if (inPorts) {
        if (stripped.startsWith("- ")) {
          if (!stripped.startsWith("- 127.0.0.1:")) {
            problems.add("a port not published on 127.0.0.1 only: " + stripped);
          }
          continue;
        }
        inPorts = false;
      }
    }
    if (!ports) {
      problems.add("the service publishes no port on 127.0.0.1");
    }
    if (!job.contains(RUNS_ON)) {
      problems.add("the job does not run on ubuntu-24.04");
    }
    if (!job.contains("      REGPACK_S3_REQUIRED: 'true'")) {
      problems.add("a missing service does not fail the job (REGPACK_S3_REQUIRED is not 'true')");
    }
    if (job.stream().filter(l -> l.startsWith("    runs-on:")).count() != 1) {
      problems.add("the job does not have exactly one runs-on");
    }
  }

  // ---------------------------------------------------------------- the blocks above the steps

  private static void blockProblems(List<String> job, List<String> problems) {
    int services = job.indexOf(SERVICES.get(0));
    int env = job.indexOf(ENV.get(0));
    int steps = job.indexOf(STEPS_LINE);
    if (services < 0 || env < 0 || steps < 0 || !(services < env && env < steps)) {
      problems.add("the job does not have services, env and steps in this order");
      return;
    }
    compareBlock("header", HEADER, job.subList(0, services), problems);
    compareBlock("services", SERVICES, job.subList(services, env), problems);
    compareBlock("env", ENV, job.subList(env, steps), problems);
  }

  private static void compareBlock(
      String name, List<String> approved, List<String> found, List<String> problems) {
    if (!found.equals(approved)) {
      problems.add(
          "the " + name + " block " + NOT_APPROVED + ": " + firstDifference(approved, found));
    }
  }

  // ---------------------------------------------------------------- the steps

  /** The steps of the job: each from its {@code - } line (six spaces) to the next one. */
  private static List<List<String>> steps(List<String> job) {
    int stepsAt = job.indexOf(STEPS_LINE);
    List<List<String>> result = new ArrayList<>();
    if (stepsAt < 0) {
      return result;
    }
    List<String> current = null;
    for (int i = stepsAt + 1; i < job.size(); i++) {
      String line = job.get(i);
      if (line.startsWith("      - ")) {
        current = new ArrayList<>();
        result.add(current);
      }
      if (current != null) {
        current.add(line);
      }
    }
    return result;
  }

  private static String title(List<String> step) {
    String first = step.get(0).strip().substring(2);
    return first.startsWith("name: ") ? first.substring("name: ".length()) : first;
  }

  private static void stepProblems(List<List<String>> steps, List<String> problems) {
    int[] at = new int[APPROVED.size()];
    Set<String> approvedFirstLines = new LinkedHashSet<>();
    for (int k = 0; k < APPROVED.size(); k++) {
      List<String> approved = APPROVED.get(k);
      approvedFirstLines.add(approved.get(0));
      List<Integer> found = new ArrayList<>();
      for (int i = 0; i < steps.size(); i++) {
        if (steps.get(i).get(0).equals(approved.get(0))) {
          found.add(i);
        }
      }
      if (found.size() != 1) {
        problems.add("expected exactly one step '" + title(approved) + "', found " + found.size());
      }
      at[k] = found.isEmpty() ? -1 : found.get(0);
      if (!found.isEmpty()) {
        oneStepProblems(approved, steps.get(found.get(0)), problems);
      }
    }
    for (List<String> step : steps) {
      if (!approvedFirstLines.contains(step.get(0))) {
        problems.add("a step outside the approved text: " + step.get(0).strip());
      }
    }
    int last = -1;
    String lastTitle = "";
    for (int k = 0; k < APPROVED.size(); k++) {
      if (at[k] < 0) {
        continue;
      }
      if (at[k] < last) {
        problems.add(
            "the step '" + title(APPROVED.get(k)) + "' runs before the step '" + lastTitle + "'");
      } else {
        last = at[k];
        lastTitle = title(APPROVED.get(k));
      }
    }
  }

  private static void oneStepProblems(
      List<String> approved, List<String> step, List<String> problems) {
    String title = title(approved);
    if (!step.equals(approved)) {
      problems.add(
          "the step '" + title + "' " + NOT_APPROVED + ": " + firstDifference(approved, step));
    }
    if (approved.contains(ALWAYS) && !step.contains(ALWAYS)) {
      problems.add(
          "the step '"
              + title
              + "' does not run when an earlier step fails (if: always() is missing)");
    }
    if (!approved.contains(ALWAYS) && step.contains(ALWAYS)) {
      problems.add("the step '" + title + "' has a condition that is not approved for it");
    }
    if (approved == PROBE && !step.contains(PROBE_RUN)) {
      problems.add("the probe step does not run exactly: " + PROBE_RUN.strip());
    }
  }

  private static String firstDifference(List<String> approved, List<String> found) {
    for (int i = 0; i < Math.max(approved.size(), found.size()); i++) {
      String a = i < approved.size() ? approved.get(i) : "(no line)";
      String f = i < found.size() ? found.get(i) : "(no line)";
      if (!a.equals(f)) {
        return "found '" + f.strip() + "' where '" + a.strip() + "' is approved";
      }
    }
    return "";
  }

  // ---------------------------------------------------------------- Maven

  /**
   * The Maven lines: the install step and the probe step as approved, the probe step names the one
   * module and the one class, and no flag skips or ignores tests.
   */
  private static void mavenProblems(List<String> job, List<String> problems) {
    int probes = 0;
    for (String line : job) {
      int at = line.indexOf("./mvnw");
      if (at < 0) {
        continue;
      }
      boolean install = line.equals(INSTALL_LINE);
      boolean probe = line.equals(PROBE_RUN);
      if (probe) {
        probes++;
      }
      if (!install && !probe) {
        List<String> words = CiDurabilityJob.words(line.substring(at + "./mvnw".length()));
        boolean verify = words.contains("verify");
        problems.add(
            "a Maven line " + NOT_APPROVED + (verify ? " (verify)" : "") + ": " + line.strip());
        for (String word : words) {
          boolean approvedWord =
              ALLOWED_PROBE_PROPERTIES.contains(word) || INSTALL_PROPERTIES.contains(word);
          if (TOLERANT_FLAG.matcher(word).matches() && !ALLOWED_PROBE_PROPERTIES.contains(word)) {
            problems.add("a flag skips or ignores tests: " + word);
          } else if (word.startsWith("-D") && !approvedWord) {
            problems.add("a Maven property outside the approved set: " + word);
          }
        }
      }
    }
    if (probes != 1) {
      problems.add("expected exactly one probe step, found " + probes);
    }
    long maven = job.stream().filter(l -> l.contains("./mvnw")).count();
    if (maven != 2) {
      problems.add("expected exactly two Maven lines (install and probe), found " + maven);
    }
  }
}
