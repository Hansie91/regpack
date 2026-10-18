package dev.regpack.bench.eval;

/** How a measured value is compared with the threshold of a criterion. */
public enum Direction {
  AT_LEAST,
  AT_MOST,
  /** Recorded, never compared. */
  MEASURED_ONLY
}
