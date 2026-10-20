package dev.regpack.evidence.sealed;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.evidence.sealed.RecordingFileOps.Op;
import dev.regpack.model.Sha256;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore.PutOutcome;
import dev.regpack.ports.evidence.EvidenceStore.PutRequest;
import dev.regpack.ports.evidence.EvidenceStore.Retention;
import dev.regpack.ports.evidence.EvidenceStore.RetentionClass;
import dev.regpack.ports.evidence.EvidenceStore.StoredObject;
import dev.regpack.testkit.clock.ManualClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T8: P-SEALED on both operating systems (review focus EF9, A7). Every test of this class has the
 * same assertions on Linux and on Windows, with two exceptions that are a pair: the test of a real
 * directory flush holds only where the platform can force a directory, its twin only where it
 * cannot, and each is guarded by {@link HostPlatform} (the {@code os.name} property), so every
 * platform runs one of the two. Every test works in a {@code @TempDir}.
 */
class SealedPortabilityTest {
  private static final Pattern RESERVED_DEVICE_NAME =
      Pattern.compile("(con|prn|aux|nul|com[1-9]|lpt[1-9])");

  @TempDir Path root;

  private final HostPlatform platform = HostPlatform.current();
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

  private Retention retention() {
    return new Retention(new RetentionClass("landing"), clock.now().plus(Duration.ofDays(1826)));
  }

  private PutRequest request(EvidenceKey key, byte[] bytes, String mediaType) {
    return new PutRequest(key, bytes, mediaType, retention());
  }

  private static byte[] utf8(String text) {
    return text.getBytes(UTF_8);
  }

  private static void deleteTree(Path directory) throws IOException {
    try (Stream<Path> walk = Files.walk(directory)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }

  /** The name of every file and directory below the object tree of the volume. */
  private static List<Path> entriesOf(Path volumeRoot) throws IOException {
    try (Stream<Path> walk = Files.walk(volumeRoot.resolve("o"))) {
      return walk.filter(path -> !path.equals(volumeRoot.resolve("o"))).toList();
    }
  }

  /** Puts every key with the bytes of its own text and reads them all back, also after a reopen. */
  private SealedEvidenceStore storeAndReadBack(List<EvidenceKey> keys) {
    SealedEvidenceStore store = open();
    for (EvidenceKey key : keys) {
      assertThat(store.put(request(key, utf8("bytes of " + key.path()), "text/plain")))
          .as(key.path())
          .isEqualTo(PutOutcome.CREATED);
    }
    for (SealedEvidenceStore reader : List.of(store, open())) {
      for (EvidenceKey key : keys) {
        assertThat(reader.get(key).orElseThrow().bytes())
            .as(key.path())
            .isEqualTo(utf8("bytes of " + key.path()));
      }
      assertThat(reader.list("", Optional.empty(), 1000))
          .containsExactlyElementsOf(
              keys.stream().sorted(Comparator.comparing(EvidenceKey::path)).toList());
      assertThat(reader.scrub()).isEmpty();
    }
    return store;
  }

  // ---------------------------------------------------------------- names

  @Test
  void keysThatDifferOnlyInCaseDoNotCollide() throws IOException {
    List<EvidenceKey> keys =
        List.of(
            new EvidenceKey("case/KeyX"),
            new EvidenceKey("case/keyx"),
            new EvidenceKey("case/KEYX"),
            new EvidenceKey("case/kEyX"),
            new EvidenceKey("Case/keyx"),
            new EvidenceKey("CASE/keyx"));

    SealedEvidenceStore store = storeAndReadBack(keys);

    assertThat(store.list("case/", Optional.empty(), 100)).hasSize(4);
    assertThat(store.list("Case/", Optional.empty(), 100)).hasSize(1);
    // on disk no two names of one directory are equal once the case is folded, so a file system
    // that ignores case (NTFS by default, APFS) holds all of them
    for (Path volumeRoot : List.of(volumeA, volumeB)) {
      List<Path> directories = new ArrayList<>(List.of(volumeRoot.resolve("o")));
      for (Path entry : entriesOf(volumeRoot)) {
        if (Files.isDirectory(entry)) {
          directories.add(entry);
        }
      }
      for (Path directory : directories) {
        try (Stream<Path> children = Files.list(directory)) {
          List<String> names =
              children
                  .map(child -> child.getFileName().toString().toLowerCase(Locale.ROOT))
                  .toList();
          assertThat(new HashSet<>(names))
              .as("below %s", directory.getFileName())
              .hasSameSizeAs(names);
        }
      }
    }
  }

  @Test
  void windowsReservedNamesAreStoredAndRead() throws IOException {
    List<EvidenceKey> keys =
        List.of(
            new EvidenceKey("con"),
            new EvidenceKey("nul"),
            new EvidenceKey("com1"),
            new EvidenceKey("lpt9"),
            new EvidenceKey("CON"),
            new EvidenceKey("aux.txt"),
            new EvidenceKey("Nul.log"),
            new EvidenceKey("prn"),
            new EvidenceKey("com9.tar.gz"),
            new EvidenceKey("con/nul/aux"),
            new EvidenceKey("x/com1.txt"),
            new EvidenceKey("lpt1/lpt1"));

    storeAndReadBack(keys);

    for (Path volumeRoot : List.of(volumeA, volumeB)) {
      for (Path entry : entriesOf(volumeRoot)) {
        String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
        String base = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        assertThat(RESERVED_DEVICE_NAME.matcher(base).matches())
            .as("a file system name that Windows reserves: %s", name)
            .isFalse();
      }
    }
  }

  @Test
  void aSegmentEndingInADotRoundTrips() throws IOException {
    List<EvidenceKey> keys =
        List.of(
            new EvidenceKey("end."),
            new EvidenceKey("a./b."),
            new EvidenceKey("dots..."),
            new EvidenceKey("x.y./z.."),
            new EvidenceKey("trailing./and.dot"));

    storeAndReadBack(keys);

    // Windows drops a trailing dot or space from a name: none of ours has one
    for (Path volumeRoot : List.of(volumeA, volumeB)) {
      for (Path entry : entriesOf(volumeRoot)) {
        String name = entry.getFileName().toString();
        assertThat(name).as("name on disk").doesNotEndWith(".").doesNotEndWith(" ");
      }
    }
  }

  @Test
  void aKeyOfFiveHundredTwelveCharactersRoundTrips() throws IOException {
    // upper case is the worst case for the encoded path: every capital becomes two characters
    EvidenceKey key =
        EvidenceKey.of(
            "A".repeat(100),
            "B".repeat(100),
            "C".repeat(100),
            "D".repeat(100),
            "E".repeat(100),
            "LastSeg");
    assertThat(key.path()).hasSize(512);
    assertThat(SealedTamper.objectPath(volumeA, key).toString().length()).isGreaterThan(1000);

    SealedEvidenceStore store = storeAndReadBack(List.of(key));

    assertThat(store.list("A", Optional.empty(), 10)).containsExactly(key);
    assertThat(store.list(key.path(), Optional.empty(), 10)).containsExactly(key);
    assertThat(store.put(request(key, utf8("bytes of " + key.path()), "text/plain")))
        .isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    store.extendRetention(key, retention().retainUntil().plus(Duration.ofDays(1)));
    store.setLegalHold(key, true);
    // the paths hold for the copy a repair makes as well
    deleteTree(volumeB);
    SealedEvidenceStore replaced = open();
    assertThat(replaced.repair()).isEqualTo(1);
    assertThat(Files.readAllBytes(SealedTamper.objectPath(volumeB, key)))
        .isEqualTo(Files.readAllBytes(SealedTamper.objectPath(volumeA, key)));
    assertThat(Files.readAllBytes(SealedTamper.metaPath(volumeB, key)))
        .isEqualTo(Files.readAllBytes(SealedTamper.metaPath(volumeA, key)));
    assertThat(replaced.get(key).orElseThrow().legalHold()).isTrue();
    assertThat(replaced.scrub()).isEmpty();
  }

  // ---------------------------------------------------------------- directory flush

  @Test
  void directoryFlushIsAttemptedWhereThePlatformCanAndTheLimitsSayWhereNot() {
    boolean platformCanForceADirectory = !platform.isWindows();
    RecordingFileOps recording = RecordingFileOps.over(FileOps.real());
    SealedEvidenceStore store = open(recording);
    recording.clear();

    store.put(request(new EvidenceKey("flush/one/two/three"), utf8("x"), "text/plain"));

    assertThat(recording.supportsDirectorySync()).isEqualTo(platformCanForceADirectory);
    assertThat(recording.paths(Op.SYNC_DIRECTORY).isEmpty()).isEqualTo(!platformCanForceADirectory);
    // the object itself is forced on every platform: a forced write precedes the link
    List<Op> ops = recording.ops();
    assertThat(ops.indexOf(Op.WRITE_AND_FORCE)).isGreaterThanOrEqualTo(0);
    assertThat(ops.indexOf(Op.WRITE_AND_FORCE)).isLessThan(ops.indexOf(Op.LINK));
    // the limits say where a directory cannot be forced, on every platform
    assertThat(store.limits())
        .contains("Directory durability")
        .contains("forced on POSIX systems only")
        .contains("on Windows the JDK cannot force a directory");
  }

  @Test
  void onPosixTheDirectoryOfANewObjectAndTheParentsOfNewDirectoriesAreForced() {
    Assumptions.assumeFalse(platform.isWindows(), "this platform cannot force a directory");
    RecordingFileOps recording = RecordingFileOps.over(FileOps.real());
    SealedEvidenceStore store = open(recording);
    recording.clear();

    store.put(request(new EvidenceKey("flush/one/two/three"), utf8("x"), "text/plain"));

    List<Path> synced = recording.paths(Op.SYNC_DIRECTORY);
    for (Path volumeRoot : List.of(volumeA, volumeB)) {
      Path objects = volumeRoot.resolve("o");
      assertThat(synced)
          .contains(
              volumeRoot,
              objects,
              objects.resolve("d-flush"),
              objects.resolve("d-flush").resolve("d-one"),
              objects.resolve("d-flush").resolve("d-one").resolve("d-two"));
    }
    // a second put of the same key syncs again: a found name is not taken as durable
    recording.clear();
    assertThat(store.put(request(new EvidenceKey("flush/one/two/three"), utf8("x"), "text/plain")))
        .isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThat(recording.paths(Op.SYNC_DIRECTORY)).isNotEmpty();
  }

  @Test
  void onWindowsNoDirectoryIsForcedAndTheStoreSaysSo() throws IOException {
    Assumptions.assumeTrue(platform.isWindows(), "this platform can force a directory");
    RecordingFileOps recording = RecordingFileOps.over(FileOps.real());
    SealedEvidenceStore store = open(recording);
    recording.clear();

    store.put(request(new EvidenceKey("flush/one/two/three"), utf8("x"), "text/plain"));
    store.put(request(new EvidenceKey("flush/one/two/three"), utf8("x"), "text/plain"));

    assertThat(recording.paths(Op.SYNC_DIRECTORY)).isEmpty();
    FileOps.real().syncDirectory(volumeA); // does nothing and does not fail
    assertThat(store.limits())
        .contains("on Windows the JDK cannot force a directory")
        .contains("a new file name may not survive a power failure");
  }

  // ---------------------------------------------------------------- content

  /**
   * Text with a Latin-1 letter, a punctuation dash, two CJK letters and a character outside the
   * basic plane (a surrogate pair in UTF-16), built from code points so that this file stays ASCII.
   */
  private static String unicodeText() {
    return new StringBuilder("Z")
        .appendCodePoint(0xFC)
        .append("rich ")
        .appendCodePoint(0x2013)
        .append(' ')
        .appendCodePoint(0x6771)
        .appendCodePoint(0x4EAC)
        .append(' ')
        .appendCodePoint(0x1F600)
        .toString();
  }

  @Test
  void unicodeMediaTypeAndZeroByteObjectRoundTrip() {
    String mediaType = "text/plain; charset=utf-8; title=\"" + unicodeText() + "\"";
    EvidenceKey zero = new EvidenceKey("content/zero");
    EvidenceKey unicode = new EvidenceKey("content/unicode");
    byte[] unicodeBytes = utf8(unicodeText());
    assertThat(unicodeBytes.length).isGreaterThan(unicodeText().length());
    SealedEvidenceStore store = open();

    assertThat(store.put(request(zero, new byte[0], mediaType))).isEqualTo(PutOutcome.CREATED);
    assertThat(store.put(request(unicode, unicodeBytes, mediaType))).isEqualTo(PutOutcome.CREATED);

    for (SealedEvidenceStore reader : List.of(store, open())) {
      StoredObject empty = reader.get(zero).orElseThrow();
      assertThat(empty.bytes()).isEmpty();
      assertThat(empty.sha256()).isEqualTo(Sha256.of(new byte[0]));
      assertThat(empty.mediaType()).isEqualTo(mediaType);
      StoredObject text = reader.get(unicode).orElseThrow();
      assertThat(text.bytes()).isEqualTo(unicodeBytes);
      assertThat(text.sha256()).isEqualTo(Sha256.of(unicodeBytes));
      assertThat(text.mediaType()).isEqualTo(mediaType);
      assertThat(reader.scrub()).isEmpty();
    }
    // the same bytes again are not a new object, and a zero-byte object still counts as stored
    assertThat(store.put(request(zero, new byte[0], "application/octet-stream")))
        .isEqualTo(PutOutcome.ALREADY_PRESENT_IDENTICAL);
    assertThat(store.get(zero).orElseThrow().mediaType()).isEqualTo(mediaType);
  }
}
