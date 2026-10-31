package dev.regpack.postgres;

import dev.regpack.ports.Clock;
import dev.regpack.ports.crypto.SubjectHash;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.SubjectIndexLedger;
import dev.regpack.ports.ledger.SubjectIndexLedger.SubjectEntry;
import dev.regpack.ports.ledger.SubjectIndexLedger.SubjectRef;
import dev.regpack.postgres.PgLedgerStore.Config;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The data-subject index on PostgreSQL (spec 11.6; migration V003, D4 and D6 of its document; P3c1
 * D-K5 and D-K8): rows of (keyed hash of an identifier, kind of reference, reference), added
 * idempotently, listed by hash in the order (kind, reference) and deleted by reference. The table
 * holds the 32 bytes of the HMAC-SHA-256 and the reference, nothing else of the person, and nothing
 * is encrypted at rest; no value reaches a message (Convention 12), and a hash is bound as bytes
 * and never as text.
 *
 * <p><b>Writing.</b> {@link #indexSubjects} inserts the entries with {@code ON CONFLICT ON
 * CONSTRAINT subject_index_pk DO NOTHING} in one batch of one unit (all or nothing; the constraint
 * is the arbiter, so a schema without it fails with a missing-arbiter error and not with a second
 * row: the schema mutant {@code NoSubjectIndexKeyPgLedgerContractIT}). The entries are sorted by
 * the primary key, (hash, kind, reference) with the reference in code point order, and a repeat
 * inside the call is sent once. An insert that meets a row that another unit has inserted and not
 * yet committed waits for that unit, so two units that take overlapping rows in opposite orders
 * could wait for each other in a circle; one order for every caller rules that out [R: the waiting
 * of an {@code ON CONFLICT DO NOTHING} insert on an uncommitted conflicting row].
 *
 * <p><b>A call that writes nothing still waits for the standby (I1, D-K8, KF22).</b> A call whose
 * entries are all stored already inserts no row, and a {@code DELETE} of a reference that has no
 * row deletes none: such a unit has no transaction id and no WAL, so its commit would return
 * without waiting for the synchronous standby. If it is the retry of a call whose commit was
 * reported as "not confirmed", the rows may exist on the primary only, and the success of the retry
 * would say that the index is durable (the destruction job deletes the index rows after it has
 * destroyed a key, and takes "none left" for done). So a call that inserted or deleted no row ends
 * with {@link Unit#durabilityBarrier}. As in {@link PgManifest}, a batch whose counts say nothing
 * (the driver's {@code SUCCESS_NO_INFO}) counts as inserting nothing: a barrier that was not needed
 * costs one lock, one that was missing costs an acknowledgement.
 *
 * <p><b>Reading and the order.</b> {@link #subjectRefs} is a range on the primary key: the rows of
 * one hash, ordered by {@code ref_kind, ref}, strictly after a position by the row comparison
 * {@code (ref_kind, ref) > (?, ? COLLATE "C")}. The order is that of the column {@code ref}, which
 * is {@code COLLATE "C"} in the script: in a UTF8 database the byte order of the UTF-8 text, which
 * is the order of the code points and the order of the in-memory ledger. The {@code ORDER BY} names
 * the column and applies no cast or function to it, so the collation of the column governs the
 * order. The bound reference of the row comparison carries the same collation explicitly: the
 * explicit clause and the column agree, an explicit collation wins over an implicit one and cannot
 * conflict with it, and so the result does not depend on how the server pairs the collations of the
 * two sides of a row comparison (deviation D30 from the statement shape of the plan). CP-1 still
 * shows byte order, through the contract tests on a cluster whose default collation is {@code
 * en_US.utf8}. {@code SubjectRef} is not restricted to the evidence key alphabet, so the order
 * beyond that alphabet is the whole point of the choice: UTF-16 order and byte order differ for a
 * supplementary character against U+E000 to U+FFFF. A cluster that is not UTF8 would not give this
 * order and is a defect of the deployment ({@code PgConstraintsIT} asks the catalogue).
 *
 * <p><b>The hash in a lookup.</b> The adapter compares no hash in Java: the server finds the rows
 * of a hash by value through the primary key. A stored hash is a pseudonym that a holder of the
 * {@code INDEX_HMAC} key and a candidate identifier can compute, not a secret that this comparison
 * protects, so the rule of Convention 3 for the check of a MAC (a comparison in constant time,
 * {@code MessageDigest.isEqual}) is not what decides here; {@code SubjectHash} itself compares with
 * it.
 *
 * <p><b>The cause of an exception.</b> Convention 12 keeps values out of the message of the ledger
 * exception only. The cause is the driver's exception, and for a failed batch its message holds the
 * statement with every bound value of the failing entry, so it holds the hash and the reference in
 * clear ({@code PgErrors#wrap}, ADAPTER-5). A caller never logs the exception with its causes; the
 * log-scrubber test of P9a must feed it a {@code BatchUpdateException} from {@code indexSubjects}.
 */
final class PgSubjectIndex {
  /** Parameters: the hash (32 bytes), the kind code, the reference, recorded at. */
  static final String INSERT_SQL =
      "INSERT INTO subject_index (subject_hash, ref_kind, ref, entity_id, recorded_at)"
          + " VALUES (?, ?, ?, 1, ?) ON CONFLICT ON CONSTRAINT subject_index_pk DO NOTHING";

  /** Parameters: the hash, the limit. */
  static final String REFS_SQL =
      "SELECT ref_kind, ref FROM subject_index WHERE subject_hash = ?"
          + " ORDER BY ref_kind, ref LIMIT ?";

  /** Parameters: the hash, the kind code and the reference after which to list, the limit. */
  static final String REFS_AFTER_SQL =
      "SELECT ref_kind, ref FROM subject_index WHERE subject_hash = ?"
          + " AND (ref_kind, ref) > (?, ? COLLATE \"C\") ORDER BY ref_kind, ref LIMIT ?";

  /** Parameters: the kind code, the reference. */
  static final String DELETE_SQL = "DELETE FROM subject_index WHERE ref_kind = ? AND ref = ?";

  /**
   * The order of the primary key: the hash (lower-case hex of one length is in byte order), the
   * kind by its code (the declaration order of the enum, see {@link Codes}), the reference by code
   * point.
   */
  private static final Comparator<SubjectEntry> KEY_ORDER =
      Comparator.<SubjectEntry, String>comparing(entry -> entry.hash().hex())
          .thenComparingInt(entry -> Codes.of(entry.ref().kind()))
          .thenComparing(entry -> entry.ref().ref(), PgSubjectIndex::compareCodePoints);

  /** One entry as it is bound: nothing here can fail inside the unit. */
  private record Row(byte[] hash, short kind, String ref) {}

  private final DataSource dataSource;
  private final Clock clock;
  private final Config config;

  PgSubjectIndex(DataSource dataSource, Clock clock, Config config) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.config = Objects.requireNonNull(config, "config");
  }

  // ---------------------------------------------------------------------------------------------
  // the port

  void indexSubjects(List<SubjectEntry> entries) {
    // List.copyOf refuses a null list or element before anything is sent
    List<SubjectEntry> copy = List.copyOf(Objects.requireNonNull(entries, "entries"));
    if (copy.isEmpty()) {
      return;
    }
    List<SubjectEntry> inKeyOrder = new ArrayList<>(copy);
    inKeyOrder.sort(KEY_ORDER);
    List<Row> rows = new ArrayList<>();
    SubjectEntry previous = null;
    for (SubjectEntry entry : inKeyOrder) {
      if (previous == null || KEY_ORDER.compare(previous, entry) != 0) {
        rows.add(
            new Row(
                HexFormat.of().parseHex(entry.hash().hex()),
                Codes.of(entry.ref().kind()),
                entry.ref().ref()));
      }
      previous = entry;
    }
    OffsetDateTime recordedAt = PgTime.timestamp(clock.now());
    Unit.inUnit(
        dataSource,
        config,
        connection -> {
          boolean inserted;
          try (PreparedStatement insert = connection.prepareStatement(INSERT_SQL)) {
            for (Row row : rows) {
              insert.setBytes(1, row.hash());
              insert.setShort(2, row.kind());
              insert.setString(3, row.ref());
              insert.setObject(4, recordedAt);
              insert.addBatch();
            }
            inserted = PgManifest.anyRowInserted(insert.executeBatch());
          }
          if (!inserted) {
            // every entry was stored already: this unit has written nothing (see the class
            // comment), and the caller may take success to mean that the index is durable
            Unit.durabilityBarrier(connection);
          }
          return Boolean.TRUE;
        });
  }

  List<SubjectRef> subjectRefs(SubjectHash hash, Optional<SubjectRef> after, int limit) {
    Objects.requireNonNull(hash, "hash");
    Objects.requireNonNull(after, "after");
    if (limit < 0) {
      throw new IllegalArgumentException("limit must not be negative");
    }
    int max = Math.min(limit, SubjectIndexLedger.MAX_REFS_PER_CALL);
    if (max == 0) {
      return List.of();
    }
    byte[] hashBytes = HexFormat.of().parseHex(hash.hex());
    return Unit.inUnit(
        dataSource,
        config,
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(after.isPresent() ? REFS_AFTER_SQL : REFS_SQL)) {
            int next = 1;
            statement.setBytes(next++, hashBytes);
            if (after.isPresent()) {
              statement.setShort(next++, Codes.of(after.get().kind()));
              statement.setString(next++, after.get().ref());
            }
            statement.setLong(next, max);
            try (ResultSet rows = statement.executeQuery()) {
              List<SubjectRef> found = new ArrayList<>();
              while (rows.next()) {
                found.add(read(rows));
              }
              return List.copyOf(found);
            }
          }
        });
  }

  int removeSubjectRefs(SubjectRef ref) {
    Objects.requireNonNull(ref, "ref");
    short kind = Codes.of(ref.kind());
    return Unit.inUnit(
        dataSource,
        config,
        connection -> {
          int removed;
          try (PreparedStatement delete = connection.prepareStatement(DELETE_SQL)) {
            delete.setShort(1, kind);
            delete.setString(2, ref.ref());
            removed = delete.executeUpdate();
          }
          if (removed == 0) {
            // an unknown reference, or one removed by an earlier attempt whose commit was not
            // confirmed: the unit has written nothing (see the class comment)
            Unit.durabilityBarrier(connection);
          }
          return removed;
        });
  }

  // ---------------------------------------------------------------------------------------------
  // internals

  /**
   * Compares two texts by Unicode code point. A text of the port has no unpaired surrogate (the
   * rules of {@code KeyText}), so this is the order of the UTF-8 bytes and of {@code COLLATE "C"}
   * in a UTF8 database.
   *
   * <p>The same rule as {@code InMemoryLedgerStore#compareCodePoints}, by another algorithm; each
   * is tested against hand-written literal orders, and nothing else ties them.
   */
  private static int compareCodePoints(String a, String b) {
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      int x = a.codePointAt(i);
      int y = b.codePointAt(j);
      if (x != y) {
        return Integer.compare(x, y);
      }
      i += Character.charCount(x);
      j += Character.charCount(y);
    }
    return Integer.compare(a.length() - i, b.length() - j);
  }

  private static SubjectRef read(ResultSet rows) throws SQLException {
    try {
      return new SubjectRef(Codes.refKind(rows.getShort(1)), rows.getString(2));
    } catch (IllegalArgumentException e) {
      throw new LedgerException("stored subject index row is malformed", e);
    }
  }
}
