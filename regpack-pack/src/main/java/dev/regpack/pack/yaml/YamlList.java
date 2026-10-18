package dev.regpack.pack.yaml;

import java.util.List;

/** A sequence; the item list is unmodifiable. */
public record YamlList(List<YamlNode> items, int line) implements YamlNode {
  public YamlList {
    items = List.copyOf(items);
  }
}
