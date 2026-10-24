package dev.regpack.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Static checks of the packaging files under {@code packaging/}; no service is installed. */
class PackagingFilesTest {
  private static Path repoRoot() {
    return Path.of(System.getProperty("regpack.repo.root"));
  }

  private static String asciiLfText(Path file) throws IOException {
    byte[] bytes = Files.readAllBytes(file);
    for (byte b : bytes) {
      assertThat(b >= 0).as(file + " is ASCII").isTrue();
      assertThat(b).as(file + " has no CR").isNotEqualTo((byte) '\r');
    }
    try {
      return StandardCharsets.US_ASCII
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      throw new IOException(file + " is not ASCII", e);
    }
  }

  /**
   * A plain (unquoted) YAML value that contains ": " is read as a nested mapping and breaks the
   * chart, as the first CI run showed for Chart.yaml. Helm lint is the authority; this catches the
   * mistake before CI.
   */
  @Test
  void helmYamlPlainValuesDoNotContainAMappingIndicator() throws IOException {
    for (String relative :
        List.of("packaging/helm/regpack/Chart.yaml", "packaging/helm/regpack/values.yaml")) {
      List<String> lines = Files.readAllLines(repoRoot().resolve(relative), StandardCharsets.UTF_8);
      for (int i = 0; i < lines.size(); i++) {
        String stripped = lines.get(i).strip();
        int indicator = stripped.indexOf(": ");
        if (stripped.isEmpty() || stripped.startsWith("#") || indicator < 0) {
          continue;
        }
        String value = stripped.substring(indicator + 2).strip();
        int comment = value.indexOf(" #");
        if (comment >= 0) {
          value = value.substring(0, comment).strip();
        }
        if (value.isEmpty() || "\"'[{|>".indexOf(value.charAt(0)) >= 0) {
          continue;
        }
        assertThat(value)
            .as("%s line %d: an unquoted value must not contain \": \"", relative, i + 1)
            .doesNotContain(": ");
      }
    }
  }

  /**
   * On Ubuntu, sudo runs PAM, which re-reads /etc/environment after --preserve-env and resets
   * JAVA_HOME to the image default (Java 17 on the CI runner), so the first CI run started the
   * service on the wrong JVM. The JDK is therefore passed under its own name, through env after
   * sudo, and the smoke refuses a JDK that is not Java 25.
   */
  @Test
  void systemdSmokePassesAndChecksTheJdkExplicitly() throws IOException {
    String smoke = asciiLfText(repoRoot().resolve("packaging/systemd/smoke.sh"));
    String workflow = Files.readString(repoRoot().resolve(".github/workflows/ci.yml"));
    String readme = Files.readString(repoRoot().resolve("packaging/systemd/README.md"));
    assertThat(smoke).contains("REGPACK_JAVA_HOME", "version \"25");
    // The script's own foreground commands (the launcher's smoke run) must use the verified JDK
    // too.
    assertThat(smoke).contains("export JAVA_HOME=\"$JDK\"");
    assertThat(smoke).doesNotContain("--preserve-env");
    assertThat(workflow).contains("sudo env \"REGPACK_JAVA_HOME=$JAVA_HOME\"");
    assertThat(workflow).doesNotContain("--preserve-env");
    assertThat(readme).doesNotContain("--preserve-env");
  }

  @Test
  void systemdUnitHasHardeningAndCleanStopSettings() throws IOException {
    Path unit = repoRoot().resolve("packaging/systemd/regpack.service");
    String text = asciiLfText(unit);
    List<String> lines = List.of(text.split("\n", -1));
    int service = lines.indexOf("[Service]");
    int install = lines.indexOf("[Install]");
    assertThat(service).isNotNegative().isLessThan(install);
    List<String> serviceSection = lines.subList(service + 1, install);

    // The hardening keys must sit in [Service], not merely somewhere in the file.
    assertThat(serviceSection)
        .contains(
            "Type=simple",
            "DynamicUser=yes",
            "StateDirectory=regpack",
            "EnvironmentFile=-/etc/regpack/regpack.env",
            "ExecStart=/opt/regpack/bin/regpack serve",
            "SuccessExitStatus=143",
            "NoNewPrivileges=yes",
            "ProtectSystem=strict",
            "ProtectHome=yes",
            "PrivateTmp=yes",
            "RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX",
            "CapabilityBoundingSet=",
            "Restart=on-failure");
    assertThat(lines)
        .noneMatch(l -> l.strip().startsWith("User=") || l.strip().startsWith("Group="));

    String smoke = asciiLfText(repoRoot().resolve("packaging/systemd/smoke.sh"));
    List<String> smokeLines = List.of(smoke.split("\n", -1));
    assertThat(smokeLines)
        .anyMatch(l -> l.startsWith("tar ") && l.contains("--no-same-owner"))
        .anyMatch(l -> l.startsWith("[ \"${2:-}\" = \"--destructive\" ]"));
  }

  @Test
  void windowsScriptsPinProcrunChecksumAndAreAscii() throws IOException {
    Path dir = repoRoot().resolve("packaging/windows");
    String procrun = asciiLfText(dir.resolve("install-procrun.ps1"));
    String service = asciiLfText(dir.resolve("install-service.ps1"));
    String smoke = asciiLfText(dir.resolve("smoke.ps1"));

    assertThat(procrun)
        .contains(
            "a878177edc92e663c1113858f8903451ec02b5b033bca373bea4f8761d9196323771301fcd6a7b8387d8e2300f7a1056b9e08e83fca9703df32ac3ecd5b129f7")
        .contains("Get-FileHash -Algorithm SHA512")
        .contains("commons-daemon-1.6.1-bin-windows.zip");
    assertThat(service)
        .contains(
            "--StartMode=jvm",
            "--StopMode=jvm",
            "dev.regpack.app.WindowsServiceEntry",
            "--StartMethod=start",
            "--StopMethod=stop",
            "bin\\server\\jvm.dll",
            "--ServiceUser",
            "icacls");
    assertThat(smoke).contains("stopped cleanly", "ExitCode", "-Destructive");

    // The default service account is LocalService and nothing defaults to LocalSystem.
    assertThat(service).contains("[string]$ServiceUser = 'NT AUTHORITY\\LocalService'");
    assertThat(List.of(service.split("\n", -1)))
        .filteredOn(l -> !l.strip().startsWith("#"))
        .noneMatch(l -> l.contains("LocalSystem"));
    // Only the logs directory is writable by LocalService (S-1-5-19); the install tree is
    // read-only.
    assertThat(service)
        .contains("'*S-1-5-19:(OI)(CI)RX'")
        .doesNotContain("S-1-5-19:(OI)(CI)F")
        .containsOnlyOnce("S-1-5-19:(OI)(CI)M");
    assertThat(List.of(service.split("\n", -1)))
        .filteredOn(l -> l.contains("S-1-5-19:(OI)(CI)M"))
        .allMatch(l -> l.startsWith("icacls $logs "));
  }

  @Test
  void dockerfileIsPinnedByDigestAndRunsNonRoot() throws IOException {
    String text = asciiLfText(repoRoot().resolve("packaging/container/Dockerfile"));
    List<String> lines = List.of(text.split("\n", -1));

    assertThat(lines)
        .anyMatch(l -> l.matches("FROM eclipse-temurin:25-jre-noble@sha256:[0-9a-f]{64}"));
    // The last USER instruction decides who runs the container.
    assertThat(lines.stream().filter(l -> l.startsWith("USER ")).reduce((first, second) -> second))
        .contains("USER 10001:10001");
    assertThat(lines)
        .anyMatch(
            l ->
                l.startsWith("ENTRYPOINT [\"java\"")
                    && l.contains("/opt/regpack/lib/regpack-app.jar"));
    assertThat(text).doesNotContain(":latest");
  }

  @Test
  void ciWorkflowHasTheExpectedJobsReadOnlyPermissionsAndMatchingArtefactNames()
      throws IOException {
    String ci = asciiLfText(repoRoot().resolve(".github/workflows/ci.yml"));

    assertThat(ci).containsPattern("(?m)^permissions:\\n  contents: read$");
    List<String> ciLines = List.of(ci.split("\n", -1));
    List<String> jobIds =
        ciLines.subList(ciLines.indexOf("jobs:") + 1, ciLines.size()).stream()
            .filter(l -> l.matches("  [a-z][a-z-]*:"))
            .map(l -> l.strip().replace(":", ""))
            .toList();
    assertThat(jobIds)
        .containsExactly(
            "build",
            "determinism-compare",
            "spikes",
            "ledger-pg",
            "ledger-capacity",
            "ledger-durability",
            "evidence-worm",
            "smoke-linux",
            "smoke-windows",
            "smoke-kubernetes");

    // The distribution is uploaded once and downloaded under the same name by the three smokes.
    assertThat(ci).containsOnlyOnce("          name: regpack-dist\n          path: |");
    assertThat(ci.split("name: regpack-dist\n          path: dist", -1)).hasSize(4);
    // The digests are uploaded per matrix os and compared from the directory of the same name.
    assertThat(ci).contains("os: [ubuntu-24.04, windows-2025]");
    assertThat(ci).contains("name: toy-digest-${{ matrix.os }}");
    assertThat(ci)
        .contains("pattern: toy-digest-*")
        .contains("digests/toy-digest-ubuntu-24.04/toy-digest.txt")
        .contains("digests/toy-digest-windows-2025/toy-digest.txt");
    // The pack-driven pipeline writes a second digest (T24): uploaded in the same artefact as the
    // skeleton digest and compared between the two operating systems like it.
    assertThat(ci)
        .contains("regpack-engine/target/toy-digest.txt")
        .contains("regpack-engine/target/pack-toy-digest.txt")
        .contains("digests/toy-digest-ubuntu-24.04/pack-toy-digest.txt")
        .contains("digests/toy-digest-windows-2025/pack-toy-digest.txt");
    // The names above are only a smoke check; what the steps do is checked by their meaning (the
    // compare step reads both files of both systems and fails on a difference, an empty or a
    // missing file), with broken copies and an execution, in CiDeterminismCompareTest.
    assertThat(CiCompareStep.problems(ci)).isEmpty();
  }

  /** The lines of the job {@code job} of the workflow {@code ci}: everything below its key. */
  private static List<String> jobLines(String ci, String job) {
    List<String> lines = List.of(ci.split("\n", -1));
    int start = lines.indexOf("  " + job + ":");
    assertThat(start).as("job " + job).isNotNegative();
    int end = lines.size();
    for (int i = start + 1; i < lines.size(); i++) {
      if (lines.get(i).matches("  [a-z][a-z-]*:")) {
        end = i;
        break;
      }
    }
    return lines.subList(start + 1, end);
  }

  /**
   * AP-6 and D-L8: the real-store job pulls one service image pinned by digest, publishes its port
   * on the runner loopback only, runs on a GitHub-hosted runner and holds no credential anywhere in
   * the workflow (the service uses trust authentication).
   */
  @Test
  void ledgerJobUsesAPinnedServiceImageAndNoPasswordInTheWorkflow() throws IOException {
    String ci = asciiLfText(repoRoot().resolve(".github/workflows/ci.yml"));
    List<String> job = jobLines(ci, "ledger-pg");

    // no key that names a password, in any job, and no password inside the URL
    assertThat(ci).doesNotContainPattern("(?im)^\\s*(?:-\\s*)?[a-z_]*password[a-z_]*\\s*:");
    assertThat(ci).doesNotContainPattern("(?i)[?&]password=");
    assertThat(ci).doesNotContainPattern("jdbc:postgresql://[^/\\s]*@");

    List<String> images = job.stream().filter(l -> l.strip().startsWith("image:")).toList();
    assertThat(images).hasSize(1);
    assertThat(images.get(0).strip())
        .isEqualTo(
            "image: postgres:18.6-trixie@sha256:"
                + "74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336");
    assertThat(images.get(0)).containsPattern("@sha256:[0-9a-f]{64}$");

    int ports = -1;
    for (int i = 0; i < job.size(); i++) {
      if (job.get(i).strip().equals("ports:")) {
        ports = i;
      }
    }
    assertThat(ports).as("ports: line").isNotNegative();
    List<String> published = new java.util.ArrayList<>();
    for (int i = ports + 1; i < job.size() && job.get(i).strip().startsWith("- "); i++) {
      published.add(job.get(i).strip());
    }
    assertThat(published).isNotEmpty().allMatch(p -> p.startsWith("- 127.0.0.1:"));

    List<String> runsOn = job.stream().filter(l -> l.strip().startsWith("runs-on:")).toList();
    assertThat(runsOn).hasSize(1);
    assertThat(runsOn.get(0).strip()).isEqualTo("runs-on: ubuntu-24.04");

    // the real store is required, so a missing server fails the job instead of skipping its tests
    assertThat(job).anyMatch(l -> l.strip().equals("REGPACK_PG_REQUIRED: 'true'"));
    assertThat(job).anyMatch(l -> l.strip().equals("POSTGRES_HOST_AUTH_METHOD: trust"));
    assertThat(job)
        .anyMatch(
            l ->
                l.strip()
                    .equals(
                        "REGPACK_PG_URL:"
                            + " jdbc:postgresql://127.0.0.1:5432/postgres?user=postgres"));
    // the report artefact is not the distribution
    assertThat(job).anyMatch(l -> l.strip().equals("name: ledger-pg-reports"));
  }

  /**
   * FP3 (TEETH-1): the steps of job ledger-pg that run the real-store suites and gate on them are
   * given word for word, and nothing in the job skips, ignores or tolerates a failure: no job-level
   * condition, no continue-on-error, one verify step of the exact command, the install with -pl
   * regpack-postgres -am, the traceability step that always runs in {@code --realstore only} mode
   * and the annotation step.
   */
  @Test
  void ledgerJobRunsAndGatesOnTheRealStoreSuites() throws IOException {
    String ci = asciiLfText(repoRoot().resolve(".github/workflows/ci.yml"));
    assertThat(CiLedgerJob.problems(ci)).isEmpty();
  }

  /** The checker has teeth: each edit of the kinds listed below is reported. */
  @Test
  void theLedgerJobCheckerReportsEachWayOfNeuteringTheJob() throws IOException {
    String ci = asciiLfText(repoRoot().resolve(".github/workflows/ci.yml"));
    assertThat(CiLedgerJob.problems(ci)).isEmpty();
    String traceability =
        CiLedgerJob.TRACEABILITY_NAME
            + "\n        if: always()\n        shell: bash\n"
            + CiLedgerJob.TRACEABILITY_RUN
            + "\n";
    String annotate =
        CiLedgerJob.ANNOTATE_NAME
            + "\n        if: always()\n        shell: bash\n"
            + CiLedgerJob.ANNOTATE_RUN
            + "\n";
    String verify = CiLedgerJob.VERIFY + "\n";
    java.util.Map<String, String> broken = new java.util.LinkedHashMap<>();
    broken.put(
        "a job-level condition",
        inLedgerJob(
            ci, "    runs-on: ubuntu-24.04\n", "    if: false\n    runs-on: ubuntu-24.04\n"));
    broken.put(
        "continue-on-error on the verify step",
        inLedgerJob(ci, verify, verify + "        continue-on-error: true\n"));
    broken.put(
        "continue-on-error on the job",
        inLedgerJob(
            ci,
            "    timeout-minutes: 40\n",
            "    timeout-minutes: 40\n    continue-on-error: true\n"));
    broken.put(
        "test failures ignored",
        inLedgerJob(
            ci,
            verify,
            "      - run: ./mvnw verify -pl regpack-postgres -Dmaven.test.failure.ignore=true\n"));
    broken.put(
        "tests skipped",
        inLedgerJob(ci, verify, "      - run: ./mvnw verify -pl regpack-postgres -DskipTests\n"));
    broken.put(
        "integration tests skipped",
        inLedgerJob(ci, verify, "      - run: ./mvnw verify -pl regpack-postgres -DskipITs\n"));
    broken.put(
        "another module verified",
        inLedgerJob(ci, verify, "      - run: ./mvnw verify -pl regpack-engine\n"));
    broken.put("no verify step", inLedgerJob(ci, verify, ""));
    broken.put("a second verify step", inLedgerJob(ci, verify, verify + verify));
    broken.put(
        "no -am on the install",
        inLedgerJob(ci, "install -pl regpack-postgres -am", "install -pl regpack-postgres"));
    broken.put("no traceability step", inLedgerJob(ci, traceability, ""));
    broken.put(
        "traceability in skip mode", inLedgerJob(ci, "--realstore only", "--realstore skip"));
    broken.put("traceability in the default mode", inLedgerJob(ci, " --realstore only", ""));
    broken.put(
        "traceability without if always",
        inLedgerJob(ci, traceability, traceability.replace("        if: always()\n", "")));
    broken.put("no annotation step", inLedgerJob(ci, annotate, ""));
    broken.put(
        "annotation without if always",
        inLedgerJob(ci, annotate, annotate.replace("        if: always()\n", "")));
    broken.put(
        "a one minute timeout", inLedgerJob(ci, "timeout-minutes: 40", "timeout-minutes: 1"));
    broken.put(
        "no health command", inLedgerJob(ci, "--health-cmd \"pg_isready -U postgres\" ", ""));
    broken.put("one health retry", inLedgerJob(ci, "--health-retries 12", "--health-retries 1"));
    broken.put(
        "a missing server skips the tests",
        inLedgerJob(ci, "REGPACK_PG_REQUIRED: 'true'", "REGPACK_PG_REQUIRED: 'false'"));
    for (java.util.Map.Entry<String, String> copy : broken.entrySet()) {
      assertThat(copy.getValue()).as(copy.getKey()).isNotEqualTo(ci);
      assertThat(CiLedgerJob.problems(copy.getValue())).as(copy.getKey()).isNotEmpty();
    }
  }

  /** The workflow with the first {@code from} inside job ledger-pg replaced by {@code to}. */
  private static String inLedgerJob(String ci, String from, String to) {
    int job = ci.indexOf("\n  ledger-pg:\n");
    assertThat(job).as("job ledger-pg").isNotNegative();
    int at = ci.indexOf(from, job);
    assertThat(at).as("text in job ledger-pg: " + from).isNotNegative();
    return ci.substring(0, at) + to + ci.substring(at + from.length());
  }

  /**
   * T21 and AP-3, AP-6, AP-8: the capacity job runs only on a manual dispatch that sets both
   * inputs, on a GitHub-hosted runner, pulls the one digest-pinned image of the real-store job (the
   * same digest), publishes its port on the loopback only, holds no secret and no password, runs
   * only the docker commands that restart and inspect its own service container (never a pull, run
   * or login), bounds the job and every step by a timeout, and keeps its sampler files as an
   * artefact.
   */
  @Test
  void ledgerCapacityJobIsGuardedPinnedBoundedAndFreeOfCredentials() throws IOException {
    String ci = asciiLfText(repoRoot().resolve(".github/workflows/ci.yml"));
    List<String> job = jobLines(ci, "ledger-capacity");
    String pinned =
        "postgres:18.6-trixie@sha256:"
            + "74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336";

    // a manual dispatch has a concurrency group of its own: a push to the branch during the long
    // capacity run cannot cancel it, and the dispatch cannot cancel a run of the push
    assertThat(ci)
        .contains(
            "group: ci-${{ github.workflow }}-${{ github.ref }}-${{ github.event_name =="
                + " 'workflow_dispatch' && 'manual' || 'auto' }}");

    // the dispatch inputs exist and default to false
    assertThat(ci)
        .contains("  workflow_dispatch:\n    inputs:\n      capacity:")
        .contains("      confirm_downloads:");
    assertThat(job)
        .contains(
            "    if: github.event_name == 'workflow_dispatch' && inputs.capacity"
                + " && inputs.confirm_downloads");
    assertThat(job).contains("    runs-on: ubuntu-24.04");
    assertThat(job).anyMatch(l -> l.matches("    timeout-minutes: [0-9]+"));

    // the one pinned image of AP-6, the same as the real-store job, and the loopback port
    List<String> images = job.stream().filter(l -> l.strip().startsWith("image:")).toList();
    assertThat(images).containsExactly("        image: " + pinned);
    assertThat(job).contains("      PG_IMAGE: " + pinned);
    assertThat(jobLines(ci, "ledger-pg")).contains("        image: " + pinned);
    assertThat(job).contains("          - 127.0.0.1:5432:5432");
    assertThat(job).contains("      REGPACK_PG_REQUIRED: 'true'");
    assertThat(job).contains("          POSTGRES_HOST_AUTH_METHOD: trust");

    // no secret, no token, no password; the job reads nothing from the secret store
    assertThat(job).noneMatch(l -> l.contains("secrets."));
    assertThat(job).noneMatch(l -> l.toLowerCase(java.util.Locale.ROOT).contains("password"));
    assertThat(job).noneMatch(l -> l.contains("GITHUB_TOKEN"));

    // docker is used only to find, restart and inspect the job's own service container: an allow
    // list of verbs, so that a pull, run, login, build, stop, create, cp, network or volume (or a
    // verb behind an option) fails the test
    java.util.regex.Matcher docker =
        java.util.regex.Pattern.compile("(?<![\\w/.-])docker(?![\\w/.-])\\s+(\\S*)")
            .matcher(String.join("\n", job));
    int dockerCalls = 0;
    while (docker.find()) {
      dockerCalls++;
      assertThat(docker.group(1))
          .as("docker verb in: " + docker.group())
          .isIn("ps", "exec", "restart", "inspect");
    }
    assertThat(dockerCalls).isGreaterThanOrEqualTo(4);
    assertThat(job).anyMatch(l -> l.contains("docker restart \"$cid\""));
    // the server settings are read with current_setting (the raw setting of pg_settings is in
    // server units: shared_buffers of 340MB would print as 435208kB)
    assertThat(job).anyMatch(l -> l.contains("current_setting(name)"));
    assertThat(job).noneMatch(l -> l.contains("coalesce(unit"));
    assertThat(job).anyMatch(l -> l.contains("ALTER SYSTEM SET shared_buffers"));
    assertThat(job).noneMatch(l -> l.contains("sudo"));

    // the variants of the plan: A no limit, B the SP-L memory and ratio, C as B without waiting
    assertThat(job)
        .contains(
            "          - variant: A",
            "          - variant: B",
            "          - variant: C",
            "            shared_buffers: 2GB",
            "            shared_buffers: 340MB",
            "            synchronous_commit: 'off'");
    assertThat(job.stream().filter(l -> l.contains("--memory=1363m")).count()).isEqualTo(2);

    // the probe is the capacity group of one class, with an explicit override of the exclusion
    assertThat(job)
        .anyMatch(
            l ->
                l.contains("-Dgroups=capacity")
                    && l.contains("-DexcludedGroups=none")
                    && l.contains("-Dit.test=LedgerBurstProbeIT"));

    // every step is bounded by a timeout (a step starts at six spaces and a dash)
    int stepsAt = job.indexOf("    steps:");
    assertThat(stepsAt).as("steps: line").isNotNegative();
    List<List<String>> steps = new java.util.ArrayList<>();
    for (String line : job.subList(stepsAt + 1, job.size())) {
      if (line.startsWith("      - ")) {
        steps.add(new java.util.ArrayList<>());
      }
      steps.get(steps.size() - 1).add(line);
    }
    assertThat(steps).hasSizeGreaterThanOrEqualTo(7);
    for (List<String> step : steps) {
      assertThat(step)
          .as("step starting at: " + step.get(0))
          .anyMatch(l -> l.matches(" {8}timeout-minutes: [0-9]+"));
    }

    // the read-out and the sampler files are kept: as annotations and as an artefact
    assertThat(job).contains("          name: ledger-capacity-${{ matrix.variant }}");
    assertThat(job).contains("            regpack-postgres/target/capacity/");
    assertThat(job).anyMatch(l -> l.contains("::notice title=capacity"));
    assertThat(job).anyMatch(l -> l.contains("head -n 5"));
    assertThat(ci).containsOnlyOnce("name: regpack-dist\n          path: |");
  }

  private static final String PINNED_IMAGE =
      "postgres:18.6-trixie@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336";

  /**
   * T19, AP-3 and AP-6: the job ledger-durability starts its own primary and quorum standby from
   * the one digest-pinned image of the real-store job (the same digest), on the host network of a
   * GitHub-hosted runner; it holds no secret, token or password, starts no service container,
   * publishes no port, runs docker only through allow-listed verbs (the workflow: stop, logs, exec,
   * rm; the script: pull, run, exec), uses sudo only for the two install lines of AP-6, tolerates
   * no failure, and bounds the job and its long steps by a timeout.
   */
  @Test
  void ledgerDurabilityJobIsGuardedPinnedAndFreeOfCredentials() throws IOException {
    String ci = asciiLfText(repoRoot().resolve(".github/workflows/ci.yml"));
    String script = asciiLfText(repoRoot().resolve(CiDurabilityJob.SCRIPT));
    List<String> job = jobLines(ci, "ledger-durability");
    List<String> scriptLines = List.of(script.split("\n", -1));

    // the checker finds nothing in the real files
    assertThat(CiDurabilityJob.problems(ci, script)).isEmpty();
    assertThat(script).startsWith("#!/usr/bin/env bash\n").endsWith("\n");

    // a GitHub-hosted runner, bounded, a real server required, the loopback URL, no other env
    assertThat(job).contains("    runs-on: ubuntu-24.04", "    timeout-minutes: 30");
    assertThat(job.stream().filter(l -> l.strip().startsWith("runs-on:"))).hasSize(1);
    assertThat(job)
        .contains(
            "      REGPACK_PG_REQUIRED: 'true'",
            "      REGPACK_PG_URL: jdbc:postgresql://127.0.0.1:5432/postgres?user=postgres");
    assertThat(job.stream().filter(l -> l.matches(" {6}[A-Z_]+: .*"))).hasSize(2);

    // checkout and setup-java are those of the job ledger-pg, with the same SHAs and comments
    List<String> ledgerPg = jobLines(ci, "ledger-pg");
    assertThat(java.util.Collections.indexOfSubList(ledgerPg, CiDurabilityJob.CHECKOUT))
        .isNotNegative();
    assertThat(java.util.Collections.indexOfSubList(ledgerPg, CiDurabilityJob.SETUP_JAVA))
        .isNotNegative();
    assertThat(java.util.Collections.indexOfSubList(job, CiDurabilityJob.CHECKOUT)).isNotNegative();
    assertThat(java.util.Collections.indexOfSubList(job, CiDurabilityJob.SETUP_JAVA))
        .isNotNegative();

    // no secret, no token, no password, no sudo, no service container, no published port
    assertThat(job).noneMatch(l -> l.contains("secrets."));
    assertThat(job).noneMatch(l -> l.contains("GITHUB_TOKEN"));
    assertThat(job).noneMatch(l -> l.toLowerCase(java.util.Locale.ROOT).contains("password"));
    assertThat(job).noneMatch(l -> l.contains("sudo"));
    assertThat(job)
        .noneMatch(
            l ->
                l.strip().equals("services:")
                    || l.strip().equals("ports:")
                    || l.strip().startsWith("image:")
                    || l.strip().startsWith("container:"));
    assertThat(ci).doesNotContainPattern("jdbc:postgresql://[^/\\s]*@");
    assertThat(ci).doesNotContainPattern("(?i)[?&]password=");
    assertThat(job)
        .noneMatch(l -> l.contains("continue-on-error") || l.contains("|| true"))
        .noneMatch(l -> l.startsWith("    if:"));

    // the image: the same digest as the real-store job, once, in the script
    assertThat(ledgerPg).contains("        image: " + PINNED_IMAGE);
    assertThat(scriptLines.stream().filter(l -> l.startsWith("IMAGE=")))
        .containsExactly("IMAGE='" + PINNED_IMAGE + "'");
    assertThat(script).containsOnlyOnce("postgres:18.6-trixie");
    assertThat(String.join("\n", job)).doesNotContain("postgres:");

    // docker: the workflow stops, reads logs, execs and removes; the script pulls, runs and execs
    assertThat(CiDurabilityJob.dockerVerbs(job)).containsOnly("stop", "logs", "exec", "rm");
    assertThat(CiDurabilityJob.dockerVerbs(scriptLines)).containsOnly("pull", "run", "exec");
    assertThat(script).doesNotContain("docker volume").doesNotContain("--privileged");
    List<String> runs = scriptLines.stream().filter(l -> l.startsWith("docker run ")).toList();
    assertThat(runs).hasSize(3);
    for (String run : runs) {
      assertThat(run).contains(" --network host ").contains(" \"$IMAGE\" ");
      String options = run.substring(0, run.indexOf(" \"$IMAGE\" "));
      assertThat(options)
          .doesNotContain(" -p")
          .doesNotContain(" -P")
          .doesNotContain("--publish")
          .doesNotContain("--privileged");
    }
    // the base backup asks for a fast checkpoint (RUNNER-2): the default one is spread and
    // unbounded
    assertThat(runs.stream().filter(l -> l.contains(" pg_basebackup ")))
        .hasSize(1)
        .allMatch(l -> l.contains(" -R -X stream -c fast -C -S standby1_slot "));
    assertThat(runs.stream().filter(l -> l.startsWith("docker run -d ")))
        .hasSize(2)
        .allMatch(l -> l.contains(" -c listen_addresses=127.0.0.1 "));

    // sudo: exactly the two install lines of AP-6
    assertThat(CiDurabilityJob.codeLines(script).stream().filter(l -> l.contains("sudo")))
        .containsExactly(
            "sudo install -d -m 755 /mnt/regpack-pg",
            "sudo install -d -o 999 -g 999 -m 700 /mnt/regpack-pg/primary /mnt/regpack-pg/standby");

    // the three Maven steps: install, then the two selected integration-test runs, no flag that
    // lets a run with no selected test pass
    List<String> maven = job.stream().filter(l -> l.contains("./mvnw")).toList();
    assertThat(maven).hasSize(3);
    assertThat(maven).noneMatch(l -> l.contains("failsafe.failIfNoSpecifiedTests"));
    assertThat(maven)
        .filteredOn(l -> l.contains(" verify "))
        .hasSize(2)
        .allMatch(
            l ->
                l.contains("-pl regpack-postgres")
                    && l.contains("-Dgroups=durability")
                    && l.contains("-DexcludedGroups=none")
                    && l.contains("-Dtest=NoUnitTestsInThisRun"));
    assertThat(maven).anyMatch(l -> l.contains("-Dit.test=PgDurabilityUpIT "));
    assertThat(maven).anyMatch(l -> l.contains("-Dit.test=PgDurabilityDownIT "));

    // the phases, in order, each long step bounded
    int up = job.indexOf("      - name: Start the primary and the quorum standby");
    int upTests = job.indexOf("      - name: Durability tests with the standby up");
    int stop = job.indexOf("      - name: Stop the standby");
    int downTests = job.indexOf("      - name: Durability tests with the standby down");
    int traceability = job.indexOf("      - name: Standby traceability");
    assertThat(up).isNotNegative().isLessThan(upTests);
    assertThat(upTests).isLessThan(stop);
    assertThat(stop).isLessThan(downTests);
    assertThat(downTests).isLessThan(traceability);
    assertThat(job.stream().filter(l -> l.equals("        timeout-minutes: 10"))).hasSize(3);
    assertThat(job).contains("        run: bash scripts/ci/pg-quorum-up.sh");
    assertThat(job).contains("        run: docker stop regpack-standby");
    // a red Up phase must not skip the Down phase: both its steps run unless the run is cancelled
    assertThat(job.get(stop + 1)).isEqualTo("        if: ${{ !cancelled() }}");
    assertThat(job.get(downTests + 1)).isEqualTo("        if: ${{ !cancelled() }}");
    assertThat(job.stream().filter(l -> l.equals("        if: ${{ !cancelled() }}"))).hasSize(2);
    // the install step skips the integration tests of failsafe, which -DskipTests does not
    assertThat(job)
        .contains(
            "      - run: ./mvnw -DskipTests -DskipITs -Dspotless.check.skip=true install -pl"
                + " regpack-postgres -am");

    // the reports are kept under a name of their own, and the distribution upload stays unique
    assertThat(job).contains("          name: ledger-durability-reports");
    assertThat(job).contains("          path: regpack-postgres/target/failsafe-reports/TEST-*.xml");
    assertThat(ci).containsOnlyOnce("name: regpack-dist\n          path: |");

    // the replication init script of the primary
    assertThat(String.join("\n", scriptLines))
        .contains(
            "-v \"$PWD/scripts/ci/pg-init-replication.sh:"
                + "/docker-entrypoint-initdb.d/10-replication.sh:ro\"");
  }

  /**
   * MAVEN-3: the traceability step of the durability job is the net under a selection that runs
   * nothing (a class without its tag, a disabled method). So every test method of the two classes
   * it runs is a REALSTANDBY line of row I1: a method that is skipped or missing from the reports
   * turns that step red, not only the four methods that once carried the evidence.
   */
  @Test
  void everyDurabilityTestMethodIsARealStandbyLineOfRowI1() throws IOException {
    java.util.Set<String> lines = new java.util.TreeSet<>();
    for (String line : asciiLfText(repoRoot().resolve("docs/traceability.md")).split("\n", -1)) {
      String[] cells = line.split("\\|", -1);
      if (cells.length > 6
          && cells[1].strip().equals("I1")
          && cells[5].strip().equals("REALSTANDBY")) {
        lines.add(cells[4].strip());
      }
    }
    List<String> found = new java.util.ArrayList<>();
    for (String clazz : List.of("PgDurabilityUpIT", "PgDurabilityDownIT")) {
      String source =
          asciiLfText(
              repoRoot()
                  .resolve(
                      "regpack-postgres/src/test/java/dev/regpack/postgres/" + clazz + ".java"));
      for (String name : TestMethods.names(source)) {
        found.add("dev.regpack.postgres." + clazz + "#" + name);
      }
    }
    java.util.Set<String> methods = new java.util.TreeSet<>(found);
    // CI-4: any annotation between the test annotation and the method counts, so the number of
    // methods found, the number of distinct names and the number of lines are one number
    assertThat(found).as("the test methods of the two classes").hasSize(9);
    assertThat(methods).as("no name twice").hasSameSizeAs(found);
    assertThat(lines).as("the REALSTANDBY lines of row I1").isEqualTo(methods);
    assertThat(lines).as("as many lines as methods").hasSameSizeAs(found);
  }

  /**
   * CI-4: the finder of the test methods sees a method whatever stands between its annotation and
   * its name, and reads nothing from comments and strings; a test annotation it cannot read is an
   * error and never skipped.
   */
  @Test
  void theFinderOfTestMethodsSeesEveryKindOfTestMethod() {
    String source =
        """
        class X {
          /** Mentions @Test in a comment: void notAMethod() {} */
          // @Test void alsoNot() {}
          String text = "@Test void inAString() {}";
          @Test
          void plain() {}
          @Test
          @Disabled
          void disabled() {}
          @Test @Timeout(value = 5, unit = TimeUnit.SECONDS) void timeout() {}
          @Test
          @DisplayName("a (name)")
          public void withParentheses() {}
          @ParameterizedTest
          @ValueSource(strings = {"a", "b"})
          void parameterized(String s) {}
          @RepeatedTest(3)
          void repeated() {}
          @TestFactory
          java.util.stream.Stream<DynamicTest> factory() { return null; }
          @TestTemplate void template() {}
          @org.junit.jupiter.api.Test
          void qualified() {}
          @TestInstance(TestInstance.Lifecycle.PER_CLASS)
          static class Nested {}
        }
        """;
    assertThat(TestMethods.names(source))
        .containsExactly(
            "plain",
            "disabled",
            "timeout",
            "withParentheses",
            "parameterized",
            "repeated",
            "factory",
            "template",
            "qualified");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> TestMethods.names("@Test\nint field = 3;\n"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("line 1");
  }

  /**
   * The init script is the spike script, copied: the same three commands (strict mode, the PGDATA
   * guard, the one pg_hba line), and nothing else.
   */
  @Test
  void theReplicationInitScriptIsTheSpikeScriptCopiedAndNothingElse() throws IOException {
    String init = asciiLfText(repoRoot().resolve(CiDurabilityJob.INIT_SCRIPT));
    String spike = asciiLfText(repoRoot().resolve("spikes/scripts/pg-init-replication.sh"));
    assertThat(CiDurabilityJob.initScriptProblems(init)).isEmpty();
    assertThat(init).endsWith("\n");
    assertThat(CiDurabilityJob.codeLines(init)).isEqualTo(CiDurabilityJob.codeLines(spike));
    assertThat(CiDurabilityJob.codeLines(init))
        .containsExactly(
            "set -euo pipefail",
            ": \"${PGDATA:?PGDATA is not set: this script runs only inside the postgres"
                + " container}\"",
            "echo \"host replication all 127.0.0.1/32 trust\" >> \"$PGDATA/pg_hba.conf\"");

    java.util.Map<String, String> broken = new java.util.LinkedHashMap<>();
    String replication =
        "echo \"host replication all 127.0.0.1/32 trust\" >> \"$PGDATA/pg_hba.conf\"";
    broken.put("an extra docker command", init + "docker rm -f regpack-primary\n");
    broken.put("an extra sudo command", init + "sudo chmod 777 /\n");
    broken.put("another network in pg_hba", init.replace("127.0.0.1/32", "0.0.0.0/0"));
    broken.put("another database in pg_hba", init.replace("replication all", "all all"));
    broken.put("no PGDATA guard", init.replace(": \"${PGDATA:?", "# \"${PGDATA:?"));
    broken.put("no strict mode", init.replace("set -euo pipefail\n", ""));
    broken.put("no pg_hba line", init.replace(replication + "\n", ""));
    broken.put("a second pg_hba line", init + replication + "\n");
    for (java.util.Map.Entry<String, String> copy : broken.entrySet()) {
      assertThat(copy.getValue()).as(copy.getKey()).isNotEqualTo(init);
      assertThat(CiDurabilityJob.initScriptProblems(copy.getValue()))
          .as(copy.getKey())
          .isNotEmpty();
    }
  }

  /** One way of neutering the durability job: the two texts and the problem that must be named. */
  private record Neutered(String ci, String script, String problem) {}

  /** The first occurrence of {@code from} replaced, which must exist. */
  private static String replaceFirst(String text, String from, String to) {
    int at = text.indexOf(from);
    assertThat(at).as("text: " + from).isNotNegative();
    return text.substring(0, at) + to + text.substring(at + from.length());
  }

  /**
   * The text with the first line that starts with {@code a} and that starting with {@code b}
   * swapped.
   */
  private static String swapLines(String text, String a, String b) {
    List<String> lines = new java.util.ArrayList<>(List.of(text.split("\n", -1)));
    int first = -1;
    int second = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (first < 0 && lines.get(i).startsWith(a)) {
        first = i;
      } else if (second < 0 && lines.get(i).startsWith(b)) {
        second = i;
      }
    }
    assertThat(first).as("line starting with: " + a).isNotNegative();
    assertThat(second).as("line starting with: " + b).isNotNegative();
    java.util.Collections.swap(lines, first, second);
    return String.join("\n", lines);
  }

  /**
   * The workflow with the first {@code from} inside job ledger-durability replaced by {@code to}.
   */
  private static String inDurabilityJob(String ci, String from, String to) {
    int job = ci.indexOf("\n  ledger-durability:\n");
    assertThat(job).as("job ledger-durability").isNotNegative();
    int at = ci.indexOf(from, job);
    assertThat(at).as("text in job ledger-durability: " + from).isNotNegative();
    return ci.substring(0, at) + to + ci.substring(at + from.length());
  }

  /** The broken copies of the real workflow and script, one single edit each. */
  private static final class Copies {
    private final String ci;
    private final String script;
    private final java.util.Map<String, Neutered> cases = new java.util.LinkedHashMap<>();

    Copies(String ci, String script) {
      this.ci = ci;
      this.script = script;
    }

    /** The first {@code from} inside the job, replaced in the workflow. */
    void yaml(String name, String from, String to, String problem) {
      yaml(name, inDurabilityJob(ci, from, to), problem);
    }

    /** A workflow built by the caller. */
    void yaml(String name, String changed, String problem) {
      cases.put(name, new Neutered(changed, script, problem));
    }

    /** The first {@code from} in the script, replaced. */
    void shell(String name, String from, String to, String problem) {
      shell(name, replaceFirst(script, from, to), problem);
    }

    /** A script built by the caller. */
    void shell(String name, String changed, String problem) {
      cases.put(name, new Neutered(ci, changed, problem));
    }

    /** The script with {@code line} added after the wait for the standby. */
    void command(String name, String line, String problem) {
      shell(name, "wait_tcp 5433\n", "wait_tcp 5433\n" + line + "\n", problem);
    }
  }

  /** SHA-256 of the 62 lines of the job, as the owner approved them on 2026-10-21 (AP-3). */
  private static final String APPROVED_JOB_SHA256 =
      "2289ac93715a04efb05c1397a57444b6e0c23802ea0d6f44bbeb284dc0a4c3bb";

  /** SHA-256 of {@code scripts/ci/pg-quorum-up.sh} as the owner approved it (AP-3). */
  private static final String APPROVED_QUORUM_SCRIPT_SHA256 =
      "c1e465f702b6fb6998a7e5b548893283352416496627cf5dd028b650fdf94ad2";

  /** SHA-256 of {@code scripts/ci/pg-init-replication.sh} as the owner approved it (AP-3). */
  private static final String APPROVED_INIT_SCRIPT_SHA256 =
      "3fe282446f6940e0921b7001a1ff6b3e2a2987d9969aca730c171e9d59097692";

  /**
   * CI-1: the exact text of the job {@code ledger-durability} (the lines from {@code "
   * ledger-durability:"} to its {@code if-no-files-found: warn}, LF-normalised, each line ended by
   * a newline) and of its two scripts is what the owner approved (AP-3). The checker below finds
   * what it is taught to find; this test finds every edit. A change of any of the three needs the
   * owner's approval of the exact new text; the new SHA-256 value goes into the constant together
   * with the record of that approval.
   */
  @Test
  void theApprovedTextsOfTheDurabilityJobAndItsScriptsAreHeldByHash() throws IOException {
    List<String> ci =
        List.of(asciiLfText(repoRoot().resolve(".github/workflows/ci.yml")).split("\n", -1));
    int from = ci.indexOf("  " + CiDurabilityJob.JOB + ":");
    assertThat(from).as("the line of the job " + CiDurabilityJob.JOB).isGreaterThanOrEqualTo(0);
    int to = from;
    while (to < ci.size() && !ci.get(to).strip().equals("if-no-files-found: warn")) {
      to++;
    }
    assertThat(to).as("the upload step that ends the job").isLessThan(ci.size());
    List<String> job = ci.subList(from, to + 1);

    // the workflow-level environment reaches every job: it holds the one Maven argument line
    // and no other variable (for example a MAVEN_OPTS that ignores test failures)
    int env = ci.indexOf("env:");
    assertThat(env).as("the workflow-level env block").isGreaterThanOrEqualTo(0);
    assertThat(ci.subList(env, ci.indexOf("jobs:")))
        .as("the workflow-level env block")
        .containsExactly("env:", "  MAVEN_ARGS: -B -ntp --strict-checksums", "");
    assertThat(job).as("the number of lines of the approved job text").hasSize(62);
    assertApprovedHash(
        "the job ledger-durability", String.join("\n", job) + "\n", APPROVED_JOB_SHA256);
    assertApprovedHash(
        CiDurabilityJob.SCRIPT,
        asciiLfText(repoRoot().resolve(CiDurabilityJob.SCRIPT)),
        APPROVED_QUORUM_SCRIPT_SHA256);
    assertApprovedHash(
        CiDurabilityJob.INIT_SCRIPT,
        asciiLfText(repoRoot().resolve(CiDurabilityJob.INIT_SCRIPT)),
        APPROVED_INIT_SCRIPT_SHA256);
  }

  private static void assertApprovedHash(String what, String text, String approved) {
    String actual = dev.regpack.model.Sha256.ofUtf8(text).hex();
    assertThat(actual)
        .as(
            "%s differs from the text the owner approved (SHA-256 %s). A change of the job"
                + " ledger-durability or of its scripts needs the owner's approval of the exact"
                + " text (AP-3). With that approval, set the constant to the new SHA-256 value %s"
                + " and record the approval in docs/evidence/decisions.md.",
            what, approved, actual)
        .isEqualTo(approved);
  }

  /**
   * The checker has teeth: each edit of the kinds listed below that neuters the job or its script
   * is reported, and under the name of the rule that the edit breaks (the expected fragment of the
   * problem text), not merely as a generic difference from the approved text. It does not find
   * every edit; the approved text itself is held by {@code
   * theApprovedTextsOfTheDurabilityJobAndItsScriptsAreHeldByHash}.
   */
  @Test
  void theDurabilityJobCheckerReportsEachWayOfNeuteringTheJob() throws IOException {
    String ci = asciiLfText(repoRoot().resolve(".github/workflows/ci.yml"));
    String script = asciiLfText(repoRoot().resolve(CiDurabilityJob.SCRIPT));
    assertThat(CiDurabilityJob.problems(ci, script)).isEmpty();

    String install = CiDurabilityJob.text(CiDurabilityJob.INSTALL);
    String start = CiDurabilityJob.text(CiDurabilityJob.UP);
    String upTests = CiDurabilityJob.text(CiDurabilityJob.UP_TESTS);
    String stop = CiDurabilityJob.text(CiDurabilityJob.STOP);
    String downTests = CiDurabilityJob.text(CiDurabilityJob.DOWN_TESTS);
    String traceability = CiDurabilityJob.text(CiDurabilityJob.TRACEABILITY);
    String annotate = CiDurabilityJob.text(CiDurabilityJob.ANNOTATE);
    String diagnostics = CiDurabilityJob.text(CiDurabilityJob.DIAGNOSTICS);
    String cleanup = CiDurabilityJob.text(CiDurabilityJob.CLEANUP);
    String upload = CiDurabilityJob.text(CiDurabilityJob.UPLOAD);
    String always = "        if: always()\n";
    String failure = "        if: failure()\n";
    String notCancelled = "        if: ${{ !cancelled() }}\n";
    String notApprovedFor = "has a condition that is not approved for it";
    String tolerated = "a failure may be tolerated";
    String skipped = "may be skipped";
    String swallowed = "a failure is swallowed";
    String notRun = "does not run when";
    String noSelect = "a flag lets a run with no selected integration test pass";
    String outsideSet = "outside the approved set";
    String standbyMode = "does not run in --realstore standby mode";
    String notFound = "expected exactly one step";
    String verifyUp =
        "verify -pl regpack-postgres -Dspotless.check.skip=true -Dgroups=durability"
            + " -DexcludedGroups=none -Dit.test=PgDurabilityUpIT";
    String downSelected = "-Dit.test=PgDurabilityDownIT";
    Copies c = new Copies(ci, script);

    // a failure tolerated or a step skipped
    c.yaml(
        "continue-on-error on the up tests",
        upTests,
        upTests + "        continue-on-error: true\n",
        tolerated);
    c.yaml(
        "continue-on-error on the job",
        "    timeout-minutes: 30\n",
        "    timeout-minutes: 30\n    continue-on-error: true\n",
        tolerated);
    c.yaml(
        "a job-level condition",
        "    runs-on: ubuntu-24.04\n",
        "    if: false\n    runs-on: ubuntu-24.04\n",
        "the job may be skipped");
    c.yaml(
        "a condition that skips the down tests",
        "tests with the standby down\n",
        "tests with the standby down\n        if: false\n",
        skipped);
    c.yaml(
        "a condition that skips the up tests",
        "tests with the standby up\n",
        "tests with the standby up\n        if: ${{ false }}\n",
        skipped);
    c.yaml(
        "|| true on the down tests",
        "-Dsurefire.failIfNoSpecifiedTests=false\n      - name: Standby",
        "-Dsurefire.failIfNoSpecifiedTests=false || true\n      - name: Standby",
        swallowed);

    // the gates and the cleanup run on every outcome, the diagnostics on failure
    c.yaml(
        "traceability without if always", traceability, traceability.replace(always, ""), notRun);
    c.yaml("annotation without if always", annotate, annotate.replace(always, ""), notRun);
    c.yaml("cleanup without if always", cleanup, cleanup.replace(always, ""), notRun);
    c.yaml("report upload without if always", upload, upload.replace(always, ""), notRun);
    c.yaml("diagnostics without if failure", diagnostics, diagnostics.replace(failure, ""), notRun);
    c.yaml(
        "diagnostics on every outcome", diagnostics, diagnostics.replace(failure, always), notRun);

    // the Down phase runs after a red Up phase (ITS-3), and only the cancelled run skips it
    c.yaml("stop without its condition", stop, stop.replace(notCancelled, ""), notRun);
    c.yaml(
        "down tests without their condition",
        downTests,
        downTests.replace(notCancelled, ""),
        notRun);
    c.yaml(
        "stop on success only",
        stop,
        stop.replace(notCancelled, "        if: ${{ success() }}\n"),
        skipped);
    c.yaml(
        "down tests on a false condition",
        downTests,
        downTests.replace(notCancelled, "        if: ${{ false }}\n"),
        skipped);
    c.yaml("stop with if always", stop, stop.replace(notCancelled, always), notApprovedFor);
    c.yaml(
        "down tests with if failure", downTests, downTests.replace(notCancelled, failure), notRun);
    c.yaml(
        "down tests with if failure (the condition is named)",
        downTests,
        downTests.replace(notCancelled, failure),
        notApprovedFor);
    c.yaml(
        "a not-cancelled condition on the up tests",
        "tests with the standby up\n",
        "tests with the standby up\n" + notCancelled,
        notApprovedFor);
    c.yaml(
        "a not-cancelled condition instead of always on the traceability",
        traceability,
        traceability.replace(always, notCancelled),
        notRun);
    c.yaml(
        "a not-cancelled condition on the cleanup",
        cleanup,
        cleanup.replace(always, notCancelled),
        notApprovedFor);

    // a missing or doubled step is named
    c.yaml("no diagnostics step", diagnostics, "", notFound + " 'Container diagnostics', found 0");
    c.yaml("no stop step", stop, "", notFound + " 'Stop the standby', found 0");
    c.yaml(
        "no down tests",
        downTests,
        "",
        notFound + " 'Durability tests with the standby down', found 0");
    c.yaml(
        "no up tests", upTests, "", notFound + " 'Durability tests with the standby up', found 0");
    c.yaml("no traceability step", traceability, "", notFound + " 'Standby traceability', found 0");
    c.yaml(
        "no annotation step",
        annotate,
        "",
        notFound + " 'Test tallies and failure annotations', found 0");
    c.yaml("no cleanup step", cleanup, "", notFound + " 'Remove the containers', found 0");
    c.yaml(
        "no start step",
        start,
        "",
        notFound + " 'Start the primary and the quorum standby', found 0");
    c.yaml("no install step", install, "", notFound);
    c.yaml(
        "an install that leaves the failsafe tests on (MAVEN-1)",
        " -DskipITs -Dspotless",
        " -Dspotless",
        notFound + " '");
    c.yaml(
        "a diagnostics tail of 30 lines",
        "docker logs --tail 60",
        "docker logs --tail 30",
        "the step 'Container diagnostics' " + "is not the approved text");
    c.yaml(
        "diagnostics without the activity of the primary",
        "pg_stat_activity WHERE pid <> pg_backend_pid()",
        "pg_stat_replication",
        "the step 'Container diagnostics' " + "is not the approved text");
    c.yaml(
        "diagnostics without the quorum setting",
        "\"SHOW synchronous_standby_names\"",
        "\"SHOW server_version\"",
        "the step 'Container diagnostics' " + "is not the approved text");
    c.yaml(
        "diagnostics that exec into the standby",
        "docker exec regpack-primary psql -h 127.0.0.1 -p 5432 -U postgres -Atc \"SHOW",
        "docker exec regpack-standby psql -h 127.0.0.1 -p 5433 -U postgres -Atc \"SHOW",
        "the step 'Container diagnostics' is not the approved text");
    c.yaml("no report upload", upload, "", notFound + " 'Upload durability test reports', found 0");
    c.yaml("a second up tests step", upTests, upTests + upTests, "found 2");
    c.yaml(
        "a step outside the approved text",
        upload,
        upload + "      - run: echo done\n",
        "a step outside the approved text");

    // phases in the wrong order
    c.yaml(
        "the down tests before the stop",
        stop + downTests,
        downTests + stop,
        "runs before the step 'Stop the standby'");
    c.yaml(
        "the stop before the up tests",
        upTests + stop,
        stop + upTests,
        "the step 'Stop the standby' runs before the step 'Durability tests with the standby up'");
    c.yaml(
        "the up tests before the server is started",
        start + upTests,
        upTests + start,
        "the step 'Durability tests with the standby up' runs before the step 'Start the primary and the quorum standby'");
    c.yaml(
        "the traceability before the down tests",
        inDurabilityJob(inDurabilityJob(ci, traceability, ""), downTests, traceability + downTests),
        "the step 'Standby traceability' runs before the step 'Durability tests with the standby down'");

    // the selection of the tests and the traceability mode
    c.yaml(
        "the up step selects the down class",
        "-Dit.test=PgDurabilityUpIT",
        downSelected,
        "does not select PgDurabilityUpIT");
    c.yaml(
        "the stop step stops the primary",
        "docker stop regpack-standby",
        "docker stop regpack-primary",
        "is not the approved text");
    c.yaml("a dropped --realstore standby", " --realstore standby", "", standbyMode);
    c.yaml("traceability in skip mode", "--realstore standby", "--realstore skip", standbyMode);
    c.yaml("traceability in only mode", "--realstore standby", "--realstore only", standbyMode);
    c.yaml("another group", "-Dgroups=durability", "-Dgroups=mutant", outsideSet);

    // flags and options that skip, ignore or let a run with no selected test pass
    c.yaml(
        "failsafe.failIfNoSpecifiedTests",
        downSelected,
        downSelected + " -Dfailsafe.failIfNoSpecifiedTests=false",
        noSelect);
    c.yaml("failIfNoTests", downSelected, downSelected + " -DfailIfNoTests=false", noSelect);
    for (String flag :
        List.of(
            "-DskipTests",
            "-DskipITs",
            "-Dmaven.test.skip=true",
            "-Dmaven.test.failure.ignore=true",
            "-Dfailsafe.skip=true",
            "-DtestFailureIgnore=true")) {
      c.yaml(flag + " on the down tests", downSelected, downSelected + " " + flag, outsideSet);
    }
    for (String option : List.of("-fn", "--fail-never", "-fae", "-o", "-q")) {
      c.yaml(
          "the Maven option " + option,
          verifyUp,
          verifyUp.replace("verify ", "verify " + option + " "),
          outsideSet);
    }
    c.yaml(
        "another module verified",
        "verify -pl regpack-postgres",
        "verify -pl regpack-engine",
        "-pl names another module than regpack-postgres");
    c.yaml(
        "no -am on the install",
        "install -pl regpack-postgres -am",
        "install -pl regpack-postgres",
        "a step outside the approved text: - run: ./mvnw -DskipTests");
    c.yaml(
        "the workflow MAVEN_ARGS",
        ci.replace("MAVEN_ARGS: -B -ntp --strict-checksums", "MAVEN_ARGS: -B -ntp -fn"),
        "MAVEN_ARGS");

    // the header: runner, bound, required server, credentials, containers of its own
    c.yaml(
        "a floating runner",
        "    runs-on: ubuntu-24.04\n",
        "    runs-on: ubuntu-latest\n",
        "does not run on ubuntu-24.04");
    c.yaml(
        "a one-day job timeout",
        "    timeout-minutes: 30\n",
        "    timeout-minutes: 1440\n",
        "is not bounded by timeout-minutes: 30");
    c.yaml(
        "a missing server skips the tests",
        "REGPACK_PG_REQUIRED: 'true'",
        "REGPACK_PG_REQUIRED: 'false'",
        "REGPACK_PG_REQUIRED");
    c.yaml(
        "another server URL",
        "127.0.0.1:5432/postgres",
        "db.example.com:5432/postgres",
        "REGPACK_PG_URL");
    c.yaml(
        "a secret",
        "    steps:\n",
        "      TOKEN: ${{ secrets.TOKEN }}\n    steps:\n",
        "reads a secret or the token");
    c.yaml(
        "the token",
        "    steps:\n",
        "      T: ${{ github.token }}\n      U: $GITHUB_TOKEN\n    steps:\n",
        "reads a secret or the token");
    c.yaml(
        "a password in the URL", "?user=postgres", "?user=postgres&password=x", "names a password");
    c.yaml(
        "a service container",
        "    steps:\n",
        "    services:\n      postgres:\n        image: postgres:18\n    steps:\n",
        "starts a service container or publishes a port");
    c.yaml(
        "a published port in the job",
        "    steps:\n",
        "    ports:\n      - 5432:5432\n    steps:\n",
        "starts a service container or publishes a port");
    c.yaml(
        "an unpinned checkout",
        "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1",
        "actions/checkout@v7",
        notFound + " 'uses: actions/checkout@3d3c42e5");

    // docker and sudo in the workflow
    for (String verb :
        List.of(
            "volume", "cp", "network", "build", "commit", "ps", "run", "pull", "kill", "login")) {
      c.yaml(
          "docker " + verb + " in the workflow",
          "docker stop regpack-standby",
          "docker " + verb + " regpack-standby",
          "docker verb '" + verb + "' in the workflow");
    }
    c.yaml(
        "a docker volume created by the cleanup",
        "docker rm -f regpack-primary regpack-standby",
        "docker rm -f regpack-primary regpack-standby && docker volume create x",
        "docker verb 'volume' in the workflow");
    c.yaml(
        "a published port in a docker run of the workflow",
        "docker stop regpack-standby",
        "docker run -d -p 5432:5432 postgres:17",
        "docker verb 'run' in the workflow");
    c.yaml(
        "sudo in the workflow",
        "run: docker stop regpack-standby",
        "run: sudo docker stop regpack-standby",
        "sudo in the workflow");
    c.yaml(
        "a docker binary named by its path in the workflow",
        "run: docker stop regpack-standby",
        "run: /usr/bin/docker stop regpack-standby",
        "a docker binary named by its path in the workflow");
    c.yaml(
        "--privileged in the workflow",
        "docker stop regpack-standby",
        "docker exec --privileged regpack-standby true",
        "a privileged container");

    assertThat(c.cases.size()).as("copies").isGreaterThanOrEqualTo(70);

    assertEveryCopyIsReported(c, ci, script);
  }

  /** The assertion of the two teeth tests: every copy differs and is reported under its rule. */
  private static void assertEveryCopyIsReported(Copies c, String ci, String script) {
    for (java.util.Map.Entry<String, Neutered> copy : c.cases.entrySet()) {
      Neutered n = copy.getValue();
      assertThat(n.ci().equals(ci) && n.script().equals(script))
          .as(copy.getKey() + " differs from the real files")
          .isFalse();
      assertThat(CiDurabilityJob.problems(n.ci(), n.script()))
          .as(copy.getKey())
          .anyMatch(p -> p.contains(n.problem()));
    }
  }

  /**
   * The same for the script: docker verbs, sudo, images, the options of docker run, the host
   * network, the loopback listener, the quorum configuration and the order of the steps.
   */
  @Test
  void theDurabilityScriptCheckerReportsEachWayOfNeuteringTheScript() throws IOException {
    String ci = asciiLfText(repoRoot().resolve(".github/workflows/ci.yml"));
    String script = asciiLfText(repoRoot().resolve(CiDurabilityJob.SCRIPT));
    assertThat(CiDurabilityJob.problems(ci, script)).isEmpty();

    String swallowed = "a failure is swallowed";
    String runOption = "docker run option outside the approved list: ";
    String noHost = "a docker run without --network host";
    String notLoopback = "a server that does not listen on 127.0.0.1 only";
    String badSetting = "a server setting that weakens or preempts the durability fixture";
    String badMount = "a mount outside /mnt/regpack-pg";
    String sudoStray = "sudo is used outside the two install lines";
    String primary = "--name regpack-primary --network host";
    String standby = "--name regpack-standby --network host";
    String pull = CiDurabilityJob.PULL;
    String sudoRoot = CiDurabilityJob.SUDO_ROOT;
    String sudoDirs = CiDurabilityJob.SUDO_DIRS;
    String guard = CiDurabilityJob.GUARD;
    String quorumWait = CiDurabilityJob.QUORUM_WAIT;
    String alterLine = CiDurabilityJob.ALTER_LINE;
    String showLine = CiDurabilityJob.SHOW_LINE;
    String ready = CiDurabilityJob.READY;
    Copies c = new Copies(ci, script);

    // the script: docker verbs
    for (String verb :
        List.of(
            "volume", "cp", "network", "build", "commit", "ps", "stop", "rm", "kill", "login",
            "load")) {
      c.shell(
          "docker " + verb + " in the script",
          pull,
          pull + "\ndocker " + verb + " regpack-data",
          "docker verb '" + verb + "' in the script");
    }
    c.shell(
        "an option before the docker verb",
        pull,
        "docker -H tcp://127.0.0.1:2375 pull \"$IMAGE\"",
        "docker verb '-H' in the script");
    c.shell(
        "a docker binary named by its path in the script",
        pull,
        "/usr/bin/docker pull \"$IMAGE\"",
        "a docker binary named by its path in the script");
    c.shell("no pull", pull + "\n", "", "the script has no pull of the pinned image");
    c.shell(
        "a base backup with the spread checkpoint of the default",
        " -X stream -c fast ",
        " -X stream ",
        "the base backup does not ask for a fast checkpoint");

    // the script: sudo
    c.shell("a stray sudo", sudoDirs, sudoDirs + "\nsudo chmod -R 777 /mnt/regpack-pg", sudoStray);
    c.shell(
        "a third sudo install", sudoDirs, sudoDirs + "\nsudo install -d -m 755 /tmp/x", sudoStray);
    c.shell("a wider install mode", "-m 700", "-m 777", sudoStray);
    c.shell(
        "no first install line", sudoRoot + "\n", "", "the script has no first sudo install line");
    c.shell(
        "no second install line",
        sudoDirs + "\n",
        "",
        "the script has no second sudo install line");

    // the script: images
    c.shell(
        "a docker run of another image",
        "\"$IMAGE\" -c listen_addresses=127.0.0.1 -c port=5433",
        "postgres:17 -c listen_addresses=127.0.0.1 -c port=5433",
        "a docker run of another image than the pinned one");
    c.shell(
        "a docker pull of another image",
        pull,
        "docker pull postgres:latest",
        "a docker pull of another image than the pinned one");
    c.shell(
        "an unpinned image literal",
        "@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336'",
        "'",
        "the image literal is not the pinned digest of ledger-pg");
    c.shell(
        "another digest",
        "c4336'",
        "c4337'",
        "the image literal is not the pinned digest of ledger-pg");
    c.shell(
        "a second image assignment",
        "cd \"$(dirname",
        "IMAGE=postgres:17\ncd \"$(dirname",
        "the image literal is not the pinned digest of ledger-pg");

    // the script: options of docker run
    c.shell("-p 5432:5432", primary, primary + " -p 5432:5432", runOption + "-p");
    c.shell("--publish", primary, primary + " --publish=5432:5432", runOption + "--publish");
    c.shell("-P", primary, primary + " -P", runOption + "-P");
    c.shell("--privileged", standby, standby + " --privileged", runOption + "--privileged");
    c.shell("--pid=host", standby, standby + " --pid=host", runOption + "--pid=host");
    c.shell("--cap-add", standby, standby + " --cap-add SYS_ADMIN", runOption + "--cap-add");
    c.shell("no host network on the standby", standby, "--name regpack-standby", noHost);
    c.shell(
        "a bridge network for the primary",
        primary,
        "--name regpack-primary --network bridge",
        noHost);
    c.shell(
        "no host network on the base backup",
        "docker run --rm --network host",
        "docker run --rm",
        noHost);
    c.shell(
        "the standby listens on every address",
        "-c listen_addresses=127.0.0.1 -c port=5433",
        "-c listen_addresses=* -c port=5433",
        notLoopback);
    c.shell(
        "the primary has no listen_addresses",
        "-c listen_addresses=127.0.0.1 -c port=5432",
        "-c port=5432",
        notLoopback);
    c.shell(
        "a second, wider listen_addresses",
        "-c port=5433 -c hot_standby=on",
        "-c port=5433 -c listen_addresses=* -c hot_standby=on",
        notLoopback);
    c.shell(
        "the docker socket mounted",
        standby,
        standby + " -v /var/run/docker.sock:/var/run/docker.sock",
        badMount);
    c.shell("the root mounted", standby, standby + " -v /:/host", badMount);
    c.shell(
        "a mount that climbs out of the data root",
        "-v /mnt/regpack-pg/primary:",
        "-v /mnt/regpack-pg/../../etc:",
        badMount);
    c.shell(
        "the init script mounted writable", "10-replication.sh:ro", "10-replication.sh", badMount);
    c.shell(
        "a password in the environment",
        "-e POSTGRES_HOST_AUTH_METHOD=trust",
        "-e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_PASSWORD=x",
        "a docker run environment value outside the approved one");
    c.shell(
        "another authentication method",
        "POSTGRES_HOST_AUTH_METHOD=trust",
        "POSTGRES_HOST_AUTH_METHOD=md5",
        "a docker run environment value outside the approved one");
    c.shell(
        "the base backup as root",
        "--user 999:999",
        "--user 0:0",
        "a docker run user other than 999:999");
    c.shell(
        "an exec as root",
        "docker exec regpack-primary psql",
        "docker exec -u root regpack-primary psql",
        "a docker exec with an option");
    c.shell(
        "an exec into another container",
        "docker exec regpack-primary psql",
        "docker exec registry psql",
        "a docker exec into another container than regpack-primary");
    c.shell(
        "a server with another name",
        "--name regpack-standby",
        "--name standby",
        "a server container with another name than regpack-primary or regpack-standby");
    c.shell(
        "a detached base backup",
        "docker run --rm --network host",
        "docker run -d --network host",
        "a server container with another name");
    c.shell(
        "a container that is neither detached nor removed",
        "docker run --rm --network host",
        "docker run --network host",
        "a docker run that is neither a server (-d) nor the removed base backup (--rm)");
    c.shell(
        "no standby started",
        "docker run -d --name regpack-standby",
        "docker run --rm --name regpack-standby",
        "the script does not start both servers");

    // the script: guard, strict mode, swallowed failures, stray commands, continuation
    c.shell(
        "no runner guard",
        guard + "\n",
        "",
        "the second command of the script is not the runner guard");
    c.shell(
        "no strict mode",
        "set -euo pipefail\n",
        "",
        "the first command of the script is not set -euo pipefail");
    c.command("|| true on a wait", "wait_tcp 5433 || true", swallowed);
    c.command("set +e", "set +e", swallowed);
    c.command("exit 0 before the end", "exit 0", swallowed);
    c.shell(
        "a backslash continuation",
        " -c port=5433 ",
        " -c port=5433 \\\n  ",
        "continues on the next line");
    for (String command :
        List.of(
            "curl -fsSL https://example.com",
            "wget https://example.com",
            "rm -rf /mnt/regpack-pg",
            "apt-get install -y x",
            "eval \"$X\"",
            "chmod -R 777 /mnt/regpack-pg",
            "cp -r /etc /mnt/regpack-pg",
            "source /etc/profile")) {
      c.command(
          "the command " + command,
          command,
          "a command that is not allowed in the script: " + command.split(" ")[0]);
    }

    // the script: the quorum configuration and the order
    c.shell(
        "synchronous_standby_names at start-up",
        "-c hot_standby=on\nwait_tcp 5432",
        "-c hot_standby=on -c synchronous_standby_names='ANY 1 (standby1)'\nwait_tcp 5432",
        badSetting);
    c.shell("fsync off", "-c port=5432 ", "-c port=5432 -c fsync=off ", badSetting);
    c.shell(
        "synchronous_commit set on the server",
        "-c port=5432 ",
        "-c port=5432 -c synchronous_commit=local ",
        badSetting);
    c.shell(
        "a quorum of two",
        "SET synchronous_standby_names = 'ANY 1",
        "SET synchronous_standby_names = 'ANY 2",
        "the quorum setting is missing or changed");
    c.shell(
        "a first-in-line standby",
        "'ANY 1 (standby1)'\" -c",
        "'FIRST 1 (standby1)'\" -c",
        "the quorum setting is missing or changed");
    c.shell(
        "no quorum wait", quorumWait + "\n", "", "the script has no wait for the quorum standby");
    c.shell(
        "the quorum waited for in the wrong state",
        "sync_state = 'quorum'",
        "sync_state = 'async'",
        "the script has no wait for the quorum standby");
    c.shell(
        "the quorum set before the standby exists",
        replaceFirst(script, alterLine + "\n", "")
            .replace(
                "docker run -d --name regpack-primary",
                alterLine + "\ndocker run -d --name regpack-primary"),
        "comes before");
    c.shell(
        "a synchronous_standby_names set elsewhere",
        showLine,
        "psql_at 5432 \"ALTER SYSTEM SET synchronous_standby_names = ''\"",
        "synchronous_standby_names is set outside the approved line");
    c.shell("no ready line", ready + "\n", "", "the script does not end with the ready line");
    c.shell(
        "the standby started before the base backup",
        swapLines(
            script,
            "docker run --rm --network host --user 999:999 ",
            "docker run -d --name regpack-standby "),
        "comes before");
    c.shell(
        "the primary started before the data directories exist",
        swapLines(script, sudoDirs, "docker run -d --name regpack-primary "),
        "comes before");

    assertThat(c.cases.size()).as("copies").isGreaterThanOrEqualTo(70);
    assertEveryCopyIsReported(c, ci, script);
  }

  /**
   * P3b2 T4, AP-3 and AP-6: the job evidence-worm (version 1, the probe only) is the approved text:
   * one digest-pinned S3-compatible service container published on the loopback only, throwaway
   * credentials as literals, a required service, the install, the wait for the port, the one probe
   * step, the annotation step and the upload, and nothing that skips, ignores or tolerates a
   * failure.
   */
  @Test
  void wormJobIsTheApprovedTextAndSound() throws IOException {
    String ci = asciiLfText(repoRoot().resolve(".github/workflows/ci.yml"));
    assertThat(CiWormJob.problems(ci)).isEmpty();
    assertThat(CiWormJob.jobText(ci)).isEqualTo(CiWormJob.text());
    List<String> job = jobLines(ci, "evidence-worm");
    assertThat(job).contains("        image: " + CiWormJob.IMAGE);
    assertThat(job.stream().filter(l -> l.strip().startsWith("image:"))).hasSize(1);
    assertThat(job.stream().filter(l -> l.strip().startsWith("- 127.0.0.1:")))
        .containsExactly("          - 127.0.0.1:8000:8000");
    assertThat(job)
        .contains(
            "      REGPACK_S3_ENDPOINT: http://127.0.0.1:8000",
            "      REGPACK_S3_REQUIRED: 'true'");
    // checkout and setup-java are those of the other real-store jobs, with the same SHAs
    assertThat(java.util.Collections.indexOfSubList(job, CiDurabilityJob.CHECKOUT)).isNotNegative();
    assertThat(java.util.Collections.indexOfSubList(job, CiDurabilityJob.SETUP_JAVA))
        .isNotNegative();
    // the version-1 job has no PostgreSQL service and no PostgreSQL variable
    assertThat(String.join("\n", job)).doesNotContain("postgres").doesNotContain("REGPACK_PG_");
    // the upload of the distribution stays unique and the reports have a name of their own
    assertThat(ci).containsOnlyOnce("name: regpack-dist\n          path: |");
    assertThat(job).contains("          name: evidence-worm-reports");
  }

  /** The text of the job is pinned by its SHA-256 (the hash the owner approves with the text). */
  @Test
  void theApprovedTextOfTheWormJobIsHeldByHash() throws IOException {
    String ci = asciiLfText(repoRoot().resolve(".github/workflows/ci.yml"));
    assertThat(CiWormJob.APPROVED_SHA256).matches("[0-9a-f]{64}");
    assertThat(CiWormJob.sha256(CiWormJob.text())).isEqualTo(CiWormJob.APPROVED_SHA256);
    assertThat(CiWormJob.sha256(CiWormJob.jobText(ci))).isEqualTo(CiWormJob.APPROVED_SHA256);
    // one character of the text changes the hash
    assertThat(
            CiWormJob.sha256(
                CiWormJob.jobText(ci).replace("timeout-minutes: 40", "timeout-minutes: 41")))
        .isNotEqualTo(CiWormJob.APPROVED_SHA256);
    assertThat(CiWormJob.sha256(CiWormJob.jobText(ci).replace("9.5.0@", "9.5.1@")))
        .isNotEqualTo(CiWormJob.APPROVED_SHA256);
    // the image reference is a name, a tag and a full digest
    assertThat(CiWormJob.IMAGE).matches("[a-z0-9./-]+:[0-9A-Za-z._-]+@sha256:[0-9a-f]{64}");
  }

  /** One way of neutering the worm job: the workflow and the fragment a problem must name. */
  private record WormCase(String ci, String problem) {}

  /** The checker has teeth: each edit of the kinds listed below is reported, by its own rule. */
  @Test
  void theWormJobCheckerReportsEachWayOfNeuteringTheJob() throws IOException {
    String ci = asciiLfText(repoRoot().resolve(".github/workflows/ci.yml"));
    assertThat(CiWormJob.problems(ci)).isEmpty();
    String probe = CiWormJob.PROBE_RUN + "\n";
    String probeStep = String.join("\n", CiWormJob.PROBE) + "\n";
    String wait = String.join("\n", CiWormJob.WAIT) + "\n";
    String annotate = String.join("\n", CiWormJob.ANNOTATE) + "\n";
    String upload = String.join("\n", CiWormJob.UPLOAD) + "\n";
    String accessKey = "      REGPACK_S3_ACCESS_KEY: REGPACKCIACCESSKEY1\n";
    java.util.Map<String, WormCase> broken = new java.util.LinkedHashMap<>();
    // the rules by name
    broken.put(
        "a secret",
        new WormCase(
            inWormJob(ci, accessKey, "      REGPACK_S3_ACCESS_KEY: ${{ secrets.S3_KEY }}\n"),
            "reads a secret or the token"));
    broken.put(
        "the token",
        new WormCase(
            inWormJob(
                ci,
                "    env:\n      REGPACK_S3",
                "    env:\n      GITHUB_TOKEN: x\n      REGPACK_S3"),
            "reads a secret or the token"));
    broken.put(
        "a password",
        new WormCase(
            inWormJob(ci, accessKey, accessKey + "      S3_PASSWORD: x\n"), "names a password"));
    broken.put(
        "continue-on-error on the probe step",
        new WormCase(
            inWormJob(ci, probe, probe + "        continue-on-error: true\n"),
            "a failure may be tolerated"));
    broken.put(
        "continue-on-error on the job",
        new WormCase(
            inWormJob(
                ci,
                "    timeout-minutes: 40\n",
                "    timeout-minutes: 40\n    continue-on-error: true\n"),
            "a failure may be tolerated"));
    broken.put(
        "a job-level condition",
        new WormCase(
            inWormJob(ci, RUNS_ON_LINE, "    if: false\n" + RUNS_ON_LINE),
            "the job may be skipped"));
    broken.put(
        "a step condition other than always",
        new WormCase(inWormJob(ci, probe, "        if: false\n" + probe), "a step may be skipped"));
    broken.put(
        "a swallowed failure",
        new WormCase(inWormJob(ci, "exit 1\n", "exit 1 || true\n"), "a failure is swallowed"));
    broken.put(
        "a docker verb",
        new WormCase(
            inWormJob(ci, "            sleep 2\n", "            docker ps\n"), "a docker command"));
    broken.put(
        "sudo",
        new WormCase(
            inWormJob(ci, "            sleep 2\n", "            sudo sleep 2\n"),
            "sudo in the job"));
    broken.put(
        "a privileged container",
        new WormCase(
            inWormJob(
                ci,
                "          - 127.0.0.1:8000:8000\n",
                "          - 127.0.0.1:8000:8000\n        options: --privileged\n"),
            "a privileged container"));
    broken.put(
        "an image without a digest",
        new WormCase(
            inWormJob(ci, "@sha256:" + CiWormJob.IMAGE.split("@sha256:")[1], ""),
            "an image without a digest"));
    broken.put(
        "a published port on all interfaces",
        new WormCase(
            inWormJob(ci, "- 127.0.0.1:8000:8000", "- 8000:8000"),
            "a port not published on 127.0.0.1 only"));
    broken.put(
        "a second port on all interfaces",
        new WormCase(
            inWormJob(
                ci,
                "          - 127.0.0.1:8000:8000\n",
                "          - 127.0.0.1:8000:8000\n          - 9000:9000\n"),
            "a port not published on 127.0.0.1 only"));
    broken.put(
        "another runner",
        new WormCase(
            inWormJob(ci, "runs-on: ubuntu-24.04", "runs-on: ubuntu-latest"),
            "does not run on ubuntu-24.04"));
    broken.put(
        "the service is not required",
        new WormCase(
            inWormJob(ci, "REGPACK_S3_REQUIRED: 'true'", "REGPACK_S3_REQUIRED: 'false'"),
            "a missing service does not fail the job"));
    broken.put(
        "tests skipped",
        new WormCase(
            inWormJob(ci, "-Dgroups=probe", "-Dgroups=probe -DskipITs"),
            "a flag skips or ignores tests"));
    broken.put(
        "test failures ignored",
        new WormCase(
            inWormJob(ci, "-Dgroups=probe", "-Dgroups=probe -Dmaven.test.failure.ignore=true"),
            "a flag skips or ignores tests"));
    broken.put(
        "a Maven property outside the approved set",
        new WormCase(
            inWormJob(ci, "-Dgroups=probe", "-Dgroups=probe -Dverbose=true"),
            "a Maven property outside the approved set"));
    broken.put(
        "a changed test selection",
        new WormCase(
            inWormJob(ci, "-Dit.test=S3ServiceProbeIT", "-Dit.test=NoSuchIT"),
            "the probe step does not run exactly"));
    broken.put(
        "no probe step",
        new WormCase(
            inWormJob(
                ci, "      - name: S3 service probe\n        timeout-minutes: 15\n" + probe, ""),
            "expected exactly one step 'S3 service probe'"));
    broken.put(
        "a second probe step",
        new WormCase(
            inWormJob(ci, probeStep, probeStep + probeStep), "expected exactly one probe step"));
    broken.put(
        "a second verify line",
        new WormCase(
            inWormJob(ci, probe, probe + "      - run: ./mvnw verify -pl regpack-evidence-s3\n"),
            "expected exactly two Maven lines"));
    broken.put(
        "an option in MAVEN_ARGS",
        new WormCase(
            ci.replace("MAVEN_ARGS: -B -ntp --strict-checksums", "MAVEN_ARGS: -B -ntp -fn"),
            "MAVEN_ARGS"));
    broken.put(
        "another module verified",
        new WormCase(
            inWormJob(ci, "verify -pl regpack-evidence-s3", "verify -pl regpack-engine"),
            "the probe step does not run exactly"));
    broken.put(
        "no -am on the install",
        new WormCase(
            inWormJob(ci, "install -pl regpack-evidence-s3 -am", "install -pl regpack-evidence-s3"),
            "a Maven line " + CiWormJob.NOT_APPROVED));
    // the wait for the service
    broken.put(
        "no wait step",
        new WormCase(
            inWormJob(ci, wait, ""), "expected exactly one step 'Wait for the S3 service'"));
    broken.put(
        "a wait step of one attempt",
        new WormCase(
            inWormJob(ci, "seq 1 40", "seq 1 1"),
            "the step 'Wait for the S3 service' " + CiWormJob.NOT_APPROVED));
    broken.put(
        "a wait step that always succeeds",
        new WormCase(
            inWormJob(ci, "          exit 1\n", "          exit 0\n"),
            "the step 'Wait for the S3 service' " + CiWormJob.NOT_APPROVED));
    // the net under a failing run
    broken.put(
        "no annotation step",
        new WormCase(inWormJob(ci, annotate, ""), "expected exactly one step 'Test tallies"));
    broken.put(
        "annotation without if always",
        new WormCase(
            inWormJob(ci, annotate, annotate.replace("        if: always()\n", "")),
            "does not run when an earlier step fails"));
    broken.put(
        "upload without if always",
        new WormCase(
            inWormJob(ci, upload, upload.replace("        if: always()\n", "")),
            "does not run when an earlier step fails"));
    // the text
    broken.put(
        "a step added",
        new WormCase(
            inWormJob(ci, upload, upload + "      - run: echo done\n"),
            "a step outside the approved text"));
    broken.put(
        "a step moved before the install",
        new WormCase(
            inWormJob(
                inWormJob(ci, wait, ""),
                CiWormJob.INSTALL_LINE + "\n",
                wait + CiWormJob.INSTALL_LINE + "\n"),
            "runs before the step"));
    broken.put(
        "an unpinned action",
        new WormCase(
            inWormJob(
                ci,
                "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1",
                "actions/checkout@v7"),
            "expected exactly one step 'uses: actions/checkout"));
    broken.put(
        "another image tag with the same digest",
        new WormCase(
            inWormJob(ci, ":9.5.0@", ":9.5.1@"), "the services block " + CiWormJob.NOT_APPROVED));
    broken.put(
        "another image digest",
        new WormCase(
            inWormJob(ci, "@sha256:652d", "@sha256:652e"),
            "the services block " + CiWormJob.NOT_APPROVED));
    broken.put(
        "another service credential",
        new WormCase(
            inWormJob(
                ci, "SCALITY_ACCESS_KEY_ID: REGPACKCIACCESSKEY1", "SCALITY_ACCESS_KEY_ID: OTHER"),
            "the services block " + CiWormJob.NOT_APPROVED));
    broken.put(
        "another endpoint",
        new WormCase(
            inWormJob(ci, "http://127.0.0.1:8000\n", "http://10.0.0.1:8000\n"),
            "the env block " + CiWormJob.NOT_APPROVED));
    broken.put(
        "a one minute timeout",
        new WormCase(
            inWormJob(ci, "timeout-minutes: 40", "timeout-minutes: 1"),
            "the header block " + CiWormJob.NOT_APPROVED));
    broken.put(
        "a changed name",
        new WormCase(
            inWormJob(ci, "name: evidence P-WORM probe", "name: evidence probe"),
            "the header block " + CiWormJob.NOT_APPROVED));
    broken.put(
        "a changed text that no other rule reads",
        new WormCase(
            inWormJob(
                ci, "          if-no-files-found: warn\n", "          if-no-files-found: ignore\n"),
            "the step 'Upload P-WORM test reports' " + CiWormJob.NOT_APPROVED));
    assertThat(broken.size()).as("copies").isGreaterThanOrEqualTo(35);
    for (java.util.Map.Entry<String, WormCase> copy : broken.entrySet()) {
      assertThat(copy.getValue().ci()).as(copy.getKey()).isNotEqualTo(ci);
      assertThat(CiWormJob.problems(copy.getValue().ci()))
          .as(copy.getKey())
          .anyMatch(p -> p.contains(copy.getValue().problem()));
    }
    // an edit of one character in a value that no rule reads is still found: the blocks are given
    // word for word
    assertThat(CiWormJob.problems(inWormJob(ci, "us-east-1", "us-west-1")))
        .as("a region")
        .isNotEmpty();
  }

  private static final String RUNS_ON_LINE = "    runs-on: ubuntu-24.04\n";

  /** The workflow with the first {@code from} inside job evidence-worm replaced by {@code to}. */
  private static String inWormJob(String ci, String from, String to) {
    int job = ci.indexOf("\n  evidence-worm:\n");
    assertThat(job).as("job evidence-worm").isNotNegative();
    int at = ci.indexOf(from, job);
    assertThat(at).as("text in job evidence-worm: " + from).isNotNegative();
    return ci.substring(0, at) + to + ci.substring(at + from.length());
  }

  @Test
  void helmChartDeclaresProbesSingleReplicaAndRecreate() throws IOException {
    Path chart = repoRoot().resolve("packaging/helm/regpack");
    String templates =
        asciiLfText(chart.resolve("templates/deployment.yaml"))
            + asciiLfText(chart.resolve("templates/service.yaml"));

    assertThat(templates)
        .contains(
            "startupProbe:",
            "livenessProbe:",
            "readinessProbe:",
            "replicas: {{ .Values.replicaCount }}",
            "automountServiceAccountToken: false");
    // Each probe is paired with its own path: startup and liveness use live, readiness uses ready.
    assertThat(templates)
        .containsPattern("(?s)startupProbe:\\s+httpGet:\\s+path: /health/live\\s")
        .containsPattern("(?s)livenessProbe:\\s+httpGet:\\s+path: /health/live\\s")
        .containsPattern("(?s)readinessProbe:\\s+httpGet:\\s+path: /health/ready\\s")
        .containsPattern("(?m)^  strategy:\\n    type: Recreate$");
    assertThat(asciiLfText(chart.resolve("values.schema.json"))).contains("\"const\": 1");
    assertThat(List.of(asciiLfText(chart.resolve("values.yaml")).split("\n", -1)))
        .contains("replicaCount: 1");
  }
}
