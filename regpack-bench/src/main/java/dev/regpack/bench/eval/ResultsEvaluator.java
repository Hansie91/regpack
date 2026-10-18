package dev.regpack.bench.eval;

import dev.regpack.bench.eval.MeasurementSet.CountValue;
import dev.regpack.bench.eval.MeasurementSet.ForkStat;
import dev.regpack.bench.eval.MeasurementSet.ForkValues;
import dev.regpack.bench.eval.MeasurementSet.MeasuredOnly;
import dev.regpack.bench.eval.MeasurementSet.Projection;
import dev.regpack.bench.eval.MeasurementSet.Series;
import dev.regpack.bench.stats.Estimate;
import dev.regpack.bench.stats.SlopeEstimate;
import dev.regpack.bench.stats.Stats;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a {@link MeasurementSet} into the verdict of its spike: one {@link CriterionResult} per
 * criterion of the spike, then the roll-up and the time-box cap. A required criterion without a
 * series is NOT_RUN; nothing here reads a clock, so the same set always gives the same result.
 */
public final class ResultsEvaluator {
  /** P-5: more discarded forks than this make a series INCONCLUSIVE. */
  private static final int MAX_DISCARDS = 3;

  /** The fewest kept forks from which an interval for the median exists. */
  private static final int MIN_KEPT = 5;

  /** P-6: forks needed for a verdict. */
  private static final int MIN_FORKS = 7;

  /** The fewest points of a projection, and its minimum for a verdict. */
  private static final int MIN_POINTS = 3;

  /** P-2: more than this share of unstable kept forks makes a series INCONCLUSIVE. */
  private static final BigDecimal MAX_UNSTABLE = new BigDecimal("0.20");

  private static final int SCALE = Stats.SCALE;

  private ResultsEvaluator() {}

  /**
   * Evaluates the set against the criteria of its spike.
   *
   * @param m the measurements
   * @param criteria the registry
   * @param evidenceLevel RAW or SUMMARY
   * @param timeBox {@code usedMinutes} and {@code boxMinutes}; the result adds {@code hit}, true
   *     when the minutes used reach the box, and a hit box caps a PASS that left a required
   *     criterion unmeasured
   * @throws IllegalArgumentException for an unknown spike, a series whose criterion is not one of
   *     the spike, or a series of the wrong kind for its criterion: a count for a noisy criterion,
   *     a fork series or projection for a criterion that counts valid runs, a series whose unit is
   *     not the unit of a compared criterion, or a series whose kind does not fit the direction of
   *     the criterion (checked by {@link Evaluator})
   */
  public static SpikeResult evaluate(
      MeasurementSet m, Criteria criteria, String evidenceLevel, Map<String, Object> timeBox) {
    List<Criterion> spikeCriteria = criteria.forSpike(m.spike());
    if (spikeCriteria.isEmpty()) {
      throw new IllegalArgumentException("no criteria for spike " + m.spike());
    }
    boolean hit = SpikeResult.boxHit(timeBox);
    Map<String, Series> byId = new LinkedHashMap<>();
    for (Series s : m.series()) {
      boolean known =
          criteria.byId(s.criterionId()).map(c -> c.spike().equals(m.spike())).orElse(false);
      if (!known) {
        throw new IllegalArgumentException(
            "unknown criterion " + s.criterionId() + " for spike " + m.spike());
      }
      byId.put(s.criterionId(), s);
    }
    Map<Resource, double[]> canary = new EnumMap<>(Resource.class);
    m.canary()
        .forEach(
            (resource, samples) ->
                canary.put(
                    resource, samples.stream().mapToDouble(BigDecimal::doubleValue).toArray()));

    List<CriterionResult> results = new ArrayList<>();
    boolean anyRequiredNotRun = false;
    for (Criterion c : spikeCriteria) {
      Series s = byId.get(c.id());
      CriterionResult r;
      if (s == null) {
        r = notRun(c);
        anyRequiredNotRun |= c.required();
      } else {
        NoiseModel noise =
            c.noisy() ? NoiseModel.fromCanary(canary, c.resources()) : NoiseModel.none();
        r = evaluateSeries(c, s, noise);
      }
      results.add(r);
    }
    Verdict status =
        Evaluator.capByTimeBox(Evaluator.rollup(results, spikeCriteria), hit, anyRequiredNotRun);

    Map<String, Object> box = new LinkedHashMap<>(timeBox);
    box.put("hit", hit);
    return new SpikeResult(
        m.spike(),
        status,
        m.scope(),
        evidenceLevel,
        results,
        m.decisions(),
        m.open(),
        m.source(),
        m.env(),
        box,
        criteria.digest().hex(),
        m.vmCount(),
        m.mixedHardware(),
        m.runs());
  }

  /**
   * The kinds of series a criterion accepts. A count is exact: for a noisy criterion it would skip
   * the forks, the noise margin and the interval. Forks and a projection estimate a value: a
   * criterion that needs a number of valid runs is an exact count, and the unit of a compared
   * criterion must be the unit of the series (a scale slip such as seconds for hours is refused).
   * The direction of the criterion is checked by the evaluator.
   */
  private static void checkKind(Criterion c, Series s) {
    switch (s) {
      case CountValue ignored -> {
        if (c.noisy()) {
          throw new IllegalArgumentException(
              c.id()
                  + " is a noisy criterion: it needs a fork series or a projection, not a count");
        }
      }
      case ForkValues f -> checkEstimated(c, "a fork series", f.unit());
      case Projection p -> checkEstimated(c, "a projection", p.unit());
      case MeasuredOnly ignored -> {}
    }
  }

  private static void checkEstimated(Criterion c, String kind, String unit) {
    if (c.minRuns() > 0) {
      throw new IllegalArgumentException(
          c.id() + " is an exact count of valid runs: it cannot take " + kind);
    }
    if (c.direction() != Direction.MEASURED_ONLY && !c.unit().equals(unit)) {
      throw new IllegalArgumentException(
          c.id() + ": the series is in '" + unit + "', the criterion in '" + c.unit() + "'");
    }
  }

  private static CriterionResult evaluateSeries(Criterion c, Series s, NoiseModel noise) {
    checkKind(c, s);
    return switch (s) {
      case ForkValues f -> forks(c, f, noise);
      case CountValue count -> Evaluator.evaluateCount(c, count.observed(), count.validRuns());
      case MeasuredOnly o -> {
        CriterionResult r =
            Evaluator.measuredOnly(c, o.invariantsHold(), o.n(), Math.max(1, c.minRuns()));
        yield withReason(r, r.reason() + "; value " + o.value().toPlainString() + " " + o.unit());
      }
      case Projection p -> projection(c, p, noise);
    };
  }

  private static CriterionResult notRun(Criterion c) {
    String reason =
        "no measurements"
            + c.consequenceIfNotRun().map(consequence -> "; " + consequence).orElse("");
    return new CriterionResult(
        c.id(),
        Verdict.NOT_RUN,
        c.basis(),
        Optional.empty(),
        c.threshold(),
        BigDecimal.ZERO.setScale(SCALE),
        reason);
  }

  private static CriterionResult inconclusive(Criterion c, String reason) {
    return new CriterionResult(
        c.id(),
        Verdict.INCONCLUSIVE,
        c.basis(),
        Optional.empty(),
        c.threshold(),
        BigDecimal.ZERO.setScale(SCALE),
        reason);
  }

  private static CriterionResult withReason(CriterionResult r, String reason) {
    return new CriterionResult(
        r.id(), r.verdict(), r.basis(), r.estimate(), r.threshold(), r.margin(), reason);
  }

  private static CriterionResult withEstimate(CriterionResult r, Estimate e) {
    return new CriterionResult(
        r.id(), r.verdict(), r.basis(), Optional.of(e), r.threshold(), r.margin(), r.reason());
  }

  /** A fork series: the kept forks times the scale, the median with its exact bounds. */
  private static CriterionResult forks(Criterion c, ForkValues f, NoiseModel noise) {
    List<ForkStat> kept = f.forks().stream().filter(x -> !x.discarded()).toList();
    List<ForkStat> discarded = f.forks().stream().filter(ForkStat::discarded).toList();
    String note = discardNote(discarded);
    if (discarded.size() > MAX_DISCARDS) {
      return inconclusive(
          c,
          discarded.size()
              + " forks were discarded, at most "
              + MAX_DISCARDS
              + " are allowed"
              + note);
    }
    if (kept.size() < MIN_KEPT) {
      return inconclusive(
          c, "only " + kept.size() + " forks were kept, " + MIN_KEPT + " are needed" + note);
    }
    long unstable = kept.stream().filter(ForkStat::unstable).count();
    if (BigDecimal.valueOf(unstable)
            .divide(BigDecimal.valueOf(kept.size()), SCALE, RoundingMode.HALF_EVEN)
            .compareTo(MAX_UNSTABLE)
        > 0) {
      return inconclusive(
          c,
          unstable
              + " of "
              + kept.size()
              + " kept forks are unstable (warm-up never settled)"
              + note);
    }
    double[] values = new double[kept.size()];
    for (int i = 0; i < values.length; i++) {
      values[i] = kept.get(i).statistic().multiply(f.scale()).doubleValue();
    }
    Estimate e = Stats.medianWithBounds(values);
    return judge(c, e, noise, MIN_FORKS, MIN_KEPT, note);
  }

  private static String discardNote(List<ForkStat> discarded) {
    if (discarded.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder("; discarded forks: ");
    int shown = Math.min(discarded.size(), 5);
    for (int i = 0; i < shown; i++) {
      ForkStat d = discarded.get(i);
      String reason = d.discardReason();
      if (reason.length() > 80) {
        reason = reason.substring(0, 80) + "...";
      }
      out.append(i == 0 ? "" : ", ").append(d.index()).append(" (").append(reason).append(')');
    }
    if (discarded.size() > shown) {
      out.append(", and ").append(discarded.size() - shown).append(" more");
    }
    return out.toString();
  }

  /** Judges an estimate: compared criteria through the evaluator, informational ones recorded. */
  private static CriterionResult judge(
      Criterion c, Estimate e, NoiseModel noise, int minForks, int minN, String note) {
    CriterionResult r;
    if (c.direction() == Direction.MEASURED_ONLY) {
      r = withEstimate(Evaluator.measuredOnly(c, true, e.n(), minN), e);
    } else {
      r = Evaluator.evaluate(c, e, noise, minForks);
    }
    return note.isEmpty() ? r : withReason(r, r.reason() + note);
  }

  /**
   * The Theil-Sen line of the points, evaluated at the target with the slope and with each bound of
   * the slope (the intercept is the median residual of that slope); the smallest and largest value
   * are the bounds. Each is multiplied by the scale and raised to at least the floor.
   */
  private static CriterionResult projection(Criterion c, Projection p, NoiseModel noise) {
    int n = p.xs().size();
    if (n < MIN_POINTS) {
      return inconclusive(
          c, "only " + n + " points to project from, " + MIN_POINTS + " are needed");
    }
    double[] x = new double[n];
    double[] y = new double[n];
    for (int i = 0; i < n; i++) {
      x[i] = p.xs().get(i).doubleValue();
      y[i] = p.ys().get(i).doubleValue();
    }
    SlopeEstimate slope;
    try {
      slope = Stats.theilSen(x, y);
    } catch (IllegalArgumentException e) {
      return inconclusive(c, "no slope can be fitted: " + e.getMessage());
    }
    double target = p.target().doubleValue();
    double scale = p.scale().doubleValue();
    double atPoint = at(x, y, slope.slope().doubleValue(), target) * scale;
    double atLower = at(x, y, slope.lower().doubleValue(), target) * scale;
    double atUpper = at(x, y, slope.upper().doubleValue(), target) * scale;
    BigDecimal floor = p.floor().setScale(SCALE, RoundingMode.HALF_EVEN);
    BigDecimal point = Stats.decimal(atPoint).max(floor);
    BigDecimal lower = Stats.decimal(Math.min(atPoint, Math.min(atLower, atUpper))).max(floor);
    BigDecimal upper = Stats.decimal(Math.max(atPoint, Math.max(atLower, atUpper))).max(floor);
    Estimate e = new Estimate(point, lower, upper, n, "theil-sen-projection");
    return judge(c, e, noise, MIN_POINTS, MIN_POINTS, "");
  }

  private static double at(double[] x, double[] y, double slope, double target) {
    double[] residuals = new double[x.length];
    for (int i = 0; i < x.length; i++) {
      residuals[i] = y[i] - slope * x[i];
    }
    return Stats.median(residuals) + slope * target;
  }
}
