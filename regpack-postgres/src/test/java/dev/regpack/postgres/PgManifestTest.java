package dev.regpack.postgres;

import static dev.regpack.testkit.contract.LedgerFixtures.attemptIntent;
import static dev.regpack.testkit.contract.LedgerFixtures.objectEntry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.ManifestConflictException;
import dev.regpack.ports.ledger.ManifestLedger.ManifestRow;
import dev.regpack.ports.ledger.ManifestLedger.ObjectClass;
import dev.regpack.ports.ledger.ManifestLedger.ObjectEntry;
import dev.regpack.ports.ledger.ManifestLedger.Provenance;
import dev.regpack.postgres.PgLedgerStore.Config;
import dev.regpack.testkit.clock.ManualClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What the manifest side of the adapter decides without a server (T12): the statement texts, the
 * range bounds of a prefix, the order in which keys are written, the arguments that are refused
 * before a connection is opened, the comparison of the stored hashes, the mapping of a stored row,
 * and static checks of the texts against {@code V002}. The statements themselves first run on a
 * real server at CP-1 ({@code PgLedgerStoreContractIT}, {@code PgConstraintsIT}).
 */
class PgManifestTest {
  private static final Instant NOW = Instant.parse("2026-10-09T08:00:00Z");
  private static final char DEL = (char) 0x7F;

  private final SqlSpy spy = new SqlSpy();
  private final ManualClock clock = new ManualClock(NOW);
  private final Config config = Config.forSchema("t42_7");

  private PgManifest manifest() {
    return new PgManifest(spy.over(null), clock, config);
  }

  private static byte[] hashBytes(ObjectEntry entry) {
    return HexFormat.of().parseHex(entry.sha256().hex());
  }

  /** The answer of the fake server to the read of the stored hashes: the rows of these entries. */
  private static SqlSpy.Answers storedHashesOf(ObjectEntry... entries) {
    return (sql, events) -> {
      List<Object[]> rows = new ArrayList<>();
      if (sql.equals(PgManifest.STORED_SQL)) {
        for (ObjectEntry entry : entries) {
          rows.add(new Object[] {entry.key().path(), hashBytes(entry)});
        }
      }
      return rows;
    };
  }

  private static String migrationV002() throws IOException {
    Path path =
        Path.of(
            System.getProperty("regpack.classes.dir"), "db/migration/V002__evidence_manifest.sql");
    return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
  }

  private List<String> executed() {
    return spy.events().stream()
        .filter(e -> e.startsWith("execute: "))
        .map(e -> e.substring("execute: ".length()))
        .toList();
  }

  // ---------------------------------------------------------------------------------------------
  // the statements

  @Test
  void theStatementsHaveTheShapesOfThePlan() {
    assertThat(PgManifest.RECORD_SQL)
        .isEqualTo(
            "INSERT INTO object_manifest (object_key, entity_id, object_class, sha256, size_bytes,"
                + " provenance, attempt_id, written_at) VALUES (?, 1, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT ON CONSTRAINT object_manifest_pk DO NOTHING");
    assertThat(PgManifest.STORED_SQL)
        .isEqualTo("SELECT object_key, sha256 FROM object_manifest WHERE object_key = ANY (?)");
    assertThat(PgManifest.ROWS_SQL)
        .endsWith(
            " FROM object_manifest WHERE object_key >= ? AND object_key < ? AND object_key > ?"
                + " ORDER BY object_key LIMIT ?");
    assertThat(PgManifest.ROW_SQL).endsWith(" FROM object_manifest WHERE object_key = ?");
    // one list of columns for both reads, in the order the mapping reads them
    String columns =
        "object_key, object_class, sha256, size_bytes, provenance, attempt_id, written_at";
    assertThat(PgManifest.ROWS_SQL).startsWith("SELECT " + columns + " FROM");
    assertThat(PgManifest.ROW_SQL).startsWith("SELECT " + columns + " FROM");
  }

  @Test
  void theTextsNameTheObjectsThatV002Creates() throws IOException {
    String v002 = migrationV002();
    assertThat(v002)
        .contains("CREATE TABLE object_manifest (")
        .contains("object_key text COLLATE \"C\" NOT NULL")
        .contains("CONSTRAINT object_manifest_pk PRIMARY KEY (object_key)")
        .contains("entity_id smallint NOT NULL DEFAULT 1");
    // every column the adapter writes is a column of the table
    for (String column :
        List.of(
            "object_key",
            "entity_id",
            "object_class",
            "sha256",
            "size_bytes",
            "provenance",
            "attempt_id",
            "written_at")) {
      assertThat(v002).as(column).contains(column + " ");
      assertThat(PgManifest.RECORD_SQL).as(column).contains(column);
    }
    // the arbiter of the insert is the constraint of the script, by name
    assertThat(PgManifest.RECORD_SQL).contains("ON CONFLICT ON CONSTRAINT object_manifest_pk ");
  }

  @Test
  void everyValueIsABindParameter() {
    String quote = String.valueOf((char) 0x27);
    for (String sql :
        List.of(
            PgManifest.RECORD_SQL,
            PgManifest.STORED_SQL,
            PgManifest.ROW_SQL,
            PgManifest.ROWS_SQL)) {
      // the only literal of the four texts is the entity 1 of the insert
      assertThat(sql).doesNotContain(quote);
    }

    ObjectEntry entry = objectEntry("sealed/ab/key-one", ObjectClass.SEALED_FILE, "bytes", 7);
    SqlSpy answering = new SqlSpy().answering(storedHashesOf(entry));
    PgManifest withAnswers = new PgManifest(answering.over(null), clock, config);
    withAnswers.recordObjects(List.of(entry), Provenance.WRITTEN, Optional.of(new AttemptId(3)));
    withAnswers.manifestRow(entry.key());
    String hostile = "x" + quote + " OR " + quote + "1" + quote + "=" + quote + "1";
    withAnswers.manifestRows(hostile, Optional.empty(), 10);

    // the prologue of a unit (isolation level, session settings) is not the manifest's
    List<String> statements =
        answering.events().stream()
            .filter(e -> e.startsWith("execute: ") && e.contains("object_manifest"))
            .map(e -> e.substring("execute: ".length()))
            .toList();
    assertThat(statements)
        .containsExactly(
            PgManifest.RECORD_SQL, PgManifest.STORED_SQL, PgManifest.ROW_SQL, PgManifest.ROWS_SQL);
    assertThat(answering.events())
        .contains("bind 1=" + entry.key().path(), "bind 1=" + hostile, "bind 2=" + hostile + DEL);
  }

  // ---------------------------------------------------------------------------------------------
  // ranges

  @Test
  void theRangeBoundsCoverAllAsciiKeysWithThePrefix() {
    for (String prefix :
        List.of("", "a", "journal/", "journal/control/e000000000003/a/", "sealed/ab/", "ord/a")) {
      String upper = PgManifest.upperBound(prefix);
      assertThat(upper).isEqualTo(prefix + DEL);
      // byte order of the "C" collation equals the code-unit order for ASCII: every character a key
      // can hold (all below U+007F) after the prefix stays inside [prefix, upper)
      for (char c = 0; c < DEL; c++) {
        String key = prefix + c;
        assertThat(key.compareTo(prefix)).as("key %d after '%s'", (int) c, prefix).isPositive();
        assertThat(key.compareTo(upper)).as("key %d after '%s'", (int) c, prefix).isNegative();
      }
      // the prefix itself is a member, and nothing that does not start with it is
      assertThat(prefix.compareTo(prefix)).isZero();
      assertThat(prefix.compareTo(upper)).isNegative();
      assertThat((prefix + DEL).compareTo(upper)).isZero();
    }
    // a sibling of the prefix, before or after it, is outside the range
    String upper = PgManifest.upperBound("ab");
    assertThat("aa/zzz".compareTo("ab")).isNegative();
    assertThat("ac".compareTo(upper)).isPositive();
    assertThat("a".compareTo("ab")).isNegative();
  }

  /**
   * The range from the prefix up to the prefix and U+007F holds exactly the keys with the prefix
   * only while every character an {@link EvidenceKey} can hold is below U+007F. If the alphabet of
   * the key grows to a character at or above the bound, {@code PgManifest.RANGE_END} must grow with
   * it (and the order of the {@code "C"} collation, which is by byte, must be rechecked for that
   * character).
   */
  @Test
  void everyCharacterOfAnEvidenceKeyIsBelowTheRangeEnd() {
    char rangeEnd = PgManifest.upperBound("").charAt(0);
    int accepted = 0;
    char largest = 0;
    for (int unit = 0; unit <= 0xFFFF; unit++) {
      char c = (char) unit;
      try {
        new EvidenceKey("a" + c);
      } catch (IllegalArgumentException refused) {
        continue;
      }
      accepted++;
      largest = (char) Math.max(largest, c);
      assertThat((int) c)
          .as(
              "EvidenceKey accepts U+%04X, which is not below the range end U+%04X of PgManifest",
              unit, (int) rangeEnd)
          .isLessThan(rangeEnd);
    }
    // the loop saw the alphabet: letters, digits and the punctuation, the largest being z
    assertThat(accepted).isGreaterThan(60);
    assertThat(largest).isEqualTo((char) 0x7A);
  }

  @Test
  void thePrefixTheUpperBoundAndTheAfterKeyAreBoundInThatOrder() {
    List<ManifestRow> none = manifest().manifestRows("sealed/ab/", Optional.empty(), 10);
    assertThat(none).isEmpty();
    // after is exclusive and, when absent, the empty string (every key sorts after it)
    assertThat(spy.events())
        .contains("bind 1=sealed/ab/", "bind 2=sealed/ab/" + DEL, "bind 3=")
        .contains("commit");

    SqlSpy second = new SqlSpy();
    new PgManifest(second.over(null), clock, config)
        .manifestRows("", Optional.of(new EvidenceKey("sealed/ab/cd")), 5);
    assertThat(second.events()).contains("bind 1=", "bind 2=" + DEL, "bind 3=sealed/ab/cd");
  }

  // ---------------------------------------------------------------------------------------------
  // arguments refused before a connection is opened

  @Test
  void duplicateKeysInOneCallAreRefusedBeforeAnyConnection() {
    ObjectEntry one = objectEntry("dup/one", "one");
    ObjectEntry two = objectEntry("dup/two", "two");
    ObjectEntry oneOther = objectEntry("dup/one", "another hash");

    assertThatThrownBy(
            () ->
                manifest()
                    .recordObjects(
                        List.of(one, two, oneOther), Provenance.WRITTEN, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .message()
        .doesNotContain("dup/one");
    assertThat(spy.events()).isEmpty();
  }

  @Test
  void anEmptyListOpensNoConnection() {
    manifest().recordObjects(List.of(), Provenance.WRITTEN, Optional.empty());

    assertThat(spy.events()).isEmpty();
  }

  @Test
  void nullArgumentsAreRefusedBeforeAnyConnection() {
    PgManifest manifest = manifest();
    ObjectEntry entry = objectEntry("n/one", "one");
    assertThatThrownBy(() -> manifest.recordObjects(null, Provenance.WRITTEN, Optional.empty()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> manifest.recordObjects(List.of(entry), null, Optional.empty()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> manifest.recordObjects(List.of(entry), Provenance.WRITTEN, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> manifest.manifestRow(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> manifest.manifestRows(null, Optional.empty(), 1))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> manifest.manifestRows("", null, 1))
        .isInstanceOf(NullPointerException.class);
    assertThat(spy.events()).isEmpty();
  }

  @Test
  void aNegativeLimitIsRefusedAndAZeroLimitAndANulPrefixSendNothing() {
    PgManifest manifest = manifest();
    assertThatThrownBy(() -> manifest.manifestRows("p/", Optional.empty(), -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(manifest.manifestRows("p/", Optional.empty(), 0)).isEmpty();
    // no key holds a NUL, and the server cannot take one in a text: the answer is the empty list
    assertThat(manifest.manifestRows("p/" + (char) 0 + "q", Optional.empty(), 10)).isEmpty();
    assertThat(spy.events()).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // the write

  @Test
  void entriesAreWrittenInKeyOrderWhateverTheOrderOfTheCall() {
    ObjectEntry b = objectEntry("order/b", "hb");
    ObjectEntry c = objectEntry("order/C", "hc");
    ObjectEntry a = objectEntry("order/a", "ha");
    SqlSpy answering = new SqlSpy().answering(storedHashesOf(b, c, a));
    PgManifest withAnswers = new PgManifest(answering.over(null), clock, config);

    withAnswers.recordObjects(List.of(b, c, a), Provenance.WRITTEN, Optional.empty());

    // two units that write overlapping keys in opposite orders would wait for each other; in key
    // order (byte order: capitals first) they cannot
    assertThat(answering.events().stream().filter(e -> e.startsWith("bind 1=order/")).toList())
        .containsExactly("bind 1=order/C", "bind 1=order/a", "bind 1=order/b");
    assertThat(answering.events()).contains("commit").doesNotContain("rollback");
    assertThat(answering.openConnections()).isZero();
  }

  @Test
  void aStoredRowWithAnotherHashIsAConflictThatRollsTheUnitBack() {
    ObjectEntry fresh = objectEntry("conflict/fresh", "fresh");
    ObjectEntry clash = objectEntry("conflict/clash", "bytes-B");
    ObjectEntry storedClash = objectEntry("conflict/clash", "bytes-A");
    SqlSpy answering = new SqlSpy().answering(storedHashesOf(fresh, storedClash));
    PgManifest withAnswers = new PgManifest(answering.over(null), clock, config);

    assertThatThrownBy(
            () ->
                withAnswers.recordObjects(
                    List.of(fresh, clash), Provenance.WRITTEN, Optional.of(new AttemptId(4))))
        .isInstanceOf(ManifestConflictException.class)
        .message()
        .contains("conflict/clash")
        .doesNotContain(clash.sha256().hex())
        .doesNotContain(storedClash.sha256().hex());

    assertThat(answering.events()).contains("rollback").doesNotContain("commit");
    assertThat(answering.openConnections()).isZero();
  }

  @Test
  void theFirstConflictOfTheCallInCallOrderIsTheOneNamed() {
    ObjectEntry first = objectEntry("named/zzz", "new-1");
    ObjectEntry second = objectEntry("named/aaa", "new-2");
    SqlSpy answering =
        new SqlSpy()
            .answering(
                storedHashesOf(
                    objectEntry("named/zzz", "old-1"), objectEntry("named/aaa", "old-2")));
    PgManifest withAnswers = new PgManifest(answering.over(null), clock, config);

    assertThatThrownBy(
            () ->
                withAnswers.recordObjects(
                    List.of(first, second), Provenance.WRITTEN, Optional.empty()))
        .isInstanceOf(ManifestConflictException.class)
        .message()
        .contains("named/zzz")
        .doesNotContain("named/aaa");
  }

  @Test
  void aRowThatIsNotThereAfterItsInsertIsAnErrorAndNeverSilentSuccess() {
    ObjectEntry entry = objectEntry("missing/one", "one");
    // the fake server answers the read of the stored hashes with no row
    PgManifest manifest = manifest();

    assertThatThrownBy(
            () -> manifest.recordObjects(List.of(entry), Provenance.WRITTEN, Optional.empty()))
        .isInstanceOf(LedgerException.class)
        .message()
        .contains("missing/one");
    assertThat(spy.events()).contains("rollback").doesNotContain("commit");
  }

  @Test
  void aMissingArbiterIsALedgerExceptionThatRollsBackAndNamesNoValue() {
    ObjectEntry entry = objectEntry("arbiter/one", "one");
    SqlSpy failing =
        new SqlSpy()
            .failStatementWith(
                "INSERT INTO object_manifest", new SQLException("no arbiter", "42704"));
    PgManifest manifest = new PgManifest(failing.over(null), clock, config);

    assertThatThrownBy(
            () -> manifest.recordObjects(List.of(entry), Provenance.WRITTEN, Optional.empty()))
        .isInstanceOf(LedgerException.class)
        .isNotInstanceOf(ManifestConflictException.class)
        .message()
        .contains("sqlstate=42704")
        .doesNotContain("arbiter/one");
    assertThat(failing.events()).contains("rollback").doesNotContain("commit");
    assertThat(failing.openConnections()).isZero();
  }

  @Test
  void aTimeFinerThanAMicrosecondIsRefusedBeforeAnyConnection() {
    // a Clock returns whole microseconds; a finer instant is refused (PgTime), not truncated
    Clock fine =
        new Clock() {
          @Override
          public Instant now() {
            return NOW.plusNanos(1);
          }

          @Override
          public long monotonicNanos() {
            return 0;
          }
        };
    PgManifest manifest = new PgManifest(spy.over(null), fine, config);

    assertThatThrownBy(
            () ->
                manifest.recordObjects(
                    List.of(objectEntry("fine/one", "one")), Provenance.WRITTEN, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(spy.events()).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // the read

  private static Object[] storedRow(
      ObjectEntry entry, int provenance, Long attempt, OffsetDateTime writtenAt) {
    return new Object[] {
      entry.key().path(),
      (short) (entry.objectClass().ordinal() + 1),
      hashBytes(entry),
      entry.sizeBytes(),
      (short) provenance,
      attempt,
      writtenAt
    };
  }

  @Test
  void aStoredRowIsMappedWithItsCodesHashAttemptAndTime() {
    ObjectEntry sealed = objectEntry("map/sealed", ObjectClass.SEALED_FILE, "s", 11);
    ObjectEntry journal = objectEntry("map/journal", ObjectClass.OTHER, "j", 1_000_000_000_000L);
    OffsetDateTime at = OffsetDateTime.of(2026, 10, 9, 8, 0, 0, 123_456_000, ZoneOffset.UTC);
    SqlSpy answering =
        new SqlSpy()
            .answering(
                (sql, events) -> {
                  List<Object[]> rows = new ArrayList<>();
                  rows.add(storedRow(sealed, 1, 42L, at));
                  if (sql.equals(PgManifest.ROWS_SQL)) {
                    rows.add(storedRow(journal, 2, null, at));
                  }
                  return rows;
                });
    PgManifest withAnswers = new PgManifest(answering.over(null), clock, config);
    Instant instant = Instant.parse("2026-10-09T08:00:00.123456Z");

    assertThat(withAnswers.manifestRow(sealed.key()))
        .contains(
            new ManifestRow(sealed, Provenance.WRITTEN, Optional.of(new AttemptId(42)), instant));
    assertThat(withAnswers.manifestRows("map/", Optional.empty(), 10))
        .containsExactly(
            new ManifestRow(sealed, Provenance.WRITTEN, Optional.of(new AttemptId(42)), instant),
            new ManifestRow(journal, Provenance.ADOPTED_AT_RESTORE, Optional.empty(), instant));
  }

  @Test
  void noRowIsAnEmptyOptional() {
    assertThat(manifest().manifestRow(new EvidenceKey("none/here"))).isEmpty();
  }

  @Test
  void aStoredRowThatTheModelCannotHoldIsALedgerExceptionWithoutValues() {
    ObjectEntry entry = objectEntry("bad/row", "x");
    OffsetDateTime at = OffsetDateTime.of(2026, 10, 9, 8, 0, 0, 0, ZoneOffset.UTC);
    Object[] unknownClass = storedRow(entry, 1, null, at);
    unknownClass[1] = (short) 99;
    Object[] unknownProvenance = storedRow(entry, 9, null, at);
    Object[] badKey = storedRow(entry, 1, null, at);
    badKey[0] = "not a valid/ key";
    Object[] shortHash = storedRow(entry, 1, null, at);
    shortHash[2] = new byte[31];
    for (Object[] row : List.of(unknownClass, unknownProvenance, badKey, shortHash)) {
      SqlSpy answering =
          new SqlSpy()
              .answering(
                  (sql, events) -> {
                    List<Object[]> rows = new ArrayList<>();
                    rows.add(row);
                    return rows;
                  });
      PgManifest withAnswers = new PgManifest(answering.over(null), clock, config);

      assertThatThrownBy(() -> withAnswers.manifestRow(entry.key()))
          .isInstanceOf(LedgerException.class)
          .message()
          .contains("malformed")
          .doesNotContain("not a valid");
      assertThat(answering.events()).contains("rollback").doesNotContain("commit");
    }
  }

  @Test
  void theStoreAnswersTheThreeManifestMethodsThroughThisAdapter() {
    // the placeholder of T10 threw IllegalStateException("not built until T12")
    PgLedgerStore store = new PgLedgerStore(spy.over(null), clock, config);

    // an empty list opens no connection: the call is the no-op of the port
    store.recordObjects(List.of(), Provenance.WRITTEN, Optional.empty());
    assertThat(spy.events()).isEmpty();
    assertThat(store.manifestRow(new EvidenceKey("sealed/ab/one"))).isEmpty();
    assertThat(store.manifestRows("sealed/", Optional.empty(), 5)).isEmpty();
    assertThat(executed()).anyMatch(sql -> sql.equals(PgManifest.ROW_SQL));
    assertThat(executed()).anyMatch(sql -> sql.equals(PgManifest.ROWS_SQL));
  }

  @Test
  void theStoreRecordsAnEntryThroughThisAdapterInOneCommittedUnit() {
    ObjectEntry entry = objectEntry("sealed/ab/stored", ObjectClass.SEALED_FILE, "bytes", 3);
    SqlSpy answering = new SqlSpy().answering(storedHashesOf(entry));
    PgLedgerStore store = new PgLedgerStore(answering.over(null), clock, config);

    store.recordObjects(List.of(entry), Provenance.WRITTEN, Optional.of(new AttemptId(5)));

    // a store whose recordObjects does nothing, or does not delegate, sends neither statement
    List<String> sent =
        answering.events().stream()
            .filter(e -> e.startsWith("execute: ") && e.contains("object_manifest"))
            .map(e -> e.substring("execute: ".length()))
            .toList();
    assertThat(sent).containsExactly(PgManifest.RECORD_SQL, PgManifest.STORED_SQL);
    assertThat(answering.events()).contains("bind 1=" + entry.key().path(), "commit");
    assertThat(answering.events()).doesNotContain("rollback");
    assertThat(answering.openConnections()).isZero();
  }

  // ---------------------------------------------------------------------------------------------
  // beginSending

  @Test
  void anIntentWithObjectsIsNoLongerRefusedUpFront() {
    PgDeliveries deliveries = new PgDeliveries(spy.over(null), clock, config);
    ObjectEntry object = objectEntry("sealed/aa/" + "1".repeat(64), "bytes");

    // the fake server knows no delivery: the refusal is the one of an unknown delivery, which
    // comes from the first read of the unit, so the objects were let through to that point
    assertThatThrownBy(
            () ->
                deliveries.beginSending(
                    new DeliveryId(987_654),
                    new AttemptId(1),
                    new LeaseToken(0, 1),
                    attemptIntent(object)))
        .isInstanceOf(IllegalArgumentException.class)
        .message()
        .contains("unknown delivery");
    assertThat(spy.events()).contains("rollback").doesNotContain("commit");
    assertThat(executed()).noneMatch(sql -> sql.contains("object_manifest"));
  }
}
