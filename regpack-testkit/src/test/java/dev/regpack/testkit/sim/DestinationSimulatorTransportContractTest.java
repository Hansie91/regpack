package dev.regpack.testkit.sim;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Sha256;
import dev.regpack.ports.connector.Transport;
import dev.regpack.ports.connector.Transport.AckCursor;
import dev.regpack.ports.connector.Transport.AckModel;
import dev.regpack.ports.connector.Transport.AckPage;
import dev.regpack.ports.connector.Transport.OutboundFile;
import dev.regpack.ports.connector.Transport.ProbeStatus;
import dev.regpack.ports.connector.Transport.SendStatus;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.TransportContract;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DestinationSimulatorTransportContractTest implements TransportContract {
  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  private final ManualClock clock = new ManualClock(START);
  private final DestinationSimulator simulator =
      DestinationSimulator.withScenario(clock, Scenarios.acpt(Duration.ofSeconds(1)));

  @Override
  public Transport transport() {
    return simulator;
  }

  @Override
  public ManualClock clock() {
    return clock;
  }

  @Override
  public void armFailureBeforeFirstByte() {
    simulator.failNextSendBeforeFirstByte();
  }

  private static OutboundFile file(String fileId, String content) {
    byte[] bytes = content.getBytes(UTF_8);
    return new OutboundFile(fileId, fileId + ".xml", bytes, Sha256.of(bytes), "application/xml");
  }

  @Override
  @Test
  public void throwingGateLeavesNothingVisibleAndPropagates() {
    TransportContract.super.throwingGateLeavesNothingVisibleAndPropagates();
    assertThat(simulator.receivedFiles()).isEmpty();
  }

  @Override
  @Test
  public void failureBeforeFirstByteDoesNotInvokeGate() {
    TransportContract.super.failureBeforeFirstByteDoesNotInvokeGate();
    // The armed failure applies to one send only: the next send of the same file goes through.
    assertThat(simulator.send(file("F-1", "<a/>"), () -> true).status())
        .isEqualTo(SendStatus.DELIVERED);
    assertThat(simulator.probe("F-1").status()).isEqualTo(ProbeStatus.PRESENT);
  }

  @Override
  @Test
  public void specAndAckModelAreDeclared() {
    TransportContract.super.specAndAckModelAreDeclared();
    assertThat(simulator.ackModel()).isEqualTo(AckModel.ASYNC_FILE);
  }

  @Test
  void silenceNeverProducesAcksButFileStaysPresent() {
    DestinationSimulator silent = DestinationSimulator.withScenario(clock, Scenarios.silence());
    assertThat(silent.send(file("F-1", "<a/>"), () -> true).status())
        .isEqualTo(SendStatus.DELIVERED);
    clock.advance(Duration.ofDays(30));
    assertThat(silent.pollAcks(AckCursor.start(), 10).acks()).isEmpty();
    assertThat(silent.probe("F-1").status()).isEqualTo(ProbeStatus.PRESENT);
  }

  @Test
  void endpointAsOfComesFromTheEndpointClock() {
    ManualClock platform = new ManualClock(START);
    ManualClock endpoint = new ManualClock(START.plus(Duration.ofHours(2)));
    DestinationSimulator skewed =
        new DestinationSimulator(platform, endpoint, Scenarios.acpt(Duration.ofSeconds(1)));
    assertThat(skewed.send(file("F-1", "<a/>"), () -> true).status())
        .isEqualTo(SendStatus.DELIVERED);
    platform.advance(Duration.ofSeconds(1));
    AckPage page = skewed.pollAcks(AckCursor.start(), 10);
    assertThat(page.acks()).hasSize(1);
    assertThat(page.acks().get(0).endpointAsOf())
        .isEqualTo(START.plus(Duration.ofSeconds(1)).plus(Duration.ofHours(2)));
  }

  @Test
  void receivedFilesRecordsOnlyTheFirstCopy() {
    OutboundFile first = file("F-1", "<a/>");
    simulator.send(first, () -> true);
    simulator.send(file("F-1", "<b/>"), () -> true);
    assertThat(simulator.receivedFiles()).hasSize(1);
    assertThat(simulator.receivedFiles().get(0).sha256()).isEqualTo(first.sha256());
    assertThat(simulator.receivedFiles().get(0).bytes()).isEqualTo(first.bytes());
    assertThat(simulator.receivedFiles().get(0).receivedAt()).isEqualTo(START);
  }

  @Test
  void scenarioCanDropAFileSilentlyAndProbeIsAbsent() {
    Scenario dropping =
        new Scenario() {
          @Override
          public String name() {
            return "DROP";
          }

          @Override
          public Disposition onReceive(ReceivedFile file, ScenarioContext context) {
            context.emitAckAt(context.endpointNow(), file.fileId(), "ACPT", Map.of());
            return Disposition.DROP_SILENTLY;
          }
        };
    DestinationSimulator dropper = DestinationSimulator.withScenario(clock, dropping);
    assertThat(dropper.send(file("F-1", "<a/>"), () -> true).status())
        .isEqualTo(SendStatus.DELIVERED);
    assertThat(dropper.receivedFiles()).isEmpty();
    assertThat(dropper.probe("F-1").status()).isEqualTo(ProbeStatus.ABSENT);
    clock.advance(Duration.ofDays(30));
    assertThat(dropper.pollAcks(AckCursor.start(), 10).acks()).isEmpty();
  }

  @Test
  void acksOfARejectedDuplicateStayDiscarded() {
    Scenario rejecting =
        new Scenario() {
          @Override
          public String name() {
            return "REJECT_WITH_ACK";
          }

          @Override
          public Disposition onReceive(ReceivedFile file, ScenarioContext context) {
            context.emitAckAt(context.endpointNow(), file.fileId(), "ACPT", Map.of());
            return Disposition.REJECT_DUPLICATE;
          }
        };
    DestinationSimulator rejector = DestinationSimulator.withScenario(clock, rejecting);
    assertThat(rejector.send(file("F-1", "<a/>"), () -> true).status())
        .isEqualTo(SendStatus.REJECTED_DUPLICATE);
    clock.advance(Duration.ofDays(30));
    assertThat(rejector.pollAcks(AckCursor.start(), 10).acks()).isEmpty();
    assertThat(rejector.receivedFiles()).isEmpty();
  }

  @Test
  void emitAckAtRejectsVisibleAtThatIsNotWholeMicroseconds() {
    Scenario nanos =
        new Scenario() {
          @Override
          public String name() {
            return "NANOS";
          }

          @Override
          public Disposition onReceive(ReceivedFile file, ScenarioContext context) {
            context.emitAckAt(context.endpointNow().plusNanos(1), file.fileId(), "ACPT", Map.of());
            return Disposition.ACCEPT;
          }
        };
    DestinationSimulator sim = DestinationSimulator.withScenario(clock, nanos);
    assertThatThrownBy(() -> sim.send(file("F-1", "<a/>"), () -> true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("microsecond");
    assertThat(sim.receivedFiles()).isEmpty();
    clock.advance(Duration.ofDays(1));
    assertThat(sim.pollAcks(AckCursor.start(), 10).acks()).isEmpty();
  }

  @Test
  void aThrowingScenarioClosesItsContext() {
    List<ScenarioContext> seen = new ArrayList<>();
    Scenario throwing =
        new Scenario() {
          @Override
          public String name() {
            return "THROWS";
          }

          @Override
          public Disposition onReceive(ReceivedFile file, ScenarioContext context) {
            seen.add(context);
            context.emitAckAt(context.endpointNow(), file.fileId(), "ACPT", Map.of());
            throw new IllegalStateException("scenario failed");
          }
        };
    DestinationSimulator sim = DestinationSimulator.withScenario(clock, throwing);
    assertThatThrownBy(() -> sim.send(file("F-1", "<a/>"), () -> true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("scenario failed");
    assertThat(seen).hasSize(1);
    // A scenario that kept the context cannot use it after the exception either.
    assertThatThrownBy(() -> seen.get(0).emitAckAt(START, "F-1", "ACPT", Map.of()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sim.receivedFiles()).isEmpty();
    clock.advance(Duration.ofDays(1));
    assertThat(sim.pollAcks(AckCursor.start(), 10).acks()).isEmpty();
  }

  @Test
  void scenarioCanReadBytesAndEarlierFiles() {
    Scenario sameBytes =
        new Scenario() {
          @Override
          public String name() {
            return "SAME_BYTES";
          }

          @Override
          public Disposition onReceive(ReceivedFile file, ScenarioContext context) {
            boolean seen =
                context.earlierFiles().stream()
                    .anyMatch(
                        earlier ->
                            Arrays.equals(earlier.bytes(), file.bytes())
                                && !earlier.fileId().equals(file.fileId()));
            return seen ? Disposition.REJECT_DUPLICATE : Disposition.ACCEPT;
          }
        };
    DestinationSimulator sim = DestinationSimulator.withScenario(clock, sameBytes);
    assertThat(sim.send(file("F-1", "<a/>"), () -> true).status()).isEqualTo(SendStatus.DELIVERED);
    assertThat(sim.send(file("F-2", "<a/>"), () -> true).status())
        .isEqualTo(SendStatus.REJECTED_DUPLICATE);
    assertThat(sim.receivedFiles()).hasSize(1);
    assertThat(sim.probe("F-2").status()).isEqualTo(ProbeStatus.ABSENT);
    assertThat(sim.send(file("F-3", "<c/>"), () -> true).status()).isEqualTo(SendStatus.DELIVERED);
    assertThat(sim.receivedFiles()).hasSize(2);
  }

  @Test
  void probeProofNoneMakesAbsentUnknowable() {
    DestinationSimulator sim =
        DestinationSimulator.withScenario(clock, Scenarios.acpt(Duration.ofSeconds(1)))
            .withProbeProof(DestinationSimulator.ProbeProof.NONE);
    assertThat(sim.probe("F-1").status()).isEqualTo(ProbeStatus.UNKNOWABLE);
    sim.send(file("F-1", "<a/>"), () -> true);
    assertThat(sim.probe("F-1").status()).isEqualTo(ProbeStatus.PRESENT);
    assertThat(sim.probe("F-2").status()).isEqualTo(ProbeStatus.UNKNOWABLE);
  }
}
