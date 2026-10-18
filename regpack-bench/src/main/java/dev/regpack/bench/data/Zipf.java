package dev.regpack.bench.data;

/**
 * A Zipf distribution over the ranks {@code 0..n-1} with weight {@code 1 / (rank + 1)^s}. The
 * cumulative table is built with {@link StrictMath#pow}, so it is bit-identical everywhere.
 */
public final class Zipf {
  private final double[] cdf;

  public Zipf(int n, double s) {
    if (n < 1) {
      throw new IllegalArgumentException("n must be at least 1: " + n);
    }
    if (!(s >= 0.0) || Double.isInfinite(s)) {
      throw new IllegalArgumentException("s must be finite and not negative: " + s);
    }
    double[] table = new double[n];
    double total = 0.0;
    for (int i = 0; i < n; i++) {
      total += StrictMath.pow(i + 1.0, -s);
      table[i] = total;
    }
    for (int i = 0; i < n; i++) {
      table[i] /= total;
    }
    table[n - 1] = 1.0;
    this.cdf = table;
  }

  /** The rank drawn for the next uniform value of {@code rng}. */
  public int sample(SplitMix64 rng) {
    double u = rng.nextUnit();
    int low = 0;
    int high = cdf.length - 1;
    while (low < high) {
      int mid = (low + high) >>> 1;
      if (cdf[mid] > u) {
        high = mid;
      } else {
        low = mid + 1;
      }
    }
    return low;
  }

  double cdfAt(int rank) {
    return cdf[rank];
  }
}
