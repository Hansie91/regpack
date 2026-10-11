package dev.regpack.spikes.l.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.spikes.l.load.BurstRunner;
import dev.regpack.spikes.l.pg.PgFixture;
import dev.regpack.spikes.l.pg.SyncPrecondition;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Runs only in the smoke dispatch: the hot row and the commit matrix against the real fixture. */
@EnabledIfSystemProperty(named = "spike.pg.url", matches = ".+")
class CommitPathIT {
  private static final int ALLOCATIONS = 10_000;
  private static final int WRITERS = 8;

  /** 8 writers allocate 10,000 numbers; returns them in the order they came back. */
  private static List<Long> allocateConcurrently(PgFixture pg) throws Exception {
    HotRowCounter.install(pg);
    ConcurrentLinkedQueue<Long> results = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
    AtomicInteger remaining = new AtomicInteger(ALLOCATIONS);
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < WRITERS; i++) {
      threads.add(
          Thread.ofPlatform()
              .start(
                  () -> {
                    try (Connection c = pg.primary()) {
                      c.setAutoCommit(false);
                      while (remaining.decrementAndGet() >= 0) {
                        results.add(HotRowCounter.allocate(c));
                      }
                    } catch (Exception | Error e) {
                      errors.add(e);
                    }
                  }));
    }
    for (Thread t : threads) {
      t.join();
    }
    assertThat(errors).isEmpty();
    return new ArrayList<>(results);
  }

  @Test
  void counterNeverSkipsOrRepeatsUnderConcurrency() throws Exception {
    try (PgFixture pg = PgFixture.fromSystemProperty().orElseThrow()) {
      List<Long> results = allocateConcurrently(pg);
      assertThat(results).hasSize(ALLOCATIONS);
      assertThat(results.stream().sorted().toList())
          .containsExactlyElementsOf(
              java.util.stream.LongStream.rangeClosed(1, ALLOCATIONS).boxed().toList());
    }
  }

  /**
   * What this test proves, and what it does not. The row lock of the counter is held until commit,
   * so number n + 1 can only be allocated after the transaction that holds n has finished; the
   * journal event of each transaction takes its identity after the counter update. Ordering the
   * events by sequence number must therefore give consecutive numbers and increasing identities: a
   * decreasing identity would mean that two transactions overlapped. That shows the row lock
   * serialised the allocations, not the order of the commits themselves: a commit timestamp ({@code
   * track_commit_timestamp}, which the fixture does not enable) would be needed for that. The SP-L
   * report records this as a limit of the check.
   */
  @Test
  void seqIsConsecutiveAndEventIdsFollowSeq() throws Exception {
    try (PgFixture pg = PgFixture.fromSystemProperty().orElseThrow()) {
      allocateConcurrently(pg);
      try (Connection c = pg.primary();
          Statement st = c.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT seq, event_id FROM \""
                      + HotRowCounter.SCHEMA
                      + "\".delivery_event ORDER BY seq")) {
        long previousSeq = 0;
        long previousEvent = Long.MIN_VALUE;
        while (rs.next()) {
          assertThat(rs.getLong(1)).isEqualTo(previousSeq + 1);
          assertThat(rs.getLong(2)).isGreaterThan(previousEvent);
          previousSeq = rs.getLong(1);
          previousEvent = rs.getLong(2);
        }
        assertThat(previousSeq).isEqualTo(ALLOCATIONS);
      }
    }
  }

  @Test
  void everyPointRunsWithTheSyncStandbyLive() throws Exception {
    try (PgFixture pg = PgFixture.fromSystemProperty().orElseThrow()) {
      assertThat(SyncPrecondition.violations(pg.syncSamples())).isEmpty();
      // a cap of 5,000 records ends each cell after ten batches of 500: the capped cell is valid
      List<CommitMatrix.Cell> cells =
          CommitMatrix.run(pg, new int[] {500}, new int[] {1}, Duration.ofSeconds(30), 5_000L);
      assertThat(cells).hasSize(2); // COPY and BATCHED_INSERT
      for (CommitMatrix.Cell cell : cells) {
        assertThat(cell.valid()).as("the standby precondition held in %s", cell).isTrue();
        assertThat(cell.maxRecords()).isEqualTo(5_000L);
        assertThat(cell.stoppedBecause()).isEqualTo(BurstRunner.Stop.MAX_RECORDS);
        assertThat(cell.batches()).isGreaterThanOrEqualTo(10);
        assertThat(cell.p99CommitNanos()).isPositive();
      }
      assertThat(SyncPrecondition.violations(pg.syncSamples())).isEmpty();
    }
  }

  /** The hot row is a point of L-11 and carries the same precondition (RF14, L-13). */
  @Test
  void theHotRowRunsWithTheSyncStandbyLive() throws Exception {
    try (PgFixture pg = PgFixture.fromSystemProperty().orElseThrow()) {
      assertThat(SyncPrecondition.violations(pg.syncSamples())).isEmpty();
      // two writer counts of 2 s and a series of one 10 s window
      HotRowCounter.Result result =
          HotRowCounter.run(pg, new int[] {1, 4}, Duration.ofSeconds(2), 1);
      assertThat(result.points()).hasSize(2);
      assertThat(result.windowRates()).hasSize(1);
      assertThat(result.windowRates()[0]).isPositive();
      assertThat(result.violations()).as("the standby precondition held").isEmpty();
      assertThat(SyncPrecondition.violations(pg.syncSamples())).isEmpty();
    }
  }
}
