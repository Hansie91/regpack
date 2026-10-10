package dev.regpack.testkit.sim;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import dev.regpack.ports.Clock;
import dev.regpack.ports.connector.CheckResult;
import dev.regpack.ports.connector.ConnectorKind;
import dev.regpack.ports.connector.ConnectorSpec;
import dev.regpack.ports.connector.Transport;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic in-memory destination. It owns the transport mechanics (commit gate, no-clobber
 * file ids, acknowledgement queue and cursor, endpoint clock); what the destination does with a
 * received file is a {@link Scenario}.
 */
public final class DestinationSimulator implements Transport {
  /** What a probe of a file that is not stored can prove. */
  public enum ProbeProof {
    /** The endpoint retains what it delivered, so a missing file is {@code ABSENT} (spec 9.2). */
    RETAINS_DELIVERED,
    /** The endpoint proves nothing, so a missing file is {@code UNKNOWABLE}. */
    NONE
  }

  private record QueuedAck(RawAck ack, Instant platformVisibleAt) {}

  private record Emitted(
      String fileId, byte[] bytes, Instant endpointAsOf, Instant platformVisibleAt) {}

  private final Clock platformClock;
  private final Clock endpointClock;
  private final Scenario scenario;
  private final Map<String, Scenario.ReceivedFile> stored = new LinkedHashMap<>();
  private final List<QueuedAck> queue = new ArrayList<>();
  private long lastPosition;
  private boolean failNext;
  private ProbeProof probeProof = ProbeProof.RETAINS_DELIVERED;

  public DestinationSimulator(Clock platformClock, Clock endpointClock, Scenario scenario) {
    this.platformClock = Objects.requireNonNull(platformClock, "platformClock");
    this.endpointClock = Objects.requireNonNull(endpointClock, "endpointClock");
    this.scenario = Objects.requireNonNull(scenario, "scenario");
  }

  /** A simulator whose endpoint clock is the platform clock itself. */
  public static DestinationSimulator withScenario(ManualClock clock, Scenario scenario) {
    return new DestinationSimulator(clock, clock, scenario);
  }

  /** The next {@code send} returns {@code FAILED_BEFORE_FIRST_BYTE} without calling its gate. */
  public synchronized void failNextSendBeforeFirstByte() {
    failNext = true;
  }

  /** Accepted files in arrival order. */
  public synchronized List<Scenario.ReceivedFile> receivedFiles() {
    return List.copyOf(stored.values());
  }

  /** Sets what a probe of an unstored file proves and returns this simulator. */
  public synchronized DestinationSimulator withProbeProof(ProbeProof proof) {
    this.probeProof = Objects.requireNonNull(proof, "proof");
    return this;
  }

  @Override
  public ConnectorSpec spec() {
    return new ConnectorSpec(
        "destination-simulator",
        "0.1.0",
        ConnectorKind.DESTINATION,
        Map.of("scenario", scenario.name()));
  }

  @Override
  public CheckResult check() {
    return new CheckResult(true, "destination simulator, scenario " + scenario.name());
  }

  @Override
  public AckModel ackModel() {
    return AckModel.ASYNC_FILE;
  }

  @Override
  public SendResult send(OutboundFile file, CommitGate gate) {
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(gate, "gate");
    synchronized (this) {
      if (failNext) {
        failNext = false;
        return new SendResult(
            SendStatus.FAILED_BEFORE_FIRST_BYTE, "armed failure before first byte");
      }
    }
    // The gate runs outside the lock and before any destination-side check (spec 11.2 step 4).
    // An exception from the gate propagates and nothing has become visible.
    if (!gate.permitFirstVisibleByte()) {
      return new SendResult(SendStatus.ABORTED_BY_GATE, "commit gate refused");
    }
    synchronized (this) {
      return receive(file);
    }
  }

  @Override
  public synchronized ProbeResult probe(String fileId) {
    Objects.requireNonNull(fileId, "fileId");
    Scenario.ReceivedFile file = stored.get(fileId);
    if (file != null) {
      return new ProbeResult(ProbeStatus.PRESENT, Optional.of(file.sha256()));
    }
    ProbeStatus status =
        probeProof == ProbeProof.NONE ? ProbeStatus.UNKNOWABLE : ProbeStatus.ABSENT;
    return new ProbeResult(status, Optional.empty());
  }

  @Override
  public synchronized AckPage pollAcks(AckCursor cursor, int max) {
    Objects.requireNonNull(cursor, "cursor");
    if (max < 0) {
      throw new IllegalArgumentException("max must not be negative");
    }
    Instant now = platformClock.now();
    List<RawAck> page = new ArrayList<>();
    for (QueuedAck queued : queue) {
      if (page.size() >= max) {
        break;
      }
      if (queued.ack().position() > cursor.position() && !queued.platformVisibleAt().isAfter(now)) {
        page.add(queued.ack());
      }
    }
    AckCursor next = page.isEmpty() ? cursor : new AckCursor(page.get(page.size() - 1).position());
    return new AckPage(page, next);
  }

  private SendResult receive(OutboundFile file) {
    if (stored.containsKey(file.fileId())) {
      return new SendResult(SendStatus.REJECTED_DUPLICATE, "file id already present");
    }
    Instant platformNow = platformClock.now();
    Instant endpointNow = endpointClock.now();
    byte[] bytes = file.bytes();
    Scenario.ReceivedFile received =
        new Scenario.ReceivedFile(
            file.fileId(), file.fileName(), Sha256.of(bytes), bytes, file.mediaType(), endpointNow);
    Handling handling =
        new Handling(
            endpointNow, Duration.between(platformNow, endpointNow), List.copyOf(stored.values()));
    Scenario.Disposition disposition;
    try {
      disposition = scenario.onReceive(received, handling);
    } finally {
      handling.close();
    }
    return switch (disposition) {
      case ACCEPT -> {
        stored.put(received.fileId(), received);
        for (Emitted pending : handling.emitted) {
          queue.add(
              new QueuedAck(
                  new RawAck(
                      ++lastPosition,
                      pending.fileId(),
                      pending.bytes(),
                      "application/json",
                      pending.endpointAsOf()),
                  pending.platformVisibleAt()));
        }
        yield new SendResult(SendStatus.DELIVERED, "accepted");
      }
      case DROP_SILENTLY -> new SendResult(SendStatus.DELIVERED, "accepted");
      case REJECT_DUPLICATE ->
          new SendResult(SendStatus.REJECTED_DUPLICATE, "rejected as duplicate by scenario");
    };
  }

  /** The context of one received file: acks are buffered until the scenario accepts the file. */
  private static final class Handling implements ScenarioContext {
    private final Instant endpointNow;
    private final Duration endpointOffset;
    private final List<Scenario.ReceivedFile> earlier;
    private final List<Emitted> emitted = new ArrayList<>();
    private boolean open = true;

    Handling(Instant endpointNow, Duration endpointOffset, List<Scenario.ReceivedFile> earlier) {
      this.endpointNow = endpointNow;
      this.endpointOffset = endpointOffset;
      this.earlier = earlier;
    }

    void close() {
      open = false;
    }

    @Override
    public Instant endpointNow() {
      return endpointNow;
    }

    @Override
    public List<Scenario.ReceivedFile> earlierFiles() {
      return earlier;
    }

    @Override
    public void emitAckAt(
        Instant visibleAt, String fileId, String status, Map<String, String> details) {
      if (!open) {
        throw new IllegalStateException("emitAckAt is valid only during Scenario.onReceive");
      }
      Objects.requireNonNull(visibleAt, "visibleAt");
      if (visibleAt.getNano() % 1000 != 0) {
        throw new IllegalArgumentException("visibleAt is not whole microseconds: " + visibleAt);
      }
      Objects.requireNonNull(fileId, "fileId");
      Objects.requireNonNull(status, "status");
      Map<String, Object> body = new LinkedHashMap<>(details);
      if (body.containsKey("fileId") || body.containsKey("status")) {
        throw new IllegalArgumentException("details must not contain fileId or status");
      }
      body.put("fileId", fileId);
      body.put("status", status);
      byte[] bytes = Json.canonical(body).getBytes(UTF_8);
      emitted.add(new Emitted(fileId, bytes, visibleAt, visibleAt.minus(endpointOffset)));
    }
  }
}
