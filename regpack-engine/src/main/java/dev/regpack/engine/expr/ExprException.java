package dev.regpack.engine.expr;

import dev.regpack.ports.expr.ExpressionException;
import java.util.Objects;

/**
 * An expression error with a kind. It carries no stack trace: a failing rule is an ordinary
 * outcome, and a hostile input must not make the engine pay for a trace on every record.
 */
public class ExprException extends ExpressionException {
  private static final long serialVersionUID = 1L;

  private final ErrorKind kind;

  public ExprException(ErrorKind kind, String message) {
    super(message);
    this.kind = Objects.requireNonNull(kind, "kind");
  }

  public ErrorKind kind() {
    return kind;
  }

  @Override
  public synchronized Throwable fillInStackTrace() {
    return this;
  }
}
