package dev.regpack.flow.ingest;

import dev.regpack.model.Checkpoint;
import dev.regpack.model.KeyText;
import dev.regpack.model.Sha256;
import dev.regpack.ports.Clock;
import dev.regpack.ports.connector.CheckResult;
import dev.regpack.ports.connector.ConnectorKind;
import dev.regpack.ports.connector.ConnectorSpec;
import dev.regpack.ports.connector.SourceConnector;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * A {@link SourceConnector} over a directory of CSV files. A file {@code <name>.csv} is a batch
 * once its completion marker {@code <name>.csv.done} ({@link FileMarker}) is there and says the
 * size and SHA-256 the file really has; a file without a valid marker waits and is listed by {@link
 * #waiting()} with the reason (SF9: it is never lost and never read half written). Every file,
 * marker included, is checked with its size before it is opened: a CSV over the cap is never read.
 *
 * <p>The batch is the whole file (media type {@code text/csv}); its manifest carries the record
 * count and the control totals the marker declares, not what the file holds: the stage after the
 * connector compares the two. The sequence number comes from a counter of this instance when a file
 * is first delivered; it is never reused or reset, a file still in the inbox keeps its number on a
 * new read, and a file dropped again after it was moved is a new file with a new number. A
 * checkpoint is the sequence number as 8 big-endian bytes.
 *
 * <p>{@code acknowledge} is cumulative and is the only thing that moves a file (SF10): every
 * delivered file of the stream with a number up to the checkpoint goes, the CSV first and its
 * marker second, to the folder of its {@link Verdict} (default {@link Verdict#PROCESSED}). A file
 * is moved only if it still is what was delivered: at acknowledge time the CSV in the inbox must
 * have the size and SHA-256 of the delivered bytes. If a producer has put other bytes under the
 * name since, nothing moves (the CSV and its marker stay in the inbox) and the next read delivers
 * the new file with a new number; unread data is never moved. A move never overwrites: into a free
 * name by a hard link and a delete of the source (where no link can be made, a copy to a temporary
 * name in the target folder, a rename to the final name that refuses to replace, and a delete of
 * the source); onto a name that holds the same bytes by deleting the source; onto a name that holds
 * other bytes into {@code <stem>.<n><ext>} with the smallest {@code n >= 1} for which the CSV name
 * and the marker name {@code <csv name>.done} are both usable (free, or holding the same bytes), so
 * the pair keeps one number. A crash between the steps leaves the source in the inbox, delivers it
 * again, and the retried move finds its own earlier copy. The check of the bytes and the move are
 * not one atomic step: a file written in the instant between them is not detected.
 *
 * <p>The directory is the trust boundary: the connector reads regular files only (links are not
 * followed) and does not authenticate who wrote them. Failures are {@link IllegalStateException}s
 * with a fixed text: no path and no cause. Thread-safe.
 */
public final class FileSourceConnector implements SourceConnector {
  /** Why a file in the inbox is not a batch (yet). */
  public enum Reason {
    /** The CSV has no marker file. */
    NO_MARKER,
    /** The marker is not a valid marker for this file. */
    MARKER_INVALID,
    /** The marker is valid and the file is not what it says (size or digest). */
    MARKER_MISMATCH,
    /** The CSV is larger than the cap and was not read. */
    OVERSIZED,
    /** The name is a directory, a link or another thing that is not a regular file. */
    NOT_REGULAR,
    /** The name is not one the source accepts (see {@code FileMarker}). */
    NAME_INVALID
  }

  /**
   * A file that waits.
   *
   * @param file the name of the CSV in the inbox
   * @param firstSeen when this instance first saw the file wait
   * @param reason why it waits
   */
  public record Waiting(String file, Instant firstSeen, Reason reason) {
    public Waiting {
      Objects.requireNonNull(file, "file");
      Objects.requireNonNull(firstSeen, "firstSeen");
      Objects.requireNonNull(reason, "reason");
    }
  }

  /** Lists the candidate files of the inbox; a seam so that a test can feed another order. */
  @FunctionalInterface
  interface Lister {
    List<Path> list(Path inbox);
  }

  /** Makes {@code link} a hard link to {@code existing}; a seam so that a test can refuse. */
  @FunctionalInterface
  interface Linker {
    void link(Path link, Path existing) throws IOException;
  }

  /** The largest cap on the size of a CSV the constructor accepts (production uses 1 MiB). */
  static final long MAX_CAP_BYTES = 16L * 1024 * 1024;

  private static final String MEDIA_TYPE = "text/csv";
  private static final String CSV = ".csv";
  private static final String MARKER = ".done";
  private static final String PART = ".part";
  private static final int MAX_SUFFIX = 100_000;

  private final Object lock = new Object();
  private final Path inbox;
  private final Path processed;
  private final Path rejected;
  private final Path quarantine;
  private final Clock clock;
  private final String stream;
  private final long maxBytes;
  private final Lister lister;
  private final Linker linker;

  private long nextSeq = 1;
  private boolean failureArmed;
  private long bytesRead;
  private final Map<Long, Entry> inFlight = new TreeMap<>();
  private final Map<String, Waiting> waiting = new TreeMap<>();

  /** A file delivered and not yet moved. */
  private static final class Entry {
    final String name;
    final Sha256 sha256;
    final int size;
    Verdict verdict = Verdict.PROCESSED;

    Entry(String name, Sha256 sha256, int size) {
      this.name = name;
      this.sha256 = sha256;
      this.size = size;
    }
  }

  /**
   * @param inbox where the files arrive
   * @param processed where an acknowledged file goes when its verdict is PROCESSED
   * @param rejected where it goes when its verdict is REJECTED
   * @param quarantine where it goes when its verdict is QUARANTINED
   * @param clock for the instant a waiting file was first seen
   * @param stream the name of the stream, a key text
   * @param limits {@code maxBytesPerBatch} is the cap on the size of a CSV (1 MiB in production, at
   *     most 16 MiB); the cap of a read is the smaller of this and the limit of its request
   */
  public FileSourceConnector(
      Path inbox,
      Path processed,
      Path rejected,
      Path quarantine,
      Clock clock,
      String stream,
      ReadLimits limits) {
    this(
        inbox,
        processed,
        rejected,
        quarantine,
        clock,
        stream,
        limits,
        FileSourceConnector::listCsv);
  }

  FileSourceConnector(
      Path inbox,
      Path processed,
      Path rejected,
      Path quarantine,
      Clock clock,
      String stream,
      ReadLimits limits,
      Lister lister) {
    this(inbox, processed, rejected, quarantine, clock, stream, limits, lister, Files::createLink);
  }

  FileSourceConnector(
      Path inbox,
      Path processed,
      Path rejected,
      Path quarantine,
      Clock clock,
      String stream,
      ReadLimits limits,
      Lister lister,
      Linker linker) {
    this.inbox = Objects.requireNonNull(inbox, "inbox");
    this.processed = Objects.requireNonNull(processed, "processed");
    this.rejected = Objects.requireNonNull(rejected, "rejected");
    this.quarantine = Objects.requireNonNull(quarantine, "quarantine");
    this.clock = Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(stream, "stream");
    if (stream.isBlank()) {
      throw new IllegalArgumentException("stream must not be blank");
    }
    this.stream = KeyText.require("stream", stream);
    this.maxBytes = Objects.requireNonNull(limits, "limits").maxBytesPerBatch();
    if (maxBytes > MAX_CAP_BYTES) {
      throw new IllegalArgumentException("maxBytesPerBatch is above the ceiling of the source");
    }
    this.lister = Objects.requireNonNull(lister, "lister");
    this.linker = Objects.requireNonNull(linker, "linker");
  }

  /** Every {@code *.csv} entry of {@code dir}, whatever it is. */
  static List<Path> listCsv(Path dir) {
    try (Stream<Path> list = Files.list(dir)) {
      return list.filter(p -> p.getFileName().toString().endsWith(CSV)).toList();
    } catch (IOException e) {
      throw new IllegalStateException("cannot list the inbox");
    }
  }

  /** The next {@code next(n)} of any stream throws a {@link RuntimeException}, once. */
  void failNextRead() {
    synchronized (lock) {
      failureArmed = true;
    }
  }

  /** The bytes of CSV files read so far (test and diagnostics). */
  long bytesRead() {
    synchronized (lock) {
      return bytesRead;
    }
  }

  /**
   * Records what became of the delivered file with this sequence number; the default is PROCESSED.
   */
  public void verdict(long seq, Verdict verdict) {
    Objects.requireNonNull(verdict, "verdict");
    synchronized (lock) {
      Entry entry = inFlight.get(seq);
      if (entry == null) {
        throw new IllegalArgumentException("No delivered, unmoved file has this seq");
      }
      entry.verdict = verdict;
    }
  }

  /** The files that wait, by name, as of the last time the inbox was looked at. */
  public List<Waiting> waiting() {
    synchronized (lock) {
      return List.copyOf(waiting.values());
    }
  }

  @Override
  public ConnectorSpec spec() {
    return new ConnectorSpec(
        "file-csv", "1", ConnectorKind.SOURCE, Map.of("stream", stream, "mediaType", MEDIA_TYPE));
  }

  @Override
  public CheckResult check() {
    Map<String, Path> folders = new TreeMap<>();
    folders.put("inbox", inbox);
    folders.put("processed", processed);
    folders.put("rejected", rejected);
    folders.put("quarantine", quarantine);
    for (Map.Entry<String, Path> folder : folders.entrySet()) {
      if (!Files.isDirectory(folder.getValue())) {
        return new CheckResult(false, "the " + folder.getKey() + " folder does not exist");
      }
    }
    return new CheckResult(true, "inbox, processed, rejected and quarantine folders exist");
  }

  @Override
  public Discovery discover() {
    return new Discovery(List.of(new StreamInfo(stream, List.of("sourceRecordId"))));
  }

  @Override
  public BatchStream read(ReadRequest request) {
    Objects.requireNonNull(request, "request");
    long resumeAfter = 0;
    if (request.resumeFrom().isPresent()) {
      resumeAfter = position(request.resumeFrom().get());
    }
    long cap = Math.min(maxBytes, request.limits().maxBytesPerBatch());
    List<String> names;
    synchronized (lock) {
      if (resumeAfter >= nextSeq) {
        // a position this instance never issued (for example one saved by an earlier instance)
        // would make every new file look already delivered
        throw new IllegalArgumentException("Checkpoint is beyond the sequence numbers issued");
      }
      names = new ArrayList<>();
      for (Path path : lister.list(inbox)) {
        String name = path.getFileName().toString();
        if (name.endsWith(CSV)) {
          names.add(name);
        }
      }
      names.sort(String::compareTo);
      waiting.keySet().retainAll(new HashSet<>(names));
    }
    return new FileStream(names, resumeAfter, cap);
  }

  private long position(Checkpoint checkpoint) {
    byte[] state = checkpoint.state();
    if (!stream.equals(checkpoint.stream()) || state.length != Long.BYTES) {
      throw new IllegalArgumentException("Checkpoint does not belong to stream " + stream);
    }
    long position = ByteBuffer.wrap(state).getLong();
    if (position < 0) {
      throw new IllegalArgumentException("Checkpoint position out of range");
    }
    return position;
  }

  private Checkpoint checkpoint(long seq) {
    return new Checkpoint(stream, ByteBuffer.allocate(Long.BYTES).putLong(seq).array());
  }

  // ------------------------------------------------------------------------------------------

  private final class FileStream implements BatchStream {
    private final List<String> names;
    private final long resumeAfter;
    private final long cap;
    private final Set<Long> delivered = new HashSet<>();
    private int cursor;
    private boolean closed;

    FileStream(List<String> names, long resumeAfter, long cap) {
      this.names = names;
      this.resumeAfter = resumeAfter;
      this.cap = cap;
    }

    @Override
    public List<Batch> next(int credits) {
      synchronized (lock) {
        if (closed) {
          throw new IllegalStateException("Stream is closed");
        }
        if (credits < 0) {
          throw new IllegalArgumentException("credits must not be negative: " + credits);
        }
        if (failureArmed) {
          failureArmed = false;
          throw new IllegalStateException("injected source read failure");
        }
        List<Batch> out = new ArrayList<>();
        while (out.size() < credits && cursor < names.size()) {
          String name = names.get(cursor++);
          Optional<Entry> known = entryOf(name);
          if (known.isPresent() && seqOf(known.get()) <= resumeAfter) {
            continue;
          }
          Optional<Loaded> batch = load(name, cap);
          if (batch.isEmpty()) {
            continue;
          }
          Loaded loaded = batch.get();
          long seq = seqFor(name, loaded.marker.sha256(), loaded.bytes.length);
          if (seq <= resumeAfter) {
            continue;
          }
          delivered.add(seq);
          Manifest manifest =
              new Manifest(
                  loaded.marker.records(), loaded.marker.sha256(), loaded.marker.controlTotals());
          out.add(
              new Batch(
                  stream,
                  seq,
                  new BatchPayload(loaded.bytes, MEDIA_TYPE, manifest),
                  "file:" + name,
                  checkpoint(seq)));
        }
        return List.copyOf(out);
      }
    }

    @Override
    public void acknowledge(Checkpoint checkpoint) {
      Objects.requireNonNull(checkpoint, "checkpoint");
      synchronized (lock) {
        long seq = position(checkpoint);
        if (!delivered.contains(seq)) {
          throw new IllegalArgumentException(
              "Cannot acknowledge a seq this stream did not deliver");
        }
        for (Long pending : new ArrayList<>(inFlight.keySet())) {
          if (pending <= seq && delivered.contains(pending)) {
            moveFile(inFlight.get(pending));
            inFlight.remove(pending);
          }
        }
      }
    }

    @Override
    public boolean exhausted() {
      synchronized (lock) {
        return cursor >= names.size();
      }
    }

    @Override
    public void close() {
      synchronized (lock) {
        closed = true;
      }
    }
  }

  // ------------------------------------------------------------------------------------------
  // All below runs under the lock.

  private Optional<Entry> entryOf(String name) {
    for (Entry entry : inFlight.values()) {
      if (entry.name.equals(name)) {
        return Optional.of(entry);
      }
    }
    return Optional.empty();
  }

  private long seqOf(Entry entry) {
    for (Map.Entry<Long, Entry> e : inFlight.entrySet()) {
      if (e.getValue() == entry) {
        return e.getKey();
      }
    }
    throw new IllegalStateException("entry without seq");
  }

  /**
   * The number of this file: its old one when the same bytes are still in the inbox, else a new
   * one.
   */
  private long seqFor(String name, Sha256 sha, int size) {
    Optional<Entry> known = entryOf(name);
    if (known.isPresent()) {
      if (known.get().sha256.equals(sha)) {
        return seqOf(known.get());
      }
      inFlight.remove(seqOf(known.get()));
    }
    long seq = nextSeq++;
    inFlight.put(seq, new Entry(name, sha, size));
    return seq;
  }

  private record Loaded(byte[] bytes, FileMarker marker) {}

  private void waits(String name, Reason reason) {
    Waiting old = waiting.get(name);
    waiting.put(name, new Waiting(name, old == null ? clock.now() : old.firstSeen(), reason));
  }

  /**
   * Checks the file and, only when it is a valid, complete batch within the cap, reads it. Empty
   * when the file waits (and records why) or has vanished.
   */
  private Optional<Loaded> load(String name, long cap) {
    if (!FileMarker.validName(name)) {
      waits(name, Reason.NAME_INVALID);
      return Optional.empty();
    }
    Path csv = inbox.resolve(name);
    BasicFileAttributes attributes;
    try {
      attributes = Files.readAttributes(csv, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException e) {
      waiting.remove(name);
      return Optional.empty();
    } catch (IOException e) {
      throw new IllegalStateException("cannot look at a source file");
    }
    if (!attributes.isRegularFile()) {
      waits(name, Reason.NOT_REGULAR);
      return Optional.empty();
    }
    long size = attributes.size();
    if (size > cap) {
      waits(name, Reason.OVERSIZED);
      return Optional.empty();
    }
    Path markerPath = inbox.resolve(name + MARKER);
    byte[] markerBytes;
    try {
      BasicFileAttributes markerAttributes =
          Files.readAttributes(markerPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!markerAttributes.isRegularFile() || markerAttributes.size() > FileMarker.MAX_BYTES) {
        waits(name, Reason.MARKER_INVALID);
        return Optional.empty();
      }
      markerBytes = readBounded(markerPath, FileMarker.MAX_BYTES);
    } catch (NoSuchFileException e) {
      waits(name, Reason.NO_MARKER);
      return Optional.empty();
    } catch (IOException e) {
      throw new IllegalStateException("cannot look at a marker");
    }
    Optional<FileMarker> parsed = FileMarker.parse(markerBytes, name);
    if (parsed.isEmpty()) {
      waits(name, Reason.MARKER_INVALID);
      return Optional.empty();
    }
    FileMarker marker = parsed.get();
    if (marker.size() != size) {
      waits(name, Reason.MARKER_MISMATCH);
      return Optional.empty();
    }
    byte[] bytes;
    try {
      // size <= cap <= MAX_CAP_BYTES, so the cast cannot overflow
      bytes = readBounded(csv, (int) size + 1);
    } catch (NoSuchFileException e) {
      waiting.remove(name);
      return Optional.empty();
    } catch (IOException e) {
      throw new IllegalStateException("cannot read a source file");
    }
    bytesRead += bytes.length;
    if (bytes.length != size || !Sha256.of(bytes).equals(marker.sha256())) {
      waits(name, Reason.MARKER_MISMATCH);
      return Optional.empty();
    }
    waiting.remove(name);
    return Optional.of(new Loaded(bytes, marker));
  }

  /** At most {@code limit} bytes of a file, opened without following a link. */
  private static byte[] readBounded(Path path, int limit) throws IOException {
    try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      return in.readNBytes(limit);
    }
  }

  /** What is under the name of a delivered file at acknowledge time. */
  private enum Found {
    /** The bytes that were delivered. */
    SAME,
    /** Nothing: an earlier acknowledge moved the CSV and stopped before the marker. */
    GONE,
    /** Other bytes, or another kind of thing: not what was delivered. */
    CHANGED
  }

  private Found inspect(Entry entry, Path csv) throws IOException {
    if (!Files.exists(csv, LinkOption.NOFOLLOW_LINKS)) {
      return Found.GONE;
    }
    if (!Files.isRegularFile(csv, LinkOption.NOFOLLOW_LINKS)) {
      return Found.CHANGED;
    }
    try {
      byte[] now = readBounded(csv, entry.size + 1);
      boolean same = now.length == entry.size && Sha256.of(now).equals(entry.sha256);
      return same ? Found.SAME : Found.CHANGED;
    } catch (NoSuchFileException e) {
      return Found.GONE;
    }
  }

  /**
   * Moves the delivered file and its marker to the folder of the verdict, or moves nothing when the
   * name holds other bytes than were delivered (the caller drops the entry; the next read delivers
   * the new file).
   */
  private void moveFile(Entry entry) {
    Path dir =
        switch (entry.verdict) {
          case PROCESSED -> processed;
          case REJECTED -> rejected;
          case QUARANTINED -> quarantine;
        };
    Path csv = inbox.resolve(entry.name);
    Path marker = inbox.resolve(entry.name + MARKER);
    try {
      Found found = inspect(entry, csv);
      if (found == Found.CHANGED) {
        return;
      }
      boolean hasMarker = Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS);
      if (found == Found.GONE && !hasMarker) {
        return;
      }
      String stem = entry.name.substring(0, entry.name.length() - CSV.length());
      for (int n = 0; n <= MAX_SUFFIX; n++) {
        // one number for the pair: the marker is named after the CSV it ends up as
        String csvName = n == 0 ? entry.name : stem + "." + n + CSV;
        Path csvTarget = dir.resolve(csvName);
        Path markerTarget = dir.resolve(csvName + MARKER);
        boolean csvFits =
            found == Found.SAME
                ? usable(csv, csvTarget)
                : !Files.exists(csvTarget, LinkOption.NOFOLLOW_LINKS) || holds(csvTarget, entry);
        if (!csvFits || (hasMarker && !usable(marker, markerTarget))) {
          continue;
        }
        if (found == Found.SAME && !place(csv, csvTarget)) {
          continue;
        }
        if (hasMarker && !place(marker, markerTarget)) {
          // somebody took the marker name since the check: it still never overwrites
          move(marker, dir);
        }
        return;
      }
      throw new IllegalStateException("No free name for a moved file");
    } catch (NoSuchFileException e) {
      // a file is already gone: nothing is left to move
    } catch (IOException e) {
      throw new IllegalStateException("cannot move a source file");
    }
  }

  /** The name is free, or holds the same bytes as {@code source} (so a move can delete it). */
  private static boolean usable(Path source, Path target) throws IOException {
    return !Files.exists(target, LinkOption.NOFOLLOW_LINKS) || sameBytes(source, target);
  }

  /** The target is a regular file with the bytes that were delivered. */
  private static boolean holds(Path target, Entry entry) throws IOException {
    return Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
        && Files.size(target) == entry.size
        && Sha256.of(Files.readAllBytes(target)).equals(entry.sha256);
  }

  /**
   * Moves {@code source} into {@code dir} under the smallest free {@code <stem>.<n><ext>} name and
   * overwrites nothing.
   */
  private void move(Path source, Path dir) {
    try {
      if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
        return;
      }
      String name = source.getFileName().toString();
      int dot = name.lastIndexOf('.');
      String stem = dot <= 0 ? name : name.substring(0, dot);
      String ext = dot <= 0 ? "" : name.substring(dot);
      for (int n = 0; n <= MAX_SUFFIX; n++) {
        Path target = dir.resolve(n == 0 ? name : stem + "." + n + ext);
        if (place(source, target)) {
          return;
        }
      }
      throw new IllegalStateException("No free name for a moved file");
    } catch (NoSuchFileException e) {
      // the source is already gone: an earlier acknowledge got as far as the delete
    } catch (IOException e) {
      throw new IllegalStateException("cannot move a source file");
    }
  }

  /** True when the source is now gone and the bytes are at {@code target}; false: name is taken. */
  private boolean place(Path source, Path target) throws IOException {
    while (true) {
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        if (sameBytes(source, target)) {
          Files.delete(source);
          return true;
        }
        return false;
      }
      try {
        linker.link(target, source);
      } catch (FileAlreadyExistsException e) {
        continue; // somebody took the name between the check and the link: look again
      } catch (UnsupportedOperationException | IOException e) {
        // no hard link here (another file system, or not supported): copy, then delete
        if (!copy(source, target)) {
          continue;
        }
      }
      Files.delete(source);
      return true;
    }
  }

  /**
   * Copies {@code source} to {@code target} so that the final name never holds a partial file: to a
   * temporary name in the same folder first, then a rename that refuses to replace. A temporary
   * name left by a crash is ours and is cleared first. False: the final name was taken meanwhile.
   */
  private static boolean copy(Path source, Path target) throws IOException {
    Path part = target.resolveSibling("." + target.getFileName() + PART);
    try {
      Files.deleteIfExists(part);
      Files.copy(source, part);
      Files.move(part, target);
      return true;
    } catch (FileAlreadyExistsException e) {
      return false;
    } finally {
      Files.deleteIfExists(part);
    }
  }

  private static boolean sameBytes(Path a, Path b) throws IOException {
    if (!Files.isRegularFile(b, LinkOption.NOFOLLOW_LINKS) || Files.size(a) != Files.size(b)) {
      return false;
    }
    return Sha256.of(Files.readAllBytes(a)).equals(Sha256.of(Files.readAllBytes(b)));
  }
}
