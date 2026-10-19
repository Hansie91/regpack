package dev.regpack.ports.evidence;

/**
 * A damaged stored copy was found (P-SEALED: a copy that fails its own seal, or copies that differ
 * though both are self-consistent). It is an integrity alarm of its own kind: {@code JournalWriter}
 * never absorbs it as "already present", so a re-write over a one-copy corruption is not
 * acknowledged.
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
