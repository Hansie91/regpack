package dev.regpack.app;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.security.Entropy;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
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

/**
 * The production {@link Entropy} adapter. A test of randomness cannot prove quality; these tests
 * show what the adapter is responsible for: the bytes differ between draws, every byte of the array
 * is written, concurrent callers get different bytes, and the construction is the one the notes
 * state. The chance that a correct adapter fails one of them is below 2^-70 (the weakest bound is
 * the one of {@code overwritesWhatTheArrayHeldBefore}).
 */
class SecureEntropyTest {
  private static final HexFormat HEX = HexFormat.of();

  private final Entropy entropy = new SecureEntropy();

  @Test
  void twoDrawsDiffer() {
    byte[] first = new byte[32];
    byte[] second = new byte[32];

    entropy.fill(first);
    entropy.fill(second);

    assertThat(first).isNotEqualTo(second);
    assertThat(first).isNotEqualTo(new byte[32]);
    assertThat(second).isNotEqualTo(new byte[32]);
  }

  @Test
  void twoInstancesDrawDifferentBytes() {
    byte[] first = new byte[32];
    byte[] second = new byte[32];

    new SecureEntropy().fill(first);
    new SecureEntropy().fill(second);

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void fillsTheWholeArray() {
    // A position that the adapter never writes stays zero in every draw; a written position is zero
    // in all 32 draws with probability 2^-256.
    for (int length : new int[] {1, 2, 11, 12, 13, 31, 32, 33, 255, 256, 257, 4096}) {
      boolean[] touched = new boolean[length];
      for (int draw = 0; draw < 32; draw++) {
        byte[] out = new byte[length];
        entropy.fill(out);
        for (int i = 0; i < length; i++) {
          touched[i] |= out[i] != 0;
        }
      }
      for (int i = 0; i < length; i++) {
        assertThat(touched[i]).as("byte %d of an array of %d", i, length).isTrue();
      }
    }
  }

  @Test
  void overwritesWhatTheArrayHeldBefore() {
    byte[] out = new byte[64];
    Arrays.fill(out, (byte) 0x5a);
    byte[] before = out.clone();

    entropy.fill(out);

    int unchanged = 0;
    for (int i = 0; i < out.length; i++) {
      if (out[i] == before[i]) {
        unchanged++;
      }
    }
    // each byte is unchanged with probability 1/256; 16 or more of 64 has probability below 2^-70
    assertThat(unchanged).isLessThan(16);
  }

  @Test
  void anEmptyArrayIsFilledWithoutError() {
    entropy.fill(new byte[0]);
  }

  @Test
  void aNullArrayIsANullPointerException() {
    assertThatThrownBy(() -> entropy.fill(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void isSafeToCallFromEightThreads() throws Exception {
    int threads = 8;
    int drawsEach = 200;
    Set<String> seen = ConcurrentHashMap.newKeySet();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<Integer>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  int zeros = 0;
                  for (int i = 0; i < drawsEach; i++) {
                    byte[] out = new byte[32];
                    entropy.fill(out);
                    seen.add(HEX.formatHex(out));
                    if (Arrays.equals(out, new byte[32])) {
                      zeros++;
                    }
                  }
                  return zeros;
                }));
      }
      start.countDown();
      for (Future<Integer> future : futures) {
        assertThat(future.get(60, TimeUnit.SECONDS)).isZero();
      }
    } finally {
      pool.shutdownNow();
    }

    // 1,600 draws of 32 bytes: a repeat has probability below 2^-230, so every draw is distinct
    assertThat(seen).hasSize(threads * drawsEach);
  }

  @Test
  void holdsOneGeneratorForTheWholeProcessAndNothingElse() {
    List<Field> secureRandoms = new ArrayList<>();
    for (Field field : SecureEntropy.class.getDeclaredFields()) {
      if (field.getType() == SecureRandom.class) {
        secureRandoms.add(field);
      }
    }

    assertThat(secureRandoms).hasSize(1);
    int modifiers = secureRandoms.get(0).getModifiers();
    assertThat(Modifier.isStatic(modifiers)).isTrue();
    assertThat(Modifier.isFinal(modifiers)).isTrue();
    assertThat(Modifier.isPrivate(modifiers)).isTrue();
    // no instance state at all, so two instances cannot differ
    for (Field field : SecureEntropy.class.getDeclaredFields()) {
      if (!field.isSynthetic()) {
        assertThat(Modifier.isStatic(field.getModifiers())).as(field.getName()).isTrue();
      }
    }
  }

  @Test
  void neverSeedsAndNeverAsksForTheBlockingGenerator() throws IOException {
    // The names in the constant pool of the compiled class are the calls it can make; comments do
    // not reach it.
    String constants;
    try (InputStream in =
        SecureEntropy.class.getResourceAsStream(SecureEntropy.class.getSimpleName() + ".class")) {
      assertThat(in).isNotNull();
      constants = new String(in.readAllBytes(), ISO_8859_1);
    }

    assertThat(constants).contains("java/security/SecureRandom");
    assertThat(constants).doesNotContain("setSeed");
    assertThat(constants).doesNotContain("getInstanceStrong");
    assertThat(constants).doesNotContain("generateSeed");
  }
}
