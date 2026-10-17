package dev.regpack.engine.packci;

import dev.regpack.engine.explain.Tier1Explainer;
import dev.regpack.engine.pack.EngineInfo;
import dev.regpack.engine.pack.PackProgram;
import dev.regpack.engine.pack.PackRegime;
import dev.regpack.model.EngineBuild;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionId;
import dev.regpack.model.decision.DecisionRecord;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.model.CitationRef;
import dev.regpack.pack.model.DecisionTable;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.pack.model.Provision;
import dev.regpack.pack.model.ReasonEntry;
import dev.regpack.pack.model.SourceEntry;
import dev.regpack.pack.model.TableRow;
import dev.regpack.pack.model.Validity;
import dev.regpack.ports.regime.RegimeProvider.EvaluationContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@code explain} for a pack (spec 6.3; plan T18): the static view of one rule and the Tier-2 view
 * of one event. The text is plain, fixed in layout, ends without a newline and is deterministic.
 *
 * <p>The explanation names rules, rows, kinds and counts and never a value of the record: the trace
 * lines carry the rule text and the error kind of a failing row, not what the row was applied to.
 * Text that came from a pack file was checked when the pack was loaded; text from a golden file was
 * not, so it is escaped and bounded here.
 */
public final class PackExplainer {
  private PackExplainer() {}

  /**
   * The static view of one row of a scope table: the row, its reason entry, each citation resolved
   * in the Legal Source Registry, the rationale and the golden cases of the pack that hit it.
   *
   * @throws IllegalArgumentException when no row has that id
   */
  public static String explainRule(LoadedPack pack, String ruleId) {
    Objects.requireNonNull(pack, "pack");
    Objects.requireNonNull(ruleId, "ruleId");
    for (DecisionTable table : pack.scopeTables().values()) {
      for (TableRow row : table.rows()) {
        if (row.id().equals(ruleId)) {
          return describe(pack, table, row);
        }
      }
    }
    throw new IllegalArgumentException(
        "the pack has no rule with the id '" + PackProblem.excerpt(ruleId) + "'");
  }

  /**
   * The Tier-2 view of one event: the Tier-1 text of the decision the regime makes for it, then the
   * full trace of the rows the interpreter evaluated, then the Tier-2 line. The event is
   * re-evaluated, so the decision is not stored and the poison circuit of the regime is neither
   * read nor fed ({@link PackRegime#evaluateTraced}).
   */
  public static String explainEvent(
      PackRegime regime, CanonicalExecution event, IngestKey key, Instant evaluatedAt) {
    Objects.requireNonNull(regime, "regime");
    Objects.requireNonNull(event, "event");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(evaluatedAt, "evaluatedAt");
    EvaluationContext context =
        new EvaluationContext(regime.decisionContext(), key, evaluatedAt, regime.recordBudget());
    PackRegime.Traced traced = regime.evaluateTraced(event, context);
    DecisionBody body = traced.result().decision();
    String tier1 =
        Tier1Explainer.explain(new DecisionRecord(new DecisionId(0), 1, Optional.empty(), body));
    int endOfFirstLine = tier1.indexOf('\n');
    StringBuilder out = new StringBuilder();
    out.append(tier1, 0, endOfFirstLine)
        .append(" (not stored)")
        .append(tier1, endOfFirstLine, tier1.length());
    out.append("\nTrace (full):");
    if (traced.rows().isEmpty()) {
      out.append(" none");
    }
    int n = 0;
    for (PackProgram.RowTrace row : traced.rows()) {
      n++;
      out.append("\n  ").append(n).append(". ").append(row.ruleId()).append(": ");
      if (row.error().isPresent()) {
        out.append("ERROR ").append(row.error().get().name());
      } else {
        out.append(row.matched() ? "MATCH" : "no match");
      }
      out.append(" (").append(row.units()).append(" units): ").append(row.when());
    }
    out.append('\n').append(tier2Line(regime.decisionContext().engineBuild(), EngineInfo.BUILD));
    return out.toString();
  }

  /** The closing line of the explanation: where the re-evaluation ran (Tier2Plan). */
  static String tier2Line(String storedEngineBuild, EngineBuild current) {
    return switch (Tier2Plan.decide(storedEngineBuild, current)) {
      case CURRENT_ENGINE ->
          // the stored text parsed, so it is short ASCII
          "Tier 2: re-evaluated on the current engine "
              + current
              + " (stored build "
              + storedEngineBuild
              + ": replay-compatible)";
      case ARCHIVED_BUILD_REQUIRED ->
          // P9b owns archived builds; the stored text may be unparsable, so it is escaped and cut
          "Tier 2: not re-evaluated, the archived build "
              + PackProblem.excerpt(storedEngineBuild)
              + " is required (current engine "
              + current
              + ")";
    };
  }

  // ---- explainRule ----------------------------------------------------------------------------

  private static String describe(LoadedPack pack, DecisionTable table, TableRow row) {
    StringBuilder out = new StringBuilder();
    out.append("Rule ")
        .append(row.id())
        .append(" (table ")
        .append(table.id())
        .append(", priority ")
        .append(row.priority())
        .append(")\n");
    out.append("When: ").append(row.when()).append('\n');
    out.append("Outcome: ").append(row.outcome().name()).append('\n');
    out.append("Node: ").append(row.node()).append('\n');
    row.ruleRef().ifPresent(ref -> out.append("Rule ref: ").append(ref).append('\n'));
    out.append("Valid: ").append(valid(row, table)).append('\n');
    if (row.outcome() == Outcome.REPORT) {
      out.append("Legs: ")
          .append(row.legs().stream().map(Leg::name).collect(Collectors.joining(", ")))
          .append('\n');
    }
    if (!row.set().isEmpty()) {
      out.append("Set: ")
          .append(
              row.set().entrySet().stream()
                  .map(e -> e.getKey() + "=" + e.getValue())
                  .collect(Collectors.joining(", ")))
          .append('\n');
    }
    out.append("Reason: ").append(row.reason().value());
    ReasonEntry reason = pack.reasons().entries().get(row.reason().value());
    if (reason == null) {
      out.append(" (not in the reason registry)");
    } else {
      out.append(" (category ")
          .append(reason.category().name())
          .append(", owner ")
          .append(reason.owner())
          .append(')');
    }
    out.append("\nCitations:");
    if (row.cite().isEmpty()) {
      out.append(" none");
    }
    for (CitationRef ref : row.cite()) {
      citation(out, pack, ref);
    }
    out.append("\nRationale ").append(row.rationale()).append(": ");
    String rationale = pack.rationales().texts().get(row.rationale());
    out.append(rationale == null ? "not in the rationale registry" : rationale);
    out.append("\nGolden cases: ").append(goldenCases(pack, row.id()));
    return out.toString();
  }

  private static String valid(TableRow row, DecisionTable table) {
    Optional<Validity> window = row.valid();
    if (window.isEmpty()) {
      return row.id().equals(table.catchAllRowId()) ? "none (the catch-all has no window)" : "none";
    }
    Validity v = window.get();
    return "from "
        + v.from()
        + v.to().map(to -> ", until " + to + " (exclusive)").orElse(", open ended");
  }

  private static void citation(StringBuilder out, LoadedPack pack, CitationRef ref) {
    out.append("\n  - ")
        .append(ref.sourceId())
        .append(' ')
        .append(ref.provisionPath())
        .append(" [")
        .append(ref.role().name())
        .append(']');
    SourceEntry source = pack.sources().sources().get(ref.sourceId());
    Optional<Provision> provision = pack.sources().resolve(ref.sourceId(), ref.provisionPath());
    if (source == null || provision.isEmpty()) {
      out.append(": not in the Legal Source Registry");
      return;
    }
    Provision p = provision.get();
    out.append(": source version ")
        .append(source.version())
        .append(", provision ")
        .append(p.status().name())
        .append(", role ")
        .append(p.role().name());
    p.quote().ifPresent(quote -> out.append("\n    quote: \"").append(quote).append('"'));
    p.quoteSha256().ifPresent(hash -> out.append("\n    quote sha256: ").append(hash.hex()));
  }

  /** The golden cases whose expected rule hits include the rule; text from the file is escaped. */
  private static String goldenCases(LoadedPack pack, String ruleId) {
    List<String> found = new ArrayList<>();
    for (Object tree : pack.goldenJson().values()) {
      if (!(tree instanceof Map<?, ?> golden)
          || !(golden.get("expected") instanceof Map<?, ?> expected)
          || !(expected.get("ruleIdsHit") instanceof List<?> hits)
          || !hits.contains(ruleId)) {
        continue;
      }
      String id = golden.get("id") instanceof String s ? PackProblem.excerpt(s) : "?";
      String kind =
          golden.get("provenance") instanceof Map<?, ?> provenance
                  && provenance.get("kind") instanceof String k
              ? PackProblem.excerpt(k)
              : "NO_PROVENANCE";
      found.add(id + " (" + kind + ")");
    }
    Collections.sort(found);
    return found.isEmpty() ? "none" : String.join(", ", found);
  }
}
