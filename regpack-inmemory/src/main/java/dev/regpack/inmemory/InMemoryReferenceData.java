package dev.regpack.inmemory;

import dev.regpack.ports.Clock;
import dev.regpack.ports.refdata.ReferenceData;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * In-memory {@link ReferenceData}: per dataset a list of immutable snapshots, per snapshot a map
 * from key to entries. Thread-safe.
 */
public final class InMemoryReferenceData implements ReferenceData {
  private record Snapshot(SnapshotInfo info, Map<String, List<Entry>> byKey) {}

  private final Clock clock;
  private final Map<DatasetId, List<Snapshot>> datasets = new HashMap<>();

  public InMemoryReferenceData(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Seals snapshot N+1 of {@code dataset}. Two entries for one key whose half-open validity
   * intervals overlap are rejected with {@link IllegalArgumentException} and nothing is created.
   */
  public synchronized SnapshotId load(DatasetId dataset, List<Entry> entries, Duration maxAge) {
    Objects.requireNonNull(dataset, "dataset");
    Objects.requireNonNull(maxAge, "maxAge");
    Map<String, List<Entry>> byKey = new TreeMap<>();
    for (Entry entry : List.copyOf(entries)) {
      byKey.computeIfAbsent(entry.key(), k -> new ArrayList<>()).add(entry);
    }
    Map<String, List<Entry>> sealed = new HashMap<>();
    for (Map.Entry<String, List<Entry>> group : byKey.entrySet()) {
      List<Entry> sorted = new ArrayList<>(group.getValue());
      sorted.sort(Comparator.comparing(Entry::validFrom));
      for (int i = 1; i < sorted.size(); i++) {
        Optional<LocalDate> previousEnd = sorted.get(i - 1).validTo();
        if (previousEnd.isEmpty() || previousEnd.get().isAfter(sorted.get(i).validFrom())) {
          throw new IllegalArgumentException(
              "overlapping validity for key " + group.getKey() + " in dataset " + dataset.name());
        }
      }
      sealed.put(group.getKey(), List.copyOf(sorted));
    }
    List<Snapshot> history = datasets.computeIfAbsent(dataset, d -> new ArrayList<>());
    SnapshotId id = new SnapshotId(history.size() + 1L);
    history.add(
        new Snapshot(new SnapshotInfo(dataset, id, clock.now(), maxAge), Map.copyOf(sealed)));
    return id;
  }

  @Override
  public synchronized SnapshotInfo currentSnapshot(DatasetId dataset) {
    List<Snapshot> history = history(dataset);
    return history.get(history.size() - 1).info();
  }

  @Override
  public synchronized Optional<Entry> lookup(
      DatasetId dataset, SnapshotId snapshot, String key, AsOf asOf, Anchor anchor) {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(asOf, "asOf");
    Objects.requireNonNull(anchor, "anchor");
    List<Snapshot> history = history(dataset);
    long sequence = snapshot.sequence();
    if (sequence < 1 || sequence > history.size()) {
      throw new IllegalArgumentException(
          "unknown snapshot " + sequence + " of dataset " + dataset.name());
    }
    LocalDate date =
        switch (anchor) {
          case TRADE_DATE -> asOf.tradeDate();
          case SUBMISSION_DATE -> asOf.submissionDate();
        };
    for (Entry entry : history.get((int) (sequence - 1)).byKey().getOrDefault(key, List.of())) {
      boolean started = !date.isBefore(entry.validFrom());
      boolean notEnded = entry.validTo().map(date::isBefore).orElse(true);
      if (started && notEnded) {
        return Optional.of(entry);
      }
    }
    return Optional.empty();
  }

  private List<Snapshot> history(DatasetId dataset) {
    Objects.requireNonNull(dataset, "dataset");
    List<Snapshot> history = datasets.get(dataset);
    if (history == null) {
      throw new IllegalArgumentException("unknown dataset " + dataset.name());
    }
    return history;
  }
}
