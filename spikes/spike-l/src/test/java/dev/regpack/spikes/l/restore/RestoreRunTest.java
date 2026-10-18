package dev.regpack.spikes.l.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.spikes.l.restore.RestoreRun.Point;
import dev.regpack.spikes.l.restore.RestoreRun.Quality;
import dev.regpack.spikes.l.restore.RestoreRun.ScriptTimes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * The restore procedure cannot run here (no PostgreSQL, no docker, no sudo), so what protects the
 * measurement (review focus RF13: a restore timed cache-hot or without fsync honesty passes
 * falsely) is checked on what the shell prints (scripted output) and on the text of {@code
 * pg-restore.sh}: the order of the removal, the timer, the copy and the sync is the content of the
 * criterion. The restore itself is proven on the runner (RestoreIT).
 */
class RestoreRunTest {
  /** What pg-restore.sh prints, with noise around it, for a restore that went as designed. */
  private static List<String> output(int cacheDropped) {
    return List.of(
        "sp-primary",
        "cache_dropped=" + cacheDropped,
        "some line of docker output",
        "copy_nanos=120000000000",
        "sync_nanos=30000000000",
        "replay_nanos=500000000000",
        "restore_seconds=620");
  }

  private static Point point(int cacheDropped, String showFsync) {
    ScriptTimes t = RestoreRun.parseRestoreOutput(output(cacheDropped));
    return RestoreRun.point(
        1_000_000L, t, showFsync, 2_000_000_000L, 40_000_000_000L, 10_000_000_000L);
  }

  // ------------------------------------------------------------------ scripted output

  @Test
  void scriptedOutputIsParsed() {
    ScriptTimes t = RestoreRun.parseRestoreOutput(output(1));
    assertThat(t.cacheDropped()).isTrue();
    assertThat(t.copyNanos()).isEqualTo(120_000_000_000L);
    assertThat(t.syncNanos()).isEqualTo(30_000_000_000L);
    assertThat(t.replayNanos()).isEqualTo(500_000_000_000L);
  }

  @Test
  void anOutputWithoutATimeIsRejected() {
    List<String> noReplay = new ArrayList<>(output(1));
    noReplay.removeIf(l -> l.startsWith("replay_nanos="));
    assertThatThrownBy(() -> RestoreRun.parseRestoreOutput(noReplay))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("replay_nanos");
    assertThatThrownBy(
            () ->
                RestoreRun.parseRestoreOutput(
                    List.of("cache_dropped=2", "copy_nanos=1", "sync_nanos=1", "replay_nanos=1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cache_dropped");
  }

  @Test
  void pointWithoutDroppedCacheIsInconclusive() {
    Point p = point(0, "on");
    assertThat(p.cacheDropped()).isFalse();
    assertThat(p.quality()).isEqualTo(Quality.INCONCLUSIVE);
    assertThat(p.reason()).contains("cache");
    // the numbers are kept for the record, but the point never feeds L-6 or L-7
    assertThat(p.rtoNanos()).isPositive();
    assertThat(point(1, "on").quality()).isEqualTo(Quality.VALID);
  }

  @Test
  void fsyncOffInvalidatesThePoint() {
    Point off = point(1, "off");
    assertThat(off.fsyncOn()).isFalse();
    assertThat(off.quality()).isEqualTo(Quality.INVALID);
    assertThat(off.reason()).contains("fsync");
    // fsync off is worse than a warm cache: it wins when both apply
    assertThat(point(0, "off").quality()).isEqualTo(Quality.INVALID);
    assertThat(point(1, "on").fsyncOn()).isTrue();
  }

  @Test
  void rtoIsTheSumOfComponents() {
    Point p = point(1, "on");
    // copy (which already contains the sync) + replay + gap objects + analyze + verify
    assertThat(p.rtoNanos())
        .isEqualTo(
            120_000_000_000L
                + 500_000_000_000L
                + 2_000_000_000L
                + 40_000_000_000L
                + 10_000_000_000L);
    assertThat(p.rtoNanos()).isNotEqualTo(p.rtoNanos() + p.syncNanos());
    assertThat(p.syncNanos()).isLessThanOrEqualTo(p.copyNanos());
  }

  @Test
  void aSyncLongerThanTheCopyThatContainsItIsRejected() {
    assertThatThrownBy(() -> new Point(1L, true, true, 10L, 11L, 0L, 0L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sync");
  }

  // ------------------------------------------------------------------ script text

  private static Path scripts() {
    return Path.of(System.getProperty("regpack.repo.root")).resolve("spikes").resolve("scripts");
  }

  /** Every non-blank, non-comment line of a script, stripped. */
  private static List<String> code(String name) throws IOException {
    List<String> lines = new ArrayList<>();
    for (String line :
        Files.readString(scripts().resolve(name), StandardCharsets.UTF_8).split("\n")) {
      String t = line.strip();
      if (!t.isEmpty() && !t.startsWith("#")) {
        lines.add(t);
      }
    }
    return lines;
  }

  private static int first(List<String> lines, Predicate<String> p) {
    for (int i = 0; i < lines.size(); i++) {
      if (p.test(lines.get(i))) {
        return i;
      }
    }
    return -1;
  }

  private static int last(List<String> lines, Predicate<String> p) {
    for (int i = lines.size() - 1; i >= 0; i--) {
      if (p.test(lines.get(i))) {
        return i;
      }
    }
    return -1;
  }

  @Test
  void syncIsInsideTheTimedRegion() throws IOException {
    List<String> lines = code("pg-restore.sh");
    int start = first(lines, l -> l.startsWith("TIMER_START="));
    int stop = first(lines, l -> l.startsWith("TIMER_STOP="));
    assertThat(start).as("timer start").isGreaterThanOrEqualTo(0);
    assertThat(stop).as("timer stop").isGreaterThan(start);
    // the sync that makes the copy durable is between the two, as a command of its own
    int insideSync = -1;
    for (int i = start + 1; i < stop; i++) {
      if (lines.get(i).equals("sync")) {
        insideSync = i;
      }
    }
    assertThat(insideSync).as("sync inside the timer").isGreaterThan(start).isLessThan(stop);

    // the removal of the old data directory precedes the timer; the copy, with sudo, is inside it
    int rm = first(lines, l -> l.startsWith("sudo rm -rf "));
    assertThat(rm).as("rm line").isGreaterThanOrEqualTo(0).isLessThan(start);
    int cp = first(lines, l -> l.startsWith("sudo cp -a "));
    assertThat(cp).as("cp line with sudo").isGreaterThan(start).isLessThan(stop);
    // the copy is durable (sync) before the server starts: sync after cp, start after sync
    int dockerStart = first(lines, l -> l.startsWith("docker start "));
    assertThat(cp).isLessThan(insideSync);
    assertThat(insideSync).isLessThan(dockerStart);
    assertThat(dockerStart).isLessThan(stop);

    // the cache is dropped before the timer, after the removal, and reported
    int drop = first(lines, l -> l.contains("/proc/sys/vm/drop_caches"));
    assertThat(drop).isGreaterThan(rm).isLessThan(start);
    assertThat(String.join("\n", lines)).contains("cache_dropped=");

    // copyNanos covers the copy, the ownership change and the sync; replayNanos starts after it
    String text = String.join("\n", lines);
    assertThat(text).contains("echo \"copy_nanos=$((SYNC_STOP - TIMER_START))\"");
    assertThat(text).contains("echo \"sync_nanos=$((SYNC_STOP - SYNC_START))\"");
    assertThat(text).contains("echo \"replay_nanos=$((TIMER_STOP - SYNC_STOP))\"");
    assertThat(first(lines, l -> l.startsWith("SYNC_START=")))
        .isGreaterThan(cp)
        .isLessThan(insideSync);
    assertThat(first(lines, l -> l.startsWith("SYNC_STOP="))).isGreaterThan(insideSync);
  }

  @Test
  void restoreWaitsForTheEndOfRecoveryNotJustForAConnection() throws IOException {
    List<String> lines = code("pg-restore.sh");
    String text = String.join("\n", lines);
    // a hot standby accepts connections during recovery, so pg_isready alone ends the timer too
    // soon
    assertThat(text).contains("SELECT pg_is_in_recovery()").contains("pg_isready");
    assertThat(text).contains("restore_command = 'cp /archive/%f \\\"%p\\\"'");
    assertThat(text).contains("recovery.signal");
    int ready = last(lines, l -> l.contains("pg_is_in_recovery"));
    int stop = first(lines, l -> l.startsWith("TIMER_STOP="));
    assertThat(ready).isGreaterThan(0).isLessThan(stop);
  }

  @Test
  void restoreRefusesAnyPathButTheFixtureDirectories() throws IOException {
    String restore = String.join("\n", code("pg-restore.sh"));
    assertThat(restore)
        .contains("[ \"$BACKUP\" = \"$ROOT/backup\" ]")
        .contains("[ \"$ARCHIVE\" = \"$ROOT/archive\" ]");
    String backup = String.join("\n", code("pg-basebackup.sh"));
    assertThat(backup).contains("[ \"$BACKUP\" = \"$ROOT/backup\" ]");
  }

  @Test
  void theBackupIsPlainSyncedAndTakenFromTheRunningPrimary() throws IOException {
    List<String> lines = code("pg-basebackup.sh");
    int run = first(lines, l -> l.contains("docker run") && l.contains("pg_basebackup"));
    assertThat(run).isGreaterThanOrEqualTo(0);
    String command = lines.get(run);
    assertThat(command)
        .contains("\"$IMAGE\"")
        .contains("-h 127.0.0.1 -p 5432")
        .contains("-X stream")
        .contains("--user 999:999")
        .doesNotContain(" -Ft")
        .doesNotContain(" -z");
    // the backup is flushed to the disk before the script returns, so that the restore reads it
    // cold
    int sync = last(lines, l -> l.equals("sync"));
    assertThat(sync).isGreaterThan(run);
    // the target is emptied first: pg_basebackup refuses a directory that is not empty
    int rm = first(lines, l -> l.startsWith("sudo rm -rf "));
    assertThat(rm).isGreaterThanOrEqualTo(0).isLessThan(run);
  }

  @Test
  void theCacheDropScriptReportsWhetherItWorked() throws IOException {
    List<String> lines = code("pg-drop-caches.sh");
    String text = String.join("\n", lines);
    assertThat(text)
        .contains("sync")
        .contains("/proc/sys/vm/drop_caches")
        .contains("cache_dropped=");
  }
}
