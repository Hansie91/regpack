package dev.regpack.spikes.l.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.regpack.bench.proto.NanoClock;
import dev.regpack.spikes.l.load.BurstSampler;
import dev.regpack.spikes.l.load.BurstSampler.Reading;
import dev.regpack.spikes.l.pg.SyncPrecondition.Sample;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The drive of the hot row against stub connections: nothing here needs PostgreSQL. What the SQL
 * does is proven by the smoke dispatch (CommitPathIT).
 */
class HotRowCounterTest {
  private static final long MILLI = 1_000_000L;

  private static BurstSampler watch(List<Sample> replication) {
    return new BurstSampler(() -> new Reading(replication, Map.of(), -1), NanoClock.system());
  }

  @Test
  void aLiveStandbyGivesNoViolationsAndEveryWriterCommits() throws Exception {
    StubCounterConnection stub = new StubCounterConnection(false);
    HotRowCounter.Driven driven =
        HotRowCounter.drive(
            stub::open,
            watch(List.of(new Sample("standby1", "quorum", "streaming"))),
            3,
            100 * MILLI,
            5_000 * MILLI);
    assertThat(driven.violations()).isEmpty();
    assertThat(driven.offsets().length).isEqualTo(3);
    for (long[] offsets : driven.offsets()) {
      assertThat(offsets).isNotEmpty();
    }
    assertThat(stub.closes()).isEqualTo(3);
  }

  @Test
  void aStandbyThatIsNotThereIsReportedByTheDrive() throws Exception {
    // RF14, L-13: the point ran without a synchronous standby, so it must not count
    StubCounterConnection stub = new StubCounterConnection(false);
    HotRowCounter.Driven driven =
        HotRowCounter.drive(stub::open, watch(List.of()), 2, 100 * MILLI, 5_000 * MILLI);
    assertThat(driven.violations()).isNotEmpty();
    assertThat(String.join(" ", driven.violations())).contains("NO_SYNC_STANDBY");
  }

  @Test
  void aStandbyThatIsNotStreamingIsReportedToo() throws Exception {
    StubCounterConnection stub = new StubCounterConnection(false);
    HotRowCounter.Driven driven =
        HotRowCounter.drive(
            stub::open,
            watch(List.of(new Sample("standby1", "quorum", "catchup"))),
            1,
            100 * MILLI,
            5_000 * MILLI);
    assertThat(String.join(" ", driven.violations())).contains("STANDBY_NOT_STREAMING");
  }

  @Test
  void aCommitThatNeverReturnsIsCutOffAtTheDeadlineInsteadOfHangingTheDrive() {
    // a synchronous standby that is gone blocks every commit for ever
    StubCounterConnection stub = new StubCounterConnection(true);
    assertTimeoutPreemptively(
        Duration.ofSeconds(20),
        () ->
            assertThatThrownBy(
                    () ->
                        HotRowCounter.drive(
                            stub::open,
                            watch(List.of(new Sample("standby1", "quorum", "streaming"))),
                            2,
                            100 * MILLI,
                            300 * MILLI))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("still in a commit"));
    assertThat(stub.aborts()).isEqualTo(2);
    assertThat(stub.closes()).isEqualTo(2);
  }
}
