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
 * copy on either volume is an {@link EvidenceCorruptionException}, a copy with other bytes an
 * {@link EvidenceIntegrityException}, and nothing is written in either case. If exactly one volume
 * holds the key, its verified file is published to the other, so the volumes stay byte-identical
 * and the first header (media type, write time, retention class) is kept. If neither holds it, the
 * object file is built and published to volume a, then to volume b; the call returns after both.
 * The result is {@code CREATED} only when neither volume held the key. A failure on the second
 * volume is an {@link EvidenceUnavailableException}; the object stays on the first volume, is
 * served from it, and a retry completes the second.
 *
 * <p><b>Reads.</b> {@code get} serves the first copy that passes its own seal (volume a, then b).
 * Retention and legal hold are kept in the object header and in meta files with a revision; the
 * retain-until instant is the maximum over the header and both metas and the hold comes from the
 * meta of the higher revision (two metas of one revision merge: later retention, hold of either).
 *
 * <p><b>Health.</b> Each volume has a marker file with its id. A volume without its marker is a
 * replaced disk: the store is {@link #degraded()}, declares no synchronous acknowledgement, refuses
 * every write and is brought back by {@link #repair()}. Messages carry key paths, volume ids and
 * fixed texts only, never file names.
 */
public final class SealedEvidenceStore implements EvidenceStore {
  private static final Grade GRADE = new Grade("P-SEALED", "");
  private static final Pattern VOLUME_ID = Pattern.compile("[a-z0-9-]{1,32}");
  private static final int STRIPES = 64;
  private static final int META_TRIES = 3;

  /**
   * Where the two volumes are and how they are told apart.
   *
   * @param volumeA the root of the first volume (absolute)
   * @param volumeB the root of the second volume (absolute); the two real paths must differ and
   *     neither may lie inside the other (checked by {@link #open})
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

  /** The header of the first valid copy and the retention state read with it. */
  private record Snapshot(SealedFormat.Header header, Effective effective) {}

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
   *     hard link, a marker names another volume, or both volumes are unmarked but hold objects
   * @throws IllegalArgumentException when the two roots are one directory or one lies in the other
   *     (also through a link or another spelling of the path)
   */
  public static SealedEvidenceStore open(Config config) {
    return open(config, FileOps.real());
  }

  /**
   * Both roots are created first and their real paths must differ with neither containing the
   * other. Two missing markers on two empty roots are a new store (both markers are written); one
   * missing marker is a replaced volume (the store is degraded and nothing is written); a marker
   * with another id, or two missing markers on volumes that hold objects, refuse the open. Stale
   * temporary files of dead processes are swept.
   */
  static SealedEvidenceStore open(Config config, FileOps ops) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(ops, "ops");
    Path realA;
    Path realB;
    try {
      createRoot(ops, config.volumeA());
      createRoot(ops, config.volumeB());
      realA = config.volumeA().toRealPath();
      realB = config.volumeB().toRealPath();
    } catch (IOException e) {
      throw new EvidenceUnavailableException("the evidence volumes cannot be opened: I/O error", e);
    }
    if (realA.equals(realB) || realA.startsWith(realB) || realB.startsWith(realA)) {
      throw new IllegalArgumentException(
          "the two volume roots must be different directories, neither inside the other");
    }
    Vol first =
        new Vol(config.volumeIdA(), realA, new SealedVolume(realA, config.volumeIdA(), ops));
    Vol second =
        new Vol(config.volumeIdB(), realB, new SealedVolume(realB, config.volumeIdB(), ops));
    SealedEvidenceStore store = new SealedEvidenceStore(config, first, second);
    store.initialise();
    return store;
  }

  /** Creates a root that is missing; an existing directory (a link to one included) is left. */
  private static void createRoot(FileOps ops, Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      ops.createDirectories(root);
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
        throw new EvidenceUnavailableException(
            "both volumes are unmarked but hold objects: not a store this code created");
      }
      run(first, () -> first.volume().writeMarker());
      run(second, () -> second.volume().writeMarker());
    }
    for (Vol vol : volumes) {
      run(vol, () -> vol.volume().sweepTemporaries());
    }
  }

  private static void requireOwnMarker(Vol vol, Optional<String> marker) {
    if (marker.isPresent() && !marker.get().equals(vol.id())) {
      throw new EvidenceUnavailableException(
          "volume " + vol.id() + ": its marker names another volume");
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
    if (onFirst.kind() == Kind.PRESENT) {
      return Optional.of(assemble(key, onFirst.decoded().orElseThrow()));
    }
    Copy onSecond = tryCopy(second, key);
    if (onSecond.kind() == Kind.PRESENT) {
      return Optional.of(assemble(key, onSecond.decoded().orElseThrow()));
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

  private StoredObject assemble(EvidenceKey key, SealedFormat.Decoded decoded) {
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
    Effective effective = effective(header, onFirst, onSecond);
    return new StoredObject(
        key,
        decoded.payload(),
        header.mediaType(),
        header.sha256(),
        header.writtenAt(),
        new Retention(new RetentionClass(header.retentionClass()), effective.retainUntil()),
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
    Copy valid = onFirst.kind() == Kind.PRESENT ? onFirst : onSecond;
    if (valid.kind() != Kind.PRESENT) {
      if (onFirst.kind() == Kind.CORRUPT || onSecond.kind() == Kind.CORRUPT) {
        throw new EvidenceCorruptionException("no valid copy of " + key.path());
      }
      throw new IllegalArgumentException("no such evidence key: " + key.path());
    }
    MetaRead metaOfFirst = readMeta(first, key);
    MetaRead metaOfSecond = readMeta(second, key);
    for (Vol vol : volumes) {
      if ((vol == first ? metaOfFirst : metaOfSecond).kind() == Kind.CORRUPT) {
        throw new EvidenceCorruptionException(
            "damaged retention record of " + key.path() + " on volume " + vol.id());
      }
    }
    SealedFormat.Header header = valid.decoded().orElseThrow().header();
    return new Snapshot(header, effective(header, metaOfFirst, metaOfSecond));
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
   * retention), and a volume without its marker is then marked. Idempotent.
   *
   * @return the number of objects copied
   * @throws EvidenceUnavailableException when a volume cannot be read or written
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

  private void repairMeta(EvidenceKey key) {
    MetaRead onFirst = readMeta(first, key);
    MetaRead onSecond = readMeta(second, key);
    if (onFirst.kind() == Kind.CORRUPT || onSecond.kind() == Kind.CORRUPT) {
      return; // never replaced; scrub reports it
    }
    long revisionOfFirst = onFirst.kind() == Kind.PRESENT ? onFirst.meta().orElseThrow().rev() : -1;
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
      // its meta was computed as the maximum over both metas as well, so nothing is lost.
      SealedFormat.Meta merged =
          new SealedFormat.Meta(
              higher.rev() + 1,
              higher.retentionClass(),
              lower.meta().orElseThrow().retainUntil(),
              higher.legalHold());
      writeToBoth(key, merged);
      return;
    }
    writeMeta(lowerVolume, key, higher);
  }

  /**
   * Reads every copy of every object and writes nothing. One line per defect, sorted by key and
   * then by volume id: {@code "<volumeId> <key> CORRUPT"} (the copy fails its own seal), {@code
   * "<volumeId> <key> MISSING_COPY"} (the other volume holds one), {@code "<volumeId> <key>
   * CORRUPT_META"} (the retention record is damaged) and {@code "<key> DIVERGENT"} (both copies are
   * whole but differ). Empty on a clean store.
   *
   * @throws EvidenceUnavailableException when a volume cannot be read
   */
  public List<String> scrub() {
    record Finding(String key, String volumeId, String line) {}
    List<Finding> findings = new ArrayList<>();
    for (String path : keysOfBothVolumes("")) {
      EvidenceKey key = new EvidenceKey(path);
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
        + "failure although its data was forced. "
        + "(2) Not exercised: power loss, drives that acknowledge a flush early, SMB and other "
        + "network file systems, ReFS and Windows Server volume pairs. "
        + "(3) The list call walks the directory tree of both volumes: its cost grows with the "
        + "number of objects and has not been measured. "
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
        + "writes, forces and discards a temporary file of the full object size on each volume "
        + "before it syncs the directories, so its cost grows with the object size; it has not "
        + "been measured.";
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
