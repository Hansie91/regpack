package dev.regpack.pack.model;

import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One row of a scope decision table (companion contracts section 2). The constructor copies its
 * collections (the record is immutable) and keeps {@code set} sorted by name; it checks no format:
 * {@link TableParser} and the table checks do.
 *
 * @param id row id, unique in the pack
 * @param node the node of the reason-code scheme (declared in {@code reasons.yaml})
 * @param priority ascending priority is evaluated first; unique in the table
 * @param when the expression text, one line
 * @param outcome the outcome when the row is the first to match
 * @param reason the reason code of the decision
 * @param ruleRef optional pack-unique reference of the rule
 * @param cite the provisions cited; at least one (checked)
 * @param rationale id of the rationale in {@code rationales.yaml}
 * @param valid the legal validity window; absent only on the catch-all
 * @param set the attributes stored on the decision (BRK-10), sorted by name
 * @param legs the legs of a REPORT decision (OI-2); empty for every other outcome
 */
public record TableRow(
    String id,
    String node,
    int priority,
    String when,
    Outcome outcome,
    ReasonCode reason,
    Optional<String> ruleRef,
    List<CitationRef> cite,
    String rationale,
    Optional<Validity> valid,
    Map<String, String> set,
    List<Leg> legs) {
  public TableRow {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(node, "node");
    Objects.requireNonNull(when, "when");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(ruleRef, "ruleRef");
    Objects.requireNonNull(rationale, "rationale");
    Objects.requireNonNull(valid, "valid");
    cite = List.copyOf(cite);
    set = Collections.unmodifiableMap(new TreeMap<>(set));
    legs = List.copyOf(legs);
  }
}
