package dev.regpack.ports.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.BatchId;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.Leg;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptIntent;
import dev.regpack.ports.ledger.DeliveryLedger.NewDelivery;
import dev.regpack.ports.ledger.IngestLedger.Commit;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.ReportLedger.NewVersion;
import dev.regpack.ports.ledger.ReportLedger.SubjectKey;
import dev.regpack.ports.ledger.ReportLedger.TrnProposal;
import dev.regpack.ports.ledger.ReportLedger.VersionOrigin;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** FP2: the records of the ports apply the key text rule of the model to their texts. */
class LedgerKeyTextRecordsTest {
  private static final EndpointKey EP = new EndpointKey("SIMULATOR", "ARM", "LEI-EP1", "NCA-EP1");
  private static final String NUL = String.valueOf((char) 0);
  private static final String LONE = String.valueOf((char) 0xD800);
  private static final String TAB = String.valueOf((char) 9);

  private static NewVersion version(String lei, String action, String keyId, EndpointKey endpoint) {
    return new NewVersion(
        new SubjectKey(
            new Regime("mifir.eu.rts22"), EventIdentity.of("LEI1", "XPAR", "E1"), Leg.MARKET),
        lei,
        Optional.of(new TrnProposal("TRN1", keyId)),
        action,
        Sha256.ofUtf8("h"),
        new byte[] {1},
        VersionOrigin.SOURCE,
        endpoint,
        1);
  }

  @Test
  void anEndpointKeyBoundsEachOfItsFourTextsAtTheirOwnLimit() {
    assertThat(new EndpointKey("e".repeat(128), "r".repeat(128), "l".repeat(128), "a".repeat(128)))
        .isNotNull();
    String[] bad = {"a" + NUL + "b", "a" + LONE, "x".repeat(129)};
    for (String text : bad) {
      assertThatThrownBy(() -> new EndpointKey(text, "r", "l", "a"))
          .isInstanceOf(IllegalArgumentException.class)
          .message()
          .doesNotContain(text);
      assertThatThrownBy(() -> new EndpointKey("e", text, "l", "a"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new EndpointKey("e", "r", text, "a"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new EndpointKey("e", "r", "l", text))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new EndpointKey(null, "r", "l", "a"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aNewVersionBoundsTheLeiTheActionAndTheKeyId() {
    assertThat(version("l".repeat(256), "a".repeat(256), "k".repeat(256), EP)).isNotNull();
    for (String text : new String[] {"a" + NUL, "a" + LONE, "x".repeat(257), "a" + TAB + "b"}) {
      assertThatThrownBy(() -> version(text, "NEWT", "KEY-1", EP))
          .isInstanceOf(IllegalArgumentException.class)
          .message()
          .doesNotContain(text);
      assertThatThrownBy(() -> version("LEI", text, "KEY-1", EP))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> version("LEI", "NEWT", text, EP))
          .isInstanceOf(IllegalArgumentException.class);
    }
    // blank stays the blank error
    assertThatThrownBy(() -> version(" ", "NEWT", "KEY-1", EP))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("executingLei must not be blank");
  }

  @Test
  void aBusinessKeyAndAFileNameAreBounded() {
    assertThat(new NewDelivery(EP, 1, "b".repeat(512), 1)).isNotNull();
    for (String text : new String[] {"a" + NUL, "a" + LONE, "x".repeat(513)}) {
      assertThatThrownBy(() -> new NewDelivery(EP, 1, text, 1))
          .isInstanceOf(IllegalArgumentException.class)
          .message()
          .doesNotContain(text);
    }
    // a blank key is still refused by createDelivery (M49), not by the record
    assertThat(new NewDelivery(EP, 1, " ", 1).businessKey()).isEqualTo(" ");
    assertThat(new AttemptIntent(Sha256.ofUtf8("a"), "f".repeat(256))).isNotNull();
    for (String text : new String[] {"a" + NUL, "a" + LONE, "x".repeat(257)}) {
      assertThatThrownBy(() -> new AttemptIntent(Sha256.ofUtf8("a"), text))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void aCommitBoundsItsSourceIdAndLeavesBlankToTheAdapters() {
    BatchId batch = BatchId.of(Sha256.ofUtf8("b"), 0);
    assertThat(new Commit(batch, "s".repeat(256), List.of(), Optional.empty())).isNotNull();
    for (String text : new String[] {"a" + NUL, "a" + LONE, "x".repeat(257)}) {
      assertThatThrownBy(() -> new Commit(batch, text, List.of(), Optional.empty()))
          .isInstanceOf(IllegalArgumentException.class)
          .message()
          .doesNotContain(text);
    }
    // M39: every blank source id, control whitespace included, is built and then refused by commit
    assertThat(new Commit(batch, TAB, List.of(), Optional.empty()).sourceId()).isEqualTo(TAB);
  }

  @Test
  void theMaximumTtlIsOneHundredYears() {
    assertThat(LeaseLedger.MAX_TTL).isEqualTo(Duration.ofDays(36_500));
  }
}
