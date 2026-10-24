package dev.regpack.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Checks the traceability file (spec 14.4): every requirement and invariant row has named evidence,
 * every cited artefact exists and records a pass, and a row whose closing plans have all exited
 * needs evidence that counts. Consistency-only golden cases never count, and for I1, I2, I9 and I10
 * only a real-store run counts; for I1 and I10 a real-files run (P-SEALED on the real file system),
 * a real-standby run (the quorum standby job) and a real-worm run (P-WORM on a real object-lock
 * service) count as well, for no other row.
 */
public final class TraceabilityCheck {
  /** One evidence line of the table. */
  public record Evidence(String kind, String ref, String store, String ci, String note) {}

  /** One row id with the plans that close it and its evidence lines. */
  public record Row(String id, List<String> closesIn, List<Evidence> evidence) {
    public Row {
      closesIn = List.copyOf(closesIn);
      evidence = List.copyOf(evidence);
    }
  }

  /**
   * Which evidence lines are evaluated. {@code ALL} is the default and evaluates every line.
   *
   * <p>{@code SKIP} (the build jobs, which have no PostgreSQL and no standby) ignores lines with
   * store {@code REALSTORE} or {@code REALSTANDBY}: they are not looked up, do not count, and a due
   * row of I1, I2, I9 or I10 that has such a line and no counting line is reported as {@code
   * REALSTORE_DEFERRED:<row>} (information, not a problem). It evaluates the {@code REALFILES}
   * lines (P-SEALED on the real file system of the job), which count as non-interim evidence for I1
   * and I10 only.
   *
   * <p>{@code ONLY} (the job with a real PostgreSQL server) evaluates only lines with store {@code
   * REALSTORE}; a due row of I1, I2, I9 or I10 needs one that passed, and a due row of any other id
   * that has no such line is not evaluated and counts as open.
   *
   * <p>{@code STANDBY} (the job with a quorum standby) evaluates only lines with store {@code
   * REALSTANDBY}, which count as non-interim evidence for I1 and I10 only; a due row that has no
   * such line is not evaluated and counts as open.
   *
   * <p>{@code WORM} (the job with a real S3-compatible object-lock service) evaluates only lines
   * with store {@code REALWORM}, which count as non-interim evidence for I1 and I10 only; a due I1
   * or I10 row without a passed such line is a problem, and any other due row is not evaluated and
   * counts as open. {@code SKIP}, {@code ONLY} and {@code STANDBY} do not look a {@code REALWORM}
   * line up and it never counts there; in {@code SKIP} a due row of I1, I2, I9 or I10 that has one
   * and no counting line is reported as {@code REALSTORE_DEFERRED:<row>}. A {@code REALWORM} line
   * that cites a class whose name ends in {@code Test} is a problem in every mode ({@code
   * REALWORM_FAKE_BACKED:<row>:<ref>}): such a class runs against the fake service, and the fake is
   * never real-store evidence.
   */
  public enum Mode {
    ALL,
    SKIP,
    ONLY,
    STANDBY,
    WORM
  }

  /**
   * The outcome of a check; {@code closed} and {@code open} count rows. {@code deferred} lists the
   * {@code REALSTORE_DEFERRED:<row>} information lines of mode {@code SKIP}; a deferred row is also
   * counted in {@code open}.
   */
  public record Result(
      List<String> problems, int rows, int closed, int open, List<String> deferred) {
    public Result {
      problems = List.copyOf(problems);
      deferred = List.copyOf(deferred);
    }

    /** A result without deferred rows. */
    public Result(List<String> problems, int rows, int closed, int open) {
      this(problems, rows, closed, open, List.of());
    }

    /** True when no problem was found. */
    public boolean ok() {
      return problems.isEmpty();
    }
  }

  private enum Status {
    PASS,
    PENDING,
    OWNERLESS,
    NOT_PASSING,
    MISSING
  }

  private static final List<String> IDS = allIds();
  private static final Set<String> KINDS =
      Set.of("TEST", "GOLDEN", "BENCHMARK", "SPIKE", "GATE", "REVIEW", "NONE");
  private static final Set<String> STORES =
      Set.of("UNIT", "INMEMORY", "REALFILES", "REALSTORE", "REALSTANDBY", "REALWORM", "NA");
  private static final Set<String> CIS = Set.of("BUILD", "NIGHTLY", "RC", "NA");
  private static final Set<String> REAL_STORE_ONLY = Set.of("I1", "I2", "I9", "I10");

  /** The rows for which a {@code REALFILES} or {@code REALSTANDBY} line is non-interim evidence. */
  private static final Set<String> REAL_FILES_COUNT = Set.of("I1", "I10");

  private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", "node_modules");
  private static final Pattern PLAN = Pattern.compile("P[0-9]+[a-d]?");
  private static final Pattern EXITED_LINE = Pattern.compile("^exited_plans:\\s*(.*)$");
  private static final Pattern SEPARATOR_CELL = Pattern.compile(":?-+:?");
  private static final Pattern STATUS_LINE = Pattern.compile("^status:\\s*(\\S+)\\s*$");
  private static final Pattern OWNER_LINE = Pattern.compile("^owner:\\s*\\S.*$");
  private static final int COLUMNS = 7;

  private TraceabilityCheck() {}

  private static List<String> allIds() {
    List<String> ids = new ArrayList<>();
    for (int i = 1; i <= 14; i++) {
      ids.add("R" + i);
    }
    for (int i = 1; i <= 10; i++) {
      ids.add("I" + i);
    }
    return List.copyOf(ids);
  }

  /**
   * The test cases and golden audit rows found under the scan roots. A test case that fails, errors
   * or is skipped in any report is not in {@code passedTests}, whatever other reports say.
   */
  private record Reports(
      Set<String> seenTests, Set<String> passedTests, Map<String, Boolean> golden) {}

  /**
   * Checks a traceability file.
   *
   * @param markdown the text of the traceability file
   * @param repoRoot the repository root; file evidence paths are relative to it
   * @param scanRoots directories searched for {@code TEST-*.xml} and {@code golden-audit.tsv}
   */
  public static Result check(String markdown, Path repoRoot, List<Path> scanRoots) {
    return check(markdown, repoRoot, scanRoots, Mode.ALL);
  }

  /**
   * Checks a traceability file in a real-store mode.
   *
   * @param markdown the text of the traceability file
   * @param repoRoot the repository root; file evidence paths are relative to it
   * @param scanRoots directories searched for {@code TEST-*.xml} and {@code golden-audit.tsv}
   * @param mode which evidence lines are evaluated
   */
  public static Result check(String markdown, Path repoRoot, List<Path> scanRoots, Mode mode) {
    List<String> problems = new ArrayList<>();
    List<String> deferred = new ArrayList<>();
    Set<String> exited = new HashSet<>();
    Map<String, Row> rows = parse(markdown, problems, exited);
    for (String id : IDS) {
      if (!rows.containsKey(id)) {
        problems.add("MISSING_ROW:" + id);
      }
    }
    Reports reports = scan(scanRoots);
    int closed = 0;
    int open = 0;
    for (String id : IDS) {
      Row row = rows.get(id);
      if (row == null) {
        continue;
      }
      boolean due = exited.containsAll(row.closesIn());
      boolean counted = checkEvidence(row, due, repoRoot, reports, problems, mode);
      boolean needsRealStore = REAL_STORE_ONLY.contains(id);
      boolean hasRealStoreLine = row.evidence().stream().anyMatch(e -> isRealStore(e));
      boolean hasDeferrableLine =
          row.evidence().stream()
              .anyMatch(e -> isRealStore(e) || isRealStandby(e) || isRealWorm(e));
      boolean hasStandbyLine = row.evidence().stream().anyMatch(e -> isRealStandby(e));
      if (!due) {
        open++;
      } else if (counted) {
        closed++;
      } else if (mode == Mode.SKIP && needsRealStore && hasDeferrableLine) {
        deferred.add("REALSTORE_DEFERRED:" + id);
        open++;
      } else if (mode == Mode.ONLY && !needsRealStore && !hasRealStoreLine) {
        open++;
      } else if (mode == Mode.STANDBY && !hasStandbyLine) {
        open++;
      } else if (mode == Mode.WORM && !REAL_FILES_COUNT.contains(id)) {
        open++;
      } else {
        problems.add("ROW_WITHOUT_EVIDENCE:" + id);
      }
    }
    return new Result(problems, rows.size(), closed, open, deferred);
  }

  private static boolean isRealStore(Evidence e) {
    return e.store().equals("REALSTORE");
  }

  private static boolean isRealStandby(Evidence e) {
    return e.store().equals("REALSTANDBY");
  }

  private static boolean isRealWorm(Evidence e) {
    return e.store().equals("REALWORM");
  }

  /** Whether the mode looks the line up at all; a line it does not evaluate never counts. */
  private static boolean evaluated(Mode mode, Evidence e) {
    return switch (mode) {
      case ALL -> true;
      case SKIP -> !isRealStore(e) && !isRealStandby(e) && !isRealWorm(e);
      case ONLY -> isRealStore(e);
      case STANDBY -> isRealStandby(e);
      case WORM -> isRealWorm(e);
    };
  }

  /**
   * Whether a passing line is non-interim evidence for the row: a {@code REALSTORE} line always is,
   * a {@code REALFILES}, {@code REALSTANDBY} or {@code REALWORM} line only for I1 and I10, and any
   * other line unless the row is one of I1, I2, I9 and I10.
   */
  private static boolean countsFor(String rowId, Evidence e) {
    return switch (e.store()) {
      case "REALSTORE" -> true;
      case "REALFILES", "REALSTANDBY", "REALWORM" -> REAL_FILES_COUNT.contains(rowId);
      default -> !REAL_STORE_ONLY.contains(rowId);
    };
  }

  private static Map<String, Row> parse(
      String markdown, List<String> problems, Set<String> exited) {
    Map<String, List<String>> closes = new LinkedHashMap<>();
    Map<String, List<Evidence>> evidence = new LinkedHashMap<>();
    boolean sawExited = false;
    int lineNo = 0;
    for (String raw : markdown.split("\\R", -1)) {
      lineNo++;
      String line = raw.strip();
      Matcher exitedLine = EXITED_LINE.matcher(line);
      if (exitedLine.matches()) {
        sawExited = true;
        readExited(exitedLine.group(1).strip(), problems, exited);
        continue;
      }
      if (!line.startsWith("|")) {
        continue;
      }
      List<String> cells = cells(line);
      if (cells.get(0).equalsIgnoreCase("row") || isSeparator(cells)) {
        continue;
      }
      String id = cells.get(0);
      if (cells.size() != COLUMNS
          || !KINDS.contains(cells.get(2))
          || !STORES.contains(cells.get(4))
          || !CIS.contains(cells.get(5))) {
        problems.add("BAD_LINE:" + lineNo);
        continue;
      }
      if (!IDS.contains(id)) {
        problems.add("UNKNOWN_ROW:" + id);
        continue;
      }
      if (cells.get(4).equals("REALWORM") && citesFakeBackedClass(cells.get(2), cells.get(3))) {
        problems.add("REALWORM_FAKE_BACKED:" + id + ":" + cells.get(3));
      }
      List<String> plans = plans(id, cells.get(1), problems);
      List<String> known = closes.putIfAbsent(id, plans);
      if (known != null && !known.equals(plans)) {
        problems.add("CLOSES_IN_MISMATCH:" + id);
      }
      evidence
          .computeIfAbsent(id, k -> new ArrayList<>())
          .add(new Evidence(cells.get(2), cells.get(3), cells.get(4), cells.get(5), cells.get(6)));
    }
    if (!sawExited) {
      problems.add("NO_EXITED_PLANS");
    }
    Map<String, Row> rows = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> e : closes.entrySet()) {
      rows.put(e.getKey(), new Row(e.getKey(), e.getValue(), evidence.get(e.getKey())));
    }
    return rows;
  }

  /**
   * Whether a {@code TEST} reference names a class whose simple name ends in {@code Test}: such a
   * class runs against the fake service, which is never real-store evidence.
   */
  private static boolean citesFakeBackedClass(String kind, String ref) {
    if (!kind.equals("TEST")) {
      return false;
    }
    int hash = ref.indexOf('#');
    String type = hash < 0 ? ref : ref.substring(0, hash);
    return type.substring(type.lastIndexOf('.') + 1).endsWith("Test");
  }

  private static void readExited(String value, List<String> problems, Set<String> exited) {
    if (value.equals("none")) {
      return;
    }
    for (String plan : value.split(",", -1)) {
      String p = plan.strip();
      if (PLAN.matcher(p).matches()) {
        exited.add(p);
      } else {
        problems.add("BAD_PLAN:exited_plans:" + p);
      }
    }
  }

  private static List<String> plans(String id, String cell, List<String> problems) {
    List<String> plans = new ArrayList<>();
    for (String plan : cell.split(",", -1)) {
      String p = plan.strip();
      if (!PLAN.matcher(p).matches()) {
        problems.add("BAD_PLAN:" + id + ":" + p);
      }
      plans.add(p);
    }
    return plans;
  }

  private static List<String> cells(String line) {
    String inner = line.substring(1);
    if (inner.endsWith("|")) {
      inner = inner.substring(0, inner.length() - 1);
    }
    List<String> cells = new ArrayList<>();
    for (String cell : inner.split("\\|", -1)) {
      cells.add(cell.strip());
    }
    return cells;
  }

  private static boolean isSeparator(List<String> cells) {
    return cells.stream().allMatch(c -> SEPARATOR_CELL.matcher(c).matches());
  }

  /**
   * Reports problems of the cited artefacts and returns whether some evidence counts. Lines the
   * mode does not evaluate are neither looked up nor counted.
   */
  private static boolean checkEvidence(
      Row row, boolean due, Path repoRoot, Reports reports, List<String> problems, Mode mode) {
    boolean counted = false;
    for (Evidence e : row.evidence()) {
      if (e.kind().equals("NONE") || !evaluated(mode, e)) {
        continue;
      }
      if (isRealWorm(e) && citesFakeBackedClass(e.kind(), e.ref())) {
        // reported when the table is read; the fake never counts, whatever its report says
        continue;
      }
      String where = row.id() + ":" + e.ref();
      Status status =
          switch (e.kind()) {
            case "TEST" -> testStatus(e.ref(), reports);
            case "GOLDEN" -> goldenStatus(e.ref(), reports);
            default -> fileStatus(e.kind(), e.ref(), repoRoot, where, problems);
          };
      switch (status) {
        case MISSING -> problems.add("ARTEFACT_MISSING:" + where);
        case NOT_PASSING -> problems.add("NOT_PASSING:" + where);
        case PENDING -> {
          if (due) {
            problems.add("NOT_PASSING:" + where);
          }
        }
        case OWNERLESS, PASS -> {}
      }
      if (status == Status.PASS && countsFor(row.id(), e)) {
        counted = true;
      }
    }
    return counted;
  }

  private static Status testStatus(String ref, Reports reports) {
    int hash = ref.indexOf('#');
    String key =
        hash < 0 ? ref : ref.substring(0, hash) + "#" + stripParentheses(ref.substring(hash + 1));
    if (reports.passedTests().contains(key)) {
      return Status.PASS;
    }
    return reports.seenTests().contains(key) ? Status.NOT_PASSING : Status.MISSING;
  }

  private static String stripParentheses(String name) {
    return name.endsWith("()") ? name.substring(0, name.length() - 2) : name;
  }

  private static Status goldenStatus(String ref, Reports reports) {
    Boolean counts = reports.golden().get(ref);
    if (counts == null) {
      return Status.MISSING;
    }
    return counts ? Status.PASS : Status.NOT_PASSING;
  }

  /** A file artefact passes with a {@code status: PASS} line; a REVIEW file also needs an owner. */
  private static Status fileStatus(
      String kind, String ref, Path repoRoot, String where, List<String> problems) {
    Path base = repoRoot.toAbsolutePath().normalize();
    Path file = base.resolve(ref).normalize();
    if (!file.startsWith(base) || !Files.isRegularFile(file)) {
      return Status.MISSING;
    }
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return Status.MISSING;
    }
    Optional<String> status = Optional.empty();
    boolean owner = false;
    for (String raw : lines) {
      String line = raw.strip();
      Matcher m = STATUS_LINE.matcher(line);
      if (status.isEmpty() && m.matches()) {
        status = Optional.of(m.group(1));
      }
      owner |= OWNER_LINE.matcher(line).matches();
    }
    boolean ownerMissing = kind.equals("REVIEW") && !owner;
    if (ownerMissing) {
      problems.add("NO_OWNER:" + where);
    }
    Status result;
    if (status.isEmpty()) {
      result = Status.NOT_PASSING;
    } else if (status.get().equals("PASS")) {
      result = Status.PASS;
    } else if (status.get().equals("PENDING")) {
      result = Status.PENDING;
    } else {
      result = Status.NOT_PASSING;
    }
    return ownerMissing && result == Status.PASS ? Status.OWNERLESS : result;
  }

  private static Reports scan(List<Path> scanRoots) {
    Set<String> seen = new HashSet<>();
    Set<String> passed = new HashSet<>();
    Set<String> failed = new HashSet<>();
    Map<String, Boolean> golden = new LinkedHashMap<>();
    for (Path root : scanRoots) {
      try {
        Files.walkFileTree(
            root,
            new SimpleFileVisitor<>() {
              @Override
              public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path name = dir.getFileName();
                boolean skipped =
                    !dir.equals(root)
                        && name != null
                        && SKIPPED_DIRECTORIES.contains(name.toString());
                return skipped ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (name.startsWith("TEST-") && name.endsWith(".xml")) {
                  readTestReport(file, seen, passed, failed);
                } else if (name.equals("golden-audit.tsv")) {
                  readGoldenAudit(file, golden);
                }
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
              }
            });
      } catch (IOException e) {
        // an unreadable scan root yields no evidence, so cited artefacts are reported missing
      }
    }
    return new Reports(seen, passed, golden);
  }

  /** An unreadable or malformed report yields no test cases, so its tests count as missing. */
  private static void readTestReport(
      Path file, Set<String> seen, Set<String> passed, Set<String> failed) {
    Optional<Document> parsed = XmlReports.parse(file);
    if (parsed.isEmpty()) {
      return;
    }
    Document doc = parsed.get();
    NodeList cases = doc.getElementsByTagName("testcase");
    for (int i = 0; i < cases.getLength(); i++) {
      Element testcase = (Element) cases.item(i);
      String key =
          testcase.getAttribute("classname")
              + "#"
              + stripParentheses(testcase.getAttribute("name"));
      seen.add(key);
      if (hasFailureChild(testcase)) {
        failed.add(key);
        passed.remove(key);
      } else if (!failed.contains(key)) {
        passed.add(key);
      }
    }
  }

  private static boolean hasFailureChild(Element testcase) {
    for (Node child = testcase.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element element) {
        String tag = element.getTagName();
        if (tag.equals("failure") || tag.equals("error") || tag.equals("skipped")) {
          return true;
        }
      }
    }
    return false;
  }

  private static void readGoldenAudit(Path file, Map<String, Boolean> golden) {
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return;
    }
    for (String line : lines) {
      String[] cols = line.split("\t", -1);
      if (cols.length >= 4 && !cols[0].isBlank()) {
        golden.merge(cols[0], cols[3].equals("true"), Boolean::logicalOr);
      }
    }
  }
}
