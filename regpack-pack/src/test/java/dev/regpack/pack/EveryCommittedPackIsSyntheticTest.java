package dev.regpack.pack;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.json.Json;
import dev.regpack.pack.yaml.StrictYaml;
import dev.regpack.pack.yaml.YamlList;
import dev.regpack.pack.yaml.YamlMap;
import dev.regpack.pack.yaml.YamlNode;
import dev.regpack.pack.yaml.YamlScalar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The G1, G3 and G4 guard (PF22): a committed pack holds no regulatory text, no third-party
 * regulator or industry artefact, and only consistency-only golden cases. The owner edits the
 * allow-lists when a real pack arrives. The guard is a vocabulary tripwire, not a proof: free text
 * that avoids the listed words is not seen by it. {@link #aHostilePackIsReportedByEveryRule} shows
 * that each rule can fail.
 */
class EveryCommittedPackIsSyntheticTest {
  private static final Set<String> SOURCE_IDS = Set.of("sample-law", "regpack-spec", "fixture-law");

  /** Lower-case; matched in file names and in file content, case-insensitively. */
  private static final List<String> FORBIDDEN_TEXT =
      List.of(
          "europa.eu",
          "rts 22",
          "mifir",
          "drr",
          "esma",
          "xsd",
          "workbook",
          "facet",
          "isda",
          "regulation (eu)");

  private static final String WHY =
      "a real pack needs gate G1 and G3 decisions first; the owner edits this allow-list";

  private static List<Path> packRoots() throws IOException {
    Path repo = Path.of(System.getProperty("regpack.repo.root"));
    List<Path> roots = new ArrayList<>();
    for (Path parent :
        List.of(repo.resolve("packs"), repo.resolve("regpack-engine/src/test/resources/packs"))) {
      if (!Files.isDirectory(parent)) {
        continue;
      }
      try (Stream<Path> list = Files.list(parent)) {
        list.filter(Files::isDirectory).sorted().forEach(roots::add);
      }
    }
    return roots;
  }

  private static List<Path> files(Path root) throws IOException {
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(Files::isRegularFile).sorted().toList();
    }
  }

  /** Every way in which the pack under {@code root} is not synthetic; empty when it is. */
  private static List<String> violations(Path root) throws IOException {
    List<String> found = new ArrayList<>();
    String pack = root.getFileName().toString();
    for (Path file : files(root)) {
      String rel = root.relativize(file).toString().replace('\\', '/');
      String where = pack + "/" + rel;
      String lowerName = rel.toLowerCase(Locale.ROOT);
      String lower = Files.readString(file, UTF_8).toLowerCase(Locale.ROOT);
      for (String word : FORBIDDEN_TEXT) {
        if (lowerName.contains(word)) {
          found.add("'" + word + "' in the file name " + where);
        }
        if (lower.contains(word)) {
          found.add("'" + word + "' in " + where);
        }
      }
      try {
        if (rel.equals("sources.yaml")) {
          checkSources(pack, Files.readString(file, UTF_8), found);
        }
        if (rel.startsWith("tests/golden/") && rel.endsWith(".json")) {
          checkGolden(where, Files.readString(file, UTF_8), found);
        }
      } catch (RuntimeException e) {
        found.add("cannot check " + where + ": " + e);
      }
    }
    return found;
  }

  @Test
  void noCommittedPackHasRegulatoryTextAndEveryCaseIsConsistencyOnly() throws IOException {
    List<Path> roots = packRoots();
    assertThat(roots).as("the sample pack exists").isNotEmpty();
    for (Path root : roots) {
      assertThat(violations(root)).as(WHY + ": " + root.getFileName()).isEmpty();
    }
  }

  @Test
  void aHostilePackIsReportedByEveryRule(@TempDir Path root) throws IOException {
    Path tests = Files.createDirectories(root.resolve("tests/golden"));
    Files.writeString(root.resolve("Workbook-notes.txt"), "plain\n", UTF_8);
    Files.writeString(root.resolve("a.yaml"), "text: see the ESMA technical standard\n", UTF_8);
    Files.writeString(root.resolve("b.yaml"), "text: schema.XSD attached\n", UTF_8);
    Files.writeString(root.resolve("c.yaml"), "text: a workbook row\n", UTF_8);
    Files.writeString(root.resolve("d.yaml"), "text: the Facet of a field\n", UTF_8);
    Files.writeString(root.resolve("e.yaml"), "text: DRR output\n", UTF_8);
    Files.writeString(root.resolve("f.yaml"), "text: ISDA definitions\n", UTF_8);
    Files.writeString(root.resolve("g.yaml"), "text: Regulation (EU) 2024/1\n", UTF_8);
    Files.writeString(root.resolve("h.yaml"), "text: https://eur-lex.europa.eu/x\n", UTF_8);
    Files.writeString(root.resolve("i.yaml"), "text: the RTS 22 annex and MiFIR\n", UTF_8);
    Files.writeString(
        root.resolve("sources.yaml"),
        "sources:\n"
            + "  - source_id: real-law\n"
            + "    provisions:\n"
            + "      - quote: Article 26 pasted\n",
        UTF_8);
    Files.writeString(tests.resolve("one.json"), "{\"provenance\":{\"kind\":\"EXPERT\"}}", UTF_8);
    Files.writeString(tests.resolve("two.json"), "{\"name\":\"no provenance\"}", UTF_8);
    String pack = root.getFileName().toString();
    assertThat(violations(root))
        .contains("'workbook' in the file name " + pack + "/Workbook-notes.txt")
        .contains(
            "'esma' in " + pack + "/a.yaml",
            "'xsd' in " + pack + "/b.yaml",
            "'workbook' in " + pack + "/c.yaml",
            "'facet' in " + pack + "/d.yaml",
            "'drr' in " + pack + "/e.yaml",
            "'isda' in " + pack + "/f.yaml",
            "'regulation (eu)' in " + pack + "/g.yaml",
            "'europa.eu' in " + pack + "/h.yaml",
            "'rts 22' in " + pack + "/i.yaml",
            "'mifir' in " + pack + "/i.yaml",
            "source id real-law is not on the allow-list in " + pack,
            "quote of real-law in " + pack + " does not start with SYNTHETIC",
            "provenance kind of " + pack + "/tests/golden/one.json is not CONSISTENCY_ONLY",
            "golden file " + pack + "/tests/golden/two.json has no provenance");
  }

  @Test
  void aSourcesFileWhoseSourcesAreNotAListFailsLoudly(@TempDir Path root) throws IOException {
    String pack = root.getFileName().toString();
    for (String body : List.of("sources: nothing\n", "other: 1\n", "sources:\n  a: b\n")) {
      Files.writeString(root.resolve("sources.yaml"), body, UTF_8);
      assertThat(violations(root))
          .as(body)
          .contains("sources is not a list in " + pack + "/sources.yaml");
    }
    Files.writeString(root.resolve("sources.yaml"), "sources:\n  - plain\n", UTF_8);
    assertThat(violations(root)).anyMatch(v -> v.startsWith("cannot check " + pack));
  }

  @Test
  void aCleanSyntheticPackIsNotReported(@TempDir Path root) throws IOException {
    Path tests = Files.createDirectories(root.resolve("tests/golden"));
    Files.writeString(
        root.resolve("sources.yaml"),
        "sources:\n"
            + "  - source_id: sample-law\n"
            + "    provisions:\n"
            + "      - quote: SYNTHETIC Article 1 text\n",
        UTF_8);
    Files.writeString(
        tests.resolve("case.json"), "{\"provenance\":{\"kind\":\"CONSISTENCY_ONLY\"}}", UTF_8);
    assertThat(violations(root)).isEmpty();
  }

  private static void checkSources(String pack, String text, List<String> found) {
    YamlMap top = (YamlMap) StrictYaml.parse(text);
    if (!(top.entries().get("sources") instanceof YamlList list)) {
      found.add("sources is not a list in " + pack + "/sources.yaml");
      return;
    }
    for (YamlNode item : list.items()) {
      YamlMap source = (YamlMap) item;
      String id = ((YamlScalar) source.entries().get("source_id")).value();
      if (!SOURCE_IDS.contains(id)) {
        found.add("source id " + id + " is not on the allow-list in " + pack);
      }
      if (source.entries().get("provisions") instanceof YamlList provisions) {
        for (YamlNode p : provisions.items()) {
          if (((YamlMap) p).entries().get("quote") instanceof YamlScalar quote
              && !quote.value().startsWith("SYNTHETIC")) {
            found.add("quote of " + id + " in " + pack + " does not start with SYNTHETIC");
          }
        }
      }
    }
  }

  private static void checkGolden(String where, String text, List<String> found) {
    if (!(Json.parse(text) instanceof Map<?, ?> map)) {
      found.add("golden file " + where + " is not an object");
      return;
    }
    if (!(map.get("provenance") instanceof Map<?, ?> provenance)) {
      found.add("golden file " + where + " has no provenance");
      return;
    }
    if (!"CONSISTENCY_ONLY".equals(provenance.get("kind"))) {
      found.add("provenance kind of " + where + " is not CONSISTENCY_ONLY");
    }
  }
}
