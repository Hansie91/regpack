package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Regime;
import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.RetentionLedger;
import dev.regpack.ports.ledger.RetentionLedger.AnchorEvent;
import dev.regpack.ports.ledger.RetentionLedger.RetentionClaim;
import dev.regpack.ports.ledger.RetentionLedger.RetentionRow;
import dev.regpack.postgres.PgLedgerStore.Config;
import dev.regpack.testkit.clock.ManualClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * What the retention side of the adapter decides without a server (P3c1 T11): the statement texts,
 * the order in which rows are written, the artifact locks that come before every write (task note
 * T11-2, decision D28), the arguments that are refused before a connection is opened, the way an
 * instant is bound (T11-5), the mapping of a stored row, and static checks of the texts against
 * {@code V003}. The statements themselves first run on a real server at CP-1 ({@code
 * PgLedgerStoreContractIT}, {@code PgConstraintsIT}); the statement shapes below are the whole
 * local evidence that the adapter does what {@code RetentionLedgerContract} asks of it.
 */
class PgRetentionTest {
  private static final Instant NOW = Instant.parse("2026-10-09T08:00:00Z");
  private static final Instant D2031 = Instant.parse("2031-06-01T12:30:45.123456Z");
  private static final Instant D2033 = Instant.parse("2033-01-01T00:00:00Z");
  private static final Regime MIFIR = new Regime("mifir.eu.rts22");
  private static final Regime EMIR = new Regime("emir.eu");
  private static final char DEL = (char) 0x7F;

  /**
   * The purpose of the artifact lock, pinned (D28): the next number after the purposes of the other
   * adapters.
   */
  private static final String PURPOSE = "4";

  /**
   * The purposes the other adapters take (Convention 12 of the P3a plan, held together by {@code
   * PgHarnessIT#advisoryKeysDifferBetweenSchemas}): 1 the migrator, 2 the creation of a partition,
   * 3 the source of an ingest commit.
   */
  private static final Set<Integer> PURPOSES_IN_USE = Set.of(1, 2, 3);

  private final SqlSpy spy = new SqlSpy().recordingTypedBinds();
  private final ManualClock clock = new ManualClock(NOW);
  private final Config config = Config.forSchema("t42_7");

  private PgRetention retention() {
    return new PgRetention(spy.over(null), clock, config);
  }

  private static RetentionClaim claim(
      String artifact,
      Regime regime,
      AnchorEvent anchor,
      Optional<Instant> retainUntil,
      String retentionClass) {
    return new RetentionClaim(
        new EvidenceKey(artifact), regime, anchor, retainUntil, retentionClass);
  }

  private static RetentionClaim claim(String artifact, Regime regime, Instant retainUntil) {
    return claim(artifact, regime, AnchorEvent.OBJECT_WRITTEN, Optional.of(retainUntil), "sealed");
  }

  private static String migrationV003() throws IOException {
    Path path =
        Path.of(
            System.getProperty("regpack.classes.dir"),
            "db/migration/V003__retention_and_subject_index.sql");
    return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
  }

  /** A clock that says {@code NOW} and counts how often it was asked. */
  private static Clock countingClock(AtomicInteger reads) {
    return new Clock() {
      @Override
      public Instant now() {
        reads.incrementAndGet();
        return NOW;
      }

      @Override
      public long monotonicNanos() {
        return 0;
      }
    };
  }

  /** The statements on the retention table, in the order they were executed. */
  private List<String> executed() {
    return spy.events().stream()
        .filter(e -> e.startsWith("execute: ") && e.contains(" retention "))
        .map(e -> e.substring("execute: ".length()))
        .toList();
  }

  /** Everything the spy recorded: opens, binds, executions, commits and rollbacks. */
  private List<String> events() {
    return spy.events();
  }

  /** The statements of the calls, without the prologue of each unit, in the order executed. */
  private static List<String> statements(SqlSpy recorded) {
    return recorded.events().stream()
        .filter(
            e ->
                e.startsWith("execute: ")
                    && !e.contains("set_config")
                    && !e.startsWith("execute: SET TRANSACTION"))
        .map(e -> e.substring("execute: ".length()))
        .toList();
  }

  /**
   * The artifact locks the calls took, in the order executed, each as {@code purpose|text} from its
   * two binds: the purpose is bound as an int and the text as a string, nothing else is bound to
   * the statement.
   */
  private static List<String> locksTaken(SqlSpy recorded) {
    List<String> events = recorded.events();
    List<String> locks = new ArrayList<>();
    for (int i = 0; i < events.size(); i++) {
      if (events.get(i).equals("execute: " + PgRetention.ARTIFACT_LOCK_SQL)) {
        assertThat(i).as("a lock statement has two binds before it").isGreaterThanOrEqualTo(2);
        String purpose = events.get(i - 2);
        String text = events.get(i - 1);
        assertThat(purpose).startsWith("bind int 1=");
        assertThat(text).startsWith("bind 2=");
        locks.add(
            purpose.substring("bind int 1=".length()) + "|" + text.substring("bind 2=".length()));
      }
    }
    return locks;
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  /** A row as the fake server answers it: the seven columns of the two read statements. */
  private static Object[] stored(
      String artifact,
      String regime,
      int anchor,
      String retentionClass,
      Instant retainUntil,
      boolean held,
      Instant recordedAt) {
    return new Object[] {
      artifact,
      regime,
      (short) anchor,
      retentionClass,
      retainUntil == null ? null : utc(retainUntil),
      held,
      utc(recordedAt)
    };
  }

  // ---------------------------------------------------------------------------------------------
  // the statements

  @Test
  void theStatementsHaveThePinnedShapes() {
    assertThat(PgRetention.RECORD_SQL)
        .isEqualTo(
            "INSERT INTO retention (object_key, regime, anchor_event, entity_id, retention_class,"
                + " retain_until, legal_hold, recorded_at) VALUES (?, ?, ?, 1, ?, ?,"
                + " EXISTS (SELECT 1 FROM retention h WHERE h.object_key = ? AND h.legal_hold), ?)"
                + " ON CONFLICT ON CONSTRAINT retention_pk DO UPDATE SET retain_until ="
                + " GREATEST(retention.retain_until, EXCLUDED.retain_until)");
    // the form of Convention 12: a transaction-level lock, the purpose and the hash of the text
    assertThat(PgRetention.ARTIFACT_LOCK_SQL)
        .isEqualTo("SELECT pg_advisory_xact_lock(?, hashtext(?))");
    assertThat(PgRetention.HOLD_SQL)
        .isEqualTo("UPDATE retention SET legal_hold = ? WHERE object_key = ?");
    // one list of columns for both reads, in the order the mapping reads them
    String columns =
        "object_key, regime, anchor_event, retention_class, retain_until, legal_hold, recorded_at";
    assertThat(PgRetention.ROWS_SQL)
        .isEqualTo(
            "SELECT "
                + columns
                + " FROM retention WHERE object_key IN (SELECT DISTINCT object_key FROM retention"
                + " WHERE object_key >= ? AND object_key < ? AND object_key > ?"
                + " ORDER BY object_key LIMIT ?) ORDER BY object_key, regime, anchor_event");
    assertThat(PgRetention.EXPIRED_SQL)
        .isEqualTo(
            "SELECT "
                + columns
                + " FROM retention WHERE retain_until IS NOT NULL AND retain_until <= ?"
                + " AND NOT legal_hold AND NOT starts_with(object_key, ?)"
                + " ORDER BY retain_until, object_key, regime, anchor_event LIMIT ?");
    assertThat(PgRetention.EXPIRED_AFTER_SQL)
        .isEqualTo(
            "SELECT "
                + columns
                + " FROM retention WHERE retain_until IS NOT NULL AND retain_until <= ?"
                + " AND NOT legal_hold AND NOT starts_with(object_key, ?)"
                + " AND (retain_until, object_key, regime, anchor_event) > (?, ?, ?, ?)"
                + " ORDER BY retain_until, object_key, regime, anchor_event LIMIT ?");
  }

  @Test
  void theTextsNameTheObjectsThatV003Creates() throws IOException {
    String v003 = migrationV003();
    assertThat(v003)
        .contains("CREATE TABLE retention (")
        .contains("object_key text COLLATE \"C\" NOT NULL")
        .contains("regime text COLLATE \"C\" NOT NULL")
        .contains("CONSTRAINT retention_pk PRIMARY KEY (object_key, regime, anchor_event)")
        .contains("entity_id smallint NOT NULL DEFAULT 1");
    // every column the adapter reads or writes is a column of the table
    for (String column :
        List.of(
            "object_key",
            "regime",
            "anchor_event",
            "entity_id",
            "retention_class",
            "retain_until",
            "legal_hold",
            "recorded_at")) {
      assertThat(v003).as(column).contains(column + " ");
      assertThat(PgRetention.RECORD_SQL).as(column).contains(column);
    }
    // the arbiter of the upsert is the constraint of the script, by name
    assertThat(PgRetention.RECORD_SQL).contains("ON CONFLICT ON CONSTRAINT retention_pk ");
    // the expiry list asks what the partial index holds, so that the planner may use it (D5)
    String predicate = "WHERE retain_until IS NOT NULL AND NOT legal_hold";
    assertThat(v003)
        .contains(
            "CREATE INDEX retention_expiry_idx ON retention (retain_until, object_key) "
                + predicate);
    assertThat(PgRetention.EXPIRED_SQL)
        .contains("WHERE retain_until IS NOT NULL AND retain_until <= ? AND NOT legal_hold")
        .contains("ORDER BY retain_until, object_key");
    // the trigger of D2 guards what the upsert and the hold never change
    assertThat(v003).contains("CREATE TRIGGER retention_extend_only BEFORE UPDATE ON retention");
    assertThat(PgRetention.RECORD_SQL).doesNotContain("recorded_at =");
    assertThat(PgRetention.HOLD_SQL).doesNotContain("retain_until");
  }

  @Test
  void everyValueIsABindParameter() {
    String quote = String.valueOf((char) 0x27);
    for (String sql :
        List.of(
            PgRetention.RECORD_SQL,
            PgRetention.ARTIFACT_LOCK_SQL,
            PgRetention.HOLD_SQL,
            PgRetention.ROWS_SQL,
            PgRetention.EXPIRED_SQL,
            PgRetention.EXPIRED_AFTER_SQL)) {
      // no text literal at all: the epoch prefix is bound too; the only literal is the entity 1
      assertThat(sql).doesNotContain(quote);
    }

    String hostileKey = "x" + quote + " OR " + quote + "1" + quote + "=" + quote + "1";
    String hostilePrefix = hostileKey + "/";
    // the fake server updates one row, so that the hold is not refused for want of a row
    SqlSpy local = new SqlSpy().recordingTypedBinds().updatingRows(1);
    PgRetention retention = new PgRetention(local.over(null), clock, config);
    // the hostile text cannot be an EvidenceKey, so it can only reach the adapter as a prefix
    retention.retentionRows(hostilePrefix, Optional.empty(), 10);
    retention.recordRetention(List.of(claim("sealed/ab/key-one", MIFIR, D2031)));
    retention.setRetentionHold(new EvidenceKey("sealed/ab/key-one"), true);
    retention.expiredRetention(NOW, Optional.empty(), 10);

    List<String> seen = local.events();
    assertThat(seen)
        .contains(
            "bind 1=" + hostilePrefix,
            "bind 2=" + hostilePrefix + DEL,
            "bind 1=sealed/ab/key-one",
            "bind 2=" + RetentionLedger.EXEMPT_PREFIX);
    // the key of the lock is a bind value as well: the schema and the key, never part of the text
    assertThat(locksTaken(local))
        .containsExactly(
            PURPOSE + "|t42_7:sealed/ab/key-one", PURPOSE + "|t42_7:sealed/ab/key-one");
    assertThat(statements(local))
        .containsExactly(
            PgRetention.ROWS_SQL,
            PgRetention.ARTIFACT_LOCK_SQL,
            PgRetention.RECORD_SQL,
            PgRetention.ARTIFACT_LOCK_SQL,
            PgRetention.HOLD_SQL,
            PgRetention.EXPIRED_SQL);
  }

  // ---------------------------------------------------------------------------------------------
  // writing

  /**
   * KF22 for {@code retention}: a call that records a claim writes even when the row exists, so it
   * needs no barrier. The fake server answers the barrier query with a row, so that a barrier would
   * be seen; the artifact lock and the upsert run (nothing is read to decide whether to write) and
   * the unit does not take the durability barrier. That the {@code DO UPDATE} writes a new row
   * version for an unchanged value is a recollection of PostgreSQL and is not shown here: the
   * statement order is.
   */
  @Test
  void recordingAnExistingClaimStillWrites() {
    SqlSpy answering =
        new SqlSpy()
            .recordingTypedBinds()
            .answering(
                (sql, events) ->
                    sql.equals(Unit.BARRIER_SQL) ? List.<Object[]>of(new Object[] {1}) : List.of());
    new PgRetention(answering.over(null), clock, config)
        .recordRetention(List.of(claim("sealed/ab/key-one", MIFIR, D2031)));

    assertThat(statements(answering))
        .as("the lock, then the upsert, and no barrier")
        .containsExactly(PgRetention.ARTIFACT_LOCK_SQL, PgRetention.RECORD_SQL);
    assertThat(answering.events()).contains("commit");
  }

  @Test
  void theUpsertKeepsTheClassAndTheTimeOfAStoredRow() {
    // T11-8 and SEAL-1: a merge changes the date and nothing else; recorded_at is the birth of the
    // row (the SEAL-1 scenario asserts that the second seal leaves it alone)
    String set = PgRetention.RECORD_SQL.substring(PgRetention.RECORD_SQL.indexOf("DO UPDATE SET"));
    assertThat(set)
        .isEqualTo(
            "DO UPDATE SET retain_until = GREATEST(retention.retain_until, EXCLUDED.retain_until)");
    assertThat(set)
        .doesNotContain("retention_class")
        .doesNotContain("recorded_at")
        .doesNotContain("legal_hold");
  }

  @Test
  void theInsertTakesTheHoldOfTheArtifact() {
    // statement shape; the behaviour is a recollection of PostgreSQL until CP-1 shows
    // RetentionLedgerContract#aClaimRecordedAfterAHoldIsHeldToo on a server
    assertThat(PgRetention.RECORD_SQL)
        .contains("EXISTS (SELECT 1 FROM retention h WHERE h.object_key = ? AND h.legal_hold)");
    retention().recordRetention(List.of(claim("sealed/ab/key-one", MIFIR, D2031)));

    // the key is bound twice: as the key of the row and as the artifact whose hold the row takes
    assertThat(events())
        .contains("bind 1=sealed/ab/key-one", "bind 6=sealed/ab/key-one")
        .contains(
            "bind 2=mifir.eu.rts22",
            "bind short 3=1",
            "bind 4=sealed",
            "bind object 5=" + utc(D2031),
            "bind object 7=" + utc(NOW));
  }

  @Test
  void claimsAreWrittenInKeyOrder() {
    // given in no order at all; stored order is (artifact, regime, anchor), the order in which two
    // calls with overlapping claims never wait for each other in a circle
    retention()
        .recordRetention(
            List.of(
                claim("sealed/b", MIFIR, D2031),
                claim("sealed/a", MIFIR, AnchorEvent.TERMINATION, Optional.empty(), "sealed"),
                claim("sealed/b", EMIR, D2031),
                claim("sealed/a", MIFIR, D2031),
                claim("landing/z", EMIR, D2033)));

    List<String> events = events();
    int lastLock = events.lastIndexOf("execute: " + PgRetention.ARTIFACT_LOCK_SQL);
    List<String> writes = events.subList(lastLock + 1, events.size());
    assertThat(writes.stream().filter(e -> e.startsWith("bind 1=")).toList())
        .containsExactly(
            "bind 1=landing/z",
            "bind 1=sealed/a",
            "bind 1=sealed/a",
            "bind 1=sealed/b",
            "bind 1=sealed/b");
    assertThat(writes.stream().filter(e -> e.startsWith("bind 2=")).toList())
        .containsExactly(
            "bind 2=emir.eu",
            "bind 2=mifir.eu.rts22",
            "bind 2=mifir.eu.rts22",
            "bind 2=emir.eu",
            "bind 2=mifir.eu.rts22");
    assertThat(writes.stream().filter(e -> e.startsWith("bind short 3=")).toList())
        .as("the OBJECT_WRITTEN row of an artifact and regime before its TERMINATION row")
        .containsExactly(
            "bind short 3=1",
            "bind short 3=1",
            "bind short 3=2",
            "bind short 3=1",
            "bind short 3=1");
  }

  /**
   * T11-2 and D28: every artifact that a call touches is locked by an advisory lock of its own
   * before the first write of the call, in the order of the artifacts, one statement per distinct
   * artifact however many claims it has. A hold that races the first claims of a new artifact (no
   * row to lock yet) then waits for the claim that holds the lock, and the write that follows takes
   * a new snapshot of {@code READ COMMITTED} and sees the row the other call committed; without the
   * lock the later one would not see it and a row would stay unheld.
   */
  @Test
  void aHoldAndARecordTakeTheArtifactLockBeforeTheyWrite() {
    retention()
        .recordRetention(
            List.of(
                claim("sealed/c", MIFIR, D2031),
                claim("sealed/a", MIFIR, D2031),
                claim("sealed/a", EMIR, D2031),
                claim("sealed/b", MIFIR, D2031)));

    // the first statements of the unit are the locks, one per distinct artifact in key order
    // (four claims, three artifacts: the two claims of sealed/a lock once), then the batch
    assertThat(statements(spy))
        .containsExactly(
            PgRetention.ARTIFACT_LOCK_SQL,
            PgRetention.ARTIFACT_LOCK_SQL,
            PgRetention.ARTIFACT_LOCK_SQL,
            PgRetention.RECORD_SQL);
    assertThat(locksTaken(spy))
        .containsExactly(
            PURPOSE + "|t42_7:sealed/a", PURPOSE + "|t42_7:sealed/b", PURPOSE + "|t42_7:sealed/c");
    // no value of a write is bound before the last lock
    List<String> events = events();
    int lastLock = events.lastIndexOf("execute: " + PgRetention.ARTIFACT_LOCK_SQL);
    assertThat(lastLock).isPositive();
    assertThat(events.subList(0, lastLock))
        .noneMatch(e -> e.startsWith("bind object 7=") || e.startsWith("bind short 3="));
    assertThat(events.subList(lastLock, events.size())).contains("commit");

    // the hold: the lock, then the update
    SqlSpy holdSpy = new SqlSpy().recordingTypedBinds().updatingRows(2);
    new PgRetention(holdSpy.over(null), clock, config)
        .setRetentionHold(new EvidenceKey("sealed/a"), true);
    assertThat(statements(holdSpy))
        .containsExactly(PgRetention.ARTIFACT_LOCK_SQL, PgRetention.HOLD_SQL);
    assertThat(locksTaken(holdSpy)).containsExactly(PURPOSE + "|t42_7:sealed/a");
    List<String> holdEvents = holdSpy.events();
    int lock = holdEvents.indexOf("execute: " + PgRetention.ARTIFACT_LOCK_SQL);
    int update = holdEvents.indexOf("execute: " + PgRetention.HOLD_SQL);
    assertThat(lock).isPositive();
    assertThat(update).as("the update comes after the lock").isGreaterThan(lock);
    assertThat(holdEvents.subList(update - 2, update))
        .containsExactly("bind boolean 1=true", "bind 2=sealed/a");
    assertThat(holdEvents.subList(0, lock)).noneMatch(e -> e.startsWith("bind boolean"));
    assertThat(holdEvents.subList(update, holdEvents.size())).contains("commit");
  }

  /**
   * Two claims of one artifact (two regimes, or two anchors) take its lock once: the list of locks
   * is the list of distinct artifacts, not one per claim.
   */
  @Test
  void twoClaimsOfOneArtifactTakeOneLock() {
    retention()
        .recordRetention(
            List.of(
                claim("sealed/a", MIFIR, D2031),
                claim("sealed/a", EMIR, D2031),
                claim("sealed/a", EMIR, AnchorEvent.TERMINATION, Optional.empty(), "sealed")));

    assertThat(locksTaken(spy)).containsExactly(PURPOSE + "|t42_7:sealed/a");
    assertThat(statements(spy))
        .containsExactly(PgRetention.ARTIFACT_LOCK_SQL, PgRetention.RECORD_SQL);
    // all three claims are still written, in the one batch
    assertThat(events().stream().filter(e -> e.startsWith("bind short 3=")).count()).isEqualTo(3);
  }

  /**
   * The locks follow the order of the keys, whatever the order of the claims (the order in which
   * two calls meet, so that two calls with overlapping artifacts never wait for each other in a
   * circle), and a key that is a prefix of another comes first.
   */
  @Test
  void theArtifactLocksFollowTheKeyOrderWhateverTheOrderOfTheClaims() {
    List<RetentionClaim> claims =
        List.of(
            claim("sealed/b", MIFIR, D2031),
            claim("landing/z", EMIR, D2033),
            claim("sealed/a", MIFIR, D2031),
            claim("sealed/a/x", MIFIR, D2031),
            claim("sealed/B", MIFIR, D2031));
    List<String> inKeyOrder =
        List.of(
            PURPOSE + "|t42_7:landing/z",
            PURPOSE + "|t42_7:sealed/B",
            PURPOSE + "|t42_7:sealed/a",
            PURPOSE + "|t42_7:sealed/a/x",
            PURPOSE + "|t42_7:sealed/b");
    List<List<RetentionClaim>> permutations =
        List.of(
            claims,
            List.of(claims.get(4), claims.get(3), claims.get(2), claims.get(1), claims.get(0)),
            List.of(claims.get(2), claims.get(0), claims.get(4), claims.get(1), claims.get(3)));
    for (List<RetentionClaim> order : permutations) {
      SqlSpy each = new SqlSpy().recordingTypedBinds();
      new PgRetention(each.over(null), clock, config).recordRetention(order);
      assertThat(locksTaken(each)).containsExactlyElementsOf(inKeyOrder);
    }
  }

  /** The reads take no lock: a reader never waits for a writer, and a listing is a snapshot. */
  @Test
  void theReadsTakeNoLock() {
    RetentionRow after = new RetentionRow(claim("sealed/a", MIFIR, D2031), false, NOW);

    retention().retentionRows("sealed/", Optional.empty(), 10);
    retention().retentionRows("sealed/", Optional.of(new EvidenceKey("sealed/a")), 10);
    retention().expiredRetention(D2033, Optional.empty(), 10);
    retention().expiredRetention(D2033, Optional.of(after), 10);

    assertThat(statements(spy))
        .containsExactly(
            PgRetention.ROWS_SQL,
            PgRetention.ROWS_SQL,
            PgRetention.EXPIRED_SQL,
            PgRetention.EXPIRED_AFTER_SQL);
    assertThat(events()).noneMatch(e -> e.contains("pg_advisory"));
    assertThat(locksTaken(spy)).isEmpty();
  }

  /**
   * The key of the lock holds the schema (Convention 12), so two ledgers in two schemas of one
   * database never wait for each other, and the artifact, so that two artifacts of one schema do
   * not. It is one text bound as a string, with a separator that no schema name holds.
   */
  @Test
  void twoSchemasBindTwoLockTexts() {
    SqlSpy first = new SqlSpy().recordingTypedBinds().updatingRows(1);
    SqlSpy second = new SqlSpy().recordingTypedBinds().updatingRows(1);
    PgRetention inFirst = new PgRetention(first.over(null), clock, Config.forSchema("t42_7"));
    PgRetention inSecond = new PgRetention(second.over(null), clock, Config.forSchema("t42_8"));
    RetentionClaim claim = claim("sealed/a", MIFIR, D2031);

    inFirst.recordRetention(List.of(claim));
    inSecond.recordRetention(List.of(claim));
    inFirst.setRetentionHold(new EvidenceKey("sealed/a"), true);
    inSecond.setRetentionHold(new EvidenceKey("sealed/a"), true);

    assertThat(locksTaken(first))
        .containsExactly(PURPOSE + "|t42_7:sealed/a", PURPOSE + "|t42_7:sealed/a");
    assertThat(locksTaken(second))
        .containsExactly(PURPOSE + "|t42_8:sealed/a", PURPOSE + "|t42_8:sealed/a");
    assertThat(locksTaken(first)).doesNotContainAnyElementsOf(locksTaken(second));
  }

  /**
   * The purpose differs from the purposes the other adapters take, so that no unit of another kind
   * waits on a retention lock of the same text: the migrator (1, keyed by the schema), the creation
   * of a partition (2, by the schema) and the ingest commit of a source (3, by schema and source).
   */
  @Test
  void theLockPurposeIsNotOneOfThePurposesOfTheOtherAdapters() {
    SqlSpy holding = new SqlSpy().recordingTypedBinds().updatingRows(1);
    PgRetention retention = new PgRetention(holding.over(null), clock, config);
    retention.recordRetention(List.of(claim("sealed/a", MIFIR, D2031)));
    retention.setRetentionHold(new EvidenceKey("sealed/a"), true);

    List<String> locks = locksTaken(holding);
    assertThat(locks).hasSize(2);
    for (String lock : locks) {
      int purpose = Integer.parseInt(lock.substring(0, lock.indexOf('|')));
      assertThat(PURPOSES_IN_USE).as("purpose %d", purpose).doesNotContain(purpose);
      assertThat(purpose).isEqualTo(Integer.parseInt(PURPOSE));
    }
  }

  @Test
  void theHoldIsBoundAsABooleanAndAReleaseIsTheSameStatement() {
    for (boolean hold : List.of(true, false)) {
      SqlSpy one = new SqlSpy().recordingTypedBinds().updatingRows(1);
      new PgRetention(one.over(null), clock, config)
          .setRetentionHold(new EvidenceKey("sealed/x"), hold);
      assertThat(one.events()).contains("bind boolean 1=" + hold, "bind 2=sealed/x", "commit");
    }
  }

  @Test
  void aHoldOnAnArtifactWithoutRowsIsRefusedAndRolledBack() {
    // the fake server answers the update with 0 rows; the lock does not depend on a row, so it is
    // taken for an artifact that has none, and it is the update that finds nothing (D28)
    assertThatThrownBy(() -> retention().setRetentionHold(new EvidenceKey("sealed/none"), true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("the artifact has no retention row");
    assertThat(statements(spy))
        .containsExactly(PgRetention.ARTIFACT_LOCK_SQL, PgRetention.HOLD_SQL);
    assertThat(locksTaken(spy)).containsExactly(PURPOSE + "|t42_7:sealed/none");
    assertThat(events()).contains("rollback").doesNotContain("commit");
  }

  // ---------------------------------------------------------------------------------------------
  // arguments that are refused before a connection is opened

  @Test
  void theRefusalTextsAreThoseOfTheInMemoryRetentionLedger() {
    // T11-8: the pin row "Refusal texts of the in-memory retention ledger" of p3c-decisions.md;
    // both ledgers say the same, and none of the texts holds a value
    PgRetention retention = retention();
    RetentionClaim a = claim("sealed/a", MIFIR, D2031);
    RetentionClaim again = claim("sealed/a", MIFIR, D2033);
    assertThatThrownBy(() -> retention.recordRetention(List.of(a, again)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("two claims of one call have one artifact and anchor");
    assertThatThrownBy(() -> retention.retentionRows("", Optional.empty(), -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("limit must not be negative");
    assertThatThrownBy(() -> retention.expiredRetention(NOW, Optional.empty(), -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("limit must not be negative");
    RetentionRow open =
        new RetentionRow(
            claim("sealed/a", MIFIR, AnchorEvent.TERMINATION, Optional.empty(), "sealed"),
            false,
            NOW);
    assertThatThrownBy(() -> retention.expiredRetention(NOW, Optional.of(open), 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("a row without a retain-until is not in the expiry list");
    // and not one of them reached a server
    assertThat(events()).isEmpty();
  }

  @Test
  void aDuplicateClaimIsRefusedForTheSameArtifactRegimeAndAnchorOnly() {
    PgRetention retention = retention();
    // the same artifact under two regimes, or two anchors, is not a duplicate
    retention.recordRetention(
        List.of(
            claim("sealed/a", MIFIR, D2031),
            claim("sealed/a", EMIR, D2031),
            claim("sealed/a", EMIR, AnchorEvent.TERMINATION, Optional.empty(), "sealed")));
    assertThat(executed()).contains(PgRetention.RECORD_SQL);
    // the same triple with another date and class is
    SqlSpy second = new SqlSpy();
    PgRetention strict = new PgRetention(second.over(null), clock, config);
    assertThatThrownBy(
            () ->
                strict.recordRetention(
                    List.of(
                        claim("sealed/a", MIFIR, D2031),
                        claim(
                            "sealed/a", MIFIR, AnchorEvent.OBJECT_WRITTEN, Optional.empty(), "x"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(second.events()).isEmpty();
  }

  @Test
  void anEmptyListOfClaimsOpensNoConnectionAndReadsNoClock() {
    AtomicInteger reads = new AtomicInteger();
    PgRetention retention = new PgRetention(spy.over(null), countingClock(reads), config);

    retention.recordRetention(List.of());

    assertThat(events()).isEmpty();
    assertThat(reads).hasValue(0);
  }

  @Test
  void theClockIsReadOnceForTheWholeCall() {
    AtomicInteger reads = new AtomicInteger();
    PgRetention retention = new PgRetention(spy.over(null), countingClock(reads), config);

    retention.recordRetention(
        List.of(
            claim("sealed/a", MIFIR, D2031),
            claim("sealed/b", MIFIR, D2031),
            claim("sealed/c", MIFIR, D2031)));

    assertThat(reads).hasValue(1);
    assertThat(events().stream().filter(e -> e.equals("bind object 7=" + utc(NOW))).count())
        .as("one ledger time for every row of the call")
        .isEqualTo(3);
  }

  @Test
  void nullArgumentsAreRefusedBeforeAnyStatement() {
    PgRetention retention = retention();
    assertThatThrownBy(() -> retention.recordRetention(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> retention.setRetentionHold(null, true))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> retention.retentionRows(null, Optional.empty(), 1))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> retention.retentionRows("", null, 1))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> retention.expiredRetention(null, Optional.empty(), 1))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> retention.expiredRetention(NOW, null, 1))
        .isInstanceOf(NullPointerException.class);
    assertThat(events()).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // reading

  /**
   * T11-4: no key has a NUL and the server cannot hold one in a text, so such a prefix is answered
   * without a connection (as {@code PgManifest#manifestRows}); the other hostile prefixes of the
   * contract (a character outside the key alphabet) reach the server as text and match nothing
   * there, because the range of a prefix is made of the same characters.
   */
  @Test
  void aPrefixWithANulIsAnsweredWithoutAConnection() {
    PgRetention retention = retention();
    char nul = (char) 0;
    for (String prefix : List.of("a" + nul, String.valueOf(nul), "a/" + nul + "b", "ord/" + nul)) {
      assertThat(retention.retentionRows(prefix, Optional.empty(), 100)).isEmpty();
      assertThat(retention.retentionRows(prefix, Optional.of(new EvidenceKey("a")), 100)).isEmpty();
    }
    assertThat(events()).as("no connection was opened for a prefix with a NUL").isEmpty();
    assertThat(spy.statements()).isZero();
  }

  @Test
  void aLimitOfZeroListsNothingWithoutAConnection() {
    assertThat(retention().retentionRows("sealed/", Optional.empty(), 0)).isEmpty();
    assertThat(retention().expiredRetention(NOW, Optional.empty(), 0)).isEmpty();
    assertThat(events()).isEmpty();
  }

  @Test
  void theRangeOfAPrefixAndTheLimitAreBound() {
    retention().retentionRows("sealed/ab/", Optional.of(new EvidenceKey("sealed/ab/c")), 5_000);

    assertThat(events())
        .contains(
            "bind 1=sealed/ab/",
            "bind 2=" + PgManifest.upperBound("sealed/ab/"),
            "bind 3=sealed/ab/c",
            "bind long 4=" + RetentionLedger.MAX_RETENTION_ROWS_PER_CALL);

    SqlSpy second = new SqlSpy().recordingTypedBinds();
    new PgRetention(second.over(null), clock, config).retentionRows("", Optional.empty(), 7);
    assertThat(second.events()).contains("bind 1=", "bind 2=" + DEL, "bind 3=", "bind long 4=7");
  }

  @Test
  void theLimitOfTheExpiryListIsCappedAtOneThousand() {
    retention().expiredRetention(NOW, Optional.empty(), Integer.MAX_VALUE);

    assertThat(events())
        .contains(
            "bind object 1=" + utc(NOW),
            "bind 2=control/",
            "bind long 3=" + RetentionLedger.MAX_RETENTION_ROWS_PER_CALL);
  }

  @Test
  void theExpiryQueryExcludesControlKeysAndHeldRows() {
    assertThat(PgRetention.EXPIRED_SQL)
        .contains("AND NOT legal_hold")
        .contains("AND NOT starts_with(object_key, ?)");
    assertThat(RetentionLedger.EXEMPT_PREFIX).isEqualTo("control/");

    RetentionRow after = new RetentionRow(claim("sealed/a", MIFIR, D2031), false, NOW);
    retention().expiredRetention(D2033, Optional.empty(), 10);
    retention().expiredRetention(D2033, Optional.of(after), 10);

    assertThat(executed()).containsExactly(PgRetention.EXPIRED_SQL, PgRetention.EXPIRED_AFTER_SQL);
    // the prefix of the epoch markers is bound from the port constant, in both statements
    assertThat(events().stream().filter(e -> e.equals("bind 2=control/")).count()).isEqualTo(2);
    // the row after which to list: its date, key, regime and anchor code, then the limit
    assertThat(events())
        .contains(
            "bind object 3=" + utc(D2031),
            "bind 4=sealed/a",
            "bind 5=mifir.eu.rts22",
            "bind short 6=1",
            "bind long 7=10");
  }

  /**
   * T11-3: {@code asOf} is checked with the helper of the port before it is bound, so that the
   * texts are those of the in-memory ledger and no instant the server would round, reject or fold
   * into another year reaches it.
   */
  @Test
  void anAsOfTheStoreCannotHoldIsRefusedBeforeAnyConnection() {
    PgRetention retention = retention();
    assertThatThrownBy(
            () ->
                retention.expiredRetention(
                    Instant.parse("2030-01-01T00:00:00.000000001Z"), Optional.empty(), 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("asOf must have whole microseconds");
    for (Instant bad :
        List.of(
            Instant.parse("+10000-01-01T00:00:00Z"),
            Instant.parse("-0001-12-31T23:59:59.999999Z"),
            Instant.MIN)) {
      assertThatThrownBy(() -> retention.expiredRetention(bad, Optional.empty(), 10))
          .as("asOf %s", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("asOf year must be from 0000 to 9999");
    }
    // Instant.MAX has nanoseconds, which the first rule meets first
    assertThatThrownBy(() -> retention.expiredRetention(Instant.MAX, Optional.empty(), 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("asOf must have whole microseconds");
    assertThat(events()).as("nothing reached the server").isEmpty();

    // the first and the last storable instant are accepted and bound as they are (T11-5)
    retention.expiredRetention(Instant.parse("0000-01-01T00:00:00Z"), Optional.empty(), 10);
    retention.expiredRetention(Instant.parse("9999-12-31T23:59:59.999999Z"), Optional.empty(), 10);
    assertThat(events())
        .contains("bind object 1=0000-01-01T00:00Z", "bind object 1=9999-12-31T23:59:59.999999Z");
  }

  /**
   * T11-5: {@code retain_until} is a {@code timestamptz} bound as an {@code OffsetDateTime} at UTC
   * from {@link PgTime#timestamp} (an open anchor as a typed null), never as text and never through
   * {@code to_char} or a cast in the statement. The server round trip of year 0000 and of the last
   * microsecond of 9999 is a recollection of the driver and is shown at CP-1 by {@code
   * RetentionLedgerContract#aRecordedClaimIsReadBackWithTheLedgerClock}.
   */
  @Test
  void retainUntilIsBoundAsATimestampAndNeverAsText() {
    for (String sql :
        List.of(PgRetention.RECORD_SQL, PgRetention.EXPIRED_SQL, PgRetention.EXPIRED_AFTER_SQL)) {
      assertThat(sql.toLowerCase(Locale.ROOT))
          .doesNotContain("to_char")
          .doesNotContain("to_timestamp")
          .doesNotContain("::")
          .doesNotContain("cast(");
    }
    Instant first = Instant.parse("0000-01-01T00:00:00Z");
    Instant last = Instant.parse("9999-12-31T23:59:59.999999Z");
    retention()
        .recordRetention(
            List.of(
                claim("sealed/a", MIFIR, D2031),
                claim("sealed/b", MIFIR, first),
                claim("sealed/c", MIFIR, last),
                claim("sealed/d", MIFIR, AnchorEvent.TERMINATION, Optional.empty(), "sealed")));

    assertThat(events())
        .contains(
            "bind object 5=" + utc(D2031),
            "bind object 5=0000-01-01T00:00Z",
            "bind object 5=9999-12-31T23:59:59.999999Z",
            "bind null 5=" + Types.TIMESTAMP_WITH_TIMEZONE);
    // no text bind at position 5 or 7: the dates are never strings
    assertThat(events()).noneMatch(e -> e.startsWith("bind 5=") || e.startsWith("bind 7="));
  }

  @Test
  void aStoredRowIsMappedWithItsHoldAndItsDates() {
    Instant born = Instant.parse("2026-01-02T03:04:05.678901Z");
    SqlSpy answering =
        new SqlSpy()
            .answering(
                (sql, events) ->
                    sql.equals(PgRetention.ROWS_SQL)
                        ? List.of(
                            stored("sealed/a", "emir.eu", 1, "sealed", D2031, true, born),
                            stored("sealed/a", "emir.eu", 2, "sealed", null, true, born),
                            stored("sealed/b", "mifir.eu.rts22", 1, "landing", D2033, false, NOW))
                        : List.of());
    PgRetention retention = new PgRetention(answering.over(null), clock, config);

    List<RetentionRow> rows = retention.retentionRows("sealed/", Optional.empty(), 10);

    assertThat(rows)
        .containsExactly(
            new RetentionRow(
                claim("sealed/a", EMIR, AnchorEvent.OBJECT_WRITTEN, Optional.of(D2031), "sealed"),
                true,
                born),
            new RetentionRow(
                claim("sealed/a", EMIR, AnchorEvent.TERMINATION, Optional.empty(), "sealed"),
                true,
                born),
            new RetentionRow(
                claim("sealed/b", MIFIR, AnchorEvent.OBJECT_WRITTEN, Optional.of(D2033), "landing"),
                false,
                NOW));
  }

  @Test
  void aStoredRowThatIsNotAClaimIsAMalformedRowAndNotAnArgumentError() {
    // an unknown anchor code, a class outside the alphabet, a key outside the alphabet and a regime
    // that is not an id: the adapter does not guess
    List<Object[]> bad =
        List.of(
            stored("sealed/a", "emir.eu", 9, "sealed", D2031, false, NOW),
            stored("sealed/a", "emir.eu", 0, "sealed", D2031, false, NOW),
            stored("sealed/a", "emir.eu", 1, "a b", D2031, false, NOW),
            stored("sealed/a b", "emir.eu", 1, "sealed", D2031, false, NOW),
            stored("sealed/a", "EMIR", 1, "sealed", D2031, false, NOW),
            stored(
                "sealed/a",
                "emir.eu",
                1,
                "sealed",
                Instant.parse("+10000-01-01T00:00:00Z"),
                false,
                NOW));
    for (Object[] row : bad) {
      SqlSpy answering = new SqlSpy().answering((sql, events) -> List.<Object[]>of(row));
      PgRetention retention = new PgRetention(answering.over(null), clock, config);
      assertThatThrownBy(() -> retention.retentionRows("", Optional.empty(), 10))
          .as("retentionRows of %s", List.of(row))
          .isInstanceOf(LedgerException.class)
          .hasMessage("stored row is malformed");
      assertThatThrownBy(() -> retention.expiredRetention(NOW, Optional.empty(), 10))
          .as("expiredRetention of %s", List.of(row))
          .isInstanceOf(LedgerException.class)
          .hasMessage("stored row is malformed");
    }
  }

  @Test
  void anExpiredRowIsMappedLikeAnyOtherRow() {
    SqlSpy answering =
        new SqlSpy()
            .answering(
                (sql, events) ->
                    sql.equals(PgRetention.EXPIRED_SQL)
                        ? List.<Object[]>of(
                            stored("sealed/a", "mifir.eu.rts22", 1, "sealed", D2031, false, NOW))
                        : List.of());
    PgRetention retention = new PgRetention(answering.over(null), clock, config);

    assertThat(retention.expiredRetention(D2033, Optional.empty(), 10))
        .containsExactly(new RetentionRow(claim("sealed/a", MIFIR, D2031), false, NOW));
  }

  // ---------------------------------------------------------------------------------------------
  // failures

  @Test
  void aFailureOfTheServerIsALedgerExceptionWithItsStateAndTheUnitIsRolledBack() {
    SQLException trigger = new SQLException("retention rows are extend-only", "55000");
    SqlSpy failing = new SqlSpy().failStatementWith("INSERT INTO retention", trigger);
    PgRetention retention = new PgRetention(failing.over(null), clock, config);

    assertThatThrownBy(() -> retention.recordRetention(List.of(claim("sealed/a", MIFIR, D2031))))
        .isInstanceOf(LedgerException.class)
        .hasMessageContaining("sqlstate=55000");
    assertThat(failing.events()).contains("rollback").doesNotContain("commit");
    assertThat(failing.openConnections()).isZero();
  }

  @Test
  void aFailureOfTheLockIsALedgerExceptionAndNothingIsWritten() {
    SQLException timeout = new SQLException("lock timeout", "55P03");
    SqlSpy failing = new SqlSpy().failStatementWith("pg_advisory_xact_lock", timeout);
    PgRetention retention = new PgRetention(failing.over(null), clock, config);

    assertThatThrownBy(() -> retention.setRetentionHold(new EvidenceKey("sealed/a"), true))
        .isInstanceOf(LedgerException.class)
        .hasMessageContaining("sqlstate=55P03");
    assertThat(failing.events()).noneMatch(e -> e.equals("execute: " + PgRetention.HOLD_SQL));
    assertThat(failing.events()).contains("rollback").doesNotContain("commit");

    SqlSpy failingRecord = new SqlSpy().failStatementWith("pg_advisory_xact_lock", timeout);
    assertThatThrownBy(
            () ->
                new PgRetention(failingRecord.over(null), clock, config)
                    .recordRetention(List.of(claim("sealed/a", MIFIR, D2031))))
        .isInstanceOf(LedgerException.class)
        .hasMessageContaining("sqlstate=55P03");
    assertThat(statements(failingRecord)).containsExactly(PgRetention.ARTIFACT_LOCK_SQL);
    assertThat(failingRecord.events()).contains("rollback").doesNotContain("commit");
  }

  /**
   * The one honest limit of the artifact lock: two artifacts whose {@code hashtext} values collide
   * share one lock, and two calls that took a colliding pair in different orders can wait for each
   * other. The server detects that and ends one of them with SQLSTATE 40P01, which comes out as the
   * ledger exception of any statement failure: the unit is rolled back and nothing is written.
   */
  @Test
  void aDeadlockOnTheLockIsALedgerExceptionWithItsStateAndNothingIsWritten() {
    SQLException deadlock = new SQLException("deadlock detected", "40P01");
    SqlSpy failing = new SqlSpy().failStatementWith("pg_advisory_xact_lock", deadlock, 2);
    PgRetention retention = new PgRetention(failing.over(null), clock, config);

    assertThatThrownBy(
            () ->
                retention.recordRetention(
                    List.of(claim("sealed/a", MIFIR, D2031), claim("sealed/b", MIFIR, D2031))))
        .isInstanceOf(LedgerException.class)
        .hasMessage("ledger statement failed: sqlstate=40P01 constraint=none");
    assertThat(statements(failing)).doesNotContain(PgRetention.RECORD_SQL);
    assertThat(failing.events()).contains("rollback").doesNotContain("commit");
    assertThat(failing.openConnections()).isZero();
  }
}
