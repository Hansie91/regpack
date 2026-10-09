package dev.regpack.testkit.fault;

import java.util.Objects;

/** Thrown by a {@link FaultInjector} to simulate a process crash at a named point. */
public final class SimulatedCrash extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String point;

  /** {@code point} is {@code <point>#<n>}, for example {@code Counter.incr:AFTER#1}. */
  public SimulatedCrash(String point) {
    super("simulated crash at " + Objects.requireNonNull(point, "point"));
    this.point = point;
  }

  public String point() {
    return point;
  }
}
