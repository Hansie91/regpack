package dev.regpack.testkit.contract;

import static dev.regpack.testkit.contract.LedgerFixtures.MIFIR_REGIME;
import static dev.regpack.testkit.contract.LedgerFixtures.commitOneVersion;
import static dev.regpack.testkit.contract.LedgerFixtures.newVersion;
import static dev.regpack.testkit.contract.LedgerFixtures.reportDecision;
import static dev.regpack.testkit.contract.LedgerFixtures.subjectKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.Leg;
import dev.regpack.model.Regime;
import dev.regpack.ports.crypto.Envelope;
import dev.regpack.ports.crypto.Envelope.Header;
import dev.regpack.ports.crypto.Envelope.Kind;
import dev.regpack.ports.ledger.DecisionLedger.NewDecision;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.ReportLedger.NewVersion;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommit;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommitted;
import dev.regpack.ports.ledger.ReportLedger.ReportableDecision;
import dev.regpack.ports.ledger.ReportLedger.StoredVersion;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
            magic, magicThenGarbage, goodHeaderThenNothing, badVersion, badKind, badKekIdChar)) {
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

  /**
   * A version of {@code event} and {@code leg} with the given TRN and body: its executing LEI is
   * {@code EXEC-<event>}.
   */
  private static NewVersion version(String event, Leg leg, String trn, byte[] body) {
    NewVersion plain = newVersion(event, leg, "EXEC-" + event, trn, "NEWT", "h-" + event + leg);
    return new NewVersion(
        plain.subject(),
        plain.executingLei(),
        plain.trn(),
        plain.action(),
        plain.logicalHash(),
        body,
        plain.origin(),
        plain.endpoint(),
        plain.endpointEpoch());
  }

  private static ReportableDecision with(NewDecision decision, NewVersion... versions) {
    return new ReportableDecision(decision, List.of(versions));
  }

  private static ReportableCommit unit(ReportableDecision... decisions) {
    return new ReportableCommit(List.of(decisions), Optional.empty(), Optional.empty());
  }

  /**
   * One plain body anywhere in a unit refuses the whole unit: the second leg of a decision, the
   * second decision of a unit, in either order. A check of the first version only, or of the first
   * decision only, or one placed after the first write, passes the other contract methods (each
   * commits a unit of one decision with one version) and fails this one.
   */
  @Test
  default void aPlainBodyAnywhereInAUnitRefusesTheWholeUnitAndWritesNothing() {
    LedgerStore ledger = ledgerRequiringEnvelopes();
    NewDecision bothLegs = reportDecision("E1", "ctx", "d1", Leg.MARKET, Leg.CLIENT);
    NewDecision marketOnly = reportDecision("E1", "ctx", "d1", Leg.MARKET);
    NewDecision event2 = reportDecision("E2", "ctx", "d2", Leg.MARKET);
    NewVersion e1Market = version("E1", Leg.MARKET, "TRNE1M", envelopeBody());
    NewVersion e1ClientPlain = version("E1", Leg.CLIENT, "TRNE1C", plainBody());
    NewVersion e1ClientEnvelope = version("E1", Leg.CLIENT, "TRNE1C", envelopeBody());
    NewVersion e2Plain = version("E2", Leg.MARKET, "TRNE2M", plainBody());
    NewVersion e2Envelope = version("E2", Leg.MARKET, "TRNE2M", envelopeBody());
    EventIdentity event2Identity = EventIdentity.of("LEI1", "XPAR", "E2");
    List<ReportableCommit> refusedUnits =
        List.of(
            unit(with(bothLegs, e1Market, e1ClientPlain)),
            unit(with(bothLegs, e1ClientPlain, e1Market)),
            unit(with(marketOnly, e1Market), with(event2, e2Plain)),
            unit(with(event2, e2Plain), with(marketOnly, e1Market)));

    for (int i = 0; i < refusedUnits.size(); i++) {
      ReportableCommit refused = refusedUnits.get(i);
      assertThatThrownBy(() -> ledger.commitReportable(refused))
          .as("refused unit %d", i)
          .isInstanceOf(IllegalArgumentException.class)
          .satisfies(
              e -> {
                assertThat(String.valueOf(e.getMessage())).doesNotContain("SECRET-PLAIN-TEXT");
                assertThat(e.toString()).doesNotContain("SECRET-PLAIN-TEXT");
              });
      assertThat(ledger.currentDecision(mifir(), event())).as("unit %d", i).isEmpty();
      assertThat(ledger.currentDecision(mifir(), event2Identity)).as("unit %d", i).isEmpty();
      assertThat(ledger.subject(subjectKey(MIFIR_REGIME, "E1", Leg.MARKET)))
          .as("unit %d", i)
          .isEmpty();
      assertThat(ledger.subject(subjectKey(MIFIR_REGIME, "E1", Leg.CLIENT)))
          .as("unit %d", i)
          .isEmpty();
      assertThat(ledger.subject(subjectKey(MIFIR_REGIME, "E2", Leg.MARKET)))
          .as("unit %d", i)
          .isEmpty();
      assertThat(ledger.subjectByTrn("EXEC-E1", "TRNE1M")).as("unit %d", i).isEmpty();
      assertThat(ledger.subjectByTrn("EXEC-E2", "TRNE2M")).as("unit %d", i).isEmpty();
    }

    // so the refusals were for the plain bodies and not for another reason: the same ledger takes
    // the units with envelopes, a fresh one takes the two legs, and the default ledger takes a
    // plain
    // body in the second leg
    assertThat(ledger.commitReportable(unit(with(marketOnly, e1Market), with(event2, e2Envelope))))
        .hasSize(2);
    assertThat(ledger.subject(subjectKey(MIFIR_REGIME, "E1", Leg.MARKET))).isPresent();
    assertThat(ledger.subject(subjectKey(MIFIR_REGIME, "E2", Leg.MARKET))).isPresent();
    assertThat(
            ledgerRequiringEnvelopes()
                .commitReportable(unit(with(bothLegs, e1Market, e1ClientEnvelope))))
        .hasSize(1);
    assertThat(ledger().commitReportable(unit(with(bothLegs, e1Market, e1ClientPlain)))).hasSize(1);
  }

  /**
   * A decision that is delivered again is decided per version by some adapters (the version exists
   * already, or it is unchanged): the envelope rule is checked before, and a plain body is refused
   * also then.
   */
  @Test
  default void aPlainBodyIsRefusedAlsoWhenTheDecisionIsRedelivered() {
    LedgerStore ledger = ledgerRequiringEnvelopes();
    ReportableCommitted first = commitOneVersion(ledger, envelopeBody());

    assertThatThrownBy(() -> commitOneVersion(ledger, plainBody()))
        .isInstanceOf(IllegalArgumentException.class);

    List<StoredVersion> versions = ledger.versions(first.versions().get(0).subject());
    assertThat(versions).hasSize(1);
    assertThat(versions.get(0).body()).isEqualTo(envelopeBody());
    assertThat(commitOneVersion(ledger, envelopeBody()).versions()).hasSize(1);
  }

  /**
   * The ledger decides by {@link Envelope#isEnvelope} alone, which accepts both kinds: it cannot
   * authenticate, and {@code BodyCipher.open} rejects an envelope of the wrong kind later. If the
   * owner ever prefers a ledger that takes BODY envelopes only, both adapters and this test change
   * together.
   */
  @Test
  default void anObjectKindEnvelopeIsAcceptedAsABodyBecauseTheLedgerDecidesByIsEnvelopeAlone() {
    byte[] objectKind = envelopeBody();
    objectKind[5] = 1;
    assertThat(Envelope.isEnvelope(objectKind))
        .as("an envelope of the OBJECT kind passes the single test of the ledger")
        .isTrue();

    assertStoredUnchanged(ledgerRequiringEnvelopes(), objectKind);
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }
}
