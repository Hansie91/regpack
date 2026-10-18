package dev.regpack.pack;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.model.DecisionTable;
import dev.regpack.pack.model.LoadResult;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.pack.model.PackLoader;
import dev.regpack.pack.model.TableRow;
import dev.regpack.pack.source.Checksums;
import dev.regpack.pack.source.PackSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The committed sample pack S0 (synthetic; plan task T10). */
class SamplePackTest {
  /**
   * The digest of the sample pack (provisional, G-S3b). A change of any evaluated file changes it;
   * re-pin it only together with a reason in docs/pack-format/decisions.md.
   */
  private static final String SAMPLE_DIGEST =
      "a421f125754b61c1682ab26021611b380e5c97409adb97f125175c1d4ac0b4f6";

  private static Path root() {
    return Path.of(System.getProperty("regpack.repo.root")).resolve("packs").resolve("sample.s0");
  }

  private static LoadedPack load() {
    LoadResult result = PackLoader.load(PackSource.fromDirectory(root()));
    assertThat(result.problems()).as("problems of the sample pack").isEmpty();
    return result.pack().orElseThrow();
  }

  @Test
  void loadsWithoutProblems() {
    LoadResult result = PackLoader.load(PackSource.fromDirectory(root()));
    List<String> problems = new ArrayList<>();
    for (PackProblem p : result.problems()) {
      problems.add(p.code() + " " + p.path() + ":" + p.line() + " " + p.message());
    }
    assertThat(problems).isEmpty();
    assertThat(result.pack()).isPresent();
    LoadedPack pack = result.pack().get();
    assertThat(pack.manifest().id()).isEqualTo("sample.s0");
    assertThat(pack.signed()).isFalse();
  }

  @Test
  void hasFourRowsFourReasonsTwoSourcesAndFourRationales() {
    LoadedPack pack = load();
    assertThat(pack.scopeTables()).containsOnlyKeys("scope/sample-scope.yaml");
    DecisionTable table = pack.scopeTables().get("scope/sample-scope.yaml");
    assertThat(table.id()).isEqualTo("sample.s0.scope");
    assertThat(table.rows().stream().map(TableRow::id).toList())
        .containsExactly(
            "S-N03-novation",
            "S-D14-natural-person",
            "S-N08-equity-on-venue",
            "S-DEFAULT-catch-all");
    assertThat(table.catchAllRowId()).isEqualTo("S-DEFAULT-catch-all");
    assertThat(pack.reasons().prefix()).isEqualTo("SAMPLE");
    assertThat(pack.reasons().nodes()).containsExactlyInAnyOrder("N03", "N08", "N13", "DEFAULT");
    assertThat(pack.reasons().entries()).hasSize(4);
    assertThat(pack.sources().sources()).containsOnlyKeys("sample-law", "regpack-spec");
    assertThat(pack.rationales().texts()).hasSize(4);
    assertThat(pack.input().variables()).hasSize(13);
    assertThat(pack.goldenJson()).hasSize(4);
  }

  @Test
  void goldenCasesNameRowsAndReasonsOfThePack() {
    LoadedPack pack = load();
    List<String> rowIds =
        pack.scopeTables().values().stream()
            .flatMap(t -> t.rows().stream())
            .map(TableRow::id)
            .toList();
    assertThat(pack.goldenJson())
        .containsOnlyKeys(
            "tests/golden/GC-DEFAULT-1.json",
            "tests/golden/GC-N03-1.json",
            "tests/golden/GC-N08-1.json",
            "tests/golden/GC-N13-1.json");
    for (Map.Entry<String, Object> golden : pack.goldenJson().entrySet()) {
      Map<?, ?> tree = (Map<?, ?>) golden.getValue();
      Map<?, ?> expected = (Map<?, ?>) tree.get("expected");
      assertThat(pack.reasons().entries()).containsKey((String) expected.get("reasonCode"));
      List<?> hit = (List<?>) expected.get("ruleIdsHit");
      assertThat(hit).hasSize(1);
      assertThat(rowIds).contains((String) hit.get(0));
      assertThat(((Map<?, ?>) tree.get("provenance")).get("kind")).isEqualTo("CONSISTENCY_ONLY");
      assertThat(tree.get("author")).isEqualTo("agent:golden-author");
    }
  }

  @Test
  void checksumsAreCurrent() throws IOException {
    String committed = Files.readString(root().resolve("CHECKSUMS"), UTF_8);
    assertThat(Checksums.render(PackSource.fromDirectory(root()))).isEqualTo(committed);
    assertThat(committed).doesNotContain("\r");
  }

  @Test
  void digestIsPinned() {
    assertThat(load().digest().hex()).isEqualTo(SAMPLE_DIGEST);
  }
}
