package dev.regpack.bench.proto;

import java.time.Duration;
import java.util.Objects;

/**
 * The fixed measurement protocol (rules P-1 to P-6 of the P1a plan). One repetition is one fresh
 * JVM; a fork warms up in windows until three consecutive windows agree, then measures fixed
 * windows; its statistic is the median of the window rates.
 *
 * @param forks number of forks, run one after another (P-1)
 * @param warmupMax longest warm-up; a fork still unstable then is flagged and kept (P-2)
 * @param window length of one warm-up or measurement window
 * @param stableWindows consecutive windows that must agree to end the warm-up
 * @param stabilityTolerance largest relative range {@code (max - min) / min} of those windows
 * @param measureWindows windows measured after the warm-up (P-3)
 * @param canary length of one canary probe; zero switches the probes off (P-4)
 * @param maxStealFraction CPU steal above which a fork is discarded (P-5)
 * @param maxDiscards more discards than this make the series unusable (P-5)
 * @param minForksForVerdict fewer kept forks than this can never give a verdict (P-6)
 */
public record Protocol(
    int forks,
    Duration warmupMax,
    Duration window,
    int stableWindows,
    double stabilityTolerance,
    int measureWindows,
    Duration canary,
    double maxStealFraction,
    int maxDiscards,
    int minForksForVerdict) {

  public Protocol {
    Objects.requireNonNull(warmupMax, "warmupMax");
    Objects.requireNonNull(window, "window");
    Objects.requireNonNull(canary, "canary");
    if (forks < 1) {
      throw new IllegalArgumentException("forks must be at least 1: " + forks);
    }
    if (window.isNegative() || window.isZero()) {
      throw new IllegalArgumentException("window must be positive: " + window);
    }
    if (warmupMax.compareTo(window) < 0) {
      throw new IllegalArgumentException("warmupMax must cover one window: " + warmupMax);
    }
    if (stableWindows < 2) {
      throw new IllegalArgumentException("stableWindows must be at least 2: " + stableWindows);
    }
    if (!(stabilityTolerance >= 0.0 && stabilityTolerance < 1.0)) {
      throw new IllegalArgumentException(
          "stabilityTolerance must be in [0, 1): " + stabilityTolerance);
    }
    if (measureWindows < 1) {
      throw new IllegalArgumentException("measureWindows must be at least 1: " + measureWindows);
    }
    if (canary.isNegative()) {
      throw new IllegalArgumentException("canary must not be negative: " + canary);
    }
    if (!(maxStealFraction >= 0.0 && maxStealFraction <= 1.0)) {
      throw new IllegalArgumentException("maxStealFraction must be in [0, 1]: " + maxStealFraction);
    }
    if (maxDiscards < 0) {
      throw new IllegalArgumentException("maxDiscards must not be negative: " + maxDiscards);
    }
    if (minForksForVerdict < 1) {
      throw new IllegalArgumentException(
          "minForksForVerdict must be at least 1: " + minForksForVerdict);
    }
  }

  /** 15 forks, 60 s warm-up at most, 2 s windows, 3 stable at 3%, 15 windows, 1 s canary. */
  public static Protocol full() {
    return new Protocol(
        15,
        Duration.ofSeconds(60),
        Duration.ofSeconds(2),
        3,
        0.03,
        15,
        Duration.ofSeconds(1),
        0.05,
        3,
        7);
  }

  /** As {@link #full()} with 7 forks: enough for a verdict, not for a gate criterion (P-6). */
  public static Protocol informative() {
    return new Protocol(
        7,
        Duration.ofSeconds(60),
        Duration.ofSeconds(2),
        3,
        0.03,
        15,
        Duration.ofSeconds(1),
        0.05,
        3,
        7);
  }

  /** Two forks with tiny windows: it checks the wiring and can only ever yield INCONCLUSIVE. */
  public static Protocol smoke() {
    return new Protocol(
        2,
        Duration.ofMillis(300),
        Duration.ofMillis(50),
        3,
        0.03,
        3,
        Duration.ofMillis(50),
        0.05,
        3,
        7);
  }
}
