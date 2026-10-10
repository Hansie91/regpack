package dev.regpack.ports.evidence;

/** A retention change that is not an extension, such as moving the retain-until instant earlier. */
public class EvidenceRetentionException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public EvidenceRetentionException(String message) {
    super(message);
  }

  public EvidenceRetentionException(String message, Throwable cause) {
    super(message, cause);
  }
}
