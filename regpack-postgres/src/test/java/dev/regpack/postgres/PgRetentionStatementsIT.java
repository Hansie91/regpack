package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Regime;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.RetentionLedger.AnchorEvent;
import dev.regpack.ports.ledger.RetentionLedger.RetentionClaim;
import dev.regpack.ports.ledger.RetentionLedger.RetentionRow;
import dev.regpack.postgres.PgLedgerStore.Config;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * What the statements and the DDL of the retention rows do on a real server, beyond the contract
 * suite, {@code PgConstraintsIT} and {@code PgRetentionLockWaitIT}: the behaviours that mutation
 * tests of the retention SQL found unpinned by any server test (the entity, the stored codes, a
 * stored row the domain refuses, an order that only the plan provides, the shape of the table).
 * Real-store test: skipped without {@code REGPACK_PG_URL}, a failure with {@code
 * REGPACK_PG_REQUIRED=true}.
 */
class PgRetentionStatementsIT extends AbstractPgStoreTest {
  private static final Instant UNTIL = Instant.parse("2030-01-01T00:00:00Z");
  private static final Instant FAR_FUTURE = Instant.parse("9999-12-31T23:59:59.999999Z");

  private static RetentionClaim claim(String artifact, String regime) {
    return claim(artifact, regime, AnchorEvent.OBJECT_WRITTEN, Optional.of(UNTIL));
  }

  private static RetentionClaim claim(
      String artifact, String regime, AnchorEvent anchor, Optional<Instant> until) {
    return new RetentionClaim(
        new EvidenceKey(artifact), new Regime(regime), anchor, until, "sealed");
  }

  private void exec(String... statements) throws SQLException {
    try (Connection connection = raw();
        Statement statement = connection.createStatement()) {
      for (String sql : statements) {
        statement.execute(sql);
      }
    }
  }

  private static String rawRow(String key, String regime, int anchor, String cls) {
    return "INSERT INTO retention (object_key, regime, anchor_event, retention_class,"
        + " retain_until, legal_hold, recorded_at) VALUES ('"
        + key
        + "', '"
        + regime
        + "', "
        + anchor
        + ", '"
        + cls
        + "', timestamptz '2030-01-01 00:00:00+00', false, now())";
  }

  private static List<String> triples(List<RetentionRow> rows) {
    List<String> out = new ArrayList<>();
    for (RetentionRow row : rows) {
      out.add(
          row.claim().artifact().path()
              + "|"
              + row.claim().regime().id()
              + "|"
              + row.claim().anchor());
    }
    return out;
  }

  // ------------------------------------------------------------------------------------------
  // the entity and the codes

  /** Release 1 has one reporting entity: the adapter writes 1 and the column defaults to 1. */
  @Test
  void everyRowIsOfEntityOneWhetherTheAdapterOrTheDefaultWritesIt() throws SQLException {
    store()
        .recordRetention(List.of(claim("sealed/e/a", "emir.eu"), claim("sealed/e/b", "emir.eu")));
    exec(rawRow("sealed/e/c", "emir.eu", 1, "sealed"));
    assertThat(count("SELECT count(*) FROM retention")).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM retention WHERE entity_id = 1")).isEqualTo(3);
  }

  /**
   * The numbers of the document, written by the adapter and read by it: OBJECT_WRITTEN 1 and
   * TERMINATION 2 (the raw statements of {@code PgConstraintsIT} use these numbers).
   */
  @Test
  void theAnchorsAreStoredAndReadWithTheCodesOfTheDocument() throws SQLException {
    store()
        .recordRetention(
            List.of(
                claim("sealed/c/a", "emir.eu"),
                claim("sealed/c/a", "emir.eu", AnchorEvent.TERMINATION, Optional.empty())));
    assertThat(
            count(
                "SELECT count(*) FROM retention WHERE anchor_event = 1"
                    + " AND retain_until IS NOT NULL"))
        .as("OBJECT_WRITTEN is stored as 1")
        .isEqualTo(1);
    assertThat(
            count("SELECT count(*) FROM retention WHERE anchor_event = 2 AND retain_until IS NULL"))
        .as("TERMINATION is stored as 2")
        .isEqualTo(1);

    exec(
        rawRow("sealed/c/b", "emir.eu", 1, "sealed"), rawRow("sealed/c/c", "emir.eu", 2, "sealed"));
    assertThat(triples(store().retentionRows("sealed/c/b", Optional.empty(), 5)))
        .containsExactly("sealed/c/b|emir.eu|OBJECT_WRITTEN");
    assertThat(triples(store().retentionRows("sealed/c/c", Optional.empty(), 5)))
        .containsExactly("sealed/c/c|emir.eu|TERMINATION");
  }

  // ------------------------------------------------------------------------------------------
  // a stored row that the domain refuses

  /**
   * The table has no CHECK on the code of the anchor, the form of the regime id or the class name
   * (the port bounds them), so the read is the one place that sees a stored row that the domain
   * would never have written: it is the ledger exception of a malformed row, which names no value,
   * and not a raw exception of the domain.
   */
  @Test
  void aStoredRowThatTheDomainRefusesIsReportedAsMalformedWithoutAValue() throws SQLException {
    List<String> bad =
        List.of(
            rawRow("sealed/m/a", "emir.eu", 99, "sealed"),
            rawRow("sealed/m/a", "Emir.EU", 1, "sealed"),
            rawRow("sealed/m/a", "emir.eu", 1, "not a class"));
    for (String sql : bad) {
      exec(sql);
      assertThatThrownBy(() -> store().retentionRows("sealed/m/", Optional.empty(), 10))
          .as(sql)
          .isInstanceOf(LedgerException.class)
          .hasMessage("stored retention row is malformed");
      assertThatThrownBy(() -> store().expiredRetention(FAR_FUTURE, Optional.empty(), 10))
          .as(sql)
          .isInstanceOf(LedgerException.class)
          .hasMessage("stored retention row is malformed");
      exec("DELETE FROM retention");
    }
  }

  // ------------------------------------------------------------------------------------------
  // the reads

  /**
   * The order of the rows and the artifacts of a page come from the {@code ORDER BY} of the
   * statements and not from the plan. The rows are stored in an order that is none of the sorted
   * ones (the termination row of the first artifact before its other row, the artifacts downwards)
   * and read through sessions that cannot use an index and prefer hashing to sorting for the {@code
   * DISTINCT}: a statement that left out a column or the sort of the keys of a page would return
   * the rows in the order of the heap or of the hash table.
   */
  @Test
  void theOrderOfTheReadsDoesNotDependOnThePlan() {
    store()
        .recordRetention(
            List.of(claim("plan/00", "emir.eu", AnchorEvent.TERMINATION, Optional.of(UNTIL))));
    for (int i = 29; i >= 0; i--) {
      store()
          .recordRetention(List.of(claim(String.format(Locale.ROOT, "plan/%02d", i), "emir.eu")));
    }
    List<String> everyRow = new ArrayList<>();
    everyRow.add("plan/00|emir.eu|OBJECT_WRITTEN");
    everyRow.add("plan/00|emir.eu|TERMINATION");
    for (int i = 1; i < 30; i++) {
      everyRow.add(String.format(Locale.ROOT, "plan/%02d|emir.eu|OBJECT_WRITTEN", i));
    }

    DataSource withoutIndexes =
        PgTestDatabase.dataSourceWithOptions(
            "-c enable_indexscan=off -c enable_indexonlyscan=off -c enable_bitmapscan=off"
                + " -c enable_sort=off");
    PgLedgerStore planless = new PgLedgerStore(withoutIndexes, clock(), Config.forSchema(schema()));

    // five artifacts a page: the first five, with both rows of the first
    assertThat(triples(planless.retentionRows("plan/", Optional.empty(), 5)))
        .containsExactlyElementsOf(everyRow.subList(0, 6));
    List<String> walked = new ArrayList<>();
    Optional<EvidenceKey> after = Optional.empty();
    for (int guard = 0; guard < 20; guard++) {
      List<RetentionRow> page = planless.retentionRows("plan/", after, 7);
      if (page.isEmpty()) {
        break;
      }
      walked.addAll(triples(page));
      after = Optional.of(page.get(page.size() - 1).claim().artifact());
    }
    assertThat(walked).containsExactlyElementsOf(everyRow);

    // the expiry list: one date for all, so the order is artifact, regime, anchor
    assertThat(triples(planless.expiredRetention(FAR_FUTURE, Optional.empty(), 100)))
        .containsExactlyElementsOf(everyRow);
    List<String> expiredWalk = new ArrayList<>();
    Optional<RetentionRow> afterRow = Optional.empty();
    for (int guard = 0; guard < 40; guard++) {
      List<RetentionRow> page = planless.expiredRetention(FAR_FUTURE, afterRow, 4);
      if (page.isEmpty()) {
        break;
      }
      expiredWalk.addAll(triples(page));
      afterRow = Optional.of(page.get(page.size() - 1));
    }
    assertThat(expiredWalk).containsExactlyElementsOf(everyRow);
  }

  // ------------------------------------------------------------------------------------------
  // the shape of the table

  /**
   * The server shows what the approved document says: the columns with their types, their
   * nullability and the one default, the primary key with its columns in order (the prefix range
   * and the paging read the leading column), the partial expiry index with its columns in order
   * (the list is ordered by them), and a {@code BEFORE UPDATE} row trigger. A mistake in these that
   * changes no result of the contract (a nullable column nobody writes null into, an integer for a
   * smallint, an index with its columns the other way round) is seen here.
   */
  @Test
  void theTableOfTheRetentionRowsHasTheColumnsKeyIndexAndTriggerOfTheDocument()
      throws SQLException {
    assertThat(
            strings(
                "SELECT a.attname::text || ' ' || format_type(a.atttypid, a.atttypmod) || ' '"
                    + " || CASE WHEN a.attnotnull THEN 'NOT NULL' ELSE 'NULL' END"
                    + " || coalesce(' DEFAULT ' || pg_get_expr(d.adbin, d.adrelid), '')"
                    + " FROM pg_attribute a LEFT JOIN pg_attrdef d"
                    + " ON d.adrelid = a.attrelid AND d.adnum = a.attnum"
                    + " WHERE a.attrelid = to_regclass(?) AND a.attnum > 0 AND NOT a.attisdropped"
                    + " ORDER BY a.attnum",
                schema() + ".retention"))
        .containsExactly(
            "object_key text NOT NULL",
            "regime text NOT NULL",
            "anchor_event smallint NOT NULL",
            "entity_id smallint NOT NULL DEFAULT 1",
            "retention_class text NOT NULL",
            "retain_until timestamp with time zone NULL",
            "legal_hold boolean NOT NULL",
            "recorded_at timestamp with time zone NOT NULL");
    assertThat(
            strings(
                "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c"
                    + " WHERE c.conname = 'retention_pk' AND c.conrelid = to_regclass(?)",
                schema() + ".retention"))
        .containsExactly("PRIMARY KEY (object_key, regime, anchor_event)");
    assertThat(
            strings("SELECT pg_get_indexdef(to_regclass(?))", schema() + ".retention_expiry_idx"))
        .singleElement()
        .asString()
        .contains("USING btree (retain_until, object_key) WHERE")
        .contains("retain_until IS NOT NULL")
        .contains("NOT legal_hold");
    // ROW (1) + BEFORE (2) + UPDATE (16)
    assertThat(
            strings(
                "SELECT t.tgtype::text FROM pg_trigger t"
                    + " WHERE t.tgname = 'retention_extend_only' AND t.tgrelid = to_regclass(?)",
                schema() + ".retention"))
        .containsExactly("19");
  }
}
