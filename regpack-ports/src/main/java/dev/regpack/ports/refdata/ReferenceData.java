package dev.regpack.ports.refdata;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Sealed as-of reference data. A snapshot never changes once loaded, so a decision can be
 * reproduced against the snapshot it used. Every lookup names a snapshot and explicit as-of dates.
 */
public interface ReferenceData {
  /** Name of a reference dataset, for example {@code lei-status}. */
  record DatasetId(String name) {
    public DatasetId {
      Objects.requireNonNull(name, "name");
    }
  }

  /** Monotonic load sequence of a dataset, starting at 1. */
  record SnapshotId(long sequence) {}

  /** Which as-of date is compared with an entry's validity interval. */
  enum Anchor {
    TRADE_DATE,
    SUBMISSION_DATE
  }

  /** The two dates a lookup may be anchored to. */
  record AsOf(LocalDate tradeDate, LocalDate submissionDate) {
    public AsOf {
      Objects.requireNonNull(tradeDate, "tradeDate");
      Objects.requireNonNull(submissionDate, "submissionDate");
    }
  }

  /**
   * One reference row, valid on the half-open interval {@code [validFrom, validTo)}. The
   * constructor throws {@link IllegalArgumentException} when {@code validTo} is present and not
   * after {@code validFrom}.
   */
  record Entry(
      String key,
      Map<String, String> attributes,
      LocalDate validFrom,
      Optional<LocalDate> validTo) {
    public Entry {
      Objects.requireNonNull(key, "key");
      attributes = Map.copyOf(attributes);
      Objects.requireNonNull(validFrom, "validFrom");
      Objects.requireNonNull(validTo, "validTo");
      if (validTo.isPresent() && !validTo.get().isAfter(validFrom)) {
        throw new IllegalArgumentException(
            "validTo must be after validFrom: " + validFrom + " .. " + validTo.get());
      }
    }
  }

  /** Metadata of a sealed snapshot. */
  record SnapshotInfo(DatasetId dataset, SnapshotId id, Instant loadedAt, Duration maxAge) {
    public SnapshotInfo {
      Objects.requireNonNull(dataset, "dataset");
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(loadedAt, "loadedAt");
      Objects.requireNonNull(maxAge, "maxAge");
    }
  }

  /** The newest snapshot of {@code dataset}; {@link IllegalArgumentException} if none exists. */
  SnapshotInfo currentSnapshot(DatasetId dataset);

  /**
   * The entry for {@code key} in the named snapshot whose validity interval contains the date of
   * {@code asOf} selected by {@code anchor}. {@link IllegalArgumentException} for an unknown
   * dataset or snapshot.
   */
  Optional<Entry> lookup(
      DatasetId dataset, SnapshotId snapshot, String key, AsOf asOf, Anchor anchor);
}
