package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.ports.crypto.Envelope;
import dev.regpack.ports.crypto.SubjectHash;
import dev.regpack.ports.ledger.ReportLedger.SubjectId;
import dev.regpack.postgres.DifferentialOps.Commit;
import dev.regpack.postgres.DifferentialOps.Op;
import dev.regpack.postgres.DifferentialOps.ReadEvent;
import dev.regpack.postgres.DifferentialOps.Side;
import dev.regpack.postgres.DifferentialOps.UnitDecision;
import dev.regpack.postgres.DifferentialOps.WalkExpired;
import dev.regpack.postgres.DifferentialOps.WalkRefs;
import dev.regpack.postgres.DifferentialOps.WalkRows;
import dev.regpack.postgres.PgLedgerStore.Config;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The in-memory ledger and the PostgreSQL ledger given the same random operations (a differential
 * test, with the in-memory ledger as the oracle): retention rows (claims, holds, the pages of both
 * reads), the data-subject index (entries, pages, removal) and report bodies (envelopes and plain
 * bodies on a strict and a default ledger). Both ledgers run over the one {@code ManualClock}.
 * After every operation the two answers are compared as text: the values, in order, or the type and
 * the message of the exception; at the end of a sequence everything the ports can show is read from
 * both and compared the same way. The port Javadoc is the only rule: where the two ledgers differ,
 * one of them is wrong or the port leaves the case open.
 *
 * <p>The sequences are generated from a seed ({@link DifferentialGenerator}), so a run is exactly
 * repeatable. At the first difference of a seed the sequence is shrunk ({@link
 * DifferentialShrinker}) to a few operations that still differ, and the failure names the seed, the
 * number of the sequence, the shrunk operations and the two answers, and how to run it again. Its
 * first line holds the seed, the options that run it again and both answers (cut), because that is
 * the line a check-run annotation shows; a difference that does not recur when the sequence is run
 * again is reported as it was found, with the line {@code NOT SHRUNK}.
 *
 * <p>System properties, with their defaults (the defaults are the run of the job {@code ledger-pg}:
 * one fixed seed, about two minutes on the development machine's local server; every sequence
 * starts from emptied tables, so the sequences of one seed are as good as those of another):
 *
 * <ul>
 *   <li>{@code regpack.differential.seeds}: the seeds separated by commas, default {@code 1}; the
 *       long run of the mutation run (D32) is {@code 1,2,3,4,5} with {@code sequences=300}, about
 *       four minutes on an idle machine
 *   <li>{@code regpack.differential.sequences}: the sequences for each seed, default {@code 600}
 *   <li>{@code regpack.differential.operations}: the operations in a sequence, default {@code 60}
 *   <li>{@code regpack.differential.exactUnits}: {@code true} compares the refusals of a unit of
 *       several decisions in full, see {@link DifferentialOps#EXACT_UNITS}; the default compares
 *       them by their kind only, since the port leaves open which defect of a unit a ledger reports
 *       (D33 (2)); a head conflict names its event identity and head: a unit of one decision always
 *       compares both, a unit of several only when both ledgers name the same event identity (D33
 *       (1), D35 (2))
 *   <li>{@code regpack.differential.all}: {@code true} goes on after a difference and reports each
 *       kind of difference once; the default stops a seed at its first
 *   <li>{@code regpack.differential.verbose}: {@code true} prints how often each kind of answer was
 *       seen
 * </ul>
 *
 * <p>A run of at least {@value #COVERAGE_FROM} sequences also asks that every kind of operation was
 * seen to succeed and to be refused, so that the comparison cannot pass on empty answers.
 *
 * <p>Real-store test: skipped without {@code REGPACK_PG_URL}, a failure with {@code
 * REGPACK_PG_REQUIRED=true}. One schema serves a whole seed: the two tables of the sequences are
 * emptied before each sequence, and a sequence names its report events with a prefix of its own.
 */
class PgInMemoryDifferentialIT extends AbstractPgStoreTest {
  private static final String SEEDS = "regpack.differential.seeds";
  private static final String SEQUENCES = "regpack.differential.sequences";
  private static final String OPERATIONS = "regpack.differential.operations";
  private static final String ALL = "regpack.differential.all";
  private static final String VERBOSE = "regpack.differential.verbose";

  /** The default seed: fixed, so that a failure of the job can be run again from its message. */
  private static final String DEFAULT_SEEDS = "1";

  /** 600 sequences of 60 operations take about 110 s on an idle development machine. */
  private static final int DEFAULT_SEQUENCES = 600;

  private static final int DEFAULT_OPERATIONS = 60;

  /** A run of this many sequences must have seen every kind of answer. */
  private static final int COVERAGE_FROM = 200;

  /** The most differences of one seed that are shrunk when the run goes on after the first. */
  private static final int MAX_SHRUNK = 6;

  private static final Instant START = Instant.parse("2026-10-09T08:00:00Z");

  /** What has to happen at least once in a long run, by the notes of the sides. */
  private static final List<String> COVERED =
      List.of(
          "record.ok",
          "refused.RecordClaims",
          "hold.ok",
          "refused.Hold",
          "rows.nonEmpty",
          "rows.empty",
          "rows.walked",
          "refused.Rows",
          "expired.nonEmpty",
          "expired.empty",
          "expired.walked",
          "refused.Expired",
          "index.ok",
          "refused.Index",
          "refs.nonEmpty",
          "refs.walked",
          "refused.Refs",
          "remove.positive",
          "remove.zero",
          "commit.CREATED",
          "commit.EXISTING",
          "commit.SUPPRESSED_UNCHANGED",
          "refused.Commit",
          "unit.headConflict",
          "unit.refusedWhileProcessing",
          "refused.Remove",
          "read.encoding0",
          "read.encoding1");

  /** The two answers to the first operation of a sequence on which they differ. */
  private record Divergence(int index, Op op, String inMemory, String postgres) {}

  private record Replay(Optional<Divergence> divergence, Map<String, Integer> notes) {}

  private int replays;
  private ReusingDataSource pool;
  private PgLedgerStore standard;
  private PgLedgerStore strict;

  /**
   * Both stores of the differential run over one pool, since a session costs a quarter of a second
   * to open and a run makes some hundred thousand calls.
   */
  @BeforeEach
  void openPool() {
    pool = new ReusingDataSource(dataSource());
    standard = new PgLedgerStore(pool, clock(), Config.forSchema(schema()));
    strict = new PgLedgerStore(pool, clock(), Config.production(schema()));
  }

  @AfterEach
  void closePool() {
    // without a server the test is skipped before the pool is opened
    if (pool != null) {
      pool.close();
    }
  }

  /** The seeds of the run, from {@code regpack.differential.seeds}. */
  static LongStream seeds() {
    return Arrays.stream(System.getProperty(SEEDS, DEFAULT_SEEDS).split(","))
        .map(String::trim)
        .mapToLong(Long::parseLong);
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void everySeedGivesBothLedgersTheSameAnswers(long seed) {
    checkSeed(seed);
  }

  private void checkSeed(long seed) {
    int sequences = Integer.getInteger(SEQUENCES, DEFAULT_SEQUENCES);
    int operations = Integer.getInteger(OPERATIONS, DEFAULT_OPERATIONS);
    boolean all = Boolean.getBoolean(ALL);
    DifferentialGenerator generator = new DifferentialGenerator(seed);
    Map<String, Integer> seen = new TreeMap<>();
    Set<String> kinds = new HashSet<>();
    List<String> reports = new ArrayList<>();
    for (int sequence = 0; sequence < sequences; sequence++) {
      List<Op> ops = generator.sequence(operations);
      Replay replay = replay(ops);
      replay.notes().forEach((note, count) -> seen.merge(note, count, Integer::sum));
      if (replay.divergence().isEmpty()) {
        continue;
      }
      Divergence first = replay.divergence().get();
      if (kinds.add(kind(first)) && reports.size() < MAX_SHRUNK) {
        reports.add(report(seed, sequence, ops, first));
      }
      if (!all) {
        break;
      }
    }
    if (Boolean.getBoolean(VERBOSE)) {
      System.out.println("differential seed " + seed + ": " + seen);
    }
    if (!reports.isEmpty()) {
      fail(String.join("\n\n", reports));
    }
    if (sequences >= COVERAGE_FROM) {
      for (String note : COVERED) {
        if (note.startsWith("unit.") && Boolean.getBoolean(DifferentialOps.EXACT_UNITS)) {
          // the units are compared in full and the refusal is not reduced to its kind
          continue;
        }
        assertThat(seen.getOrDefault(note, 0))
            .as("seed %d: how often '%s' was seen in %d sequences", seed, note, sequences)
            .isPositive();
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // one sequence on both ledgers

  /** The sequence on a fresh in-memory ledger and on the emptied tables, then the final reads. */
  private Replay replay(List<Op> ops) {
    String qualified = SqlIdentifier.require(schema()) + ".";
    try (Connection connection = pool.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("TRUNCATE " + qualified + "retention, " + qualified + "subject_index");
    } catch (SQLException e) {
      throw new IllegalStateException("the tables of the sequence could not be emptied", e);
    }
    clock().set(START);
    String namespace = "N" + (++replays) + "x";
    InMemoryLedgerStore memory = new InMemoryLedgerStore(clock());
    Side inMemory =
        new Side(
            requireEnvelopes -> memory.requireEnvelopeBodies(requireEnvelopes),
            clock(),
            (subject, versionSeq, body) -> Envelope.isEnvelope(body) ? 1 : 0,
            namespace);
    Side postgres =
        new Side(
            requireEnvelopes -> requireEnvelopes ? strict : standard,
            clock(),
            this::storedEncoding,
            namespace);
    List<Op> all = new ArrayList<>(ops);
    all.addAll(finalReads(ops));
    for (int i = 0; i < all.size(); i++) {
      Op op = all.get(i);
      String expected = DifferentialOps.outcome(op, inMemory);
      String actual = DifferentialOps.outcome(op, postgres);
      if (!DifferentialOps.agree(op, expected, actual)) {
        return new Replay(Optional.of(new Divergence(i, op, expected, actual)), inMemory.notes());
      }
    }
    return new Replay(Optional.empty(), inMemory.notes());
  }

  /** Everything the ports show, to be compared after the last operation. */
  private static List<Op> finalReads(List<Op> ops) {
    List<Op> reads = new ArrayList<>();
    Instant end = Instant.parse("9999-12-31T23:59:59.999999Z");
    for (String prefix : List.of("", "sealed/", "control/")) {
      reads.add(new WalkRows(prefix, 1_000));
      reads.add(new WalkRows(prefix, 2));
    }
    for (Instant asOf : List.of(end, Instant.parse("2027-01-01T00:00:00Z"))) {
      reads.add(new WalkExpired(asOf, 1_000));
      reads.add(new WalkExpired(asOf, 3));
    }
    for (SubjectHash hash : DifferentialGenerator.HASHES) {
      reads.add(new WalkRefs(hash, 1_000));
      reads.add(new WalkRefs(hash, 3));
    }
    Set<ReadEvent> events = new LinkedHashSet<>();
    for (Op op : ops) {
      if (op instanceof Commit commit) {
        for (UnitDecision decision : commit.decisions()) {
          events.add(new ReadEvent(decision.regime(), decision.event()));
        }
      }
    }
    reads.addAll(events);
    return reads;
  }

  /** The encoding that PostgreSQL stored with a body, read with SQL since the port hides it. */
  private int storedEncoding(SubjectId subject, long versionSeq, byte[] body) {
    try (Connection connection = pool.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT body_encoding FROM "
                    + SqlIdentifier.require(schema())
                    + ".report_version WHERE subject_id = ? AND version_seq = ?")) {
      statement.setLong(1, subject.value());
      statement.setLong(2, versionSeq);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getInt(1) : -1;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("the body encoding of a version could not be read", e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // a difference

  /** The kind of a difference: the step and how each ledger ended it, without the numbers. */
  private static String kind(Divergence divergence) {
    return divergence.op().getClass().getSimpleName()
        + " | "
        + abbreviate(divergence.inMemory())
        + " | "
        + abbreviate(divergence.postgres());
  }

  private static String abbreviate(String answer) {
    String plain = answer.replaceAll("[0-9]+", "#");
    return plain.length() <= 40 ? plain : plain.substring(0, 40);
  }

  private List<Op> shrink(List<Op> ops) {
    Predicate<List<Op>> differs = candidate -> replay(candidate).divergence().isPresent();
    List<Op> shorter = DifferentialShrinker.minimize(ops, differs);
    return DifferentialShrinker.simplify(shorter, Op::simpler, differs);
  }

  /**
   * The text of a failure. Its first line is what a check-run annotation shows (it keeps the first
   * line, cut at 300 characters): the seed, the sequence, the options that run it again and both
   * answers, each cut, so that the head of a head conflict survives. The sequence is run once more
   * before it is shrunk, because a difference that does not recur (a fault of the server, a
   * timeout) cannot be shrunk; it is then reported as it was found, with the line {@code NOT
   * SHRUNK}.
   */
  private String report(long seed, int sequence, List<Op> ops, Divergence first) {
    int originalLength = ops.size();
    boolean again = replay(ops).divergence().isPresent();
    List<Op> shrunk = again ? shrink(ops) : ops;
    Optional<Divergence> afterShrinking = again ? replay(shrunk).divergence() : Optional.empty();
    boolean notShrunk = afterShrinking.isEmpty();
    if (notShrunk) {
      shrunk = ops;
    }
    Divergence divergence = afterShrinking.orElse(first);
    StringBuilder out = new StringBuilder();
    out.append("seed ")
        .append(seed)
        .append(", sequence ")
        .append(sequence)
        .append(", run again: ")
        .append(runAgain(seed, sequence, originalLength))
        .append(": in-memory ")
        .append(oneLine(divergence.inMemory(), 60))
        .append(" / postgres ")
        .append(oneLine(divergence.postgres(), 60))
        .append('\n');
    out.append(
        notShrunk
            ? "NOT SHRUNK, the second run did not differ; " + originalLength + " operations"
            : "shrunk from " + originalLength + " operations to " + shrunk.size());
    out.append(", the clock starts at ").append(START).append('\n');
    for (int i = 0; i < shrunk.size(); i++) {
      out.append("  ").append(i + 1).append(". ").append(shrunk.get(i).describe()).append('\n');
    }
    out.append("  the first difference is at ")
        .append(
            divergence.index() < shrunk.size()
                ? "operation " + (divergence.index() + 1)
                : "a final read, made after the last operation")
        .append(" (")
        .append(divergence.op().describe())
        .append(")\n  in-memory: ")
        .append(divergence.inMemory())
        .append("\n  postgres:  ")
        .append(divergence.postgres())
        .append("\n  run again: ")
        .append(runAgain(seed, sequence, originalLength));
    return out.append(" (the sequences before it are generated again from the seed)").toString();
  }

  /** The options that run the sequence again: the sequences before it are generated again. */
  private static String runAgain(long seed, int sequence, int operations) {
    StringBuilder options =
        new StringBuilder("-D")
            .append(SEEDS)
            .append('=')
            .append(seed)
            .append(" -D")
            .append(SEQUENCES)
            .append('=')
            .append(sequence + 1)
            .append(" -D")
            .append(OPERATIONS)
            .append('=')
            .append(operations);
    if (Boolean.getBoolean(DifferentialOps.EXACT_UNITS)) {
      options.append(" -D").append(DifferentialOps.EXACT_UNITS).append("=true");
    }
    return options.toString();
  }

  /** An answer on one line of at most {@code max} characters, with "..." when it was cut. */
  private static String oneLine(String answer, int max) {
    String line = answer.replace('\n', ' ').replace('\r', ' ');
    return line.length() <= max ? line : line.substring(0, max) + "...";
  }
}
