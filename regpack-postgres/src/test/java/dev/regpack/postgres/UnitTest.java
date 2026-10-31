package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.ledger.DeliveryLedger.AttemptId;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.postgres.PgLedgerStore.Config;
import dev.regpack.testkit.clock.ManualClock;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Convention 11 and 12, review focus LF19, without a server: the prologue of a unit, the order of
 * commit, rollback, auto-commit restore and close, and the shape of the exception that a failed
 * statement becomes. The server-side effect of the prologue (the SHOWs inside a unit) is
 * PgLedgerStoreIT#sessionDefaultsAreOverriddenInsideAUnit.
 */
class UnitTest {
  private static final String SCHEMA = "t42_7";
  private static final Config CONFIG = Config.forSchema(SCHEMA);

  /** A server error as the driver builds it from the wire: one letter, its value, a NUL. */
  private static PSQLException server(String sqlState, String constraint, String message) {
    String wire =
        "SERROR\0C"
            + sqlState
            + "\0M"
            + message
            + "\0DKey (batch_id)=(secret-value) already exists.\0n"
            + constraint
            + "\0";
    return new PSQLException(new ServerErrorMessage(wire));
  }

  @Test
  void aSuccessfulUnitSetsTheSessionCommitsOnceAndGivesTheConnectionBackAsItCame() {
    SqlSpy spy = new SqlSpy();
    String result =
        Unit.inUnit(
            spy.over(null),
            CONFIG,
            connection -> {
              spy.mark("work");
              return "done";
            });
    assertThat(result).isEqualTo("done");
    assertThat(spy.events())
        .containsSubsequence(
            "open",
            "setAutoCommit(false)",
            "execute: SET TRANSACTION ISOLATION LEVEL READ COMMITTED",
            "bind 1=" + SCHEMA,
            "work",
            "commit",
            "setAutoCommit(true)",
            "close");
    assertThat(spy.events()).containsOnlyOnce("commit").doesNotContain("rollback");
    assertThat(spy.openConnections()).isZero();
  }

  @Test
  void theSchemaAndTheTimeoutsAreBoundNeverConcatenated() {
    SqlSpy spy = new SqlSpy();
    Unit.inUnit(spy.over(null), CONFIG, connection -> "x");
    assertThat(spy.events())
        .contains("bind 1=" + SCHEMA, "bind 2=5000ms", "bind 3=60000ms")
        .noneMatch(e -> e.startsWith("execute: ") && e.contains(SCHEMA));
    SqlSpy custom = new SqlSpy();
    Config tuned =
        CONFIG.withLockTimeout(Duration.ofMillis(250)).withStatementTimeout(Duration.ofMinutes(5));
    Unit.inUnit(custom.over(null), tuned, connection -> "x");
    assertThat(custom.events()).contains("bind 2=250ms", "bind 3=300000ms");
  }

  @Test
  void synchronousCommitIsForcedOnUnlessTheConfigSaysOtherwise() {
    SqlSpy forced = new SqlSpy();
    Unit.inUnit(forced.over(null), CONFIG, connection -> "x");
    assertThat(forced.events())
        .anyMatch(e -> e.startsWith("execute: SELECT set_config") && e.contains("'on'"));
    SqlSpy left = new SqlSpy();
    Unit.inUnit(left.over(null), CONFIG.withForceSynchronousCommit(false), connection -> "x");
    assertThat(left.events())
        .anyMatch(e -> e.startsWith("execute: SELECT set_config") && e.contains("'UTC'"))
        .noneMatch(e -> e.startsWith("execute: SELECT set_config") && e.contains("synchronous"));
  }

  /**
   * TEETH-9: every setting of the prologue is transaction-local (the third argument of {@code
   * set_config} is {@code true}), so nothing of the ledger leaks into the next user of a pooled
   * connection.
   */
  @Test
  void everySettingOfThePrologueIsTransactionLocal() {
    for (boolean synchronous : new boolean[] {true, false}) {
      SqlSpy spy = new SqlSpy();
      Unit.inUnit(
          spy.over(null), CONFIG.withForceSynchronousCommit(synchronous), connection -> "x");
      List<String> settings =
          spy.events().stream().filter(e -> e.startsWith("execute: SELECT set_config")).toList();
      assertThat(settings).hasSize(1);
      java.util.regex.Matcher calls =
          java.util.regex.Pattern.compile("set_config\\(([^()]*)\\)").matcher(settings.get(0));
      List<String> names = new java.util.ArrayList<>();
      while (calls.find()) {
        String arguments = calls.group(1);
        assertThat(arguments).as(arguments).endsWith(", true");
        names.add(arguments.substring(0, arguments.indexOf(',')));
      }
      assertThat(names)
          .contains("'TimeZone'", "'search_path'", "'lock_timeout'", "'statement_timeout'");
      if (synchronous) {
        assertThat(names).contains("'synchronous_commit'");
      } else {
        assertThat(names).doesNotContain("'synchronous_commit'");
      }
      // and no statement sets anything for the session
      assertThat(spy.events())
          .noneMatch(e -> e.toUpperCase(java.util.Locale.ROOT).contains("SET SESSION"))
          .noneMatch(e -> e.startsWith("execute: SET ") && !e.contains("SET TRANSACTION"))
          .noneMatch(e -> e.contains("ALTER ROLE") || e.contains("ALTER DATABASE"));
    }
  }

  @Test
  void aFailingUnitRollsBackClosesAndNeverTouchesAutoCommitAgain() {
    SqlSpy spy = new SqlSpy();
    IllegalStateException boom = new IllegalStateException("boom");
    assertThatThrownBy(
            () ->
                Unit.inUnit(
                    spy.over(null),
                    CONFIG,
                    connection -> {
                      throw boom;
                    }))
        .isSameAs(boom);
    List<String> events = spy.events();
    assertThat(events).containsSubsequence("setAutoCommit(false)", "rollback", "close");
    // restoring auto-commit after a failed rollback would commit what the unit wrote
    assertThat(events).doesNotContain("commit", "setAutoCommit(true)");
    assertThat(spy.openConnections()).isZero();
  }

  @Test
  void aServerErrorBecomesALedgerExceptionWithOnlyTheStateAndTheConstraint() {
    SqlSpy spy = new SqlSpy();
    PSQLException failure = server("23505", "ingest_pk", "duplicate key value (secret-value)");
    assertThatThrownBy(
            () ->
                Unit.inUnit(
                    spy.over(null),
                    CONFIG,
                    connection -> {
                      throw failure;
                    }))
        .isInstanceOf(LedgerException.class)
        .hasMessage("ledger statement failed: sqlstate=23505 constraint=ingest_pk")
        .hasCause(failure);
    assertThat(spy.events()).contains("rollback", "close").doesNotContain("commit");
    assertThat(spy.openConnections()).isZero();
  }

  @Test
  void aFailedCommitIsALedgerExceptionAndTheConnectionIsClosedWithoutRestoringAutoCommit() {
    SqlSpy spy = new SqlSpy().failCommitWith(new SQLException("connection lost", "08006"));
    assertThatThrownBy(() -> Unit.inUnit(spy.over(null), CONFIG, connection -> "x"))
        .isInstanceOf(LedgerException.class)
        .hasMessage("ledger statement failed: sqlstate=08006 constraint=none");
    assertThat(spy.events()).containsSubsequence("commit", "rollback", "close");
    assertThat(spy.events()).doesNotContain("setAutoCommit(true)");
    assertThat(spy.openConnections()).isZero();
  }

  @Test
  void aConnectionThatCannotBeOpenedIsALedgerExceptionNotAnAnswer() {
    SqlSpy spy = new SqlSpy().failOpenWith(new SQLException("refused (secret-host)", "08001"));
    assertThatThrownBy(() -> Unit.inUnit(spy.over(null), CONFIG, connection -> "x"))
        .isInstanceOf(LedgerException.class)
        .hasMessage("ledger statement failed: sqlstate=08001 constraint=none");
    assertThat(spy.events()).isEmpty();
  }

  @Test
  void aRuntimeFailureInsideTheUnitKeepsItsOwnTypeAndMessage() {
    SqlSpy spy = new SqlSpy();
    assertThatThrownBy(
            () ->
                Unit.inUnit(
                    spy.over(null),
                    CONFIG,
                    connection -> {
                      throw new IllegalArgumentException("bad input");
                    }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("bad input");
  }

  /**
   * lock_timeout and statement_timeout are 32-bit millisecond settings: a larger value would be
   * refused by the server at the first unit (SQLSTATE 22023), so the Config refuses it at once.
   */
  @Test
  void theConfigRefusesATimeoutAboveTheThirtyTwoBitMillisecondRange() {
    Duration largest = Duration.ofMillis(Integer.MAX_VALUE);
    assertThat(CONFIG.withLockTimeout(largest).lockTimeout()).isEqualTo(largest);
    assertThat(CONFIG.withStatementTimeout(largest).statementTimeout()).isEqualTo(largest);
    for (Duration tooLong :
        new Duration[] {
          largest.plusMillis(1),
          Duration.ofDays(30),
          Duration.ofSeconds(Long.MAX_VALUE),
          Duration.ofSeconds(Long.MAX_VALUE, 999_000_000)
        }) {
      assertThatThrownBy(() -> CONFIG.withLockTimeout(tooLong))
          .as("lockTimeout %s", tooLong)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("lockTimeout");
      assertThatThrownBy(() -> CONFIG.withStatementTimeout(tooLong))
          .as("statementTimeout %s", tooLong)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("statementTimeout");
      assertThatThrownBy(
              () -> new Config("t1_1", true, true, tooLong, Duration.ofSeconds(1), false))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void theConfigRefusesABadSchemaAndTimeoutsThatAreNotPositiveWholeMilliseconds() {
    assertThat(CONFIG.partitionsOnDemand()).isTrue();
    assertThat(CONFIG.forceSynchronousCommit()).isTrue();
    assertThat(CONFIG.lockTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(CONFIG.statementTimeout()).isEqualTo(Duration.ofSeconds(60));
    for (String bad : new String[] {"", "Public", "a-b", "1abc", "x".repeat(64), "a;drop"}) {
      assertThatThrownBy(() -> Config.forSchema(bad))
          .as("schema '%s'", bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> CONFIG.withLockTimeout(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CONFIG.withStatementTimeout(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CONFIG.withLockTimeout(Duration.ofNanos(1_500_000)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Config(null, true, true, Duration.ofSeconds(1), Duration.ofDays(1), false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * P3c1 T12: the sixth component, {@code requireEnvelopeBodies}, is false for every configuration
   * but the production one, and the production one is {@code forSchema} with it on.
   */
  @Test
  void theProductionConfigRequiresEnvelopeBodiesAndNoOtherConfigDoes() {
    assertThat(Config.production(SCHEMA).requireEnvelopeBodies()).isTrue();
    assertThat(CONFIG.requireEnvelopeBodies()).isFalse();
    assertThat(
            new Config(SCHEMA, true, true, Duration.ofSeconds(5), Duration.ofSeconds(60), true)
                .requireEnvelopeBodies())
        .isTrue();
    assertThat(
            new Config(SCHEMA, true, true, Duration.ofSeconds(5), Duration.ofSeconds(60), false)
                .requireEnvelopeBodies())
        .isFalse();
    assertThat(Config.production(SCHEMA).withEnvelopeBodiesRequired(false)).isEqualTo(CONFIG);
    assertThat(CONFIG.withEnvelopeBodiesRequired(true)).isEqualTo(Config.production(SCHEMA));
  }

  // ------------------------------------------------------------------------------------------
  // The warning after COMMIT (P3b1 T17, assumption A11, review focus EF15). On a primary with a
  // synchronous standby, a wait that is cancelled (query cancel) ends with a WARNING and the
  // commit returns normally: the transaction has committed locally and may not be on the standby.
  // pgjdbc hands that warning to Connection#getWarnings (its COMMIT handler adds it). The unit
  // refuses every warning after the commit whose SQLSTATE is not of class 00 (a NOTICE), so that
  // no SQLSTATE list and no message text is matched.

  private static final String UNCONFIRMED = "commit not confirmed by the synchronous standby";

  private static String unconfirmed(String shownState) {
    return UNCONFIRMED + " (sqlstate=" + shownState + ")";
  }

  private static SQLWarning warning(String sqlState) {
    return new SQLWarning("a warning of the server", sqlState);
  }

  /**
   * Whatever the SQLSTATE of a warning after the commit, even none, the unit fails; the message
   * carries the SQLSTATE only when it has the shape of one, and the work has run and committed.
   */
  @Test
  void aWarningAfterCommitFailsTheUnitWhateverItsSqlstate() {
    String[] states = {
      "01000", "57014", "57P01", "25P01", "01P01", "0", "", "a secret value", null
    };
    for (String state : states) {
      SqlSpy spy = new SqlSpy().warnOnCommit(warning(state));
      AtomicInteger ran = new AtomicInteger();
      String shown = state != null && state.matches("[0-9A-Z]{5}") ? state : "none";
      assertThatThrownBy(
              () ->
                  Unit.inUnit(
                      spy.over(null),
                      CONFIG,
                      connection -> {
                        ran.incrementAndGet();
                        return "done";
                      }))
          .as("sqlstate %s", state)
          .isInstanceOf(LedgerException.class)
          .hasMessage(unconfirmed(shown));
      assertThat(ran).as("the work ran").hasValue(1);
      assertThat(spy.events()).as("sqlstate %s", state).containsOnlyOnce("commit");
      assertThat(spy.openConnections()).isZero();
    }
  }

  /**
   * The case of the plan: the driver reports the cancelled wait with SQLSTATE 01000. The
   * transaction has committed, so the connection is released as committed (auto-commit restored, no
   * rollback), and the warning is the cause of the exception.
   */
  @Test
  void aWarning01000AfterCommitFailsTheUnit() {
    SQLWarning cancelled = warning("01000");
    SqlSpy spy = new SqlSpy().warnOnCommit(cancelled);
    assertThatThrownBy(() -> Unit.inUnit(spy.over(null), CONFIG, connection -> "x"))
        .isInstanceOf(LedgerException.class)
        .hasMessage(unconfirmed("01000"))
        .hasCause(cancelled);
    assertThat(spy.events())
        .containsSubsequence("commit", "getWarnings", "setAutoCommit(true)", "close")
        .doesNotContain("rollback");
    assertThat(spy.openConnections()).isZero();
  }

  /**
   * A warning that a statement of the work raised is the statement's, not the commit's: it is
   * dropped immediately before the commit (and again when the unit begins), so only what the commit
   * itself raised can fail the unit.
   */
  @Test
  void aWarningRaisedBeforeTheCommitIsClearedAndDoesNotFailTheUnit() {
    SqlSpy spy = new SqlSpy();
    String result =
        Unit.inUnit(
            spy.over(null),
            CONFIG,
            connection -> {
              spy.raise(warning("01000"));
              spy.mark("work");
              return "done";
            });
    assertThat(result).isEqualTo("done");
    assertThat(spy.events())
        .containsSubsequence("clearWarnings", "work", "clearWarnings", "commit", "getWarnings")
        .doesNotContain("rollback");
  }

  /** The driver reports a NOTICE with SQLSTATE 00000: class 00 is not a warning of the commit. */
  @Test
  void aClass00NoticeAfterCommitIsIgnored() {
    SQLWarning notice = warning("00000");
    notice.setNextWarning(warning("00001"));
    SqlSpy spy = new SqlSpy().warnOnCommit(notice);
    String result = Unit.inUnit(spy.over(null), CONFIG, connection -> "done");
    assertThat(result).isEqualTo("done");
    assertThat(spy.events()).containsSubsequence("commit", "getWarnings", "setAutoCommit(true)");
    assertThat(spy.events()).doesNotContain("rollback");
  }

  /** The whole chain is walked: a warning behind a notice still fails the unit. */
  @Test
  void aWarningLaterInTheChainIsFoundToo() {
    SQLWarning notice = warning("00000");
    notice.setNextWarning(warning("01000"));
    SqlSpy spy = new SqlSpy().warnOnCommit(notice);
    assertThatThrownBy(() -> Unit.inUnit(spy.over(null), CONFIG, connection -> "x"))
        .isInstanceOf(LedgerException.class)
        .hasMessage(unconfirmed("01000"));
  }

  /**
   * A commit that raised nothing succeeds. The driver has no empty chain: {@code getWarnings}
   * returns null, and the unit must have looked (the event), or the check would be a no-op.
   */
  @Test
  void aCommitWithoutWarningSucceeds() {
    SqlSpy spy = new SqlSpy();
    String result = Unit.inUnit(spy.over(null), CONFIG, connection -> "done");
    assertThat(result).isEqualTo("done");
    assertThat(spy.events())
        .containsSubsequence("commit", "getWarnings", "setAutoCommit(true)", "close");
    assertThat(spy.events()).doesNotContain("rollback");
    assertThat(spy.openConnections()).isZero();
  }

  /**
   * The check is part of every unit, not of the first one: a unit of the adapter (here the attempt
   * marker, and an attempt id) that commits cleanly is a success, and the same unit fails once the
   * commit raises a warning.
   */
  @Test
  void aWarningAfterTheCommitOfASecondUnitFailsThatUnitToo() {
    SqlSpy spy =
        new SqlSpy()
            .answering(
                (sql, events) ->
                    sql.equals(PgDeliveries.ALLOCATE_SQL)
                        ? List.<Object[]>of(new Object[] {7L})
                        : List.<Object[]>of());
    PgDeliveries deliveries =
        new PgDeliveries(
            spy.over(null), new ManualClock(Instant.parse("2026-10-09T08:00:00Z")), CONFIG);

    assertThat(deliveries.allocateAttemptId()).isEqualTo(new AttemptId(7));

    spy.warnOnCommit(warning("01000"));
    assertThatThrownBy(() -> deliveries.markCommitIssued(new AttemptId(7), new LeaseToken(0, 1)))
        .isInstanceOf(LedgerException.class)
        .hasMessage(unconfirmed("01000"));
    assertThatThrownBy(deliveries::allocateAttemptId)
        .isInstanceOf(LedgerException.class)
        .hasMessage(unconfirmed("01000"));
    assertThat(spy.openConnections()).isZero();
  }

  /**
   * A leftover warning of an earlier use of a pooled connection is gone when the work starts, and
   * the clear comes before anything else of the unit.
   */
  @Test
  void warningsAreClearedWhenTheUnitBegins() {
    SqlSpy spy = new SqlSpy();
    spy.raise(warning("01000"));
    AtomicReference<SQLWarning> seen = new AtomicReference<>(warning("99999"));
    String result =
        Unit.inUnit(
            spy.over(null),
            CONFIG,
            connection -> {
              seen.set(connection.getWarnings());
              spy.mark("work");
              return "done";
            });
    assertThat(result).isEqualTo("done");
    assertThat(seen.get() == null).as("the leftover warning is gone").isTrue();
    assertThat(spy.events())
        .containsSubsequence(
            "open",
            "setAutoCommit(false)",
            "clearWarnings",
            "execute: SET TRANSACTION ISOLATION LEVEL READ COMMITTED",
            "work");
  }

  /**
   * After a commit whose warnings cannot be read the outcome is unknown, and an unknown outcome is
   * a failure (the connection is released as committed: the commit itself had returned).
   */
  @Test
  void warningsThatCannotBeReadAfterTheCommitFailTheUnit() {
    SqlSpy spy = new SqlSpy().failGetWarningsWith(new SQLException("connection closed", "08003"));
    DataSource source = spy.over(null);
    assertThatThrownBy(() -> Unit.inUnit(source, CONFIG, connection -> "x"))
        .isInstanceOf(LedgerException.class)
        .hasMessage("ledger statement failed: sqlstate=08003 constraint=none");
    assertThat(spy.events())
        .containsSubsequence("commit", "getWarnings", "setAutoCommit(true)", "close")
        .doesNotContain("rollback");
  }

  /** A failed commit is still the exception of the driver; there is nothing to read after it. */
  @Test
  void aFailedCommitIsNeverFollowedByAReadOfTheWarnings() {
    SqlSpy spy =
        new SqlSpy()
            .failCommitWith(new SQLException("connection lost", "08006"))
            .warnOnCommit(warning("01000"));
    assertThatThrownBy(() -> Unit.inUnit(spy.over(null), CONFIG, connection -> "x"))
        .isInstanceOf(LedgerException.class)
        .hasMessage("ledger statement failed: sqlstate=08006 constraint=none");
    assertThat(spy.events()).doesNotContain("getWarnings");
  }

  // ---------------------------------------------------------------------------------------------
  // the durability barrier (I1, acknowledgement limb)

  private static final SqlSpy.Answers BARRIER_ROW =
      (sql, events) ->
          sql.equals(Unit.BARRIER_SQL)
              ? Collections.<Object[]>singletonList(new Object[] {1})
              : List.of();

  @Test
  void theBarrierIsAShareLockOnTheRestoreEpochRowAndRunsBeforeTheCommit() {
    SqlSpy spy = new SqlSpy().answering(BARRIER_ROW);

    Unit.inUnit(
        spy.over(null),
        CONFIG,
        connection -> {
          Unit.durabilityBarrier(connection);
          return "x";
        });

    assertThat(Unit.BARRIER_SQL)
        .as("a row lock is WAL-logged, a bare transaction id is not")
        .isEqualTo("SELECT 1 FROM restore_epoch WHERE singleton FOR SHARE");
    assertThat(spy.events())
        .containsSubsequence("execute: " + Unit.BARRIER_SQL, "commit", "setAutoCommit(true)");
    assertThat(spy.events()).containsOnlyOnce("execute: " + Unit.BARRIER_SQL);
  }

  @Test
  void aUnitAddsNoBarrierOfItsOwn() {
    SqlSpy spy = new SqlSpy().answering(BARRIER_ROW);

    Unit.inUnit(spy.over(null), CONFIG, connection -> "x");

    assertThat(spy.events()).doesNotContain("execute: " + Unit.BARRIER_SQL);
  }

  @Test
  void aBarrierThatLockedNoRowFailsTheUnitAndRollsBack() {
    SqlSpy spy = new SqlSpy();

    assertThatThrownBy(
            () ->
                Unit.inUnit(
                    spy.over(null),
                    CONFIG,
                    connection -> {
                      Unit.durabilityBarrier(connection);
                      return "x";
                    }))
        .isInstanceOf(LedgerException.class)
        .hasMessage("the restore epoch row is missing");
    assertThat(spy.events()).contains("rollback").doesNotContain("commit");
  }
}
