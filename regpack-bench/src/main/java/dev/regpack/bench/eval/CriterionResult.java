package dev.regpack.bench.eval;

import dev.regpack.bench.stats.Estimate;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * The verdict of one criterion with the numbers behind it.
 *
 * @param id the criterion id
 * @param verdict the verdict
 * @param basis what the evidence rests on
 * @param estimate the estimate that was judged, if the criterion is a fork series
 * @param threshold the threshold that was applied, if any
 * @param margin the noise margin that was applied (zero when none)
 * @param reason one sentence saying why
 */
public record CriterionResult(
    String id,
    Verdict verdict,
    Basis basis,
    Optional<Estimate> estimate,
    Optional<BigDecimal> threshold,
    BigDecimal margin,
    String reason) {
  public CriterionResult {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(verdict, "verdict");
    Objects.requireNonNull(basis, "basis");
    Objects.requireNonNull(estimate, "estimate");
    Objects.requireNonNull(threshold, "threshold");
    Objects.requireNonNull(margin, "margin");
    Objects.requireNonNull(reason, "reason");
  }
}
