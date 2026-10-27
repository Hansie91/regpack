package dev.regpack.ports.ledger;

/**
 * Unit-of-work API: every "same transaction" rule of the spec is one call. The aggregate of the
 * ledger sub-ports; later plans add sub-ports without touching these. The {@code retention} rows
 * ({@link RetentionLedger}) are part of it since P3c1 T11; the data-subject index ({@link
 * SubjectIndexLedger}) joins it in T12.
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
        LedgerDurability {}
