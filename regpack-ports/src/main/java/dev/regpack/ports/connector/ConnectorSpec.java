package dev.regpack.ports.connector;

import java.util.Map;
import java.util.Objects;

/** Static description of a connector: name, version, kind and free-form properties. */
public record ConnectorSpec(
    String name, String version, ConnectorKind kind, Map<String, String> properties) {
  public ConnectorSpec {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("ConnectorSpec name must not be blank");
    }
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(kind, "kind");
    properties = Map.copyOf(properties);
  }
}
