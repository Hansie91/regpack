package dev.regpack.bench.eval;

/** The three-valued verdict of a criterion, plus the case where nothing was measured. */
public enum Verdict {
  /** The evidence clears the threshold by more than the noise margin. */
  PASS,
  /** The evidence is below the threshold by more than the noise margin. */
  FAIL,
  /** The evidence cannot tell: too few runs, a marginal result or a noisy runner. */
  INCONCLUSIVE,
  /** Nothing was measured for this criterion. */
  NOT_RUN
}
