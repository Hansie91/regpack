package dev.regpack.spikes.l.pg;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Runs only in the smoke dispatch (the runner has the three servers; this machine has none): the
 * fixture really is a quorum synchronous standby and an asynchronous replica of PostgreSQL 18.6.
 */
@EnabledIfSystemProperty(named = "spike.pg.url", matches = ".+")
class PgFixtureIT {
  private static Path repo() {
    return Path.of(System.getProperty("regpack.repo.root"));
  }

  private static void standby(String command) throws IOException, InterruptedException {
    Process p =
        new ProcessBuilder("bash", "spikes/scripts/pg-standby.sh", command)
            .directory(repo().toFile())
            .redirectErrorStream(true)
            .start();
    String output = new String(p.getInputStream().readAllBytes());
    if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) {
      throw new IllegalStateException("pg-standby.sh " + command + " failed: " + output);
    }
  }

  private static List<String> violationsNow(PgFixture pg) throws SQLException {
    return SyncPrecondition.violations(pg.syncSamples());
  }

  private static List<String> pollViolations(PgFixture pg, boolean expectClean, Duration limit)
      throws Exception {
    long deadline = System.nanoTime() + limit.toNanos();
    List<String> last = violationsNow(pg);
    while (last.isEmpty() != expectClean && System.nanoTime() < deadline) {
      Thread.sleep(500);
      last = violationsNow(pg);
    }
    return last;
  }

  @Test
  void startsWithQuorumSyncStandby() throws Exception {
    try (PgFixture pg = PgFixture.fromSystemProperty().orElseThrow()) {
      Map<String, String> settings =
          pg.settings("server_version", "synchronous_standby_names", "data_directory", "fsync");
      assertThat(settings.get("server_version")).startsWith("18.6");
      assertThat(settings.get("synchronous_standby_names")).contains("ANY 1");
      assertThat(settings.get("data_directory")).isEqualTo("/var/lib/postgresql/18/docker");
      assertThat(settings.get("fsync")).isEqualTo("on");
      assertThat(violationsNow(pg)).isEmpty();
      assertThat(pg.syncSamples())
          .anySatisfy(
              s -> {
                assertThat(s.applicationName()).isEqualTo("standby1");
                assertThat(s.syncState()).isEqualTo("quorum");
                assertThat(s.state()).isEqualTo("streaming");
              });
      List<String> slots = new ArrayList<>();
      try (Connection c = pg.primary();
          Statement st = c.createStatement();
          ResultSet rs = st.executeQuery("SELECT slot_name FROM pg_replication_slots ORDER BY 1")) {
        while (rs.next()) {
          slots.add(rs.getString(1));
        }
      }
      assertThat(slots).contains("standby1_slot", "replica1_slot");
    }
  }

  @Test
  void syncPreconditionFailsWhenStandbyDown() throws Exception {
    try (PgFixture pg = PgFixture.fromSystemProperty().orElseThrow()) {
      assertThat(violationsNow(pg)).isEmpty();
      standby("stop");
      try {
        List<String> down = pollViolations(pg, false, Duration.ofSeconds(30));
        assertThat(down).contains("NO_SYNC_STANDBY");
      } finally {
        standby("start");
      }
      assertThat(pollViolations(pg, true, Duration.ofSeconds(60))).isEmpty();
    }
  }

  @Test
  void asyncReplicaFollows() throws Exception {
    try (PgFixture pg = PgFixture.fromSystemProperty().orElseThrow()) {
      try (Connection c = pg.primary();
          Statement st = c.createStatement()) {
        st.execute("DROP TABLE IF EXISTS fixture_probe");
        st.execute("CREATE TABLE fixture_probe (id int PRIMARY KEY)");
        st.execute("INSERT INTO fixture_probe VALUES (1)");
      }
      try (Connection r = pg.replica();
          Statement st = r.createStatement()) {
        try (ResultSet rs = st.executeQuery("SELECT pg_is_in_recovery()")) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getBoolean(1)).isTrue();
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        boolean seen = false;
        while (!seen && System.nanoTime() < deadline) {
          try (ResultSet rs =
              st.executeQuery("SELECT count(*) FROM pg_class WHERE relname = 'fixture_probe'")) {
            rs.next();
            seen = rs.getInt(1) == 1;
          }
          if (!seen) {
            Thread.sleep(250);
          }
        }
        assertThat(seen).as("the replica sees the table the primary created").isTrue();
        try (ResultSet rs = st.executeQuery("SELECT count(*) FROM fixture_probe")) {
          rs.next();
          assertThat(rs.getInt(1)).isEqualTo(1);
        }
      } finally {
        try (Connection c = pg.primary();
            Statement st = c.createStatement()) {
          st.execute("DROP TABLE IF EXISTS fixture_probe");
        }
      }
    }
  }
}
