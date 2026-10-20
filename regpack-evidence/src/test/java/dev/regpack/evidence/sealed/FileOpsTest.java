package dev.regpack.evidence.sealed;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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

  private static boolean posix() {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
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
  void aDirectoryCanBeSyncedWhereThePlatformCanAndNowhereElseIsClaimed() throws IOException {
    ops.createDirectories(root.resolve("dir"));

    ops.syncDirectory(root.resolve("dir")); // never throws for a directory that exists

    // where the file system has no POSIX view (Windows) the JDK cannot open a directory: nothing
    // is forced, and the seam says so (the volume then makes no call and the limits of the store
    // say it too)
    assertThat(ops.supportsDirectorySync()).isEqualTo(posix());
  }

  @Test
  void aSyncOfADirectoryThatIsNotThereFailsWhereTheSyncIsReal() throws IOException {
    Assumptions.assumeTrue(ops.supportsDirectorySync(), "a platform with directory sync");

    assertThatThrownBy(() -> ops.syncDirectory(root.resolve("absent")))
        .isInstanceOf(IOException.class);
  }

  @Test
  void createdFilesAndDirectoriesAreOwnerOnlyOnPosix() throws IOException {
    Assumptions.assumeTrue(posix(), "the POSIX attribute view is needed");

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
  void aWriteThatIsRefusedLeavesNothingBehind() throws IOException {
    // the parent directory is missing: the call fails and creates nothing
    assertThatThrownBy(() -> ops.writeAndForce(root.resolve("missing/f"), bytes("x")))
        .isInstanceOf(IOException.class);
    try (Stream<Path> walk = Files.walk(root)) {
      assertThat(walk.filter(p -> !p.equals(root)).toList()).isEmpty();
    }
  }
}
