package dev.regpack.postgres.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.regpack.postgres.capacity.ProbeSummary.Batch;
import dev.regpack.postgres.capacity.ProbeSummary.Inputs;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * The read-out of the capacity probe (T21). It is information for the owner's L-4 decision and
 * never a verdict (review focus LF21), so these tests pin the figures and the words that keep it
 * that way.
 */
class ProbeSummaryTest {
  private static final long SECOND = 1_000_000_000L;
  private static final long MIB = 1024L * 1024L;

  private static long s(double seconds) {
    return Math.round(seconds * SECOND);
  }

  /** A batch of {@code records} that arrived, started and ended at the given seconds. */
  private static Batch done(
      long index, double arrival, double start, double end, int records, long commitMillis) {
    long commit = commitMillis * 1_000_000L;
    return new Batch(
        index,
        (int) (index % 4),
        s(arrival),
        s(start),
        s(end),
        1_000_000L,
        commit / 2,
        commit - commit / 2,
        records,
        40,
        2_000);
  }

  private static Inputs inputs(long coveredSeconds, long scheduledBatches, int batchSize) {
    return new Inputs(
        "B",
        batchSize,
        4,
        3_470,
        scheduledBatches,
        coveredSeconds * SECOND,
        new ProbeSummary.Sizes(1_000, 800_000, 4_000_000),
        new ProbeSummary.Memory(OptionalLong.of(1_363 * MIB), "container memory limit", true),
        true,
        new ProbeSummary.SamplerFile("sampler-B-n1000.csv", coveredSeconds, 0),
        Optional.empty());
  }

  private static Inputs stopped(String cause) {
    Inputs base = inputs(180, 1, 1_000);
    return new Inputs(
        base.variant(),
        base.batchSize(),
        base.writers(),
        base.offeredRecordsPerSecond(),
        base.scheduledBatches(),
        base.coveredNanos(),
        base.sizes(),
        base.memory(),
        base.synchronousCommitForced(),
        base.sampler(),
        Optional.of(cause));
  }

  @Test
  void windowMedianMinimumAndMaximum() {
    // three full 60 s windows: 3,000, 6,000 and 1,000 records completed in them
    List<Batch> batches = new ArrayList<>();
    long index = 0;
    for (int i = 0; i < 3; i++) {
      batches.add(done(index++, 10 + i, 10 + i, 20 + i * 10, 1_000, 100));
    }
    for (int i = 0; i < 6; i++) {
      batches.add(done(index++, 70 + i, 70 + i, 80 + i * 5, 1_000, 100));
    }
    batches.add(done(index++, 130, 130, 170, 1_000, 100));
    // a batch that finished after the covered 180 s: completed, but in no window
    batches.add(done(index++, 175, 175, 190, 1_000, 100));

    ProbeSummary summary = ProbeSummary.of(inputs(180, 11, 1_000), batches);

    assertThat(summary.windowCount()).isEqualTo(3);
    assertThat(summary.windowMinimum()).isCloseTo(1_000.0 / 60.0, within(1e-9));
    assertThat(summary.windowMedian()).isCloseTo(3_000.0 / 60.0, within(1e-9));
    assertThat(summary.windowMaximum()).isCloseTo(6_000.0 / 60.0, within(1e-9));
    assertThat(summary.completedBatches()).isEqualTo(11);
    assertThat(summary.finishedAfterTheSlice()).isEqualTo(1);
  }

  @Test
  void anEvenNumberOfWindowsTakesTheMeanOfTheMiddleTwo() {
    List<Batch> batches = new ArrayList<>();
    // windows of 600, 1,200, 3,000 and 6,000 records
    batches.add(done(0, 1, 1, 30, 600, 100));
    batches.add(done(1, 61, 61, 100, 1_200, 100));
    batches.add(done(2, 121, 121, 150, 3_000, 100));
    batches.add(done(3, 181, 181, 230, 6_000, 100));

    ProbeSummary summary = ProbeSummary.of(inputs(240, 4, 1_000), batches);

    assertThat(summary.windowCount()).isEqualTo(4);
    assertThat(summary.windowMedian()).isCloseTo((1_200.0 + 3_000.0) / 2 / 60.0, within(1e-9));
  }

  @Test
  void commitTimesUseTheNearestRankPercentile() {
    // 101 values, so that ceil(q * n) differs from floor(q * n): the 51st and the 100th smallest
    List<Batch> batches = new ArrayList<>();
    for (int i = 1; i <= 101; i++) {
      batches.add(done(i - 1, i, i, i + 1, 1_000, i));
    }
    ProbeSummary summary = ProbeSummary.of(inputs(120, 101, 1_000), batches);

    assertThat(summary.commitP50Millis()).isEqualTo(51);
    assertThat(summary.commitP99Millis()).isEqualTo(100);
    assertThat(summary.commitMaxMillis()).isEqualTo(101);
  }

  @Test
  void backlogIsTheLargestQueuedArrivalAge() {
    List<Batch> batches = new ArrayList<>();
    batches.add(done(0, 10, 10, 12, 1_000, 100)); // taken at once
    batches.add(done(1, 20, 35, 40, 1_000, 100)); // queued 15 s
    batches.add(done(2, 30, 36, 41, 1_000, 100)); // queued 6 s
    ProbeSummary withoutTail = ProbeSummary.of(inputs(180, 3, 1_000), batches);
    assertThat(withoutTail.backlogSeconds()).isCloseTo(15.0, within(1e-9));

    // a batch that arrived at 150 s and never started is queued until the slice ends at 180 s
    List<Batch> withUnstarted = new ArrayList<>(batches);
    withUnstarted.add(Batch.notStarted(3, 3, s(150), 1_000));
    ProbeSummary withTail = ProbeSummary.of(inputs(180, 4, 1_000), withUnstarted);
    assertThat(withTail.backlogSeconds()).isCloseTo(30.0, within(1e-9));
    assertThat(withTail.neverStarted()).isEqualTo(1);
    assertThat(withTail.completedFraction()).isCloseTo(3.0 / 4.0, within(1e-9));
  }

  @Test
  void summaryStatesItIsInformationAndListsTheFixtureDifferences() {
    List<Batch> batches = List.of(done(0, 10, 10, 20, 1_000, 100));
    ProbeSummary summary = ProbeSummary.of(inputs(180, 1, 1_000), batches);
    List<String> lines = summary.lines();
    String text = String.join("\n", lines);

    assertThat(text).contains("information, not a verdict");
    assertThat(text).contains("no standby and no replica");
    assertThat(text).contains("no WAL archiving");
    assertThat(text).contains("production adapter");
    assertThat(text).contains("cannot separate adapter cost from server limit");
    assertThat(text).contains("SP-L");
    // the headline figures come first, each line short enough for one workflow annotation
    assertThat(lines.get(0)).contains("variant B").contains("records/s").contains("window");
    assertThat(lines.subList(0, Math.min(5, lines.size())))
        .allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(300))
        .allSatisfy(line -> assertThat(line).doesNotContain("\n").doesNotContain("\r"));
    assertThat(String.join("\n", lines.subList(0, 5))).contains("information, not a verdict");
    // it never claims a pass or a fail
    assertThat(text).doesNotContainPattern("(?i)\\b(PASS|FAIL)(ED)?\\b");
  }

  @Test
  void achievedRatioIsComputedFromTableSizesAndTheLimit() {
    // SP-L: 969 bytes a record, a slice of 4,160,000 records, 1,363 MiB: ratio 2.82
    double sp = ProbeSummary.achievedRatio(969L * 4_160_000L, 4_160_000L, 4_160_000L, 1_363 * MIB);
    assertThat(sp).isCloseTo(2.82, within(0.01));

    // 1,400 bytes a record and a run that completed 2,080,000 of 4,160,000 records
    double ours =
        ProbeSummary.achievedRatio(1_400L * 2_080_000L, 2_080_000L, 4_160_000L, 1_363 * MIB);
    assertThat(ours).isCloseTo(1_400.0 * 4_160_000.0 / (1_363.0 * MIB), within(1e-9));

    // the node memory that gives the same ratio for a 50-million-record day (SP-L: 16 GiB)
    long equivalent = ProbeSummary.equivalentNodeMemoryBytes(1_363 * MIB, 4_160_000L);
    assertThat(equivalent / (double) (1024L * MIB)).isCloseTo(16.0, within(0.01));
  }

  @Test
  void aRatioIsComparableToSpLOnlyWithinTenPercentOfItsRatio() {
    assertThat(ProbeSummary.comparableToSpL(2.82)).isTrue();
    assertThat(ProbeSummary.comparableToSpL(2.82 * 1.09)).isTrue();
    assertThat(ProbeSummary.comparableToSpL(2.82 * 0.91)).isTrue();
    assertThat(ProbeSummary.comparableToSpL(2.82 * 1.11)).isFalse();
    assertThat(ProbeSummary.comparableToSpL(2.82 * 0.89)).isFalse();

    // one batch that holds the whole SP-L slice of 4,160,000 records at SP-L's 969 bytes a record
    List<Batch> batches = List.of(done(0, 10, 10, 20, 4_160_000, 100));
    Inputs spL =
        spLLike(
            new ProbeSummary.Memory(OptionalLong.of(1_363 * MIB), "container memory limit", true));
    ProbeSummary near = ProbeSummary.of(spL, batches);
    String nearText = String.join("\n", near.lines());
    assertThat(nearText).contains("SP-L 2.82");
    assertThat(nearText).contains("comparable to SP-L: yes");

    ProbeSummary far =
        ProbeSummary.of(inputs(180, 1, 1_000), List.of(done(0, 10, 10, 20, 1_000, 100)));
    assertThat(String.join("\n", far.lines())).contains("comparable to SP-L: no");
  }

  /** A run whose bytes a record and memory give SP-L's ratio of 2.82, with the memory as given. */
  private static Inputs spLLike(ProbeSummary.Memory memory) {
    return new Inputs(
        "B",
        5_000,
        4,
        3_470,
        832,
        100 * SECOND,
        new ProbeSummary.Sizes(4_160_000, 3_200_000_000L, 969L * 4_160_000L),
        memory,
        true,
        new ProbeSummary.SamplerFile("s.csv", 100, 0),
        Optional.empty());
  }

  @Test
  void withoutAMemoryLimitTheRatioIsAgainstTheRunnerAndIsNotComparable() {
    // the memory of the server is not known at all
    Inputs unknown =
        new Inputs(
            "A",
            5_000,
            4,
            3_470,
            1,
            100 * SECOND,
            new ProbeSummary.Sizes(1_000, 800_000, 1_400_000),
            new ProbeSummary.Memory(OptionalLong.empty(), "no memory limit", false),
            true,
            new ProbeSummary.SamplerFile("s.csv", 100, 0),
            Optional.empty());
    ProbeSummary summary = ProbeSummary.of(unknown, List.of(done(0, 10, 10, 20, 1_000, 100)));
    String text = String.join("\n", summary.lines());
    assertThat(text).contains("no memory limit");
    assertThat(text).contains("comparable to SP-L: no");
  }

  @Test
  void aRunnerMemoryBasisIsNeverComparableToSpLEvenWhenTheNumberCoincides() {
    // variant A has no container limit, so the IT passes the memory of the runner; the same figures
    // as the SP-L-like run give the ratio 2.82, and still the sentence must not say "yes"
    ProbeSummary coincidence =
        ProbeSummary.of(
            spLLike(
                new ProbeSummary.Memory(
                    OptionalLong.of(1_363 * MIB), "runner memory, no container limit", false)),
            List.of(done(0, 10, 10, 20, 4_160_000, 100)));
    List<String> lines = coincidence.lines();
    String ratioLine = lines.get(2);

    assertThat(ratioLine).contains("table-to-memory ratio 2.8");
    assertThat(ratioLine).contains("against the runner memory");
    assertThat(ratioLine).contains("comparable to SP-L: not applicable (no container limit)");
    assertThat(String.join("\n", lines)).doesNotContain("comparable to SP-L: yes");
  }

  @Test
  void aFailureIsDescribedByItsClassItsSqlStateOrItsFirstLine() {
    assertThat(ProbeSummary.describe(new IllegalStateException("first line\nsecond line")))
        .isEqualTo("IllegalStateException: first line");
    assertThat(ProbeSummary.describe(new IllegalStateException()))
        .isEqualTo("IllegalStateException");
    // a SQLException shows its SQLState and not its message (server text can hold values)
    assertThat(
            ProbeSummary.describe(
                new java.sql.SQLException("duplicate key value (secret-key)=(1)", "23505")))
        .isEqualTo("SQLException: sqlstate=23505")
        .doesNotContain("secret-key");
    assertThat(ProbeSummary.describe(new java.sql.SQLException("no state")))
        .isEqualTo("SQLException: sqlstate=none");
    // a long message is cut
    assertThat(ProbeSummary.describe(new RuntimeException("x".repeat(500))))
        .hasSize("RuntimeException: ".length() + 200);
    // the adapter's own text is carried as it is
    assertThat(
            ProbeSummary.describe(
                new IllegalStateException(
                    "ledger statement failed: sqlstate=57014 constraint=none")))
        .isEqualTo(
            "IllegalStateException: ledger statement failed: sqlstate=57014 constraint=none");
  }

  @Test
  void theMessagesOfAStoppedRunNameTheCause() {
    Throwable cause = new IllegalStateException("ledger statement failed: sqlstate=40P01");
    String load = ProbeSummary.loadStoppedMessage("B-n5000", cause);
    assertThat(load)
        .isEqualTo(
            "the load stopped on an error (see summary-B-n5000.txt): IllegalStateException:"
                + " ledger statement failed: sqlstate=40P01");
    // a run that failed in a wrapper (the assertion of the load) is described by its cause
    String probe = ProbeSummary.probeStoppedMessage(2, new AssertionError(load, cause));
    assertThat(probe)
        .isEqualTo(
            "the capacity probe stopped on an error in 2 run(s): IllegalStateException:"
                + " ledger statement failed: sqlstate=40P01");
    assertThat(probe).doesNotContain("\n");
    // a failure without a wrapper is described itself
    assertThat(ProbeSummary.probeStoppedMessage(1, new IllegalStateException("boom")))
        .isEqualTo(
            "the capacity probe stopped on an error in 1 run(s): IllegalStateException: boom");
  }

  @Test
  void aStoppedRunPutsItsCauseAmongTheFirstFiveLines() {
    ProbeSummary summary =
        ProbeSummary.of(
            stopped("IllegalStateException: ledger statement failed: sqlstate=57014"),
            List.of(done(0, 10, 10, 20, 1_000, 100)));
    List<String> lines = summary.lines();

    assertThat(String.join("\n", lines.subList(0, 5)))
        .contains("the run stopped on an error: IllegalStateException: ledger statement failed")
        .contains("sqlstate=57014");
    assertThat(lines.subList(0, 5))
        .allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(300));
    // the first four lines are the figures, and the words of the detail are still in the file
    assertThat(lines.get(0)).contains("records/s");
    assertThat(String.join("\n", lines)).contains("cannot separate adapter cost from server limit");
    assertThat(String.join("\n", lines.subList(0, 5))).contains("information, not a verdict");

    // a run that did not stop has no such line
    ProbeSummary fine =
        ProbeSummary.of(inputs(180, 1, 1_000), List.of(done(0, 10, 10, 20, 1_000, 100)));
    assertThat(String.join("\n", fine.lines())).doesNotContain("stopped on an error");
  }

  @Test
  void latencyFromTheScheduledArrivalIsReportedNextToTheServiceTime() {
    // batch i arrives at i s, starts at i s and ends at 2i s: the time from the arrival grows with
    // the queue (i s) while the commit time stays 100 ms (CAPACITY-6)
    List<Batch> batches = new ArrayList<>();
    for (int i = 1; i <= 101; i++) {
      batches.add(done(i - 1, i, i, 2.0 * i, 1_000, 100));
    }
    ProbeSummary summary = ProbeSummary.of(inputs(300, 101, 1_000), batches);

    assertThat(summary.arrivalToEndP50Millis()).isEqualTo(51_000);
    assertThat(summary.arrivalToEndP99Millis()).isEqualTo(100_000);
    assertThat(summary.arrivalToEndMaxMillis()).isEqualTo(101_000);
    assertThat(summary.commitP50Millis()).isEqualTo(100);
    String line = summary.lines().get(1);
    assertThat(line)
        .contains("from scheduled arrival")
        .contains("p50 51000")
        .contains("p99 100000")
        .contains("max 101000")
        .contains("commit ms p50 100");
    assertThat(String.join("\n", summary.lines())).contains("service time");
  }

  @Test
  void aRunThatKeepsUpExactlyShowsTheBatchQuantisationAndSaysSo() {
    // 3,470 records/s in batches of 10,000, every batch taken at its arrival and done 2 s later
    // (CAPACITY-7): the 60 s windows hold 20 or 21 batches, so the rates read 3,333 and 3,500
    int batchSize = 10_000;
    int seconds = 1_200;
    OpenLoopSchedule schedule = new OpenLoopSchedule(3_470, batchSize, seconds);
    List<Batch> batches = new ArrayList<>();
    for (long k = 0; k < schedule.batchCount(); k++) {
      long arrival = schedule.arrivalNanos(k);
      batches.add(
          new Batch(
              k,
              (int) (k % 4),
              arrival,
              arrival,
              arrival + 2 * SECOND,
              1_000_000L,
              SECOND,
              SECOND,
              batchSize,
              40,
              2_000));
    }
    Inputs inputs =
        new Inputs(
            "A",
            batchSize,
            4,
            3_470,
            schedule.batchCount(),
            schedule.coveredNanos(),
            new ProbeSummary.Sizes(1_000, 800_000, 4_000_000),
            new ProbeSummary.Memory(OptionalLong.empty(), "not known", false),
            true,
            new ProbeSummary.SamplerFile("s.csv", seconds, 0),
            Optional.empty());
    ProbeSummary summary = ProbeSummary.of(inputs, batches);

    double step = batchSize / 60.0;
    // the windows differ from the offered rate by less than one batch per window
    assertThat(summary.windowMinimum()).isLessThan(3_470).isGreaterThan(3_470 - step);
    assertThat(summary.windowMaximum()).isGreaterThan(3_470).isLessThan(3_470 + step);
    assertThat(summary.windowMinimum()).as("the dip is arithmetic").isNotEqualTo(3_470.0);
    // and the read-out says that the figures move in steps of N / 60
    assertThat(summary.lines().get(0)).contains("steps of 166.7 records/s");
    assertThat(String.join("\n", summary.lines()))
        .contains("whole batches")
        .contains("not a dip of the ledger");
  }

  @Test
  void theCommitSentenceFollowsTheCommitMode() {
    List<Batch> batches = List.of(done(0, 10, 10, 20, 1_000, 100));
    String forced = String.join("\n", ProbeSummary.of(inputs(180, 1, 1_000), batches).lines());
    assertThat(forced).contains("a commit waits for the local WAL flush only");

    Inputs unforced = withCommit(inputs(180, 1, 1_000), false);
    String off = String.join("\n", ProbeSummary.of(unforced, batches).lines());
    assertThat(off)
        .as("variant C (synchronous_commit off) must not claim a flush wait")
        .doesNotContain("a commit waits for the local WAL flush only")
        .contains("the server setting of synchronous_commit decides whether a commit waits");
    // the differences of the fixture that the review found missing (CAPACITY-4)
    assertThat(forced)
        .contains("published loopback port")
        .contains("Docker volume of the root disk")
        .contains("swap is off")
        .contains("different VMs")
        .contains("batch sizes run in sequence on one server")
        .contains("no standby and no replica")
        .contains("no WAL archiving");
  }

  private static Inputs withCommit(Inputs base, boolean forced) {
    return new Inputs(
        base.variant(),
        base.batchSize(),
        base.writers(),
        base.offeredRecordsPerSecond(),
        base.scheduledBatches(),
        base.coveredNanos(),
        base.sizes(),
        base.memory(),
        forced,
        base.sampler(),
        base.stopped());
  }

  @Test
  void theCpuModelIsInTheReadOut() {
    Inputs base = inputs(180, 1, 1_000);
    Inputs withCpu =
        new Inputs(
            base.variant(),
            base.batchSize(),
            base.writers(),
            base.offeredRecordsPerSecond(),
            base.scheduledBatches(),
            base.coveredNanos(),
            base.sizes(),
            base.memory(),
            base.synchronousCommitForced(),
            base.sampler(),
            base.stopped(),
            "AMD EPYC 7763 64-Core Processor (4 cpus)");
    List<Batch> batches = List.of(done(0, 10, 10, 20, 1_000, 100));
    assertThat(String.join("\n", ProbeSummary.of(withCpu, batches).lines()))
        .contains("runner cpu: AMD EPYC 7763 64-Core Processor (4 cpus)");
    assertThat(String.join("\n", ProbeSummary.of(base, batches).lines()))
        .contains("runner cpu: not known");
  }

  @Test
  void sizesThatCouldNotBeReadAreSaidSoAndTheRatioIsNotComputed() {
    Inputs base = inputs(180, 1, 1_000);
    Inputs unknown =
        new Inputs(
            base.variant(),
            base.batchSize(),
            base.writers(),
            base.offeredRecordsPerSecond(),
            base.scheduledBatches(),
            base.coveredNanos(),
            ProbeSummary.Sizes.unavailable("sizes unavailable (sqlstate=57P03)"),
            base.memory(),
            base.synchronousCommitForced(),
            base.sampler(),
            base.stopped());
    ProbeSummary summary = ProbeSummary.of(unknown, List.of(done(0, 10, 10, 20, 1_000, 100)));
    List<String> lines = summary.lines();

    assertThat(lines.get(2))
        .contains("sizes unavailable (sqlstate=57P03)")
        .contains("table-to-memory ratio not computed")
        .contains("comparable to SP-L: no");
    assertThat(lines.get(3)).contains("decision row size unknown");
    assertThat(summary.ratio()).isZero();
    assertThat(summary.decisionRowBytes()).isZero();
    assertThat(summary.ledgerBytesPerRecord()).isZero();
    // the figures that do not need the sizes are still there
    assertThat(lines.get(0)).contains("records/s per 60 s window");
    assertThat(lines.get(1)).contains("from scheduled arrival");
  }

  @Test
  void theSamplerShortfallIsAmongTheFiveHeadlineLines() {
    Inputs base = inputs(1_200, 1, 1_000);
    Inputs thin =
        new Inputs(
            base.variant(),
            base.batchSize(),
            base.writers(),
            base.offeredRecordsPerSecond(),
            base.scheduledBatches(),
            base.coveredNanos(),
            base.sizes(),
            base.memory(),
            base.synchronousCommitForced(),
            new ProbeSummary.SamplerFile("sampler-B-n1000.csv", 130, 970, 3),
            base.stopped());
    List<String> lines = ProbeSummary.of(thin, List.of(done(0, 10, 10, 20, 1_000, 100))).lines();

    assertThat(String.join("\n", lines.subList(0, 5)))
        .contains("sampler rows 130 of about 1200 s, missed 970, with views 3");
    assertThat(lines.subList(0, 5))
        .allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(300));
    // rows exist, so the sentence about the kept rows holds
    assertThat(String.join("\n", lines)).contains("the rows are kept for the cause");

    // no row at all: the detail does not promise rows that are not there
    Inputs none =
        new Inputs(
            base.variant(),
            base.batchSize(),
            base.writers(),
            base.offeredRecordsPerSecond(),
            base.scheduledBatches(),
            base.coveredNanos(),
            base.sizes(),
            base.memory(),
            base.synchronousCommitForced(),
            new ProbeSummary.SamplerFile("sampler-B-n1000.csv", 0, 1),
            base.stopped());
    String text =
        String.join("\n", ProbeSummary.of(none, List.of(done(0, 10, 10, 20, 1_000, 100))).lines());
    assertThat(text)
        .contains("sampler rows 0 of about 1200 s, missed 1")
        .doesNotContain("the rows are kept for the cause")
        .contains("no sampler row was written");
  }

  @Test
  void aSamplerThatRecordedLessThanHalfOfTheRunOrNoViewsIsAProblem() {
    // 1,200 s covered: 600 rows are enough, 599 are not
    assertThat(
            ProbeSummary.samplerProblem(new ProbeSummary.SamplerFile("s.csv", 600, 0, 600), 1_200))
        .isEmpty();
    assertThat(
            ProbeSummary.samplerProblem(new ProbeSummary.SamplerFile("s.csv", 599, 0, 599), 1_200))
        .hasValueSatisfying(problem -> assertThat(problem).contains("599 rows").contains("1200 s"));
    // rows without any view column
    assertThat(
            ProbeSummary.samplerProblem(new ProbeSummary.SamplerFile("s.csv", 1_000, 5, 0), 1_200))
        .hasValueSatisfying(
            problem -> assertThat(problem).contains("no row has fresh view columns"));
    // both reasons in one message
    assertThat(ProbeSummary.samplerProblem(new ProbeSummary.SamplerFile("s.csv", 0, 3, 0), 1_200))
        .hasValueSatisfying(
            problem ->
                assertThat(problem).contains("0 rows").contains("no row has fresh view columns"));
    // a tiny slice needs a row or two, never zero
    assertThat(ProbeSummary.samplerProblem(new ProbeSummary.SamplerFile("s.csv", 3, 0, 3), 5))
        .isEmpty();
    assertThat(ProbeSummary.samplerProblem(new ProbeSummary.SamplerFile("s.csv", 2, 0, 2), 5))
        .isPresent();
    assertThat(ProbeSummary.samplerStoppedMessage("B-n5000", "x")).doesNotContain("\n");
  }

  @Test
  void theBatchesFileHoldsEveryObservationOfTheRun() {
    List<Batch> batches =
        List.of(
            done(0, 10, 11, 20, 1_000, 100),
            new Batch(1, 1, s(20), s(21), Batch.NOT_DONE, 0, 0, 0, 1_000, 0, 0),
            Batch.notStarted(2, 2, s(30), 1_000));
    List<String> lines = ProbeSummary.batchLines(batches);

    assertThat(lines).hasSize(4);
    assertThat(lines.get(0))
        .isEqualTo(
            "index,source,arrival_ns,start_ns,end_ns,generate_ns,ingest_ns,reportable_ns,"
                + "records,executions,batched_rows");
    assertThat(lines.get(1))
        .isEqualTo(
            "0,0,10000000000,11000000000,20000000000,1000000,50000000,50000000,1000,40,2000");
    assertThat(lines.get(2)).as("a batch that failed has no end").contains(",21000000000,-1,");
    assertThat(lines.get(3))
        .as("a batch never started has no start")
        .contains(",30000000000,-1,-1,");
  }

  @Test
  void anUnavailableReasonNamesTheSqlStateAndNotTheMessage() {
    assertThat(
            ProbeSummary.unavailable(
                "sizes", new java.sql.SQLException("in recovery mode (secret)", "57P03")))
        .isEqualTo("sizes unavailable (sqlstate=57P03)");
    assertThat(ProbeSummary.unavailable("server settings", new java.sql.SQLException("x")))
        .isEqualTo("server settings unavailable (sqlstate=none)");
    assertThat(ProbeSummary.unavailable("sizes", new IllegalStateException("boom")))
        .isEqualTo("sizes unavailable (IllegalStateException: boom)");
  }

  @Test
  void rowSizeAndStatementsPerBatchAreReported() {
    List<Batch> batches = new ArrayList<>();
    batches.add(done(0, 10, 10, 20, 1_000, 100));
    batches.add(done(1, 20, 20, 30, 1_000, 100));
    ProbeSummary summary = ProbeSummary.of(inputs(180, 2, 1_000), batches);

    assertThat(summary.decisionRowBytes()).isCloseTo(800.0, within(1e-9));
    assertThat(summary.statementsPerBatch()).isCloseTo(40.0, within(1e-9));
    assertThat(summary.batchedRowsPerBatch()).isCloseTo(2_000.0, within(1e-9));
    String text = String.join("\n", summary.lines());
    assertThat(text).contains("800 bytes").contains("300 to 500");
    assertThat(text).contains("4 sources");
  }
}
