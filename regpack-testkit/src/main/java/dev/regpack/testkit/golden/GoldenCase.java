package dev.regpack.testkit.golden;

import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One golden case: an input, the expected decision and where the expectation comes from (spec
 * 14.2). A case without a provenance of a real kind is {@link ProvenanceKind#CONSISTENCY_ONLY}.
 */
public record GoldenCase(
    String id,
    String node,
    String description,
    Map<String, Object> input,
    Expected expected,
    Provenance provenance,
    String author) {

  public GoldenCase {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(node, "node");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(author, "author");
    input = deepCopyMap(Objects.requireNonNull(input, "input"));
  }

  /** The expected decision of a case. */
  public record Expected(Outcome outcome, ReasonCode reasonCode, List<String> ruleIdsHit) {
    public Expected {
      Objects.requireNonNull(outcome, "outcome");
      Objects.requireNonNull(reasonCode, "reasonCode");
      ruleIdsHit = List.copyOf(ruleIdsHit);
    }
  }

  /** Where the expectation comes from. */
  public enum ProvenanceKind {
    REGULATOR_WORKBOOK,
    ESMA_QA,
    GUIDELINES,
    ARM_NCA_SANDBOX,
    REAL_TRADE,
    CONSISTENCY_ONLY
  }

  /** Source and named human review of a case; absent parts are {@link Optional#empty()}. */
  public record Provenance(
      ProvenanceKind kind,
      Optional<String> source,
      Optional<String> reviewerId,
      Optional<LocalDate> reviewedOn) {
    public Provenance {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(reviewerId, "reviewerId");
      Objects.requireNonNull(reviewedOn, "reviewedOn");
    }

    /** The provenance of a case that records none. */
    public static Provenance consistencyOnly() {
      return new Provenance(
          ProvenanceKind.CONSISTENCY_ONLY, Optional.empty(), Optional.empty(), Optional.empty());
    }
  }

  private static Map<String, Object> deepCopyMap(Map<?, ?> map) {
    Map<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      copy.put((String) Objects.requireNonNull(entry.getKey()), deepCopy(entry.getValue()));
    }
    return Collections.unmodifiableMap(copy);
  }

  private static Object deepCopy(Object value) {
    return switch (value) {
      case null -> throw new NullPointerException("null is not allowed in a golden input");
      case Map<?, ?> m -> deepCopyMap(m);
      case List<?> l -> {
        List<Object> copy = new ArrayList<>(l.size());
        for (Object element : l) {
          copy.add(deepCopy(element));
        }
        yield Collections.unmodifiableList(copy);
      }
      default -> value;
    };
  }
}
