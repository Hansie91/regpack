package dev.regpack.postgres;

/**
 * Runs the shared ledger contract suites against the PostgreSQL adapter (T15: {@code
 * IngestLedgerContract}, the P0 methods and the T4 ones, which include the outbox; T16: {@code
 * DecisionLedgerContract}, which includes the decision contexts). The suites that belong to later
 * tasks are added to {@link AbstractPgLedgerContract} as those land. Real-store tests: skipped
 * without {@code REGPACK_PG_URL}, a failure with {@code REGPACK_PG_REQUIRED=true}.
 */
class PgLedgerStoreContractIT extends AbstractPgLedgerContract {}
