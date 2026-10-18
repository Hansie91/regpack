package dev.regpack.postgres;

import static dev.regpack.testkit.contract.LedgerFixtures.batchId;
import static dev.regpack.testkit.contract.LedgerFixtures.checkpoint;
import static dev.regpack.testkit.contract.LedgerFixtures.commit;
import static dev.regpack.testkit.contract.LedgerFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.IngestKey;
import dev.regpack.model.Sha256;
import dev.regpack.model.decision.DecisionCodec;
import dev.regpack.model.decision.DecisionContext;
import dev.regpack.model.json.Json;
import dev.regpack.ports.ledger.IngestLedger.Commit;
import dev.regpack.ports.ledger.IngestLedger.Entry;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.postgres.PgLedgerStore.Config;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Real-store tests of the adapter as a whole (T15, Convention 4 and 11): what a unit does to the
 * session and to its connection, the instants, the statement count, and the context dictionary
 * reads. Skipped without {@code REGPACK_PG_URL}.
 */
class PgLedgerStoreIT extends AbstractPgStoreTest {
  private static final String SRC = "srcA";

  private static String show(Connection connection, String setting) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("SELECT current_setting(?)")) {
      statement.setString(1, setting);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1);
      }
    }
  }

  /** The advisory lock a commit of this source takes (Convention 12, purpose 3), held by a test. */
  private void holdSourceLock(Connection holder, String source) throws SQLException {
    holder.setAutoCommit(false);
    try (PreparedStatement lock =
        holder.prepareStatement("SELECT pg_advisory_xact_lock(3, hashtext(?))")) {
      lock.setString(1, schema() + ":" + source);
      lock.execute();
    }
  }

  @Test
  void afterAFailedUnitNoConnectionStaysOpen() throws Exception {
    String application = "regpack-it-" + schema();
    PGSimpleDataSource named = new PGSimpleDataSource();
    named.setUrl(System.getenv("REGPACK_PG_URL"));
    named.setApplicationName(application);
    Config impatient = Config.forSchema(schema()).withLockTimeout(Duration.ofMillis(300));
    PgLedgerStore store = new PgLedgerStore(named, clock(), impatient);

    // a commit that cannot get the lock of its source in time fails with the lock timeout
    try (Connection holder = dataSource().getConnection()) {
      holdSourceLock(holder, SRC);
      Commit blocked = commit("F1", SRC, checkpoint(1), entry(SRC, "r1", 1, "A"));
      assertThatThrownBy(() -> store.commitIngest(blocked))
          .isInstanceOf(LedgerException.class)
          .hasMessage("ledger statement failed: sqlstate=55P03 constraint=none");
      holder.rollback();
    }
    // a unit whose work throws leaves nothing behind either
    assertThatThrownBy(
            () ->
                Unit.inUnit(
                    named,
                    impatient,
                    connection -> {
                      show(connection, "TimeZone");
                      throw new IllegalStateException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class);

    try (Connection connection = dataSource().getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE application_name = ? AND state LIKE"
                    + " 'idle in transaction%'")) {
      statement.setString(1, application);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getLong(1)).isZero();
      }
    }
    // the failed commit wrote nothing, not even its batch id: the same commit succeeds now
    assertThat(count("SELECT count(*) FROM ingest_batch")).isZero();
    assertThat(
            store()
                .commitIngest(commit("F1", SRC, checkpoint(1), entry(SRC, "r1", 1, "A")))
                .redelivery())
        .isFalse();
  }

  @Test
  void instantsRoundTripAtMicrosecondPrecision() throws SQLException {
    List<Instant> instants =
        List.of(
            Instant.parse("2026-10-09T23:59:59.999999Z"),
            Instant.parse("2026-10-10T00:00:00.000001Z"),
            Instant.parse("2000-01-01T00:00:00.000000Z"),
            Instant.parse("2199-12-31T23:59:59.999999Z"));
    for (int i = 0; i < instants.size(); i++) {
      clock().set(instants.get(i));
      store().commitIngest(commit("I" + i, SRC, checkpoint(i), entry(SRC, "i" + i, 1, "A")));
    }
    try (Connection connection = raw()) {
      for (int i = 0; i < instants.size(); i++) {
        try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT landed_on FROM ingest WHERE source_record_id = ?")) {
          statement.setString(1, "i" + i);
          try (ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getObject(1, OffsetDateTime.class).toInstant())
                .as("instant %d", i)
                .isEqualTo(instants.get(i));
          }
        }
      }
    }
  }

  @Test
  void sessionDefaultsAreOverriddenInsideAUnit() throws Exception {
    DataSource skewed =
        SqlSpy.withSessionSetup(
            dataSource(),
            "SET synchronous_commit = off",
            "SET TimeZone = 'Pacific/Kiritimati'",
            "SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL SERIALIZABLE");
    Config config = Config.forSchema(schema());
    // the skew is real
    try (Connection connection = skewed.getConnection()) {
      assertThat(show(connection, "synchronous_commit")).isEqualTo("off");
      assertThat(show(connection, "TimeZone")).isEqualTo("Pacific/Kiritimati");
      assertThat(show(connection, "transaction_isolation")).isEqualTo("serializable");
    }
    List<String> inside =
        Unit.inUnit(
            skewed,
            config,
            connection ->
                List.of(
                    show(connection, "synchronous_commit"),
                    show(connection, "TimeZone"),
                    show(connection, "transaction_isolation"),
                    show(connection, "search_path"),
                    show(connection, "lock_timeout"),
                    show(connection, "statement_timeout")));
    assertThat(inside).containsExactly("on", "UTC", "read committed", schema(), "5s", "1min");
    // SET LOCAL ends with the transaction: the session is as the pool left it
    try (Connection connection = skewed.getConnection()) {
      Unit.begin(connection, config);
      assertThat(show(connection, "synchronous_commit")).isEqualTo("on");
      connection.rollback();
      connection.setAutoCommit(true);
      assertThat(show(connection, "synchronous_commit")).isEqualTo("off");
      assertThat(show(connection, "TimeZone")).isEqualTo("Pacific/Kiritimati");
      assertThat(show(connection, "transaction_isolation")).isEqualTo("serializable");
    }
    // and the adapter works through such sessions
    PgLedgerStore through = new PgLedgerStore(skewed, clock(), config);
    through.commitIngest(commit("K1", SRC, checkpoint(1), entry(SRC, "r1", 1, "A")));
    assertThat(store().findIngest(new IngestKey(SRC, "r1", 1))).isPresent();
  }

  @Test
  void aUnitOfOneThousandIndependentRowsUsesAtMostSixtyStatements() {
    SqlSpy spy = new SqlSpy();
    PgLedgerStore counted =
        new PgLedgerStore(spy.over(dataSource()), clock(), Config.forSchema(schema()));
    List<Entry> entries = new ArrayList<>();
    for (int i = 0; i < 1_000; i++) {
      entries.add(entry(SRC, "row-" + i, 1, "content"));
    }
    counted.commitIngest(new Commit(batchId("ROWS"), SRC, entries, Optional.of(checkpoint(1))));
    System.out.println("statements for one ingest unit of 1,000 entries: " + spy.statements());
    assertThat(spy.statements()).isPositive().isLessThanOrEqualTo(60);
    assertThat(spy.openConnections()).isZero();
  }

  @Test
  void aStubContextRowReadsAsEmptyAndSoDoesAnUnknownId() throws SQLException {
    Sha256 stub = Sha256.ofUtf8("context-stub");
    try (Connection connection = raw();
        PreparedStatement insert =
            connection.prepareStatement(
                "INSERT INTO decision_context (context_sha) VALUES (decode(?, 'hex'))")) {
      insert.setString(1, stub.hex());
      insert.executeUpdate();
    }
    assertThat(store().decisionContext(stub)).isEmpty();
    assertThat(store().decisionContext(Sha256.ofUtf8("context-unknown"))).isEmpty();
  }

  @Test
  void aStoredContextReadsBackEqual() throws SQLException {
    DecisionContext context = sampleContext(3L);
    insertContext(context.id(), context);
    assertThat(store().decisionContext(context.id())).contains(context);
    DecisionContext epochZero = sampleContext(0L);
    insertContext(epochZero.id(), epochZero);
    assertThat(store().decisionContext(epochZero.id())).contains(epochZero);
  }

  @Test
  void aContextWhoseBodyDoesNotMatchItsIdFailsClosed() throws SQLException {
    DecisionContext context = sampleContext(1L);
    Sha256 wrongId = Sha256.ofUtf8("some other id");
    insertContext(wrongId, context);
    assertThatThrownBy(() -> store().decisionContext(wrongId)).isInstanceOf(LedgerException.class);
  }

  private static DecisionContext sampleContext(long epoch) {
    return new DecisionContext(
        Sha256.ofUtf8("pack"),
        Optional.of(Sha256.ofUtf8("overlay")),
        List.of("pin-1", "pin-2"),
        List.of("ref-1"),
        List.of("enr-1"),
        "fp-1",
        "map-1",
        "b1",
        "interpreter",
        epoch);
  }

  /** Writes the row as the decision commit of T16 does, under the id that is given. */
  private void insertContext(Sha256 id, DecisionContext context) throws SQLException {
    try (Connection connection = raw();
        PreparedStatement insert =
            connection.prepareStatement(
                "INSERT INTO decision_context (context_sha, pack_digest, circuit_epoch,"
                    + " engine_build, body) VALUES (decode(?, 'hex'), decode(?, 'hex'), ?, ?, ?)")) {
      insert.setString(1, id.hex());
      insert.setString(2, context.packDigest().hex());
      insert.setLong(3, context.circuitEpoch());
      insert.setString(4, context.engineBuild());
      insert.setString(5, Json.canonical(DecisionCodec.contextToMap(context)));
      insert.executeUpdate();
    }
  }
}
