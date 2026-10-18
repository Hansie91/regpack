package dev.regpack.bench.proto;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EnvCaptureTest {
  private static String resource(String name) throws IOException {
    try (InputStream in = EnvCaptureTest.class.getResourceAsStream(name)) {
      assertThat(in).as(name).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void parsesCpuModelAndMemoryFromFixtures() throws IOException {
    assertThat(EnvCapture.parseCpuModel(resource("/env/cpuinfo.txt")))
        .isEqualTo("AMD EPYC 7763 64-Core Processor");
    assertThat(EnvCapture.parseMemTotalBytes(resource("/env/meminfo.txt")))
        .isEqualTo(16_379_596L * 1024L);
  }

  @Test
  void unavailableFilesGiveUnknownAndZero() {
    assertThat(EnvCapture.parseCpuModel("")).isEqualTo("unknown");
    assertThat(EnvCapture.parseCpuModel("processor\t: 0\nBogoMIPS\t: 50.00\n"))
        .isEqualTo("unknown");
    assertThat(EnvCapture.parseMemTotalBytes("")).isZero();
    assertThat(EnvCapture.parseMemTotalBytes("MemTotal: lots kB\n")).isZero();
  }

  /** The runner environment also holds tokens; only the allow list may ever be recorded. */
  @Test
  void capturesOnlyAllowListedVariables() throws IOException {
    Map<String, String> env = new LinkedHashMap<>();
    Set<String> allowed =
        Set.of(
            "ImageOS",
            "ImageVersion",
            "RUNNER_OS",
            "RUNNER_ARCH",
            "GITHUB_RUN_ID",
            "GITHUB_RUN_ATTEMPT",
            "GITHUB_SHA",
            "GITHUB_REPOSITORY",
            "GITHUB_WORKFLOW",
            "GITHUB_REF_NAME");
    for (String name : allowed) {
      env.put(name, "value-of-" + name);
    }
    env.put("GITHUB_TOKEN", "ghs_secret-token-value");
    env.put("ACTIONS_RUNTIME_TOKEN", "secret-runtime-value");
    env.put("ACTIONS_ID_TOKEN_REQUEST_TOKEN", "secret-id-value");
    env.put("PATH", "/usr/bin");
    env.put("GITHUB_SHA_EXTRA", "prefix-match-must-not-count");
    env.put("github_sha", "case-variant-must-not-count");

    Map<String, Object> captured =
        EnvCapture.capture(env, resource("/env/cpuinfo.txt"), resource("/env/meminfo.txt"));

    @SuppressWarnings("unchecked")
    Map<String, Object> recorded = (Map<String, Object>) captured.get("env");
    assertThat(recorded.keySet()).containsExactlyInAnyOrderElementsOf(allowed);
    assertThat(recorded).containsEntry("GITHUB_SHA", "value-of-GITHUB_SHA");
    String json = Json.canonical(captured);
    assertThat(json)
        .doesNotContain("secret")
        .doesNotContain("ghs_")
        .doesNotContain("/usr/bin")
        .doesNotContain("must-not-count");
    assertThat(captured)
        .containsEntry("cpu.model", "AMD EPYC 7763 64-Core Processor")
        .containsEntry("mem.totalBytes", 16_379_596L * 1024L);
  }

  @Test
  void aVariableThatIsNotSetIsAbsentNotEmpty() throws IOException {
    Map<String, Object> captured = EnvCapture.capture(Map.of("RUNNER_OS", "Linux"), "", "");
    @SuppressWarnings("unchecked")
    Map<String, Object> recorded = (Map<String, Object>) captured.get("env");
    assertThat(recorded).containsOnlyKeys("RUNNER_OS");
    assertThat(captured).containsEntry("cpu.model", "unknown");
  }

  @Test
  void theLiveCaptureIsCanonicalJsonAndHoldsNoDouble() {
    Map<String, Object> captured = EnvCapture.capture();
    // Json.canonical rejects a Double, so this also proves there is none
    assertThat(Json.canonical(captured)).startsWith("{").contains("\"java.version\"");
    assertThat(captured.get("cpu.count")).isInstanceOf(Long.class);
    assertThat((Long) captured.get("cpu.count")).isPositive();
  }
}
