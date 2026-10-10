package dev.regpack.ports.exec;

/** Runs a rule program under a cost budget. The reference implementation is in the engine. */
public interface Executor {
  String id();

  <I, O> ExecutionResult<O> execute(RuleProgram<I, O> program, I input, CostBudget budget);
}
