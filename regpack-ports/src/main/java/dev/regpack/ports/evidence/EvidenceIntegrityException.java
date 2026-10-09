package dev.regpack.ports.evidence;

/**
 * A put of an existing key with different bytes: an integrity alarm; the stored object is
 * unchanged.
 */
public class EvidenceIntegrityException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public EvidenceIntegrityException(String message) {
    super(message);
  }

  public EvidenceIntegrityException(String message, Throwable cause) {
    super(message, cause);
  }
}
