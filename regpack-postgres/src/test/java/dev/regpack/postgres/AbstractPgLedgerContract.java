package dev.regpack.postgres;

import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.testkit.contract.DecisionLedgerContract;
import dev.regpack.testkit.contract.ExitFixturesContract;
import dev.regpack.testkit.contract.FencingScenarioContract;
import dev.regpack.testkit.contract.IngestLedgerContract;
import dev.regpack.testkit.contract.LeaseDeliveryLedgerContract;
import dev.regpack.testkit.contract.ManifestLedgerContract;
import dev.regpack.testkit.contract.ReportLedgerContract;
import dev.regpack.testkit.contract.StorableValuesLedgerContract;

/**
 * The binding of the ledger contract suites to the PostgreSQL adapter (Convention 4): every test
 * gets a fresh, migrated schema (see {@link AbstractPgStoreTest}) and the contract interfaces of
 * the testkit are implemented here as their tasks land (T15: ingest and outbox; T16: decisions and
 * contexts; T17: leases, deliveries and the fencing scenarios; T18: the report suites and the exit
 * fixtures; P3b1 T12: the object manifest, whose {@code LedgerDurabilityContract} follows in T17 of
 * that plan). Without a server every test of a subclass is skipped by an assumption. The class is
 * public so that the schema mutants of T20 (package {@code mutants}) can bind the same suites.
 */
public abstract class AbstractPgLedgerContract extends AbstractPgStoreTest
    implements IngestLedgerContract,
        DecisionLedgerContract,
        LeaseDeliveryLedgerContract,
        FencingScenarioContract,
        ReportLedgerContract,
        ExitFixturesContract,
        StorableValuesLedgerContract,
        ManifestLedgerContract {
  @Override
  public LedgerStore ledger() {
    return store();
  }
}
