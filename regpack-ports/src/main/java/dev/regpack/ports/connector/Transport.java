package dev.regpack.ports.connector;

import dev.regpack.model.Sha256;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The destination kind of the connector SPI. {@link #send} calls the {@link CommitGate} exactly
 * once, immediately before the first destination-visible effect; a refused or throwing gate leaves
 * nothing visible. A second send of an existing file id is a no-clobber {@link
 * SendStatus#REJECTED_DUPLICATE}.
 */
public non-sealed interface Transport extends Connector {
  enum AckModel {
    SYNC_RESPONSE,
    ASYNC_FILE,
    ASYNC_MESSAGE,
    PORTAL_ONLY
  }

  /** A sealed file to deliver; {@code sha256} is the digest of {@code bytes}. */
  record OutboundFile(
      String fileId, String fileName, byte[] bytes, Sha256 sha256, String mediaType) {
    public OutboundFile {
      requireNotBlank(fileId, "fileId");
      Objects.requireNonNull(fileName, "fileName");
      bytes = bytes.clone();
      Objects.requireNonNull(sha256, "sha256");
      Objects.requireNonNull(mediaType, "mediaType");
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof OutboundFile f
          && fileId.equals(f.fileId)
          && fileName.equals(f.fileName)
          && Arrays.equals(bytes, f.bytes)
          && sha256.equals(f.sha256)
          && mediaType.equals(f.mediaType);
    }

    @Override
    public int hashCode() {
      return Objects.hash(fileId, fileName, Arrays.hashCode(bytes), sha256, mediaType);
    }

    @Override
    public String toString() {
      return "OutboundFile[fileId="
          + fileId
          + ", fileName="
          + fileName
          + ", length="
          + bytes.length
          + ", sha256="
          + sha256.hex()
          + ", mediaType="
          + mediaType
          + "]";
    }
  }

  /**
   * Asked immediately before the first destination-visible byte. {@code true} lets the send
   * proceed; {@code false} aborts it; an exception propagates and nothing becomes visible.
   */
  @FunctionalInterface
  interface CommitGate {
    boolean permitFirstVisibleByte();
  }

  enum SendStatus {
    DELIVERED,
    REJECTED_DUPLICATE,
    ABORTED_BY_GATE,
    FAILED_BEFORE_FIRST_BYTE,
    UNKNOWN_OUTCOME
  }

  record SendResult(SendStatus status, String detail) {
    public SendResult {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(detail, "detail");
    }
  }

  enum ProbeStatus {
    PRESENT,
    ABSENT,
    UNKNOWABLE
  }

  record ProbeResult(ProbeStatus status, Optional<Sha256> sha256) {
    public ProbeResult {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(sha256, "sha256");
    }
  }

  record AckCursor(long position) {
    public AckCursor {
      if (position < 0) {
        throw new IllegalArgumentException("AckCursor position must not be negative");
      }
    }

    public static AckCursor start() {
      return new AckCursor(0L);
    }
  }

  /** One acknowledgement as the endpoint produced it, with the endpoint's own as-of. */
  record RawAck(
      long position, String fileId, byte[] bytes, String mediaType, Instant endpointAsOf) {
    public RawAck {
      if (position < 1) {
        throw new IllegalArgumentException("RawAck position starts at 1");
      }
      Objects.requireNonNull(fileId, "fileId");
      bytes = bytes.clone();
      Objects.requireNonNull(mediaType, "mediaType");
      Objects.requireNonNull(endpointAsOf, "endpointAsOf");
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof RawAck a
          && position == a.position
          && fileId.equals(a.fileId)
          && Arrays.equals(bytes, a.bytes)
          && mediaType.equals(a.mediaType)
          && endpointAsOf.equals(a.endpointAsOf);
    }

    @Override
    public int hashCode() {
      return Objects.hash(position, fileId, Arrays.hashCode(bytes), mediaType, endpointAsOf);
    }

    @Override
    public String toString() {
      return "RawAck[position="
          + position
          + ", fileId="
          + fileId
          + ", length="
          + bytes.length
          + ", mediaType="
          + mediaType
          + ", endpointAsOf="
          + endpointAsOf
          + "]";
    }
  }

  record AckPage(List<RawAck> acks, AckCursor next) {
    public AckPage {
      acks = List.copyOf(acks);
      Objects.requireNonNull(next, "next");
    }
  }

  AckModel ackModel();

  SendResult send(OutboundFile file, CommitGate gate);

  ProbeResult probe(String fileId);

  AckPage pollAcks(AckCursor cursor, int max);

  private static void requireNotBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
