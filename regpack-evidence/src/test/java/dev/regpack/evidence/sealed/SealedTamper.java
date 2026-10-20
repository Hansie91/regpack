package dev.regpack.evidence.sealed;

import dev.regpack.model.Sha256;
import dev.regpack.ports.evidence.EvidenceKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test helper (T8; T15 uses it too): damages one volume of a P-SEALED store on disk the way a
 * failing disk, a careless operator or an attacker with storage access would, so that the tests of
 * the store and of {@code verify} can seed a fault of a known kind. It is built on {@link
 * SealedNames} and {@link SealedFormat} only, so it also works on a volume that no store has open,
 * and it never goes through the store's own checks.
 *
 * <p>All methods take the root of a volume (the directory that holds {@code .sealed-volume}).
 */
public final class SealedTamper {
  private SealedTamper() {}

  /** The path of the object file of {@code key} on the volume (it need not exist). */
  public static Path objectPath(Path volumeRoot, EvidenceKey key) {
    String[] segments = key.path().split("/", -1);
    Path directory = volumeRoot.resolve("o");
    for (int i = 0; i < segments.length - 1; i++) {
      directory = directory.resolve(SealedNames.directory(segments[i]));
    }
    return directory.resolve(SealedNames.object(segments[segments.length - 1]));
  }

  /** The path of the meta file of {@code key} on the volume (it need not exist). */
  public static Path metaPath(Path volumeRoot, EvidenceKey key) {
    String path = key.path();
    return objectPath(volumeRoot, key)
        .resolveSibling(SealedNames.meta(path.substring(path.lastIndexOf('/') + 1)));
  }

  /**
   * Flips the lowest bit of the last byte of the object file: a byte of its SHA-256 trailer, for
   * any payload (also an empty one). A flip of another byte is made on a copy of the bytes, with
   * {@link #replaceObject}.
   */
  public static void flipByte(Path volumeRoot, EvidenceKey key) throws IOException {
    Path file = objectPath(volumeRoot, key);
    byte[] bytes = Files.readAllBytes(file);
    bytes[bytes.length - 1] ^= 0x01;
    Files.write(file, bytes);
  }

  /** Removes the object file of {@code key} (its meta file stays). */
  public static void deleteObject(Path volumeRoot, EvidenceKey key) throws IOException {
    Files.delete(objectPath(volumeRoot, key));
  }

  /**
   * Writes {@code objectFile} as the object file of {@code key}, over the file that is there or
   * where there is none (the directories are created). The bytes are written as given.
   */
  public static void replaceObject(Path volumeRoot, EvidenceKey key, byte[] objectFile)
      throws IOException {
    Path file = objectPath(volumeRoot, key);
    Files.createDirectories(file.getParent());
    Files.write(file, objectFile);
  }

  /**
   * An object file that is whole and passes its own seal but carries {@code payload} instead of the
   * stored bytes: the header of the copy on the volume with the length and hash of the new payload.
   * Put in place of one copy of a key it gives two self-consistent copies that differ.
   */
  public static byte[] fileWithOtherPayload(Path volumeRoot, EvidenceKey key, byte[] payload)
      throws IOException {
    SealedFormat.Header stored =
        SealedFormat.decodeObject(Files.readAllBytes(objectPath(volumeRoot, key)), key.path())
            .header();
    return SealedFormat.encodeObject(
        new SealedFormat.Header(
            stored.key(),
            stored.mediaType(),
            Sha256.of(payload),
            payload.length,
            stored.writtenAt(),
            stored.retentionClass(),
            stored.retainUntil()),
        payload);
  }
}
