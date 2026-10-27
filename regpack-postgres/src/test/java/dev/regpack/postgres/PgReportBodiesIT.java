package dev.regpack.postgres;

import static dev.regpack.postgres.EnvelopeBodyFixtures.envelope;
import static dev.regpack.postgres.EnvelopeBodyFixtures.plain;
import static dev.regpack.postgres.EnvelopeBodyFixtures.unit;
import static dev.regpack.postgres.EnvelopeBodyFixtures.version;
import static dev.regpack.postgres.EnvelopeBodyFixtures.with;
import static dev.regpack.testkit.contract.LedgerFixtures.reportDecision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Leg;
import dev.regpack.ports.crypto.Envelope;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommitted;
import dev.regpack.ports.ledger.ReportLedger.SubjectId;
import dev.regpack.postgres.PgLedgerStore.Config;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The stored encoding of a report body and the read of it, on a real server, where the contract
 * suites (they see bodies through the port only) and {@code PgEnvelopeBodiesIT} (an envelope and a
 * plain JSON body) leave three cases open: a body that only looks like an envelope, a stored
 * encoding that this code never writes, and the rows of a request that is refused for its body.
 * {@code report_version.body_encoding} is 1 exactly when {@code Envelope.isEnvelope} holds (P3c1
 * D-K6), by that test alone and in every configuration. Real-store test: skipped without {@code
 * REGPACK_PG_URL}, a failure with {@code REGPACK_PG_REQUIRED=true}.
 */
class PgReportBodiesIT extends AbstractPgStoreTest {
  /** The first four bytes of an envelope followed by bytes that are not one. */
  private static byte[] looksLikeAnEnvelope() {
    byte[] magic = "RPEN".getBytes(StandardCharsets.US_ASCII);
    byte[] body = Arrays.copyOf(magic, 204);
    Arrays.fill(body, 4, body.length, (byte) 0x5a);
    return body;
  }

  /** A well-formed envelope of the OBJECT kind: the ledger decides by isEnvelope alone. */
  private static byte[] objectKindEnvelope() {
    byte[] body = envelope();
    body[5] = 1;
    return body;
  }

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

  private ReportableCommitted commit(String event, String trn, byte[] body) {
    return store()
        .commitReportable(
            unit(
                with(
                    reportDecision(event, "ctx", "d-" + event, Leg.MARKET),
                    version(event, Leg.MARKET, trn, body))))
        .get(0);
  }

  @Test
  void theEncodingIsOneForAnythingThatIsAnEnvelopeAndZeroForAnythingElse() throws SQLException {
    assertThat(Envelope.isEnvelope(looksLikeAnEnvelope())).isFalse();
    assertThat(Envelope.isEnvelope(objectKindEnvelope())).isTrue();

    ReportableCommitted lookAlike = commit("E1", "TRNE1M", looksLikeAnEnvelope());
    ReportableCommitted objectKind = commit("E2", "TRNE2M", objectKindEnvelope());
    ReportableCommitted empty = commit("E3", "TRNE3M", new byte[0]);
    ReportableCommitted sealed = commit("E4", "TRNE4M", envelope());

    SubjectId lookAlikeSubject = lookAlike.versions().get(0).subject();
    SubjectId objectKindSubject = objectKind.versions().get(0).subject();
    SubjectId emptySubject = empty.versions().get(0).subject();
    SubjectId sealedSubject = sealed.versions().get(0).subject();
    assertThat(storedEncodings(lookAlikeSubject))
        .as("four bytes of magic are not an envelope")
        .containsExactly((short) 0);
    assertThat(storedEncodings(objectKindSubject))
        .as("an envelope of the OBJECT kind passes the test of the ledger")
        .containsExactly((short) 1);
    assertThat(storedEncodings(emptySubject)).as("an empty body").containsExactly((short) 0);
    assertThat(storedEncodings(sealedSubject)).containsExactly((short) 1);
    // the stored bytes are the bytes given, whatever the encoding
    assertThat(store().versions(lookAlikeSubject).get(0).body()).isEqualTo(looksLikeAnEnvelope());
    assertThat(store().versions(objectKindSubject).get(0).body()).isEqualTo(objectKindEnvelope());
    assertThat(store().versions(emptySubject).get(0).body()).isEmpty();
  }

  /**
   * The refusal comes before the first statement, not only before the first row of the unit: the
   * partitions and the dictionary rows (regime, context, endpoint) of a request are written by
   * statements of their own before the unit starts, and a plain body in a request that is refused
   * leaves none of them behind. The other tests of the refusal see the ledger through the port,
   * where those rows are invisible.
   */
  @Test
  void aRefusedPlainBodyLeavesNoPartitionAndNoDictionaryRowBehind() throws SQLException {
    PgLedgerStore strict = new PgLedgerStore(dataSource(), clock(), Config.production(schema()));
    List<Long> before = footprint();

    assertThatThrownBy(
            () ->
                strict.commitReportable(
                    unit(
                        with(
                            reportDecision("E9", "ctx9", "d9", Leg.MARKET),
                            version("E9", Leg.MARKET, "TRNE9M", plain())))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("a report body must be an envelope");
    assertThat(footprint()).as("regime, context, endpoint, partitions").isEqualTo(before);

    // the same request with an envelope does write them, so the counts above can tell
    strict.commitReportable(
        unit(
            with(
                reportDecision("E9", "ctx9", "d9", Leg.MARKET),
                version("E9", Leg.MARKET, "TRNE9M", envelope()))));
    List<Long> after = footprint();
    for (int i = 0; i < before.size(); i++) {
      assertThat(after.get(i)).as("count %d", i).isGreaterThan(before.get(i));
    }
  }

  /** Rows of regime, decision_context and endpoint, and the partitions of decision. */
  private List<Long> footprint() throws SQLException {
    return List.of(
        count("SELECT count(*) FROM regime"),
        count("SELECT count(*) FROM decision_context"),
        count("SELECT count(*) FROM endpoint"),
        count(
            "SELECT count(*) FROM pg_inherits"
                + " WHERE inhparent = to_regclass('"
                + schema()
                + ".decision')"));
  }

  @Test
  void aStoredEncodingThatTheLedgerNeverWritesIsRefusedWhenTheVersionsAreRead()
      throws SQLException {
    ReportableCommitted committed = commit("E1", "TRNE1M", envelope());
    SubjectId subject = committed.versions().get(0).subject();
    assertThat(store().versions(subject)).hasSize(1);

    // report_version is append-only (a trigger refuses UPDATE): lift it for the test schema only
    for (int unknown : new int[] {2, 7, -1, 32_767}) {
      try (Connection connection = raw();
          Statement statement = connection.createStatement()) {
        statement.execute("ALTER TABLE report_version DISABLE TRIGGER report_version_immutable");
        statement.execute("UPDATE report_version SET body_encoding = " + unknown);
        statement.execute("ALTER TABLE report_version ENABLE TRIGGER report_version_immutable");
      }
      assertThatThrownBy(() -> store().versions(subject))
          .as("body_encoding %d", unknown)
          .isInstanceOf(LedgerException.class)
          .hasMessage("a stored report version has an unknown body encoding");
    }
    // both known encodings are read back
    for (int known : new int[] {0, 1}) {
      try (Connection connection = raw();
          Statement statement = connection.createStatement()) {
        statement.execute("ALTER TABLE report_version DISABLE TRIGGER report_version_immutable");
        statement.execute("UPDATE report_version SET body_encoding = " + known);
        statement.execute("ALTER TABLE report_version ENABLE TRIGGER report_version_immutable");
      }
      assertThat(store().versions(subject)).as("body_encoding %d", known).hasSize(1);
    }
  }
}
