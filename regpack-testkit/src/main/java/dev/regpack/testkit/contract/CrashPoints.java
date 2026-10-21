package dev.regpack.testkit.contract;

import java.util.List;
import java.util.Set;

/**
 * The crash points of seal-and-send that exist before the state machine (spec 11.5 Drills, P3b1):
 * call boundaries of the ports that {@code FaultInjector} wraps, to be used as the {@code point} of
 * {@code FaultPlan.crashAt(point, nthHit)}. A crash "after" a call leaves the effect of the call
 * committed and unseen by the caller. The points "after the response" and "mid-restore" need the
 * state machine and the restore procedure and are added here by P3d and P4.
 *
 * <p>No crash point may be placed on a call that the sealer reads fail-closed: {@code
 * EvidenceStore.list} and {@code EvidenceStore.get} (the restore-epoch marker), {@code
 * EvidenceStore.durability} and {@code LedgerDurability.durability} (the send gate). The sealer
 * treats every {@link RuntimeException} on these reads as "unreadable" and closes the gate, and a
 * {@code SimulatedCrash} is a {@link RuntimeException}: the crash would be swallowed and the run
 * would go on. {@link #ALL} is the registry that a test checks with {@link #onAFailClosedRead}; a
 * new point is added to both.
 */
public final class CrashPoints {
  private CrashPoints() {}

  /** The first put of a seal: the sealed bytes are acknowledged. Hit 1 of the point. */
  public static final String AFTER_SEALED_PUT = "EvidenceStore.put:AFTER";

  /**
   * The second put of a seal: the {@code DeliveryIntent} object is acknowledged (the crash point
   * "after intent" of the spec). Hit 2 of the same point as {@link #AFTER_SEALED_PUT}.
   */
  public static final String AFTER_INTENT_PUT = "EvidenceStore.put:AFTER";

  /** The SENDING unit has committed: the delivery, the attempt row and the manifest rows. */
  public static final String AFTER_SENDING_COMMIT = "LedgerStore.beginSending:AFTER";

  /** The fenced {@code commit_issued} marker is set; no byte has left. */
  public static final String AFTER_MARKER = "LedgerStore.markCommitIssued:AFTER";

  /** The send call has returned: the file is at the destination. */
  public static final String AFTER_FIRST_BYTE = "Transport.send:AFTER";

  /** Every distinct crash point (the intent put is hit 2 of the sealed put's point). */
  public static final List<String> ALL =
      List.of(AFTER_SEALED_PUT, AFTER_SENDING_COMMIT, AFTER_MARKER, AFTER_FIRST_BYTE);

  /** The calls (as {@code <Interface>.<method>}) that the sealer reads fail-closed. */
  private static final Set<String> FAIL_CLOSED_READS =
      Set.of(
          "EvidenceStore.list",
          "EvidenceStore.get",
          "EvidenceStore.durability",
          "LedgerStore.durability",
          "LedgerDurability.durability");

  /**
   * True when {@code point} ({@code <Interface>.<method>:<BEFORE|AFTER>}) is on a call that the
   * sealer reads fail-closed, where an injected crash would be swallowed.
   */
  public static boolean onAFailClosedRead(String point) {
    int colon = point.indexOf(':');
    String call = colon < 0 ? point : point.substring(0, colon);
    return FAIL_CLOSED_READS.contains(call);
  }
}
