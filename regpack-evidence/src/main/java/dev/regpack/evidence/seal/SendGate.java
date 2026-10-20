package dev.regpack.evidence.seal;

import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.ledger.LedgerDurability;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The send gate of spec 11.2 and 5.4 as a library check (D-E11): the first byte may leave only when
 * the ledger acknowledges a commit after a synchronous standby has it, the evidence store
 * acknowledges a write only when it is durable, and the evidence store has the required number of
 * independent failure domains. The gate is closed, with a fixed reason, when a side cannot be read
 * ({@link RuntimeException} while asking): the text of the exception is never repeated, because it
 * may name a host or carry a credential.
 *
 * <p>There is no manifest condition yet (the manifest function and the signed manifest are P3c;
 * P-SEALED stays test-only until then). A side is read only when the requirement asks something of
 * it; {@link Requirement#none()} reads nothing.
 */
public final class SendGate {
  /** The ledger does not report a synchronous commit with at least one standby. */
  public static final String LEDGER_NOT_SYNCHRONOUS =
      "the ledger commit is not acknowledged by a synchronous standby";

  /** The ledger could not be asked. */
  public static final String LEDGER_UNREADABLE = "the ledger durability cannot be read";

  /** The evidence store does not acknowledge a write only when it is durable. */
  public static final String EVIDENCE_NOT_SYNCHRONOUS =
      "the evidence store does not acknowledge a write only when it is durable";

  /** The evidence store could not be asked. */
  public static final String EVIDENCE_UNREADABLE = "the evidence store durability cannot be read";

  /**
   * What the gate asks of the ledger and of the evidence store. {@link #production()} is what a
   * production send needs; {@link #none()} is for tests and tools and is named so.
   */
  public record Requirement(
      boolean ledgerSynchronous, boolean evidenceSynchronous, int minFailureDomains) {
    public Requirement {
      if (minFailureDomains < 0) {
        throw new IllegalArgumentException("minFailureDomains must not be negative");
      }
    }

    public static Requirement production() {
      return new Requirement(true, true, 2);
    }

    /** Asks nothing and reads nothing: for tests and tools only. */
    public static Requirement none() {
      return new Requirement(false, false, 0);
    }

    private boolean asksTheLedger() {
      return ledgerSynchronous;
    }

    private boolean asksTheEvidenceStore() {
      return evidenceSynchronous || minFailureDomains > 0;
    }
  }

  /** Open if and only if there is no reason. */
  public record Result(boolean open, List<String> reasons) {
    public Result {
      reasons = List.copyOf(reasons);
      if (open != reasons.isEmpty()) {
        throw new IllegalArgumentException("a gate is open if and only if it has no reason");
      }
    }
  }

  private final Requirement requirement;

  public SendGate(Requirement requirement) {
    this.requirement = Objects.requireNonNull(requirement, "requirement");
  }

  /** The reason for fewer failure domains than {@code required}. */
  public static String tooFewDomains(int required) {
    return "the evidence store has fewer than " + required + " independent failure domains";
  }

  /**
   * Checks both sides against the requirement. The reasons are in the order ledger, acknowledgement
   * of the evidence store, failure domains.
   */
  public Result check(LedgerDurability ledger, EvidenceStore evidence) {
    Objects.requireNonNull(ledger, "ledger");
    Objects.requireNonNull(evidence, "evidence");
    List<String> reasons = new ArrayList<>();
    if (requirement.asksTheLedger()) {
      checkLedger(ledger, reasons);
    }
    if (requirement.asksTheEvidenceStore()) {
      checkEvidence(evidence, reasons);
    }
    return new Result(reasons.isEmpty(), reasons);
  }

  private static void checkLedger(LedgerDurability ledger, List<String> reasons) {
    LedgerDurability.Status status;
    try {
      status = ledger.durability();
    } catch (RuntimeException e) {
      // the text is dropped on purpose: it may name a host or carry a credential
      reasons.add(LEDGER_UNREADABLE);
      return;
    }
    if (status == null) {
      reasons.add(LEDGER_UNREADABLE);
      return;
    }
    // a synchronous status names at least one standby (the contract of LedgerDurability); the
    // gate does not take the claim without it
    if (!status.synchronous() || status.synchronousStandbys() < 1) {
      reasons.add(LEDGER_NOT_SYNCHRONOUS);
    }
  }

  private void checkEvidence(EvidenceStore evidence, List<String> reasons) {
    EvidenceStore.Durability durability;
    try {
      durability = evidence.durability();
    } catch (RuntimeException e) {
      // the text is dropped on purpose: it may name a path or a host
      reasons.add(EVIDENCE_UNREADABLE);
      return;
    }
    if (durability == null) {
      reasons.add(EVIDENCE_UNREADABLE);
      return;
    }
    if (requirement.evidenceSynchronous() && !durability.synchronousAcknowledgement()) {
      reasons.add(EVIDENCE_NOT_SYNCHRONOUS);
    }
    if (durability.independentFailureDomains() < requirement.minFailureDomains()) {
      reasons.add(tooFewDomains(requirement.minFailureDomains()));
    }
  }
}
