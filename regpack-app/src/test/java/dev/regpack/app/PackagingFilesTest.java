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
        java.util.regex.Pattern.compile("\\bdocker\\b\\s*(\\S*)").matcher(String.join("\n", job));
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
