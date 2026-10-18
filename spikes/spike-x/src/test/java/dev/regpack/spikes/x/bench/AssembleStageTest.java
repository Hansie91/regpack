package dev.regpack.spikes.x.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.bench.data.SplitMix64;
import dev.regpack.model.Sha256;
import dev.regpack.spikes.x.TestDocuments;
import dev.regpack.spikes.x.TestSchemas;
import dev.regpack.spikes.x.bench.AssembleStage.Sealed;
import dev.regpack.spikes.x.validate.DefectInjector;
import dev.regpack.spikes.x.validate.DefectInjector.Kind;
import dev.regpack.spikes.x.validate.PolicyModel;
import dev.regpack.spikes.x.validate.PolicyModel.Policy;
import dev.regpack.spikes.x.xml.TxModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.validation.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.SAXException;

class AssembleStageTest {
  private static int count(String text, String needle) {
    int count = 0;
    for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
      count++;
    }
    return count;
  }

  @Test
  void outputFilesValidateAndHashesMatch(@TempDir Path dir) throws IOException, SAXException {
    Schema schema = TestSchemas.txn();
    ConcurrentLinkedQueue<Sealed> sealed = new ConcurrentLinkedQueue<>();
    try (AssembleStage stage =
        new AssembleStage(
            schema, PolicyModel.ALL, 2, 300, 12L, dir, AssembleStage.gateOf(schema), sealed::add)) {
      assertThat(stage.cycleUnits()).as("2 threads x 300 records").isEqualTo(600);
      assertThat(stage.runUnits(stage.cycleUnits())).isEqualTo(600);
      assertThat(stage.checksum()).isNotZero();

      assertThat(sealed).hasSize(2);
      for (Sealed s : sealed) {
        assertThat(s.file()).exists().startsWith(dir);
        assertThat(s.records()).isEqualTo(300);
        assertThat(s.validated()).as("ALL validates every record").isEqualTo(300);
        assertThat(Sha256.of(Files.readAllBytes(s.file())).hex()).isEqualTo(s.sha256());
        assertThat(
                count(Files.readString(s.file(), StandardCharsets.UTF_8), "<transaction_record>"))
            .isEqualTo(300);
        assertThat(s.bytes()).isEqualTo(Files.size(s.file()));
        assertThat(FileGateBench.validateNanos(s.file(), schema)).isPositive();
      }
      List<Path> files = sealed.stream().map(Sealed::file).distinct().toList();
      assertThat(files).as("one file per lane").hasSize(2);
      // a second cycle overwrites the lane files; nothing piles up in the directory
      assertThat(stage.runUnits(stage.cycleUnits())).isEqualTo(600);
      try (var listing = Files.list(dir)) {
        assertThat(listing.count()).isEqualTo(2);
      }
    }
    try (var listing = Files.list(dir)) {
      assertThat(listing.count()).as("close deletes the files it wrote").isZero();
    }
  }

  @Test
  void sameSeedSameFiles(@TempDir Path dir) throws IOException {
    Schema schema = schemaUnchecked();
    String[] hashes = new String[2];
    for (int run = 0; run < 2; run++) {
      Path sub = Files.createDirectory(dir.resolve("r" + run));
      ConcurrentLinkedQueue<Sealed> sealed = new ConcurrentLinkedQueue<>();
      try (AssembleStage stage =
          new AssembleStage(
              schema,
              PolicyModel.SAMPLED_10,
              1,
              200,
              3L,
              sub,
              AssembleStage.gateOf(schema),
              sealed::add)) {
        stage.runUnits(stage.cycleUnits());
      }
      hashes[run] = sealed.peek().sha256();
    }
    assertThat(hashes[0]).isEqualTo(hashes[1]);
  }

  @Test
  void samplingValidatesAboutTheRequestedFraction(@TempDir Path dir) throws IOException {
    Schema schema = schemaUnchecked();
    try (AssembleStage all =
            new AssembleStage(
                schema, PolicyModel.ALL, 2, 500, 4L, Files.createDirectory(dir.resolve("all")));
        AssembleStage tenth =
            new AssembleStage(
                schema,
                PolicyModel.SAMPLED_10,
                2,
                500,
                4L,
                Files.createDirectory(dir.resolve("tenth")))) {
      int cycles = 10;
      for (int i = 0; i < cycles; i++) {
        all.runUnits(all.cycleUnits());
        tenth.runUnits(tenth.cycleUnits());
      }
      long total = (long) cycles * 2 * 500;
      assertThat(all.sealedRecords()).isEqualTo(total);
      assertThat(all.validatedRecords()).isEqualTo(total);
      assertThat(tenth.sealedRecords()).isEqualTo(total);
      // 10,000 records at 10%: 1,000 validations with a standard deviation of 30
      assertThat(tenth.validatedRecords()).isBetween(850L, 1_150L);
    }
  }

  @Test
  void sealedRateIncludesFileGateAndHash(@TempDir Path dir) throws IOException {
    Schema schema = schemaUnchecked();
    long sleepMillis = 150;
    AtomicInteger gateCalls = new AtomicInteger();
    ConcurrentLinkedQueue<String> gateThreads = new ConcurrentLinkedQueue<>();
    Map<String, List<String>> gateHashes = new ConcurrentHashMap<>();
    AssembleStage.Gate gate =
        file -> {
          gateCalls.incrementAndGet();
          gateThreads.add(Thread.currentThread().getName());
          try {
            // the gate sees the sealed file: its bytes are the ones the stage hashed
            gateHashes
                .computeIfAbsent(file.getFileName().toString(), k -> new CopyOnWriteArrayList<>())
                .add(Sha256.of(Files.readAllBytes(file)).hex());
            Thread.sleep(sleepMillis);
          } catch (IOException e) {
            throw new IllegalStateException(e);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
          AssembleStage.gateOf(schema).check(file);
        };
    Map<String, List<String>> sealedHashes = new ConcurrentHashMap<>();
    try (AssembleStage stage =
        new AssembleStage(
            schema,
            PolicyModel.ALL,
            2,
            300,
            6L,
            dir,
            gate,
            s ->
                sealedHashes
                    .computeIfAbsent(
                        s.file().getFileName().toString(), k -> new CopyOnWriteArrayList<>())
                    .add(s.sha256()))) {
      long first = timed(stage);
      long second = timed(stage);
      // the sleep sits inside the measured cycle, so each cycle lasts at least that long: a floor
      // that does not depend on how fast another stage runs (two thirds allows for timer slack)
      assertThat(first).isGreaterThanOrEqualTo(sleepMillis * 1_000_000L * 2 / 3);
      assertThat(second).isGreaterThanOrEqualTo(sleepMillis * 1_000_000L * 2 / 3);
      assertThat(gateCalls.get()).as("one gate call per lane and cycle").isEqualTo(2 * 2);
      assertThat(gateThreads)
          .as("the gate runs on the pool threads, inside runUnits")
          .allMatch(name -> name.startsWith("spike-x-assemble-"))
          .doesNotContain(Thread.currentThread().getName());
      assertThat(sealedHashes.keySet()).hasSize(2);
      assertThat(gateHashes)
          .as("the file at the gate is the file that was hashed, cycle by cycle")
          .isEqualTo(sealedHashes);
      sealedHashes.values().forEach(hashes -> assertThat(hashes).hasSize(2));
    }
  }

  @Test
  void checksumFoldsTheSealedFiles(@TempDir Path dir) throws IOException {
    Schema schema = schemaUnchecked();
    long same1 = checksumOf(schema, Files.createDirectory(dir.resolve("a")), 5L, 1);
    long same2 = checksumOf(schema, Files.createDirectory(dir.resolve("b")), 5L, 1);
    long other = checksumOf(schema, Files.createDirectory(dir.resolve("c")), 6L, 1);
    assertThat(same1).isNotZero().isEqualTo(same2);
    assertThat(other).as("another seed writes other files").isNotEqualTo(same1);
    long twoCycles = checksumOf(schema, Files.createDirectory(dir.resolve("d")), 5L, 2);
    assertThat(twoCycles).as("each cycle folds into the checksum").isNotEqualTo(same1);

    // the same records in another order: a file of the same size and a different hash
    TxModel[] ringA = ring(31L, 50);
    TxModel[] ringB = ringA.clone();
    ringB[10] = ringA[20];
    ringB[20] = ringA[10];
    ConcurrentLinkedQueue<Sealed> sealedA = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<Sealed> sealedB = new ConcurrentLinkedQueue<>();
    long checksumA;
    long checksumB;
    try (AssembleStage a =
            new AssembleStage(
                schema,
                PolicyModel.ALL,
                1,
                50,
                7L,
                Files.createDirectory(dir.resolve("ra")),
                ignored -> {},
                sealedA::add,
                lane -> ringA);
        AssembleStage b =
            new AssembleStage(
                schema,
                PolicyModel.ALL,
                1,
                50,
                7L,
                Files.createDirectory(dir.resolve("rb")),
                ignored -> {},
                sealedB::add,
                lane -> ringB)) {
      a.runUnits(a.cycleUnits());
      b.runUnits(b.cycleUnits());
      checksumA = a.checksum();
      checksumB = b.checksum();
    }
    assertThat(sealedB.peek().bytes()).as("same size").isEqualTo(sealedA.peek().bytes());
    assertThat(sealedB.peek().sha256()).as("other content").isNotEqualTo(sealedA.peek().sha256());
    assertThat(checksumB).as("the checksum follows the file hash").isNotEqualTo(checksumA);
  }

  @Test
  void aDefectInTheRecordBeingWrittenFailsTheCycleUnderAll(@TempDir Path dir) throws IOException {
    Schema schema = schemaUnchecked();
    for (Kind kind : Kind.values()) {
      for (int variant = 0; variant < 3; variant++) {
        TxModel[] ring = ring(21L, 8);
        ring[3] = DefectInjector.corrupt(ring[3], kind, variant);
        try (AssembleStage stage =
            new AssembleStage(
                schema, PolicyModel.ALL, 1, 8, 5L, dir, ignored -> {}, s -> {}, lane -> ring)) {
          assertThatThrownBy(() -> stage.runUnits(stage.cycleUnits()))
              .as("%s variant %d", kind, variant)
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("failed the per-record gate")
              .hasMessageContaining("record 3 of lane 0");
          assertThat(stage.sealedRecords()).as("a failed cycle seals nothing").isZero();
        }
      }
    }
    // the same ring without the defect passes under ALL
    TxModel[] clean = ring(21L, 8);
    try (AssembleStage stage =
        new AssembleStage(
            schema, PolicyModel.ALL, 1, 8, 5L, dir, ignored -> {}, s -> {}, lane -> clean)) {
      assertThat(stage.runUnits(stage.cycleUnits())).isEqualTo(8);
      assertThat(stage.validatedRecords()).isEqualTo(8);
    }
  }

  @Test
  void aPolicyOfFractionZeroValidatesNothingAndSoLetsADefectThrough(@TempDir Path dir)
      throws IOException {
    Schema schema = schemaUnchecked();
    TxModel[] ring = ring(21L, 8);
    for (int i = 0; i < ring.length; i++) {
      ring[i] = DefectInjector.corrupt(ring[i], Kind.values()[i % 6], i);
    }
    Policy none = new Policy("NONE", 0.0);
    try (AssembleStage stage =
        new AssembleStage(schema, none, 1, 8, 5L, dir, ignored -> {}, s -> {}, lane -> ring)) {
      assertThat(stage.runUnits(stage.cycleUnits())).isEqualTo(8);
      assertThat(stage.validatedRecords()).isZero();
      assertThat(stage.sealedRecords()).isEqualTo(8);
    }
  }

  @Test
  void sampledValidationFailsExactlyWhereTheSamplerPicksADefectiveRecord(@TempDir Path dir)
      throws IOException {
    Schema schema = schemaUnchecked();
    // every record defective: each cycle fails at the first sampled record, no validation passes
    boolean[] all = new boolean[60];
    Arrays.fill(all, true);
    int[] everyRecord = replayAgainstStage(schema, Files.createDirectory(dir.resolve("all")), all);
    assertThat(everyRecord[0]).as("every cycle failed").isEqualTo(40);
    assertThat(everyRecord[1]).isZero();

    // two defective records among 60: some cycles pass with their sampled records validated,
    // some fail at the defective record the sampler reached
    boolean[] two = new boolean[60];
    two[7] = true;
    two[41] = true;
    int[] mixed = replayAgainstStage(schema, Files.createDirectory(dir.resolve("two")), two);
    assertThat(mixed[0]).as("failed cycles").isPositive();
    assertThat(mixed[1]).as("passed cycles").isPositive();
  }

  /**
   * Replays the sampler of lane 0 outside the stage, over 40 cycles of a ring in which the marked
   * records are defective, and checks that the stage fails and passes exactly where the replay says
   * and that its validation count is the replay's. Returns the failed and the passed cycles.
   */
  private static int[] replayAgainstStage(Schema schema, Path dir, boolean[] defective)
      throws IOException {
    int size = defective.length;
    TxModel[] ring = ring(77L, size);
    for (int i = 0; i < size; i++) {
      if (defective[i]) {
        ring[i] = DefectInjector.corrupt(ring[i], Kind.values()[i % 6], i);
      }
    }
    long seed = 9L;
    SplitMix64 sampler = AssembleStage.samplerOf(seed, 0);
    int cursor = 0;
    long expectedValidated = 0;
    int failed = 0;
    int passed = 0;
    try (AssembleStage stage =
        new AssembleStage(
            schema,
            PolicyModel.SAMPLED_10,
            1,
            size,
            seed,
            dir,
            ignored -> {},
            s -> {},
            lane -> ring)) {
      for (int cycle = 0; cycle < 40; cycle++) {
        int failAt = -1;
        int sampledOk = 0;
        for (int i = 0; i < size && failAt < 0; i++) {
          int at = cursor;
          cursor = cursor + 1 == ring.length ? 0 : cursor + 1;
          if (PolicyModel.SAMPLED_10.sample(sampler)) {
            if (defective[at]) {
              failAt = i;
            } else {
              sampledOk++;
            }
          }
        }
        if (failAt >= 0) {
          int expected = failAt;
          assertThatThrownBy(() -> stage.runUnits(stage.cycleUnits()))
              .hasMessageContaining("failed the per-record gate")
              .hasMessageContaining("record " + expected + " of lane 0");
          failed++;
        } else {
          assertThat(stage.runUnits(stage.cycleUnits())).isEqualTo(size);
          expectedValidated += sampledOk;
          passed++;
        }
        assertThat(stage.validatedRecords()).as("cycle %d", cycle).isEqualTo(expectedValidated);
      }
    }
    return new int[] {failed, passed};
  }

  private static long checksumOf(Schema schema, Path dir, long seed, int cycles) {
    try (AssembleStage stage = new AssembleStage(schema, PolicyModel.ALL, 2, 100, seed, dir)) {
      for (int i = 0; i < cycles; i++) {
        stage.runUnits(stage.cycleUnits());
      }
      return stage.checksum();
    }
  }

  private static TxModel[] ring(long seed, int size) {
    return TestDocuments.records(seed, size).toArray(new TxModel[0]);
  }

  @Test
  void aFileThatTheGateRejectsFailsTheCycle(@TempDir Path dir) throws IOException {
    Schema schema = schemaUnchecked();
    AssembleStage.Gate reject =
        file -> {
          throw new IllegalStateException("gate says no");
        };
    try (AssembleStage stage =
        new AssembleStage(schema, PolicyModel.ALL, 1, 50, 1L, dir, reject, s -> {})) {
      assertThatThrownBy(() -> stage.runUnits(stage.cycleUnits()))
          .hasMessageContaining("gate says no");
    }
  }

  @Test
  void latencyPerFileSizeGrowsWithTheFile() throws Exception {
    long[] nanos =
        AssembleStage.latencyPerFileSize(new int[] {100, 400, 1_600}, TestSchemas.txn(), 2L);
    assertThat(nanos).hasSize(3);
    assertThat(java.util.Arrays.stream(nanos).allMatch(n -> n > 0)).isTrue();
    assertThat(nanos[2]).as("16 times the records").isGreaterThan(nanos[0]);
  }

  private static long timed(AssembleStage stage) {
    long start = System.nanoTime();
    stage.runUnits(stage.cycleUnits());
    return System.nanoTime() - start;
  }

  private static Schema schemaUnchecked() {
    try {
      return TestSchemas.txn();
    } catch (SAXException | IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
