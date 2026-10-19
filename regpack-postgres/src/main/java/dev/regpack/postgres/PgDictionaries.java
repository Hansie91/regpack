package dev.regpack.postgres;

import dev.regpack.model.decision.DecisionCodec;
import dev.regpack.model.decision.DecisionContext;
import dev.regpack.model.json.Json;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.postgres.PgLedgerStore.Config;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/**
 * The dictionary rows that a write unit needs, resolved before the unit starts: the regime, the
 * decision context (a stub row, filled when the context is given, D-S7) and the endpoint.
 *
 * <p>Why not in the unit. {@code INSERT ... ON CONFLICT DO NOTHING} of a new row waits for the
 * transaction of a concurrent inserter of the same key, and that transaction is the whole write
 * unit of the other writer (seconds for a large batch). Every writer that needs the same new row,
 * for example the first use of a pack context after an activation, would wait for the first one's
 * unit, capped by {@code lock_timeout}, and fail with 55P03. So the rows are resolved here, each
 * kind in one short transaction of its own on its own connection, as {@link PgDecisions} already
 * does for the daily partitions; the unit then only reads the ids. Rows are taken in a fixed order
 * (name, context id, endpoint fields), so that two resolvers never deadlock, and a waiting resolver
 * waits for a transaction of milliseconds.
 *
 * <p>The consequence, deliberate and owner-visible (D-S7 says "in the same unit"): a dictionary row
 * stays when the unit that needed it fails or is refused after this step. An orphan regime,
 * endpoint or context row is harmless: the dictionary is content-addressed or keyed by name,
 * nothing refers to it, and the next unit that needs it finds it. A context that was given is
 * stored by then too (a rolled-back batch can leave a readable context). What is refused before any
 * write stays refused before any write: the checks of {@link PgDecisions#prepare} run first.
 *
 * <p>The ids are cached per instance (thread-safe, bounded). Rows are never deleted or renumbered
 * by the adapter, so a cached id stays right for the life of the schema; a context is cached as a
 * stub until a unit supplies it, and then resolved again once to fill it. A cache that was filled
 * in front of a restore from backup does not outlive the process that restores.
 */
final class PgDictionaries {
  static final String REGIME_SELECT_SQL = "SELECT regime_id FROM regime WHERE regime = ?";

  static final String REGIME_INSERT_SQL =
      "INSERT INTO regime (regime) VALUES (?) ON CONFLICT ON CONSTRAINT regime_name_key DO NOTHING";

  static final String CONTEXT_SELECT_SQL =
      "SELECT context_id, body IS NULL FROM decision_context WHERE context_sha = decode(?, 'hex')";

  static final String CONTEXT_INSERT_SQL =
      "INSERT INTO decision_context (context_sha) VALUES (decode(?, 'hex'))"
          + " ON CONFLICT (context_sha) DO NOTHING";

  /** The only UPDATE of {@code decision_context}: the stub fill (D-S7). */
  static final String CONTEXT_FILL_SQL =
      "UPDATE decision_context SET pack_digest = decode(?, 'hex'), circuit_epoch = ?,"
          + " engine_build = ?, body = ? WHERE context_sha = decode(?, 'hex') AND body IS NULL";

  /** Entries kept per cache before it is emptied (a cache miss costs one short transaction). */
  static final int MAX_CACHED = 10_000;

  private static final Comparator<EndpointKey> ENDPOINT_ORDER =
      Comparator.comparing(EndpointKey::environment)
          .thenComparing(EndpointKey::submissionRoute)
          .thenComparing(EndpointKey::submittingEntityLei)
          .thenComparing(EndpointKey::destinationAuthority);

  private record ContextEntry(int id, boolean filled) {}

  private final DataSource dataSource;
  private final Config config;
  private final Map<String, Short> regimes = new ConcurrentHashMap<>();
  private final Map<String, ContextEntry> contexts = new ConcurrentHashMap<>();
  private final Map<EndpointKey, Integer> endpoints = new ConcurrentHashMap<>();

  PgDictionaries(DataSource dataSource, Config config) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.config = Objects.requireNonNull(config, "config");
  }

  // ---------------------------------------------------------------------------------------------
  // regimes

  /** The dictionary id of each regime name; new names are created in one short transaction. */
  Map<String, Short> regimes(Collection<String> names) {
    Map<String, Short> ids = new HashMap<>();
    TreeSet<String> missing = new TreeSet<>();
    for (String name : names) {
      Short cached = regimes.get(name);
      if (cached == null) {
        missing.add(name);
      } else {
        ids.put(name, cached);
      }
    }
    if (!missing.isEmpty()) {
      Map<String, Short> resolved =
          Unit.inUnit(
              dataSource,
              config,
              connection -> {
                Map<String, Short> found = new HashMap<>();
                for (String name : missing) {
                  found.put(name, resolveRegime(connection, name));
                }
                return found;
              });
      if (regimes.size() >= MAX_CACHED) {
        regimes.clear();
      }
      regimes.putAll(resolved);
      ids.putAll(resolved);
    }
    return ids;
  }

  private static short resolveRegime(Connection connection, String name) throws SQLException {
    OptionalInt id = selectRegime(connection, name);
    if (id.isEmpty()) {
      try (PreparedStatement insert = connection.prepareStatement(REGIME_INSERT_SQL)) {
        insert.setString(1, name);
        insert.executeUpdate();
      }
      id = selectRegime(connection, name);
    }
    return (short)
        id.orElseThrow(() -> new LedgerException("the regime dictionary row is missing"));
  }

  private static OptionalInt selectRegime(Connection connection, String name) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(REGIME_SELECT_SQL)) {
      statement.setString(1, name);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? OptionalInt.of(rows.getShort(1)) : OptionalInt.empty();
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // decision contexts

  /**
   * The row id of each context id (hex) with the context the batch supplies for it, if any: new
   * rows are created as stubs and a supplied context fills a stub, in one short transaction in id
   * order. A cached stub is resolved again only when a context is supplied for it.
   */
  Map<String, Integer> contexts(Map<String, Optional<DecisionContext>> supplied) {
    Map<String, Integer> ids = new HashMap<>();
    Map<String, Optional<DecisionContext>> missing = new TreeMap<>();
    for (Map.Entry<String, Optional<DecisionContext>> entry : supplied.entrySet()) {
      ContextEntry cached = contexts.get(entry.getKey());
      if (cached != null && (cached.filled() || entry.getValue().isEmpty())) {
        ids.put(entry.getKey(), cached.id());
      } else {
        missing.put(entry.getKey(), entry.getValue());
      }
    }
    if (!missing.isEmpty()) {
      Map<String, ContextEntry> resolved =
          Unit.inUnit(
              dataSource,
              config,
              connection -> {
                Map<String, ContextEntry> found = new HashMap<>();
                for (Map.Entry<String, Optional<DecisionContext>> entry : missing.entrySet()) {
                  found.put(
                      entry.getKey(), resolveContext(connection, entry.getKey(), entry.getValue()));
                }
                return found;
              });
      if (contexts.size() >= MAX_CACHED) {
        contexts.clear();
      }
      contexts.putAll(resolved);
      for (Map.Entry<String, ContextEntry> entry : resolved.entrySet()) {
        ids.put(entry.getKey(), entry.getValue().id());
      }
    }
    return ids;
  }

  private record ContextRow(int id, boolean stub) {}

  private static ContextEntry resolveContext(
      Connection connection, String contextHex, Optional<DecisionContext> context)
      throws SQLException {
    Optional<ContextRow> row = selectContext(connection, contextHex);
    if (row.isEmpty()) {
      try (PreparedStatement insert = connection.prepareStatement(CONTEXT_INSERT_SQL)) {
        insert.setString(1, contextHex);
        insert.executeUpdate();
      }
      // a skipped insert burns an identity value; the row that won the race is read here
      row = selectContext(connection, contextHex);
    }
    ContextRow found =
        row.orElseThrow(() -> new LedgerException("the decision context row is missing"));
    if (found.stub() && context.isPresent()) {
      fillContext(connection, contextHex, context.get());
    }
    return new ContextEntry(found.id(), !found.stub() || context.isPresent());
  }

  private static Optional<ContextRow> selectContext(Connection connection, String contextHex)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(CONTEXT_SELECT_SQL)) {
      statement.setString(1, contextHex);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next()
            ? Optional.of(new ContextRow(rows.getInt(1), rows.getBoolean(2)))
            : Optional.empty();
      }
    }
  }

  /** Fills a stub row; a row that is not a stub any more (a concurrent fill) is left alone. */
  private static void fillContext(Connection connection, String contextHex, DecisionContext context)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(CONTEXT_FILL_SQL)) {
      statement.setString(1, context.packDigest().hex());
      statement.setLong(2, context.circuitEpoch());
      statement.setString(3, context.engineBuild());
      statement.setString(4, Json.canonical(DecisionCodec.contextToMap(context)));
      statement.setString(5, contextHex);
      statement.executeUpdate();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // endpoints

  /** The dictionary id of each endpoint; new endpoints are created in one short transaction. */
  Map<EndpointKey, Integer> endpoints(Collection<EndpointKey> keys) {
    Map<EndpointKey, Integer> ids = new HashMap<>();
    TreeSet<EndpointKey> missing = new TreeSet<>(ENDPOINT_ORDER);
    for (EndpointKey key : keys) {
      Integer cached = endpoints.get(key);
      if (cached == null) {
        missing.add(key);
      } else {
        ids.put(key, cached);
      }
    }
    if (!missing.isEmpty()) {
      Map<EndpointKey, Integer> resolved =
          Unit.inUnit(
              dataSource,
              config,
              connection -> {
                Map<EndpointKey, Integer> found = new HashMap<>();
                for (EndpointKey key : missing) {
                  found.put(key, PgLeases.resolveEndpoint(connection, key));
                }
                return found;
              });
      if (endpoints.size() >= MAX_CACHED) {
        endpoints.clear();
      }
      endpoints.putAll(resolved);
      ids.putAll(resolved);
    }
    return ids;
  }
}
