package dev.regpack.inmemory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.ports.connector.SourceConnector;
import dev.regpack.ports.connector.SourceConnector.Batch;
import dev.regpack.ports.connector.SourceConnector.BatchStream;
import dev.regpack.ports.connector.SourceConnector.ReadLimits;
import dev.regpack.ports.connector.SourceConnector.ReadRequest;
import dev.regpack.testkit.contract.SourceConnectorContract;
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
