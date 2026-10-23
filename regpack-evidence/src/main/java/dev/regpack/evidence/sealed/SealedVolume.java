package dev.regpack.evidence.sealed;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.ports.evidence.EvidenceKey;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * One volume of P-SEALED (D-E5): a root directory that holds the objects as {@code
 * root/o/<d-segments>/o-<last>} (the names of {@link SealedNames}), the meta of an object beside it
 * as {@code m-<last>}, and {@code root/.sealed-volume}, which holds the volume id.
 *
 * <p><b>Publish.</b> An object file is written under a temporary name, forced to the device, and
 * given its name by a hard link. A link never replaces, so of any number of writers (threads of
 * this process, other instances over the same directory, other processes) exactly one gets the name
 * and the file at that name is always one writer's bytes in full, never a mixture and never a file
 * that was not yet forced. After the link the directory that holds it is synced and so is the
 * parent of every directory that this volume created and has not yet been able to sync. When the
 * link is refused because the name exists, the directory that holds it AND every ancestor up to the
 * root are synced before {@code false} is returned (the bookkeeping below does not survive a
 * restart, so a found name is not taken as durable), and a sync that fails throws: an object found
 * present is never acknowledged on presence alone while its directory entries may still be only in
 * memory.
 *
 * <p><b>What this does not show.</b> Directory syncs exist on POSIX systems only ({@link
 * FileOps#supportsDirectorySync}); on Windows none is made and the durability of a directory entry
 * is not claimed. The unsynced-directory bookkeeping is held in memory: after a restart it is
 * empty, so a publish whose link succeeds in directories that an earlier, crashed attempt had
 * created syncs only the directory that holds the link, and the entries of those directories in
 * their parents are not synced by it (the refused-link path above does sync them). Several
 * processes on one directory are not coordinated beyond the no-clobber link and the temporary names
 * (pid and counter); the rest of the coordination of one store (retention, the two volumes) is the
 * store's, under its own locks.
 *
 * <p><b>Locks.</b> The directory lock and the 64 meta locks are static, so JVM-wide: a volume whose
 * file system stalls inside directory creation or a meta replace can hold up unrelated volumes of
 * the same JVM. A caller that takes its own lock per key takes it first and the volume's lock
 * second, never the other way round (the volume calls out to nothing that takes a caller's lock).
 *
 * <p>Messages carry the volume id and fixed texts only, never a file name (Convention 8 of P3b1).
 */
final class SealedVolume {
  private static final String MARKER = ".sealed-volume";
  private static final String OBJECTS = "o";
  private static final String SELFTEST = ".selftest";
  private static final byte[] SELFTEST_BYTES = "regpack hard-link self-test".getBytes(UTF_8);
  private static final Pattern VOLUME_ID = Pattern.compile("[a-z0-9-]{1,32}");
  private static final int MAX_MARKER_BYTES = 256;
  private static final int MAX_TEMP_TRIES = 8;
  private static final int MAX_KEY_LENGTH = 512;
  private static final int META_STRIPES = 64;

  /** One counter for the whole JVM, so that no two volume objects ever make the same name. */
  private static final AtomicLong TEMP_COUNTER = new AtomicLong();

  private static final long PID = ProcessHandle.current().pid();

  /** Serialises "which directories are missing" with their creation, JVM-wide. */
  private static final ReentrantLock DIRECTORY_LOCK = new ReentrantLock();

  /**
   * Directories that this JVM created and whose parent has not yet been synced after the creation.
   * Registered before the creation, removed after the sync of the parent.
   */
  private static final Set<Path> UNSYNCED = ConcurrentHashMap.newKeySet();

  /**
   * Makes the read-compare-replace of a meta file one step, JVM-wide, and keeps readers of a meta
   * out of the way of its replacement.
   */
  private static final ReentrantLock[] META_LOCKS = metaLocks();

  /**
   * The start of this JVM as the operating system reports it (not a clock reading: it comes from
   * the process table), read once. Empty where the platform does not report it.
   */
  private static final Optional<Instant> JVM_START = ProcessHandle.current().info().startInstant();

  /**
   * A temporary file is taken to be an earlier incarnation's when it was last written more than
   * this long before the start of this JVM: file systems round times (FAT keeps two seconds), and
   * the start instant of a process is itself reported to the second on some systems.
   */
  private static final Duration START_SKEW = Duration.ofSeconds(2);

  private final Path root;
  private final String volumeId;
  private final FileOps ops;
  private final Supplier<Optional<Instant>> processStart;

  /**
   * @param root an absolute directory (a store creates it with {@link #ensureRoot} when it opens; a
   *     write creates it if it is missing)
   * @param volumeId 1 to 32 characters of a-z, 0-9 and '-'
   */
  SealedVolume(Path root, String volumeId, FileOps ops) {
    this(root, volumeId, ops, () -> JVM_START);
  }

  /**
   * @param processStart where the sweep reads the start instant of this JVM (a test replaces it)
   */
  SealedVolume(Path root, String volumeId, FileOps ops, Supplier<Optional<Instant>> processStart) {
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(volumeId, "volumeId");
    Objects.requireNonNull(ops, "ops");
    Objects.requireNonNull(processStart, "processStart");
    this.processStart = processStart;
    if (!root.isAbsolute()) {
      throw new IllegalArgumentException("the root of a volume must be an absolute path");
    }
    if (!VOLUME_ID.matcher(volumeId).matches()) {
      throw new IllegalArgumentException("a volume id has 1 to 32 characters of a-z, 0-9 and '-'");
    }
    this.root = root.normalize();
    this.volumeId = volumeId;
    this.ops = ops;
  }

  private static ReentrantLock[] metaLocks() {
    ReentrantLock[] locks = new ReentrantLock[META_STRIPES];
    for (int i = 0; i < locks.length; i++) {
      locks[i] = new ReentrantLock();
    }
    return locks;
  }

  // ---------------------------------------------------------------- the volume itself

  /**
   * Creates the root of the volume with its missing ancestors, and syncs the parent of every
   * directory that this call (or an earlier call of this JVM that failed at a sync) created: the
   * root's entry in its parent, and the entry of each created ancestor in its own. A root that
   * exists is left alone. The ancestors above the first one that existed are not synced: they were
   * there before, and their entries are the operator's. A sync that fails throws; the directories
   * stay registered as unsynced, so the next call syncs them again. A no-op beyond the creation
   * where the platform cannot sync a directory.
   *
   * @throws IOException when the root cannot be created or a sync fails
   */
  void ensureRoot() throws IOException {
    DIRECTORY_LOCK.lock();
    try {
      boolean syncs = ops.supportsDirectorySync();
      List<Path> missing = new ArrayList<>();
      for (Path probe = root;
          probe != null && !Files.isDirectory(probe);
          probe = probe.getParent()) {
        missing.add(probe);
      }
      if (!missing.isEmpty()) {
        if (syncs) {
          UNSYNCED.addAll(missing); // registered before the creation, as ensureDirectory does
        }
        ops.createDirectories(root);
      }
      if (syncs) {
        // from the root up to the top of what was created, also what an earlier failed call left
        for (Path directory = root; directory != null; directory = directory.getParent()) {
          Path parent = directory.getParent();
          if (parent != null && UNSYNCED.contains(directory)) {
            ops.syncDirectory(parent);
          }
          UNSYNCED.remove(directory);
        }
      }
    } finally {
      DIRECTORY_LOCK.unlock();
    }
  }

  /**
   * Proves that this volume can make a hard link (A10) as the publish protocol needs it: creates a
   * file and a second name for it, and requires that the data show through the second name, that
   * the two names be one file (and, where the file system reports a link count, that the file have
   * exactly two names), that a link onto an existing name be refused with a {@link
   * FileAlreadyExistsException} (a "link" that copies or replaces would lose the no-clobber
   * guarantee), and, where the platform can sync a directory, that the sync of the root work (a
   * volume whose directory sync fails is refused here and not at the first send). Both names are
   * removed. A second name that is taken by a file of another writer (a leftover of an earlier run
   * with the same pid and counter) is skipped and that file is never touched; when eight names in a
   * row are taken the check fails with a message that says so.
   *
   * @throws IOException naming the volume when it cannot, or when the file operations fail
   */
  void checkHardLinks() throws IOException {
    ensureDirectory(root);
    Path source = writeTemporary(root, SELFTEST, SELFTEST_BYTES);
    Path ours = null; // the second name, once this call may have created it
    IOException failure;
    try {
      Path second = null;
      IOException taken = null;
      for (int attempt = 0; attempt < MAX_TEMP_TRIES && second == null; attempt++) {
        Path candidate =
            root.resolve(SealedNames.temporary(SELFTEST, PID, TEMP_COUNTER.getAndIncrement()));
        ours = candidate;
        try {
          ops.link(source, candidate);
          second = candidate;
        } catch (FileAlreadyExistsException e) {
          ours = null; // a file of another writer: never ours to delete
          taken = e;
        }
      }
      failure =
          second == null
              ? new IOException(
                  "volume " + volumeId + ": no free name for the hard-link self-test", taken)
              : verifyHardLink(source, second);
    } catch (IOException | UnsupportedOperationException e) {
      failure = new IOException("volume " + volumeId + " cannot create hard links", e);
    }
    for (Path file : Arrays.asList(ours, source)) {
      if (file == null) {
        continue;
      }
      try {
        ops.delete(file);
      } catch (IOException e) {
        // a leftover self-test name is never listed or served and is swept at the next open
        if (failure != null) {
          failure.addSuppressed(e);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  /** The failure of the self-test of a second name that was made, or null when it holds. */
  private IOException verifyHardLink(Path source, Path second) {
    String volume = "volume " + volumeId;
    try {
      if (!Arrays.equals(ops.read(second), SELFTEST_BYTES)) {
        return new IOException(volume + ": a hard link does not show the data");
      }
      if (!isSameFile(source, second)) {
        return new IOException(volume + ": the two names of a hard link are not one file");
      }
      OptionalInt names = linkCount(second);
      if (names.isPresent() && names.getAsInt() != 2) {
        return new IOException(
            volume + ": a hard link gives the file " + names.getAsInt() + " names, not 2");
      }
      try {
        ops.link(source, second);
        return new IOException(volume + ": a hard link onto an existing name does not refuse it");
      } catch (FileAlreadyExistsException expected) {
        // the no-clobber property of the publish protocol holds
      }
    } catch (IOException | UnsupportedOperationException e) {
      return new IOException(volume + " cannot create hard links", e);
    }
    if (ops.supportsDirectorySync()) {
      try {
        ops.syncDirectory(root);
      } catch (IOException e) {
        return new IOException(volume + " cannot sync a directory", e);
      }
    }
    return null;
  }

  private static boolean isSameFile(Path first, Path second) throws IOException {
    try {
      return Files.isSameFile(first, second);
    } catch (NoSuchFileException e) {
      return false; // one of the names is gone: a move, not a link
    }
  }

  /** The number of names of the file where the file system reports it (POSIX), else empty. */
  private static OptionalInt linkCount(Path file) throws IOException {
    if (!file.getFileSystem().supportedFileAttributeViews().contains("unix")) {
      return OptionalInt.empty();
    }
    return OptionalInt.of(((Number) Files.getAttribute(file, "unix:nlink")).intValue());
  }

  /**
   * The id stored in the marker file, or empty when the volume has no marker. Trailing white space
   * (the newline an operator's editor adds) is not part of the id.
   */
  Optional<String> markerId() throws IOException {
    try {
      byte[] bytes = ops.read(root.resolve(MARKER));
      if (bytes.length > MAX_MARKER_BYTES) {
        throw new IOException("volume " + volumeId + ": the marker is not a volume marker");
      }
      return Optional.of(new String(bytes, UTF_8).stripTrailing());
    } catch (NoSuchFileException e) {
      return Optional.empty();
    }
  }

  /**
   * Writes the marker with this volume's id, forced and synced like an object. A marker that
   * already holds this id is left as it is; one that holds another id is never replaced.
   *
   * @throws IOException when the root holds the marker of another volume
   */
  void writeMarker() throws IOException {
    boolean created = publishFile(root, root.resolve(MARKER), volumeId.getBytes(UTF_8));
    if (!created && !markerId().equals(Optional.of(volumeId))) {
      throw new IOException("volume " + volumeId + ": the marker names another volume");
    }
  }

  /**
   * True when the root holds nothing but temporary names and empty object directories (an absent
   * root is empty). A marker, an object, a meta or any other name makes it not empty.
   */
  boolean isEmpty() throws IOException {
    return holdsNothing(false);
  }

  /**
   * True when the root holds no object, no meta and no other name: the marker, temporary names and
   * empty object directories do not count. A volume that is replaced or half initialised and has
   * never stored anything is like this.
   */
  boolean holdsNothingButItsMarker() throws IOException {
    return holdsNothing(true);
  }

  private boolean holdsNothing(boolean markerDoesNotCount) throws IOException {
    return countEntries(markerDoesNotCount) == 0;
  }

  /**
   * The number of entries of the root that make {@link #isEmpty} false (the marker counts), without
   * their names: for the message that refuses a root which is not empty.
   */
  int entriesInTheWay() throws IOException {
    return countEntries(false);
  }

  private int countEntries(boolean markerDoesNotCount) throws IOException {
    int count = 0;
    for (Path entry : ops.list(root)) {
      String name = nameOf(entry);
      if (SealedNames.isTemporary(name)
          || (markerDoesNotCount && name.equals(MARKER))
          || (name.equals(OBJECTS) && !holdsFiles(entry))) {
        continue;
      }
      count++;
    }
    return count;
  }

  private boolean holdsFiles(Path directory) throws IOException {
    for (Path entry : ops.list(directory)) {
      String name = nameOf(entry);
      if (SealedNames.isTemporary(name)) {
        continue;
      }
      if (!name.startsWith("d-") || holdsFiles(entry)) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------- objects

  /**
   * Gives the object file its name, durably and without replacing: temp, {@code writeAndForce},
   * {@code link}, then the directory syncs of the class comment.
   *
   * @return true when this call created the object, false when the key already existed (and its
   *     directory and its ancestors up to the root have been synced)
   * @throws IOException when any step fails; no visible object is left unless the link happened
   */
  boolean publish(EvidenceKey key, byte[] objectFile) throws IOException {
    Objects.requireNonNull(objectFile, "objectFile");
    Path directory = directoryOf(key);
    return publishFile(
        directory, directory.resolve(SealedNames.object(lastSegment(key))), objectFile);
  }

  /** The bytes of the object file, or empty when this volume has none under the key. */
  Optional<byte[]> read(EvidenceKey key) throws IOException {
    Path directory = directoryOf(key);
    try {
      return Optional.of(ops.read(directory.resolve(SealedNames.object(lastSegment(key)))));
    } catch (NoSuchFileException e) {
      return Optional.empty();
    }
  }

  /**
   * The keys that start with {@code prefix} (a plain string comparison on the key text), unsorted.
   * Temporary names, meta files and names that are not ours are skipped; directories that cannot
   * lead to the prefix are not read.
   */
  List<EvidenceKey> keys(String prefix) throws IOException {
    Objects.requireNonNull(prefix, "prefix");
    List<EvidenceKey> found = new ArrayList<>();
    collect(root.resolve(OBJECTS), "", prefix, found);
    return found;
  }

  private void collect(Path directory, String parentPath, String prefix, List<EvidenceKey> found)
      throws IOException {
    for (Path entry : ops.list(directory)) {
      String name = nameOf(entry);
      if (SealedNames.isTemporary(name) || name.length() < 3) {
        continue;
      }
      char kind = name.charAt(0);
      if (kind != 'o' && kind != 'd') {
        continue;
      }
      String segment;
      try {
        segment = SealedNames.decode(name);
      } catch (IllegalArgumentException e) {
        continue; // not a name of ours
      }
      if (segment.equals(".") || segment.equals("..")) {
        continue; // a canonical name, but never a key segment: skipped, and not entered
      }
      String path = parentPath.isEmpty() ? segment : parentPath + "/" + segment;
      if (path.length() > MAX_KEY_LENGTH) {
        continue;
      }
      if (kind == 'o') {
        if (path.startsWith(prefix)) {
          try {
            found.add(new EvidenceKey(path));
          } catch (IllegalArgumentException e) {
            // a name of the encoding that is not a key: one stray entry must not break the listing
          }
        }
      } else {
        String below = path + "/";
        if (below.startsWith(prefix) || prefix.startsWith(below)) {
          collect(entry, path, prefix, found);
        }
      }
    }
  }

  // ---------------------------------------------------------------- meta

  /**
   * The meta of the key, or empty when there is none. The read takes the lock of {@link
   * #writeMeta}: on Windows an atomic replace of a file that a reader has open fails with an access
   * error, so within this JVM readers and the replacing writer take turns. A process that is not
   * this JVM (a virus scanner, a backup tool, a second store process) can still make a replace fail
   * that way; that is an {@link IOException} and the caller's to retry.
   *
   * @throws SealedFormat.CorruptException when the meta file is damaged
   */
  Optional<SealedFormat.Meta> readMeta(EvidenceKey key) throws IOException {
    Path file = metaPath(key);
    ReentrantLock lock = metaLock(file);
    lock.lock();
    try {
      return readMetaFile(file);
    } finally {
      lock.unlock();
    }
  }

  private static ReentrantLock metaLock(Path file) {
    return META_LOCKS[Math.floorMod(file.hashCode(), META_STRIPES)];
  }

  private Optional<SealedFormat.Meta> readMetaFile(Path file) throws IOException {
    try {
      return Optional.of(SealedFormat.decodeMeta(ops.read(file)));
    } catch (NoSuchFileException e) {
      return Optional.empty();
    }
  }

  /**
   * Replaces the meta of the key by {@code meta} when the stored revision is lower (or there is no
   * meta): temp, force, atomic replace, directory sync. When the stored revision is the same or
   * higher nothing is changed, the directory is synced (the stored meta may be one whose sync
   * failed) and the result is false. The compare and the replace are one step per file within this
   * JVM.
   *
   * @throws SealedFormat.CorruptException when the stored meta is damaged (it is never replaced)
   */
  boolean writeMeta(EvidenceKey key, SealedFormat.Meta meta) throws IOException {
    byte[] encoded = SealedFormat.encodeMeta(meta);
    Path directory = directoryOf(key);
    Path target = metaPath(key);
    ReentrantLock lock = metaLock(target);
    lock.lock();
    try {
      Optional<SealedFormat.Meta> stored = readMetaFile(target);
      if (stored.isPresent() && stored.get().rev() >= meta.rev()) {
        syncDirectories(directory);
        return false;
      }
      ensureDirectory(directory);
      Path temp = writeTemporary(directory, nameOf(target), encoded);
      try {
        ops.replace(temp, target);
      } catch (IOException | RuntimeException e) {
        discardAfterFailure(temp, e);
        throw e;
      }
      syncDirectories(directory);
      return true;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Syncs the directory of the meta of the key, as {@link #writeMeta} does when the stored revision
   * is the same or higher: a meta that is found present is not taken as durable, because the sync
   * that followed its replace may have failed or never happened. Call it for a volume whose meta
   * exists; it writes nothing. A no-op where the platform cannot sync a directory.
   *
   * @throws IOException when a sync fails
   */
  void resyncMeta(EvidenceKey key) throws IOException {
    syncDirectories(directoryOf(key));
  }

  // ---------------------------------------------------------------- temporaries

  /** What the sweep does with one temporary file. */
  private enum Fate {
    /** Not ours, or owned by a running writer: not touched, not reported. */
    LEAVE,
    /** Debris that is no trace of an acknowledged write: deleted. */
    DELETE,
    /** A complete object or meta that may be the only surviving name of a write: kept, reported. */
    KEEP
  }

  /**
   * Deletes the temporary files ({@code ~tmp~<pid>~<counter>}) that no running writer can own and
   * that are not the only trace of a write, anywhere in the volume. A file is no running writer's
   * when its pid is not running, or when its pid is running but it was last written more than two
   * seconds before this JVM started (a container whose JVM is always pid 1, or a pid that was
   * handed on to another process, would otherwise keep the debris of its predecessors for ever;
   * where the platform does not report the start of this JVM only the first rule applies). The
   * files that this JVM or another running process wrote since and names that do not have the
   * temporary form are left alone.
   *
   * <p><b>A complete temporary file of an object or a meta is kept.</b> Where no directory is
   * forced (Windows) a power loss can leave the forced temporary file of an acknowledged write
   * without the link that gave it its final name. Such a file is decoded first: when it is a whole,
   * valid file whose header key is the key that its directory and name give (an object) or a valid
   * meta, and the final name does not hold the same bytes (for a meta: and does not hold a higher
   * revision), it is not deleted but left in place, where it is never listed or served, and {@link
   * #completeTemporaries} names it so that an operator can adopt or remove it. Only files that do
   * not decode (half written), that belong to another key than their place says, and those whose
   * final name already holds the same bytes are deleted. A file that cannot be read is an {@link
   * IOException}: nothing that was not inspected is deleted.
   *
   * <p>The owner of a file is told by the pid of this PID namespace and the age of the file alone:
   * a second process on the same directory in another container or on another host is not
   * recognised, and a temporary file of it can be swept from under it (its publish then fails and
   * is retried). Sharing a volume between processes is not claimed.
   *
   * @return the number of files deleted
   */
  int sweepTemporaries() throws IOException {
    int swept = 0;
    for (Path file : temporaryFiles()) {
      if (fateOf(file) != Fate.DELETE) {
        continue;
      }
      try {
        ops.delete(file);
      } catch (DirectoryNotEmptyException e) {
        continue; // a directory with a temporary name is not ours to remove
      }
      swept++;
    }
    return swept;
  }

  /**
   * The temporary files that {@link #sweepTemporaries} keeps because each is a complete object or
   * meta that may be the only surviving name of a write, as paths below the root of the volume with
   * '/' (for example {@code o/d-landing/o-k1~tmp~4711~3}), sorted. Writes nothing.
   */
  List<String> completeTemporaries() throws IOException {
    List<String> kept = new ArrayList<>();
    for (Path file : temporaryFiles()) {
      if (fateOf(file) == Fate.KEEP) {
        StringJoiner joined = new StringJoiner("/");
        for (Path part : root.relativize(file)) {
          joined.add(part.toString());
        }
        kept.add(joined.toString());
      }
    }
    kept.sort(Comparator.naturalOrder());
    return kept;
  }

  /** Every file or directory with a temporary name in the root, in {@code o} and below. */
  private List<Path> temporaryFiles() throws IOException {
    List<Path> found = new ArrayList<>();
    for (Path entry : ops.list(root)) {
      String name = nameOf(entry);
      if (SealedNames.isTemporary(name)) {
        found.add(entry);
      } else if (name.equals(OBJECTS)) {
        collectTemporaries(entry, found);
      }
    }
    return found;
  }

  private void collectTemporaries(Path directory, List<Path> found) throws IOException {
    for (Path entry : ops.list(directory)) {
      String name = nameOf(entry);
      if (SealedNames.isTemporary(name)) {
        found.add(entry);
      } else if (name.startsWith("d-")) {
        collectTemporaries(entry, found);
      }
    }
  }

  private Fate fateOf(Path file) throws IOException {
    String name = nameOf(file);
    OptionalLong pid = temporaryPid(name);
    if (pid.isEmpty()) {
      return Fate.LEAVE;
    }
    if (ProcessHandle.of(pid.getAsLong()).isPresent()) {
      Optional<Instant> start = processStart.get();
      if (start.isEmpty()) {
        return Fate.LEAVE; // no start instant: a running pid owns its files
      }
      Instant modified;
      try {
        modified = Files.getLastModifiedTime(file).toInstant();
      } catch (NoSuchFileException e) {
        return Fate.LEAVE; // gone already: not ours to count
      }
      if (!modified.isBefore(start.get().minus(START_SKEW))) {
        return Fate.LEAVE; // written by this incarnation or by a process that has run since
      }
    }
    try {
      return mayBeTheOnlyTrace(file, name.substring(0, name.indexOf("~tmp~")))
          ? Fate.KEEP
          : Fate.DELETE;
    } catch (NoSuchFileException e) {
      return Fate.LEAVE; // gone already
    }
  }

  /**
   * True when the temporary file is a complete object or meta whose final name {@code base} does
   * not yet hold it (see {@link #sweepTemporaries}).
   */
  private boolean mayBeTheOnlyTrace(Path file, String base) throws IOException {
    if (base.length() < 3
        || (base.charAt(0) != 'o' && base.charAt(0) != 'm')
        || !Files.isRegularFile(file)) {
      return false; // the self-test, a directory, a name that is not an object's or a meta's
    }
    String segment;
    try {
      segment = SealedNames.decode(base);
    } catch (IllegalArgumentException e) {
      return false;
    }
    Optional<String> keyPath = keyPathOf(file.getParent(), segment);
    if (keyPath.isEmpty()) {
      return false;
    }
    byte[] bytes = ops.read(file);
    Optional<byte[]> named;
    try {
      named = Optional.of(ops.read(file.resolveSibling(base)));
    } catch (NoSuchFileException e) {
      named = Optional.empty();
    }
    if (base.charAt(0) == 'o') {
      return decodesAsObject(bytes, keyPath.get())
          && (named.isEmpty() || !Arrays.equals(named.get(), bytes));
    }
    SealedFormat.Meta meta;
    try {
      meta = SealedFormat.decodeMeta(bytes);
    } catch (SealedFormat.CorruptException | IllegalArgumentException e) {
      return false;
    }
    if (named.isEmpty()) {
      return true;
    }
    if (Arrays.equals(named.get(), bytes)) {
      return false;
    }
    try {
      return SealedFormat.decodeMeta(named.get()).rev() <= meta.rev();
    } catch (SealedFormat.CorruptException | IllegalArgumentException e) {
      return true; // a damaged stored meta: the temporary may be the only good one
    }
  }

  private static boolean decodesAsObject(byte[] bytes, String keyPath) {
    try {
      SealedFormat.decodeObject(bytes, keyPath);
      return true;
    } catch (SealedFormat.CorruptException | IllegalArgumentException e) {
      return false;
    }
  }

  /** The key path of a file in {@code directory} with the last segment, if the place is ours. */
  private Optional<String> keyPathOf(Path directory, String lastSegment) {
    Path objects = root.resolve(OBJECTS);
    if (!directory.startsWith(objects)) {
      return Optional.empty();
    }
    StringJoiner path = new StringJoiner("/");
    for (Path part : objects.relativize(directory)) {
      String name = nameOf(part);
      if (name.isEmpty()) {
        continue; // the relative path of the directory itself
      }
      if (!name.startsWith("d-")) {
        return Optional.empty();
      }
      try {
        path.add(SealedNames.decode(name));
      } catch (IllegalArgumentException e) {
        return Optional.empty();
      }
    }
    path.add(lastSegment);
    return Optional.of(path.toString());
  }

  /** The pid of {@code <name>~tmp~<pid>~<counter>} (both numbers digits only), else empty. */
  private static OptionalLong temporaryPid(String name) {
    String[] parts = name.split("~", -1);
    if (parts.length != 4 || !parts[1].equals("tmp") || !digits(parts[2]) || !digits(parts[3])) {
      return OptionalLong.empty();
    }
    try {
      return OptionalLong.of(Long.parseLong(parts[2]));
    } catch (NumberFormatException e) {
      return OptionalLong.empty(); // more digits than a long holds
    }
  }

  private static boolean digits(String text) {
    if (text.isEmpty()) {
      return false;
    }
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  // ---------------------------------------------------------------- publishing a file

  private boolean publishFile(Path directory, Path target, byte[] bytes) throws IOException {
    ensureDirectory(directory);
    Path temp = writeTemporary(directory, nameOf(target), bytes);
    boolean linked;
    try {
      linked = link(temp, target);
    } catch (IOException | RuntimeException e) {
      discardAfterFailure(temp, e);
      throw e;
    }
    discard(temp);
    // a name that was already there may stem from an attempt of an earlier process that died
    // before it synced the directories it had made, and this JVM cannot know: sync the chain
    syncDirectories(directory, !linked);
    return linked;
  }

  /** True when {@code target} was created, false when the name exists. */
  private boolean link(Path temp, Path target) throws IOException {
    try {
      ops.link(temp, target);
      return true;
    } catch (FileAlreadyExistsException e) {
      return false;
    } catch (UnsupportedOperationException e) {
      throw new IOException("volume " + volumeId + " cannot create hard links", e);
    }
  }

  /**
   * Writes {@code bytes} to a new temporary name beside the final name and returns it. A name that
   * is taken (a file left by another process with the same pid) is skipped, never touched; a file
   * that this call created and could not finish is deleted.
   */
  private Path writeTemporary(Path directory, String finalName, byte[] bytes) throws IOException {
    IOException taken = null;
    for (int attempt = 0; attempt < MAX_TEMP_TRIES; attempt++) {
      Path temp =
          directory.resolve(SealedNames.temporary(finalName, PID, TEMP_COUNTER.getAndIncrement()));
      try {
        ops.writeAndForce(temp, bytes);
        return temp;
      } catch (FileAlreadyExistsException e) {
        taken = e;
      } catch (IOException | RuntimeException e) {
        discardAfterFailure(temp, e);
        throw e;
      }
    }
    throw new IOException("volume " + volumeId + ": no free temporary name", taken);
  }

  /** Removes a temporary file after the work is done; a file that stays is swept later. */
  private void discard(Path temp) {
    try {
      ops.delete(temp);
    } catch (IOException e) {
      // never listed or served (temporary names), and swept once its process is gone
    }
  }

  private void discardAfterFailure(Path temp, Throwable primary) {
    try {
      ops.delete(temp);
    } catch (IOException e) {
      primary.addSuppressed(e);
    }
  }

  // ---------------------------------------------------------------- directories

  /**
   * Makes sure {@code directory} exists. The directories that are missing are registered as "parent
   * not yet synced" before they are created, so that the sync that follows the link (or a retry
   * after a failure) covers them.
   */
  private void ensureDirectory(Path directory) throws IOException {
    DIRECTORY_LOCK.lock();
    try {
      List<Path> missing = new ArrayList<>();
      Path probe = directory;
      while (probe != null && !Files.isDirectory(probe)) {
        missing.add(probe);
        probe = probe.equals(root) ? null : probe.getParent();
      }
      if (missing.isEmpty()) {
        return;
      }
      if (ops.supportsDirectorySync()) {
        UNSYNCED.addAll(missing);
      }
      ops.createDirectories(directory);
    } finally {
      DIRECTORY_LOCK.unlock();
    }
  }

  private void syncDirectories(Path holder) throws IOException {
    syncDirectories(holder, false);
  }

  /**
   * Syncs the directory that holds a new or found entry, and the parent of every directory on the
   * way up to the root that was created and not yet synced. With {@code wholeChain} every directory
   * from the holder up to the root is synced, whatever the bookkeeping says (the chain visits each
   * directory once, so no call is repeated). The parent of the root is synced here only when this
   * volume object created the root itself (a root that vanished after the open); a store creates
   * its roots with {@link #ensureRoot}, which syncs the parent of the root and of each ancestor it
   * created. A no-op where the platform cannot sync a directory.
   */
  private void syncDirectories(Path holder, boolean wholeChain) throws IOException {
    if (!ops.supportsDirectorySync()) {
      return;
    }
    ops.syncDirectory(holder);
    Path directory = holder;
    while (directory != null) {
      boolean atRoot = directory.equals(root);
      Path parent = directory.getParent();
      if (parent != null && ((wholeChain && !atRoot) || UNSYNCED.contains(directory))) {
        ops.syncDirectory(parent);
      }
      // The pending mark is cleared only AFTER the sync of the parent has succeeded: when that
      // sync throws, the mark stays, and the next publish into this directory (of any key) syncs
      // the parent again. Clearing it first would let a different key be acknowledged while the
      // entry of this directory in its parent was never synced.
      UNSYNCED.remove(directory);
      directory = atRoot ? null : parent;
    }
  }

  // ---------------------------------------------------------------- names

  private Path directoryOf(EvidenceKey key) {
    String[] segments = key.path().split("/", -1);
    Path directory = root.resolve(OBJECTS);
    for (int i = 0; i < segments.length - 1; i++) {
      directory = directory.resolve(SealedNames.directory(segments[i]));
    }
    return directory;
  }

  private Path metaPath(EvidenceKey key) {
    return directoryOf(key).resolve(SealedNames.meta(lastSegment(key)));
  }

  private static String lastSegment(EvidenceKey key) {
    String path = key.path();
    return path.substring(path.lastIndexOf('/') + 1);
  }

  private static String nameOf(Path entry) {
    return entry.getFileName().toString();
  }
}
