package dev.regpack.ports.connector;

import dev.regpack.model.BatchId;
import dev.regpack.model.Sha256;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The lookup kind of the connector SPI (spec 7.5). {@link #lookup} returns one outcome per key, in
 * request order; keys must be distinct and no more than {@code limits().maxKeysPerRequest()}, else
 * {@link IllegalArgumentException}. Every outcome carries an as-of and a canonical serialisation
 * whose SHA-256 is its response hash. A whole-request timeout or rate limit throws {@link
 * LookupUnavailableException}.
 */
public non-sealed interface EnrichmentSource extends Connector {
  /** Request limits; {@code maxRequestsPerSecond} of 0 means unlimited. */
  record Limits(int maxKeysPerRequest, Duration timeout, int maxRequestsPerSecond) {
    public Limits {
      if (maxKeysPerRequest < 1) {
        throw new IllegalArgumentException("maxKeysPerRequest must be at least 1");
      }
      Objects.requireNonNull(timeout, "timeout");
      if (timeout.isNegative() || timeout.isZero()) {
        throw new IllegalArgumentException("timeout must be positive");
      }
      if (maxRequestsPerSecond < 0) {
        throw new IllegalArgumentException("maxRequestsPerSecond must not be negative");
      }
    }
  }

  record Context(String field, Instant asOf, BatchId batchId) {
    public Context {
      if (field == null || field.isBlank()) {
        throw new IllegalArgumentException("field must not be blank");
      }
      Objects.requireNonNull(asOf, "asOf");
      Objects.requireNonNull(batchId, "batchId");
    }
  }

  record Request(List<String> keys, Context context) {
    public Request {
      keys = List.copyOf(keys);
      Objects.requireNonNull(context, "context");
    }
  }

  /** The answer for one key; {@code canonical} is hashed to {@code responseHash}. */
  sealed interface KeyOutcome {
    String key();

    Instant asOf();

    byte[] canonical();

    Sha256 responseHash();

    record Hit(String key, Instant asOf, byte[] canonical, Sha256 responseHash)
        implements KeyOutcome {
      public Hit {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(asOf, "asOf");
        canonical = canonical.clone();
        Objects.requireNonNull(responseHash, "responseHash");
      }

      @Override
      public byte[] canonical() {
        return canonical.clone();
      }

      @Override
      public boolean equals(Object other) {
        return other instanceof Hit h
            && key.equals(h.key)
            && asOf.equals(h.asOf)
            && Arrays.equals(canonical, h.canonical)
            && responseHash.equals(h.responseHash);
      }

      @Override
      public int hashCode() {
        return Objects.hash(key, asOf, Arrays.hashCode(canonical), responseHash);
      }

      @Override
      public String toString() {
        return "Hit[key="
            + key
            + ", asOf="
            + asOf
            + ", canonicalLength="
            + canonical.length
            + ", responseHash="
            + responseHash.hex()
            + "]";
      }
    }

    record Miss(String key, Instant asOf, byte[] canonical, Sha256 responseHash)
        implements KeyOutcome {
      public Miss {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(asOf, "asOf");
        canonical = canonical.clone();
        Objects.requireNonNull(responseHash, "responseHash");
      }

      @Override
      public byte[] canonical() {
        return canonical.clone();
      }

      @Override
      public boolean equals(Object other) {
        return other instanceof Miss m
            && key.equals(m.key)
            && asOf.equals(m.asOf)
            && Arrays.equals(canonical, m.canonical)
            && responseHash.equals(m.responseHash);
      }

      @Override
      public int hashCode() {
        return Objects.hash(key, asOf, Arrays.hashCode(canonical), responseHash);
      }

      @Override
      public String toString() {
        return "Miss[key="
            + key
            + ", asOf="
            + asOf
            + ", canonicalLength="
            + canonical.length
            + ", responseHash="
            + responseHash.hex()
            + "]";
      }
    }

    record Failure(
        String key,
        Instant asOf,
        String errorCode,
        boolean retryable,
        byte[] canonical,
        Sha256 responseHash)
        implements KeyOutcome {
      public Failure {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(errorCode, "errorCode");
        canonical = canonical.clone();
        Objects.requireNonNull(responseHash, "responseHash");
      }

      @Override
      public byte[] canonical() {
        return canonical.clone();
      }

      @Override
      public boolean equals(Object other) {
        return other instanceof Failure f
            && key.equals(f.key)
            && asOf.equals(f.asOf)
            && errorCode.equals(f.errorCode)
            && retryable == f.retryable
            && Arrays.equals(canonical, f.canonical)
            && responseHash.equals(f.responseHash);
      }

      @Override
      public int hashCode() {
        return Objects.hash(
            key, asOf, errorCode, retryable, Arrays.hashCode(canonical), responseHash);
      }

      @Override
      public String toString() {
        return "Failure[key="
            + key
            + ", asOf="
            + asOf
            + ", errorCode="
            + errorCode
            + ", retryable="
            + retryable
            + ", canonicalLength="
            + canonical.length
            + ", responseHash="
            + responseHash.hex()
            + "]";
      }
    }
  }

  record Result(List<KeyOutcome> outcomes) {
    public Result {
      outcomes = List.copyOf(outcomes);
    }
  }

  Limits limits();

  Result lookup(Request request);
}
