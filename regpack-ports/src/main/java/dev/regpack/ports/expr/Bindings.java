package dev.regpack.ports.expr;

import java.util.Optional;

/** The variables an expression reads. An empty result is explicit absence. */
public interface Bindings {
  Optional<Value> get(String path);
}
