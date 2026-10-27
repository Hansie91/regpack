package dev.regpack.inmemory.crypto;

import static dev.regpack.inmemory.crypto.CryptoFixtures.APPLICATION;
import static dev.regpack.inmemory.crypto.CryptoFixtures.clockAt;
import static dev.regpack.inmemory.crypto.CryptoFixtures.entropy;
import static dev.regpack.inmemory.crypto.CryptoFixtures.kek;
import static dev.regpack.inmemory.crypto.CryptoFixtures.kms;
import static dev.regpack.testkit.contract.LedgerFixtures.MIFIR_REGIME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.inmemory.InMemoryKms;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.model.Leg;
import dev.regpack.ports.crypto.BodyCipher;
import dev.regpack.ports.crypto.BodyContext;
import dev.regpack.ports.crypto.DataShreddedException;
import dev.regpack.ports.crypto.Envelope;
import dev.regpack.ports.crypto.KekSelector;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommitted;
import dev.regpack.ports.ledger.ReportLedger.StoredVersion;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.LedgerFixtures;
import org.junit.jupiter.api.Test;

/**
 * A report body sealed by {@link BodyCipher} goes through the in-memory ledger built to require
 * envelopes: what the writer seals is what the ledger stores, and destroying the KEK shreds the
 * body and nothing else.
 */
class BodyCipherLedgerTest {
  private static final String MORNING = "2026-06-15T08:00:00Z";
  private static final byte[] PLAIN = "{\"name\":\"Jane Q. Public\"}".getBytes(UTF_8);

  /** The context of the one version that {@link LedgerFixtures#commitOneVersion} requests. */
  private static BodyContext context() {
    return BodyContext.of(LedgerFixtures.oneVersion(new byte[0]));
  }

  private static BodyCipher cipher(InMemoryKms kms, ManualClock clock) {
    return new BodyCipher(kms.as(APPLICATION), KekSelector.perUtcYear("kek"), entropy(), clock);
  }

  private static StoredVersion theVersion(InMemoryLedgerStore ledger, ReportableCommitted c) {
    return ledger.versions(c.versions().get(0).subject()).get(0);
  }

  @Test
  void aSealedBodyCommitsReadsBackAndOpens() {
    InMemoryKms raw = kms();
    ManualClock clock = clockAt(MORNING);
    BodyCipher cipher = cipher(raw, clock);
    InMemoryLedgerStore ledger = new InMemoryLedgerStore(clock).requireEnvelopeBodies(true);
    byte[] envelope = cipher.seal(context(), PLAIN);

    ReportableCommitted committed = LedgerFixtures.commitOneVersion(ledger, envelope);

    StoredVersion stored = theVersion(ledger, committed);
    assertThat(stored.body()).isEqualTo(envelope);
    assertThat(stored.logicalHash()).isEqualTo(context().logicalHash());
    assertThat(new String(stored.body(), UTF_8)).doesNotContain("Jane");
    assertThat(cipher.open(context(), stored.body())).isEqualTo(PLAIN);
    // a reader that shares only the KMS, in a later year, opens it too
    assertThat(Envelope.header(stored.body()).kekId()).isEqualTo("kek-2026");
    BodyCipher reader = cipher(raw, clockAt("2027-03-01T00:00:00Z"));
    assertThat(reader.open(context(), stored.body())).isEqualTo(PLAIN);
  }

  @Test
  void afterTheKekIsDestroyedTheVersionStaysReadableButItsBodyIsShredded() {
    InMemoryKms raw = kms();
    ManualClock clock = clockAt(MORNING);
    BodyCipher cipher = cipher(raw, clock);
    InMemoryLedgerStore ledger = new InMemoryLedgerStore(clock).requireEnvelopeBodies(true);
    byte[] envelope = cipher.seal(context(), PLAIN);
    ReportableCommitted committed = LedgerFixtures.commitOneVersion(ledger, envelope);
    assertThat(cipher.open(context(), theVersion(ledger, committed).body())).isEqualTo(PLAIN);

    raw.destroy(kek(2026));

    StoredVersion stored = theVersion(ledger, committed);
    assertThat(stored.body()).as("the ledger keeps the bytes").isEqualTo(envelope);
    assertThat(stored.logicalHash()).isEqualTo(context().logicalHash());
    assertThat(stored.action()).isEqualTo("NEWT");
    assertThat(ledger.subject(LedgerFixtures.subjectKey(MIFIR_REGIME, "E1", Leg.MARKET)))
        .isPresent();
    assertThat(ledger.subjectByTrn("EXEC1", "TRN1")).isPresent();
    // the same instance that sealed the body: it has no cache of the key it unwrapped
    assertThatThrownBy(() -> cipher.open(context(), stored.body()))
        .isInstanceOf(DataShreddedException.class);
  }
}
