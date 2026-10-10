package dev.regpack.ports.connector;

import dev.regpack.model.Checkpoint;
import dev.regpack.model.Sha256;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A connector that reads records from a source system as ordered batches with credit-based
 * backpressure and explicit acknowledgement. Delivery is at-least-once: unacknowledged batches are
 * redelivered by a later {@link #read}.
 */
public non-sealed interface SourceConnector extends Connector {
  record StreamInfo(String name, List<String> stableKeyFields) {
    public StreamInfo {
      Objects.requireNonNull(name, "name");
      stableKeyFields = List.copyOf(stableKeyFields);
    }
  }

  record Discovery(List<StreamInfo> streams) {
    public Discovery {
      streams = List.copyOf(streams);
    }
  }

  record ReadLimits(int maxRecordsPerBatch, long maxBytesPerBatch) {
    public ReadLimits {
      if (maxRecordsPerBatch < 1 || maxBytesPerBatch < 1) {
        throw new IllegalArgumentException("ReadLimits must be positive");
      }
    }
  }

  record ReadRequest(Optional<Checkpoint> resumeFrom, ReadLimits limits) {
    public ReadRequest {
      Objects.requireNonNull(resumeFrom, "resumeFrom");
      Objects.requireNonNull(limits, "limits");
    }
  }

  record Manifest(int recordCount, Sha256 sha256, Map<String, String> controlTotals) {
    public Manifest {
      if (recordCount < 0) {
        throw new IllegalArgumentException("recordCount must not be negative");
      }
      Objects.requireNonNull(sha256, "sha256");
      controlTotals = Map.copyOf(controlTotals);
    }
  }

  /** Payload bytes with their media type and manifest; {@code manifest.sha256} covers the bytes. */
  record BatchPayload(byte[] bytes, String mediaType, Manifest manifest) {
    public BatchPayload {
      bytes = bytes.clone();
      Objects.requireNonNull(mediaType, "mediaType");
      Objects.requireNonNull(manifest, "manifest");
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof BatchPayload p
          && java.util.Arrays.equals(bytes, p.bytes)
          && mediaType.equals(p.mediaType)
          && manifest.equals(p.manifest);
    }

    @Override
    public int hashCode() {
      return 31 * (31 * java.util.Arrays.hashCode(bytes) + mediaType.hashCode())
          + manifest.hashCode();
    }

    @Override
    public String toString() {
      return "BatchPayload[length=" + bytes.length + ", mediaType=" + mediaType + "]";
    }
  }

  record Batch(
      String stream,
      long seq,
      BatchPayload payload,
      String sourceCoordinates,
      Checkpoint checkpoint) {
    public Batch {
      Objects.requireNonNull(stream, "stream");
      if (seq < 1) {
        throw new IllegalArgumentException("Batch seq starts at 1");
      }
      Objects.requireNonNull(payload, "payload");
      Objects.requireNonNull(sourceCoordinates, "sourceCoordinates");
      Objects.requireNonNull(checkpoint, "checkpoint");
    }
  }

  interface BatchStream extends AutoCloseable {
    /** At most {@code credits} batches, in strictly increasing seq; zero credits gives none. */
    List<Batch> next(int credits);

    /** Every batch up to and including {@code checkpoint} is durable in the ledger (I1). */
    void acknowledge(Checkpoint checkpoint);

    boolean exhausted();

    @Override
    void close();
  }

  Discovery discover();

  BatchStream read(ReadRequest request);
}
