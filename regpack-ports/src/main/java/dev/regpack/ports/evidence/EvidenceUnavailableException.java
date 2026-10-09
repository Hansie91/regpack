package dev.regpack.ports.evidence;

/** The store is unavailable (outage); the call failed closed and stored nothing. */
public class EvidenceUnavailableException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public EvidenceUnavailableException(String message) {
    super(message);
  }

  public EvidenceUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
