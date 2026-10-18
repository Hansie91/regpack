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
import org.junit.jupiter.api.io.TempDir;

/**
 * Invariant I4 (regulation is data): regime logic found in engine code is a design alarm. The
 * engine and the pack module name no regime, no regulation text and no decision-tree node of the
 * placeholder regimes; the P0 {@code skeleton} package is the one place that does, until it is
 * removed (open item OI-6). Comments are scanned too: a regime name in a comment is how it creeps
 * into code. The scan is a tripwire for the likely shapes (a name in any case, a dotted regime id,
 * a camel-case method name), not a proof: logic that names its regime by some other route is not
 * seen by it.
 */
class NoRegimeLogicInEngineTest {
  /**
   * Regime and technical-standard names in any case and in any identifier shape (a lower-case
   * dotted id such as {@code mifir.eu.rts22}, a method {@code isMifir}), with the separators the
   * standard's name is written with ({@code RTS 22}, {@code rts-22}, {@code rts_22}).
   */
  private static final Pattern REGIME_NAME =
      Pattern.compile(
          "mifir|mifid|(?<![a-z])(emir|sftr|csdr)(?![a-z])|(?<![a-z])rts[ _.-]?22(?![0-9])",
          Pattern.CASE_INSENSITIVE);

  /** The decision-tree nodes N01 to N19 of the placeholder regimes (case-sensitive). */
  private static final Pattern NODE = Pattern.compile("\\bN(0[1-9]|1[0-9])\\b");

  /** A camel-case boundary: {@code isEmir} is read as {@code is Emir}. */
  private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

  private static final String SKELETON = "/dev/regpack/engine/skeleton/";

  static boolean carriesRegimeLiteral(String line) {
    return REGIME_NAME.matcher(line).find()
        || REGIME_NAME.matcher(CAMEL_BOUNDARY.matcher(line).replaceAll(" ")).find()
        || NODE.matcher(line).find();
  }

  private static Path repo() {
    return Path.of(System.getProperty("regpack.repo.root"));
  }

  private static List<Path> sources(Path root) throws IOException {
    List<Path> files = new ArrayList<>();
    for (String module : List.of("regpack-engine/src/main/java", "regpack-pack/src/main/java")) {
      try (Stream<Path> walk = Files.walk(root.resolve(module))) {
        walk.filter(p -> p.toString().endsWith(".java"))
            .filter(p -> !p.toString().replace('\\', '/').contains(SKELETON))
            .sorted()
            .forEach(files::add);
      }
    }
    return files;
  }

  private static List<String> literalsIn(Path root, List<Path> files) throws IOException {
    List<String> found = new ArrayList<>();
    for (Path file : files) {
      String[] lines = Files.readString(file, UTF_8).split("\n", -1);
      for (int i = 0; i < lines.length; i++) {
        if (carriesRegimeLiteral(lines[i])) {
          found.add(root.relativize(file).toString().replace('\\', '/') + ":" + (i + 1));
        }
      }
    }
    return found;
  }

  @Test
  void engineAndPackSourcesCarryNoRegimeLiterals() throws IOException {
    List<Path> files = sources(repo());
    assertThat(files).as("the scan reads the engine and the pack module").hasSizeGreaterThan(50);
    assertThat(files.stream().map(p -> p.getFileName().toString()))
        .contains("PackRegime.java", "PackProgram.java", "PackLoader.java", "TableParser.java");
    assertThat(files.stream().map(p -> p.toString().replace('\\', '/')))
        .noneMatch(p -> p.contains(SKELETON));
    assertThat(literalsIn(repo(), files)).as("regime literals in engine or pack sources").isEmpty();
  }

  @Test
  void regimeLogicPlantedInAnEngineSourceIsFound(@TempDir Path root) throws IOException {
    Path dir = root.resolve("regpack-engine/src/main/java/dev/regpack/engine/pack");
    Files.createDirectories(dir);
    Files.createDirectories(root.resolve("regpack-pack/src/main/java"));
    Path planted = dir.resolve("PackProgram.java");
    Files.writeString(
        planted,
        "class PackProgram {\n"
            + "  static boolean isMifir(String regime) {\n"
            + "    return regime.equals(\"mifir.eu.rts22\") || regime.startsWith(\"emir.\");\n"
            + "  }\n"
            + "}\n",
        UTF_8);
    assertThat(literalsIn(root, sources(root)))
        .containsExactly(
            "regpack-engine/src/main/java/dev/regpack/engine/pack/PackProgram.java:2",
            "regpack-engine/src/main/java/dev/regpack/engine/pack/PackProgram.java:3");
    Files.writeString(planted, "class PackProgram {\n  // a regime-neutral engine\n}\n", UTF_8);
    assertThat(literalsIn(root, sources(root))).isEmpty();
  }

  @Test
  void theScanCanFail() {
    List<String> hits =
        List.of(
            "String regime = \"MIFIR\";",
            "String regime = \"mifir.eu.rts22\";",
            "regime.startsWith(\"emir.\")",
            "// the EMIR action type",
            "// the emir action type",
            "MiFIR",
            "mifid",
            "static boolean isMifir(String regime)",
            "static boolean isEmir()",
            "boolean IS_EMIR",
            "see RTS 22 annex",
            "see RTS22",
            "see rts22",
            "see rts-22",
            "see rts_22 annex",
            "see rts 22",
            "id.endsWith(\"rts22\")",
            "sftr.eu.x",
            "csdr",
            "node N03 excludes",
            "{@code N19}",
            "(N01)");
    for (String text : hits) {
      assertThat(carriesRegimeLiteral(text)).as(text).isTrue();
    }
    List<String> clean =
        List.of(
            "EMIRS",
            "demirel",
            "emirates",
            "UNN03",
            "N03X",
            "n03",
            "N00",
            "N20",
            "N3",
            "rts2",
            "rts222",
            "a regime-neutral engine",
            "x.y.z",
            "for example {@code SAMPLE}");
    for (String text : clean) {
      assertThat(carriesRegimeLiteral(text)).as(text).isFalse();
    }
  }
}
