package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.crypto.SubjectHash;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.SubjectIndexLedger.RefKind;
import dev.regpack.ports.ledger.SubjectIndexLedger.SubjectEntry;
import dev.regpack.ports.ledger.SubjectIndexLedger.SubjectRef;
import dev.regpack.postgres.PgLedgerStore.Config;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * What the data-subject index does on a real server that its contract suite and its statement tests
 * do not show: the durability barrier (D-K8, KF22) by the lock it takes, the time stored with a
 * row, the strict start of a page, a stored row that is not a reference, the key and the index of
 * the table as designed, and the order of the writes that keeps two callers from waiting for each
 * other. The barrier cannot be shown by a standby here (there is none), but it can be shown by what
 * it needs: {@code Unit.durabilityBarrier} takes a share lock on the one row of {@code
 * restore_epoch}, and a connection that holds that row for update makes every call that takes the
 * barrier wait and end by the lock timeout (SQLSTATE 55P03), while a call that writes a row does
 * not wait. Real-store test: skipped without {@code REGPACK_PG_URL}, a failure with {@code
 * REGPACK_PG_REQUIRED=true}.
 */
class PgSubjectIndexIT extends AbstractPgStoreTest {
  private static final Instant START = Instant.parse("2026-10-09T08:00:00Z");

  private static SubjectHash hash(int n) {
    String digits = Integer.toHexString(n);
    return new SubjectHash("0".repeat(64 - digits.length()) + digits);
  }

  private static SubjectRef report(String ref) {
    return new SubjectRef(RefKind.REPORT_VERSION, ref);
  }

  private static SubjectRef object(String ref) {
    return new SubjectRef(RefKind.EVIDENCE_OBJECT, ref);
  }

  private static SubjectEntry entry(int hash, SubjectRef ref) {
    return new SubjectEntry(hash(hash), ref);
  }

  private List<SubjectRef> refsOf(PgLedgerStore store, int hash) {
    return store.subjectRefs(hash(hash), Optional.empty(), 1_000);
  }

  /** The same schema and clock with a lock timeout of 300 ms: a blocked call fails at once. */
  private PgLedgerStore quick(DataSource source) {
    return new PgLedgerStore(
        source, clock(), Config.forSchema(schema()).withLockTimeout(Duration.ofMillis(300)));
  }

  /** A transaction that holds the row of the restore epoch for update; roll it back to let go. */
  private Connection holdRestoreEpoch() throws SQLException {
    Connection holder = dataSource().getConnection();
    try {
      holder.setAutoCommit(false);
      try (Statement statement = holder.createStatement()) {
        statement.execute(
            "SELECT 1 FROM " + schema() + ".restore_epoch WHERE singleton FOR UPDATE");
      }
      return holder;
    } catch (SQLException | RuntimeException e) {
      holder.close();
      throw e;
    }
  }

  private static void assertWaitsForTheBarrier(String what, Runnable call) {
    assertThatThrownBy(call::run)
        .as(what)
        .isInstanceOf(LedgerException.class)
        .hasMessageContaining("sqlstate=55P03");
  }

  @Test
  void aCallThatWritesNothingTakesTheBarrierAndACallThatWritesARowDoesNot() throws SQLException {
    store().indexSubjects(List.of(entry(1, object("b/a")), entry(1, object("b/b"))));
    PgLedgerStore quick = quick(dataSource());

    try (Connection holder = holdRestoreEpoch()) {
      // every entry is stored already: the unit wrote nothing and takes the barrier (KF22)
      assertWaitsForTheBarrier(
          "one stored entry", () -> quick.indexSubjects(List.of(entry(1, object("b/a")))));
      assertWaitsForTheBarrier(
          "two stored entries",
          () -> quick.indexSubjects(List.of(entry(1, object("b/a")), entry(1, object("b/b")))));
      // a removal that removes no row: an unknown reference, or the other kind of a known text
      assertWaitsForTheBarrier(
          "an unknown reference", () -> quick.removeSubjectRefs(object("b/x")));
      assertWaitsForTheBarrier(
          "the other kind of a known text", () -> quick.removeSubjectRefs(report("b/a")));

      // a call that inserts a row has a transaction id and WAL of its own: no barrier
      quick.indexSubjects(List.of(entry(1, object("b/c"))));
      // a call with one stored entry and one new entry inserts a row: no barrier either
      quick.indexSubjects(List.of(entry(1, object("b/a")), entry(1, object("b/d"))));
      // a removal that removes a row: no barrier
      assertThat(quick.removeSubjectRefs(object("b/c"))).isEqualTo(1);
      // an empty list does nothing at all, the reads take no barrier
      quick.indexSubjects(List.of());
      assertThat(refsOf(quick, 1)).containsExactly(object("b/a"), object("b/b"), object("b/d"));

      holder.rollback();
    }

    // let go: the calls that waited go through, and the rows are as they were
    quick.indexSubjects(List.of(entry(1, object("b/a"))));
    assertThat(quick.removeSubjectRefs(object("b/x"))).isZero();
    assertThat(refsOf(quick, 1)).containsExactly(object("b/a"), object("b/b"), object("b/d"));
  }

  /**
   * With {@code reWriteBatchedInserts} the driver answers a batch of new rows with SUCCESS_NO_INFO.
   * The ledger counts such a batch as inserting nothing and takes the barrier (a barrier that was
   * not needed costs one lock; one that was missing costs an acknowledgement).
   */
  @Test
  void aBatchWhoseCountsSayNothingIsTakenForAnInsertOfNothing() throws SQLException {
    DataSource rewriting = rewritingSource();
    // the premise: this driver and this statement answer SUCCESS_NO_INFO for two new rows
    try (Connection connection = rewriting.getConnection()) {
      connection.setAutoCommit(false);
      try (Statement path = connection.createStatement()) {
        path.execute("SET LOCAL search_path = " + schema());
      }
      try (PreparedStatement insert = connection.prepareStatement(PgSubjectIndex.INSERT_SQL)) {
        for (String ref : List.of("premise/a", "premise/b")) {
          insert.setBytes(1, new byte[32]);
          insert.setShort(2, (short) 2);
          insert.setString(3, ref);
          insert.setObject(4, OffsetDateTime.parse("2026-10-09T08:00:00Z"));
          insert.addBatch();
        }
        assertThat(insert.executeBatch())
            .as("the counts of a rewritten batch of new rows")
            .containsExactly(Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO);
      } finally {
        connection.rollback();
      }
    }
    PgLedgerStore quick = quick(rewriting);

    try (Connection holder = holdRestoreEpoch()) {
      assertWaitsForTheBarrier(
          "a batch that says nothing about its rows",
          () -> quick.indexSubjects(List.of(entry(1, object("n/a")), entry(1, object("n/b")))));
      holder.rollback();
    }
    // the unit was rolled back by the lock timeout: nothing of the batch is stored
    assertThat(refsOf(quick, 1)).isEmpty();
    quick.indexSubjects(List.of(entry(1, object("n/a")), entry(1, object("n/b"))));
    assertThat(refsOf(quick, 1)).containsExactly(object("n/a"), object("n/b"));
  }

  private DataSource rewritingSource() {
    if (!(dataSource() instanceof PGSimpleDataSource base)) {
      throw new IllegalStateException(
          "the data source of the real store is not a PGSimpleDataSource");
    }
    PGSimpleDataSource copy = new PGSimpleDataSource();
    try {
      copy.setUrl(base.getUrl());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("the data source could not be copied");
    }
    copy.setUser(base.getUser());
    copy.setPassword(base.getPassword());
    copy.setReWriteBatchedInserts(true);
    return copy;
  }

  @Test
  void aRepeatedEntryKeepsItsFirstRecordTimeAndANewEntryTakesTheClockOfItsCall()
      throws SQLException {
    store().indexSubjects(List.of(entry(1, object("t/a"))));
    clock().advance(Duration.ofHours(1));
    store().indexSubjects(List.of(entry(1, object("t/a")), entry(1, object("t/b"))));
    clock().advance(Duration.ofHours(1));
    store().indexSubjects(List.of(entry(1, object("t/b"))));

    try (Connection connection = raw();
        PreparedStatement statement =
            connection.prepareStatement("SELECT ref, recorded_at FROM subject_index ORDER BY ref");
        ResultSet rows = statement.executeQuery()) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString(1)).isEqualTo("t/a");
      assertThat(rows.getObject(2, OffsetDateTime.class).toInstant())
          .as("the first call wrote it, the repeat did not touch it")
          .isEqualTo(START);
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString(1)).isEqualTo("t/b");
      assertThat(rows.getObject(2, OffsetDateTime.class).toInstant())
          .as("written by the second call, repeated by the third")
          .isEqualTo(START.plus(Duration.ofHours(1)));
      assertThat(rows.next()).isFalse();
    }
    // release 1 has one entity: every row carries entity_id 1
    assertThat(count("SELECT count(*) FROM subject_index WHERE entity_id = 1")).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM subject_index")).isEqualTo(2);
  }

  /**
   * A row that is not a reference (a kind this code does not know, an empty text, a control
   * character: none can be written through the port) is reported as a malformed row, with a message
   * that holds nothing of the row (Convention 12), and not as an argument error.
   */
  @Test
  void aStoredRowThatIsNotAReferenceIsReportedAsMalformedWithoutItsValue() throws SQLException {
    String hash = "decode(repeat('00', 31) || '01', 'hex')";
    String[] bad = {
      "99, 'x/secret-kind'", "1, ''", "2, E'x/secret-control\tz'",
    };
    for (String row : bad) {
      try (Connection connection = raw();
          Statement statement = connection.createStatement()) {
        statement.execute("DELETE FROM subject_index");
        statement.execute(
            "INSERT INTO subject_index (subject_hash, ref_kind, ref, recorded_at) VALUES ("
                + hash
                + ", "
                + row
                + ", now())");
      }
      assertThatThrownBy(() -> store().subjectRefs(hash(1), Optional.empty(), 10))
          .as(row)
          .isInstanceOf(LedgerException.class)
          .hasMessage("stored subject index row is malformed")
          .satisfies(e -> assertThat(e.toString()).doesNotContain("secret"));
    }
  }

  /**
   * A page starts strictly after its bound. The walks of the contract suite show it too, but only
   * by running to their guard of 5,000 pages (about half an hour on a loaded machine, one new
   * connection per page); here a flaw ends the test at once.
   */
  @Test
  void aPageStartsStrictlyAfterItsBound() {
    store()
        .indexSubjects(
            List.of(
                entry(1, report("s/1")),
                entry(1, object("s/1")),
                entry(1, object("s/2")),
                entry(1, object("s/3"))));

    assertThat(store().subjectRefs(hash(1), Optional.of(report("s/1")), 10))
        .containsExactly(object("s/1"), object("s/2"), object("s/3"));
    assertThat(store().subjectRefs(hash(1), Optional.of(object("s/1")), 10))
        .containsExactly(object("s/2"), object("s/3"));
    assertThat(store().subjectRefs(hash(1), Optional.of(object("s/2")), 1))
        .containsExactly(object("s/3"));
    assertThat(store().subjectRefs(hash(1), Optional.of(object("s/3")), 10)).isEmpty();
    // the bound is a position, not a row: a reference that is not stored can be the bound
    assertThat(store().subjectRefs(hash(1), Optional.of(object("s/15")), 10))
        .containsExactly(object("s/2"), object("s/3"));
  }

  @Test
  void theKeyAndTheIndexOfTheSubjectIndexAreAsDesigned() throws SQLException {
    String table = schema() + ".subject_index";
    assertThat(
            strings(
                "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c"
                    + " WHERE c.conname = 'subject_index_pk' AND c.conrelid = to_regclass(?)",
                table))
        .as("the key: the lookup by hash is a range on its first column, and kind and text follow")
        .containsExactly("PRIMARY KEY (subject_hash, ref_kind, ref)");
    assertThat(
            strings(
                "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c"
                    + " WHERE c.conname = 'subject_index_hash_check' AND c.conrelid = to_regclass(?)",
                table))
        .singleElement()
        .asString()
        .contains("octet_length(subject_hash) = 32");
    assertThat(
            strings("SELECT pg_get_indexdef(to_regclass(?))", schema() + ".subject_index_ref_idx"))
        .as("the index that serves the removal by reference")
        .singleElement()
        .asString()
        .endsWith("USING btree (ref_kind, ref)");
    for (String column : List.of("subject_hash", "ref_kind", "ref", "entity_id", "recorded_at")) {
      assertThat(
              strings(
                  "SELECT a.attnotnull::text FROM pg_attribute a"
                      + " WHERE a.attrelid = to_regclass(?) AND a.attname = '"
                      + column
                      + "'",
                  table))
          .as("%s is NOT NULL", column)
          .containsExactly("true");
    }
  }

  /**
   * Two callers that take the same rows in opposite orders. An insert that meets a row that another
   * unit has inserted and not committed waits for it, so units that took the rows in opposite
   * orders could wait for each other in a circle (deadlock, SQLSTATE 40P01). The ledger writes in
   * the order of the key for every caller, so that one waits for the other and none fails.
   */
  @Test
  @Timeout(value = 3, unit = TimeUnit.MINUTES)
  void callersThatTakeTheSameRowsInOppositeOrdersDoNotWaitForEachOther() throws Exception {
    int rows = 400;
    // a long lock timeout: the caller that waits for the other one must not fail on a slow runner
    PgLedgerStore patient =
        new PgLedgerStore(
            dataSource(),
            clock(),
            Config.forSchema(schema()).withLockTimeout(Duration.ofSeconds(30)));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 4; round++) {
        int hash = 100 + round;
        List<SubjectEntry> ascending = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
          ascending.add(entry(hash, object(String.format(Locale.ROOT, "race/%04d", i))));
        }
        List<SubjectEntry> descending = new ArrayList<>(ascending);
        Collections.reverse(descending);
        CyclicBarrier together = new CyclicBarrier(2);
        List<Future<Void>> calls = new ArrayList<>();
        for (List<SubjectEntry> order : List.of(ascending, descending)) {
          Callable<Void> call =
              () -> {
                together.await(30, TimeUnit.SECONDS);
                patient.indexSubjects(order);
                return null;
              };
          calls.add(pool.submit(call));
        }
        for (Future<Void> call : calls) {
          call.get(2, TimeUnit.MINUTES);
        }
        assertThat(patient.subjectRefs(hash(hash), Optional.empty(), 1_000))
            .as("round %d", round)
            .hasSize(rows);
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
