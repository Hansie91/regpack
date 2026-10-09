package dev.regpack.testkit.golden;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.testkit.golden.GoldenAudit.Row;
import dev.regpack.testkit.golden.GoldenCase.ProvenanceKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoldenAuditTest {
  private static final ReviewerRegistry REGISTRY =
      ReviewerRegistry.parse("rev-1|Test Reviewer|Compliance");
  private static final Map<String, String> RULE_AUTHORS = Map.of("R-1", "agent:rules");

  private static String caseJson(String id, String author, String ruleIds, String provenance) {
    return "{\"id\":\""
        + id
        + "\",\"node\":\"N03\",\"description\":\"d\",\"author\":\""
        + author
        + "\",\"input\":{\"venueMic\":\"XAMS\",\"quantity\":10},"
        + "\"expected\":{\"outcome\":\"REPORT\",\"reasonCode\":\"MIFIR.N03.EXCLUDED\","
        + "\"ruleIdsHit\":["
        + ruleIds
        + "]}"
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

  private static Row auditOne(String json) {
    List<Row> rows =
        GoldenAudit.audit(List.of(GoldenCaseLoader.parse(json)), REGISTRY, RULE_AUTHORS);
    assertThat(rows).hasSize(1);
    return rows.get(0);
  }

  @Test
  void consistencyOnlyNeverCounts() {
    Row missing = auditOne(caseJson("GC-1", "agent:golden", "\"R-1\"", ""));
    assertThat(missing.kind()).isEqualTo(ProvenanceKind.CONSISTENCY_ONLY);
    assertThat(missing.countsForTraceability()).isFalse();
    assertThat(missing.problems()).isEmpty();

    Row explicit =
        auditOne(
            caseJson(
                "GC-2",
                "agent:golden",
                "\"R-1\"",
                provenance("CONSISTENCY_ONLY", "Q&A 1699", "rev-1", "2026-10-09")));
    assertThat(explicit.countsForTraceability()).isFalse();
    assertThat(explicit.problems()).isEmpty();
  }

  @Test
  void fullyReviewedCaseCounts() {
    Row row =
        auditOne(
            caseJson(
                "GC-3",
                "agent:golden",
                "\"R-1\"",
                provenance("ESMA_QA", "Q&A 1699", "rev-1", "2026-10-09")));
    assertThat(row.caseId()).isEqualTo("GC-3");
    assertThat(row.kind()).isEqualTo(ProvenanceKind.ESMA_QA);
    assertThat(row.reviewerId()).isEqualTo("rev-1");
    assertThat(row.problems()).isEmpty();
    assertThat(row.countsForTraceability()).isTrue();
  }

  @Test
  void unregisteredReviewerNeverCounts() {
    Row row =
        auditOne(
            caseJson(
                "GC-4",
                "agent:golden",
                "\"R-1\"",
                provenance("ESMA_QA", "Q&A 1699", "ghost", "2026-10-09")));
    assertThat(row.countsForTraceability()).isFalse();
    assertThat(row.problems()).containsExactly("REVIEWER_NOT_REGISTERED");
  }

  @Test
  void missingSourceNeverCounts() {
    Row absent =
        auditOne(
            caseJson(
                "GC-5",
                "agent:golden",
                "\"R-1\"",
                provenance("GUIDELINES", null, "rev-1", "2026-10-09")));
    assertThat(absent.countsForTraceability()).isFalse();
    assertThat(absent.problems()).containsExactly("NO_SOURCE");

    Row blank =
        auditOne(
            caseJson(
                "GC-6",
                "agent:golden",
                "\"R-1\"",
                provenance("GUIDELINES", "  ", "rev-1", "2026-10-09")));
    assertThat(blank.countsForTraceability()).isFalse();
    assertThat(blank.problems()).containsExactly("NO_SOURCE");

    Row noReviewer =
        auditOne(
            caseJson(
                "GC-7",
                "agent:golden",
                "\"R-1\"",
                provenance("REAL_TRADE", "trade 7", null, "2026-10-09")));
    assertThat(noReviewer.countsForTraceability()).isFalse();
    assertThat(noReviewer.problems()).containsExactly("NO_REVIEWER");

    Row noDate =
        auditOne(
            caseJson(
                "GC-8",
                "agent:golden",
                "\"R-1\"",
                provenance("REAL_TRADE", "trade 7", "rev-1", null)));
    assertThat(noDate.countsForTraceability()).isFalse();
    assertThat(noDate.problems()).containsExactly("NO_REVIEW_DATE");
  }

  @Test
  void sameAuthorAsRuleIsSeparationViolation() {
    Row row =
        auditOne(
            caseJson(
                "GC-9",
                "agent:rules",
                "\"R-1\"",
                provenance("ESMA_QA", "Q&A 1699", "rev-1", "2026-10-09")));
    assertThat(row.countsForTraceability()).isFalse();
    assertThat(row.problems()).containsExactly("SEPARATION:R-1");
  }

  @Test
  void unknownRuleIdNeverCounts() {
    Row row =
        auditOne(
            caseJson(
                "GC-10",
                "agent:golden",
                "\"R-1\",\"R-404\"",
                provenance("ESMA_QA", "Q&A 1699", "rev-1", "2026-10-09")));
    assertThat(row.countsForTraceability()).isFalse();
    assertThat(row.problems()).containsExactly("UNKNOWN_RULE:R-404");
  }

  @Test
  void reviewerEqualToAuthorNeverCounts() {
    ReviewerRegistry registry = ReviewerRegistry.parse("agent:golden|Not A Person|Compliance");
    GoldenCase golden =
        GoldenCaseLoader.parse(
            caseJson(
                "GC-11",
                "agent:golden",
                "\"R-1\"",
                provenance("ESMA_QA", "Q&A 1699", "agent:golden", "2026-10-09")));
    Row row = GoldenAudit.audit(List.of(golden), registry, RULE_AUTHORS).get(0);
    assertThat(row.countsForTraceability()).isFalse();
    assertThat(row.problems()).containsExactly("REVIEWER_IS_AUTHOR");

    ReviewerRegistry ruleAuthorRegistry = ReviewerRegistry.parse("agent:rules|Rule Author|Dev");
    GoldenCase byRuleAuthor =
        GoldenCaseLoader.parse(
            caseJson(
                "GC-12",
                "agent:golden",
                "\"R-1\"",
                provenance("ESMA_QA", "Q&A 1699", "agent:rules", "2026-10-09")));
    Row second = GoldenAudit.audit(List.of(byRuleAuthor), ruleAuthorRegistry, RULE_AUTHORS).get(0);
    assertThat(second.countsForTraceability()).isFalse();
    assertThat(second.problems()).containsExactly("REVIEWER_IS_AUTHOR");
  }

  @Test
  void registryParsesCommentsAndRejectsDuplicateIds() {
    ReviewerRegistry registry =
        ReviewerRegistry.parse(
            "# comment\n\n  \nrev-1|Test Reviewer|Compliance\r\n# another\nrev-2|Second One|Legal\n");
    assertThat(registry.contains("rev-1")).isTrue();
    assertThat(registry.contains("rev-2")).isTrue();
    assertThat(registry.contains("rev-3")).isFalse();
    assertThat(ReviewerRegistry.parse("# only a comment\n").contains("rev-1")).isFalse();

    assertThatThrownBy(() -> ReviewerRegistry.parse("rev-1|A|x\nrev-1|B|y\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rev-1");
    assertThatThrownBy(() -> ReviewerRegistry.parse("rev-1|only two fields\n"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tsvHasOneTabSeparatedLinePerCase() {
    GoldenCase counted =
        GoldenCaseLoader.parse(
            caseJson(
                "GC-A",
                "agent:golden",
                "\"R-1\"",
                provenance("ESMA_QA", "Q&A 1699", "rev-1", "2026-10-09")));
    GoldenCase consistency =
        GoldenCaseLoader.parse(caseJson("GC-B", "agent:golden", "\"R-1\"", ""));
    GoldenCase broken =
        GoldenCaseLoader.parse(
            caseJson(
                "GC-C",
                "agent:rules",
                "\"R-1\",\"R-404\"",
                provenance("GUIDELINES", null, null, null)));
    String tsv =
        GoldenAudit.toTsv(
            GoldenAudit.audit(List.of(counted, consistency, broken), REGISTRY, RULE_AUTHORS));
    assertThat(tsv)
        .isEqualTo(
            "GC-A\tESMA_QA\trev-1\ttrue\t-\n"
                + "GC-B\tCONSISTENCY_ONLY\t-\tfalse\t-\n"
                + "GC-C\tGUIDELINES\t-\tfalse\t"
                + "NO_SOURCE;NO_REVIEWER;NO_REVIEW_DATE;SEPARATION:R-1;UNKNOWN_RULE:R-404\n");
    assertThat(GoldenAudit.toTsv(List.of())).isEmpty();
  }

  @Test
  void loaderRejectsUnknownKindAndMissingFields() {
    assertThatThrownBy(
            () ->
                GoldenCaseLoader.parse(
                    caseJson(
                        "GC-X", "a", "\"R-1\"", provenance("MADE_UP", "s", "rev-1", "2026-10-09"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MADE_UP");
    assertThatThrownBy(
            () ->
                GoldenCaseLoader.parse(
                    caseJson(
                        "GC-X", "a", "\"R-1\"", provenance("ESMA_QA", "s", "rev-1", "09/10/2026"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GoldenCaseLoader.parse("{\"id\":\"GC-X\"}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("node");
    String noAuthor = caseJson("GC-X", "a", "\"R-1\"", "").replace("\"author\":\"a\",", "");
    assertThatThrownBy(() -> GoldenCaseLoader.parse(noAuthor))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("author");
    String noOutcome = caseJson("GC-X", "a", "\"R-1\"", "").replace("\"outcome\":\"REPORT\",", "");
    assertThatThrownBy(() -> GoldenCaseLoader.parse(noOutcome))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("outcome");
    String unknownKey =
        caseJson("GC-X", "a", "\"R-1\"", "").replace("\"node\"", "\"extra\":1,\"node\"");
    assertThatThrownBy(() -> GoldenCaseLoader.parse(unknownKey))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("extra");
    assertThatThrownBy(() -> GoldenCaseLoader.parse("[]"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
