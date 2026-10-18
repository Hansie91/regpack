package dev.regpack.engine.packci;

import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.model.CitationRef;
import dev.regpack.pack.model.DecisionTable;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.pack.model.TableRow;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The coverage gates of pack CI (spec 6.5 step 2; plan T20; review focus PF15). The gate is never
 * vacuous:
 *
 * <ul>
 *   <li>no golden case at all fails ({@code COVERAGE_NO_CASES});
 *   <li>a case exercises a row only if it <em>passed</em> (outcome, reason, node and rule hits
 *       equal the expectation) and its <em>actual</em> hits contain the row, so a failing case
 *       exercises nothing;
 *   <li>a reason is reached when a passed case produced it and some row uses it;
 *   <li>a provision is exercised when a passed case hit a row that cites it;
 *   <li>coverage by <em>counting</em> cases only (those that satisfy a traceability row, O3) is
 *       reported and never gates: it is 0 while gate G1 is open.
 * </ul>
 *
 * Problem codes: {@code COVERAGE_NO_CASES}, {@code COVERAGE_ROW_NOT_HIT}, {@code
 * COVERAGE_REASON_NOT_REACHED}, {@code COVERAGE_PROVISION_NOT_EXERCISED}; the message is exactly
 * the row id, the reason code or {@code <source_id>/<provision path>} (all three are pack text that
 * the loader has already checked).
 */
public final class CoverageGates {
  private static final String REASONS_FILE = "reasons.yaml";
  private static final String SOURCES_FILE = "sources.yaml";
  private static final String GOLDEN_DIR = "tests/golden";

  private CoverageGates() {}

  /**
   * What the tester observed for one golden case.
   *
   * @param caseId the id of the case
   * @param passed whether the decision equals the expectation in every checked field
   * @param actualRuleIds the rules the decision named as hit
   * @param actualReason the reason code of the decision
   * @param counts whether the case satisfies a traceability row (a real source and a registered
   *     reviewer who is not an author, spec 14.2); a failed case never counts
   */
  public record CaseOutcome(
      String caseId,
      boolean passed,
      List<String> actualRuleIds,
      String actualReason,
      boolean counts) {
    public CaseOutcome {
      Objects.requireNonNull(caseId, "caseId");
      actualRuleIds = List.copyOf(actualRuleIds);
      Objects.requireNonNull(actualReason, "actualReason");
    }
  }

  /**
   * The coverage figures.
   *
   * @param rowsTotal the rows of all scope tables
   * @param rowsHit the rows hit by a passed case
   * @param reasonsTotal the reasons of the reason-code registry
   * @param reasonsReached the reasons that a passed case produced and some row uses
   * @param provisionsCited the distinct provisions cited by a row
   * @param provisionsExercised the cited provisions of a row that a passed case hit
   * @param countingRowsHit the rows hit by a passed case that counts for traceability
   */
  public record Coverage(
      int rowsTotal,
      int rowsHit,
      int reasonsTotal,
      int reasonsReached,
      int provisionsCited,
      int provisionsExercised,
      int countingRowsHit) {}

  /** The figures and the problems; the gate passes exactly when {@code problems} is empty. */
  public record CoverageResult(Coverage coverage, List<PackProblem> problems) {
    public CoverageResult {
      Objects.requireNonNull(coverage, "coverage");
      List<PackProblem> sorted = new ArrayList<>(problems);
      Collections.sort(sorted);
      problems = List.copyOf(sorted);
    }
  }

  /** Evaluates the gates of one pack against the outcomes of its golden cases. */
  public static CoverageResult evaluate(LoadedPack pack, List<CaseOutcome> outcomes) {
    Objects.requireNonNull(pack, "pack");
    Objects.requireNonNull(outcomes, "outcomes");

    Set<String> hit = new HashSet<>();
    Set<String> countingHit = new HashSet<>();
    Set<String> reasonsProduced = new HashSet<>();
    for (CaseOutcome outcome : outcomes) {
      if (!outcome.passed()) {
        continue; // a failing case exercises nothing
      }
      hit.addAll(outcome.actualRuleIds());
      reasonsProduced.add(outcome.actualReason());
      if (outcome.counts()) {
        countingHit.addAll(outcome.actualRuleIds());
      }
    }

    // rows, the reasons rows use and the provisions rows cite, in path then row order
    Map<String, String> tableOfRow = new TreeMap<>();
    Set<String> reasonsUsed = new TreeSet<>();
    Set<String> provisionsCited = new TreeSet<>();
    Set<String> provisionsExercised = new TreeSet<>();
    Set<String> rowsHit = new TreeSet<>();
    Set<String> countingRows = new TreeSet<>();
    for (Map.Entry<String, DecisionTable> table : pack.scopeTables().entrySet()) {
      for (TableRow row : table.getValue().rows()) {
        tableOfRow.put(row.id(), table.getKey());
        reasonsUsed.add(row.reason().value());
        boolean rowHit = hit.contains(row.id());
        if (rowHit) {
          rowsHit.add(row.id());
        }
        if (countingHit.contains(row.id())) {
          countingRows.add(row.id());
        }
        for (CitationRef ref : row.cite()) {
          String provision = ref.sourceId() + "/" + ref.provisionPath();
          provisionsCited.add(provision);
          if (rowHit) {
            provisionsExercised.add(provision);
          }
        }
      }
    }
    Set<String> reasonsReached = new TreeSet<>();
    for (String code : pack.reasons().entries().keySet()) {
      if (reasonsUsed.contains(code) && reasonsProduced.contains(code)) {
        reasonsReached.add(code);
      }
    }

    Coverage coverage =
        new Coverage(
            tableOfRow.size(),
            rowsHit.size(),
            pack.reasons().entries().size(),
            reasonsReached.size(),
            provisionsCited.size(),
            provisionsExercised.size(),
            countingRows.size());

    List<PackProblem> problems = new ArrayList<>();
    if (outcomes.isEmpty()) {
      problems.add(
          new PackProblem(
              "COVERAGE_NO_CASES",
              GOLDEN_DIR,
              0,
              "the pack has no golden case, so nothing is covered"));
      return new CoverageResult(coverage, problems);
    }
    for (Map.Entry<String, String> row : tableOfRow.entrySet()) {
      if (!rowsHit.contains(row.getKey())) {
        problems.add(new PackProblem("COVERAGE_ROW_NOT_HIT", row.getValue(), 0, row.getKey()));
      }
    }
    for (String code : pack.reasons().entries().keySet()) {
      if (!reasonsReached.contains(code)) {
        problems.add(new PackProblem("COVERAGE_REASON_NOT_REACHED", REASONS_FILE, 0, code));
      }
    }
    for (String provision : provisionsCited) {
      if (!provisionsExercised.contains(provision)) {
        problems.add(
            new PackProblem("COVERAGE_PROVISION_NOT_EXERCISED", SOURCES_FILE, 0, provision));
      }
    }
    return new CoverageResult(coverage, problems);
  }
}
