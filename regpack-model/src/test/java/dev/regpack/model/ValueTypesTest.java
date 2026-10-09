package dev.regpack.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
