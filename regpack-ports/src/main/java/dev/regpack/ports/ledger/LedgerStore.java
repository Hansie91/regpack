package dev.regpack.ports.ledger;

/**
 * Unit-of-work API: every "same transaction" rule of the spec is one call. The aggregate of the
 * ledger sub-ports; later tasks add the decision, lease and delivery sub-ports to the {@code
 * extends} list.
 */
public interface LedgerStore extends IngestLedger, OutboxLedger {}
