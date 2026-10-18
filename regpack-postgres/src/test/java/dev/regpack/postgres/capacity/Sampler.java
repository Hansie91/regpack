package dev.regpack.postgres.capacity;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * The per-second sampler of the capacity probe (T21). It writes one CSV row a second and keeps the
 * file, because SP-L collected the same rows and kept none of them, so the cause of its L-4 result
 * could not be read afterwards (H-4).
 *
 * <p>A row holds the cumulative counters of {@code pg_stat_wal}, {@code pg_stat_checkpointer} and
 * {@code pg_stat_io} (every column that the server returns: the statements are {@code SELECT *} and
 * the header is taken from the result, so a renamed or added column cannot break the sampler), the
 * CPU lines of {@code /proc/stat} (the total and each CPU), the lines of {@code /proc/meminfo}, and
 * the columns that the caller supplies (the progress of the run). The counters are cumulative: the
 * analysis takes differences between rows. The {@code /proc} files are those of the runner, and
 * where they do not exist (a development machine) they give no columns.
 *
 * <p>The header is fixed by the first sample that holds the view columns (a sample whose queries
 * failed is skipped for up to ten tries, so that the evidence the sampler exists for is not lost);
 * a later sample is projected onto it (a column that is missing is empty, one that is new is
 * dropped). Each row is flushed when it is written, so a run that is cut off by a timeout still
 * leaves its rows.
 */
final class Sampler {
  /** The views that are sampled, with the prefix of their columns, in column order. */
  private static final Map<String, String> VIEWS = new LinkedHashMap<>();

  static {
    VIEWS.put("wal", "SELECT * FROM pg_stat_wal");
    VIEWS.put("checkpointer", "SELECT * FROM pg_stat_checkpointer");
    VIEWS.put("io", "SELECT * FROM pg_stat_io");
  }

  /** The single-value lines of {@code /proc/stat} that are kept besides the CPU line. */
  private static final Set<String> STAT_LINES =
      Set.of("ctxt", "processes", "procs_running", "procs_blocked", "intr", "softirq");

  private static final String[] CPU_FIELDS = {
    "user", "nice", "system", "idle", "iowait", "irq", "softirq", "steal", "guest", "guest_nice"
  };

  private static final long ONE_SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);

  /**
   * Samples without the views that are skipped before the header is fixed anyway: the header must
   * hold the view columns (H-4), so a first sample whose queries failed is not allowed to fix it,
   * but a server that never answers must not leave an empty file either.
   */
  private static final int HEADER_ATTEMPTS = 10;

  /** The column set of the file: fixed by the first sample. */
  static final class Columns {
    private final List<String> names;

    private Columns(List<String> names) {
      this.names = List.copyOf(names);
    }

    static Columns fixedBy(Map<String, String> first) {
      return new Columns(new ArrayList<>(first.keySet()));
    }

    String header() {
      return csvLine(names);
    }

    String row(Map<String, String> sample) {
      List<String> fields = new ArrayList<>(names.size());
      for (String name : names) {
        fields.add(sample.getOrDefault(name, ""));
      }
      return csvLine(fields);
    }
  }

  private final DataSource dataSource;
  private final Path file;
  private final Supplier<Map<String, String>> extras;
  private final AtomicLong rows = new AtomicLong();
  private final AtomicLong failures = new AtomicLong();
  private final long periodNanos;
  private volatile boolean running;
  private Thread thread;

  /** A sampler with one row a second. */
  Sampler(DataSource dataSource, Path file, Supplier<Map<String, String>> extras) {
    this(dataSource, file, extras, ONE_SECOND_NANOS);
  }

  /** A sampler with a row every {@code periodNanos} (the tests use a short period). */
  Sampler(
      DataSource dataSource, Path file, Supplier<Map<String, String>> extras, long periodNanos) {
    this.dataSource = dataSource;
    this.file = file;
    this.extras = extras;
    this.periodNanos = periodNanos;
  }

  /** Starts the sampling thread; the first row is written about one period later. */
  void start() {
    running = true;
    thread = new Thread(this::loop, "capacity-sampler");
    thread.setDaemon(true);
    thread.start();
  }

  /**
   * Stops the thread after its current sample and waits for it to finish. The thread is interrupted
   * only if it has not finished a period and a few seconds after the request: an interrupt that
   * arrives while a row is written closes the file channel, which loses that row.
   */
  void stop() throws InterruptedException {
    running = false;
    Thread t = thread;
    if (t != null) {
      t.join(TimeUnit.NANOSECONDS.toMillis(periodNanos) + TimeUnit.SECONDS.toMillis(10));
      if (t.isAlive()) {
        t.interrupt();
        t.join(TimeUnit.SECONDS.toMillis(30));
      }
    }
  }

  /** Rows written so far. */
  long rows() {
    return rows.get();
  }

  /** Samples that could not be taken (a database error); the row is written without the views. */
  long failures() {
    return failures.get();
  }

  private void loop() {
    long start = System.nanoTime();
    Columns columns = null;
    try (Connection connection = dataSource.getConnection();
        BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      connection.setAutoCommit(true);
      long tick = 0;
      int withoutViews = 0;
      while (running) {
        tick++;
        long due = start + tick * periodNanos;
        long wait = due - System.nanoTime();
        if (wait > 0) {
          try {
            TimeUnit.NANOSECONDS.sleep(wait);
          } catch (InterruptedException e) {
            // stop() interrupts a thread that did not finish in time: the loop ends
            return;
          }
        }
        if (!running) {
          return;
        }
        Map<String, String> sample = new LinkedHashMap<>();
        sample.put("t_ms", Long.toString((System.nanoTime() - start) / 1_000_000L));
        sample.put("epoch_ms", Long.toString(System.currentTimeMillis()));
        sample.putAll(extras.get());
        boolean viewsRead = false;
        try {
          sample.putAll(views(connection));
          viewsRead = true;
        } catch (SQLException e) {
          failures.incrementAndGet();
        }
        sample.putAll(parseProcStat(readLines(Path.of("/proc/stat"))));
        sample.putAll(parseMeminfo(readLines(Path.of("/proc/meminfo"))));
        if (columns == null) {
          if (!viewsRead && ++withoutViews < HEADER_ATTEMPTS) {
            continue; // no header from a sample without the views: wait for one that has them
          }
          columns = Columns.fixedBy(sample);
          out.write(columns.header());
          out.newLine();
        }
        out.write(columns.row(sample));
        out.newLine();
        out.flush();
        rows.incrementAndGet();
      }
    } catch (SQLException | IOException e) {
      // the connection or the file is gone: the rows written so far stay in the file
      failures.incrementAndGet();
    }
  }

  private static Map<String, String> views(Connection connection) throws SQLException {
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, String> view : VIEWS.entrySet()) {
      try (Statement statement = connection.createStatement();
          ResultSet rows = statement.executeQuery(view.getValue())) {
        out.putAll(flatten(view.getKey(), rows));
      }
    }
    return out;
  }

  private static List<String> readLines(Path path) {
    try {
      return Files.readAllLines(path, StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException e) {
      return List.of();
    }
  }

  /** The machine memory in bytes from {@code /proc/meminfo}, when it can be read. */
  static OptionalLong machineMemoryBytes() {
    String kilobytes = parseMeminfo(readLines(Path.of("/proc/meminfo"))).get("mem.MemTotal_kB");
    if (kilobytes == null) {
      return OptionalLong.empty();
    }
    try {
      return OptionalLong.of(Math.multiplyExact(Long.parseLong(kilobytes), 1024L));
    } catch (NumberFormatException | ArithmeticException e) {
      return OptionalLong.empty();
    }
  }

  /**
   * Turns a result into columns named {@code prefix.label}. A result of one row gives a column for
   * every number or text value (other types, such as a reset timestamp, are skipped). A result of
   * several rows (the shape of {@code pg_stat_io}) is grouped by its first two text columns, which
   * name the group; the other text columns are dropped and the numbers of the rows of a group are
   * added up, a null counting as zero. Every column is named from the labels of the result, never
   * from a list in this class.
   */
  static Map<String, String> flatten(String prefix, ResultSet rows) throws SQLException {
    ResultSetMetaData metaData = rows.getMetaData();
    int width = metaData.getColumnCount();
    List<String> labels = new ArrayList<>(width);
    for (int i = 1; i <= width; i++) {
      labels.add(metaData.getColumnLabel(i));
    }
    List<Object[]> all = new ArrayList<>();
    while (rows.next()) {
      Object[] row = new Object[width];
      for (int i = 0; i < width; i++) {
        row[i] = rows.getObject(i + 1);
      }
      all.add(row);
    }
    Map<String, String> out = new LinkedHashMap<>();
    if (all.size() == 1) {
      Object[] row = all.get(0);
      for (int i = 0; i < width; i++) {
        if (row[i] instanceof Number || row[i] instanceof String) {
          out.put(prefix + "." + labels.get(i), String.valueOf(row[i]));
        }
      }
    } else if (all.size() > 1) {
      groupedSums(prefix, labels, all, out);
    }
    return out;
  }

  private static void groupedSums(
      String prefix, List<String> labels, List<Object[]> all, Map<String, String> out) {
    int width = labels.size();
    boolean[] text = new boolean[width];
    boolean[] number = new boolean[width];
    for (Object[] row : all) {
      for (int i = 0; i < width; i++) {
        text[i] |= row[i] instanceof String;
        number[i] |= row[i] instanceof Number;
      }
    }
    List<Integer> keys = new ArrayList<>(2);
    for (int i = 0; i < width && keys.size() < 2; i++) {
      if (text[i]) {
        keys.add(i);
      }
    }
    // group -> column -> sum, in order of first appearance
    Map<String, Map<String, BigDecimal>> sums = new LinkedHashMap<>();
    Set<String> groupOrder = new LinkedHashSet<>();
    for (Object[] row : all) {
      StringBuilder group = new StringBuilder();
      for (int key : keys) {
        group.append('.').append(String.valueOf(row[key]).replace(' ', '_'));
      }
      String name = prefix + group;
      groupOrder.add(name);
      Map<String, BigDecimal> columns = sums.computeIfAbsent(name, n -> new LinkedHashMap<>());
      for (int i = 0; i < width; i++) {
        if (!number[i]) {
          continue;
        }
        BigDecimal add = row[i] instanceof Number n ? decimal(n) : BigDecimal.ZERO;
        columns.merge(labels.get(i), add, BigDecimal::add);
      }
    }
    for (String group : groupOrder) {
      for (Map.Entry<String, BigDecimal> column : sums.get(group).entrySet()) {
        out.put(group + "." + column.getKey(), column.getValue().toPlainString());
      }
    }
  }

  private static BigDecimal decimal(Number value) {
    if (value instanceof Double d && (d.isNaN() || d.isInfinite())) {
      return BigDecimal.ZERO;
    }
    if (value instanceof Float f && (f.isNaN() || f.isInfinite())) {
      return BigDecimal.ZERO;
    }
    return new BigDecimal(value.toString());
  }

  /**
   * The cumulative CPU lines of {@code /proc/stat} (the total as {@code cpu.*}, each CPU as {@code
   * cpu0.*}, {@code cpu1.*}, and so on) and its single-value lines, as columns.
   */
  static Map<String, String> parseProcStat(List<String> lines) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String line : lines) {
      String[] fields = line.strip().split("\\s+");
      if (fields.length < 2) {
        continue;
      }
      if (fields[0].matches("cpu[0-9]*")) {
        for (int i = 0; i < CPU_FIELDS.length && i + 1 < fields.length; i++) {
          out.put(fields[0] + "." + CPU_FIELDS[i], fields[i + 1]);
        }
      } else if (STAT_LINES.contains(fields[0])) {
        out.put("stat." + fields[0], fields[1]);
      }
    }
    return out;
  }

  /** Every {@code Key: value [unit]} line of {@code /proc/meminfo} as a column {@code mem.Key}. */
  static Map<String, String> parseMeminfo(List<String> lines) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String line : lines) {
      int colon = line.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      String key = line.substring(0, colon).strip();
      String[] value = line.substring(colon + 1).strip().split("\\s+");
      if (value.length == 0
          || value[0].isEmpty()
          || !value[0].chars().allMatch(Character::isDigit)) {
        continue;
      }
      boolean unit = value.length > 1 && value[1].equals("kB");
      out.put("mem." + key + (unit ? "_kB" : ""), value[0]);
    }
    return out;
  }

  /** One CSV line (RFC 4180): a field with a comma, a quote or a line break is quoted. */
  static String csvLine(List<String> fields) {
    StringBuilder line = new StringBuilder();
    for (int i = 0; i < fields.size(); i++) {
      if (i > 0) {
        line.append(',');
      }
      String field = fields.get(i);
      if (field.indexOf(',') >= 0
          || field.indexOf('"') >= 0
          || field.indexOf('\n') >= 0
          || field.indexOf('\r') >= 0) {
        line.append('"').append(field.replace("\"", "\"\"")).append('"');
      } else {
        line.append(field);
      }
    }
    return line.toString();
  }
}
