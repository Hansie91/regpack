package dev.regpack.bench.proto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CanaryTest {
  private static final long MS = 1_000_000L;

  @Test
  void cpuProbeIsMegabytesPerSecondOnAScriptedClock() {
    // every reading costs 10 ms; the probe stops once 100 ms have elapsed: ten 1 MiB hashes
    ScriptedClock clock = new ScriptedClock(10 * MS);
    assertThat(Canary.cpuMbPerSec(clock, Duration.ofMillis(100))).isEqualTo(100.0, within(1e-9));
    ScriptedClock slower = new ScriptedClock(20 * MS);
    assertThat(Canary.cpuMbPerSec(slower, Duration.ofMillis(100))).isEqualTo(50.0, within(1e-9));
  }

  @Test
  void memoryProbeCountsWholePassesOverSixtyFourMebibytes() {
    // ten passes in 100 ms of scripted time: 10 x 64 MiB in 0.1 s
    ScriptedClock clock = new ScriptedClock(10 * MS);
    assertThat(Canary.memoryMbPerSec(clock, Duration.ofMillis(100)))
        .isEqualTo(6_400.0, within(1e-6));
  }

  @Test
  void memoryProbeIsPositiveOnTheRealClock() {
    assertThat(Canary.memoryMbPerSec(NanoClock.system(), Duration.ofMillis(30))).isPositive();
    assertThat(Canary.cpuMbPerSec(NanoClock.system(), Duration.ofMillis(30))).isPositive();
  }

  @Test
  void diskProbeWritesWithDsync(@TempDir Path dir) throws IOException {
    Path scratch = dir.resolve("canary.bin");
    List<Long> sizes = new ArrayList<>();
    long[] now = {0};
    NanoClock observing =
        () -> {
          try {
            sizes.add(Files.exists(scratch) ? Files.size(scratch) : -1L);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          long reading = now[0];
          now[0] += 10 * MS;
          return reading;
        };
    double rate = Canary.diskSyncOpsPerSec(observing, Duration.ofMillis(100), scratch);
    // ten 4 KiB synchronous writes in 100 ms
    assertThat(rate).isEqualTo(100.0, within(1e-9));
    assertThat(Files.size(scratch)).isEqualTo(10 * 4096L);
    // between two readings the file grew by exactly one 4 KiB step, never by a partial block
    assertThat(sizes).allSatisfy(size -> assertThat(size == -1L || size % 4096 == 0).isTrue());
    List<Long> written = sizes.stream().filter(s -> s >= 0).toList();
    for (int i = 1; i < written.size(); i++) {
      assertThat(written.get(i) - written.get(i - 1)).isIn(0L, 4096L);
    }
  }

  /** The sizes above cannot tell DSYNC from a page-cache write, so the options are asserted. */
  @Test
  void theDiskProbeOpensWithDsyncAndWrite() {
    assertThat(Canary.DISK_OPEN_OPTIONS)
        .contains(StandardOpenOption.DSYNC, StandardOpenOption.WRITE);
  }

  @Test
  void diskProbeWritesOneBlockPerOperation(@TempDir Path dir) throws IOException {
    Path scratch = dir.resolve("canary.bin");
    Canary.diskSyncOpsPerSec(new ScriptedClock(MS), Duration.ofMillis(20), scratch);
    assertThat(Files.size(scratch)).isEqualTo(20 * 4096L);
  }

  @Test
  void aProbeNeedsAPositiveDuration() {
    assertThatThrownBy(() -> Canary.cpuMbPerSec(ScriptedClock.manual(), Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Canary.memoryMbPerSec(ScriptedClock.manual(), Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
