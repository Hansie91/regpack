package dev.regpack.bench.proto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.regpack.bench.cli.TestBash;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The runner capture script records the machine for the result hand-off. The runner environment
 * holds tokens, so the script may print only the ten variables of {@link EnvCapture}, never the
 * whole environment.
 */
class RunnerCaptureScriptTest {
  private static final String SCRIPT = "spikes/scripts/runner-capture.sh";

  private static Path repo() {
    return Path.of(System.getProperty("regpack.repo.root"));
  }

  /** The lines of the script without the shebang and without comment lines. */
  private static List<String> codeLines(String script) {
    List<String> out = new ArrayList<>();
    List<String> lines = script.lines().toList();
    for (int i = 1; i < lines.size(); i++) {
      String stripped = lines.get(i).strip();
      if (!stripped.isEmpty() && !stripped.startsWith("#")) {
        out.add(stripped);
      }
    }
    return out;
  }

  @Test
  void scriptIsAsciiBashWithTheAllowListAndNoEnvironmentDump() throws IOException {
    byte[] bytes = Files.readAllBytes(repo().resolve(SCRIPT));
    for (byte b : bytes) {
      assertThat(b).as("ASCII only").isBetween((byte) 0, (byte) 127);
    }
    String script = new String(bytes, StandardCharsets.UTF_8);
    assertThat(script).startsWith("#!/usr/bin/env bash\n").doesNotContain("\r");
    for (String name : EnvCapture.ALLOWED_VARIABLES) {
      assertThat(script).as(name).contains(name);
    }
    Pattern dump =
        Pattern.compile("(^|[;&|(\\s])(env|printenv|export\\s+-p|declare\\s+-x)(\\s|$|;)");
    for (String line : codeLines(script)) {
      assertThat(dump.matcher(line).find()).as("environment dump: " + line).isFalse();
      assertThat(line).as("bare set").doesNotMatch("^set\\s*(;.*)?$");
    }
    assertThat(script).contains("uname -a").contains("lscpu").contains("model name");
    assertThat(script).contains("free -b").contains("/proc/meminfo").contains("df -B1 -T");
    assertThat(script).contains("lsblk -b").contains("docker --version");
    assertThat(script).contains("java -version").contains("/sys/fs/cgroup/cpu.max");
    assertThat(script).contains("runner.txt");
  }

  @Test
  void printsOnlyAllowListedVariables(@TempDir Path dir) throws Exception {
    String bash = TestBash.find().orElse(null);
    assumeTrue(bash != null, "no usable bash on this machine");
    Path out = dir.resolve("out");
    ProcessBuilder pb =
        new ProcessBuilder(bash, SCRIPT, out.toString())
            .directory(repo().toFile())
            .redirectErrorStream(true);
    pb.environment().put("ImageVersion", "20260101.7");
    pb.environment().put("GITHUB_RUN_ID", "424242");
    pb.environment().put("GITHUB_TOKEN", "ghs_secret_token_value");
    pb.environment().put("SPIKE_DB_PASSWORD", "hunter2-planted");
    pb.environment().put("ACTIONS_RUNTIME_TOKEN", "runtime-token-planted");
    Process process = pb.start();
    process.getInputStream().readAllBytes();
    assertThat(process.waitFor()).isZero();

    String captured = Files.readString(out.resolve("runner.txt"), StandardCharsets.UTF_8);
    assertThat(captured).contains("ImageVersion=20260101.7").contains("GITHUB_RUN_ID=424242");
    assertThat(captured)
        .doesNotContain("ghs_secret_token_value")
        .doesNotContain("hunter2-planted")
        .doesNotContain("runtime-token-planted")
        .doesNotContain("GITHUB_TOKEN")
        .doesNotContain("SPIKE_DB_PASSWORD");
    Matcher assignments = Pattern.compile("(?m)^([A-Za-z][A-Za-z0-9_]*)=").matcher(captured);
    while (assignments.find()) {
      assertThat(EnvCapture.ALLOWED_VARIABLES)
          .as("variable printed: " + assignments.group(1))
          .contains(assignments.group(1));
    }
  }
}
