package dev.regpack.testkit.enrichment;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import dev.regpack.ports.connector.EnrichmentSource;
import dev.regpack.ports.connector.EnrichmentSource.Context;
import dev.regpack.ports.connector.EnrichmentSource.KeyOutcome;
import dev.regpack.ports.connector.EnrichmentSource.Request;
import dev.regpack.ports.connector.LookupUnavailableException;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.LookupContract;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ScriptedEnrichmentSourceLookupContractTest implements LookupContract {
  private static final Instant START = Instant.parse("2026-10-09T08:00:00Z");
  private static final EnrichmentSource.Limits LIMITS =
      new EnrichmentSource.Limits(3, Duration.ofSeconds(2), 10);

  private final ManualClock clock = new ManualClock(START);
  private final Context context = LookupContract.standardContext();
  private final ScriptedEnrichmentSource source =
      ScriptedEnrichmentSource.builder(clock, "scripted-lookup")
          .limits(LIMITS)
          .hit("K1", Map.of("lei", "LEI-K1"))
          .hit("K2", Map.of("lei", "LEI-K2"))
          .miss("M1")
          .failure("F1", "E_UPSTREAM", true)
          .staleHit("S1", Map.of("lei", "LEI-S1"), START.minus(Duration.ofDays(3)))
          .build();

  @Override
  public EnrichmentSource source() {
    return source;
  }

  @Override
  public ManualClock clock() {
    return clock;
  }

  @Override
  public Context context() {
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

  private Request request(String... keys) {
    return new Request(List.of(keys), context);
  }

  @Test
  void timeoutIsDeterministicViaClock() {
    ManualClock own = new ManualClock(START);
    ScriptedEnrichmentSource timing =
        ScriptedEnrichmentSource.builder(own, "timing")
            .limits(LIMITS)
            .timeoutOnKey("T1")
            .hit("K1", Map.of("lei", "LEI-K1"))
            .build();
    long before = own.monotonicNanos();
    assertThatThrownBy(() -> timing.lookup(request("K1", "T1")))
        .isInstanceOfSatisfying(
            LookupUnavailableException.class,
            e -> assertThat(e.kind()).isEqualTo(LookupUnavailableException.Kind.TIMEOUT));
    assertThat(own.monotonicNanos() - before).isEqualTo(2_000_000_000L);
  }

  @Test
  void rateLimitedRequestSurfacesRetryAfter() {
    ScriptedEnrichmentSource limited =
        ScriptedEnrichmentSource.builder(clock, "limited")
            .limits(LIMITS)
            .hit("K1", Map.of("lei", "LEI-K1"))
            .rateLimitedOnRequest(2, Duration.ofSeconds(1))
            .build();
    assertThat(limited.lookup(request("K1")).outcomes()).hasSize(1);
    assertThatThrownBy(() -> limited.lookup(request("K1")))
        .isInstanceOfSatisfying(
            LookupUnavailableException.class,
            e -> {
              assertThat(e.kind()).isEqualTo(LookupUnavailableException.Kind.RATE_LIMITED);
              assertThat(e.retryAfter()).isEqualTo(Optional.of(Duration.ofSeconds(1)));
            });
    assertThat(limited.lookup(request("K1")).outcomes()).hasSize(1);
    assertThat(limited.requestCount()).isEqualTo(3);
  }

  @Test
  void failureAsOfIsTheRequestStartEvenWhenLatencyAdvancesTheClock() {
    ManualClock own = new ManualClock(START);
    ScriptedEnrichmentSource slow =
        ScriptedEnrichmentSource.builder(own, "slow-failing")
            .limits(LIMITS)
            .failure("F1", "E_UPSTREAM", true)
            .latency(Duration.ofMillis(250))
            .build();
    KeyOutcome outcome = slow.lookup(request("F1")).outcomes().get(0);
    assertThat(outcome).isInstanceOf(KeyOutcome.Failure.class);
    assertThat(outcome.asOf()).isEqualTo(START);
    assertThat(own.now()).isEqualTo(START.plusMillis(250));
  }

  @Test
  void armedTimeoutAdvancesTheClockByTheTimeoutExactlyOnceAndIsOneShot() {
    ManualClock own = new ManualClock(START);
    ScriptedEnrichmentSource armed =
        ScriptedEnrichmentSource.builder(own, "armed-timeout")
            .limits(LIMITS)
            .hit("K1", Map.of("lei", "LEI-K1"))
            .build();
    armed.armTimeout();
    long before = own.monotonicNanos();
    assertThatThrownBy(() -> armed.lookup(request("K1")))
        .isInstanceOfSatisfying(
            LookupUnavailableException.class,
            e -> assertThat(e.kind()).isEqualTo(LookupUnavailableException.Kind.TIMEOUT));
    assertThat(own.monotonicNanos() - before).isEqualTo(2_000_000_000L);
    assertThat(armed.requestCount()).isEqualTo(1);
    // One-shot: the next lookup is answered, and without a further timeout advance.
    assertThat(armed.lookup(request("K1")).outcomes()).hasSize(1);
    assertThat(own.monotonicNanos() - before).isEqualTo(2_000_000_000L);
    assertThat(armed.lookup(request("K1")).outcomes()).hasSize(1);
    assertThat(own.monotonicNanos() - before).isEqualTo(2_000_000_000L);
  }

  @Test
  void armedRateLimitIsOneShotAndAdvancesNothing() {
    ManualClock own = new ManualClock(START);
    ScriptedEnrichmentSource armed =
        ScriptedEnrichmentSource.builder(own, "armed-rate-limit")
            .limits(LIMITS)
            .hit("K1", Map.of("lei", "LEI-K1"))
            .build();
    armed.armRateLimit(Duration.ofSeconds(7));
    assertThatThrownBy(() -> armed.lookup(request("K1")))
        .isInstanceOfSatisfying(
            LookupUnavailableException.class,
            e -> {
              assertThat(e.kind()).isEqualTo(LookupUnavailableException.Kind.RATE_LIMITED);
              assertThat(e.retryAfter()).isEqualTo(Optional.of(Duration.ofSeconds(7)));
            });
    assertThat(own.monotonicNanos()).isZero();
    assertThat(armed.lookup(request("K1")).outcomes()).hasSize(1);
    assertThat(armed.lookup(request("K1")).outcomes()).hasSize(1);
  }

  @Test
  void anUnscriptedKeyIsAMissWithTheCanonicalMissBytes() {
    KeyOutcome outcome = source.lookup(request("UNKNOWN")).outcomes().get(0);
    assertThat(outcome).isInstanceOf(KeyOutcome.Miss.class);
    assertThat(outcome.key()).isEqualTo("UNKNOWN");
    assertThat(outcome.asOf()).isEqualTo(context.asOf());
    byte[] expected = Json.canonical(Map.of("key", "UNKNOWN", "outcome", "MISS")).getBytes(UTF_8);
    assertThat(outcome.canonical()).isEqualTo(expected);
    assertThat(outcome.responseHash()).isEqualTo(Sha256.of(expected));
  }

  @Test
  void conflictingAnswersFromTwoSourcesAreRepresentable() {
    ScriptedEnrichmentSource other =
        ScriptedEnrichmentSource.builder(clock, "other")
            .limits(LIMITS)
            .hit("K1", Map.of("lei", "LEI-OTHER"))
            .build();
    KeyOutcome ours = source.lookup(request("K1")).outcomes().get(0);
    KeyOutcome theirs = other.lookup(request("K1")).outcomes().get(0);
    assertThat(theirs.responseHash()).isNotEqualTo(ours.responseHash());
  }

  @Test
  void latencyAdvancesTheClockWithoutSleeping() {
    ManualClock own = new ManualClock(START);
    ScriptedEnrichmentSource slow =
        ScriptedEnrichmentSource.builder(own, "slow")
            .limits(LIMITS)
            .hit("K1", Map.of("lei", "LEI-K1"))
            .latency(Duration.ofMillis(250))
            .build();
    long before = own.monotonicNanos();
    slow.lookup(request("K1"));
    slow.lookup(request("K1"));
    slow.lookup(request("K1"));
    assertThat(own.monotonicNanos() - before).isEqualTo(750_000_000L);
  }
}
