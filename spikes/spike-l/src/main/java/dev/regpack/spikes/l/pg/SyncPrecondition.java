package dev.regpack.spikes.l.pg;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The precondition of every SP-L measurement (spec I1, 5.6; review focus RF14): group commit is
 * only measured while a synchronous or quorum standby streams. A sample is one row of {@code
 * pg_stat_replication}; the sampler of L2 takes one a second and marks the run invalid at the first
 * violation.
 */
public final class SyncPrecondition {
  /** No standby that commits wait for: no sample is {@code sync} or {@code quorum}. */
  public static final String NO_SYNC_STANDBY = "NO_SYNC_STANDBY";

  /**
   * A standby that commits wait for is not in the {@code streaming} state (for example {@code
   * catchup}).
   */
  public static final String STANDBY_NOT_STREAMING = "STANDBY_NOT_STREAMING";

  /**
   * One row of {@code pg_stat_replication}: application name, {@code sync_state}, {@code state}.
   */
  public record Sample(String applicationName, String syncState, String state) {
    public Sample {
      Objects.requireNonNull(applicationName, "applicationName");
      Objects.requireNonNull(syncState, "syncState");
      Objects.requireNonNull(state, "state");
    }

    /** True for a standby that a commit waits for: {@code sync} or {@code quorum}. */
    boolean committing() {
      return syncState.equals("sync") || syncState.equals("quorum");
    }
  }

  private SyncPrecondition() {}

  /**
   * The violations of the samples, empty when the precondition holds: {@link #NO_SYNC_STANDBY} when
   * no sample is {@code sync} or {@code quorum} (an {@code async} or {@code potential} standby does
   * not make a commit wait), {@link #STANDBY_NOT_STREAMING} when a standby that commits wait for is
   * not {@code streaming}. An asynchronous replica beside the quorum standby is allowed.
   */
  public static List<String> violations(List<Sample> samples) {
    List<String> out = new ArrayList<>();
    boolean any = false;
    boolean notStreaming = false;
    for (Sample s : samples) {
      if (s.committing()) {
        any = true;
        if (!s.state().equals("streaming")) {
          notStreaming = true;
        }
      }
    }
    if (!any) {
      out.add(NO_SYNC_STANDBY);
    }
    if (notStreaming) {
      out.add(STANDBY_NOT_STREAMING);
    }
    return List.copyOf(out);
  }
}
