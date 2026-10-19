package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.postgres.PgErrors.Violation;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Convention 6, 11 and 12: errors are classified by SQLState and constraint name, never by text.
 */
class PgErrorsTest {
  /** A server error as the driver builds it from the wire: one letter, its value, a NUL. */
  private static PSQLException server(
      String sqlState, String constraint, String message, String detail) {
    StringBuilder wire = new StringBuilder();
    wire.append('S').append("ERROR").append('\0');
    wire.append('C').append(sqlState).append('\0');
    wire.append('M').append(message).append('\0');
    if (detail != null) {
      wire.append('D').append(detail).append('\0');
    }
    if (constraint != null) {
      wire.append('n').append(constraint).append('\0');
    }
    return new PSQLException(new ServerErrorMessage(wire.toString()));
  }

  @Test
  void sqlStatesAreClassifiedAndTheConstraintNameIsKept() {
    PSQLException unique = server("23505", "decision_idem_key", "duplicate key", null);
    assertThat(PgErrors.classify(unique)).isEqualTo(Violation.UNIQUE);
    assertThat(PgErrors.constraint(unique)).contains("decision_idem_key");

    PSQLException foreign = server("23503", "decision_context_fk", "violates foreign key", null);
    assertThat(PgErrors.classify(foreign)).isEqualTo(Violation.FOREIGN_KEY);
    assertThat(PgErrors.constraint(foreign)).contains("decision_context_fk");

    PSQLException check = server("23514", "restore_epoch_singleton", "violates check", null);
    assertThat(PgErrors.classify(check)).isEqualTo(Violation.CHECK);
    assertThat(PgErrors.constraint(check)).contains("restore_epoch_singleton");

    PSQLException serialization = server("40001", null, "could not serialize", null);
    assertThat(PgErrors.classify(serialization)).isEqualTo(Violation.SERIALIZATION);
    assertThat(PgErrors.constraint(serialization)).isEmpty();

    assertThat(PgErrors.classify(server("22001", null, "value too long", null)))
        .isEqualTo(Violation.OTHER);
    assertThat(PgErrors.classify(server("55000", null, "reject_modification", null)))
        .isEqualTo(Violation.OTHER);
    assertThat(PgErrors.classify(new SQLException("no state"))).isEqualTo(Violation.OTHER);
    assertThat(PgErrors.classify(new SQLException("x", "ZZZZZ"))).isEqualTo(Violation.OTHER);
  }

  @Test
  void constraintNameIsReadFromTheServerErrorMessage() {
    // the text of the message names another constraint; only the server field counts
    PSQLException e =
        server(
            "23505",
            "report_subject_key",
            "duplicate key value violates unique constraint \"some_other_name\"",
            null);
    assertThat(PgErrors.constraint(e)).contains("report_subject_key");
    // a plain SQLException has no server message, whatever its text says
    assertThat(
            PgErrors.constraint(
                new SQLException("violates unique constraint \"decision_pk\"", "23505")))
        .isEmpty();
    // a wrapper around the server error still yields it
    assertThat(PgErrors.constraint(new SQLException("batch failed", "23505", e)))
        .contains("report_subject_key");
    assertThat(PgErrors.classify(new SQLException("batch failed", e))).isEqualTo(Violation.UNIQUE);
  }

  @Test
  void connectionClassAndShutdownCodesAreConnection() {
    for (String state :
        new String[] {
          "08000", "08001", "08003", "08006", "08P01", "57P01", "57P02", "57P03", "53300"
        }) {
      assertThat(PgErrors.classify(new SQLException("down", state)))
          .as(state)
          .isEqualTo(Violation.CONNECTION);
    }
    assertThat(PgErrors.classify(server("57P01", null, "terminating connection", null)))
        .isEqualTo(Violation.CONNECTION);
    // drivers and pools report a lost connection without a SQLState
    assertThat(PgErrors.classify(new SQLNonTransientConnectionException("closed")))
        .isEqualTo(Violation.CONNECTION);
    assertThat(PgErrors.classify(new SQLTransientConnectionException("pool timeout")))
        .isEqualTo(Violation.CONNECTION);
    // near misses are not connection errors
    assertThat(PgErrors.classify(new SQLException("x", "57014"))).isEqualTo(Violation.OTHER);
    assertThat(PgErrors.classify(new SQLException("x", "53200"))).isEqualTo(Violation.OTHER);
  }

  @Test
  void deadlockAndSerializationAreDistinct() {
    assertThat(PgErrors.classify(new SQLException("d", "40P01"))).isEqualTo(Violation.DEADLOCK);
    assertThat(PgErrors.classify(new SQLException("s", "40001")))
        .isEqualTo(Violation.SERIALIZATION);
    assertThat(PgErrors.classify(new SQLException("x", "40002"))).isEqualTo(Violation.OTHER);
  }

  @Test
  void noPartitionHasNoConstraintName() {
    // "no partition of relation found for row" is SQLState 23514 with no constraint field
    PSQLException noPartition =
        server("23514", null, "no partition of relation \"decision\" found for row", null);
    assertThat(PgErrors.classify(noPartition)).isEqualTo(Violation.NO_PARTITION);
    assertThat(PgErrors.constraint(noPartition)).isEmpty();
    assertThat(PgErrors.classify(new SQLException("no partition", "23514")))
        .isEqualTo(Violation.NO_PARTITION);
    // the same SQLState with a constraint name is a CHECK violation
    assertThat(PgErrors.classify(server("23514", "some_check", "violates check", null)))
        .isEqualTo(Violation.CHECK);
  }

  @Test
  void wrappedMessageHasNoServerDetailOrFailingRow() {
    PSQLException notNull =
        server(
            "23502",
            null,
            "null value in column \"body\" of relation \"decision\" violates not-null constraint",
            "Failing row contains (1, 2026-10-09, secret-value, \\x01)");
    LedgerException wrapped = PgErrors.wrap(notNull);
    assertThat(wrapped.getMessage())
        .isEqualTo("ledger statement failed: sqlstate=23502 constraint=none");
    assertThat(wrapped.getCause()).isSameAs(notNull);

    LedgerException unique =
        PgErrors.wrap(
            server(
                "23505", "decision_pk", "duplicate key", "Key (decision_id)=(7) already exists."));
    assertThat(unique.getMessage())
        .isEqualTo("ledger statement failed: sqlstate=23505 constraint=decision_pk");
  }

  /**
   * The limit of the claim of Convention 12 (ADAPTER-5): only the message of the wrapping exception
   * is free of server text. The original stays as the cause, because the classification and the
   * unit's callers read it, and its message holds the server's detail (the failing key or row; for
   * a batch also the statement with its bound values). A caller therefore logs {@code getMessage()}
   * of a {@code LedgerException}, never the exception with its causes ({@code printStackTrace}, a
   * logger given the throwable); this test pins the fact so that the documentation cannot drift.
   */
  @Test
  void onlyTheWrappingMessageIsFreeOfServerTextTheCauseKeepsIt() {
    PSQLException unique =
        server(
            "23505",
            "ingest_pk",
            "duplicate key value violates unique constraint",
            "Key (source_id, source_record_id)=(1, REC-7) already exists.");
    LedgerException wrapped = PgErrors.wrap(unique);

    assertThat(wrapped.getMessage()).doesNotContain("REC-7").doesNotContain("duplicate");
    assertThat(wrapped.getCause()).isSameAs(unique);
    assertThat(wrapped.getCause().getMessage()).contains("REC-7");
  }

  @Test
  void wrapNeverRepeatsFreeTextFromTheException() {
    LedgerException noState = PgErrors.wrap(new SQLException("password=hunter2 select 1"));
    assertThat(noState.getMessage())
        .isEqualTo("ledger statement failed: sqlstate=none constraint=none");
    // a state or constraint that is not of the expected shape is not echoed
    LedgerException odd = PgErrors.wrap(new SQLException("x", "bad state with spaces"));
    assertThat(odd.getMessage())
        .isEqualTo("ledger statement failed: sqlstate=none constraint=none");
    LedgerException oddConstraint =
        PgErrors.wrap(server("23505", "x'; drop table y; --", "duplicate key", null));
    assertThat(oddConstraint.getMessage())
        .isEqualTo("ledger statement failed: sqlstate=23505 constraint=none");
    // the constraint accessor still reports what the server sent
    assertThat(PgErrors.constraint(server("23505", "x'; drop", "duplicate", null)))
        .contains("x'; drop");
  }
}
