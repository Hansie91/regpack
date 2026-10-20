package dev.regpack.evidence.sealed;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tells which failure domain a volume root belongs to, so that {@link SealedEvidenceStore} can say
 * how many independent domains its two volumes span (spec 5.4 durability). Two roots with the same
 * answer are one domain.
 *
 * <p>What the real probe can and cannot show: it identifies the file system a directory lives on,
 * not the disk, the controller or the host behind it. Two directories of one file system are one
 * domain; two file systems that happen to sit on one physical disk (two logical volumes of one
 * drive, two partitions) are counted as two. That the two volumes are independent is attested in
 * the G8 record, never inferred here.
 */
@FunctionalInterface
public interface VolumeProbe {
  /**
   * The identity of the failure domain of the directory {@code root}, which must exist.
   *
   * @throws IOException when the identity cannot be read
   */
  String domainOf(Path root) throws IOException;

  /**
   * The probe of the machine: where the platform has the {@code unix} attribute view, the device
   * number ({@code unix:dev}) and the file-store type; elsewhere (Windows) the file-store name,
   * type and its description, which names the drive. The mount path is not part of the identity
   * where a device number is available, so a bind mount of one file system is one domain.
   */
  static VolumeProbe fileStores() {
    return VolumeProbe::fileStoreIdentity;
  }

  private static String fileStoreIdentity(Path root) throws IOException {
    FileStore store = Files.getFileStore(root);
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("unix")) {
      Object device = Files.getAttribute(root, "unix:dev");
      return "dev=" + device + ";type=" + store.type();
    }
    return "name=" + store.name() + ";type=" + store.type() + ";store=" + store;
  }
}
