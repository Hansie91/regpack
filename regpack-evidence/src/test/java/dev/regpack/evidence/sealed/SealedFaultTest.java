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
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
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

  /** Above every pid_max (Linux 4,194,304) and never a Windows pid (multiples of 4). */
  private static final long DEAD_PID = Integer.MAX_VALUE;

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
    byte[] payload = utf8("the payload of the object");
    store.put(new PutRequest(KEY, payload, "text/plain", retention()));
    StoredObject expected = store.get(KEY).orElseThrow();
    byte[] original = Files.readAllBytes(SealedTamper.objectPath(volumeA, KEY));
    int trailerLength = 32; // the SHA-256 that ends every file
    int firstPayloadByte = original.length - trailerLength - payload.length;
    int lastPayloadByte = original.length - trailerLength - 1;
    assertThat(Arrays.copyOfRange(original, firstPayloadByte, lastPayloadByte + 1))
        .as("the offsets point at the payload")
        .isEqualTo(payload);

    // the file is the header, the payload and the 32-byte trailer; a digit of the year of
    // retain_until (a field no cross-check covers: key, length and payload hash are the others)
    // and of written_at
    String text = new String(original, StandardCharsets.ISO_8859_1);
    int retainDigit = text.indexOf("\"retain_until\":\"") + "\"retain_until\":\"".length() + 3;
    int writtenDigit = text.indexOf("\"written_at\":\"") + "\"written_at\":\"".length() + 3;
    assertThat(retainDigit).isGreaterThan(20);
    assertThat(writtenDigit).isGreaterThan(20);
    assertThat(Character.isDigit(text.charAt(retainDigit))).isTrue();
    assertThat(Character.isDigit(text.charAt(writtenDigit))).isTrue();

    // the seal names the fault: a flipped payload byte is a hash mismatch
    assertThatThrownBy(
            () -> SealedFormat.decodeObject(withByteFlipped(original, lastPayloadByte), KEY.path()))
        .isInstanceOfSatisfying(
            SealedFormat.CorruptException.class,
            e -> assertThat(e.reason()).isEqualTo(SealedFormat.Reason.HASH_MISMATCH));
    // ... and so is a flipped digit of the retention
    assertThatThrownBy(
            () -> SealedFormat.decodeObject(withByteFlipped(original, retainDigit), KEY.path()))
        .isInstanceOfSatisfying(
            SealedFormat.CorruptException.class,
            e -> assertThat(e.reason()).isEqualTo(SealedFormat.Reason.HASH_MISMATCH));

    Map<String, byte[]> damages = new TreeMap<>();
    damages.put("payload byte flipped", withByteFlipped(original, lastPayloadByte));
    damages.put("first payload byte flipped", withByteFlipped(original, firstPayloadByte));
    damages.put("trailer byte flipped", withByteFlipped(original, original.length - 1));
    damages.put("retain_until digit flipped", withByteFlipped(original, retainDigit));
    damages.put("written_at digit flipped", withByteFlipped(original, writtenDigit));
    damages.put("key byte flipped", withByteFlipped(original, 20));
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
        assertThat(store.get(KEY).orElseThrow().retention().retainUntil())
            .as("the retention served, %s on %s", damage.getKey(), damaged)
            .isEqualTo(expected.retention().retainUntil());
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
    assertThat(ProcessHandle.of(DEAD_PID)).as("the constant must name no process").isEmpty();
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
    Files.write(deadMeta, utf8("RPMETA02 half a meta"));
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

    // none of it is listed or served by the store that is open; scrub sees the real gap and names
    // the two complete temporaries (not the half meta, not the self-test, not the live writer's)
    String deadTempLine = "b " + relative(volumeB, deadTemp) + " COMPLETE_TEMPORARY";
    String deadGhostLine = "a " + relative(volumeA, deadGhost) + " COMPLETE_TEMPORARY";
    assertThat(store.list("", Optional.empty(), 10)).containsExactly(KEY);
    assertThat(store.get(KEY).orElseThrow().bytes()).isEqualTo(utf8("hello"));
    assertThat(store.get(ghost)).isEmpty();
    assertThat(store.scrub())
        .containsExactly("b landing/k1 MISSING_COPY", deadGhostLine, deadTempLine);

    // opening sweeps what belongs to dead processes and cannot be an object or a meta: a complete
    // object that never got its name is kept for an operator (PSEALED-1), the rest is deleted
    SealedEvidenceStore reopened = open();
    assertThat(deadMeta).doesNotExist();
    assertThat(deadSelfTest).doesNotExist();
    assertThat(deadTemp).exists();
    assertThat(deadGhost).exists();
    assertThat(Files.readAllBytes(deadGhost)).isEqualTo(ghostFile);
    assertThat(liveTemp).exists();
    assertThat(reopened.list("", Optional.empty(), 10)).containsExactly(KEY);
    assertThat(reopened.get(ghost)).isEmpty();
    assertThat(reopened.scrub())
        .containsExactly("b landing/k1 MISSING_COPY", deadGhostLine, deadTempLine);
    // the retry completes the second volume
    assertThat(reopened.put(request(KEY, "hello"))).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThat(Files.readAllBytes(SealedTamper.objectPath(volumeB, KEY)))
        .isEqualTo(Files.readAllBytes(SealedTamper.objectPath(volumeA, KEY)));
    // the temporary whose name now exists with the same bytes is debris and no longer reported
    assertThat(reopened.scrub()).containsExactly(deadGhostLine);

    // and the next open deletes it, but never the complete object without a name
    open();
    assertThat(deadTemp).doesNotExist();
    assertThat(deadGhost).exists();
    assertThat(liveTemp).exists();
  }

  /** The path of {@code file} below {@code volumeRoot}, as scrub names a temporary file. */
  private static String relative(Path volumeRoot, Path file) {
    return volumeRoot.relativize(file).toString().replace('\\', '/');
  }

  /** Renames the object file of the key on the volume to a temporary name of a dead process. */
  private static Path turnIntoDeadTemporary(Path volumeRoot, EvidenceKey key, long counter)
      throws IOException {
    Path object = SealedTamper.objectPath(volumeRoot, key);
    String last = object.getFileName().toString();
    Path dead = object.resolveSibling(SealedNames.temporary(last, DEAD_PID, counter));
    Files.move(object, dead);
    return dead;
  }

  @Test
  void aCompleteObjectWhoseNameWasLostWithAPowerFailureSurvivesOpenAndIsReportedNotServed()
      throws IOException {
    assertThat(ProcessHandle.of(DEAD_PID)).as("the constant must name no process").isEmpty();
    open().put(request(KEY, "acknowledged"));
    // what NTFS can replay after a power loss: the forced temporary files, not the links
    Path tempOnA = turnIntoDeadTemporary(volumeA, KEY, 1);
    Path tempOnB = turnIntoDeadTemporary(volumeB, KEY, 2);
    byte[] fileOnA = Files.readAllBytes(tempOnA);

    SealedEvidenceStore store = open();

    assertThat(tempOnA).exists();
    assertThat(tempOnB).exists();
    assertThat(Files.readAllBytes(tempOnA)).isEqualTo(fileOnA);
    assertThat(store.get(KEY)).isEmpty();
    assertThat(store.list("", Optional.empty(), 10)).isEmpty();
    assertThat(store.repair()).isZero();
    assertThat(store.scrub())
        .containsExactly(
            "a " + relative(volumeA, tempOnA) + " COMPLETE_TEMPORARY",
            "b " + relative(volumeB, tempOnB) + " COMPLETE_TEMPORARY");
    // the operator adopts it: the name comes back and the temporary is no longer reported
    Files.move(tempOnA, SealedTamper.objectPath(volumeA, KEY));
    Files.move(tempOnB, SealedTamper.objectPath(volumeB, KEY));
    assertThat(store.get(KEY).orElseThrow().bytes()).isEqualTo(utf8("acknowledged"));
    assertThat(store.scrub()).isEmpty();
  }

  @Test
  void aCompleteMetaWhoseReplaceWasLostIsKeptAndASupersededOrHalfWrittenOneIsDeleted()
      throws IOException {
    SealedEvidenceStore store = open();
    store.put(request(KEY, "hello"));
    store.setLegalHold(KEY, true); // revision 1 on both volumes
    Path metaOnA = SealedTamper.metaPath(volumeA, KEY);
    Path metaOnB = SealedTamper.metaPath(volumeB, KEY);
    byte[] storedOnB = Files.readAllBytes(metaOnB);
    SealedFormat.Meta stored = SealedFormat.decodeMeta(storedOnB);
    assertThat(stored.rev()).isEqualTo(1);
    // volume a: the replace of the stored meta was lost, only the forced temporary survived
    Path onlyTrace = metaOnA.resolveSibling(SealedNames.temporary("m-k1", DEAD_PID, 1));
    Files.move(metaOnA, onlyTrace);
    // volume b: temporaries beside the stored revision 1
    Path same = metaOnB.resolveSibling(SealedNames.temporary("m-k1", DEAD_PID, 2));
    Files.write(same, storedOnB);
    Path lower = metaOnB.resolveSibling(SealedNames.temporary("m-k1", DEAD_PID, 3));
    Files.write(
        lower,
        SealedFormat.encodeMeta(
            new SealedFormat.Meta(0, stored.retentionClass(), stored.retainUntil(), false)));
    Path higher = metaOnB.resolveSibling(SealedNames.temporary("m-k1", DEAD_PID, 4));
    Files.write(
        higher,
        SealedFormat.encodeMeta(
            new SealedFormat.Meta(2, stored.retentionClass(), stored.retainUntil(), false)));
    Path half = metaOnB.resolveSibling(SealedNames.temporary("m-k1", DEAD_PID, 5));
    Files.write(half, Arrays.copyOf(storedOnB, storedOnB.length - 3));

    SealedEvidenceStore reopened = open();

    assertThat(onlyTrace).as("a complete meta with no stored meta").exists();
    assertThat(higher).as("a complete meta above the stored revision").exists();
    assertThat(same).as("the stored meta again").doesNotExist();
    assertThat(lower).as("below the stored revision").doesNotExist();
    assertThat(half).as("half written").doesNotExist();
    assertThat(Files.readAllBytes(metaOnB)).isEqualTo(storedOnB);
    assertThat(reopened.scrub())
        .contains(
            "a " + relative(volumeA, onlyTrace) + " COMPLETE_TEMPORARY",
            "b " + relative(volumeB, higher) + " COMPLETE_TEMPORARY")
        .hasSize(2);
  }

  @Test
  void aHalfWrittenOrMisplacedOrAlreadyNamedTemporaryIsDeletedAtOpen() throws IOException {
    SealedEvidenceStore store = open();
    EvidenceKey other = new EvidenceKey("landing/k2");
    store.put(request(KEY, "hello"));
    store.put(request(other, "other"));
    byte[] whole = Files.readAllBytes(SealedTamper.objectPath(volumeA, KEY));
    Path dir = SealedTamper.objectPath(volumeA, KEY).getParent();
    // half written: a cut of a whole file, and the whole file without its trailer
    Path half = dir.resolve(SealedNames.temporary("o-k3", DEAD_PID, 1));
    Files.write(half, Arrays.copyOf(whole, whole.length / 2));
    Path noTrailer = dir.resolve(SealedNames.temporary("o-k4", DEAD_PID, 2));
    Files.write(noTrailer, Arrays.copyOf(whole, whole.length - 1));
    // a whole file of another key under the name of a key it does not hold
    Path misplaced = dir.resolve(SealedNames.temporary("o-k5", DEAD_PID, 3));
    Files.write(misplaced, whole);
    // a whole file whose final name exists with the same bytes
    Path named = dir.resolve(SealedNames.temporary("o-k1", DEAD_PID, 4));
    Files.write(named, whole);

    SealedEvidenceStore reopened = open();

    for (Path gone : List.of(half, noTrailer, misplaced, named)) {
      assertThat(gone).as(gone.getFileName().toString()).doesNotExist();
    }
    assertThat(reopened.scrub()).isEmpty();
    assertThat(reopened.get(KEY).orElseThrow().bytes()).isEqualTo(utf8("hello"));
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
  void aShortenedRetentionInTheOnlyCopyIsNeverCopiedByRepair() throws IOException {
    // FORMAT-1: before the trailer, a flipped digit of retain_until was a valid file, repair copied
    // it to the replaced volume and scrub then showed nothing
    SealedEvidenceStore store = open();
    store.put(request(KEY, "the payload"));
    byte[] original = Files.readAllBytes(SealedTamper.objectPath(volumeA, KEY));
    String text = new String(original, StandardCharsets.ISO_8859_1);
    int digit = text.indexOf("\"retain_until\":\"") + "\"retain_until\":\"".length() + 3;
    SealedTamper.replaceObject(volumeA, KEY, withByteFlipped(original, digit));
    SealedTamper.deleteObject(volumeB, KEY);

    assertThat(store.repair()).isZero();

    assertThat(SealedTamper.objectPath(volumeB, KEY)).doesNotExist();
    assertThat(store.scrub()).containsExactly("a landing/k1 CORRUPT", "b landing/k1 MISSING_COPY");
    assertThatThrownBy(() -> store.get(KEY)).isInstanceOf(EvidenceCorruptionException.class);
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
  private static Callable<Boolean> retrying(Runnable call, AtomicInteger unavailableAnswers) {
    return () -> {
      for (int attempt = 1; ; attempt++) {
        try {
          call.run();
          return Boolean.TRUE;
        } catch (EvidenceUnavailableException e) {
          unavailableAnswers.incrementAndGet();
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
    // With two actors a call is overtaken at most once and then recomputed inside the store, so an
    // "unavailable" answer (three overtakes in a row) is a rarity; a regression that makes it
    // common, or permanent for one caller, shows in this count (T8 F1).
    AtomicInteger unavailableAnswers = new AtomicInteger();
    // one store: the two calls of a key take turns under the key's lock; this test would pass
    // without the lock (two actors are rarely overtaken three times), and the lock is pinned by
    // manyConcurrentCallsOnOneKeyThroughOneStoreAreAllAcknowledged
    for (int round = 0; round < 20; round++) {
      EvidenceKey key = new EvidenceKey("race/one-store-" + round);
      one.put(request(key, "v" + round));
      Instant later = retention().retainUntil().plus(Duration.ofDays(30 + round));
      runTogether(
          List.of(
              retrying(() -> one.extendRetention(key, later), unavailableAnswers),
              retrying(() -> one.setLegalHold(key, true), unavailableAnswers)));
      assertRetentionAndHoldBothKept(one, key, later);
    }
    // two stores: the revision of the meta file decides, and a call that lost is recomputed
    for (int round = 0; round < 20; round++) {
      EvidenceKey key = new EvidenceKey("race/two-stores-" + round);
      one.put(request(key, "v" + round));
      Instant later = retention().retainUntil().plus(Duration.ofDays(30 + round));
      runTogether(
          List.of(
              retrying(() -> one.extendRetention(key, later), unavailableAnswers),
              retrying(() -> other.setLegalHold(key, true), unavailableAnswers)));
      assertRetentionAndHoldBothKept(one, key, later);
      assertRetentionAndHoldBothKept(other, key, later);
    }
    assertThat(unavailableAnswers.get())
        .as("unavailable answers in 40 rounds")
        .isLessThanOrEqualTo(5);
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
    Map<String, String> before = bothVolumes();
    // a divergent pair is an alarm of its own kind (EvidenceCorruptionException) on the next
    // write, whichever bytes are offered, and a read serves volume a
    assertThatThrownBy(() -> store.put(request(KEY, "the first bytes")))
        .isInstanceOf(EvidenceCorruptionException.class)
        .hasMessageContaining(KEY.path());
    assertThatThrownBy(() -> store.put(new PutRequest(KEY, other, "text/plain", retention())))
        .isInstanceOf(EvidenceCorruptionException.class)
        .hasMessageContaining(KEY.path());
    assertThat(bothVolumes()).as("nothing is written").isEqualTo(before);
    assertThat(store.get(KEY).orElseThrow().bytes()).isEqualTo(utf8("the first bytes"));
    // repair does not pick a side
    assertThat(store.repair()).isZero();
    assertThat(store.scrub()).containsExactly("landing/k1 DIVERGENT");
  }

  @Test
  void aDivergentPairWithVolumeATamperedIsACorruptionAlarmToo() throws IOException {
    SealedEvidenceStore store = open();
    store.put(request(KEY, "the first bytes"));
    byte[] other = utf8("other bytes under the same key");
    SealedTamper.replaceObject(
        volumeA, KEY, SealedTamper.fileWithOtherPayload(volumeA, KEY, other));
    Map<String, String> before = bothVolumes();

    assertThat(store.scrub()).containsExactly("landing/k1 DIVERGENT");
    assertThatThrownBy(() -> store.put(request(KEY, "the first bytes")))
        .isInstanceOf(EvidenceCorruptionException.class)
        .hasMessageContaining(KEY.path());
    assertThatThrownBy(() -> store.put(new PutRequest(KEY, other, "text/plain", retention())))
        .isInstanceOf(EvidenceCorruptionException.class)
        .hasMessageContaining(KEY.path());
    assertThat(bothVolumes()).as("nothing is written").isEqualTo(before);
  }

  /**
   * One copy has its header edited (media type, class, retain-until) and its trailer recomputed:
   * both copies pass their own seal, hold the same payload and differ in their bytes.
   */
  private void aHeaderOnlyDivergence(Path edited, Instant editedRetainUntil) throws IOException {
    SealedEvidenceStore store = open();
    store.put(request(KEY, "the bytes"));
    Instant original = retention().retainUntil();
    byte[] editedFile =
        SealedTamper.fileWithOtherHeader(
            edited, KEY, "application/x-edited", "edited", editedRetainUntil);
    SealedFormat.decodeObject(editedFile, KEY.path()); // whole and self-consistent
    SealedTamper.replaceObject(edited, KEY, editedFile);
    Map<String, String> before = bothVolumes();
    boolean editedIsLonger = editedRetainUntil.isAfter(original);
    Instant between = editedIsLonger ? original.plusSeconds(1) : editedRetainUntil.plusSeconds(1);
    Instant longest = editedIsLonger ? editedRetainUntil : original;

    // scrub sees any byte difference, and so do the writes: no side is taken for the truth
    assertThat(store.scrub()).containsExactly(KEY.path() + " DIVERGENT");
    assertThatThrownBy(() -> store.put(request(KEY, "the bytes")))
        .isInstanceOf(EvidenceCorruptionException.class)
        .hasMessageContaining(KEY.path());
    assertThatThrownBy(() -> store.extendRetention(KEY, between))
        .isInstanceOf(EvidenceCorruptionException.class)
        .hasMessageContaining(KEY.path());
    assertThatThrownBy(() -> store.setLegalHold(KEY, true))
        .isInstanceOf(EvidenceCorruptionException.class)
        .hasMessageContaining(KEY.path());
    assertThat(bothVolumes()).as("nothing is written").isEqualTo(before);
    assertThat(store.repair()).isZero();
    // a read serves the longest retention over both headers, whichever volume was edited
    StoredObject served = store.get(KEY).orElseThrow();
    assertThat(served.retention().retainUntil()).isEqualTo(longest);
    assertThat(served.bytes()).isEqualTo(utf8("the bytes"));
    assertThat(served.retention().retentionClass().name())
        .isEqualTo(editedIsLonger ? "edited" : "landing");
  }

  @Test
  void aHeaderOnlyDivergenceWithAShorterRetentionOnVolumeAServesTheLongerOneFromB()
      throws IOException {
    aHeaderOnlyDivergence(volumeA, clock.now().plus(Duration.ofDays(1)));
  }

  @Test
  void aHeaderOnlyDivergenceWithAShorterRetentionOnVolumeBServesTheLongerOneFromA()
      throws IOException {
    aHeaderOnlyDivergence(volumeB, clock.now().plus(Duration.ofDays(1)));
  }

  @Test
  void aHeaderOnlyDivergenceWithALongerRetentionOnVolumeAIsServedToo() throws IOException {
    aHeaderOnlyDivergence(volumeA, clock.now().plus(Duration.ofDays(4000)));
  }

  @Test
  void aHeaderOnlyDivergenceWithALongerRetentionOnVolumeBIsServedToo() throws IOException {
    aHeaderOnlyDivergence(volumeB, clock.now().plus(Duration.ofDays(4000)));
  }

  @Test
  void aMetaLongerThanBothDivergentHeadersStillWinsTheServedRetention() throws IOException {
    SealedEvidenceStore store = open();
    store.put(request(KEY, "the bytes"));
    Instant meta = retention().retainUntil().plus(Duration.ofDays(9000));
    store.extendRetention(KEY, meta);
    SealedTamper.replaceObject(
        volumeA,
        KEY,
        SealedTamper.fileWithOtherHeader(
            volumeA, KEY, "text/plain", "landing", clock.now().plus(Duration.ofDays(1))));

    assertThat(store.get(KEY).orElseThrow().retention().retainUntil()).isEqualTo(meta);
    assertThat(store.scrub()).containsExactly(KEY.path() + " DIVERGENT");
  }

  // ---------------------------------------------------------------- restart states of repair

  @Test
  void aRepairThatFailsMidwayLeavesTheStoreDegraded() throws IOException {
    SealedEvidenceStore store = open();
    List<EvidenceKey> keys = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      EvidenceKey key = new EvidenceKey("landing/k" + i);
      keys.add(key);
      store.put(request(key, "object " + i));
    }
    deleteTree(volumeB);
    FailingFileOps failing = new FailingFileOps(FileOps.real());
    SealedEvidenceStore replaced = open(failing);
    assertThat(replaced.degraded()).isTrue();
    // the third object cannot be linked on the replaced volume: two are copied before it
    Path third = SealedTamper.objectPath(volumeB, keys.get(2));
    failing.failNext(Op.LINK, third::equals);

    assertThatThrownBy(replaced::repair).isInstanceOf(EvidenceUnavailableException.class);

    // markers come last: a repair that did not finish leaves the volume unmarked and degraded
    assertThat(replaced.degraded()).isTrue();
    assertThat(replaced.durability()).isEqualTo(new Durability(false, 1));
    assertThat(volumeB.resolve(MARKER)).doesNotExist();
    assertThat(SealedTamper.objectPath(volumeB, keys.get(1))).exists();
    assertThat(third).doesNotExist();
    assertThatThrownBy(() -> replaced.put(request(new EvidenceKey("landing/new"), "new")))
        .isInstanceOf(EvidenceUnavailableException.class);

    // once the fault is gone the retry copies what is missing and only then marks the volume
    assertThat(replaced.repair()).isEqualTo(3);
    assertThat(replaced.degraded()).isFalse();
    assertThat(replaced.durability().synchronousAcknowledgement()).isTrue();
    assertThat(Files.readString(volumeB.resolve(MARKER))).isEqualTo("b");
    assertThat(withoutMarker(digests(volumeB))).isEqualTo(withoutMarker(digests(volumeA)));
    assertThat(replaced.scrub()).isEmpty();
  }

  private void repairOfAnEmptyUnmarkedVolumeWritesItsMarker(Path lost, String lostId)
      throws IOException {
    SealedEvidenceStore store = open(); // an empty store: a new pair, both marked
    deleteTree(lost);
    Files.createDirectories(lost); // a new disk, empty and unmarked
    assertThat(store.degraded()).isTrue();
    assertThatThrownBy(() -> store.put(request(KEY, "hello")))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessageContaining("volume " + lostId);

    // nothing is missing, but the marker is: a repair that writes a marker only after it copied
    // something would leave this store degraded for ever
    assertThat(store.repair()).isZero();

    assertThat(store.degraded()).isFalse();
    assertThat(Files.readString(lost.resolve(MARKER))).isEqualTo(lostId);
    assertThat(store.put(request(KEY, "hello"))).isEqualTo(PutOutcome.CREATED);
    assertThat(store.scrub()).isEmpty();
  }

  @Test
  void repairOfAnEmptyUnmarkedVolumeBWritesItsMarker() throws IOException {
    repairOfAnEmptyUnmarkedVolumeWritesItsMarker(volumeB, "b");
  }

  @Test
  void repairOfAnEmptyUnmarkedVolumeAWritesItsMarker() throws IOException {
    repairOfAnEmptyUnmarkedVolumeWritesItsMarker(volumeA, "a");
  }

  // ---------------------------------------------------------------- the first open

  private void aFirstOpenThatStoppedBetweenTheMarkersIsFinished(Path unmarked, String id)
      throws IOException {
    open(); // a new pair: both markers
    Files.delete(unmarked.resolve(MARKER)); // the open stopped before this marker was written

    SealedEvidenceStore reopened = open();

    assertThat(reopened.degraded()).isFalse();
    assertThat(reopened.durability().synchronousAcknowledgement()).isTrue();
    assertThat(Files.readString(volumeA.resolve(MARKER))).isEqualTo("a");
    assertThat(Files.readString(volumeB.resolve(MARKER))).isEqualTo("b");
    assertThat(Files.readString(unmarked.resolve(MARKER))).isEqualTo(id);
    assertThat(reopened.put(request(KEY, "hello"))).isEqualTo(PutOutcome.CREATED);
  }

  @Test
  void aFirstOpenThatStoppedBeforeMarkerBIsFinishedByTheNextOpen() throws IOException {
    aFirstOpenThatStoppedBetweenTheMarkersIsFinished(volumeB, "b");
  }

  @Test
  void aFirstOpenWithMarkerAMissingIsFinishedByTheNextOpen() throws IOException {
    aFirstOpenThatStoppedBetweenTheMarkersIsFinished(volumeA, "a");
  }

  @Test
  void aFirstOpenThatFailsBetweenTheMarkersIsFinishedByTheNextOpen() throws IOException {
    FailingFileOps failing = new FailingFileOps(FileOps.real());
    // the real interruption: the link of marker b fails after marker a was written
    failing.failNext(Op.LINK, path -> path.equals(volumeB.resolve(MARKER)));

    assertThatThrownBy(() -> open(failing)).isInstanceOf(EvidenceUnavailableException.class);
    assertThat(volumeA.resolve(MARKER)).exists();
    assertThat(volumeB.resolve(MARKER)).doesNotExist();

    SealedEvidenceStore reopened = open();

    assertThat(reopened.degraded()).isFalse();
    assertThat(Files.readString(volumeB.resolve(MARKER))).isEqualTo("b");
  }

  @Test
  void aMissingMarkerBesideAnObjectStaysAReplacedDisk() throws IOException {
    SealedEvidenceStore store = open();
    store.put(request(KEY, "hello"));
    SealedTamper.deleteObject(volumeB, KEY); // volume b holds nothing, volume a holds the object
    Files.delete(volumeB.resolve(MARKER));

    SealedEvidenceStore degraded = open();

    assertThat(degraded.degraded()).isTrue();
    assertThat(degraded.durability()).isEqualTo(new Durability(false, 1));
    assertThat(volumeB.resolve(MARKER)).doesNotExist();
    assertThatThrownBy(() -> degraded.put(request(new EvidenceKey("landing/k2"), "second")))
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessageContaining("volume b");
    assertThat(degraded.repair()).isEqualTo(1);
    assertThat(degraded.degraded()).isFalse();
    assertThat(Files.readString(volumeB.resolve(MARKER))).isEqualTo("b");
  }

  @Test
  void aMissingMarkerOnTheVolumeThatHoldsTheObjectStaysAReplacedDisk() throws IOException {
    SealedEvidenceStore store = open();
    store.put(request(KEY, "hello"));
    SealedTamper.deleteObject(volumeA, KEY);
    Files.delete(volumeA.resolve(MARKER)); // the unmarked volume is the empty one: a replaced disk

    SealedEvidenceStore degraded = open();

    assertThat(degraded.degraded()).isTrue();
    assertThat(volumeA.resolve(MARKER)).doesNotExist();
    assertThat(degraded.repair()).isEqualTo(1);
    assertThat(degraded.degraded()).isFalse();
  }

  private void aMissingMarkerBesideAMetaStaysAReplacedDisk(Path withMeta, String id, Path unmarked)
      throws IOException {
    SealedEvidenceStore store = open();
    store.put(request(KEY, "hello"));
    // no object is left anywhere: only a retention record on one volume
    SealedTamper.deleteObject(volumeA, KEY);
    SealedTamper.deleteObject(volumeB, KEY);
    assertThat(
            volume(withMeta, id)
                .writeMeta(
                    KEY, new SealedFormat.Meta(1, "landing", retention().retainUntil(), true)))
        .isTrue();
    Files.delete(unmarked.resolve(MARKER));

    SealedEvidenceStore degraded = open();

    assertThat(degraded.degraded()).isTrue();
    assertThat(unmarked.resolve(MARKER)).doesNotExist();
  }

  @Test
  void aMissingMarkerBesideAMetaOnTheMarkedVolumeStaysAReplacedDisk() throws IOException {
    aMissingMarkerBesideAMetaStaysAReplacedDisk(volumeA, "a", volumeB);
  }

  @Test
  void aMissingMarkerBesideAMetaOnTheUnmarkedVolumeStaysAReplacedDisk() throws IOException {
    aMissingMarkerBesideAMetaStaysAReplacedDisk(volumeB, "b", volumeB);
  }

  /** Runs {@code hook} once, before the first listing of the root of {@code volume}. */
  private static final class BeforeFirstListing implements FileOps {
    private final FileOps delegate;
    private final Path volume;
    private final AtomicInteger fired = new AtomicInteger();
    private final Runnable hook;

    BeforeFirstListing(FileOps delegate, Path volume, Runnable hook) {
      this.delegate = delegate;
      this.volume = volume;
      this.hook = hook;
    }

    int fired() {
      return fired.get();
    }

    @Override
    public List<Path> list(Path dir) throws IOException {
      if (dir.equals(volume) && fired.getAndIncrement() == 0) {
        hook.run();
      }
      return delegate.list(dir);
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
  }

  @Test
  void twoOpenersOfAFreshPairDoNotRefuseOrDegradeEachOther() throws IOException {
    // The other opener writes marker a after this one has read both markers as missing and before
    // it looks at volume a: it must see a new store being marked, not volumes with content.
    BeforeFirstListing race =
        new BeforeFirstListing(
            FileOps.real(),
            volumeA,
            () -> {
              try {
                volume(volumeA, "a").writeMarker();
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });

    SealedEvidenceStore store = open(race);

    assertThat(race.fired()).isGreaterThanOrEqualTo(1);
    assertThat(store.degraded()).isFalse();
    assertThat(Files.readString(volumeA.resolve(MARKER))).isEqualTo("a");
    assertThat(Files.readString(volumeB.resolve(MARKER))).isEqualTo("b");
    assertThat(store.put(request(KEY, "hello"))).isEqualTo(PutOutcome.CREATED);
  }

  // ---------------------------------------------------------------- losing the name on volume a

  /**
   * Passes every call on, but runs {@code hook} once just before the first link that gives {@code
   * name} on {@code volume}: another writer wins the name.
   */
  private static final class BeforeObjectLink implements FileOps {
    private final FileOps delegate;
    private final Path volume;
    private final String name;
    private final AtomicInteger fired = new AtomicInteger();
    private final Runnable hook;

    BeforeObjectLink(FileOps delegate, Path volume, String name, Runnable hook) {
      this.delegate = delegate;
      this.volume = volume;
      this.name = name;
      this.hook = hook;
    }

    int fired() {
      return fired.get();
    }

    @Override
    public void link(Path existing, Path newName) throws IOException {
      if (newName.startsWith(volume)
          && newName.getFileName().toString().equals(name)
          && fired.getAndIncrement() == 0) {
        hook.run();
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
  void aWriterThatLosesTheNameOnTheFirstVolumeNeverCopiesItsOwnBytesToTheSecond()
      throws IOException {
    byte[] winnerPayload = utf8("the winner's bytes");
    byte[] winnerFile =
        SealedFormat.encodeObject(
            new SealedFormat.Header(
                KEY.path(),
                "text/plain",
                Sha256.of(winnerPayload),
                winnerPayload.length,
                clock.now(),
                "landing",
                retention().retainUntil()),
            winnerPayload);
    SealedVolume onA = volume(volumeA, "a");
    // the winner takes the name on volume a after the loser has read both volumes as empty and
    // before the loser links its own file there
    BeforeObjectLink race =
        new BeforeObjectLink(
            FileOps.real(),
            volumeA,
            SealedNames.object("k1"),
            () -> {
              try {
                assertThat(onA.publish(KEY, winnerFile)).isTrue();
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
    SealedEvidenceStore loser = open(race);

    assertThatThrownBy(() -> loser.put(request(KEY, "the loser's bytes")))
        .isInstanceOf(EvidenceIntegrityException.class)
        .isNotInstanceOf(EvidenceCorruptionException.class);

    assertThat(race.fired()).isEqualTo(1);
    assertThat(SealedTamper.objectPath(volumeB, KEY))
        .as("no copy of the loser's bytes")
        .doesNotExist();
    assertThat(Files.readAllBytes(SealedTamper.objectPath(volumeA, KEY))).isEqualTo(winnerFile);
    assertThat(temporaryFiles(volumeA)).isEmpty();
    assertThat(temporaryFiles(volumeB)).isEmpty();
    // the winner's own bytes still complete the second volume
    assertThat(open().put(new PutRequest(KEY, winnerPayload, "text/plain", retention())))
        .isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThat(Files.readAllBytes(SealedTamper.objectPath(volumeB, KEY))).isEqualTo(winnerFile);
  }

  // ---------------------------------------------------------------- interruption (CONCURRENCY-5)

  /** What a call that ran in a thread of its own threw, and whether the thread kept its flag. */
  private record Outcome(Throwable thrown, boolean flagStillSet) {}

  /** Runs {@code call} in a fresh thread; reading the flag at the end clears it, for the pool. */
  private static Outcome inAThread(Callable<?> call) throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      return pool.submit(
              () -> {
                Throwable thrown = null;
                try {
                  call.call();
                } catch (Exception e) {
                  thrown = e;
                }
                return new Outcome(thrown, Thread.interrupted());
              })
          .get(60, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aThreadInterruptedBeforeAPutGetsAClearAnswerKeepsItsFlagAndLeavesNothingHalfWritten()
      throws Exception {
    SealedEvidenceStore store = open();

    Outcome outcome =
        inAThread(
            () -> {
              Thread.currentThread().interrupt();
              return store.put(request(KEY, "hello"));
            });

    assertThat(outcome.thrown())
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessage("volume a failed: interrupted")
        .hasCauseInstanceOf(ClosedByInterruptException.class);
    assertThat(outcome.flagStillSet()).as("the interrupt is not swallowed").isTrue();
    assertThat(temporaryFiles(volumeA)).isEmpty();
    assertThat(temporaryFiles(volumeB)).isEmpty();
    assertThat(SealedTamper.objectPath(volumeA, KEY)).doesNotExist();
    assertThat(SealedTamper.objectPath(volumeB, KEY)).doesNotExist();
    assertThat(store.get(KEY)).isEmpty();
    // a retry in a thread whose flag is clear is acknowledged
    assertThat(store.put(request(KEY, "hello"))).isEqualTo(PutOutcome.CREATED);
    assertThat(store.scrub()).isEmpty();
  }

  @Test
  void aThreadInterruptedBetweenTheVolumesLeavesACompleteCopyOnTheFirstAndAByteIdenticalRetryHeals()
      throws Exception {
    AtomicBoolean armed = new AtomicBoolean();
    FailingFileOps ops =
        new FailingFileOps(FileOps.real())
            .before(
                Op.WRITE_AND_FORCE,
                path -> armed.get() && path.startsWith(volumeB),
                path -> Thread.currentThread().interrupt());
    SealedEvidenceStore store = open(ops);
    armed.set(true); // not before the open: its hard-link self-test writes to volume b too

    Outcome outcome = inAThread(() -> store.put(request(KEY, "hello")));

    armed.set(false);
    assertThat(outcome.thrown())
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessage("volume b failed: interrupted");
    assertThat(outcome.flagStillSet()).isTrue();
    assertThat(temporaryFiles(volumeA)).isEmpty();
    assertThat(temporaryFiles(volumeB)).isEmpty();
    assertThat(SealedTamper.objectPath(volumeB, KEY)).doesNotExist();
    // the copy on volume a is whole and is served; the gap is the one scrub knows
    assertThat(store.get(KEY).orElseThrow().bytes()).isEqualTo(utf8("hello"));
    assertThat(store.scrub()).containsExactly("b landing/k1 MISSING_COPY");
    assertThat(store.put(request(KEY, "hello"))).isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThat(store.scrub()).isEmpty();
    assertThat(Files.readAllBytes(SealedTamper.objectPath(volumeB, KEY)))
        .isEqualTo(Files.readAllBytes(SealedTamper.objectPath(volumeA, KEY)));
  }

  @Test
  void everyKindOfInterruptedIoIsTheInterruptedAnswerAndAPlainIoErrorStaysOne() throws Exception {
    Map<String, Supplier<IOException>> kinds = new TreeMap<>();
    kinds.put("closed by interrupt", ClosedByInterruptException::new);
    kinds.put("interrupted io", () -> new InterruptedIOException("injected"));
    for (Map.Entry<String, Supplier<IOException>> kind : kinds.entrySet()) {
      AtomicBoolean armed = new AtomicBoolean();
      FailingFileOps ops =
          new FailingFileOps(FileOps.real())
              .failNextWith(
                  Op.WRITE_AND_FORCE, p -> armed.get() && p.startsWith(volumeA), kind.getValue());
      SealedEvidenceStore store = open(ops);
      armed.set(true);

      Outcome outcome = inAThread(() -> store.put(request(KEY, "hello")));

      assertThat(outcome.thrown())
          .as(kind.getKey())
          .isInstanceOf(EvidenceUnavailableException.class)
          .hasMessage("volume a failed: interrupted");
      assertThat(outcome.flagStillSet()).as("%s: the flag is set again", kind.getKey()).isTrue();
      assertThat(temporaryFiles(volumeA)).isEmpty();
      assertThat(store.get(KEY)).isEmpty();
      deleteTree(volumeA);
      deleteTree(volumeB);
    }

    // an ordinary I/O error is not an interruption: its text, and no flag
    AtomicBoolean armed = new AtomicBoolean();
    FailingFileOps plain =
        new FailingFileOps(FileOps.real())
            .failNextWith(
                Op.WRITE_AND_FORCE,
                p -> armed.get() && p.startsWith(volumeA),
                () -> new IOException("disk"));
    SealedEvidenceStore store = open(plain);
    armed.set(true);

    Outcome outcome = inAThread(() -> store.put(request(KEY, "hello")));

    assertThat(outcome.thrown())
        .isInstanceOf(EvidenceUnavailableException.class)
        .hasMessage("volume a failed: I/O error");
    assertThat(outcome.flagStillSet()).isFalse();
  }

  // ---------------------------------------------------------------- scrub and the key lock

  @Test
  void scrubWaitsForAPutThatHoldsTheKeyAndDoesNotReportItsFirstCopyAsMissing() throws Exception {
    Path objectOnA = SealedTamper.objectPath(volumeA, KEY);
    Path objectOnB = SealedTamper.objectPath(volumeB, KEY);
    CountDownLatch putAtTheLinkOnB = new CountDownLatch(1);
    CountDownLatch releasePut = new CountDownLatch(1);
    CountDownLatch scrubReadsA = new CountDownLatch(1);
    AtomicBoolean scrubbing = new AtomicBoolean();
    FailingFileOps ops =
        new FailingFileOps(FileOps.real())
            .before(
                Op.LINK,
                objectOnB::equals,
                path -> {
                  putAtTheLinkOnB.countDown();
                  awaitOrFail(releasePut);
                })
            .before(
                Op.READ,
                path -> scrubbing.get() && path.equals(objectOnA),
                path -> scrubReadsA.countDown());
    SealedEvidenceStore store = open(ops);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<PutOutcome> put = pool.submit(() -> store.put(request(KEY, "hello")));
      assertThat(putAtTheLinkOnB.await(60, TimeUnit.SECONDS)).isTrue();
      // the put holds the key: the copy on volume a is whole, the one on volume b is not there yet
      assertThat(objectOnA).exists();
      assertThat(objectOnB).doesNotExist();

      AtomicReference<Thread> scrubThread = new AtomicReference<>();
      Future<List<String>> scrub =
          pool.submit(
              () -> {
                scrubThread.set(Thread.currentThread());
                scrubbing.set(true); // set before the call, so that every read of it is counted
                return store.scrub();
              });
      // a positive wait (bounded): the scrub thread is inside scrub() and parked, which is where a
      // scrub that takes the key lock waits for the put; one that does not take it reads the copy
      // on volume a and finishes, and this wait ends at once with the read recorded
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (!scrub.isDone() && !isParkedInScrub(scrubThread.get())) {
        assertThat(System.nanoTime()).as("the scrub thread never parked").isLessThan(deadline);
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
      }
      boolean readTheKeyWhilePutHeldIt = scrubReadsA.getCount() == 0;
      boolean finishedWhilePutHeldIt = scrub.isDone();
      releasePut.countDown();

      assertThat(put.get(60, TimeUnit.SECONDS)).isEqualTo(PutOutcome.CREATED);
      List<String> findings = scrub.get(60, TimeUnit.SECONDS);
      assertThat(readTheKeyWhilePutHeldIt)
          .as("scrub read the key while a put held its lock")
          .isFalse();
      assertThat(finishedWhilePutHeldIt).as("scrub finished while a put held the key").isFalse();
      assertThat(findings).as("a key between two puts is whole").isEmpty();
    } finally {
      releasePut.countDown();
      pool.shutdownNow();
    }
    assertThat(store.scrub()).isEmpty();
  }

  /** True when the thread is parked (or blocked) in a call of {@code scrub()} of the store. */
  private static boolean isParkedInScrub(Thread thread) {
    if (thread == null) {
      return false;
    }
    Thread.State state = thread.getState();
    if (state != Thread.State.WAITING
        && state != Thread.State.TIMED_WAITING
        && state != Thread.State.BLOCKED) {
      return false;
    }
    return Arrays.stream(thread.getStackTrace())
        .anyMatch(
            frame ->
                frame.getClassName().equals(SealedEvidenceStore.class.getName())
                    && frame.getMethodName().equals("scrub"));
  }

  private static void awaitOrFail(CountDownLatch latch) throws IOException {
    try {
      if (!latch.await(60, TimeUnit.SECONDS)) {
        throw new IOException("the test did not release the latch");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("interrupted while the test held a call");
    }
  }

  // ---------------------------------------------------------------- the key lock (TEETH-10)

  @Test
  void manyConcurrentCallsOnOneKeyThroughOneStoreAreAllAcknowledged() throws Exception {
    SealedEvidenceStore store = open();
    int callers = 8;
    // The key's lock is what keeps these callers from overtaking one another until a call has used
    // its three tries (without it 4 rounds in 8 end in an "unavailable" answer): many rounds, so
    // that a store without the lock is caught on practically every run.
    for (int round = 0; round < 25; round++) {
      EvidenceKey key = new EvidenceKey("race/lock-" + round);
      store.put(request(key, "v" + round));
      Instant base = retention().retainUntil();
      List<Callable<Boolean>> tasks = new ArrayList<>();
      for (int i = 1; i <= callers; i++) {
        Instant later = base.plus(Duration.ofDays(i));
        boolean extend = i % 2 == 0;
        tasks.add(
            () -> {
              try {
                if (extend) {
                  store.extendRetention(key, later);
                } else {
                  store.setLegalHold(key, true);
                }
                return true;
              } catch (EvidenceRetentionException e) {
                return true; // a later instant won first: a legitimate refusal
              } catch (EvidenceUnavailableException e) {
                return false; // gave up after three tries
              }
            });
      }

      assertThat(runTogether(tasks)).as("round %d", round).containsOnly(true);
    }
    assertThat(store.scrub()).isEmpty();
  }
}
