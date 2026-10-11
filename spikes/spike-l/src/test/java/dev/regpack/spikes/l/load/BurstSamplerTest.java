package dev.regpack.spikes.l.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.spikes.l.load.BurstSampler.Reading;
import dev.regpack.spikes.l.load.BurstSampler.Row;
import dev.regpack.spikes.l.load.MicroBatchWriter.Method;
import dev.regpack.spikes.l.load.MicroBatchWriter.Timings;
import dev.regpack.spikes.l.pg.SyncPrecondition.Sample;
import dev.regpack.spikes.l.schema.SchemaInstaller.Variant;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BurstSamplerTest {
  private static final long GIB = 1L << 30;
  private static final List<Sample> LIVE = List.of(new Sample("standby1", "quorum", "streaming"));

  /** A source that returns the scripted readings in order and then the last one again. */
  private static BurstSampler.Source script(Reading... readings) {
    Deque<Reading> queue = new ArrayDeque<>(List.of(readings));
    return () -> queue.size() > 1 ? queue.poll() : queue.peek();
  }

  private static Reading reading(List<Sample> replication, long walBytes, long usable) {
    return new Reading(replication, Map.of("wal_bytes", walBytes), usable);
  }

  @Test
  void aSingleViolatingSampleInvalidatesTheRun() {
    VirtualClock clock = new VirtualClock();
    BurstSampler sampler =
        new BurstSampler(
            script(
                reading(LIVE, 0, 10 * GIB),
                reading(LIVE, 1_000, 10 * GIB),
                reading(List.of(), 2_000, 10 * GIB), // the third sample has no sync standby
                reading(LIVE, 3_000, 10 * GIB)),
            clock);
    sampler.sampleOnce();
    clock.advance(VirtualClock.SECOND);
    sampler.sampleOnce();
    assertThat(sampler.invalid()).isFalse();
    clock.advance(VirtualClock.SECOND);
    sampler.sampleOnce();
    assertThat(sampler.invalid()).isTrue();
    assertThat(sampler.reasons()).anyMatch(r -> r.contains("NO_SYNC_STANDBY"));
    // a later good sample does not clear it: one violating second is enough
    clock.advance(VirtualClock.SECOND);
    sampler.sampleOnce();
    assertThat(sampler.invalid()).isTrue();
    assertThat(sampler.rows()).hasSize(4);
    assertThat(sampler.rows().get(2).violations()).containsExactly("NO_SYNC_STANDBY");
  }

  @Test
  void aStandbyThatIsNotStreamingInvalidatesToo() {
    VirtualClock clock = new VirtualClock();
    BurstSampler sampler =
        new BurstSampler(
            script(reading(List.of(new Sample("standby1", "quorum", "catchup")), 0, 10 * GIB)),
            clock);
    sampler.sampleOnce();
    assertThat(sampler.invalid()).isTrue();
    assertThat(sampler.reasons()).anyMatch(r -> r.contains("STANDBY_NOT_STREAMING"));
  }

  @Test
  void aSourceThatCannotBeReadInvalidatesBecauseTheStandbyCannotBeVerified() {
    VirtualClock clock = new VirtualClock();
    BurstSampler sampler =
        new BurstSampler(
            () -> {
              throw new java.io.IOException("connection refused");
            },
            clock);
    sampler.sampleOnce();
    assertThat(sampler.invalid()).isTrue();
    assertThat(sampler.reasons()).anyMatch(r -> r.contains("SAMPLE_FAILED"));
  }

  @Test
  void deltasOfCountersArePerSecond() {
    VirtualClock clock = new VirtualClock();
    BurstSampler sampler =
        new BurstSampler(
            script(
                reading(LIVE, 0, 10 * GIB),
                reading(LIVE, 2_000, 10 * GIB),
                reading(LIVE, 5_000, 10 * GIB)),
            clock);
    sampler.sampleOnce();
    clock.advance(2 * VirtualClock.SECOND);
    sampler.sampleOnce();
    clock.advance(VirtualClock.SECOND);
    sampler.sampleOnce();
    List<Row> rows = sampler.rows();
    assertThat(rows.get(0).perSecond()).isEmpty(); // nothing to subtract from yet
    assertThat(rows.get(1).perSecond()).containsEntry("wal_bytes", 1_000L);
    assertThat(rows.get(2).perSecond()).containsEntry("wal_bytes", 3_000L);
    assertThat(rows.get(2).offsetNanos() - rows.get(0).offsetNanos())
        .isEqualTo(3 * VirtualClock.SECOND);
    assertThat(sampler.invalid()).isFalse();
  }

  @Test
  void diskLowStopsTheRunAsAnEnvironmentFault() {
    VirtualClock clock = new VirtualClock();
    BurstSampler sampler =
        new BurstSampler(
            script(reading(LIVE, 0, 10 * GIB), reading(LIVE, 10, 511L * 1024 * 1024)), clock);
    sampler.sampleOnce();
    assertThat(sampler.diskLow()).isFalse();
    clock.advance(VirtualClock.SECOND);
    sampler.sampleOnce();
    assertThat(sampler.diskLow()).isTrue();
    assertThat(sampler.invalid()).isFalse();

    // the runner turns the flag into DISK: an environment fault, neither failed nor valid
    VirtualClock runClock = new VirtualClock();
    RunResult result =
        BurstRunner.execute(
            new BurstRunner.Config(
                Duration.ofSeconds(30),
                Long.MAX_VALUE,
                100.0,
                100,
                1,
                Method.COPY,
                Variant.E1_COMPACT,
                false),
            writer ->
                (id, batch) -> {
                  runClock.advance(VirtualClock.MILLI);
                  return new Timings(1, 1, 1, batch.size());
                },
            new TradeRecordGen(1),
            runClock,
            runClock,
            sampler);
    assertThat(result.stoppedBecause()).isEqualTo(BurstRunner.Stop.DISK);
    assertThat(result.valid()).isFalse();
    assertThat(result.failed()).isFalse();
  }

  @Test
  void closingThePgSourceClosesItsConnection() throws Exception {
    TrackedConnection tracked = new TrackedConnection();
    try (BurstSampler.Source source =
        BurstSampler.pgSource(tracked.connection(), BurstSampler.dataDisk())) {
      assertThat(source).isNotNull();
      assertThat(tracked.closed()).isFalse();
    }
    assertThat(tracked.closes()).isEqualTo(1);
  }

  @Test
  void aPgSourceThatCannotBeSetUpClosesItsConnection() {
    TrackedConnection tracked = new TrackedConnection("setAutoCommit");
    assertThatThrownBy(() -> BurstSampler.pgSource(tracked.connection(), BurstSampler.dataDisk()))
        .isInstanceOf(SQLException.class);
    assertThat(tracked.closes()).isEqualTo(1);
  }

  @Test
  void exactlyTheMinimumUsableBytesIsStillFine() {
    VirtualClock clock = new VirtualClock();
    BurstSampler sampler = new BurstSampler(script(reading(LIVE, 0, 512L * 1024 * 1024)), clock);
    sampler.sampleOnce();
    assertThat(sampler.diskLow()).isFalse();
  }
}
