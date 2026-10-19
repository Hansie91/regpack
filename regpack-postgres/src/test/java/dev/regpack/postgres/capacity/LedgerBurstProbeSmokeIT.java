package dev.regpack.postgres.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.postgres.PgTestDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The smoke test of the capacity probe (CAPACITY-3): the same {@link ProbeRun}, with the same
 * {@link BurstLoad}, {@link Sampler}, {@link ProbeSummary} and size queries, against the real store
 * for five seconds, so that the owner-dispatched capacity job (hours, up to three variants) does
 * not meet the server for the first time. The parameters are a {@link ProbeSettings} (1,000 records
 * a second, batches of 100, two sources) and nothing else differs from the job.
 *
 * <p>It is not tagged: the {@code ledger-pg} job runs it under {@code verify} with {@code
 * REGPACK_PG_REQUIRED=true}, and locally, without a server, it is skipped. Its files go to {@code
 * target/capacity-smoke/}, apart from those of the capacity job.
 */
class LedgerBurstProbeSmokeIT {
  private static final int BATCH_SIZE = 100;

  private static final ProbeSettings SETTINGS =
      new ProbeSettings("smoke", List.of(BATCH_SIZE), 2, 1_000, 5, OptionalLong.empty(), true);

  @Test
  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  void theProbeRunsAgainstTheRealStoreAndLeavesItsFiles() throws Exception {
    DataSource server = PgTestDatabase.dataSource(); // skips, or fails when the store is required
    Path directory = Path.of(System.getProperty("regpack.build.dir", "target"), "capacity-smoke");
    Files.createDirectories(directory);

    // throws, after the read-out is written, when the load stopped or the sampler did not record
    ProbeRun.Result result = ProbeRun.run(server, SETTINGS, BATCH_SIZE, directory);

    // the load: no failure, and every scheduled batch was taken and completed
    BurstLoad.Outcome outcome = result.outcome();
    assertThat(outcome.failure()).as("the load stopped on an error").isEmpty();
    assertThat(outcome.scheduledBatches()).isEqualTo(50);
    assertThat(outcome.observations())
        .hasSize(50)
        .allSatisfy(batch -> assertThat(batch.completed()).as("batch %s", batch.index()).isTrue());

    // the sampler: rows with the view columns of the real server
    assertThat(result.sampler().rows()).as("sampler rows").isGreaterThanOrEqualTo(3);
    assertThat(result.sampler().rowsWithViews()).as("rows with fresh views").isGreaterThan(0);
    assertThat(result.samplerProblem()).isEmpty();
    List<String> sampler = Files.readAllLines(result.samplerFile(), StandardCharsets.UTF_8);
    List<String> header = Arrays.asList(sampler.get(0).split(",", -1));
    assertThat(header).contains("wal.wal_records", "checkpointer.num_timed");
    assertThat(header).anyMatch(column -> column.startsWith("io."));
    assertThat(header).contains("probe.records_completed", "views_age_ms");
    assertThat(sampler.size() - 1).isEqualTo((int) result.sampler().rows());
    int walRecords = header.indexOf("wal.wal_records");
    int completed = header.indexOf("probe.records_completed");
    long lastCompleted = 0;
    for (String row : sampler.subList(1, sampler.size())) {
      String[] fields = row.split(",", -1);
      assertThat(fields[walRecords]).as("wal.wal_records in %s", row).matches("[0-9]+");
      lastCompleted = Math.max(lastCompleted, Long.parseLong(fields[completed]));
    }
    assertThat(lastCompleted).as("the progress of the load is in the rows").isGreaterThan(0);

    // the batches file and the read-out with its five headline lines
    assertThat(Files.readAllLines(result.batchesFile(), StandardCharsets.UTF_8)).hasSize(51);
    List<String> summary = Files.readAllLines(result.summaryFile(), StandardCharsets.UTF_8);
    assertThat(summary).isEqualTo(result.lines());
    assertThat(summary.subList(0, 5))
        .allSatisfy(line -> assertThat(line).startsWith("variant smoke batches of 100: "))
        .allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(300));
    // the ratio is computed, or reported as unavailable, without failing the run
    assertThat(summary.get(2)).contains("table-to-memory ratio");
    // the size and settings queries ran against the real server
    String text = String.join("\n", summary);
    assertThat(text)
        .doesNotContain("unavailable (")
        .contains("decision row ")
        .contains("(rows 5000;")
        .contains("server version ")
        .contains("synchronous_commit=on");
  }
}
