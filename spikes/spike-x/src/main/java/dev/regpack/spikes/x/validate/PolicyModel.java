package dev.regpack.spikes.x.validate;

import dev.regpack.bench.data.SplitMix64;
import java.util.Objects;

/**
 * The cost model of the per-record validation policy, with the failure path included. With every
 * record validated no file ever fails. With a sampling fraction {@code s} a defect survives with
 * probability {@code 1 - s}, fails the whole-file gate and costs a bisect plus one rebuild of the
 * file.
 *
 * <pre>
 * capacity = threads / ( 1/writer + s/perRecordXsd + 1/fileGate
 *                        + defectRate (1 - s) bisectSecondsPerDefect
 *                        + pFail (1/writer + 1/fileGate) )
 * pFail    = 1 - (1 - defectRate (1 - s))^100000
 * </pre>
 *
 * The rates are per thread, in records per second; {@code pFail} is the chance that a file of
 * 100,000 records fails the gate and is rebuilt once; the bisect seconds exclude the rebuild.
 */
public final class PolicyModel {
  /** The records in the file that the gate rate and the rebuild term refer to. */
  public static final int FILE_RECORDS = 100_000;

  /** Every record is validated. */
  public static final Policy ALL = new Policy("ALL", 1.0);

  /** One record in ten is validated, picked at random. */
  public static final Policy SAMPLED_10 = new Policy("SAMPLED_10", 0.10);

  private static final long SAMPLE_DENOMINATOR = 1_000_000_000L;

  private PolicyModel() {}

  /** What one thread costs per step and how many threads there are. */
  public record Component(
      double writerPerThread,
      double perRecordXsdPerThread,
      double fileGatePerThread,
      double bisectSecondsPerDefect,
      int threads) {
    public Component {
      requirePositive("writerPerThread", writerPerThread);
      requirePositive("perRecordXsdPerThread", perRecordXsdPerThread);
      requirePositive("fileGatePerThread", fileGatePerThread);
      if (!(bisectSecondsPerDefect >= 0.0) || Double.isInfinite(bisectSecondsPerDefect)) {
        throw new IllegalArgumentException(
            "bisectSecondsPerDefect must be finite and not negative: " + bisectSecondsPerDefect);
      }
      if (threads < 1) {
        throw new IllegalArgumentException("threads must be at least 1: " + threads);
      }
    }

    private static void requirePositive(String name, double value) {
      if (!(value > 0.0) || Double.isInfinite(value)) {
        throw new IllegalArgumentException(name + " must be positive and finite: " + value);
      }
    }
  }

  /** The share of records that is validated one by one. */
  public record Policy(String name, double fraction) {
    public Policy {
      Objects.requireNonNull(name, "name");
      if (!(fraction >= 0.0 && fraction <= 1.0)) {
        throw new IllegalArgumentException("fraction must be from 0 to 1: " + fraction);
      }
    }

    /** One independent sampling decision for one record, from the seeded stream. */
    public boolean sample(SplitMix64 rng) {
      if (fraction >= 1.0) {
        return true;
      }
      if (fraction <= 0.0) {
        return false;
      }
      return rng.chance(Math.round(fraction * SAMPLE_DENOMINATOR), SAMPLE_DENOMINATOR);
    }
  }

  /** Records per second of the whole stage for the policy at the defect rate. */
  public static double capacity(Component c, Policy policy, double defectRate) {
    if (!(defectRate >= 0.0 && defectRate <= 1.0)) {
      throw new IllegalArgumentException("defectRate must be from 0 to 1: " + defectRate);
    }
    double s = policy.fraction();
    double surviving = defectRate * (1.0 - s);
    double pFail = -StrictMath.expm1(FILE_RECORDS * StrictMath.log1p(-surviving));
    double secondsPerRecord =
        1.0 / c.writerPerThread()
            + s / c.perRecordXsdPerThread()
            + 1.0 / c.fileGatePerThread()
            + surviving * c.bisectSecondsPerDefect()
            + pFail * (1.0 / c.writerPerThread() + 1.0 / c.fileGatePerThread());
    return c.threads() / secondsPerRecord;
  }

  /** {@code (model - integrated) / integrated}, signed. */
  public static double relDeviation(double model, double integrated) {
    if (!(integrated > 0.0)) {
      throw new IllegalArgumentException("integrated must be positive: " + integrated);
    }
    return (model - integrated) / integrated;
  }
}
