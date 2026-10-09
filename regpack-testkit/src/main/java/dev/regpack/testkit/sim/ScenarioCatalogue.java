package dev.regpack.testkit.sim;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The fourteen destination behaviours of spec 14.3, with what is implemented now and which plan
 * owns the rest.
 */
public final class ScenarioCatalogue {
  public enum Status {
    IMPLEMENTED,
    PLANNED
  }

  public enum Kind {
    SCENARIO,
    MECHANISM
  }

  public record Entry(String name, Kind kind, Status status, String owningPlan, String specSource) {
    public Entry {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(owningPlan, "owningPlan");
      Objects.requireNonNull(specSource, "specSource");
    }
  }

  private static final String SPEC = "spec 14.3 ";

  private static final List<Entry> ALL =
      List.of(
          implemented("ACPT", Kind.SCENARIO, "ACPT"),
          planned("PDNG_THEN_ACPT", "PDNG then ACPT"),
          planned("PDNG_TURNS_RJCT_DAY8", "PDNG turning RJCT on day 8"),
          planned("RJCT_WITH_CON_CODES", "RJCT with CON codes"),
          planned("RCVD", "RCVD"),
          planned("FILE_RJCT", "file RJCT"),
          planned("FILE_CRPT", "file CRPT"),
          planned("FILE_INCF", "file INCF"),
          planned("DUPLICATE_TRN", "duplicate TRN"),
          planned("SILENCE_NOT_RECEIVED", "silence, file not received (spec 9.3)"),
          implemented(
              "SILENCE_RECEIVED_UNANSWERED",
              Kind.SCENARIO,
              "silence, file received and unanswered (spec 9.3)"),
          planned("FEEDBACK_ANOMALIES", "feedback anomalies (spec 9.4)"),
          implemented(
              "SKEWED_CLOCK",
              Kind.MECHANISM,
              "a skewed clock: the endpoint-clock offset is the mechanism; P8 adds the scenario"
                  + " content, a skewed-clock scenario factory"),
          implemented(
              "FAULT_INJECTION",
              Kind.MECHANISM,
              "fault injection: the T19 fault simulator wraps any port; the fault matrix is P4"));

  private ScenarioCatalogue() {}

  /** The entries in fixed order. */
  public static List<Entry> all() {
    return ALL;
  }

  public static Optional<Entry> byName(String name) {
    return ALL.stream().filter(entry -> entry.name().equals(name)).findFirst();
  }

  private static Entry implemented(String name, Kind kind, String source) {
    return new Entry(name, kind, Status.IMPLEMENTED, "P0", SPEC + source);
  }

  private static Entry planned(String name, String source) {
    return new Entry(name, Kind.SCENARIO, Status.PLANNED, "P8", SPEC + source);
  }
}
