package dev.regpack.postgres;

import dev.regpack.ports.ledger.LedgerException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Classifies PostgreSQL errors by SQLState and by the constraint name that the server reports,
 * never by message text (Convention 6 and 11), and turns them into a {@link LedgerException} whose
 * message carries nothing but those two fields (Convention 12).
 */
public final class PgErrors {
  /** What a failed statement means to the adapter. */
  public enum Violation {
    UNIQUE,
    FOREIGN_KEY,
    CHECK,
    /** SQLState 23514 without a constraint name: no partition accepts the row. */
    NO_PARTITION,
    SERIALIZATION,
    DEADLOCK,
    /** A lost or refused connection: never to be read as a negative answer. */
    CONNECTION,
    OTHER
  }

  private static final Pattern SQL_STATE = Pattern.compile("[0-9A-Z]{5}");
  private static final Pattern CONSTRAINT = Pattern.compile("[A-Za-z0-9_$]{1,63}");

  /** Longest cause chain that is searched. */
  private static final int MAX_DEPTH = 10;

  private PgErrors() {}

  /**
   * The class of the error. The SQLState is that of the first exception in the cause chain that has
   * one; an exception chain without any SQLState counts as {@link Violation#CONNECTION} when it
   * holds a JDBC connection exception (a driver or pool reporting a closed connection) and
   * otherwise as {@link Violation#OTHER}.
   */
  public static Violation classify(SQLException e) {
    String state = sqlState(e);
    if (state == null) {
      return hasConnectionException(e) ? Violation.CONNECTION : Violation.OTHER;
    }
    return switch (state) {
      case "23505" -> Violation.UNIQUE;
      case "23503" -> Violation.FOREIGN_KEY;
      case "23514" -> constraint(e).isPresent() ? Violation.CHECK : Violation.NO_PARTITION;
      case "40001" -> Violation.SERIALIZATION;
      case "40P01" -> Violation.DEADLOCK;
      case "57P01", "57P02", "57P03", "53300" -> Violation.CONNECTION;
      default -> state.startsWith("08") ? Violation.CONNECTION : Violation.OTHER;
    };
  }

  /**
   * The constraint name from the server error message (field {@code n}), empty when the server sent
   * none or the exception holds no server message. The text of the message is never parsed.
   */
  public static Optional<String> constraint(SQLException e) {
    Throwable current = e;
    for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
      if (current instanceof PSQLException p) {
        ServerErrorMessage message = p.getServerErrorMessage();
        if (message != null) {
          String name = message.getConstraint();
          return name == null || name.isEmpty() ? Optional.empty() : Optional.of(name);
        }
      }
      current = current.getCause();
    }
    return Optional.empty();
  }

  /**
   * Returns {@code ledger statement failed: sqlstate=<SQLSTATE> constraint=<name|none>} with the
   * original exception as the cause. A SQLState or constraint name that does not have the expected
   * shape is written as {@code none}; no message, detail, hint, SQL text or value is ever repeated.
   */
  public static LedgerException wrap(SQLException e) {
    String state = sqlState(e);
    String stateText = state != null && SQL_STATE.matcher(state).matches() ? state : "none";
    String name = constraint(e).filter(c -> CONSTRAINT.matcher(c).matches()).orElse("none");
    return new LedgerException(
        "ledger statement failed: sqlstate=" + stateText + " constraint=" + name, e);
  }

  private static String sqlState(SQLException e) {
    Throwable current = e;
    for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
      if (current instanceof SQLException s && s.getSQLState() != null) {
        return s.getSQLState();
      }
      current = current.getCause();
    }
    return null;
  }

  private static boolean hasConnectionException(SQLException e) {
    Throwable current = e;
    for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
      if (current instanceof SQLNonTransientConnectionException
          || current instanceof SQLTransientConnectionException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
