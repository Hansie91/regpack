package dev.regpack.ports.ledger;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.Regime;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionContext;
import dev.regpack.model.decision.DecisionRecord;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Decision side of the ledger (spec 6.3): idempotent, head compare-and-set, atomic per batch. */
public interface DecisionLedger {
  /**
   * A decision to commit. The two-argument constructor means no context. A present {@code context}
   * must have {@code id()} equal to {@code body.decisionContextId()}, otherwise {@code
   * commitDecisions} throws {@link IllegalArgumentException} before it writes anything; a valid one
   * is stored in the same unit as the decision, idempotently (also when the decision itself is a
   * redelivery), and read back through {@link ContextLedger}.
   */
  record NewDecision(DecisionBody body, long expectedHeadSeq, Optional<DecisionContext> context) {
    public NewDecision {
      Objects.requireNonNull(context, "context");
    }

    public NewDecision(DecisionBody body, long expectedHeadSeq) {
      this(body, expectedHeadSeq, Optional.empty());
    }
  }

  /** The decisions are copied defensively, so a later change to the caller's list has no effect. */
  record BatchCommit(List<NewDecision> decisions, Optional<OutboxLedger.OutboxId> completesOutbox) {
    public BatchCommit {
      decisions = List.copyOf(decisions);
    }
  }

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
