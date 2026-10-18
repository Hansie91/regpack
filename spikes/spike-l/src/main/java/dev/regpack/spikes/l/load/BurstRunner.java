package dev.regpack.spikes.l.load;

import dev.regpack.bench.data.SplitMix64;
import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.bench.proto.NanoClock;
import dev.regpack.bench.stats.Stats;
import dev.regpack.spikes.l.load.LedgerLoadModel.Candidate;
import dev.regpack.spikes.l.load.LedgerLoadModel.Params;
import dev.regpack.spikes.l.load.MicroBatchWriter.Method;
import dev.regpack.spikes.l.load.MicroBatchWriter.Timings;
import dev.regpack.spikes.l.pg.PgFixture;
import dev.regpack.spikes.l.schema.SchemaInstaller;
import dev.regpack.spikes.l.schema.SchemaInstaller.Variant;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The burst runner of SP-L. A producer thread generates micro-batches ahead of time; {@code
 * writers} threads write them through a {@link BatchSink}.
 *
 * <p><b>Open loop</b> (the sustain test, L-1, L-2, L-4, L-9): batch {@code k} arrives at a fixed
 * instant of the {@link RateController} schedule, whatever the writers are doing; a writer that is
 * free waits for the arrival, one that is behind finds the batch already queued. The latency of a
 * batch is its completion minus its scheduled arrival, so queueing behind a stall counts (a closed
 * loop would hide it: coordinated omission). A run <b>fails</b> when the Theil-Sen slope of its
 * backlog exceeds 1 record per second, when less than 99.9% of the scheduled records are completed
 * one interval after the last arrival, when more than 4 batches are still queued then, or when the
 * sampler marked it invalid (RF14: no live synchronous standby).
 *
 * <p><b>Closed loop</b> (the capacity run C1, L-3): the writers take batches as fast as they can.
 */
public final class BurstRunner {
  /** The schema that {@link #run} installs and leaves in place for the caller to inspect. */
  public static final String SCHEMA = "sp_burst";

  /** Why a run ended. */
  public enum Stop {
    /** The scheduled duration was covered. */
    DURATION,
    /** The synchronous-standby precondition was violated: a failed run. */
    SYNC_PRECONDITION,
    /** The disk ran low: an environment fault, neither failed nor valid. */
    DISK,
    /** The record cap came first: valid only if at least 480 s of load were covered. */
    MAX_RECORDS,
    /** A writer or the producer threw: a failed run. */
    ERROR
  }

  /**
   * The configuration of a run.
   *
   * @param duration scheduled duration of the load (open loop) or time limit (closed loop)
   * @param maxRecords stop after this many scheduled records
   * @param targetRecordsPerSecond the open-loop arrival rate, ignored in a closed loop
   * @param batchSize candidates per micro-batch (N)
   * @param writers writer threads, each with a connection of its own
   * @param method COPY or batched INSERT
   * @param variant the decision encoding of the schema
   * @param closedLoop run as fast as possible instead of on a schedule
   */
  public record Config(
      Duration duration,
      long maxRecords,
      double targetRecordsPerSecond,
      int batchSize,
      int writers,
      Method method,
      Variant variant,
      boolean closedLoop) {
    public Config {
      Objects.requireNonNull(duration, "duration");
      Objects.requireNonNull(method, "method");
      Objects.requireNonNull(variant, "variant");
      if (duration.isZero() || duration.isNegative()) {
        throw new IllegalArgumentException("the duration must be positive: " + duration);
      }
      if (maxRecords <= 0) {
        throw new IllegalArgumentException("maxRecords must be positive: " + maxRecords);
      }
      if (!closedLoop && !(targetRecordsPerSecond > 0.0)) {
        throw new IllegalArgumentException(
            "an open-loop run needs a positive rate: " + targetRecordsPerSecond);
      }
      if (batchSize <= 0) {
        throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
      }
      if (writers <= 0) {
        throw new IllegalArgumentException("writers must be positive: " + writers);
      }
    }
  }

  /** What the run asks of the sampler, so that tests can script it. */
  public interface Monitor {
    /** True once the standby precondition was violated (or could not be verified). */
    boolean invalid();

    /** True once the usable bytes of the disk fell below the minimum. */
    boolean diskLow();

    /** The reasons so far, for the report. */
    default List<String> reasons() {
      return List.of();
    }

    /** A monitor that never objects. */
    static Monitor none() {
      return new Monitor() {
        @Override
        public boolean invalid() {
          return false;
        }

        @Override
        public boolean diskLow() {
          return false;
        }
      };
    }
  }

  /** Waits; tests advance a virtual clock instead. */
  @FunctionalInterface
  public interface Sleeper {
    void sleepNanos(long nanos) throws InterruptedException;

    /** Sleeps on the thread. */
    static Sleeper system() {
      return nanos -> Thread.sleep(Duration.ofNanos(nanos));
    }
  }

  /** Writes one batch and reports its timings; one sink per writer thread. */
  public interface BatchSink extends AutoCloseable {
    Timings write(long batchId, List<Candidate> batch) throws Exception;

    @Override
    default void close() throws SQLException {
      // nothing to release by default
    }
  }

  /** Opens the sink of writer {@code index}. */
  @FunctionalInterface
  public interface SinkFactory {
    BatchSink open(int index) throws Exception;
  }

  static final double REPORT_SHARE = 0.5;
  static final double LEGS_PER_REPORT = 1.5;
  static final double AMEND_RATIO = 0.02;
  static final long LOAD_SEED = 20_261_010L;

  private static final double SPEC_RECORDS_PER_SECOND = 3_470.0;
  private static final long SECOND = 1_000_000_000L;
  private static final long WINDOW_NANOS = 60 * SECOND;
  private static final long DRAIN_NANOS = 120 * SECOND;
  private static final int QUEUE_CAPACITY = 16;
  private static final int EXISTING_CAP = 100_000;
  private static final double MAX_BACKLOG_SLOPE = 1.0;
  private static final double MIN_COMPLETED_SHARE = 0.999;
  private static final int MAX_QUEUED_BATCHES = 4;
  private static final int MAX_SLOPE_POINTS = 200;
  private static final Item POISON = new Item(-1, List.of());

  private BurstRunner() {}

  private record Item(long k, List<Candidate> batch) {}

  private record Done(long k, long endOffset, long latency, long commit) {}

  /**
   * Installs the schema {@link #SCHEMA} (dropping an earlier one), samples the fixture once a
   * second and runs.
   */
  public static RunResult run(PgFixture pg, Config config, TradeRecordGen gen, NanoClock clock)
      throws SQLException {
    // the one partition that is installed: the generator draws every trade on this day, and a
    // batch on another day is refused by the sinks before it reaches PostgreSQL
    LocalDate day = new TradeRecordGen(1).next().tradeDate();
    try (Connection c = pg.primary()) {
      SchemaInstaller.drop(c, SCHEMA);
      SchemaInstaller.install(c, SCHEMA, config.variant(), day, 1);
    }
    // the sampler's connection is closed with the run: a fixture does not collect one per run
    try (BurstSampler.Source source = BurstSampler.pgSource(pg, BurstSampler.dataDisk())) {
      BurstSampler sampler = new BurstSampler(source, clock);
      Thread sampling = Thread.ofPlatform().daemon().name("burst-sampler").start(sampler);
      try {
        return execute(
            config, pgSinks(pg::primary, config, day), gen, clock, Sleeper.system(), sampler);
      } finally {
        sampler.stop();
        try {
          sampling.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  /**
   * Runs the load into a schema that already holds the tables of the ledger sketch with a partition
   * of {@code decision} and {@code ingest} for {@code day}, the day on which the generator draws
   * every trade; nothing is installed and nothing is sampled. The restore study uses it to ingest
   * the gap after a backup: that run has no synchronous standby on purpose, so the caller passes
   * the monitor (usually {@link Monitor#none()}) and the result never counts for L-1 to L-4.
   */
  public static RunResult runInto(
      PgFixture pg,
      String schema,
      LocalDate day,
      Config config,
      TradeRecordGen gen,
      NanoClock clock,
      Monitor monitor) {
    return execute(
        config, pgSinks(pg::primary, schema, config, day), gen, clock, Sleeper.system(), monitor);
  }

  /** Opens a connection for a writer. */
  @FunctionalInterface
  interface Connections {
    Connection open() throws SQLException;
  }

  /**
   * The sinks of {@link #run}: one {@link MicroBatchWriter} per writer on a connection of its own,
   * closed with the sink, or at once when the writer cannot be built.
   *
   * @param day the one day that has a partition; a batch of another day is refused
   */
  static SinkFactory pgSinks(Connections connections, Config config, LocalDate day) {
    return pgSinks(connections, SCHEMA, config, day);
  }

  /** As {@link #pgSinks(Connections, Config, LocalDate)}, into a schema of the caller's choice. */
  static SinkFactory pgSinks(Connections connections, String schema, Config config, LocalDate day) {
    return index -> {
      Connection c = connections.open();
      MicroBatchWriter writer;
      try {
        writer = new MicroBatchWriter(c, schema, config.method(), config.variant());
      } catch (SQLException | RuntimeException e) {
        try {
          c.close();
        } catch (SQLException closing) {
          e.addSuppressed(closing);
        }
        throw e;
      }
      return new BatchSink() {
        @Override
        public Timings write(long batchId, List<Candidate> batch) throws SQLException {
          for (Candidate candidate : batch) {
            if (!day.equals(candidate.record().tradeDate())) {
              throw new IllegalStateException(
                  "trade date "
                      + candidate.record().tradeDate()
                      + " has no partition: only "
                      + day
                      + " is installed");
            }
          }
          return writer.write(batchId, batch);
        }

        @Override
        public void close() throws SQLException {
          c.close();
        }
      };
    };
  }

  /** The run itself, against any sink, clock and monitor. */
  static RunResult execute(
      Config config,
      SinkFactory sinks,
      TradeRecordGen gen,
      NanoClock clock,
      Sleeper sleeper,
      Monitor monitor) {
    return new Run(config, sinks, gen, clock, sleeper, monitor).execute();
  }

  private static final class Run {
    private final Config cfg;
    private final SinkFactory sinks;
    private final TradeRecordGen gen;
    private final NanoClock clock;
    private final Sleeper sleeper;
    private final Monitor monitor;
    private final Params params;
    private final boolean closedLoop;

    /** The schedule of an open loop; in a closed loop it only supplies the start of the run. */
    private final RateController rate;

    private final long start;
    private final long durationNanos;
    private final long scheduledBatches;
    private final Stop plannedStop;
    private final BlockingQueue<Item> queue;
    private final AtomicReference<Stop> early = new AtomicReference<>();
    private final AtomicInteger liveWriters = new AtomicInteger();
    private final Queue<String> errors = new ConcurrentLinkedQueue<>();
    private final Queue<Done> completed = new ConcurrentLinkedQueue<>();
    private final Queue<String> committed = new ConcurrentLinkedQueue<>();
    private final AtomicLong completedRecords = new AtomicLong();

    Run(
        Config cfg,
        SinkFactory sinks,
        TradeRecordGen gen,
        NanoClock clock,
        Sleeper sleeper,
        Monitor monitor) {
      this.cfg = cfg;
      this.sinks = sinks;
      this.gen = gen;
      this.clock = clock;
      this.sleeper = sleeper;
      this.monitor = monitor;
      this.params =
          new Params(cfg.batchSize(), REPORT_SHARE, LEGS_PER_REPORT, AMEND_RATIO, LOAD_SEED);
      this.durationNanos = cfg.duration().toNanos();
      this.closedLoop = cfg.closedLoop();
      this.queue = new ArrayBlockingQueue<>(closedLoop ? 2 * cfg.writers() : QUEUE_CAPACITY);
      if (closedLoop) {
        this.rate = new RateController(1.0, cfg.batchSize(), clock);
        this.start = rate.startNanos();
        this.scheduledBatches = Long.MAX_VALUE;
        this.plannedStop = Stop.DURATION;
      } else {
        this.rate = new RateController(cfg.targetRecordsPerSecond(), cfg.batchSize(), clock);
        this.start = rate.startNanos();
        long byDuration = batchesWithin(rate, durationNanos);
        long byRecords = cfg.maxRecords() / cfg.batchSize();
        if (Math.min(byDuration, byRecords) < 1) {
          throw new IllegalArgumentException(
              "the duration and the record cap must cover at least one batch");
        }
        if (byRecords < byDuration) {
          this.scheduledBatches = byRecords;
          this.plannedStop = Stop.MAX_RECORDS;
        } else {
          this.scheduledBatches = byDuration;
          this.plannedStop = Stop.DURATION;
        }
      }
    }

    private static long batchesWithin(RateController rate, long nanos) {
      // a linear walk from a good estimate is exact and short
      double estimate = nanos / (double) rate.offsetNanos(1);
      long k = Math.max(0L, (long) Math.floor(estimate) - 1);
      while (rate.offsetNanos(k + 1) <= nanos) {
        k++;
      }
      while (k > 0 && rate.offsetNanos(k) > nanos) {
        k--;
      }
      return k;
    }

    RunResult execute() {
      List<Thread> threads = new ArrayList<>();
      liveWriters.set(cfg.writers());
      threads.add(Thread.ofPlatform().daemon().name("burst-producer").start(this::produce));
      for (int i = 0; i < cfg.writers(); i++) {
        int index = i;
        threads.add(
            Thread.ofPlatform().daemon().name("burst-writer-" + i).start(() -> write(index)));
      }
      for (Thread t : threads) {
        try {
          t.join();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          errors.add("interrupted while waiting for the run");
          end(Stop.ERROR);
        }
      }
      return result();
    }

    private boolean ended() {
      return early.get() != null;
    }

    /** The run goes on and at least one writer is left to take batches. */
    private boolean taking() {
      return !ended() && liveWriters.get() > 0;
    }

    private void end(Stop reason) {
      early.compareAndSet(null, reason);
    }

    private void fail(String where, Throwable t) {
      errors.add(where + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
      end(Stop.ERROR);
    }

    private void produce() {
      try {
        List<String> existing = new ArrayList<>();
        SplitMix64 rng = new SplitMix64(LOAD_SEED ^ 0x9E3779B97F4A7C15L);
        for (long k = 1; k <= scheduledBatches && taking(); k++) {
          String id;
          while ((id = committed.poll()) != null) {
            if (existing.size() < EXISTING_CAP) {
              existing.add(id);
            }
          }
          Item item = new Item(k, LedgerLoadModel.nextBatch(params, gen, rng, existing));
          offer(item);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("producer", e);
      } catch (RuntimeException e) {
        fail("producer", e);
      } finally {
        try {
          for (int i = 0; i < cfg.writers(); i++) {
            offer(POISON);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }

    private void offer(Item item) throws InterruptedException {
      while (taking() && !queue.offer(item, 50, TimeUnit.MILLISECONDS)) {
        // the writers are busy: wait for room, until the run ends or no writer is left
      }
    }

    private void write(int index) {
      try {
        writeBatches(index);
      } finally {
        liveWriters.decrementAndGet();
      }
    }

    private void writeBatches(int index) {
      try (BatchSink sink = sinks.open(index)) {
        while (true) {
          Item item = queue.poll(50, TimeUnit.MILLISECONDS);
          if (item == null) {
            if (ended()) {
              return;
            }
            continue;
          }
          if (item == POISON || ended()) {
            return;
          }
          // a violated standby precondition comes first: it fails the run, a low disk only
          // faults the environment and must not hide it (RF14)
          if (monitor.invalid()) {
            end(Stop.SYNC_PRECONDITION);
            return;
          }
          if (monitor.diskLow()) {
            end(Stop.DISK);
            return;
          }
          long arrival = 0;
          if (!closedLoop) {
            arrival = rate.arrivalNanos(item.k());
            waitUntil(arrival);
            if (clock.nanoTime() > start + rate.offsetNanos(scheduledBatches) + DRAIN_NANOS) {
              // hopelessly behind: end the run as a finished one, so that what is left counts as
              // not completed and fails it; the producer and the other writers stop with it
              end(plannedStop);
              return;
            }
          }
          Timings timings = sink.write(item.k(), item.batch());
          long finished = clock.nanoTime();
          completed.add(
              new Done(
                  item.k(),
                  finished - start,
                  closedLoop ? 0 : finished - arrival,
                  timings.ingestCommitNanos() + timings.reportCommitNanos()));
          long total = completedRecords.addAndGet(cfg.batchSize());
          for (Candidate c : item.batch()) {
            if (c.report() && !c.superseding()) {
              committed.add(c.eventIdentity());
            }
          }
          if (closedLoop) {
            if (total >= cfg.maxRecords()) {
              end(Stop.MAX_RECORDS);
            } else if (finished - start >= durationNanos) {
              end(Stop.DURATION);
            }
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("writer " + index, e);
      } catch (Exception e) {
        fail("writer " + index, e);
      }
    }

    private void waitUntil(long arrival) throws InterruptedException {
      while (true) {
        long remaining = arrival - clock.nanoTime();
        if (remaining <= 0) {
          return;
        }
        sleeper.sleepNanos(remaining);
      }
    }

    private RunResult result() {
      List<Done> done = new ArrayList<>(completed);
      done.sort(Comparator.comparingLong(Done::k));
      int n = done.size();
      long[] ends = new long[n];
      long[] commits = new long[n];
      long[] latency = closedLoop ? new long[0] : new long[n];
      long[] sizes = new long[n];
      long lastEnd = 0;
      for (int i = 0; i < n; i++) {
        Done d = done.get(i);
        ends[i] = d.endOffset();
        commits[i] = d.commit();
        sizes[i] = cfg.batchSize();
        if (!closedLoop) {
          latency[i] = d.latency();
        }
        lastEnd = Math.max(lastEnd, d.endOffset());
      }
      long[] sortedEnds = ends.clone();
      Arrays.sort(sortedEnds);

      Stop stop = early.get() != null ? early.get() : plannedStop;
      List<String> reasons = new ArrayList<>(errors);
      // a flag raised after the last batch counts too; a violated precondition (a failed run)
      // wins over a low disk (an environment fault), and the disk is named as well
      if (stop != Stop.ERROR) {
        if (monitor.invalid()) {
          stop = Stop.SYNC_PRECONDITION;
        } else if (monitor.diskLow()) {
          stop = Stop.DISK;
        }
      }
      boolean finished = stop == Stop.DURATION || stop == Stop.MAX_RECORDS;
      switch (stop) {
        case SYNC_PRECONDITION -> {
          reasons.add("the standby precondition was violated: " + monitor.reasons());
          if (monitor.diskLow()) {
            reasons.add("the disk also ran low");
          }
        }
        case DISK -> reasons.add("the disk ran low: " + monitor.reasons());
        case ERROR -> {
          if (reasons.isEmpty()) {
            reasons.add("a writer or the producer failed");
          }
        }
        default -> {
          // the load was covered; the criteria below decide
        }
      }

      long records = (long) n * cfg.batchSize();
      long scheduledRecords = records;
      long covered = lastEnd;
      int queuedAtEnd = 0;
      double slope = 0.0;
      double peak = 0.0;
      if (!closedLoop) {
        scheduledRecords = scheduledBatches * cfg.batchSize();
        if (finished) {
          covered = rate.offsetNanos(scheduledBatches);
          long interval = rate.offsetNanos(1);
          long byEnd = countAtMost(sortedEnds, covered + interval);
          queuedAtEnd = (int) Math.min(Integer.MAX_VALUE, scheduledBatches - byEnd);
          double share = byEnd / (double) scheduledBatches;
          if (share < MIN_COMPLETED_SHARE) {
            reasons.add(
                "only "
                    + byEnd * cfg.batchSize()
                    + " of "
                    + scheduledRecords
                    + " scheduled records were completed one interval after the last arrival");
          }
          if (queuedAtEnd > MAX_QUEUED_BATCHES) {
            reasons.add(queuedAtEnd + " batches were still queued at the end");
          }
          slope = backlogSlope(sortedEnds, covered);
          if (slope > MAX_BACKLOG_SLOPE) {
            reasons.add("the backlog grew by " + slope + " records per second");
          }
          peak = peakBacklogSeconds(sortedEnds);
        }
      }
      if (stop == Stop.MAX_RECORDS && covered < RunResult.MIN_COVERED_NANOS) {
        reasons.add(
            RunResult.CAP_REASON_PREFIX
                + covered / SECOND
                + " s of load; at least "
                + RunResult.MIN_COVERED_NANOS / SECOND
                + " s are needed");
      }
      double[] windows =
          WindowRates.recordsPerSecond(
              ends, sizes, WINDOW_NANOS, WindowRates.fullWindows(covered, WINDOW_NANOS));
      return new RunResult(
          reasons.isEmpty(),
          records,
          commits,
          latency,
          slope,
          peak,
          windows,
          stop,
          scheduledRecords,
          covered,
          queuedAtEnd,
          ends,
          reasons);
    }

    private long completedRecordsAt(long[] sortedEnds, long offset) {
      return countAtMost(sortedEnds, offset) * cfg.batchSize();
    }

    private double backlogSlope(long[] sortedEnds, long covered) {
      long step = Math.max(SECOND, covered / MAX_SLOPE_POINTS);
      List<double[]> points = new ArrayList<>();
      for (long t = step; t <= covered; t += step) {
        long backlog = rate.backlogRecords(start + t, completedRecordsAt(sortedEnds, t));
        points.add(new double[] {t / (double) SECOND, backlog});
      }
      if (points.size() < 3) {
        return 0.0;
      }
      double[] x = new double[points.size()];
      double[] y = new double[points.size()];
      for (int i = 0; i < x.length; i++) {
        x[i] = points.get(i)[0];
        y[i] = points.get(i)[1];
      }
      return Stats.theilSen(x, y).slope().doubleValue();
    }

    private double peakBacklogSeconds(long[] sortedEnds) {
      long peak = 0;
      for (long k = 1; k <= scheduledBatches; k++) {
        long t = rate.offsetNanos(k);
        peak = Math.max(peak, rate.backlogRecords(start + t, completedRecordsAt(sortedEnds, t)));
      }
      return peak / SPEC_RECORDS_PER_SECOND;
    }

    /** The number of values in the sorted array that are at most {@code limit}. */
    private static long countAtMost(long[] sorted, long limit) {
      int low = 0;
      int high = sorted.length;
      while (low < high) {
        int mid = (low + high) >>> 1;
        if (sorted[mid] <= limit) {
          low = mid + 1;
        } else {
          high = mid;
        }
      }
      return low;
    }
  }
}
