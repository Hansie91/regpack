package dev.regpack.ports.ledger;

/**
 * Unit-of-work API: every "same transaction" rule of the spec is one call. The aggregate of the
 * five ledger sub-ports; later plans add sub-ports without touching these.
 */
public interface LedgerStore
    extends IngestLedger, OutboxLedger, DecisionLedger, LeaseLedger, DeliveryLedger {}
