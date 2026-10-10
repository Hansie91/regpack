package dev.regpack.testkit.sim;

import dev.regpack.model.Sha256;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * One destination behaviour of the {@link DestinationSimulator}. The simulator asks the scenario
 * once for every file whose commit gate answered true and whose file id is new; later scenarios of
 * the spec 14.3 catalogue are added by implementing this interface, not by changing the simulator.
 */
public interface Scenario {
  enum Disposition {
    /** Store the file; the sender sees {@code DELIVERED}. */
    ACCEPT,
    /** The sender sees {@code DELIVERED} but the destination keeps nothing and discards acks. */
    DROP_SILENTLY,
    /** The sender sees {@code REJECTED_DUPLICATE}; earlier files are untouched. */
    REJECT_DUPLICATE
  }

  String name();

  /** Called once per file whose gate answered true and whose file id is new. */
  Disposition onReceive(ReceivedFile file, ScenarioContext context);

  /** A file as the destination received it; {@code receivedAt} is on the endpoint clock. */
  record ReceivedFile(
      String fileId,
      String fileName,
      Sha256 sha256,
      byte[] bytes,
      String mediaType,
      Instant receivedAt) {
    public ReceivedFile {
      Objects.requireNonNull(fileId, "fileId");
      Objects.requireNonNull(fileName, "fileName");
      Objects.requireNonNull(sha256, "sha256");
      bytes = bytes.clone();
      Objects.requireNonNull(mediaType, "mediaType");
      Objects.requireNonNull(receivedAt, "receivedAt");
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof ReceivedFile f
          && fileId.equals(f.fileId)
          && fileName.equals(f.fileName)
          && sha256.equals(f.sha256)
          && Arrays.equals(bytes, f.bytes)
          && mediaType.equals(f.mediaType)
          && receivedAt.equals(f.receivedAt);
    }

    @Override
    public int hashCode() {
      return Objects.hash(fileId, fileName, sha256, Arrays.hashCode(bytes), mediaType, receivedAt);
    }

    @Override
    public String toString() {
      return "ReceivedFile[fileId="
          + fileId
          + ", fileName="
          + fileName
          + ", length="
          + bytes.length
          + ", sha256="
          + sha256.hex()
          + ", receivedAt="
          + receivedAt
          + "]";
    }
  }
}
