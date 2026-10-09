package dev.regpack.ports.expr;

/** The static types of the expression language (spec 6.2). */
public enum ValueType {
  BOOL,
  INT,
  DECIMAL,
  STRING,
  TIMESTAMP,
  DATE,
  ENUM,
  LIST
}
