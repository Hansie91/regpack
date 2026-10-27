package dev.regpack.ports.ledger;

import dev.regpack.model.KeyText;
import dev.regpack.ports.crypto.SubjectHash;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The ledger's data-subject index (spec 11.6; P3c1 A9, D4 and D-K5): rows of (keyed hash of an
 * identifier, reference of an artifact that names the person). The ledger holds the keyed hash and
 * the reference only, never an identifier (see {@code SubjectHasher}), and nothing is encrypted at
 * rest. An access request computes the hash of the identifier and lists the references; the job
 * that ends a retention period deletes the rows of an artifact with {@link #removeSubjectRefs}.
 * Nothing else deletes a row.
 *
 * <p>Order. References are ordered by (kind, text): the kind by its code (REPORT_VERSION before
 * EVIDENCE_OBJECT), the text by UTF-16 code unit, which for the alphabet of an evidence key is byte
 * order. An adapter must not use a locale or collation order: on PostgreSQL the column is {@code
 * COLLATE "C"} (D6 of the V003 document).
 */
public interface SubjectIndexLedger {
  /** The most references that one {@link #subjectRefs} call returns. */
  int MAX_REFS_PER_CALL = 1_000;

  /** What a reference names. Codes 1 and 2 in this order. */
  enum RefKind {
    /** A report version (the reference is its business reference in the ledger). */
    REPORT_VERSION,
    /** An evidence object (the reference is its evidence key). */
    EVIDENCE_OBJECT
  }

  /**
   * A reference to an artifact. {@code ref} is 1 to 512 characters under the rules of {@link
   * KeyText}: no control character and no unpaired surrogate.
   *
   * @throws IllegalArgumentException when {@code ref} is outside these rules; the message names the
   *     rule and holds no value
   */
  record SubjectRef(RefKind kind, String ref) {
    /** The longest reference. */
    public static final int MAX_REF_CHARS = 512;

    public SubjectRef {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(ref, "ref");
      if (ref.isEmpty()) {
        throw new IllegalArgumentException("ref must be at least 1 character");
      }
      KeyText.require("ref", ref, MAX_REF_CHARS);
    }
  }

  /** One index row: the keyed hash of an identifier and a reference that names it. */
  record SubjectEntry(SubjectHash hash, SubjectRef ref) {
    public SubjectEntry {
      Objects.requireNonNull(hash, "hash");
      Objects.requireNonNull(ref, "ref");
    }
  }

  /**
   * Adds the entries in one unit, all or nothing. An entry that is already stored, or that appears
   * twice in the list, is one row and not an error. An empty list does nothing.
   *
   * @throws NullPointerException when the list or an element is null; nothing is stored
   */
  void indexSubjects(List<SubjectEntry> entries);

  /**
   * The references of {@code hash} that sort strictly after {@code after} in the order (kind,
   * text), at most {@code min(limit, MAX_REFS_PER_CALL)}. {@code after} need not exist. An unknown
   * hash has no reference.
   *
   * @throws IllegalArgumentException when {@code limit} is negative
   */
  List<SubjectRef> subjectRefs(SubjectHash hash, Optional<SubjectRef> after, int limit);

  /**
   * Deletes the rows of {@code ref}, for every hash that names it, and returns how many rows it
   * deleted (0 for an unknown reference, which is not an error). Rows of other references of the
   * same hash, and the same text under the other kind, stay.
   */
  int removeSubjectRefs(SubjectRef ref);
}
