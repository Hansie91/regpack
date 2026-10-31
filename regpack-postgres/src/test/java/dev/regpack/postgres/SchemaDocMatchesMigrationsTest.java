package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.postgres.SchemaDocument.Entry;
import dev.regpack.postgres.migrate.ScriptCatalogue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The reviewed schema documents and the migrations must say the same (review focus LF13): per
 * script, the machine-read blocks of its document ({@code docs/ledger/schema-design.md} for V001,
 * {@code docs/ledger/schema-design-p3b.md} for V002, {@code docs/ledger/schema-design-p3c.md} for
 * V003) against the statements of the script, the recorded digest of each approved document and the
 * recorded checksum of each applied script.
 */
class SchemaDocMatchesMigrationsTest {
  private static final String V001 = "V001__core.sql";
  private static final String V002 = "V002__evidence_manifest.sql";
  private static final String V003 = "V003__retention_and_subject_index.sql";

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

  /** Where the approval of one document is recorded and under which names. */
  private record Approval(String decisions, String digestLine, String documentPin) {}

  private static final Map<String, Approval> APPROVALS = approvals();

  private static Map<String, Approval> approvals() {
    Map<String, Approval> approvals = new LinkedHashMap<>();
    approvals.put(
        V001,
        new Approval(
            SchemaDocument.LEDGER_DECISIONS,
            "approved-schema-sha256",
            "schema-design.md (approved)"));
    approvals.put(
        V002,
        new Approval(
            SchemaDocument.EVIDENCE_DECISIONS,
            "approved-schema-p3b-sha256",
            "schema-design-p3b.md (approved)"));
    approvals.put(
        V003,
        new Approval(
            SchemaDocument.P3C_DECISIONS,
            "approved-schema-p3c-sha256",
            "schema-design-p3c.md (approved)"));
    return approvals;
  }

  private static List<ScriptCatalogue.Script> scripts() {
    return ScriptCatalogue.fromClasspath().scripts();
  }

  private static List<String> scriptNames() {
    return scripts().stream().map(ScriptCatalogue.Script::name).toList();
  }

  private static ScriptCatalogue.Script script(String name) {
    return scripts().stream()
        .filter(s -> s.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("script not in the catalogue: " + name));
  }

  /** The statements of one script, whitespace collapsed to single blanks. */
  private static List<String> statements(ScriptCatalogue.Script script) {
    List<String> all = new ArrayList<>();
    for (String statement : script.statements()) {
      all.add(statement.replaceAll("\\s+", " ").strip());
    }
    return all;
  }

  /** The statements of every script. */
  private static List<String> statements() {
    List<String> all = new ArrayList<>();
    for (ScriptCatalogue.Script script : scripts()) {
      all.addAll(statements(script));
    }
    return all;
  }

  private static List<String> tablesOf(ScriptCatalogue.Script script) {
    List<String> tables = new ArrayList<>();
    for (String statement : statements(script)) {
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

  private static List<Entry> namedObjectsOf(ScriptCatalogue.Script script) {
    List<Entry> found = new ArrayList<>();
    for (String statement : statements(script)) {
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
  void tablesInEachDocumentEqualTheTablesOfItsScript() {
    assertThat(scriptNames())
        .as("every script has a document in SchemaDocument and every document a script")
        .containsExactlyElementsOf(SchemaDocument.DOCUMENT_OF_SCRIPT.keySet());
    for (Map.Entry<String, String> pair : SchemaDocument.DOCUMENT_OF_SCRIPT.entrySet()) {
      List<String> document = SchemaDocument.tableNames(pair.getValue());
      assertThat(document).as("tables of %s", pair.getValue()).doesNotHaveDuplicates();
      assertThat(tablesOf(script(pair.getKey())))
          .as("tables of %s against %s", pair.getKey(), pair.getValue())
          .containsExactlyInAnyOrderElementsOf(document);
    }
    assertThat(SchemaDocument.tableNames(SchemaDocument.DOCUMENT_OF_SCRIPT.get(V001)))
        .as("the 18 tables of D-S15")
        .hasSize(18);
    assertThat(SchemaDocument.tableNames(SchemaDocument.DOCUMENT_OF_SCRIPT.get(V002)))
        .as("V002 adds exactly one table")
        .containsExactly("object_manifest");
    assertThat(SchemaDocument.tableNames(SchemaDocument.DOCUMENT_OF_SCRIPT.get(V003)))
        .as("V003 adds exactly retention and subject_index")
        .containsExactly("retention", "subject_index");
    assertThat(SchemaDocument.allTableNames())
        .as("no table is created by two scripts")
        .doesNotHaveDuplicates();
  }

  @Test
  void constraintsInEachDocumentEqualTheNamedObjectsOfItsScript() {
    List<String> allNames = new ArrayList<>();
    for (Map.Entry<String, String> pair : SchemaDocument.DOCUMENT_OF_SCRIPT.entrySet()) {
      List<Entry> document = SchemaDocument.constraintEntries(pair.getValue());
      assertThat(document).extracting(Entry::name).doesNotHaveDuplicates();
      List<Entry> migrations = namedObjectsOf(script(pair.getKey()));
      assertThat(migrations)
          .extracting(Entry::name)
          .as("names in %s", pair.getKey())
          .doesNotHaveDuplicates();
      // name, table and kind: a constraint that moved to another table or changed its kind differs
      assertThat(migrations)
          .as("%s against %s", pair.getKey(), pair.getValue())
          .containsExactlyInAnyOrderElementsOf(document);
      // every table of a constraint row is a table of the same document
      Set<String> tables = new HashSet<>(SchemaDocument.tableNames(pair.getValue()));
      assertThat(document).extracting(Entry::table).allMatch(tables::contains);
      document.forEach(entry -> allNames.add(entry.name()));
    }
    assertThat(allNames).as("a name is used by one script only").doesNotHaveDuplicates();
    // the union that MigratorIT reads: the documents in script order, each in its own order
    assertThat(SchemaDocument.allConstraintEntries())
        .extracting(Entry::name)
        .containsExactlyElementsOf(allNames);
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
    for (ScriptCatalogue.Script script : scripts()) {
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
    // applied_at of the migration history, written by the migrator itself (V002 has none)
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
  void approvedDocumentDigestsAreRecorded() {
    assertThat(APPROVALS.keySet())
        .as("every script has its approval recorded")
        .containsExactlyElementsOf(SchemaDocument.DOCUMENT_OF_SCRIPT.keySet());
    for (Map.Entry<String, String> pair : SchemaDocument.DOCUMENT_OF_SCRIPT.entrySet()) {
      Approval approval = APPROVALS.get(pair.getKey());
      String recorded = SchemaDocument.approvedSha256(approval.decisions(), approval.digestLine());
      assertThat(recorded)
          .as("%s in %s (AP-7) is a SHA-256", approval.digestLine(), approval.decisions())
          .matches("[0-9a-f]{64}");
      assertThat(SchemaDocument.documentSha256(pair.getValue()))
          .as("%s changed after the owner approved it (AP-7)", pair.getValue())
          .isEqualTo(recorded);
      Map<String, String> pins = SchemaDocument.pins(approval.decisions());
      assertThat(pins)
          .as("Pins created in %s", approval.decisions())
          .containsKey(approval.documentPin());
      assertThat(pins.get(approval.documentPin())).isEqualTo(recorded);
    }
  }

  /**
   * Every script of the catalogue has a pin in the record of its own document (the map {@code
   * APPROVALS} names it), equal to its checksum: a new script cannot be added without its pin,
   * whatever per-version method exists or does not.
   */
  @Test
  void everyScriptOfTheCatalogueHasAPinEqualToItsChecksum() {
    assertThat(scripts()).isNotEmpty();
    for (ScriptCatalogue.Script script : scripts()) {
      Approval approval = APPROVALS.get(script.name());
      assertThat(approval).as("approval of %s", script.name()).isNotNull();
      Map<String, String> pins = SchemaDocument.pins(approval.decisions());
      assertThat(pins)
          .as("Pins created in %s has a line for %s", approval.decisions(), script.name())
          .containsKey(script.name());
      assertThat(script.sha256())
          .as("an applied script is never edited; a change is a new script: %s", script.name())
          .isEqualTo(pins.get(script.name()));
    }
  }

  @Test
  void v001ChecksumIsRecorded() {
    ScriptCatalogue.Script v001 = script(V001);
    assertThat(v001.version()).isEqualTo(1);
    Map<String, String> pins = SchemaDocument.pins(SchemaDocument.LEDGER_DECISIONS);
    assertThat(pins)
        .as("Pins created in docs/ledger/decisions.md has a line for V001__core.sql")
        .containsKey(V001);
    assertThat(v001.sha256())
        .as("an applied script is never edited; a change is a new script")
        .isEqualTo(pins.get(V001));
  }

  @Test
  void v002ChecksumIsRecorded() {
    ScriptCatalogue.Script v002 = script(V002);
    assertThat(v002.version()).isEqualTo(2);
    Map<String, String> pins = SchemaDocument.pins(SchemaDocument.EVIDENCE_DECISIONS);
    assertThat(pins)
        .as("Pins created in docs/evidence/decisions.md has a line for V002__evidence_manifest.sql")
        .containsKey(V002);
    assertThat(v002.sha256())
        .as("an applied script is never edited; a change is a new script")
        .isEqualTo(pins.get(V002));
  }

  @Test
  void v002HoldsExactlyTheStatementsOfTheProposedTextOfItsDocument() {
    // T11: "exactly the SQL of Port and model changes"; the document (frozen by its digest) shows
    // it as the first fenced sql block after the words "Proposed text of the migration"
    String document = SchemaDocument.read(SchemaDocument.DOCUMENT_OF_SCRIPT.get(V002));
    int heading = document.indexOf("**Proposed text of the migration");
    assertThat(heading).as("the proposed text is announced").isGreaterThanOrEqualTo(0);
    int open = document.indexOf("```sql\n", heading);
    assertThat(open).as("the proposed text is a fenced sql block").isGreaterThan(heading);
    int from = open + "```sql\n".length();
    int close = document.indexOf("\n```", from);
    assertThat(close).as("the block is closed").isGreaterThan(from);
    List<String> proposed = new ArrayList<>();
    for (String statement : ScriptCatalogue.statements(document.substring(from, close + 1))) {
      proposed.add(statement.replaceAll("\\s+", " ").strip());
    }
    assertThat(proposed).as("the block holds the three statements").hasSize(3);
    assertThat(statements(script(V002))).containsExactlyElementsOf(proposed);
  }

  @Test
  void v003ChecksumIsRecorded() {
    ScriptCatalogue.Script v003 = script(V003);
    assertThat(v003.version()).isEqualTo(3);
    Map<String, String> pins = SchemaDocument.pins(SchemaDocument.P3C_DECISIONS);
    assertThat(pins)
        .as(
            "Pins created in docs/evidence/p3c-decisions.md has a line for"
                + " V003__retention_and_subject_index.sql")
        .containsKey(V003);
    assertThat(v003.sha256())
        .as("an applied script is never edited; a change is a new script")
        .isEqualTo(pins.get(V003));
  }

  @Test
  void v003HoldsExactlyTheStatementsOfTheProposedTextOfItsDocument() {
    // T11: "exactly the statements of the approved document"; the document (frozen by its digest)
    // shows them as the first fenced sql block after the words "Proposed text of the migration"
    String document = SchemaDocument.read(SchemaDocument.DOCUMENT_OF_SCRIPT.get(V003));
    int heading = document.indexOf("**Proposed text of the migration");
    assertThat(heading).as("the proposed text is announced").isGreaterThanOrEqualTo(0);
    int open = document.indexOf("```sql\n", heading);
    assertThat(open).as("the proposed text is a fenced sql block").isGreaterThan(heading);
    int from = open + "```sql\n".length();
    int close = document.indexOf("\n```", from);
    assertThat(close).as("the block is closed").isGreaterThan(from);
    List<String> proposed = new ArrayList<>();
    for (String statement : ScriptCatalogue.statements(document.substring(from, close + 1))) {
      proposed.add(statement.replaceAll("\\s+", " ").strip());
    }
    // D2 option (a) of the approved document: two tables, two indexes, the function, the trigger
    assertThat(proposed).as("the block holds the six statements").hasSize(6);
    assertThat(statements(script(V003))).containsExactlyElementsOf(proposed);
  }

  @Test
  void aMarkerThatOccursTwiceIsRefusedBecauseTheReaderTakesTheFirstOccurrence() {
    String block = "<!-- schema:tables -->\n| h |\n|---|\n| `a` |\n<!-- /schema:tables -->\n";
    assertThat(SchemaDocument.block(block, "tables")).containsExactly("| `a` |");
    assertThatThrownBy(
            () -> SchemaDocument.block("prose <!-- schema:tables -->\n" + block, "tables"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("more than once");
    assertThatThrownBy(() -> SchemaDocument.block(block + block, "tables"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("more than once");
    assertThatThrownBy(() -> SchemaDocument.block("no block here", "tables"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not found");
  }
}
