package dev.regpack.pack.golden;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.canonical.CanonicalExecutionCodec;
import dev.regpack.pack.PackFixtures;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.golden.GoldenAudit.Row;
import dev.regpack.pack.golden.GoldenCase.ProvenanceKind;
import dev.regpack.pack.golden.PackGoldenLoader.Loaded;
import dev.regpack.pack.source.PackSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Review focus PF14: the pack path of the golden harness is stricter than the lenient P0 loader
 * (plan decision D-P6). A case whose provenance is missing, partial or contradictory is refused
 * with a code; it is never labelled consistency-only silently.
 */
class PackGoldenStrictnessTest {
  private static final String PREFIX = "tests/golden/";
  private static final String CONSISTENCY = ",\"provenance\":{\"kind\":\"CONSISTENCY_ONLY\"}";
  private static final ReviewerRegistry REGISTRY =
      ReviewerRegistry.parse("rev-1|Test Reviewer|Compliance\nagent:author|Rule Author|Rules");

  /** A complete case; {@code provenance} is the text of the provenance member or empty. */
  private static String caseJson(String id, String author, String description, String provenance) {
    return "{\"id\":\""
        + id
        + "\",\"node\":\"N03\",\"description\":\""
        + description
        + "\",\"author\":\""
        + author
        + "\",\"input\":{\"clientKind\":\"LEGAL_ENTITY\",\"price\":12.25,\"quantity\":10},"
        + "\"expected\":{\"outcome\":\"DO_NOT_REPORT\",\"reasonCode\":\"FIX.N03.EXCLUDED\","
        + "\"ruleIdsHit\":[\"A-N03-novation\"]}"
        + provenance
        + "}";
  }

  private static String provenance(String kind, String source, String reviewer, String date) {
    StringBuilder sb = new StringBuilder(",\"provenance\":{\"kind\":\"").append(kind).append('"');
    if (source != null) {
      sb.append(",\"source\":\"").append(source).append('"');
    }
    if (reviewer != null) {
      sb.append(",\"reviewer\":\"").append(reviewer).append('"');
    }
    if (date != null) {
      sb.append(",\"reviewedOn\":\"").append(date).append('"');
    }
    return sb.append('}').toString();
  }

  /** The fixture pack with its minimal golden file replaced by the given files. */
  private static PackFixtures fixture(String... pathAndJson) {
    PackFixtures f = PackFixtures.valid().remove(PackFixtures.GOLDEN);
    for (int i = 0; i < pathAndJson.length; i += 2) {
      f.put(PREFIX + pathAndJson[i] + ".json", pathAndJson[i + 1]);
    }
    return f;
  }

  private static Loaded load(PackFixtures f) {
    return PackGoldenLoader.load(f.unsealed());
  }

  private static List<String> codes(Loaded loaded) {
    return loaded.problems().stream().map(PackProblem::code).toList();
  }

  private static boolean has(Loaded loaded, String code, String path) {
    return loaded.problems().stream().anyMatch(p -> p.code().equals(code) && p.path().equals(path));
  }

  @Test
  void aCaseWithoutProvenanceIsRefusedEvenThoughTheLenientLoaderAcceptsIt() {
    String json = caseJson("GC-1", "agent:golden", "d", "");

    assertThat(GoldenCaseLoader.parse(json).provenance().kind())
        .isEqualTo(ProvenanceKind.CONSISTENCY_ONLY);
    Loaded loaded = load(fixture("GC-1", json));

    assertThat(codes(loaded)).containsExactly("GOLDEN_NO_PROVENANCE");
    assertThat(loaded.cases()).isEmpty();
  }

  @Test
  void escapedControlAndBidiCharactersInGoldenStringsAreRefused() {
    // the JSON escapes decode to U+001B and U+202E; no raw control character is in the file
    String esc = caseJson("GC-1", "agent:golden", "plain \\u001b[31m red", CONSISTENCY);
    String bidi = caseJson("GC-2", "agent:golden", "abc\\u202edef", CONSISTENCY);
    String key =
        caseJson("GC-3", "agent:golden", "d", CONSISTENCY).replace("\"price\"", "\"pri\\u0007ce\"");

    Loaded loaded = load(fixture("GC-1", esc, "GC-2", bidi, "GC-3", key));

    assertThat(has(loaded, "TEXT_BAD_CHARACTER", PREFIX + "GC-1.json")).isTrue();
    assertThat(has(loaded, "TEXT_BAD_CHARACTER", PREFIX + "GC-2.json")).isTrue();
    assertThat(has(loaded, "TEXT_BAD_CHARACTER", PREFIX + "GC-3.json")).isTrue();
    assertThat(loaded.cases()).isEmpty();
    assertThat(loaded.problems())
        .allSatisfy(p -> assertThat(p.message()).doesNotContain("\u001b").doesNotContain("‮"));
  }

  @Test
  void consistencyOnlyMustNotCarryReviewFields() {
    Loaded loaded =
        load(
            fixture(
                "GC-1",
                caseJson(
                    "GC-1",
                    "agent:golden",
                    "d",
                    provenance("CONSISTENCY_ONLY", "Q&A 1699", null, null)),
                "GC-2",
                caseJson(
                    "GC-2",
                    "agent:golden",
                    "d",
                    provenance("CONSISTENCY_ONLY", null, "rev-1", null)),
                "GC-3",
                caseJson(
                    "GC-3",
                    "agent:golden",
                    "d",
                    provenance("CONSISTENCY_ONLY", null, null, "2026-10-09"))));

    assertThat(codes(loaded))
        .containsExactly(
            "GOLDEN_CONSISTENCY_WITH_FIELDS",
            "GOLDEN_CONSISTENCY_WITH_FIELDS",
            "GOLDEN_CONSISTENCY_WITH_FIELDS");
    assertThat(loaded.cases()).isEmpty();
  }

  @Test
  void aRealKindNeedsSourceReviewerAndDate() {
    Loaded none =
        load(
            fixture(
                "GC-1",
                caseJson(
                    "GC-1",
                    "agent:golden",
                    "d",
                    provenance("REGULATOR_WORKBOOK", null, null, null))));
    assertThat(codes(none))
        .containsExactlyInAnyOrder(
            "GOLDEN_NO_SOURCE", "GOLDEN_NO_REVIEWER", "GOLDEN_NO_REVIEW_DATE");
    assertThat(none.cases()).isEmpty();

    Loaded blank =
        load(
            fixture(
                "GC-1",
                caseJson(
                    "GC-1",
                    "agent:golden",
                    "d",
                    provenance("ESMA_QA", " ", "rev-1", "2026-10-09"))));
    assertThat(codes(blank)).containsExactly("GOLDEN_NO_SOURCE");

    Loaded full =
        load(
            fixture(
                "GC-1",
                caseJson(
                    "GC-1",
                    "agent:golden",
                    "d",
                    provenance("ESMA_QA", "Q&A 1699", "rev-1", "2026-10-09"))));
    assertThat(full.problems()).isEmpty();
    assertThat(full.cases()).hasSize(1);
    assertThat(full.cases().get(0).provenance().kind()).isEqualTo(ProvenanceKind.ESMA_QA);
  }

  @Test
  void fileNameMustEqualTheCaseId() {
    Loaded loaded = load(fixture("GC-9", caseJson("GC-1", "agent:golden", "d", CONSISTENCY)));

    assertThat(codes(loaded)).containsExactly("GOLDEN_ID_FILE_MISMATCH");
    assertThat(loaded.cases()).isEmpty();
  }

  @Test
  void duplicateIdsAreRefused() {
    String json = caseJson("GC-1", "agent:golden", "d", CONSISTENCY);

    Loaded loaded = load(fixture("GC-1", json, "GC-2", json));

    assertThat(codes(loaded)).contains("GOLDEN_ID_DUPLICATE", "GOLDEN_ID_FILE_MISMATCH");
    assertThat(loaded.cases()).extracting(GoldenCase::id).doesNotContain("GC-2");
  }

  @Test
  void malformedJsonAndUnknownKeysAreGoldenJsonInvalid() {
    String unknownKey =
        caseJson("GC-2", "agent:golden", "d", CONSISTENCY)
            .replace("{\"id\"", "{\"extra\":\"x\",\"id\"");
    String duplicateKey =
        caseJson("GC-3", "agent:golden", "d", CONSISTENCY)
            .replace("{\"id\"", "{\"id\":\"GC-3\",\"id\"");
    String unknownKind =
        caseJson("GC-4", "agent:golden", "d", provenance("GUESS", null, null, null));
    String notAnObject = "[]";

    Loaded loaded =
        load(
            fixture(
                "GC-1", "{\"id\":",
                "GC-2", unknownKey,
                "GC-3", duplicateKey,
                "GC-4", unknownKind,
                "GC-5", notAnObject));

    assertThat(loaded.problems()).hasSize(5);
    assertThat(codes(loaded)).containsOnly("GOLDEN_JSON_INVALID");
    assertThat(loaded.cases()).isEmpty();
  }

  @Test
  void authorshipMustCoverEveryRowAndNoOther() {
    String ok = caseJson("GC-1", "agent:golden", "d", CONSISTENCY);

    Loaded missingRow =
        load(
            fixture("GC-1", ok)
                .replace(PackFixtures.AUTHORSHIP, "  B-DEFAULT: \"agent:author\"\n", ""));
    assertThat(codes(missingRow)).containsExactly("AUTHOR_MISSING");
    assertThat(missingRow.problems().get(0).message()).contains("B-DEFAULT");

    Loaded unknownRule =
        load(
            fixture("GC-1", ok)
                .replace(
                    PackFixtures.AUTHORSHIP,
                    "  B-DEFAULT: \"agent:author\"\n",
                    "  B-DEFAULT: \"agent:author\"\n  Z-GHOST: \"agent:author\"\n"));
    assertThat(codes(unknownRule)).containsExactly("AUTHOR_UNKNOWN_RULE");
    assertThat(unknownRule.authorByRuleId()).containsKey("A-N03-novation").containsKey("Z-GHOST");

    Loaded noFile = load(fixture("GC-1", ok).remove(PackFixtures.AUTHORSHIP));
    assertThat(codes(noFile)).containsExactly("AUTHORSHIP_MISSING_FILE");

    Loaded complete = load(fixture("GC-1", ok));
    assertThat(complete.problems()).isEmpty();
    assertThat(complete.authorByRuleId())
        .containsOnlyKeys(
            "A-N03-novation", "A-N08-equity", "A-DEFAULT", "B-N13-natural-person", "B-DEFAULT");
  }

  @Test
  void auditCountsOnlyFullyReviewedCasesWithRegisteredReviewers() {
    Loaded loaded =
        load(
            fixture(
                "GC-1",
                caseJson(
                    "GC-1",
                    "agent:golden",
                    "d",
                    provenance("ESMA_QA", "Q&A 1699", "rev-1", "2026-10-09")),
                "GC-2",
                caseJson(
                    "GC-2",
                    "agent:golden",
                    "d",
                    provenance("ESMA_QA", "Q&A 1699", "rev-unknown", "2026-10-09")),
                "GC-3",
                caseJson("GC-3", "agent:golden", "d", CONSISTENCY)));
    assertThat(loaded.problems()).isEmpty();

    List<Row> rows = GoldenAudit.audit(loaded.cases(), REGISTRY, loaded.authorByRuleId());

    assertThat(rows).extracting(Row::caseId).containsExactly("GC-1", "GC-2", "GC-3");
    assertThat(rows).extracting(Row::countsForTraceability).containsExactly(true, false, false);
    assertThat(rows.get(1).problems()).containsExactly("REVIEWER_NOT_REGISTERED");
    assertThat(rows.get(2).problems()).isEmpty();
  }

  @Test
  void separationOfAuthorsIsReported() {
    Loaded loaded =
        load(
            fixture(
                "GC-1",
                caseJson(
                    "GC-1",
                    "agent:author",
                    "d",
                    provenance("GUIDELINES", "Guidelines 4.2", "rev-1", "2026-10-09")),
                "GC-2",
                caseJson(
                    "GC-2",
                    "agent:golden",
                    "d",
                    provenance("GUIDELINES", "Guidelines 4.2", "agent:author", "2026-10-09"))));
    assertThat(loaded.problems()).isEmpty();

    List<Row> rows = GoldenAudit.audit(loaded.cases(), REGISTRY, loaded.authorByRuleId());

    assertThat(rows.get(0).countsForTraceability()).isFalse();
    assertThat(rows.get(0).problems()).containsExactly("SEPARATION:A-N03-novation");
    assertThat(rows.get(1).countsForTraceability()).isFalse();
    assertThat(rows.get(1).problems()).containsExactly("REVIEWER_IS_AUTHOR");
  }

  @Test
  void theSampleGoldensLoadAndAreAllConsistencyOnly() {
    Path root =
        Path.of(System.getProperty("regpack.repo.root")).resolve("packs").resolve("sample.s0");

    Loaded loaded = PackGoldenLoader.load(PackSource.fromDirectory(root));

    assertThat(loaded.problems()).isEmpty();
    assertThat(loaded.cases())
        .extracting(GoldenCase::id)
        .containsExactly("GC-DEFAULT-1", "GC-N03-1", "GC-N08-1", "GC-N13-1");
    assertThat(loaded.cases())
        .allSatisfy(
            c -> {
              assertThat(c.provenance().kind()).isEqualTo(ProvenanceKind.CONSISTENCY_ONLY);
              assertThat(c.provenance().source()).isEmpty();
              assertThat(c.provenance().reviewerId()).isEmpty();
              assertThat(c.provenance().reviewedOn()).isEmpty();
              assertThat(c.input()).containsKeys("firmLei", "venueMic", "price", "quantity");
              // the harness reads the inputs as the canonical execution of the sample model
              assertThat(CanonicalExecutionCodec.fromMap(c.input())).isNotNull();
            });
    assertThat(loaded.authorByRuleId())
        .containsOnlyKeys(
            "S-N03-novation",
            "S-D14-natural-person",
            "S-N08-equity-on-venue",
            "S-DEFAULT-catch-all");
    Map<String, Object> first = loaded.cases().get(0).input();
    assertThat(first.get("quantity")).isInstanceOf(Long.class);
    assertThat(first.get("price")).isInstanceOf(java.math.BigDecimal.class);
  }
}
