package dev.regpack.inmemory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Checkpoint;
import dev.regpack.model.Sha256;
import dev.regpack.ports.connector.SourceConnector;
import dev.regpack.ports.connector.SourceConnector.Batch;
import dev.regpack.ports.connector.SourceConnector.BatchStream;
import dev.regpack.ports.connector.SourceConnector.ReadLimits;
import dev.regpack.ports.connector.SourceConnector.ReadRequest;
import dev.regpack.testkit.contract.SourceConnectorContract;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InMemorySourceConnectorContractTest implements SourceConnectorContract {
  private InMemorySourceConnector current;

  @Override
  public SourceConnector connector(int batchCount) {
    List<byte[]> payloads = new ArrayList<>();
    for (int i = 1; i <= batchCount; i++) {
      payloads.add(("{\"id\":" + i + "}\n").getBytes(UTF_8));
    }
    current = new InMemorySourceConnector("mem", "executions", "application/x-ndjson", payloads);
    return current;
  }

  @Override
  public void armFailureOnNextRead() {
    current.armFailureOnNextRead();
  }

  private static final ReadLimits LIMITS = new ReadLimits(10, 1_000L);

  private static List<Batch> readAll(InMemorySourceConnector source) {
    try (BatchStream stream = source.read(new ReadRequest(Optional.empty(), LIMITS))) {
      return stream.next(10);
    }
  }

  @Test
  void recordCountIsTheNonBlankLinesForNdjsonAndOneForOtherMediaTypes() {
    List<byte[]> payloads = List.of("a\n\nb\n".getBytes(UTF_8), "  \n\n".getBytes(UTF_8));
    Batch ndjson =
        readAll(new InMemorySourceConnector("mem", "executions", "application/x-ndjson", payloads))
            .get(0);
    assertThat(ndjson.payload().manifest().recordCount()).isEqualTo(2);
    List<Batch> blank =
        readAll(new InMemorySourceConnector("mem", "executions", "application/x-ndjson", payloads));
    assertThat(blank.get(1).payload().manifest().recordCount()).isZero();
    Batch plain =
        readAll(new InMemorySourceConnector("mem", "executions", "text/plain", payloads)).get(0);
    assertThat(plain.payload().manifest().recordCount()).isEqualTo(1);
    assertThat(plain.payload().mediaType()).isEqualTo("text/plain");
    assertThat(plain.payload().bytes()).isEqualTo("a\n\nb\n".getBytes(UTF_8));
    assertThat(plain.payload().manifest().sha256()).isEqualTo(Sha256.of(plain.payload().bytes()));
  }

  @Test
  void coordinatesAndCheckpointsEncodeStreamAndPosition() {
    InMemorySourceConnector source = (InMemorySourceConnector) connector(3);
    List<Batch> batches = readAll(source);
    assertThat(batches).extracting(Batch::seq).containsExactly(1L, 2L, 3L);
    assertThat(batches)
        .extracting(Batch::sourceCoordinates)
        .containsExactly("mem:executions:1", "mem:executions:2", "mem:executions:3");
    for (int i = 1; i <= 3; i++) {
      assertThat(batches.get(i - 1).stream()).isEqualTo("executions");
      assertThat(batches.get(i - 1).checkpoint())
          .isEqualTo(new Checkpoint("executions", ByteBuffer.allocate(8).putLong(i).array()));
    }
  }

  @Test
  void aCheckpointOfAnotherStreamIsRejectedOnReadAndOnAcknowledge() {
    InMemorySourceConnector source = (InMemorySourceConnector) connector(2);
    Checkpoint foreign = new Checkpoint("quotes", ByteBuffer.allocate(8).putLong(1).array());
    assertThatThrownBy(() -> source.read(new ReadRequest(Optional.of(foreign), LIMITS)))
        .isInstanceOf(IllegalArgumentException.class);
    try (BatchStream stream = source.read(new ReadRequest(Optional.empty(), LIMITS))) {
      stream.next(2);
      assertThatThrownBy(() -> stream.acknowledge(foreign))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(source.acknowledgedSeq()).isZero();
  }

  @Test
  void aConnectorWithoutBatchesIsExhaustedAtOnce() {
    InMemorySourceConnector source = (InMemorySourceConnector) connector(0);
    try (BatchStream stream = source.read(new ReadRequest(Optional.empty(), LIMITS))) {
      assertThat(stream.exhausted()).isTrue();
      assertThat(stream.next(5)).isEmpty();
    }
  }

  @Test
  void acknowledgedSeqTracksTheGreatestAcknowledgedPosition() {
    InMemorySourceConnector source = (InMemorySourceConnector) connector(3);
    assertThat(source.acknowledgedSeq()).isZero();
    try (BatchStream stream =
        source.read(new ReadRequest(Optional.empty(), new ReadLimits(10, 1_000L)))) {
      List<Batch> batches = stream.next(3);
      stream.acknowledge(batches.get(1).checkpoint());
      assertThat(source.acknowledgedSeq()).isEqualTo(2);
      stream.acknowledge(batches.get(0).checkpoint());
      assertThat(source.acknowledgedSeq()).isEqualTo(2);
    }
  }
}
