package dev.regpack.spikes.l.restore;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.PackShape;
import dev.regpack.bench.data.RulePackGen;
import dev.regpack.bench.data.TradeRecord;
import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.bench.proto.NanoClock;
import dev.regpack.model.Outcome;
import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import dev.regpack.spikes.l.encoding.PathCodec;
import dev.regpack.spikes.l.load.LedgerLoadModel;
import dev.regpack.spikes.l.pg.PgFixture;
import dev.regpack.spikes.l.restore.PackDecider.Decision;
import dev.regpack.spikes.l.schema.SchemaInstaller;
import dev.regpack.spikes.l.schema.SchemaInstaller.Variant;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.postgresql.PGConnection;

/**
 * The secondary full rebuild from evidence (spec 11.4, I10): the landed batches are read again,
 * every record is decided by the rule pack and the decisions and heads are loaded into a fresh
 * schema. NDJSON files of {@value #FILE_RECORDS} records are generated first and are not timed; the
 * page cache is dropped; then the files are read and parsed, each record is decided on the 200-rule
 * S1 pack, and the rows go in with {@code COPY}, {@code deciderThreads} files at a time, one
 * connection and one transaction per file. The result is seconds per million keys at the measured
 * size (L-8), and the report projects it to 10M and 100M keys.
 *
 * <p>What a rebuild cannot recover is not in the landed batches: queue assignments, comments,
 * incident notes and access events (I10); the report says so.
 *
 * <p>The decider is {@link PackDecider}, a narrow interpreter of the generator's own conditions
 * (see its class comment for why it is not the in-house layer of SP-R): the time of the decide step
 * is a lower bound of a rebuild through the in-house layer.
 */
public final class RebuildRun {
  /** The schema of the rebuild. */
  public static final String SCHEMA = "sp_rebuild";

  /** Records per landing file (a micro-batch of the design). */
  public static final int FILE_RECORDS = 5_000;

  /** The seed of the landed records and of the event identities derived from their sequence. */
  static final long SEED = 20_261_014L;

  private static final long PACK_SEED = 20_261_010L;
  private static final int PACK_RULES = 200;
  private static final String REGIME = "EMIR";
  private static final String FILE_GLOB = "landing-*.ndjson";
  private static final List<String> DECISION_COLUMNS =
      List.of(
          "decision_id",
          "regime",
          "event_identity",
          "decision_seq",
          "decision_context_id",
          "input_digest",
          "as_at",
          "outcome",
          "body");
  private static final List<String> HEAD_COLUMNS =
      List.of("regime", "event_identity", "decision_seq", "decision_id");

  private RebuildRun() {}

  /**
   * What a rebuild measured.
   *
   * @param keys records (keys) rebuilt
   * @param deciderThreads files processed at a time
   * @param cacheDropped whether the page cache was dropped before the timer
   * @param wallNanos the time from the start of the first reader to the end of the last commit
   * @param readNanos reading and parsing, summed over the threads
   * @param decideNanos deciding and building the rows, summed over the threads
   * @param copyNanos {@code COPY} and commit, summed over the threads
   * @param content the content of the rebuilt tables, comparable with {@link #directContent}
   */
  public record Result(
      long keys,
      int deciderThreads,
      boolean cacheDropped,
      long wallNanos,
      long readNanos,
      long decideNanos,
      long copyNanos,
      String content) {
    /** Seconds of wall time per million keys, the L-8 figure. */
    public BigDecimal secondsPerMillionKeys() {
      return BigDecimal.valueOf(wallNanos)
          .multiply(BigDecimal.valueOf(1_000_000L))
          .divide(
              BigDecimal.valueOf(keys).multiply(BigDecimal.valueOf(1_000_000_000L)),
              3,
              RoundingMode.HALF_EVEN);
    }
  }

  // ------------------------------------------------------------------ the content of decisions

  /** Counts and sums over the decisions, equal for equal decisions in any order. */
  private static final class Tally {
    long rows;
    final long[] byOutcome = new long[Outcome.values().length + 1];
    long weighted;

    void add(long decisionId, int outcome) {
      rows++;
      byOutcome[outcome]++;
      weighted += decisionId * outcome;
    }

    void merge(Tally other) {
      rows += other.rows;
      for (int i = 0; i < byOutcome.length; i++) {
        byOutcome[i] += other.byOutcome[i];
      }
      weighted += other.weighted;
    }

    String text(long heads) {
      StringBuilder out = new StringBuilder("rows=").append(rows);
      for (int i = 1; i < byOutcome.length; i++) {
        out.append(";o").append(i).append('=').append(byOutcome[i]);
      }
      return out.append(";w=").append(weighted).append(";heads=").append(heads).toString();
    }
  }

  /** The outcome code stored in {@code decision.outcome}: 1 to 4, in the order of the model. */
  static int outcomeCode(String outcome) {
    return Outcome.valueOf(outcome).ordinal() + 1;
  }

  /** What the decisions of the first {@code keys} records are, decided in memory. */
  public static String directContent(long keys) {
    PackDecider decider = decider();
    TradeRecordGen gen = new TradeRecordGen(SEED);
    Tally tally = new Tally();
    for (long i = 0; i < keys; i++) {
      TradeRecord record = gen.next();
      Landed landed = landed(record.toNdjsonLine(), decider);
      tally.add(landed.decisionId(), landed.outcome());
    }
    return tally.text(tally.rows);
  }

  /** Reads the content of the rebuilt tables of {@link #SCHEMA}. */
  static String dbContent(Connection c) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute("SET search_path TO \"" + SCHEMA + "\"");
      try (ResultSet rs =
          st.executeQuery(
              "SELECT count(*), count(*) FILTER (WHERE outcome = 1),"
                  + " count(*) FILTER (WHERE outcome = 2), count(*) FILTER (WHERE outcome = 3),"
                  + " count(*) FILTER (WHERE outcome = 4),"
                  + " coalesce(sum(decision_id * outcome::bigint), 0),"
                  + " (SELECT count(*) FROM decision_head) FROM decision")) {
        rs.next();
        return "rows="
            + rs.getLong(1)
            + ";o1="
            + rs.getLong(2)
            + ";o2="
            + rs.getLong(3)
            + ";o3="
            + rs.getLong(4)
            + ";o4="
            + rs.getLong(5)
            + ";w="
            + rs.getLong(6)
            + ";heads="
            + rs.getLong(7);
      }
    }
  }

  // ------------------------------------------------------------------ landing files

  /**
   * Writes the landing files of the first {@code keys} records, {@value #FILE_RECORDS} to a file
   * (the last one may hold fewer), named {@code landing-00000001.ndjson} and so on. An earlier set
   * of landing files in the directory is removed first. Nothing here is timed.
   *
   * @return the files, in order
   */
  static List<Path> generateLanding(Path dir, long keys) throws IOException {
    Files.createDirectories(dir);
    try (DirectoryStream<Path> old = Files.newDirectoryStream(dir, FILE_GLOB)) {
      for (Path p : old) {
        Files.delete(p);
      }
    }
    TradeRecordGen gen = new TradeRecordGen(SEED);
    List<Path> files = new ArrayList<>();
    long remaining = keys;
    int index = 0;
    while (remaining > 0) {
      Path file = dir.resolve(String.format(Locale.ROOT, "landing-%08d.ndjson", ++index));
      long inFile = Math.min(remaining, FILE_RECORDS);
      try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
        for (long i = 0; i < inFile; i++) {
          out.write(gen.next().toNdjsonLine());
          out.write('\n');
        }
      }
      files.add(file);
      remaining -= inFile;
    }
    return List.copyOf(files);
  }

  // ------------------------------------------------------------------ one landed record

  private static PackDecider decider() {
    GeneratedPack pack =
        RulePackGen.generate(PACK_SEED, PACK_RULES, PackShape.S1_FIRST_HIT_REALISTIC);
    return new PackDecider(pack);
  }

  /** The decision of one landed record and the rows it becomes. */
  private record Landed(
      long decisionId,
      String eventIdentity,
      String inputDigestHex,
      String tradeDate,
      int outcome,
      String bodyHex) {}

  @SuppressWarnings("unchecked")
  private static Landed landed(String line, PackDecider decider) {
    Map<String, Object> record = (Map<String, Object>) Json.parse(line);
    return landed(line, record, decider);
  }

  @SuppressWarnings("unchecked")
  private static Landed landed(String line, Map<String, Object> record, PackDecider decider) {
    long seq = (Long) record.get("seq");
    Decision decision = decider.decide(record);
    int rows = decision.decidingRow();
    int[] ordinals = new int[rows];
    boolean[] matched = new boolean[rows];
    int[] costs = new int[rows];
    for (int i = 0; i < rows; i++) {
      ordinals[i] = i + 1;
      costs[i] = 1;
    }
    matched[rows - 1] = true;
    Map<String, Object> trade = (Map<String, Object>) record.get("trade");
    return new Landed(
        seq,
        LedgerLoadModel.identityOf(SEED, seq),
        Sha256.ofUtf8(line).hex(),
        (String) trade.get("tradeDate"),
        outcomeCode(decision.outcome()),
        hex(PathCodec.encode(ordinals, matched, costs)));
  }

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      out.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
    }
    return out.toString();
  }

  /** A bytea in COPY text: the backslash is doubled because COPY removes one level. */
  private static void bytea(StringBuilder out, String hex) {
    out.append("\\\\x").append(hex);
  }

  // ------------------------------------------------------------------ the run

  /**
   * Generates the landing files in {@code landingDir} (untimed), installs a fresh schema, drops the
   * page cache and rebuilds.
   *
   * @param landingDir a directory on the volume of the clusters; its {@code landing-*.ndjson} files
   *     are replaced
   * @param keys the number of records
   * @param deciderThreads files processed at a time (each with its own connection)
   */
  public static Result run(PgFixture pg, Path landingDir, long keys, int deciderThreads)
      throws SQLException, IOException, InterruptedException {
    return execute(pg, landingDir, keys, deciderThreads, Shell.system(), NanoClock.system());
  }

  static Result execute(
      PgFixture pg, Path landingDir, long keys, int deciderThreads, Shell shell, NanoClock clock)
      throws SQLException, IOException, InterruptedException {
    Objects.requireNonNull(pg, "pg");
    if (keys <= 0) {
      throw new IllegalArgumentException("keys must be positive: " + keys);
    }
    if (deciderThreads <= 0) {
      throw new IllegalArgumentException("deciderThreads must be positive: " + deciderThreads);
    }
    List<Path> files = generateLanding(landingDir, keys);
    LocalDate day = new TradeRecordGen(SEED).next().tradeDate();
    try (Connection c = pg.primary()) {
      c.setAutoCommit(true);
      SchemaInstaller.drop(c, SCHEMA);
      SchemaInstaller.install(c, SCHEMA, Variant.E1_COMPACT, day, 1);
    }
    PackDecider decider = decider();

    boolean cacheDropped = cacheDropped(shell.script("pg-drop-caches.sh"));

    Counters counters = new Counters();
    long start = clock.nanoTime();
    process(pg, files, day.toString(), decider, deciderThreads, counters, clock);
    long wall = clock.nanoTime() - start;

    String content;
    try (Connection c = pg.primary()) {
      content = dbContent(c);
    }
    return new Result(
        keys,
        deciderThreads,
        cacheDropped,
        wall,
        counters.read.get(),
        counters.decide.get(),
        counters.copy.get(),
        content);
  }

  private static boolean cacheDropped(List<String> printed) {
    String value = null;
    for (String line : printed) {
      if (line.strip().startsWith("cache_dropped=")) {
        value = line.strip().substring("cache_dropped=".length());
      }
    }
    if (value == null || !(value.equals("0") || value.equals("1"))) {
      throw new IllegalArgumentException(
          "pg-drop-caches.sh printed no cache_dropped=0|1: " + printed);
    }
    return value.equals("1");
  }

  private static final class Counters {
    final AtomicLong read = new AtomicLong();
    final AtomicLong decide = new AtomicLong();
    final AtomicLong copy = new AtomicLong();
  }

  private static void process(
      PgFixture pg,
      List<Path> files,
      String day,
      PackDecider decider,
      int threads,
      Counters counters,
      NanoClock clock)
      throws InterruptedException {
    AtomicInteger next = new AtomicInteger();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    List<Thread> workers = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      workers.add(
          Thread.ofPlatform()
              .name("rebuild-" + t)
              .start(
                  () -> {
                    try (Connection c = pg.primary()) {
                      c.setAutoCommit(false);
                      int index;
                      while (failure.get() == null
                          && (index = next.getAndIncrement()) < files.size()) {
                        file(c, files.get(index), day, decider, counters, clock);
                      }
                    } catch (Throwable e) {
                      failure.compareAndSet(null, e);
                    }
                  }));
    }
    for (Thread worker : workers) {
      worker.join();
    }
    Throwable error = failure.get();
    if (error != null) {
      throw new IllegalStateException("the rebuild failed: " + error, error);
    }
  }

  @SuppressWarnings("unchecked")
  private static void file(
      Connection c, Path file, String day, PackDecider decider, Counters counters, NanoClock clock)
      throws IOException, SQLException {
    long t0 = clock.nanoTime();
    List<String> lines = new ArrayList<>(FILE_RECORDS);
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        lines.add(line);
      }
    }
    List<Map<String, Object>> records = new ArrayList<>(lines.size());
    for (String line : lines) {
      records.add((Map<String, Object>) Json.parse(line));
    }
    long t1 = clock.nanoTime();

    StringBuilder decisions = new StringBuilder(lines.size() * 220);
    StringBuilder heads = new StringBuilder(lines.size() * 40);
    for (int i = 0; i < lines.size(); i++) {
      Landed landed = landed(lines.get(i), records.get(i), decider);
      if (!landed.tradeDate().equals(day)) {
        throw new IllegalStateException(
            "trade date "
                + landed.tradeDate()
                + " has no partition: only "
                + day
                + " is installed");
      }
      decisions
          .append(landed.decisionId())
          .append('\t')
          .append(REGIME)
          .append('\t')
          .append(landed.eventIdentity())
          .append("\t1\t1\t");
      bytea(decisions, landed.inputDigestHex());
      decisions
          .append('\t')
          .append(landed.tradeDate())
          .append('\t')
          .append(landed.outcome())
          .append('\t');
      bytea(decisions, landed.bodyHex());
      decisions.append('\n');
      heads
          .append(REGIME)
          .append('\t')
          .append(landed.eventIdentity())
          .append("\t1\t")
          .append(landed.decisionId())
          .append('\n');
    }
    long t2 = clock.nanoTime();

    PGConnection pgc = c.unwrap(PGConnection.class);
    try {
      pgc.getCopyAPI()
          .copyIn(copySql("decision", DECISION_COLUMNS), new StringReader(decisions.toString()));
      pgc.getCopyAPI()
          .copyIn(copySql("decision_head", HEAD_COLUMNS), new StringReader(heads.toString()));
    } catch (IOException e) {
      throw new SQLException("COPY failed", e);
    }
    c.commit();
    long t3 = clock.nanoTime();

    counters.read.addAndGet(t1 - t0);
    counters.decide.addAndGet(t2 - t1);
    counters.copy.addAndGet(t3 - t2);
  }

  static String copySql(String table, List<String> columns) {
    return "COPY \"" + SCHEMA + "\"." + table + " (" + String.join(", ", columns) + ") FROM STDIN";
  }
}
