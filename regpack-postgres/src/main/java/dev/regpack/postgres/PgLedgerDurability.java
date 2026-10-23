package dev.regpack.postgres;

import dev.regpack.ports.ledger.LedgerDurability;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.postgres.PgLedgerStore.Config;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * What the PostgreSQL ledger knows about the durability of its own commits (spec 11.2, I1;
 * assumption A11): whether a commit of this adapter is acknowledged only after enough synchronous
 * standbys have it. The send gate reads {@link #durability()}; a status that is not synchronous
 * closes the gate, and so does an exception (the port says so).
 *
 * <p><b>The rule</b> ({@link #evaluate}). A commit is synchronous when all three hold:
 *
 * <ol>
 *   <li>the effective {@code synchronous_commit} of a unit of this adapter is {@code on} or {@code
 *       remote_apply} (not {@code local}, {@code remote_write} or {@code off});
 *   <li>{@code synchronous_standby_names} is not blank;
 *   <li>at least as many standbys as the setting requires are {@code streaming} with {@code
 *       sync_state} {@code sync} or {@code quorum}: the number {@code n} of {@code ANY n (...)},
 *       {@code FIRST n (...)} or {@code n (...)} (case-insensitive), and 1 for a plain list or
 *       {@code *}.
 * </ol>
 *
 * <p><b>The read</b> is one unit that only reads (the server enforces it with {@code SET
 * TRANSACTION READ ONLY}, so the commit of the unit has no transaction id and never waits for a
 * standby): the setting of the standby names, the number of qualifying standbys and, when the
 * {@link Config} does not force {@code synchronous_commit} on, the reset value of {@code
 * synchronous_commit} of the session. The prologue of a unit overrides {@code synchronous_commit}
 * inside the transaction when it is forced, so {@code current_setting} would always say {@code on}
 * there; when it is forced the effective value is {@code on} by construction, and when it is not,
 * the value the session starts with (a connection URL option, a role or database setting, the
 * server default) is what a write unit of this adapter commits with.
 *
 * <p><b>Rights.</b> The rows of {@code pg_stat_replication} are shown in full only to a superuser
 * and to members of {@code pg_read_all_stats} (for example {@code pg_monitor}); another role sees
 * no qualifying standby, and the answer is then "not synchronous" (the safe direction). The ledger
 * role of a deployment needs the grant (a hand-off of the notes of P3b1).
 *
 * <p><b>What it is not.</b> It is a reading at one moment; it does not make a commit synchronous. A
 * commit whose wait for the standby was cancelled after the read is refused by the unit itself
 * ({@link Unit#inUnit}: any warning after COMMIT), and that is the part of I1 that does not depend
 * on a reading being fresh.
 *
 * <p>The detail of a status is a fixed text that names the failing conditions, the counts and the
 * setting of the standby names (an application name of the operator's configuration); it names no
 * host, credential or business key, and carries no control character.
 */
final class PgLedgerDurability implements LedgerDurability {
  /** The unit only reads; the server refuses any write in it. */
  static final String READ_ONLY_SQL = "SET TRANSACTION READ ONLY";

  /** The names of the synchronous standbys: empty when none is configured. */
  static final String NAMES_SQL =
      "SELECT setting FROM pg_settings WHERE name = 'synchronous_standby_names'";

  /** The number of standbys that take part in the acknowledgement now. */
  static final String STANDBYS_SQL =
      "SELECT count(*) FROM pg_stat_replication"
          + " WHERE state = 'streaming' AND sync_state IN ('sync', 'quorum')";

  /** The value the session of a unit starts with, read only when the unit does not force it. */
  static final String COMMIT_SQL =
      "SELECT reset_val FROM pg_settings WHERE name = 'synchronous_commit'";

  /** The values of {@code synchronous_commit} that a detail may repeat (the enum of the server). */
  private static final Set<String> COMMIT_SETTINGS =
      Set.of("on", "off", "local", "remote_write", "remote_apply");

  /** Longest piece of a server setting that a detail repeats. */
  private static final int MAX_SHOWN = 200;

  /** {@code ANY n (}, {@code FIRST n (} or {@code n (} at the head of the setting. */
  private static final Pattern COUNT_AT_HEAD =
      Pattern.compile("^(?:(?:ANY|FIRST)\\s+)?([0-9]+)\\s*\\(", Pattern.CASE_INSENSITIVE);

  private final DataSource dataSource;
  private final Config config;

  PgLedgerDurability(DataSource dataSource, Config config) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.config = Objects.requireNonNull(config, "config");
  }

  @Override
  public Status durability() {
    return Unit.inUnit(dataSource, config, this::read);
  }

  private Status read(Connection connection) throws SQLException {
    try (Statement readOnly = connection.createStatement()) {
      readOnly.execute(READ_ONLY_SQL);
    }
    String names = text(connection, NAMES_SQL);
    long streaming = number(connection, STANDBYS_SQL);
    String commit = config.forceSynchronousCommit() ? "on" : text(connection, COMMIT_SQL);
    return evaluate(commit, names, (int) Math.min(streaming, Integer.MAX_VALUE));
  }

  private static String text(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      if (!rows.next()) {
        throw new LedgerException("the durability query returned no row");
      }
      String value = rows.getString(1);
      return value == null ? "" : value;
    }
  }

  private static long number(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      if (!rows.next()) {
        throw new LedgerException("the durability query returned no row");
      }
      return rows.getLong(1);
    }
  }

  /**
   * The rule of the class comment, without a server (assumption A11).
   *
   * @param effectiveCommit the {@code synchronous_commit} a unit of the adapter commits with
   * @param standbyNames the setting {@code synchronous_standby_names}, empty when none
   * @param streamingSyncStandbys how many standbys are {@code streaming} and {@code sync} or {@code
   *     quorum} now, at least 0
   * @return a status that is synchronous only if all three conditions hold; its standby count is
   *     {@code streamingSyncStandbys}, which is at least 1 then
   */
  static Status evaluate(String effectiveCommit, String standbyNames, int streamingSyncStandbys) {
    Objects.requireNonNull(effectiveCommit, "effectiveCommit");
    Objects.requireNonNull(standbyNames, "standbyNames");
    if (streamingSyncStandbys < 0) {
      throw new IllegalArgumentException("streamingSyncStandbys must not be negative");
    }
    String mode = effectiveCommit.strip().toLowerCase(Locale.ROOT);
    String names = standbyNames.strip();
    List<String> problems = new ArrayList<>();
    if (!mode.equals("on") && !mode.equals("remote_apply")) {
      String shown = COMMIT_SETTINGS.contains(mode) ? "'" + mode + "'" : "an unrecognised value";
      problems.add("synchronous_commit is " + shown + ", not 'on' or 'remote_apply'");
    }
    int required = 0;
    if (names.isEmpty()) {
      problems.add("synchronous_standby_names is empty");
    } else {
      required = requiredStandbys(names);
      if (required < 1) {
        problems.add("the number of standbys cannot be read from synchronous_standby_names");
      } else if (streamingSyncStandbys < required) {
        problems.add(
            streamingSyncStandbys
                + " of "
                + required
                + " required synchronous standbys are streaming");
      }
    }
    if (problems.isEmpty()) {
      return new Status(
          true,
          streamingSyncStandbys,
          "synchronous: synchronous_commit is '"
              + mode
              + "', synchronous_standby_names is '"
              + shown(names)
              + "', "
              + streamingSyncStandbys
              + " of "
              + required
              + " required synchronous standbys are streaming");
    }
    return new Status(
        false,
        streamingSyncStandbys,
        "not synchronous: "
            + String.join("; ", problems)
            + " (synchronous standbys streaming: "
            + streamingSyncStandbys
            + ")");
  }

  /**
   * The number of standbys a commit waits for: {@code n} of {@code ANY n (...)}, {@code FIRST n
   * (...)} and {@code n (...)}, 1 for a plain list or {@code *}, and 0 when the number is zero or
   * beyond an {@code int} (the server accepts neither, so the setting cannot be read as stated).
   */
  private static int requiredStandbys(String names) {
    Matcher head = COUNT_AT_HEAD.matcher(names);
    if (!head.find()) {
      return 1;
    }
    try {
      return Integer.parseInt(head.group(1));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** Printable ASCII only and at most {@link #MAX_SHOWN} characters of a server setting. */
  private static String shown(String setting) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < setting.length() && i < MAX_SHOWN; i++) {
      char c = setting.charAt(i);
      out.append(c >= 0x20 && c < 0x7F ? c : '?');
    }
    if (setting.length() > MAX_SHOWN) {
      out.append("...");
    }
    return out.toString();
  }
}
