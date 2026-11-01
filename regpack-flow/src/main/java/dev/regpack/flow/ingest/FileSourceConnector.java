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
 * Names are listed whatever the letter case of the extension, and a name whose extension is not
 * exactly {@code .csv} in lower case (such as {@code TRADES.CSV}) waits as {@link
 * Reason#NAME_INVALID}, so that it is reported and not ignored.
 *
 * <p>The batch is the whole file (media type {@code text/csv}); its manifest carries the record
 * count and the control totals the marker declares, not what the file holds: the stage after the
 * connector compares the two. The sequence number comes from a counter of this instance when a file
 * is first delivered; it is never reused or reset, a file still in the inbox keeps its number on a
 * new read while its bytes are the same, and a file dropped again after it was moved, or rewritten
 * with other bytes, is a new file with a new number. A checkpoint is the sequence number as 8
 * big-endian bytes.
 *
 * <p>{@code acknowledge} is cumulative and is what moves a file (SF10; the one other move is the
 * retry of a marker, below): every delivered file of the stream with a number up to the checkpoint
 * goes, the CSV first and its marker second, to the folder of its {@link Verdict} (default {@link
 * Verdict#PROCESSED}). A file is moved only if it still is what was delivered: at acknowledge time
 * the CSV in the inbox must have the size and SHA-256 of the delivered bytes. If a producer has put
 * other bytes under the name since, nothing moves (the CSV and its marker stay in the inbox) and
 * the next read delivers the new file with a new number. A move never overwrites: into a free name
 * by a hard link and a delete of the source (where no link can be made, a copy to a temporary name
 * in the target folder, a rename to the final name that refuses to replace, and a delete of the
 * source); onto a name that holds the same bytes by deleting the source; onto a name that holds
 * other bytes into {@code <stem>.<n><ext>} with the smallest {@code n >= 1} for which the CSV name
 * and the marker name {@code <csv name>.done} are both usable (free, or holding the same bytes), so
 * the pair keeps one number. The search compares a candidate with the delivered digest and only
 * when the sizes are equal, and never reads the source again for each candidate; it asks the file
 * system about each name it tries, and {@code place} is the check that decides. A crash between the
 * steps leaves the source in the inbox, delivers it again, and the retried move finds its own
 * earlier copy.
 *
 * <p>The check of the bytes and the move are not one atomic step. Before the source is deleted the
 * connector checks that the name still holds the file it linked (a hard link: the same file) and,
 * for a CSV, that the linked or copied bytes are the delivered bytes; if not, it removes only its
 * own link or copy and leaves the source in the inbox, to be delivered again with a new number. Two
 * windows remain. One is between that last check and the delete: a file that a producer puts under
 * the name between the check and the delete is deleted unread. The other is a marker's own: a
 * marker has no delivered digest, so a marker that a producer swaps in during its own copy (the
 * fallback where no hard link can be made) is deleted unread, and its CSV then waits without a
 * marker and is reported.
 *
 * <p>A failure of one file never stalls the others (SF9): a CSV or marker that cannot be looked at
 * or read waits as {@link Reason#UNREADABLE} and the next file is read; a move that fails (the
 * source cannot be deleted, no name is free, the target folder is missing) is listed as {@link
 * Reason#MOVE_FAILED}, the file stays in flight and in the inbox, the other files are moved, and
 * the next acknowledge that covers the file retries. When the CSV was moved and only its marker is
 * left, no later acknowledge covers the file (the next tick opens a new stream and the CSV is no
 * longer listed): the open of a new stream retries that marker, the file stays listed as {@link
 * Reason#MOVE_FAILED} while the retry fails, and after a retry that succeeds it is no longer in
 * flight. The retry never touches a file whose CSV is still in the inbox, nor a marker whose file
 * had no failed move (a marker with no CSV is not a file that waits). Only a failure of the inbox
 * as a whole (it cannot be listed) is an {@link IllegalStateException}.
 *
 * <p>The directory is the trust boundary: the connector reads regular files only (links are not
 * followed: a link named like a CSV waits as {@link Reason#NOT_REGULAR}, a marker that is a link is
 * invalid) and does not authenticate who wrote them. Failures are {@link IllegalStateException}s
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
    NAME_INVALID,
    /** The CSV or its marker could not be looked at or read (a lock, a permission, a fault). */
    UNREADABLE,
    /**
     * The file was delivered and committed, but moving it failed; the next acknowledge retries, and
     * the open of the next stream when only its marker is left.
     */
    MOVE_FAILED
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

  /**
   * Looks at and reads files of the folders without following a link; a seam so that a test can
   * make one named file fail with an {@link IOException} or count the bytes that are read.
   */
  interface Reader {
    /** The attributes of the entry itself (a link is not followed). */
    BasicFileAttributes attributes(Path path) throws IOException;

    /** At most {@code limit} bytes of a file, opened without following a link. */
    byte[] read(Path path, int limit) throws IOException;
  }

  /** Deletes the source of a move once its bytes are placed; a seam so that a test can refuse. */
  @FunctionalInterface
  interface Remover {
    void delete(Path path) throws IOException;
  }

  /**
   * What a test may replace.
   *
   * @param lister lists the candidate files of the inbox
   * @param linker makes the hard link of a move
   * @param reader looks at and reads files
   * @param remover deletes a source after its bytes are placed
   * @param maxSuffix the largest collision number {@code n} tried for a moved file
   */
  record Seams(Lister lister, Linker linker, Reader reader, Remover remover, int maxSuffix) {
    Seams {
      Objects.requireNonNull(lister, "lister");
      Objects.requireNonNull(linker, "linker");
      Objects.requireNonNull(reader, "reader");
      Objects.requireNonNull(remover, "remover");
    }

    static Seams defaults() {
      return new Seams(
          FileSourceConnector::listCsv, Files::createLink, NATIVE, Files::delete, MAX_SUFFIX);
    }

    Seams withLister(Lister other) {
      return new Seams(other, linker, reader, remover, maxSuffix);
    }

    Seams withLinker(Linker other) {
      return new Seams(lister, other, reader, remover, maxSuffix);
    }

    Seams withReader(Reader other) {
      return new Seams(lister, linker, other, remover, maxSuffix);
    }

    Seams withRemover(Remover other) {
      return new Seams(lister, linker, reader, other, maxSuffix);
    }

    Seams withMaxSuffix(int other) {
      return new Seams(lister, linker, reader, remover, other);
    }
  }

  /** The largest cap on the size of a CSV the constructor accepts (production uses 1 MiB). */
  static final long MAX_CAP_BYTES = 16L * 1024 * 1024;

  private static final Reader NATIVE =
      new Reader() {
        @Override
        public BasicFileAttributes attributes(Path path) throws IOException {
          return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public byte[] read(Path path, int limit) throws IOException {
          try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return in.readNBytes(limit);
          }
        }
      };

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
  private final Seams seams;

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

    /** When a move of this file first failed, if it did; the file is retried by a later move. */
    Optional<Instant> moveFailedSince = Optional.empty();

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
    this(inbox, processed, rejected, quarantine, clock, stream, limits, Seams.defaults());
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
    this(
        inbox,
        processed,
        rejected,
        quarantine,
        clock,
        stream,
        limits,
        Seams.defaults().withLister(lister));
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
    this(
        inbox,
        processed,
        rejected,
        quarantine,
        clock,
        stream,
        limits,
        Seams.defaults().withLister(lister).withLinker(linker));
  }

  FileSourceConnector(
      Path inbox,
      Path processed,
      Path rejected,
      Path quarantine,
      Clock clock,
      String stream,
      ReadLimits limits,
      Seams seams) {
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
    this.seams = Objects.requireNonNull(seams, "seams");
  }

  /**
   * Every entry of {@code dir} whose name ends in {@code .csv} in any letter case, whatever it is:
   * a name such as {@code TRADES.CSV} is listed so that it can wait as {@link Reason#NAME_INVALID}
   * and be reported, not ignored.
   */
  static List<Path> listCsv(Path dir) {
    try (Stream<Path> list = Files.list(dir)) {
      return list.filter(p -> hasCsvSuffix(p.getFileName().toString())).toList();
    } catch (IOException e) {
      throw new IllegalStateException("cannot list the inbox");
    }
  }

  /** True when {@code name} ends in {@code .csv}, in any letter case. */
  private static boolean hasCsvSuffix(String name) {
    int length = name.length();
    return length >= CSV.length()
        && name.regionMatches(true, length - CSV.length(), CSV, 0, CSV.length());
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

  /**
   * The files that wait, by name, as of the last time the inbox was looked at: those that are no
   * batch yet, and those that were delivered but whose move failed ({@link Reason#MOVE_FAILED}; the
   * instant is that of the first failure).
   */
  public List<Waiting> waiting() {
    synchronized (lock) {
      Map<String, Waiting> all = new TreeMap<>(waiting);
      for (Entry entry : inFlight.values()) {
        entry.moveFailedSince.ifPresent(
            since ->
                all.putIfAbsent(entry.name, new Waiting(entry.name, since, Reason.MOVE_FAILED)));
      }
      return List.copyOf(all.values());
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
      for (Path path : seams.lister().list(inbox)) {
        String name = path.getFileName().toString();
        if (hasCsvSuffix(name)) {
          names.add(name);
        }
      }
      names.sort(String::compareTo);
      Set<String> listed = new HashSet<>(names);
      waiting.keySet().retainAll(listed);
      retryOrphanMarkers(listed);
    }
    return new FileStream(names, resumeAfter, cap);
  }

  /**
   * Retries the move of a delivered file whose CSV has left the inbox after a failed move, so that
   * its marker is all that is left: no acknowledge of a later stream covers that file, because the
   * CSV is not listed and so is never delivered again. A file whose CSV is still in the inbox is
   * left to the next acknowledge, and a file with no failed move is left as it is (a marker with no
   * CSV is not a file that waits). A file that is settled leaves the flight; one that fails again
   * stays listed as {@link Reason#MOVE_FAILED} with the instant of its first failure.
   */
  private void retryOrphanMarkers(Set<String> listed) {
    inFlight
        .values()
        .removeIf(
            entry ->
                !listed.contains(entry.name)
                    && entry.moveFailedSince.isPresent()
                    && moveFile(entry));
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
          if (pending <= seq && delivered.contains(pending) && moveFile(inFlight.get(pending))) {
            inFlight.remove(pending);
          }
          // a file whose move failed stays in flight: the next acknowledge that covers it
          // retries (the retried move finds its own earlier copy), and the others go on; if only
          // its marker is left, the open of the next stream retries it (retryOrphanMarkers)
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
      attributes = seams.reader().attributes(csv);
    } catch (NoSuchFileException e) {
      waiting.remove(name);
      return Optional.empty();
    } catch (IOException e) {
      waits(name, Reason.UNREADABLE);
      return Optional.empty();
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
      BasicFileAttributes markerAttributes = seams.reader().attributes(markerPath);
      if (!markerAttributes.isRegularFile() || markerAttributes.size() > FileMarker.MAX_BYTES) {
        waits(name, Reason.MARKER_INVALID);
        return Optional.empty();
      }
      markerBytes = seams.reader().read(markerPath, FileMarker.MAX_BYTES);
    } catch (NoSuchFileException e) {
      waits(name, Reason.NO_MARKER);
      return Optional.empty();
    } catch (IOException e) {
      waits(name, Reason.UNREADABLE);
      return Optional.empty();
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
      bytes = seams.reader().read(csv, (int) size + 1);
    } catch (NoSuchFileException e) {
      waiting.remove(name);
      return Optional.empty();
    } catch (IOException e) {
      waits(name, Reason.UNREADABLE);
      return Optional.empty();
    }
    bytesRead += bytes.length;
    if (bytes.length != size || !Sha256.of(bytes).equals(marker.sha256())) {
      waits(name, Reason.MARKER_MISMATCH);
      return Optional.empty();
    }
    waiting.remove(name);
    return Optional.of(new Loaded(bytes, marker));
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
      byte[] now = seams.reader().read(csv, entry.size + 1);
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
   *
   * @return true when the file is settled: moved, nothing left to move, or not what was delivered;
   *     false when the move failed (the entry is marked {@link Reason#MOVE_FAILED} and stays in
   *     flight, so that the next acknowledge that covers it retries, or the open of the next stream
   *     when only its marker is left)
   */
  private boolean moveFile(Entry entry) {
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
        return true;
      }
      boolean hasMarker = Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS);
      if (found == Found.GONE && !hasMarker) {
        return true;
      }
      String stem = entry.name.substring(0, entry.name.length() - CSV.length());
      for (int n = 0; n <= seams.maxSuffix(); n++) {
        // one number for the pair: the marker is named after the CSV it ends up as
        String csvName = n == 0 ? entry.name : stem + "." + n + CSV;
        String markerName = csvName + MARKER;
        Path csvTarget = dir.resolve(csvName);
        Path markerTarget = dir.resolve(markerName);
        // the CSV is compared with the digest it was delivered with, and only when the sizes are
        // equal; the source is not read again for each candidate
        boolean fits =
            (!taken(csvTarget) || holds(csvTarget, entry))
                && (!hasMarker || !taken(markerTarget) || sameBytes(marker, markerTarget));
        if (!fits) {
          continue;
        }
        if (found == Found.SAME) {
          Placed placed = place(csv, csvTarget, Optional.of(entry));
          if (placed == Placed.TAKEN) {
            continue;
          }
          if (placed == Placed.CHANGED) {
            return true; // other bytes are under the name: nothing moved, delivered again later
          }
        }
        if (hasMarker && place(marker, markerTarget, Optional.empty()) == Placed.TAKEN) {
          // somebody took the marker name since the check: it still never overwrites
          return move(marker, dir) || failed(entry);
        }
        return true;
      }
      return failed(entry); // no free name
    } catch (NoSuchFileException e) {
      // a file is already gone, nothing is left to move; but a missing folder is a failed move
      return Files.isDirectory(dir) || failed(entry);
    } catch (IOException e) {
      return failed(entry);
    }
  }

  /** Records that the move of this file failed (the first instant is kept) and says so. */
  private boolean failed(Entry entry) {
    entry.moveFailedSince = Optional.of(entry.moveFailedSince.orElseGet(clock::now));
    return false;
  }

  /** A name is in use in a target folder (a link is not followed; {@code place} decides). */
  private static boolean taken(Path target) {
    return Files.exists(target, LinkOption.NOFOLLOW_LINKS);
  }

  /** The target is a regular file with the bytes that were delivered. */
  private boolean holds(Path target, Entry entry) throws IOException {
    return Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
        && Files.size(target) == entry.size
        && Sha256.of(seams.reader().read(target, entry.size + 1)).equals(entry.sha256);
  }

  /**
   * Moves {@code source} into {@code dir} under the smallest free {@code <stem>.<n><ext>} name and
   * overwrites nothing.
   *
   * @return false when no name up to the largest collision number is free
   */
  private boolean move(Path source, Path dir) throws IOException {
    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
      return true;
    }
    String name = source.getFileName().toString();
    int dot = name.lastIndexOf('.');
    String stem = dot <= 0 ? name : name.substring(0, dot);
    String ext = dot <= 0 ? "" : name.substring(dot);
    for (int n = 0; n <= seams.maxSuffix(); n++) {
      Path target = dir.resolve(n == 0 ? name : stem + "." + n + ext);
      if (place(source, target, Optional.empty()) != Placed.TAKEN) {
        return true;
      }
    }
    return false;
  }

  /** What became of an attempt to put a file under a name of the target folder. */
  private enum Placed {
    /** The bytes are at the target and the source is gone. */
    DONE,
    /** The name holds other bytes: try the next one. */
    TAKEN,
    /** The source is no longer what was linked or copied: only our own link or copy was removed. */
    CHANGED
  }

  /**
   * Puts {@code source} under {@code target} and deletes the source. Before the delete it checks
   * that what was linked or copied is what the source still is and, for a CSV, the bytes that were
   * delivered; otherwise only the link or copy just made is removed and the source stays where it
   * is.
   *
   * @param delivered the delivered file whose bytes a CSV must still have; empty for a marker,
   *     which has no delivered digest
   */
  private Placed place(Path source, Path target, Optional<Entry> delivered) throws IOException {
    while (true) {
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        if (!sameBytes(source, target)) {
          return Placed.TAKEN;
        }
        seams.remover().delete(source);
        return Placed.DONE;
      }
      boolean linked;
      try {
        seams.linker().link(target, source);
        linked = true;
      } catch (FileAlreadyExistsException e) {
        continue; // somebody took the name between the check and the link: look again
      } catch (UnsupportedOperationException | IOException e) {
        // no hard link here (another file system, or not supported): copy, then delete
        if (!copy(source, target)) {
          continue;
        }
        linked = false;
      }
      if (!isWhatWasMoved(source, target, linked, delivered)) {
        Files.deleteIfExists(target);
        return Placed.CHANGED;
      }
      seams.remover().delete(source);
      return Placed.DONE;
    }
  }

  /**
   * The link or copy just made is the source (a hard link: the same file, which the source still is
   * a regular file of) and, for a delivered CSV, holds the bytes that were delivered.
   */
  private boolean isWhatWasMoved(
      Path source, Path target, boolean linked, Optional<Entry> delivered) throws IOException {
    if (linked
        && (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
            || !Files.isSameFile(source, target))) {
      return false;
    }
    if (delivered.isEmpty()) {
      return true;
    }
    Entry entry = delivered.get();
    byte[] bytes = seams.reader().read(target, entry.size + 1);
    return bytes.length == entry.size && Sha256.of(bytes).equals(entry.sha256);
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

  private boolean sameBytes(Path a, Path b) throws IOException {
    if (!Files.isRegularFile(b, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    long size = Files.size(a);
    if (size != Files.size(b) || size >= Integer.MAX_VALUE) {
      return false;
    }
    int limit = (int) size + 1;
    return Sha256.of(seams.reader().read(a, limit))
        .equals(Sha256.of(seams.reader().read(b, limit)));
  }
}
