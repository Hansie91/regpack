package dev.regpack.bench.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.regpack.bench.eval.Basis;
import dev.regpack.bench.eval.CriterionResult;
import dev.regpack.bench.eval.SpikeResult;
import dev.regpack.bench.eval.Verdict;
import dev.regpack.model.Sha256;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResultSealTest {
  private static SpikeResult result() {
    return new SpikeResult(
        "HARNESS",
        Verdict.PASS,
        "FULL",
        "RAW",
        List.of(
            new CriterionResult(
                "ST-1",
                Verdict.PASS,
                Basis.DERIVED,
                Optional.empty(),
                Optional.of(BigDecimal.ZERO),
                BigDecimal.ZERO,
                "observed 0")),
        Map.of(),
        List.of(),
        Map.of(),
        Map.of(),
        Map.of("usedMinutes", 1L, "boxMinutes", 480L, "hit", Boolean.FALSE),
        "d".repeat(64),
        1,
        false,
        List.of());
  }

  private static String sha(Path file) throws IOException {
    return Sha256.of(Files.readAllBytes(file)).hex();
  }

  private static Path populated(Path dir) throws IOException {
    Files.writeString(dir.resolve("measurements.json"), "{}", StandardCharsets.UTF_8);
    Files.createDirectories(dir.resolve("raw/fork"));
    Files.writeString(dir.resolve("raw/fork/f0.json"), "{\"a\":1}\n", StandardCharsets.UTF_8);
    ResultSeal.seal(dir, result(), "# HARNESS report\n");
    return dir;
  }

  @Test
  void manifestIsTheLastWrite(@TempDir Path dir) throws IOException {
    populated(dir);
    Path manifest = dir.resolve("manifest.sha256");
    List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
    assertThat(lines)
        .containsExactly(
            sha(dir.resolve("measurements.json")) + "  measurements.json",
            sha(dir.resolve("raw/fork/f0.json")) + "  raw/fork/f0.json",
            sha(dir.resolve("report.md")) + "  report.md",
            sha(dir.resolve("summary.json")) + "  summary.json");
    assertThat(Files.readString(manifest))
        .doesNotContain("manifest.sha256")
        .doesNotContain("\r")
        .endsWith("\n");
    // the final hashes: the files were complete before they were hashed
    assertThat(Files.readString(dir.resolve("report.md"))).isEqualTo("# HARNESS report\n");
    assertThat(Files.readString(dir.resolve("summary.json")))
        .contains("\"spike\":\"HARNESS\"")
        .endsWith("\n");
    // Nothing is written after the manifest. The listing above is the strict check: a manifest
    // taken before the report was complete would lack or mis-hash it. The mtime comparison is best
    // effort only (a coarse file-system clock cannot order two writes of the same tick), so it
    // can miss a late touch but never fails a correct seal.
    assertThat(Files.getLastModifiedTime(manifest))
        .isGreaterThanOrEqualTo(Files.getLastModifiedTime(dir.resolve("summary.json")))
        .isGreaterThanOrEqualTo(Files.getLastModifiedTime(dir.resolve("report.md")));
    try (var names = Files.list(dir)) {
      assertThat(names.map(p -> p.getFileName().toString()).sorted().toList())
          .containsExactly(
              "manifest.sha256", "measurements.json", "raw", "report.md", "summary.json");
    }
    assertThat(ResultSeal.verify(dir)).isEmpty();
  }

  @Test
  void sealRefusesAnExistingManifest(@TempDir Path dir) throws IOException {
    populated(dir);
    String before = Files.readString(dir.resolve("manifest.sha256"));
    assertThatThrownBy(() -> ResultSeal.seal(dir, result(), "# changed\n"))
        .isInstanceOf(FileAlreadyExistsException.class);
    assertThat(Files.readString(dir.resolve("manifest.sha256"))).isEqualTo(before);
    assertThat(Files.readString(dir.resolve("report.md"))).isEqualTo("# HARNESS report\n");
  }

  @Test
  void sealRefusesAMissingDirectory(@TempDir Path dir) {
    assertThatThrownBy(() -> ResultSeal.seal(dir.resolve("nope"), result(), "x"))
        .isInstanceOf(IOException.class);
  }

  @Test
  void fileAddedAfterSealIsUnlisted(@TempDir Path dir) throws IOException {
    populated(dir);
    Files.writeString(dir.resolve("raw/extra.json"), "{}");
    assertThat(ResultSeal.verify(dir)).containsExactly("UNLISTED raw/extra.json");
  }

  @Test
  void problemsComeInAFixedOrderWhateverTheFileSystemWalkOrder(@TempDir Path dir)
      throws IOException {
    populated(dir);
    // by kind (MISSING, HASH_MISMATCH, UNLISTED), then by path; the unlisted files are created in
    // reverse order and the missing file sorts after the changed one by path
    Files.writeString(dir.resolve("zz.txt"), "z");
    Files.writeString(dir.resolve("aa.txt"), "a");
    Files.createDirectories(dir.resolve("mid"));
    Files.writeString(dir.resolve("mid/m.txt"), "m");
    Files.writeString(dir.resolve("measurements.json"), "{\"changed\":true}");
    Files.delete(dir.resolve("raw/fork/f0.json"));
    assertThat(ResultSeal.verify(dir))
        .containsExactly(
            "MISSING raw/fork/f0.json",
            "HASH_MISMATCH measurements.json",
            "UNLISTED aa.txt",
            "UNLISTED mid/m.txt",
            "UNLISTED zz.txt");
  }

  @Test
  void changedByteIsHashMismatch(@TempDir Path dir) throws IOException {
    populated(dir);
    Files.writeString(
        dir.resolve("summary.json"),
        Files.readString(dir.resolve("summary.json")).replace("PASS", "FAIL"));
    assertThat(ResultSeal.verify(dir)).containsExactly("HASH_MISMATCH summary.json");
  }

  @Test
  void deletedFileIsMissing(@TempDir Path dir) throws IOException {
    populated(dir);
    Files.delete(dir.resolve("raw/fork/f0.json"));
    assertThat(ResultSeal.verify(dir)).containsExactly("MISSING raw/fork/f0.json");
  }

  @Test
  void aDirectoryWithoutAManifestIsNotClean(@TempDir Path dir) throws IOException {
    Files.writeString(dir.resolve("report.md"), "x");
    assertThat(ResultSeal.verify(dir)).contains("MISSING manifest.sha256");
  }

  @Test
  void aManifestCannotNameAPathOutsideTheDirectory(@TempDir Path dir) throws IOException {
    Path inner = Files.createDirectories(dir.resolve("inner"));
    Files.writeString(dir.resolve("outside.txt"), "secret");
    Files.writeString(inner.resolve("a.txt"), "a");
    String bad =
        sha(dir.resolve("outside.txt"))
            + "  ../outside.txt\n"
            + sha(inner.resolve("a.txt"))
            + "  a.txt\n";
    Files.writeString(inner.resolve("manifest.sha256"), bad);
    List<String> problems = ResultSeal.verify(inner);
    assertThat(problems).anyMatch(p -> p.startsWith("BAD_MANIFEST"));
    for (String bad2 :
        List.of(
            "/etc/passwd",
            "a/../../x",
            "C:" + '\\' + "x",
            "a" + '\\' + "b",
            "./a.txt",
            "manifest.sha256",
            "")) {
      Files.writeString(inner.resolve("manifest.sha256"), "0".repeat(64) + "  " + bad2 + "\n");
      assertThat(ResultSeal.verify(inner)).as(bad2).anyMatch(p -> p.startsWith("BAD_MANIFEST"));
    }
    Files.writeString(inner.resolve("manifest.sha256"), "not a hash  a.txt\n");
    assertThat(ResultSeal.verify(inner)).anyMatch(p -> p.startsWith("BAD_MANIFEST"));
    String twice =
        sha(inner.resolve("a.txt")) + "  a.txt\n" + sha(inner.resolve("a.txt")) + "  a.txt\n";
    Files.writeString(inner.resolve("manifest.sha256"), twice);
    assertThat(ResultSeal.verify(inner)).anyMatch(p -> p.startsWith("BAD_MANIFEST"));
  }

  /**
   * A link to a directory: a symbolic link where the platform allows one, else a directory junction
   * on Windows (no privilege needed). Returns false when neither can be made.
   */
  private static boolean link(Path link, Path targetDir) {
    try {
      Files.createSymbolicLink(link, targetDir);
      return true;
    } catch (UnsupportedOperationException | IOException | SecurityException e) {
      if (!System.getProperty("os.name", "").startsWith("Windows")) {
        return false;
      }
    }
    try {
      Process p =
          new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), targetDir.toString())
              .redirectErrorStream(true)
              .start();
      p.getInputStream().readAllBytes();
      return p.waitFor() == 0 && Files.exists(link);
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Test
  void symlinkIsRefused(@TempDir Path dir) throws IOException {
    Path target = Files.createDirectories(dir.resolve("target"));
    Files.writeString(target.resolve("t.txt"), "t");
    Path sealDir = Files.createDirectories(dir.resolve("out"));
    Path link = sealDir.resolve("link");
    assumeTrue(link(link, target), "neither a symbolic link nor a junction can be created here");
    assertThatThrownBy(() -> ResultSeal.seal(sealDir, result(), "# r\n"))
        .isInstanceOf(IOException.class);
    assertThat(sealDir.resolve("manifest.sha256")).doesNotExist();
    // and a link added after a seal is reported, not followed
    Files.delete(link);
    ResultSeal.seal(sealDir, result(), "# r\n");
    assertThat(link(link, target)).isTrue();
    assertThat(ResultSeal.verify(sealDir)).containsExactly("SYMLINK link");
    Files.delete(link);
    assertThat(ResultSeal.verify(sealDir)).isEmpty();
  }

  /**
   * A symbolic link to a file comes through the file branch of the walk, not the directory branch
   * that a junction takes. A junction cannot stand in for it, so this case needs the privilege to
   * create a real symbolic link (always there on Linux and macOS, and on Windows with developer
   * mode or an elevated shell).
   */
  @Test
  void aFileSymlinkIsRefusedAndReported(@TempDir Path dir) throws IOException {
    Path target = dir.resolve("target.txt");
    Files.writeString(target, "t");
    Path sealDir = Files.createDirectories(dir.resolve("out"));
    Path link = sealDir.resolve("link.txt");
    assumeTrue(fileLink(link, target), "a symbolic link to a file cannot be created here");
    assertThatThrownBy(() -> ResultSeal.seal(sealDir, result(), "# r\n"))
        .isInstanceOf(IOException.class);
    assertThat(sealDir.resolve("manifest.sha256")).doesNotExist();
    Files.delete(link);
    ResultSeal.seal(sealDir, result(), "# r\n");
    assertThat(fileLink(link, target)).isTrue();
    assertThat(ResultSeal.verify(sealDir)).containsExactly("SYMLINK link.txt");
    Files.delete(link);
    assertThat(ResultSeal.verify(sealDir)).isEmpty();
  }

  private static boolean fileLink(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
      return true;
    } catch (UnsupportedOperationException | IOException | SecurityException e) {
      return false;
    }
  }
}
