package dev.regpack.testkit.sim;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** The scenarios P0 implements. P8 adds the rest of the spec 14.3 catalogue here. */
public final class Scenarios {
  private Scenarios() {}

  /**
   * Accepts every file and acknowledges it with {@code {"fileId", "status":"ACPT"}} after {@code
   * delay} on the endpoint clock.
   */
  public static Scenario acpt(Duration delay) {
    Objects.requireNonNull(delay, "delay");
    if (delay.isNegative() || delay.getNano() % 1000 != 0) {
      throw new IllegalArgumentException(
          "delay must be non-negative and in whole microseconds: " + delay);
    }
    return new Scenario() {
      @Override
      public String name() {
        return "ACPT";
      }

      @Override
      public Disposition onReceive(ReceivedFile file, ScenarioContext context) {
        context.emitAckAt(context.endpointNow().plus(delay), file.fileId(), "ACPT", Map.of());
        return Disposition.ACCEPT;
      }
    };
  }

  /** Receives and keeps every file and never answers (spec 9.3 silence, received case). */
  public static Scenario silence() {
    return new Scenario() {
      @Override
      public String name() {
        return "SILENCE_RECEIVED_UNANSWERED";
      }

      @Override
      public Disposition onReceive(ReceivedFile file, ScenarioContext context) {
        return Disposition.ACCEPT;
      }
    };
  }
}
