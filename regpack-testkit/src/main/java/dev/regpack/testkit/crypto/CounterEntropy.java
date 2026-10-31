package dev.regpack.testkit.crypto;

import dev.regpack.ports.security.Entropy;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Deterministic {@link Entropy} for tests. The stream is the blocks {@code SHA-256(seed, i)}
 * joined, with {@code i} as 8 big-endian bytes, for i = 0, 1, 2, and so on; {@code fill} takes the
 * next bytes of that stream, a fill that ends inside a block continues there on the next call. Two
 * instances with one seed agree; the class is thread-safe and gives no byte twice. Never for
 * production keys.
 */
public final class CounterEntropy implements Entropy {
  private final byte[] seed;
  private long nextBlock;
  private byte[] block = new byte[0];
  private int used;

  public CounterEntropy(byte[] seed) {
    this.seed = Objects.requireNonNull(seed, "seed").clone();
  }

  @Override
  public synchronized void fill(byte[] out) {
    Objects.requireNonNull(out, "out");
    for (int i = 0; i < out.length; i++) {
      if (used == block.length) {
        block = digestOfNextBlock();
        used = 0;
      }
      out[i] = block[used++];
    }
  }

  private byte[] digestOfNextBlock() {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(seed);
      digest.update(ByteBuffer.allocate(Long.BYTES).putLong(nextBlock).array());
      nextBlock++;
      return digest.digest();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
