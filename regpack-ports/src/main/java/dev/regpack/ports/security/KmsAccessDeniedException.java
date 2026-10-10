package dev.regpack.ports.security;

/**
 * The bound identity may not use an existing key, or the key is used for another purpose than the
 * one requested.
 */
public class KmsAccessDeniedException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public KmsAccessDeniedException(String message) {
    super(message);
  }
}
