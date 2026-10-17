package dev.regpack.engine.expr.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.engine.expr.Library;
import dev.regpack.engine.pack.EngineInfo;
import dev.regpack.model.Sha256;
import org.junit.jupiter.api.Test;

/**
 * Open item OI-5: a ruling, a vector or a cost fixture cannot change without a version bump. The
 * fingerprint covers the three data files; the pair beside it is the version that was current when
 * the fingerprint was pinned.
 */
class SemanticsFingerprintTest {
  /** SHA-256 of the three files (see {@link #fingerprint}); re-pinned only with a version bump. */
  private static final String PINNED =
      "4ffe461683fad4b3fd2ea69bcd8bf24b199b06b53e26160487154408a4b908c2";

  /** The semantics major and library version that go with {@link #PINNED}. */
  private static final int PINNED_SEMANTICS_MAJOR = 1;

  private static final int PINNED_LIBRARY_VERSION = 1;

  static String fingerprint() {
    StringBuilder all = new StringBuilder();
    append(all, "rulings.tsv", RulingsFile.text());
    append(all, "vectors.tsv", VectorFile.resourceText(VectorFile.RESOURCE));
    append(all, "cost-fixtures.tsv", VectorFile.resourceText(CostFixtures.RESOURCE));
    return Sha256.ofUtf8(all.toString()).hex();
  }

  private static void append(StringBuilder all, String name, String content) {
    all.append(name).append('\n').append(content.replace("\r\n", "\n"));
  }

  @Test
  void fingerprintMatchesThePinnedValue() {
    String actual = fingerprint();
    System.out.println("semantics fingerprint: " + actual);
    String message =
        "a ruling, a vector or a cost fixture changed: bump EngineInfo.SEMANTICS_MAJOR (decisions"
            + " can change) or Library.VERSION (a library function changed), then update these"
            + " pinned values in the same commit";
    assertThat(actual).as(message).isEqualTo(PINNED);
    assertThat(EngineInfo.SEMANTICS_MAJOR).as(message).isEqualTo(PINNED_SEMANTICS_MAJOR);
    assertThat(Library.VERSION).as(message).isEqualTo(PINNED_LIBRARY_VERSION);
  }

  @Test
  void fingerprintDependsOnEachFileAndIgnoresLineEndings() {
    String base = fingerprint();
    assertThat(base).matches("[0-9a-f]{64}");
    StringBuilder crlf = new StringBuilder();
    append(crlf, "rulings.tsv", RulingsFile.text().replace("\n", "\r\n"));
    append(crlf, "vectors.tsv", VectorFile.resourceText(VectorFile.RESOURCE));
    append(crlf, "cost-fixtures.tsv", VectorFile.resourceText(CostFixtures.RESOURCE));
    assertThat(Sha256.ofUtf8(crlf.toString()).hex()).isEqualTo(base);
    StringBuilder changed = new StringBuilder();
    append(changed, "rulings.tsv", RulingsFile.withValue(RulingsFile.text(), "D13", "DENY"));
    append(changed, "vectors.tsv", VectorFile.resourceText(VectorFile.RESOURCE));
    append(changed, "cost-fixtures.tsv", VectorFile.resourceText(CostFixtures.RESOURCE));
    assertThat(Sha256.ofUtf8(changed.toString()).hex()).isNotEqualTo(base);
  }
}
