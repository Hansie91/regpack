package dev.regpack.postgres;

import static dev.regpack.testkit.contract.LedgerFixtures.newVersion;

import dev.regpack.model.Leg;
import dev.regpack.ports.crypto.Envelope;
import dev.regpack.ports.crypto.Envelope.Header;
import dev.regpack.ports.crypto.Envelope.Kind;
import dev.regpack.ports.ledger.DecisionLedger.NewDecision;
import dev.regpack.ports.ledger.ReportLedger.NewVersion;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommit;
import dev.regpack.ports.ledger.ReportLedger.ReportableDecision;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Report bodies and units for the tests of the envelope rule of the PostgreSQL ledger (P3c1 T12): a
 * well-formed envelope (the header of a 60-byte wrapped key, a nonce and a sealed text; no key
 * service is involved), a plain body, and the versions and units that carry them. The same shapes
 * as {@code EnvelopeBodyLedgerContract}, whose own fixtures are private.
 */
final class EnvelopeBodyFixtures {
  /** The text that must never reach a message: the plain body holds it. */
  static final String SECRET = "SECRET-PLAIN-TEXT";

  private EnvelopeBodyFixtures() {}

  /** A well-formed BODY envelope. */
  static byte[] envelope() {
    byte[] wrapped = new byte[60];
    for (int i = 0; i < wrapped.length; i++) {
      wrapped[i] = (byte) i;
    }
    byte[] dataKey = new byte[32];
    Arrays.fill(dataKey, (byte) 7);
    byte[] nonce = new byte[12];
    Arrays.fill(nonce, (byte) 9);
    return Envelope.seal(
        new Header(Kind.BODY, "kek-2026", wrapped),
        dataKey,
        nonce,
        "context".getBytes(StandardCharsets.UTF_8),
        "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
  }

  /** A plain JSON body that holds {@link #SECRET}. */
  static byte[] plain() {
    return ("{\"personalData\":\"" + SECRET + "\"}").getBytes(StandardCharsets.UTF_8);
  }

  /** A version of {@code event} and {@code leg} with the TRN {@code trn} and the given body. */
  static NewVersion version(String event, Leg leg, String trn, byte[] body) {
    NewVersion base = newVersion(event, leg, "EXEC-" + event, trn, "NEWT", "h-" + event + leg);
    return new NewVersion(
        base.subject(),
        base.executingLei(),
        base.trn(),
        base.action(),
        base.logicalHash(),
        body,
        base.origin(),
        base.endpoint(),
        base.endpointEpoch());
  }

  static ReportableDecision with(NewDecision decision, NewVersion... versions) {
    return new ReportableDecision(decision, List.of(versions));
  }

  static ReportableCommit unit(ReportableDecision... decisions) {
    return new ReportableCommit(List.of(decisions), Optional.empty(), Optional.empty());
  }
}
