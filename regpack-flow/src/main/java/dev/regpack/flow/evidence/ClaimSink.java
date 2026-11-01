package dev.regpack.flow.evidence;

import dev.regpack.ports.ledger.RetentionLedger;

/**
 * Where the retention claim of an evidence object goes. The flow does not know the retention
 * engine: the composition passes it, a test passes a recorder. A call is idempotent for the same
 * claim (the ledger merges a claim into its row by the later date).
 */
@FunctionalInterface
public interface ClaimSink {
  void register(RetentionLedger.RetentionClaim claim);
}
