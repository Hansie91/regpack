package dev.regpack.postgres.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.postgres.capacity.ProbeSummary.Batch;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the probe keeps when the server is gone after the load (CAPACITY-2): the read-out and the
 * per-batch file are on disk before the server is asked anything, a query that fails is named in
 * the read-out instead of discarding it, and a cleanup that fails cannot replace the error of the
 * run. No server is needed: the post-run queries are replaced.
 */
class ProbeRunTest {
  private static final long SECOND = 1_000_000_000L;

  @TempDir Path directory;

  private static final ProbeSettings SETTINGS =
      new ProbeSettings("B", List.of(1_000), 4, 1_000, 20, OptionalLong.of(1_363L << 20), true);

  private static Batch done(long index, double arrival, double end) {
    return new Batch(
        index,
        (int) (index % 4),
        Math.round(arrival * SECOND),
        Math.round(arrival * SECOND),
        Math.round(end * SECOND),
        1_000_000L,
        50_000_000L,
        50_000_000L,
        1_000,
        40,
        2_000);
  }

  private static BurstLoad.Outcome outcome(Optional<Throwable> failure) {
    List<Batch> batches = new ArrayList<>();
    for (int k = 0; k < 20; k++) {
      batches.add(done(k, k + 1, k + 1.5));
    }
    return new BurstLoad.Outcome(batches, 20 * SECOND, 20, failure);
  }

  private static final ProbeSummary.SamplerFile SAMPLER =
      new ProbeSummary.SamplerFile("sampler-B-n1000.csv", 20, 0, 20);

  /** Queries that fail the way a server in crash recovery does. */
  private static final ProbeRun.Queries IN_RECOVERY =
      new ProbeRun.Queries() {
        @Override
        public ProbeSummary.Sizes sizes() throws SQLException {
          throw new SQLException("the database system is in recovery mode", "57P03");
        }

        @Override
        public List<String> serverSettings() throws SQLException {
          throw new SQLException("the database system is in recovery mode", "57P03");
        }
      };

  @Test
  void aServerInCrashRecoveryStillLeavesTheReadOutAndTheBatches() throws IOException {
    ProbeRun.Result result =
        ProbeRun.finish(
            directory, SETTINGS, 1_000, outcome(Optional.empty()), SAMPLER, IN_RECOVERY);

    List<String> lines = Files.readAllLines(result.summaryFile(), StandardCharsets.UTF_8);
    assertThat(lines).isEqualTo(result.lines());
    assertThat(lines.get(2)).contains("sizes unavailable (sqlstate=57P03)");
    assertThat(lines.get(0)).contains("records/s per 60 s window");
    assertThat(String.join("\n", lines))
        .contains("server settings unavailable (sqlstate=57P03)")
        .doesNotContain("recovery mode");
    // the observations of every batch are in a file of their own
    List<String> batches = Files.readAllLines(result.batchesFile(), StandardCharsets.UTF_8);
    assertThat(batches).hasSize(21);
    assertThat(batches.get(0)).startsWith("index,source,arrival_ns,start_ns,end_ns,");
    assertThat(result.summaryFile().getFileName().toString()).isEqualTo("summary-B-n1000.txt");
    assertThat(result.batchesFile().getFileName().toString()).isEqualTo("batches-B-n1000.csv");
  }

  @Test
  void theReadOutIsOnDiskBeforeTheServerIsAsked() throws IOException {
    AtomicReference<List<String>> seenBySizes = new AtomicReference<>();
    AtomicReference<Boolean> batchesSeen = new AtomicReference<>();
    ProbeRun.Queries probe =
        new ProbeRun.Queries() {
          @Override
          public ProbeSummary.Sizes sizes() throws SQLException {
            try {
              seenBySizes.set(Files.readAllLines(directory.resolve("summary-B-n1000.txt")));
              batchesSeen.set(Files.exists(directory.resolve("batches-B-n1000.csv")));
            } catch (IOException e) {
              throw new SQLException("the summary was not there", "XX000");
            }
            return new ProbeSummary.Sizes(1_000, 800_000, 4_000_000);
          }

          @Override
          public List<String> serverSettings() {
            return List.of(
                "server version 18.6", "server settings at the end of the run: fsync=on");
          }
        };

    ProbeRun.Result result =
        ProbeRun.finish(directory, SETTINGS, 1_000, outcome(Optional.empty()), SAMPLER, probe);

    assertThat(seenBySizes.get()).as("the summary existed when the server was asked").isNotNull();
    assertThat(seenBySizes.get().get(2)).contains("sizes unavailable (not read yet)");
    assertThat(batchesSeen.get()).isTrue();
    // and after the answers the final file has the sizes and the server lines
    assertThat(result.lines().get(2)).contains("bytes a record in all ledger tables");
    assertThat(String.join("\n", result.lines()))
        .doesNotContain("not read yet")
        .contains("server version 18.6")
        .contains("fsync=on");
    assertThat(result.samplerProblem()).isEmpty();
  }

  @Test
  void aRunThatStoppedIsRaisedAfterTheReadOutAndNamesTheSamplerToo() throws IOException {
    Throwable cause = new IllegalStateException("ledger statement failed: sqlstate=57P01");
    ProbeSummary.SamplerFile thin = new ProbeSummary.SamplerFile("sampler-B-n1000.csv", 3, 0, 0);
    ProbeRun.Result result =
        ProbeRun.finish(directory, SETTINGS, 1_000, outcome(Optional.of(cause)), thin, IN_RECOVERY);

    assertThat(Files.exists(result.summaryFile())).isTrue();
    assertThat(result.samplerProblem()).isPresent();
    assertThatThrownBy(() -> ProbeRun.raise(result))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("the load stopped on an error")
        .hasMessageContaining("sqlstate=57P01")
        .hasCause(cause)
        .satisfies(
            e ->
                assertThat(e.getSuppressed()[0].getMessage())
                    .contains("the sampler did not record the run")
                    .contains("no row has fresh view columns"));
  }

  @Test
  void aCleanRunWithAThinSamplerFailsOnTheSamplerAlone() throws IOException {
    ProbeSummary.SamplerFile thin = new ProbeSummary.SamplerFile("sampler-B-n1000.csv", 4, 9, 4);
    ProbeRun.Result result =
        ProbeRun.finish(directory, SETTINGS, 1_000, outcome(Optional.empty()), thin, IN_RECOVERY);

    assertThatThrownBy(() -> ProbeRun.raise(result))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("the sampler did not record the run")
        .hasMessageContaining("4 rows for about 20 s");

    ProbeRun.Result fine =
        ProbeRun.finish(
            directory, SETTINGS, 1_000, outcome(Optional.empty()), SAMPLER, IN_RECOVERY);
    ProbeRun.raise(fine); // nothing to raise
  }

  @Test
  void aCleanupThatFailsNeverReplacesTheErrorOfTheRun() {
    RuntimeException dropFailed = new IllegalStateException("could not drop a test schema");
    AssertionError primary = new AssertionError("the load stopped on an error");

    ProbeRun.cleanUp(
        primary,
        () -> {
          throw dropFailed;
        });

    assertThat(primary.getSuppressed()).containsExactly(dropFailed);

    // after a clean run the failure of the cleanup is the failure
    assertThatThrownBy(
            () ->
                ProbeRun.cleanUp(
                    null,
                    () -> {
                      throw dropFailed;
                    }))
        .isSameAs(dropFailed);
    ProbeRun.cleanUp(null, () -> {}); // and no failure is no failure
  }
}
