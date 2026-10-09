package dev.regpack.ports.ledger;

/** Thrown when a key that must be unique (a delivery key or an attempt id) is already stored. */
public class DuplicateKeyException extends LedgerException {
  private static final long serialVersionUID = 1L;

  public DuplicateKeyException(String message) {
    super(message);
  }
}
