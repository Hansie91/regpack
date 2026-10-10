package dev.regpack.ports.ledger;

import java.util.List;
import java.util.Optional;

/** Transactional outbox: work items created in the same unit as the data they describe. */
public interface OutboxLedger {
  record OutboxId(long value) {}

  enum OutboxKind {
    DECIDE_BATCH
  }

  enum OutboxState {
    PENDING,
    DONE
  }

  record OutboxItem(OutboxId id, OutboxKind kind, String subjectId, OutboxState state) {}

  /** Pending items of {@code kind}, oldest first, at most {@code max}. */
  List<OutboxItem> pendingOutbox(OutboxKind kind, int max);

  Optional<OutboxItem> outboxItem(OutboxId id);
}
