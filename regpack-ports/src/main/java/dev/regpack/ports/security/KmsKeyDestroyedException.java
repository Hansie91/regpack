package dev.regpack.ports.security;

/**
 * The key was destroyed on purpose (crypto-shredding), as opposed to never having existed. It is
 * not an {@link IllegalArgumentException}: an unknown key stays one, so a caller can tell shredded
 * data from a wrong key id. The message is fixed and names no key.
 */
public class KmsKeyDestroyedException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public KmsKeyDestroyedException() {
    super("the key has been destroyed");
  }
}
