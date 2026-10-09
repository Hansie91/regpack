package dev.regpack.engine.skeleton;

import dev.regpack.model.Citation;
import dev.regpack.model.CitationRole;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * WALKING SKELETON: replaced by P2a.
 *
 * <p>The placeholder scope rules of the walking skeleton. The citations name {@code
 * mifir-placeholder} and {@code regpack-spec}, never a real provision; nothing here is regime
 * content.
 */
public final class WalkingSkeletonRules {
  /** Author recorded for every placeholder rule (golden-case separation of duties, spec 14.2). */
  public static final String RULE_AUTHOR = "agent:p0-implementer";

  /** One decision-table row; the hit policy is FIRST over ascending {@code priority}. */
  public record Row(
      String id,
      String node,
      int priority,
      String when,
      Outcome outcome,
      ReasonCode reason,
      List<Citation> citations,
      String author,
      int exprCost) {
    public Row {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(node, "node");
      Objects.requireNonNull(when, "when");
      Objects.requireNonNull(outcome, "outcome");
      Objects.requireNonNull(reason, "reason");
      citations = List.copyOf(citations);
      Objects.requireNonNull(author, "author");
      if (exprCost < 0) {
        throw new IllegalArgumentException("exprCost must be >= 0");
      }
    }
  }

  private static final List<Row> ROWS =
      List.of(
          new Row(
              "S-N03-novation",
              "N03",
              300,
              "event.kind in ['NOVATION','ASSIGNMENT']",
              Outcome.DO_NOT_REPORT,
              new ReasonCode("MIFIR.N03.EXCLUDED"),
              List.of(
                  new Citation(
                      "mifir-placeholder",
                      "art/2/par/5/pt/PLACEHOLDER",
                      CitationRole.BASIS,
                      Optional.empty())),
              RULE_AUTHOR,
              2),
          new Row(
              "S-D14-natural-person",
              "N13",
              400,
              "client.kind == 'NATURAL_PERSON'",
              Outcome.UNDETERMINED,
              new ReasonCode("MIFIR.N13.NATURAL_PERSON"),
              List.of(
                  new Citation(
                      "regpack-spec", "D14", CitationRole.INTERPRETATION, Optional.empty())),
              RULE_AUTHOR,
              1),
          new Row(
              "S-N08-equity-on-venue",
              "N08",
              800,
              "instrument.assetClass == 'EQUITY' && instrument.tradedOnVenue",
              Outcome.REPORT,
              new ReasonCode("MIFIR.N08.IN_SCOPE_26_2_A"),
              List.of(
                  new Citation(
                      "mifir-placeholder",
                      "art/26/par/2/pt/a",
                      CitationRole.BASIS,
                      Optional.empty())),
              RULE_AUTHOR,
              2),
          new Row(
              "S-DEFAULT-catch-all",
              "DEFAULT",
              9999,
              "true",
              Outcome.UNDETERMINED,
              new ReasonCode("DEFAULT.NO_RULE_HIT"),
              List.of(new Citation("regpack-spec", "I5", CitationRole.GUIDANCE, Optional.empty())),
              RULE_AUTHOR,
              0));

  private WalkingSkeletonRules() {}

  /** The rows in ascending priority; the last row is the catch-all. */
  public static List<Row> rows() {
    return ROWS;
  }

  /** The author of each rule, keyed by rule id, in row order. */
  public static Map<String, String> authorsById() {
    Map<String, String> authors = new LinkedHashMap<>();
    for (Row row : ROWS) {
      authors.put(row.id(), row.author());
    }
    return Map.copyOf(authors);
  }
}
