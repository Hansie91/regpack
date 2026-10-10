package dev.regpack.ports.ledger;

/** Base class of the unchecked exceptions that a {@link LedgerStore} throws. */
public class LedgerException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public LedgerException(String message) {
    super(message);
  }

  public LedgerException(String message, Throwable cause) {
    super(message, cause);
  }
}
