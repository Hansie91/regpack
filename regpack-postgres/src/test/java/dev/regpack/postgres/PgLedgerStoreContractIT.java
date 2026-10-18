package dev.regpack.postgres;

/**
 * Runs the shared ledger contract suites against the PostgreSQL adapter (T15: {@code
 * IngestLedgerContract}, the P0 methods and the T4 ones, which include the outbox; T16: {@code
 * DecisionLedgerContract}, which includes the decision contexts; T17: {@code
 * LeaseDeliveryLedgerContract} and {@code FencingScenarioContract}; T18: {@code
 * ReportLedgerContract} and {@code ExitFixturesContract}). Real-store tests: skipped without {@code
 * REGPACK_PG_URL}, a failure with {@code REGPACK_PG_REQUIRED=true}.
 */
class PgLedgerStoreContractIT extends AbstractPgLedgerContract {}
