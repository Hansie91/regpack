package dev.regpack.bench.data;

/**
 * One row of a generated decision table. {@code outcome} is the name of a model {@code Outcome};
 * {@code reason} is a synthetic reason code of the form {@code SYN.Tnn.Rnnn}.
 */
public record GeneratedRule(
    String id, int table, int priority, String when, String outcome, String reason) {}
