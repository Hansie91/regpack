package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.postgres.SchemaDocument.Entry;
import dev.regpack.postgres.migrate.ScriptCatalogue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The reviewed schema document and the migrations must say the same (review focus LF13): the
 * machine-read blocks of {@code docs/ledger/schema-design.md} against the statements of {@code
 * V*.sql}, the recorded digest of the approved document and the recorded checksum of {@code V001}.
 */
class SchemaDocMatchesMigrationsTest {
  private static final Pattern TABLE = Pattern.compile("^CREATE TABLE (\\w+) \\(");
  private static final Pattern CONSTRAINT =
      Pattern.compile("CONSTRAINT (\\w+) (PRIMARY KEY|UNIQUE|FOREIGN KEY|CHECK)\\b");
  private static final Pattern INDEX =
      Pattern.compile("^CREATE (UNIQUE )?INDEX (\\w+) ON (\\w+)\\b");
  private static final Pattern TRIGGER = Pattern.compile("^CREATE TRIGGER (\\w+) .*? ON (\\w+) ");
  private static final Pattern KIND_WORD =
      Pattern.compile("\\b(PRIMARY KEY|UNIQUE|FOREIGN KEY|CHECK)\\b");
  private static final Pattern TIME_OR_RANDOM =
      Pattern.compile(
          "(?i)\\b(now|current_timestamp|current_date|localtimestamp|clock_timestamp|statement_timestamp|transaction_timestamp|random|gen_random_uuid)\\b");

  /** The statements of every script, whitespace collapsed to single blanks. */
  private static List<String> statements() {
    List<String> all = new ArrayList<>();
    for (ScriptCatalogue.Script script : ScriptCatalogue.fromClasspath().scripts()) {
      for (String statement : script.statements()) {
        all.add(statement.replaceAll("\\s+", " ").strip());
      }
    }
    return all;
  }

  private static List<String> tablesOfTheMigrations() {
    List<String> tables = new ArrayList<>();
    for (String statement : statements()) {
      Matcher m = TABLE.matcher(statement);
      if (m.find()) {
        tables.add(m.group(1));
      }
    }
    return tables;
  }

  private static String kindOf(String words) {
    return switch (words) {
      case "PRIMARY KEY" -> "PK";
      case "UNIQUE" -> "UNIQUE";
      case "FOREIGN KEY" -> "FK";
      case "CHECK" -> "CHECK";
      default -> throw new IllegalStateException(words);
    };
  }

  private static List<Entry> namedObjectsOfTheMigrations() {
    List<Entry> found = new ArrayList<>();
    for (String statement : statements()) {
      Matcher table = TABLE.matcher(statement);
      if (table.find()) {
        Matcher c = CONSTRAINT.matcher(statement);
        while (c.find()) {
          found.add(new Entry(c.group(1), table.group(1), kindOf(c.group(2))));
        }
        continue;
      }
      Matcher index = INDEX.matcher(statement);
      if (index.find()) {
        boolean unique = index.group(1) != null;
        boolean partial = statement.contains(" WHERE ");
        String kind = unique ? (partial ? "PARTIAL_UNIQUE" : "UNIQUE") : "INDEX";
        found.add(new Entry(index.group(2), index.group(3), kind));
        continue;
      }
      Matcher trigger = TRIGGER.matcher(statement);
      if (trigger.find()) {
        found.add(new Entry(trigger.group(1), trigger.group(2), "TRIGGER"));
      }
    }
    return found;
  }

  @Test
  void tablesInTheDocumentEqualTheTablesOfTheMigrations() {
    List<String> document = SchemaDocument.tableNames();
    assertThat(document).as("the 18 tables of D-S15").hasSize(18).doesNotHaveDuplicates();
    assertThat(tablesOfTheMigrations()).containsExactlyInAnyOrderElementsOf(document);
  }

  @Test
  void constraintsInTheDocumentEqualTheNamedConstraintsOfTheMigrations() {
    List<Entry> document = SchemaDocument.constraintEntries();
    assertThat(document).extracting(Entry::name).doesNotHaveDuplicates();
    List<Entry> migrations = namedObjectsOfTheMigrations();
    assertThat(migrations)
        .extracting(Entry::name)
        .as("names in the migrations")
        .doesNotHaveDuplicates();
    // name, table and kind: a constraint that moved to another table or changed its kind differs
    assertThat(migrations).containsExactlyInAnyOrderElementsOf(document);
    // every table of a constraint row is a table of the document
    Set<String> tables = new HashSet<>(SchemaDocument.tableNames());
    assertThat(document).extracting(Entry::table).allMatch(tables::contains);
  }

  @Test
  void everyConstraintAndIndexIsNamed() {
    for (String statement : statements()) {
      if (statement.startsWith("CREATE INDEX") || statement.startsWith("CREATE UNIQUE INDEX")) {
        assertThat(INDEX.matcher(statement).find())
            .as("an index has a name, then ON: %s", statement)
            .isTrue();
        continue;
      }
      if (statement.startsWith("CREATE FUNCTION")
          || statement.startsWith("CREATE SEQUENCE")
          || statement.startsWith("INSERT INTO")) {
        continue;
      }
      int words = count(KIND_WORD, statement);
      int named = count(CONSTRAINT, statement);
      assertThat(named)
          .as(
              "every PRIMARY KEY, UNIQUE, FOREIGN KEY and CHECK is introduced by CONSTRAINT <name>: %s",
              statement)
          .isEqualTo(words);
      int references = count(Pattern.compile("\\bREFERENCES\\b"), statement);
      int foreignKeys = count(Pattern.compile("\\bFOREIGN KEY\\b"), statement);
      assertThat(references)
          .as("no inline column REFERENCES: %s", statement)
          .isEqualTo(foreignKeys);
    }
    for (String statement : statements()) {
      if (statement.startsWith("CREATE TRIGGER")) {
        assertThat(TRIGGER.matcher(statement).find())
            .as("a trigger has a name and a table: %s", statement)
            .isTrue();
      }
    }
  }

  private static int count(Pattern pattern, String text) {
    Matcher m = pattern.matcher(text);
    int n = 0;
    while (m.find()) {
      n++;
    }
    return n;
  }

  @Test
  void noTimeOrRandomnessDefaultsInTheMigrations() {
    List<String> hits = new ArrayList<>();
    for (ScriptCatalogue.Script script : ScriptCatalogue.fromClasspath().scripts()) {
      for (String statement : script.statements()) {
        String flat = statement.replaceAll("\\s+", " ").strip();
        Matcher table = TABLE.matcher(flat);
        String where = table.find() ? table.group(1) : "other";
        Matcher m = TIME_OR_RANDOM.matcher(flat);
        while (m.find()) {
          hits.add(script.name() + "/" + where + "/" + m.group(1));
        }
      }
    }
    // D-S13: every instant is bound from the injected Clock; the only SQL default is the
    // applied_at of the migration history, written by the migrator itself
    assertThat(hits).containsExactly("V001__core.sql/schema_migration/now");
  }

  @Test
  void partialIndexPredicatesCarryThePinnedStateCodes() {
    // the names and kinds are compared above; the state codes inside the predicates are pinned in
    // docs/ledger/decisions.md ("state codes inside V001") and checked here against the text
    List<String> flat = statements();
    assertThat(flat)
        .contains(
            "CREATE UNIQUE INDEX delivery_in_flight ON delivery (endpoint_id, business_key)"
                + " WHERE state IN (4, 5, 11) AND NOT gate_released;")
        .contains("CREATE INDEX delivery_sending_idx ON delivery (endpoint_id) WHERE state = 4;")
        .contains("CREATE INDEX outbox_pending_idx ON outbox (outbox_id) WHERE state = 1;");
  }

  @Test
  void approvedDocumentDigestIsRecorded() {
    String recorded = SchemaDocument.approvedSha256();
    assertThat(recorded)
        .as("approved-schema-sha256 in docs/ledger/decisions.md (AP-7) is a SHA-256")
        .matches("[0-9a-f]{64}");
    assertThat(SchemaDocument.documentSha256())
        .as("docs/ledger/schema-design.md changed after the owner approved it (AP-7)")
        .isEqualTo(recorded);
    Map<String, String> pins = SchemaDocument.pins();
    assertThat(pins).containsKey("schema-design.md (approved)");
    assertThat(pins.get("schema-design.md (approved)")).isEqualTo(recorded);
  }

  @Test
  void v001ChecksumIsRecorded() {
    ScriptCatalogue.Script v001 = ScriptCatalogue.fromClasspath().scripts().get(0);
    assertThat(v001.name()).isEqualTo("V001__core.sql");
    Map<String, String> pins = SchemaDocument.pins();
    assertThat(pins)
        .as("Pins created in docs/ledger/decisions.md has a line for V001__core.sql")
        .containsKey("V001__core.sql");
    assertThat(v001.sha256())
        .as("an applied script is never edited; a change is a new script")
        .isEqualTo(pins.get("V001__core.sql"));
  }
}
