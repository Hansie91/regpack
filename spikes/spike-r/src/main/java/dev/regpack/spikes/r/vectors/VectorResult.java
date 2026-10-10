package dev.regpack.spikes.r.vectors;

import java.util.Objects;

/** The result of one vector on one layer: PASS, FAIL, UNSUPPORTED or FUNCTIONAL. */
public record VectorResult(String id, String vectorClass, String result, String actual) {
  public VectorResult {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(vectorClass, "vectorClass");
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(actual, "actual");
  }
}
