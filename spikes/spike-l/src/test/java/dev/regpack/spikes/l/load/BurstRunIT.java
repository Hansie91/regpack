package dev.regpack.spikes.l.load;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.bench.proto.NanoClock;
import dev.regpack.spikes.l.load.MicroBatchWriter.Method;
import dev.regpack.spikes.l.pg.PgFixture;
import dev.regpack.spikes.l.pg.SyncPrecondition;
import dev.regpack.spikes.l.schema.SchemaInstaller.Variant;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Runs only in the smoke dispatch: the runner and the writer against the real fixture. */
@EnabledIfSystemProperty(named = "spike.pg.url", matches = ".+")
class BurstRunIT {
  private static long count(Statement st, String sql) throws SQLException {
    try (ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Test
  void smokeRunInsertsExactlyTheExpectedRows() throws Exception {
    try (PgFixture pg = PgFixture.fromSystemProperty().orElseThrow()) {
      // 20 s at 500 records/s in batches of 500: 20 batches, 10,000 candidates, 2 writers
      BurstRunner.Config config =
          new BurstRunner.Config(
              Duration.ofSeconds(20),
              Long.MAX_VALUE,
              500.0,
              500,
              2,
              Method.COPY,
              Variant.E1_COMPACT,
              false);
      RunResult result = BurstRunner.run(pg, config, new TradeRecordGen(11), NanoClock.system());

      assertThat(result.reasons()).isEmpty();
      assertThat(result.stoppedBecause()).isEqualTo(BurstRunner.Stop.DURATION);
      assertThat(result.valid()).isTrue();
      assertThat(result.records()).isEqualTo(10_000);
      assertThat(result.commitNanosSync()).hasSize(20);
      assertThat(result.openLoopLatencyNanos()).hasSize(20);
      // every sync sample was valid, and the standby is still there
      assertThat(SyncPrecondition.violations(pg.syncSamples())).isEmpty();

      String s = "\"" + BurstRunner.SCHEMA + "\".";
      try (Connection c = pg.primary();
          Statement st = c.createStatement()) {
        assertThat(count(st, "SELECT count(*) FROM " + s + "ingest")).isEqualTo(10_000);
        assertThat(count(st, "SELECT count(*) FROM " + s + "ingest_registry")).isEqualTo(10_000);
        assertThat(count(st, "SELECT count(*) FROM " + s + "decision")).isEqualTo(10_000);
        assertThat(count(st, "SELECT count(*) FROM " + s + "outbox")).isEqualTo(20);
        assertThat(count(st, "SELECT count(*) FROM " + s + "outbox WHERE state <> 1")).isZero();
        assertThat(
                count(
                    st,
                    "SELECT count(DISTINCT event_identity) FROM "
                        + s
                        + "decision WHERE outcome = 1"))
            .isEqualTo(count(st, "SELECT count(*) FROM " + s + "decision_head"));
        assertThat(count(st, "SELECT count(*) FROM " + s + "delivery"))
            .isEqualTo(count(st, "SELECT count(*) FROM " + s + "report_version"));
        assertThat(count(st, "SELECT count(*) FROM " + s + "report_subject"))
            .isLessThanOrEqualTo(count(st, "SELECT count(*) FROM " + s + "report_version"));
      }
    }
  }
}
