package dev.regpack.testkit.golden;

import dev.regpack.testkit.golden.GoldenCase.Provenance;
import dev.regpack.testkit.golden.GoldenCase.ProvenanceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies the spec 14.2 oracle rules mechanically: a golden case counts for traceability only with
 * a real source, a registered named human reviewer, a review date, and an author who differs from
 * the author of every rule the case hits.
 */
public final class GoldenAudit {
  /** The audit verdict for one case; {@code reviewerId} is empty when the case names none. */
  public record Row(
      String caseId,
      ProvenanceKind kind,
      String reviewerId,
      boolean countsForTraceability,
      List<String> problems) {
    public Row {
      problems = List.copyOf(problems);
    }
  }

  private GoldenAudit() {}

  /** Audits every case, in input order. */
  public static List<Row> audit(
      List<GoldenCase> cases, ReviewerRegistry reviewers, Map<String, String> ruleAuthorById) {
    List<Row> rows = new ArrayList<>(cases.size());
    for (GoldenCase golden : cases) {
      rows.add(auditOne(golden, reviewers, ruleAuthorById));
    }
    return List.copyOf(rows);
  }

  /**
   * One line per row: case id, kind, reviewer id or {@code -}, {@code true|false}, problems joined
   * by {@code ;} or {@code -}; fields separated by TAB, lines ended by LF.
   */
  public static String toTsv(List<Row> rows) {
    StringBuilder out = new StringBuilder();
    for (Row row : rows) {
      out.append(row.caseId())
          .append('\t')
          .append(row.kind().name())
          .append('\t')
          .append(row.reviewerId().isEmpty() ? "-" : row.reviewerId())
          .append('\t')
          .append(row.countsForTraceability() ? "true" : "false")
          .append('\t')
          .append(row.problems().isEmpty() ? "-" : String.join(";", row.problems()))
          .append('\n');
    }
    return out.toString();
  }

  private static Row auditOne(
      GoldenCase golden, ReviewerRegistry reviewers, Map<String, String> ruleAuthorById) {
    Provenance provenance = golden.provenance();
    String reviewerId = provenance.reviewerId().orElse("");
    if (provenance.kind() == ProvenanceKind.CONSISTENCY_ONLY) {
      return new Row(golden.id(), provenance.kind(), reviewerId, false, List.of());
    }
    List<String> problems = new ArrayList<>();
    if (provenance.source().map(String::isBlank).orElse(true)) {
      problems.add("NO_SOURCE");
    }
    if (reviewerId.isBlank()) {
      problems.add("NO_REVIEWER");
    } else if (!reviewers.contains(reviewerId)) {
      problems.add("REVIEWER_NOT_REGISTERED");
    }
    if (provenance.reviewedOn().isEmpty()) {
      problems.add("NO_REVIEW_DATE");
    }
    boolean reviewerIsAuthor = !reviewerId.isBlank() && reviewerId.equals(golden.author());
    for (String ruleId : golden.expected().ruleIdsHit()) {
      String ruleAuthor = ruleAuthorById.get(ruleId);
      if (ruleAuthor == null) {
        problems.add("UNKNOWN_RULE:" + ruleId);
        continue;
      }
      if (ruleAuthor.equals(golden.author())) {
        problems.add("SEPARATION:" + ruleId);
      }
      if (!reviewerId.isBlank() && ruleAuthor.equals(reviewerId)) {
        reviewerIsAuthor = true;
      }
    }
    if (reviewerIsAuthor) {
      problems.add("REVIEWER_IS_AUTHOR");
    }
    return new Row(golden.id(), provenance.kind(), reviewerId, problems.isEmpty(), problems);
  }
}
