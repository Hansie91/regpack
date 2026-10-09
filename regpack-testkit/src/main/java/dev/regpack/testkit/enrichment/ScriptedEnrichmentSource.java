package dev.regpack.testkit.enrichment;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import dev.regpack.ports.connector.CheckResult;
import dev.regpack.ports.connector.ConnectorKind;
import dev.regpack.ports.connector.ConnectorSpec;
import dev.regpack.ports.connector.EnrichmentSource;
import dev.regpack.ports.connector.LookupUnavailableException;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A deterministic {@link EnrichmentSource} driven by a script. It never sleeps and never reads real
 * time: latency and timeouts advance the supplied {@link ManualClock}.
 *
 * <p>Behaviour that the script does not state: a key without a scripted answer is a {@code Miss}
 * (the source does not know it); {@code limits().maxRequestsPerSecond()} is descriptive only, rate
 * limiting happens when scripted ({@link Builder#rateLimitedOnRequest}, {@link #armRateLimit}); a
 * request that is rejected as invalid (duplicate or too many keys) is not counted by {@link
 * #requestCount()}; latency applies to requests that are answered, a timeout advances the clock by
 * {@code limits().timeout()} instead, and a rate-limited request advances nothing. When several
 * faults apply to one request the order is: armed rate limit, scripted rate limit, armed timeout,
 * key timeout.
 */
public final class ScriptedEnrichmentSource implements EnrichmentSource {
  private static final Limits DEFAULT_LIMITS = new Limits(100, Duration.ofSeconds(2), 10);

  private enum Shape {
    HIT,
    MISS,
    FAILURE
  }

  private record Answer(
      Shape shape,
      Optional<Instant> fixedAsOf,
      byte[] canonical,
      Sha256 responseHash,
      String errorCode,
      boolean retryable) {}

  private final ManualClock clock;
  private final String name;
  private final Limits limits;
  private final Map<String, Answer> answers;
  private final Duration latency;
  private final Set<String> timeoutKeys;
  private final Map<Integer, Duration> rateLimitedRequests;

  private int requestCount;
  private boolean timeoutArmed;
  private Optional<Duration> rateLimitArmed = Optional.empty();

  private ScriptedEnrichmentSource(Builder builder) {
    this.clock = builder.clock;
    this.name = builder.name;
    this.limits = builder.limits;
    this.answers = Map.copyOf(builder.answers);
    this.latency = builder.latency;
    this.timeoutKeys = Set.copyOf(builder.timeoutKeys);
    this.rateLimitedRequests = Map.copyOf(builder.rateLimitedRequests);
  }

  public static Builder builder(ManualClock clock, String name) {
    return new Builder(clock, name);
  }

  /** The number of valid lookups received so far, including those that threw. */
  public synchronized int requestCount() {
    return requestCount;
  }

  /** The next lookup advances the clock by {@code limits.timeout()} then throws TIMEOUT. */
  public synchronized void armTimeout() {
    timeoutArmed = true;
  }

  /** The next lookup throws RATE_LIMITED carrying {@code retryAfter}. */
  public synchronized void armRateLimit(Duration retryAfter) {
    rateLimitArmed = Optional.of(Objects.requireNonNull(retryAfter, "retryAfter"));
  }

  @Override
  public ConnectorSpec spec() {
    return new ConnectorSpec(name, "0.1.0", ConnectorKind.LOOKUP, Map.of());
  }

  @Override
  public CheckResult check() {
    return new CheckResult(true, "scripted enrichment source " + name);
  }

  @Override
  public Limits limits() {
    return limits;
  }

  @Override
  public synchronized Result lookup(Request request) {
    List<String> keys = request.keys();
    if (keys.size() > limits.maxKeysPerRequest()) {
      throw new IllegalArgumentException(
          "request has " + keys.size() + " keys, limit is " + limits.maxKeysPerRequest());
    }
    if (new HashSet<>(keys).size() != keys.size()) {
      throw new IllegalArgumentException("request keys must be distinct");
    }
    requestCount++;
    Instant startedAt = clock.now();

    Optional<Duration> armedRetry = rateLimitArmed;
    rateLimitArmed = Optional.empty();
    if (armedRetry.isPresent()) {
      throw rateLimited(armedRetry.get());
    }
    Duration scriptedRetry = rateLimitedRequests.get(requestCount);
    if (scriptedRetry != null) {
      throw rateLimited(scriptedRetry);
    }
    boolean armedTimeout = timeoutArmed;
    timeoutArmed = false;
    if (armedTimeout || keys.stream().anyMatch(timeoutKeys::contains)) {
      clock.advance(limits.timeout());
      throw new LookupUnavailableException(
          LookupUnavailableException.Kind.TIMEOUT, "TIMEOUT", Optional.empty());
    }

    clock.advance(latency);
    List<KeyOutcome> outcomes = new ArrayList<>(keys.size());
    for (String key : keys) {
      outcomes.add(outcomeFor(key, request.context().asOf(), startedAt));
    }
    return new Result(outcomes);
  }

  private static LookupUnavailableException rateLimited(Duration retryAfter) {
    return new LookupUnavailableException(
        LookupUnavailableException.Kind.RATE_LIMITED, "RATE_LIMITED", Optional.of(retryAfter));
  }

  private KeyOutcome outcomeFor(String key, Instant contextAsOf, Instant startedAt) {
    Answer answer = answers.get(key);
    if (answer == null) {
      return miss(key, contextAsOf, missCanonical(key));
    }
    return switch (answer.shape()) {
      case HIT ->
          new KeyOutcome.Hit(
              key,
              answer.fixedAsOf().orElse(contextAsOf),
              answer.canonical(),
              answer.responseHash());
      case MISS -> miss(key, contextAsOf, answer.canonical());
      case FAILURE ->
          new KeyOutcome.Failure(
              key,
              startedAt,
              answer.errorCode(),
              answer.retryable(),
              answer.canonical(),
              answer.responseHash());
    };
  }

  private static KeyOutcome miss(String key, Instant asOf, byte[] canonical) {
    return new KeyOutcome.Miss(key, asOf, canonical, Sha256.of(canonical));
  }

  private static byte[] missCanonical(String key) {
    return Json.canonical(Map.of("key", key, "outcome", "MISS")).getBytes(UTF_8);
  }

  /** Builds a {@link ScriptedEnrichmentSource}; each key may be scripted once. */
  public static final class Builder {
    private final ManualClock clock;
    private final String name;
    private Limits limits = DEFAULT_LIMITS;
    private final Map<String, Answer> answers = new HashMap<>();
    private Duration latency = Duration.ZERO;
    private final Set<String> timeoutKeys = new HashSet<>();
    private final Map<Integer, Duration> rateLimitedRequests = new HashMap<>();

    private Builder(ManualClock clock, String name) {
      this.clock = Objects.requireNonNull(clock, "clock");
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("name must not be blank");
      }
      this.name = name;
    }

    public Builder limits(Limits limits) {
      this.limits = Objects.requireNonNull(limits, "limits");
      return this;
    }

    /** A hit whose as-of is the request context's as-of. */
    public Builder hit(String key, Map<String, Object> value) {
      return script(key, hitAnswer(value, Optional.empty()));
    }

    /** A hit that keeps its own, older as-of. */
    public Builder staleHit(String key, Map<String, Object> value, Instant asOf) {
      return script(key, hitAnswer(value, Optional.of(Objects.requireNonNull(asOf, "asOf"))));
    }

    /** A miss whose as-of is the request context's as-of. */
    public Builder miss(String key) {
      byte[] canonical = missCanonical(key);
      return script(
          key,
          new Answer(Shape.MISS, Optional.empty(), canonical, Sha256.of(canonical), "", false));
    }

    /** A failure whose as-of is the clock's now at the start of the request. */
    public Builder failure(String key, String errorCode, boolean retryable) {
      Objects.requireNonNull(errorCode, "errorCode");
      byte[] canonical =
          Json.canonical(
                  Map.<String, Object>of(
                      "errorCode",
                      errorCode,
                      "key",
                      key,
                      "outcome",
                      "ERROR",
                      "retryable",
                      retryable))
              .getBytes(UTF_8);
      return script(
          key,
          new Answer(
              Shape.FAILURE,
              Optional.empty(),
              canonical,
              Sha256.of(canonical),
              errorCode,
              retryable));
    }

    /** Advances the clock by {@code perRequest} on every answered lookup; never sleeps. */
    public Builder latency(Duration perRequest) {
      if (Objects.requireNonNull(perRequest, "perRequest").isNegative()) {
        throw new IllegalArgumentException("latency must not be negative");
      }
      this.latency = perRequest;
      return this;
    }

    /** A request containing the key advances the clock by the timeout then throws TIMEOUT. */
    public Builder timeoutOnKey(String key) {
      timeoutKeys.add(Objects.requireNonNull(key, "key"));
      return this;
    }

    /** The nth request (1-based) throws RATE_LIMITED carrying {@code retryAfter}. */
    public Builder rateLimitedOnRequest(int requestNumber, Duration retryAfter) {
      if (requestNumber < 1) {
        throw new IllegalArgumentException("requestNumber is 1-based");
      }
      rateLimitedRequests.put(requestNumber, Objects.requireNonNull(retryAfter, "retryAfter"));
      return this;
    }

    public ScriptedEnrichmentSource build() {
      return new ScriptedEnrichmentSource(this);
    }

    private static Answer hitAnswer(Map<String, Object> value, Optional<Instant> asOf) {
      byte[] canonical = Json.canonical(value).getBytes(UTF_8);
      return new Answer(Shape.HIT, asOf, canonical, Sha256.of(canonical), "", false);
    }

    private Builder script(String key, Answer answer) {
      Objects.requireNonNull(key, "key");
      if (answers.putIfAbsent(key, answer) != null) {
        throw new IllegalArgumentException("key is already scripted: " + key);
      }
      return this;
    }
  }
}
