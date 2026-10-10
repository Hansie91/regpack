package dev.regpack.bench.eval;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One row of the criteria section, as data.
 *
 * @param id the criterion id, for example {@code R-2a}
 * @param spike the spike that owns it ({@code SP-R}, {@code SP-G}, {@code SP-L}, {@code SP-X}) or
 *     {@code HARNESS} for the self-test rows
 * @param metric what is measured and the rule in words
 * @param unit the unit of the measured value
 * @param direction how the value is compared with the threshold
 * @param threshold the threshold; present exactly when the direction is not measured-only
 * @param source where the threshold comes from (a source containing {@code PLAN} is a placeholder
 *     the owner confirms before the first full run)
 * @param required whether the criterion decides the spike status
 * @param noisy whether the verdict uses the noise margin
 * @param resources the canary resources whose spread widens the margin
 * @param minRuns the number of valid runs a count criterion needs
 * @param basis what the evidence rests on
 * @param group the group the criterion belongs to, if any
 * @param consequenceIfNotRun what stays true when the criterion is not run
 */
public record Criterion(
    String id,
    String spike,
    String metric,
    String unit,
    Direction direction,
    Optional<BigDecimal> threshold,
    String source,
    boolean required,
    boolean noisy,
    Set<Resource> resources,
    int minRuns,
    Basis basis,
    Optional<String> group,
    Optional<String> consequenceIfNotRun) {
  public Criterion {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(spike, "spike");
    Objects.requireNonNull(metric, "metric");
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(threshold, "threshold");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(basis, "basis");
    Objects.requireNonNull(group, "group");
    Objects.requireNonNull(consequenceIfNotRun, "consequenceIfNotRun");
    resources = Set.copyOf(resources);
    if (minRuns < 0) {
      throw new IllegalArgumentException(id + ": minRuns must not be negative");
    }
    if (direction == Direction.MEASURED_ONLY && threshold.isPresent()) {
      throw new IllegalArgumentException(id + ": a measured-only criterion has no threshold");
    }
    if (direction != Direction.MEASURED_ONLY && threshold.isEmpty()) {
      throw new IllegalArgumentException(id + ": a compared criterion needs a threshold");
    }
  }
}
