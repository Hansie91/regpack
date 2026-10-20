package dev.regpack.evidence.sealed;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.evidence.sealed.RecordingFileOps.Op;
import dev.regpack.model.Sha256;
import dev.regpack.ports.evidence.EvidenceCorruptionException;
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceRetentionException;
import dev.regpack.ports.evidence.EvidenceStore.Durability;
import dev.regpack.ports.evidence.EvidenceStore.PutOutcome;
import dev.regpack.ports.evidence.EvidenceStore.PutRequest;
import dev.regpack.ports.evidence.EvidenceStore.Retention;
import dev.regpack.ports.evidence.EvidenceStore.RetentionClass;
import dev.regpack.ports.evidence.EvidenceStore.StoredObject;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import dev.regpack.testkit.clock.ManualClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T8: P-SEALED under faults, loss and concurrency (review focus EF8, EF10, EF11, EF27). The two
 * volumes are called {@code a} and {@code b}. Every test works in a {@code @TempDir} and damages
 * the volumes through {@link SealedTamper} or through {@link SealedVolume} directly, never through
 * the store under test.
 */
class SealedFaultTest {
  private static final EvidenceKey KEY = new EvidenceKey("landing/k1");
  private static final long DEAD_PID = Long.MAX_VALUE;
  private static final String MARKER = ".sealed-volume";

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
    return new SealedEvidenceStore.Config(volumeA, volumeB, "a", "b", clock);
  }

  private SealedEvidenceStore open() {
    return SealedEvidenceStore.open(config());
  }

  private SealedEvidenceStore open(FileOps ops) {
    return SealedEvidenceStore.open(config(), ops);
  }

  private static SealedVolume volume(Path volumeRoot, String id) {
    return new SealedVolume(volumeRoot, id, FileOps.real());
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

  /** Every regular file below {@code dir} (relative, '/') with the SHA-256 of its bytes. */
  private static Map<String, String> digests(Path dir) throws IOException {
    Map<String, String> found = new TreeMap<>();
    if (!Files.isDirectory(dir)) {
      return found;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path file : walk.filter(Files::isRegularFile).toList()) {
        found.put(
            dir.relativize(file).toString().replace('\\', '/'),
            Sha256.of(Files.readAllBytes(file)).hex());
      }
    }
    return found;
  }

  /** Both volumes, every file of each, under "a:" and "b:". */
  private Map<String, String> bothVolumes() throws IOException {
    Map<String, String> all = new TreeMap<>();
    digests(volumeA).forEach((name, digest) -> all.put("a:" + name, digest));
    digests(volumeB).forEach((name, digest) -> all.put("b:" + name, digest));
    return all;
  }

  private static Map<String, String> withoutMarker(Map<String, String> files) {
    Map<String, String> copy = new TreeMap<>(files);
    copy.remove(MARKER);
    return copy;
  }

  private static List<Path> temporaryFiles(Path dir) throws IOException {
    try (Stream<Path> walk = Files.walk(dir)) {
      return walk.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().contains("~tmp~"))
          .toList();
    }
  }

  private static void deleteTree(Path directory) throws IOException {
    try (Stream<Path> walk = Files.walk(directory)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
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
        results.add(future.get(120, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  private static byte[] withByteFlipped(byte[] file, int position) {
    byte[] copy = file.clone();
    copy[position] ^= 0x01;
    return copy;
  }

  // ---------------------------------------------------------------- losing a volume

  @Test
  void loseOneVolumeThenReadRepairAndWriteAgain() throws IOException {
    SealedEvidenceStore store = open();
    List<EvidenceKey> keys = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      EvidenceKey key =
          new EvidenceKey("batch/b" + (i % 5) + "/object-" + String.format(Locale.ROOT, "%03d", i));
      keys.add(key);
      Retention retention =
          new Retention(new RetentionClass("landing"), clock.now().plus(Duration.ofDays(1000 + i)));
      store.put(new PutRequest(key, utf8("payload " + i), "text/plain", retention));
      if (i % 10 == 3) {
        store.extendRetention(key, retention.retainUntil().plus(Duration.ofDays(500)));
      }
    }
    store.setLegalHold(keys.get(7), true);
    List<EvidenceKey> sorted =
        keys.stream().sorted(Comparator.comparing(EvidenceKey::path)).toList();
    Map<EvidenceKey, StoredObject> expected =
        new TreeMap<>(Comparator.comparing(EvidenceKey::path));
    for (EvidenceKey key : keys) {
      expected.put(key, store.get(key).orElseThrow());
    }
    assertThat(expected.get(keys.get(7)).legalHold()).isTrue();

    // the disk of volume b is lost: the directory is gone, a new empty one is mounted
    deleteTree(volumeB);
    SealedEvidenceStore replaced = open();

    assertThat(replaced.degraded()).isTrue();
    assertThat(replaced.durability()).isEqualTo(new Durability(false, 1));
    for (EvidenceKey key : keys) {
      assertThat(replaced.get(key)).as(key.path()).contains(expected.get(key));
    }
    assertThat(replaced.list("", Optional.empty(), 100)).isEqualTo(sorted);
    assertThat(replaced.list("batch/b2/", Optional.empty(), 100))
        .isEqualTo(sorted.stream().filter(key -> key.path().startsWith("batch/b2/")).toList());
    // nothing is written while a volume is missing, and the message names the volume
    assertThatThrownBy(() -> replaced.put(request(new EvidenceKey("landing/new"), "new")))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessageContaining("volume b");
    assertThatThrownBy(
            () -> replaced.extendRetention(keys.get(0), Instant.parse("2099-01-01T00:00:00Z")))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessageContaining("volume b");
    assertThatThrownBy(() -> replaced.setLegalHold(keys.get(1), true))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessageContaining("volume b");
    assertThat(digests(volumeB)).as("nothing is written to the new volume before repair").isEmpty();

    assertThat(replaced.repair()).isEqualTo(50);

    assertThat(replaced.degraded()).isFalse();
    assertThat(replaced.durability().synchronousAcknowledgement()).isTrue();
    assertThat(Files.readString(volumeA.resolve(MARKER))).isEqualTo("a");
    assertThat(Files.readString(volumeB.resolve(MARKER))).isEqualTo("b");
    Map<String, String> onA = withoutMarker(digests(volumeA));
    Map<String, String> onB = withoutMarker(digests(volumeB));
    assertThat(onB).isEqualTo(onA);
    assertThat(onA).hasSize(50 + 6); // 50 objects, 5 extended retentions and 1 hold as metas
    assertThat(onA.keySet()).noneMatch(name -> name.contains("~tmp~"));
    // volume b alone now holds everything: lose volume a as well and read from b
    deleteTree(volumeA);
    SealedEvidenceStore onlyB = open();
    assertThat(onlyB.degraded()).isTrue();
    for (EvidenceKey key : keys) {
      assertThat(onlyB.get(key)).as(key.path()).contains(expected.get(key));
    }
    assertThat(onlyB.list("", Optional.empty(), 100)).isEqualTo(sorted);
    assertThat(onlyB.repair()).isEqualTo(50);
    // and a write works again on the repaired pair
    assertThat(onlyB.put(request(new EvidenceKey("landing/new"), "new")))
        .isEqualTo(PutOutcome.CREATED);
    assertThat(SealedTamper.objectPath(volumeA, new EvidenceKey("landing/new"))).exists();
    assertThat(SealedTamper.objectPath(volumeB, new EvidenceKey("landing/new"))).exists();
    assertThat(withoutMarker(digests(volumeA))).isEqualTo(withoutMarker(digests(volumeB)));
  }

  @Test
  void aMismatchedVolumeIdRefusesToOpen() throws IOException {
    SealedEvidenceStore store = open();
    store.put(request(KEY, "hello"));
    Map<String, String> before = bothVolumes();

    List<SealedEvidenceStore.Config> wrong =
        List.of(
            // other ids over the same directories
            new SealedEvidenceStore.Config(volumeA, volumeB, "x", "y", clock),
            // one id wrong
            new SealedEvidenceStore.Config(volumeA, volumeB, "a", "y", clock),
            new SealedEvidenceStore.Config(volumeA, volumeB, "x", "b", clock),
            // the ids exchanged
            new SealedEvidenceStore.Config(volumeA, volumeB, "b", "a", clock),
            // the directories exchanged
            new SealedEvidenceStore.Config(volumeB, volumeA, "a", "b", clock));
    for (SealedEvidenceStore.Config config : wrong) {
      assertThatThrownBy(() -> SealedEvidenceStore.open(config))
          .as("%s / %s", config.volumeIdA(), config.volumeIdB())
          .isInstanceOf(EvidenceUnavailableException.class)
          .hasMessageContaining("another volume");
    }

    // a refusal writes nothing and the right configuration still opens
    assertThat(bothVolumes()).isEqualTo(before);
    SealedEvidenceStore again = open();
    assertThat(again.degraded()).isFalse();
    assertThat(again.get(KEY).orElseThrow().bytes()).isEqualTo(utf8("hello"));
  }

  // ---------------------------------------------------------------- damaged copies

  @Test
  void aCorruptedCopyIsDetectedByHashAndServedFromTheOtherVolume() throws IOException {
    SealedEvidenceStore store = open();
    store.put(request(KEY, "the payload of the object"));
    StoredObject expected = store.get(KEY).orElseThrow();
    byte[] original = Files.readAllBytes(SealedTamper.objectPath(volumeA, KEY));

    // the seal names the fault: a flipped payload byte is a hash mismatch
    assertThatThrownBy(
            () ->
                SealedFormat.decodeObject(
                    withByteFlipped(original, original.length - 1), KEY.path()))
        .isInstanceOfSatisfying(
            SealedFormat.CorruptException.class,
            e -> assertThat(e.reason()).isEqualTo(SealedFormat.Reason.HASH_MISMATCH));

    Map<String, byte[]> damages = new TreeMap<>();
    damages.put("payload byte flipped", withByteFlipped(original, original.length - 1));
    damages.put("first payload byte flipped", withByteFlipped(original, original.length - 25));
    damages.put("header byte flipped", withByteFlipped(original, 20));
    damages.put("magic flipped", withByteFlipped(original, 0));
    damages.put("cut in half", Arrays.copyOf(original, original.length / 2));
    damages.put("last byte missing", Arrays.copyOf(original, original.length - 1));
    damages.put("a byte appended", Arrays.copyOf(original, original.length + 1));
    damages.put("empty file", new byte[0]);
    for (Map.Entry<String, byte[]> damage : damages.entrySet()) {
      assertThatThrownBy(() -> SealedFormat.decodeObject(damage.getValue(), KEY.path()))
          .as(damage.getKey())
          .isInstanceOf(SealedFormat.CorruptException.class);
      for (String damaged : List.of("a", "b")) {
        Path volumeRoot = damaged.equals("a") ? volumeA : volumeB;
        SealedTamper.replaceObject(volumeRoot, KEY, damage.getValue());

        assertThat(store.get(KEY)).as("%s on %s", damage.getKey(), damaged).contains(expected);
        assertThat(store.list("", Optional.empty(), 10)).containsExactly(KEY);
        assertThat(store.scrub()).containsExactly(damaged + " landing/k1 CORRUPT");

        SealedTamper.replaceObject(volumeRoot, KEY, original);
        assertThat(store.scrub()).isEmpty();
      }
    }
  }

  @Test
  void bothCopiesCorruptIsAnIntegrityAlarm() throws IOException {
    SealedEvidenceStore store = open();
    store.put(request(KEY, "hello"));
    byte[] original = Files.readAllBytes(SealedTamper.objectPath(volumeA, KEY));

    SealedTamper.flipByte(volumeA, KEY);
    SealedTamper.replaceObject(volumeB, KEY, Arrays.copyOf(original, original.length / 2));

    assertThatThrownBy(() -> store.get(KEY))
        .isInstanceOf(EvidenceCorruptionException.class)
        .isInstanceOf(EvidenceIntegrityException.class)
        .hasMessageContaining(KEY.path());
    assertThatThrownBy(() -> store.put(request(KEY, "hello")))
        .isInstanceOf(EvidenceCorruptionException.class);
    // the key is still listed (a damaged object is not a missing one) and scrub names both copies
    assertThat(store.list("", Optional.empty(), 10)).containsExactly(KEY);
    assertThat(store.scrub()).containsExactly("a landing/k1 CORRUPT", "b landing/k1 CORRUPT");
    // repair copies from neither
    assertThat(store.repair()).isZero();
    assertThatThrownBy(() -> store.get(KEY)).isInstanceOf(EvidenceCorruptionException.class);
  }

  // ---------------------------------------------------------------- concurrency

  private record Attempt(byte[] payload, PutOutcome outcome, Throwable failure) {}

  @Test
  void twoStoreInstancesOverTheSameDirectoriesAgreeOnExactlyOneWinner() throws Exception {
    SealedEvidenceStore first = open();
    SealedEvidenceStore second = open();
    List<Callable<Attempt>> tasks = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      SealedEvidenceStore store = i % 2 == 0 ? first : second;
      byte[] payload = utf8("writer " + i + " " + "x".repeat(i));
      PutRequest request = new PutRequest(KEY, payload, "text/plain", retention());
      tasks.add(
          () -> {
            try {
              return new Attempt(payload, store.put(request), null);
            } catch (EvidenceIntegrityException e) {
              return new Attempt(payload, null, e);
            }
          });
    }

    List<Attempt> attempts = runTogether(tasks);

    List<Attempt> created =
        attempts.stream().filter(a -> a.outcome() == PutOutcome.CREATED).toList();
    List<Attempt> refused = attempts.stream().filter(a -> a.failure() != null).toList();
    assertThat(created).hasSize(1);
    assertThat(refused).hasSize(15);
    assertThat(refused)
        .allSatisfy(
            a ->
                assertThat(a.failure())
                    .isInstanceOf(EvidenceIntegrityException.class)
                    .isNotInstanceOf(EvidenceCorruptionException.class));
    byte[] winner = created.get(0).payload();
    for (SealedEvidenceStore store : List.of(first, second)) {
      assertThat(store.get(KEY).orElseThrow().bytes()).isEqualTo(winner);
    }
    for (Path volumeRoot : List.of(volumeA, volumeB)) {
      assertThat(
              SealedFormat.decodeObject(
                      Files.readAllBytes(SealedTamper.objectPath(volumeRoot, KEY)), KEY.path())
                  .payload())
          .isEqualTo(winner);
      assertThat(temporaryFiles(volumeRoot)).isEmpty();
    }
    assertThat(Files.readAllBytes(SealedTamper.objectPath(volumeA, KEY)))
        .isEqualTo(Files.readAllBytes(SealedTamper.objectPath(volumeB, KEY)));
    assertThat(first.scrub()).isEmpty();
  }

  /** The error of a process that dies; it is not an IOException, so no clean-up code runs. */
  private static final class SimulatedCrash extends Error {
    private static final long serialVersionUID = 1L;

    SimulatedCrash() {
      super("the writer died");
    }
  }

  /**
   * Passes every call on, except that the first link below {@code volume} after {@link #arm} kills
   * the writer.
   */
  private static final class CrashOnLink implements FileOps {
    private final FileOps delegate;
    private final Path volume;
    private final AtomicInteger crashes = new AtomicInteger(0);

    CrashOnLink(FileOps delegate, Path volume) {
      this.delegate = delegate;
      this.volume = volume;
    }

    void arm() {
      crashes.set(1);
    }

    @Override
    public void link(Path existing, Path newName) throws IOException {
      if (newName.startsWith(volume) && crashes.getAndDecrement() > 0) {
        throw new SimulatedCrash();
      }
      delegate.link(existing, newName);
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

  @Test
  void temporaryFilesLeftByACrashAreNeverListedOrServedAndAreSweptAtOpen() throws IOException {
    CrashOnLink crashing = new CrashOnLink(FileOps.real(), volumeB);
    SealedEvidenceStore store = open(crashing);
    crashing.arm(); // not before the open: its hard-link self-test makes a link too

    // the writer dies on volume b after its temporary file is forced and before the link
    assertThatThrownBy(() -> store.put(request(KEY, "hello"))).isInstanceOf(SimulatedCrash.class);
    assertThat(SealedTamper.objectPath(volumeA, KEY)).exists();
    assertThat(SealedTamper.objectPath(volumeB, KEY)).doesNotExist();
    List<Path> debris = temporaryFiles(volumeB);
    assertThat(debris).hasSize(1);

    // the dead writer was another process: its temporary file carries the pid of a dead one
    Path deadTemp = debris.get(0).resolveSibling(SealedNames.temporary("o-k1", DEAD_PID, 7));
    Files.move(debris.get(0), deadTemp);
    // more debris: half a meta, a stale hard-link self-test, a complete object under a temporary
    // name, and a temporary file of a process that is alive (this one), which must survive
    Path deadMeta =
        SealedTamper.metaPath(volumeA, KEY)
            .resolveSibling(SealedNames.temporary("m-k1", DEAD_PID, 8));
    Files.write(deadMeta, utf8("RPMETA01 half a meta"));
    Path deadSelfTest = volumeB.resolve(SealedNames.temporary(".selftest", DEAD_PID, 9));
    Files.write(deadSelfTest, utf8("regpack hard-link self-test"));
    EvidenceKey ghost = new EvidenceKey("landing/ghost");
    byte[] ghostPayload = utf8("a whole object that never got its name");
    SealedFormat.Header header =
        SealedFormat.decodeObject(
                Files.readAllBytes(SealedTamper.objectPath(volumeA, KEY)), KEY.path())
            .header();
    byte[] ghostFile =
        SealedFormat.encodeObject(
            new SealedFormat.Header(
                ghost.path(),
                header.mediaType(),
                Sha256.of(ghostPayload),
                ghostPayload.length,
                header.writtenAt(),
                header.retentionClass(),
                header.retainUntil()),
            ghostPayload);
    Path deadGhost =
        SealedTamper.objectPath(volumeA, ghost)
            .resolveSibling(SealedNames.temporary("o-ghost", DEAD_PID, 10));
    Files.write(deadGhost, ghostFile);
    Path liveTemp =
        SealedTamper.objectPath(volumeA, KEY)
            .resolveSibling(
                SealedNames.temporary("o-k1", ProcessHandle.current().pid(), 10_000_000));
    Files.write(liveTemp, utf8("a writer that is still running"));

    // none of it is listed or served by the store that is open, and scrub sees only the real gap
    assertThat(store.list("", Optional.empty(), 10)).containsExactly(KEY);
    assertThat(store.get(KEY).orElseThrow().bytes()).isEqualTo(utf8("hello"));
    assertThat(store.get(ghost)).isEmpty();
    assertThat(store.scrub()).containsExactly("b landing/k1 MISSING_COPY");

    // opening sweeps what belongs to dead processes and only that
    SealedEvidenceStore reopened = open();
    assertThat(deadTemp).doesNotExist();
    assertThat(deadMeta).doesNotExist();
    assertThat(deadSelfTest).doesNotExist();
    assertThat(deadGhost).doesNotExist();
    assertThat(liveTemp).exists();
    assertThat(reopened.list("", Optional.empty(), 10)).containsExactly(KEY);
    assertThat(reopened.get(ghost)).isEmpty();
    // the retry completes the second volume
    assertThat(reopened.put(request(KEY, "hello"))).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThat(Files.readAllBytes(SealedTamper.objectPath(volumeB, KEY)))
        .isEqualTo(Files.readAllBytes(SealedTamper.objectPath(volumeA, KEY)));
    assertThat(reopened.scrub()).isEmpty();
  }

  // ---------------------------------------------------------------- outages

  @Test
  void anIoFailureDuringAnOutageStoresNothingAndLeavesNoTemporaryFile() throws IOException {
    FaultableFileOps outage = new FaultableFileOps(FileOps.real());
    SealedEvidenceStore store = open(outage);
    EvidenceKey kept = new EvidenceKey("landing/kept");
    EvidenceKey refused = new EvidenceKey("landing/refused");
    store.put(request(kept, "kept"));
    store.setLegalHold(kept, true);
    Map<String, String> before = bothVolumes();

    // a total outage: every call fails, and the volumes are exactly as they were
    outage.setDown(true);
    assertThatThrownBy(() -> store.put(request(refused, "refused")))
        .isInstanceOf(EvidenceUnavailableException.class);
    assertThatThrownBy(() -> store.extendRetention(kept, Instant.parse("2099-01-01T00:00:00Z")))
        .isInstanceOf(EvidenceUnavailableException.class);
    assertThatThrownBy(() -> store.setLegalHold(kept, false))
        .isInstanceOf(EvidenceUnavailableException.class);
    assertThatThrownBy(store::repair).isInstanceOf(EvidenceUnavailableException.class);
    assertThatThrownBy(store::scrub).isInstanceOf(EvidenceUnavailableException.class);
    assertThatThrownBy(() -> store.get(kept)).isInstanceOf(EvidenceUnavailableException.class);
    assertThatThrownBy(() -> store.list("", Optional.empty(), 10))
        .isInstanceOf(EvidenceUnavailableException.class);
    assertThatThrownBy(store::durability).isInstanceOf(EvidenceUnavailableException.class);
    outage.setDown(false);
    assertThat(bothVolumes()).isEqualTo(before);
    assertThat(store.get(refused)).isEmpty();

    // the first failing step of a write, on the first volume: nothing is stored on either
    List<Op> steps = List.of(Op.WRITE_AND_FORCE, Op.LINK);
    for (Op step : steps) {
      FailingFileOps failing = new FailingFileOps(FileOps.real());
      SealedEvidenceStore faulty = open(failing);
      failing.failNext(step, path -> path.startsWith(volumeA));

      assertThatThrownBy(() -> faulty.put(request(refused, "refused")))
          .as(step.name())
          .isInstanceOf(EvidenceUnavailableException.class);

      assertThat(bothVolumes()).as(step.name()).isEqualTo(before);
    }
    // the same for the write of a meta
    FailingFileOps failing = new FailingFileOps(FileOps.real());
    SealedEvidenceStore faulty = open(failing);
    failing.failNext(Op.REPLACE, path -> path.startsWith(volumeA));
    assertThatThrownBy(() -> faulty.extendRetention(kept, Instant.parse("2099-01-01T00:00:00Z")))
        .isInstanceOf(EvidenceUnavailableException.class);
    assertThat(bothVolumes()).isEqualTo(before);
    assertThat(temporaryFiles(volumeA)).isEmpty();
    assertThat(temporaryFiles(volumeB)).isEmpty();

    // and the store works once the outage is over
    assertThat(store.put(request(refused, "refused"))).isEqualTo(PutOutcome.CREATED);
  }

  // ---------------------------------------------------------------- repair

  @Test
  void repairIsIdempotent() throws IOException {
    SealedEvidenceStore store = open();
    for (int i = 0; i < 5; i++) {
      EvidenceKey key = new EvidenceKey("landing/k" + i);
      store.put(request(key, "object " + i));
      if (i % 2 == 0) {
        store.setLegalHold(key, true);
      }
    }
    assertThat(store.repair()).isZero(); // a healthy pair needs nothing
    Map<String, String> healthy = bothVolumes();

    for (Path lost : List.of(volumeB, volumeA)) {
      deleteTree(lost);
      SealedEvidenceStore replaced = open();
      assertThat(replaced.repair()).isEqualTo(5);
      Map<String, String> repaired = bothVolumes();
      assertThat(repaired).isEqualTo(healthy);

      assertThat(replaced.repair()).isZero();
      assertThat(bothVolumes()).isEqualTo(repaired);
      assertThat(open().repair()).isZero();
      assertThat(bothVolumes()).isEqualTo(repaired);
      assertThat(replaced.scrub()).isEmpty();
    }
  }

  @Test
  void repairNeverCopiesACorruptSource() throws IOException {
    SealedEvidenceStore store = open();
    EvidenceKey k1 = new EvidenceKey("landing/k1");
    EvidenceKey k2 = new EvidenceKey("landing/k2");
    EvidenceKey k3 = new EvidenceKey("landing/k3");
    EvidenceKey k4 = new EvidenceKey("landing/k4");
    EvidenceKey k5 = new EvidenceKey("landing/k5");
    for (EvidenceKey key : List.of(k1, k2, k3, k4, k5)) {
      store.put(request(key, "object " + key.path()));
    }
    // k1: damaged on a, missing on b.  k2: damaged on b, missing on a.  k3: missing on a only.
    // k4: damaged on a, whole on b.  k5: a whole file of k1 under the name of k5 on a, none on b.
    byte[] wholeK1 = Files.readAllBytes(SealedTamper.objectPath(volumeA, k1));
    SealedTamper.flipByte(volumeA, k1);
    SealedTamper.deleteObject(volumeB, k1);
    SealedTamper.flipByte(volumeB, k2);
    SealedTamper.deleteObject(volumeA, k2);
    SealedTamper.deleteObject(volumeA, k3);
    SealedTamper.flipByte(volumeA, k4);
    SealedTamper.replaceObject(volumeA, k5, wholeK1);
    SealedTamper.deleteObject(volumeB, k5);
    byte[] damagedK1 = Files.readAllBytes(SealedTamper.objectPath(volumeA, k1));
    byte[] damagedK2 = Files.readAllBytes(SealedTamper.objectPath(volumeB, k2));
    byte[] damagedK4 = Files.readAllBytes(SealedTamper.objectPath(volumeA, k4));

    int copied = store.repair();

    assertThat(copied).isEqualTo(1); // k3 only
    assertThat(SealedTamper.objectPath(volumeB, k1)).doesNotExist();
    assertThat(SealedTamper.objectPath(volumeA, k2)).doesNotExist();
    assertThat(SealedTamper.objectPath(volumeB, k5)).doesNotExist();
    assertThat(Files.readAllBytes(SealedTamper.objectPath(volumeA, k1))).isEqualTo(damagedK1);
    assertThat(Files.readAllBytes(SealedTamper.objectPath(volumeB, k2))).isEqualTo(damagedK2);
    assertThat(Files.readAllBytes(SealedTamper.objectPath(volumeA, k4))).isEqualTo(damagedK4);
    assertThat(Files.readAllBytes(SealedTamper.objectPath(volumeA, k3)))
        .isEqualTo(Files.readAllBytes(SealedTamper.objectPath(volumeB, k3)));
    assertThat(store.scrub())
        .containsExactly(
            "a landing/k1 CORRUPT",
            "b landing/k1 MISSING_COPY",
            "a landing/k2 MISSING_COPY",
            "b landing/k2 CORRUPT",
            "a landing/k4 CORRUPT",
            "a landing/k5 CORRUPT",
            "b landing/k5 MISSING_COPY");
  }

  @Test
  void repairReconcilesMetaRevisions() throws IOException {
    SealedEvidenceStore store = open();
    EvidenceKey higherOnB = new EvidenceKey("meta/higher-on-b");
    EvidenceKey onlyOnB = new EvidenceKey("meta/only-on-b");
    EvidenceKey onlyOnA = new EvidenceKey("meta/only-on-a");
    EvidenceKey none = new EvidenceKey("meta/none");
    EvidenceKey sameRevision = new EvidenceKey("meta/same-revision");
    for (EvidenceKey key : List.of(higherOnB, onlyOnB, onlyOnA, none, sameRevision)) {
      store.put(request(key, "object " + key.path()));
    }
    Instant base = retention().retainUntil();
    SealedVolume onA = volume(volumeA, "a");
    SealedVolume onB = volume(volumeB, "b");
    assertThat(
            onA.writeMeta(
                higherOnB, new SealedFormat.Meta(2, "landing", base.plusSeconds(100), false)))
        .isTrue();
    assertThat(
            onB.writeMeta(
                higherOnB, new SealedFormat.Meta(5, "landing", base.plusSeconds(200), true)))
        .isTrue();
    assertThat(
            onB.writeMeta(
                onlyOnB, new SealedFormat.Meta(1, "landing", base.plusSeconds(300), true)))
        .isTrue();
    assertThat(
            onA.writeMeta(
                onlyOnA, new SealedFormat.Meta(3, "landing", base.plusSeconds(400), false)))
        .isTrue();
    // one revision on both volumes with different contents is not repair's to decide
    assertThat(
            onA.writeMeta(
                sameRevision, new SealedFormat.Meta(4, "landing", base.plusSeconds(500), true)))
        .isTrue();
    assertThat(
            onB.writeMeta(
                sameRevision, new SealedFormat.Meta(4, "landing", base.plusSeconds(450), false)))
        .isTrue();
    byte[] sameRevisionOnA = Files.readAllBytes(SealedTamper.metaPath(volumeA, sameRevision));
    byte[] sameRevisionOnB = Files.readAllBytes(SealedTamper.metaPath(volumeB, sameRevision));

    assertThat(store.repair()).isZero(); // no object was missing

    for (EvidenceKey key : List.of(higherOnB, onlyOnB, onlyOnA)) {
      assertThat(onA.readMeta(key)).as(key.path()).isPresent().isEqualTo(onB.readMeta(key));
    }
    assertThat(onA.readMeta(higherOnB))
        .contains(new SealedFormat.Meta(5, "landing", base.plusSeconds(200), true));
    assertThat(onA.readMeta(onlyOnB))
        .contains(new SealedFormat.Meta(1, "landing", base.plusSeconds(300), true));
    assertThat(onB.readMeta(onlyOnA))
        .contains(new SealedFormat.Meta(3, "landing", base.plusSeconds(400), false));
    assertThat(onA.readMeta(none)).isEmpty();
    assertThat(onB.readMeta(none)).isEmpty();
    assertThat(Files.readAllBytes(SealedTamper.metaPath(volumeA, sameRevision)))
        .isEqualTo(sameRevisionOnA);
    assertThat(Files.readAllBytes(SealedTamper.metaPath(volumeB, sameRevision)))
        .isEqualTo(sameRevisionOnB);
    // what a reader sees follows the higher revision
    StoredObject got = store.get(higherOnB).orElseThrow();
    assertThat(got.legalHold()).isTrue();
    assertThat(got.retention().retainUntil()).isEqualTo(base.plusSeconds(200));
    assertThat(store.repair()).isZero();
  }

  // ---------------------------------------------------------------- retention under concurrency

  private void assertRetentionAndHoldBothKept(
      SealedEvidenceStore store, EvidenceKey key, Instant later) throws IOException {
    StoredObject got = store.get(key).orElseThrow();
    assertThat(got.retention().retainUntil()).as("retention of %s", key.path()).isEqualTo(later);
    assertThat(got.legalHold()).as("hold of %s", key.path()).isTrue();
    assertThat(volume(volumeA, "a").readMeta(key)).isEqualTo(volume(volumeB, "b").readMeta(key));
  }

  /**
   * The caller's retry that the contract asks for: an unavailable answer is not an acknowledgement.
   */
  private static Callable<Boolean> retrying(Runnable call) {
    return () -> {
      for (int attempt = 1; ; attempt++) {
        try {
          call.run();
          return Boolean.TRUE;
        } catch (EvidenceUnavailableException e) {
          if (attempt >= 5) {
            throw e;
          }
        }
      }
    };
  }

  @Test
  void twoThreadsExtendingRetentionAndSettingAHoldLoseNeither() throws Exception {
    SealedEvidenceStore one = open();
    SealedEvidenceStore other = open(); // a second store over the same directories
    // one store: the two calls of a key take turns under the key's lock
    for (int round = 0; round < 20; round++) {
      EvidenceKey key = new EvidenceKey("race/one-store-" + round);
      one.put(request(key, "v" + round));
      Instant later = retention().retainUntil().plus(Duration.ofDays(30 + round));
      runTogether(
          List.of(
              retrying(() -> one.extendRetention(key, later)),
              retrying(() -> one.setLegalHold(key, true))));
      assertRetentionAndHoldBothKept(one, key, later);
    }
    // two stores: the revision of the meta file decides, and a call that lost is recomputed
    for (int round = 0; round < 20; round++) {
      EvidenceKey key = new EvidenceKey("race/two-stores-" + round);
      one.put(request(key, "v" + round));
      Instant later = retention().retainUntil().plus(Duration.ofDays(30 + round));
      runTogether(
          List.of(
              retrying(() -> one.extendRetention(key, later)),
              retrying(() -> other.setLegalHold(key, true))));
      assertRetentionAndHoldBothKept(one, key, later);
      assertRetentionAndHoldBothKept(other, key, later);
    }
    assertThat(one.scrub()).isEmpty();
  }

  @Test
  void aLowerRetainUntilInAHigherRevisionNeverShortensRetention() throws IOException {
    SealedEvidenceStore store = open();
    store.put(request(KEY, "hello"));
    Instant headerRetention = retention().retainUntil();
    Instant lower = headerRetention.minus(Duration.ofDays(30));
    SealedVolume onA = volume(volumeA, "a");
    SealedVolume onB = volume(volumeB, "b");
    assertThat(onA.writeMeta(KEY, new SealedFormat.Meta(7, "landing", lower, false))).isTrue();
    assertThat(onB.writeMeta(KEY, new SealedFormat.Meta(7, "landing", lower, false))).isTrue();

    // the header's retention still holds, and the lower instant is no extension target
    assertThat(store.get(KEY).orElseThrow().retention().retainUntil()).isEqualTo(headerRetention);
    assertThatThrownBy(() -> store.extendRetention(KEY, lower))
        .isInstanceOf(EvidenceRetentionException.class);
    assertThatThrownBy(() -> store.extendRetention(KEY, headerRetention.minusSeconds(1)))
        .isInstanceOf(EvidenceRetentionException.class);

    // a hold written on top of the high revision carries the maximum, never the lower instant
    store.setLegalHold(KEY, true);
    SealedFormat.Meta written = onA.readMeta(KEY).orElseThrow();
    assertThat(written.rev()).isEqualTo(8);
    assertThat(written.retainUntil()).isEqualTo(headerRetention);
    assertThat(onB.readMeta(KEY)).contains(written);

    // an older revision with a longer retention beats a newer one with a shorter retention
    Instant longer = headerRetention.plus(Duration.ofDays(100));
    Instant shorter = headerRetention.plus(Duration.ofDays(10));
    assertThat(onA.writeMeta(KEY, new SealedFormat.Meta(9, "landing", longer, true))).isTrue();
    assertThat(onB.writeMeta(KEY, new SealedFormat.Meta(10, "landing", shorter, true))).isTrue();
    assertThat(store.get(KEY).orElseThrow().retention().retainUntil()).isEqualTo(longer);
    assertThatThrownBy(() -> store.extendRetention(KEY, shorter))
        .isInstanceOf(EvidenceRetentionException.class);

    // repair copies the higher revision over the lower, except where that would shorten the
    // retention: then a new revision above both carries the longer retain-until to both volumes
    assertThat(store.repair()).isZero();
    SealedFormat.Meta repaired = onA.readMeta(KEY).orElseThrow();
    assertThat(onB.readMeta(KEY)).contains(repaired);
    assertThat(repaired.rev()).isEqualTo(11);
    assertThat(repaired.retainUntil()).isEqualTo(longer);
    assertThat(repaired.legalHold()).isTrue();
    assertThat(store.get(KEY).orElseThrow().retention().retainUntil()).isEqualTo(longer);
    assertThat(store.repair()).isZero();
    assertThat(onA.readMeta(KEY)).contains(repaired);
    assertThat(onB.readMeta(KEY)).contains(repaired);

    // the same with the volumes the other way round, and a hold that the higher revision released
    EvidenceKey mirrored = new EvidenceKey("landing/k2");
    store.put(request(mirrored, "hello again"));
    assertThat(onA.writeMeta(mirrored, new SealedFormat.Meta(12, "landing", shorter, false)))
        .isTrue();
    assertThat(onB.writeMeta(mirrored, new SealedFormat.Meta(11, "landing", longer, true)))
        .isTrue();
    StoredObject before = store.get(mirrored).orElseThrow();
    assertThat(before.retention().retainUntil()).isEqualTo(longer);
    assertThat(before.legalHold()).isFalse();

    assertThat(store.repair()).isZero();

    SealedFormat.Meta mirroredRepair = onB.readMeta(mirrored).orElseThrow();
    assertThat(onA.readMeta(mirrored)).contains(mirroredRepair);
    assertThat(mirroredRepair.rev()).isEqualTo(13);
    assertThat(mirroredRepair.retainUntil()).isEqualTo(longer);
    assertThat(mirroredRepair.legalHold()).isFalse();
    assertThat(store.get(mirrored).orElseThrow()).isEqualTo(before);
  }

  // ---------------------------------------------------------------- scrub

  @Test
  void scrubFindsAOneCopyCorruptionAndAMissingCopyAndNothingOnACleanStore() throws IOException {
    SealedEvidenceStore store = open();
    EvidenceKey corrupt = new EvidenceKey("scrub/corrupt");
    EvidenceKey missing = new EvidenceKey("scrub/missing");
    EvidenceKey fine = new EvidenceKey("scrub/fine");
    EvidenceKey held = new EvidenceKey("scrub/held");
    for (EvidenceKey key : List.of(corrupt, missing, fine, held)) {
      store.put(request(key, "object " + key.path()));
    }
    store.setLegalHold(held, true);
    assertThat(store.scrub()).isEmpty();

    SealedTamper.flipByte(volumeB, corrupt);
    SealedTamper.deleteObject(volumeA, missing);
    Map<String, String> before = bothVolumes();

    assertThat(store.scrub())
        .containsExactly("b scrub/corrupt CORRUPT", "a scrub/missing MISSING_COPY");
    // a scrub only reads
    assertThat(bothVolumes()).isEqualTo(before);
    // and what was damaged is still served from the other copy
    assertThat(store.get(corrupt).orElseThrow().bytes()).isEqualTo(utf8("object scrub/corrupt"));
    assertThat(store.get(missing).orElseThrow().bytes()).isEqualTo(utf8("object scrub/missing"));
  }

  @Test
  void scrubReportsTwoSelfConsistentButDifferentCopiesAsDivergent() throws IOException {
    SealedEvidenceStore store = open();
    store.put(request(KEY, "the first bytes"));
    byte[] other = utf8("other bytes under the same key");
    byte[] otherFile = SealedTamper.fileWithOtherPayload(volumeB, KEY, other);
    // each copy passes its own seal, so no single-copy check can tell
    SealedFormat.decodeObject(otherFile, KEY.path());
    SealedTamper.replaceObject(volumeB, KEY, otherFile);

    assertThat(store.scrub()).containsExactly("landing/k1 DIVERGENT");
    // it is an alarm on the next write, whichever bytes are offered, and a read serves volume a
    assertThatThrownBy(() -> store.put(request(KEY, "the first bytes")))
        .isInstanceOf(EvidenceIntegrityException.class)
        .isNotInstanceOf(EvidenceCorruptionException.class);
    assertThatThrownBy(() -> store.put(new PutRequest(KEY, other, "text/plain", retention())))
        .isInstanceOf(EvidenceIntegrityException.class)
        .isNotInstanceOf(EvidenceCorruptionException.class);
    assertThat(store.get(KEY).orElseThrow().bytes()).isEqualTo(utf8("the first bytes"));
    // repair does not pick a side
    assertThat(store.repair()).isZero();
    assertThat(store.scrub()).containsExactly("landing/k1 DIVERGENT");
  }
}
