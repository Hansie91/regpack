package dev.regpack.bench.data;

/**
 * SplitMix64 (Vigna): a 64-bit state that advances by a fixed odd increment, finished by a bit
 * mixer. Pure integer arithmetic, so the sequence is identical on every JVM and operating system.
 * Not thread-safe; one instance per thread or per stream.
 */
public final class SplitMix64 {
  private static final long GAMMA = 0x9E3779B97F4A7C15L;
  private static final double UNIT = 0x1.0p-53;

  private long state;

  public SplitMix64(long seed) {
    this.state = seed;
  }

  private static long mix(long value) {
    long z = value;
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  public long nextLong() {
    state += GAMMA;
    return mix(state);
  }

  /** A uniform value in {@code [0, bound)}, unbiased by rejection. */
  public int nextInt(int bound) {
    if (bound <= 0) {
      throw new IllegalArgumentException("bound must be positive: " + bound);
    }
    return (int) nextLong(bound);
  }

  /** A uniform value in {@code [0, bound)}, unbiased by rejection. */
  public long nextLong(long bound) {
    if (bound <= 0) {
      throw new IllegalArgumentException("bound must be positive: " + bound);
    }
    long mask = bound - 1;
    long r = nextLong() >>> 1;
    if ((bound & mask) == 0) {
      return r & mask;
    }
    // Reject the draws of the incomplete last block: u - r + mask overflows into the sign bit.
    for (long u = r; u - (r = u % bound) + mask < 0; u = nextLong() >>> 1) {
      // draw again
    }
    return r;
  }

  /** A double on the exact grid of multiples of 2^-53 in {@code [0, 1)}. */
  public double nextUnit() {
    return (nextLong() >>> 11) * UNIT;
  }

  /** True with probability {@code numerator / denominator}, exactly. */
  public boolean chance(long numerator, long denominator) {
    if (denominator <= 0 || numerator < 0 || numerator > denominator) {
      throw new IllegalArgumentException(
          "need 0 <= numerator <= denominator and denominator > 0: "
              + numerator
              + "/"
              + denominator);
    }
    return nextLong(denominator) < numerator;
  }

  /** An independent stream derived from the current state; this generator is not advanced. */
  public SplitMix64 fork(long streamId) {
    if (streamId == 0) {
      throw new IllegalArgumentException("streamId must not be 0");
    }
    return new SplitMix64(mix(state + streamId * GAMMA));
  }
}
