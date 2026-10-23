package dev.regpack.ports.ledger;

/**
 * Thrown when an object key is recorded again with another SHA-256 than the one stored for it. The
 * message names the key path only (Convention 8): never a hash and never a payload. Nothing of the
 * call that threw it is stored.
 */
public class ManifestConflictException extends LedgerException {
  private static final long serialVersionUID = 1L;

  public ManifestConflictException(String message) {
    super(message);
  }
}
