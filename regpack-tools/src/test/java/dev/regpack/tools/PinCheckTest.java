package dev.regpack.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
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

  private static String withRunsOn(String runsOn) {
    return workflow("uses: actions/checkout@" + SHA + " # v7.0.1", "")
        .replace("runs-on: ubuntu-24.04\n", runsOn);
  }

  @Test
  void latestRunnerInBlockSequenceFails() {
    String content = withRunsOn("runs-on:\n      - self-hosted\n      - ubuntu-latest # comment\n");
    List<String> problems = PinCheck.checkWorkflow("ci.yml", content);
    assertThat(problems).hasSize(1);
    assertThat(problems.get(0)).contains("ci.yml:9: ").endsWith("LATEST_RUNNER ubuntu-latest");
  }

  @Test
  void latestRunnerInBlockSequenceAtKeyIndentFails() {
    String content = withRunsOn("runs-on:\n    - macos-latest\n");
    assertThat(PinCheck.checkWorkflow("ci.yml", content))
        .hasSize(1)
        .allMatch(p -> p.endsWith("LATEST_RUNNER macos-latest"));
  }

  @Test
  void latestRunnerInFlowMapFails() {
    for (String map : List.of("{ labels: ubuntu-latest }", "{labels: ubuntu-latest}")) {
      assertThat(PinCheck.checkWorkflow("ci.yml", withRunsOn("runs-on: " + map + "\n")))
          .as(map)
          .hasSize(1)
          .allMatch(p -> p.endsWith("LATEST_RUNNER ubuntu-latest"));
    }
  }

  @Test
  void latestRunnerInBlockMapFails() {
    String content =
        withRunsOn("runs-on:\n      group: shared\n      labels:\n        - windows-latest\n");
    assertThat(PinCheck.checkWorkflow("ci.yml", content))
        .hasSize(1)
        .allMatch(p -> p.endsWith("LATEST_RUNNER windows-latest"));
  }

  @Test
  void fixedRunnerBlockSequenceDoesNotLeakIntoFollowingKeys() {
    String content = withRunsOn("runs-on:\n      - ubuntu-24.04\n    name: ubuntu-latest\n");
    assertThat(PinCheck.checkWorkflow("ci.yml", content)).isEmpty();
  }

  @Test
  void shortOrUpperCaseOrOverlongActionShaFails() {
    for (String sha :
        List.of(SHA.substring(1), SHA.toUpperCase(Locale.ROOT), SHA + "0", "v7", "main")) {
      assertThat(PinCheck.checkWorkflow("ci.yml", workflow("uses: actions/checkout@" + sha, "")))
          .as(sha)
          .anyMatch(p -> p.contains("UNPINNED_ACTION"));
    }
  }

  @Test
  void writeAllPermissionsFail() {
    String top =
        workflow("uses: actions/checkout@" + SHA + " # v7.0.1", "")
            .replace("permissions:\n  contents: read\n", "permissions: write-all\n");
    assertThat(PinCheck.checkWorkflow("ci.yml", top))
        .hasSize(1)
        .allMatch(p -> p.endsWith("WRITE_ALL"));
    String job =
        workflow("uses: actions/checkout@" + SHA + " # v7.0.1", "")
            .replace("    steps:", "    permissions: write-all\n    steps:");
    assertThat(PinCheck.checkWorkflow("ci.yml", job))
        .hasSize(1)
        .allMatch(p -> p.endsWith("WRITE_ALL"));
    String commented =
        workflow("uses: actions/checkout@" + SHA + " # v7.0.1", "# permissions: write-all\n");
    assertThat(PinCheck.checkWorkflow("ci.yml", commented)).isEmpty();
  }

  @Test
  void localAndDockerActionsAreIgnored() {
    assertThat(PinCheck.checkWorkflow("ci.yml", workflow("uses: ./.github/actions/setup", "")))
        .isEmpty();
    assertThat(PinCheck.checkWorkflow("ci.yml", workflow("uses: docker://alpine:3.20", "")))
        .isEmpty();
  }

  @Test
  void shortUpperCaseOrOverlongDigestFails() {
    for (String digest :
        List.of(
            "sha256:" + "a".repeat(63),
            "sha256:" + "a".repeat(65),
            "sha256:" + "A".repeat(64),
            "sha256:abc",
            "sha512:" + "a".repeat(64))) {
      assertThat(PinCheck.checkDockerfile("Dockerfile", "FROM eclipse-temurin:25@" + digest + "\n"))
          .as(digest)
          .containsExactly("Dockerfile:1: FROM not pinned by digest");
    }
  }

  @Test
  void variableImageIsUnverifiable() {
    assertThat(PinCheck.checkDockerfile("Dockerfile", "ARG BASE\nFROM ${BASE}\n"))
        .containsExactly("Dockerfile:2: FROM image is unverifiable (contains a variable)");
    assertThat(PinCheck.checkDockerfile("Dockerfile", "ARG BASE\nFROM $BASE@" + DIGEST + "\n"))
        .containsExactly("Dockerfile:2: FROM image is unverifiable (contains a variable)");
  }
}
