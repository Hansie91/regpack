package dev.regpack.flow.enrich;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import dev.regpack.ports.connector.CheckResult;
import dev.regpack.ports.connector.ConnectorSpec;
import dev.regpack.ports.connector.EnrichmentSource;
import dev.regpack.ports.connector.LookupUnavailableException;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.LookupContract;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The shared lookup contract (SF5) run against {@link StaticDataSource}. The production class has
 * no failure column and no way to arm a fault, so the source is wrapped in a test-scope decorator
 * that scripts the one failure outcome of the contract (key {@code F1}) and the two armed faults.
 * Every other answer, and every check of the request, is the production class's own.
 */
class StaticDataSourceLookupContractTest implements LookupContract {
  private static final Instant START = Instant.parse("2026-10-09T08:00:00Z");
  private static final EnrichmentSource.Limits LIMITS =
      new EnrichmentSource.Limits(3, Duration.ofSeconds(2), 10);

  private final ManualClock clock = new ManualClock(START);
  private final EnrichmentSource.Context context = LookupContract.standardContext();
  private final FaultScript source =
      new FaultScript(
          new StaticDataSource(
              "key,lei,as_of\n"
                  + "K1,LEI-K1,\n"
                  + "K2,LEI-K2,\n"
                  + "S1,LEI-S1,"
                  + START.minus(Duration.ofDays(3))
                  + "\n",
              clock,
              LIMITS));

  /** Decorates a source: key F1 fails, and a fault can be armed for the next lookup. */
  private static final class FaultScript implements EnrichmentSource {
    private final EnrichmentSource inner;
    private boolean timeoutArmed;
    private Optional<Duration> rateLimitArmed = Optional.empty();

    FaultScript(EnrichmentSource inner) {
      this.inner = inner;
    }

    void armTimeout() {
      timeoutArmed = true;
    }

    void armRateLimit(Duration retryAfter) {
      rateLimitArmed = Optional.of(retryAfter);
    }

    @Override
    public ConnectorSpec spec() {
      return inner.spec();
    }

    @Override
    public CheckResult check() {
      return inner.check();
    }

    @Override
    public Limits limits() {
      return inner.limits();
    }

    @Override
    public Result lookup(Request request) {
      if (rateLimitArmed.isPresent()) {
        Duration retryAfter = rateLimitArmed.get();
        rateLimitArmed = Optional.empty();
        throw new LookupUnavailableException(
            LookupUnavailableException.Kind.RATE_LIMITED, "RATE_LIMITED", Optional.of(retryAfter));
      }
      if (timeoutArmed) {
        timeoutArmed = false;
        throw new LookupUnavailableException(
            LookupUnavailableException.Kind.TIMEOUT, "TIMEOUT", Optional.empty());
      }
      // the production class answers first, so it also checks the request (distinct keys, limit)
      List<KeyOutcome> answered = inner.lookup(request).outcomes();
      List<KeyOutcome> outcomes = new ArrayList<>();
      for (KeyOutcome outcome : answered) {
        outcomes.add(outcome.key().equals("F1") ? failure(request) : outcome);
      }
      return new Result(outcomes);
    }

    private static KeyOutcome failure(Request request) {
      byte[] canonical =
          Json.canonical(
                  Map.of(
                      "errorCode",
                      "E_UPSTREAM",
                      "key",
                      "F1",
                      "outcome",
                      "ERROR",
                      "retryable",
                      true))
              .getBytes(UTF_8);
      return new KeyOutcome.Failure(
          "F1", request.context().asOf(), "E_UPSTREAM", true, canonical, Sha256.of(canonical));
    }
  }

  @Override
  public EnrichmentSource source() {
    return source;
  }

  @Override
  public ManualClock clock() {
    return clock;
  }

  @Override
  public EnrichmentSource.Context context() {
    return context;
  }

  @Override
  public void armTimeout() {
    source.armTimeout();
  }

  @Override
  public void armRateLimit(Duration retryAfter) {
    source.armRateLimit(retryAfter);
  }
}
