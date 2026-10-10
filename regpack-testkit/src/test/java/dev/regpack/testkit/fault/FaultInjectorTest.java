package dev.regpack.testkit.fault;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.BatchId;
import dev.regpack.model.Sha256;
import dev.regpack.ports.connector.EnrichmentSource;
import dev.regpack.ports.connector.LookupUnavailableException;
import dev.regpack.ports.connector.Transport;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.enrichment.ScriptedEnrichmentSource;
import dev.regpack.testkit.sim.DestinationSimulator;
import dev.regpack.testkit.sim.Scenarios;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FaultInjectorTest {
  private static final Instant START = Instant.parse("2026-10-09T08:00:00Z");

  interface Counter {
    int incr();
  }

  private final AtomicInteger count = new AtomicInteger();
  private final Counter delegate = count::incrementAndGet;

  @Test
  void crashBeforeLeavesTheEffectUnapplied() {
    Counter counter =
        new FaultInjector(FaultPlan.crashAt("Counter.incr:BEFORE", 2))
            .wrap(Counter.class, delegate);
    assertThat(counter.incr()).isEqualTo(1);
    assertThatThrownBy(counter::incr)
        .isInstanceOfSatisfying(
            SimulatedCrash.class,
            crash -> assertThat(crash.point()).isEqualTo("Counter.incr:BEFORE#2"));
    assertThat(count.get()).isEqualTo(1);
  }

  @Test
  void crashAfterLeavesTheEffectCommitted() {
    Counter counter =
        new FaultInjector(FaultPlan.crashAt("Counter.incr:AFTER", 1)).wrap(Counter.class, delegate);
    assertThatThrownBy(counter::incr)
        .isInstanceOfSatisfying(
            SimulatedCrash.class,
            crash -> assertThat(crash.point()).isEqualTo("Counter.incr:AFTER#1"));
    assertThat(count.get()).isEqualTo(1);
  }

  @Test
  void failAtThrowsTheGivenExceptionOnlyOnTheNthHit() {
    IllegalStateException boom = new IllegalStateException("boom");
    Counter counter =
        new FaultInjector(FaultPlan.failAt("Counter.incr:BEFORE", 2, boom))
            .wrap(Counter.class, delegate);
    assertThat(counter.incr()).isEqualTo(1);
    assertThatThrownBy(counter::incr).isSameAs(boom);
    assertThat(counter.incr()).isEqualTo(2);
    assertThat(count.get()).isEqualTo(2);
  }

  @Test
  void traceRecordsBeforeAndAfterInOrderAndDigestIsStable() {
    FaultInjector first = new FaultInjector(FaultPlan.none());
    Counter counter = first.wrap(Counter.class, delegate);
    counter.incr();
    counter.incr();
    assertThat(first.trace())
        .containsExactly(
            "Counter.incr:BEFORE#1",
            "Counter.incr:AFTER#1",
            "Counter.incr:BEFORE#2",
            "Counter.incr:AFTER#2");
    FaultInjector second = new FaultInjector(FaultPlan.none());
    Counter other = second.wrap(Counter.class, new AtomicInteger()::incrementAndGet);
    other.incr();
    other.incr();
    assertThat(second.traceDigest()).isEqualTo(first.traceDigest());
    assertThat(first.traceDigest())
        .isEqualTo(Sha256.of(String.join("\n", first.trace()).getBytes(UTF_8)));
    other.incr();
    assertThat(second.traceDigest()).isNotEqualTo(first.traceDigest());
  }

  @Test
  void delegateExceptionPassesThroughUnwrapped() {
    FaultInjector injector = new FaultInjector(FaultPlan.none());
    IllegalStateException failure = new IllegalStateException("delegate failed");
    Counter counter =
        injector.wrap(
            Counter.class,
            () -> {
              throw failure;
            });
    assertThatThrownBy(counter::incr).isSameAs(failure);
    assertThat(injector.trace()).containsExactly("Counter.incr:BEFORE#1");
  }

  @Test
  void advanceClockFaultMovesTheManualClock() {
    ManualClock clock = new ManualClock(START);
    FaultInjector injector =
        new FaultInjector(
            FaultPlan.advanceClockAt("Counter.incr:AFTER", 2, Duration.ofSeconds(30)), clock);
    Counter counter = injector.wrap(Counter.class, delegate);
    counter.incr();
    assertThat(clock.now()).isEqualTo(START);
    counter.incr();
    assertThat(clock.now()).isEqualTo(START.plusSeconds(30));
    counter.incr();
    assertThat(clock.now()).isEqualTo(START.plusSeconds(30));
  }

  @Test
  void singleArgumentConstructorRejectsPlansWithClockFaults() {
    FaultPlan advance = FaultPlan.advanceClockAt("X.y:AFTER", 1, Duration.ofSeconds(1));
    assertThatThrownBy(() -> new FaultInjector(advance))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FaultInjector(FaultPlan.crashAt("X.y:BEFORE", 1).then(advance)))
        .isInstanceOf(IllegalArgumentException.class);
    // The same plans are fine with a clock to move.
    assertThat(new FaultInjector(advance, new ManualClock(START))).isNotNull();
  }

  @Test
  void whenFaultsShareAPointTheFirstThrowingFaultWinsAndClockAdvancesStillApply() {
    IllegalStateException boom = new IllegalStateException("boom");
    ManualClock clock = new ManualClock(START);
    FaultPlan crashFirst =
        FaultPlan.crashAt("Counter.incr:BEFORE", 1)
            .then(FaultPlan.failAt("Counter.incr:BEFORE", 1, boom))
            .then(FaultPlan.advanceClockAt("Counter.incr:BEFORE", 1, Duration.ofSeconds(30)));
    Counter crashing = new FaultInjector(crashFirst, clock).wrap(Counter.class, delegate);
    assertThatThrownBy(crashing::incr).isInstanceOf(SimulatedCrash.class);
    assertThat(clock.now()).isEqualTo(START.plusSeconds(30));
    assertThat(count.get()).isZero();

    FaultPlan failFirst =
        FaultPlan.failAt("Counter.incr:BEFORE", 1, boom)
            .then(FaultPlan.crashAt("Counter.incr:BEFORE", 1));
    Counter failing = new FaultInjector(failFirst).wrap(Counter.class, delegate);
    assertThatThrownBy(failing::incr).isSameAs(boom);
    assertThat(count.get()).isZero();
  }

  @Test
  void wrapRejectsClasses() {
    FaultInjector injector = new FaultInjector(FaultPlan.none());
    assertThatThrownBy(() -> injector.wrap(AtomicInteger.class, new AtomicInteger()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void objectMethodsAreNotTraced() {
    FaultInjector injector = new FaultInjector(FaultPlan.none());
    Counter counter = injector.wrap(Counter.class, delegate);
    assertThat(counter.equals(counter)).isTrue();
    assertThat(counter.hashCode()).isEqualTo(counter.hashCode());
    assertThat(counter.toString()).isNotNull();
    assertThat(injector.trace()).isEmpty();
  }

  @Test
  void faultsApplyToEnrichmentSourceAndTransportPorts() {
    ManualClock clock = new ManualClock(START);
    ScriptedEnrichmentSource source =
        ScriptedEnrichmentSource.builder(clock, "scripted").hit("K1", Map.of("v", "1")).build();
    FaultInjector lookups =
        new FaultInjector(
            FaultPlan.failAt(
                "EnrichmentSource.lookup:BEFORE",
                1,
                new LookupUnavailableException(
                    LookupUnavailableException.Kind.UNAVAILABLE, "DOWN", Optional.empty())));
    EnrichmentSource wrappedSource = lookups.wrap(EnrichmentSource.class, source);
    EnrichmentSource.Request request =
        new EnrichmentSource.Request(
            List.of("K1"),
            new EnrichmentSource.Context("field", START, BatchId.of(Sha256.ofUtf8("landing"), 0)));
    assertThatThrownBy(() -> wrappedSource.lookup(request))
        .isInstanceOf(LookupUnavailableException.class);
    assertThat(source.requestCount()).isZero();
    assertThat(wrappedSource.lookup(request).outcomes()).hasSize(1);

    DestinationSimulator simulator =
        DestinationSimulator.withScenario(clock, Scenarios.acpt(Duration.ofSeconds(1)));
    FaultInjector sends = new FaultInjector(FaultPlan.crashAt("Transport.send:AFTER", 1));
    Transport wrappedTransport = sends.wrap(Transport.class, simulator);
    byte[] bytes = "<a/>".getBytes(UTF_8);
    Transport.OutboundFile file =
        new Transport.OutboundFile("F-1", "F-1.xml", bytes, Sha256.of(bytes), "application/xml");
    assertThatThrownBy(() -> wrappedTransport.send(file, () -> true))
        .isInstanceOf(SimulatedCrash.class);
    assertThat(simulator.probe("F-1").status()).isEqualTo(Transport.ProbeStatus.PRESENT);
  }
}
