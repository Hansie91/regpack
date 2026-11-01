package dev.regpack.flow.ingest;

/**
 * What became of a source file. The connector moves an acknowledged file and its marker to the
 * folder of its verdict.
 */
public enum Verdict {
  /** The file was ingested (a record of it may still have been quarantined). */
  PROCESSED,
  /** The file as a whole was refused. */
  REJECTED,
  /** The file was landed and held back as a whole (a control total did not hold). */
  QUARANTINED
}
