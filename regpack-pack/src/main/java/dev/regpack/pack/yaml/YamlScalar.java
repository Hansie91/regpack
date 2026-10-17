package dev.regpack.pack.yaml;

import java.util.Objects;

/** A scalar. Every scalar of PackYaml/1 is a string; the consumer types it explicitly. */
public record YamlScalar(String value, int line) implements YamlNode {
  public YamlScalar {
    Objects.requireNonNull(value, "value");
  }
}
