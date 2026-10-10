package dev.regpack.testkit.fault;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;

/**
 * An ordered, immutable list of faults, each tied to the nth hit of a named point. Entries are kept
 * in a list so that neither the trace nor {@link #describe()} can depend on hash order.
 */
public final class FaultPlan {
  enum Kind {
    CRASH,
    FAIL,
    ADVANCE_CLOCK
  }

  record Fault(
      Kind kind,
      String point,
      int nthHit,
      Optional<RuntimeException> toThrow,
      Optional<Duration> advance) {}

  private static final FaultPlan NONE = new FaultPlan(List.of());

  private final List<Fault> faults;

  private FaultPlan(List<Fault> faults) {
    this.faults = List.copyOf(faults);
  }

  public static FaultPlan none() {
    return NONE;
  }

  public static FaultPlan crashAt(String point, int nthHit) {
    return single(
        new Fault(
            Kind.CRASH,
            requirePoint(point),
            requireHit(nthHit),
            Optional.empty(),
            Optional.empty()));
  }

  public static FaultPlan failAt(String point, int nthHit, RuntimeException toThrow) {
    Objects.requireNonNull(toThrow, "toThrow");
    return single(
        new Fault(
            Kind.FAIL,
            requirePoint(point),
            requireHit(nthHit),
            Optional.of(toThrow),
            Optional.empty()));
  }

  public static FaultPlan advanceClockAt(String point, int nthHit, Duration d) {
    Objects.requireNonNull(d, "d");
    return single(
        new Fault(
            Kind.ADVANCE_CLOCK,
            requirePoint(point),
            requireHit(nthHit),
            Optional.empty(),
            Optional.of(d)));
  }

  /** One crash; the point and the hit come from {@code new SplittableRandom(seed)}. */
  public static FaultPlan seeded(long seed, List<String> candidatePoints, int maxHit) {
    List<String> points = List.copyOf(Objects.requireNonNull(candidatePoints, "candidatePoints"));
    if (points.isEmpty()) {
      throw new IllegalArgumentException("candidatePoints must not be empty");
    }
    if (maxHit < 1) {
      throw new IllegalArgumentException("maxHit must be at least 1: " + maxHit);
    }
    SplittableRandom rng = new SplittableRandom(seed);
    String point = points.get(rng.nextInt(points.size()));
    int hit = 1 + rng.nextInt(maxHit);
    return crashAt(point, hit);
  }

  /** Both plans apply: this plan's faults first, then the other's. */
  public FaultPlan then(FaultPlan other) {
    Objects.requireNonNull(other, "other");
    List<Fault> all = new ArrayList<>(faults);
    all.addAll(other.faults);
    return new FaultPlan(all);
  }

  /** For example {@code crash IngestLedger.commitIngest:AFTER #2}; entries are joined by "; ". */
  public String describe() {
    if (faults.isEmpty()) {
      return "none";
    }
    List<String> parts = new ArrayList<>(faults.size());
    for (Fault fault : faults) {
      parts.add(describe(fault));
    }
    return String.join("; ", parts);
  }

  List<Fault> faults() {
    return faults;
  }

  boolean hasClockFaults() {
    return faults.stream().anyMatch(fault -> fault.kind() == Kind.ADVANCE_CLOCK);
  }

  private static String describe(Fault fault) {
    String where = fault.point() + " #" + fault.nthHit();
    return switch (fault.kind()) {
      case CRASH -> "crash " + where;
      case FAIL -> "fail " + where + " " + fault.toThrow().orElseThrow().getClass().getSimpleName();
      case ADVANCE_CLOCK -> "advance-clock " + where + " " + fault.advance().orElseThrow();
    };
  }

  private static FaultPlan single(Fault fault) {
    return new FaultPlan(List.of(fault));
  }

  private static String requirePoint(String point) {
    Objects.requireNonNull(point, "point");
    if (point.isBlank()) {
      throw new IllegalArgumentException("point must not be blank");
    }
    return point;
  }

  private static int requireHit(int nthHit) {
    if (nthHit < 1) {
      throw new IllegalArgumentException("nthHit must be at least 1: " + nthHit);
    }
    return nthHit;
  }
}
