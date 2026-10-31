package dev.regpack.postgres;

import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.testkit.contract.DecisionLedgerContract;
import dev.regpack.testkit.contract.ExitFixturesContract;
import dev.regpack.testkit.contract.FencingScenarioContract;
import dev.regpack.testkit.contract.IngestLedgerContract;
import dev.regpack.testkit.contract.LeaseDeliveryLedgerContract;
import dev.regpack.testkit.contract.LedgerDurabilityContract;
import dev.regpack.testkit.contract.ManifestLedgerContract;
import dev.regpack.testkit.contract.ReportLedgerContract;
import dev.regpack.testkit.contract.RetentionLedgerContract;
import dev.regpack.testkit.contract.StorableValuesLedgerContract;
import dev.regpack.testkit.contract.SubjectIndexLedgerContract;

/**
 * The binding of the ledger contract suites to the PostgreSQL adapter (Convention 4): every test
 * gets a fresh, migrated schema (see {@link AbstractPgStoreTest}) and the contract interfaces of
 * the testkit are implemented here as their tasks land (T15: ingest and outbox; T16: decisions and
 * contexts; T17: leases, deliveries and the fencing scenarios; T18: the report suites and the exit
 * fixtures; P3b1 T12: the object manifest; P3b1 T17: {@code LedgerDurabilityContract}, bound once
 * the placeholder of the durability query was replaced by {@link PgLedgerDurability}; P3c1 T11:
 * {@code RetentionLedgerContract}, the retention rows; P3c1 T12: {@code
 * SubjectIndexLedgerContract}, the data-subject index). Without a server every test of a subclass
 * is skipped by an assumption. The class is public so that the schema mutants of T20 (package
 * {@code mutants}) can bind the same suites.
 *
 * <p>In the job {@code ledger-pg} there is no standby, so {@code durability()} answers "not
 * synchronous" and {@code aSynchronousStatusNamesAtLeastOneStandby} has nothing to check there (its
 * condition is false); the synchronous branch is exercised on the rule of {@link
 * PgLedgerDurability#evaluate} in {@code PgLedgerDurabilityTest} and on the server by {@code
 * PgDurabilityUpIT}, which is the proof of the job {@code ledger-durability}.
 */
public abstract class AbstractPgLedgerContract extends AbstractPgStoreTest
    implements IngestLedgerContract,
        DecisionLedgerContract,
        LeaseDeliveryLedgerContract,
        FencingScenarioContract,
        ReportLedgerContract,
        ExitFixturesContract,
        StorableValuesLedgerContract,
        ManifestLedgerContract,
        RetentionLedgerContract,
        SubjectIndexLedgerContract,
        LedgerDurabilityContract {
  @Override
  public LedgerStore ledger() {
    return store();
  }
}
