package dev.regpack.model;

import java.util.regex.Pattern;

/** A machine-readable reason, for example {@code MIFIR.N03.EXCLUDED} or {@code EXEC_BUDGET}. */
public record ReasonCode(String value) {
  private static final Pattern SHAPE = Pattern.compile("[A-Z][A-Z0-9_]*(\\.[A-Za-z0-9_]+){0,3}");

  public ReasonCode {
    if (value == null || !SHAPE.matcher(value).matches()) {
      throw new IllegalArgumentException("ReasonCode has an invalid shape: " + value);
    }
  }
}
