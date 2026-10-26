package dev.regpack.ports.crypto;

/**
 * The key that protects the data was destroyed on purpose (crypto-shredding): the data cannot be
 * decrypted any more, and that is neither a corruption nor an outage.
 *
 * <p>The message is fixed. The exception has no cause and records no suppressed exception.
 */
public final class DataShreddedException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public DataShreddedException() {
    super("the key that protects this data was destroyed", null, false, true);
  }
}
