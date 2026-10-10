package dev.regpack.bench.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Conversions of doubles to exact decimals, because result files hold no {@code double}. */
public final class Num {
  private Num() {}

  /** A finite double as a decimal of the given scale, rounded half-even. */
  public static BigDecimal dec(double value, int scale) {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException("not a finite number: " + value);
    }
    return new BigDecimal(Double.toString(value)).setScale(scale, RoundingMode.HALF_EVEN);
  }
}
