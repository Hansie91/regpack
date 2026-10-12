package dev.regpack.bench.consolidate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The partial latency budget of spec 5.6: the nine stage points give eight intervals, and the P1a
 * spikes measure four of them. An interval whose decision key is absent or {@code NOT_MEASURED} is
 * pending, never zero; the four intervals that no P1a spike measures are pending with their owner.
 * The verdict is always {@link Verdict#PARTIAL}: while a pending interval exists the budget cannot
 * be said to fit, and the measured sum against the margin is printed as information only.
 *
 * <p>Two rules come from the review of the reports. {@code ledger.ingestCommitP99Ms} and {@code
 * ledger.commitP99Ms} are the same measurement (the two synchronous transactions of a micro-batch
 * together), so both intervals show it and the sum counts it once. The enriched-to-decided interval
 * uses the unloaded lower bound of the plan formula and prints the loaded figure beside it.
 */
public final class LatencyBudget {
  /** The spike ids whose decisions the budget reads. */
  public static final String SP_R = "SP-R";

  /** The ledger spike. */
  public static final String SP_L = "SP-L";

  /** The XML spike. */
  public static final String SP_X = "SP-X";

  /** The micro-batch size of the load model until the owner adopts another (spec 7.8). */
  public static final int DEFAULT_MICRO_BATCH_N = 5000;

  /** The threads of the reference measurement (four vCPUs busy). */
  public static final int DEFAULT_THREADS = 4;

  /** The REPORT share and legs per candidate of the SP-L load model. */
  static final double REPORT_SHARE = 0.5;

  static final double LEGS = 1.5;

  /**
   * What the SP-X report gives for a file cycle with four lanes busy: the factor is derived from
   * two figures of the report, it is not stated there.
   */
  static final String LOADED_ASSEMBLE_REMARK =
      "the stage needs about 1.9 times as long with four lanes busy (derived from the SP-X report:"
          + " about 1.3 s against about 0.7 s for a file of 10,000 records)";

  /**
   * What the ledger commit p99 is in the SP-L report: one pooled figure over the burst runs, and
   * the criterion L-2 on this quantity is INCONCLUSIVE. It is printed in every interval that shows
   * {@code ledger.ingestCommitP99Ms} or {@code ledger.commitP99Ms}, which are this figure.
   */
  static final String POOLED_BURST_CLAUSE =
      "pooled over all batches of the cache-hot runs under the burst (SP-L runs A1 and A2);"
          + " L-2 on this quantity is INCONCLUSIVE (see results.md)";

  private static final String ASSEMBLE_PREFIX = "assemble.latencyMs.";

  private LatencyBudget() {}

  /** What an interval rests on. */
  public enum Basis {
    MEASURED,
    DERIVED,
    PENDING
  }

  /** The only verdict P1a can give. */
  public enum Verdict {
    PARTIAL
  }

  /**
   * One interval between two stage points.
   *
   * @param from the earlier stage point
   * @param to the later stage point
   * @param composition what the figure is made of, with its source keys
   * @param p99Millis the figure in milliseconds; empty exactly when the interval is pending
   * @param basis MEASURED, DERIVED or PENDING
   * @param owner the spike or plan that measures it
   */
  public record Interval(
      String from,
      String to,
      String composition,
      Optional<Double> p99Millis,
      Basis basis,
      String owner) {
    public Interval {
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
      Objects.requireNonNull(composition, "composition");
      Objects.requireNonNull(p99Millis, "p99Millis");
      Objects.requireNonNull(basis, "basis");
      Objects.requireNonNull(owner, "owner");
      if (owner.isBlank()) {
        throw new IllegalArgumentException("an interval names its owner");
      }
      if (p99Millis.isPresent() == (basis == Basis.PENDING)) {
        throw new IllegalArgumentException("a pending interval has no figure and only it");
      }
    }
  }

  /**
   * The assembled budget.
   *
   * @param intervals the eight intervals in order
   * @param measuredP99SumMillis the sum of the intervals that have a figure, a shared measurement
   *     counted once
   * @param marginMillis the available margin in milliseconds
   * @param pending the pending intervals
   * @param verdict always PARTIAL
   */
  public record Result(
      List<Interval> intervals,
      double measuredP99SumMillis,
      double marginMillis,
      List<Interval> pending,
      Verdict verdict) {
    public Result {
      intervals = List.copyOf(intervals);
      pending = List.copyOf(pending);
      Objects.requireNonNull(verdict, "verdict");
    }
  }

  /**
   * Assembles the budget from the decisions of the spikes.
   *
   * @param decisionsBySpike the {@code decisions} object of each spike id (SP-R, SP-L, SP-X)
   * @param microBatchN records per micro-batch
   * @param burstRecordsPerSecond the burst rate
   * @param marginHours the available margin in hours
   * @param threads the threads of the decide stage
   */
  public static Result assemble(
      Map<String, Map<String, Object>> decisionsBySpike,
      int microBatchN,
      double burstRecordsPerSecond,
      double marginHours,
      int threads) {
    if (microBatchN < 1 || threads < 1 || !(burstRecordsPerSecond > 0) || !(marginHours > 0)) {
      throw new IllegalArgumentException("N, threads, burst and margin must be positive");
    }
    Map<String, Object> sr = decisionsBySpike.getOrDefault(SP_R, Map.of());
    Map<String, Object> sl = decisionsBySpike.getOrDefault(SP_L, Map.of());
    Map<String, Object> sx = decisionsBySpike.getOrDefault(SP_X, Map.of());

    Optional<Double> ingest = number(sl, "ledger.ingestCommitP99Ms");
    Optional<Double> commit = number(sl, "ledger.commitP99Ms");
    // one measurement under two names: shown twice, counted once; unequal values are not shared
    boolean shared = ingest.isPresent() && commit.isPresent() && ingest.get().equals(commit.get());

    List<Interval> intervals = new ArrayList<>();
    intervals.add(arrivalToLanded(microBatchN, burstRecordsPerSecond, ingest, shared));
    intervals.add(
        pending(
            "landed",
            "enriched",
            "enrichment latency, rate limits and breaker: no P1a spike measures it",
            "SP-N (P1b)"));
    intervals.add(enrichedToDecided(sr, microBatchN, threads));
    intervals.add(
        pending(
            "decided",
            "validated",
            "the validation layers of P5: no spike exists for this interval",
            "P5 (validation layers; no spike)"));
    Interval validatedToVersioned = validatedToVersioned(sl, commit, shared);
    intervals.add(validatedToVersioned);
    intervals.add(versionedToAssembled(sx, microBatchN));
    intervals.add(
        pending(
            "assembled",
            "sealed",
            "the sealed-file evidence write: no P1a spike measures it",
            "SP-W (P1b)"));
    intervals.add(
        pending("sealed", "sent", "the SFTP transfer: no P1a spike measures it", "SP-S (P1b)"));

    double sum = 0;
    for (Interval interval : intervals) {
      if (shared && interval.equals(validatedToVersioned)) {
        // the second interval that shows the shared measurement adds nothing: it is skipped, not
        // subtracted afterwards, so that the sum is exactly the sum of the other figures
        continue;
      }
      sum += interval.p99Millis().orElse(0.0);
    }
    List<Interval> pending = intervals.stream().filter(i -> i.basis() == Basis.PENDING).toList();
    return new Result(intervals, sum, marginHours * 3_600_000.0, pending, Verdict.PARTIAL);
  }

  /**
   * The first decision key a budget needs and the summary lacks, as {@code <spike> <key>}; empty
   * when the budget can be assembled. The loaded-figure and listing keys are optional.
   */
  public static Optional<String> firstMissing(Map<String, Map<String, Object>> decisionsBySpike) {
    Map<String, Object> sr = decisionsBySpike.getOrDefault(SP_R, Map.of());
    Map<String, Object> sl = decisionsBySpike.getOrDefault(SP_L, Map.of());
    Map<String, Object> sx = decisionsBySpike.getOrDefault(SP_X, Map.of());
    if (number(sr, "decide.usPerRecordWorst").isEmpty()) {
      return Optional.of(SP_R + " decide.usPerRecordWorst");
    }
    if (number(sl, "ledger.ingestCommitP99Ms").isEmpty()) {
      return Optional.of(SP_L + " ledger.ingestCommitP99Ms");
    }
    if (number(sl, "ledger.commitP99Ms").isEmpty()) {
      return Optional.of(SP_L + " ledger.commitP99Ms");
    }
    if (assembleSizes(sx).isEmpty()) {
      return Optional.of(SP_X + " " + ASSEMBLE_PREFIX + "<size>");
    }
    return Optional.empty();
  }

  // ---------------------------------------------------------------- the four measured intervals

  private static Interval arrivalToLanded(
      int n, double burst, Optional<Double> ingest, boolean shared) {
    if (ingest.isEmpty()) {
      return pending(
          "arrival",
          "landed",
          "ledger.ingestCommitP99Ms is absent or NOT_MEASURED in the SP-L summary",
          "SP-L");
    }
    double fillMillis = n / burst * 1000.0;
    String text =
        "batch fill N / burst = "
            + n
            + " / "
            + plain(burst)
            + " records/s = "
            + ms(fillMillis)
            + " ms (DERIVED) + ledger.ingestCommitP99Ms "
            + ms(ingest.get())
            + " ms (SP-L, MEASURED p99 of the two synchronous transactions of a micro-batch, "
            + POOLED_BURST_CLAUSE
            + (shared
                ? "; the same measurement as ledger.commitP99Ms in validated to versioned,"
                    + " a shared measurement counted once in the sum"
                : "")
            + "); the evidence PUT of the landing batch is not included: pending SP-W";
    return new Interval(
        "arrival",
        "landed",
        text,
        Optional.of(fillMillis + ingest.get()),
        Basis.MEASURED,
        "SP-L (evidence PUT pending SP-W)");
  }

  private static Interval enrichedToDecided(Map<String, Object> sr, int n, int threads) {
    Optional<Double> usPerRecord = number(sr, "decide.usPerRecordWorst");
    if (usPerRecord.isEmpty()) {
      return pending(
          "enriched",
          "decided",
          "decide.usPerRecordWorst is absent or NOT_MEASURED in the SP-R summary",
          SP_R);
    }
    double lowerBound = n * usPerRecord.get() / threads / 1000.0;
    StringBuilder text = new StringBuilder();
    text.append("N x decide.usPerRecordWorst / threads = ")
        .append(n)
        .append(" x ")
        .append(micro(usPerRecord.get()))
        .append(" us / ")
        .append(threads)
        .append(" = ")
        .append(ms(lowerBound))
        .append(" ms (DERIVED, SP-R worst-case shape, unloaded lower bound: interpreter evaluation")
        .append(" only, without path encoding, the input_digest SHA-256 and decision-record")
        .append(" assembly)");
    Optional<Double> t4 = loadedRate(sr);
    if (t4.isPresent()) {
      double loaded = n / (threads * t4.get()) * 1000.0;
      text.append("; loaded figure N / (threads x executorGateReadings.t4S2) = ")
          .append(n)
          .append(" / (")
          .append(threads)
          .append(" x ")
          .append(plain(t4.get()))
          .append(" records/s) = ")
          .append(ms(loaded))
          .append(" ms");
    } else {
      text.append("; the loaded figure is not available (executorGateReadings.t4S2 is absent)");
    }
    return new Interval(
        "enriched", "decided", text.toString(), Optional.of(lowerBound), Basis.DERIVED, SP_R);
  }

  private static Interval validatedToVersioned(
      Map<String, Object> sl, Optional<Double> commit, boolean shared) {
    if (commit.isEmpty()) {
      return pending(
          "validated",
          "versioned",
          "ledger.commitP99Ms is absent or NOT_MEASURED in the SP-L summary",
          SP_L);
    }
    StringBuilder text = new StringBuilder();
    text.append("ledger.commitP99Ms ").append(ms(commit.get())).append(" ms (SP-L, MEASURED p99, ");
    text.append(POOLED_BURST_CLAUSE);
    if (shared) {
      text.append("; the same measurement as ledger.ingestCommitP99Ms in arrival to landed,");
      text.append(" a shared measurement counted once in the sum");
    }
    text.append("); beside it ")
        .append(listing(sl, "ledger.peakBacklogSeconds", "s"))
        .append(" and ")
        .append(listing(sl, "ledger.openLoopP99Ms", "ms"))
        .append(" (measured only)");
    return new Interval("validated", "versioned", text.toString(), commit, Basis.MEASURED, SP_L);
  }

  private static Interval versionedToAssembled(Map<String, Object> sx, int n) {
    List<Integer> sizes = assembleSizes(sx);
    if (sizes.isEmpty()) {
      return pending(
          "versioned", "assembled", "no assemble.latencyMs.<size> in the SP-X summary", SP_X);
    }
    double target = n * REPORT_SHARE * LEGS;
    int best = sizes.get(0);
    for (int size : sizes) {
      if (Math.abs(size - target) < Math.abs(best - target)) {
        best = size;
      }
    }
    double value = number(sx, ASSEMBLE_PREFIX + best).orElseThrow();
    String text =
        ASSEMBLE_PREFIX
            + best
            + " "
            + ms(value)
            + " ms (SP-X, MEASURED median of the stage for a file of "
            + best
            + " records, the size nearest N x REPORT share x legs = "
            + n
            + " x "
            + plain(REPORT_SHARE)
            + " x "
            + plain(LEGS)
            + " = "
            + plain(target)
            + ", one thread on an idle runner, not a p99); "
            + LOADED_ASSEMBLE_REMARK;
    return new Interval("versioned", "assembled", text, Optional.of(value), Basis.MEASURED, SP_X);
  }

  private static Interval pending(String from, String to, String why, String owner) {
    return new Interval(from, to, why, Optional.empty(), Basis.PENDING, owner);
  }

  // ---------------------------------------------------------------- reading the decisions

  /** A finite positive number under the key; a string such as NOT_MEASURED counts as absent. */
  private static Optional<Double> number(Map<String, Object> decisions, String key) {
    Object value = decisions.get(key);
    if (value instanceof Number number) {
      double d = number.doubleValue();
      if (Double.isFinite(d) && d > 0) {
        return Optional.of(d);
      }
    }
    return Optional.empty();
  }

  private static Optional<Double> loadedRate(Map<String, Object> sr) {
    if (sr.get("executorGateReadings") instanceof Map<?, ?> readings) {
      Object t4 = readings.get("t4S2");
      if (t4 instanceof Number number) {
        double d = number.doubleValue();
        if (Double.isFinite(d) && d > 0) {
          return Optional.of(d);
        }
      }
    }
    return Optional.empty();
  }

  /** The sizes of the plain {@code assemble.latencyMs.<size>} keys (no policy in the key). */
  private static List<Integer> assembleSizes(Map<String, Object> sx) {
    List<Integer> sizes = new ArrayList<>();
    for (String key : sx.keySet().stream().sorted().toList()) {
      if (!key.startsWith(ASSEMBLE_PREFIX)) {
        continue;
      }
      String rest = key.substring(ASSEMBLE_PREFIX.length());
      if (rest.isEmpty()
          || rest.length() > 9
          || !rest.chars().allMatch(c -> c >= '0' && c <= '9')) {
        continue;
      }
      if (number(sx, key).isPresent()) {
        sizes.add(Integer.parseInt(rest));
      }
    }
    sizes.sort(Comparator.naturalOrder());
    return sizes;
  }

  private static String listing(Map<String, Object> sl, String key, String unit) {
    Optional<Double> value = number(sl, key);
    return value.isPresent() ? key + " " + ms(value.get()) + " " + unit : key + " not measured";
  }

  // ---------------------------------------------------------------- text

  private static String ms(double value) {
    return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_EVEN).toPlainString();
  }

  private static String micro(double value) {
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }

  private static String plain(double value) {
    BigDecimal d = BigDecimal.valueOf(value).stripTrailingZeros();
    return d.scale() < 0 ? d.setScale(0).toPlainString() : d.toPlainString();
  }

  /**
   * The budget as Markdown for {@code docs/spikes/latency-budget.md}: text only, no timestamp, LF
   * line ends, so that regenerating it from the same summaries gives the same bytes.
   */
  public static String render(
      Result result,
      int microBatchN,
      double burstRecordsPerSecond,
      double marginHours,
      int threads) {
    StringBuilder out = new StringBuilder();
    out.append("# Partial latency budget (spec 5.6)\n\n");
    out.append("Generated by `bench budget --write` from the committed summaries ");
    out.append("`docs/spikes/SP-R.summary.json`, `SP-L.summary.json` and `SP-X.summary.json`. ");
    out.append("Do not edit by hand.\n\n");
    out.append("verdict: ").append(result.verdict().name()).append("\n\n");
    out.append("The only verdict P1a can give is PARTIAL: ");
    out.append(result.pending().size()).append(" of ").append(result.intervals().size());
    out.append(" intervals are pending, so the budget is not said to fit or to miss the margin. ");
    out.append("The sum below is information.\n\n");
    out.append("## Inputs\n\n");
    out.append("- micro-batch N: ").append(microBatchN);
    out.append(" records (the load-model default, a placeholder of spec 7.8)\n");
    out.append("- burst: ").append(plain(burstRecordsPerSecond)).append(" records/s\n");
    out.append("- decide threads: ").append(threads).append("\n");
    out.append("- margin: ").append(plain(marginHours)).append(" h = ");
    out.append(plain(result.marginMillis())).append(" ms (`availableMarginHours`)\n\n");
    out.append("## Intervals\n\n");
    out.append("| # | From | To | Figure (ms) | Basis | Owner | Composition |\n");
    out.append("|---|---|---|---|---|---|---|\n");
    int index = 1;
    for (Interval i : result.intervals()) {
      out.append("| ").append(index++);
      out.append(" | ").append(i.from());
      out.append(" | ").append(i.to());
      out.append(" | ").append(i.p99Millis().map(LatencyBudget::ms).orElse("-"));
      out.append(" | ").append(i.basis().name());
      out.append(" | ").append(i.owner());
      out.append(" | ").append(i.composition());
      out.append(" |\n");
    }
    out.append("\n## Sum of the measured intervals\n\n");
    out.append("- sum of the intervals that have a figure: ");
    out.append(ms(result.measuredP99SumMillis())).append(" ms\n");
    out.append("- margin: ").append(plain(result.marginMillis())).append(" ms\n");
    out.append("- share of the margin used by the intervals with a figure: ");
    out.append(share(result.measuredP99SumMillis(), result.marginMillis())).append(" %\n\n");
    out.append("A shared measurement is counted once. The sum leaves out the pending intervals, ");
    out.append("the evidence PUT of the landing batch, path encoding, the input digest and ");
    out.append("decision-record assembly, and it mixes p99 figures, a fill time, a lower bound ");
    out.append("and a median, so it is neither a p99 of the whole path nor an upper bound.\n\n");
    out.append("## Pending intervals\n\n");
    out.append("| From | To | Owner | Why |\n");
    out.append("|---|---|---|---|\n");
    for (Interval i : result.pending()) {
      out.append("| ").append(i.from());
      out.append(" | ").append(i.to());
      out.append(" | ").append(i.owner());
      out.append(" | ").append(i.composition());
      out.append(" |\n");
    }
    return out.toString();
  }

  private static String share(double part, double whole) {
    return BigDecimal.valueOf(part)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(whole), 3, RoundingMode.HALF_EVEN)
        .toPlainString();
  }
}
