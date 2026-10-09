package dev.regpack.ports.ledger;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.Regime;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionRecord;
import java.util.List;
import java.util.Optional;

/** Decision side of the ledger (spec 6.3): idempotent, head compare-and-set, atomic per batch. */
public interface DecisionLedger {
  record NewDecision(DecisionBody body, long expectedHeadSeq) {}

  record BatchCommit(
      List<NewDecision> decisions, Optional<OutboxLedger.OutboxId> completesOutbox) {}

  enum CommitStatus {
    INSERTED,
    NOOP_REDELIVERY
  }

  record Committed(CommitStatus status, DecisionRecord record) {}

  /**
   * Commits every decision of the batch or none of them, and completes the outbox item in the same
   * unit.
   *
   * @throws DecisionHeadConflictException if a decision's expected head differs from its chain
   *     head; nothing is written
   */
  List<Committed> commitDecisions(BatchCommit commit);

  Optional<DecisionRecord> currentDecision(Regime regime, EventIdentity eventIdentity);

  /** The chain of a regime and event, ascending by sequence. */
  List<DecisionRecord> decisionChain(Regime regime, EventIdentity eventIdentity);
}
