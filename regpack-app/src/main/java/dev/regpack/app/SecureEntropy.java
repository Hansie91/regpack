package dev.regpack.app;

import dev.regpack.ports.security.Entropy;
import java.security.SecureRandom;
import java.util.Objects;

/**
 * The production {@link Entropy}: bytes from the JDK default {@link SecureRandom}. Key and nonce
 * material is drawn through the port; this adapter is the one place of production code meant to
 * build the generator, and it is wired in from the application module.
 *
 * <p>One generator is built for the whole process, in the class initialiser, and shared by every
 * instance; the class holds no other state. It does not seed the generator by hand and does not ask
 * for the blocking strong instance, so where the bytes come from and what a draw costs is the
 * platform provider's business ({@code
 * SecureEntropyTest#neverSeedsAndNeverAsksForTheBlockingGenerator} and {@code
 * #holdsOneGeneratorForTheWholeProcessAndNothingElse} show the construction). {@code SecureRandom}
 * is documented as safe for concurrent use and {@code
 * SecureEntropyTest#isSafeToCallFromEightThreads} draws from eight threads. Nothing here shows the
 * statistical quality of the bytes.
 */
public final class SecureEntropy implements Entropy {
  private static final SecureRandom RANDOM = new SecureRandom();

  @Override
  public void fill(byte[] out) {
    Objects.requireNonNull(out, "out");
    RANDOM.nextBytes(out);
  }
}
