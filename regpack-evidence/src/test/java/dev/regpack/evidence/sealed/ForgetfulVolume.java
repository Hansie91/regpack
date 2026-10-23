package dev.regpack.evidence.sealed;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Test helper (T14a, the tooth of the volume-loss scenarios): opens a {@link SealedEvidenceStore}
 * whose file operations make one volume forget. The volume acknowledges the publishing of every
 * object file and keeps none of them, as a disk that acknowledges a write it never made. The store
 * above reports a successful put, which is what an acknowledgement before both volumes hold the
 * object looks like from outside. The package-private seam {@link FileOps} is why this class is
 * public in this package: the scenarios of {@code dev.regpack.evidence.seal} cannot reach it.
 */
public final class ForgetfulVolume {
  private ForgetfulVolume() {}

  /**
   * Opens the store of {@code config}; the first volume forgets its object files when {@code
   * firstForgets} is true, otherwise the second one does.
   */
  public static SealedEvidenceStore open(SealedEvidenceStore.Config config, boolean firstForgets) {
    Objects.requireNonNull(config, "config");
    Path forgetful = firstForgets ? config.volumeA() : config.volumeB();
    return SealedEvidenceStore.open(config, new Forgetting(FileOps.real(), forgetful));
  }

  private static final class Forgetting implements FileOps {
    private final FileOps delegate;
    private final Path forgetful;

    Forgetting(FileOps delegate, Path forgetful) {
      this.delegate = delegate;
      this.forgetful = forgetful;
    }

    @Override
    public void createDirectories(Path dir) throws IOException {
      delegate.createDirectories(dir);
    }

    @Override
    public void writeAndForce(Path file, byte[] bytes) throws IOException {
      delegate.writeAndForce(file, bytes);
    }

    @Override
    public void link(Path existing, Path newName) throws IOException {
      if (newName.startsWith(forgetful) && newName.getFileName().toString().startsWith("o-")) {
        return; // the object file is never given its name: the temporary is discarded afterwards
      }
      delegate.link(existing, newName);
    }

    @Override
    public void replace(Path source, Path target) throws IOException {
      delegate.replace(source, target);
    }

    @Override
    public void syncDirectory(Path dir) throws IOException {
      delegate.syncDirectory(dir);
    }

    @Override
    public boolean supportsDirectorySync() {
      return delegate.supportsDirectorySync();
    }

    @Override
    public byte[] read(Path file) throws IOException {
      return delegate.read(file);
    }

    @Override
    public void delete(Path file) throws IOException {
      delegate.delete(file);
    }

    @Override
    public List<Path> list(Path dir) throws IOException {
      return delegate.list(dir);
    }
  }
}
