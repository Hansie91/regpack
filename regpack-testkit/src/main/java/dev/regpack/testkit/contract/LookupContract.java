package dev.regpack.testkit.contract;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.BatchId;
import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import dev.regpack.ports.connector.ConnectorKind;
import dev.regpack.ports.connector.EnrichmentSource;
import dev.regpack.ports.connector.EnrichmentSource.Context;
import dev.regpack.ports.connector.EnrichmentSource.KeyOutcome;
import dev.regpack.ports.connector.EnrichmentSource.Request;
import dev.regpack.ports.connector.LookupUnavailableException;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Contract every {@link EnrichmentSource} adapter must satisfy. Each test is a default method.
 * {@link #source()}, {@link #clock()} and {@link #context()} return the same instance on every call
 * within one test.
 */
public interface LookupContract {
  /**
   * The source under test, scripted: hits {@code K1} (value {@code {"lei":"LEI-K1"}}) and {@code
   * K2} (value {@code {"lei":"LEI-K2"}}), miss {@code M1}, failure {@code F1} ({@code E_UPSTREAM},
   * retryable), stale hit {@code S1} (as-of three days before the context), limits (3 keys, 2 s, 10
   * requests per second).
   */
  EnrichmentSource source();

  /** The clock the source under test reads; starts at 2026-10-09T08:00:00Z, the context's as-of. */
  ManualClock clock();

  /** Field {@code client.lei}, as-of 2026-10-09T08:00:00Z, batch {@code B1} slice 0. */
  Context context();

  /** Fault injection: the next lookup throws {@code TIMEOUT}. */
  void armTimeout();

  /** Fault injection: the next lookup throws {@code RATE_LIMITED} carrying {@code retryAfter}. */
  void armRateLimit(Duration retryAfter);

  /** The context every contract implementation returns from {@link #context()}. */
  static Context standardContext() {
    return new Context(
        "client.lei", Instant.parse("2026-10-09T08:00:00Z"), BatchId.of(Sha256.ofUtf8("B1"), 0));
  }

  private Request request(String... keys) {
    return new Request(List.of(keys), context());
  }

  private static byte[] utf8(Object value) {
    return Json.canonical(value).getBytes(UTF_8);
  }

  @Test
  default void oneOutcomePerDistinctKeyInRequestOrder() {
    List<KeyOutcome> outcomes = source().lookup(request("K2", "M1", "K1")).outcomes();
    assertThat(outcomes).hasSize(3);
    assertThat(outcomes.get(0)).isInstanceOf(KeyOutcome.Hit.class);
    assertThat(outcomes.get(0).key()).isEqualTo("K2");
    assertThat(outcomes.get(1)).isInstanceOf(KeyOutcome.Miss.class);
    assertThat(outcomes.get(1).key()).isEqualTo("M1");
    assertThat(outcomes.get(2)).isInstanceOf(KeyOutcome.Hit.class);
    assertThat(outcomes.get(2).key()).isEqualTo("K1");
  }

  @Test
  default void hitCarriesCanonicalBytesAndMatchingResponseHash() {
    KeyOutcome hit = source().lookup(request("K1")).outcomes().get(0);
    assertThat(hit).isInstanceOf(KeyOutcome.Hit.class);
    assertThat(hit.canonical()).isEqualTo(utf8(Map.of("lei", "LEI-K1")));
    assertThat(hit.responseHash()).isEqualTo(Sha256.of(hit.canonical()));
    assertThat(hit.asOf()).isEqualTo(context().asOf());
  }

  @Test
  default void duplicateKeysInRequestAreRejected() {
    EnrichmentSource source = source();
    assertThatThrownBy(() -> source.lookup(request("K1", "K1")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  default void batchLargerThanLimitIsRejected() {
    EnrichmentSource source = source();
    assertThat(source.limits().maxKeysPerRequest()).isEqualTo(3);
    assertThatThrownBy(() -> source.lookup(request("K1", "K2", "M1", "F1")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  default void partialResultsAreAllowed() {
    List<KeyOutcome> outcomes = source().lookup(request("K1", "F1", "M1")).outcomes();
    assertThat(outcomes).hasSize(3);
    assertThat(outcomes.get(0)).isInstanceOf(KeyOutcome.Hit.class);
    assertThat(outcomes.get(1)).isInstanceOf(KeyOutcome.Failure.class);
    KeyOutcome.Failure failure = (KeyOutcome.Failure) outcomes.get(1);
    assertThat(failure.key()).isEqualTo("F1");
    assertThat(failure.errorCode()).isEqualTo("E_UPSTREAM");
    assertThat(failure.retryable()).isTrue();
    assertThat(outcomes.get(2)).isInstanceOf(KeyOutcome.Miss.class);
  }

  @Test
  default void sameKeyAndAsOfGiveSameResponseHash() {
    EnrichmentSource source = source();
    KeyOutcome first = source.lookup(request("K1")).outcomes().get(0);
    KeyOutcome second = source.lookup(request("K1")).outcomes().get(0);
    assertThat(second.responseHash()).isEqualTo(first.responseHash());
    assertThat(second.canonical()).isEqualTo(first.canonical());
  }

  @Test
  default void staleValueCarriesItsOwnOlderAsOf() {
    KeyOutcome stale = source().lookup(request("S1")).outcomes().get(0);
    assertThat(stale).isInstanceOf(KeyOutcome.Hit.class);
    assertThat(stale.asOf()).isEqualTo(context().asOf().minus(Duration.ofDays(3)));
  }

  @Test
  default void missCarriesAsOfAndResponseHash() {
    KeyOutcome miss = source().lookup(request("M1")).outcomes().get(0);
    assertThat(miss).isInstanceOf(KeyOutcome.Miss.class);
    assertThat(miss.asOf()).isEqualTo(context().asOf());
    assertThat(miss.canonical()).isEqualTo(utf8(Map.of("key", "M1", "outcome", "MISS")));
    assertThat(miss.responseHash()).isEqualTo(Sha256.of(miss.canonical()));
  }

  @Test
  default void failureCarriesAsOfAndResponseHash() {
    Instant before = clock().now();
    KeyOutcome failure = source().lookup(request("F1")).outcomes().get(0);
    assertThat(failure).isInstanceOf(KeyOutcome.Failure.class);
    assertThat(failure.asOf()).isEqualTo(before).isEqualTo(context().asOf());
    assertThat(failure.canonical())
        .isEqualTo(
            utf8(
                Map.of(
                    "errorCode",
                    "E_UPSTREAM",
                    "key",
                    "F1",
                    "outcome",
                    "ERROR",
                    "retryable",
                    true)));
    assertThat(failure.responseHash()).isEqualTo(Sha256.of(failure.canonical()));
  }

  @Test
  default void timeoutThrowsLookupUnavailableTimeout() {
    EnrichmentSource source = source();
    armTimeout();
    assertThatThrownBy(() -> source.lookup(request("K1")))
        .isInstanceOfSatisfying(
            LookupUnavailableException.class,
            e -> assertThat(e.kind()).isEqualTo(LookupUnavailableException.Kind.TIMEOUT));
  }

  @Test
  default void rateLimitCarriesRetryAfter() {
    EnrichmentSource source = source();
    armRateLimit(Duration.ofSeconds(1));
    assertThatThrownBy(() -> source.lookup(request("K1")))
        .isInstanceOfSatisfying(
            LookupUnavailableException.class,
            e -> {
              assertThat(e.kind()).isEqualTo(LookupUnavailableException.Kind.RATE_LIMITED);
              assertThat(e.retryAfter()).isEqualTo(Optional.of(Duration.ofSeconds(1)));
            });
  }

  @Test
  default void specDeclaresLookupKind() {
    EnrichmentSource source = source();
    assertThat(source.spec().kind()).isEqualTo(ConnectorKind.LOOKUP);
    assertThat(source.spec().name()).isNotBlank();
    assertThat(source.check().ok()).isTrue();
  }
}
