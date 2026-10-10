package dev.regpack.spikes.r.vectors;

import dev.regpack.ports.expr.Value;
import java.util.Objects;
import java.util.Set;

/**
 * What an expression layer answers for one vector. A layer never throws; it returns one of these.
 */
public sealed interface Outcome {
  /** The error kinds a vector can expect. */
  Set<String> ERROR_KINDS =
      Set.of(
          "ABSENT",
          "TYPE",
          "OVERFLOW",
          "DIV_ZERO",
          "NON_TERMINATING",
          "PARSE",
          "RANGE",
          "SCALE",
          "LOSSY",
          "BUDGET",
          "OTHER");

  /** The expression evaluated to a value. */
  record Ok(Value value) implements Outcome {
    public Ok {
      Objects.requireNonNull(value, "value");
    }
  }

  /** The expression compiled and failed at evaluation; only the kind is compared. */
  record Error(String kind) implements Outcome {
    public Error {
      Objects.requireNonNull(kind, "kind");
      if (!ERROR_KINDS.contains(kind)) {
        throw new IllegalArgumentException("unknown error kind " + kind);
      }
    }
  }

  /** The layer refused to compile the expression; the reason is data, never compared. */
  record CompileError(String reason) implements Outcome {
    public CompileError {
      Objects.requireNonNull(reason, "reason");
    }
  }

  /** The layer cannot express the vector at all. */
  record Unsupported(String reason) implements Outcome {
    public Unsupported {
      Objects.requireNonNull(reason, "reason");
    }
  }
}
