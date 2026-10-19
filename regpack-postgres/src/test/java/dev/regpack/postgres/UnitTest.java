package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.postgres.PgLedgerStore.Config;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
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
      assertThatThrownBy(() -> new Config("t1_1", true, true, tooLong, Duration.ofSeconds(1)))
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
            () -> new Config(null, true, true, Duration.ofSeconds(1), Duration.ofDays(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
