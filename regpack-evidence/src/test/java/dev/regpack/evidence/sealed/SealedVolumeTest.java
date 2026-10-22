package dev.regpack.evidence.sealed;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.evidence.sealed.RecordingFileOps.Op;
import dev.regpack.model.Sha256;
import dev.regpack.ports.evidence.EvidenceKey;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T6: one P-SEALED volume (D-E5, review focus EF7, EF10, EF11). Every test works in a
 * {@code @TempDir}. The tests of the sync rules use a recorder that claims a platform with
 * directory sync, so that they run on Windows too. They show which calls the volume makes and in
 * which order, and that a failing call propagates; they do not show that a byte reached the device.
 * What the real operations force (the force call itself, through a recording force seam) is shown
 * in {@code FileOpsTest}.
 */
class SealedVolumeTest {
  private static final EvidenceKey KEY = new EvidenceKey("landing/k1");
  private static final byte[] FILE = "the object file, as the store built it".getBytes(UTF_8);

  /** Above every pid_max (Linux 4,194,304) and never a Windows pid (multiples of 4). */
  private static final long DEAD_PID = Integer.MAX_VALUE;

  private static final Instant UNTIL = Instant.parse("2030-01-01T00:00:00Z");

  @TempDir Path root;

  // ---------------------------------------------------------------- helpers

  private static byte[] bytes(String text) {
    return text.getBytes(UTF_8);
  }

  private Path fresh(String name) throws IOException {
    return Files.createDirectory(root.resolve(name));
  }

  /** Every regular file below {@code dir}, relative and with '/', sorted. */
  private static List<String> files(Path dir) throws IOException {
    try (Stream<Path> walk = Files.walk(dir)) {
      return walk.filter(Files::isRegularFile)
          .map(p -> dir.relativize(p).toString().replace('\\', '/'))
          .sorted()
          .toList();
    }
  }

  private static List<String> temporaries(Path dir) throws IOException {
    return files(dir).stream().filter(n -> n.contains("~tmp~")).toList();
  }

  /** The paths relative to {@code base} with '/', "." for the base itself, as a sorted set. */
  private static Set<String> relative(Path base, List<Path> paths) {
    Set<String> out = new TreeSet<>();
    for (Path p : paths) {
      String text = base.relativize(p).toString().replace('\\', '/');
      out.add(text.isEmpty() ? "." : text);
    }
    return out;
  }

  private static <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<T>> futures = new ArrayList<>();
      for (Callable<T> task : tasks) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return task.call();
                }));
      }
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get(60, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  // ---------------------------------------------------------------- publish and read

  @Test
  void publishThenReadReturnsTheSameBytes() throws IOException {
    SealedVolume volume = new SealedVolume(root, "vol-a", FileOps.real());

    assertThat(volume.publish(KEY, FILE)).isTrue();

    assertThat(volume.read(KEY)).hasValueSatisfying(b -> assertThat(b).isEqualTo(FILE));
    assertThat(volume.read(new EvidenceKey("landing/other"))).isEmpty();
    assertThat(volume.read(new EvidenceKey("other/k1"))).isEmpty();
    // the layout: root/o/<d-segments>/o-<last>
    assertThat(files(root)).containsExactly("o/d-landing/o-k1");
    assertThat(root.resolve("o/d-landing/o-k1")).hasBinaryContent(FILE);
  }

  @Test
  void capitalsAndAFinalDotAreEncodedInTheFileNames() throws IOException {
    SealedVolume volume = new SealedVolume(root, "vol-a", FileOps.real());
    EvidenceKey key = new EvidenceKey("Landing/K.1/end.");

    assertThat(volume.publish(key, FILE)).isTrue();

    assertThat(files(root)).containsExactly("o/d-^landing/d-^k.1/o-end^0");
    assertThat(volume.read(key)).isPresent();
    assertThat(volume.read(new EvidenceKey("landing/k.1/end."))).isEmpty();
  }

  @Test
  void aSecondPublishOfTheSameKeyReturnsFalseAndKeepsTheFirst() throws IOException {
    SealedVolume volume = new SealedVolume(root, "vol-a", FileOps.real());
    byte[] second = bytes("another object file for the same key");

    assertThat(volume.publish(KEY, FILE)).isTrue();
    assertThat(volume.publish(KEY, second)).isFalse();

    assertThat(volume.read(KEY)).hasValueSatisfying(b -> assertThat(b).isEqualTo(FILE));
    assertThat(temporaries(root)).isEmpty();
    assertThat(files(root)).containsExactly("o/d-landing/o-k1");
  }

  @Test
  void publishCallsWriteAndForceThenLinkThenSyncDirectoryInThatOrder() throws IOException {
    // what the real platform does: on Windows there is no directory sync to call
    RecordingFileOps real = RecordingFileOps.over(FileOps.real());
    new SealedVolume(fresh("real"), "vol-a", real).publish(KEY, FILE);

    assertThat(real.ops()).containsSubsequence(Op.WRITE_AND_FORCE, Op.LINK);
    if (real.supportsDirectorySync()) {
      assertThat(real.ops()).containsSubsequence(Op.WRITE_AND_FORCE, Op.LINK, Op.SYNC_DIRECTORY);
      assertThat(real.ops().get(real.ops().size() - 1)).isEqualTo(Op.SYNC_DIRECTORY);
    } else {
      assertThat(real.ops()).doesNotContain(Op.SYNC_DIRECTORY);
    }

    // the order of the calls, on every platform: writeAndForce is called for a temporary name, the
    // link
    // gives it its name, and only then is the directory synced
    Path claimedRoot = fresh("claimed");
    RecordingFileOps claimed = RecordingFileOps.claimingDirectorySync(FileOps.real());
    new SealedVolume(claimedRoot, "vol-a", claimed).publish(KEY, FILE);

    assertThat(claimed.ops()).containsSubsequence(Op.WRITE_AND_FORCE, Op.LINK, Op.SYNC_DIRECTORY);
    assertThat(claimed.ops().get(claimed.ops().size() - 1)).isEqualTo(Op.SYNC_DIRECTORY);
    RecordingFileOps.Call write = claimed.calls().get(claimed.ops().indexOf(Op.WRITE_AND_FORCE));
    RecordingFileOps.Call link = claimed.calls().get(claimed.ops().indexOf(Op.LINK));
    assertThat(write.path().getFileName().toString()).contains("~tmp~");
    assertThat(link.source()).isEqualTo(write.path());
    assertThat(link.path()).isEqualTo(claimedRoot.resolve("o/d-landing/o-k1"));
    assertThat(temporaries(claimedRoot)).isEmpty();
  }

  @Test
  void aFailureAtEachStepLeavesNoVisibleObjectUnlessTheLinkHappened() throws Exception {
    // the temporary file cannot be written
    Path writeFails = fresh("write-fails");
    FailingFileOps failing = new FailingFileOps(FileOps.real(), true).failNext(Op.WRITE_AND_FORCE);
    SealedVolume volume = new SealedVolume(writeFails, "vol-a", failing);
    assertThatIOException().isThrownBy(() -> volume.publish(KEY, FILE));
    assertThat(volume.read(KEY)).isEmpty();
    assertThat(files(writeFails)).isEmpty();
    assertThat(volume.publish(KEY, FILE)).as("the retry").isTrue();

    // the write did its work and then reported an error: the temporary file is deleted
    Path writeThenFails = fresh("write-then-fails");
    failing = new FailingFileOps(FileOps.real(), true).failNextAfterEffect(Op.WRITE_AND_FORCE);
    SealedVolume afterWrite = new SealedVolume(writeThenFails, "vol-a", failing);
    assertThatIOException().isThrownBy(() -> afterWrite.publish(KEY, FILE));
    assertThat(afterWrite.read(KEY)).isEmpty();
    assertThat(files(writeThenFails)).isEmpty();

    // the link fails before it acts
    Path linkFails = fresh("link-fails");
    failing = new FailingFileOps(FileOps.real(), true).failNext(Op.LINK);
    SealedVolume noLink = new SealedVolume(linkFails, "vol-a", failing);
    assertThatIOException().isThrownBy(() -> noLink.publish(KEY, FILE));
    assertThat(noLink.read(KEY)).isEmpty();
    assertThat(files(linkFails)).isEmpty();

    // a file system without hard links: an IOException that names the volume, nothing left
    Path unsupported = fresh("link-unsupported");
    failing = new FailingFileOps(FileOps.real(), true).failNextAsUnsupported(Op.LINK);
    SealedVolume unsupportedLink = new SealedVolume(unsupported, "vol-a", failing);
    assertThatIOException()
        .isThrownBy(() -> unsupportedLink.publish(KEY, FILE))
        .withMessageContaining("vol-a");
    assertThat(unsupportedLink.read(KEY)).isEmpty();
    assertThat(files(unsupported)).isEmpty();

    // the link happened and then an error was reported: the object is there, complete, and the
    // temporary name is gone
    Path linkThenFails = fresh("link-then-fails");
    failing = new FailingFileOps(FileOps.real(), true).failNextAfterEffect(Op.LINK);
    SealedVolume afterLink = new SealedVolume(linkThenFails, "vol-a", failing);
    assertThatIOException().isThrownBy(() -> afterLink.publish(KEY, FILE));
    assertThat(afterLink.read(KEY)).hasValueSatisfying(b -> assertThat(b).isEqualTo(FILE));
    assertThat(files(linkThenFails)).containsExactly("o/d-landing/o-k1");

    // the directory sync fails after the link: the object is complete; a retry syncs the
    // directory again and then returns false
    Path syncFails = fresh("sync-fails");
    FailingFileOps failingSync =
        new FailingFileOps(FileOps.real(), true).failNext(Op.SYNC_DIRECTORY);
    RecordingFileOps recorder = RecordingFileOps.over(failingSync);
    SealedVolume afterSync = new SealedVolume(syncFails, "vol-a", recorder);
    assertThatIOException().isThrownBy(() -> afterSync.publish(KEY, FILE));
    assertThat(afterSync.read(KEY)).hasValueSatisfying(b -> assertThat(b).isEqualTo(FILE));
    recorder.clear();
    assertThat(afterSync.publish(KEY, bytes("a retry"))).isFalse();
    // the directory that holds the link and the directories that gained their first entry in the
    // failed attempt are all synced again
    assertThat(relative(syncFails, recorder.paths(Op.SYNC_DIRECTORY)))
        .containsExactlyInAnyOrder("o/d-landing", "o", ".");
    assertThat(afterSync.read(KEY)).hasValueSatisfying(b -> assertThat(b).isEqualTo(FILE));
    assertThat(temporaries(syncFails)).isEmpty();
  }

  @Test
  void aFailedReadIsAnIoErrorAndNeverAnAbsence() throws IOException {
    FailingFileOps failing = new FailingFileOps(FileOps.real(), true);
    SealedVolume volume = new SealedVolume(root, "vol-a", failing);
    SealedFormat.Meta meta = new SealedFormat.Meta(1, "journal", UNTIL, true);
    volume.writeMarker();
    volume.publish(KEY, FILE);
    volume.writeMeta(KEY, meta);

    failing.failNext(Op.READ);
    assertThatIOException().isThrownBy(() -> volume.read(KEY));
    assertThat(volume.read(KEY)).hasValueSatisfying(b -> assertThat(b).isEqualTo(FILE));

    failing.failNext(Op.READ);
    assertThatIOException().isThrownBy(() -> volume.readMeta(KEY));
    assertThat(volume.readMeta(KEY)).contains(meta);

    failing.failNext(Op.READ);
    assertThatIOException().isThrownBy(volume::markerId);
    assertThat(volume.markerId()).contains("vol-a");
  }

  @Test
  void aTemporaryNameThatAnotherWriterTookIsSkippedAndItsFileIsNeverTouched() throws IOException {
    FailingFileOps taken = new FailingFileOps(FileOps.real(), true).nameTaken(1);
    RecordingFileOps recorder = RecordingFileOps.over(taken);
    SealedVolume volume = new SealedVolume(root, "vol-a", recorder);

    assertThat(volume.publish(KEY, FILE)).isTrue();

    // the first name was taken, the second was free
    assertThat(recorder.paths(Op.WRITE_AND_FORCE)).hasSize(2).doesNotHaveDuplicates();
    assertThat(taken.planted()).containsExactly(recorder.paths(Op.WRITE_AND_FORCE).get(0));
    // the foreign file is still there, byte for byte, and was never deleted; the call's own
    // temporary file was
    Path foreign = taken.planted().get(0);
    assertThat(foreign).hasBinaryContent(FailingFileOps.FOREIGN);
    assertThat(recorder.paths(Op.DELETE)).doesNotContain(foreign);
    assertThat(temporaries(root)).hasSize(1);
    assertThat(volume.read(KEY)).hasValueSatisfying(b -> assertThat(b).isEqualTo(FILE));
  }

  @Test
  void whenEveryTemporaryNameIsTakenThePublishGivesUpAfterEightTriesAndDeletesNothing()
      throws IOException {
    FailingFileOps taken = new FailingFileOps(FileOps.real(), true).nameAlwaysTaken();
    RecordingFileOps recorder = RecordingFileOps.over(taken);
    SealedVolume volume = new SealedVolume(root, "vol-a", recorder);

    assertThatIOException()
        .isThrownBy(() -> volume.publish(KEY, FILE))
        .withMessageContaining("vol-a")
        .withCauseInstanceOf(FileAlreadyExistsException.class);

    assertThat(recorder.paths(Op.WRITE_AND_FORCE)).hasSize(8).doesNotHaveDuplicates();
    assertThat(recorder.ops()).doesNotContain(Op.LINK, Op.DELETE);
    assertThat(taken.planted()).hasSize(8);
    for (Path foreign : taken.planted()) {
      assertThat(foreign).hasBinaryContent(FailingFileOps.FOREIGN);
    }
    assertThat(volume.read(KEY)).isEmpty();
    assertThat(files(root)).hasSize(8).allMatch(name -> name.contains("~tmp~"));
  }

  @Test
  void aFailedReplaceOfAMetaLeavesTheOldMetaAndNoTemporaryFile() throws IOException {
    FailingFileOps failing = new FailingFileOps(FileOps.real(), true);
    SealedVolume volume = new SealedVolume(root, "vol-a", failing);
    SealedFormat.Meta old = new SealedFormat.Meta(1, "journal", UNTIL, false);
    SealedFormat.Meta higher = new SealedFormat.Meta(2, "journal", UNTIL.plusSeconds(30), true);
    volume.publish(KEY, FILE);
    assertThat(volume.writeMeta(KEY, old)).isTrue();

    // the replace fails before it acts: the old meta stays, the temporary file is removed
    failing.failNext(Op.REPLACE);
    assertThatIOException().isThrownBy(() -> volume.writeMeta(KEY, higher));
    assertThat(volume.readMeta(KEY)).contains(old);
    assertThat(temporaries(root)).isEmpty();

    // the replace happened and then reported an error: the new meta is there, nothing is left
    failing.failNextAfterEffect(Op.REPLACE);
    assertThatIOException().isThrownBy(() -> volume.writeMeta(KEY, higher));
    assertThat(volume.readMeta(KEY)).contains(higher);
    assertThat(temporaries(root)).isEmpty();
    assertThat(volume.writeMeta(KEY, higher)).as("the retry").isFalse();
    assertThat(files(root)).containsExactly("o/d-landing/m-k1", "o/d-landing/o-k1");
  }

  @Test
  void aSecondNameThatShowsOtherDataFailsTheHardLinkCheck() throws IOException {
    Path dir = fresh("liar");
    FailingFileOps liar =
        new FailingFileOps(FileOps.real())
            .readsAs(p -> p.getFileName().toString().startsWith(".selftest"), bytes("other data"));

    assertThatIOException()
        .isThrownBy(() -> new SealedVolume(dir, "vol-b", liar).checkHardLinks())
        .withMessageContaining("vol-b");

    assertThat(files(dir)).as("no self-test file is left").isEmpty();
  }

  @Test
  void aTemporaryNameThatCannotBeRemovedAfterTheLinkDoesNotUndoThePublish() throws IOException {
    FailingFileOps failing = new FailingFileOps(FileOps.real(), true).failNext(Op.DELETE);
    SealedVolume volume = new SealedVolume(root, "vol-a", failing);

    assertThat(volume.publish(KEY, FILE)).isTrue();

    assertThat(volume.read(KEY)).hasValueSatisfying(b -> assertThat(b).isEqualTo(FILE));
    assertThat(volume.keys("")).containsExactly(KEY);
    assertThat(temporaries(root)).as("left for the sweep").hasSize(1);
  }

  @Test
  void aFailedCleanupIsSuppressedUnderTheFailureThatCausedIt() throws IOException {
    FailingFileOps failing =
        new FailingFileOps(FileOps.real(), true).failNext(Op.LINK).failNext(Op.DELETE);
    SealedVolume volume = new SealedVolume(root, "vol-a", failing);

    assertThatThrownBy(() -> volume.publish(KEY, FILE))
        .isInstanceOf(IOException.class)
        .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1));

    assertThat(volume.read(KEY)).isEmpty();
    assertThat(temporaries(root)).hasSize(1);
  }

  @Test
  void aDirectoryThatCannotBeCreatedOrListedIsAnIOExceptionAndNothingIsLeft() throws IOException {
    FailingFileOps failing =
        new FailingFileOps(FileOps.real(), true).failNext(Op.CREATE_DIRECTORIES);
    SealedVolume volume = new SealedVolume(root, "vol-a", failing);

    assertThatIOException().isThrownBy(() -> volume.publish(KEY, FILE));
    assertThat(volume.read(KEY)).isEmpty();
    assertThat(files(root)).isEmpty();
    assertThat(volume.publish(KEY, FILE)).as("the retry").isTrue();

    failing.failNext(Op.LIST);
    assertThatIOException().isThrownBy(() -> volume.keys(""));
    assertThat(volume.keys("")).containsExactly(KEY);
  }

  @Test
  void anExistingKeyIsResyncedBeforeFalseIsReturned() throws IOException {
    FailingFileOps failing = new FailingFileOps(FileOps.real(), true);
    RecordingFileOps recorder = RecordingFileOps.over(failing);
    SealedVolume volume = new SealedVolume(root, "vol-a", recorder);
    byte[] second = bytes("a second writer");
    assertThat(volume.publish(KEY, FILE)).isTrue();

    recorder.clear();
    assertThat(volume.publish(KEY, second)).isFalse();
    assertThat(recorder.ops()).containsSubsequence(Op.LINK, Op.SYNC_DIRECTORY);
    // the directory that holds the name and, since a refusal may follow a restart that lost the
    // bookkeeping, every ancestor up to the root (each once; never the parent of the root)
    assertThat(recorder.paths(Op.SYNC_DIRECTORY)).doesNotHaveDuplicates();
    assertThat(relative(root, recorder.paths(Op.SYNC_DIRECTORY)))
        .containsExactlyInAnyOrder("o/d-landing", "o", ".");

    // a sync that fails makes the put throw: presence alone is never an acknowledgement
    failing.failNext(Op.SYNC_DIRECTORY);
    assertThatIOException().isThrownBy(() -> volume.publish(KEY, second));
    assertThat(volume.publish(KEY, second)).isFalse();
    assertThat(volume.read(KEY)).hasValueSatisfying(b -> assertThat(b).isEqualTo(FILE));
  }

  @Test
  void anExistingKeyFoundAfterARestartSyncsTheWholeChainAndFailsWhenAnySyncFails()
      throws IOException {
    // an earlier process made the directories and the object: nothing in this JVM remembers
    // that a directory was new, so the refusal has to sync the whole chain itself
    Path holder = Files.createDirectories(root.resolve("o/d-a/d-b"));
    Files.write(holder.resolve("o-k"), FILE);
    EvidenceKey key = new EvidenceKey("a/b/k");
    byte[] second = bytes("another writer");

    RecordingFileOps recorder = RecordingFileOps.claimingDirectorySync(FileOps.real());
    SealedVolume volume = new SealedVolume(root, "vol-a", recorder);
    assertThat(volume.publish(key, second)).isFalse();
    assertThat(recorder.paths(Op.SYNC_DIRECTORY)).doesNotHaveDuplicates();
    assertThat(relative(root, recorder.paths(Op.SYNC_DIRECTORY)))
        .containsExactlyInAnyOrder("o/d-a/d-b", "o/d-a", "o", ".");

    // a sync that fails on any directory of the chain (here the root) is not an acknowledgement
    for (String name : List.of("o/d-a/d-b", "o/d-a", "o", "")) {
      Path failing = name.isEmpty() ? root : root.resolve(name);
      FailingFileOps ops =
          new FailingFileOps(FileOps.real(), true).failNext(Op.SYNC_DIRECTORY, failing::equals);
      SealedVolume failingVolume = new SealedVolume(root, "vol-a", ops);
      assertThatIOException()
          .as("sync of '%s'", name)
          .isThrownBy(() -> failingVolume.publish(key, second));
    }
    assertThat(root.resolve("o/d-a/d-b/o-k")).hasBinaryContent(FILE);
    assertThat(temporaries(root)).isEmpty();
  }

  @Test
  void publishSyncsEveryDirectoryThatGainedAnEntry() throws IOException {
    RecordingFileOps recorder = RecordingFileOps.claimingDirectorySync(FileOps.real());
    SealedVolume volume = new SealedVolume(root, "vol-a", recorder);

    // an empty volume and four segments: o, d-a, d-b and d-c are new, so the root, o, d-a and
    // d-b gained an entry, and d-c holds the link
    assertThat(volume.publish(new EvidenceKey("a/b/c/d"), FILE)).isTrue();
    assertThat(relative(root, recorder.paths(Op.SYNC_DIRECTORY)))
        .containsExactlyInAnyOrder(".", "o", "o/d-a", "o/d-a/d-b", "o/d-a/d-b/d-c");

    // the same directory: only the directory that holds the link
    recorder.clear();
    assertThat(volume.publish(new EvidenceKey("a/b/c/e"), FILE)).isTrue();
    assertThat(relative(root, recorder.paths(Op.SYNC_DIRECTORY))).containsExactly("o/d-a/d-b/d-c");

    // one new directory below an old one: the old one gained an entry
    recorder.clear();
    assertThat(volume.publish(new EvidenceKey("a/b/x/y"), FILE)).isTrue();
    assertThat(relative(root, recorder.paths(Op.SYNC_DIRECTORY)))
        .containsExactlyInAnyOrder("o/d-a/d-b", "o/d-a/d-b/d-x");

    // a key of one segment lives in o itself
    recorder.clear();
    assertThat(volume.publish(new EvidenceKey("top"), FILE)).isTrue();
    assertThat(relative(root, recorder.paths(Op.SYNC_DIRECTORY))).containsExactly("o");
  }

  @Test
  void aRetryAfterAFailedSyncOfAParentDirectoryStillSyncsIt() throws IOException {
    Path o = root.resolve("o");
    FailingFileOps failing =
        new FailingFileOps(FileOps.real(), true).failNext(Op.SYNC_DIRECTORY, p -> p.equals(o));
    RecordingFileOps recorder = RecordingFileOps.over(failing);
    SealedVolume volume = new SealedVolume(root, "vol-a", recorder);
    EvidenceKey key = new EvidenceKey("a/b");

    // o is new: the root gained an entry, and the sync of o (for d-a) fails
    assertThatIOException().isThrownBy(() -> volume.publish(key, FILE));

    recorder.clear();
    assertThat(volume.publish(key, FILE)).isFalse();
    assertThat(relative(root, recorder.paths(Op.SYNC_DIRECTORY)))
        .containsExactlyInAnyOrder("o/d-a", "o", ".");

    // once synced, a further publish does not repeat it
    recorder.clear();
    assertThat(volume.publish(new EvidenceKey("a/c"), FILE)).isTrue();
    assertThat(relative(root, recorder.paths(Op.SYNC_DIRECTORY))).containsExactly("o/d-a");
  }

  @Test
  void aDifferentKeyPublishedAfterAFailedSyncOfAParentStillSyncsThatParent() throws IOException {
    Path o = root.resolve("o");
    FailingFileOps failing =
        new FailingFileOps(FileOps.real(), true).failNext(Op.SYNC_DIRECTORY, p -> p.equals(o));
    RecordingFileOps recorder = RecordingFileOps.over(failing);
    SealedVolume volume = new SealedVolume(root, "vol-a", recorder);

    // o and d-a are new: the sync of o (the entry of d-a) fails, so the put is not acknowledged
    assertThatIOException().isThrownBy(() -> volume.publish(new EvidenceKey("a/b"), FILE));

    // A different key into the same new directory links (true) and does not go through the
    // refused-link path that syncs the whole chain: only the pending marks bring o (and the root,
    // which gained o) back. The mark of d-a must have survived the failed sync of its parent.
    recorder.clear();
    assertThat(volume.publish(new EvidenceKey("a/c"), FILE)).isTrue();
    assertThat(relative(root, recorder.paths(Op.SYNC_DIRECTORY)))
        .containsExactlyInAnyOrder("o/d-a", "o", ".");

    // every mark is cleared by now
    recorder.clear();
    assertThat(volume.publish(new EvidenceKey("a/d"), FILE)).isTrue();
    assertThat(relative(root, recorder.paths(Op.SYNC_DIRECTORY))).containsExactly("o/d-a");
  }

  @Test
  void aMetaWrittenAfterAFailedSyncOfAParentStillSyncsThatParent() throws IOException {
    Path o = root.resolve("o");
    FailingFileOps failing =
        new FailingFileOps(FileOps.real(), true).failNext(Op.SYNC_DIRECTORY, p -> p.equals(o));
    RecordingFileOps recorder = RecordingFileOps.over(failing);
    SealedVolume volume = new SealedVolume(root, "vol-a", recorder);
    SealedFormat.Meta meta = new SealedFormat.Meta(1, "journal", UNTIL, false);

    assertThatIOException().isThrownBy(() -> volume.writeMeta(new EvidenceKey("a/b"), meta));

    recorder.clear();
    assertThat(volume.writeMeta(new EvidenceKey("a/c"), meta)).isTrue();
    assertThat(relative(root, recorder.paths(Op.SYNC_DIRECTORY)))
        .containsExactlyInAnyOrder("o/d-a", "o", ".");

    recorder.clear();
    assertThat(volume.writeMeta(new EvidenceKey("a/d"), meta)).isTrue();
    assertThat(relative(root, recorder.paths(Op.SYNC_DIRECTORY))).containsExactly("o/d-a");
  }

  @Test
  void concurrentPublishesIntoNewDirectoriesSyncEveryParent() throws Exception {
    RecordingFileOps recorder = RecordingFileOps.claimingDirectorySync(FileOps.real());
    SealedVolume volume = new SealedVolume(root, "vol-a", recorder);
    List<Callable<Boolean>> tasks = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      EvidenceKey key = new EvidenceKey("x/" + (i % 2 == 0 ? "y" : "z") + "/k" + i);
      tasks.add(() -> volume.publish(key, bytes("object " + key.path())));
    }

    assertThat(runTogether(tasks)).containsOnly(true);

    assertThat(relative(root, recorder.paths(Op.SYNC_DIRECTORY)))
        .contains(".", "o", "o/d-x", "o/d-x/d-y", "o/d-x/d-z");
    assertThat(temporaries(root)).isEmpty();
    assertThat(volume.keys("x/")).hasSize(16);
  }

  // ---------------------------------------------------------------- concurrency

  @Test
  void twoVolumeInstancesOverOneDirectoryNeverShareATempName() throws Exception {
    RecordingFileOps recorder = RecordingFileOps.over(FileOps.real());
    SealedVolume first = new SealedVolume(root, "vol-a", recorder);
    SealedVolume second = new SealedVolume(root, "vol-a", recorder);
    List<Callable<Boolean>> tasks = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      SealedVolume volume = i % 2 == 0 ? first : second;
      byte[] file = bytes("writer " + i);
      tasks.add(() -> volume.publish(KEY, file));
    }

    List<Boolean> results = runTogether(tasks);

    assertThat(results.stream().filter(r -> r).count()).isEqualTo(1);
    List<Path> temporaryNames = recorder.paths(Op.WRITE_AND_FORCE);
    assertThat(temporaryNames).hasSize(16).doesNotHaveDuplicates();
    assertThat(temporaries(root)).isEmpty();
  }

  @Test
  void concurrentPublishesOfOneKeyHaveOneWinner() throws Exception {
    SealedVolume volume = new SealedVolume(root, "vol-a", FileOps.real());
    List<byte[]> candidates = new ArrayList<>();
    List<Callable<Boolean>> tasks = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      // different lengths and contents, so that a torn mixture of two writers would show
      byte[] file = ("writer " + i + " ".repeat(i * 100) + "|end").getBytes(UTF_8);
      candidates.add(file);
      tasks.add(() -> volume.publish(KEY, file));
    }

    List<Boolean> results = runTogether(tasks);

    assertThat(results.stream().filter(r -> r).count()).isEqualTo(1);
    int winner = results.indexOf(true);
    assertThat(volume.read(KEY))
        .hasValueSatisfying(b -> assertThat(b).isEqualTo(candidates.get(winner)));
    assertThat(temporaries(root)).isEmpty();
  }

  // ---------------------------------------------------------------- permissions

  @Test
  void filesAndDirectoriesAreOwnerOnlyOnPosix() throws IOException {
    Assumptions.assumeFalse(HostPlatform.current().isWindows(), "the POSIX permissions are needed");
    SealedVolume volume = new SealedVolume(root, "vol-a", FileOps.real());
    EvidenceKey key = new EvidenceKey("a/b/c");

    volume.writeMarker();
    volume.publish(key, FILE);
    volume.writeMeta(key, new SealedFormat.Meta(1, "journal", UNTIL, false));

    Set<PosixFilePermission> directory = PosixFilePermissions.fromString("rwx------");
    Set<PosixFilePermission> file = PosixFilePermissions.fromString("rw-------");
    for (String name : List.of("o", "o/d-a", "o/d-a/d-b")) {
      assertThat(Files.getPosixFilePermissions(root.resolve(name))).as(name).isEqualTo(directory);
    }
    for (String name : List.of(".sealed-volume", "o/d-a/d-b/o-c", "o/d-a/d-b/m-c")) {
      assertThat(Files.getPosixFilePermissions(root.resolve(name))).as(name).isEqualTo(file);
    }
  }

  @Test
  void onWindowsTheFilesCarryTheAclTheyInheritAndTheStoreSaysTheyAreNotOwnerOnly()
      throws IOException {
    Assumptions.assumeTrue(HostPlatform.current().isWindows(), "the twin of the POSIX test");
    SealedVolume volume = new SealedVolume(root, "vol-a", FileOps.real());
    EvidenceKey key = new EvidenceKey("a/b/c");

    volume.writeMarker();
    volume.publish(key, FILE);
    volume.writeMeta(key, new SealedFormat.Meta(1, "journal", UNTIL, false));

    assertThat(root.getFileSystem().supportedFileAttributeViews()).doesNotContain("posix");
    for (String name : List.of(".sealed-volume", "o/d-a/d-b/o-c", "o/d-a/d-b/m-c")) {
      assertThat(Files.getFileAttributeView(root.resolve(name), AclFileAttributeView.class))
          .as(name)
          .isNotNull();
    }
    // the text of the store's limits() about the inherited list is pinned in SealedPortabilityTest
  }

  // ---------------------------------------------------------------- listing and temporaries

  @Test
  void temporaryFilesAreNeverListedOrRead() throws IOException {
    SealedVolume volume = new SealedVolume(root, "vol-a", FileOps.real());
    EvidenceKey kept = new EvidenceKey("a/k");
    volume.publish(kept, FILE);
    Path dir = root.resolve("o/d-a");
    Files.write(dir.resolve("o-z~tmp~1~1"), FILE);
    Files.write(dir.resolve("m-z~tmp~1~1"), bytes("not a meta"));
    Files.write(dir.resolve("o-k~tmp~1~2"), bytes("a second copy of a key, half written"));
    Files.write(dir.resolve("stray.txt"), bytes("unknown"));
    Files.write(dir.resolve("o-"), bytes("too short"));
    // a raw capital is not an encoded name (and Q, not K: o-K and o-k are one file on NTFS)
    Files.write(dir.resolve("o-Q"), bytes("not an encoded name: a raw capital"));
    Files.createDirectory(dir.resolve("d-zzz~tmp~1~3"));
    Files.createDirectory(dir.resolve("unknown"));

    assertThat(volume.keys("")).containsExactly(kept);
    assertThat(volume.read(new EvidenceKey("a/z"))).isEmpty();
    assertThat(volume.readMeta(new EvidenceKey("a/z"))).isEmpty();
    assertThat(volume.read(kept)).hasValueSatisfying(b -> assertThat(b).isEqualTo(FILE));
  }

  @Test
  void namesThatDecodeToADotSegmentAreSkippedNotFatal() throws IOException {
    SealedVolume volume = new SealedVolume(root, "vol-a", FileOps.real());
    EvidenceKey kept = new EvidenceKey("a/k");
    volume.publish(kept, FILE);
    Path dir = root.resolve("o/d-a");
    // "o-^0" and "o-.^0" are canonical encodings of the segments "." and "..": SealedNames
    // accepts them, EvidenceKey does not, and one such entry must not break the listing
    Files.write(dir.resolve("o-^0"), FILE);
    Files.write(dir.resolve("o-.^0"), FILE);
    Files.createDirectories(dir.resolve("d-^0"));
    Files.write(dir.resolve("d-^0/o-x"), FILE);
    Files.createDirectories(dir.resolve("d-.^0"));
    Files.write(dir.resolve("d-.^0/o-x"), FILE);
    // and the same at the top of the object tree
    Files.write(root.resolve("o/o-^0"), FILE);
    Files.createDirectories(root.resolve("o/d-.^0"));
    Files.write(root.resolve("o/d-.^0/o-x"), FILE);

    assertThat(volume.keys("")).containsExactly(kept);
    assertThat(volume.keys("a/")).containsExactly(kept);
    assertThat(volume.keys("a/k")).containsExactly(kept);
    assertThat(volume.isEmpty()).isFalse();
    assertThat(volume.sweepTemporaries()).isZero();
    assertThat(volume.read(kept)).hasValueSatisfying(b -> assertThat(b).isEqualTo(FILE));
  }

  @Test
  void keysOfAPrefixAreDecodedFilteredAndComplete() throws IOException {
    SealedVolume volume = new SealedVolume(root, "vol-a", FileOps.real());
    List<String> all =
        List.of(
            "a/b",
            "a/b/c",
            "a/B",
            "a/Bc/d",
            "a/x",
            "ab/c",
            "z",
            "Landing/k1",
            "a/b.",
            "a/b.c",
            "a/b/c/d/e");
    for (String path : all) {
      volume.publish(new EvidenceKey(path), bytes(path));
    }
    // a meta file is not a key
    volume.writeMeta(new EvidenceKey("a/b"), new SealedFormat.Meta(1, "journal", UNTIL, true));

    for (String prefix :
        List.of(
            "",
            "a",
            "a/",
            "a/b",
            "a/b/",
            "a/B",
            "a/Bc",
            "a/Bc/",
            "a/Bc/d",
            "a/b.",
            "ab",
            "Landing/",
            "land",
            "z",
            "zz",
            "a/b/c/",
            "x",
            "a/b/c/d/e",
            "a/b/c/d/e/",
            "A")) {
      List<String> expected = all.stream().filter(k -> k.startsWith(prefix)).sorted().toList();
      List<String> actual = volume.keys(prefix).stream().map(EvidenceKey::path).sorted().toList();
      assertThat(actual).as("prefix '%s'", prefix).isEqualTo(expected);
    }
    // the key that is a prefix of another is listed with the longer one
    assertThat(volume.keys("a/b").stream().map(EvidenceKey::path))
        .contains("a/b", "a/b/c", "a/b.", "a/b.c");
    // an empty volume
    assertThat(new SealedVolume(fresh("empty"), "vol-b", FileOps.real()).keys("")).isEmpty();
  }

  // ---------------------------------------------------------------- meta

  @Test
  void metaIsReplacedOnlyByAHigherRevision() throws IOException {
    RecordingFileOps recorder = RecordingFileOps.claimingDirectorySync(FileOps.real());
    SealedVolume volume = new SealedVolume(root, "vol-a", recorder);
    SealedFormat.Meta first = new SealedFormat.Meta(1, "journal", UNTIL, false);
    SealedFormat.Meta sameRev = new SealedFormat.Meta(1, "journal", UNTIL.plusSeconds(60), true);
    SealedFormat.Meta lower = new SealedFormat.Meta(0, "journal", UNTIL.plusSeconds(90), true);
    SealedFormat.Meta higher = new SealedFormat.Meta(2, "journal", UNTIL.plusSeconds(30), true);
    volume.publish(KEY, FILE);
    assertThat(volume.readMeta(KEY)).isEmpty();

    assertThat(volume.writeMeta(KEY, first)).isTrue();
    assertThat(volume.readMeta(KEY)).contains(first);

    recorder.clear();
    assertThat(volume.writeMeta(KEY, sameRev)).as("the same revision").isFalse();
    assertThat(volume.writeMeta(KEY, lower)).as("a lower revision").isFalse();
    assertThat(volume.readMeta(KEY)).contains(first);
    // a refusal is acknowledged on presence, so the directory is synced first
    assertThat(recorder.ops()).contains(Op.SYNC_DIRECTORY);
    assertThat(recorder.ops()).doesNotContain(Op.REPLACE);

    recorder.clear();
    assertThat(volume.writeMeta(KEY, higher)).isTrue();
    assertThat(volume.readMeta(KEY)).contains(higher);
    assertThat(recorder.ops())
        .containsSubsequence(Op.WRITE_AND_FORCE, Op.REPLACE, Op.SYNC_DIRECTORY);

    // the object is untouched, nothing is left over, and a meta is not a key
    assertThat(volume.read(KEY)).hasValueSatisfying(b -> assertThat(b).isEqualTo(FILE));
    assertThat(volume.keys("")).containsExactly(KEY);
    assertThat(files(root)).containsExactly("o/d-landing/m-k1", "o/d-landing/o-k1");
  }

  @Test
  void aCorruptMetaIsNeverOverwritten() throws IOException {
    SealedVolume volume = new SealedVolume(root, "vol-a", FileOps.real());
    volume.publish(KEY, FILE);
    Path meta = root.resolve("o/d-landing/m-k1");
    byte[] damaged = bytes("RPMETA02 this is not a meta body");
    Files.write(meta, damaged);

    assertThatThrownBy(() -> volume.readMeta(KEY))
        .isInstanceOf(SealedFormat.CorruptException.class);
    assertThatThrownBy(
            () -> volume.writeMeta(KEY, new SealedFormat.Meta(9, "journal", UNTIL, true)))
        .isInstanceOf(SealedFormat.CorruptException.class);
    assertThat(meta).hasBinaryContent(damaged);
    assertThat(temporaries(root)).isEmpty();
  }

  @Test
  void concurrentWritesOfOneMetaRevisionHaveOneWinner() throws Exception {
    SealedVolume volume = new SealedVolume(root, "vol-a", FileOps.real());
    volume.publish(KEY, FILE);
    List<SealedFormat.Meta> candidates = new ArrayList<>();
    List<Callable<Boolean>> tasks = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      SealedFormat.Meta meta =
          new SealedFormat.Meta(5, "journal", UNTIL.plusSeconds(i), i % 2 == 0);
      candidates.add(meta);
      tasks.add(() -> volume.writeMeta(KEY, meta));
    }

    List<Boolean> results = runTogether(tasks);

    assertThat(results.stream().filter(r -> r).count()).isEqualTo(1);
    assertThat(volume.readMeta(KEY)).contains(candidates.get(results.indexOf(true)));
    assertThat(temporaries(root)).isEmpty();
  }

  @Test
  @Timeout(120)
  void aMetaReadWhileItIsBeingReplacedIsAlwaysAWholeMeta() throws Exception {
    SealedVolume volume = new SealedVolume(root, "vol-a", FileOps.real());
    volume.publish(KEY, FILE);
    volume.writeMeta(KEY, new SealedFormat.Meta(1, "journal", UNTIL, false));
    AtomicBoolean writing = new AtomicBoolean(true);
    CountDownLatch readerRunning = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Long> reader =
          pool.submit(
              () -> {
                long reads = 0;
                long lastRev = 0;
                while (writing.get()) {
                  // a torn or missing meta would throw here, a stale one would show a lower rev
                  long rev = volume.readMeta(KEY).orElseThrow().rev();
                  assertThat(rev).isGreaterThanOrEqualTo(lastRev);
                  lastRev = rev;
                  reads++;
                  readerRunning.countDown();
                  // a short pause, so that the writer is never starved of the lock
                  LockSupport.parkNanos(20_000);
                }
                return reads;
              });
      // the writer starts only once the reader is reading, so the two always overlap
      assertThat(readerRunning.await(60, TimeUnit.SECONDS)).isTrue();
      for (long rev = 2; rev <= 150; rev++) {
        assertThat(volume.writeMeta(KEY, new SealedFormat.Meta(rev, "journal", UNTIL, false)))
            .isTrue();
      }
      writing.set(false);
      assertThat(reader.get(60, TimeUnit.SECONDS)).isPositive();
    } finally {
      writing.set(false);
      pool.shutdownNow();
    }
    assertThat(volume.readMeta(KEY).orElseThrow().rev()).isEqualTo(150);
    assertThat(temporaries(root)).isEmpty();
  }

  // ---------------------------------------------------------------- sweep

  @Test
  void sweepRemovesTemporariesOfDeadProcessesOnly() throws IOException {
    assertThat(ProcessHandle.of(DEAD_PID)).as("the constant must name no process").isEmpty();
    SealedVolume volume = new SealedVolume(root, "vol-a", FileOps.real());
    volume.publish(new EvidenceKey("a/k"), FILE);
    long self = ProcessHandle.current().pid();
    Path dir = root.resolve("o/d-a");
    String dead = "~tmp~" + DEAD_PID + "~";
    Files.write(dir.resolve("o-x" + dead + "1"), FILE);
    Files.write(dir.resolve("m-x" + dead + "2"), FILE);
    Files.write(root.resolve(".selftest" + dead + "3"), FILE);
    Files.write(root.resolve("o").resolve("o-top" + dead + "4"), FILE);
    // a live process keeps its files; names that are not ours are left alone
    Files.write(dir.resolve("o-y~tmp~" + self + "~1"), FILE);
    Files.write(dir.resolve("o-w~tmp~abc~1"), FILE);
    Files.write(dir.resolve("o-v~tmp~" + DEAD_PID), FILE);
    Files.write(dir.resolve("o-u~tmp~+5~1"), FILE);
    Files.write(dir.resolve("stray.txt"), FILE);

    assertThat(volume.sweepTemporaries()).isEqualTo(4);

    assertThat(files(root))
        .containsExactlyInAnyOrder(
            "o/d-a/o-k",
            "o/d-a/o-y~tmp~" + self + "~1",
            "o/d-a/o-w~tmp~abc~1",
            "o/d-a/o-v~tmp~" + DEAD_PID,
            "o/d-a/o-u~tmp~+5~1",
            "o/d-a/stray.txt");
    assertThat(volume.sweepTemporaries()).as("idempotent").isZero();
    assertThat(volume.read(new EvidenceKey("a/k"))).isPresent();
  }

  @Test
  void theTemporaryNameOfARealPublishCarriesThePidOfThisProcessAndIsLeftAloneByAnotherSweep()
      throws IOException {
    AtomicReference<Path> temporary = new AtomicReference<>();
    AtomicInteger sweptByTheOther = new AtomicInteger(-1);
    AtomicBoolean stillThere = new AtomicBoolean();
    // between writeAndForce and link, another volume object over the same directory (another
    // store's open) sweeps the volume
    FailingFileOps ops =
        new FailingFileOps(FileOps.real())
            .after(Op.WRITE_AND_FORCE, path -> true, temporary::set)
            .before(
                Op.LINK,
                path -> true,
                path -> {
                  sweptByTheOther.set(
                      new SealedVolume(root, "vol-a", FileOps.real()).sweepTemporaries());
                  stillThere.set(Files.exists(temporary.get()));
                });
    SealedVolume volume = new SealedVolume(root, "vol-a", ops);

    assertThat(volume.publish(KEY, FILE)).isTrue();

    assertThat(temporary.get().getFileName().toString())
        .matches("o-k1~tmp~" + ProcessHandle.current().pid() + "~[0-9]+");
    assertThat(sweptByTheOther).hasValue(0);
    assertThat(stillThere).isTrue();
    assertThat(volume.read(KEY)).hasValueSatisfying(b -> assertThat(b).isEqualTo(FILE));
    assertThat(temporaries(root)).isEmpty();
  }

  @Test
  void aCompleteDeadTemporaryIsKeptAndListedAndOneThatDecodesNotIsDeleted() throws IOException {
    SealedVolume volume = new SealedVolume(root, "vol-a", FileOps.real());
    EvidenceKey key = new EvidenceKey("a/k");
    byte[] whole = completeObjectFile(key);
    Path dir = Files.createDirectories(root.resolve("o/d-a"));
    Path complete = dir.resolve(SealedNames.temporary("o-k", DEAD_PID, 1));
    Files.write(complete, whole);
    Path half = dir.resolve(SealedNames.temporary("o-j", DEAD_PID, 2));
    Files.write(half, FILE);

    assertThat(volume.completeTemporaries())
        .containsExactly("o/d-a/" + complete.getFileName().toString());
    assertThat(volume.sweepTemporaries()).isEqualTo(1);

    assertThat(complete).exists();
    assertThat(half).doesNotExist();
    assertThat(volume.keys("")).isEmpty();
    assertThat(volume.read(key)).isEmpty();
    assertThat(volume.sweepTemporaries()).as("idempotent").isZero();
    // once the name exists with the same bytes the temporary is debris
    assertThat(volume.publish(key, whole)).isTrue();
    assertThat(volume.completeTemporaries()).isEmpty();
    assertThat(volume.sweepTemporaries()).isEqualTo(1);
    assertThat(complete).doesNotExist();
  }

  @Test
  void aCompleteTemporaryOfAnEarlierIncarnationOfThisPidIsKeptToo() throws IOException {
    long self = ProcessHandle.current().pid();
    EvidenceKey key = new EvidenceKey("a/k");
    Path stale = plantTemporary("o-k", self, 1, START.minusSeconds(3600));
    Files.write(stale, completeObjectFile(key));
    Files.setLastModifiedTime(stale, FileTime.from(START.minusSeconds(3600)));
    SealedVolume volume = volumeStartedAt(Optional.of(START));

    assertThat(volume.sweepTemporaries()).isZero();
    assertThat(stale).exists();
    assertThat(volume.completeTemporaries()).hasSize(1);
    // a complete one that a running writer owns (written since the start) is neither
    Path live = plantTemporary("o-k", self, 2, START.plusSeconds(60));
    Files.write(live, completeObjectFile(key));
    Files.setLastModifiedTime(live, FileTime.from(START.plusSeconds(60)));
    assertThat(volume.completeTemporaries()).hasSize(1);
    assertThat(volume.sweepTemporaries()).isZero();
    assertThat(live).exists();
  }

  /** A whole object file for the key, as the store would build it. */
  private static byte[] completeObjectFile(EvidenceKey key) {
    byte[] payload = bytes("payload of " + key.path());
    return SealedFormat.encodeObject(
        new SealedFormat.Header(
            key.path(),
            "text/plain",
            Sha256.of(payload),
            payload.length,
            Instant.parse("2026-03-01T00:00:00Z"),
            "landing",
            UNTIL),
        payload);
  }

  /** The start of the JVM as the sweep sees it in the tests below. */
  private static final Instant START = Instant.parse("2026-06-01T12:00:00Z");

  private SealedVolume volumeStartedAt(Optional<Instant> start) {
    return new SealedVolume(root, "vol-a", FileOps.real(), () -> start);
  }

  private Path plantTemporary(String name, long pid, long counter, Instant modified)
      throws IOException {
    Path file =
        Files.createDirectories(root.resolve("o/d-a"))
            .resolve(SealedNames.temporary(name, pid, counter));
    Files.write(file, FILE);
    Files.setLastModifiedTime(file, FileTime.from(modified));
    return file;
  }

  @Test
  void aTemporaryOfThisPidFromAnEarlierIncarnationIsSwept() throws IOException {
    long self = ProcessHandle.current().pid();
    // a container whose JVM is always pid 1, or a recycled pid: the process is "alive", but the
    // file is older than the JVM that now holds the pid
    Path stale = plantTemporary("o-x", self, 1, START.minusSeconds(3600));
    Path rootStale = root.resolve(SealedNames.temporary(".selftest", self, 2));
    Files.write(rootStale, FILE);
    Files.setLastModifiedTime(rootStale, FileTime.from(START.minusSeconds(3600)));
    Path inO = root.resolve("o").resolve(SealedNames.temporary("o-top", self, 3));
    Files.write(inO, FILE);
    Files.setLastModifiedTime(inO, FileTime.from(START.minusSeconds(3600)));

    assertThat(volumeStartedAt(Optional.of(START)).sweepTemporaries()).isEqualTo(3);

    assertThat(stale).doesNotExist();
    assertThat(rootStale).doesNotExist();
    assertThat(inO).doesNotExist();
  }

  @Test
  void aTemporaryOfThisPidMadeByThisIncarnationIsKept() throws IOException {
    long self = ProcessHandle.current().pid();
    Path after = plantTemporary("o-a", self, 1, START.plusSeconds(5));
    Path atStart = plantTemporary("o-b", self, 2, START);
    // the file system may round a time down (FAT keeps two seconds): two seconds of skew are kept
    Path withinSkew = plantTemporary("o-c", self, 3, START.minusSeconds(2));
    Path beyondSkew = plantTemporary("o-d", self, 4, START.minusSeconds(2).minusMillis(1));

    assertThat(volumeStartedAt(Optional.of(START)).sweepTemporaries()).isEqualTo(1);

    assertThat(after).exists();
    assertThat(atStart).exists();
    assertThat(withinSkew).exists();
    assertThat(beyondSkew).doesNotExist();
  }

  @Test
  void aTemporaryOfAnotherLiveProcessIsKeptWhileItIsNotOlderThanThisJvm() throws IOException {
    Optional<ProcessHandle> parent = ProcessHandle.current().parent();
    Assumptions.assumeTrue(parent.isPresent(), "a live process other than this one is needed");
    long other = parent.get().pid();
    assertThat(other).isNotEqualTo(ProcessHandle.current().pid());
    Path running = plantTemporary("o-a", other, 1, START.plusSeconds(1));
    Path recycled = plantTemporary("o-b", other, 2, START.minusSeconds(3600));

    assertThat(volumeStartedAt(Optional.of(START)).sweepTemporaries()).isEqualTo(1);

    assertThat(running).as("a writer of another live process").exists();
    assertThat(recycled).as("older than this JVM: a pid that was handed on").doesNotExist();
  }

  @Test
  void withoutAStartInstantTheRuleIsTheLivenessOfTheProcessAlone() throws IOException {
    long self = ProcessHandle.current().pid();
    Path old = plantTemporary("o-a", self, 1, START.minusSeconds(3600));
    Path dead = plantTemporary("o-b", DEAD_PID, 2, START.minusSeconds(3600));

    assertThat(volumeStartedAt(Optional.empty()).sweepTemporaries()).isEqualTo(1);

    assertThat(old).exists();
    assertThat(dead).doesNotExist();
  }

  @Test
  void aTemporaryThatVanishesDuringTheSweepIsNotCounted() throws IOException {
    long self = ProcessHandle.current().pid();
    Path stale = plantTemporary("o-a", self, 1, START.minusSeconds(3600));
    // the listing shows the file, the file is gone when the sweep asks for its age
    FailingFileOps vanishing =
        new FailingFileOps(FileOps.real())
            .after(Op.LIST, stale.getParent()::equals, directory -> Files.deleteIfExists(stale));

    SealedVolume volume = new SealedVolume(root, "vol-a", vanishing, () -> Optional.of(START));

    assertThat(volume.sweepTemporaries()).isZero();
  }

  // ---------------------------------------------------------------- hard links, marker

  @Test
  void aVolumeThatCannotHardLinkIsRefused() throws IOException {
    // a real file system that can: nothing is left behind
    SealedVolume good = new SealedVolume(fresh("good"), "vol-a", FileOps.real());
    good.checkHardLinks();
    assertThat(files(root.resolve("good"))).isEmpty();

    // a volume whose link is unsupported, and one whose link fails: the message names the volume
    List<FailingFileOps> failures =
        List.of(
            new FailingFileOps(FileOps.real()).failNextAsUnsupported(Op.LINK),
            new FailingFileOps(FileOps.real()).failNext(Op.LINK));
    for (int i = 0; i < failures.size(); i++) {
      Path bad = fresh("bad-" + i);
      SealedVolume volume = new SealedVolume(bad, "vol-b", failures.get(i));
      assertThatIOException().isThrownBy(volume::checkHardLinks).withMessageContaining("vol-b");
      assertThat(files(bad)).as("no self-test file is left").isEmpty();
    }
  }

  private static final FailingFileOps.LinkAction COPY =
      (existing, name) -> Files.copy(existing, name);

  private static final FailingFileOps.LinkAction MOVE =
      (existing, name) -> Files.move(existing, name);

  /** A link that is a true second name but takes over a name that exists (no no-clobber). */
  private static final FailingFileOps.LinkAction LINK_THAT_REPLACES =
      (existing, name) -> {
        Files.deleteIfExists(name);
        Files.createLink(name, existing);
      };

  /** A link that makes the second name and a third one: the file ends with three names. */
  private static final FailingFileOps.LinkAction LINK_WITH_AN_EXTRA_NAME =
      (existing, name) -> {
        Files.createLink(name, existing);
        Files.createLink(name.resolveSibling("extra-" + name.getFileName()), existing);
      };

  @Test
  void aLinkThatCopiesIsRefusedBecauseTheTwoNamesAreNotOneFile() throws IOException {
    Path dir = fresh("copy");
    FailingFileOps ops = new FailingFileOps(FileOps.real()).linksBy(COPY);

    assertThatIOException()
        .isThrownBy(() -> new SealedVolume(dir, "vol-b", ops).checkHardLinks())
        .withMessage("volume vol-b: the two names of a hard link are not one file");
    assertThat(files(dir)).as("no self-test file is left").isEmpty();
  }

  @Test
  void aLinkThatMovesIsRefused() throws IOException {
    Path dir = fresh("move");
    FailingFileOps ops = new FailingFileOps(FileOps.real()).linksBy(MOVE);

    assertThatIOException()
        .isThrownBy(() -> new SealedVolume(dir, "vol-b", ops).checkHardLinks())
        .withMessage("volume vol-b: the two names of a hard link are not one file");
    assertThat(files(dir)).as("no self-test file is left").isEmpty();
  }

  @Test
  void aLinkOntoAnExistingNameThatDoesNotRefuseItIsRefused() throws IOException {
    Path dir = fresh("replace");
    FailingFileOps ops = new FailingFileOps(FileOps.real()).linksBy(LINK_THAT_REPLACES);

    assertThatIOException()
        .isThrownBy(() -> new SealedVolume(dir, "vol-b", ops).checkHardLinks())
        .withMessage("volume vol-b: a hard link onto an existing name does not refuse it");
    assertThat(files(dir)).as("no self-test file is left").isEmpty();
  }

  @Test
  void aSecondLinkOntoTheNewNameIsAttemptedAndRefusedByTheRealFileSystem() throws IOException {
    Path dir = fresh("twice");
    RecordingFileOps recorder = RecordingFileOps.over(FileOps.real());

    new SealedVolume(dir, "vol-a", recorder).checkHardLinks();

    List<RecordingFileOps.Call> links =
        recorder.calls().stream().filter(c -> c.op() == Op.LINK).toList();
    assertThat(links).hasSize(2);
    assertThat(links.get(1).path()).as("the same new name").isEqualTo(links.get(0).path());
    assertThat(files(dir)).isEmpty();
  }

  @Test
  void theLinkCountOfTheFileIsTwoWhereTheFileSystemReportsIt() throws IOException {
    Path dir = fresh("count");
    FailingFileOps ops = new FailingFileOps(FileOps.real()).linksBy(LINK_WITH_AN_EXTRA_NAME);
    boolean reports = dir.getFileSystem().supportedFileAttributeViews().contains("unix");
    // the guard is the platform, and the file system must agree with it (HostPlatformTest)
    assertThat(reports).isEqualTo(!HostPlatform.current().isWindows());

    if (reports) {
      assertThatIOException()
          .isThrownBy(() -> new SealedVolume(dir, "vol-b", ops).checkHardLinks())
          .withMessage("volume vol-b: a hard link gives the file 3 names, not 2");
    } else {
      // no link count to read here: three names of one file pass (the other checks hold)
      new SealedVolume(dir, "vol-b", ops).checkHardLinks();
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path leftover : walk.filter(Files::isRegularFile).toList()) {
        assertThat(leftover.getFileName().toString()).startsWith("extra-");
      }
    }
  }

  @Test
  void aForeignFileOnTheSecondNameIsSkippedAndNeverDeleted() throws IOException {
    Path dir = fresh("taken");
    FailingFileOps ops = new FailingFileOps(FileOps.real()).linkNameTaken(2);

    new SealedVolume(dir, "vol-a", ops).checkHardLinks();

    assertThat(ops.planted()).hasSize(2);
    for (Path foreign : ops.planted()) {
      assertThat(foreign).hasBinaryContent(FailingFileOps.FOREIGN);
    }
    assertThat(files(dir)).hasSize(2).allMatch(name -> name.contains("~tmp~"));
  }

  @Test
  void whenEverySecondNameIsTakenTheSelfTestFailsClearlyAndDeletesNothingForeign()
      throws IOException {
    Path dir = fresh("always-taken");
    FailingFileOps ops = new FailingFileOps(FileOps.real()).linkNameAlwaysTaken();

    assertThatIOException()
        .isThrownBy(() -> new SealedVolume(dir, "vol-b", ops).checkHardLinks())
        .withMessage("volume vol-b: no free name for the hard-link self-test")
        .withCauseInstanceOf(FileAlreadyExistsException.class);

    assertThat(ops.planted()).hasSize(8);
    for (Path foreign : ops.planted()) {
      assertThat(foreign).hasBinaryContent(FailingFileOps.FOREIGN);
    }
    assertThat(files(dir)).hasSize(8).allMatch(name -> name.contains("~tmp~"));
  }

  @Test
  void theSelfTestSyncsTheRootWhereTheVolumeCanSyncADirectory() throws IOException {
    Path dir = fresh("sync");
    RecordingFileOps claiming = RecordingFileOps.claimingDirectorySync(FileOps.real());
    new SealedVolume(dir, "vol-a", claiming).checkHardLinks();
    assertThat(claiming.paths(Op.SYNC_DIRECTORY)).containsExactly(dir);

    // a volume whose directory sync fails is refused at the open, not at the first send
    Path failingDir = fresh("sync-fails");
    FailingFileOps failing = new FailingFileOps(FileOps.real(), true).failNext(Op.SYNC_DIRECTORY);
    assertThatIOException()
        .isThrownBy(() -> new SealedVolume(failingDir, "vol-b", failing).checkHardLinks())
        .withMessage("volume vol-b cannot sync a directory");
    assertThat(files(failingDir)).as("no self-test file is left").isEmpty();

    // and where the platform cannot sync a directory (Windows) none is attempted
    Path plainDir = fresh("plain");
    RecordingFileOps plain = RecordingFileOps.over(FileOps.real());
    new SealedVolume(plainDir, "vol-c", plain).checkHardLinks();
    assertThat(plain.paths(Op.SYNC_DIRECTORY).isEmpty())
        .isEqualTo(HostPlatform.current().isWindows());
  }

  @Test
  void markerRoundTripsAndAnEmptyRootIsRecognised() throws IOException {
    Path volumeRoot = root.resolve("not-yet-created");
    SealedVolume volume = new SealedVolume(volumeRoot, "vol-a", FileOps.real());
    assertThat(volume.isEmpty()).as("an absent root").isTrue();
    assertThat(volume.markerId()).isEmpty();

    volume.writeMarker();
    assertThat(volume.markerId()).contains("vol-a");
    assertThat(volume.isEmpty()).as("a marker is an entry").isFalse();
    assertThat(volumeRoot.resolve(".sealed-volume")).hasBinaryContent(bytes("vol-a"));
    volume.writeMarker(); // the same marker again changes nothing
    assertThat(volume.markerId()).contains("vol-a");

    // another volume never replaces it
    SealedVolume other = new SealedVolume(volumeRoot, "vol-b", FileOps.real());
    assertThat(other.markerId()).as("what is stored, whoever asks").contains("vol-a");
    assertThatIOException().isThrownBy(other::writeMarker).withMessageContaining("vol-b");
    assertThat(volume.markerId()).contains("vol-a");
    assertThat(temporaries(volumeRoot)).isEmpty();

    // an empty root: temporaries and empty object directories do not count
    Path empty = fresh("empty");
    SealedVolume fresh = new SealedVolume(empty, "vol-c", FileOps.real());
    assertThat(fresh.isEmpty()).isTrue();
    Files.write(empty.resolve("x~tmp~1~1"), bytes("left by a crash"));
    Files.createDirectories(empty.resolve("o/d-a/d-b"));
    assertThat(fresh.isEmpty()).isTrue();
    fresh.publish(new EvidenceKey("a/b/c"), FILE);
    assertThat(fresh.isEmpty()).isFalse();

    // anything else is an entry
    Path stray = fresh("stray");
    Files.write(stray.resolve("notes.txt"), bytes("not ours"));
    assertThat(new SealedVolume(stray, "vol-d", FileOps.real()).isEmpty()).isFalse();
  }

  @Test
  void aMarkerThatIsNotAVolumeMarkerIsRefused() throws IOException {
    SealedVolume volume = new SealedVolume(root, "vol-a", FileOps.real());
    Files.write(root.resolve(".sealed-volume"), new byte[10_000]);

    assertThatIOException().isThrownBy(volume::markerId).withMessageContaining("vol-a");
  }

  @Test
  void aVolumeNeedsAnAbsoluteRootAndAValidId() {
    FileOps ops = FileOps.real();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SealedVolume(Path.of("relative"), "vol-a", ops));
    for (String id : List.of("", "A", "a b", "a/b", "a_b", "x".repeat(33))) {
      assertThatIllegalArgumentException()
          .as("id '%s'", id)
          .isThrownBy(() -> new SealedVolume(root, id, ops));
    }
    assertThat(new SealedVolume(root, "x".repeat(32), ops)).isNotNull();
    assertThat(new SealedVolume(root, "0-9-a", ops)).isNotNull();
  }
}
