package dev.regpack.testkit.sim;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** What a {@link Scenario} may see and do while it handles one received file. */
public interface ScenarioContext {
  /** The endpoint's own clock, read once when the file arrived. */
  Instant endpointNow();

  /** Accepted files in arrival order, excluding the current one. */
  List<Scenario.ReceivedFile> earlierFiles();

  /**
   * Queues an acknowledgement that becomes visible to {@code pollAcks} when the endpoint clock
   * reaches {@code visibleAt}. Its bytes are the canonical JSON of {@code details} plus {@code
   * fileId} and {@code status}; it is queued only if the scenario accepts the file, and it may be
   * called only during {@link Scenario#onReceive}.
   */
  void emitAckAt(Instant visibleAt, String fileId, String status, Map<String, String> details);
}
