package dev.regpack.pack.yaml;

/** A node of the PackYaml/1 tree: a scalar (always a string), a list or a map. */
public sealed interface YamlNode permits YamlScalar, YamlList, YamlMap {
  /** The 1-based line on which the node starts (a collection: its first key or dash). */
  int line();
}
