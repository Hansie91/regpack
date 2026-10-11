package dev.regpack.spikes.l.restore;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.spikes.l.pg.PgFixture;
import dev.regpack.spikes.l.restore.RestoreRun.Outcome;
import dev.regpack.spikes.l.restore.RestoreRun.Point;
import dev.regpack.spikes.l.restore.RestoreRun.Quality;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Runs only in the restore dispatch (the runner has the fixture, docker and sudo; this machine has
 * none): the whole restore procedure at a small size. The run itself compares the counts and the
 * content digest before the stop and after the restore and throws on any difference, so a return
 * means they are equal.
 */
@EnabledIfSystemProperty(named = "spike.pg.url", matches = ".+")
class RestoreIT {
  @Test
  void restoreFromBackupAndWalReproducesCountsAndDigest() throws Exception {
    try (PgFixture pg = PgFixture.fromSystemProperty().orElseThrow()) {
      Outcome outcome = RestoreRun.runWithGap(pg, 20_000, Duration.ofSeconds(20));
      Point point = outcome.point();
      assertThat(outcome.gapRecords()).as("records of the gap").isPositive();
      assertThat(outcome.gapObjects()).as("journal objects of the gap").isPositive();
      assertThat(point.keys()).isEqualTo(20_000);
      assertThat(point.fsyncOn()).as("fsync after the restore").isTrue();
      assertThat(point.quality()).as(point.reason()).isEqualTo(Quality.VALID);
      assertThat(point.copyNanos()).isPositive();
      assertThat(point.syncNanos()).isPositive().isLessThanOrEqualTo(point.copyNanos());
      assertThat(point.replayNanos()).isPositive();
      assertThat(point.rtoNanos())
          .isEqualTo(
              point.copyNanos()
                  + point.replayNanos()
                  + point.gapObjectsNanos()
                  + point.analyzeNanos()
                  + point.verifyNanos());
    }
  }
}
