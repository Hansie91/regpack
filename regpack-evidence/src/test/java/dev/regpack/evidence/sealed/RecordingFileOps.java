package dev.regpack.evidence.sealed;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test helper (T6): records every call in call order, before it is passed on, so a call that the
 * delegate refuses is recorded too. {@link #claimingDirectorySync} reports a platform that can sync
 * directories, so that the sync rules of {@link SealedVolume} are tested on every operating system
 * (on Windows the real {@code syncDirectory} is a no-op).
 */
final class RecordingFileOps implements FileOps {
  /** The operations of the seam. */
  enum Op {
    CREATE_DIRECTORIES,
    WRITE_AND_FORCE,
    LINK,
    REPLACE,
    SYNC_DIRECTORY,
    READ,
    DELETE,
    LIST
  }

  /**
   * One call: the operation, its path (the new name of a link, the target of a replace) and, for
   * those two, the source.
   */
  record Call(Op op, Path path, Path source) {}

  private final FileOps delegate;
  private final boolean claimDirectorySync;
  private final List<Call> calls = new CopyOnWriteArrayList<>();

  private RecordingFileOps(FileOps delegate, boolean claimDirectorySync) {
    this.delegate = delegate;
    this.claimDirectorySync = claimDirectorySync;
  }

  /** Records and reports what the delegate reports. */
  static RecordingFileOps over(FileOps delegate) {
    return new RecordingFileOps(delegate, false);
  }

  /** Records and reports that directories can be synced, whatever the delegate says. */
  static RecordingFileOps claimingDirectorySync(FileOps delegate) {
    return new RecordingFileOps(delegate, true);
  }

  List<Call> calls() {
    return List.copyOf(calls);
  }

  List<Op> ops() {
    return calls.stream().map(Call::op).toList();
  }

  /** The paths of the calls of one operation, in call order. */
  List<Path> paths(Op op) {
    return calls.stream().filter(c -> c.op() == op).map(Call::path).toList();
  }

  void clear() {
    calls.clear();
  }

  private void record(Op op, Path path) {
    calls.add(new Call(op, path, path));
  }

  @Override
  public void createDirectories(Path dir) throws IOException {
    record(Op.CREATE_DIRECTORIES, dir);
    delegate.createDirectories(dir);
  }

  @Override
  public void writeAndForce(Path file, byte[] bytes) throws IOException {
    record(Op.WRITE_AND_FORCE, file);
    delegate.writeAndForce(file, bytes);
  }

  @Override
  public void link(Path existing, Path newName) throws IOException {
    calls.add(new Call(Op.LINK, newName, existing));
    delegate.link(existing, newName);
  }

  @Override
  public void replace(Path source, Path target) throws IOException {
    calls.add(new Call(Op.REPLACE, target, source));
    delegate.replace(source, target);
  }

  @Override
  public void syncDirectory(Path dir) throws IOException {
    record(Op.SYNC_DIRECTORY, dir);
    delegate.syncDirectory(dir);
  }

  @Override
  public boolean supportsDirectorySync() {
    return claimDirectorySync || delegate.supportsDirectorySync();
  }

  @Override
  public byte[] read(Path file) throws IOException {
    record(Op.READ, file);
    return delegate.read(file);
  }

  @Override
  public void delete(Path file) throws IOException {
    record(Op.DELETE, file);
    delegate.delete(file);
  }

  @Override
  public List<Path> list(Path dir) throws IOException {
    record(Op.LIST, dir);
    return delegate.list(dir);
  }
}
