package dev.regpack.ports.connector;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Thrown by {@link EnrichmentSource#lookup} when the whole request could not be served: a timeout,
 * a rate limit (optionally with a retry-after) or any other unavailability.
 */
public class LookupUnavailableException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public enum Kind {
    TIMEOUT,
    RATE_LIMITED,
    UNAVAILABLE
  }

  private final Kind kind;
  private final String errorCode;
  private final transient Optional<Duration> retryAfter;

  public LookupUnavailableException(Kind kind, String errorCode, Optional<Duration> retryAfter) {
    super(
        Objects.requireNonNull(kind, "kind")
            + " ("
            + Objects.requireNonNull(errorCode, "errorCode")
            + ")"
            + Objects.requireNonNull(retryAfter, "retryAfter")
                .map(d -> ", retry after " + d)
                .orElse(""));
    this.kind = kind;
    this.errorCode = errorCode;
    this.retryAfter = retryAfter;
  }

  public Kind kind() {
    return kind;
  }

  public String errorCode() {
    return errorCode;
  }

  public Optional<Duration> retryAfter() {
    return retryAfter;
  }
}
