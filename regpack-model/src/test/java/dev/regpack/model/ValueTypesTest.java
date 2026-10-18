package dev.regpack.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ValueTypesTest {
  @Test
  void eventIdentityJoinsPartsWithPipe() {
    assertThat(EventIdentity.of("LEI1", "XPAR", "EX-1").value()).isEqualTo("LEI1|XPAR|EX-1");
    assertThatThrownBy(() -> EventIdentity.of("a|b")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EventIdentity.of("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(EventIdentity::of).isInstanceOf(IllegalArgumentException.class);
    // a blank part is rejected by the per-part check, not only by the blank joined value
    assertThatThrownBy(() -> EventIdentity.of("a", ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EventIdentity.of("a", " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EventIdentity.of(" ", "b"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EventIdentity.of("a", (String) null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void eventIdentityRejectsEmptyAndBlankParts() {
    // M12: a row decoder that builds an identity from a stored value cannot make "a||b"
    for (String bad : new String[] {"a||b", "|a", "a|", " |a", "a| ", "a| |b", "|"}) {
      assertThatThrownBy(() -> new EventIdentity(bad))
          .as(bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(new EventIdentity("a").value()).isEqualTo("a");
    assertThat(new EventIdentity("LEI1|XPAR|EX-1").value()).isEqualTo("LEI1|XPAR|EX-1");
  }

  @Test
  void reasonCodeAcceptsSpecShapes() {
    for (String ok :
        new String[] {
          "MIFIR.N03.EXCLUDED", "MIFIR.N03.EXCLUDED.2_5_e", "EXEC_BUDGET", "ENRICH_MISS"
        }) {
      assertThat(new ReasonCode(ok).value()).isEqualTo(ok);
    }
    for (String bad : new String[] {"mifir.n03", "MIFIR..X", "", "A B"}) {
      assertThatThrownBy(() -> new ReasonCode(bad))
          .as(bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void regimeIdIsLowerCaseDotted() {
    assertThat(new Regime("mifir.eu.rts22").id()).isEqualTo("mifir.eu.rts22");
    assertThatThrownBy(() -> new Regime("MiFIR")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Regime("a..b")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void batchIdCombinesDigestAndSlice() {
    assertThat(BatchId.of(Sha256.ofUtf8("abc"), 3).value())
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad:3");
    assertThatThrownBy(() -> BatchId.of(Sha256.ofUtf8("abc"), -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void batchIdAccessorsParseTheStructuredForm() {
    Sha256 digest = Sha256.ofUtf8("abc");
    BatchId id = BatchId.of(digest, 3);
    assertThat(id.landingDigest()).isEqualTo(digest);
    assertThat(id.slice()).isEqualTo(3);
    assertThat(BatchId.of(digest, 0).slice()).isZero();
    assertThat(BatchId.of(digest, Integer.MAX_VALUE).slice()).isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  void batchIdAccessorsRejectOtherValues() {
    String hex = Sha256.ofUtf8("abc").hex();
    // the constructor stays permissive (an outbox subject id is any non-blank text); the
    // accessors are strict
    for (String bad :
        new String[] {
          "B1",
          hex,
          hex + ":",
          ":3",
          hex + ":-1",
          hex + ":x",
          hex + ":3:4",
          hex.toUpperCase(Locale.ROOT) + ":3",
          hex.substring(1) + ":3",
          hex + ": 3",
          hex + ":03"
        }) {
      BatchId id = new BatchId(bad);
      assertThatThrownBy(id::landingDigest).as(bad).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(id::slice).as(bad).isInstanceOf(IllegalArgumentException.class);
    }
    // a slice beyond int is a bad slice, though the digest part is fine
    BatchId huge = new BatchId(hex + ":99999999999");
    assertThat(huge.landingDigest().hex()).isEqualTo(hex);
    assertThatThrownBy(huge::slice).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void checkpointHasContentEquality() {
    byte[] input = {1, 2};
    var a = new Checkpoint("s", input);
    var b = new Checkpoint("s", new byte[] {1, 2});
    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    input[0] = 9;
    assertThat(a.state()).containsExactly(1, 2);
    a.state()[1] = 9;
    assertThat(a.state()).containsExactly(1, 2);
    assertThat(a).isEqualTo(b);
    assertThat(a.toString()).isEqualTo("Checkpoint[stream=s, stateLength=2]");
    assertThatThrownBy(() -> new Checkpoint(" ", new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ingestKeyRejectsBlankAndNegativeVersion() {
    assertThat(new IngestKey("src", "rec", 0).sourceVersion()).isZero();
    assertThatThrownBy(() -> new IngestKey(" ", "rec", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IngestKey("src", "", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IngestKey("src", "rec", -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void citationRequiresSourceAndPath() {
    var c = new Citation("eu.mifir", "Art 26(2)", CitationRole.BASIS, Optional.empty());
    assertThat(c.sourceId()).isEqualTo("eu.mifir");
    assertThatThrownBy(() -> new Citation(" ", "p", CitationRole.BASIS, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Citation("s", "", CitationRole.BASIS, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
