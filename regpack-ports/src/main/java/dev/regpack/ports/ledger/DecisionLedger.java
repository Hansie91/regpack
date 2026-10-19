package dev.regpack.ports.ledger;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.Regime;
import dev.regpack.model.decision.AsAtRange;
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
   * <p><b>Idempotency key.</b> A decision is a redelivery of a stored one when the regime, the
   * event identity, the decision context id, the input digest and the <em>UTC calendar day</em> of
   * {@code asAt} all equal those of the stored decision (the schema keeps the day, not the instant:
   * D-S3). The same event, context and input digest on another day is a new decision, not a
   * redelivery, and needs the current head like any other. Two instants of one day with the same
   * other parts are one decision.
   *
   * <p><b>Supported {@code asAt}.</b> Every adapter refuses a decision whose {@code asAt} lies
   * outside [2000-01-01, 2200-01-01) UTC (the daily partitions of the real store), see {@link
   * AsAtRange}. The decisions are checked before anything is written, so one such decision rejects
   * the whole batch: a caller that must not lose the batch behind it range-checks the instant first
   * and quarantines the record. Every text of the batch that becomes a key is a key text ({@code
   * KeyText} of the model) by construction of its type.
   *
   * @throws DecisionHeadConflictException if a decision's expected head differs from its chain
   *     head; nothing is written
   * @throws IllegalArgumentException for a negative expected head, a context that is not the one
   *     the body cites, an {@code asAt} outside the supported range, a body without a canonical
   *     JSON form (a control character or an unpaired surrogate in one of its texts, an instant
   *     finer than a microsecond) or an unknown outbox item; nothing is written
   */
  List<Committed> commitDecisions(BatchCommit commit);

  Optional<DecisionRecord> currentDecision(Regime regime, EventIdentity eventIdentity);

  /** The chain of a regime and event, ascending by sequence. */
  List<DecisionRecord> decisionChain(Regime regime, EventIdentity eventIdentity);
}
