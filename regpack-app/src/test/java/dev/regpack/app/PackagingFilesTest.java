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
  void ciWorkflowHasTheFiveJobsReadOnlyPermissionsAndMatchingArtefactNames() throws IOException {
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
            "build", "determinism-compare", "smoke-linux", "smoke-windows", "smoke-kubernetes");

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
