package dev.regpack.engine.packci;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.engine.pack.PackBuilder;
import dev.regpack.engine.pack.SamplePacks;
import dev.regpack.model.Outcome;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.golden.ReviewerRegistry;
import dev.regpack.pack.model.DecisionTable;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.pack.model.TableRow;
import dev.regpack.pack.source.PackSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The pack-level test command (spec 6.5, plan T20). The sample pack is run at run time: every
 * outcome, reason, node, priority and {@code when} of its rows is exercised by a golden case, so a
 * wrong value makes a test here fail (review note F2 of T10, review focus PF15).
 */
class PackTesterTest {
  private static final String SAMPLE_DIGEST =
      "a421f125754b61c1682ab26021611b380e5c97409adb97f125175c1d4ac0b4f6";
  private static final String SCOPE = "scope/sample-scope.yaml";
  private static final String REASONS = "reasons.yaml";
  private static final ReviewerRegistry NO_REVIEWERS = ReviewerRegistry.parse("# none\n");
  private static final String CONSISTENCY = "\"provenance\": {\"kind\": \"CONSISTENCY_ONLY\"},";

  /** A failing case exercises nothing, so its row, reason and provision are uncovered too. */
  private static final List<String> FAILED_CASE_FALLOUT =
      List.of(
          "GOLDEN_CASE_FAILED",
          "COVERAGE_ROW_NOT_HIT",
          "COVERAGE_REASON_NOT_REACHED",
          "COVERAGE_PROVISION_NOT_EXERCISED");

  private static final List<String> SAMPLE_OUTPUT =
      List.of(
          "pack test: sample.s0 0.1.0 digest " + SAMPLE_DIGEST,
          "files: 11 (CHECKSUMS verified)",
          "signature: UNSIGNED (an ATTESTATION is not verified before P9b)",
          "static checks: 0 problems",
          "engine: 1.0.0+lib1 satisfies engine_min 1.0.0+lib1",
          "golden: 4 of 4 pass",
          "coverage: rows 4/4, reasons 4/4, provisions 4/4 (all cases); rows 0/4 (counting cases)",
          "audit: 0 of 4 counting, 4 consistency-only; reviewers registered: 0 from none (empty registry)"
              + " (gate G1: no named Compliance owner)",
          "budget: record budget 40 units (static worst case 20); largest rule 5 nodes;"
              + " rule ceiling 512; SP-R recommended 64",
          "pack test: PASS");

  private static PackTestReport run(PackSource source) {
    return PackTester.run(source, NO_REVIEWERS, PackTester.NO_REGISTRY);
  }

  private static PackTestReport runSample(Map<String, String> overrides) {
    return run(SamplePacks.sealed(overrides));
  }

  private static List<String> codes(PackTestReport report) {
    List<String> codes = new ArrayList<>();
    for (PackProblem p : report.problems()) {
      codes.add(p.code());
    }
    return codes;
  }

  private static String last(PackTestReport report) {
    return report.lines().get(report.lines().size() - 1);
  }

  private static String sampleFile(String path) {
    return SamplePacks.files().get(path);
  }

  private static Map<String, String> with(String path, String text) {
    Map<String, String> map = new LinkedHashMap<>();
    map.put(path, text);
    return map;
  }

  // ---------------------------------------------------------------------------------------------

  @Test
  void theSamplePackPassesWithTheDocumentedLines() {
    PackTestReport report = run(PackSource.fromDirectory(SamplePacks.directory()));

    assertThat(report.lines()).containsExactlyElementsOf(SAMPLE_OUTPUT);
    assertThat(report.problems()).isEmpty();
    assertThat(report.passed()).isTrue();
    assertThat(report.render()).isEqualTo(String.join("\n", SAMPLE_OUTPUT));
  }

  @Test
  void theSampleRowsAreExactlyThePinnedOnes() {
    // outcome, reason, node, priority and when of every row, the facts the goldens run against
    LoadedPack pack = SamplePacks.load();
    DecisionTable table = pack.scopeTables().get(SCOPE);
    List<String> rows = new ArrayList<>();
    for (TableRow row : table.rows()) {
      rows.add(
          row.id()
              + "|"
              + row.node()
              + "|"
              + row.priority()
              + "|"
              + row.outcome()
              + "|"
              + row.reason().value()
              + "|"
              + row.when());
    }

    assertThat(rows)
        .containsExactly(
            "S-N03-novation|N03|300|DO_NOT_REPORT|SAMPLE.N03.EXCLUDED"
                + "|event.kind in ['NOVATION','ASSIGNMENT']",
            "S-D14-natural-person|N13|400|UNDETERMINED|SAMPLE.N13.NATURAL_PERSON"
                + "|client.kind == 'NATURAL_PERSON'",
            "S-N08-equity-on-venue|N08|800|REPORT|SAMPLE.N08.IN_SCOPE"
                + "|instrument.assetClass == 'EQUITY' && instrument.tradedOnVenue",
            "S-DEFAULT-catch-all|DEFAULT|9999|UNDETERMINED|SAMPLE.DEFAULT.NO_RULE_HIT|true");
    assertThat(table.rows().get(0).outcome()).isEqualTo(Outcome.DO_NOT_REPORT);
  }

  @Test
  void aFailingGoldenCaseFailsThePack() {
    String broken =
        SamplePacks.replaced(
            "tests/golden/GC-N08-1.json",
            "\"outcome\": \"REPORT\"",
            "\"outcome\": \"DO_NOT_REPORT\"");

    PackTestReport report = runSample(with("tests/golden/GC-N08-1.json", broken));

    assertThat(report.passed()).isFalse();
    assertThat(report.lines()).contains("golden: 3 of 4 pass");
    assertThat(codes(report))
        .containsExactlyInAnyOrder(
            "GOLDEN_CASE_FAILED",
            "COVERAGE_ROW_NOT_HIT",
            "COVERAGE_REASON_NOT_REACHED",
            "COVERAGE_PROVISION_NOT_EXERCISED");
    assertThat(last(report)).isEqualTo("pack test: FAIL (4 problems)");
    assertThat(report.lines())
        .anyMatch(l -> l.startsWith("problem GOLDEN_CASE_FAILED tests/golden/GC-N08-1.json "));
  }

  @Test
  void aWrongWhenFailsTheSamplePack() {
    String text =
        SamplePacks.replaced(
            SCOPE, "client.kind == 'NATURAL_PERSON'", "client.kind == 'LEGAL_ENTITY'");

    PackTestReport report = runSample(with(SCOPE, text));

    assertThat(report.passed()).isFalse();
    assertThat(codes(report)).contains("GOLDEN_CASE_FAILED");
    assertThat(report.lines()).noneMatch(l -> l.equals("golden: 4 of 4 pass"));
  }

  @Test
  void aWrongOutcomeFailsTheSamplePackEvenWhenTheReasonIsRight() {
    Map<String, String> overrides = new LinkedHashMap<>();
    overrides.put(
        SCOPE, SamplePacks.replaced(SCOPE, "outcome: DO_NOT_REPORT", "outcome: UNDETERMINED"));
    overrides.put(
        REASONS,
        SamplePacks.replaced(
            REASONS, "default_outcome: DO_NOT_REPORT", "default_outcome: UNDETERMINED"));

    PackTestReport report = runSample(overrides);

    assertThat(report.passed()).isFalse();
    assertThat(codes(report)).containsExactlyInAnyOrderElementsOf(FAILED_CASE_FALLOUT);
    assertThat(report.lines()).contains("golden: 3 of 4 pass");
    assertThat(report.lines())
        .anyMatch(l -> l.contains("GC-N03-1") || l.contains("S-N03-novation"));
  }

  @Test
  void aWrongReasonFailsTheSamplePack() {
    Map<String, String> overrides = new LinkedHashMap<>();
    overrides.put(
        SCOPE,
        SamplePacks.replaced(SCOPE, "reason: SAMPLE.N08.IN_SCOPE", "reason: SAMPLE.N08.OTHER"));
    overrides.put(
        REASONS,
        sampleFile(REASONS)
            + "  - {code: SAMPLE.N08.OTHER, label: OTHER, category: INCLUSION,"
            + " default_outcome: REPORT, owner: agent.p2a-sample, valid: {from: 2018-01-03}}\n");

    PackTestReport report = runSample(overrides);

    assertThat(report.passed()).isFalse();
    assertThat(codes(report)).contains("GOLDEN_CASE_FAILED");
    assertThat(report.lines()).contains("golden: 3 of 4 pass");
  }

  @Test
  void aWrongNodeFailsTheSamplePack() {
    PackTestReport report =
        runSample(with(SCOPE, SamplePacks.replaced(SCOPE, "node: N08", "node: N13")));

    assertThat(report.passed()).isFalse();
    assertThat(codes(report)).containsExactlyInAnyOrderElementsOf(FAILED_CASE_FALLOUT);
    assertThat(report.lines()).contains("golden: 3 of 4 pass");
  }

  @Test
  void aWrongPriorityIsCaughtByACaseWhereTwoRowsOverlap() {
    // a natural person who novates matches both the N03 row and the natural-person row; the
    // priority decides. The four sample cases never overlap, so the case is added here.
    String overlap =
        SamplePacks.replaced("tests/golden/GC-N03-1.json", "GC-N03-1", "GC-OVERLAP-1")
            .replace("\"clientKind\": \"LEGAL_ENTITY\"", "\"clientKind\": \"NATURAL_PERSON\"")
            .replace("\"clientLei\": \"LEI-CLIENT-0001\",", "");
    Map<String, String> good = with("tests/golden/GC-OVERLAP-1.json", overlap);

    PackTestReport baseline = runSample(good);

    assertThat(baseline.problems()).isEmpty();
    assertThat(baseline.lines()).contains("golden: 5 of 5 pass");

    Map<String, String> swapped = new LinkedHashMap<>(good);
    swapped.put(SCOPE, SamplePacks.replaced(SCOPE, "priority: 300", "priority: 500"));
    PackTestReport report = runSample(swapped);

    assertThat(report.passed()).isFalse();
    assertThat(codes(report)).containsExactly("GOLDEN_CASE_FAILED");
    assertThat(report.lines()).contains("golden: 4 of 5 pass");
  }

  @Test
  void aStaticProblemStopsBeforeEvaluation() {
    PackTestReport report =
        runSample(with(SCOPE, SamplePacks.replaced(SCOPE, "priority: 400", "priority: 300")));

    assertThat(report.passed()).isFalse();
    assertThat(codes(report)).containsExactly("PRIORITY_DUPLICATE");
    assertThat(report.lines()).noneMatch(l -> l.startsWith("golden:"));
    assertThat(report.lines()).noneMatch(l -> l.startsWith("coverage:"));
    assertThat(report.lines()).noneMatch(l -> l.startsWith("budget:"));
    assertThat(report.lines()).contains("static checks: 1 problems");
    assertThat(report.lines()).anyMatch(l -> l.startsWith("problem PRIORITY_DUPLICATE " + SCOPE));
    assertThat(last(report)).isEqualTo("pack test: FAIL (1 problems)");
  }

  @Test
  void aGoldenFileWithoutProvenanceIsReportedAndStopsBeforeEvaluation() {
    String text = SamplePacks.replaced("tests/golden/GC-N03-1.json", CONSISTENCY, "");

    PackTestReport report = runSample(with("tests/golden/GC-N03-1.json", text));

    assertThat(codes(report)).containsExactly("GOLDEN_NO_PROVENANCE");
    assertThat(report.lines()).noneMatch(l -> l.startsWith("golden:"));
    assertThat(last(report)).isEqualTo("pack test: FAIL (1 problems)");
  }

  @Test
  void aGoldenNodeThatTheReasonRegistryDoesNotDeclareFails() {
    String text =
        SamplePacks.replaced(
            "tests/golden/GC-N03-1.json", "\"node\": \"N03\"", "\"node\": \"N99\"");

    PackTestReport report = runSample(with("tests/golden/GC-N03-1.json", text));

    assertThat(codes(report)).containsExactly("GOLDEN_NODE_UNDECLARED");
    assertThat(report.passed()).isFalse();
  }

  @Test
  void multipleReportsAreRefused() {
    PackSource source =
        PackBuilder.sample()
            .secondReport(
                "sample.second", PackBuilder.Rule.of("R-DEBT", "instrument.assetClass == 'DEBT'"))
            .source();

    PackTestReport report = run(source);

    assertThat(codes(report)).contains("MULTI_REPORT_NOT_SUPPORTED_BY_PACK_TEST");
    assertThat(report.lines()).noneMatch(l -> l.startsWith("golden:"));
    assertThat(last(report)).isEqualTo("pack test: FAIL (1 problems)");
  }

  @Test
  void aPackWhoseEngineMinIsNotSatisfiedFails() {
    PackSource source = PackBuilder.sample().engineMin("2.0.0+lib1").source();

    PackTestReport report = run(source);

    assertThat(codes(report)).contains("ENGINE_MIN_NOT_SATISFIED");
    assertThat(report.lines())
        .contains("engine: 1.0.0+lib1 does not satisfy engine_min 2.0.0+lib1");
    assertThat(report.passed()).isFalse();
  }

  @Test
  void unsignedIsAlwaysPrinted() {
    String unsigned = "signature: UNSIGNED (an ATTESTATION is not verified before P9b)";

    assertThat(run(PackSource.fromDirectory(SamplePacks.directory())).lines()).contains(unsigned);
    PackTestReport failing =
        runSample(with(SCOPE, SamplePacks.replaced(SCOPE, "priority: 400", "priority: 300")));
    assertThat(failing.passed()).isFalse();
    assertThat(failing.lines()).contains(unsigned);
  }

  @Test
  void notInterpretedSectionsAreListed() {
    PackTestReport report = runSample(with("lifecycle.yaml", "business_key: [event.kind]\n"));

    assertThat(report.problems()).isEmpty();
    assertThat(report.lines()).contains("not interpreted by this engine build: lifecycle.yaml");
    assertThat(report.lines()).contains("files: 12 (CHECKSUMS verified)");
    assertThat(last(report)).isEqualTo("pack test: PASS");
  }

  @Test
  void aClaimedReviewWithAnUnregisteredReviewerFails() {
    String claimed =
        SamplePacks.replaced(
            "tests/golden/GC-N03-1.json",
            CONSISTENCY,
            "\"provenance\": {\"kind\": \"ESMA_QA\", \"source\": \"synthetic-source\","
                + " \"reviewer\": \"reviewer.test\", \"reviewedOn\": \"2026-10-01\"},");

    PackTestReport report = runSample(with("tests/golden/GC-N03-1.json", claimed));

    assertThat(report.passed()).isFalse();
    assertThat(codes(report)).containsExactly("GOLDEN_AUDIT");
    assertThat(report.lines())
        .anyMatch(
            l ->
                l.startsWith("problem GOLDEN_AUDIT tests/golden/GC-N03-1.json")
                    && l.contains("REVIEWER_NOT_REGISTERED"));
    assertThat(report.lines())
        .contains(
            "audit: 0 of 4 counting, 3 consistency-only; reviewers registered: 0 from none (empty registry)"
                + " (gate G1: no named Compliance owner)");
  }

  @Test
  void aClaimedReviewWithARegisteredReviewerOtherThanTheAuthorCounts() {
    String claimed =
        SamplePacks.replaced(
            "tests/golden/GC-N03-1.json",
            CONSISTENCY,
            "\"provenance\": {\"kind\": \"ESMA_QA\", \"source\": \"synthetic-source\","
                + " \"reviewer\": \"reviewer.test\", \"reviewedOn\": \"2026-10-01\"},");
    ReviewerRegistry registry = ReviewerRegistry.parse("reviewer.test|Test Reviewer|test role\n");

    PackTestReport report =
        PackTester.run(
            SamplePacks.sealed(with("tests/golden/GC-N03-1.json", claimed)),
            registry,
            "reviewers.txt");

    assertThat(report.problems()).isEmpty();
    assertThat(report.passed()).isTrue();
    assertThat(report.lines())
        .contains(
            "audit: 1 of 4 counting, 3 consistency-only; reviewers registered: 1 from reviewers.txt");
    assertThat(report.lines())
        .contains(
            "coverage: rows 4/4, reasons 4/4, provisions 4/4 (all cases); rows 1/4 (counting cases)");
  }

  @Test
  void theAuditLineNamesTheRegistryAndEscapesAHostileName() {
    String hostile = "r" + (char) 0x1B + new String(Character.toChars(0x202E)) + "x".repeat(2000);

    PackTestReport report = PackTester.run(SamplePacks.sealed(Map.of()), NO_REVIEWERS, hostile);

    String audit = report.lines().stream().filter(l -> l.startsWith("audit: ")).findFirst().get();
    assertThat(audit)
        .contains("reviewers registered: 0 from r\\u001b\\u202exxx")
        .endsWith(" (gate G1: no named Compliance owner)")
        .hasSizeLessThan(500);
    assertThat(audit.chars().allMatch(c -> c >= 0x20 && c <= 0x7E)).isTrue();
  }

  @Test
  void aReviewerWhoAuthoredARuleDoesNotCount() {
    String claimed =
        SamplePacks.replaced(
            "tests/golden/GC-N03-1.json",
            CONSISTENCY,
            "\"provenance\": {\"kind\": \"ESMA_QA\", \"source\": \"synthetic-source\","
                + " \"reviewer\": \"agent:p2a-implementer\", \"reviewedOn\": \"2026-10-01\"},");
    ReviewerRegistry registry =
        ReviewerRegistry.parse("agent:p2a-implementer|The Implementer|test role\n");

    PackTestReport report =
        PackTester.run(
            SamplePacks.sealed(with("tests/golden/GC-N03-1.json", claimed)),
            registry,
            "reviewers.txt");

    assertThat(report.passed()).isFalse();
    assertThat(codes(report)).containsExactly("GOLDEN_AUDIT");
    assertThat(report.lines()).anyMatch(l -> l.contains("REVIEWER_IS_AUTHOR"));
  }

  private static final String SECOND_CATCH_ALL = "sample.s0.scope2-catch-all";

  /**
   * A two-table chain with the sample's row ids: T1 holds the novation and natural-person rows, T2
   * the equity row; each table ends in its own catch-all. The four sample goldens fit it, except
   * that an unmatched record is decided by the LAST table's catch-all.
   */
  private static PackBuilder chain() {
    return PackBuilder.sample()
        .chain(
            List.of(
                PackBuilder.Rule.of("S-N03-novation", "event.kind in ['NOVATION','ASSIGNMENT']")
                    .outcome("N03", "DO_NOT_REPORT", "SAMPLE.N03.EXCLUDED"),
                PackBuilder.Rule.of("S-D14-natural-person", "client.kind == 'NATURAL_PERSON'")
                    .outcome("N13", "UNDETERMINED", "SAMPLE.N13.NATURAL_PERSON")),
            List.of(
                PackBuilder.Rule.of(
                    "S-N08-equity-on-venue",
                    "instrument.assetClass == 'EQUITY' && instrument.tradedOnVenue")))
        .file(
            "tests/authorship.yaml",
            sampleFile("tests/authorship.yaml").stripTrailing()
                + "\n  "
                + SECOND_CATCH_ALL
                + ": \"agent:p2a-implementer\"\n");
  }

  private static final String DEFAULT_GOLDEN = "tests/golden/GC-DEFAULT-1.json";

  /** The unmatched-record golden of the chain: the LAST table's catch-all decides. */
  private static PackBuilder chainWithGoldens() {
    return chain()
        .file(
            DEFAULT_GOLDEN,
            SamplePacks.replaced(
                DEFAULT_GOLDEN,
                "\"ruleIdsHit\": [\"S-DEFAULT-catch-all\"]",
                "\"ruleIdsHit\": [\"" + SECOND_CATCH_ALL + "\"]"));
  }

  @Test
  void aTwoTableChainPassesWithEveryRowCovered() {
    PackTestReport report =
        PackTester.run(chainWithGoldens().source(), NO_REVIEWERS, PackTester.NO_REGISTRY);

    assertThat(report.problems()).isEmpty();
    assertThat(report.passed()).isTrue();
    assertThat(report.lines()).contains("golden: 4 of 4 pass");
    assertThat(report.lines())
        .contains(
            "coverage: rows 5/5, reasons 4/4, provisions 2/2 (all cases); rows 0/5 (counting cases)");
  }

  @Test
  void aCatchAllThatIsPassedThroughIsCoveredByTheCaseThatReachesTheNextTable() {
    // no golden decides on the FIRST table's catch-all (it never decides when a second table
    // follows); the equity case passes through it, which exercises the row
    PackSource source =
        chain().file(DEFAULT_GOLDEN, null).file("tests/golden/GC-N13-1.json", null).source();

    PackTestReport report = PackTester.run(source, NO_REVIEWERS, PackTester.NO_REGISTRY);

    assertThat(report.lines()).contains("golden: 2 of 2 pass");
    assertThat(report.lines())
        .noneMatch(
            l ->
                l.startsWith("problem COVERAGE_ROW_NOT_HIT ")
                    && l.endsWith(" S-DEFAULT-catch-all"));
    assertThat(report.lines())
        .anyMatch(
            l ->
                l.startsWith("problem COVERAGE_ROW_NOT_HIT ")
                    && l.endsWith(" " + SECOND_CATCH_ALL));
    assertThat(report.lines())
        .anyMatch(
            l ->
                l.startsWith("problem COVERAGE_ROW_NOT_HIT ")
                    && l.endsWith(" S-D14-natural-person"));
  }

  @Test
  void aChainWhoseLaterTableIsNeverReachedFailsTheCoverageGate() {
    // only the first table's own rows are exercised: the first catch-all, the equity row and the
    // last catch-all are never matched
    PackSource source =
        chain().file(DEFAULT_GOLDEN, null).file("tests/golden/GC-N08-1.json", null).source();

    PackTestReport report = PackTester.run(source, NO_REVIEWERS, PackTester.NO_REGISTRY);

    assertThat(report.passed()).isFalse();
    assertThat(codes(report)).contains("COVERAGE_ROW_NOT_HIT", "COVERAGE_REASON_NOT_REACHED");
    assertThat(report.lines()).anyMatch(l -> l.endsWith(" S-DEFAULT-catch-all"));
    assertThat(report.lines()).anyMatch(l -> l.endsWith(" S-N08-equity-on-venue"));
    assertThat(report.lines()).anyMatch(l -> l.endsWith(" " + SECOND_CATCH_ALL));
  }

  @Test
  void aGoldenExpectingAPassedThroughCatchAllAsTheDecidingRowFails() {
    // the golden's ruleIdsHit is the DECIDING row; the first table's catch-all never decides
    // the unchanged sample golden expects [S-DEFAULT-catch-all]
    PackTestReport report = PackTester.run(chain().source(), NO_REVIEWERS, PackTester.NO_REGISTRY);

    assertThat(codes(report)).contains("GOLDEN_CASE_FAILED");
    assertThat(report.lines()).contains("golden: 3 of 4 pass");
  }

  private static final String BAD_TIME = "\"tradedAt\": \"2026-10-09T09:30:00.123456789Z\"";
  private static final String GOOD_TIME = "\"tradedAt\": \"2026-10-09T09:30:00.123456Z\"";

  @Test
  void aGoldenInputThatCannotBeCanonicalisedIsReportedNotThrown() {
    String[][] bad = {
      {GOOD_TIME, BAD_TIME},
      {GOOD_TIME, "\"tradedAt\": \"+10000-01-01T00:00:00Z\""},
      {"\"venueExecutionId\": \"EX-3\"", "\"venueExecutionId\": \"EX|3\""},
      {"\"firmLei\": \"LEI-FIRM-0001\"", "\"firmLei\": \"LEI|FIRM\""},
    };
    for (String[] change : bad) {
      String text = SamplePacks.replaced("tests/golden/GC-N08-1.json", change[0], change[1]);

      PackTestReport report = runSample(with("tests/golden/GC-N08-1.json", text));

      assertThat(report.passed()).as(change[1]).isFalse();
      assertThat(report.problems())
          .as(change[1])
          .anyMatch(
              p ->
                  p.code().equals("GOLDEN_INPUT_INVALID")
                      && p.path().equals("tests/golden/GC-N08-1.json")
                      && p.message().length() < 400);
      assertThat(last(report)).startsWith("pack test: FAIL");
      assertThat(report.lines()).contains("golden: 3 of 4 pass");
    }
  }

  @Test
  void theOutputIsDeterministic() {
    PackSource source = PackSource.fromDirectory(SamplePacks.directory());

    assertThat(run(source).render()).isEqualTo(run(source).render());
    PackSource broken =
        SamplePacks.sealed(
            with(SCOPE, SamplePacks.replaced(SCOPE, "priority: 400", "priority: 300")));
    assertThat(run(broken).render()).isEqualTo(run(broken).render());
  }
}
