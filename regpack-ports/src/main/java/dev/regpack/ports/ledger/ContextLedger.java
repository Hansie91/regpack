package dev.regpack.ports.ledger;

import dev.regpack.model.Sha256;
import dev.regpack.model.decision.DecisionContext;
import java.util.Optional;

/**
 * The decision-context dictionary (spec 6.3): a decision cites its context by id, and the context
 * itself is stored once, in the unit that commits the first decision that carries it (see {@link
 * DecisionLedger.NewDecision}). Restore and replay read it back by id.
 */
public interface ContextLedger {
  /**
   * The context with this id, or empty for an unknown id and for an id whose context was never
   * supplied (a decision committed without a context leaves only a stub entry on a real store).
   */
  Optional<DecisionContext> decisionContext(Sha256 contextId);
}
