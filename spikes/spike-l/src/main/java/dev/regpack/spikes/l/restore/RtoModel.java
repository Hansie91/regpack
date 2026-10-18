package dev.regpack.spikes.l.restore;

import dev.regpack.bench.stats.SlopeEstimate;
import dev.regpack.bench.stats.Stats;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * The restore time at a size that the 14 GB runner cannot hold (10M and 100M keys), projected from
 * the sizes that it can. The part of a restore that grows with the size (copy, sync, analyze,
 * verify) is projected with a Theil-Sen line through at least three measured points and raised to a
 * physical floor (the time to read and write the cluster once each); the part that depends on the
 * backup interval, the replay of the gap, is modelled apart and added last, because it does not
 * scale with the number of keys. The result is EXTRAPOLATED and needs the owner's acknowledgement
 * (spec 16).
 */
public final class RtoModel {
  private static final int MIN_POINTS = 3;
  private static final int SCALE = Stats.SCALE;
  private static final BigDecimal NANOS_PER_SECOND = BigDecimal.valueOf(1_000_000_000L);
  private static final BigDecimal HOURS_PER_DAY = BigDecimal.valueOf(24);

  private RtoModel() {}

  /**
   * A projection in seconds: the value of the fitted line at the target and the values at the two
   * bounds of the slope, each raised to the floor and then increased by the replay term.
   *
   * @param point the median line
   * @param lower the smaller of the bounds
   * @param upper the larger of the bounds
   */
  public record Bounds(BigDecimal point, BigDecimal lower, BigDecimal upper) {}

  /**
   * Projects to {@code target} keys.
   *
   * @param keys the measured sizes
   * @param seconds the measured times at those sizes, without the replay of the gap ({@code
   *     rtoNanos() - replayNanos}, in seconds)
   * @param target the size to project to
   * @param floorSeconds the physical floor in seconds ({@link #floorSeconds}), applied to every
   *     bound before the replay is added
   * @param replaySeconds the replay term ({@link #replaySeconds}), added to every bound
   * @throws IllegalArgumentException for fewer than three points or series of different length
   */
  public static Bounds project(
      List<BigDecimal> keys,
      List<BigDecimal> seconds,
      BigDecimal target,
      BigDecimal floorSeconds,
      BigDecimal replaySeconds) {
    int n = keys.size();
    if (seconds.size() != n) {
      throw new IllegalArgumentException(
          "keys and seconds differ in length: " + n + " and " + seconds.size());
    }
    if (n < MIN_POINTS) {
      throw new IllegalArgumentException(
          "at least " + MIN_POINTS + " measured sizes are needed to project, got " + n);
    }
    double[] x = new double[n];
    double[] y = new double[n];
    for (int i = 0; i < n; i++) {
      x[i] = keys.get(i).doubleValue();
      y[i] = seconds.get(i).doubleValue();
    }
    SlopeEstimate slope = Stats.theilSen(x, y);
    double t = target.doubleValue();
    double atPoint = at(x, y, slope.slope().doubleValue(), t);
    double atLower = at(x, y, slope.lower().doubleValue(), t);
    double atUpper = at(x, y, slope.upper().doubleValue(), t);
    BigDecimal floor = floorSeconds.setScale(SCALE, RoundingMode.HALF_EVEN);
    BigDecimal replay = replaySeconds.setScale(SCALE, RoundingMode.HALF_EVEN);
    BigDecimal point = Stats.decimal(atPoint).max(floor).add(replay);
    BigDecimal lower =
        Stats.decimal(Math.min(atPoint, Math.min(atLower, atUpper))).max(floor).add(replay);
    BigDecimal upper =
        Stats.decimal(Math.max(atPoint, Math.max(atLower, atUpper))).max(floor).add(replay);
    return new Bounds(point, lower, upper);
  }

  /** The line of slope {@code slope} through the median residual, evaluated at {@code target}. */
  private static double at(double[] x, double[] y, double slope, double target) {
    double[] residuals = new double[x.length];
    for (int i = 0; i < x.length; i++) {
      residuals[i] = y[i] - slope * x[i];
    }
    return Stats.median(residuals) + slope * target;
  }

  /**
   * The replay of the gap between the last backup and the failure, scaled from the measured gap:
   * {@code replayNanos / gapRecords x recordsPerDay x backupIntervalHours / 24}, in seconds. The
   * L-6 and L-7 reasons state the assumed interval; the replay rate comes from the largest measured
   * point.
   *
   * @throws IllegalArgumentException unless the gap and the interval are positive
   */
  public static BigDecimal replaySeconds(
      long replayNanos, long gapRecords, long recordsPerDay, BigDecimal backupIntervalHours) {
    if (gapRecords <= 0) {
      throw new IllegalArgumentException("the gap must hold records: " + gapRecords);
    }
    if (replayNanos < 0 || recordsPerDay <= 0) {
      throw new IllegalArgumentException("replay time and records per day must be positive");
    }
    if (backupIntervalHours.signum() <= 0) {
      throw new IllegalArgumentException(
          "the backup interval must be positive: " + backupIntervalHours);
    }
    MathContext mc = new MathContext(30, RoundingMode.HALF_EVEN);
    BigDecimal secondsPerRecord =
        BigDecimal.valueOf(replayNanos)
            .divide(NANOS_PER_SECOND, mc)
            .divide(BigDecimal.valueOf(gapRecords), mc);
    return secondsPerRecord
        .multiply(BigDecimal.valueOf(recordsPerDay), mc)
        .multiply(backupIntervalHours, mc)
        .divide(HOURS_PER_DAY, mc)
        .setScale(SCALE, RoundingMode.HALF_EVEN);
  }

  /**
   * The physical floor of a restore: the cluster is read once and written once, so twice its size
   * over the slower of the two rates, in seconds.
   *
   * @param bytes the size of the cluster
   * @param readBytesPerSec the sustained read rate of the disk
   * @param writeBytesPerSec the sustained write rate of the disk
   * @throws IllegalArgumentException unless both rates are positive
   */
  public static BigDecimal floorSeconds(long bytes, long readBytesPerSec, long writeBytesPerSec) {
    if (bytes < 0 || readBytesPerSec <= 0 || writeBytesPerSec <= 0) {
      throw new IllegalArgumentException(
          "the size must not be negative and the rates must be positive");
    }
    long slower = Math.min(readBytesPerSec, writeBytesPerSec);
    return BigDecimal.valueOf(bytes)
        .multiply(BigDecimal.valueOf(2))
        .divide(BigDecimal.valueOf(slower), SCALE, RoundingMode.HALF_EVEN);
  }
}
