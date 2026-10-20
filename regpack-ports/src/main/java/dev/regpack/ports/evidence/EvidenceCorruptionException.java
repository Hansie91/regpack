package dev.regpack.ports.evidence;

/**
 * A damaged stored copy was found (P-SEALED: a copy that fails its own seal, or two copies that
 * differ in their payload though both are self-consistent; the latter is what {@code scrub} reports
 * as DIVERGENT). It is an integrity alarm of its own kind: {@code JournalWriter} never absorbs it
 * as "already present", so a re-write over a one-copy corruption or a divergent pair is not
 * acknowledged. A plain {@link EvidenceIntegrityException} is only "same key, other bytes".
 */
public class EvidenceCorruptionException extends EvidenceIntegrityException {
  private static final long serialVersionUID = 1L;

  public EvidenceCorruptionException(String message) {
    super(message);
  }

  public EvidenceCorruptionException(String message, Throwable cause) {
    super(message, cause);
  }
}
