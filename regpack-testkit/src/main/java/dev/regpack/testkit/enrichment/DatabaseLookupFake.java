package dev.regpack.testkit.enrichment;

import dev.regpack.ports.connector.EnrichmentSource;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;

/**
 * A scripted stand-in for a database lookup: name {@code database-lookup-fake}, limits of 1000 keys
 * per request, a 500 ms timeout and no request-rate limit (0 means unlimited).
 */
public final class DatabaseLookupFake {
  private DatabaseLookupFake() {}

  public static ScriptedEnrichmentSource.Builder builder(ManualClock clock) {
    return ScriptedEnrichmentSource.builder(clock, "database-lookup-fake")
        .limits(new EnrichmentSource.Limits(1000, Duration.ofMillis(500), 0));
  }
}
