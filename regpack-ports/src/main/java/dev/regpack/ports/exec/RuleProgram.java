package dev.regpack.ports.exec;

/** A deterministic program that maps an input to an output, charging the meter as it goes. */
public interface RuleProgram<I, O> {
  String id();

  O run(I input, CostMeter meter, Trace trace);
}
