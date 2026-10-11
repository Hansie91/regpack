package dev.regpack.spikes.l.restore;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.spikes.l.pg.PgFixture;
import dev.regpack.spikes.l.restore.RebuildRun.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Runs only in the restore dispatch: the rebuild from landing files against the real fixture. */
@EnabledIfSystemProperty(named = "spike.pg.url", matches = ".+")
class RebuildIT {
  private static void deleteTree(Path dir) throws IOException {
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }

  @Test
  void rebuildFromLandingFilesGivesTheSameDecisionsAsTheDirectRun() throws Exception {
    Path dir = Files.createTempDirectory("sp-landing");
    try (PgFixture pg = PgFixture.fromSystemProperty().orElseThrow()) {
      // 20,000 records are four landing files of 5,000; two threads take two files each
      Result result = RebuildRun.run(pg, dir, 20_000, 2);
      try (Stream<Path> files = Files.list(dir)) {
        assertThat(files.count()).isEqualTo(4);
      }
      String direct = RebuildRun.directContent(20_000);
      assertThat(result.content()).isEqualTo(direct);
      assertThat(direct).startsWith("rows=20000;");
      assertThat(result.keys()).isEqualTo(20_000);
      assertThat(result.cacheDropped()).as("the page cache was dropped before the timer").isTrue();
      assertThat(result.wallNanos()).isPositive();
      assertThat(result.readNanos()).isPositive();
      assertThat(result.decideNanos()).isPositive();
      assertThat(result.copyNanos()).isPositive();
      assertThat(result.secondsPerMillionKeys().signum()).isPositive();
    } finally {
      deleteTree(dir);
    }
  }
}
