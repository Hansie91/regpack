package dev.regpack.engine.pack;

import dev.regpack.engine.expr.Rulings;
import dev.regpack.model.Citation;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.Regime;
import dev.regpack.model.canonical.CanonicalEvent;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionContext;
import dev.regpack.model.decision.InputDigest;
import dev.regpack.model.decision.PathStep;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.model.CitationRef;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.pack.model.PackManifest;
import dev.regpack.pack.model.Provision;
import dev.regpack.pack.model.TableRow;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.ExecutionResult;
import dev.regpack.ports.exec.ExecutionResult.BudgetExhausted;
import dev.regpack.ports.exec.ExecutionResult.Completed;
import dev.regpack.ports.exec.ExecutionResult.Failed;
import dev.regpack.ports.exec.Executor;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.Value.TimestampValue;
import dev.regpack.ports.regime.RegimeProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One report of a {@link LoadedPack} as a {@link RegimeProvider}: pack to decision record (plan
 * T16, companion contracts section 5.6; BRK-14: the {@link Regime} is the id of the report).
 *
 * <p>The provider is immutable and thread-safe: every {@link #evaluate} builds its own {@link
 * PackProgram} and the shared executor keeps no state between calls. A failed or over-budget
 * evaluation becomes an UNDETERMINED decision (spec 7.3, I5), never a silent skip. The flags of
 * such a decision name the rule, the error class and kind and the cost units, and never a value of
 * the record.
 *
 * <p>The provider does not check signatures: a loaded pack is unsigned in P2a ({@code
 * LoadedPack.signed()} is false) and activation is P9b.
 */
public final class PackRegime implements RegimeProvider {
  private static final String NODE_EXEC = "EXEC";
  private static final ReasonCode EXEC_BUDGET = new ReasonCode("EXEC_BUDGET");
  private static final ReasonCode EXEC_ERROR = new ReasonCode("EXEC_ERROR");
  private static final int MAX_PROBLEMS_SHOWN = 8;

  private final CompiledPack compiled;
  private final PackManifest manifest;
  private final Executor executor;
  private final DecisionContext decisionContext;
  private final Map<String, List<Citation>> citations;
  private final List<Citation> catchAllCitations;

  private PackRegime(
      CompiledPack compiled, LoadedPack pack, Executor executor, DecisionContext context) {
    this.compiled = compiled;
    this.manifest = pack.manifest();
    this.executor = executor;
    this.decisionContext = context;
    Map<String, List<Citation>> resolved = new HashMap<>();
    for (CompiledPack.Table table : compiled.tables()) {
      for (CompiledPack.Row row : table.rows()) {
        resolved.put(row.id(), resolve(pack, row.source()));
      }
    }
    this.citations = Map.copyOf(resolved);
    this.catchAllCitations = citations.get(catchAllOf(compiled.lastTable()).id());
  }

  /**
   * Creates the provider of one report.
   *
   * @throws IllegalArgumentException listing the problems when the pack does not compile, its
   *     canonical input is not one this engine build binds, or this build does not satisfy its
   *     {@code engine_min} (the pack never runs on another major or library version)
   */
  public static PackRegime create(
      LoadedPack pack, String reportId, Executor executor, Rulings rulings) {
    Objects.requireNonNull(pack, "pack");
    Objects.requireNonNull(reportId, "reportId");
    Objects.requireNonNull(executor, "executor");
    Objects.requireNonNull(rulings, "rulings");
    List<PackProblem> problems = new ArrayList<>();
    if (!EngineInfo.BUILD.satisfies(pack.manifest().engineMin())) {
      problems.add(
          new PackProblem(
              "ENGINE_MIN_NOT_SATISFIED",
              PackManifest.FILE,
              0,
              "this engine build "
                  + EngineInfo.BUILD
                  + " does not satisfy engine_min "
                  + pack.manifest().engineMin()));
    }
    problems.addAll(CanonicalBindings.checkSupported(pack.input()));
    CompiledPack.CompileResult compiled = CompiledPack.compile(pack, reportId, rulings);
    problems.addAll(compiled.problems());
    if (!problems.isEmpty()) {
      throw new IllegalArgumentException(refusal(problems));
    }
    DecisionContext context =
        new DecisionContext(
            pack.digest(),
            Optional.empty(),
            List.of(),
            List.of(),
            List.of(),
            "none",
            "none",
            EngineInfo.BUILD.toString(),
            executor.id());
    return new PackRegime(compiled.pack().orElseThrow(), pack, executor, context);
  }

  private static String refusal(List<PackProblem> problems) {
    List<PackProblem> sorted = new ArrayList<>(problems);
    Collections.sort(sorted);
    StringBuilder text = new StringBuilder("the pack cannot run on this engine build:");
    int shown = Math.min(sorted.size(), MAX_PROBLEMS_SHOWN);
    for (int i = 0; i < shown; i++) {
      PackProblem p = sorted.get(i);
      text.append(' ').append(p.code()).append(" (").append(p.path()).append("): ");
      text.append(p.message()).append(';');
    }
    if (sorted.size() > shown) {
      text.append(" and ").append(sorted.size() - shown).append(" more");
    }
    return text.toString();
  }

  /** The regime of the decisions: the id of the report. */
  public Regime regime() {
    return compiled.regime();
  }

  /** The per-record budget of the compiled pack, in cost units. */
  public CostBudget recordBudget() {
    return new CostBudget(compiled.budget().perRecordUnits());
  }

  /** The context every decision of this provider is stamped with. */
  public DecisionContext decisionContext() {
    return decisionContext;
  }

  public CompiledPack compiled() {
    return compiled;
  }

  @Override
  public Descriptor describe() {
    return new Descriptor(
        compiled.regime(), manifest.version().toString(), manifest.engineMin().toString());
  }

  @Override
  public Result evaluate(CanonicalEvent event, EvaluationContext context) {
    Objects.requireNonNull(event, "event");
    Objects.requireNonNull(context, "context");
    CanonicalExecution execution =
        switch (event) {
          case CanonicalExecution e -> e;
        };
    Bindings bindings = new CanonicalBindings(execution);
    PackProgram program = new PackProgram(compiled, false);
    ExecutionResult<PackProgram.Decision> result =
        executor.execute(program, bindings, context.budget());
    Instant asAt =
        bindings
            .get(compiled.asAtField())
            .filter(TimestampValue.class::isInstance)
            .map(v -> ((TimestampValue) v).value())
            .orElse(execution.occurredAt());
    DecisionBody body =
        switch (result) {
          case Completed<PackProgram.Decision> done -> {
            TableRow row = done.value().row();
            yield body(
                execution,
                context,
                asAt,
                row.outcome(),
                List.of(),
                row.reason(),
                row.node(),
                List.of(row.id()),
                done.path(),
                citations.get(row.id()),
                row.outcome() == Outcome.REPORT ? row.legs() : List.of(),
                row.set());
          }
          case BudgetExhausted<PackProgram.Decision> out ->
              body(
                  execution,
                  context,
                  asAt,
                  Outcome.UNDETERMINED,
                  List.of(
                      "rule_id=" + out.ruleId(),
                      "cost_units=" + out.costUnits(),
                      "limit=" + out.limit()),
                  EXEC_BUDGET,
                  NODE_EXEC,
                  List.of(),
                  out.path(),
                  catchAllCitations,
                  List.of(),
                  Map.of());
          case Failed<PackProgram.Decision> failed ->
              body(
                  execution,
                  context,
                  asAt,
                  Outcome.UNDETERMINED,
                  failureFlags(failed, program),
                  EXEC_ERROR,
                  NODE_EXEC,
                  List.of(),
                  failed.path(),
                  catchAllCitations,
                  List.of(),
                  Map.of());
        };
    return new Result(body, List.of(), Optional.empty());
  }

  /** Rule, error class, the kind when known, cost units: never a value of the record (7.3). */
  private static List<String> failureFlags(
      Failed<PackProgram.Decision> failed, PackProgram program) {
    List<String> flags = new ArrayList<>();
    flags.add("rule_id=" + failed.ruleId());
    flags.add("error_class=" + failed.errorClass());
    program.lastErrorKind().ifPresent(kind -> flags.add("error_kind=" + kind));
    flags.add("cost_units=" + failed.costUnits());
    return flags;
  }

  private static TableRow catchAllOf(CompiledPack.Table table) {
    for (CompiledPack.Row row : table.rows()) {
      if (row.catchAll()) {
        return row.source();
      }
    }
    return table.rows().get(table.rows().size() - 1).source();
  }

  private static List<Citation> resolve(LoadedPack pack, TableRow row) {
    List<Citation> resolved = new ArrayList<>();
    for (CitationRef ref : row.cite()) {
      resolved.add(
          new Citation(
              ref.sourceId(),
              ref.provisionPath(),
              ref.role(),
              pack.sources()
                  .resolve(ref.sourceId(), ref.provisionPath())
                  .flatMap(Provision::quoteSha256)));
    }
    return List.copyOf(resolved);
  }

  private DecisionBody body(
      CanonicalExecution execution,
      EvaluationContext context,
      Instant asAt,
      Outcome outcome,
      List<String> flags,
      ReasonCode reason,
      String node,
      List<String> ruleIdsHit,
      List<PathStep> path,
      List<Citation> decisionCitations,
      List<Leg> legs,
      Map<String, String> attributes) {
    return new DecisionBody(
        execution.eventIdentity(),
        compiled.regime(),
        context.decisionContext().id(),
        asAt,
        context.evaluatedAt(),
        outcome,
        flags,
        reason,
        node,
        ruleIdsHit,
        path,
        decisionCitations,
        legs,
        InputDigest.of(execution, context.ingestKey()),
        attributes);
  }
}
