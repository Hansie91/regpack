package dev.regpack.engine.pack;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.model.LoadResult;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.pack.model.PackLoader;
import dev.regpack.pack.source.Checksums;
import dev.regpack.pack.source.PackSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Test helper: the committed sample pack {@code packs/sample.s0} as a map of file to text, and
 * variants of it. A variant replaces the text of some files; {@link #sealed} recomputes {@code
 * CHECKSUMS}, so the loader's integrity check passes and only the intended change is under test.
 */
public final class SamplePacks {
  private SamplePacks() {}

  /** The directory of the sample pack under the repository root. */
  public static Path directory() {
    return Path.of(System.getProperty("regpack.repo.root")).resolve("packs").resolve("sample.s0");
  }

  /** Every file of the sample pack except {@code CHECKSUMS} and {@code ATTESTATION}, as text. */
  public static Map<String, String> files() {
    PackSource source = PackSource.fromDirectory(directory());
    Map<String, String> files = new TreeMap<>();
    for (String path : source.paths()) {
      if (!path.equals(PackSource.CHECKSUMS) && !path.equals(PackSource.ATTESTATION)) {
        files.put(path, source.text(path));
      }
    }
    return files;
  }

  /**
   * The sample pack with the given files replaced (a null text removes the file; a new path adds
   * it), sealed with a fresh {@code CHECKSUMS}.
   */
  public static PackSource sealed(Map<String, String> overrides) {
    Map<String, String> files = files();
    overrides.forEach(
        (path, text) -> {
          if (text == null) {
            files.remove(path);
          } else {
            files.put(path, text);
          }
        });
    Map<String, byte[]> bytes = new HashMap<>();
    files.forEach((path, text) -> bytes.put(path, text.getBytes(UTF_8)));
    String checksums = Checksums.render(PackSource.of(bytes));
    bytes.put(PackSource.CHECKSUMS, checksums.getBytes(UTF_8));
    return PackSource.of(bytes);
  }

  /** Loads the unchanged sample pack; fails the test on any problem. */
  public static LoadedPack load() {
    return loadSealed(Map.of());
  }

  /** Loads a sealed variant; fails the test on any problem (the variant must be loadable). */
  public static LoadedPack loadSealed(Map<String, String> overrides) {
    LoadResult result = PackLoader.load(sealed(overrides));
    List<String> problems = new ArrayList<>();
    for (PackProblem p : result.problems()) {
      problems.add(p.code() + " " + p.path() + ": " + p.message());
    }
    assertThat(problems).as("problems of the pack variant").isEmpty();
    return result.pack().orElseThrow();
  }

  /** The text of a sample file with every occurrence of {@code from} replaced. */
  public static String replaced(String path, String from, String to) {
    String text = files().get(path);
    assertThat(text).as(path).contains(from);
    return text.replace(from, to);
  }
}
