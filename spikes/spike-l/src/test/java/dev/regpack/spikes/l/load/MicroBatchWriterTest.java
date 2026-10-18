package dev.regpack.spikes.l.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.bench.data.SplitMix64;
import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.spikes.l.encoding.PathCodec;
import dev.regpack.spikes.l.load.LedgerLoadModel.Candidate;
import dev.regpack.spikes.l.load.LedgerLoadModel.Params;
import dev.regpack.spikes.l.load.MicroBatchWriter.Method;
import dev.regpack.spikes.l.load.MicroBatchWriter.Timings;
import dev.regpack.spikes.l.schema.SchemaInstaller.Variant;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The shape of the three transactions of a micro-batch (spec 7.2 step 2, 7.8, 11.4), checked
 * against a recording connection: what the SQL does on PostgreSQL is proven by the smoke dispatch.
 */
class MicroBatchWriterTest {
  private static final Params PARAMS = new Params(400, 0.5, 1.5, 0.05, 3);

  private static List<Candidate> batch() {
    List<String> existing = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      existing.add(LedgerLoadModel.identityOf(3, 5_000_000L + i));
    }
    return LedgerLoadModel.nextBatch(PARAMS, new TradeRecordGen(8), new SplitMix64(4), existing);
  }

  private static MicroBatchWriter writer(FakeConnection fake, Variant variant) throws SQLException {
    return new MicroBatchWriter(fake.connection(), "sp_t", Method.COPY, variant, fake.loader());
  }

  private static int indexOf(List<String> log, String prefix, int from) {
    for (int i = from; i < log.size(); i++) {
      if (log.get(i).startsWith(prefix)) {
        return i;
      }
    }
    return -1;
  }

  private static int rows(FakeConnection fake, String table) {
    return fake.loaded.getOrDefault(table, List.of()).size();
  }

  @Test
  void threeTransactionsTheThirdWithAsynchronousCommit() throws Exception {
    FakeConnection fake = new FakeConnection();
    List<Candidate> batch = batch();
    MicroBatchWriter writer = writer(fake, Variant.E1_COMPACT);
    Timings timings = writer.write(7, batch);

    List<String> log = fake.log;
    assertThat(log.get(0)).isEqualTo("autocommit=false");
    assertThat(log.stream().filter(s -> s.equals("commit"))).hasSize(3);
    assertThat(log).doesNotContain("rollback");

    int commit1 = indexOf(log, "commit", 0);
    int commit2 = indexOf(log, "commit", commit1 + 1);
    int commit3 = indexOf(log, "commit", commit2 + 1);

    // transaction 1: ingest rows, registry, checkpoint, outbox row
    int ingest = indexOf(log, "load ingest ", 0);
    int registry = indexOf(log, "load ingest_registry", 0);
    int checkpoint = indexOf(log, "update INSERT INTO \"sp_t\".ingest_checkpoint", 0);
    int outbox = indexOf(log, "query INSERT INTO \"sp_t\".outbox", 0);
    assertThat(ingest).isBetween(0, commit1);
    assertThat(registry).isBetween(ingest, commit1);
    assertThat(checkpoint).isBetween(registry, commit1);
    assertThat(outbox).isBetween(checkpoint, commit1);

    // transaction 2: REPORT decisions, head, subjects before versions, deliveries, outbox complete
    int decisions = indexOf(log, "load decision ", commit1);
    int head = indexOf(log, "update INSERT INTO \"sp_t\".decision_head", commit1);
    int subjects = indexOf(log, "load report_subject", commit1);
    int versions = indexOf(log, "load report_version", commit1);
    int deliveries = indexOf(log, "load delivery", commit1);
    int complete = indexOf(log, "update UPDATE \"sp_t\".outbox", commit1);
    assertThat(decisions).isBetween(commit1, commit2);
    assertThat(head).isBetween(decisions, commit2);
    assertThat(subjects).isBetween(head, commit2);
    assertThat(versions).isBetween(subjects, commit2);
    assertThat(deliveries).isBetween(versions, commit2);
    assertThat(complete).isBetween(deliveries, commit2);
    assertThat(indexOf(log, "exec SET LOCAL", 0)).isGreaterThan(commit2);

    // transaction 3: the non-REPORT decisions with synchronous_commit off, set locally
    int local = indexOf(log, "exec SET LOCAL synchronous_commit = off", commit2);
    int rest = indexOf(log, "load decision ", commit2);
    assertThat(local).isBetween(commit2, rest);
    assertThat(rest).isBetween(local, commit3);
    assertThat(log.get(log.size() - 1)).isEqualTo("commit");

    assertThat(timings.ingestCommitNanos()).isPositive();
    assertThat(timings.reportCommitNanos()).isPositive();
    assertThat(timings.asyncCommitNanos()).isPositive();
  }

  @Test
  void rowCountsFollowTheCandidates() throws Exception {
    FakeConnection fake = new FakeConnection();
    List<Candidate> batch = batch();
    Timings timings = writer(fake, Variant.E1_COMPACT).write(7, batch);

    long reports = batch.stream().filter(Candidate::report).count();
    long fresh = batch.stream().filter(c -> c.report() && !c.superseding()).count();
    int freshLegs =
        batch.stream().filter(c -> c.report() && !c.superseding()).mapToInt(Candidate::legs).sum();
    int legs = batch.stream().filter(Candidate::report).mapToInt(Candidate::legs).sum();
    assertThat(rows(fake, "ingest")).isEqualTo(400);
    assertThat(rows(fake, "ingest_registry")).isEqualTo(400);
    assertThat(rows(fake, "decision")).isEqualTo(400);
    assertThat(rows(fake, "report_subject")).isEqualTo(freshLegs);
    assertThat(rows(fake, "report_version")).isEqualTo(legs);
    assertThat(rows(fake, "delivery")).isEqualTo(legs);
    assertThat(fresh).isLessThan(reports); // the batch does contain superseding candidates
    // rows: loaded rows, the head rows of the REPORT candidates, one checkpoint and one outbox row
    long loaded = fake.loaded.values().stream().mapToLong(List::size).sum();
    assertThat(timings.rows()).isEqualTo(loaded + reports + 2);
  }

  @Test
  void versionsAreOneForNewReportsAndTwoForSuperseding() throws Exception {
    FakeConnection fake = new FakeConnection();
    List<Candidate> batch = batch();
    writer(fake, Variant.E1_COMPACT).write(7, batch);
    assertThat(fake.loadedColumns.get("report_version"))
        .containsExactly("subject_id", "version_seq", "decision_id", "state");
    long twos = fake.loaded.get("report_version").stream().filter(r -> (int) r[1] == 2).count();
    long expectedTwos =
        batch.stream().filter(Candidate::superseding).mapToInt(Candidate::legs).sum();
    assertThat(twos).isEqualTo(expectedTwos).isPositive();
    assertThat(fake.loaded.get("report_version").stream().filter(r -> (int) r[1] == 1).count())
        .isEqualTo(
            batch.stream()
                .filter(c -> c.report() && !c.superseding())
                .mapToInt(Candidate::legs)
                .sum());
    // a superseding decision carries decision_seq 2, a new one 1
    List<String> columns = fake.loadedColumns.get("decision");
    int seqColumn = columns.indexOf("decision_seq");
    assertThat(fake.loaded.get("decision").stream().map(r -> r[seqColumn])).containsOnly(1L, 2L);
  }

  @Test
  void theVerboseVariantAddsThePathColumnAsTheLastColumn() throws Exception {
    FakeConnection compact = new FakeConnection();
    writer(compact, Variant.E1_COMPACT).write(1, batch());
    FakeConnection verbose = new FakeConnection();
    writer(verbose, Variant.E3_VERBOSE).write(1, batch());
    List<String> e1 = compact.loadedColumns.get("decision");
    List<String> e3 = verbose.loadedColumns.get("decision");
    assertThat(e1).doesNotContain("path");
    assertThat(e3).hasSize(e1.size() + 1);
    assertThat(e3.get(e3.size() - 1)).isEqualTo("path");
    assertThat(e3.subList(0, e1.size())).isEqualTo(e1);
    for (Object[] row : verbose.loaded.get("decision")) {
      assertThat(row).hasSize(e3.size());
      assertThat(row[e3.size() - 1]).isInstanceOf(MicroBatchWriter.Jsonb.class);
    }
  }

  @Test
  void theTwoEncodingsAreAlternativesNotCumulative() throws Exception {
    // E1 stores the packed path in body; E3 stores the verbose path in path and leaves body NULL,
    // so that the size of a variant is not the size of the other one plus something
    FakeConnection compact = new FakeConnection();
    writer(compact, Variant.E1_COMPACT).write(1, batch());
    FakeConnection verbose = new FakeConnection();
    writer(verbose, Variant.E3_VERBOSE).write(1, batch());
    int body = compact.loadedColumns.get("decision").indexOf("body");
    assertThat(body).isNotNegative();
    for (Object[] row : compact.loaded.get("decision")) {
      assertThat(row[body]).isInstanceOf(byte[].class);
    }
    for (Object[] row : verbose.loaded.get("decision")) {
      assertThat(row[body]).isNull();
    }
  }

  @Test
  void theVerbosePathIsFarLargerThanThePackedBodySoTheVariantsAreWorthComparing() {
    long compact = 0;
    long verbose = 0;
    for (long seq = 1; seq <= 500; seq++) {
      LedgerLoadModel.SyntheticPath path = LedgerLoadModel.syntheticPath(17L, seq);
      compact += PathCodec.encode(path.ruleOrdinals(), path.matched(), path.costUnits()).length;
      verbose += path.verboseJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
    // RowSizeIT asserts the same ratio on the stored bytes
    assertThat(verbose).isGreaterThan(5 * compact);
  }

  /**
   * The timer of a transaction starts after the rows are built in Java and just before the first
   * statement: the commit time that feeds L-2 is not client CPU. A list that logs every access
   * shows the order.
   */
  @Test
  void theTimerStartsAfterTheRowsAreBuilt() throws Exception {
    FakeConnection fake = new FakeConnection();
    List<Candidate> real = batch();
    List<Candidate> watched =
        new java.util.AbstractList<>() {
          @Override
          public Candidate get(int index) {
            fake.log.add("build");
            return real.get(index);
          }

          @Override
          public int size() {
            return real.size();
          }
        };
    List<Long> readings = new ArrayList<>();
    dev.regpack.bench.proto.NanoClock clock =
        () -> {
          fake.log.add("clock");
          readings.add((long) readings.size() * 1_000L);
          return readings.get(readings.size() - 1);
        };
    new MicroBatchWriter(
            fake.connection(), "sp_t", Method.COPY, Variant.E1_COMPACT, fake.loader(), clock)
        .write(7, watched);

    List<String> log = fake.log;
    int from = 0;
    for (int tx = 0; tx < 3; tx++) {
      int start = indexOf(log, "clock", from);
      int commit = indexOf(log, "commit", start);
      assertThat(indexOf(log, "build", from))
          .as("transaction %d builds its rows before the timer", tx)
          .isBetween(from, start);
      // no row is built while the timer runs, and the first thing after it is a statement
      int builtLate = indexOf(log, "build", start);
      assertThat(builtLate < 0 || builtLate > commit)
          .as("a row is built inside the timer of transaction %d", tx)
          .isTrue();
      assertThat(log.get(start + 1))
          .as("first entry after the timer of transaction %d", tx)
          .isNotEqualTo("build");
      from = indexOf(log, "clock", commit) + 1; // the reading that ends the transaction
    }
  }

  @Test
  void aFailureRollsBackAndThrows() throws Exception {
    FakeConnection fake = new FakeConnection("report_version");
    MicroBatchWriter writer = writer(fake, Variant.E1_COMPACT);
    assertThatThrownBy(() -> writer.write(7, batch()))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("report_version");
    assertThat(fake.log).contains("rollback");
    assertThat(fake.log.stream().filter(s -> s.equals("commit"))).hasSize(1);
  }

  @Test
  void idsAreUniquePerBatchAndDerivedFromTheBatchId() throws Exception {
    FakeConnection first = new FakeConnection();
    FakeConnection second = new FakeConnection();
    List<Candidate> batch = batch();
    writer(first, Variant.E1_COMPACT).write(1, batch);
    writer(second, Variant.E1_COMPACT).write(2, batch);
    int idColumn = first.loadedColumns.get("decision").indexOf("decision_id");
    List<Object> idsOne = first.loaded.get("decision").stream().map(r -> r[idColumn]).toList();
    List<Object> idsTwo = second.loaded.get("decision").stream().map(r -> r[idColumn]).toList();
    assertThat(idsOne).doesNotHaveDuplicates();
    assertThat(idsOne).doesNotContainAnyElementsOf(idsTwo);
  }

  @Test
  void theSchemaNameIsValidated() {
    FakeConnection fake = new FakeConnection();
    assertThatThrownBy(
            () ->
                new MicroBatchWriter(
                    fake.connection(),
                    "Bad Name; DROP",
                    Method.COPY,
                    Variant.E1_COMPACT,
                    fake.loader()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
