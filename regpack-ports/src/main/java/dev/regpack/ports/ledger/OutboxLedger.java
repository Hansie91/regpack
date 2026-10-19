package dev.regpack.ports.ledger;

import java.util.List;
import java.util.Optional;

/** Transactional outbox: work items created in the same unit as the data they describe. */
public interface OutboxLedger {
  /**
   * The most items one {@link #pendingOutbox} call returns, whatever {@code max} is (P0 note
   * M115/M116). A caller that must see more pages through the queue; the sweep loop is the batch
   * driver of a later plan.
   */
  int PAGE_LIMIT = 1_000;

  record OutboxId(long value) {}

  enum OutboxKind {
    DECIDE_BATCH,
    SEQUENCE
  }

  enum OutboxState {
    PENDING,
    DONE
  }

  record OutboxItem(OutboxId id, OutboxKind kind, String subjectId, OutboxState state) {}

  /**
   * Pending items of {@code kind}, oldest first (ascending id), at most {@code min(max,
   * PAGE_LIMIT)}; a negative {@code max} is an {@link IllegalArgumentException}.
   */
  List<OutboxItem> pendingOutbox(OutboxKind kind, int max);

  Optional<OutboxItem> outboxItem(OutboxId id);
}
