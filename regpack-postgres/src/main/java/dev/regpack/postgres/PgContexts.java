package dev.regpack.postgres;

import dev.regpack.model.Sha256;
import dev.regpack.model.decision.DecisionCodec;
import dev.regpack.model.decision.DecisionContext;
import dev.regpack.model.json.Json;
import dev.regpack.model.json.JsonException;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.postgres.PgLedgerStore.Config;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The reads of the decision-context dictionary (spec 6.3, D-S7). A row whose {@code body} is NULL
 * is a stub, made when a decision cited its context id before the context was given: it reads as
 * empty. The context is written with the decision, by {@link PgDecisions} (T16).
 */
final class PgContexts {
  private static final String SQL =
      "SELECT body FROM decision_context WHERE context_sha = decode(?, 'hex') AND body IS NOT NULL";

  private final DataSource dataSource;
  private final Config config;

  PgContexts(DataSource dataSource, Config config) {
    this.dataSource = dataSource;
    this.config = config;
  }

  Optional<DecisionContext> decisionContext(Sha256 contextId) {
    Objects.requireNonNull(contextId, "contextId");
    return Unit.inUnit(
        dataSource,
        config,
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(SQL)) {
            statement.setString(1, contextId.hex());
            try (ResultSet rows = statement.executeQuery()) {
              if (!rows.next()) {
                return Optional.empty();
              }
              return Optional.of(parse(contextId, rows.getString(1)));
            }
          }
        });
  }

  /**
   * The stored canonical JSON back to a context, which must hash to the id it is stored under: a
   * restore that read a different context than the decision cites would replay on the wrong inputs.
   */
  private static DecisionContext parse(Sha256 contextId, String body) {
    DecisionContext context;
    try {
      Object parsed = Json.parse(body);
      if (!(parsed instanceof Map<?, ?> raw)) {
        throw new LedgerException("stored decision context is not a JSON object");
      }
      Map<String, Object> map = new LinkedHashMap<>();
      raw.forEach((key, value) -> map.put((String) key, value));
      context = DecisionCodec.contextFromMap(map);
    } catch (JsonException | IllegalArgumentException e) {
      throw new LedgerException("stored decision context cannot be read", e);
    }
    if (!context.id().equals(contextId)) {
      throw new LedgerException("stored decision context does not match its id");
    }
    return context;
  }
}
