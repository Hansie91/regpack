package dev.regpack.evidence.sealed;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Test helper (T7): passes every call to a delegate, except while the outage switch is on, when
 * every operation that can fail throws an {@link IOException} before it does anything. This is how
 * the shared contract suites make "every call fail" (the {@code simulateOutage} of the suites) for
 * a store over real files.
 */
final class FaultableFileOps implements FileOps {
  private final FileOps delegate;
  private volatile boolean down;

  FaultableFileOps(FileOps delegate) {
    this.delegate = delegate;
  }

  /** From now on every operation fails ({@code true}) or works again ({@code false}). */
  void setDown(boolean down) {
    this.down = down;
  }

  private void check(String operation) throws IOException {
    if (down) {
      throw new IOException("injected outage: " + operation);
    }
  }

  @Override
  public void createDirectories(Path dir) throws IOException {
    check("createDirectories");
    delegate.createDirectories(dir);
  }

  @Override
  public void writeAndForce(Path file, byte[] bytes) throws IOException {
    check("writeAndForce");
    delegate.writeAndForce(file, bytes);
  }

  @Override
  public void link(Path existing, Path newName) throws IOException {
    check("link");
    delegate.link(existing, newName);
  }

  @Override
  public void replace(Path source, Path target) throws IOException {
    check("replace");
    delegate.replace(source, target);
  }

  @Override
  public void syncDirectory(Path dir) throws IOException {
    check("syncDirectory");
    delegate.syncDirectory(dir);
  }

  @Override
  public boolean supportsDirectorySync() {
    return delegate.supportsDirectorySync();
  }

  @Override
  public byte[] read(Path file) throws IOException {
    check("read");
    return delegate.read(file);
  }

  @Override
  public void delete(Path file) throws IOException {
    check("delete");
    delegate.delete(file);
  }

  @Override
  public List<Path> list(Path dir) throws IOException {
    check("list");
    return delegate.list(dir);
  }
}
