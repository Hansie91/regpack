package dev.regpack.ports.crypto;

/**
 * The key service could not serve a request: it was unreachable or failed, refused the caller, on
 * seal did not know the KEK (on open an unknown KEK is reported as {@link
 * Envelope.AuthenticationException}), or answered with something unusable. The ciphers and the
 * hasher fail closed with this type instead of letting a KMS failure escape. A failure of the
 * Entropy or the Clock is not mapped to this type.
 *
 * <p>The message is fixed. The exception has no cause and records no suppressed exception, so
 * nothing the key service said, no key id, no path and no identifier can reach a log through it.
 */
public final class KeyUnavailableException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public KeyUnavailableException() {
    super("the key service could not serve this request", null, false, true);
  }
}
