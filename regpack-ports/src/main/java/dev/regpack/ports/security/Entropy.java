package dev.regpack.ports.security;

/**
 * The only source of randomness for main code. The ports and evidence modules forbid {@code
 * SecureRandom} and {@code Random} (determinism rules), so key and nonce material is drawn here:
 * production wires a cryptographically secure adapter, tests wire a counter-based one.
 *
 * <p>Implementations are thread-safe.
 */
public interface Entropy {
  /** Overwrites every byte of {@code out} with fresh random bytes. */
  void fill(byte[] out);
}
