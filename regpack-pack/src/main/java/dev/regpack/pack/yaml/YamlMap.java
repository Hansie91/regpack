package dev.regpack.pack.yaml;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A mapping in file order; the entry map is unmodifiable. */
public record YamlMap(Map<String, YamlNode> entries, int line) implements YamlNode {
  public YamlMap {
    entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
  }
}
