package dev.regpack.evidence.sealed;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.evidence.sealed.RecordingFileOps.Op;
import dev.regpack.model.Sha256;
import dev.regpack.ports.evidence.EvidenceCorruptionException;
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore.Durability;
import dev.regpack.ports.evidence.EvidenceStore.Grade;
import dev.regpack.ports.evidence.EvidenceStore.PutOutcome;
import dev.regpack.ports.evidence.EvidenceStore.PutRequest;
import dev.regpack.ports.evidence.EvidenceStore.Retention;
import dev.regpack.ports.evidence.EvidenceStore.RetentionClass;
import dev.regpack.ports.evidence.EvidenceStore.StoredObject;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.evidence.journal.JournalKey;
import dev.regpack.ports.evidence.journal.JournalObject;
import dev.regpack.ports.evidence.journal.JournalScope;
import dev.regpack.ports.evidence.journal.JournalType;
import dev.regpack.ports.evidence.journal.JournalWriter;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.testkit.clock.ManualClock;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T7: P-SEALED on two volumes (D-E5; review focus EF7, EF13, EF26). Every test works in a
 * {@code @TempDir}. The shared contract suites run in {@link SealedEvidenceStoreContractTest}; the
 * tests here are the ones that need the two directories in view.
 */
class SealedEvidenceStoreTest {
  private static final EvidenceKey KEY = new EvidenceKey("landing/k1");

  @TempDir Path root;

  private ManualClock clock;
  private Path volumeA;
  private Path volumeB;

  @BeforeEach
  void setUp() throws IOException {
    clock = new ManualClock(Instant.parse("2026-03-01T00:00:00Z"));
    Path base = root.toRealPath();
    volumeA = base.resolve("vol-a");
    volumeB = base.resolve("vol-b");
  }

  // ---------------------------------------------------------------- helpers

  private SealedEvidenceStore.Config config() {
    return new SealedEvidenceStore.Config(volumeA, volumeB, "vol-a", "vol-b", clock);
  }

  private SealedEvidenceStore open() {
    return SealedEvidenceStore.open(config());
  }

  private SealedEvidenceStore open(FileOps ops) {
    return SealedEvidenceStore.open(config(), ops);
  }

  private SealedEvidenceStore openWith(VolumeProbe probe) {
    return SealedEvidenceStore.open(
        new SealedEvidenceStore.Config(volumeA, volumeB, "vol-a", "vol-b", clock, probe));
  }

  private static byte[] utf8(String text) {
    return text.getBytes(UTF_8);
  }

  private Retention retention() {
    return new Retention(new RetentionClass("landing"), clock.now().plus(Duration.ofDays(1826)));
  }

  private PutRequest request(EvidenceKey key, String text) {
    return new PutRequest(key, utf8(text), "text/plain", retention());
  }

  private static Path objectPath(Path volume, EvidenceKey key) {
    String[] segments = key.path().split("/", -1);
    Path directory = volume.resolve("o");
    for (int i = 0; i < segments.length - 1; i++) {
      directory = directory.resolve(SealedNames.directory(segments[i]));
    }
    return directory.resolve(SealedNames.object(segments[segments.length - 1]));
  }

  private static SealedVolume volume(Path root, String id) {
    return new SealedVolume(root, id, FileOps.real());
  }

  private static void flipLastByte(Path file) throws IOException {
    byte[] bytes = Files.readAllBytes(file);
    bytes[bytes.length - 1] ^= 0x01;
    Files.write(file, bytes);
  }

  private static void deleteTree(Path directory) throws IOException {
    try (Stream<Path> walk = Files.walk(directory)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
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

  private static Path metaPath(Path volume, EvidenceKey key) {
    String path = key.path();
    return objectPath(volume, key)
        .resolveSibling(SealedNames.meta(path.substring(path.lastIndexOf('/') + 1)));
  }

  /** Leaves the magic of a meta file and puts junk behind it. */
  private static void damageMeta(Path volume, EvidenceKey key) throws IOException {
    Files.write(metaPath(volume, key), utf8("RPMETA01 this is not a retention record"));
  }

  @FunctionalInterface
  private interface Hook {
    void run(int firing) throws IOException;
  }

  /**
   * Runs {@code hook} before each of the first {@code times} replaces of a meta file on {@code
   * volume} (the replace is the step that gives a meta its name), then does the replace: another
   * writer's meta lands on the other volume while this store is in the middle of its own write.
   */
  private static final class BeforeMetaReplace implements FileOps {
    private final FileOps delegate;
    private final Path volume;
    private final int times;
    private final Hook hook;
    private final AtomicInteger fired = new AtomicInteger();

    BeforeMetaReplace(FileOps delegate, Path volume, int times, Hook hook) {
      this.delegate = delegate;
      this.volume = volume;
      this.times = times;
      this.hook = hook;
    }

    int fired() {
      return fired.get();
    }

    @Override
    public void replace(Path source, Path target) throws IOException {
      if (target.startsWith(volume)
          && target.getFileName().toString().startsWith("m-")
          && fired.get() < times) {
        hook.run(fired.incrementAndGet());
      }
      delegate.replace(source, target);
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
      delegate.link(existing, newName);
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

  /**
   * Makes {@code link} lead to the directory {@code target}: a symbolic link, or where there is no
   * POSIX view (Windows), where an account without the privilege cannot make one, a junction (no
   * privilege needed; it is made by a short-lived {@code cmd /c mklink /J} child process, found on
   * the PATH, with both paths inside this test's temporary directory).
   */
  private static boolean createLink(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
      return Files.isDirectory(link);
    } catch (IOException | UnsupportedOperationException | SecurityException e) {
      if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
        return false;
      }
    }
    try {
      Process process =
          new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
              .redirectErrorStream(true)
              .start();
      process.getInputStream().readAllBytes();
      return process.waitFor() == 0 && Files.isDirectory(link);
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static JournalObject transportResult(Instant at, String detail) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("attempt_id", 7L);
    payload.put("status", "ACCEPTED");
    payload.put("detail", detail);
    payload.put("observed_at", at);
    JournalScope scope =
        JournalScope.of(new EndpointKey("PRODUCTION", "ARM-ONE", "5493001KJTIIGC8Y1R12", "FCA"));
    return new JournalObject(
        new JournalKey.ClassB(scope, 3, 1), JournalType.TRANSPORT_RESULT, 1, at, payload);
  }

  // ---------------------------------------------------------------- the two volumes

  @Test
  void anAcknowledgedPutIsOnBothVolumesAndByteIdentical() throws IOException {
    SealedEvidenceStore store = open();
    Instant putAt = clock.now();
    assertThat(store.put(request(KEY, "hello"))).isEqualTo(PutOutcome.CREATED);

    byte[] onA = Files.readAllBytes(objectPath(volumeA, KEY));
    byte[] onB = Files.readAllBytes(objectPath(volumeB, KEY));
    assertThat(onA).isEqualTo(onB);
    SealedFormat.Decoded decoded = SealedFormat.decodeObject(onA, KEY.path());
    assertThat(decoded.payload()).isEqualTo(utf8("hello"));
    assertThat(decoded.header().mediaType()).isEqualTo("text/plain");
    assertThat(decoded.header().writtenAt()).isEqualTo(putAt);
    assertThat(decoded.header().retentionClass()).isEqualTo("landing");

    // a repeated put changes neither file and keeps the first header
    clock.advance(Duration.ofHours(1));
    assertThat(store.put(request(KEY, "hello"))).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThat(Files.readAllBytes(objectPath(volumeA, KEY))).isEqualTo(onA);
    assertThat(Files.readAllBytes(objectPath(volumeB, KEY))).isEqualTo(onA);
    assertThat(files(volumeA)).noneMatch(name -> name.contains("~tmp~"));
    assertThat(files(volumeB)).noneMatch(name -> name.contains("~tmp~"));
  }

  @Test
  void aFailureOnTheSecondVolumeFailsThePutAndTheRetryCompletesIt() throws IOException {
    FailingFileOps failing = new FailingFileOps(FileOps.real());
    SealedEvidenceStore store = open(failing);
    failing.failNext(Op.LINK, path -> path.startsWith(volumeB));

    assertThatThrownBy(() -> store.put(request(KEY, "hello")))
        .isInstanceOf(EvidenceUnavailableException.class);
    assertThat(objectPath(volumeA, KEY)).exists();
    assertThat(objectPath(volumeB, KEY)).doesNotExist();
    assertThat(files(volumeB)).noneMatch(name -> name.contains("~tmp~"));

    // the first volume holds the object, so the retry is not a creation; it completes volume b
    assertThat(store.put(request(KEY, "hello"))).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThat(Files.readAllBytes(objectPath(volumeB, KEY)))
        .isEqualTo(Files.readAllBytes(objectPath(volumeA, KEY)));
    assertThat(store.get(KEY).orElseThrow().bytes()).isEqualTo(utf8("hello"));
  }

  @Test
  void aCrashBetweenTheVolumesIsNeverAcknowledged() throws IOException {
    FailingFileOps failing = new FailingFileOps(FileOps.real());
    SealedEvidenceStore store = open(failing);
    failing.failNext(Op.WRITE_AND_FORCE, path -> path.startsWith(volumeB));

    // the caller saw an exception, so nothing was acknowledged...
    assertThatThrownBy(() -> store.put(request(KEY, "hello")))
        .isInstanceOf(EvidenceUnavailableException.class);
    // ...and the state is: the first volume has it, the second not, and reads serve it
    assertThat(objectPath(volumeA, KEY)).exists();
    assertThat(objectPath(volumeB, KEY)).doesNotExist();
    assertThat(store.get(KEY).orElseThrow().bytes()).isEqualTo(utf8("hello"));
    assertThat(store.list("landing/", Optional.empty(), 10)).containsExactly(KEY);
    SealedEvidenceStore reopened = open();
    assertThat(reopened.get(KEY).orElseThrow().bytes()).isEqualTo(utf8("hello"));
  }

  @Test
  void aPutWhenOnlyTheSecondVolumeHoldsTheKeyCopiesItsBytesToTheFirstAndIsNotCreated()
      throws IOException {
    SealedEvidenceStore store = open();
    store.put(request(KEY, "hello"));
    byte[] first = Files.readAllBytes(objectPath(volumeB, KEY));
    Files.delete(objectPath(volumeA, KEY));

    clock.advance(Duration.ofHours(2));
    assertThat(store.put(request(KEY, "hello"))).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    // the bytes are the second volume's, header and write time of the first put included
    assertThat(Files.readAllBytes(objectPath(volumeA, KEY))).isEqualTo(first);
    assertThat(Files.readAllBytes(objectPath(volumeB, KEY))).isEqualTo(first);
  }

  @Test
  void aPutOfAnExistingKeyIsNotAcknowledgedBeforeItsDirectoriesAreSyncedOnBothVolumes()
      throws IOException {
    FailingFileOps failing = new FailingFileOps(FileOps.real(), true);
    RecordingFileOps recording = RecordingFileOps.claimingDirectorySync(failing);
    SealedEvidenceStore store = open(recording);
    store.put(request(KEY, "hello"));
    recording.clear();

    assertThat(store.put(request(KEY, "hello"))).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);

    List<Path> synced = recording.paths(Op.SYNC_DIRECTORY);
    assertThat(synced).anyMatch(path -> path.startsWith(volumeA));
    assertThat(synced).anyMatch(path -> path.startsWith(volumeB));
    // a retry that finds the key present is not acknowledged on presence alone
    failing.failNext(Op.SYNC_DIRECTORY, path -> path.startsWith(volumeB));
    assertThatThrownBy(() -> store.put(request(KEY, "hello")))
        .isInstanceOf(EvidenceUnavailableException.class);
  }

  @Test
  void aPutWithADamagedCopyOnEitherVolumeIsRefused() throws IOException {
    SealedEvidenceStore store = open();
    EvidenceKey onA = new EvidenceKey("landing/damaged-on-a");
    EvidenceKey onB = new EvidenceKey("landing/damaged-on-b");
    store.put(request(onA, "hello"));
    store.put(request(onB, "hello"));

    flipLastByte(objectPath(volumeA, onA));
    byte[] untouchedB = Files.readAllBytes(objectPath(volumeB, onA));
    assertThatThrownBy(() -> store.put(request(onA, "hello")))
        .isInstanceOf(EvidenceCorruptionException.class);
    assertThatThrownBy(() -> store.put(request(onA, "other bytes")))
        .isInstanceOf(EvidenceCorruptionException.class);
    assertThat(Files.readAllBytes(objectPath(volumeB, onA))).isEqualTo(untouchedB);

    flipLastByte(objectPath(volumeB, onB));
    byte[] untouchedA = Files.readAllBytes(objectPath(volumeA, onB));
    assertThatThrownBy(() -> store.put(request(onB, "hello")))
        .isInstanceOf(EvidenceCorruptionException.class);
    assertThatThrownBy(() -> store.put(request(onB, "other bytes")))
        .isInstanceOf(EvidenceCorruptionException.class);
    assertThat(Files.readAllBytes(objectPath(volumeA, onB))).isEqualTo(untouchedA);
  }

  @Test
  void aPutOfOtherBytesIsAnIntegrityAlarmWhenOnlyOneVolumeHoldsTheKey() throws IOException {
    // the state a crash between the volumes leaves, and a replaced disk before repair: one copy
    SealedEvidenceStore store = open();
    EvidenceKey onlyOnA = new EvidenceKey("landing/only-on-a");
    EvidenceKey onlyOnB = new EvidenceKey("landing/only-on-b");
    store.put(request(onlyOnA, "hello"));
    store.put(request(onlyOnB, "hello"));
    Files.delete(objectPath(volumeB, onlyOnA));
    Files.delete(objectPath(volumeA, onlyOnB));
    byte[] heldByA = Files.readAllBytes(objectPath(volumeA, onlyOnA));
    byte[] heldByB = Files.readAllBytes(objectPath(volumeB, onlyOnB));

    assertThatThrownBy(() -> store.put(request(onlyOnA, "other bytes")))
        .isInstanceOf(EvidenceIntegrityException.class)
        .isNotInstanceOf(EvidenceCorruptionException.class);
    assertThatThrownBy(() -> store.put(request(onlyOnB, "other bytes")))
        .isInstanceOf(EvidenceIntegrityException.class)
        .isNotInstanceOf(EvidenceCorruptionException.class);

    // nothing was acknowledged and nothing was written: the holder is as it was, the other empty
    assertThat(Files.readAllBytes(objectPath(volumeA, onlyOnA))).isEqualTo(heldByA);
    assertThat(Files.readAllBytes(objectPath(volumeB, onlyOnB))).isEqualTo(heldByB);
    assertThat(objectPath(volumeB, onlyOnA)).doesNotExist();
    assertThat(objectPath(volumeA, onlyOnB)).doesNotExist();
    assertThat(files(volumeA)).noneMatch(name -> name.contains("~tmp~"));
    assertThat(files(volumeB)).noneMatch(name -> name.contains("~tmp~"));
    // the same bytes still complete the missing copy
    assertThat(store.put(request(onlyOnA, "hello")))
        .isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThat(Files.readAllBytes(objectPath(volumeB, onlyOnA))).isEqualTo(heldByA);
  }

  @Test
  void aCorruptCopyIsAnAlarmOfItsOwnKindAndNeverAbsorbedByTheJournalWriter() throws IOException {
    SealedEvidenceStore store = open();
    JournalWriter writer = new JournalWriter(store, RetentionPolicy.fixedDays(1826));
    JournalObject object = transportResult(clock.now(), "http 202");
    assertThat(writer.write(object)).isEqualTo(JournalWriter.Outcome.CREATED);
    assertThat(writer.write(object)).isEqualTo(JournalWriter.Outcome.ALREADY_PRESENT_SAME_PAYLOAD);

    flipLastByte(objectPath(volumeA, object.key().evidenceKey()));

    // the same object and another one: neither is acknowledged as "already present"
    assertThatThrownBy(() -> writer.write(object)).isInstanceOf(EvidenceCorruptionException.class);
    assertThatThrownBy(() -> writer.write(transportResult(clock.now(), "http 500")))
        .isInstanceOf(EvidenceCorruptionException.class)
        .isInstanceOf(EvidenceIntegrityException.class);
    // the other copy is still served
    assertThat(writer.read(object.key())).contains(object);
  }

  @Test
  void aMediaTypeTooLongForTheHeaderIsRefusedAndStoresNothing() throws IOException {
    SealedEvidenceStore store = open();
    PutRequest request = new PutRequest(KEY, utf8("hello"), "x".repeat(70_000), retention());

    assertThatIllegalArgumentException().isThrownBy(() -> store.put(request));

    assertThat(files(volumeA)).containsExactly(".sealed-volume");
    assertThat(files(volumeB)).containsExactly(".sealed-volume");
    assertThat(store.get(KEY)).isEmpty();
    assertThat(store.list("", Optional.empty(), 10)).isEmpty();
  }

  @Test
  void aRetainUntilWithNanosecondsIsRefusedByPutBeforeAnyIo() throws IOException {
    RecordingFileOps recording = RecordingFileOps.over(FileOps.real());
    SealedEvidenceStore store = open(recording);
    recording.clear();
    PutRequest request =
        new PutRequest(
            KEY,
            utf8("hello"),
            "text/plain",
            new Retention(new RetentionClass("landing"), clock.now().plusNanos(1)));

    assertThatIllegalArgumentException().isThrownBy(() -> store.put(request));

    assertThat(recording.calls()).isEmpty();
    assertThat(files(volumeA)).containsExactly(".sealed-volume");
    assertThat(files(volumeB)).containsExactly(".sealed-volume");
  }

  @Test
  void aRetainUntilWithNanosecondsIsRefusedByExtendRetentionBeforeAnyIo() throws IOException {
    RecordingFileOps recording = RecordingFileOps.over(FileOps.real());
    SealedEvidenceStore store = open(recording);
    store.put(request(KEY, "hello"));
    recording.clear();

    assertThatIllegalArgumentException()
        .isThrownBy(() -> store.extendRetention(KEY, retention().retainUntil().plusNanos(1)))
        .withMessageContaining("microsecond");

    assertThat(recording.calls()).isEmpty();
    assertThat(volume(volumeA, "vol-a").readMeta(KEY)).isEmpty();
    assertThat(volume(volumeB, "vol-b").readMeta(KEY)).isEmpty();
  }

  // ---------------------------------------------------------------- grade and durability

  @Test
  void durabilityDeclaresOneDomainForTwoDirectoriesOnOneDeviceAndTwoWhenTheProbeSaysSo() {
    VolumeProbe oneDevice = path -> "one device";
    VolumeProbe twoDevices = path -> path.getFileName().toString();

    assertThat(openWith(oneDevice).durability()).isEqualTo(new Durability(true, 1));
    assertThat(openWith(twoDevices).durability()).isEqualTo(new Durability(true, 2));
  }

  @Test
  void aProbeThatFailsCountsOneDomain() {
    VolumeProbe failing =
        path -> {
          throw new IOException("injected");
        };

    assertThat(openWith(failing).durability()).isEqualTo(new Durability(true, 1));
  }

  @Test
  void twoDirectoriesOfOneFileSystemAreOneDomain() throws IOException {
    SealedEvidenceStore store = open();

    assertThat(VolumeProbe.fileStores().domainOf(volumeA))
        .isEqualTo(VolumeProbe.fileStores().domainOf(volumeB));
    assertThat(store.durability()).isEqualTo(new Durability(true, 1));
  }

  @Test
  void aDegradedStoreDeclaresNoSynchronousAcknowledgement() throws IOException {
    SealedEvidenceStore healthy = open();
    healthy.put(request(KEY, "hello"));
    assertThat(healthy.degraded()).isFalse();
    deleteTree(volumeB);

    SealedEvidenceStore degraded = open();

    assertThat(degraded.degraded()).isTrue();
    assertThat(degraded.durability()).isEqualTo(new Durability(false, 1));
    assertThat(degraded.grade()).isEqualTo(new Grade("P-SEALED", ""));
    assertThat(degraded.get(KEY).orElseThrow().bytes()).isEqualTo(utf8("hello"));
    assertThatThrownBy(() -> degraded.put(request(new EvidenceKey("landing/k2"), "second")))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessageContaining("vol-b");
  }

  @Test
  void gradeIsPSealedWithAnEmptyMode() {
    assertThat(open().grade()).isEqualTo(new Grade("P-SEALED", ""));
  }

  // ---------------------------------------------------------------- retention and legal hold

  @Test
  void retentionAndHoldAreWrittenToBothVolumes() throws IOException {
    SealedEvidenceStore store = open();
    store.put(request(KEY, "hello"));
    Instant later = retention().retainUntil().plus(Duration.ofDays(365));

    store.extendRetention(KEY, later);
    store.setLegalHold(KEY, true);

    SealedFormat.Meta onA = volume(volumeA, "vol-a").readMeta(KEY).orElseThrow();
    SealedFormat.Meta onB = volume(volumeB, "vol-b").readMeta(KEY).orElseThrow();
    assertThat(onA).isEqualTo(onB);
    assertThat(onA.legalHold()).isTrue();
    assertThat(onA.retainUntil()).isEqualTo(later);
    assertThat(onA.retentionClass()).isEqualTo("landing");
    assertThat(onA.rev()).isEqualTo(2);
    StoredObject got = store.get(KEY).orElseThrow();
    assertThat(got.legalHold()).isTrue();
    assertThat(got.retention().retainUntil()).isEqualTo(later);
  }

  @Test
  void aHigherMetaRevisionWins() throws IOException {
    SealedEvidenceStore store = open();
    store.put(request(KEY, "hello"));
    Instant base = retention().retainUntil();
    SealedVolume onA = volume(volumeA, "vol-a");
    SealedVolume onB = volume(volumeB, "vol-b");

    // revision 4 on b releases the hold that revision 3 on a set; retention is the maximum
    assertThat(onA.writeMeta(KEY, new SealedFormat.Meta(3, "landing", base.plusSeconds(100), true)))
        .isTrue();
    assertThat(onB.writeMeta(KEY, new SealedFormat.Meta(4, "landing", base.plusSeconds(50), false)))
        .isTrue();
    StoredObject got = store.get(KEY).orElseThrow();
    assertThat(got.legalHold()).isFalse();
    assertThat(got.retention().retainUntil()).isEqualTo(base.plusSeconds(100));

    // two metas of one revision merge: the later retention and the hold of either
    assertThat(onA.writeMeta(KEY, new SealedFormat.Meta(5, "landing", base.plusSeconds(200), true)))
        .isTrue();
    assertThat(
            onB.writeMeta(KEY, new SealedFormat.Meta(5, "landing", base.plusSeconds(150), false)))
        .isTrue();
    got = store.get(KEY).orElseThrow();
    assertThat(got.legalHold()).isTrue();
    assertThat(got.retention().retainUntil()).isEqualTo(base.plusSeconds(200));
  }

  @Test
  void aRetentionExtensionThatFailsOnTheSecondVolumeIsCompletedByTheSameCallAgain()
      throws IOException {
    FailingFileOps failing = new FailingFileOps(FileOps.real());
    SealedEvidenceStore store = open(failing);
    store.put(request(KEY, "hello"));
    Instant later = retention().retainUntil().plus(Duration.ofDays(365));
    failing.failNext(Op.REPLACE, path -> path.startsWith(volumeB));

    assertThatThrownBy(() -> store.extendRetention(KEY, later))
        .isInstanceOf(EvidenceUnavailableException.class);

    // volume a holds the new retention, volume b no meta: the call was not acknowledged
    assertThat(volume(volumeA, "vol-a").readMeta(KEY).orElseThrow().retainUntil()).isEqualTo(later);
    assertThat(volume(volumeB, "vol-b").readMeta(KEY)).isEmpty();
    assertThat(files(volumeB)).noneMatch(name -> name.contains("~tmp~"));

    // the same call is not a no-op while the volumes differ: it completes volume b
    store.extendRetention(KEY, later);
    Optional<SealedFormat.Meta> onA = volume(volumeA, "vol-a").readMeta(KEY);
    assertThat(volume(volumeB, "vol-b").readMeta(KEY)).isEqualTo(onA);
    assertThat(onA.orElseThrow().retainUntil()).isEqualTo(later);

    // and once the volumes agree it is one: no new revision
    long revision = onA.orElseThrow().rev();
    store.extendRetention(KEY, later);
    assertThat(volume(volumeA, "vol-a").readMeta(KEY).orElseThrow().rev()).isEqualTo(revision);
    assertThat(volume(volumeB, "vol-b").readMeta(KEY).orElseThrow().rev()).isEqualTo(revision);
  }

  @Test
  void aLegalHoldThatFailsOnTheSecondVolumeIsCompletedByTheSameCallAgain() throws IOException {
    FailingFileOps failing = new FailingFileOps(FileOps.real());
    SealedEvidenceStore store = open(failing);
    store.put(request(KEY, "hello"));
    failing.failNext(Op.REPLACE, path -> path.startsWith(volumeB));

    assertThatThrownBy(() -> store.setLegalHold(KEY, true))
        .isInstanceOf(EvidenceUnavailableException.class);

    assertThat(volume(volumeA, "vol-a").readMeta(KEY).orElseThrow().legalHold()).isTrue();
    assertThat(volume(volumeB, "vol-b").readMeta(KEY)).isEmpty();
    assertThat(files(volumeB)).noneMatch(name -> name.contains("~tmp~"));

    // a hold that exists on one volume only is lost with that volume: the retry must write b
    store.setLegalHold(KEY, true);
    Optional<SealedFormat.Meta> onA = volume(volumeA, "vol-a").readMeta(KEY);
    assertThat(volume(volumeB, "vol-b").readMeta(KEY)).isEqualTo(onA);
    assertThat(onA.orElseThrow().legalHold()).isTrue();

    long revision = onA.orElseThrow().rev();
    store.setLegalHold(KEY, true);
    assertThat(volume(volumeA, "vol-a").readMeta(KEY).orElseThrow().rev()).isEqualTo(revision);
    assertThat(volume(volumeB, "vol-b").readMeta(KEY).orElseThrow().rev()).isEqualTo(revision);
  }

  @Test
  void aMetaThatLosesARaceForTheSecondVolumeIsRecomputedFromTheHigherRevision() throws IOException {
    Instant base = retention().retainUntil();
    Instant later = base.plus(Duration.ofDays(365));
    SealedVolume onB = volume(volumeB, "vol-b");
    // another writer puts revision 5 (a hold, and a retention below the one asked for) on b
    // after this call has chosen revision 1 and before volume b is written
    BeforeMetaReplace race =
        new BeforeMetaReplace(
            FileOps.real(),
            volumeA,
            1,
            firing ->
                assertThat(
                        onB.writeMeta(
                            KEY,
                            new SealedFormat.Meta(
                                5, "landing", base.plus(Duration.ofDays(10)), true)))
                    .isTrue());
    SealedEvidenceStore store = open(race);
    store.put(request(KEY, "hello"));

    store.extendRetention(KEY, later);

    assertThat(race.fired()).isEqualTo(1);
    SealedFormat.Meta metaOfA = volume(volumeA, "vol-a").readMeta(KEY).orElseThrow();
    assertThat(volume(volumeB, "vol-b").readMeta(KEY)).contains(metaOfA);
    assertThat(metaOfA.rev()).isEqualTo(6);
    assertThat(metaOfA.retainUntil()).isEqualTo(later);
    assertThat(metaOfA.legalHold()).isTrue(); // the other writer's hold is not lost
    StoredObject got = store.get(KEY).orElseThrow();
    assertThat(got.legalHold()).isTrue();
    assertThat(got.retention().retainUntil()).isEqualTo(later);
  }

  @Test
  void aHoldThatLosesARaceForTheSecondVolumeIsRecomputedFromTheHigherRevision() throws IOException {
    Instant base = retention().retainUntil();
    Instant injected = base.plus(Duration.ofDays(10));
    SealedVolume onB = volume(volumeB, "vol-b");
    BeforeMetaReplace race =
        new BeforeMetaReplace(
            FileOps.real(),
            volumeA,
            1,
            firing ->
                assertThat(onB.writeMeta(KEY, new SealedFormat.Meta(5, "landing", injected, false)))
                    .isTrue());
    SealedEvidenceStore store = open(race);
    store.put(request(KEY, "hello"));

    store.setLegalHold(KEY, true);

    assertThat(race.fired()).isEqualTo(1);
    SealedFormat.Meta metaOfA = volume(volumeA, "vol-a").readMeta(KEY).orElseThrow();
    assertThat(volume(volumeB, "vol-b").readMeta(KEY)).contains(metaOfA);
    assertThat(metaOfA.rev()).isEqualTo(6);
    assertThat(metaOfA.legalHold()).isTrue();
    assertThat(metaOfA.retainUntil()).isEqualTo(injected); // the other writer's retention is kept
  }

  /** A writer that always gets in first: revision 10, 20, 30... lands on b before each try ends. */
  private BeforeMetaReplace alwaysOvertaken() {
    SealedVolume onB = volume(volumeB, "vol-b");
    Instant base = retention().retainUntil();
    return new BeforeMetaReplace(
        FileOps.real(),
        volumeA,
        Integer.MAX_VALUE,
        firing ->
            assertThat(
                    onB.writeMeta(KEY, new SealedFormat.Meta(10L * firing, "landing", base, false)))
                .isTrue());
  }

  @Test
  void aRetentionExtensionThatIsOvertakenOnEveryTryIsNotAcknowledged() throws IOException {
    BeforeMetaReplace race = alwaysOvertaken();
    SealedEvidenceStore store = open(race);
    store.put(request(KEY, "hello"));

    assertThatThrownBy(
            () -> store.extendRetention(KEY, retention().retainUntil().plus(Duration.ofDays(1))))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessageContaining(KEY.path());

    assertThat(race.fired()).isEqualTo(3); // three tries, then the alarm
  }

  @Test
  void aLegalHoldThatIsOvertakenOnEveryTryIsNotAcknowledged() throws IOException {
    BeforeMetaReplace race = alwaysOvertaken();
    SealedEvidenceStore store = open(race);
    store.put(request(KEY, "hello"));

    assertThatThrownBy(() -> store.setLegalHold(KEY, true))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessageContaining(KEY.path());

    assertThat(race.fired()).isEqualTo(3);
  }

  // ---------------------------------------------------------------- reads with a volume failing

  private void getServesWhileOneVolumeFailsEveryRead(Path failedVolume) throws IOException {
    FailingFileOps failing = new FailingFileOps(FileOps.real());
    SealedEvidenceStore store = open(failing);
    Instant later = retention().retainUntil().plus(Duration.ofDays(365));
    store.put(request(KEY, "hello"));
    store.extendRetention(KEY, later);
    store.setLegalHold(KEY, true);
    failing.failAlways(Op.READ, path -> path.startsWith(failedVolume));

    StoredObject got = store.get(KEY).orElseThrow();

    assertThat(got.bytes()).isEqualTo(utf8("hello"));
    assertThat(got.legalHold()).isTrue();
    assertThat(got.retention().retainUntil()).isEqualTo(later);
    // a key the surviving volume does not hold cannot be called absent while the other is dark
    assertThatThrownBy(() -> store.get(new EvidenceKey("landing/never-put")))
        .isInstanceOf(EvidenceUnavailableException.class);
  }

  @Test
  void getStaysAvailableWhenTheFirstVolumeFailsEveryRead() throws IOException {
    getServesWhileOneVolumeFailsEveryRead(volumeA);
  }

  @Test
  void getStaysAvailableWhenTheSecondVolumeFailsEveryRead() throws IOException {
    getServesWhileOneVolumeFailsEveryRead(volumeB);
  }

  @Test
  void getFailsClosedWhenNeitherRetentionRecordCanBeRead() throws IOException {
    FailingFileOps failing = new FailingFileOps(FileOps.real());
    SealedEvidenceStore store = open(failing);
    store.put(request(KEY, "hello"));
    store.setLegalHold(KEY, true);
    failing.failAlways(Op.READ, path -> path.getFileName().toString().startsWith("m-"));

    // the object copy is readable, but the header alone would answer: no hold, weaker retention
    assertThatThrownBy(() -> store.get(KEY))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessageContaining(KEY.path());
  }

  // ---------------------------------------------------------------- a damaged meta

  @Test
  void aDamagedMetaOnOneVolumeIsServedAroundAndReportedByScrub() throws IOException {
    SealedEvidenceStore store = open();
    store.put(request(KEY, "hello"));
    store.setLegalHold(KEY, true);

    damageMeta(volumeA, KEY);

    StoredObject got = store.get(KEY).orElseThrow();
    assertThat(got.legalHold()).isTrue(); // from the valid meta on b
    assertThat(store.scrub()).containsExactly("vol-a landing/k1 CORRUPT_META");

    damageMeta(volumeB, KEY);
    assertThatThrownBy(() -> store.get(KEY)).isInstanceOf(EvidenceCorruptionException.class);
    assertThat(store.scrub())
        .containsExactly("vol-a landing/k1 CORRUPT_META", "vol-b landing/k1 CORRUPT_META");
  }

  @Test
  void aDamagedMetaWithNoValidMetaOnTheOtherVolumeIsAnAlarmOnGet() throws IOException {
    SealedEvidenceStore store = open();
    store.put(request(KEY, "hello"));
    store.setLegalHold(KEY, true);
    damageMeta(volumeA, KEY);
    Files.delete(metaPath(volumeB, KEY));

    assertThatThrownBy(() -> store.get(KEY)).isInstanceOf(EvidenceCorruptionException.class);
  }

  @Test
  void aDamagedMetaOnEitherVolumeRefusesAnExtensionAndAHoldAndChangesNothing() throws IOException {
    SealedEvidenceStore store = open();
    EvidenceKey damagedOnA = new EvidenceKey("landing/meta-damaged-on-a");
    EvidenceKey damagedOnB = new EvidenceKey("landing/meta-damaged-on-b");
    Instant later = retention().retainUntil().plus(Duration.ofDays(365));
    for (EvidenceKey key : List.of(damagedOnA, damagedOnB)) {
      store.put(request(key, "hello"));
      store.setLegalHold(key, true);
    }
    damageMeta(volumeA, damagedOnA);
    damageMeta(volumeB, damagedOnB);
    byte[] damagedFileA = Files.readAllBytes(metaPath(volumeA, damagedOnA));
    byte[] validFileB = Files.readAllBytes(metaPath(volumeB, damagedOnA));
    byte[] validFileA = Files.readAllBytes(metaPath(volumeA, damagedOnB));
    byte[] damagedFileB = Files.readAllBytes(metaPath(volumeB, damagedOnB));

    for (EvidenceKey key : List.of(damagedOnA, damagedOnB)) {
      assertThatThrownBy(() -> store.extendRetention(key, later))
          .isInstanceOf(EvidenceCorruptionException.class);
      assertThatThrownBy(() -> store.setLegalHold(key, false))
          .isInstanceOf(EvidenceCorruptionException.class);
    }

    assertThat(Files.readAllBytes(metaPath(volumeA, damagedOnA))).isEqualTo(damagedFileA);
    assertThat(Files.readAllBytes(metaPath(volumeB, damagedOnA))).isEqualTo(validFileB);
    assertThat(Files.readAllBytes(metaPath(volumeA, damagedOnB))).isEqualTo(validFileA);
    assertThat(Files.readAllBytes(metaPath(volumeB, damagedOnB))).isEqualTo(damagedFileB);
  }

  // ---------------------------------------------------------------- open

  @Test
  void aNewStoreMarksBothVolumes() throws IOException {
    SealedEvidenceStore store = open();

    assertThat(store.degraded()).isFalse();
    assertThat(volume(volumeA, "vol-a").markerId()).contains("vol-a");
    assertThat(volume(volumeB, "vol-b").markerId()).contains("vol-b");
  }

  @Test
  void openRefusesTwoRootsThatOverlap() throws IOException {
    Files.createDirectories(volumeA.resolve("inner"));
    List<SealedEvidenceStore.Config> overlapping =
        List.of(
            new SealedEvidenceStore.Config(
                volumeA, volumeA.resolve("inner"), "vol-a", "vol-b", clock),
            new SealedEvidenceStore.Config(
                volumeA.resolve("inner"), volumeA, "vol-a", "vol-b", clock),
            new SealedEvidenceStore.Config(volumeA, volumeA, "vol-a", "vol-b", clock),
            new SealedEvidenceStore.Config(
                volumeA, volumeA.resolve("inner").resolve(".."), "vol-a", "vol-b", clock));
    for (SealedEvidenceStore.Config config : overlapping) {
      assertThatIllegalArgumentException().isThrownBy(() -> SealedEvidenceStore.open(config));
    }
  }

  @Test
  void openRefusesTwoRootsThatReachOneDirectoryThroughALink() throws IOException {
    Files.createDirectories(volumeA);
    Path link = volumeA.resolveSibling("link-to-a");
    Assumptions.assumeTrue(
        createLink(link, volumeA),
        "this account can create neither a symbolic link nor a junction");

    SealedEvidenceStore.Config config =
        new SealedEvidenceStore.Config(volumeA, link, "vol-a", "vol-b", clock);

    assertThatIllegalArgumentException().isThrownBy(() -> SealedEvidenceStore.open(config));
  }

  @Test
  void aVolumeThatCannotHardLinkRefusesToOpen() {
    FailingFileOps failing = new FailingFileOps(FileOps.real());
    failing.failNextAsUnsupported(Op.LINK);

    assertThatThrownBy(() -> open(failing)).isInstanceOf(EvidenceUnavailableException.class);
  }

  @Test
  void twoUnmarkedVolumesThatHoldObjectsRefuseToOpen() throws IOException {
    open().put(request(KEY, "hello"));
    Files.delete(volumeA.resolve(".sealed-volume"));
    Files.delete(volumeB.resolve(".sealed-volume"));

    assertThatThrownBy(this::open).isInstanceOf(EvidenceUnavailableException.class);
  }

  @Test
  void openSweepsTheTemporaryFilesOfDeadProcesses() throws IOException {
    open().put(request(KEY, "hello"));
    Path leftover =
        objectPath(volumeA, KEY)
            .resolveSibling(SealedNames.temporary("o-left-over", Long.MAX_VALUE, 1));
    Files.write(leftover, utf8("a crashed writer"));

    SealedEvidenceStore store = open();

    assertThat(leftover).doesNotExist();
    assertThat(store.list("", Optional.empty(), 10)).containsExactly(KEY);
  }

  // ---------------------------------------------------------------- reads, list, repair, scrub

  @Test
  void getServesTheValidCopyAndRefusesWhenNoCopyIsValid() throws IOException {
    SealedEvidenceStore store = open();
    store.put(request(KEY, "hello"));

    flipLastByte(objectPath(volumeA, KEY));
    assertThat(store.get(KEY).orElseThrow().bytes()).isEqualTo(utf8("hello"));

    flipLastByte(objectPath(volumeB, KEY));
    assertThatThrownBy(() -> store.get(KEY)).isInstanceOf(EvidenceCorruptionException.class);

    // one copy corrupt and the other missing is an alarm too
    Files.delete(objectPath(volumeB, KEY));
    assertThatThrownBy(() -> store.get(KEY)).isInstanceOf(EvidenceCorruptionException.class);
  }

  @Test
  void listIsTheUnionOfBothVolumesInCodeUnitOrder() throws IOException {
    SealedEvidenceStore store = open();
    EvidenceKey onBoth = new EvidenceKey("a/b");
    EvidenceKey onlyOnA = new EvidenceKey("a/B");
    EvidenceKey onlyOnB = new EvidenceKey("a/c");
    store.put(request(onBoth, "v"));
    store.put(request(onlyOnA, "v"));
    store.put(request(onlyOnB, "v"));
    Files.delete(objectPath(volumeB, onlyOnA));
    Files.delete(objectPath(volumeA, onlyOnB));

    assertThat(store.list("a/", Optional.empty(), 10)).containsExactly(onlyOnA, onBoth, onlyOnB);
    assertThat(store.list("a/", Optional.of(onlyOnA), 1)).containsExactly(onBoth);
  }

  @Test
  void repairCopiesWhatIsMissingAndMarksTheReplacedVolume() throws IOException {
    SealedEvidenceStore store = open();
    EvidenceKey second = new EvidenceKey("landing/k2");
    EvidenceKey third = new EvidenceKey("other/k3");
    store.put(request(KEY, "one"));
    store.put(request(second, "two"));
    store.put(request(third, "three"));
    store.setLegalHold(second, true);
    deleteTree(volumeB);
    SealedEvidenceStore replaced = open();
    assertThat(replaced.degraded()).isTrue();

    assertThat(replaced.repair()).isEqualTo(3);

    assertThat(replaced.degraded()).isFalse();
    assertThat(replaced.durability().synchronousAcknowledgement()).isTrue();
    for (EvidenceKey key : List.of(KEY, second, third)) {
      assertThat(Files.readAllBytes(objectPath(volumeB, key)))
          .isEqualTo(Files.readAllBytes(objectPath(volumeA, key)));
    }
    assertThat(volume(volumeB, "vol-b").readMeta(second))
        .isEqualTo(volume(volumeA, "vol-a").readMeta(second));
    assertThat(replaced.repair()).isZero();
    assertThat(replaced.put(request(new EvidenceKey("landing/k4"), "four")))
        .isEqualTo(PutOutcome.CREATED);
  }

  @Test
  void scrubIsEmptyOnACleanStoreAndNamesWhatIsDamaged() throws IOException {
    SealedEvidenceStore store = open();
    EvidenceKey second = new EvidenceKey("landing/k2");
    EvidenceKey third = new EvidenceKey("landing/k3");
    store.put(request(KEY, "one"));
    store.put(request(second, "two"));
    store.put(request(third, "three"));
    assertThat(store.scrub()).isEmpty();

    flipLastByte(objectPath(volumeA, KEY));
    Files.delete(objectPath(volumeB, second));
    SealedFormat.Header header =
        SealedFormat.decodeObject(Files.readAllBytes(objectPath(volumeA, third)), third.path())
            .header();
    byte[] other = utf8("a different payload");
    Files.delete(objectPath(volumeB, third));
    Files.write(
        objectPath(volumeB, third),
        SealedFormat.encodeObject(
            new SealedFormat.Header(
                header.key(),
                header.mediaType(),
                Sha256.of(other),
                other.length,
                header.writtenAt(),
                header.retentionClass(),
                header.retainUntil()),
            other));

    assertThat(store.scrub())
        .containsExactly(
            "vol-a landing/k1 CORRUPT", "vol-b landing/k2 MISSING_COPY", "landing/k3 DIVERGENT");
  }

  // ---------------------------------------------------------------- the stated limits

  @Test
  void limitsNameTheUnverifiedClaims() {
    String limits = open().limits();

    assertThat(limits)
        .contains("SP-W has not been run")
        .contains("Directory durability")
        .contains("on Windows the JDK cannot force a directory")
        .contains("power loss")
        .contains("SMB")
        .contains("The list call")
        .contains("file-store identity")
        .contains("attested in the G8 record")
        .contains("plaintext")
        .contains("must not receive production data before P3c")
        .contains("access control list")
        .contains("a shortened or deleted meta is not detected")
        .contains("both volumes already hold")
        .contains("temporary file of the full object size");
  }
}
