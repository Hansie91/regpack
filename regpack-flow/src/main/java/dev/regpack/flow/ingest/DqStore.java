package dev.regpack.flow.ingest;

import dev.regpack.flow.evidence.EvidenceWriter;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.ledger.ManifestLedger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The data-quality items of the flow (plan D-W3, A2): each item is put through the {@link
 * EvidenceWriter} as an evidence object, and kept in an in-process map from its key to the item,
 * because the writer has no list method and nothing here survives a restart (W1a is in memory).
 *
 * <p>A key is deterministic, so an item written again is the same key and the same bytes and writes
 * nothing new. Two different items on one key are a defect of the key design, not a replay: the
 * second is refused with a fixed text and the first stays (evidence is write-once and must not
 * silently drop an item). Thread-safe.
 */
public final class DqStore {
  private static final String COLLISION = "a DQ item key already holds another item";

  private final EvidenceWriter writer;
  private final Map<String, Dq> items = new TreeMap<>();

  public DqStore(EvidenceWriter writer) {
    this.writer = Objects.requireNonNull(writer, "writer");
  }

  /**
   * Writes the item.
   *
   * @throws IllegalStateException another item with other bytes already holds the key
   */
  public synchronized void write(Dq item) {
    Objects.requireNonNull(item, "item");
    EvidenceKey key = item.key();
    byte[] bytes = item.toBytes();
    var stored = writer.get(key);
    if (stored.isPresent() && !Arrays.equals(stored.get().bytes(), bytes)) {
      throw new IllegalStateException(COLLISION);
    }
    writer.put(key, bytes, Dq.MEDIA_TYPE, Dq.RETENTION_CLASS, ManifestLedger.ObjectClass.OTHER);
    items.put(key.path(), item);
  }

  /** The items written by this instance, sorted by key. */
  public synchronized List<Dq> list() {
    return new ArrayList<>(items.values());
  }
}
