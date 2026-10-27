package dev.regpack.ports.ledger;

/**
 * Unit-of-work API: every "same transaction" rule of the spec is one call. The aggregate of the
 * ledger sub-ports; a later plan adds a sub-port by extending this list (the retention rows and the
 * data-subject index were added by P3c1).
 */
public interface LedgerStore
    extends IngestLedger,
        OutboxLedger,
        DecisionLedger,
        LeaseLedger,
        DeliveryLedger,
        ContextLedger,
        ReportLedger,
        ManifestLedger,
        RetentionLedger,
        SubjectIndexLedger,
        LedgerDurability {}
