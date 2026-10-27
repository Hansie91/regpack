package dev.regpack.testkit.contract;

import static dev.regpack.testkit.contract.LedgerFixtures.MIFIR_REGIME;
import static dev.regpack.testkit.contract.LedgerFixtures.commitOneVersion;
import static dev.regpack.testkit.contract.LedgerFixtures.subjectKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.Leg;
import dev.regpack.model.Regime;
import dev.regpack.ports.crypto.Envelope;
import dev.regpack.ports.crypto.Envelope.Header;
import dev.regpack.ports.crypto.Envelope.Kind;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommitted;
import dev.regpack.ports.ledger.ReportLedger.StoredVersion;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract of a {@link LedgerStore} for report bodies that are envelopes (P3c1 D-K6): the ledger
 * treats a body as opaque bytes and stores it unchanged; a ledger that is built to require
 * envelopes refuses every other body before it writes anything. The ledger decides by {@link
 * Envelope#isEnvelope} alone, so a body that only starts like an envelope is refused, and a
 * well-formed header over random bytes is accepted (nothing here holds a key).
 */
public interface EnvelopeBodyLedgerContract {
  /** A fresh, empty ledger in the default configuration (plain bodies are accepted). */
  LedgerStore ledger();

  /** A fresh, empty ledger that is built to require envelope bodies. */
  LedgerStore ledgerRequiringEnvelopes();

  /**
   * A well-formed BODY envelope: the header of a 60-byte wrapped key, a nonce and a sealed text.
   */
  private static byte[] envelopeBody() {
    byte[] wrapped = new byte[60];
    for (int i = 0; i < wrapped.length; i++) {
      wrapped[i] = (byte) i;
    }
    byte[] dataKey = new byte[32];
    Arrays.fill(dataKey, (byte) 7);
    byte[] nonce = new byte[12];
    Arrays.fill(nonce, (byte) 9);
    byte[] body =
        Envelope.seal(
            new Header(Kind.BODY, "kek-2026", wrapped),
            dataKey,
            nonce,
            "context".getBytes(StandardCharsets.UTF_8),
            "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
    assertThat(Envelope.isEnvelope(body)).as("the fixture is an envelope").isTrue();
    return body;
  }

  private static byte[] plainBody() {
    return "{\"personalData\":\"SECRET-PLAIN-TEXT\"}".getBytes(StandardCharsets.UTF_8);
  }

  private static Regime mifir() {
    return new Regime(MIFIR_REGIME);
  }

  private static EventIdentity event() {
    return EventIdentity.of("LEI1", "XPAR", "E1");
  }

  private static void assertStoredUnchanged(LedgerStore ledger, byte[] body) {
    ReportableCommitted committed = commitOneVersion(ledger, body);

    List<StoredVersion> versions = ledger.versions(committed.versions().get(0).subject());
    assertThat(versions).hasSize(1);
    assertThat(versions.get(0).body()).isEqualTo(body);
    assertThat(ledger.subject(subjectKey(MIFIR_REGIME, "E1", Leg.MARKET))).isPresent();
  }

  private static void assertRefusedAndNothingStored(LedgerStore ledger, byte[] body) {
    assertThatThrownBy(() -> commitOneVersion(ledger, body))
        .isInstanceOf(IllegalArgumentException.class)
        .satisfies(
            e -> {
              assertThat(String.valueOf(e.getMessage())).doesNotContain("SECRET-PLAIN-TEXT");
              assertThat(e.toString()).doesNotContain("SECRET-PLAIN-TEXT");
            });
    assertThat(ledger.currentDecision(mifir(), event())).isEmpty();
    assertThat(ledger.decisionChain(mifir(), event())).isEmpty();
    assertThat(ledger.subject(subjectKey(MIFIR_REGIME, "E1", Leg.MARKET))).isEmpty();
    assertThat(ledger.subjectByTrn("EXEC1", "TRN1")).isEmpty();
  }

  @Test
  default void anEnvelopeBodyIsStoredAndReadBackUnchanged() {
    assertStoredUnchanged(ledgerRequiringEnvelopes(), envelopeBody());
  }

  @Test
  default void aPlainBodyIsRefusedWhenEnvelopesAreRequired() {
    LedgerStore ledger = ledgerRequiringEnvelopes();

    assertRefusedAndNothingStored(ledger, plainBody());
    assertRefusedAndNothingStored(ledger, new byte[0]);
    // the refusal wrote nothing, so the same unit with an envelope still commits on an empty chain
    assertStoredUnchanged(ledger, envelopeBody());
  }

  @Test
  default void aPlainBodyIsAcceptedWhenEnvelopesAreNotRequired() {
    assertStoredUnchanged(ledger(), plainBody());
  }

  @Test
  default void aBodyThatOnlyStartsLikeAnEnvelopeIsRefusedWhenRequired() {
    byte[] magic = "RPEN".getBytes(StandardCharsets.US_ASCII);
    byte[] garbage = new byte[200];
    Arrays.fill(garbage, (byte) 0x5a);
    byte[] onlyTheMagic = magic;
    byte[] magicThenGarbage = concat(magic, garbage);
    byte[] goodHeaderThenNothing = Arrays.copyOf(envelopeBody(), 4 + 1 + 1 + 1 + 8 + 2 + 60 + 11);
    byte[] badVersion = envelopeBody();
    badVersion[4] = 2;
    byte[] badKind = envelopeBody();
    badKind[5] = 3;
    byte[] badKekIdChar = envelopeBody();
    badKekIdChar[7] = '/';
    for (byte[] body :
        List.of(
            onlyTheMagic,
            magicThenGarbage,
            goodHeaderThenNothing,
            badVersion,
            badKind,
            badKekIdChar)) {
      assertThat(Envelope.isEnvelope(body))
          .as("the test body is not an envelope by the single test of the ledger")
          .isFalse();
      assertRefusedAndNothingStored(ledgerRequiringEnvelopes(), body);
    }
  }

  @Test
  default void anEnvelopeBodyIsAcceptedByTheDefaultLedgerToo() {
    assertStoredUnchanged(ledger(), envelopeBody());
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }
}
