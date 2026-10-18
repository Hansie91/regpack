package dev.regpack.postgres;

import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.OutboxLedger;
import dev.regpack.ports.ledger.OutboxLedger.OutboxId;
import dev.regpack.ports.ledger.OutboxLedger.OutboxItem;
import dev.regpack.ports.ledger.OutboxLedger.OutboxKind;
import dev.regpack.ports.ledger.OutboxLedger.OutboxState;
import dev.regpack.postgres.PgLedgerStore.Config;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The outbox reads (spec 7.8). The rows are written by the units that create the work: ingest in
 * T15, reports in T18; claiming them is P3d.
 */
final class PgOutbox {
  /**
   * The pending items of one kind, oldest first. The state code is a literal of the statement, not
   * a bind parameter: the planner can prove the predicate of the partial index {@code
   * outbox_pending_idx} only from a constant, and {@link Codes} is where the number comes from.
   */
  static final String PENDING_SQL =
      "SELECT outbox_id, subject_id FROM outbox WHERE kind = ? AND state = "
          + Codes.of(OutboxState.PENDING)
          + " ORDER BY outbox_id LIMIT ?";

  private static final String ONE_SQL =
      "SELECT kind, subject_id, state FROM outbox WHERE outbox_id = ?";

  private final DataSource dataSource;
  private final Config config;

  PgOutbox(DataSource dataSource, Config config) {
    this.dataSource = dataSource;
    this.config = config;
  }

  List<OutboxItem> pendingOutbox(OutboxKind kind, int max) {
    Objects.requireNonNull(kind, "kind");
    if (max < 0) {
      throw new IllegalArgumentException("max must be >= 0");
    }
    int limit = Math.min(max, OutboxLedger.PAGE_LIMIT);
    if (limit == 0) {
      return List.of();
    }
    return Unit.inUnit(
        dataSource,
        config,
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(PENDING_SQL)) {
            statement.setShort(1, Codes.of(kind));
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
              List<OutboxItem> items = new ArrayList<>();
              while (rows.next()) {
                items.add(
                    new OutboxItem(
                        new OutboxId(rows.getLong(1)),
                        kind,
                        rows.getString(2),
                        OutboxState.PENDING));
              }
              return List.copyOf(items);
            }
          }
        });
  }

  Optional<OutboxItem> outboxItem(OutboxId id) {
    Objects.requireNonNull(id, "id");
    return Unit.inUnit(
        dataSource,
        config,
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(ONE_SQL)) {
            statement.setLong(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
              if (!rows.next()) {
                return Optional.empty();
              }
              try {
                return Optional.of(
                    new OutboxItem(
                        id,
                        Codes.outboxKind(rows.getShort(1)),
                        rows.getString(2),
                        Codes.outboxState(rows.getShort(3))));
              } catch (IllegalArgumentException e) {
                throw new LedgerException("stored outbox row has an unknown kind or state", e);
              }
            }
          }
        });
  }
}
