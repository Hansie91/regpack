package dev.regpack.testkit.contract;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import dev.regpack.ports.connector.ConnectorKind;
import dev.regpack.ports.connector.Transport;
import dev.regpack.ports.connector.Transport.AckCursor;
import dev.regpack.ports.connector.Transport.AckPage;
import dev.regpack.ports.connector.Transport.OutboundFile;
import dev.regpack.ports.connector.Transport.ProbeStatus;
import dev.regpack.ports.connector.Transport.SendStatus;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Contract every {@link Transport} adapter must satisfy. Each test is a default method. {@link
 * #transport()} and {@link #clock()} return the same instance on every call within one test.
 */
public interface TransportContract {
  /** The transport under test: an ACPT scenario with a 1 s delay on a platform clock. */
  Transport transport();

  /** The platform clock the transport under test reads. */
  ManualClock clock();

  /** Fault injection: the next send fails before anything becomes visible. */
  void armFailureBeforeFirstByte();

  private static OutboundFile file(String fileId, String content) {
    byte[] bytes = content.getBytes(UTF_8);
    return new OutboundFile(fileId, fileId + ".xml", bytes, Sha256.of(bytes), "application/xml");
  }

  private static Transport.CommitGate counting(AtomicInteger calls) {
    return () -> {
      calls.incrementAndGet();
      return true;
    };
  }

  @Test
  default void sendDeliversAndProbeShowsPresentWithHash() {
    Transport transport = transport();
    OutboundFile file = file("F-1", "<a/>");
    assertThat(transport.send(file, () -> true).status()).isEqualTo(SendStatus.DELIVERED);
    Transport.ProbeResult probe = transport.probe("F-1");
    assertThat(probe.status()).isEqualTo(ProbeStatus.PRESENT);
    assertThat(probe.sha256()).contains(file.sha256());
  }

  @Test
  default void gateIsCalledExactlyOnceBeforeAnythingIsVisible() {
    Transport transport = transport();
    AtomicInteger calls = new AtomicInteger();
    Transport.CommitGate gate =
        () -> {
          calls.incrementAndGet();
          assertThat(transport.probe("F-1").status()).isEqualTo(ProbeStatus.ABSENT);
          return true;
        };
    assertThat(transport.send(file("F-1", "<a/>"), gate).status()).isEqualTo(SendStatus.DELIVERED);
    assertThat(calls).hasValue(1);
    assertThat(transport.probe("F-1").status()).isEqualTo(ProbeStatus.PRESENT);
  }

  @Test
  default void refusedGateLeavesNothingVisible() {
    Transport transport = transport();
    assertThat(transport.send(file("F-1", "<a/>"), () -> false).status())
        .isEqualTo(SendStatus.ABORTED_BY_GATE);
    assertThat(transport.probe("F-1").status()).isEqualTo(ProbeStatus.ABSENT);
  }

  @Test
  default void duplicateFileIdIsRejectedNoClobber() {
    Transport transport = transport();
    OutboundFile first = file("F-1", "<a/>");
    assertThat(transport.send(first, () -> true).status()).isEqualTo(SendStatus.DELIVERED);
    assertThat(transport.send(file("F-1", "<b/>"), () -> true).status())
        .isEqualTo(SendStatus.REJECTED_DUPLICATE);
    assertThat(transport.probe("F-1").sha256()).contains(first.sha256());
  }

  @Test
  default void failureBeforeFirstByteDoesNotInvokeGate() {
    Transport transport = transport();
    AtomicInteger calls = new AtomicInteger();
    armFailureBeforeFirstByte();
    assertThat(transport.send(file("F-1", "<a/>"), counting(calls)).status())
        .isEqualTo(SendStatus.FAILED_BEFORE_FIRST_BYTE);
    assertThat(calls).hasValue(0);
    assertThat(transport.probe("F-1").status()).isEqualTo(ProbeStatus.ABSENT);
  }

  @Test
  default void pollAcksHonoursCursorAndAckDelay() {
    Transport transport = transport();
    assertThat(transport.send(file("F-1", "<a/>"), () -> true).status())
        .isEqualTo(SendStatus.DELIVERED);
    assertThat(transport.pollAcks(AckCursor.start(), 10).acks()).isEmpty();
    clock().advance(Duration.ofSeconds(1));
    AckPage page = transport.pollAcks(AckCursor.start(), 10);
    assertThat(page.acks()).hasSize(1);
    assertThat(page.acks().get(0).fileId()).isEqualTo("F-1");
    Object parsed = Json.parse(new String(page.acks().get(0).bytes(), UTF_8));
    assertThat(parsed).isEqualTo(Map.of("fileId", "F-1", "status", "ACPT"));
    assertThat(transport.pollAcks(page.next(), 10).acks()).isEmpty();
  }

  @Test
  default void pollAcksNeverReturnsMoreThanMaxAndRepollYieldsNothingSeen() {
    Transport transport = transport();
    for (String id : new String[] {"F-1", "F-2", "F-3"}) {
      assertThat(transport.send(file(id, "<" + id + "/>"), () -> true).status())
          .isEqualTo(SendStatus.DELIVERED);
    }
    clock().advance(Duration.ofSeconds(1));
    AckPage first = transport.pollAcks(AckCursor.start(), 2);
    assertThat(first.acks()).hasSize(2);
    AckPage second = transport.pollAcks(first.next(), 2);
    assertThat(second.acks()).hasSize(1);
    assertThat(second.acks().get(0).fileId()).isEqualTo("F-3");
    assertThat(transport.pollAcks(second.next(), 2).acks()).isEmpty();
  }

  @Test
  default void duplicateFileIdInvokesTheGateOnceAndIsRejected() {
    Transport transport = transport();
    OutboundFile first = file("F-1", "<a/>");
    assertThat(transport.send(first, () -> true).status()).isEqualTo(SendStatus.DELIVERED);
    AtomicInteger calls = new AtomicInteger();
    assertThat(transport.send(file("F-1", "<b/>"), counting(calls)).status())
        .isEqualTo(SendStatus.REJECTED_DUPLICATE);
    assertThat(calls).hasValue(1);
    assertThat(transport.probe("F-1").sha256()).contains(first.sha256());
  }

  @Test
  default void throwingGateLeavesNothingVisibleAndPropagates() {
    Transport transport = transport();
    IllegalStateException failure = new IllegalStateException("gate unavailable");
    assertThatThrownBy(
            () ->
                transport.send(
                    file("F-1", "<a/>"),
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);
    assertThat(transport.probe("F-1").status()).isEqualTo(ProbeStatus.ABSENT);
  }

  @Test
  default void specAndAckModelAreDeclared() {
    Transport transport = transport();
    assertThat(transport.spec().kind()).isEqualTo(ConnectorKind.DESTINATION);
    assertThat(transport.spec().name()).isNotBlank();
    assertThat(transport.check().ok()).isTrue();
    assertThat(transport.ackModel()).isNotNull();
  }
}
