package dev.regpack.postgres.migrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.postgres.PgTestDatabase;
import dev.regpack.postgres.SchemaDocument;
import dev.regpack.postgres.SqlIdentifier;
import dev.regpack.postgres.migrate.MigrationException.Kind;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real-store tests of the migrator (review focus LF13). Skipped without a server. */
class MigratorIT {
  private static final String ZEROS = "0".repeat(64);

  private DataSource dataSource;
  private String schema;

  @BeforeEach
  void freshSchema() {
    dataSource = PgTestDatabase.dataSource();
    schema = PgTestDatabase.freshSchema();
  }

  @AfterEach
  void dropSchema() {
    if (schema != null && dataSource != null) {
      PgTestDatabase.dropSchema(schema);
    }
  }

  private void update(String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private List<String> column(String sql, String... parameters) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setString(i + 1, parameters[i]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          values.add(rows.getString(1));
        }
      }
    }
    return values;
  }

  private String table(String name) {
    return SqlIdentifier.require(schema) + "." + SqlIdentifier.require(name);
  }

  /** The real scripts followed by one broken script that is the next version (V003 today). */
  private static ScriptCatalogue withABrokenNextScript(String brokenSql) {
    List<ScriptCatalogue.Script> scripts =
        new ArrayList<>(ScriptCatalogue.fromClasspath().scripts());
    scripts.add(ScriptCatalogue.Script.of(scripts.size() + 1, "V003__broken.sql", brokenSql));
    return ScriptCatalogue.of(scripts);
  }

  @Test
  void appliesAllScriptsOnAnEmptySchemaAndIsIdempotent() throws SQLException {
    ScriptCatalogue catalogue = ScriptCatalogue.fromClasspath();
    int last = catalogue.scripts().size();

    Migrator.Report first = Migrator.migrate(dataSource, schema);
    assertThat(first.applied()).hasSize(last).startsWith(1);
    assertThat(first.currentVersion()).isEqualTo(last);

    Migrator.Report second = Migrator.migrate(dataSource, schema);
    assertThat(second.applied()).isEmpty();
    assertThat(second.currentVersion()).isEqualTo(last);

    assertThat(
            column(
                "SELECT version::text || ' ' || name || ' ' || sha256 FROM "
                    + table("schema_migration")
                    + " ORDER BY version"))
        .isEqualTo(
            catalogue.scripts().stream()
                .map(s -> s.version() + " " + s.name() + " " + s.sha256())
                .toList());
  }

  @Test
  void refusesAChangedAppliedScript() throws SQLException {
    Migrator.migrate(dataSource, schema);
    update(
        "UPDATE " + table("schema_migration") + " SET sha256 = '" + ZEROS + "' WHERE version = 1");
    assertThatThrownBy(() -> Migrator.migrate(dataSource, schema))
        .isInstanceOfSatisfying(
            MigrationException.class, e -> assertThat(e.kind()).isEqualTo(Kind.DRIFT));
  }

  /** The second script is covered by the checksum too, and the refusal names it. */
  @Test
  void refusesAChangedSecondScript() throws SQLException {
    Migrator.migrate(dataSource, schema);
    ScriptCatalogue.Script second = ScriptCatalogue.fromClasspath().find(2).orElseThrow();
    update(
        "UPDATE " + table("schema_migration") + " SET sha256 = '" + ZEROS + "' WHERE version = 2");
    assertThatThrownBy(() -> Migrator.migrate(dataSource, schema))
        .isInstanceOfSatisfying(
            MigrationException.class,
            e -> {
              assertThat(e.kind()).isEqualTo(Kind.DRIFT);
              assertThat(e.version()).isEqualTo(2);
              assertThat(e.script()).isEqualTo(second.name());
            });
    // with the checksum put back the database is accepted again
    update(
        "UPDATE "
            + table("schema_migration")
            + " SET sha256 = '"
            + second.sha256()
            + "' WHERE version = 2");
    assertThat(Migrator.migrate(dataSource, schema).applied()).isEmpty();
  }

  /**
   * The production starting point of a database that the P3a build migrated: only V001 is stored.
   * The first start of the next build applies V002 alone, leaves the rows of V001 and the data as
   * they are, and is then idempotent.
   */
  @Test
  void anUpgradeFromAV001OnlyDatabaseAppliesOnlyV002() throws SQLException {
    ScriptCatalogue full = ScriptCatalogue.fromClasspath();
    ScriptCatalogue.Script v001 = full.scripts().get(0);
    assertThat(v001.name()).isEqualTo("V001__core.sql");
    Migrator.Report p3a = Migrator.migrate(dataSource, schema, ScriptCatalogue.of(List.of(v001)));
    assertThat(p3a.applied()).containsExactly(1);
    assertThat(p3a.currentVersion()).isEqualTo(1);
    assertThat(column("SELECT to_regclass(?)::text", schema + ".object_manifest"))
        .as("the P3a database has no manifest")
        .containsExactly((String) null);
    update("INSERT INTO " + table("regime") + " (regime) VALUES ('survivor')");
    String appliedAtOfV001 =
        column("SELECT applied_at::text FROM " + table("schema_migration") + " WHERE version = 1")
            .get(0);

    Migrator.Report upgrade = Migrator.migrate(dataSource, schema);

    assertThat(upgrade.applied())
        .as("only the scripts after V001 are applied")
        .containsExactlyElementsOf(
            full.scripts().stream().skip(1).map(ScriptCatalogue.Script::version).toList());
    assertThat(upgrade.applied()).startsWith(2);
    assertThat(upgrade.currentVersion()).isEqualTo(full.latestVersion());
    assertThat(
            column(
                "SELECT version::text || ' ' || name || ' ' || sha256 FROM "
                    + table("schema_migration")
                    + " ORDER BY version"))
        .isEqualTo(
            full.scripts().stream()
                .map(s -> s.version() + " " + s.name() + " " + s.sha256())
                .toList());
    assertThat(
            column(
                "SELECT applied_at::text FROM " + table("schema_migration") + " WHERE version = 1"))
        .as("the row of V001 is left alone")
        .containsExactly(appliedAtOfV001);
    assertThat(column("SELECT regime FROM " + table("regime")))
        .as("the data of the P3a database survives")
        .containsExactly("survivor");
    assertThat(column("SELECT to_regclass(?)::text", schema + ".object_manifest"))
        .as("the manifest exists after the upgrade")
        .doesNotContainNull()
        .hasSize(1);
    assertThat(Migrator.migrate(dataSource, schema).applied()).isEmpty();
  }

  @Test
  void refusesADatabaseNewerThanTheCode() throws SQLException {
    Migrator.migrate(dataSource, schema);
    int newer = ScriptCatalogue.fromClasspath().scripts().size() + 1;
    update(
        "INSERT INTO "
            + table("schema_migration")
            + " (version, name, sha256) VALUES ("
            + newer
            + ", 'V999__from_the_future.sql', '"
            + ZEROS
            + "')");
    assertThatThrownBy(() -> Migrator.migrate(dataSource, schema))
        .isInstanceOfSatisfying(
            MigrationException.class, e -> assertThat(e.kind()).isEqualTo(Kind.NEWER_DATABASE));
  }

  @Test
  void refusesAGapInTheStoredVersions() throws SQLException {
    Migrator.migrate(dataSource, schema);
    // stored versions {2,3} instead of {1,2}: the history does not start at 1
    update("UPDATE " + table("schema_migration") + " SET version = 3 WHERE version = 1");
    assertThatThrownBy(() -> Migrator.migrate(dataSource, schema))
        .isInstanceOfSatisfying(
            MigrationException.class,
            e -> {
              assertThat(e.kind()).isEqualTo(Kind.GAP);
              assertThat(e.version()).as("the first version that is missing").isEqualTo(1);
            });
    // a hole in the middle: stored versions {1,3} instead of {1,2}
    update("UPDATE " + table("schema_migration") + " SET version = 1 WHERE version = 2");
    assertThatThrownBy(() -> Migrator.migrate(dataSource, schema))
        .isInstanceOfSatisfying(
            MigrationException.class,
            e -> {
              assertThat(e.kind()).isEqualTo(Kind.GAP);
              assertThat(e.version()).as("the first version that is missing").isEqualTo(2);
            });
  }

  @Test
  void concurrentMigratorsApplyEachScriptOnce() throws Exception {
    int scripts = ScriptCatalogue.fromClasspath().scripts().size();
    int threads = 4;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<Migrator.Report>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return Migrator.migrate(dataSource, schema);
                }));
      }
      start.countDown();
      List<Integer> appliedByAll = new ArrayList<>();
      for (Future<Migrator.Report> future : futures) {
        Migrator.Report report = future.get(120, TimeUnit.SECONDS);
        assertThat(report.currentVersion()).isEqualTo(scripts);
        appliedByAll.addAll(report.applied());
      }
      assertThat(appliedByAll)
          .as("each version was applied by exactly one of the migrators")
          .hasSize(scripts)
          .doesNotHaveDuplicates();
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            column("SELECT version::text FROM " + table("schema_migration") + " ORDER BY version"))
        .hasSize(scripts);
  }

  @Test
  void aFailingScriptLeavesNothingBehind() throws SQLException {
    ScriptCatalogue catalogue =
        withABrokenNextScript(
            "CREATE TABLE broken_probe (x int);\nINSERT INTO no_such_table_at_all VALUES (1);\n");
    assertThatThrownBy(() -> Migrator.migrate(dataSource, schema, catalogue))
        .isInstanceOfSatisfying(
            MigrationException.class,
            e -> {
              assertThat(e.kind()).isEqualTo(Kind.SCRIPT_FAILED);
              assertThat(e.version()).isEqualTo(3);
            });
    assertThat(
            column("SELECT version::text FROM " + table("schema_migration") + " ORDER BY version"))
        .as("the two real scripts stay applied")
        .containsExactly("1", "2");
    assertThat(column("SELECT to_regclass(?)::text", schema + ".broken_probe"))
        .as("nothing of the broken script: its first statement was rolled back")
        .containsExactly((String) null);
    // version 3 was never recorded, so a run with the real catalogue finds nothing to do
    assertThat(Migrator.migrate(dataSource, schema).applied()).isEmpty();
  }

  @Test
  void aFailingScriptMessageHasNoSqlText() {
    ScriptCatalogue catalogue =
        withABrokenNextScript("INSERT INTO secret_marker_table_zz VALUES (42);\n");
    assertThatThrownBy(() -> Migrator.migrate(dataSource, schema, catalogue))
        .isInstanceOfSatisfying(
            MigrationException.class,
            e -> {
              assertThat(e.getMessage())
                  .contains("V003__broken.sql")
                  .contains("SCRIPT_FAILED")
                  .doesNotContain("secret_marker_table_zz")
                  .doesNotContain("INSERT")
                  .doesNotContain("VALUES");
              assertThat(e.getMessage()).doesNotContain(schema);
            });
  }

  @Test
  void everyTableOfTheDocumentExistsAfterMigration() throws SQLException {
    Migrator.migrate(dataSource, schema);
    List<String> tables =
        column(
            "SELECT c.relname::text FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname = ? AND c.relkind IN ('r', 'p')",
            schema);
    // the union of the documents of every script: the 18 tables of V001 and object_manifest (V002)
    assertThat(tables).containsExactlyInAnyOrderElementsOf(SchemaDocument.allTableNames());

    // and, as far as the server can show it, every named constraint, index and trigger of the
    // document (the server also holds unnamed internal objects, so this is a subset check)
    Set<String> existing = new HashSet<>();
    existing.addAll(
        column(
            "SELECT con.conname::text FROM pg_constraint con"
                + " JOIN pg_namespace n ON n.oid = con.connamespace"
                + " WHERE n.nspname = ? AND con.contype IN ('p', 'u', 'f', 'c')",
            schema));
    existing.addAll(column("SELECT indexname::text FROM pg_indexes WHERE schemaname = ?", schema));
    existing.addAll(
        column(
            "SELECT t.tgname::text FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid"
                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname = ? AND NOT t.tgisinternal",
            schema));
    assertThat(existing)
        .containsAll(
            SchemaDocument.allConstraintEntries().stream()
                .map(SchemaDocument.Entry::name)
                .toList());

    // the one seeded row: T17 reads and updates it (V001 deviation 1)
    assertThat(column("SELECT epoch::text FROM " + table("restore_epoch"))).containsExactly("0");
  }
}
