package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.postgres.LedgerIntegrity.Kind;
import dev.regpack.postgres.LedgerIntegrity.Violation;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The parts of {@link LedgerIntegrity} that need no server: the argument checks, the read-only
 * snapshot prologue and the order of commit, auto-commit restore and close (against the recording
 * fake server of {@link SqlSpy}), the rule that a lost connection is an exception and never a clean
 * ledger (review focus LF19), and the cut of a long list. What the four queries find is {@code
 * LedgerIntegrityIT}, which runs on a real PostgreSQL.
 */
class LedgerIntegrityTest {
  private static final String SCHEMA = "t42_7";
  private static final LocalDate FROM = LocalDate.of(2026, 10, 9);
  private static final LocalDate TO = LocalDate.of(2026, 10, 10);

  @Test
  void theArgumentsAreCheckedBeforeAConnectionIsOpened() {
    SqlSpy spy = new SqlSpy();
    assertThatThrownBy(() -> LedgerIntegrity.check(null, SCHEMA, FROM, TO))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> LedgerIntegrity.check(spy.over(null), null, FROM, TO))
        .isInstanceOf(IllegalArgumentException.class);
    for (String bad : new String[] {"", "Public", "a-b", "1abc", "x".repeat(64), "a;drop"}) {
      assertThatThrownBy(() -> LedgerIntegrity.check(spy.over(null), bad, FROM, TO))
          .as("schema '%s'", bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> LedgerIntegrity.check(spy.over(null), SCHEMA, null, TO))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> LedgerIntegrity.check(spy.over(null), SCHEMA, FROM, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> LedgerIntegrity.check(spy.over(null), SCHEMA, TO, FROM))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(spy.events()).as("no connection was opened").isEmpty();
  }

  @Test
  void aOneDayRangeIsAllowed() {
    SqlSpy spy = new SqlSpy();
    assertThat(LedgerIntegrity.check(spy.over(null), SCHEMA, FROM, FROM)).isEmpty();
  }

  @Test
  void aCheckRunsItsFourQueriesInOneReadOnlySnapshotAndGivesTheConnectionBack() {
    SqlSpy spy = new SqlSpy();
    List<Violation> found = LedgerIntegrity.check(spy.over(null), SCHEMA, FROM, TO);
    assertThat(found).isEmpty();
    List<String> events = spy.events();
    assertThat(events)
        .containsSubsequence(
            "open",
            "setAutoCommit(false)",
            "execute: SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY",
            "bind 1=" + SCHEMA,
            "commit",
            "setAutoCommit(true)",
            "close");
    // the snapshot statement, the session settings, then one query per kind: nothing else
    assertThat(spy.statements()).isEqualTo(6);
    List<String> queries =
        events.stream()
            .filter(e -> e.startsWith("execute: "))
            .filter(e -> !e.startsWith("execute: SET TRANSACTION") && !e.contains("set_config"))
            .toList();
    assertThat(queries).hasSize(4);
    for (String query : queries) {
      assertThat(query).startsWith("execute: SELECT ").doesNotContain(" INSERT ", " UPDATE ");
    }
    assertThat(events).doesNotContain("rollback");
    assertThat(spy.openConnections()).isZero();
  }

  @Test
  void theSchemaAndTheLockTimeoutAreBoundNeverConcatenated() {
    SqlSpy spy = new SqlSpy();
    LedgerIntegrity.check(spy.over(null), SCHEMA, FROM, TO);
    assertThat(spy.events())
        .contains("bind 1=" + SCHEMA, "bind 2=5000ms")
        .noneMatch(e -> e.startsWith("execute: ") && e.contains(SCHEMA))
        .noneMatch(e -> e.startsWith("execute: ") && e.contains("2026"));
    // a read of the whole history must not be cut by the statement timeout of a write unit
    assertThat(spy.events()).noneMatch(e -> e.contains("statement_timeout"));
  }

  @Test
  void aConnectionThatCannotBeOpenedIsALedgerExceptionNotACleanLedger() {
    SqlSpy spy = new SqlSpy().failOpenWith(new SQLException("refused (secret-host)", "08001"));
    assertThatThrownBy(() -> LedgerIntegrity.check(spy.over(null), SCHEMA, FROM, TO))
        .isInstanceOf(LedgerException.class)
        .hasMessage("ledger statement failed: sqlstate=08001 constraint=none");
    assertThat(spy.events()).isEmpty();
  }

  @Test
  void aFailedCommitIsALedgerExceptionAndTheConnectionIsClosedWithoutRestoringAutoCommit() {
    SqlSpy spy = new SqlSpy().failCommitWith(new SQLException("connection lost", "08006"));
    assertThatThrownBy(() -> LedgerIntegrity.check(spy.over(null), SCHEMA, FROM, TO))
        .isInstanceOf(LedgerException.class)
        .hasMessage("ledger statement failed: sqlstate=08006 constraint=none");
    assertThat(spy.events()).containsSubsequence("commit", "rollback", "close");
    assertThat(spy.events()).doesNotContain("setAutoCommit(true)");
    assertThat(spy.openConnections()).isZero();
  }

  @Test
  void aViolationNeedsAKindAndADetail() {
    assertThat(new Violation(Kind.VERSION_GAP, "subject_id=1").kind()).isEqualTo(Kind.VERSION_GAP);
    assertThatThrownBy(() -> new Violation(null, "x")).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Violation(Kind.VERSION_GAP, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Violation(Kind.VERSION_GAP, " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static List<Violation> violations(int count) {
    List<Violation> list = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      list.add(new Violation(Kind.DECISION_WITHOUT_CONTEXT, "decision_id=" + i));
    }
    return list;
  }

  @Test
  void aListLongerThanTheLimitIsCutAndMarked() {
    int limit = LedgerIntegrity.MAX_VIOLATIONS_PER_KIND;
    List<Violation> cut =
        LedgerIntegrity.capped(Kind.DECISION_WITHOUT_CONTEXT, violations(limit + 1));
    assertThat(cut).hasSize(limit + 1);
    assertThat(cut.subList(0, limit)).isEqualTo(violations(limit));
    Violation marker = cut.get(limit);
    assertThat(marker.kind()).isEqualTo(Kind.DECISION_WITHOUT_CONTEXT);
    assertThat(marker.detail()).contains("more than " + limit).contains("not listed");
  }

  @Test
  void aListOfExactlyTheLimitIsNotMarked() {
    int limit = LedgerIntegrity.MAX_VIOLATIONS_PER_KIND;
    List<Violation> same = violations(limit);
    assertThat(LedgerIntegrity.capped(Kind.DECISION_WITHOUT_CONTEXT, same)).isEqualTo(same);
    assertThat(LedgerIntegrity.capped(Kind.DECISION_WITHOUT_CONTEXT, List.of())).isEmpty();
  }
}
