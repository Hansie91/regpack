package dev.regpack.spikes.l.matrix;

import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.bench.proto.NanoClock;
import dev.regpack.bench.stats.Stats;
import dev.regpack.spikes.l.load.BurstRunner;
import dev.regpack.spikes.l.load.MicroBatchWriter.Method;
import dev.regpack.spikes.l.load.RunResult;
import dev.regpack.spikes.l.pg.PgFixture;
import dev.regpack.spikes.l.schema.SchemaInstaller.Variant;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.TreeSet;

/**
 * The commit matrix of SP-L: the micro-batch commit time as a function of the batch size N, the
 * number of writers and the loading method, always with the synchronous standby live (a cell whose
 * run is not valid, because the standby precondition failed, is kept but marked and never used for
 * the knee). Each cell is a closed-loop run of {@link BurstRunner}.
 *
 * <p>The knee is the smallest N at which the <b>fixed part</b> of the commit (the intercept of a
 * regression of commit time on N: WAL flush, standby round trip, transaction overhead) is at most a
 * given share of the batch time: beyond it a larger batch buys little, and only adds latency.
 */
public final class CommitMatrix {
  /** The batch sizes of spec 7.8's search. */
  public static final int[] SIZES = {500, 2_000, 5_000, 10_000, 25_000};

  /** The writer counts of the matrix. */
  public static final int[] WRITERS = {1, 4};

  /**
   * One cell of the matrix.
   *
   * @param batchSize N
   * @param writers writer threads
   * @param method COPY or batched INSERT
   * @param valid whether the run counts (no standby violation, no error)
   * @param batches completed batches
   * @param meanCommitNanos mean time of the two synchronous transactions of a batch
   * @param p50CommitNanos median of the same
   * @param p99CommitNanos 99th percentile of the same
   * @param recordsPerSecond completed records per second of the cell
   * @param maxRecords the record cap of the cell, which bounds the disk that the matrix writes
   * @param stoppedBecause why the cell ended: DURATION, or MAX_RECORDS when the cap came first (the
   *     cell is then as short as its cap and is still valid), or a fault
   */
  public record Cell(
      int batchSize,
      int writers,
      Method method,
      boolean valid,
      long batches,
      long meanCommitNanos,
      long p50CommitNanos,
      long p99CommitNanos,
      long recordsPerSecond,
      long maxRecords,
      BurstRunner.Stop stoppedBecause) {}

  /**
   * The record cap of a cell when the caller gives none: 200,000 records are about 300 MB of rows
   * and indexes at the planning figure of 1,500 bytes a candidate, and the matrix has 20 cells
   * whose archived WAL stays on the disk. The runner of L4 passes the cap that its disk plan
   * derives.
   */
  public static final long DEFAULT_MAX_RECORDS_PER_CELL = 200_000L;

  private CommitMatrix() {}

  /**
   * Runs every combination of the methods, writer counts and sizes for {@code perCell} each, on the
   * primary of the fixture (a fresh schema per cell), standby live, each cell capped at {@link
   * #DEFAULT_MAX_RECORDS_PER_CELL} records.
   */
  public static List<Cell> run(PgFixture pg, int[] sizes, int[] writers, Duration perCell)
      throws SQLException {
    return run(pg, sizes, writers, perCell, DEFAULT_MAX_RECORDS_PER_CELL);
  }

  /**
   * As {@link #run(PgFixture, int[], int[], Duration)} with a cap of {@code maxRecordsPerCell}
   * records a cell, so that a closed-loop COPY cannot fill the disk of the runner: a cell that the
   * cap ends is valid, and its record shows the cap and the stop. The cap is raised to two batches
   * for a size that is larger than half of it.
   */
  public static List<Cell> run(
      PgFixture pg, int[] sizes, int[] writers, Duration perCell, long maxRecordsPerCell)
      throws SQLException {
    if (maxRecordsPerCell <= 0) {
      throw new IllegalArgumentException("the cap must be positive: " + maxRecordsPerCell);
    }
    List<Cell> cells = new ArrayList<>();
    NanoClock clock = NanoClock.system();
    for (Method method : Method.values()) {
      for (int w : writers) {
        for (int n : sizes) {
          long cap = Math.max(maxRecordsPerCell, 2L * n);
          BurstRunner.Config config =
              new BurstRunner.Config(perCell, cap, 0.0, n, w, method, Variant.E1_COMPACT, true);
          RunResult result = BurstRunner.run(pg, config, new TradeRecordGen(n * 31L + w), clock);
          cells.add(cell(n, w, method, cap, result));
        }
      }
    }
    return List.copyOf(cells);
  }

  /**
   * One cell from its run. A run that the record cap ended is valid here: the cap is how the matrix
   * keeps its disk use bounded, and the 480 s that a sustain run needs do not apply to a 30 s cell.
   */
  static Cell cell(int n, int writers, Method method, long maxRecords, RunResult result) {
    long[] commits = result.commitNanosSync();
    boolean usable = result.valid() || result.onlyCappedByRecords();
    if (commits.length == 0) {
      return new Cell(
          n, writers, method, false, 0, 0, 0, 0, 0, maxRecords, result.stoppedBecause());
    }
    double[] values = new double[commits.length];
    double sum = 0;
    for (int i = 0; i < values.length; i++) {
      values[i] = commits[i];
      sum += commits[i];
    }
    double seconds = Math.max(1, result.coveredNanos()) / 1e9;
    return new Cell(
        n,
        writers,
        method,
        usable,
        commits.length,
        Math.round(sum / values.length),
        Math.round(Stats.median(values)),
        Math.round(Stats.percentile(values, 99)),
        Math.round(result.records() / seconds),
        maxRecords,
        result.stoppedBecause());
  }

  /**
   * The smallest N whose fixed part is at most {@code maxFixedShare} of its batch time, from the
   * least-squares line of the mean commit time against N over the valid cells; empty when fewer
   * than two sizes are measured, when commit time does not grow with N, or when no size gets there.
   * Pass the cells of one method and one writer count.
   */
  public static OptionalInt knee(List<Cell> cells, double maxFixedShare) {
    List<Cell> valid = cells.stream().filter(Cell::valid).toList();
    TreeSet<Integer> sizes = new TreeSet<>();
    for (Cell c : valid) {
      sizes.add(c.batchSize());
    }
    if (sizes.size() < 2) {
      return OptionalInt.empty();
    }
    double meanX = 0;
    double meanY = 0;
    for (Cell c : valid) {
      meanX += c.batchSize();
      meanY += c.meanCommitNanos();
    }
    meanX /= valid.size();
    meanY /= valid.size();
    double sxy = 0;
    double sxx = 0;
    for (Cell c : valid) {
      sxy += (c.batchSize() - meanX) * (c.meanCommitNanos() - meanY);
      sxx += (c.batchSize() - meanX) * (c.batchSize() - meanX);
    }
    double slope = sxy / sxx;
    double fixed = meanY - slope * meanX;
    if (slope <= 0) {
      return OptionalInt.empty();
    }
    for (int n : sizes) {
      double perBatch = fixed + slope * n;
      double share = fixed <= 0 ? 0.0 : fixed / perBatch;
      if (share <= maxFixedShare) {
        return OptionalInt.of(n);
      }
    }
    return OptionalInt.empty();
  }
}
