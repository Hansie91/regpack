package dev.regpack.postgres;

import dev.regpack.postgres.PgLedgerStore.Config;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * The integrity queries of the ledger (T19): read-only checks of the things that the schema cannot
 * enforce by itself. Chain linearity across the daily pieces of {@code decision} rests on the head
 * compare-and-set alone (D-S4: no trigger), the context of a decision can be a stub (D-S7), and the
 * gaplessness of {@code version_seq} rests on the subject row lock. This is what P4 {@code verify}
 * will call; the durability gate ({@code synchronous_commit}, {@code pg_stat_replication}) and the
 * ledger clock are P3b.
 *
 * <p>Every check is bounded by the range of {@code as_at} days that the caller names, and is driven
 * from the decisions of that range, so that the cost follows the range and not the history: the
 * unpartitioned {@code decision_head} and {@code report_subject} are probed by primary key for the
 * chains and subjects that the decisions of the range touch. A violation whose rows lie outside the
 * range is not found; for instance the twin of a sequence number is found only when both rows are
 * in the range, and the full scan is a batch job of P4.
 *
 * <p>The checks run in one {@code REPEATABLE READ READ ONLY} transaction, so that all four see one
 * snapshot and nothing is written. No {@code statement_timeout} is set: the caller owns the data
 * source, and the scan of a long range may take longer than the timeout of a write unit. A lost
 * connection or a failed statement is a {@link dev.regpack.ports.ledger.LedgerException}, never an
 * empty list: an empty answer means a clean ledger.
 *
 * <p>The cross-source clash of {@code ingest} (two sources landing the same new event identity in
 * the same instant) is accepted by the owner at the schema stop and has no check here.
 */
public final class LedgerIntegrity {
  /** What was found. */
  public enum Kind {
    /**
     * Decision rows in the scanned range that share (regime, event identity, sequence number): the
     * unique keys of the daily pieces cannot see across pieces, only the head compare-and-set stops
     * a second row (D-S4).
     */
    DUPLICATE_DECISION_SEQ,
    /**
     * The chain of a decision in the range has no head row, or its head is behind the chain, names
     * no decision, or differs from the decision it names in regime, event identity, sequence number
     * or outcome.
     */
    HEAD_MISMATCH,
    /** A decision in the range whose context row has {@code body IS NULL}, a stub (D-S7). */
    DECISION_WITHOUT_CONTEXT,
    /**
     * A subject touched by a decision in the range whose versions are not exactly 1 to {@code
     * last_version_seq}.
     */
    VERSION_GAP
  }

  /**
   * One finding.
   *
   * @param kind what kind of violation
   * @param detail identifiers and numbers that locate it, plain text for an operator; it never
   *     holds a report body, a SQL text or a server message
   */
  public record Violation(Kind kind, String detail) {
    public Violation {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(detail, "detail");
      if (detail.isBlank()) {
        throw new IllegalArgumentException("detail must not be blank");
      }
    }
  }

  /**
   * The most violations of one kind that a check lists. A damaged day of 50 million decisions must
   * not become a list of 50 million objects: beyond this number the list of that kind ends in one
   * extra violation of the same kind that says so.
   */
  public static final int MAX_VIOLATIONS_PER_KIND = 1_000;

  private static final String SET_SNAPSHOT =
      "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY";

  private static final String SET_SESSION =
      "SELECT set_config('TimeZone', 'UTC', true), set_config('search_path', ?, true),"
          + " set_config('lock_timeout', ?, true)";

  /** Parameters of every query below: the first day, the last day (both inclusive), the limit. */
  private static final String DUPLICATE_DECISION_SEQ_SQL =
      "SELECT g.regime_id, r.regime, g.event_identity, g.decision_seq, g.copies, g.decision_ids"
          + " FROM (SELECT regime_id, event_identity, decision_seq, count(*) AS copies,"
          + " string_agg(decision_id::text, ',' ORDER BY decision_id) AS decision_ids"
          + " FROM decision WHERE as_at BETWEEN ? AND ?"
          + " GROUP BY regime_id, event_identity, decision_seq HAVING count(*) > 1) g"
          + " LEFT JOIN regime r ON r.regime_id = g.regime_id"
          + " ORDER BY g.regime_id, g.event_identity, g.decision_seq LIMIT ?";

  private static final String HEAD_MISMATCH_SQL =
      "SELECT t.regime_id, t.regime, t.event_identity, t.reason, t.top_seq, t.head_seq,"
          + " t.head_decision_id FROM ("
          + "SELECT c.regime_id, r.regime, c.event_identity, c.top_seq,"
          + " h.decision_seq AS head_seq, h.decision_id AS head_decision_id,"
          + " CASE"
          + " WHEN h.regime_id IS NULL THEN 'no head row'"
          + " WHEN h.decision_seq < c.top_seq THEN 'head behind the chain'"
          + " WHEN x.decision_id IS NULL THEN 'head names no decision'"
          + " WHEN x.regime_id <> h.regime_id OR x.event_identity <> h.event_identity"
          + " OR x.decision_seq <> h.decision_seq OR x.outcome <> h.outcome"
          + " THEN 'head differs from the decision it names'"
          + " END AS reason"
          + " FROM (SELECT regime_id, event_identity, max(decision_seq) AS top_seq"
          + " FROM decision WHERE as_at BETWEEN ? AND ? GROUP BY regime_id, event_identity) c"
          + " LEFT JOIN regime r ON r.regime_id = c.regime_id"
          + " LEFT JOIN decision_head h ON h.regime_id = c.regime_id"
          + " AND h.event_identity = c.event_identity"
          + " LEFT JOIN decision x ON x.decision_id = h.decision_id AND x.as_at = h.as_at"
          + ") t WHERE t.reason IS NOT NULL ORDER BY t.regime_id, t.event_identity LIMIT ?";

  private static final String DECISION_WITHOUT_CONTEXT_SQL =
      "SELECT d.decision_id, d.as_at, d.regime_id, r.regime, d.event_identity, d.decision_seq,"
          + " d.context_id"
          + " FROM decision d JOIN decision_context c ON c.context_id = d.context_id"
          + " LEFT JOIN regime r ON r.regime_id = d.regime_id"
          + " WHERE d.as_at BETWEEN ? AND ? AND c.body IS NULL"
          + " ORDER BY d.as_at, d.decision_id LIMIT ?";

  private static final String VERSION_GAP_SQL =
      "SELECT s.subject_id, s.regime_id, r.regime, s.event_identity, s.leg, s.last_version_seq,"
          + " a.versions, a.lowest, a.highest"
          + " FROM (SELECT DISTINCT rv.subject_id FROM decision d"
          + " JOIN report_version rv ON rv.decision_id = d.decision_id"
          + " AND rv.decision_as_at = d.as_at"
          + " WHERE d.as_at BETWEEN ? AND ?) t"
          + " JOIN report_subject s ON s.subject_id = t.subject_id"
          + " CROSS JOIN LATERAL (SELECT count(*) AS versions, min(version_seq) AS lowest,"
          + " max(version_seq) AS highest FROM report_version WHERE subject_id = s.subject_id) a"
          + " LEFT JOIN regime r ON r.regime_id = s.regime_id"
          + " WHERE a.versions <> s.last_version_seq OR a.lowest <> 1 OR a.highest <> a.versions"
          + " ORDER BY s.subject_id LIMIT ?";

  private LedgerIntegrity() {}

  /** The text of one row of a query, in the terms of {@link Violation#detail()}. */
  @FunctionalInterface
  private interface RowDetail {
    String of(ResultSet row) throws SQLException;
  }

  /**
   * Runs the four checks over the days {@code from} to {@code toInclusive} (both inclusive) of the
   * decision date {@code as_at}, in the order of {@link Kind}, and within a kind in the order of
   * its key. An empty list means that nothing was found in the range.
   *
   * @param dataSource where the ledger is; the caller keeps it
   * @param schema the schema that holds the tables of the ledger, an identifier ({@link
   *     SqlIdentifier})
   * @throws IllegalArgumentException if the schema is not an identifier or {@code from} is after
   *     {@code toInclusive}
   * @throws dev.regpack.ports.ledger.LedgerException if the database cannot be read
   */
  public static List<Violation> check(
      DataSource dataSource, String schema, LocalDate from, LocalDate toInclusive) {
    Objects.requireNonNull(dataSource, "dataSource");
    SqlIdentifier.require(schema);
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(toInclusive, "toInclusive");
    if (from.isAfter(toInclusive)) {
      throw new IllegalArgumentException("from is after toInclusive");
    }
    Connection connection = null;
    boolean committed = false;
    try {
      connection = dataSource.getConnection();
      begin(connection, schema);
      List<Violation> found = new ArrayList<>();
      found.addAll(
          query(
              connection,
              Kind.DUPLICATE_DECISION_SEQ,
              DUPLICATE_DECISION_SEQ_SQL,
              from,
              toInclusive,
              LedgerIntegrity::duplicateDetail));
      found.addAll(
          query(
              connection,
              Kind.HEAD_MISMATCH,
              HEAD_MISMATCH_SQL,
              from,
              toInclusive,
              LedgerIntegrity::headDetail));
      found.addAll(
          query(
              connection,
              Kind.DECISION_WITHOUT_CONTEXT,
              DECISION_WITHOUT_CONTEXT_SQL,
              from,
              toInclusive,
              LedgerIntegrity::stubDetail));
      found.addAll(
          query(
              connection,
              Kind.VERSION_GAP,
              VERSION_GAP_SQL,
              from,
              toInclusive,
              LedgerIntegrity::gapDetail));
      connection.commit();
      committed = true;
      return List.copyOf(found);
    } catch (SQLException e) {
      throw PgErrors.wrap(e);
    } finally {
      Unit.release(connection, committed);
    }
  }

  /** The prologue: one snapshot for all four queries, read-only, UTC, the schema, a lock bound. */
  private static void begin(Connection connection, String schema) throws SQLException {
    connection.setAutoCommit(false);
    try (Statement snapshot = connection.createStatement()) {
      snapshot.execute(SET_SNAPSHOT);
    }
    try (PreparedStatement settings = connection.prepareStatement(SET_SESSION)) {
      settings.setString(1, schema);
      settings.setString(2, Config.DEFAULT_LOCK_TIMEOUT.toMillis() + "ms");
      settings.execute();
    }
  }

  private static List<Violation> query(
      Connection connection,
      Kind kind,
      String sql,
      LocalDate from,
      LocalDate toInclusive,
      RowDetail detail)
      throws SQLException {
    List<Violation> found = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, from);
      statement.setObject(2, toInclusive);
      // one more than the limit, to know whether the list is cut
      statement.setInt(3, MAX_VIOLATIONS_PER_KIND + 1);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          found.add(new Violation(kind, detail.of(rows)));
        }
      }
    }
    return capped(kind, found);
  }

  /**
   * The list as it is when it holds at most {@link #MAX_VIOLATIONS_PER_KIND} violations; otherwise
   * the first {@link #MAX_VIOLATIONS_PER_KIND} and one more of the same kind that says the rest is
   * not listed.
   */
  static List<Violation> capped(Kind kind, List<Violation> found) {
    if (found.size() <= MAX_VIOLATIONS_PER_KIND) {
      return found;
    }
    List<Violation> cut = new ArrayList<>(found.subList(0, MAX_VIOLATIONS_PER_KIND));
    cut.add(
        new Violation(
            kind,
            "more than "
                + MAX_VIOLATIONS_PER_KIND
                + " violations of this kind in the range; the rest are not listed"));
    return cut;
  }

  // columns: regime_id, regime, event_identity, decision_seq, copies, decision_ids
  private static String duplicateDetail(ResultSet row) throws SQLException {
    return "regime="
        + regime(row.getShort(1), row.getString(2))
        + " event="
        + row.getString(3)
        + " decision_seq="
        + row.getLong(4)
        + " copies="
        + row.getLong(5)
        + " decision_ids="
        + row.getString(6);
  }

  // columns: regime_id, regime, event_identity, reason, top_seq, head_seq, head_decision_id
  private static String headDetail(ResultSet row) throws SQLException {
    String regime = regime(row.getShort(1), row.getString(2));
    String event = row.getString(3);
    String reason = row.getString(4);
    long topSeq = row.getLong(5);
    long headSeq = row.getLong(6);
    String headSeqText = row.wasNull() ? "none" : Long.toString(headSeq);
    long headDecisionId = row.getLong(7);
    String headDecisionIdText = row.wasNull() ? "none" : Long.toString(headDecisionId);
    return "regime="
        + regime
        + " event="
        + event
        + " reason="
        + reason
        + " highest_seq_in_range="
        + topSeq
        + " head_seq="
        + headSeqText
        + " head_decision_id="
        + headDecisionIdText;
  }

  // columns: decision_id, as_at, regime_id, regime, event_identity, decision_seq, context_id
  private static String stubDetail(ResultSet row) throws SQLException {
    return "decision_id="
        + row.getLong(1)
        + " as_at="
        + row.getObject(2, LocalDate.class)
        + " regime="
        + regime(row.getShort(3), row.getString(4))
        + " event="
        + row.getString(5)
        + " decision_seq="
        + row.getLong(6)
        + " context_id="
        + row.getLong(7)
        + " (the context row has no body)";
  }

  // columns: subject_id, regime_id, regime, event_identity, leg, last_version_seq, versions,
  // lowest,
  // highest
  private static String gapDetail(ResultSet row) throws SQLException {
    return "subject_id="
        + row.getLong(1)
        + " regime="
        + regime(row.getShort(2), row.getString(3))
        + " event="
        + row.getString(4)
        + " leg="
        + row.getShort(5)
        + " last_version_seq="
        + row.getLong(6)
        + " versions="
        + row.getLong(7)
        + " lowest="
        + row.getLong(8)
        + " highest="
        + row.getLong(9);
  }

  /** The regime name, or its dictionary id when the dictionary has no such row. */
  private static String regime(short id, String name) {
    return name != null ? name : "#" + id;
  }
}
