package dev.regpack.spikes.l.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The SQL of the sketch cannot run on this machine (no PostgreSQL), so the rules of PostgreSQL 18
 * that a mistake would break only on the runner are checked on the text: a unique or primary key of
 * a partitioned table holds the partition key, a referenced table exists earlier, every statement
 * ends in a semicolon at the end of a line (the installer splits there).
 */
class LedgerSketchSqlTest {
  private static final Pattern CREATE_TABLE =
      // reluctant body: the first closing parenthesis that is followed by the end or by the
      // partition clause
      Pattern.compile("(?s)^CREATE TABLE (\\w+) \\((.*?)\\)( PARTITION BY RANGE \\((\\w+)\\))?$");
  private static final List<String> TABLES =
      List.of(
          "decision_head",
          "decision",
          "report_subject",
          "trn_assignment",
          "delivery",
          "attempt",
          "lease",
          "seq_counter",
          "ingest",
          "ingest_registry",
          "ingest_checkpoint",
          "outbox",
          "report_version",
          "delivery_event");

  private static String sketch() throws IOException {
    try (InputStream in = LedgerSketchSqlTest.class.getResourceAsStream("/ledger-sketch.sql")) {
      assertThat(in).as("ledger-sketch.sql on the class path").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void statementsSplitAtSemicolonsThatEndALine() {
    String sql =
        "-- a comment; with a semicolon\nCREATE TABLE a (\n  x int\n);\n\nCREATE TABLE b (y int);\n";
    assertThat(SchemaInstaller.statements(sql))
        .containsExactly("CREATE TABLE a (\n  x int\n)", "CREATE TABLE b (y int)");
  }

  @Test
  void anUnterminatedStatementIsRejected() {
    assertThatThrownBy(() -> SchemaInstaller.statements("CREATE TABLE a (x int)\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unterminated");
  }

  @Test
  void theSketchHasEveryTableOfTheDesign() throws IOException {
    List<String> created = new ArrayList<>();
    for (String statement : SchemaInstaller.statements(sketch())) {
      Matcher m = CREATE_TABLE.matcher(statement.strip());
      if (m.matches()) {
        created.add(m.group(1));
      }
    }
    assertThat(created).containsExactlyElementsOf(TABLES);
  }

  @Test
  void everyKeyOfAPartitionedTableContainsThePartitionKey() throws IOException {
    int partitioned = 0;
    for (String statement : SchemaInstaller.statements(sketch())) {
      Matcher m = CREATE_TABLE.matcher(statement.strip());
      if (!m.matches() || m.group(3) == null) {
        continue;
      }
      partitioned++;
      String key = m.group(4);
      Matcher keys = Pattern.compile("(?:PRIMARY KEY|UNIQUE) \\(([^)]*)\\)").matcher(m.group(2));
      int seen = 0;
      while (keys.find()) {
        seen++;
        assertThat(List.of(keys.group(1).split("\\s*,\\s*")))
            .as(m.group(1) + " key (" + keys.group(1) + ") holds " + key)
            .contains(key);
      }
      assertThat(seen).as(m.group(1) + " declares keys").isGreaterThanOrEqualTo(1);
    }
    // decision and ingest, nothing else is partitioned
    assertThat(partitioned).isEqualTo(2);
  }

  @Test
  void aColumnLevelKeyIsNeverUsedOnAPartitionedTable() throws IOException {
    for (String statement : SchemaInstaller.statements(sketch())) {
      Matcher m = CREATE_TABLE.matcher(statement.strip());
      if (m.matches() && m.group(3) != null) {
        assertThat(m.group(2)).as(m.group(1)).doesNotContainPattern("\\w+ \\w+[^,]* PRIMARY KEY");
        assertThat(m.group(2)).as(m.group(1)).doesNotContainPattern("\\w+ \\w+[^,]* UNIQUE");
      }
    }
  }

  @Test
  void everyReferencedTableIsCreatedEarlier() throws IOException {
    Map<String, Integer> position = new LinkedHashMap<>();
    int i = 0;
    for (String statement : SchemaInstaller.statements(sketch())) {
      Matcher m = CREATE_TABLE.matcher(statement.strip());
      if (m.matches()) {
        Matcher ref = Pattern.compile("REFERENCES (\\w+)").matcher(m.group(2));
        while (ref.find()) {
          assertThat(position)
              .as(m.group(1) + " references " + ref.group(1))
              .containsKey(ref.group(1));
        }
        position.put(m.group(1), i);
      }
      i++;
    }
  }

  @Test
  void theInFlightGuardIsAPartialUniqueIndexOnTheThreeStates() throws IOException {
    String sql = sketch();
    assertThat(sql)
        .contains("CREATE UNIQUE INDEX delivery_in_flight ON delivery (endpoint_id, business_key)")
        .contains("WHERE state IN ('SENDING','AWAITING_ACK','IN_DOUBT') AND NOT gate_released");
  }

  @Test
  void theSketchUsesNoSchemaQualifiedNames() throws IOException {
    // the installer sets the search path; a qualified name would escape the check's own schema
    String code = String.join(";\n", SchemaInstaller.statements(sketch()));
    assertThat(code).doesNotContain("public.").doesNotContain("CREATE SCHEMA");
  }

  @Test
  void dailyPartitionsAreContiguousAndNamedByDate() {
    List<String> statements =
        SchemaInstaller.partitionStatements("decision", LocalDate.of(2026, 12, 30), 3);
    assertThat(statements)
        .containsExactly(
            "CREATE TABLE decision_20261230 PARTITION OF decision FOR VALUES FROM ('2026-12-30') TO ('2026-12-31')",
            "CREATE TABLE decision_20261231 PARTITION OF decision FOR VALUES FROM ('2026-12-31') TO ('2027-01-01')",
            "CREATE TABLE decision_20270101 PARTITION OF decision FOR VALUES FROM ('2027-01-01') TO ('2027-01-02')");
  }

  @Test
  void schemaNamesAreRestrictedToLowerCaseIdentifiers() {
    assertThat(SchemaInstaller.quote("sp_chk_c01")).isEqualTo("\"sp_chk_c01\"");
    assertThatThrownBy(() -> SchemaInstaller.quote("x\"; DROP SCHEMA public"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SchemaInstaller.quote("Upper"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SchemaInstaller.quote(""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SchemaInstaller.quote("a".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void dropListsOnlyTableChildrenOfTheSchema() {
    // pg_inherits also holds the index inheritance (each partition index is a child of the
    // partitioned index); dropping those as tables fails once their partition is gone
    String sql = SchemaInstaller.DROP_CHILDREN_SQL;
    assertThat(sql)
        .contains("FROM pg_inherits")
        .contains("c.relkind IN ('r', 'p')")
        .contains("n.nspname = ?");
  }

  @Test
  void dropRemovesTheChildTablesWithIfExists() {
    assertThat(SchemaInstaller.dropChildStatement("\"s\"", "decision_20260101"))
        .isEqualTo("DROP TABLE IF EXISTS \"s\".\"decision_20260101\"");
  }

  @Test
  void partitionCountMustBePositive() {
    assertThatThrownBy(
            () -> SchemaInstaller.partitionStatements("decision", LocalDate.of(2026, 1, 1), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
