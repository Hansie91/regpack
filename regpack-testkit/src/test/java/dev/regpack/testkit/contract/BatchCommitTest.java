package dev.regpack.testkit.contract;

import static dev.regpack.testkit.contract.LedgerFixtures.body;
import static dev.regpack.testkit.contract.LedgerFixtures.decision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Outcome;
import dev.regpack.ports.ledger.DecisionLedger.BatchCommit;
import dev.regpack.ports.ledger.DecisionLedger.NewDecision;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The ledger sub-port records copy the lists they are given (Convention 7). */
class BatchCommitTest {
  private static final Instant AT = Instant.parse("2026-10-09T10:00:00Z");

  private static NewDecision first() {
    return decision(body("E1", "mifir.eu.rts22", "ctx", "digest", Outcome.REPORT, AT), 0);
  }

  @Test
  void batchCommitCopiesItsDecisionsDefensively() {
    List<NewDecision> supplied = new ArrayList<>(List.of(first()));
    BatchCommit commit = new BatchCommit(supplied, Optional.empty());
    supplied.add(decision(body("E2", "mifir.eu.rts22", "ctx", "digest", Outcome.REPORT, AT), 0));
    assertThat(commit.decisions()).containsExactly(first());
    supplied.clear();
    assertThat(commit.decisions()).hasSize(1);
    assertThatThrownBy(() -> commit.decisions().add(first()))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void batchCommitRejectsNullDecisions() {
    assertThatThrownBy(() -> new BatchCommit(null, Optional.empty()))
        .isInstanceOf(NullPointerException.class);
  }
}
