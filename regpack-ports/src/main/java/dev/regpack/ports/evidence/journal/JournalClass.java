package dev.regpack.ports.evidence.journal;

/**
 * The two classes of journal object (spec 11.2): class (a) objects gate an external effect and are
 * written before the PostgreSQL commit that depends on them; class (b) objects are written after
 * the fact and shipped asynchronously.
 */
public enum JournalClass {
  A,
  B
}
