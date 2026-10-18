package dev.regpack.spikes.g;

import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.Regime;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionRecord;
import dev.regpack.ports.Clock;
import dev.regpack.ports.ledger.DecisionLedger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A ledger double with a deliberate fault: it ignores the regime, so one chain per event identity
 * serves every regime. {@link CoexistenceCheck} must notice (the check has teeth).
 */
final class RegimeBlindLedger implements DecisionLedger {
  private static final Regime ONE = new Regime("blind.eu");
  private static final Clock CLOCK =
      new Clock() {
        @Override
        public Instant now() {
          return Instant.parse("2026-10-09T10:00:00Z");
        }

        @Override
        public long monotonicNanos() {
          return 0;
        }
      };

  private final InMemoryLedgerStore inner = new InMemoryLedgerStore(CLOCK);

  private static DecisionBody blind(DecisionBody b) {
    return new DecisionBody(
        b.eventIdentity(),
        ONE,
        b.decisionContextId(),
        b.asAt(),
        b.evaluatedAt(),
        b.outcome(),
        b.flags(),
        b.reasonCode(),
        b.node(),
        b.ruleIdsHit(),
        b.path(),
        b.citations(),
        b.legs(),
        b.inputDigest());
  }

  @Override
  public List<Committed> commitDecisions(BatchCommit commit) {
    List<NewDecision> blinded =
        commit.decisions().stream()
            .map(d -> new NewDecision(blind(d.body()), d.expectedHeadSeq()))
            .toList();
    return inner.commitDecisions(new BatchCommit(blinded, commit.completesOutbox()));
  }

  @Override
  public Optional<DecisionRecord> currentDecision(Regime regime, EventIdentity eventIdentity) {
    return inner.currentDecision(ONE, eventIdentity);
  }

  @Override
  public List<DecisionRecord> decisionChain(Regime regime, EventIdentity eventIdentity) {
    return inner.decisionChain(ONE, eventIdentity);
  }
}
