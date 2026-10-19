package dev.regpack.postgres;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.Regime;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionContext;
import dev.regpack.model.decision.DecisionId;
import dev.regpack.model.decision.DecisionRecord;
import dev.regpack.ports.ledger.DecisionHeadConflictException;
import dev.regpack.ports.ledger.DecisionLedger.BatchCommit;
import dev.regpack.ports.ledger.DecisionLedger.CommitStatus;
import dev.regpack.ports.ledger.DecisionLedger.Committed;
import dev.regpack.ports.ledger.DecisionLedger.NewDecision;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.OutboxLedger.OutboxId;
import dev.regpack.ports.ledger.OutboxLedger.OutboxState;
import dev.regpack.ports.ledger.WithdrawalRequiredException;
import dev.regpack.postgres.DecisionWaves.Chain;
import dev.regpack.postgres.PgErrors.Violation;
import dev.regpack.postgres.PgLedgerStore.Config;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/**
 * The decision side of the ledger on PostgreSQL (spec 6.3 and 10, D-S1 to D-S4, D-S7, D-S17, D-L2;
 * T16).
 *
 * <p>A commit is one unit. For every decision, in this order (the contract is the oracle):
 *
 * <ol>
 *   <li>the dictionary rows: the regime, and the decision context once per distinct context id of
 *       the unit (a stub row when the context is not given, filled once when it is, D-S7). They are
 *       resolved before the unit, each kind in a short transaction of its own ({@link
 *       PgDictionaries}), so that a new row is not held uncommitted for the length of a long unit
 *       (a concurrent unit that needs the same new row would wait for it until {@code
 *       lock_timeout}); an orphan row after a failed unit is harmless;
 *   <li>a plain read of the head of the chain; it supplies the link to the superseded decision and
 *       is not judged yet, because a redelivery with a stale expected head is a no-op;
 *   <li>the insert of the decision row, {@code ON CONFLICT ON CONSTRAINT decision_idem_key DO
 *       NOTHING}: no row back means a redelivery, whose stored record is read by the key;
 *   <li>the compare-and-set of the head, only for a row that was inserted: a first decision inserts
 *       the head, a later one updates it where the sequence is the expected one. No row affected
 *       rolls the whole unit back, and {@link DecisionHeadConflictException} carries the head that
 *       is read again after the rollback.
 * </ol>
 *
 * <p>The decisions are not written one by one. {@link DecisionWaves} cuts the batch into waves with
 * at most one decision per chain, and each wave is written by a few set-based statements (the heads
 * in one read, the decisions in one insert, the new heads in one insert, the advanced heads in one
 * JDBC batch), so that a batch of thousands of independent decisions costs a constant number of
 * round trips. Inside a wave everything is written and locked in {@link DecisionWaves#CANONICAL}
 * order (Convention 11). A redelivery costs one point read per decision.
 *
 * <p>A head that was read before the insert and differs from the expected one is a conflict as soon
 * as the row is known to be new: heads only rise, so the compare-and-set could never match, and the
 * link read would be wrong if a concurrent writer raised the head to the expected value in between.
 * A lost race at the compare-and-set (no row, a deadlock or a serialization failure on a head
 * statement) is the same conflict. Any other failure is a {@link LedgerException} built by {@link
 * PgErrors#wrap}.
 *
 * <p>The daily partitions of {@code decision} are created before the unit starts, on a connection
 * of their own, in one transaction under the partition advisory lock (Convention 12, purpose 2); a
 * day that is already listed in {@code pg_inherits} is skipped, and known days are cached. A plain
 * table of the same name makes the creation fail with 42P07 and so the call, before any decision
 * row is written.
 *
 * <p>The head and the stored body are read back with the primary key plus the day, so a read
 * touches one partition: {@code currentDecision} is the head and one probe, {@code decisionChain}
 * follows the {@code prev_decision_id} and {@code prev_as_at} links, one probe per decision.
 */
final class PgDecisions {
  /** {@code decision.body_encoding}: the version byte of {@link DecisionBodyCodec} (E2, A13). */
  static final short BODY_ENCODING = (short) DecisionBodyCodec.VERSION;

  /** The flag that {@code PackRegime} stamps on a decision made under an open circuit. */
  static final String CIRCUIT_OPEN_FLAG = "circuit=open";

  /** Advisory-lock purpose of partition creation (Convention 12). */
  private static final int PARTITION_LOCK_PURPOSE = 2;

  private static final String LOCK_SQL = "SELECT pg_advisory_xact_lock(?, hashtext(?))";

  /** Is the partition of this name already attached to {@code decision}? (T14 hand-off) */
  static final String PARTITION_LISTED_SQL =
      "SELECT 1 FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
          + " WHERE c.relname = ? AND i.inhparent = 'decision'::regclass";

  /** The heads of a wave, plain read: regime ids and event identities in, heads out. */
  static final String HEADS_SQL =
      "SELECT h.regime_id, h.event_identity, h.decision_seq, h.decision_id, h.as_at"
          + " FROM unnest(?::int8[], ?::text[]) AS t(regime_id, event_identity)"
          + " JOIN decision_head h ON h.regime_id = t.regime_id::smallint"
          + " AND h.event_identity = t.event_identity";

  /**
   * The decision rows of a wave, in array order. Twelve arrays: day, regime id, event identity,
   * sequence, context id, input digest (hex), previous decision id, previous day, outcome code,
   * circuit-open flag (1 or 0), body encoding, body (hex). Returns the rows that were inserted.
   */
  static final String INSERT_SQL =
      "INSERT INTO decision (as_at, regime_id, event_identity, decision_seq, context_id,"
          + " input_digest, prev_decision_id, prev_as_at, outcome, circuit_open, body_encoding,"
          + " body)"
          + " SELECT t.as_at::date, t.regime_id::smallint, t.event_identity, t.decision_seq,"
          + " t.context_id::int, decode(t.input_digest, 'hex'), t.prev_decision_id,"
          + " t.prev_as_at::date, t.outcome::smallint, t.circuit_open <> 0,"
          + " t.body_encoding::smallint, decode(t.body, 'hex')"
          + " FROM unnest(?::text[], ?::int8[], ?::text[], ?::int8[], ?::int8[], ?::text[],"
          + " ?::int8[], ?::text[], ?::int8[], ?::int8[], ?::int8[], ?::text[])"
          + " WITH ORDINALITY AS t(as_at, regime_id, event_identity, decision_seq, context_id,"
          + " input_digest, prev_decision_id, prev_as_at, outcome, circuit_open, body_encoding,"
          + " body, ord)"
          + " WHERE true ORDER BY t.ord"
          + " ON CONFLICT ON CONSTRAINT decision_idem_key DO NOTHING"
          + " RETURNING regime_id, event_identity, decision_id";

  /** The first decision of each chain: the head compare-and-set for an expected sequence of 0. */
  static final String HEAD_INSERT_SQL =
      "INSERT INTO decision_head (regime_id, event_identity, decision_seq, decision_id, as_at,"
          + " outcome)"
          + " SELECT t.regime_id::smallint, t.event_identity, 1, t.decision_id, t.as_at::date,"
          + " t.outcome::smallint"
          + " FROM unnest(?::int8[], ?::text[], ?::int8[], ?::text[], ?::int8[])"
          + " WITH ORDINALITY AS t(regime_id, event_identity, decision_id, as_at, outcome, ord)"
          + " WHERE true ORDER BY t.ord"
          + " ON CONFLICT ON CONSTRAINT decision_head_pk DO NOTHING"
          + " RETURNING regime_id, event_identity";

  /** The head compare-and-set for an expected sequence above 0, run as a JDBC batch. */
  static final String HEAD_UPDATE_SQL =
      "UPDATE decision_head SET decision_seq = ?, decision_id = ?, as_at = ?, outcome = ?"
          + " WHERE regime_id = ? AND event_identity = ? AND decision_seq = ?";

  /** The stored record of a redelivered decision, by its idempotency key (one partition). */
  static final String STORED_SQL =
      "SELECT decision_id, decision_seq, prev_decision_id, body_encoding, body FROM decision"
          + " WHERE regime_id = ? AND event_identity = ? AND context_id = ?"
          + " AND input_digest = decode(?, 'hex') AND as_at = ?";

  static final String CURRENT_HEAD_SQL =
      "SELECT h.decision_seq, h.decision_id, h.as_at FROM decision_head h"
          + " JOIN regime r ON r.regime_id = h.regime_id WHERE r.regime = ? AND h.event_identity = ?";

  /** The primary-key probe of one decision; binding the day prunes it to one partition. */
  static final String PROBE_SQL =
      "SELECT decision_seq, prev_decision_id, prev_as_at, body_encoding, body FROM decision"
          + " WHERE decision_id = ? AND as_at = ?";

  static final String OUTBOX_DONE_SQL = "UPDATE outbox SET state = ? WHERE outbox_id = ?";

  /**
   * The withdrawal guard (rule 8): the legs of the superseded chains that have report versions
   * although the new decision is not REPORT or does not name the leg. Four arrays, one element per
   * superseding decision of the wave: regime id, event identity, 1 when the outcome is REPORT, and
   * the bit mask of the reported legs ({@link #legMask}). Returns (position, leg code), lowest
   * position first. A subject row exists only with at least one version, but the count is checked.
   */
  static final String WITHDRAWAL_SQL =
      "SELECT t.ord, s.leg FROM unnest(?::int8[], ?::text[], ?::int8[], ?::int8[])"
          + " WITH ORDINALITY AS t(regime_id, event_identity, reports, leg_mask, ord)"
          + " JOIN report_subject s ON s.regime_id = t.regime_id::smallint"
          + " AND s.event_identity = t.event_identity"
          + " WHERE s.last_version_seq > 0"
          + " AND (t.reports = 0 OR (t.leg_mask & (1::int8 << s.leg::int)) = 0)"
          + " ORDER BY t.ord, s.leg";

  private final DataSource dataSource;
  private final Config config;
  private final PgDictionaries dictionaries;
  private final Set<LocalDate> knownPartitions = ConcurrentHashMap.newKeySet();

  PgDecisions(DataSource dataSource, Config config) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.config = Objects.requireNonNull(config, "config");
    this.dictionaries = new PgDictionaries(dataSource, config);
  }

  /** The dictionary rows (regime, context, endpoint), resolved before a unit starts. */
  PgDictionaries dictionaries() {
    return dictionaries;
  }

  /** True when the decision was made under an open circuit (flag {@value #CIRCUIT_OPEN_FLAG}). */
  static boolean circuitOpen(DecisionBody body) {
    return body.flags().contains(CIRCUIT_OPEN_FLAG);
  }

  // ---------------------------------------------------------------------------------------------
  // commit

  List<Committed> commitDecisions(BatchCommit commit) {
    Prepared prepared = prepare(commit);
    beforeUnit(prepared);
    return runUnit(connection -> commitIn(connection, prepared, WaveHook.NONE));
  }

  /**
   * What a unit needs before it starts, each step outside the unit and in short transactions of its
   * own: the daily partitions of the batch, then its dictionary rows ({@link PgDictionaries}). A
   * partition that cannot be made fails the call before any dictionary row is written.
   */
  void beforeUnit(Prepared prepared) {
    ensurePartitions(prepared.days());
    resolveDictionaries(prepared);
  }

  /**
   * Runs one unit whose work includes the decision steps ({@link #commitIn}) after {@link
   * #beforeUnit}: a lost race for a head, signalled by {@link HeadMismatch}, becomes the {@link
   * DecisionHeadConflictException} of a head that is read again after the rollback; the cache of
   * known partitions is forgotten when no partition accepted a row. The report side of T18 runs its
   * unit through here so that it shares both.
   */
  <T> T runUnit(Unit.Work<T> work) {
    try {
      return Unit.inUnit(dataSource, config, work);
    } catch (HeadMismatch mismatch) {
      throw conflict(mismatch);
    } catch (LedgerException e) {
      forgetPartitionsAfterMissingPartition(e);
      throw e;
    }
  }

  /**
   * Validates and encodes the whole commit before any connection is opened: an expected head below
   * zero, a context that is not the one the body cites, an asAt outside the supported range and a
   * body without a canonical form are {@link IllegalArgumentException}s with nothing written.
   */
  static Prepared prepare(BatchCommit commit) {
    Objects.requireNonNull(commit, "commit");
    List<NewDecision> decisions = commit.decisions();
    List<Item> items = new ArrayList<>(decisions.size());
    Set<LocalDate> days = new TreeSet<>();
    for (int i = 0; i < decisions.size(); i++) {
      NewDecision decision = decisions.get(i);
      DecisionBody body = decision.body();
      if (decision.expectedHeadSeq() < 0) {
        throw new IllegalArgumentException("expectedHeadSeq must be >= 0");
      }
      if (decision.context().isPresent()
          && !decision.context().get().id().equals(body.decisionContextId())) {
        throw new IllegalArgumentException(
            "the context supplied with a decision has id "
                + decision.context().get().id().hex()
                + " but the body cites "
                + body.decisionContextId().hex());
      }
      LocalDate day = Partitions.dateOf(body.asAt());
      byte[] stored = DecisionBodyCodec.encode(body);
      days.add(day);
      items.add(new Item(i, decision, day, stored));
    }
    return new Prepared(
        List.copyOf(items), Collections.unmodifiableSet(days), commit.completesOutbox());
  }

  /**
   * The decision steps of a unit, inside a transaction that the caller owns and started with {@link
   * Unit#begin}: nothing is committed here, the partitions must exist and the dictionary rows must
   * be resolved (see {@link #beforeUnit}). After each wave the {@code hook} runs in the same
   * transaction, so that the report side can add the versions of a decision before the next
   * decision of the same chain is written and the withdrawal guard of that next decision sees them.
   * The outbox item is completed last.
   */
  List<Committed> commitIn(Connection connection, Prepared prepared, WaveHook hook)
      throws SQLException {
    List<Item> items = prepared.items();
    List<Chain> chains = new ArrayList<>(items.size());
    for (Item item : items) {
      if (!item.resolved) {
        throw new IllegalStateException("the dictionary rows of the batch are not resolved");
      }
      item.chain = new Chain(item.regimeId, item.body().eventIdentity().value());
      chains.add(item.chain);
    }
    Committed[] results = new Committed[items.size()];
    for (List<Integer> positions : DecisionWaves.split(chains)) {
      List<Item> wave = new ArrayList<>(positions.size());
      for (int position : positions) {
        wave.add(items.get(position));
      }
      writeWave(connection, wave, results, hook);
    }
    completeOutbox(connection, prepared.completesOutbox());
    return List.of(results);
  }

  // ---------------------------------------------------------------------------------------------
  // dictionaries (step 0), before the unit

  /**
   * Resolves the regimes and the contexts of the batch, outside the unit, and sets the ids on the
   * items. The first context that the batch supplies for an id fills the stub row of that id. The
   * context id that a supplied context carries was checked against the body in {@link #prepare},
   * before anything is written.
   */
  void resolveDictionaries(Prepared prepared) {
    List<Item> items = prepared.items();
    Set<String> names = new TreeSet<>();
    Map<String, Optional<DecisionContext>> supplied = new TreeMap<>();
    for (Item item : items) {
      names.add(item.regime());
      supplied.merge(
          item.body().decisionContextId().hex(),
          item.decision().context(),
          (known, next) -> known.isPresent() ? known : next);
    }
    Map<String, Short> regimes = dictionaries.regimes(names);
    Map<String, Integer> contexts = dictionaries.contexts(supplied);
    for (Item item : items) {
      item.regimeId = regimes.get(item.regime());
      item.contextId = contexts.get(item.body().decisionContextId().hex());
      item.resolved = true;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // one wave: at most one decision per chain

  private void writeWave(Connection connection, List<Item> wave, Committed[] results, WaveHook hook)
      throws SQLException {
    List<Item> ordered = new ArrayList<>(wave);
    ordered.sort(Comparator.comparing(item -> item.chain, DecisionWaves.CANONICAL));
    readHeads(connection, ordered);
    Map<Chain, Long> inserted = insertDecisions(connection, ordered);
    List<Item> created = new ArrayList<>();
    List<Item> redelivered = new ArrayList<>();
    for (Item item : ordered) {
      Long id = inserted.get(item.chain);
      if (id == null) {
        redelivered.add(item);
      } else {
        item.decisionId = id;
        created.add(item);
      }
    }
    requireExpectedHeads(created);
    advanceHeads(connection, created);
    // rule 8 (D-L4) runs only now: the compare-and-set above waits behind the share lock that a
    // concurrent redelivery takes on the head before it creates a version, so the guard reads that
    // version once it is committed
    requireNoWithdrawal(connection, created);
    for (Item item : redelivered) {
      results[item.index] =
          new Committed(CommitStatus.NOOP_REDELIVERY, storedRecord(connection, item));
    }
    for (Item item : created) {
      results[item.index] = new Committed(CommitStatus.INSERTED, insertedRecord(item));
    }
    List<Written> written = new ArrayList<>(ordered.size());
    for (Item item : ordered) {
      written.add(new Written(item.index, item.regimeId, item.day, results[item.index]));
    }
    hook.afterWave(connection, written);
  }

  /**
   * Rule 8 of the report semantics (D-L4): a decision that supersedes another and is not REPORT, or
   * that omits a leg, fails closed while that leg of the chain has report versions; the withdrawal
   * transaction of spec 9.3 belongs to the delivery state machine. One query over the wave. A first
   * decision of a chain supersedes nothing and so has no subject to withdraw.
   */
  private static void requireNoWithdrawal(Connection connection, List<Item> created)
      throws SQLException {
    List<Item> superseding = new ArrayList<>(created.size());
    for (Item item : created) {
      if (item.expected() > 0) {
        superseding.add(item);
      }
    }
    if (superseding.isEmpty()) {
      return;
    }
    int size = superseding.size();
    Long[] regimes = new Long[size];
    String[] events = new String[size];
    Long[] reports = new Long[size];
    Long[] masks = new Long[size];
    for (int i = 0; i < size; i++) {
      Item item = superseding.get(i);
      regimes[i] = (long) item.regimeId;
      events[i] = item.chain.eventIdentity();
      reports[i] = item.body().outcome() == Outcome.REPORT ? 1L : 0L;
      masks[i] = legMask(item.body().legs());
    }
    try (PreparedStatement statement = connection.prepareStatement(WITHDRAWAL_SQL)) {
      statement.setArray(1, connection.createArrayOf("int8", regimes));
      statement.setArray(2, connection.createArrayOf("text", events));
      statement.setArray(3, connection.createArrayOf("int8", reports));
      statement.setArray(4, connection.createArrayOf("int8", masks));
      try (ResultSet rows = statement.executeQuery()) {
        if (rows.next()) {
          Item item = superseding.get(rows.getInt(1) - 1);
          String leg;
          try {
            leg = Codes.leg(rows.getShort(2)).name();
          } catch (IllegalArgumentException e) {
            throw new LedgerException("a stored report subject has an unknown leg", e);
          }
          throw new WithdrawalRequiredException(
              "the "
                  + leg
                  + " leg of "
                  + item.chain.eventIdentity()
                  + " has report versions and the superseding decision does not report it");
        }
      }
    }
  }

  /** One bit per leg, at the position of its stored code: the legs a decision reports. */
  static long legMask(Collection<Leg> legs) {
    long mask = 0;
    for (Leg leg : legs) {
      mask |= 1L << Codes.of(leg);
    }
    return mask;
  }

  /** Step 1: the heads of the wave, one plain read; a chain without a row has head 0. */
  private static void readHeads(Connection connection, List<Item> ordered) throws SQLException {
    int size = ordered.size();
    Long[] regimes = new Long[size];
    String[] events = new String[size];
    Map<Chain, Item> byChain = new HashMap<>();
    for (int i = 0; i < size; i++) {
      Item item = ordered.get(i);
      regimes[i] = (long) item.regimeId;
      events[i] = item.chain.eventIdentity();
      byChain.put(item.chain, item);
    }
    try (PreparedStatement statement = connection.prepareStatement(HEADS_SQL)) {
      statement.setArray(1, connection.createArrayOf("int8", regimes));
      statement.setArray(2, connection.createArrayOf("text", events));
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          Item item = byChain.get(new Chain(rows.getShort(1), rows.getString(2)));
          if (item == null) {
            throw new LedgerException("the head read returned a chain that was not asked for");
          }
          item.headSeq = rows.getLong(3);
          item.headDecisionId = OptionalLong.of(rows.getLong(4));
          item.headDay = Optional.of(rows.getObject(5, LocalDate.class));
        }
      }
    }
  }

  /** Step 2: the decision rows, one statement; the chains whose row was new come back. */
  private static Map<Chain, Long> insertDecisions(Connection connection, List<Item> ordered)
      throws SQLException {
    int size = ordered.size();
    String[] days = new String[size];
    Long[] regimes = new Long[size];
    String[] events = new String[size];
    Long[] sequences = new Long[size];
    Long[] contexts = new Long[size];
    String[] digests = new String[size];
    Long[] previousIds = new Long[size];
    String[] previousDays = new String[size];
    Long[] outcomes = new Long[size];
    Long[] circuitFlags = new Long[size];
    Long[] encodings = new Long[size];
    String[] bodies = new String[size];
    HexFormat hex = HexFormat.of();
    for (int i = 0; i < size; i++) {
      Item item = ordered.get(i);
      days[i] = item.day().toString();
      regimes[i] = (long) item.regimeId;
      events[i] = item.chain.eventIdentity();
      sequences[i] = item.expected() + 1;
      contexts[i] = (long) item.contextId;
      digests[i] = item.body().inputDigest().hex();
      if (item.headDecisionId.isPresent()) {
        previousIds[i] = item.headDecisionId.getAsLong();
        previousDays[i] = item.headDay.orElseThrow().toString();
      }
      outcomes[i] = (long) Codes.of(item.body().outcome());
      circuitFlags[i] = circuitOpen(item.body()) ? 1L : 0L;
      encodings[i] = (long) BODY_ENCODING;
      bodies[i] = hex.formatHex(item.stored());
    }
    try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
      statement.setArray(1, connection.createArrayOf("text", days));
      statement.setArray(2, connection.createArrayOf("int8", regimes));
      statement.setArray(3, connection.createArrayOf("text", events));
      statement.setArray(4, connection.createArrayOf("int8", sequences));
      statement.setArray(5, connection.createArrayOf("int8", contexts));
      statement.setArray(6, connection.createArrayOf("text", digests));
      statement.setArray(7, connection.createArrayOf("int8", previousIds));
      statement.setArray(8, connection.createArrayOf("text", previousDays));
      statement.setArray(9, connection.createArrayOf("int8", outcomes));
      statement.setArray(10, connection.createArrayOf("int8", circuitFlags));
      statement.setArray(11, connection.createArrayOf("int8", encodings));
      statement.setArray(12, connection.createArrayOf("text", bodies));
      try (ResultSet rows = statement.executeQuery()) {
        Map<Chain, Long> inserted = new HashMap<>();
        while (rows.next()) {
          inserted.put(new Chain(rows.getShort(1), rows.getString(2)), rows.getLong(3));
        }
        return inserted;
      }
    }
  }

  /**
   * A row that is new while its chain has another head than the one expected is a conflict: heads
   * only rise, so the compare-and-set could not succeed, or, if a concurrent writer raised the head
   * to the expected value after the read, the link of the new row would be wrong.
   */
  private static void requireExpectedHeads(List<Item> created) {
    Item first = null;
    for (Item item : created) {
      if (item.headSeq != item.expected() && (first == null || item.index < first.index)) {
        first = item;
      }
    }
    if (first != null) {
      throw new HeadMismatch(first, null);
    }
  }

  /** Step 3: the head compare-and-set of the rows that were inserted. */
  private static void advanceHeads(Connection connection, List<Item> created) throws SQLException {
    List<Item> firsts = new ArrayList<>();
    List<Item> later = new ArrayList<>();
    for (Item item : created) {
      (item.expected() == 0 ? firsts : later).add(item);
    }
    if (!later.isEmpty()) {
      int[] counts = updateHeads(connection, later);
      if (counts.length != later.size()) {
        throw new LedgerException("a head update batch returned the wrong number of counts");
      }
      Item lost = null;
      for (int i = 0; i < counts.length; i++) {
        Item item = later.get(i);
        if (counts[i] != 1 && (lost == null || item.index < lost.index)) {
          lost = item;
        }
      }
      if (lost != null) {
        throw new HeadMismatch(lost, null);
      }
    }
    if (!firsts.isEmpty()) {
      Set<Chain> stored = insertHeads(connection, firsts);
      Item lost = null;
      for (Item item : firsts) {
        if (!stored.contains(item.chain) && (lost == null || item.index < lost.index)) {
          lost = item;
        }
      }
      if (lost != null) {
        throw new HeadMismatch(lost, null);
      }
    }
  }

  /** The rows are updated in the canonical order, one statement each, in one round trip. */
  private static int[] updateHeads(Connection connection, List<Item> later) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(HEAD_UPDATE_SQL)) {
      for (Item item : later) {
        statement.setLong(1, item.expected() + 1);
        statement.setLong(2, item.decisionId);
        statement.setObject(3, item.day());
        statement.setShort(4, Codes.of(item.body().outcome()));
        statement.setShort(5, item.regimeId);
        statement.setString(6, item.chain.eventIdentity());
        statement.setLong(7, item.expected());
        statement.addBatch();
      }
      return statement.executeBatch();
    } catch (SQLException e) {
      throw lostRace(e, later);
    }
  }

  private static Set<Chain> insertHeads(Connection connection, List<Item> firsts)
      throws SQLException {
    int size = firsts.size();
    Long[] regimes = new Long[size];
    String[] events = new String[size];
    Long[] decisionIds = new Long[size];
    String[] days = new String[size];
    Long[] outcomes = new Long[size];
    for (int i = 0; i < size; i++) {
      Item item = firsts.get(i);
      regimes[i] = (long) item.regimeId;
      events[i] = item.chain.eventIdentity();
      decisionIds[i] = item.decisionId;
      days[i] = item.day().toString();
      outcomes[i] = (long) Codes.of(item.body().outcome());
    }
    try (PreparedStatement statement = connection.prepareStatement(HEAD_INSERT_SQL)) {
      statement.setArray(1, connection.createArrayOf("int8", regimes));
      statement.setArray(2, connection.createArrayOf("text", events));
      statement.setArray(3, connection.createArrayOf("int8", decisionIds));
      statement.setArray(4, connection.createArrayOf("text", days));
      statement.setArray(5, connection.createArrayOf("int8", outcomes));
      try (ResultSet rows = statement.executeQuery()) {
        Set<Chain> stored = new HashSet<>();
        while (rows.next()) {
          stored.add(new Chain(rows.getShort(1), rows.getString(2)));
        }
        return stored;
      }
    } catch (SQLException e) {
      throw lostRace(e, firsts);
    }
  }

  /**
   * A deadlock or serialization failure on a head statement is a lost race for the head (Convention
   * 11): the unit rolls back and the caller gets the conflict, with the failure as its cause. Any
   * other failure is returned as it is, to become a {@link LedgerException}.
   */
  private static SQLException lostRace(SQLException e, List<Item> statementItems) {
    Violation violation = PgErrors.classify(e);
    if (violation == Violation.DEADLOCK || violation == Violation.SERIALIZATION) {
      Item lowest = statementItems.get(0);
      for (Item item : statementItems) {
        if (item.index < lowest.index) {
          lowest = item;
        }
      }
      throw new HeadMismatch(lowest, e);
    }
    return e;
  }

  /**
   * {@link #lostRace} for a statement on the head of one chain that the report side runs (the
   * shared read of rule 3): a deadlock or serialization failure is the conflict, any other failure
   * is returned as it is.
   */
  static SQLException lostRaceOnHead(SQLException e, String regime, EventIdentity eventIdentity) {
    Violation violation = PgErrors.classify(e);
    if (violation == Violation.DEADLOCK || violation == Violation.SERIALIZATION) {
      throw new HeadMismatch(regime, eventIdentity, e);
    }
    return e;
  }

  private static DecisionRecord insertedRecord(Item item) {
    Optional<DecisionId> supersedes =
        item.headDecisionId.isPresent()
            ? Optional.of(new DecisionId(item.headDecisionId.getAsLong()))
            : Optional.empty();
    return new DecisionRecord(
        new DecisionId(item.decisionId), item.expected() + 1, supersedes, item.body());
  }

  /** The stored record of a redelivered decision, read by its idempotency key. */
  private static DecisionRecord storedRecord(Connection connection, Item item) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(STORED_SQL)) {
      statement.setShort(1, item.regimeId);
      statement.setString(2, item.chain.eventIdentity());
      statement.setInt(3, item.contextId);
      statement.setString(4, item.body().inputDigest().hex());
      statement.setObject(5, item.day());
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new LedgerException("a redelivered decision cannot be read back by its key");
        }
        long previous = rows.getLong(3);
        Optional<DecisionId> supersedes =
            rows.wasNull() ? Optional.empty() : Optional.of(new DecisionId(previous));
        return decode(
            rows.getLong(1), rows.getLong(2), supersedes, rows.getShort(4), rows.getBytes(5));
      }
    }
  }

  private static DecisionRecord decode(
      long decisionId,
      long decisionSeq,
      Optional<DecisionId> supersedes,
      short encoding,
      byte[] stored) {
    DecisionBody body;
    try {
      if (encoding != BODY_ENCODING) {
        throw new IllegalArgumentException("unknown decision body encoding: " + encoding);
      }
      body = DecisionBodyCodec.decode(stored);
    } catch (IllegalArgumentException e) {
      throw new LedgerException("a stored decision cannot be read", e);
    }
    return new DecisionRecord(new DecisionId(decisionId), decisionSeq, supersedes, body);
  }

  private static void completeOutbox(Connection connection, Optional<OutboxId> completes)
      throws SQLException {
    if (completes.isEmpty()) {
      return;
    }
    try (PreparedStatement statement = connection.prepareStatement(OUTBOX_DONE_SQL)) {
      statement.setShort(1, Codes.of(OutboxState.DONE));
      statement.setLong(2, completes.get().value());
      if (statement.executeUpdate() != 1) {
        // the unit rolls back, so nothing of the batch is written
        throw new IllegalArgumentException("unknown outbox item " + completes.get().value());
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // head conflict, after the rollback

  /** The conflict of a unit that has been rolled back: the head is read again, in a new unit. */
  private DecisionHeadConflictException conflict(HeadMismatch mismatch) {
    long head =
        Unit.inUnit(
                dataSource,
                config,
                connection -> readHead(connection, mismatch.regime, mismatch.eventIdentity.value()))
            .map(Head::seq)
            .orElse(0L);
    DecisionHeadConflictException exception =
        new DecisionHeadConflictException(mismatch.eventIdentity, head);
    if (mismatch.getCause() != null) {
      exception.initCause(mismatch.getCause());
    }
    return exception;
  }

  /** The internal signal that a unit must be rolled back because its head compare-and-set lost. */
  static final class HeadMismatch extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String regime;
    private final transient EventIdentity eventIdentity;

    HeadMismatch(Item item, SQLException cause) {
      this(item.regime(), item.body().eventIdentity(), cause);
    }

    /**
     * The chain is named directly: rule 3 of the report semantics (a redelivery not at the head).
     */
    HeadMismatch(String regime, EventIdentity eventIdentity, SQLException cause) {
      super("decision head mismatch", cause);
      this.regime = Objects.requireNonNull(regime, "regime");
      this.eventIdentity = Objects.requireNonNull(eventIdentity, "eventIdentity");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // partitions

  /**
   * Makes sure that a partition exists for each of the days (ascending), unless the adapter is
   * configured not to create them. A day is created in its own transaction on its own connection.
   */
  void ensurePartitions(Collection<LocalDate> days) {
    if (!config.partitionsOnDemand()) {
      return;
    }
    for (LocalDate day : new TreeSet<>(days)) {
      if (!knownPartitions.contains(day)) {
        createPartition(day);
        knownPartitions.add(day);
      }
    }
  }

  private void createPartition(LocalDate day) {
    String name = Partitions.partitionName(day);
    List<String> statements = Partitions.createStatements(config.schema(), day);
    Unit.inUnit(
        dataSource,
        config,
        connection -> {
          try (PreparedStatement lock = connection.prepareStatement(LOCK_SQL)) {
            lock.setInt(1, PARTITION_LOCK_PURPOSE);
            lock.setString(2, config.schema());
            lock.execute();
          }
          if (partitionListed(connection, name)) {
            return false;
          }
          try (Statement create = connection.createStatement()) {
            for (String sql : statements) {
              create.execute(sql);
            }
          }
          return true;
        });
  }

  private static boolean partitionListed(Connection connection, String name) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(PARTITION_LISTED_SQL)) {
      statement.setString(1, name);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  /**
   * A partition that the cache knows may have been dropped since (retention): when no partition
   * accepts a row, the cache is forgotten so that the next call checks again.
   */
  private void forgetPartitionsAfterMissingPartition(LedgerException e) {
    if (e.getCause() instanceof SQLException sql
        && PgErrors.classify(sql) == Violation.NO_PARTITION) {
      knownPartitions.clear();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // reads

  Optional<DecisionRecord> currentDecision(Regime regime, EventIdentity eventIdentity) {
    Objects.requireNonNull(regime, "regime");
    Objects.requireNonNull(eventIdentity, "eventIdentity");
    return Unit.inUnit(
        dataSource,
        config,
        connection -> {
          Optional<Head> head = readHead(connection, regime.id(), eventIdentity.value());
          if (head.isEmpty()) {
            return Optional.<DecisionRecord>empty();
          }
          Probe probe = probe(connection, head.get().decisionId(), head.get().day());
          if (probe.record().decisionSeq() != head.get().seq()) {
            throw new LedgerException("a decision head and its decision disagree on the sequence");
          }
          return Optional.of(probe.record());
        });
  }

  /** The chain, ascending: the head, then one probe along the links of each decision. */
  List<DecisionRecord> decisionChain(Regime regime, EventIdentity eventIdentity) {
    Objects.requireNonNull(regime, "regime");
    Objects.requireNonNull(eventIdentity, "eventIdentity");
    return Unit.inUnit(
        dataSource,
        config,
        connection -> {
          Optional<Head> head = readHead(connection, regime.id(), eventIdentity.value());
          if (head.isEmpty()) {
            return List.<DecisionRecord>of();
          }
          List<DecisionRecord> descending = new ArrayList<>();
          long id = head.get().decisionId();
          LocalDate day = head.get().day();
          long seq = head.get().seq();
          while (true) {
            Probe probe = probe(connection, id, day);
            if (probe.record().decisionSeq() != seq) {
              throw new LedgerException("a decision chain is not consecutive");
            }
            descending.add(probe.record());
            if (probe.previousDay().isEmpty()) {
              break;
            }
            seq--;
            if (seq < 1) {
              throw new LedgerException("a decision chain is longer than its head sequence");
            }
            id = probe.record().supersedesDecisionId().orElseThrow().value();
            day = probe.previousDay().get();
          }
          if (seq != 1) {
            throw new LedgerException("a decision chain does not start at sequence 1");
          }
          Collections.reverse(descending);
          return List.copyOf(descending);
        });
  }

  private record Head(long seq, long decisionId, LocalDate day) {}

  private record Probe(DecisionRecord record, Optional<LocalDate> previousDay) {}

  private static Optional<Head> readHead(Connection connection, String regime, String event)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(CURRENT_HEAD_SQL)) {
      statement.setString(1, regime);
      statement.setString(2, event);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next()
            ? Optional.of(
                new Head(rows.getLong(1), rows.getLong(2), rows.getObject(3, LocalDate.class)))
            : Optional.empty();
      }
    }
  }

  private static Probe probe(Connection connection, long decisionId, LocalDate day)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(PROBE_SQL)) {
      statement.setLong(1, decisionId);
      statement.setObject(2, day);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new LedgerException("a decision named by a head or a link is missing");
        }
        long previous = rows.getLong(2);
        boolean hasPrevious = !rows.wasNull();
        LocalDate previousDay = rows.getObject(3, LocalDate.class);
        if (hasPrevious != (previousDay != null)) {
          throw new LedgerException("a decision has a previous id without a day, or the reverse");
        }
        Optional<DecisionId> supersedes =
            hasPrevious ? Optional.of(new DecisionId(previous)) : Optional.empty();
        DecisionRecord record =
            decode(decisionId, rows.getLong(1), supersedes, rows.getShort(4), rows.getBytes(5));
        return new Probe(record, Optional.ofNullable(previousDay));
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // the batch in memory

  /**
   * A validated commit: nothing in it has touched the database. Opaque to the report side, which
   * only reads the days whose partitions the unit needs.
   */
  record Prepared(List<Item> items, Set<LocalDate> days, Optional<OutboxId> completesOutbox) {}

  /**
   * One decision of the wave just written, in the canonical order of the wave: the position in the
   * input, the dictionary id of its regime, the UTC day of its partition and the outcome of the
   * decision steps.
   */
  record Written(int index, short regimeId, LocalDate day, Committed committed) {}

  /**
   * What runs inside the transaction after each wave of {@link #commitIn}, before the next wave is
   * written. Anything it throws rolls the whole unit back.
   */
  @FunctionalInterface
  interface WaveHook {
    WaveHook NONE = (connection, wave) -> {};

    void afterWave(Connection connection, List<Written> wave) throws SQLException;
  }

  /**
   * One decision of a batch with what the unit needs from the outside; the mutable fields are
   * filled by the unit as it runs and belong to the one thread that runs it.
   */
  static final class Item {
    private final int index;
    private final NewDecision decision;
    private final LocalDate day;
    private final byte[] stored;

    short regimeId;
    int contextId;

    /** True once {@link #resolveDictionaries} has set the ids above. */
    boolean resolved;

    Chain chain;

    /** The sequence of the head that was read, 0 when the chain has no head. */
    long headSeq;

    OptionalLong headDecisionId = OptionalLong.empty();
    Optional<LocalDate> headDay = Optional.empty();
    long decisionId;

    Item(int index, NewDecision decision, LocalDate day, byte[] stored) {
      this.index = index;
      this.decision = decision;
      this.day = day;
      this.stored = stored;
    }

    NewDecision decision() {
      return decision;
    }

    DecisionBody body() {
      return decision.body();
    }

    String regime() {
      return decision.body().regime().id();
    }

    LocalDate day() {
      return day;
    }

    byte[] stored() {
      return stored;
    }

    long expected() {
      return decision.expectedHeadSeq();
    }
  }
}
