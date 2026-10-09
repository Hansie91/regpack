package dev.regpack.engine.skeleton;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.IngestKey;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.CanonicalExecutionCodec;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.regime.RegimeProvider.EvaluationContext;
import dev.regpack.testkit.golden.GoldenAudit;
import dev.regpack.testkit.golden.GoldenCase;
import dev.regpack.testkit.golden.GoldenCaseLoader;
import dev.regpack.testkit.golden.ReviewerRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Runs the repository's golden cases through the walking-skeleton regime and audits them. */
class RepositoryGoldenAuditTest {
  private static final Instant EVALUATED_AT = Instant.parse("2026-10-09T10:00:00Z");

  private static List<GoldenCase> goldenCases() {
    Path dir = Path.of(System.getProperty("regpack.test.classes.dir"), "golden");
    return GoldenCaseLoader.loadDirectory(dir);
  }

  @Test
  void everyGoldenCaseRunsThroughTheEngineAndMatchesItsExpectation() {
    WalkingSkeletonRegime regime = WalkingSkeletonRegime.standard();
    List<GoldenCase> cases = goldenCases();
    assertThat(cases)
        .extracting(GoldenCase::id)
        .containsExactly("GC-DEFAULT-1", "GC-N03-1", "GC-N08-1", "GC-N13-1");

    for (GoldenCase golden : cases) {
      CanonicalExecution execution = CanonicalExecutionCodec.fromMap(golden.input());
      EvaluationContext context =
          new EvaluationContext(
              WalkingSkeletonRegime.skeletonContext(),
              new IngestKey("golden", golden.id(), 1),
              EVALUATED_AT,
              new CostBudget(100));

      DecisionBody body = regime.evaluate(execution, context).decision();

      assertThat(body.outcome()).as(golden.id()).isEqualTo(golden.expected().outcome());
      assertThat(body.reasonCode()).as(golden.id()).isEqualTo(golden.expected().reasonCode());
      assertThat(body.ruleIdsHit()).as(golden.id()).isEqualTo(golden.expected().ruleIdsHit());
      assertThat(body.node()).as(golden.id()).isEqualTo(golden.node());
    }
  }

  @Test
  void everyGoldenCaseIsAuditedAndNoneCounts() throws IOException {
    Path repoRoot = Path.of(System.getProperty("regpack.repo.root"));
    ReviewerRegistry reviewers =
        ReviewerRegistry.load(repoRoot.resolve("governance").resolve("golden-reviewers.txt"));

    List<GoldenAudit.Row> rows =
        GoldenAudit.audit(goldenCases(), reviewers, WalkingSkeletonRules.authorsById());

    assertThat(rows).hasSize(4);
    assertThat(rows).allSatisfy(row -> assertThat(row.countsForTraceability()).isFalse());
    assertThat(rows).allSatisfy(row -> assertThat(row.problems()).isEmpty());

    Path buildDir = Path.of(System.getProperty("regpack.build.dir"));
    Files.createDirectories(buildDir);
    Files.writeString(
        buildDir.resolve("golden-audit.tsv"), GoldenAudit.toTsv(rows), StandardCharsets.UTF_8);
  }
}
