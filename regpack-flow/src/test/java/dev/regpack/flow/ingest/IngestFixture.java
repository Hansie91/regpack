package dev.regpack.flow.ingest;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.flow.evidence.EvidenceWriter;
import dev.regpack.flow.evidence.TwoCopyEvidenceStore;
import dev.regpack.flow.ingest.FileIngest.Flaw;
import dev.regpack.flow.ingest.FileIngest.IngestReport;
import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.model.Checkpoint;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.ports.connector.SourceConnector.Batch;
import dev.regpack.ports.connector.SourceConnector.BatchStream;
import dev.regpack.ports.connector.SourceConnector.ReadLimits;
import dev.regpack.ports.connector.SourceConnector.ReadRequest;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.ledger.IngestLedger;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.ManifestLedger.ManifestRow;
import dev.regpack.ports.ledger.RetentionLedger.RetentionClaim;
import dev.regpack.ports.ledger.RetentionLedger.RetentionRow;
import dev.regpack.testkit.clock.ManualClock;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Test helper: the whole ingest stage over in-memory ports in a temporary directory. The evidence
 * store, the ledger and the stream are wrapped so that the order of the calls is logged, a ledger
 * failure can be injected, and a crash can be injected at a call boundary. Everything is synthetic.
 */
final class IngestFixture {
  static final Regime REGIME = new Regime("demo.tr.s1");
  static final String SOURCE = "demo-csv";
  static final Duration DEADLINE = Duration.ofHours(1);
  static final Sha256 PACK_DIGEST = Sha256.ofUtf8("w1a test pack");
  static final RetentionPolicy POLICY = RetentionPolicy.fixedDays(1826);

  static final List<String> COLUMNS =
      List.of(
          "record_id",
          "version",
          "event",
          "exec_id",
          "venue_mic",
          "traded_at",
          "isin",
          "on_venue",
          "account",
          "client_lei",
          "client_type",
          "side",
          "capacity",
          "quantity",
          "price",
          "currency",
          "counterparty",
          "exec_within");

  private static final String SAMPLES = "/dev/regpack/flow/samples/";

  /** The mapping of the sample files. */
  static MappingPack sampleMapping() {
    return MappingPack.parse(new String(sample("mapping-csv-trades-v1.yaml"), UTF_8));
  }

  /** A crash that a test injects at a call boundary; it is no defect of the code under test. */
  static final class SimulatedCrash extends RuntimeException {
    private static final long serialVersionUID = 1L;

    SimulatedCrash(String where) {
      super("simulated crash at " + where);
    }
  }

  @FunctionalInterface
  private interface Hook {
    void run(String method, Object[] args);
  }

  final InboxFixture inbox;
  final ManualClock clock;
  final InMemoryLedgerStore ledgerStore;
  final InMemoryEvidenceStore evidenceStore;

  /** The tier of two copies when the fixture was made by {@link #twoCopies}, else null. */
  final TwoCopyEvidenceStore twoCopy;

  final LedgerStore ledger;
  final EvidenceStore evidence;
  final List<String> log = new ArrayList<>();
  final List<IngestLedger.Commit> commits = new ArrayList<>();
  final List<RetentionClaim> claims = new ArrayList<>();
  final MappingPack mapping;
  final EvidenceWriter writer;
  final DqStore dq;
  final FileSourceConnector connector;
  final FileIngest ingest;

  /** While true every {@code commitIngest} throws a {@link LedgerException}. */
  boolean failCommits;

  /** The next {@code commitIngest} stores its batch and then throws a {@link LedgerException}. */
  boolean failAfterApplying;

  /** The next put of a key that starts with this text throws a {@link SimulatedCrash}. */
  String crashOnPut;

  /** The next {@code recordObjects} throws a {@link SimulatedCrash}. */
  boolean crashOnRecordObjects;

  /**
   * The nth following {@code recordObjects} (1 is the next one) throws a {@link SimulatedCrash}; 0
   * is off.
   */
  int crashOnNthRecordObjects;

  /** The next {@code commitIngest} throws this before it reaches the ledger; it is not retried. */
  RuntimeException failNextCommitWith;

  /** The next put of a key that starts with {@link #failNextPutPrefix} throws this. */
  RuntimeException failNextPut;

  String failNextPutPrefix = "";

  IngestFixture(Path root) {
    this(root, Flaw.NONE, 1_048_576L, null);
  }

  IngestFixture(Path root, Flaw flaw) {
    this(root, flaw, 1_048_576L, null);
  }

  IngestFixture(Path root, long maxBytes) {
    this(root, Flaw.NONE, maxBytes, null);
  }

  IngestFixture(Path root, Flaw flaw, long maxBytes, FileIngest.RowMapper rowMapper) {
    this(root, flaw, maxBytes, rowMapper, false);
  }

  /**
   * A fixture over an evidence tier of two copies (see {@link TwoCopyEvidenceStore}); {@link
   * #evidenceStore} is its first copy, which holds every key the tier holds.
   */
  static IngestFixture twoCopies(Path root) {
    return new IngestFixture(root, Flaw.NONE, 1_048_576L, null, true);
  }

  private IngestFixture(
      Path root, Flaw flaw, long maxBytes, FileIngest.RowMapper rowMapper, boolean twoCopies) {
    this.inbox = new InboxFixture(root);
    this.clock = inbox.clock;
    this.ledgerStore = new InMemoryLedgerStore(clock);
    this.twoCopy = twoCopies ? new TwoCopyEvidenceStore(clock) : null;
    this.evidenceStore = twoCopies ? twoCopy.firstVolume() : new InMemoryEvidenceStore(clock);
    EvidenceStore backing = twoCopies ? twoCopy : evidenceStore;
    this.ledger =
        logged(
            LedgerStore.class,
            ledgerStore,
            (method, args) -> {
              switch (method) {
                case "commitIngest" -> {
                  log.add("commitIngest");
                  commits.add((IngestLedger.Commit) args[0]);
                  if (failNextCommitWith != null) {
                    RuntimeException failure = failNextCommitWith;
                    failNextCommitWith = null;
                    throw failure;
                  }
                  if (failCommits) {
                    throw new LedgerException("simulated ledger failure");
                  }
                }
                case "recordObjects" -> {
                  log.add("recordObjects");
                  if (crashOnRecordObjects) {
                    crashOnRecordObjects = false;
                    throw new SimulatedCrash("recordObjects");
                  }
                  if (crashOnNthRecordObjects > 0 && --crashOnNthRecordObjects == 0) {
                    throw new SimulatedCrash("recordObjects");
                  }
                }
                default -> {}
              }
            },
            (method, args) -> {
              if (method.equals("commitIngest") && failAfterApplying) {
                failAfterApplying = false;
                throw new LedgerException("simulated loss of the answer");
              }
            });
    this.evidence =
        logged(
            EvidenceStore.class,
            backing,
            (method, args) -> {
              if (method.equals("put")) {
                EvidenceKey key = ((EvidenceStore.PutRequest) args[0]).key();
                log.add("put:" + key.namespace());
                if (crashOnPut != null && key.path().startsWith(crashOnPut)) {
                  crashOnPut = null;
                  throw new SimulatedCrash("put");
                }
                if (failNextPut != null && key.path().startsWith(failNextPutPrefix)) {
                  RuntimeException failure = failNextPut;
                  failNextPut = null;
                  throw failure;
                }
              }
            },
            (method, args) -> {});
    this.mapping = sampleMapping();
    this.writer =
        new EvidenceWriter(
            evidence,
            ledger,
            claim -> {
              claims.add(claim);
              ledgerStore.recordRetention(List.of(claim));
            },
            POLICY,
            clock,
            REGIME);
    this.dq = new DqStore(writer);
    this.connector = inbox.connector(maxBytes);
    FileIngest.RowMapper mapper = rowMapper == null ? new Mapper(mapping)::map : rowMapper;
    this.ingest =
        new FileIngest(
            clock, writer, ledger, mapping, PACK_DIGEST, SOURCE, dq, DEADLINE, mapper, flaw);
  }

  @SuppressWarnings("unchecked")
  private static <T> T logged(Class<T> type, T target, Hook before, Hook after) {
    return (T)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              Object[] arguments = args == null ? new Object[0] : args;
              before.run(method.getName(), arguments);
              Object result;
              try {
                result = method.invoke(target, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
              after.run(method.getName(), arguments);
              return result;
            });
  }

  // ------------------------------------------------------------------ running

  /** One tick of the ingest: reads the inbox from the start and ingests up to 100 files. */
  IngestReport run() {
    return run(ingest);
  }

  /** One tick of another stage over the same inbox, connector, ledger and evidence tier. */
  IngestReport run(FileIngest stage) {
    ReadRequest request = new ReadRequest(Optional.empty(), new ReadLimits(100, 1_000_000L));
    try (BatchStream stream = new LoggingStream(connector.read(request))) {
      return stage.ingest(stream, connector, 100);
    }
  }

  /** A second stage for another source id, sharing everything else with {@link #ingest}. */
  FileIngest otherSource(String sourceId) {
    return new FileIngest(clock, writer, ledger, mapping, PACK_DIGEST, sourceId, dq, DEADLINE);
  }

  /** A second stage for another pack digest (the pack was upgraded), sharing everything else. */
  FileIngest withPack(Sha256 packDigest) {
    return new FileIngest(clock, writer, ledger, mapping, packDigest, SOURCE, dq, DEADLINE);
  }

  /** Logs the acknowledge and whether the file had left the inbox when it returned. */
  private final class LoggingStream implements BatchStream {
    private final BatchStream delegate;
    private final Map<Long, String> names = new LinkedHashMap<>();

    LoggingStream(BatchStream delegate) {
      this.delegate = delegate;
    }

    @Override
    public List<Batch> next(int credits) {
      List<Batch> batches = delegate.next(credits);
      for (Batch batch : batches) {
        names.put(batch.seq(), batch.sourceCoordinates().substring("file:".length()));
      }
      return batches;
    }

    @Override
    public void acknowledge(Checkpoint checkpoint) {
      long seq = java.nio.ByteBuffer.wrap(checkpoint.state()).getLong();
      log.add("acknowledge");
      delegate.acknowledge(checkpoint);
      String name = names.get(seq);
      log.add(name != null && !inbox.exists(inbox.inbox, name) ? "moved" : "stayed");
    }

    @Override
    public boolean exhausted() {
      return delegate.exhausted();
    }

    @Override
    public void close() {
      delegate.close();
    }
  }

  // ------------------------------------------------------------------ dropping files

  static byte[] sample(String name) {
    try (InputStream in = IngestFixture.class.getResourceAsStream(SAMPLES + name)) {
      if (in == null) {
        throw new IllegalStateException("no sample " + name);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Drops a sample file with the marker the sample comes with. */
  void dropSample(String name) {
    inbox.write(name, sample(name));
    inbox.write(name + ".done", sample(name + ".done"));
  }

  /** Drops a sample file under another name with its own marker (same bytes, new name). */
  void dropSampleAs(String sample, String name) {
    byte[] csv = sample(sample);
    inbox.write(name, csv);
    inbox.write(name + ".done", markerFor(name, csv, countRows(csv), null, true).getBytes(UTF_8));
  }

  /** Drops a CSV and a marker that states {@code records} rows and the sum. */
  void drop(String name, byte[] csv, int records, String sumQuantity) {
    inbox.write(name, csv);
    inbox.write(name + ".done", markerFor(name, csv, records, sumQuantity, true).getBytes(UTF_8));
  }

  /** Drops a CSV and the marker with a custom {@code data_class} member (or none). */
  void dropWithDataClass(String name, byte[] csv, int records, String dataClassMember) {
    inbox.write(name, csv);
    String marker =
        "{"
            + (dataClassMember.isEmpty() ? "" : dataClassMember + ",")
            + "\"file\":\""
            + name
            + "\",\"records\":"
            + records
            + ",\"sha256\":\""
            + Sha256.of(csv).hex()
            + "\",\"size\":"
            + csv.length
            + "}\n";
    inbox.write(name + ".done", marker.getBytes(UTF_8));
  }

  static String markerFor(
      String name, byte[] csv, int records, String sumQuantity, boolean synthetic) {
    return "{"
        + (synthetic ? "\"data_class\":\"SYNTHETIC\"," : "")
        + "\"file\":\""
        + name
        + "\",\"records\":"
        + records
        + ",\"sha256\":\""
        + Sha256.of(csv).hex()
        + "\",\"size\":"
        + csv.length
        + (sumQuantity == null ? "" : ",\"sum_quantity\":\"" + sumQuantity + "\"")
        + "}\n";
  }

  private static int countRows(byte[] csv) {
    int lines = 0;
    for (byte b : csv) {
      if (b == '\n') {
        lines++;
      }
    }
    return lines - 1;
  }

  // ------------------------------------------------------------------ rows

  /** The cells of T-001 of the day-1 sample under the column names. */
  static Map<String, String> cells(String recordId, long version, String execId, String quantity) {
    Map<String, String> cells = new LinkedHashMap<>();
    cells.put("record_id", recordId);
    cells.put("version", Long.toString(version));
    cells.put("event", "NEW");
    cells.put("exec_id", execId);
    cells.put("venue_mic", "XPAR");
    cells.put("traded_at", "2026-10-30T09:30:00.123456Z");
    cells.put("isin", "ZZ0000000016");
    cells.put("on_venue", "true");
    cells.put("account", "ACC-1");
    cells.put("client_lei", "SYNT00CLIENT00000167");
    cells.put("client_type", "LEGAL_ENTITY");
    cells.put("side", "BUY");
    cells.put("capacity", "AOTC");
    cells.put("quantity", quantity);
    cells.put("price", "12.25");
    cells.put("currency", "EUR");
    cells.put("counterparty", "SYNT00CCP00000000194");
    cells.put("exec_within", "ALGO-1");
    return cells;
  }

  static String line(Map<String, String> cells) {
    List<String> ordered = new ArrayList<>();
    for (String column : COLUMNS) {
      ordered.add(cells.get(column));
    }
    return String.join(",", ordered) + "\n";
  }

  static String row(String recordId, long version, String execId, String quantity) {
    return line(cells(recordId, version, execId, quantity));
  }

  static byte[] csv(String... rows) {
    return (InboxFixture.HEADER + String.join("", rows)).getBytes(UTF_8);
  }

  // ------------------------------------------------------------------ counting

  /** The keys of the evidence store that start with {@code prefix}. */
  List<String> keys(String prefix) {
    return evidenceStore.list(prefix, Optional.empty(), 1000).stream()
        .map(EvidenceKey::path)
        .toList();
  }

  /** The stored data-quality objects: the key path to the text of the object, sorted by path. */
  Map<String, String> dqObjects() {
    Map<String, String> objects = new TreeMap<>();
    for (String path : keys("dq/")) {
      objects.put(
          path, new String(evidenceStore.get(new EvidenceKey(path)).orElseThrow().bytes(), UTF_8));
    }
    return objects;
  }

  List<ManifestRow> manifest(String prefix) {
    return ledgerStore.manifestRows(prefix, Optional.empty(), 1000);
  }

  List<RetentionRow> retention(String prefix) {
    return ledgerStore.retentionRows(prefix, Optional.empty(), 1000);
  }

  /** The number of objects, manifest rows and retention rows outside the {@code dq/} namespace. */
  List<Integer> countsOutsideDq() {
    return List.of(
        (int) keys("").stream().filter(k -> !k.startsWith("dq/")).count(),
        (int) manifest("").stream().filter(r -> !r.entry().key().path().startsWith("dq/")).count(),
        (int)
            retention("").stream()
                .filter(r -> !r.claim().artifact().path().startsWith("dq/"))
                .count());
  }

  /** The names in a folder, sorted. */
  List<String> names(Path dir) {
    try (var list = java.nio.file.Files.list(dir)) {
      return list.map(p -> p.getFileName().toString()).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
