package dev.regpack.evidence.sealed;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The file operations of a P-SEALED volume (D-E5), as a package-private seam: the real operations
 * use {@code java.nio}; tests wrap them to record the order of the calls and to make one of them
 * fail. Every method does one thing and reports every failure as an {@link IOException}.
 *
 * <p>What the real operations force, and what they cannot (SP-W is not run, so nothing here is
 * claimed beyond what a test shows):
 *
 * <ul>
 *   <li>{@link #writeAndForce} forces the file's data and metadata to the device before it returns
 *       ({@code FileChannel.force(true)}).
 *   <li>{@link #syncDirectory} forces a directory on POSIX systems (the directory is opened
 *       read-only and forced). The JDK cannot open a directory on Windows, so there the call does
 *       nothing and {@link #supportsDirectorySync} says so; this code does not force a directory
 *       there and no test shows more.
 *   <li>Power loss, network file systems and drives that acknowledge a flush early are not
 *       exercised.
 * </ul>
 */
interface FileOps {
  /** The real file system. */
  static FileOps real() {
    return Real.INSTANCE;
  }

  /** Creates {@code dir} and its missing parents (idempotent); POSIX: they are rwx------. */
  void createDirectories(Path dir) throws IOException;

  /**
   * Creates {@code file}, writes {@code bytes} and forces them to the device before it closes. The
   * file must not exist ({@code CREATE_NEW}: a {@link java.nio.file.FileAlreadyExistsException}
   * otherwise); POSIX: created rw-------.
   */
  void writeAndForce(Path file, byte[] bytes) throws IOException;

  /**
   * Makes {@code newName} a second name for the file {@code existing} (the JDK takes the new name
   * first: {@code Files.createLink(newName, existing)}). A link never replaces: a {@link
   * java.nio.file.FileAlreadyExistsException} when {@code newName} exists.
   */
  void link(Path existing, Path newName) throws IOException;

  /** Renames {@code source} to {@code target}, atomically, replacing a target that exists. */
  void replace(Path source, Path target) throws IOException;

  /** POSIX: forces the directory (opened read-only). Windows: does nothing. */
  void syncDirectory(Path dir) throws IOException;

  /** False where {@link #syncDirectory} cannot force a directory (Windows). */
  boolean supportsDirectorySync();

  /** The whole file; {@link NoSuchFileException} when it is absent. */
  byte[] read(Path file) throws IOException;

  /** Deletes the file if it exists. */
  void delete(Path file) throws IOException;

  /**
   * The entries of a directory, unsorted; empty when the directory is absent or not a directory.
   */
  List<Path> list(Path dir) throws IOException;

  /** The operations on the file system of the machine, through {@code java.nio}. */
  final class Real implements FileOps {
    private static final Real INSTANCE = new Real();
    private static final boolean POSIX =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final FileAttribute<Set<PosixFilePermission>> DIRECTORY_PERMISSIONS =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private static final FileAttribute<Set<PosixFilePermission>> FILE_PERMISSIONS =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    private static final Set<OpenOption> CREATE_NEW_FOR_WRITE =
        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

    private Real() {}

    @Override
    public void createDirectories(Path dir) throws IOException {
      if (POSIX) {
        Files.createDirectories(dir, DIRECTORY_PERMISSIONS);
      } else {
        Files.createDirectories(dir);
      }
    }

    @Override
    public void writeAndForce(Path file, byte[] bytes) throws IOException {
      try (FileChannel channel =
          POSIX
              ? FileChannel.open(file, CREATE_NEW_FOR_WRITE, FILE_PERMISSIONS)
              : FileChannel.open(file, CREATE_NEW_FOR_WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
    }

    @Override
    public void link(Path existing, Path newName) throws IOException {
      Files.createLink(newName, existing);
    }

    @Override
    public void replace(Path source, Path target) throws IOException {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public void syncDirectory(Path dir) throws IOException {
      if (!POSIX) {
        return;
      }
      try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
        channel.force(true);
      }
    }

    @Override
    public boolean supportsDirectorySync() {
      return POSIX;
    }

    @Override
    public byte[] read(Path file) throws IOException {
      return Files.readAllBytes(file);
    }

    @Override
    public void delete(Path file) throws IOException {
      Files.deleteIfExists(file);
    }

    @Override
    public List<Path> list(Path dir) throws IOException {
      try (Stream<Path> entries = Files.list(dir)) {
        return entries.toList();
      } catch (NoSuchFileException | NotDirectoryException e) {
        return List.of();
      } catch (UncheckedIOException e) {
        throw e.getCause();
      }
    }
  }
}
