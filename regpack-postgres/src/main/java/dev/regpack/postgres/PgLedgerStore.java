package dev.regpack.postgres;

import dev.regpack.model.BatchId;
import dev.regpack.model.Checkpoint;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.decision.DecisionContext;
import dev.regpack.model.decision.DecisionRecord;
import dev.regpack.ports.Clock;
import dev.regpack.ports.ledger.LedgerStore;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The PostgreSQL {@link LedgerStore}: a composition of one class per sub-port over a {@link
 * DataSource} (no pool, no driver type in the signature), an injected {@link Clock} for every
 * instant that is written, and a {@link Config}. Correctness comes from the constraints of the
 * schema (I2, D-L3); every unit of work starts through {@link Unit#begin} (Convention 11).
 *
 * <p>Built in tasks: T15 ingest, outbox and context reads; T16 decisions and partitions; T17 leases
 * and deliveries; T18 reports (subjects, the TRN, versions and the reportable unit of work). No
 * method is left unbuilt ({@code NoStubsLeftTest}). The integrity queries of T19 are not part of
 * the {@link LedgerStore} port: {@link LedgerIntegrity} reads the same schema through a {@link
 * DataSource} and the schema name of the {@link Config}.
 */
public final class PgLedgerStore implements LedgerStore {
  /**
   * The settings of an adapter (Convention 11 and 12).
   *
   * @param schema the schema that holds the tables of the ledger; set as the search path of every
   *     unit and part of the key of every advisory lock; an identifier, never quoted text
   * @param partitionsOnDemand whether a missing daily partition of {@code decision} is created by
   *     the adapter (T16)
   * @param forceSynchronousCommit whether every unit sets {@code synchronous_commit = on}; only the
   *     capacity variant C of T21 turns it off
   * @param lockTimeout {@code lock_timeout} of every unit, a positive whole number of milliseconds
   * @param statementTimeout {@code statement_timeout} of every unit, likewise
   */
  public record Config(
      String schema,
      boolean partitionsOnDemand,
      boolean forceSynchronousCommit,
      Duration lockTimeout,
      Duration statementTimeout) {
    public static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_STATEMENT_TIMEOUT = Duration.ofSeconds(60);

    public Config {
      SqlIdentifier.require(schema);
      requirePositiveWholeMillis(lockTimeout, "lockTimeout");
      requirePositiveWholeMillis(statementTimeout, "statementTimeout");
    }

    /** The defaults: partitions on demand, synchronous commit forced, timeouts of 5 and 60 s. */
    public static Config forSchema(String schema) {
      return new Config(schema, true, true, DEFAULT_LOCK_TIMEOUT, DEFAULT_STATEMENT_TIMEOUT);
    }

    public Config withPartitionsOnDemand(boolean value) {
      return new Config(schema, value, forceSynchronousCommit, lockTimeout, statementTimeout);
    }

    public Config withForceSynchronousCommit(boolean value) {
      return new Config(schema, partitionsOnDemand, value, lockTimeout, statementTimeout);
    }

    public Config withLockTimeout(Duration value) {
      return new Config(
          schema, partitionsOnDemand, forceSynchronousCommit, value, statementTimeout);
    }

    public Config withStatementTimeout(Duration value) {
      return new Config(schema, partitionsOnDemand, forceSynchronousCommit, lockTimeout, value);
    }

    private static void requirePositiveWholeMillis(Duration duration, String name) {
      Objects.requireNonNull(duration, name);
      long millis;
      try {
        millis = duration.toMillis();
      } catch (ArithmeticException e) {
        throw new IllegalArgumentException(name + " is out of range");
      }
      if (millis <= 0 || !Duration.ofMillis(millis).equals(duration)) {
        throw new IllegalArgumentException(
            name + " must be a positive whole number of milliseconds");
      }
    }
  }

  private final PgIngest ingest;
  private final PgOutbox outbox;
  private final PgContexts contexts;
  private final PgDecisions decisions;
  private final PgLeases leases;
  private final PgDeliveries deliveries;
  private final PgReports reports;

  public PgLedgerStore(DataSource dataSource, Clock clock, Config config) {
    Objects.requireNonNull(dataSource, "dataSource");
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(config, "config");
    this.ingest = new PgIngest(dataSource, clock, config);
    this.outbox = new PgOutbox(dataSource, config);
    this.contexts = new PgContexts(dataSource, config);
    this.decisions = new PgDecisions(dataSource, config);
    this.leases = new PgLeases(dataSource, clock, config);
    this.deliveries = new PgDeliveries(dataSource, clock, config);
    // the report unit shares the decision side: its partitions cache and its head conflicts
    this.reports = new PgReports(dataSource, clock, config, decisions);
  }

  // IngestLedger (T15)

  @Override
  public Result commitIngest(Commit commit) {
    return ingest.commitIngest(commit);
  }

  @Override
  public Optional<Result> ingestResult(BatchId batchId) {
    return ingest.ingestResult(batchId);
  }

  @Override
  public Optional<StoredIngest> findIngest(IngestKey key) {
    return ingest.findIngest(key);
  }

  @Override
  public Optional<Checkpoint> latestCheckpoint(String sourceId, String stream) {
    return ingest.latestCheckpoint(sourceId, stream);
  }

  // OutboxLedger (T15)

  @Override
  public List<OutboxItem> pendingOutbox(OutboxKind kind, int max) {
    return outbox.pendingOutbox(kind, max);
  }

  @Override
  public Optional<OutboxItem> outboxItem(OutboxId id) {
    return outbox.outboxItem(id);
  }

  // ContextLedger (T15: reads; the context is written with the decision, T16: PgDecisions)

  @Override
  public Optional<DecisionContext> decisionContext(Sha256 contextId) {
    return contexts.decisionContext(contextId);
  }

  // DecisionLedger (T16)

  @Override
  public List<Committed> commitDecisions(BatchCommit commit) {
    return decisions.commitDecisions(commit);
  }

  @Override
  public Optional<DecisionRecord> currentDecision(Regime regime, EventIdentity eventIdentity) {
    return decisions.currentDecision(regime, eventIdentity);
  }

  @Override
  public List<DecisionRecord> decisionChain(Regime regime, EventIdentity eventIdentity) {
    return decisions.decisionChain(regime, eventIdentity);
  }

  // LeaseLedger (T17)

  @Override
  public LeaseResult acquire(EndpointKey endpoint, String holder, Duration ttl) {
    return leases.acquire(endpoint, holder, ttl);
  }

  @Override
  public boolean renew(EndpointKey endpoint, LeaseToken token, Duration ttl) {
    return leases.renew(endpoint, token, ttl);
  }

  @Override
  public void release(EndpointKey endpoint, LeaseToken token) {
    leases.release(endpoint, token);
  }

  @Override
  public Optional<LeaseGrant> currentLease(EndpointKey endpoint) {
    return leases.currentLease(endpoint);
  }

  @Override
  public long restoreEpoch() {
    return leases.restoreEpoch();
  }

  @Override
  public void declareRestoreEpoch(long epoch) {
    leases.declareRestoreEpoch(epoch);
  }

  // DeliveryLedger (T17)

  @Override
  public DeliveryId createDelivery(NewDelivery delivery) {
    return deliveries.createDelivery(delivery);
  }

  @Override
  public Optional<Delivery> delivery(DeliveryId id) {
    return deliveries.delivery(id);
  }

  @Override
  public TransitionStatus transition(DeliveryId id, DeliveryState expectedFrom, DeliveryState to) {
    return deliveries.transition(id, expectedFrom, to);
  }

  @Override
  public AttemptId allocateAttemptId() {
    return deliveries.allocateAttemptId();
  }

  @Override
  public SendingResult beginSending(
      DeliveryId id, AttemptId attemptId, LeaseToken token, AttemptIntent intent) {
    return deliveries.beginSending(id, attemptId, token, intent);
  }

  @Override
  public boolean markCommitIssued(AttemptId attempt, LeaseToken token) {
    return deliveries.markCommitIssued(attempt, token);
  }

  @Override
  public Optional<Attempt> attempt(AttemptId id) {
    return deliveries.attempt(id);
  }

  // ReportLedger (T18)

  @Override
  public List<ReportableCommitted> commitReportable(ReportableCommit commit) {
    return reports.commitReportable(commit);
  }

  @Override
  public Optional<Subject> subject(SubjectKey key) {
    return reports.subject(key);
  }

  @Override
  public Optional<Subject> subjectByTrn(String executingLei, String trn) {
    return reports.subjectByTrn(executingLei, trn);
  }

  @Override
  public List<StoredVersion> versions(SubjectId subject) {
    return reports.versions(subject);
  }
}
