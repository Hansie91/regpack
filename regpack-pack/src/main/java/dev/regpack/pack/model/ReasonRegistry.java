package dev.regpack.pack.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The reason-code registry of a pack ({@code reasons.yaml}; spec 6.3, BRK-11, OI-7): the prefix and
 * the decision-tree nodes the pack declares, and its reason codes keyed by code. Nodes and entries
 * iterate in sorted order, whatever the order of the file, so that everything derived from them
 * (problems, the canonical IR) is deterministic.
 *
 * @param prefix the reason-code prefix of the regime, for example {@code SAMPLE}
 * @param nodes the declared nodes of the regime's decision tree
 * @param entries the declared reasons, keyed by code
 */
public record ReasonRegistry(String prefix, Set<String> nodes, Map<String, ReasonEntry> entries) {
  public ReasonRegistry {
    Objects.requireNonNull(prefix, "prefix");
    nodes = Collections.unmodifiableSet(new TreeSet<>(nodes));
    entries = Collections.unmodifiableMap(new TreeMap<>(entries));
  }
}
