package dev.regpack.testkit.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.ports.connector.EnrichmentSource;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EnrichmentFakesTest {
  private final ManualClock clock = new ManualClock(Instant.parse("2026-10-09T08:00:00Z"));

  @Test
  void providerApiSimulatorHasDocumentedNameAndLimits() {
    ScriptedEnrichmentSource source = ProviderApiSimulator.builder(clock).build();
    assertThat(source.spec().name()).isEqualTo("provider-api-simulator");
    assertThat(source.limits())
        .isEqualTo(new EnrichmentSource.Limits(100, Duration.ofSeconds(2), 10));
  }

  @Test
  void databaseLookupFakeHasDocumentedNameAndLimits() {
    ScriptedEnrichmentSource source = DatabaseLookupFake.builder(clock).build();
    assertThat(source.spec().name()).isEqualTo("database-lookup-fake");
    assertThat(source.limits())
        .isEqualTo(new EnrichmentSource.Limits(1000, Duration.ofMillis(500), 0));
  }
}
