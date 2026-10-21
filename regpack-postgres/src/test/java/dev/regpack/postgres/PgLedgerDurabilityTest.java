package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.ledger.LedgerDurability.Status;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.postgres.PgLedgerStore.Config;
import dev.regpack.testkit.contract.LedgerDurabilityContract;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * What the durability gate of the ledger decides without a server (P3b1 T17, assumption A11, review
 * focus EF14): the pure rule {@link PgLedgerDurability#evaluate}, and the shape of the server read
 * through a fake server (one read-only unit, three statements, none with a value). That the
 * statements mean on a real server what this class assumes is shown first at CP-2 ({@code
 * PgDurabilityUpIT}, {@code PgDurabilityDownIT}); the rule that a synchronous status names a
 * standby is applied to every verdict of the rule here, which is the only place where the
 * synchronous branch of {@link LedgerDurabilityContract} is exercised in the job {@code ledger-pg},
 * because that job has no standby.
 */
class PgLedgerDurabilityTest {
  private static final Config FORCED = Config.forSchema("t42_7");
  private static final Config NOT_FORCED = FORCED.withForceSynchronousCommit(false);

  /** The verdict of the rule, checked against the shape rule of the contract suite as well. */
  private static Status evaluate(String commit, String names, int standbys) {
    Status status = PgLedgerDurability.evaluate(commit, names, standbys);
    assertThat(status.detail()).as("detail of %s %s %s", commit, names, standbys).isNotBlank();
    assertThat(status.synchronousStandbys()).isEqualTo(standbys);
    LedgerDurabilityContract.requireASynchronousStatusToNameAStandby(status);
    return status;
  }

  // ---------------------------------------------------------------------------------------------
  // the rule

  @Test
  void evaluateAQuorumWithOneStreamingStandbyIsSynchronousAndNamesTheSetting() {
    Status status = evaluate("on", "ANY 1 (s1)", 1);
    assertThat(status.synchronous()).isTrue();
    assertThat(status.synchronousStandbys()).isEqualTo(1);
    assertThat(status.detail()).contains("ANY 1 (s1)").contains("1 of 1");
  }

  @Test
  void evaluateWithNoStreamingStandbyIsNotSynchronousAndSaysZeroOfOne() {
    Status status = evaluate("on", "ANY 1 (s1)", 0);
    assertThat(status.synchronous()).isFalse();
    assertThat(status.synchronousStandbys()).isZero();
    assertThat(status.detail()).contains("0 of 1");
  }

  @Test
  void evaluateAnyCommitSettingOtherThanOnOrRemoteApplyIsNotSynchronous() {
    for (String commit : new String[] {"local", "remote_write", "off", "false", "", "x'; --"}) {
      Status status = evaluate(commit, "ANY 1 (s1)", 1);
      assertThat(status.synchronous()).as("synchronous_commit '%s'", commit).isFalse();
      assertThat(status.detail()).contains("synchronous_commit");
    }
    assertThat(evaluate("local", "ANY 1 (s1)", 1).detail()).contains("'local'");
    assertThat(evaluate("remote_write", "ANY 1 (s1)", 1).detail()).contains("'remote_write'");
    assertThat(evaluate("off", "ANY 1 (s1)", 1).detail()).contains("'off'");
  }

  @Test
  void evaluateRemoteApplyWithTheFirstTwoOfThreeNamedStandbys() {
    Status two = evaluate("remote_apply", "FIRST 2 (a, b, c)", 2);
    assertThat(two.synchronous()).isTrue();
    assertThat(two.detail()).contains("2 of 2");
    Status one = evaluate("remote_apply", "FIRST 2 (a, b, c)", 1);
    assertThat(one.synchronous()).isFalse();
    assertThat(one.detail()).contains("1 of 2");
  }

  @Test
  void evaluateBlankStandbyNamesAreNotSynchronous() {
    for (String names : new String[] {"", " ", "\t\n"}) {
      Status status = evaluate("on", names, 1);
      assertThat(status.synchronous()).as("names '%s'", names).isFalse();
      assertThat(status.detail()).contains("synchronous_standby_names is empty");
    }
  }

  @Test
  void evaluateAPlainListAndAStarNeedOneStandby() {
    assertThat(evaluate("on", "s1, s2", 1).synchronous()).isTrue();
    assertThat(evaluate("on", "s1, s2", 0).synchronous()).isFalse();
    assertThat(evaluate("on", "*", 1).synchronous()).isTrue();
    assertThat(evaluate("on", "*", 0).synchronous()).isFalse();
    assertThat(evaluate("on", "s1", 2).synchronous()).isTrue();
  }

  @Test
  void evaluateAQuorumOfTwoNeedsTwoStreamingStandbys() {
    Status status = evaluate("on", "ANY 2 (a, b)", 1);
    assertThat(status.synchronous()).isFalse();
    assertThat(status.detail()).contains("1 of 2");
    assertThat(evaluate("on", "ANY 2 (a, b)", 2).synchronous()).isTrue();
    assertThat(evaluate("on", "ANY 2 (a, b)", 3).synchronous()).isTrue();
  }

  /** The keywords and the bare number are read without regard to case, in every locale. */
  @Test
  void evaluateReadsTheKeywordsAndTheBareNumberCaseInsensitively() {
    for (String names :
        new String[] {
          "any 2 (a, b)", "Any 2 (a, b)", "FIRST 2 (a, b)", "first 2 (a, b)", "2 (a, b)"
        }) {
      assertThat(evaluate("on", names, 2).synchronous()).as(names).isTrue();
      assertThat(evaluate("on", names, 1).synchronous()).as(names).isFalse();
    }
    assertThat(evaluate("ON", "ANY 1 (s1)", 1).synchronous()).as("upper-case setting").isTrue();
    assertThat(evaluate(" Remote_Apply ", "ANY 1 (s1)", 1).synchronous()).isTrue();
    // the test JVM runs with the default locale tr_TR (the hostile environment of the build), where
    // a rule that folds case with the default locale would read "FIRST" and "ON" wrongly
    assertThat(evaluate("ON", "first 1 (s1)", 1).synchronous()).isTrue();
    assertThat(evaluate("REMOTE_APPLY", "FIRST 1 (s1)", 1).synchronous()).isTrue();
  }

  /** The head of the setting decides the count, whatever the names are (quoted names included). */
  @Test
  void evaluateReadsTheCountFromTheHeadAndNotFromTheNames() {
    assertThat(evaluate("on", "ANY 1 (\"standby (1)\", s2)", 1).synchronous()).isTrue();
    assertThat(evaluate("on", "ANY 2 (\"a, b\", c)", 1).synchronous()).isFalse();
    assertThat(evaluate("on", "s1 (x)", 1).synchronous()).as("a name, not a count").isTrue();
  }

  /** A count that is zero, not a number or beyond any server's range is never a verdict of yes. */
  @Test
  void evaluateAnUnreadableCountIsNotSynchronous() {
    for (String names :
        new String[] {
          "ANY 0 (a)",
          "0 (a)",
          "ANY 99999999999999999999 (a)",
          "ANY 2147483648 (a)",
          "ANY 2147483647 (a)"
        }) {
      Status status = evaluate("on", names, 1_000);
      assertThat(status.synchronous()).as(names).isFalse();
      assertThat(status.detail()).as(names).isNotBlank();
    }
  }

  @Test
  void evaluateDetailsCarryNoControlCharactersAndAreBounded() {
    String names = "ANY 1 (" + "x".repeat(5_000) + ")\n\u0000\u0007";
    Status status = evaluate("on", names, 0);
    assertThat(status.synchronous()).isFalse();
    assertThat(status.detail())
        .doesNotContain("\n")
        .doesNotContain("\u0000")
        .doesNotContain("\u0007")
        .hasSizeLessThan(1_000);
    String hostile = "local\n::error::pwned";
    assertThat(evaluate(hostile, "ANY 1 (s1)", 1).detail()).doesNotContain("\n");
  }

  @Test
  void evaluateRefusesNullsAndANegativeCount() {
    assertThatThrownBy(() -> PgLedgerDurability.evaluate(null, "ANY 1 (s1)", 1))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> PgLedgerDurability.evaluate("on", null, 1))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> PgLedgerDurability.evaluate("on", "ANY 1 (s1)", -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** Every combination of a verdict names at least one standby when it is a yes (the contract). */
  @Test
  void everyYesOfTheRuleNamesAtLeastOneStandby() {
    int yes = 0;
    for (String commit : new String[] {"on", "remote_apply", "local"}) {
      for (String names :
          new String[] {"", "s1", "*", "ANY 1 (a, b)", "ANY 2 (a, b)", "FIRST 3 (a, b, c)"}) {
        for (int standbys = 0; standbys <= 4; standbys++) {
          Status status = evaluate(commit, names, standbys);
          if (status.synchronous()) {
            yes++;
            assertThat(status.synchronousStandbys()).isGreaterThanOrEqualTo(1);
          }
        }
      }
    }
    assertThat(yes).as("the sweep reaches the synchronous branch").isGreaterThan(10);
  }

  // ---------------------------------------------------------------------------------------------
  // the server read, through a fake server

  /** A fake server for the three reads: the settings and the number of streaming standbys. */
  private static SqlSpy.Answers server(String commit, String names, long standbys) {
    return (sql, events) -> {
      if (sql.equals(PgLedgerDurability.COMMIT_SQL)) {
        return List.<Object[]>of(new Object[] {commit});
      }
      if (sql.equals(PgLedgerDurability.NAMES_SQL)) {
        return List.<Object[]>of(new Object[] {names});
      }
      if (sql.equals(PgLedgerDurability.STANDBYS_SQL)) {
        return List.<Object[]>of(new Object[] {standbys});
      }
      return List.<Object[]>of();
    };
  }

  private static List<String> statements(SqlSpy spy) {
    return spy.events().stream()
        .filter(e -> e.startsWith("execute: "))
        .map(e -> e.substring("execute: ".length()))
        .toList();
  }

  @Test
  void theServerReadIsOneReadOnlyUnitWithTheNamesAndTheStandbysAndNoValue() {
    SqlSpy spy = new SqlSpy().answering(server("local", "ANY 1 (standby1)", 1));
    Status status = new PgLedgerDurability(spy.over(null), FORCED).durability();

    assertThat(status.synchronous()).as(status.detail()).isTrue();
    assertThat(status.synchronousStandbys()).isEqualTo(1);
    assertThat(status.detail()).contains("ANY 1 (standby1)");
    List<String> executed = statements(spy);
    assertThat(executed.stream().filter(sql -> sql.startsWith("SELECT set_config"))).hasSize(1);
    assertThat(executed)
        .containsSubsequence(
            "SET TRANSACTION READ ONLY",
            PgLedgerDurability.NAMES_SQL,
            PgLedgerDurability.STANDBYS_SQL);
    assertThat(executed)
        .as("a unit that forces the commit setting reports it as on and does not ask the server")
        .doesNotContain(PgLedgerDurability.COMMIT_SQL);
    assertThat(spy.events()).containsOnlyOnce("commit").doesNotContain("rollback");
    assertThat(spy.events().stream().filter(e -> e.startsWith("bind ")))
        .as("only the prologue binds values")
        .hasSize(3);
    assertThat(spy.openConnections()).isZero();
  }

  @Test
  void theCommitSettingIsReadFromTheServerOnlyWhenTheUnitDoesNotForceIt() {
    SqlSpy local = new SqlSpy().answering(server("local", "ANY 1 (standby1)", 1));
    Status notSynchronous = new PgLedgerDurability(local.over(null), NOT_FORCED).durability();
    assertThat(notSynchronous.synchronous()).isFalse();
    assertThat(notSynchronous.detail()).contains("'local'");
    assertThat(statements(local))
        .containsSubsequence(
            "SET TRANSACTION READ ONLY",
            PgLedgerDurability.NAMES_SQL,
            PgLedgerDurability.STANDBYS_SQL,
            PgLedgerDurability.COMMIT_SQL);

    SqlSpy apply = new SqlSpy().answering(server("remote_apply", "ANY 1 (standby1)", 1));
    assertThat(new PgLedgerDurability(apply.over(null), NOT_FORCED).durability().synchronous())
        .isTrue();
  }

  @Test
  void theStatementsAreTheOnesOfThePlan() {
    assertThat(PgLedgerDurability.NAMES_SQL)
        .isEqualTo("SELECT setting FROM pg_settings WHERE name = 'synchronous_standby_names'");
    assertThat(PgLedgerDurability.STANDBYS_SQL)
        .isEqualTo(
            "SELECT count(*) FROM pg_stat_replication"
                + " WHERE state = 'streaming' AND sync_state IN ('sync', 'quorum')");
    assertThat(PgLedgerDurability.COMMIT_SQL)
        .isEqualTo("SELECT reset_val FROM pg_settings WHERE name = 'synchronous_commit'");
    for (String sql :
        new String[] {
          PgLedgerDurability.NAMES_SQL,
          PgLedgerDurability.STANDBYS_SQL,
          PgLedgerDurability.COMMIT_SQL
        }) {
      assertThat(sql).as(sql).startsWith("SELECT ").doesNotContain("?").doesNotContain(";");
    }
  }

  /** On a server without a standby: not synchronous, with a reason, and no exception. */
  @Test
  void aServerWithoutAStandbyAnswersNotSynchronousWithAReason() {
    SqlSpy spy = new SqlSpy().answering(server("on", "", 0));
    Status status = new PgLedgerDurability(spy.over(null), FORCED).durability();
    assertThat(status.synchronous()).isFalse();
    assertThat(status.synchronousStandbys()).isZero();
    assertThat(status.detail()).contains("synchronous_standby_names is empty");
  }

  @Test
  void aServerErrorIsALedgerExceptionAndNeverAnAnswer() {
    for (String failing :
        new String[] {
          PgLedgerDurability.NAMES_SQL, PgLedgerDurability.STANDBYS_SQL, "SET TRANSACTION READ ONLY"
        }) {
      SqlSpy spy =
          new SqlSpy()
              .answering(server("on", "ANY 1 (s1)", 1))
              .failStatementWith(failing, new SQLException("connection lost (secret)", "08006"));
      DataSource source = spy.over(null);
      assertThatThrownBy(() -> new PgLedgerDurability(source, FORCED).durability())
          .as(failing)
          .isInstanceOf(LedgerException.class)
          .hasMessage("ledger statement failed: sqlstate=08006 constraint=none");
      assertThat(spy.events()).contains("rollback").doesNotContain("commit");
      assertThat(spy.openConnections()).isZero();
    }
    SqlSpy notForced =
        new SqlSpy()
            .answering(server("on", "ANY 1 (s1)", 1))
            .failStatementWith(PgLedgerDurability.COMMIT_SQL, new SQLException("lost", "08006"));
    assertThatThrownBy(() -> new PgLedgerDurability(notForced.over(null), NOT_FORCED).durability())
        .isInstanceOf(LedgerException.class);
  }

  /** A server that answers no row at all (a fake that knows nothing) is an exception, not a no. */
  @Test
  void aReadThatReturnsNoRowIsALedgerException() {
    SqlSpy spy = new SqlSpy();
    assertThatThrownBy(() -> new PgLedgerDurability(spy.over(null), FORCED).durability())
        .isInstanceOf(LedgerException.class)
        .hasMessageContaining("durability");
  }

  /** The read is a unit like any other: a warning of its commit fails it too (EF15). */
  @Test
  void theReadIsSubjectToTheCommitWarningCheck() {
    SqlSpy spy =
        new SqlSpy()
            .answering(server("on", "ANY 1 (s1)", 1))
            .warnOnCommit(new SQLWarning("a warning", "01000"));
    assertThatThrownBy(() -> new PgLedgerDurability(spy.over(null), FORCED).durability())
        .isInstanceOf(LedgerException.class)
        .hasMessageStartingWith("commit not confirmed by the synchronous standby");
  }

  @Test
  void theAdapterRefusesNullsAtConstruction() {
    SqlSpy spy = new SqlSpy();
    assertThatThrownBy(() -> new PgLedgerDurability(null, FORCED))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new PgLedgerDurability(spy.over(null), null))
        .isInstanceOf(NullPointerException.class);
    assertThat(spy.events()).isEmpty();
  }
}
