package dev.regpack.ports.connector;

import java.util.Objects;

/** The outcome of a connector self-check. */
public record CheckResult(boolean ok, String detail) {
  public CheckResult {
    Objects.requireNonNull(detail, "detail");
  }
}
