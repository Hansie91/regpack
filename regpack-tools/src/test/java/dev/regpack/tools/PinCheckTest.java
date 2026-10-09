package dev.regpack.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PinCheckTest {
  private static final String SHA = "3d3c42e5aac5ba805825da76410c181273ba90b1";
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  private static String workflow(String uses, String extra) {
    return "name: ci\non: push\npermissions:\n  contents: read\n"
        + extra
        + "jobs:\n  b:\n    runs-on: ubuntu-24.04\n    steps:\n      - "
        + uses
        + "\n";
  }

  @Test
  void unpinnedActionFails() {
    List<String> problems =
        PinCheck.checkWorkflow("ci.yml", workflow("uses: actions/checkout@v7", ""));
    assertThat(problems).anyMatch(p -> p.contains("UNPINNED_ACTION"));
  }

  @Test
  void shaPinnedActionWithVersionCommentPasses() {
    String content = workflow("uses: actions/checkout@" + SHA + " # v7.0.1", "");
    assertThat(PinCheck.checkWorkflow("ci.yml", content)).isEmpty();
  }

  @Test
  void missingVersionCommentFails() {
    List<String> problems =
        PinCheck.checkWorkflow("ci.yml", workflow("uses: actions/checkout@" + SHA, ""));
    assertThat(problems).hasSize(1).allMatch(p -> p.contains("MISSING_VERSION_COMMENT"));
  }

  @Test
  void missingTopLevelPermissionsFails() {
    String content =
        "name: ci\non: push\njobs:\n  b:\n    runs-on: ubuntu-24.04\n    permissions:\n"
            + "      contents: read\n    steps:\n      - uses: actions/checkout@"
            + SHA
            + " # v7.0.1\n";
    assertThat(PinCheck.checkWorkflow("ci.yml", content))
        .hasSize(1)
        .allMatch(p -> p.contains("MISSING_PERMISSIONS"));
  }

  @Test
  void latestRunnerFails() {
    String content =
        workflow("uses: actions/checkout@" + SHA + " # v7.0.1", "")
            .replace("ubuntu-24.04", "ubuntu-latest");
    assertThat(PinCheck.checkWorkflow("ci.yml", content))
        .hasSize(1)
        .allMatch(p -> p.contains("LATEST_RUNNER"));
  }

  @Test
  void pullRequestTargetFails() {
    String content =
        workflow("uses: actions/checkout@" + SHA + " # v7.0.1", "")
            .replace("on: push", "on:\n  pull_request_target:");
    assertThat(PinCheck.checkWorkflow("ci.yml", content))
        .anyMatch(p -> p.contains("PULL_REQUEST_TARGET"));
  }

  @Test
  void unpinnedFromFails() {
    List<String> problems =
        PinCheck.checkDockerfile("Dockerfile", "FROM eclipse-temurin:25-jre-noble\nRUN true\n");
    assertThat(problems).containsExactly("Dockerfile:1: FROM not pinned by digest");
  }

  @Test
  void pinnedFromPasses() {
    String content =
        "FROM --platform=linux/amd64 eclipse-temurin:25-jre-noble@" + DIGEST + " AS runtime\n";
    assertThat(PinCheck.checkDockerfile("Dockerfile", content)).isEmpty();
  }

  @Test
  void stageAliasReferencesAndScratchAreAllowed() {
    String content =
        "FROM eclipse-temurin:25-jdk@"
            + DIGEST
            + " AS build\nFROM scratch AS empty\nFROM build AS again\nFROM empty\n";
    assertThat(PinCheck.checkDockerfile("Dockerfile", content)).isEmpty();
  }
}
