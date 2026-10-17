package dev.regpack.engine.pack;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Invariant I4 (regulation is data): regime logic found in engine code is a design alarm. The
 * engine and the pack module name no regime, no regulation text and no decision-tree node of the
 * placeholder regimes; the P0 {@code skeleton} package is the one place that does, until it is
 * removed (open item OI-6). Comments are scanned too: a regime name in a comment is how it creeps
 * into code.
 */
class NoRegimeLogicInEngineTest {
  /**
   * Regime names, the technical-standard name and decision-tree nodes N01 to N19 (case-sensitive).
   */
  private static final Pattern REGIME_LITERAL =
      Pattern.compile("\\bMIFIR\\b|\\bEMIR\\b|RTS ?22|\\bN(0[1-9]|1[0-9])\\b");

  private static final String SKELETON = "/dev/regpack/engine/skeleton/";

  private static Path repo() {
    return Path.of(System.getProperty("regpack.repo.root"));
  }

  private static List<Path> sources() throws IOException {
    List<Path> files = new ArrayList<>();
    for (String root : List.of("regpack-engine/src/main/java", "regpack-pack/src/main/java")) {
      try (Stream<Path> walk = Files.walk(repo().resolve(root))) {
        walk.filter(p -> p.toString().endsWith(".java"))
            .filter(p -> !p.toString().replace('\\', '/').contains(SKELETON))
            .sorted()
            .forEach(files::add);
      }
    }
    return files;
  }

  @Test
  void engineAndPackSourcesCarryNoRegimeLiterals() throws IOException {
    List<Path> files = sources();
    assertThat(files).as("the scan reads the engine and the pack module").hasSizeGreaterThan(50);
    assertThat(files.stream().map(p -> p.getFileName().toString()))
        .contains("PackRegime.java", "PackProgram.java", "PackLoader.java", "TableParser.java");
    assertThat(files.stream().map(p -> p.toString().replace('\\', '/')))
        .noneMatch(p -> p.contains(SKELETON));

    List<String> found = new ArrayList<>();
    for (Path file : files) {
      String[] lines = Files.readString(file, UTF_8).split("\n", -1);
      for (int i = 0; i < lines.length; i++) {
        if (REGIME_LITERAL.matcher(lines[i]).find()) {
          found.add(repo().relativize(file).toString().replace('\\', '/') + ":" + (i + 1));
        }
      }
    }
    assertThat(found).as("regime literals in engine or pack sources").isEmpty();
  }

  @Test
  void theScanCanFail() {
    List<String> hits =
        List.of(
            "String regime = \"MIFIR\";",
            "// the EMIR action type",
            "see RTS 22 annex",
            "see RTS22",
            "node N03 excludes",
            "{@code N19}",
            "(N01)");
    for (String text : hits) {
      assertThat(REGIME_LITERAL.matcher(text).find()).as(text).isTrue();
    }
    List<String> clean =
        List.of(
            "MiFIR",
            "emir",
            "EMIRS",
            "UNN03",
            "N03X",
            "N00",
            "N20",
            "N3",
            "a regime-neutral engine",
            "for example {@code SAMPLE}");
    for (String text : clean) {
      assertThat(REGIME_LITERAL.matcher(text).find()).as(text).isFalse();
    }
  }
}
