package dev.regpack.spikes.l.pg;

import dev.regpack.spikes.l.pg.SyncPrecondition.Sample;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The three PostgreSQL servers of the SP-L fixture as the code sees them: the primary at the port
 * of {@code -Dspike.pg.url}, the quorum synchronous standby on 5433 and the asynchronous replica on
 * 5434. The standby and the replica URL differ from the primary URL only in the port, so the user
 * (postgres, empty password, trust authentication) travels in the query string. The servers are
 * started by {@code spikes/scripts/pg-up.sh} on the runner; on any other machine {@link
 * #fromSystemProperty()} is empty and every integration test is skipped.
 */
public final class PgFixture implements AutoCloseable {
  /** The system property that carries the JDBC URL of the primary. */
  public static final String PROPERTY = "spike.pg.url";

  static final int STANDBY_PORT = 5433;
  static final int REPLICA_PORT = 5434;

  private static final Pattern URL =
      Pattern.compile("^(jdbc:postgresql://(?:\\[[^\\]]+\\]|[^/:?\\[\\]]+))(?::(\\d+))?([/?].*)?$");

  private final String primaryUrl;
  private final String prefix;
  private final String rest;
  private final Queue<Connection> opened = new ConcurrentLinkedQueue<>();

  private PgFixture(String primaryUrl, String prefix, String rest) {
    this.primaryUrl = primaryUrl;
    this.prefix = prefix;
    this.rest = rest;
  }

  /** The fixture of {@code -Dspike.pg.url}; empty when the property is absent or blank. */
  public static Optional<PgFixture> fromSystemProperty() {
    String value = System.getProperty(PROPERTY);
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(of(value));
  }

  /**
   * The fixture whose primary is at {@code url} ({@code jdbc:postgresql://host[:port]/...}); no
   * connection is made.
   *
   * @throws IllegalArgumentException if the URL is not a PostgreSQL JDBC URL
   */
  static PgFixture of(String url) {
    String trimmed = url.strip();
    Matcher m = URL.matcher(trimmed);
    if (!m.matches()) {
      throw new IllegalArgumentException(
          "the PostgreSQL URL must look like jdbc:postgresql://host:port/database?user=postgres: "
              + trimmed);
    }
    String rest = m.group(3) == null ? "" : m.group(3);
    return new PgFixture(trimmed, m.group(1), rest);
  }

  /** The JDBC URL of the primary, as given. */
  public String primaryUrl() {
    return primaryUrl;
  }

  /** The JDBC URL of the synchronous standby: the primary URL with port 5433. */
  public String standbyUrl() {
    return prefix + ":" + STANDBY_PORT + rest;
  }

  /** The JDBC URL of the asynchronous replica: the primary URL with port 5434. */
  public String replicaUrl() {
    return prefix + ":" + REPLICA_PORT + rest;
  }

  /** A new connection to the primary; the fixture closes it at the latest in {@link #close()}. */
  public Connection primary() throws SQLException {
    return open(primaryUrl);
  }

  /** A new connection to the synchronous standby (a read-only hot standby). */
  public Connection standby() throws SQLException {
    return open(standbyUrl());
  }

  /** A new connection to the asynchronous replica (a read-only hot standby). */
  public Connection replica() throws SQLException {
    return open(replicaUrl());
  }

  private Connection open(String url) throws SQLException {
    Properties properties = new Properties();
    properties.setProperty("ApplicationName", "spike-l");
    properties.setProperty("connectTimeout", "10");
    Connection connection = DriverManager.getConnection(url, properties);
    opened.removeIf(PgFixture::isClosed);
    opened.add(connection);
    return connection;
  }

  private static boolean isClosed(Connection connection) {
    try {
      return connection.isClosed();
    } catch (SQLException e) {
      return true;
    }
  }

  /** The values of {@code current_setting(name)} on the primary, in the order of the names. */
  public Map<String, String> settings(String... names) throws SQLException {
    Map<String, String> out = new LinkedHashMap<>();
    try (Connection c = primary();
        PreparedStatement ps = c.prepareStatement("SELECT current_setting(?)")) {
      for (String name : names) {
        ps.setString(1, name);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          out.put(name, rs.getString(1));
        }
      }
    }
    return Collections.unmodifiableMap(out);
  }

  /** One sample of {@code pg_stat_replication} on the primary, ordered by application name. */
  public List<Sample> syncSamples() throws SQLException {
    List<Sample> out = new ArrayList<>();
    try (Connection c = primary();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT application_name, sync_state, state FROM pg_stat_replication"
                    + " ORDER BY application_name")) {
      while (rs.next()) {
        out.add(new Sample(text(rs, 1), text(rs, 2), text(rs, 3)));
      }
    }
    return List.copyOf(out);
  }

  private static String text(ResultSet rs, int column) throws SQLException {
    String value = rs.getString(column);
    return value == null ? "" : value;
  }

  /** Closes every connection that the fixture opened and that is still open. */
  @Override
  public void close() {
    Connection c;
    while ((c = opened.poll()) != null) {
      try {
        c.close();
      } catch (SQLException e) {
        // closing is best effort: the connection is gone either way
      }
    }
  }
}
