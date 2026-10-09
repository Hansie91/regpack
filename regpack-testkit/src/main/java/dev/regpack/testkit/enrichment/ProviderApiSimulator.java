package dev.regpack.testkit.enrichment;

import dev.regpack.ports.connector.EnrichmentSource;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;

/**
 * A scripted stand-in for a third-party provider API: name {@code provider-api-simulator}, limits
 * of 100 keys per request, a 2 s timeout and 10 requests per second.
 */
public final class ProviderApiSimulator {
  private ProviderApiSimulator() {}

  public static ScriptedEnrichmentSource.Builder builder(ManualClock clock) {
    return ScriptedEnrichmentSource.builder(clock, "provider-api-simulator")
        .limits(new EnrichmentSource.Limits(100, Duration.ofSeconds(2), 10));
  }
}
