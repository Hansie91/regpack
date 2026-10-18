package dev.regpack.spikes.l.load;

import static dev.regpack.spikes.l.load.VirtualClock.MILLI;
import static dev.regpack.spikes.l.load.VirtualClock.SECOND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.regpack.bench.data.SplitMix64;
import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.spikes.l.load.BurstRunner.Config;
import dev.regpack.spikes.l.load.BurstRunner.SinkFactory;
import dev.regpack.spikes.l.load.BurstRunner.Stop;
import dev.regpack.spikes.l.load.MicroBatchWriter.Method;
import dev.regpack.spikes.l.load.MicroBatchWriter.Timings;
import dev.regpack.spikes.l.schema.SchemaInstaller.Variant;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongUnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The runner against a scripted sink and a virtual clock: nothing here needs PostgreSQL. 100
 * records a second in batches of 100 is one batch a second.
 */
class BurstRunnerTest {
  private static Config open(Duration duration, long maxRecords) {
    return new Config(duration, maxRecords, 100.0, 100, 1, Method.COPY, Variant.E1_COMPACT, false);
  }

  /** A sink whose service time per batch id is scripted and whose commits take 30 ms. */
  private static SinkFactory scripted(VirtualClock clock, LongUnaryOperator serviceNanos) {
    return writer ->
        (batchId, batch) -> {
          clock.advance(serviceNanos.applyAsLong(batchId));
          return new Timings(10 * MILLI, 20 * MILLI, 5 * MILLI, batch.size());
        };
  }

  private static RunResult run(Config config, VirtualClock clock, SinkFactory sinks) {
    return run(config, clock, sinks, BurstRunner.Monitor.none());
  }

  private static RunResult run(
      Config config, VirtualClock clock, SinkFactory sinks, BurstRunner.Monitor monitor) {
    return BurstRunner.execute(config, sinks, new TradeRecordGen(1), clock, clock, monitor);
  }

  @Test
  void openLoopLatencyCountsQueueingBehindAStall() {
    VirtualClock clock = new VirtualClock();
    // batch 5 stalls for 10 s (a pause on the client, not a slow commit); the others take 100 ms
    RunResult result =
        run(
            open(Duration.ofSeconds(20), Long.MAX_VALUE),
            clock,
            scripted(clock, id -> id == 5 ? 10 * SECOND : 100 * MILLI));

    assertThat(result.stoppedBecause()).isEqualTo(Stop.DURATION);
    assertThat(result.records()).isEqualTo(2_000);
    long[] latency = result.openLoopLatencyNanos();
    assertThat(latency).hasSize(20);
    for (int i = 0; i < 4; i++) {
      assertThat(latency[i]).isCloseTo(100 * MILLI, within(MILLI));
    }
    // the stalled batch arrived at 5 s and finished at 15 s; batch 6 arrived at 6 s and starts at
    // 15 s
    assertThat(latency[4]).isCloseTo(10 * SECOND, within(MILLI));
    assertThat(latency[5]).isCloseTo(9 * SECOND + 100 * MILLI, within(MILLI));
    assertThat(latency[6]).isCloseTo(8 * SECOND + 200 * MILLI, within(MILLI));
    // the commit time of every batch stays short: the stall is queueing, not commit
    for (long commit : result.commitNanosSync()) {
      assertThat(commit).isEqualTo(30 * MILLI);
    }
    // at the arrival of batch 14 ten batches wait: 1,000 records over the 3,470 records/s of the
    // spec
    assertThat(result.peakBacklogSeconds()).isGreaterThanOrEqualTo(1_000 / 3_470.0 - 1e-9);
  }

  @Test
  void aFastServiceIsValidWithoutBacklog() {
    VirtualClock clock = new VirtualClock();
    RunResult result =
        run(
            open(Duration.ofSeconds(120), Long.MAX_VALUE),
            clock,
            scripted(clock, id -> 200 * MILLI));
    assertThat(result.valid()).isTrue();
    assertThat(result.failed()).isFalse();
    assertThat(result.environmentFault()).isFalse();
    assertThat(result.records()).isEqualTo(12_000);
    assertThat(result.scheduledRecords()).isEqualTo(12_000);
    assertThat(result.backlogSlope()).isLessThanOrEqualTo(1.0);
    assertThat(result.queuedAtEnd()).isZero();
    assertThat(result.windowRates()).hasSize(2);
    assertThat(result.windowRates()[0]).isCloseTo(100.0, within(2.0));
    assertThat(result.coveredNanos()).isEqualTo(120 * SECOND);
  }

  @Test
  void aServiceSlowerThanTheArrivalsFailsTheRun() {
    VirtualClock clock = new VirtualClock();
    RunResult result =
        run(open(Duration.ofSeconds(60), Long.MAX_VALUE), clock, scripted(clock, id -> 3 * SECOND));
    assertThat(result.stoppedBecause()).isEqualTo(Stop.DURATION);
    assertThat(result.valid()).isFalse();
    assertThat(result.failed()).isTrue();
    assertThat(result.environmentFault()).isFalse();
    assertThat(result.backlogSlope()).isGreaterThan(1.0);
    assertThat(result.queuedAtEnd()).isGreaterThan(4);
    assertThat(result.reasons()).isNotEmpty();
  }

  /** One batch a second for 60 s, but every batch takes 5 s: the service sustains a fifth. */
  private static void assertFailedBecauseItFellBehind(RunResult result) {
    assertThat(result.stoppedBecause()).isEqualTo(Stop.DURATION);
    assertThat(result.valid()).isFalse();
    assertThat(result.failed()).isTrue();
    assertThat(result.environmentFault()).isFalse();
    assertThat(result.queuedAtEnd()).isGreaterThan(4);
    assertThat(result.reasons()).isNotEmpty();
  }

  @Test
  void aHopelesslyBehindRunFailsInsteadOfHanging() {
    VirtualClock clock = new VirtualClock();
    // the writer abandons the run once it is 120 s behind the last arrival; the producer, blocked
    // on
    // a full queue, must then give up too (an earlier version waited for ever)
    RunResult result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () ->
                run(
                    open(Duration.ofSeconds(60), Long.MAX_VALUE),
                    clock,
                    scripted(clock, id -> 5 * SECOND)));
    assertFailedBecauseItFellBehind(result);
  }

  @Test
  void aHopelesslyBehindRunWithFourWritersFailsInsteadOfHanging() {
    VirtualClock clock = new VirtualClock();
    Config config =
        new Config(
            Duration.ofSeconds(60),
            Long.MAX_VALUE,
            100.0,
            100,
            4,
            Method.COPY,
            Variant.E1_COMPACT,
            false);
    RunResult result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10), () -> run(config, clock, scripted(clock, id -> 5 * SECOND)));
    assertFailedBecauseItFellBehind(result);
  }

  @Test
  void aWriterThatDiesWithAnErrorDoesNotHangTheRun() {
    VirtualClock clock = new VirtualClock();
    // an Error is not caught by the writer: the thread ends without ending the run, and the
    // producer must notice that no writer is left to take its batches
    SinkFactory sinks =
        writer ->
            (batchId, batch) -> {
              clock.advance(10 * MILLI);
              if (batchId == 3) {
                throw new AssertionError("fatal at batch 3");
              }
              return new Timings(MILLI, MILLI, MILLI, batch.size());
            };
    RunResult result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () -> run(open(Duration.ofSeconds(120), Long.MAX_VALUE), clock, sinks));
    assertThat(result.valid()).isFalse();
    assertThat(result.failed()).isTrue();
    assertThat(result.records()).isLessThan(1_000);
  }

  /** A monitor with both flags set and both reasons, as the sampler gives them. */
  private static BurstRunner.Monitor bothFlags(AtomicBoolean armed) {
    return new BurstRunner.Monitor() {
      @Override
      public boolean invalid() {
        return armed.get();
      }

      @Override
      public boolean diskLow() {
        return armed.get();
      }

      @Override
      public List<String> reasons() {
        return List.of("NO_SYNC_STANDBY at 3 s", "DISK_LOW at 3 s: 1000 bytes");
      }
    };
  }

  @Test
  void aViolatedPreconditionIsNotHiddenByALowDiskWhileTheRunIsOn() {
    VirtualClock clock = new VirtualClock();
    RunResult result =
        run(
            open(Duration.ofSeconds(60), Long.MAX_VALUE),
            clock,
            scripted(clock, id -> 10 * MILLI),
            bothFlags(new AtomicBoolean(true)));
    assertThat(result.stoppedBecause()).isEqualTo(Stop.SYNC_PRECONDITION);
    assertThat(result.failed()).isTrue();
    assertThat(result.environmentFault()).isFalse();
    assertThat(String.join(" ", result.reasons()))
        .contains("NO_SYNC_STANDBY")
        .contains("the disk also ran low");
  }

  @Test
  void aViolatedPreconditionIsNotHiddenByALowDiskThatAppearsAtTheEnd() {
    VirtualClock clock = new VirtualClock();
    AtomicBoolean armed = new AtomicBoolean();
    // the flags turn on while the last batch is written: the run was covered, then both are seen
    SinkFactory sinks =
        writer ->
            (batchId, batch) -> {
              clock.advance(10 * MILLI);
              if (batchId == 20) {
                armed.set(true);
              }
              return new Timings(MILLI, MILLI, MILLI, batch.size());
            };
    RunResult result =
        run(open(Duration.ofSeconds(20), Long.MAX_VALUE), clock, sinks, bothFlags(armed));
    assertThat(result.stoppedBecause()).isEqualTo(Stop.SYNC_PRECONDITION);
    assertThat(result.failed()).isTrue();
    assertThat(String.join(" ", result.reasons()))
        .contains("NO_SYNC_STANDBY")
        .contains("the disk also ran low");
  }

  @Test
  void maxRecordsStopBelowMinimumDurationIsNotValid() {
    VirtualClock clock = new VirtualClock();
    // 20,000 records are 200 s of load: below the 480 s that make a run comparable
    RunResult shortRun =
        run(open(Duration.ofSeconds(1_000), 20_000), clock, scripted(clock, id -> 10 * MILLI));
    assertThat(shortRun.stoppedBecause()).isEqualTo(Stop.MAX_RECORDS);
    assertThat(shortRun.valid()).isFalse();
    assertThat(shortRun.failed()).isFalse();
    assertThat(shortRun.environmentFault()).isTrue();
    assertThat(shortRun.coveredNanos()).isEqualTo(200 * SECOND);
    assertThat(shortRun.records()).isEqualTo(20_000);

    VirtualClock clock2 = new VirtualClock();
    RunResult longRun =
        run(open(Duration.ofSeconds(1_000), 60_000), clock2, scripted(clock2, id -> 10 * MILLI));
    assertThat(longRun.stoppedBecause()).isEqualTo(Stop.MAX_RECORDS);
    assertThat(longRun.valid()).isTrue();
    assertThat(longRun.coveredNanos()).isEqualTo(600 * SECOND);
  }

  @Test
  void aViolatedSyncPreconditionStopsAndFailsTheRun() {
    VirtualClock clock = new VirtualClock();
    AtomicInteger polls = new AtomicInteger();
    BurstRunner.Monitor monitor =
        new BurstRunner.Monitor() {
          @Override
          public boolean invalid() {
            return polls.incrementAndGet() > 3;
          }

          @Override
          public boolean diskLow() {
            return false;
          }
        };
    RunResult result =
        run(
            open(Duration.ofSeconds(60), Long.MAX_VALUE),
            clock,
            scripted(clock, id -> 10 * MILLI),
            monitor);
    assertThat(result.stoppedBecause()).isEqualTo(Stop.SYNC_PRECONDITION);
    assertThat(result.valid()).isFalse();
    assertThat(result.failed()).isTrue();
    assertThat(result.records()).isLessThan(6_000);
  }

  @Test
  void aLowDiskIsAnEnvironmentFaultNeitherFailedNorValid() {
    VirtualClock clock = new VirtualClock();
    BurstRunner.Monitor monitor =
        new BurstRunner.Monitor() {
          @Override
          public boolean invalid() {
            return false;
          }

          @Override
          public boolean diskLow() {
            return true;
          }
        };
    RunResult result =
        run(
            open(Duration.ofSeconds(60), Long.MAX_VALUE),
            clock,
            scripted(clock, id -> 10 * MILLI),
            monitor);
    assertThat(result.stoppedBecause()).isEqualTo(Stop.DISK);
    assertThat(result.valid()).isFalse();
    assertThat(result.failed()).isFalse();
    assertThat(result.environmentFault()).isTrue();
  }

  @Test
  void aWriterErrorIsAFailedRunWithItsMessage() {
    VirtualClock clock = new VirtualClock();
    SinkFactory sinks =
        writer ->
            (batchId, batch) -> {
              clock.advance(10 * MILLI);
              if (batchId == 3) {
                throw new IllegalStateException("boom at batch 3");
              }
              return new Timings(MILLI, MILLI, MILLI, batch.size());
            };
    RunResult result = run(open(Duration.ofSeconds(30), Long.MAX_VALUE), clock, sinks);
    assertThat(result.stoppedBecause()).isEqualTo(Stop.ERROR);
    assertThat(result.valid()).isFalse();
    assertThat(result.failed()).isTrue();
    assertThat(String.join(" ", result.reasons())).contains("boom at batch 3");
  }

  @Test
  void closedLoopRunsAsFastAsTheSinkAllowsAndReportsWindowRates() {
    VirtualClock clock = new VirtualClock();
    Config config =
        new Config(
            Duration.ofSeconds(130),
            Long.MAX_VALUE,
            0.0,
            100,
            1,
            Method.COPY,
            Variant.E1_COMPACT,
            true);
    RunResult result = run(config, clock, scripted(clock, id -> 500 * MILLI));
    assertThat(result.stoppedBecause()).isEqualTo(Stop.DURATION);
    assertThat(result.valid()).isTrue();
    assertThat(result.windowRates()).hasSize(2);
    for (double rate : result.windowRates()) {
      assertThat(rate).isBetween(190.0, 201.0);
    }
    assertThat(result.openLoopLatencyNanos()).isEmpty();
  }

  private static final LocalDate DAY = LocalDate.of(2026, 10, 9);

  private static List<LedgerLoadModel.Candidate> someCandidates() {
    return LedgerLoadModel.nextBatch(
        new LedgerLoadModel.Params(10, 0.5, 1.5, 0.02, 1),
        new TradeRecordGen(1),
        new SplitMix64(1),
        new ArrayList<>());
  }

  @Test
  void aWriterThatCannotBeBuiltDoesNotLeakItsConnection() {
    TrackedConnection tracked = new TrackedConnection("setAutoCommit");
    SinkFactory sinks =
        BurstRunner.pgSinks(tracked::connection, open(Duration.ofSeconds(1), Long.MAX_VALUE), DAY);
    assertThatThrownBy(() -> sinks.open(0))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("setAutoCommit");
    assertThat(tracked.closes()).isEqualTo(1);
  }

  @Test
  void closingASinkClosesItsConnection() throws Exception {
    TrackedConnection tracked = new TrackedConnection();
    SinkFactory sinks =
        BurstRunner.pgSinks(tracked::connection, open(Duration.ofSeconds(1), Long.MAX_VALUE), DAY);
    try (BurstRunner.BatchSink sink = sinks.open(0)) {
      assertThat(sink).isNotNull();
      assertThat(tracked.closed()).isFalse();
    }
    assertThat(tracked.closes()).isEqualTo(1);
  }

  @Test
  void aBatchOfAnotherDayThanTheInstalledPartitionIsRefusedBeforePostgreSql() throws Exception {
    TrackedConnection tracked = new TrackedConnection();
    SinkFactory sinks =
        BurstRunner.pgSinks(
            tracked::connection,
            open(Duration.ofSeconds(1), Long.MAX_VALUE),
            LocalDate.of(2000, 1, 1));
    try (BurstRunner.BatchSink sink = sinks.open(0)) {
      assertThatThrownBy(() -> sink.write(1, someCandidates()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("2026-10-09")
          .hasMessageContaining("no partition");
    }
  }

  @Test
  void theConfigIsValidated() {
    assertThatThrownBy(
            () ->
                new Config(
                    Duration.ofSeconds(1), 10, 0.0, 100, 1, Method.COPY, Variant.E1_COMPACT, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Config(
                    Duration.ofSeconds(1), 10, 100.0, 0, 1, Method.COPY, Variant.E1_COMPACT, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Config(
                    Duration.ofSeconds(1),
                    10,
                    100.0,
                    100,
                    0,
                    Method.COPY,
                    Variant.E1_COMPACT,
                    false))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
