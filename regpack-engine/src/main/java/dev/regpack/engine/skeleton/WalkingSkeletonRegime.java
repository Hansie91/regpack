package dev.regpack.engine.skeleton;

import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.engine.skeleton.WalkingSkeletonRules.Row;
import dev.regpack.model.Citation;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.canonical.CanonicalEvent;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionContext;
import dev.regpack.model.decision.InputDigest;
import dev.regpack.model.decision.PathStep;
import dev.regpack.ports.exec.ExecutionResult;
import dev.regpack.ports.exec.ExecutionResult.BudgetExhausted;
import dev.regpack.ports.exec.ExecutionResult.Completed;
import dev.regpack.ports.exec.ExecutionResult.Failed;
import dev.regpack.ports.exec.Executor;
import dev.regpack.ports.expr.Expression;
import dev.regpack.ports.regime.RegimeProvider;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * WALKING SKELETON: replaced by P2a.
 *
 * <p>The placeholder regime: three scope rules and a catch-all, run by the given executor. A failed
 * or over-budget evaluation becomes an UNDETERMINED decision (spec 7.3), never a silent skip.
 */
public final class WalkingSkeletonRegime implements RegimeProvider {
  public static final Regime REGIME = new Regime("mifir.eu.rts22");

  private static final String NODE_EXEC = "EXEC";

  private final Executor executor;
  private final ScopeProgram program;

  public WalkingSkeletonRegime(Executor executor, Expression expression) {
    this.executor = Objects.requireNonNull(executor, "executor");
    this.program = new ScopeProgram(expression);
  }

  /** The reference executor with the skeleton expression language. */
  public static WalkingSkeletonRegime standard() {
    return new WalkingSkeletonRegime(new MeteredExecutor(), new SkeletonExpression());
  }

  /** The pinned context of the skeleton: no overlay, no pins, placeholder pack digest. */
  public static DecisionContext skeletonContext() {
    return new DecisionContext(
        Sha256.ofUtf8("walking-skeleton"),
        Optional.empty(),
        List.of(),
        List.of(),
        List.of(),
        "none",
        "toy-1",
        "p0",
        MeteredExecutor.ID);
  }

  @Override
  public Descriptor describe() {
    return new Descriptor(REGIME, "walking-skeleton-placeholder-0", "p0");
  }

  @Override
  public Result evaluate(CanonicalEvent event, EvaluationContext context) {
    Objects.requireNonNull(event, "event");
    Objects.requireNonNull(context, "context");
    CanonicalExecution execution =
        switch (event) {
          case CanonicalExecution e -> e;
        };
    ExecutionResult<Row> result = executor.execute(program, execution, context.budget());
    Row catchAll = lastRow();
    DecisionBody body =
        switch (result) {
          case Completed<Row> done -> {
            Row row = done.value();
            yield body(
                execution,
                context,
                row.outcome(),
                List.of(),
                row.reason(),
                row.node(),
                List.of(row.id()),
                done.path(),
                row.citations(),
                row.outcome() == Outcome.REPORT ? List.of(Leg.MARKET) : List.of());
          }
          case BudgetExhausted<Row> out ->
              body(
                  execution,
                  context,
                  Outcome.UNDETERMINED,
                  List.of(
                      "rule_id=" + out.ruleId(),
                      "cost_units=" + out.costUnits(),
                      "limit=" + out.limit()),
                  new ReasonCode("EXEC_BUDGET"),
                  NODE_EXEC,
                  List.of(),
                  out.path(),
                  catchAll.citations(),
                  List.of());
          case Failed<Row> failed ->
              body(
                  execution,
                  context,
                  Outcome.UNDETERMINED,
                  List.of(
                      "rule_id=" + failed.ruleId(),
                      "error_class=" + failed.errorClass(),
                      "cost_units=" + failed.costUnits()),
                  new ReasonCode("EXEC_ERROR"),
                  NODE_EXEC,
                  List.of(),
                  failed.path(),
                  catchAll.citations(),
                  List.of());
        };
    return new Result(body, List.of(), Optional.empty());
  }

  private static Row lastRow() {
    List<Row> rows = WalkingSkeletonRules.rows();
    return rows.get(rows.size() - 1);
  }

  private static DecisionBody body(
      CanonicalExecution execution,
      EvaluationContext context,
      Outcome outcome,
      List<String> flags,
      ReasonCode reason,
      String node,
      List<String> ruleIdsHit,
      List<PathStep> path,
      List<Citation> citations,
      List<Leg> legs) {
    return new DecisionBody(
        execution.eventIdentity(),
        REGIME,
        context.decisionContext().id(),
        execution.occurredAt(),
        context.evaluatedAt(),
        outcome,
        flags,
        reason,
        node,
        ruleIdsHit,
        path,
        citations,
        legs,
        InputDigest.of(execution, context.ingestKey()));
  }
}
