package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Tests of the real-store harness itself (Convention 4, review focus LF2): the server is the
 * expected major version, a test schema is isolated and dropped, the required mode fails instead of
 * skipping, the CI service needs no password, and the advisory-lock keys of Convention 12 do not
 * collide across schemas or purposes. Without a server every test but the required-mode one is
 * skipped by the assumption in {@link PgTestDatabase#dataSource()}.
 */
class PgHarnessIT {
  /** Nothing listens on port 1 of the loopback; the connect timeout bounds a filtered port. */
  private static final String UNREACHABLE =
      "jdbc:postgresql://127.0.0.1:1/postgres?user=postgres&connectTimeout=3";

  private static String scalar(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      return rows.getString(1);
    }
  }

  /** True when a try-lock with the Convention 12 key succeeds; the lock lives until rollback. */
  private static boolean tryLock(Connection connection, int purpose, String key)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?, hashtext(?))")) {
      statement.setInt(1, purpose);
      statement.setString(2, key);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getBoolean(1);
      }
    }
  }

  @Test
  void serverIsPostgres18() throws SQLException {
    DataSource dataSource = PgTestDatabase.dataSource();
    try (Connection connection = dataSource.getConnection()) {
      assertThat(scalar(connection, "SHOW server_version")).startsWith("18.");
    }
  }

  @Test
  void freshSchemaIsIsolatedAndDropped() throws SQLException {
    DataSource dataSource = PgTestDatabase.dataSource();
    String first = PgTestDatabase.freshSchema();
    String second = PgTestDatabase.freshSchema();
    assertThat(first).isNotEqualTo(second);
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + first);
      statement.execute("CREATE SCHEMA " + second);
      statement.execute("CREATE TABLE " + first + ".marker (id int)");
      assertThat(scalar(connection, "SELECT to_regclass('" + first + ".marker')::text"))
          .isEqualTo(first + ".marker");
      // the other schema does not see the table of the first
      assertThat(scalar(connection, "SELECT to_regclass('" + second + ".marker')::text")).isNull();
      PgTestDatabase.dropSchema(first);
      assertThat(
              scalar(
                  connection, "SELECT count(*) FROM pg_namespace WHERE nspname = '" + first + "'"))
          .isEqualTo("0");
      assertThat(
              scalar(
                  connection, "SELECT count(*) FROM pg_namespace WHERE nspname = '" + second + "'"))
          .isEqualTo("1");
    } finally {
      PgTestDatabase.dropSchema(first);
      PgTestDatabase.dropSchema(second);
    }
    try (Connection connection = dataSource.getConnection()) {
      assertThat(
              scalar(
                  connection,
                  "SELECT count(*) FROM pg_namespace WHERE nspname IN ('"
                      + first
                      + "', '"
                      + second
                      + "')"))
          .isEqualTo("0");
    }
    // only a schema made by freshSchema() can be dropped
    assertThatThrownBy(() -> PgTestDatabase.dropSchema("public"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiredModeFailsInsteadOfSkippingWhenTheServerIsUnreachable() {
    // a canary instead of a password: the failure message must not repeat the URL
    String url = UNREACHABLE + "&password=canary-must-not-appear";
    assertThatThrownBy(() -> PgTestDatabase.requireOrAbort(url, true))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("REGPACK_PG_REQUIRED")
        .hasMessageNotContaining("canary-must-not-appear");
    // an unset URL fails in the same way
    assertThatThrownBy(() -> PgTestDatabase.requireOrAbort("", true))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("REGPACK_PG_REQUIRED");
    // the same situation without the required flag is a skip, never a failure
    assertThatThrownBy(() -> PgTestDatabase.requireOrAbort(UNREACHABLE, false))
        .isInstanceOf(TestAbortedException.class);
    assertThatThrownBy(() -> PgTestDatabase.requireOrAbort(null, false))
        .isInstanceOf(TestAbortedException.class);
  }

  @Test
  void trustAuthenticationNeedsNoPasswordInTheUrl() throws SQLException {
    DataSource dataSource = PgTestDatabase.dataSource();
    // trust authentication is a property of the CI service container (D-L8); a developer's own
    // server may well use a password, so the check is made where the real store is required
    Assumptions.assumeTrue(
        PgTestDatabase.required(), "trust authentication is checked only in the required mode");
    String url = System.getenv("REGPACK_PG_URL");
    assertThat(url).isNotBlank().doesNotContainIgnoringCase("password");
    assertThat(url).doesNotContainPattern("//[^/]*@");
    try (Connection connection = dataSource.getConnection()) {
      assertThat(scalar(connection, "SELECT 1")).isEqualTo("1");
    }
    // the server ignores a password altogether: a wrong one is accepted as well
    PGSimpleDataSource wrong = new PGSimpleDataSource();
    wrong.setUrl(url);
    wrong.setPassword("a-password-the-server-never-asks-for");
    try (Connection connection = wrong.getConnection()) {
      assertThat(scalar(connection, "SELECT 1")).isEqualTo("1");
    }
  }

  @Test
  void advisoryKeysDifferBetweenSchemas() throws SQLException {
    DataSource dataSource = PgTestDatabase.dataSource();
    String a = PgTestDatabase.freshSchema();
    String b = PgTestDatabase.freshSchema();
    String source = "source-1";
    String artifact = "sealed/x";
    try (Connection holder = dataSource.getConnection();
        Connection other = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      other.setAutoCommit(false);
      // the holder takes the migrator (1), partition (2), source (3) and retention artifact (4)
      // locks of schema a
      assertThat(tryLock(holder, 1, a)).isTrue();
      assertThat(tryLock(holder, 2, a)).isTrue();
      assertThat(tryLock(holder, 3, a + ":" + source)).isTrue();
      assertThat(tryLock(holder, 4, a + ":" + artifact)).isTrue();

      // each is really held: the same key cannot be taken by another transaction
      assertThat(tryLock(other, 1, a)).isFalse();
      assertThat(tryLock(other, 2, a)).isFalse();
      assertThat(tryLock(other, 3, a + ":" + source)).isFalse();
      assertThat(tryLock(other, 4, a + ":" + artifact)).isFalse();

      // the same purposes in another schema are different keys
      assertThat(tryLock(other, 1, b)).isTrue();
      assertThat(tryLock(other, 2, b)).isTrue();
      assertThat(tryLock(other, 3, b + ":" + source)).isTrue();
      assertThat(tryLock(other, 4, b + ":" + artifact)).isTrue();

      // the source lock of another source of the same schema is a different key as well, and so is
      // the artifact lock of another artifact
      assertThat(tryLock(other, 3, a + ":source-2")).isTrue();
      assertThat(tryLock(other, 4, a + ":sealed/y")).isTrue();
      holder.rollback();
      other.rollback();

      // the purposes are different keys: holding purpose 1 does not hold purpose 2, 3 or 4
      assertThat(tryLock(holder, 1, a)).isTrue();
      assertThat(tryLock(other, 2, a)).isTrue();
      assertThat(tryLock(other, 3, a)).isTrue();
      assertThat(tryLock(other, 4, a)).isTrue();
      holder.rollback();
      other.rollback();
    }
  }
}
