package dev.regpack.testkit.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The deterministic entropy of the tests: V5 and the rules the later pins rely on. */
class CounterEntropyTest {
  private static final HexFormat HEX = HexFormat.of();

  /**
   * Block i is SHA-256(seed, i as 8 big-endian bytes); written out here, not taken from the class.
   */
  private static byte[] block(String seed, long index) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(seed.getBytes(UTF_8));
      digest.update(ByteBuffer.allocate(8).putLong(index).array());
      return digest.digest();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void theFirst48BytesOfSeedRegpackTestArePinned() {
    CounterEntropy entropy = new CounterEntropy("regpack-test".getBytes(UTF_8));
    byte[] out = new byte[48];

    entropy.fill(out);

    assertThat(HEX.formatHex(out)) // V5
        .isEqualTo(
            "982676dcc36d4a110a6e64955bdfe6f855c0bd4c86cc9bef3d4552c8a30adf44"
                + "0f64ff0f62f482b237a8b913b186365c");
  }

  @Test
  void theStreamIsTheJoinedBlocksAndSuccessiveFillsContinueIt() {
    CounterEntropy entropy = new CounterEntropy("regpack-test".getBytes(UTF_8));
    byte[] first = new byte[32];
    byte[] second = new byte[12];
    byte[] third = new byte[40];

    entropy.fill(first);
    entropy.fill(second);
    entropy.fill(third);

    byte[] expected = new byte[32 + 12 + 40];
    for (int i = 0; i < 3; i++) {
      System.arraycopy(
          block("regpack-test", i), 0, expected, i * 32, Math.min(32, expected.length - i * 32));
    }
    byte[] actual = new byte[expected.length];
    System.arraycopy(first, 0, actual, 0, 32);
    System.arraycopy(second, 0, actual, 32, 12);
    System.arraycopy(third, 0, actual, 44, 40);
    assertThat(actual).isEqualTo(expected);
    // the data key is bytes 0 to 31 and the nonce bytes 32 to 43 (V6 relies on it)
    assertThat(first).isEqualTo(block("regpack-test", 0));
    assertThat(second).isEqualTo(Arrays.copyOf(block("regpack-test", 1), 12));
  }

  @Test
  void twoInstancesWithOneSeedAgree() {
    CounterEntropy one = new CounterEntropy("seed-a".getBytes(UTF_8));
    CounterEntropy two = new CounterEntropy("seed-a".getBytes(UTF_8));
    CounterEntropy other = new CounterEntropy("seed-b".getBytes(UTF_8));
    byte[] a = new byte[100];
    byte[] b = new byte[100];
    byte[] c = new byte[100];

    one.fill(a);
    two.fill(b);
    other.fill(c);

    assertThat(a).isEqualTo(b);
    assertThat(a).isNotEqualTo(c);
  }

  @Test
  void theSeedIsCopiedAndAnEmptyFillTakesNothing() {
    byte[] seed = "regpack-test".getBytes(UTF_8);
    CounterEntropy entropy = new CounterEntropy(seed);
    seed[0] ^= 1;

    entropy.fill(new byte[0]);
    byte[] out = new byte[32];
    entropy.fill(out);

    assertThat(out).isEqualTo(block("regpack-test", 0));
    assertThatThrownBy(() -> entropy.fill(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new CounterEntropy(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void threadsNeverReceiveTheSameBlock() throws Exception {
    int threads = 8;
    int blocksEach = 100;
    CounterEntropy entropy = new CounterEntropy("regpack-test".getBytes(UTF_8));
    Set<String> seen = ConcurrentHashMap.newKeySet();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  for (int i = 0; i < blocksEach; i++) {
                    byte[] out = new byte[32];
                    entropy.fill(out);
                    seen.add(HEX.formatHex(out));
                  }
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    // 800 distinct blocks, and they are exactly the first 800 blocks of the stream
    assertThat(seen).hasSize(threads * blocksEach);
    Set<String> expected = new HashSet<>();
    for (int i = 0; i < threads * blocksEach; i++) {
      expected.add(HEX.formatHex(block("regpack-test", i)));
    }
    assertThat(seen).isEqualTo(expected);
  }
}
