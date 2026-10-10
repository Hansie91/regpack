package dev.regpack.ports.security;

/** No secret exists for a reference. The message names the reference, never a value. */
public class SecretNotFoundException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public SecretNotFoundException(String message) {
    super(message);
  }
}
