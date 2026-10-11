package dev.regpack.spikes.l.schema;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.spikes.l.pg.PgFixture;
import dev.regpack.spikes.l.schema.ConstraintChecks.CheckResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Criterion L-12 (review focus RF15), run in the smoke and the misc dispatch only. A failing check
 * is a finding, not a bug to hide: the report states which constraint PostgreSQL 18 cannot express
 * and how it can be re-shaped.
 */
@EnabledIfSystemProperty(named = "spike.pg.url", matches = ".+")
class ConstraintChecksIT {
  @Test
  void everyCheckPasses() throws Exception {
    try (PgFixture pg = PgFixture.fromSystemProperty().orElseThrow()) {
      List<CheckResult> results = ConstraintChecks.runAll(pg);
      assertThat(results.stream().map(CheckResult::name).toList())
          .containsExactlyElementsOf(ConstraintChecks.NAMES);
      for (CheckResult r : results) {
        assertThat(r.passed()).as(r.name() + ": " + r.detail()).isTrue();
      }
    }
  }

  @Test
  void theChecksLeaveNoSchemaBehind() throws Exception {
    try (PgFixture pg = PgFixture.fromSystemProperty().orElseThrow()) {
      ConstraintChecks.runAll(pg);
      try (var c = pg.primary();
          var st = c.createStatement();
          var rs =
              st.executeQuery(
                  "SELECT count(*) FROM pg_namespace WHERE nspname LIKE 'sp\\_chk\\_c%'")) {
        rs.next();
        assertThat(rs.getInt(1)).isZero();
      }
    }
  }
}
