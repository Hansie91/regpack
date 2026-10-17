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

/**
 * The G1, G3 and G4 guard (PF22): a committed pack holds no regulatory text, no ESMA or DRR
 * artefact, and only consistency-only golden cases. The owner edits the allow-lists when a real
 * pack arrives.
 */
class EveryCommittedPackIsSyntheticTest {
  private static final Set<String> SOURCE_IDS = Set.of("sample-law", "regpack-spec", "fixture-law");
  private static final List<String> FORBIDDEN_TEXT = List.of("europa.eu", "rts 22", "mifir", "drr");
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

  @Test
  void noCommittedPackHasRegulatoryTextAndEveryCaseIsConsistencyOnly() throws IOException {
    List<Path> roots = packRoots();
    assertThat(roots).as("the sample pack exists").isNotEmpty();
    for (Path root : roots) {
      for (Path file : files(root)) {
        String rel = root.relativize(file).toString().replace('\\', '/');
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        assertThat(name).as(WHY + ": file name " + rel).doesNotContain("workbook");
        String text = Files.readString(file, UTF_8);
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : FORBIDDEN_TEXT) {
          assertThat(lower)
              .as(WHY + ": '" + word + "' in " + root.getFileName() + "/" + rel)
              .doesNotContain(word);
        }
        if (rel.equals("sources.yaml")) {
          checkSources(root, text);
        }
        if (rel.startsWith("tests/golden/") && rel.endsWith(".json")) {
          checkGolden(root, rel, text);
        }
      }
    }
  }

  private static void checkSources(Path root, String text) {
    YamlMap top = (YamlMap) StrictYaml.parse(text);
    YamlNode sources = top.entries().get("sources");
    if (!(sources instanceof YamlList list)) {
      return;
    }
    for (YamlNode item : list.items()) {
      YamlMap source = (YamlMap) item;
      String id = ((YamlScalar) source.entries().get("source_id")).value();
      assertThat(SOURCE_IDS).as(WHY + ": source id in " + root.getFileName()).contains(id);
      if (source.entries().get("provisions") instanceof YamlList provisions) {
        for (YamlNode p : provisions.items()) {
          if (((YamlMap) p).entries().get("quote") instanceof YamlScalar quote) {
            assertThat(quote.value())
                .as(WHY + ": quote of " + id + " in " + root.getFileName())
                .startsWith("SYNTHETIC");
          }
        }
      }
    }
  }

  private static void checkGolden(Path root, String rel, String text) {
    Object tree = Json.parse(text);
    assertThat(tree).isInstanceOf(Map.class);
    Object provenance = ((Map<?, ?>) tree).get("provenance");
    assertThat(provenance)
        .as(WHY + ": provenance of " + root.getFileName() + "/" + rel)
        .isNotNull();
    assertThat(((Map<?, ?>) provenance).get("kind"))
        .as(WHY + ": provenance kind of " + root.getFileName() + "/" + rel)
        .isEqualTo("CONSISTENCY_ONLY");
  }
}
