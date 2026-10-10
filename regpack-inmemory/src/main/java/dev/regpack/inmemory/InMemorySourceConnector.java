package dev.regpack.inmemory;

import dev.regpack.model.Checkpoint;
import dev.regpack.model.Sha256;
import dev.regpack.ports.connector.CheckResult;
import dev.regpack.ports.connector.ConnectorKind;
import dev.regpack.ports.connector.ConnectorSpec;
import dev.regpack.ports.connector.SourceConnector;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory {@link SourceConnector} over a fixed list of payloads, one batch per payload. Batch
 * {@code i} (1-based) has seq {@code i}, coordinates {@code mem:<stream>:<i>} and checkpoint
 * (stream, 8-byte big-endian {@code i}). Thread-safe.
 */
public final class InMemorySourceConnector implements SourceConnector {
  private static final String NDJSON = "application/x-ndjson";

  private final Object lock = new Object();
  private final String name;
  private final String stream;
  private final String mediaType;
  private final List<byte[]> payloads;
  private long acknowledgedSeq;
  private boolean failureArmed;

  public InMemorySourceConnector(
      String name, String stream, String mediaType, List<byte[]> payloads) {
    this.name = Objects.requireNonNull(name, "name");
    this.stream = Objects.requireNonNull(stream, "stream");
    this.mediaType = Objects.requireNonNull(mediaType, "mediaType");
    List<byte[]> copy = new ArrayList<>();
    for (byte[] payload : payloads) {
      copy.add(payload.clone());
    }
    this.payloads = List.copyOf(copy);
  }

  /** The next {@code BatchStream.next(n)} throws a {@link RuntimeException}, once. */
  public void armFailureOnNextRead() {
    synchronized (lock) {
      failureArmed = true;
    }
  }

  /**
   * Greatest acknowledged position across all streams; 0 before any acknowledgement. Test and
   * diagnostics accessor, not part of the {@link SourceConnector} port.
   */
  public long acknowledgedSeq() {
    synchronized (lock) {
      return acknowledgedSeq;
    }
  }

  @Override
  public ConnectorSpec spec() {
    return new ConnectorSpec(
        name, "1", ConnectorKind.SOURCE, Map.of("stream", stream, "mediaType", mediaType));
  }

  @Override
  public CheckResult check() {
    return new CheckResult(true, "in-memory source with " + payloads.size() + " batches");
  }

  @Override
  public Discovery discover() {
    return new Discovery(List.of(new StreamInfo(stream, List.of("sourceRecordId"))));
  }

  @Override
  public BatchStream read(ReadRequest request) {
    long start;
    synchronized (lock) {
      start = acknowledgedSeq;
    }
    if (request.resumeFrom().isPresent()) {
      start = Math.max(start, position(request.resumeFrom().get()));
    }
    return new MemoryStream(start);
  }

  private long position(Checkpoint checkpoint) {
    byte[] state = checkpoint.state();
    if (!stream.equals(checkpoint.stream()) || state.length != Long.BYTES) {
      throw new IllegalArgumentException("Checkpoint does not belong to stream " + stream);
    }
    long position = ByteBuffer.wrap(state).getLong();
    if (position < 0 || position > payloads.size()) {
      throw new IllegalArgumentException("Checkpoint position out of range: " + position);
    }
    return position;
  }

  private Batch batch(int seq) {
    byte[] bytes = payloads.get(seq - 1);
    int records = NDJSON.equals(mediaType) ? nonBlankLines(bytes) : 1;
    Manifest manifest = new Manifest(records, Sha256.of(bytes), Map.of());
    Checkpoint checkpoint =
        new Checkpoint(stream, ByteBuffer.allocate(Long.BYTES).putLong(seq).array());
    return new Batch(
        stream,
        seq,
        new BatchPayload(bytes, mediaType, manifest),
        "mem:" + stream + ":" + seq,
        checkpoint);
  }

  private static int nonBlankLines(byte[] bytes) {
    int count = 0;
    for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n", -1)) {
      if (!line.isBlank()) {
        count++;
      }
    }
    return count;
  }

  private final class MemoryStream implements BatchStream {
    private long cursor;
    private boolean closed;

    MemoryStream(long start) {
      this.cursor = start;
    }

    @Override
    public List<Batch> next(int credits) {
      synchronized (lock) {
        if (closed) {
          throw new IllegalStateException("Stream is closed");
        }
        if (credits < 0) {
          throw new IllegalArgumentException("credits must not be negative: " + credits);
        }
        if (failureArmed) {
          failureArmed = false;
          throw new UncheckedIOException(new IOException("injected source read failure"));
        }
        List<Batch> out = new ArrayList<>();
        while (out.size() < credits && cursor < payloads.size()) {
          cursor++;
          out.add(batch((int) cursor));
        }
        return List.copyOf(out);
      }
    }

    @Override
    public void acknowledge(Checkpoint checkpoint) {
      synchronized (lock) {
        long position = position(checkpoint);
        if (position > cursor) {
          throw new IllegalArgumentException(
              "Cannot acknowledge position " + position + " beyond delivered " + cursor);
        }
        acknowledgedSeq = Math.max(acknowledgedSeq, position);
      }
    }

    @Override
    public boolean exhausted() {
      synchronized (lock) {
        return cursor >= payloads.size();
      }
    }

    @Override
    public void close() {
      synchronized (lock) {
        closed = true;
      }
    }
  }
}
