package dev.regpack.ports.ledger;

import dev.regpack.model.EventIdentity;

/**
 * Thrown by {@link DecisionLedger#commitDecisions} when an expected head sequence is stale. It
 * carries the current head so that the loser re-reads and re-evaluates.
 */
public class DecisionHeadConflictException extends LedgerException {
  private static final long serialVersionUID = 1L;

  private final transient EventIdentity eventIdentity;
  private final long currentHeadSeq;

  public DecisionHeadConflictException(EventIdentity eventIdentity, long currentHeadSeq) {
    super("decision head conflict for " + eventIdentity.value() + ": head is " + currentHeadSeq);
    this.eventIdentity = eventIdentity;
    this.currentHeadSeq = currentHeadSeq;
  }

  public EventIdentity eventIdentity() {
    return eventIdentity;
  }

  public long currentHeadSeq() {
    return currentHeadSeq;
  }
}
