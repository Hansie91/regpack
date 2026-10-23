package dev.regpack.evidence.sealed;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T6: the real file operations behind the seam, on the file system of the machine that runs the
 * test (NTFS on the Windows development machine and on windows-2025, ext4 or similar on Linux).
 */
class FileOpsTest {
  private final FileOps ops = FileOps.real();

  @TempDir Path root;

  private static byte[] bytes(String text) {
    return text.getBytes(UTF_8);
  }

  @Test
  void createDirectoriesMakesTheWholeChainAndRepeats() throws IOException {
    Path deep = root.resolve("a/b/c");

    ops.createDirectories(deep);
    ops.createDirectories(deep);

    assertThat(deep).isDirectory();
  }

  @Test
  void writeAndForceCreatesTheFileAndNeverReplacesOne() throws IOException {
    Path file = root.resolve("f");

    ops.writeAndForce(file, bytes("first"));

    assertThat(file).hasBinaryContent(bytes("first"));
    assertThatThrownBy(() -> ops.writeAndForce(file, bytes("second")))
        .isInstanceOf(FileAlreadyExistsException.class);
    assertThat(file).hasBinaryContent(bytes("first"));
    ops.writeAndForce(root.resolve("empty"), new byte[0]);
    assertThat(root.resolve("empty")).hasSize(0);
  }

  @Test
  void writeAndForceWritesALargeFileInFull() throws IOException {
    byte[] big = new byte[3 * 1024 * 1024 + 17];
    for (int i = 0; i < big.length; i++) {
      big[i] = (byte) (i * 31 + (i >>> 8));
    }

    ops.writeAndForce(root.resolve("big"), big);

    assertThat(root.resolve("big")).hasBinaryContent(big);
  }

  @Test
  void linkGivesASecondNameAndNeverReplacesOne() throws IOException {
    Path source = root.resolve("source");
    Path name = root.resolve("name");
    ops.writeAndForce(source, bytes("data"));

    ops.link(source, name);

    // a link, not a copy and not a move: the source stays, and the two names are one file
    assertThat(source).exists();
    assertThat(Files.isSameFile(source, name)).isTrue();
    if (HostPlatform.current().isWindows()) {
      assertThat(name.getFileSystem().supportedFileAttributeViews())
          .as("no link count to read on Windows")
          .doesNotContain("unix");
    } else {
      assertThat(Files.getAttribute(name, "unix:nlink")).isEqualTo(2);
    }
    assertThat(name).hasBinaryContent(bytes("data"));
    ops.delete(source);
    assertThat(name).as("the data lives on under the other name").hasBinaryContent(bytes("data"));

    Path other = root.resolve("other");
    ops.writeAndForce(other, bytes("other"));
    assertThatThrownBy(() -> ops.link(other, name)).isInstanceOf(FileAlreadyExistsException.class);
    assertThat(name).hasBinaryContent(bytes("data"));
  }

  @Test
  void replaceOverwritesAnExistingTarget() throws IOException {
    Path target = root.resolve("target");
    Path source = root.resolve("source");
    ops.writeAndForce(target, bytes("old"));
    ops.writeAndForce(source, bytes("new"));

    ops.replace(source, target);

    assertThat(target).hasBinaryContent(bytes("new"));
    assertThat(source).doesNotExist();
    // and a target that is not there yet
    Path fresh = root.resolve("fresh");
    ops.writeAndForce(root.resolve("again"), bytes("again"));
    ops.replace(root.resolve("again"), fresh);
    assertThat(fresh).hasBinaryContent(bytes("again"));
  }

  @Test
  void readOfAnAbsentFileIsNoSuchFileAndDeleteOfOneIsQuiet() throws IOException {
    Path file = root.resolve("absent");

    assertThatThrownBy(() -> ops.read(file)).isInstanceOf(NoSuchFileException.class);
    ops.delete(file);
    ops.writeAndForce(file, bytes("x"));
    assertThat(ops.read(file)).isEqualTo(bytes("x"));
    ops.delete(file);
    assertThat(file).doesNotExist();
  }

  @Test
  void listGivesTheEntriesOfADirectoryAndNothingForAnAbsentOneOrAFile() throws IOException {
    ops.createDirectories(root.resolve("dir/sub"));
    ops.writeAndForce(root.resolve("dir/f"), bytes("x"));

    List<Path> entries = ops.list(root.resolve("dir"));

    assertThat(entries).containsExactlyInAnyOrder(root.resolve("dir/sub"), root.resolve("dir/f"));
    assertThat(ops.list(root.resolve("absent"))).isEmpty();
    assertThat(ops.list(root.resolve("dir/f"))).isEmpty();
    assertThat(ops.list(root.resolve("dir/sub"))).isEmpty();
  }

  @Test
  void aDirectoryThatExistsCanBeSyncedWithoutAnError() throws IOException {
    ops.createDirectories(root.resolve("dir"));

    ops.syncDirectory(root.resolve("dir")); // never throws for a directory that exists
    // that supportsDirectorySync() agrees with the platform is HostPlatformTest's cross-check
  }

  @Test
  void aSyncOfADirectoryThatIsNotThereFailsWhereTheSyncIsReal() throws IOException {
    Assumptions.assumeFalse(HostPlatform.current().isWindows(), "a platform with directory sync");

    assertThatThrownBy(() -> ops.syncDirectory(root.resolve("absent")))
        .isInstanceOf(IOException.class);
  }

  /** Records what the channel is when a force is asked for. */
  private static final class RecordingForcer implements FileOps.Forcer {
    final List<Boolean> open = new ArrayList<>();
    final List<Long> sizes = new ArrayList<>();
    final List<FileChannel> channels = new ArrayList<>();

    @Override
    public void force(FileChannel channel) throws IOException {
      open.add(channel.isOpen());
      sizes.add(channel.size());
      channels.add(channel);
    }
  }

  @Test
  void writeAndForceForcesOnceWhileTheChannelIsOpenAndAfterEveryByteIsWritten() throws IOException {
    RecordingForcer forcer = new RecordingForcer();
    FileOps recording = new FileOps.Real(forcer);
    byte[] data = bytes("some data that has to reach the device");

    recording.writeAndForce(root.resolve("f"), data);

    assertThat(forcer.open).as("forced once, on an open channel").containsExactly(true);
    assertThat(forcer.sizes)
        .as("all bytes written when forced")
        .containsExactly((long) data.length);
    assertThat(forcer.channels.get(0).isOpen()).as("closed only after the force").isFalse();
    assertThat(root.resolve("f")).hasBinaryContent(data);

    // an empty file is forced too; a large one is written in full before the force
    recording.writeAndForce(root.resolve("empty"), new byte[0]);
    byte[] big = new byte[3 * 1024 * 1024 + 17];
    recording.writeAndForce(root.resolve("big"), big);
    assertThat(forcer.sizes).containsExactly((long) data.length, 0L, (long) big.length);
  }

  @Test
  void aFailedForceFailsTheWriteAndARefusedWriteForcesNothing() throws IOException {
    FileOps failing =
        new FileOps.Real(
            channel -> {
              throw new IOException("injected force failure");
            });
    assertThatThrownBy(() -> failing.writeAndForce(root.resolve("f"), bytes("x")))
        .isInstanceOf(IOException.class)
        .hasMessage("injected force failure");

    RecordingForcer forcer = new RecordingForcer();
    FileOps recording = new FileOps.Real(forcer);
    Files.write(root.resolve("taken"), bytes("x"));
    assertThatThrownBy(() -> recording.writeAndForce(root.resolve("taken"), bytes("y")))
        .isInstanceOf(FileAlreadyExistsException.class);
    assertThat(forcer.open).isEmpty();
  }

  @Test
  void onPosixADirectoryIsForcedThroughAChannelOpenOnIt() throws IOException {
    Assumptions.assumeFalse(HostPlatform.current().isWindows(), "a platform with directory sync");
    RecordingForcer forcer = new RecordingForcer();
    FileOps recording = new FileOps.Real(forcer);
    ops.createDirectories(root.resolve("dir"));

    recording.syncDirectory(root.resolve("dir"));

    assertThat(recording.supportsDirectorySync()).isTrue();
    assertThat(forcer.open).containsExactly(true);
    assertThat(forcer.channels.get(0).isOpen()).as("closed after the force").isFalse();
    // a directory that is not there is not forced, and the call says so
    assertThatThrownBy(() -> recording.syncDirectory(root.resolve("absent")))
        .isInstanceOf(IOException.class);
    assertThat(forcer.open).hasSize(1);
  }

  @Test
  void onWindowsNoDirectoryIsForcedAndTheSeamSaysSo() throws IOException {
    Assumptions.assumeTrue(HostPlatform.current().isWindows(), "a platform without it");
    RecordingForcer forcer = new RecordingForcer();
    FileOps recording = new FileOps.Real(forcer);
    ops.createDirectories(root.resolve("dir"));

    recording.syncDirectory(root.resolve("dir"));
    recording.syncDirectory(root.resolve("absent")); // nothing is opened, so nothing can fail

    assertThat(recording.supportsDirectorySync()).isFalse();
    assertThat(forcer.open).as("no directory is forced").isEmpty();
  }

  @Test
  void createdFilesAndDirectoriesAreOwnerOnlyOnPosix() throws IOException {
    Assumptions.assumeFalse(HostPlatform.current().isWindows(), "the POSIX permissions are needed");

    ops.createDirectories(root.resolve("a/b"));
    ops.writeAndForce(root.resolve("a/b/f"), bytes("x"));

    assertThat(Files.getPosixFilePermissions(root.resolve("a")))
        .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    assertThat(Files.getPosixFilePermissions(root.resolve("a/b")))
        .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    assertThat(Files.getPosixFilePermissions(root.resolve("a/b/f")))
        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
  }

  @Test
  void onWindowsCreatedFilesCarryTheAclTheyInheritAndHaveNoPosixPermissions() throws IOException {
    Assumptions.assumeTrue(HostPlatform.current().isWindows(), "the twin of the POSIX test");

    ops.createDirectories(root.resolve("a/b"));
    ops.writeAndForce(root.resolve("a/b/f"), bytes("x"));

    assertThat(root.getFileSystem().supportedFileAttributeViews())
        .doesNotContain("posix")
        .contains("acl");
    assertThat(Files.getFileAttributeView(root.resolve("a/b/f"), AclFileAttributeView.class))
        .as("the access control list that the file inherits (see limits() of the store)")
        .isNotNull();
  }

  @Test
  void aWriteThatIsRefusedLeavesNothingBehind() throws IOException {
    // the parent directory is missing: the call fails and creates nothing
    assertThatThrownBy(() -> ops.writeAndForce(root.resolve("missing/f"), bytes("x")))
        .isInstanceOf(IOException.class);
    try (Stream<Path> walk = Files.walk(root)) {
      assertThat(walk.filter(p -> !p.equals(root)).toList()).isEmpty();
    }
  }
}
