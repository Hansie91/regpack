package dev.regpack.postgres.capacity;

import dev.regpack.postgres.PgTestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The capacity probe for the L-4 consequence (T21): information, not a verdict.
 *
 * <p>It drives the production adapter at the SP-L load shape (micro-batches of N, four writers with
 * one source id each, open-loop arrivals at 3,470 records/s for a configured number of seconds,
 * each batch one {@code commitIngest} and one {@code commitReportable}, half of the decisions
 * REPORT with one version each) against the server of {@code REGPACK_PG_URL}, keeps the per-second
 * sampler file and writes the read-out ({@link ProbeSummary}). The variants A, B and C of the CI
 * job differ in the server, not in this code: see {@link ProbeSettings} for what the workflow sets.
 * The run itself is {@link ProbeRun}, which {@code LedgerBurstProbeSmokeIT} runs for five seconds
 * on every {@code verify} of the real store, so that this job does not meet the server first.
 *
 * <p>The class is tagged {@code capacity} and the module excludes that group from {@code verify};
 * the workflow selects it with {@code -Dgroups=capacity -DexcludedGroups=none
 * -Dit.test=LedgerBurstProbeIT}. Without {@code REGPACK_PG_URL} it is skipped, with {@code
 * REGPACK_PG_REQUIRED=true} and no server it fails. It runs the batch sizes one after the other,
 * each in a schema of its own that is dropped at the end, and writes in {@code target/capacity/}:
 * {@code sampler-<variant>-n<N>.csv}, {@code batches-<variant>-n<N>.csv} and {@code
 * summary-<variant>-n<N>.txt}. It fails, after the read-out is written, when the load stopped on an
 * error or the sampler did not record the run.
 */
@Tag("capacity")
class LedgerBurstProbeIT {
  @Test
  @Timeout(value = 3, unit = TimeUnit.HOURS)
  void burstAtTheSpLLoadShape() throws Exception {
    DataSource server = PgTestDatabase.dataSource(); // skips, or fails when the store is required
    ProbeSettings settings = ProbeSettings.parse(System.getenv());
    Path directory = Path.of(System.getProperty("regpack.build.dir", "target"), "capacity");
    Files.createDirectories(directory);

    List<Throwable> failures = new ArrayList<>();
    for (int batchSize : settings.batchSizes()) {
      try {
        ProbeRun.run(server, settings, batchSize, directory);
      } catch (InterruptedException e) {
        throw e; // a timeout or a cancel ends the whole probe
      } catch (Exception | AssertionError e) {
        // one batch size failing does not stop the next one: the sizes are independent runs
        failures.add(e);
      }
    }
    if (!failures.isEmpty()) {
      // the cause is in the message: the failure annotation shows only its first line
      AssertionError error =
          new AssertionError(
              ProbeSummary.probeStoppedMessage(failures.size(), failures.get(0)), failures.get(0));
      failures.stream().skip(1).forEach(error::addSuppressed);
      throw error;
    }
  }
}
