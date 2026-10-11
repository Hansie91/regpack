package dev.regpack.spikes.l.partition;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.spikes.l.partition.PartitionPlan.Granularity;
import dev.regpack.spikes.l.partition.PartitionStudy.Point;
import dev.regpack.spikes.l.pg.PgFixture;
import dev.regpack.spikes.l.schema.SchemaInstaller;
import dev.regpack.spikes.l.schema.SchemaInstaller.Variant;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Runs only in the misc dispatch: the partition study against the real fixture (L-9, L-10). */
@EnabledIfSystemProperty(named = "spike.pg.url", matches = ".+")
class PartitionIT {
  private static final String SCHEMA = "sp_part_it";
  private static final LocalDate FIRST = LocalDate.of(2026, 9, 1);
  private static final Pattern INDEX_SCAN = Pattern.compile("Index( Only)? Scan using");
  private static final Pattern PARTITION = Pattern.compile("decision_\\d{8}");

  private static List<String> explain(Connection c, String sql) throws SQLException {
    List<String> lines = new ArrayList<>();
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("EXPLAIN (COSTS OFF) " + sql)) {
      while (rs.next()) {
        lines.add(rs.getString(1));
      }
    }
    return lines;
  }

  private static void insertRows(Connection c, int rows, LocalDate day) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute(
          "INSERT INTO decision (decision_id, regime, event_identity, decision_seq,"
              + " decision_context_id, input_digest, as_at, outcome, body)"
              + " SELECT g, 'EMIR', substr(md5(g::text), 1, 16), 1, 1,"
              + " sha256(convert_to(g::text, 'UTF8')), DATE '"
              + day
              + "', 1, NULL FROM generate_series(1, "
              + rows
              + ") AS g");
    }
  }

  @Test
  void lookupWithoutPartitionKeyProbesEveryPartitionIndex() throws Exception {
    try (PgFixture pg = PgFixture.fromSystemProperty().orElseThrow();
        Connection c = pg.primary()) {
      SchemaInstaller.drop(c, SCHEMA);
      try {
        SchemaInstaller.install(c, SCHEMA, Variant.E1_COMPACT, FIRST, 30);
        insertRows(c, 3_000, FIRST.plusDays(29));
        try (Statement st = c.createStatement()) {
          st.execute("ANALYZE decision");
          // the plan shape is what is under test, not the planner's choice for tiny tables
          st.execute("SET enable_seqscan = off");
          st.execute("SET enable_bitmapscan = off");
        }
        List<String> plan =
            explain(c, "SELECT decision_id, as_at FROM decision WHERE event_identity = 'x'");
        long indexScans = plan.stream().filter(l -> INDEX_SCAN.matcher(l).find()).count();
        assertThat(indexScans).as(String.join("\n", plan)).isEqualTo(30);
      } finally {
        SchemaInstaller.drop(c, SCHEMA);
      }
    }
  }

  @Test
  void headPlusPrunedFetchTouchesOnePartition() throws Exception {
    try (PgFixture pg = PgFixture.fromSystemProperty().orElseThrow();
        Connection c = pg.primary()) {
      SchemaInstaller.drop(c, SCHEMA);
      try {
        SchemaInstaller.install(c, SCHEMA, Variant.E1_COMPACT, FIRST, 30);
        LocalDate day = FIRST.plusDays(12);
        insertRows(c, 2_000, day);
        try (Statement st = c.createStatement()) {
          st.execute("ALTER TABLE decision_head ADD COLUMN as_at date");
          st.execute(
              "INSERT INTO decision_head (regime, event_identity, decision_seq, decision_id, as_at)"
                  + " SELECT regime, event_identity, decision_seq, decision_id, as_at FROM decision");
          st.execute("ANALYZE decision");
        }
        long decisionId;
        LocalDate asAt;
        try (Statement st = c.createStatement();
            ResultSet rs =
                st.executeQuery(
                    "SELECT decision_id, as_at FROM decision_head WHERE regime = 'EMIR'"
                        + " AND event_identity = substr(md5('77'), 1, 16)")) {
          assertThat(rs.next()).isTrue();
          decisionId = rs.getLong(1);
          asAt = rs.getObject(2, LocalDate.class);
        }
        assertThat(decisionId).isEqualTo(77);
        assertThat(asAt).isEqualTo(day);

        List<String> plan =
            explain(
                c,
                "SELECT decision_seq, outcome, body FROM decision WHERE decision_id = "
                    + decisionId
                    + " AND as_at = DATE '"
                    + asAt
                    + "'");
        Set<String> touched = new LinkedHashSet<>();
        for (String line : plan) {
          Matcher m = PARTITION.matcher(line);
          while (m.find()) {
            touched.add(m.group());
          }
        }
        assertThat(touched)
            .as(String.join("\n", plan))
            .containsExactly("decision_" + day.toString().replace("-", ""));
      } finally {
        SchemaInstaller.drop(c, SCHEMA);
      }
    }
  }

  @Test
  void detachAndDropAreMetadataOperations() throws Exception {
    try (PgFixture pg = PgFixture.fromSystemProperty().orElseThrow();
        Connection c = pg.primary()) {
      SchemaInstaller.drop(c, SCHEMA);
      try {
        SchemaInstaller.install(c, SCHEMA, Variant.E1_COMPACT, FIRST, 3);
        insertRows(c, 100_000, FIRST);
        String partition = "decision_" + FIRST.toString().replace("-", "");
        assertThat(count(c, "decision")).isEqualTo(100_000);
        long t0 = System.nanoTime();
        try (Statement st = c.createStatement()) {
          st.execute("ALTER TABLE decision DETACH PARTITION " + partition);
        }
        long detach = System.nanoTime() - t0;
        // the rows left the parent and are all still in the detached table
        assertThat(count(c, "decision")).isZero();
        assertThat(count(c, partition)).isEqualTo(100_000);
        t0 = System.nanoTime();
        try (Statement st = c.createStatement()) {
          st.execute("DROP TABLE " + partition);
        }
        long drop = System.nanoTime() - t0;
        // a metadata operation does not scale with its 100,000 rows: generous bound, 5 s each
        assertThat(detach).isLessThan(5_000_000_000L);
        assertThat(drop).isLessThan(5_000_000_000L);
      } finally {
        SchemaInstaller.drop(c, SCHEMA);
      }
    }
  }

  @Test
  void theStudyRunsAndMeasuresEveryPoint() throws Exception {
    try (PgFixture pg = PgFixture.fromSystemProperty().orElseThrow()) {
      List<Point> points = PartitionStudy.run(pg, new int[] {30, 60}, 3, 10, 2_000, 3, 20);
      assertThat(points).hasSize(3);
      assertThat(points.stream().map(Point::granularity).toList())
          .containsExactly(Granularity.DAILY, Granularity.DAILY, Granularity.MONTHLY);
      assertThat(points.stream().map(Point::partitions).toList()).containsExactly(30, 60, 3);
      for (Point p : points) {
        assertThat(p.lookupP99NanosPerBlock()).hasSize(3);
        assertThat(p.headThenPrunedFetchP99NanosPerBlock()).hasSize(3);
        assertThat(Arrays.stream(p.lookupP99NanosPerBlock()).allMatch(v -> v > 0)).isTrue();
        assertThat(Arrays.stream(p.headThenPrunedFetchP99NanosPerBlock()).allMatch(v -> v > 0))
            .isTrue();
        assertThat(p.detachNanos()).isPositive();
        assertThat(p.dropNanos()).isPositive();
      }
    }
  }

  private static long count(Connection c, String table) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
