package dev.regpack.testkit.contract;

/**
 * The crash points of seal-and-send that exist before the state machine (spec 11.5 Drills, P3b1):
 * call boundaries of the ports that {@code FaultInjector} wraps, to be used as the {@code point} of
 * {@code FaultPlan.crashAt(point, nthHit)}. A crash "after" a call leaves the effect of the call
 * committed and unseen by the caller. The points "after the response" and "mid-restore" need the
 * state machine and the restore procedure and are added here by P3d and P4.
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
}
