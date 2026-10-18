package dev.regpack.postgres;

import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.testkit.contract.IngestLedgerContract;

/**
 * The binding of the ledger contract suites to the PostgreSQL adapter (Convention 4): every test
 * gets a fresh, migrated schema (see {@link AbstractPgStoreTest}) and the contract interfaces of
 * the testkit are implemented here as their tasks land (T15: ingest and outbox; T16 to T18 add the
 * others). Without a server every test of a subclass is skipped by an assumption.
 */
abstract class AbstractPgLedgerContract extends AbstractPgStoreTest
    implements IngestLedgerContract {
  @Override
  public LedgerStore ledger() {
    return store();
  }
}
