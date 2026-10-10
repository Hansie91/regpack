package dev.regpack.ports.expr;

/** Thrown when an expression cannot be compiled or evaluated. */
public class ExpressionException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ExpressionException(String message) {
    super(message);
  }

  public ExpressionException(String message, Throwable cause) {
    super(message, cause);
  }
}
