package dev.regpack.evidence.sealed;

import dev.regpack.model.Sha256;
import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceCorruptionException;
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceRetentionException;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * The P-SEALED evidence store (spec 5.4, D-E5): append-only, hash-sealed files on two volumes, each
 * of which is forced to the device before a put is acknowledged. The grade is tamper-evident, not
 * immutable: it does not stop someone with storage access from deleting or rewriting files. It
 * claims the durability of spec 5.4 (an acknowledged write survives the loss of one volume) and
 * says how many failure domains the two volumes span only as far as {@link VolumeProbe} can show.
 *
 * <p><b>Put.</b> Under a 64-way striped lock of the key, both volumes are read first. A damaged
 * copy on either volume, and two whole copies that differ in any byte (the payload, or the header
 * alone: media type, retention class, retain-until; what {@link #scrub()} calls DIVERGENT), are an
 * {@link EvidenceCorruptionException}; the retention methods refuse such a pair in the same way.
 * The volumes are byte-identical by construction, so this refuses nothing legitimate. A copy with
 * other bytes than the request is an {@link EvidenceIntegrityException}; nothing is written in any
 * of these cases. If exactly one volume holds the key, its verified file is published to the other,
 * so the volumes stay byte-identical and the first header (media type, write time, retention class)
 * is kept. If neither holds it, the object file is built and published to volume a, then to volume
 * b; the call returns after both. The result is {@code CREATED} only when neither volume held the
 * key. A failure on the second volume is an {@link EvidenceUnavailableException}; the object stays
 * on the first volume, is served from it, and a retry with the same bytes completes the second. A
 * retry with other bytes (a journal rewrite with another {@code clock_instant}) is refused as other
 * bytes before it touches the second volume; {@code JournalWriter} then puts the stored object
 * itself again, which completes the second volume or throws, so that it never acknowledges a write
 * that only one volume holds.
 *
 * <p><b>Reads.</b> {@code get} serves the payload and the header fields of the first copy that
 * passes its own seal (volume a, then b). Retention and legal hold are kept in the object headers
 * and in meta files with a revision; the retain-until instant is the maximum over the headers of
 * both copies (when both are readable) and both metas, so a header that was cut short on one volume
 * shortens nothing (the retention class is that of the header with the longest retain-until), and
 * the hold comes from the meta of the higher revision (two metas of one revision merge: later
 * retention, hold of either). A pair of copies that differ is an alarm for {@link #scrub()} and for
 * every write; a read still serves it.
 *
 * <p><b>Health.</b> Each volume has a marker file with its id. A volume without its marker is a
 * replaced disk: the store is {@link #degraded()}, declares no synchronous acknowledgement, refuses
 * every write and is brought back by {@link #repair()}. Messages carry key paths, volume ids and
 * fixed texts only, never file names.
 *
 * <p><b>Interruption.</b> A thread that is interrupted while a forced write is under way has its
 * file channel closed by the JDK. The call then throws an {@link EvidenceUnavailableException}
 * whose message ends in "interrupted" (not "I/O error"), the temporary file is removed, and the
 * thread's interrupt flag is set again before the call returns. The state left is one of the
 * designed ones: nothing written, or a whole object on the first volume only, which a retry with
 * the same bytes completes. A caller that retries on {@link EvidenceUnavailableException} in the
 * same thread must clear the flag first ({@code Thread.interrupted()}) or honour it and stop: with
 * the flag set, every further forced write fails the same way. Waits for the key locks are not
 * interruptible.
 *
 * <p><b>Scrub and repair.</b> {@link #scrub()} reads each key under the key's lock, so it sees a
 * key between two puts, never in the middle of one; its findings are a snapshot per key, and a
 * defect that arises after its key was read is not in them. {@link #repair()} takes the same locks,
 * and the store is write-closed ({@link #degraded()}) for as long as a replaced volume has no
 * marker, which a repair writes last: for a repair that is the time to read both copies of every
 * key.
 *
 * <p><b>One process owns a volume pair.</b> The read-compare-replace of a meta file is atomic only
 * through locks that are shared inside one JVM, and {@link #open} takes no file lock. A second
 * process over the same two directories (a rolling restart that overlaps, a sender that was not
 * stopped after a failover, an operator tool) silently loses legal-hold and retention-extension
 * updates. Advisory file locks are unreliable on network file systems; the durable cure is the
 * immutable per-revision meta file of P3c. "Forced to the device" above holds for the data of a
 * file; what {@link #limits()} says about directories (none forced on Windows, a gap after a
 * restart on POSIX systems) bounds it, and {@link #limits()} lists everything else that is not
 * shown.
 */
public final class SealedEvidenceStore implements EvidenceStore {
  private static final Grade GRADE = new Grade("P-SEALED", "");
  private static final Pattern VOLUME_ID = Pattern.compile("[a-z0-9-]{1,32}");
  private static final int STRIPES = 64;
  private static final int META_TRIES = 3;

  /**
   * Where the two volumes are and how they are told apart.
   *
   * @param volumeA the root of the first volume (absolute); for a new store a new empty directory
   *     below the mount point of the volume, not the mount point itself (lost+found, System Volume
   *     Information and $RECYCLE.BIN count as content and make {@link #open} refuse two unmarked
   *     roots)
   * @param volumeB the root of the second volume (absolute), like {@code volumeA}; the two real
   *     paths must differ and neither may lie inside the other (checked by {@link #open})
   * @param volumeIdA 1 to 32 characters of a-z, 0-9 and '-'; stored in the marker of volume a
   * @param volumeIdB like {@code volumeIdA}, and different from it
   * @param clock the source of the write time of an object
   * @param probe how the failure domain of a volume is found
   */
  public record Config(
      Path volumeA,
      Path volumeB,
      String volumeIdA,
      String volumeIdB,
      Clock clock,
      VolumeProbe probe) {
    public Config {
      Objects.requireNonNull(volumeA, "volumeA");
      Objects.requireNonNull(volumeB, "volumeB");
      Objects.requireNonNull(volumeIdA, "volumeIdA");
      Objects.requireNonNull(volumeIdB, "volumeIdB");
      Objects.requireNonNull(clock, "clock");
      Objects.requireNonNull(probe, "probe");
      if (!volumeA.isAbsolute() || !volumeB.isAbsolute()) {
        throw new IllegalArgumentException("the root of a volume must be an absolute path");
      }
      if (!VOLUME_ID.matcher(volumeIdA).matches() || !VOLUME_ID.matcher(volumeIdB).matches()) {
        throw new IllegalArgumentException(
            "a volume id has 1 to 32 characters of a-z, 0-9 and '-'");
      }
      if (volumeIdA.equals(volumeIdB)) {
        throw new IllegalArgumentException("the two volume ids must be different");
      }
    }

    /** With the probe of the machine, {@link VolumeProbe#fileStores()}. */
    public Config(Path volumeA, Path volumeB, String volumeIdA, String volumeIdB, Clock clock) {
      this(volumeA, volumeB, volumeIdA, volumeIdB, clock, VolumeProbe.fileStores());
    }
  }

  /** One of the two volumes. */
  private record Vol(String id, Path root, SealedVolume volume) {}

  private enum Kind {
    PRESENT,
    ABSENT,
    CORRUPT,
    UNREACHABLE
  }

  /** What one volume holds under a key. */
  private record Copy(Kind kind, byte[] file, Optional<SealedFormat.Decoded> decoded) {
    static final Copy ABSENT = new Copy(Kind.ABSENT, new byte[0], Optional.empty());
    static final Copy CORRUPT = new Copy(Kind.CORRUPT, new byte[0], Optional.empty());
    static final Copy UNREACHABLE = new Copy(Kind.UNREACHABLE, new byte[0], Optional.empty());

    static Copy present(byte[] file, SealedFormat.Decoded decoded) {
      return new Copy(Kind.PRESENT, file, Optional.of(decoded));
    }
  }

  /** What one volume holds as the meta of a key. */
  private record MetaRead(Kind kind, Optional<SealedFormat.Meta> meta) {
    static final MetaRead ABSENT = new MetaRead(Kind.ABSENT, Optional.empty());
    static final MetaRead CORRUPT = new MetaRead(Kind.CORRUPT, Optional.empty());
    static final MetaRead UNREACHABLE = new MetaRead(Kind.UNREACHABLE, Optional.empty());

    static MetaRead present(SealedFormat.Meta meta) {
      return new MetaRead(Kind.PRESENT, Optional.of(meta));
    }
  }

  /**
   * The retention state of a key: the retain-until instant (maximum over header and metas), the
   * legal hold (of the higher revision), the highest revision, and whether both volumes already
   * hold the same meta (or none).
   */
  private record Effective(
      Instant retainUntil, boolean legalHold, long topRevision, boolean same) {}

  /**
   * The header of the first valid copy, the retention state read with it, and the volumes whose
   * meta is present (volume a first).
   */
  private record Snapshot(SealedFormat.Header header, Effective effective, List<Vol> metaHolders) {}

  private enum Health {
    OK(""),
    UNMARKED("its marker is missing (a replaced disk; run repair)"),
    OTHER_ID("its marker names another volume"),
    UNREACHABLE("it cannot be read");

    private final String reason;

    Health(String reason) {
      this.reason = reason;
    }
  }

  @FunctionalInterface
  private interface IoCall<T> {
    T run() throws IOException;
  }

  @FunctionalInterface
  private interface IoRun {
    void run() throws IOException;
  }

  private final Vol first;
  private final Vol second;
  private final List<Vol> volumes;
  private final Clock clock;
  private final VolumeProbe probe;
  private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

  private SealedEvidenceStore(Config config, Vol first, Vol second) {
    this.first = first;
    this.second = second;
    this.volumes = List.of(first, second);
    this.clock = config.clock();
    this.probe = config.probe();
    for (int i = 0; i < STRIPES; i++) {
      locks[i] = new ReentrantLock();
    }
  }

  // ---------------------------------------------------------------- open

  /**
   * Opens the store over the two directories of the configuration with the real file operations.
   *
   * @throws EvidenceUnavailableException when a directory cannot be used, a volume cannot make a
   *     hard link, a marker names another volume, or both volumes are unmarked and a root is not
   *     empty
   * @throws IllegalArgumentException when the two roots are one directory or one lies in the other
   *     (also through a link or another spelling of the path)
   */
  public static SealedEvidenceStore open(Config config) {
    return open(config, FileOps.real());
  }

  /**
   * The two roots must differ with neither containing the other (decided on the absolute normalised
   * paths and on the real path of the nearest existing ancestor, before anything is created: a
   * refused open leaves the file system as it was, and the real paths are checked again once the
   * roots exist). Both roots are then created (with their parents synced, see {@link
   * SealedVolume#ensureRoot}). Two missing markers on two empty roots are a new store (both markers
   * are written); one missing marker is a replaced volume (the store is degraded and nothing is
   * written), unless neither volume holds an object or a meta: that is a first open that was
   * interrupted between its two markers, and the open writes the missing one. A marker with another
   * id, or two missing markers on volumes that hold objects, refuse the open. Stale temporary files
   * are swept: those of dead processes, and those of this pid that an earlier incarnation of the
   * process left, except a complete temporary file of an object or a meta that may be the only
   * surviving name of an acknowledged write: that one is kept and reported by {@link #scrub()} (see
   * {@link SealedVolume#sweepTemporaries}). Each volume also proves that it can make a hard link as
   * the publish protocol needs it, and sync its root where the platform can ({@link
   * SealedVolume#checkHardLinks}).
   *
   * <p><b>Operator notes.</b> The root of a new store must be a new empty directory below the mount
   * point: lost+found, System Volume Information and $RECYCLE.BIN count as content, so two unmarked
   * roots of which either holds an entry are refused with a text that names the volume and the
   * number of its entries and no file. A marker is read with its trailing white space removed. Two
   * volumes that are not mounted (empty mount-point directories on the system disk) look like two
   * new volumes and open as a new store on the system disk. The marker is a dot file: a restore by
   * a shell glob over the volume leaves it out, and the next open refuses (two unmarked roots that
   * hold objects); restore the directory itself or the marker. Opening takes no lock and sweeps
   * every directory of both volumes (see {@link #limits()}, items 1 to 3 and 11 to 15); only one
   * process may use a volume pair.
   */
  static SealedEvidenceStore open(Config config, FileOps ops) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(ops, "ops");
    Path realA;
    Path realB;
    try {
      // decided before any directory is created: a refused open leaves the file system as it was
      requireDisjointRoots(config.volumeA(), config.volumeB());
      new SealedVolume(config.volumeA(), config.volumeIdA(), ops).ensureRoot();
      new SealedVolume(config.volumeB(), config.volumeIdB(), ops).ensureRoot();
      realA = config.volumeA().toRealPath();
      realB = config.volumeB().toRealPath();
    } catch (IOException e) {
      throw new EvidenceUnavailableException("the evidence volumes cannot be opened: I/O error", e);
    }
    // the same test on the real paths of the roots that now exist (a link made since the check)
    requireNeitherInsideTheOther(realA, realB);
    Vol first =
        new Vol(config.volumeIdA(), realA, new SealedVolume(realA, config.volumeIdA(), ops));
    Vol second =
        new Vol(config.volumeIdB(), realB, new SealedVolume(realB, config.volumeIdB(), ops));
    SealedEvidenceStore store = new SealedEvidenceStore(config, first, second);
    store.initialise();
    return store;
  }

  /**
   * The two roots must not be one directory or lie one in the other, decided on the absolute
   * normalised paths and on their location with the nearest existing ancestor resolved to its real
   * path (a link above a root that does not exist yet), before anything is created.
   */
  private static void requireDisjointRoots(Path rootA, Path rootB) throws IOException {
    Path normalA = rootA.normalize();
    Path normalB = rootB.normalize();
    requireNeitherInsideTheOther(normalA, normalB);
    requireNeitherInsideTheOther(locationOf(normalA), locationOf(normalB));
  }

  /** The root with its nearest existing ancestor replaced by that ancestor's real path. */
  private static Path locationOf(Path path) throws IOException {
    Path existing = path;
    while (existing != null && !Files.exists(existing)) {
      existing = existing.getParent();
    }
    return existing == null ? path : existing.toRealPath().resolve(existing.relativize(path));
  }

  private static void requireNeitherInsideTheOther(Path one, Path other) {
    if (one.equals(other) || one.startsWith(other) || other.startsWith(one)) {
      throw new IllegalArgumentException(
          "the two volume roots must be different directories, neither inside the other");
    }
  }

  private void initialise() {
    for (Vol vol : volumes) {
      run(vol, () -> vol.volume().checkHardLinks());
    }
    Optional<String> markerOfFirst = on(first, () -> first.volume().markerId());
    Optional<String> markerOfSecond = on(second, () -> second.volume().markerId());
    requireOwnMarker(first, markerOfFirst);
    requireOwnMarker(second, markerOfSecond);
    if (markerOfFirst.isEmpty() && markerOfSecond.isEmpty()) {
      boolean fresh =
          on(first, () -> first.volume().isEmpty()) && on(second, () -> second.volume().isEmpty());
      if (!fresh) {
        // Another opener may have written marker a between our reads of the markers and of the
        // volume: look at the markers once more before the volumes are called foreign.
        markerOfFirst = on(first, () -> first.volume().markerId());
        markerOfSecond = on(second, () -> second.volume().markerId());
        requireOwnMarker(first, markerOfFirst);
        requireOwnMarker(second, markerOfSecond);
        if (markerOfFirst.isEmpty() && markerOfSecond.isEmpty()) {
          throw new EvidenceUnavailableException(
              "both volumes are unmarked and their roots are not empty ("
                  + entriesInTheWay()
                  + "; the root of a new store must be a new empty directory below the mount"
                  + " point)");
        }
      } else {
        run(first, () -> first.volume().writeMarker());
        run(second, () -> second.volume().writeMarker());
        markerOfFirst = Optional.of(first.id());
        markerOfSecond = Optional.of(second.id());
      }
    }
    if (markerOfFirst.isEmpty() != markerOfSecond.isEmpty()) {
      finishInterruptedInitialisation(markerOfFirst.isEmpty() ? first : second);
    }
    for (Vol vol : volumes) {
      run(vol, () -> vol.volume().sweepTemporaries());
    }
  }

  /**
   * "volume a holds 2 entries, volume b holds 1 entry" for the volumes whose root is not empty: the
   * volume id and a number, never a name (Convention 8).
   */
  private String entriesInTheWay() {
    List<String> parts = new ArrayList<>();
    for (Vol vol : volumes) {
      int count = on(vol, () -> vol.volume().entriesInTheWay());
      if (count > 0) {
        parts.add("volume " + vol.id() + " holds " + count + (count == 1 ? " entry" : " entries"));
      }
    }
    return parts.isEmpty() ? "no entry found when counted" : String.join(", ", parts);
  }

  /**
   * One marker is missing. If neither volume holds an object or a meta, nothing was ever stored
   * that a replaced disk could have lost: the first open stopped between its two markers (a crash,
   * an I/O error, another opener that is still at it), and the missing marker is written (volume a
   * first, as the first open does). Otherwise the volume is a replaced disk and is left to {@link
   * #repair()}.
   */
  private void finishInterruptedInitialisation(Vol unmarked) {
    boolean nothingStored =
        on(first, () -> first.volume().holdsNothingButItsMarker())
            && on(second, () -> second.volume().holdsNothingButItsMarker());
    if (nothingStored) {
      run(unmarked, () -> unmarked.volume().writeMarker());
    }
  }

  private static void requireOwnMarker(Vol vol, Optional<String> marker) {
    if (marker.isPresent() && !marker.get().equals(vol.id())) {
      // Both ids are named so that an operator sees which configuration is wrong; a marker that is
      // not a volume id is not echoed (it is file content, not a name this code wrote).
      String found = VOLUME_ID.matcher(marker.get()).matches() ? marker.get() : "not a volume id";
      throw new EvidenceUnavailableException(
          "volume "
              + vol.id()
              + ": its marker names another volume ("
              + found
              + ", not "
              + vol.id()
              + ")");
    }
  }

  // ---------------------------------------------------------------- grade, durability, health

  @Override
  public Grade grade() {
    requireSomeVolumeReachable();
    return GRADE;
  }

  /**
   * {@code synchronousAcknowledgement} is true while both volumes are healthy (marked and
   * readable); the number of failure domains is 2 when both are healthy and the probe puts them in
   * different domains, else 1. The count rests on the probe alone (see {@link VolumeProbe}); that
   * the volumes are independent is attested in the G8 record.
   *
   * @throws EvidenceUnavailableException when no volume can be reached
   */
  @Override
  public Durability durability() {
    Health healthOfFirst = healthOf(first);
    Health healthOfSecond = healthOf(second);
    if (healthOfFirst == Health.UNREACHABLE && healthOfSecond == Health.UNREACHABLE) {
      throw new EvidenceUnavailableException("no evidence volume can be reached");
    }
    boolean healthy = healthOfFirst == Health.OK && healthOfSecond == Health.OK;
    int domains = 1;
    if (healthy) {
      try {
        if (!probe.domainOf(first.root()).equals(probe.domainOf(second.root()))) {
          domains = 2;
        }
      } catch (IOException e) {
        domains = 1; // a domain that cannot be read cannot be shown to be separate
      }
    }
    return new Durability(healthy, domains);
  }

  /**
   * True when a volume is not in its two-volume state: it lost its marker (a replaced disk), its
   * marker names another volume, or it cannot be read. Cleared by {@link #repair()} for a replaced
   * volume.
   */
  public boolean degraded() {
    return healthOf(first) != Health.OK || healthOf(second) != Health.OK;
  }

  private Health healthOf(Vol vol) {
    try {
      Optional<String> marker = vol.volume().markerId();
      if (marker.isEmpty()) {
        return Health.UNMARKED;
      }
      return marker.get().equals(vol.id()) ? Health.OK : Health.OTHER_ID;
    } catch (IOException e) {
      return Health.UNREACHABLE;
    }
  }

  private void requireSomeVolumeReachable() {
    if (healthOf(first) == Health.UNREACHABLE && healthOf(second) == Health.UNREACHABLE) {
      throw new EvidenceUnavailableException("no evidence volume can be reached");
    }
  }

  /** Every write needs both volumes: the first that is not healthy is named. */
  private void requireHealthy() {
    for (Vol vol : volumes) {
      Health health = healthOf(vol);
      if (health != Health.OK) {
        throw new EvidenceUnavailableException(
            "volume " + vol.id() + " is not available: " + health.reason);
      }
    }
  }

  // ---------------------------------------------------------------- put

  @Override
  public PutOutcome put(PutRequest request) {
    Objects.requireNonNull(request, "request");
    EvidenceKey key = request.key();
    byte[] payload = request.bytes();
    Sha256 digest = Sha256.of(payload);
    byte[] candidate = encode(request, payload, digest);
    ReentrantLock lock = lockOf(key);
    lock.lock();
    try {
      requireHealthy();
      Copy onFirst = readCopy(first, key);
      Copy onSecond = readCopy(second, key);
      refuseDamaged(first, onFirst, key);
      refuseDamaged(second, onSecond, key);
      refuseDivergent(onFirst, onSecond, key);
      refuseOtherBytes(onFirst, digest, key);
      refuseOtherBytes(onSecond, digest, key);
      boolean neitherHeldIt = onFirst.kind() == Kind.ABSENT && onSecond.kind() == Kind.ABSENT;
      byte[] file = candidate;
      if (onFirst.kind() == Kind.PRESENT) {
        file = onFirst.file();
      } else if (onSecond.kind() == Kind.PRESENT) {
        file = onSecond.file();
      }
      // every publish syncs the directories of its name, also when the name was already there
      boolean linkedFirst = publish(first, key, file);
      if (!linkedFirst && onFirst.kind() == Kind.ABSENT) {
        // a second store over the same directories took the name between the read and the link
        file = settle(first, key, digest);
        neitherHeldIt = false;
      }
      boolean linkedSecond = publish(second, key, file);
      if (!linkedSecond && onSecond.kind() == Kind.ABSENT) {
        settle(second, key, digest);
      }
      return neitherHeldIt && linkedFirst
          ? PutOutcome.CREATED
          : PutOutcome.ALREADY_PRESENT_IDENTICAL;
    } finally {
      lock.unlock();
    }
  }

  /**
   * The object file of a new key. A header that cannot be decoded again after it is encoded (a
   * media type over the header limit, text that is not valid UTF-16, an instant the format cannot
   * hold) is refused here, before any I/O.
   */
  private byte[] encode(PutRequest request, byte[] payload, Sha256 digest) {
    EvidenceKey key = request.key();
    try {
      SealedFormat.Header header =
          new SealedFormat.Header(
              key.path(),
              request.mediaType(),
              digest,
              payload.length,
              clock.now(),
              request.retention().retentionClass().name(),
              request.retention().retainUntil());
      byte[] file = SealedFormat.encodeObject(header, payload);
      SealedFormat.decodeObject(file, key.path());
      return file;
    } catch (IllegalArgumentException | SealedFormat.CorruptException e) {
      throw new IllegalArgumentException(
          "the object cannot be stored: its header is not storable (media type, retention or time)",
          e);
    }
  }

  private static void refuseDamaged(Vol vol, Copy copy, EvidenceKey key) {
    if (copy.kind() == Kind.CORRUPT) {
      throw new EvidenceCorruptionException(
          "damaged copy of " + key.path() + " on volume " + vol.id());
    }
  }

  /**
   * Two whole copies of one key that differ in any byte (the payload, or the header alone: media
   * type, retention class, retain-until) are an alarm of their own kind (found by {@link #scrub()}
   * as DIVERGENT): neither volume can be taken for the truth, so nothing is written and nothing is
   * acknowledged, whatever bytes the caller offers. The volumes are byte-identical by construction
   * (a put publishes the first file's bytes to the second), so this refuses nothing that is
   * legitimate.
   */
  private static void refuseDivergent(Copy onFirst, Copy onSecond, EvidenceKey key) {
    if (onFirst.kind() == Kind.PRESENT
        && onSecond.kind() == Kind.PRESENT
        && !Arrays.equals(onFirst.file(), onSecond.file())) {
      throw new EvidenceCorruptionException("the two copies of " + key.path() + " differ");
    }
  }

  private static void refuseOtherBytes(Copy copy, Sha256 digest, EvidenceKey key) {
    if (copy.kind() == Kind.PRESENT
        && !copy.decoded().orElseThrow().header().sha256().equals(digest)) {
      throw new EvidenceIntegrityException("different bytes for existing key " + key.path());
    }
  }

  private boolean publish(Vol vol, EvidenceKey key, byte[] file) {
    return on(vol, () -> vol.volume().publish(key, file));
  }

  /**
   * After a publish that found the name taken although this call had read the volume as empty: the
   * copy that won must be whole and hold the same bytes. Returns its file.
   */
  private byte[] settle(Vol vol, EvidenceKey key, Sha256 digest) {
    Copy copy = readCopy(vol, key);
    refuseDamaged(vol, copy, key);
    refuseOtherBytes(copy, digest, key);
    if (copy.kind() != Kind.PRESENT) {
      throw new EvidenceUnavailableException(
          "volume " + vol.id() + ": the object is not there after it was found");
    }
    return copy.file();
  }

  // ---------------------------------------------------------------- get and list

  @Override
  public Optional<StoredObject> get(EvidenceKey key) {
    Objects.requireNonNull(key, "key");
    Copy onFirst = tryCopy(first, key);
    Copy onSecond = tryCopy(second, key);
    if (onFirst.kind() == Kind.PRESENT || onSecond.kind() == Kind.PRESENT) {
      // the payload and the header fields come from the first valid copy (volume a, then b); the
      // retention comes from the copy whose header holds the longest retain-until, so that a copy
      // whose header was cut short (scrub reports the pair as DIVERGENT) shortens nothing
      Copy served = onFirst.kind() == Kind.PRESENT ? onFirst : onSecond;
      return Optional.of(
          assemble(key, served.decoded().orElseThrow(), longestRetention(onFirst, onSecond)));
    }
    if (onFirst.kind() == Kind.UNREACHABLE || onSecond.kind() == Kind.UNREACHABLE) {
      throw new EvidenceUnavailableException(
          "no valid copy of " + key.path() + " could be read: a volume cannot be reached");
    }
    if (onFirst.kind() == Kind.CORRUPT || onSecond.kind() == Kind.CORRUPT) {
      throw new EvidenceCorruptionException("no valid copy of " + key.path());
    }
    return Optional.empty();
  }

  /** The header of a valid copy that holds the longest retain-until (the first on a tie). */
  private static SealedFormat.Header longestRetention(Copy onFirst, Copy onSecond) {
    SealedFormat.Header best =
        (onFirst.kind() == Kind.PRESENT ? onFirst : onSecond).decoded().orElseThrow().header();
    if (onFirst.kind() == Kind.PRESENT && onSecond.kind() == Kind.PRESENT) {
      SealedFormat.Header other = onSecond.decoded().orElseThrow().header();
      if (other.retainUntil().isAfter(best.retainUntil())) {
        best = other;
      }
    }
    return best;
  }

  /**
   * @param decoded the copy that is served: payload, media type, hash and write time
   * @param retentionHeader the header whose retention class and retain-until count (with the metas)
   */
  private StoredObject assemble(
      EvidenceKey key, SealedFormat.Decoded decoded, SealedFormat.Header retentionHeader) {
    SealedFormat.Header header = decoded.header();
    MetaRead onFirst = tryMeta(first, key);
    MetaRead onSecond = tryMeta(second, key);
    if (onFirst.kind() == Kind.UNREACHABLE && onSecond.kind() == Kind.UNREACHABLE) {
      // fail closed: without any retention record the header alone would answer with a weaker
      // retention and no hold (one reachable volume is enough: writes reach a, then b)
      throw new EvidenceUnavailableException("retention records unreadable: " + key.path());
    }
    boolean anyDamaged = onFirst.kind() == Kind.CORRUPT || onSecond.kind() == Kind.CORRUPT;
    boolean anyValid = onFirst.kind() == Kind.PRESENT || onSecond.kind() == Kind.PRESENT;
    if (anyDamaged && !anyValid) {
      throw new EvidenceCorruptionException("damaged retention record of " + key.path());
    }
    Effective effective = effective(retentionHeader, onFirst, onSecond);
    return new StoredObject(
        key,
        decoded.payload(),
        header.mediaType(),
        header.sha256(),
        header.writtenAt(),
        new Retention(
            new RetentionClass(retentionHeader.retentionClass()), effective.retainUntil()),
        effective.legalHold());
  }

  @Override
  public List<EvidenceKey> list(String prefix, Optional<EvidenceKey> after, int limit) {
    Objects.requireNonNull(prefix, "prefix");
    Objects.requireNonNull(after, "after");
    if (limit < 0) {
      throw new IllegalArgumentException("limit must not be negative");
    }
    NavigableSet<String> paths = keysOfBothVolumes(prefix);
    NavigableSet<String> rest =
        after.isPresent() ? paths.tailSet(after.get().path(), false) : paths;
    List<EvidenceKey> result = new ArrayList<>();
    for (String path : rest) {
      if (result.size() >= limit) {
        break;
      }
      result.add(new EvidenceKey(path));
    }
    return List.copyOf(result);
  }

  /** The union of the keys of both volumes, in UTF-16 code-unit order. */
  private NavigableSet<String> keysOfBothVolumes(String prefix) {
    NavigableSet<String> paths = new TreeSet<>();
    for (Vol vol : volumes) {
      for (EvidenceKey key : on(vol, () -> vol.volume().keys(prefix))) {
        paths.add(key.path());
      }
    }
    return paths;
  }

  // ---------------------------------------------------------------- retention and legal hold

  @Override
  public void extendRetention(EvidenceKey key, Instant newRetainUntil) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(newRetainUntil, "newRetainUntil");
    SealedFormat.requireStorable(newRetainUntil); // a pure check: refused before any I/O
    requireHealthy(); // an outage comes before a key that is not there (M28)
    ReentrantLock lock = lockOf(key);
    lock.lock();
    try {
      for (int attempt = 1; ; attempt++) {
        Snapshot snapshot = snapshot(key);
        Effective current = snapshot.effective();
        if (newRetainUntil.isBefore(current.retainUntil())) {
          throw new EvidenceRetentionException(
              "retention can only be extended: "
                  + key.path()
                  + " until "
                  + current.retainUntil()
                  + ", requested "
                  + newRetainUntil);
        }
        if (newRetainUntil.equals(current.retainUntil()) && current.same()) {
          resyncMetas(key, snapshot);
          return;
        }
        SealedFormat.Meta next =
            new SealedFormat.Meta(
                current.topRevision() + 1,
                snapshot.header().retentionClass(),
                newRetainUntil,
                current.legalHold());
        if (writeToBoth(key, next)) {
          return;
        }
        requireAnotherTry(key, attempt);
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void setLegalHold(EvidenceKey key, boolean hold) {
    Objects.requireNonNull(key, "key");
    requireHealthy(); // an outage comes first (M33)
    ReentrantLock lock = lockOf(key);
    lock.lock();
    try {
      for (int attempt = 1; ; attempt++) {
        Snapshot snapshot = snapshot(key);
        Effective current = snapshot.effective();
        if (hold == current.legalHold() && current.same()) {
          resyncMetas(key, snapshot);
          return;
        }
        SealedFormat.Meta next =
            new SealedFormat.Meta(
                current.topRevision() + 1,
                snapshot.header().retentionClass(),
                current.retainUntil(),
                hold);
        if (writeToBoth(key, next)) {
          return;
        }
        requireAnotherTry(key, attempt);
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * The requested state already holds, so nothing is written; but a meta that is found is not
   * acknowledged on presence alone (the call that wrote it may have failed at its directory sync,
   * or died after the replace). The directory of every meta that exists is synced, volume a first;
   * where no meta exists nothing was written and nothing needs a sync.
   */
  private void resyncMetas(EvidenceKey key, Snapshot snapshot) {
    for (Vol vol : snapshot.metaHolders()) {
      run(vol, () -> vol.volume().resyncMeta(key));
    }
  }

  /** A meta that was not written because a higher revision appeared: recompute, three times. */
  private static void requireAnotherTry(EvidenceKey key, int attempt) {
    if (attempt >= META_TRIES) {
      throw new EvidenceUnavailableException(
          "the retention record of "
              + key.path()
              + " could not be stored: another writer keeps changing it");
    }
  }

  /** The meta goes to volume a, then to volume b; false when either holds a revision as high. */
  private boolean writeToBoth(EvidenceKey key, SealedFormat.Meta meta) {
    return writeMeta(first, key, meta) && writeMeta(second, key, meta);
  }

  private boolean writeMeta(Vol vol, EvidenceKey key, SealedFormat.Meta meta) {
    try {
      return on(vol, () -> vol.volume().writeMeta(key, meta));
    } catch (SealedFormat.CorruptException e) {
      throw new EvidenceCorruptionException(
          "damaged retention record of " + key.path() + " on volume " + vol.id());
    }
  }

  /**
   * The header and the retention state of a key, for a write: both volumes are read, a damaged meta
   * is an alarm.
   *
   * @throws IllegalArgumentException when neither volume holds the key
   */
  private Snapshot snapshot(EvidenceKey key) {
    Copy onFirst = readCopy(first, key);
    Copy onSecond = readCopy(second, key);
    refuseDivergent(onFirst, onSecond, key); // a header edit on one volume is no extension basis
    Copy valid = onFirst.kind() == Kind.PRESENT ? onFirst : onSecond;
    if (valid.kind() != Kind.PRESENT) {
      if (onFirst.kind() == Kind.CORRUPT || onSecond.kind() == Kind.CORRUPT) {
        throw new EvidenceCorruptionException("no valid copy of " + key.path());
      }
      throw new IllegalArgumentException("no such evidence key: " + key.path());
    }
    MetaRead metaOfFirst = readMeta(first, key);
    MetaRead metaOfSecond = readMeta(second, key);
    List<Vol> metaHolders = new ArrayList<>();
    for (Vol vol : volumes) {
      MetaRead read = vol == first ? metaOfFirst : metaOfSecond;
      if (read.kind() == Kind.CORRUPT) {
        throw new EvidenceCorruptionException(
            "damaged retention record of " + key.path() + " on volume " + vol.id());
      }
      if (read.kind() == Kind.PRESENT) {
        metaHolders.add(vol);
      }
    }
    SealedFormat.Header header = valid.decoded().orElseThrow().header();
    return new Snapshot(header, effective(header, metaOfFirst, metaOfSecond), metaHolders);
  }

  /** Maximum retention over the header and the metas, the hold of the higher revision. */
  private static Effective effective(SealedFormat.Header header, MetaRead a, MetaRead b) {
    List<SealedFormat.Meta> metas = new ArrayList<>();
    for (MetaRead read : List.of(a, b)) {
      if (read.kind() == Kind.PRESENT) {
        metas.add(read.meta().orElseThrow());
      }
    }
    Instant retainUntil = header.retainUntil();
    long top = 0;
    for (SealedFormat.Meta meta : metas) {
      if (meta.retainUntil().isAfter(retainUntil)) {
        retainUntil = meta.retainUntil();
      }
      top = Math.max(top, meta.rev());
    }
    boolean hold = false;
    for (SealedFormat.Meta meta : metas) {
      if (meta.rev() == top) {
        hold |= meta.legalHold();
      }
    }
    boolean same =
        (a.kind() == Kind.ABSENT && b.kind() == Kind.ABSENT)
            || (a.kind() == Kind.PRESENT && b.kind() == Kind.PRESENT && a.meta().equals(b.meta()));
    return new Effective(retainUntil, hold, top, same);
  }

  // ---------------------------------------------------------------- repair and scrub

  /**
   * Brings a replaced or lagging volume back: every object that one volume holds and the other does
   * not is copied (its file is decoded and hash-checked first; a damaged source is skipped, and
   * {@link #scrub()} reports it; an existing copy is never overwritten), a meta of a higher
   * revision is copied over a lower one (unless the lower one holds a later retain-until: then a
   * new revision with the later retain-until goes to both volumes, so that a repair never shortens
   * retention), and a volume without its marker is then marked (markers come last, so a repair that
   * fails midway leaves the store degraded). Idempotent.
   *
   * <p>Each key is repaired under its lock. For a replaced volume the store stays write-closed
   * (every put is refused for the missing marker) from the open until the markers are written at
   * the end, so that time is the time to read both copies of every key, which has not been
   * measured. It holds the key set of both volumes in memory.
   *
   * <p><b>Operator notes.</b> A corrupt copy of an object or of a meta next to a valid one is never
   * replaced: {@link #scrub()} reports it, {@code put} (object) and the retention methods (meta)
   * fail closed on it, and {@code repair} returns without copying it. The cure is manual: move the
   * damaged file aside (it stays as evidence of the damage), then run {@code repair()}, which finds
   * the name absent and copies the valid file. A repair that returns normally has not shown that
   * every key has two valid copies: run {@code scrub()} afterwards and expect it empty. A hold
   * release that reached one volume only is already the hold of every reader (the higher revision
   * wins), and a repair copies it to the other volume.
   *
   * @return the number of objects copied
   * @throws EvidenceUnavailableException when a volume cannot be read or written, or when another
   *     writer keeps changing the meta of a key (three tries, as for {@link #extendRetention})
   */
  public int repair() {
    int copied = 0;
    for (String path : keysOfBothVolumes("")) {
      EvidenceKey key = new EvidenceKey(path);
      ReentrantLock lock = lockOf(key);
      lock.lock();
      try {
        copied += repairObject(key);
        repairMeta(key);
      } finally {
        lock.unlock();
      }
    }
    for (Vol vol : volumes) {
      if (on(vol, () -> vol.volume().markerId()).isEmpty()) {
        run(vol, () -> vol.volume().writeMarker());
      }
    }
    return copied;
  }

  private int repairObject(EvidenceKey key) {
    Copy onFirst = readCopy(first, key);
    Copy onSecond = readCopy(second, key);
    if (onFirst.kind() == Kind.PRESENT && onSecond.kind() == Kind.ABSENT) {
      return publish(second, key, onFirst.file()) ? 1 : 0;
    }
    if (onSecond.kind() == Kind.PRESENT && onFirst.kind() == Kind.ABSENT) {
      return publish(first, key, onSecond.file()) ? 1 : 0;
    }
    return 0;
  }

  /**
   * Brings the two metas of a key together. A write that finds a revision as high as its own on a
   * volume (another writer got in first) is not ignored: the metas are read again and the step is
   * recomputed, three times, as {@link #extendRetention} does.
   */
  private void repairMeta(EvidenceKey key) {
    for (int attempt = 1; ; attempt++) {
      MetaRead onFirst = readMeta(first, key);
      MetaRead onSecond = readMeta(second, key);
      if (onFirst.kind() == Kind.CORRUPT || onSecond.kind() == Kind.CORRUPT) {
        return; // never replaced; scrub reports it
      }
      long revisionOfFirst =
          onFirst.kind() == Kind.PRESENT ? onFirst.meta().orElseThrow().rev() : -1;
      long revisionOfSecond =
          onSecond.kind() == Kind.PRESENT ? onSecond.meta().orElseThrow().rev() : -1;
      if (revisionOfFirst == revisionOfSecond) {
        return; // nothing to copy; two metas of one revision are merged by every reader
      }
      boolean firstIsHigher = revisionOfFirst > revisionOfSecond;
      SealedFormat.Meta higher = (firstIsHigher ? onFirst : onSecond).meta().orElseThrow();
      Vol lowerVolume = firstIsHigher ? second : first;
      MetaRead lower = firstIsHigher ? onSecond : onFirst;
      if (lower.kind() == Kind.PRESENT
          && lower.meta().orElseThrow().retainUntil().isAfter(higher.retainUntil())) {
        // Copying the higher revision over this one would shorten the retention that a reader sees
        // (the maximum over all metas). A new revision above both carries the longer retention, and
        // the legal hold of the higher revision, to both volumes. If another writer got in first,
        // writeToBoth says so and the metas are read again.
        SealedFormat.Meta merged =
            new SealedFormat.Meta(
                higher.rev() + 1,
                higher.retentionClass(),
                lower.meta().orElseThrow().retainUntil(),
                higher.legalHold());
        if (writeToBoth(key, merged)) {
          return;
        }
        requireAnotherTry(key, attempt);
        continue;
      }
      if (writeMeta(lowerVolume, key, higher)) {
        return;
      }
      requireAnotherTry(key, attempt); // the lower volume holds a revision as high by now
    }
  }

  /**
   * Reads every copy of every object and writes nothing. One line per defect, sorted by key and
   * then by volume id: {@code "<volumeId> <key> CORRUPT"} (the copy fails its own seal), {@code
   * "<volumeId> <key> MISSING_COPY"} (the other volume holds one), {@code "<volumeId> <key>
   * CORRUPT_META"} (the retention record is damaged), {@code "<key> DIVERGENT"} (both copies are
   * whole but differ in any byte, also in the header alone) and {@code "<volumeId> <path>
   * COMPLETE_TEMPORARY"} (a temporary file of a dead writer that is a complete object or meta whose
   * final name does not hold it: after a power loss on Windows it can be the only surviving name of
   * an acknowledged write; {@code <path>} is the path of the file below the volume root with '/',
   * for example {@code o/d-landing/o-k1~tmp~4711~3}, sorted among the others by that path; an
   * operator adopts it by renaming it to its final name, or removes it). Empty on a clean store.
   *
   * <p>Each key is read under the key's lock, the one a put takes, so a put of this store is seen
   * as done or not begun and never as a missing copy. The findings are a snapshot per key: the keys
   * are listed first, a key put afterwards is not in them, and a defect that arises after its key
   * was read is not either. Another store over the same directories is not held back by these locks
   * and can still show a copy that is on its way.
   *
   * @throws EvidenceUnavailableException when a volume cannot be read
   */
  public List<String> scrub() {
    record Finding(String key, String volumeId, String line) {}
    List<Finding> findings = new ArrayList<>();
    for (String path : keysOfBothVolumes("")) {
      EvidenceKey key = new EvidenceKey(path);
      ReentrantLock lock = lockOf(key);
      lock.lock();
      try {
        Copy onFirst = readCopy(first, key);
        Copy onSecond = readCopy(second, key);
        for (Vol vol : volumes) {
          Copy copy = vol == first ? onFirst : onSecond;
          if (copy.kind() == Kind.CORRUPT) {
            findings.add(new Finding(path, vol.id(), vol.id() + " " + path + " CORRUPT"));
          } else if (copy.kind() == Kind.ABSENT) {
            findings.add(new Finding(path, vol.id(), vol.id() + " " + path + " MISSING_COPY"));
          }
          if (readMeta(vol, key).kind() == Kind.CORRUPT) {
            findings.add(new Finding(path, vol.id(), vol.id() + " " + path + " CORRUPT_META"));
          }
        }
        if (onFirst.kind() == Kind.PRESENT
            && onSecond.kind() == Kind.PRESENT
            && !Arrays.equals(onFirst.file(), onSecond.file())) {
          findings.add(new Finding(path, "", path + " DIVERGENT"));
        }
      } finally {
        lock.unlock();
      }
    }
    for (Vol vol : volumes) {
      for (String name : on(vol, () -> vol.volume().completeTemporaries())) {
        findings.add(new Finding(name, vol.id(), vol.id() + " " + name + " COMPLETE_TEMPORARY"));
      }
    }
    findings.sort(
        Comparator.comparing(Finding::key)
            .thenComparing(Finding::volumeId)
            .thenComparing(Finding::line));
    return findings.stream().map(Finding::line).toList();
  }

  // ---------------------------------------------------------------- limits

  /**
   * What this store does not show, in plain words (SP-W has not been run, so a test shows none of
   * it). A caller that grades a deployment reads this before it relies on the store.
   */
  public String limits() {
    return "P-SEALED limits. SP-W has not been run, so none of the following is shown by a test. "
        + "(1) Directory durability: directory entries are forced on POSIX systems only; on "
        + "Windows the JDK cannot force a directory, so a new file name may not survive a power "
        + "failure although its data was forced: after a power loss the final name of an "
        + "acknowledged object may be missing while a complete temporary file of it survives and "
        + "is kept for the operator (the sweep at open leaves it in place, it is never served, and "
        + "scrub() reports it as COMPLETE_TEMPORARY so that an operator can adopt or remove it); "
        + "on POSIX systems the gap after a restart in "
        + "item (12) applies. "
        + "(2) Not exercised: power loss, drives that acknowledge a flush early, SMB and other "
        + "network file systems, ReFS and Windows Server volume pairs. "
        + "(3) Scale: the list call walks the directory tree of both volumes; open() lists every "
        + "directory of both volumes to sweep temporary files; repair() and scrub() hold the key "
        + "set of both volumes in memory. The cost and the heap grow with the number of objects "
        + "and are unmeasured (SP-W); a replaced volume blocks every write until repair() has "
        + "finished; verify and any caller that pages list() cost N/500 + 1 full walks of both "
        + "volumes (quadratic in the number of objects) and are not usable beyond test sizes, a "
        + "streaming or pruning list is SP-W/P3b2 scale work. "
        + "(4) The number of failure domains is inferred from file-store identity (device number "
        + "and type, or the drive description); two file systems on one physical disk count as "
        + "two, and that the two volumes are independent is attested in the G8 record, not shown "
        + "here. "
        + "(5) Objects are stored exactly as given (plaintext) until the envelope encryption of "
        + "P3c, so P-SEALED must not receive production data before P3c and gate G8. "
        + "(6) On Windows the files carry the access control list they inherit from their "
        + "directory; only on POSIX systems are they owner-only. "
        + "(7) Retention and legal hold are kept in the header and in meta files. Every file ends "
        + "in a SHA-256 trailer over all its bytes, so a flipped bit anywhere in a file is found; "
        + "a deliberate edit that recomputes the trailer, and a shortened or deleted meta, are "
        + "not detected until P3c (retention rows and signed manifests). "
        + "(8) The grade is tamper-evident, not immutable: someone with storage access can delete "
        + "or rewrite files. "
        + "(9) A put of a key that both volumes already hold (a journal re-ship, a retry) still "
        + "writes and forces a temporary file of the full object size on each volume, has its "
        + "link refused and deletes the file, only to trigger the directory-sync chain; its cost "
        + "grows with the object size and has not been measured. "
        + "(10) Temporary files that a crashed writer leaves are swept when the store is opened, "
        + "except a complete temporary file of an object or of a meta whose final name does not "
        + "hold it (see item (1)). "
        + "Their owner is told by the process id in this process-id namespace and by the age of "
        + "the file (older than the start of this JVM); two stores in different containers or "
        + "hosts over one directory are not told apart, and one can sweep the file that the other "
        + "is still writing (its put then fails and is retried). Sharing a volume between "
        + "processes is not claimed (see item (11)). "
        + "(11) One process owns a volume pair. A second process over the same two directories "
        + "silently loses legal-hold and retention-extension updates: the read-compare-replace of "
        + "a meta file is atomic only through locks that are shared inside one JVM. Advisory file "
        + "locks are unreliable on network file systems; the durable cure is the immutable "
        + "per-revision meta file of P3c. "
        + "(12) Restart gap and lock coupling. After a restart the bookkeeping of unsynced "
        + "directories is empty, so a publish whose link succeeds in directories that a crashed "
        + "earlier attempt created syncs only the directory that holds the link (the "
        + "refused-link path syncs the whole chain). The directory lock and the 64 meta locks are "
        + "static and shared by every volume and store in the JVM, so a stalled volume can hold "
        + "up another; the lock order is the key stripe of the store first, then the meta lock of "
        + "the volume. "
        + "(13) The root of a volume must be a new empty directory below the mount point: "
        + "lost+found, System Volume Information and $RECYCLE.BIN count as content, and two "
        + "unmarked roots of which either holds any entry are refused. "
        + "(14) The header and meta fields are sealed by the trailer, not authenticated: the "
        + "format finds any flipped bit, but not a deliberate edit that recomputes the trailer; "
        + "that needs the signed manifests of P3c. "
        + "(15) Manual cures and unrecovered states. A corrupt copy next to a valid one (object or "
        + "meta) is reported by scrub and never replaced by repair; put (for an object) and the "
        + "retention methods (for a meta) fail closed on it until an operator moves the damaged "
        + "file aside and runs repair(). Two unmounted volumes (empty mount-point directories on "
        + "the system disk) are indistinguishable from two new volumes and open as a new store on "
        + "the system disk; the production send gate refuses fewer than two failure domains. The "
        + "marker is a dot file, and a restore by copying vol/* drops it, after which open "
        + "refuses. Two copies of a key that differ in any byte, also in the header only "
        + "(retention, class, media type), are DIVERGENT: scrub() reports them, put, "
        + "extendRetention and setLegalHold refuse them with an EvidenceCorruptionException, "
        + "repair() picks no side, and get() serves the payload of the first valid copy with "
        + "the longest retain-until over both headers and the metas; the cure is manual. "
        + "(16) A hold release that reached one volume only is effective before it is "
        + "acknowledged: the hold of two metas of different revisions is the hold of the higher "
        + "revision. "
        + "(17) On Windows durability() reports synchronousAcknowledgement true although no "
        + "directory is forced there; a send-gate decision on Windows therefore rests on the "
        + "attestation of G8 and on SP-W. "
        + "(18) A foreign handle on a meta file makes the atomic replace fail on Windows with an "
        + "access error (a virus scanner, a backup tool or another process holds such handles): "
        + "the store reports an I/O error and does not retry, and the caller of setLegalHold or "
        + "extendRetention retries. "
        + "(19) A put holds the whole object in memory: about 8 times the object size at its "
        + "peak when both volumes already hold the key (the clone of the request, the clone "
        + "taken by put, the candidate file, and per volume the file as read and the decoded "
        + "payload), 3 times for a new key, plus transient copies while a file is decoded; the "
        + "key locks admit up to 64 puts of different keys at once, so the heap needed is up to "
        + "64 times that for the largest object.";
  }

  // ---------------------------------------------------------------- reading one volume

  private ReentrantLock lockOf(EvidenceKey key) {
    return locks[Math.floorMod(key.path().hashCode(), STRIPES)];
  }

  /** What the volume holds under the key; an I/O failure is an EvidenceUnavailableException. */
  private Copy readCopy(Vol vol, EvidenceKey key) {
    Optional<byte[]> file = on(vol, () -> vol.volume().read(key));
    if (file.isEmpty()) {
      return Copy.ABSENT;
    }
    try {
      return Copy.present(file.get(), SealedFormat.decodeObject(file.get(), key.path()));
    } catch (SealedFormat.CorruptException e) {
      return Copy.CORRUPT;
    }
  }

  private Copy tryCopy(Vol vol, EvidenceKey key) {
    try {
      return readCopy(vol, key);
    } catch (EvidenceUnavailableException e) {
      return Copy.UNREACHABLE;
    }
  }

  private MetaRead readMeta(Vol vol, EvidenceKey key) {
    try {
      Optional<SealedFormat.Meta> meta = on(vol, () -> vol.volume().readMeta(key));
      return meta.isPresent() ? MetaRead.present(meta.get()) : MetaRead.ABSENT;
    } catch (SealedFormat.CorruptException e) {
      return MetaRead.CORRUPT;
    }
  }

  private MetaRead tryMeta(Vol vol, EvidenceKey key) {
    try {
      return readMeta(vol, key);
    } catch (EvidenceUnavailableException e) {
      return MetaRead.UNREACHABLE;
    }
  }

  private static <T> T on(Vol vol, IoCall<T> call) {
    try {
      return call.run();
    } catch (ClosedByInterruptException | InterruptedIOException e) {
      // The interrupt closed the channel (or the call was interrupted): not a failing disk, and
      // the caller's flag must survive, so that code above this call can see it.
      Thread.currentThread().interrupt();
      throw new EvidenceUnavailableException("volume " + vol.id() + " failed: interrupted", e);
    } catch (IOException e) {
      throw new EvidenceUnavailableException("volume " + vol.id() + " failed: I/O error", e);
    }
  }

  private static void run(Vol vol, IoRun call) {
    on(
        vol,
        () -> {
          call.run();
          return Boolean.TRUE;
        });
  }
}
