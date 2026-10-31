package dev.regpack.postgres;

import static dev.regpack.postgres.EnvelopeBodyFixtures.envelope;
import static dev.regpack.postgres.EnvelopeBodyFixtures.plain;
import static dev.regpack.postgres.EnvelopeBodyFixtures.unit;
import static dev.regpack.postgres.EnvelopeBodyFixtures.version;
import static dev.regpack.postgres.EnvelopeBodyFixtures.with;
import static dev.regpack.testkit.contract.LedgerFixtures.reportDecision;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.Leg;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommitted;
import dev.regpack.ports.ledger.ReportLedger.SubjectId;
import dev.regpack.ports.ledger.ReportLedger.VersionResult;
import dev.regpack.postgres.PgLedgerStore.Config;
import dev.regpack.testkit.contract.EnvelopeBodyLedgerContract;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The envelope-body contract (P3c1 D-K6) on a real server, in both configurations: the default
 * ledger stores any body, and the ledger built with {@link Config#production} stores an envelope
 * and refuses every other body before it writes anything. The raw SQL below reads {@code
 * report_version.body_encoding}, which the port does not show: 1 for a body for which {@code
 * Envelope.isEnvelope} holds and 0 for any other, whatever the configuration. Real-store test:
 * skipped without {@code REGPACK_PG_URL}, a failure with {@code REGPACK_PG_REQUIRED=true}. First
 * run at CP-1.
 */
class PgEnvelopeBodiesIT extends AbstractPgStoreTest implements EnvelopeBodyLedgerContract {
  @Override
  public LedgerStore ledger() {
    return store();
  }

  /** A second store over the same data source, clock and schema that requires envelope bodies. */
  @Override
  public LedgerStore ledgerRequiringEnvelopes() {
    return new PgLedgerStore(dataSource(), clock(), Config.production(schema()));
  }

  /** The stored encodings of the versions of a subject, in the order of the version numbers. */
  private List<Short> storedEncodings(SubjectId subject) throws SQLException {
    try (Connection connection = raw();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT body_encoding FROM report_version WHERE subject_id = ?"
                    + " ORDER BY version_seq")) {
      statement.setLong(1, subject.value());
      try (ResultSet rows = statement.executeQuery()) {
        List<Short> found = new ArrayList<>();
        while (rows.next()) {
          found.add(rows.getShort(1));
        }
        return found;
      }
    }
  }

  private static SubjectId subjectOf(ReportableCommitted committed, int version) {
    VersionResult result = committed.versions().get(version);
    return result.subject();
  }

  /** One event on its own, with the TRN {@code trn}, in the default or the strict ledger. */
  private static ReportableCommitted commit(
      LedgerStore ledger, String event, String trn, byte[] body) {
    return ledger
        .commitReportable(
            unit(
                with(
                    reportDecision(event, "ctx", "d-" + event, Leg.MARKET),
                    version(event, Leg.MARKET, trn, body))))
        .get(0);
  }

  @Test
  void aStoredEnvelopeBodyHasBodyEncoding1AndAPlainOneHas0() throws SQLException {
    ReportableCommitted sealed = commit(ledgerRequiringEnvelopes(), "E1", "TRNE1M", envelope());
    ReportableCommitted unsealed = commit(ledger(), "E2", "TRNE2M", plain());

    assertThat(storedEncodings(subjectOf(sealed, 0))).containsExactly((short) 1);
    assertThat(storedEncodings(subjectOf(unsealed, 0))).containsExactly((short) 0);
    // and both come back unchanged through the port, which reads both encodings
    assertThat(ledger().versions(subjectOf(sealed, 0)).get(0).body()).isEqualTo(envelope());
    assertThat(ledger().versions(subjectOf(unsealed, 0)).get(0).body()).isEqualTo(plain());
  }

  /**
   * The mixed unit on the DEFAULT ledger (note T12-1): one decision, an envelope body on the market
   * leg and a plain body on the client leg, so the encoding is decided per version and not per unit
   * or per decision: 1 for the envelope version and 0 for the plain one.
   */
  @Test
  void aMixedUnitOnTheDefaultLedgerStoresOneEncodingPerVersion() throws SQLException {
    LedgerStore standard = ledger();

    ReportableCommitted committed =
        standard
            .commitReportable(
                unit(
                    with(
                        reportDecision("E1", "ctx", "d1", Leg.MARKET, Leg.CLIENT),
                        version("E1", Leg.MARKET, "TRNE1M", envelope()),
                        version("E1", Leg.CLIENT, "TRNE1C", plain()))))
            .get(0);

    assertThat(committed.versions()).hasSize(2);
    // the results come in the order of the request: market, client
    assertThat(storedEncodings(subjectOf(committed, 0))).containsExactly((short) 1);
    assertThat(storedEncodings(subjectOf(committed, 1))).containsExactly((short) 0);
    assertThat(standard.versions(subjectOf(committed, 0)).get(0).body()).isEqualTo(envelope());
    assertThat(standard.versions(subjectOf(committed, 1)).get(0).body()).isEqualTo(plain());
  }
}
