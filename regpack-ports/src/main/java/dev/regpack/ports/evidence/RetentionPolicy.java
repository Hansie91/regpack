package dev.regpack.ports.evidence;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The retention an evidence write gets, by retention class name and write time (D-E12). There is no
 * production default: the writers take a policy at construction, and production wiring passes
 * Legal's classes and periods (gate G8). The class names in use are {@code journal} (journal
 * objects), {@code sealed} (sealed files) and {@code epoch} (the restore-epoch marker).
 */
@FunctionalInterface
public interface RetentionPolicy {
  EvidenceStore.Retention retentionFor(String retentionClass, Instant writtenAt);

  /**
   * Every class is retained for {@code days} days from the write time; for tests and tools only. At
   * least one day.
   */
  static RetentionPolicy fixedDays(int days) {
    if (days < 1) {
      throw new IllegalArgumentException("days must be at least 1");
    }
    Duration period = Duration.ofDays(days);
    return (retentionClass, writtenAt) ->
        new EvidenceStore.Retention(
            new EvidenceStore.RetentionClass(Objects.requireNonNull(retentionClass, "class")),
            Objects.requireNonNull(writtenAt, "writtenAt").plus(period));
  }
}
