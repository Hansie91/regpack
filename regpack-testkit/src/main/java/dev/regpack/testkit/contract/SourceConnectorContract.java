package dev.regpack.testkit.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Checkpoint;
import dev.regpack.model.Sha256;
import dev.regpack.ports.connector.ConnectorKind;
import dev.regpack.ports.connector.SourceConnector;
import dev.regpack.ports.connector.SourceConnector.Batch;
import dev.regpack.ports.connector.SourceConnector.BatchStream;
import dev.regpack.ports.connector.SourceConnector.ReadLimits;
import dev.regpack.ports.connector.SourceConnector.ReadRequest;
import dev.regpack.ports.connector.SourceConnector.StreamInfo;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Contract every {@link SourceConnector} adapter must satisfy. Each test is a default method. The
 * connector under test is the last one returned by {@link #connector(int)} in the current test.
 */
public interface SourceConnectorContract {
  /**
   * A fresh connector holding {@code batchCount} one-line NDJSON batches with media type {@code
   * application/x-ndjson}.
   */
  SourceConnector connector(int batchCount);

  /**
   * Fault injection: the next {@code next(n)} on the connector under test throws a {@link
   * RuntimeException}, once.
   */
  void armFailureOnNextRead();

  private static ReadRequest fromStart() {
    return new ReadRequest(Optional.empty(), new ReadLimits(100, 1_000_000L));
  }

  private static ReadRequest resumeFrom(Checkpoint checkpoint) {
    return new ReadRequest(Optional.of(checkpoint), new ReadLimits(100, 1_000_000L));
  }

  private static List<Long> seqs(List<Batch> batches) {
    return batches.stream().map(Batch::seq).toList();
  }

  @Test
  default void nextNeverReturnsMoreThanCredits() {
    SourceConnector source = connector(5);
    try (BatchStream stream = source.read(fromStart())) {
      assertThat(stream.next(0)).isEmpty();
      assertThat(stream.exhausted()).isFalse();
      assertThat(stream.next(2)).hasSize(2);
      assertThat(stream.next(10)).hasSize(3);
      assertThat(stream.exhausted()).isTrue();
      assertThat(stream.next(10)).isEmpty();
      assertThatThrownBy(() -> stream.next(-1)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  default void batchSeqIsStrictlyIncreasingFromOne() {
    SourceConnector source = connector(5);
    try (BatchStream stream = source.read(fromStart())) {
      List<Batch> first = stream.next(2);
      List<Batch> second = stream.next(10);
      assertThat(seqs(first)).containsExactly(1L, 2L);
      assertThat(seqs(second)).containsExactly(3L, 4L, 5L);
    }
  }

  @Test
  default void manifestHashMatchesPayloadAndCountsRecords() {
    SourceConnector source = connector(3);
    try (BatchStream stream = source.read(fromStart())) {
      List<Batch> batches = stream.next(10);
      assertThat(batches).hasSize(3);
      for (Batch batch : batches) {
        assertThat(batch.payload().mediaType()).isEqualTo("application/x-ndjson");
        assertThat(batch.payload().manifest().sha256())
            .isEqualTo(Sha256.of(batch.payload().bytes()));
        assertThat(batch.payload().manifest().recordCount()).isEqualTo(1);
      }
    }
  }

  @Test
  default void unacknowledgedBatchesAreRedeliveredOnNewRead() {
    SourceConnector source = connector(5);
    try (BatchStream stream = source.read(fromStart())) {
      List<Batch> all = stream.next(10);
      assertThat(all).hasSize(5);
      stream.acknowledge(all.get(1).checkpoint());
    }
    try (BatchStream again = source.read(fromStart())) {
      assertThat(seqs(again.next(10))).containsExactly(3L, 4L, 5L);
      assertThat(again.exhausted()).isTrue();
    }
  }

  @Test
  default void resumeFromCheckpointSkipsEarlierBatches() {
    SourceConnector source = connector(5);
    Checkpoint third;
    try (BatchStream stream = source.read(fromStart())) {
      third = stream.next(3).get(2).checkpoint();
    }
    try (BatchStream resumed = source.read(resumeFrom(third))) {
      assertThat(seqs(resumed.next(10))).containsExactly(4L, 5L);
    }
  }

  @Test
  default void acknowledgeBeyondDeliveredIsRejected() {
    SourceConnector source = connector(5);
    Checkpoint fourth;
    try (BatchStream first = source.read(fromStart())) {
      fourth = first.next(4).get(3).checkpoint();
    }
    try (BatchStream fresh = source.read(fromStart())) {
      assertThat(fresh.next(2)).hasSize(2);
      assertThatThrownBy(() -> fresh.acknowledge(fourth))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  default void discoverListsTheStreamWithStableKeys() {
    SourceConnector source = connector(2);
    List<StreamInfo> streams = source.discover().streams();
    assertThat(streams).hasSize(1);
    assertThat(streams.get(0).name()).isNotBlank();
    assertThat(streams.get(0).stableKeyFields()).isNotEmpty();
  }

  @Test
  default void checkReportsOkAndSpecDeclaresSourceKind() {
    SourceConnector source = connector(1);
    assertThat(source.check().ok()).isTrue();
    assertThat(source.spec().kind()).isEqualTo(ConnectorKind.SOURCE);
    assertThat(source.spec().name()).isNotBlank();
  }

  @Test
  default void closedStreamRejectsFurtherReads() {
    SourceConnector source = connector(3);
    BatchStream stream = source.read(fromStart());
    stream.close();
    assertThatThrownBy(() -> stream.next(1)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  default void failureMidStreamResumesFromCheckpointWithoutLossOrDuplicate() {
    SourceConnector source = connector(5);
    BatchStream stream = source.read(fromStart());
    List<Batch> firstTwo = stream.next(2);
    assertThat(seqs(firstTwo)).containsExactly(1L, 2L);
    stream.acknowledge(firstTwo.get(1).checkpoint());
    armFailureOnNextRead();
    assertThatThrownBy(() -> stream.next(1)).isInstanceOf(RuntimeException.class);
    stream.close();
    try (BatchStream resumed = source.read(fromStart())) {
      assertThat(seqs(resumed.next(1))).containsExactly(3L);
      assertThat(seqs(resumed.next(10))).containsExactly(4L, 5L);
      assertThat(resumed.exhausted()).isTrue();
    }
  }
}
