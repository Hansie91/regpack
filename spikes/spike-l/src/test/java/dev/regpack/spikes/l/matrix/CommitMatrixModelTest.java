package dev.regpack.spikes.l.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.regpack.spikes.l.load.BurstRunner;
import dev.regpack.spikes.l.load.MicroBatchWriter.Method;
import dev.regpack.spikes.l.load.RunResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommitMatrixModelTest {
  private static CommitMatrix.Cell cell(int n, long meanCommitNanos, boolean valid) {
    return new CommitMatrix.Cell(
        n,
        4,
        Method.COPY,
        valid,
        100,
        meanCommitNanos,
        meanCommitNanos,
        meanCommitNanos,
        1_000,
        CommitMatrix.DEFAULT_MAX_RECORDS_PER_CELL,
        BurstRunner.Stop.DURATION);
  }

  /** A closed-loop cell run of 12 batches that took 5 s and ended for the given reason. */
  private static RunResult run(BurstRunner.Stop stop, boolean valid, List<String> reasons) {
    long[] commits = new long[12];
    Arrays.fill(commits, 30_000_000L);
    return new RunResult(
        valid,
        12 * 500L,
        commits,
        new long[0],
        0.0,
        0.0,
        new double[0],
        stop,
        12 * 500L,
        5_000_000_000L,
        0,
        new long[12],
        reasons);
  }

  private static final List<String> CAPPED =
      List.of(RunResult.CAP_REASON_PREFIX + "5 s of load; at least 480 s are needed");

  /** Commit time 20 ms plus 0.02 ms per record. */
  private static List<CommitMatrix.Cell> cells(int... sizes) {
    List<CommitMatrix.Cell> out = new ArrayList<>();
    for (int n : sizes) {
      out.add(cell(n, 20_000_000L + 20_000L * n, true));
    }
    return out;
  }

  @Test
  void kneeIsTheSmallestNWithFixedOverheadBelowTenPercent() {
    // fixed share: 0.667 at 500, 0.333 at 2,000, 0.167 at 5,000, 0.091 at 10,000, 0.038 at 25,000
    assertThat(CommitMatrix.knee(cells(500, 2_000, 5_000, 10_000, 25_000), 0.10)).hasValue(10_000);
    assertThat(CommitMatrix.knee(cells(25_000, 10_000, 5_000, 2_000, 500), 0.10)).hasValue(10_000);
    assertThat(CommitMatrix.knee(cells(500, 2_000, 5_000, 10_000, 25_000), 0.34)).hasValue(2_000);
  }

  @Test
  void noKneeWhenEverySizeIsAboveTheShare() {
    assertThat(CommitMatrix.knee(cells(500, 2_000, 5_000), 0.10)).isEmpty();
  }

  @Test
  void fewerThanTwoSizesGiveNoKnee() {
    assertThat(CommitMatrix.knee(cells(5_000), 0.10)).isEmpty();
    assertThat(CommitMatrix.knee(List.of(), 0.10)).isEmpty();
  }

  @Test
  void invalidCellsAreIgnored() {
    List<CommitMatrix.Cell> list = new ArrayList<>(cells(500, 2_000, 5_000, 10_000, 25_000));
    // a bogus invalid cell that would move the regression if it counted
    list.add(cell(700, 900_000_000L, false));
    assertThat(CommitMatrix.knee(list, 0.10)).hasValue(10_000);
  }

  @Test
  void aCellThatTheRecordCapEndedIsValidAndRecordsItsCap() {
    CommitMatrix.Cell cell =
        CommitMatrix.cell(
            500, 1, Method.COPY, 6_000L, run(BurstRunner.Stop.MAX_RECORDS, false, CAPPED));
    assertThat(cell.valid()).isTrue();
    assertThat(cell.maxRecords()).isEqualTo(6_000L);
    assertThat(cell.stoppedBecause()).isEqualTo(BurstRunner.Stop.MAX_RECORDS);
    assertThat(cell.batches()).isEqualTo(12);
    assertThat(cell.recordsPerSecond()).isEqualTo(1_200);
  }

  @Test
  void aCapThatCameWithAnotherFaultDoesNotMakeTheCellValid() {
    // another reason next to the cap, or a stop that is not the cap, keeps the cell out of the knee
    List<String> two = new ArrayList<>(CAPPED);
    two.add("writer 0: SQLException: boom");
    assertThat(
            CommitMatrix.cell(
                    500, 1, Method.COPY, 6_000L, run(BurstRunner.Stop.MAX_RECORDS, false, two))
                .valid())
        .isFalse();
    for (BurstRunner.Stop stop :
        List.of(
            BurstRunner.Stop.SYNC_PRECONDITION, BurstRunner.Stop.DISK, BurstRunner.Stop.ERROR)) {
      assertThat(
              CommitMatrix.cell(500, 1, Method.COPY, 6_000L, run(stop, false, List.of("a fault")))
                  .valid())
          .as("stopped because of %s", stop)
          .isFalse();
    }
    assertThat(
            CommitMatrix.cell(
                    500, 1, Method.COPY, 6_000L, run(BurstRunner.Stop.DURATION, true, List.of()))
                .valid())
        .isTrue();
  }

  @Test
  void requiredEventRateFormula() {
    // 3,470 records/s over files of 500 reports is 6.94 file events a second, plus 3 other events
    assertThat(HotRowCounter.requiredEventsPerSecond(3_470, 500, 3)).isCloseTo(9.94, within(1e-9));
  }
}
