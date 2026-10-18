package dev.regpack.spikes.l;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.regpack.spikes.l.LedgerEvaluator.MemoryPlan;
import dev.regpack.spikes.l.LedgerEvaluator.RowSizeData;
import dev.regpack.spikes.l.SpLRunner.Part;
import dev.regpack.spikes.l.SpLRunner.Plan;
import dev.regpack.spikes.l.SpLRunner.Profile;
import dev.regpack.spikes.l.SpLRunner.Role;
import dev.regpack.spikes.l.SpLRunner.Step;
import dev.regpack.spikes.l.load.BurstRunner;
import dev.regpack.spikes.l.load.MicroBatchWriter.Method;
import dev.regpack.spikes.l.pg.PgFixture;
import dev.regpack.spikes.l.restore.RestoreRun;
import dev.regpack.spikes.l.restore.Shell;
import dev.regpack.spikes.l.schema.SchemaInstaller.Variant;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pure helpers of the runner: what it parses, caps and projects. No fixture is needed. */
class SpLRunnerTest {
  private static final long GIB = 1L << 30;

  private static final List<String> FSYNC_OUTPUT =
      List.of(
          "5 seconds per test",
          "O_DIRECT supported on this platform for open_datasync and open_sync.",
          "",
          "Compare file sync methods using one 8kB write:",
          "(in wal_sync_method preference order, except fdatasync is Linux's default)",
          "        open_datasync                    1234.567 ops/sec     810 usecs/op",
          "        fdatasync                        1100.123 ops/sec     909 usecs/op",
          "        fsync                             800.000 ops/sec    1250 usecs/op",
          "        fsync_writethrough                              n/a",
          "        open_sync                         900.500 ops/sec    1110 usecs/op",
          "",
          "Compare file sync methods using two 8kB writes:",
          "        open_datasync                     600.000 ops/sec    1666 usecs/op",
          "        fdatasync                         900.000 ops/sec    1111 usecs/op");

  @Test
  void theFsyncBaselineIsReadFromTheFirstBlockOnly() {
    Map<String, Object> parsed = SpLRunner.parseFsync(FSYNC_OUTPUT);
    assertThat(parsed)
        .containsEntry("pgTestFsync.open_datasync.usecsPerOp", 810L)
        .containsEntry("pgTestFsync.fdatasync.usecsPerOp", 909L)
        .containsEntry("pgTestFsync.fsync.usecsPerOp", 1_250L)
        .containsEntry("pgTestFsync.open_sync.usecsPerOp", 1_110L);
    assertThat(parsed).doesNotContainKey("pgTestFsync.fsync_writethrough.usecsPerOp");
    assertThat(SpLRunner.parseFsync(List.of("garbage", "")))
        .as("a baseline that printed nothing usable is empty, never an error")
        .isEmpty();
  }

  @Test
  void aRunIsCappedByTheDiskThatItsCopiesNeed() {
    long scheduled = 4_164_000L;
    // 14 GiB, 1,500 bytes a candidate, three live copies and the archived WAL: about 1.3M records
    long capped = SpLRunner.maxRecords(14 * GIB, 1_500L, scheduled, 5_000);
    assertThat(capped).isEqualTo(1_300_000L);
    assertThat(capped % 5_000).isZero();
    // a large disk holds the whole slice
    assertThat(SpLRunner.maxRecords(200 * GIB, 1_500L, scheduled, 5_000)).isEqualTo(scheduled);
    // a disk that cannot hold even the smallest slice gives no run
    assertThat(SpLRunner.maxRecords(2 * GIB, 1_500L, scheduled, 5_000)).isZero();
  }

  private static Map<String, Long> measured(Plan smoke, long seconds) {
    Map<String, Long> out = new LinkedHashMap<>();
    for (Step step : smoke.steps()) {
      out.put(step.id(), seconds);
    }
    return out;
  }

  @Test
  void theProjectionStatesTheFullDurationAgainstTheDeadline() {
    Plan smoke = SpLRunner.plan(Profile.SMOKE, false);
    Plan full = SpLRunner.plan(Profile.FULL, false);
    String quick = SpLRunner.projection(full, smoke, measured(smoke, 120), 90, 305);
    assertThat(quick)
        .startsWith("projected full duration: ")
        .contains("minutes typical")
        .contains("minutes worst");

    String slow = SpLRunner.projection(full, smoke, measured(smoke, 100_000), 90, 305);
    assertThat(slow).contains("ABOVE 85%").contains("tell the owner");
    assertThat(SpLRunner.projection(full, smoke, Map.of(), 0, 305))
        .as("a smoke run that measured nothing still says so")
        .contains("assumed");
  }

  @Test
  void theFsyncBaselineAcceptsThousandsSeparators() {
    // the image runs under en_US.utf8: values of 1,000 and more can be printed with a comma
    Map<String, Object> parsed =
        SpLRunner.parseFsync(
            List.of(
                "Compare file sync methods using one 8kB write:",
                "        open_datasync                    4,492.275 ops/sec     1,234 usecs/op",
                "        fdatasync                        1,100.123 ops/sec       909 usecs/op",
                "        fsync                           12,000.000 ops/sec    11,250 usecs/op"));
    assertThat(parsed)
        .containsEntry("pgTestFsync.open_datasync.usecsPerOp", 1_234L)
        .containsEntry("pgTestFsync.fdatasync.usecsPerOp", 909L)
        .containsEntry("pgTestFsync.fsync.usecsPerOp", 11_250L);
  }

  // ---------------------------------------------------------------- the wiring of the runs (RF14)

  @Test
  void runAIsAnOpenLoopBurstAtTheDesignRateAndC1IsClosedLoop() {
    BurstRunner.Config a = SpLRunner.sustainConfig(Profile.FULL.sizes(), 4_164_000L);
    assertThat(a.closedLoop()).isFalse();
    assertThat(a.targetRecordsPerSecond()).isEqualTo(3_470.0);
    assertThat(a.batchSize()).isEqualTo(5_000);
    assertThat(a.writers()).isEqualTo(4);
    assertThat(a.method()).isEqualTo(Method.COPY);
    assertThat(a.variant()).isEqualTo(Variant.E1_COMPACT);
    assertThat(a.duration().toSeconds()).isEqualTo(1_200);
    assertThat(a.maxRecords()).isEqualTo(4_164_000L);

    BurstRunner.Config c = SpLRunner.closedLoopConfig(Profile.FULL.sizes(), 20_000_000L);
    assertThat(c.closedLoop()).isTrue();
    assertThat(c.batchSize()).isEqualTo(5_000);
    assertThat(c.writers()).isEqualTo(4);
    assertThat(c.method()).isEqualTo(Method.COPY);
    assertThat(c.variant()).isEqualTo(Variant.E1_COMPACT);
    assertThat(c.duration().toSeconds()).isEqualTo(600);

    Plan full = SpLRunner.plan(Profile.FULL, false);
    assertThat(full.steps(Part.INGEST))
        .extracting(Step::id, Step::role)
        .containsExactly(
            tuple("baseline", Role.BASELINE),
            tuple("A1", Role.SUSTAIN_A),
            tuple("A2", Role.SUSTAIN_A),
            tuple("C1", Role.CLOSED_LOOP),
            tuple("B1", Role.SUSTAIN_B),
            tuple("B2", Role.SUSTAIN_B));
  }

  @Test
  void runBPassesTheMemoryFlagsToTheRestartAndRunADoesNot() throws Exception {
    MemoryPlan plan = MemoryPlan.of(4_164_000L, 1_500L);
    assertThat(SpLRunner.restartArgs(Part.INGEST, Optional.empty())).containsExactly("sp-l-ingest");
    assertThat(SpLRunner.restartArgs(Part.INGEST, Optional.of(plan)))
        .containsExactly(
            "sp-l-ingest",
            "--memory=" + plan.dockerMemory(),
            "--shared-buffers=" + plan.sharedBuffers());

    // the runner really hands those arguments to pg-restart.sh: a recording shell sees them
    List<List<String>> commands = new ArrayList<>();
    Shell recording =
        command -> {
          commands.add(command);
          return List.of();
        };
    SpLRunner runner =
        new SpLRunner(options(Path.of("unused")), unreachablePg(), recording, quiet());
    runner.freshCluster(Part.INGEST, Optional.of(plan));
    assertThat(commands).hasSize(1);
    assertThat(commands.get(0).get(1)).endsWith("pg-restart.sh");
    assertThat(commands.get(0).subList(2, commands.get(0).size()))
        .containsExactly(
            "sp-l-ingest",
            "--memory=" + plan.dockerMemory(),
            "--shared-buffers=" + plan.sharedBuffers());
    // a following run without the limit must restart without it (the settings of B do not stay)
    runner.freshCluster(Part.INGEST, Optional.empty());
    assertThat(commands).hasSize(2);
    assertThat(commands.get(1).subList(2, commands.get(1).size())).containsExactly("sp-l-ingest");
  }

  // ---------------------------------------------------------------- F1: bytes per key

  @Test
  void bytesPerKeyIsTheLoadedSizeBeforeTheGapAndOnlyTheFirstPointSetsIt() {
    SpLRunner runner =
        new SpLRunner(options(Path.of("unused")), unreachablePg(), quietShell(), quiet());
    assertThat(runner.bytesPerKey()).isZero();
    runner.recordRestore(outcome(500_000L, 1_000L));
    assertThat(runner.bytesPerKey())
        .as("what the outcome measured after the load and before the gap and the backup")
        .isEqualTo(1_000L);
    // a later point has a gap and gap objects in its schema too; it never changes the figure that
    // sizes the next restore points
    runner.recordRestore(outcome(1_000_000L, 4_500L));
    assertThat(runner.bytesPerKey()).isEqualTo(1_000L);
  }

  private static RestoreRun.Outcome outcome(long keys, long bytesPerKey) {
    long second = 1_000_000_000L;
    RestoreRun.Point point =
        new RestoreRun.Point(
            keys, true, true, 10 * second, second, 5 * second, second, second, second);
    return new RestoreRun.Outcome(point, 1_040_000L, 800L, bytesPerKey);
  }

  // ---------------------------------------------------------------- F2: the fetch under the burst

  @Test
  void theFetchBlocksAreSpreadOverTheWholeBurstAfterAWarmUp() {
    for (Profile profile : Profile.values()) {
      var z = profile.sizes();
      long second = 1_000_000_000L;
      long[] offsets = SpLRunner.fetchBlockOffsets(z.fetchSeconds(), z.blocks());
      assertThat(offsets).hasSize(z.blocks());
      assertThat(offsets[0]).as(profile + ": a warm-up first").isGreaterThanOrEqualTo(2 * second);
      for (int i = 1; i < offsets.length; i++) {
        assertThat(offsets[i]).isGreaterThan(offsets[i - 1]);
        assertThat(offsets[i] - offsets[i - 1])
            .as("equal spacing")
            .isEqualTo(offsets[1] - offsets[0]);
      }
      long spacing = offsets[1] - offsets[0];
      assertThat(offsets[offsets.length - 1] + spacing)
          .as(profile + ": the last block ends before the burst does")
          .isLessThan(z.fetchSeconds() * second);
      assertThat(offsets[offsets.length - 1] + spacing)
          .as(profile + ": and the blocks cover most of its life")
          .isGreaterThan(z.fetchSeconds() * second * 3 / 4);
    }
    long[] full = SpLRunner.fetchBlockOffsets(90, 15);
    assertThat(full[0] / 1_000_000_000L).isEqualTo(9);
    assertThat(full[14] / 1_000_000_000L)
        .as("the last of 15 blocks starts in the last quarter of a 90 s burst")
        .isGreaterThanOrEqualTo(70);
  }

  // ---------------------------------------------------------------- F3: the partition study and
  // the disk

  @Test
  void theStudyRowSizeComesFromTheMeasuredDecisionAndHeadTables() {
    RowSizeData e1 =
        new RowSizeData(
            "E1_COMPACT",
            1_000,
            300_000,
            100_000,
            480,
            Map.of("decision", 400_000L, "decision_head", 60_000L, "ingest", 900_000L));
    assertThat(SpLRunner.studyBytesPerRow(Optional.of(e1)))
        .as("decision and head with their indexes over the decision rows, rounded up")
        .isEqualTo(460L);
    assertThat(SpLRunner.studyBytesPerRow(Optional.empty()))
        .as("a conservative figure until the row size has been measured")
        .isEqualTo(SpLRunner.STUDY_BYTES_PER_ROW_FALLBACK);
  }

  @Test
  void thePartitionLoadIsReducedToWhatTheDiskHolds() {
    long gib = 1L << 30;
    // 30 x 300,000 rows of 400 bytes: the primary and the archived WAL (2.2 x 3.6 GB), the live WAL
    // and the reserve fit 14 GiB
    assertThat(SpLRunner.fittingRowsPerDay(14 * gib, 400, 30, 300_000)).isEqualTo(300_000);
    // 10 GiB: only three quarters of the rows fit
    assertThat(SpLRunner.fittingRowsPerDay(10 * gib, 400, 30, 300_000)).isEqualTo(225_000);
    // a disk that cannot hold even an eighth of the load gives no point
    assertThat(SpLRunner.fittingRowsPerDay(3 * gib, 400, 30, 300_000)).isZero();
    // the figure never exceeds what was asked for
    assertThat(SpLRunner.fittingRowsPerDay(500 * gib, 400, 30, 300_000)).isEqualTo(300_000);
  }

  // ---------------------------------------------------------------- F6

  @Test
  void theConsoleIgnoresMissingObjectsOnlyWhileTheBurstInstallsItsSchema() {
    long second = 1_000_000_000L;
    assertThat(SpLRunner.ignorableConsoleError("42P01", 3 * second)).isTrue();
    assertThat(SpLRunner.ignorableConsoleError("3F000", 3 * second)).isTrue();
    assertThat(SpLRunner.ignorableConsoleError("42P01", 120 * second))
        .as("after the start-up the same error is a fault of the study")
        .isFalse();
    assertThat(SpLRunner.ignorableConsoleError("XX000", 3 * second)).isFalse();
  }

  @Test
  void aRunInWhichNothingCouldBeMeasuredExitsWithOneAndSaysSo(@TempDir Path tmp) throws Exception {
    // PostgreSQL is not reachable and every script fails: the file holds notes only, and the job
    // must not look green
    Shell failing =
        command -> {
          throw new IOException("no fixture on this machine");
        };
    ByteArrayOutputStream printed = new ByteArrayOutputStream();
    int exit =
        SpLRunner.run(
            options(tmp.resolve("results")),
            unreachablePg(),
            failing,
            new PrintStream(printed, true, StandardCharsets.UTF_8));
    assertThat(exit).isEqualTo(1);
    String text = printed.toString(StandardCharsets.UTF_8);
    assertThat(text).contains("nothing could be measured");
    String file = Files.readString(tmp.resolve("results").resolve("measurements.json"));
    assertThat(file).contains("nothing could be measured");
  }

  // ---------------------------------------------------------------- helpers

  private static SpLRunner.Options options(Path out) {
    return new SpLRunner.Options(
        Profile.SMOKE,
        false,
        Optional.of(Part.INGEST),
        5,
        out,
        Map.of(),
        Path.of("").toAbsolutePath());
  }

  private static PrintStream quiet() {
    return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
  }

  private static Shell quietShell() {
    return command -> List.of();
  }

  /** A fixture whose primary refuses connections at once: nothing in these tests needs a server. */
  private static PgFixture unreachablePg() {
    String saved = System.getProperty(PgFixture.PROPERTY);
    System.setProperty(
        PgFixture.PROPERTY,
        "jdbc:postgresql://127.0.0.1:1/postgres?user=postgres&connectTimeout=2");
    try {
      return PgFixture.fromSystemProperty().orElseThrow();
    } finally {
      if (saved == null) {
        System.clearProperty(PgFixture.PROPERTY);
      } else {
        System.setProperty(PgFixture.PROPERTY, saved);
      }
    }
  }

  @Test
  void onlyTheStepsAfterTheFirstNeedAFreshCluster() {
    Plan full = SpLRunner.plan(Profile.FULL, false);
    List<Step> ingest = full.steps(SpLRunner.Part.INGEST);
    assertThat(ingest.stream().filter(Step::restart).map(Step::id))
        .as("A1 runs on the cluster that pg-up.sh started; every later run starts fresh")
        .containsExactly("A2", "C1", "B1", "B2");
  }
}
