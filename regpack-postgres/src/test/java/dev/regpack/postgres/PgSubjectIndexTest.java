package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.Clock;
import dev.regpack.ports.crypto.SubjectHash;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.SubjectIndexLedger;
import dev.regpack.ports.ledger.SubjectIndexLedger.RefKind;
import dev.regpack.ports.ledger.SubjectIndexLedger.SubjectEntry;
import dev.regpack.ports.ledger.SubjectIndexLedger.SubjectRef;
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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * What the data-subject index side of the adapter decides without a server (P3c1 T12): the
 * statement texts, the order in which rows are written, the barrier of a call that writes nothing
 * (D-K8, KF22), the arguments that are refused before a connection is opened, the way a hash and a
 * reference are bound, the mapping of a stored row, and static checks of the texts against {@code
 * V003}. The statements themselves first run on a real server at CP-1 ({@code
 * PgLedgerStoreContractIT}, which binds {@code SubjectIndexLedgerContract}, and the schema mutant
 * {@code NoSubjectIndexKeyPgLedgerContractIT}); the statement shapes below are the whole local
 * evidence that the adapter does what the contract asks of it.
 */
class PgSubjectIndexTest {
  private static final Instant NOW = Instant.parse("2026-10-09T08:00:00Z");

  private final ManualClock clock = new ManualClock(NOW);
  private final Config config = Config.forSchema("t42_7");

  /** The row the fake server answers to the barrier, which fails the unit without it. */
  private static final SqlSpy.Answers BARRIER_ROW =
      (sql, events) ->
          sql.equals(Unit.BARRIER_SQL) ? List.<Object[]>of(new Object[] {1}) : List.of();

  private PgSubjectIndex index(SqlSpy spy) {
    return new PgSubjectIndex(spy.over(null), clock, config);
  }

  private static String migrationV003() throws IOException {
    Path path =
        Path.of(
            System.getProperty("regpack.classes.dir"),
            "db/migration/V003__retention_and_subject_index.sql");
    return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
  }

  /**
   * The CREATE TABLE statement of {@code subject_index} in the script: up to the first ";" line.
   */
  private static String createTableOfSubjectIndex(String script) {
    int start = script.indexOf("CREATE TABLE subject_index (");
    assertThat(start).as("the script creates subject_index").isNotNegative();
    int end = script.indexOf(";\n", start);
    assertThat(end).as("the statement ends with a semicolon line").isGreaterThan(start);
    return script.substring(start, end + 1);
  }

  /** The names in the column list of an {@code INSERT INTO table (a, b, ...) VALUES} statement. */
  private static List<String> columnsOfTheInsert(String insert) {
    int open = insert.indexOf('(');
    int close = insert.indexOf(") VALUES");
    assertThat(open).isPositive().isLessThan(close);
    return List.of(insert.substring(open + 1, close).split(", "));
  }

  /** A hash of 64 lower-case hex digits that differs for each {@code n}. */
  private static SubjectHash hash(int n) {
    String digits = Integer.toHexString(n);
    return new SubjectHash("0".repeat(64 - digits.length()) + digits);
  }

  private static SubjectRef report(String ref) {
    return new SubjectRef(RefKind.REPORT_VERSION, ref);
  }

  private static SubjectRef object(String ref) {
    return new SubjectRef(RefKind.EVIDENCE_OBJECT, ref);
  }

  private static SubjectEntry entry(SubjectHash hash, SubjectRef ref) {
    return new SubjectEntry(hash, ref);
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  /** The statements of the call, without the prologue of the unit, in the order executed. */
  private static List<String> statements(SqlSpy spy) {
    return spy.events().stream()
        .filter(
            e ->
                e.startsWith("execute: ")
                    && !e.contains("set_config")
                    && !e.startsWith("execute: SET TRANSACTION"))
        .map(e -> e.substring("execute: ".length()))
        .toList();
  }

  /** A clock that says {@code NOW} and counts how often it was asked. */
  private static Clock countingClock(AtomicInteger reads) {
    return new Clock() {
      @Override
      public Instant now() {
        reads.incrementAndGet();
        return NOW;
      }

      @Override
      public long monotonicNanos() {
        return 0;
      }
    };
  }

  /** The fake server's answer to the read of the references: (kind code, reference) per row. */
  private static SqlSpy.Answers refsAnswer(Object[]... rows) {
    return (sql, events) ->
        sql.equals(PgSubjectIndex.REFS_SQL) || sql.equals(PgSubjectIndex.REFS_AFTER_SQL)
            ? Arrays.asList(rows)
            : List.<Object[]>of();
  }

  // ---------------------------------------------------------------------------------------------
  // the statements

  @Test
  void theStatementsHaveTheShapesOfThePlan() {
    assertThat(PgSubjectIndex.INSERT_SQL)
        .isEqualTo(
            "INSERT INTO subject_index (subject_hash, ref_kind, ref, entity_id, recorded_at)"
                + " VALUES (?, ?, ?, 1, ?) ON CONFLICT ON CONSTRAINT subject_index_pk DO NOTHING");
    assertThat(PgSubjectIndex.REFS_SQL)
        .isEqualTo(
            "SELECT ref_kind, ref FROM subject_index WHERE subject_hash = ?"
                + " ORDER BY ref_kind, ref LIMIT ?");
    assertThat(PgSubjectIndex.REFS_AFTER_SQL)
        .isEqualTo(
            "SELECT ref_kind, ref FROM subject_index WHERE subject_hash = ?"
                + " AND (ref_kind, ref) > (?, ?) ORDER BY ref_kind, ref LIMIT ?");
    assertThat(PgSubjectIndex.DELETE_SQL)
        .isEqualTo("DELETE FROM subject_index WHERE ref_kind = ? AND ref = ?");
  }

  @Test
  void theTextsNameTheObjectsThatV003Creates() throws IOException {
    String v003 = migrationV003();
    assertThat(v003)
        .contains("CREATE TABLE subject_index (")
        .contains("subject_hash bytea NOT NULL")
        .contains("ref_kind smallint NOT NULL")
        .contains("ref text COLLATE \"C\" NOT NULL")
        .contains("entity_id smallint NOT NULL DEFAULT 1")
        .contains("CONSTRAINT subject_index_pk PRIMARY KEY (subject_hash, ref_kind, ref)")
        .contains("CONSTRAINT subject_index_hash_check CHECK (octet_length(subject_hash) = 32)")
        .contains("CREATE INDEX subject_index_ref_idx ON subject_index (ref_kind, ref)");
    // every column the adapter writes is defined by the CREATE TABLE statement of subject_index
    // (and only that statement: the script holds the names of other tables' columns as well, so a
    // search of the whole script would find any name), and the insert names exactly those columns
    String createTable = createTableOfSubjectIndex(v003);
    List<String> insertColumns = columnsOfTheInsert(PgSubjectIndex.INSERT_SQL);
    List<String> columns = List.of("subject_hash", "ref_kind", "ref", "entity_id", "recorded_at");
    assertThat(insertColumns).as("the columns of INSERT_SQL").containsExactlyElementsOf(columns);
    for (String column : columns) {
      assertThat(createTable)
          .as("a definition of %s in the CREATE TABLE statement", column)
          .containsPattern("(^|[\\s(,])" + column + " (bytea|smallint|text|timestamptz)\\b");
    }
    // the arbiter of the insert is the constraint of the script, by name
    assertThat(PgSubjectIndex.INSERT_SQL).contains("ON CONFLICT ON CONSTRAINT subject_index_pk ");
    // the reads follow the primary key (hash, kind, reference), the delete follows the index of
    // (kind, reference) that the script creates for it
    assertThat(PgSubjectIndex.REFS_SQL).contains("WHERE subject_hash = ? ORDER BY ref_kind, ref");
    assertThat(PgSubjectIndex.DELETE_SQL).contains("WHERE ref_kind = ? AND ref = ?");
  }

  /**
   * The order of the references is the order of the column {@code ref}, {@code COLLATE "C"} in the
   * script: the same column is sorted ({@code ORDER BY}) and compared ({@code (ref_kind, ref) > (?,
   * ?)}), and neither statement names a collation, a cast or a function of the column, so that the
   * collation of the column governs both. What the comparison relies on is a recollection of the
   * PostgreSQL rule for collation derivation: a bound parameter carries the default collation
   * (implicit), a column its own, and when two inputs of an operator differ in implicit collation
   * the one that is not the default wins [R]. CP-1 shows it: {@code
   * refsAreOrderedByKindThenByByteOrderOfTheRefNotByCollation} and {@code
   * refsBeyondTheKeyAlphabetAreOrderedByCodePointNotByUtf16Unit} page with bounds in the middle of
   * the characters on a cluster whose default collation is not C.
   */
  @Test
  void theOrderAndTheBoundAreOnTheColumnAndNameNoCollation() throws IOException {
    for (String sql : List.of(PgSubjectIndex.REFS_SQL, PgSubjectIndex.REFS_AFTER_SQL)) {
      assertThat(sql)
          .contains(" ORDER BY ref_kind, ref LIMIT ?")
          .doesNotContain("COLLATE")
          .doesNotContain("::")
          .doesNotContain("(ref)")
          .doesNotContain("lower(")
          .doesNotContain("upper(")
          .doesNotContain("cast(");
    }
    assertThat(PgSubjectIndex.REFS_AFTER_SQL).contains(" AND (ref_kind, ref) > (?, ?) ORDER BY ");
    assertThat(migrationV003()).contains("ref text COLLATE \"C\" NOT NULL");
  }

  @Test
  void everyValueIsABindParameter() {
    String quote = String.valueOf((char) 0x27);
    for (String sql :
        List.of(
            PgSubjectIndex.INSERT_SQL,
            PgSubjectIndex.REFS_SQL,
            PgSubjectIndex.REFS_AFTER_SQL,
            PgSubjectIndex.DELETE_SQL)) {
      // no text literal at all; the only literal is the entity 1 of the insert
      assertThat(sql).doesNotContain(quote);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // writing

  /**
   * D-K8 and KF22: a call that may insert nothing (every entry stored already) or delete nothing
   * (the reference is unknown) writes no WAL, so its commit would not wait for the standby, and a
   * retry of a commit that was "not confirmed" would be acknowledged on the strength of rows that
   * exist on the primary only. The last statement before the commit is the barrier.
   */
  @Test
  void aCallThatInsertsOrDeletesNothingEndsWithTheBarrier() {
    SqlSpy inserting = new SqlSpy().batchingRows(0).answering(BARRIER_ROW);
    index(inserting).indexSubjects(List.of(entry(hash(1), object("sealed/a"))));

    assertThat(statements(inserting))
        .as("the insert that inserted 0 rows is followed by the barrier")
        .containsExactly(PgSubjectIndex.INSERT_SQL, Unit.BARRIER_SQL);
    List<String> events = inserting.events();
    assertThat(events.subList(events.indexOf("execute: " + Unit.BARRIER_SQL), events.size()))
        .as("nothing between the barrier and the commit")
        .filteredOn(e -> e.startsWith("execute: "))
        .containsExactly("execute: " + Unit.BARRIER_SQL);
    assertThat(events).containsSubsequence("execute: " + Unit.BARRIER_SQL, "commit");

    SqlSpy deleting = new SqlSpy().updatingRows(0).answering(BARRIER_ROW);
    assertThat(index(deleting).removeSubjectRefs(object("sealed/none"))).isZero();

    assertThat(statements(deleting))
        .as("the DELETE of 0 rows is followed by the barrier")
        .containsExactly(PgSubjectIndex.DELETE_SQL, Unit.BARRIER_SQL);
    assertThat(deleting.events()).containsSubsequence("execute: " + Unit.BARRIER_SQL, "commit");
  }

  @Test
  void aCallThatWritesARowAddsNoBarrier() {
    SqlSpy inserting = new SqlSpy().batchingRows(1).answering(BARRIER_ROW);
    index(inserting).indexSubjects(List.of(entry(hash(1), object("sealed/a"))));
    assertThat(statements(inserting)).containsExactly(PgSubjectIndex.INSERT_SQL);
    assertThat(inserting.events()).contains("commit");

    SqlSpy deleting = new SqlSpy().updatingRows(3).answering(BARRIER_ROW);
    assertThat(index(deleting).removeSubjectRefs(object("sealed/a"))).isEqualTo(3);
    assertThat(statements(deleting)).containsExactly(PgSubjectIndex.DELETE_SQL);
    assertThat(deleting.events()).contains("commit");
  }

  /**
   * A driver that rewrites a batch answers {@code SUCCESS_NO_INFO} (-2) and says nothing about the
   * rows: that is not a row inserted, and the barrier that was not needed costs one lock where a
   * barrier that was missing costs an acknowledgement (as {@code PgManifest}).
   */
  @Test
  void aBatchThatSaysNothingAboutItsRowsIsTreatedAsInsertingNothing() {
    SqlSpy unknown = new SqlSpy().batchingRows(-2).answering(BARRIER_ROW);
    index(unknown).indexSubjects(List.of(entry(hash(1), object("sealed/a"))));

    assertThat(statements(unknown)).containsExactly(PgSubjectIndex.INSERT_SQL, Unit.BARRIER_SQL);
  }

  /**
   * The rows are inserted in the order of the primary key, (hash, kind, reference) with the
   * reference in code point order, whatever the order of the list, and a repeat inside the call is
   * sent once. Two calls with overlapping entries then wait for each other in one direction only:
   * an insert that conflicts with a row that another unit has not committed waits for it, and two
   * units that take the same rows in opposite orders would wait in a circle.
   */
  @Test
  void entriesAreWrittenInKeyOrder() {
    SqlSpy spy = new SqlSpy().recordingTypedBinds();
    String e000 = "r/" + Character.toString(0xE000);
    String smile = "r/" + Character.toString(0x1F600);
    List<SubjectEntry> given =
        List.of(
            entry(hash(2), object("r/b")),
            entry(hash(1), object(smile)),
            entry(hash(1), report("z")),
            entry(hash(1), object(e000)),
            entry(hash(2), report("a")),
            entry(hash(1), object("r/a")),
            entry(hash(1), report("z")));

    index(spy).indexSubjects(given);

    List<String> events = spy.events();
    List<String> hashes = binds(events, "bind bytes 1=");
    List<String> kinds = binds(events, "bind short 2=");
    List<String> refs = binds(events, "bind 3=");
    // the supplementary character sorts after U+E000 by code point (UTF-16 would put it before)
    assertThat(refs).containsExactly("z", "r/a", e000, smile, "a", "r/b");
    assertThat(kinds).containsExactly("1", "2", "2", "2", "1", "2");
    assertThat(hashes)
        .containsExactly(
            hash(1).hex(),
            hash(1).hex(),
            hash(1).hex(),
            hash(1).hex(),
            hash(2).hex(),
            hash(2).hex());
    assertThat(statements(spy)).containsExactly(PgSubjectIndex.INSERT_SQL);
  }

  /**
   * The binds of the statements of the calls that start with {@code prefix}, in order, without the
   * binds of the prologue of each unit (the schema and the two timeouts, which are text binds too).
   */
  private static List<String> binds(List<String> events, String prefix) {
    List<String> found = new ArrayList<>();
    boolean inPrologue = false;
    for (String event : events) {
      if (event.startsWith("execute: SET TRANSACTION")) {
        inPrologue = true;
      } else if (inPrologue && event.startsWith("execute: ") && event.contains("set_config")) {
        inPrologue = false;
      } else if (!inPrologue && event.startsWith(prefix)) {
        found.add(event.substring(prefix.length()));
      }
    }
    return found;
  }

  /**
   * The hash is bound as the 32 bytes of the {@code bytea} column (the script's check is {@code
   * octet_length = 32}), not as the 64 hex digits; the kind as its stored code; the reference as
   * text; and one ledger time for every row of the call.
   */
  @Test
  void theHashIsBoundAsThirtyTwoBytesTheKindAsItsCodeAndOneTimeForTheCall() {
    SqlSpy spy = new SqlSpy().recordingTypedBinds();
    SubjectHash hash =
        new SubjectHash("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");

    index(spy)
        .indexSubjects(List.of(entry(hash, object("sealed/a")), entry(hash, report("LEI1|X|E1"))));

    List<String> events = spy.events();
    assertThat(binds(events, "bind bytes 1="))
        .containsExactly(hash.hex(), hash.hex())
        .allSatisfy(bound -> assertThat(HexFormat.of().parseHex(bound)).hasSize(32));
    assertThat(binds(events, "bind short 2=")).containsExactly("1", "2");
    assertThat(binds(events, "bind 3=")).containsExactly("LEI1|X|E1", "sealed/a");
    assertThat(binds(events, "bind object 4="))
        .containsExactly(utc(NOW).toString(), utc(NOW).toString());
    // the hash never travels as text
    assertThat(binds(events, "bind 1=")).isEmpty();
  }

  @Test
  void theClockIsReadOnceForTheWholeCall() {
    AtomicInteger reads = new AtomicInteger();
    SqlSpy spy = new SqlSpy();
    PgSubjectIndex index = new PgSubjectIndex(spy.over(null), countingClock(reads), config);

    index.indexSubjects(
        List.of(
            entry(hash(1), object("sealed/a")),
            entry(hash(1), object("sealed/b")),
            entry(hash(2), object("sealed/a"))));

    assertThat(reads).hasValue(1);
  }

  @Test
  void anEmptyListOpensNoConnectionAndReadsNoClock() {
    AtomicInteger reads = new AtomicInteger();
    SqlSpy spy = new SqlSpy();
    PgSubjectIndex index = new PgSubjectIndex(spy.over(null), countingClock(reads), config);

    index.indexSubjects(List.of());

    assertThat(spy.events()).isEmpty();
    assertThat(reads).hasValue(0);
  }

  @Test
  void nullArgumentsAreRefusedBeforeAnyStatement() {
    SqlSpy spy = new SqlSpy();
    PgSubjectIndex index = index(spy);
    SubjectEntry good = entry(hash(1), object("sealed/a"));

    assertThatThrownBy(() -> index.indexSubjects(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> index.indexSubjects(Arrays.asList(good, null)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> index.indexSubjects(Arrays.asList(null, good)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> index.subjectRefs(null, Optional.empty(), 1))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> index.subjectRefs(hash(1), null, 1))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> index.removeSubjectRefs(null))
        .isInstanceOf(NullPointerException.class);
    assertThat(spy.events()).isEmpty();
  }

  @Test
  void aFailureOfTheServerIsALedgerExceptionWithItsStateAndNoValue() {
    // the text of a server error can hold the key of a row; the message of the exception never does
    SQLException server =
        new SQLException("Key (subject_hash, ref)=(secret-hash, sealed/SECRET-REF)", "23505");
    SqlSpy failing = new SqlSpy().failStatementWith("INSERT INTO subject_index", server);

    assertThatThrownBy(
            () ->
                index(failing).indexSubjects(List.of(entry(hash(1), object("sealed/SECRET-REF")))))
        .isInstanceOf(LedgerException.class)
        .hasMessage("ledger statement failed: sqlstate=23505 constraint=none");
    assertThat(failing.events()).contains("rollback").doesNotContain("commit");
    assertThat(failing.openConnections()).isZero();

    SqlSpy deleting = new SqlSpy().failStatementWith("DELETE FROM subject_index", server);
    assertThatThrownBy(() -> index(deleting).removeSubjectRefs(object("sealed/SECRET-REF")))
        .isInstanceOf(LedgerException.class)
        .hasMessage("ledger statement failed: sqlstate=23505 constraint=none");
    assertThat(deleting.events()).contains("rollback").doesNotContain("commit");
  }

  // ---------------------------------------------------------------------------------------------
  // deleting

  @Test
  void theDeleteNamesTheKindAndTheTextAndReturnsTheRowsDeleted() {
    SqlSpy spy = new SqlSpy().recordingTypedBinds().updatingRows(2);

    assertThat(index(spy).removeSubjectRefs(object("sealed/2026/a"))).isEqualTo(2);
    assertThat(index(spy).removeSubjectRefs(report("LEI1|XPAR|E1"))).isEqualTo(2);

    assertThat(binds(spy.events(), "bind short 1=")).containsExactly("2", "1");
    assertThat(binds(spy.events(), "bind 2=")).contains("sealed/2026/a", "LEI1|XPAR|E1");
    assertThat(statements(spy))
        .containsExactly(PgSubjectIndex.DELETE_SQL, PgSubjectIndex.DELETE_SQL);
  }

  // ---------------------------------------------------------------------------------------------
  // reading

  @Test
  void aNegativeLimitIsRefusedAndALimitOfZeroListsNothingWithoutAConnection() {
    SqlSpy spy = new SqlSpy();
    PgSubjectIndex index = index(spy);

    assertThatThrownBy(() -> index.subjectRefs(hash(1), Optional.empty(), -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("limit must not be negative");
    assertThat(index.subjectRefs(hash(1), Optional.empty(), 0)).isEmpty();
    assertThat(index.subjectRefs(hash(1), Optional.of(object("a")), 0)).isEmpty();
    assertThat(spy.events()).isEmpty();
  }

  @Test
  void theLimitIsCappedAtOneThousandAndTheHashIsBoundAsBytes() {
    SqlSpy spy = new SqlSpy().recordingTypedBinds();
    SubjectHash hash = hash(0xabcdef);

    index(spy).subjectRefs(hash, Optional.empty(), Integer.MAX_VALUE);
    index(spy).subjectRefs(hash, Optional.empty(), 7);

    assertThat(statements(spy)).containsExactly(PgSubjectIndex.REFS_SQL, PgSubjectIndex.REFS_SQL);
    assertThat(binds(spy.events(), "bind bytes 1=")).containsExactly(hash.hex(), hash.hex());
    assertThat(binds(spy.events(), "bind long 2="))
        .containsExactly(String.valueOf(SubjectIndexLedger.MAX_REFS_PER_CALL), "7");
  }

  @Test
  void theReferenceAfterWhichToListIsBoundAsItsKindCodeAndItsText() {
    SqlSpy spy = new SqlSpy().recordingTypedBinds();

    index(spy).subjectRefs(hash(1), Optional.of(object("r/a.")), 5);
    index(spy).subjectRefs(hash(1), Optional.of(report("zzz")), 5);

    assertThat(statements(spy))
        .containsExactly(PgSubjectIndex.REFS_AFTER_SQL, PgSubjectIndex.REFS_AFTER_SQL);
    assertThat(binds(spy.events(), "bind short 2=")).containsExactly("2", "1");
    assertThat(binds(spy.events(), "bind 3=")).containsExactly("r/a.", "zzz");
    assertThat(binds(spy.events(), "bind long 4=")).containsExactly("5", "5");
    // the bound is a position, not a row that must exist: no statement looks for it
    assertThat(statements(spy)).doesNotContain(PgSubjectIndex.DELETE_SQL);
  }

  @Test
  void aStoredRowIsMappedToItsKindAndItsReference() {
    String smile = "r/" + Character.toString(0x1F600);
    SqlSpy answering =
        new SqlSpy()
            .answering(
                refsAnswer(
                    new Object[] {(short) 1, "LEI1|XPAR|E1"},
                    new Object[] {(short) 2, "sealed/ab/0123"},
                    new Object[] {(short) 2, smile}));

    List<SubjectRef> refs = index(answering).subjectRefs(hash(1), Optional.empty(), 10);

    assertThat(refs)
        .containsExactly(report("LEI1|XPAR|E1"), object("sealed/ab/0123"), object(smile));
    assertThat(index(answering).subjectRefs(hash(1), Optional.of(report("A")), 10))
        .containsExactlyElementsOf(refs);
  }

  @Test
  void aStoredRowThatIsNotAReferenceIsAMalformedRowAndNotAnArgumentError() {
    // an unknown or missing kind code, an empty text, a control character, a text over 512
    // characters: the adapter does not guess and does not echo the text
    List<Object[]> bad =
        List.of(
            new Object[] {(short) 0, "sealed/a"},
            new Object[] {(short) 3, "sealed/a"},
            new Object[] {(short) -1, "sealed/a"},
            new Object[] {(short) 2, ""},
            new Object[] {(short) 2, "a" + (char) 0x0a + "b"},
            new Object[] {(short) 2, "k".repeat(513)});
    for (int i = 0; i < bad.size(); i++) {
      SqlSpy answering = new SqlSpy().answering(refsAnswer(bad.get(i)));
      PgSubjectIndex index = index(answering);
      assertThatThrownBy(() -> index.subjectRefs(hash(1), Optional.empty(), 10))
          .as("the malformed stored row number %d", i)
          .isInstanceOf(LedgerException.class)
          .hasMessage("stored row is malformed");
    }
  }
}
