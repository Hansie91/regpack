package dev.regpack.engine.expr;

/** The classes of expression error (companion contract section 3). */
public enum ErrorKind {
  /** A declared path is read while it has no binding. */
  ABSENT,
  /** An operand of the wrong type. */
  TYPE,
  /** An integer result outside 64 bits. */
  OVERFLOW,
  /** Division by zero. */
  DIV_ZERO,
  /** A decimal division with no exact result. */
  NON_TERMINATING,
  /** A compile error: the source is not a valid expression. */
  PARSE,
  /** A value or argument outside its allowed range or size. */
  RANGE,
  /** A decimal above 38 digits. */
  SCALE,
  /** A conversion that would lose information. */
  LOSSY,
  /** Any other evaluation error. */
  OTHER
}
