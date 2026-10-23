package dev.regpack.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the job {@code ledger-durability} of {@code ci.yml} and its script {@code
 * scripts/ci/pg-quorum-up.sh} by their meaning, as text (no YAML library, no shell parser): the job
 * that starts a primary and a quorum standby from the digest-pinned PostgreSQL image on the host
 * network of the runner, runs the standby tests with the standby up, stops the standby and runs
 * them again. {@link #problems} is the one checker; {@code PackagingFilesTest} runs it on the real
 * files and on deliberately broken copies of them, in the style of {@link CiLedgerJob}.
 *
 * <p>Both texts are owner-approved (AP-3, AP-6), so both are allow-lists. In the workflow every
 * step is given word for word and in order, and nothing may skip, ignore or tolerate a failure,
 * read a credential, publish a port or start a container of its own; the docker verbs are exactly
 * {@code stop}, {@code logs}, {@code exec} and {@code rm}. In the script the docker verbs are
 * within {@code pull}, {@code run} and {@code exec}, every image is the one pinned digest of the
 * job {@code ledger-pg}, every {@code docker run} uses an allow-listed option, the host network and
 * (for a server) {@code listen_addresses=127.0.0.1}, {@code sudo} appears only in the two install
 * lines of AP-6, and no word of a deny-list of fetch, install, copy, delete, mount and privilege
 * commands appears. A message names the rule that is broken, so that a test can show that the rule,
 * not a generic difference, found the edit.
 */
final class CiDurabilityJob {
  static final String JOB = "ledger-durability";
  static final String SCRIPT = "scripts/ci/pg-quorum-up.sh";
  static final String INIT_SCRIPT = "scripts/ci/pg-init-replication.sh";

  static final String ALWAYS = "        if: always()";
  static final String FAILURE = "        if: failure()";
  static final String NOT_CANCELLED = "        if: ${{ !cancelled() }}";
  static final String NOT_APPROVED = "is not the approved text";

  private static final String PG_URL =
      "      REGPACK_PG_URL: jdbc:postgresql://127.0.0.1:5432/postgres?user=postgres";
  private static final String PG_REQUIRED = "      REGPACK_PG_REQUIRED: 'true'";
  private static final String JOB_NAME = "    name: ledger durability (primary and quorum standby)";
  private static final String RUNS_ON = "    runs-on: ubuntu-24.04";
  private static final String TIMEOUT = "    timeout-minutes: 30";

  /** The job lines above the steps, word for word. */
  static final List<String> HEADER =
      List.of(
          "  ledger-durability:",
          JOB_NAME,
          RUNS_ON,
          TIMEOUT,
          "    env:",
          PG_URL,
          PG_REQUIRED,
          "    steps:");

  /** As the job {@code ledger-pg}: same SHAs and version comments. */
  static final List<String> CHECKOUT =
      List.of(
          "      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1",
          "        with:",
          "          persist-credentials: false");

  static final List<String> SETUP_JAVA =
      List.of(
          "      - uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6.0.1",
          "        with:",
          "          distribution: temurin",
          "          java-version: '25'",
          "          cache: maven");

  static final List<String> INSTALL =
      List.of(
          "      - run: ./mvnw -DskipTests -DskipITs -Dspotless.check.skip=true install -pl"
              + " regpack-postgres -am");

  static final List<String> UP =
      List.of(
          "      - name: Start the primary and the quorum standby",
          "        timeout-minutes: 10",
          "        shell: bash",
          "        run: bash scripts/ci/pg-quorum-up.sh");

  private static final String TESTS =
      "        run: ./mvnw verify -pl regpack-postgres -Dspotless.check.skip=true"
          + " -Dgroups=durability -DexcludedGroups=none -Dit.test=%s"
          + " -Dtest=NoUnitTestsInThisRun -Dsurefire.failIfNoSpecifiedTests=false";

  static final List<String> UP_TESTS =
      List.of(
          "      - name: Durability tests with the standby up",
          "        timeout-minutes: 10",
          TESTS.formatted("PgDurabilityUpIT"));

  // the two steps of the Down phase run after a red Up phase too: it holds the discriminating
  // evidence (ITS-3); only a cancelled run skips them
  static final List<String> STOP =
      List.of(
          "      - name: Stop the standby",
          NOT_CANCELLED,
          "        shell: bash",
          "        run: docker stop regpack-standby");

  static final List<String> DOWN_TESTS =
      List.of(
          "      - name: Durability tests with the standby down",
          NOT_CANCELLED,
          "        timeout-minutes: 10",
          TESTS.formatted("PgDurabilityDownIT"));

  static final List<String> TRACEABILITY =
      List.of(
          "      - name: Standby traceability",
          ALWAYS,
          "        shell: bash",
          "        run: java -cp regpack-tools/target/classes dev.regpack.tools.Gates traceability"
              + " --file docs/traceability.md --root . --scan regpack-postgres/target"
              + " --realstore standby");

  static final List<String> ANNOTATE =
      List.of(
          "      - name: Test tallies and failure annotations",
          ALWAYS,
          "        shell: bash",
          "        run: java -cp regpack-tools/target/classes dev.regpack.tools.Gates annotate"
              + " --scan regpack-postgres/target");

  static final List<String> DIAGNOSTICS =
      List.of(
          "      - name: Container diagnostics",
          FAILURE,
          "        shell: bash",
          "        run: |",
          "          for c in regpack-primary regpack-standby; do",
          "            msg=$(docker logs --tail 60 \"$c\" 2>&1 | sed 's/%/%25/g' | paste -sd '|' -)",
          "            echo \"::notice title=$c::$msg\"",
          "          done",
          "          docker exec regpack-primary psql -h 127.0.0.1 -p 5432 -U postgres -Atc"
              + " \"SELECT application_name, state, sync_state FROM pg_stat_replication\""
              + " | sed 's/%/%25/g; s/^/::notice title=pg_stat_replication::/'",
          "          docker exec regpack-primary psql -h 127.0.0.1 -p 5432 -U postgres -Atc"
              + " \"SELECT pid, state, wait_event_type, wait_event, left(query, 60) FROM"
              + " pg_stat_activity WHERE pid <> pg_backend_pid()\""
              + " | sed 's/%/%25/g; s/^/::notice title=pg_stat_activity::/'",
          "          docker exec regpack-primary psql -h 127.0.0.1 -p 5432 -U postgres -Atc"
              + " \"SHOW synchronous_standby_names\""
              + " | sed 's/%/%25/g; s/^/::notice title=synchronous_standby_names::/'");

  static final List<String> CLEANUP =
      List.of(
          "      - name: Remove the containers",
          ALWAYS,
          "        shell: bash",
          "        run: docker rm -f regpack-primary regpack-standby");

  static final List<String> UPLOAD =
      List.of(
          "      - name: Upload durability test reports",
          ALWAYS,
          "        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1",
          "        with:",
          "          name: ledger-durability-reports",
          "          path: regpack-postgres/target/failsafe-reports/TEST-*.xml",
          "          if-no-files-found: warn");

  /** The steps of the job, in the order they must run. */
  private static final List<List<String>> APPROVED =
      List.of(
          CHECKOUT,
          SETUP_JAVA,
          INSTALL,
          UP,
          UP_TESTS,
          STOP,
          DOWN_TESTS,
          TRACEABILITY,
          ANNOTATE,
          DIAGNOSTICS,
          CLEANUP,
          UPLOAD);

  // the approved lines of the script (AP-3, AP-6); the rest is checked by rule
  static final String GUARD =
      "if [ \"${GITHUB_ACTIONS:-}\" != \"true\" ] || [ \"${RUNNER_ENVIRONMENT:-}\" !="
          + " \"github-hosted\" ]; then echo \"refusing: runs only on a GitHub-hosted runner\" >&2;"
          + " exit 1; fi";
  static final String PULL = "docker pull \"$IMAGE\"";
  static final String SUDO_ROOT = "sudo install -d -m 755 /mnt/regpack-pg";
  static final String SUDO_DIRS =
      "sudo install -d -o 999 -g 999 -m 700 /mnt/regpack-pg/primary /mnt/regpack-pg/standby";
  static final String ALTER_LINE =
      "docker exec regpack-primary psql -h 127.0.0.1 -p 5432 -U postgres -v ON_ERROR_STOP=1 -c"
          + " \"ALTER SYSTEM SET synchronous_standby_names = 'ANY 1 (standby1)'\" -c \"SELECT"
          + " pg_reload_conf()\"";
  static final String QUORUM_WAIT =
      "wait_query 5432 \"standby1 quorum and streaming\" \"SELECT count(*) FROM pg_stat_replication"
          + " WHERE application_name = 'standby1' AND sync_state = 'quorum' AND state ="
          + " 'streaming'\" 1";
  static final String SHOW_LINE = "psql_at 5432 \"SHOW synchronous_standby_names\"";
  static final String READY = "echo \"pg-quorum-up: ready\"";

  /** The three commands of the init script (the spike script, copied). */
  static final List<String> INIT_CODE =
      List.of(
          "set -euo pipefail",
          ": \"${PGDATA:?PGDATA is not set: this script runs only inside the postgres container}\"",
          "echo \"host replication all 127.0.0.1/32 trust\" >> \"$PGDATA/pg_hba.conf\"");

  private static final Set<String> WORKFLOW_VERBS = Set.of("stop", "logs", "exec", "rm");
  private static final Set<String> SCRIPT_VERBS = Set.of("pull", "run", "exec");
  private static final Set<String> RUN_VALUE_OPTIONS =
      Set.of("--name", "--network", "--shm-size", "-e", "-v", "--user");
  private static final Set<String> INSTALL_PROPERTIES =
      Set.of("-DskipTests", "-DskipITs", "-Dspotless.check.skip=true");
  private static final Set<String> VERIFY_PROPERTIES =
      Set.of(
          "-Dspotless.check.skip=true",
          "-Dgroups=durability",
          "-DexcludedGroups=none",
          "-Dit.test=PgDurabilityUpIT",
          "-Dit.test=PgDurabilityDownIT",
          "-Dtest=NoUnitTestsInThisRun",
          "-Dsurefire.failIfNoSpecifiedTests=false");
  private static final String NO_SELECTED_TEST =
      "a flag lets a run with no selected integration test pass";

  // a docker command: the word in command position (a path such as /var/lib/postgresql/18/docker
  // is no command), or a docker binary named by its path
  private static final Pattern DOCKER = Pattern.compile("(?:^|[\\s(;&|`\"'])docker(?![\\w./-])");
  private static final Pattern DOCKER_BY_PATH =
      Pattern.compile("(?:^|[\\s(;&|`\"'])/(?:usr/)?(?:local/)?(?:snap/)?s?bin/docker(?![\\w./-])");
  private static final Pattern SUDO = Pattern.compile("(?<![\\w.-])sudo(?![\\w.-])");
  private static final Pattern SWALLOWED =
      Pattern.compile("\\|\\|\\s*(?:true|:)(?!\\w)|set\\s+\\+e|exit\\s+0\\b|;\\s*true\\b");
  private static final Pattern SERVICE_KEY =
      Pattern.compile("^\\s*(?:-\\s+)?['\"]?(?:services|ports|container|image)['\"]?:.*$");
  private static final Pattern IMAGE_ASSIGNMENT = Pattern.compile("(?<![\\w])IMAGE=(\\S*)");
  private static final Pattern FORBIDDEN_COMMAND =
      Pattern.compile(
          "(?<![\\w.$-])(curl|wget|apt|apt-get|dpkg|pip|pip3|npm|snap|rm|mv|cp|mount|umount|chmod"
              + "|chown|kill|pkill|ssh|scp|nc|ncat|eval|source|su|doas|pkexec|install|tee|dd|mkdir"
              + "|ln)(?![\\w./-])");

  private CiDurabilityJob() {}

  /** The lines of a step as workflow text: each line ends in a newline. */
  static String text(List<String> step) {
    return String.join("\n", step) + "\n";
  }

  /** The non-blank, non-comment lines of a shell script, stripped. */
  static List<String> codeLines(String script) {
    return script
        .lines()
        .map(String::strip)
        .filter(l -> !l.isEmpty() && !l.startsWith("#"))
        .toList();
  }

  /** The docker verbs of the lines, in order of appearance (comment lines are not read). */
  static List<String> dockerVerbs(List<String> lines) {
    return dockerCalls(lines).stream().map(DockerCall::verb).toList();
  }

  /**
   * Everything that lets the job pass without its standby suites, or lets it do more than approved.
   */
  static List<String> problems(String ci, String script) {
    List<String> problems = new ArrayList<>();
    if (!ci.contains("\n  MAVEN_ARGS: -B -ntp --strict-checksums\n")) {
      problems.add(
          "the workflow MAVEN_ARGS is not -B -ntp --strict-checksums (an option such as -fn would"
              + " apply to the job)");
    }
    List<String> job = CiCompareStep.jobLines(ci, JOB).stream().filter(l -> !l.isBlank()).toList();
    if (job.isEmpty()) {
      problems.add("no job " + JOB);
    } else {
      jobProblems(job, problems);
    }
    scriptProblems(pinnedImage(ci), script, problems);
    return problems;
  }

  /** The init script: the three approved commands and nothing else. */
  static List<String> initScriptProblems(String init) {
    List<String> code = codeLines(init);
    List<String> problems = new ArrayList<>();
    for (String line : code) {
      if (!INIT_CODE.contains(line)) {
        problems.add("the init script holds a command outside the approved three: " + line);
      }
    }
    for (String line : INIT_CODE) {
      long count = code.stream().filter(line::equals).count();
      if (count != 1) {
        problems.add("the init script has the approved command " + count + " times: " + line);
      }
    }
    if (problems.isEmpty() && !code.equals(INIT_CODE)) {
      problems.add("the init script commands are not in the approved order");
    }
    return problems;
  }

  // ---------------------------------------------------------------- the workflow

  private static void jobProblems(List<String> job, List<String> problems) {
    int stepsAt = job.indexOf("    steps:");
    if (stepsAt < 0) {
      problems.add("the job has no steps: line");
    }
    headerProblems(stepsAt < 0 ? job : job.subList(0, stepsAt + 1), problems);
    stepProblems(steps(job, stepsAt), problems);
    lineProblems(job, problems);
    mavenProblems(job, problems);
    workflowDockerProblems(job, problems);
  }

  private static void headerProblems(List<String> header, List<String> problems) {
    require(
        header,
        JOB_NAME,
        "the job is not named 'ledger durability (primary and quorum standby)'",
        problems);
    require(header, RUNS_ON, "the job does not run on ubuntu-24.04", problems);
    require(header, TIMEOUT, "the job is not bounded by timeout-minutes: 30", problems);
    require(
        header,
        PG_REQUIRED,
        "a missing server does not fail the job (REGPACK_PG_REQUIRED is not 'true')",
        problems);
    require(
        header,
        PG_URL,
        "the job's REGPACK_PG_URL is not the loopback URL of the primary on port 5432",
        problems);
    for (String line : header) {
      if (!HEADER.contains(line)) {
        problems.add("the job header holds a line outside the approved text: " + line.strip());
      }
    }
  }

  private static void require(
      List<String> lines, String line, String problem, List<String> problems) {
    if (!lines.contains(line)) {
      problems.add(problem);
    }
  }

  /** The steps of the job: each from its {@code - } line (six spaces) to the next one. */
  private static List<List<String>> steps(List<String> job, int stepsAt) {
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
    if (approved.contains(FAILURE) && !step.contains(FAILURE)) {
      problems.add(
          "the step '" + title + "' does not run when a step fails (if: failure() is missing)");
    }
    if (approved.contains(NOT_CANCELLED) && !step.contains(NOT_CANCELLED)) {
      problems.add(
          "the step '"
              + title
              + "' does not run when an earlier step fails (if: ${{ !cancelled() }} is missing)");
    }
    for (String condition : List.of(ALWAYS, FAILURE, NOT_CANCELLED)) {
      if (step.contains(condition) && !approved.contains(condition)) {
        problems.add(
            "the step '"
                + title
                + "' has a condition that is not approved for it: "
                + condition.strip());
      }
    }
    if (approved == TRACEABILITY
        && step.stream().noneMatch(l -> l.endsWith(" --realstore standby"))) {
      problems.add("the step '" + title + "' does not run in --realstore standby mode");
    }
    for (String selected : List.of("PgDurabilityUpIT", "PgDurabilityDownIT")) {
      boolean mustSelect =
          approved.stream().anyMatch(l -> l.contains("-Dit.test=" + selected + " "));
      boolean selects =
          step.stream()
              .anyMatch(
                  l -> Pattern.compile("-Dit\\.test=" + selected + "(\\s|$)").matcher(l).find());
      if (mustSelect && !selects) {
        problems.add("the step '" + title + "' does not select " + selected);
      }
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

  /** Rules on every line of the job, wherever it is. */
  private static void lineProblems(List<String> job, List<String> problems) {
    for (String line : job) {
      String stripped = line.strip();
      if (line.contains("continue-on-error")) {
        problems.add("a failure may be tolerated: " + stripped);
      }
      if (line.startsWith("    if:")) {
        problems.add("the job may be skipped: " + stripped);
      }
      if (line.startsWith("        if:")
          && !line.equals(ALWAYS)
          && !line.equals(FAILURE)
          && !line.equals(NOT_CANCELLED)) {
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
        problems.add("sudo in the workflow: " + stripped);
      }
      if (DOCKER_BY_PATH.matcher(line).find()) {
        problems.add("a docker binary named by its path in the workflow: " + stripped);
      }
      if (SERVICE_KEY.matcher(line).matches()) {
        problems.add("the job starts a service container or publishes a port: " + stripped);
      }
    }
  }

  /**
   * The Maven lines: only the approved properties, one module, and no option that tolerates a
   * failure.
   */
  private static void mavenProblems(List<String> job, List<String> problems) {
    for (String line : job) {
      int at = line.indexOf("./mvnw");
      if (at < 0) {
        continue;
      }
      List<String> words = words(line.substring(at + "./mvnw".length()));
      boolean install = words.contains("install");
      boolean verify = words.contains("verify");
      if (install == verify) {
        problems.add("a Maven line that is neither an install nor a verify: " + line.strip());
        continue;
      }
      Set<String> allowed = install ? INSTALL_PROPERTIES : VERIFY_PROPERTIES;
      for (int i = 0; i < words.size(); i++) {
        String word = words.get(i);
        if (word.startsWith("-D")) {
          if (allowed.contains(word)) {
            continue;
          }
          if (word.contains("failIfNo")) {
            problems.add(NO_SELECTED_TEST + ": " + word);
          } else {
            problems.add("a Maven property outside the approved set: " + word);
          }
        } else if (word.equals("-pl")) {
          String module = i + 1 < words.size() ? words.get(++i) : "(nothing)";
          if (!module.equals("regpack-postgres")) {
            problems.add("-pl names another module than regpack-postgres: " + module);
          }
        } else if (!word.equals("-am") && !word.equals("install") && !word.equals("verify")) {
          problems.add("a Maven option or goal outside the approved set: " + word);
        }
      }
    }
  }

  private static void workflowDockerProblems(List<String> job, List<String> problems) {
    Set<String> seen = new LinkedHashSet<>();
    for (DockerCall call : dockerCalls(job)) {
      seen.add(call.verb());
      if (!WORKFLOW_VERBS.contains(call.verb())) {
        problems.add(
            "docker verb '"
                + call.verb()
                + "' in the workflow is outside the allow-list (stop, logs, exec, rm): "
                + call.line().strip());
      }
    }
    for (String verb : List.of("stop", "logs", "exec", "rm")) {
      if (!seen.contains(verb)) {
        problems.add("the workflow never uses docker " + verb);
      }
    }
  }

  // ---------------------------------------------------------------- the script

  private static Optional<String> pinnedImage(String ci) {
    for (String line : CiCompareStep.jobLines(ci, "ledger-pg")) {
      if (line.strip().startsWith("image: ")) {
        return Optional.of(line.strip().substring("image: ".length()));
      }
    }
    return Optional.empty();
  }

  /** A line of the script that holds the label of a step and where it is. */
  private record Marker(String label, int index) {}

  private static void scriptProblems(
      Optional<String> pinned, String script, List<String> problems) {
    List<String> code = codeLines(script);
    if (code.isEmpty()) {
      problems.add("the script is empty");
      return;
    }
    for (String line : code) {
      if (line.endsWith("\\")) {
        problems.add(
            "a script line continues on the next line (the checker reads one command per line): "
                + line);
      }
    }
    if (!code.get(0).equals("set -euo pipefail")) {
      problems.add("the first command of the script is not set -euo pipefail");
    }
    if (code.size() < 2 || !code.get(1).equals(GUARD)) {
      problems.add(
          "the second command of the script is not the runner guard of spikes/scripts/pg-up.sh");
    }
    if (!code.get(code.size() - 1).equals(READY)) {
      problems.add("the script does not end with the ready line (" + READY + ")");
    }
    imageProblems(pinned, code, problems);
    sudoAndCommandProblems(code, problems);
    dockerProblems(pinned, code, problems);
    quorumProblems(code, problems);
    baseBackupProblems(code, problems);
    orderProblems(code, problems);
    for (String line : code) {
      if (SWALLOWED.matcher(line).find()) {
        problems.add("a failure is swallowed: " + line);
      }
    }
  }

  private static void imageProblems(
      Optional<String> pinned, List<String> code, List<String> problems) {
    if (pinned.isEmpty()) {
      problems.add("the job ledger-pg has no pinned image to compare the script with");
    }
    int assignments = 0;
    for (String line : code) {
      Matcher m = IMAGE_ASSIGNMENT.matcher(line);
      while (m.find()) {
        assignments++;
        String value = unquote(m.group(1));
        if (pinned.isEmpty() || !value.equals(pinned.get())) {
          problems.add("the image literal is not the pinned digest of ledger-pg: " + value);
        }
      }
    }
    if (assignments != 1) {
      problems.add("expected exactly one IMAGE= assignment in the script, found " + assignments);
    }
  }

  private static void sudoAndCommandProblems(List<String> code, List<String> problems) {
    for (String line : code) {
      boolean install = line.equals(SUDO_ROOT) || line.equals(SUDO_DIRS);
      if (SUDO.matcher(line).find() && !install) {
        problems.add("sudo is used outside the two install lines of AP-6: " + line);
      }
      if (DOCKER_BY_PATH.matcher(line).find()) {
        problems.add("a docker binary named by its path in the script: " + line);
      }
      if (!install) {
        Matcher m = FORBIDDEN_COMMAND.matcher(line);
        while (m.find()) {
          problems.add(
              "a command that is not allowed in the script: " + m.group(1) + " (" + line + ")");
        }
      }
    }
  }

  private static void quorumProblems(List<String> code, List<String> problems) {
    if (!code.contains(ALTER_LINE)) {
      problems.add("the quorum setting is missing or changed; it must be exactly: " + ALTER_LINE);
    }
    for (String line : code) {
      if (line.contains("synchronous_standby_names")
          && !line.equals(ALTER_LINE)
          && !line.equals(SHOW_LINE)) {
        problems.add("synchronous_standby_names is set outside the approved line: " + line);
      }
    }
  }

  /** The base backup starts with a fast checkpoint: the default spread one is not bounded. */
  private static void baseBackupProblems(List<String> code, List<String> problems) {
    for (String line : code) {
      if (line.startsWith("docker run --rm ")
          && line.contains(" pg_basebackup ")
          && !line.contains(" -X stream -c fast ")) {
        problems.add(
            "the base backup does not ask for a fast checkpoint (-X stream -c fast): " + line);
      }
    }
  }

  private static void orderProblems(List<String> code, List<String> problems) {
    List<Marker> markers = new ArrayList<>();
    markers.add(marker(code, "pull of the pinned image", l -> l.equals(PULL)));
    markers.add(marker(code, "first sudo install line", l -> l.equals(SUDO_ROOT)));
    markers.add(marker(code, "second sudo install line", l -> l.equals(SUDO_DIRS)));
    markers.add(
        marker(
            code,
            "start of the primary",
            l -> l.startsWith("docker run -d --name regpack-primary ")));
    markers.add(marker(code, "wait for the primary", l -> l.equals("wait_tcp 5432")));
    markers.add(
        marker(
            code,
            "base backup",
            l -> l.startsWith("docker run --rm ") && l.contains(" pg_basebackup ")));
    markers.add(
        marker(
            code,
            "start of the standby",
            l -> l.startsWith("docker run -d --name regpack-standby ")));
    markers.add(marker(code, "wait for the standby", l -> l.equals("wait_tcp 5433")));
    markers.add(marker(code, "quorum setting", l -> l.equals(ALTER_LINE)));
    markers.add(marker(code, "wait for the quorum standby", l -> l.equals(QUORUM_WAIT)));
    int last = -1;
    String lastLabel = "";
    for (Marker marker : markers) {
      if (marker.index() < 0) {
        problems.add("the script has no " + marker.label());
      } else if (marker.index() < last) {
        problems.add("the " + marker.label() + " comes before the " + lastLabel);
      } else {
        last = marker.index();
        lastLabel = marker.label();
      }
    }
  }

  private static Marker marker(
      List<String> code, String label, java.util.function.Predicate<String> match) {
    for (int i = 0; i < code.size(); i++) {
      if (match.test(code.get(i))) {
        return new Marker(label, i);
      }
    }
    return new Marker(label, -1);
  }

  private static void dockerProblems(
      Optional<String> pinned, List<String> code, List<String> problems) {
    Set<String> servers = new LinkedHashSet<>();
    for (DockerCall call : dockerCalls(code)) {
      String where = " (" + call.line().strip() + ")";
      if (!SCRIPT_VERBS.contains(call.verb())) {
        problems.add(
            "docker verb '"
                + call.verb()
                + "' in the script is outside the allow-list (pull, run, exec): "
                + call.line().strip());
        continue;
      }
      switch (call.verb()) {
        case "pull" -> pullProblems(pinned, call, problems);
        case "exec" -> execProblems(call, problems);
        default -> runProblems(pinned, call, servers, problems);
      }
      if (call.words().isEmpty()) {
        problems.add("a docker " + call.verb() + " without arguments" + where);
      }
    }
    if (!code.contains(PULL)) {
      problems.add("the script has no pull of the pinned image (" + PULL + ")");
    }
    if (!servers.equals(Set.of("regpack-primary", "regpack-standby"))) {
      problems.add(
          "the script does not start both servers (docker run -d --name regpack-primary and"
              + " regpack-standby), it starts: "
              + servers);
    }
  }

  private static void pullProblems(
      Optional<String> pinned, DockerCall call, List<String> problems) {
    List<String> words = call.words();
    if (words.size() != 1) {
      problems.add("a docker pull with options or several images: " + call.line().strip());
    } else if (!isPinnedImage(words.get(0), pinned)) {
      problems.add("a docker pull of another image than the pinned one: " + unquote(words.get(0)));
    }
  }

  private static void execProblems(DockerCall call, List<String> problems) {
    List<String> words = call.words();
    if (words.isEmpty()) {
      return;
    }
    if (words.get(0).startsWith("-")) {
      problems.add("a docker exec with an option: " + words.get(0));
    } else if (!unquote(words.get(0)).equals("regpack-primary")) {
      problems.add(
          "a docker exec into another container than regpack-primary: " + unquote(words.get(0)));
    }
  }

  private static boolean isPinnedImage(String word, Optional<String> pinned) {
    String image = unquote(word);
    return image.equals("$IMAGE") || (pinned.isPresent() && image.equals(pinned.get()));
  }

  private static void runProblems(
      Optional<String> pinned, DockerCall call, Set<String> servers, List<String> problems) {
    List<String> words = call.words();
    boolean detached = false;
    boolean removed = false;
    String name = null;
    String network = null;
    int i = 0;
    for (; i < words.size() && words.get(i).startsWith("-"); i++) {
      String option = words.get(i);
      String value = null;
      int equals = option.indexOf('=');
      if (option.startsWith("--") && equals > 0) {
        value = unquote(option.substring(equals + 1));
        option = option.substring(0, equals);
      }
      if (option.equals("-d")) {
        detached = true;
      } else if (option.equals("--rm")) {
        removed = true;
      } else if (RUN_VALUE_OPTIONS.contains(option)) {
        if (value == null) {
          if (i + 1 >= words.size()) {
            problems.add("a docker run option without a value: " + option);
            return;
          }
          value = unquote(words.get(++i));
        }
        switch (option) {
          case "--name" -> name = value;
          case "--network" -> network = value;
          case "-e" -> {
            if (!value.equals("POSTGRES_HOST_AUTH_METHOD=trust")) {
              problems.add(
                  "a docker run environment value outside the approved one"
                      + " (POSTGRES_HOST_AUTH_METHOD=trust): "
                      + value);
            }
          }
          case "-v" -> mountProblems(value, problems);
          case "--user" -> {
            if (!value.equals("999:999")) {
              problems.add("a docker run user other than 999:999: " + value);
            }
          }
          default -> {}
        }
      } else {
        problems.add(
            "docker run option outside the approved list: "
                + words.get(i)
                + " (allowed: -d, --rm, --name, --network, --shm-size, -e, -v, --user)");
        return;
      }
    }
    if (i >= words.size()) {
      problems.add("a docker run without an image: " + call.line().strip());
      return;
    }
    if (!isPinnedImage(words.get(i), pinned)) {
      problems.add("a docker run of another image than the pinned one: " + unquote(words.get(i)));
    }
    List<String> command = words.subList(i + 1, words.size());
    if (!"host".equals(network)) {
      problems.add("a docker run without --network host: " + call.line().strip());
    }
    if (detached) {
      if ("regpack-primary".equals(name) || "regpack-standby".equals(name)) {
        servers.add(name);
      } else {
        problems.add(
            "a server container with another name than regpack-primary or regpack-standby: "
                + name);
      }
      serverSettingProblems(command, problems);
    } else if (!removed) {
      problems.add(
          "a docker run that is neither a server (-d) nor the removed base backup (--rm): "
              + call.line().strip());
    } else if (command.isEmpty() || !command.get(0).equals("pg_basebackup")) {
      problems.add(
          "a removed container runs something other than pg_basebackup: " + call.line().strip());
    }
  }

  private static void serverSettingProblems(List<String> command, List<String> problems) {
    int listen = 0;
    boolean wide = false;
    for (String word : command) {
      String setting = unquote(word);
      if (setting.contains("listen_addresses")) {
        listen++;
        if (!setting.equals("listen_addresses=127.0.0.1")) {
          wide = true;
        }
      }
      if (setting.contains("synchronous_standby_names")
          || setting.contains("synchronous_commit")
          || setting.contains("fsync")
          || setting.contains("full_page_writes")) {
        problems.add(
            "a server setting that weakens or preempts the durability fixture: " + setting);
      }
    }
    if (listen == 0 || wide) {
      problems.add(
          "a server that does not listen on 127.0.0.1 only (-c listen_addresses=127.0.0.1 and no"
              + " other value)");
    }
  }

  private static void mountProblems(String mount, List<String> problems) {
    String[] parts = mount.split(":", -1);
    boolean data =
        parts.length == 2
            && parts[0].startsWith("/mnt/regpack-pg/")
            && !parts[0].contains("..")
            && parts[1].equals("/var/lib/postgresql");
    boolean init =
        parts.length == 3
            && parts[0].equals("$PWD/scripts/ci/pg-init-replication.sh")
            && parts[1].equals("/docker-entrypoint-initdb.d/10-replication.sh")
            && parts[2].equals("ro");
    if (!data && !init) {
      problems.add("a mount outside /mnt/regpack-pg and the read-only init script: " + mount);
    }
  }

  // ---------------------------------------------------------------- text helpers

  /** A docker command in text: its verb, the words after the verb, and its line. */
  record DockerCall(String verb, List<String> words, String line) {}

  private static List<DockerCall> dockerCalls(List<String> lines) {
    List<DockerCall> calls = new ArrayList<>();
    for (String line : lines) {
      if (line.strip().startsWith("#")) {
        continue;
      }
      Matcher m = DOCKER.matcher(line);
      while (m.find()) {
        List<String> words = words(line.substring(m.end()));
        if (words.isEmpty()) {
          calls.add(new DockerCall("", List.of(), line));
        } else {
          calls.add(new DockerCall(unquote(words.get(0)), words.subList(1, words.size()), line));
        }
      }
    }
    return calls;
  }

  /** Blank-separated words; a quoted part (single or double quotes) stays inside its word. */
  static List<String> words(String text) {
    List<String> words = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    char quote = 0;
    for (char c : text.toCharArray()) {
      if (quote != 0) {
        current.append(c);
        if (c == quote) {
          quote = 0;
        }
      } else if (c == '"' || c == '\'') {
        quote = c;
        current.append(c);
      } else if (Character.isWhitespace(c)) {
        if (current.length() > 0) {
          words.add(current.toString());
          current.setLength(0);
        }
      } else {
        current.append(c);
      }
    }
    if (current.length() > 0) {
      words.add(current.toString());
    }
    return words;
  }

  static String unquote(String word) {
    if (word.length() >= 2) {
      char first = word.charAt(0);
      if ((first == '"' || first == '\'') && word.charAt(word.length() - 1) == first) {
        return word.substring(1, word.length() - 1);
      }
    }
    return word;
  }
}
