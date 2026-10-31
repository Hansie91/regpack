package dev.regpack.ports.crypto;

/**
 * The key that protects the data was destroyed on purpose (crypto-shredding): the data cannot be
 * decrypted any more, and that is neither a corruption nor an outage.
 *
 * <p>The message is fixed. The exception has no cause and records no suppressed exception.
 *
 * <p>The cipher only knows that the KMS reported the KEK as destroyed; it cannot tell destruction
 * at expiry from a header rewritten to name a destroyed KEK.
 */
public final class DataShreddedException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public DataShreddedException() {
    super("the key that protects this data was destroyed", null, false, true);
  }
}
