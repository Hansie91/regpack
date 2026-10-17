package dev.regpack.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cross-operating-system determinism check of {@code ci.yml} (the only evidence for the second
 * digest of the crash-determinism clause), tested by its meaning: the checker reads the upload and
 * the compare step, the same checker reports deliberately broken copies of the workflow, and the
 * real compare step is executed on directories built here.
 */
class CiDeterminismCompareTest {
  private static final String STEP = "Compare toy crash digests";

  private static String ci() throws IOException {
    return Files.readString(
        Path.of(System.getProperty("regpack.repo.root")).resolve(".github/workflows/ci.yml"),
        StandardCharsets.UTF_8);
  }

  private static String without(String ci, String lineContaining) {
    return ci.lines()
        .map(l -> l.contains(lineContaining) ? "          true" : l)
        .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
  }

  @Test
  void theShippedWorkflowUploadsAndComparesBothDigestsOfBothOperatingSystems() throws IOException {
    assertThat(CiCompareStep.problems(ci())).isEmpty();
  }

  @Test
  void theCheckerReportsEachWayOfSilentlyDisablingTheComparison() throws IOException {
    String ci = ci();
    assertThat(CiCompareStep.problems(ci)).isEmpty();
    Map<String, String> broken =
        Map.ofEntries(
            Map.entry("pack digest compare removed", without(ci, "[ \"$pa\" = \"$pb\" ]")),
            Map.entry("skeleton digest compare removed", without(ci, "[ \"$a\" = \"$b\" ]")),
            Map.entry(
                "or true added to the pack compare",
                ci.replace(
                    "pack-pipeline toy crash digests differ between operating systems\"; exit 1; }",
                    "pack-pipeline toy crash digests differ between operating systems\"; exit 1; } || true")),
            Map.entry(
                "or true added to the skeleton compare",
                ci.replace(
                    "toy crash digests differ between operating systems\"; exit 1; }\n",
                    "toy crash digests differ between operating systems\"; exit 1; } || true\n")),
            Map.entry(
                "the compare no longer exits",
                ci.replace(
                    "pack-pipeline toy crash digests differ between operating systems\"; exit 1; }",
                    "pack-pipeline toy crash digests differ between operating systems\"; }")),
            Map.entry(
                "strict mode removed",
                ci.replace("          set -euo pipefail\n          a=$(cat", "          a=$(cat")),
            Map.entry(
                "missing-file check removed (windows pack digest not read)",
                ci.replace(
                    "pb=$(cat digests/toy-digest-windows-2025/pack-toy-digest.txt)", "pb=\"$pa\"")),
            Map.entry(
                "the same file read for both systems",
                ci.replace(
                    "b=$(cat digests/toy-digest-windows-2025/toy-digest.txt)",
                    "b=$(cat digests/toy-digest-ubuntu-24.04/toy-digest.txt)")),
            Map.entry("empty-digest check removed", without(ci, "[ -n \"$pa\" ]")),
            Map.entry(
                "compare step may fail silently",
                ci.replace(
                    "      - name: " + STEP + "\n        shell: bash\n",
                    "      - name: "
                        + STEP
                        + "\n        continue-on-error: true\n        shell: bash\n")),
            Map.entry(
                "compare step skipped",
                ci.replace(
                    "      - name: " + STEP + "\n        shell: bash\n",
                    "      - name: " + STEP + "\n        if: false\n        shell: bash\n")),
            Map.entry(
                "pack digest not uploaded",
                ci.replace("            regpack-engine/target/pack-toy-digest.txt\n", "")),
            Map.entry(
                "skeleton digest not uploaded",
                ci.replace("            regpack-engine/target/toy-digest.txt\n", "")),
            Map.entry(
                "a missing digest does not fail the upload",
                ci.replace(
                    "          path: |\n            regpack-engine/target/toy-digest.txt\n"
                        + "            regpack-engine/target/pack-toy-digest.txt\n"
                        + "          if-no-files-found: error",
                    "          path: |\n            regpack-engine/target/toy-digest.txt\n"
                        + "            regpack-engine/target/pack-toy-digest.txt\n"
                        + "          if-no-files-found: warn")),
            Map.entry(
                "built on one operating system only",
                ci.replace(
                    "        os: [ubuntu-24.04, windows-2025]", "        os: [ubuntu-24.04]")));
    for (Map.Entry<String, String> entry : broken.entrySet()) {
      assertThat(entry.getValue()).as(entry.getKey() + " changed the workflow").isNotEqualTo(ci);
      assertThat(CiCompareStep.problems(entry.getValue())).as(entry.getKey()).isNotEmpty();
    }
  }

  /** The real compare step, as a shell script, executed in a directory built by the test. */
  private static int runCompare(String bash, Path workDir)
      throws IOException, InterruptedException {
    String script =
        CiCompareStep.runBlock(CiCompareStep.stepLines(ci(), "determinism-compare", STEP));
    assertThat(script).contains("set -euo pipefail");
    Process process =
        new ProcessBuilder(bash, "-s")
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    process.getOutputStream().write(script.getBytes(StandardCharsets.UTF_8));
    process.getOutputStream().close();
    assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("the compare step ends").isTrue();
    return process.exitValue();
  }

  private static void digest(Path dir, String os, String file, String content) throws IOException {
    Path target = dir.resolve("digests/toy-digest-" + os);
    Files.createDirectories(target);
    Files.writeString(target.resolve(file), content, StandardCharsets.UTF_8);
  }

  private static void bothDigests(Path dir, String os, String toy, String pack) throws IOException {
    digest(dir, os, "toy-digest.txt", toy);
    digest(dir, os, "pack-toy-digest.txt", pack);
  }

  /**
   * A working bash on the PATH. On Windows a bare {@code bash} is resolved to the WSL launcher of
   * the system directory, which fails without a distribution, so the PATH is scanned by hand and
   * anything under the Windows directory is skipped.
   */
  private static Optional<String> findBash() {
    String path = System.getenv("PATH");
    String windows = System.getenv("SystemRoot");
    if (path == null) {
      return Optional.empty();
    }
    for (String entry : path.split(File.pathSeparator)) {
      if (entry.isBlank()
          || (windows != null && entry.regionMatches(true, 0, windows, 0, windows.length()))) {
        continue;
      }
      for (String name : new String[] {"bash", "bash.exe"}) {
        Path candidate = Path.of(entry).resolve(name);
        if (Files.isRegularFile(candidate) && bashWorks(candidate.toString())) {
          return Optional.of(candidate.toString());
        }
      }
    }
    return Optional.empty();
  }

  private static boolean bashWorks(String bash) {
    try {
      Process p = new ProcessBuilder(bash, "-c", "echo ok").redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      return p.waitFor() == 0 && out.trim().equals("ok");
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Test
  void theShippedCompareStepPassesOnEqualDigestsAndFailsOnEveryDefect(@TempDir Path root)
      throws IOException, InterruptedException {
    Optional<String> found = findBash();
    assumeTrue(found.isPresent(), "bash is needed to execute the compare step (CI always has it)");
    String bash = found.get();
    Path equal = Files.createDirectories(root.resolve("equal"));
    bothDigests(equal, "ubuntu-24.04", "aaaa\n", "bbbb\n");
    bothDigests(equal, "windows-2025", "aaaa\n", "bbbb\n");
    assertThat(runCompare(bash, equal)).as("equal digests").isZero();

    Path toyDiffers = Files.createDirectories(root.resolve("toy-differs"));
    bothDigests(toyDiffers, "ubuntu-24.04", "aaaa\n", "bbbb\n");
    bothDigests(toyDiffers, "windows-2025", "aaa0\n", "bbbb\n");
    assertThat(runCompare(bash, toyDiffers)).as("skeleton digests differ").isNotZero();

    Path packDiffers = Files.createDirectories(root.resolve("pack-differs"));
    bothDigests(packDiffers, "ubuntu-24.04", "aaaa\n", "bbbb\n");
    bothDigests(packDiffers, "windows-2025", "aaaa\n", "bbb0\n");
    assertThat(runCompare(bash, packDiffers)).as("pack-pipeline digests differ").isNotZero();

    Path packMissing = Files.createDirectories(root.resolve("pack-missing"));
    bothDigests(packMissing, "ubuntu-24.04", "aaaa\n", "bbbb\n");
    digest(packMissing, "windows-2025", "toy-digest.txt", "aaaa\n");
    assertThat(runCompare(bash, packMissing)).as("a pack digest file is missing").isNotZero();

    Path toyMissing = Files.createDirectories(root.resolve("toy-missing"));
    digest(toyMissing, "ubuntu-24.04", "pack-toy-digest.txt", "bbbb\n");
    bothDigests(toyMissing, "windows-2025", "aaaa\n", "bbbb\n");
    assertThat(runCompare(bash, toyMissing)).as("a skeleton digest file is missing").isNotZero();

    Path noDirectory = Files.createDirectories(root.resolve("no-directory"));
    bothDigests(noDirectory, "ubuntu-24.04", "aaaa\n", "bbbb\n");
    assertThat(runCompare(bash, noDirectory)).as("the windows artefact is missing").isNotZero();

    Path emptyToy = Files.createDirectories(root.resolve("empty-toy"));
    bothDigests(emptyToy, "ubuntu-24.04", "", "bbbb\n");
    bothDigests(emptyToy, "windows-2025", "", "bbbb\n");
    assertThat(runCompare(bash, emptyToy)).as("two empty skeleton digests").isNotZero();

    Path emptyPack = Files.createDirectories(root.resolve("empty-pack"));
    bothDigests(emptyPack, "ubuntu-24.04", "aaaa\n", "");
    bothDigests(emptyPack, "windows-2025", "aaaa\n", "");
    assertThat(runCompare(bash, emptyPack)).as("two empty pack-pipeline digests").isNotZero();
  }

  @Test
  void theCheckerFindsTheStepsItReads() throws IOException {
    String ci = ci();
    List<String> step = CiCompareStep.stepLines(ci, "determinism-compare", STEP);
    assertThat(step).isNotEmpty();
    assertThat(CiCompareStep.runBlock(step))
        .startsWith("set -euo pipefail\n")
        .contains("digests/toy-digest-ubuntu-24.04/toy-digest.txt")
        .contains("digests/toy-digest-windows-2025/pack-toy-digest.txt");
    assertThat(CiCompareStep.stepLines(ci, "determinism-compare", "no such step")).isEmpty();
    assertThat(CiCompareStep.stepLines(ci, "no-such-job", STEP)).isEmpty();
  }
}
