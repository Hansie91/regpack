package dev.regpack.flow.ingest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.regpack.flow.ingest.Dq.Kind;
import dev.regpack.flow.ingest.FileIngest.IngestReport;
import dev.regpack.flow.ingest.IngestFixture.SimulatedCrash;
import dev.regpack.model.BatchId;
import dev.regpack.model.Checkpoint;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Sha256;
import dev.regpack.ports.connector.SourceConnector.Batch;
import dev.regpack.ports.connector.SourceConnector.BatchPayload;
import dev.regpack.ports.connector.SourceConnector.BatchStream;
import dev.regpack.ports.connector.SourceConnector.Manifest;
import dev.regpack.ports.connector.SourceConnector.ReadLimits;
import dev.regpack.ports.connector.SourceConnector.ReadRequest;
import dev.regpack.ports.crypto.KeyUnavailableException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore.StoredObject;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import dev.regpack.ports.evidence.journal.JournalKey;
import dev.regpack.ports.evidence.journal.JournalObject;
import dev.regpack.ports.evidence.journal.JournalSchemas;
import dev.regpack.ports.evidence.journal.JournalScope;
import dev.regpack.ports.evidence.journal.JournalType;
import dev.regpack.ports.ledger.DecisionLedger;
import dev.regpack.ports.ledger.IngestLedger;
import dev.regpack.ports.ledger.IngestLedger.Disposition;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.ManifestLedger.ObjectClass;
import dev.regpack.ports.ledger.OutboxLedger.OutboxItem;
import dev.regpack.ports.ledger.OutboxLedger.OutboxKind;
import dev.regpack.ports.ledger.OutboxLedger.OutboxState;
import dev.regpack.ports.ledger.RetentionLedger.RetentionClaim;
import dev.regpack.ports.ledger.RetentionLedger.RetentionRow;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The ingest stage (plan T7, D-W2): land, put the {@code BatchAdmitted} object, commit the ingest,
 * acknowledge the source, and only then does the connector move the file. The scenarios are static
 * methods over a fixture so that {@link FileIngestMutantsTest} can run each one against a stage
 * with a deliberate flaw and see it fail.
 */
class FileIngestTest {
  @TempDir Path root;

  // ------------------------------------------------------------------ helpers

  private static final List<String> ORDER_EVENTS =
      List.of("put:landing", "put:journal", "commitIngest", "acknowledge", "moved", "stayed");

  private static BatchReport only(IngestReport report) {
    assertThat(report.batches()).hasSize(1);
    return report.batches().getFirst();
  }

  private static List<Dq> items(IngestFixture fx, Kind kind) {
    return fx.dq.list().stream().filter(d -> d.kind() == kind).toList();
  }

  private static BatchId idOf(byte[] csv) {
    return BatchId.of(Sha256.of(csv), 0);
  }

  private static EvidenceKey landingKey(byte[] csv) {
    return EvidenceKey.of("landing", Sha256.of(csv).hex(), "0");
  }

  private static JournalKey admittedKey(BatchId id) {
    return new JournalKey.ClassA(
        JournalScope.control(), 0, JournalType.BATCH_ADMITTED, id.value(), 1);
  }

  private static String hexOfName(String name) {
    return Sha256.ofUtf8(name).hex();
  }

  private static byte[] twoRows() {
    return IngestFixture.csv(
        IngestFixture.row("T-1", 1, "EX-1", "10"), IngestFixture.row("T-2", 1, "EX-2", "10"));
  }

  // ------------------------------------------------------------------ the order of D-W2

  @Test
  void aGoodFileLandsCommitsAndAcknowledgesInThatOrder() {
    goodFileOrder(new IngestFixture(root));
  }

  static void goodFileOrder(IngestFixture fx) {
    byte[] csv = twoRows();
    fx.drop("a.csv", csv, 2, "20");
    IngestReport report = fx.run();

    assertThat(fx.log.stream().filter(ORDER_EVENTS::contains).toList())
        .containsExactly("put:landing", "put:journal", "commitIngest", "acknowledge", "moved");
    BatchReport batch = only(report);
    BatchId id = idOf(csv);
    assertThat(batch.batchId()).isEqualTo(id);
    assertThat(batch.file()).isEqualTo("a.csv");
    assertThat(batch.verdict()).isEqualTo(Verdict.PROCESSED);
    assertThat(batch.redelivery()).isFalse();
    assertThat(batch.declaredRecords()).isEqualTo(2);
    assertThat(batch.ingested()).isEqualTo(2);
    assertThat(batch.quarantined()).isZero();
    assertThat(batch.newVersions()).hasSize(2);
    assertThat(batch.outbox()).isPresent();
    assertThat(report.defects()).isEmpty();

    IngestLedger.Result stored = fx.ledgerStore.ingestResult(id).orElseThrow();
    assertThat(stored.entries())
        .extracting(IngestLedger.EntryResult::disposition)
        .containsOnly(Disposition.NEW_VERSION);
    assertThat(fx.ledgerStore.latestCheckpoint(IngestFixture.SOURCE, "trades")).isPresent();
    assertThat(fx.names(fx.inbox.inbox)).isEmpty();
    assertThat(fx.names(fx.inbox.processed)).containsExactly("a.csv", "a.csv.done");

    // the landing object is the file, the admitted object names the batch, both are in the manifest
    StoredObject landed = fx.evidenceStore.get(landingKey(csv)).orElseThrow();
    assertThat(landed.bytes()).isEqualTo(csv);
    assertThat(landed.mediaType()).isEqualTo("text/csv");
    EvidenceKey admitted = admittedKey(id).evidenceKey();
    assertThat(fx.evidenceStore.get(admitted)).isPresent();
    assertThat(fx.ledgerStore.manifestRow(landingKey(csv)).orElseThrow().entry().objectClass())
        .isEqualTo(ObjectClass.LANDING_BATCH);
    assertThat(fx.ledgerStore.manifestRow(admitted).orElseThrow().entry().objectClass())
        .isEqualTo(ObjectClass.JOURNAL_A);

    // the content of the BatchAdmitted object (class A, control scope, epoch 0, ordinal 1)
    JournalObject object = fx.writer.journal().read(admittedKey(id)).orElseThrow();
    assertThat(object.type()).isEqualTo(JournalType.BATCH_ADMITTED);
    assertThat(object.schemaVersion()).isEqualTo(JournalSchemas.V1);
    assertThat(object.key()).isEqualTo(admittedKey(id));
    assertThat(object.key())
        .isEqualTo(
            new JournalKey.ClassA(
                JournalScope.control(), 0, JournalType.BATCH_ADMITTED, id.value(), 1));
    assertThat(object.payload()).containsOnlyKeys("batch_id", "pack_digest", "context_digests");
    assertThat(object.payload().get("batch_id")).isEqualTo(id.value());
    assertThat(object.payload().get("pack_digest")).isEqualTo(IngestFixture.PACK_DIGEST.hex());
    assertThat(object.payload().get("context_digests"))
        .isEqualTo(List.of(fx.mapping.digest().hex()));
    assertThat(IngestFixture.PACK_DIGEST.hex()).isNotEqualTo(fx.mapping.digest().hex());

    // the retention class of each object is the class of its kind
    assertThat(retentionClassesOf(fx, landingKey(csv))).containsExactly("landing");
    assertThat(retentionClassesOf(fx, admitted)).containsExactly("journal");
  }

  private static List<String> retentionClassesOf(IngestFixture fx, EvidenceKey key) {
    return fx.claims.stream()
        .filter(c -> c.artifact().equals(key))
        .map(RetentionClaim::retentionClass)
        .distinct()
        .toList();
  }

  @Test
  void aLedgerExceptionAtCommitMovesNoFileAndAcknowledgesNothing() {
    ledgerExceptionAtCommit(new IngestFixture(root));
  }

  static void ledgerExceptionAtCommit(IngestFixture fx) {
    byte[] csv = twoRows();
    fx.drop("a.csv", csv, 2, "20");
    fx.failCommits = true;

    assertThatThrownBy(fx::run).isInstanceOf(LedgerException.class);

    // the call and three identical re-issues, then the exception
    assertThat(fx.commits).hasSize(4);
    assertThat(fx.commits).allSatisfy(c -> assertThat(c).isEqualTo(fx.commits.getFirst()));
    assertThat(fx.log).doesNotContain("acknowledge", "moved");
    assertThat(fx.names(fx.inbox.inbox)).containsExactly("a.csv", "a.csv.done");
    assertThat(fx.names(fx.inbox.processed)).isEmpty();
    assertThat(fx.ledgerStore.ingestResult(idOf(csv))).isEmpty();

    // the ledger recovers: nothing was stored, so this is the first commit and the file moves
    fx.failCommits = false;
    BatchReport batch = only(fx.run());
    assertThat(batch.redelivery()).isFalse();
    assertThat(batch.ingested()).isEqualTo(2);
    assertThat(fx.names(fx.inbox.processed)).containsExactly("a.csv", "a.csv.done");
  }

  @Test
  void anExceptionThatIsNoLedgerExceptionIsNotRetried() {
    IngestFixture fx = new IngestFixture(root);
    byte[] csv = twoRows();
    fx.drop("a.csv", csv, 2, "20");
    IllegalArgumentException refusal =
        new IllegalArgumentException("the ledger refuses the commit");
    fx.failNextCommitWith = refusal;

    // a deterministic refusal is a defect, not an unknown outcome: one call, no re-issue
    assertThatThrownBy(fx::run).isSameAs(refusal);

    assertThat(fx.commits).hasSize(1);
    assertThat(fx.log).doesNotContain("acknowledge", "moved");
    assertThat(fx.names(fx.inbox.inbox)).containsExactly("a.csv", "a.csv.done");
    assertThat(fx.ledgerStore.ingestResult(idOf(csv))).isEmpty();
  }

  @Test
  void aCommitThatWasAppliedBeforeTheExceptionIsFoundByTheReissue() {
    IngestFixture fx = new IngestFixture(root);
    byte[] csv = twoRows();
    fx.drop("a.csv", csv, 2, "20");
    fx.failAfterApplying = true;

    BatchReport batch = only(fx.run());

    // the first call stored the batch and lost the answer; the re-issue is a replay
    assertThat(fx.commits).hasSize(2);
    assertThat(batch.redelivery()).isTrue();
    assertThat(batch.newVersions()).hasSize(2);
    assertThat(fx.ledgerStore.pendingOutbox(OutboxKind.DECIDE_BATCH, 10)).hasSize(1);
    assertThat(fx.names(fx.inbox.processed)).containsExactly("a.csv", "a.csv.done");
  }

  @Test
  void anOutageOfTheEvidenceTierOrTheKeyServicePropagatesAndMovesNothing() {
    List<RuntimeException> failures =
        List.of(new EvidenceUnavailableException("outage"), new KeyUnavailableException());
    for (int i = 0; i < failures.size(); i++) {
      IngestFixture fx = new IngestFixture(root.resolve("f" + i));
      byte[] csv = twoRows();
      fx.drop("a.csv", csv, 2, "20");
      fx.failNextPut = failures.get(i);
      fx.failNextPutPrefix = "landing/";

      assertThatThrownBy(fx::run).isSameAs(failures.get(i));

      assertThat(fx.log).doesNotContain("commitIngest", "acknowledge");
      assertThat(fx.names(fx.inbox.inbox)).containsExactly("a.csv", "a.csv.done");
      assertThat(fx.ledgerStore.ingestResult(idOf(csv))).isEmpty();
      // the tier is back: the same file is ingested as a first delivery
      BatchReport batch = only(fx.run());
      assertThat(batch.redelivery()).isFalse();
      assertThat(fx.names(fx.inbox.processed)).containsExactly("a.csv", "a.csv.done");
    }
  }

  @Test
  void theItemOfAQuarantinedRecordIsDurableBeforeTheSourceIsAcknowledged() {
    IngestFixture fx = new IngestFixture(root);
    byte[] csv =
        IngestFixture.csv(
            IngestFixture.row("T-1", 1, "EX-1", "10"), IngestFixture.row("T-2", 1, "EX-2", "abc"));
    fx.drop("a.csv", csv, 2, "10");
    fx.failNextPut = new EvidenceUnavailableException("outage");
    fx.failNextPutPrefix = "dq/";

    assertThatThrownBy(fx::run).isInstanceOf(EvidenceUnavailableException.class);

    // the record that is not committed has no record of its own yet: the file must not move
    assertThat(fx.log).doesNotContain("commitIngest", "acknowledge");
    assertThat(fx.names(fx.inbox.inbox)).containsExactly("a.csv", "a.csv.done");
    only(fx.run());
    assertThat(items(fx, Kind.RECORD_QUARANTINED)).hasSize(1);
    assertThat(fx.names(fx.inbox.processed)).containsExactly("a.csv", "a.csv.done");
  }

  // ------------------------------------------------------------------ redelivery and replay

  @Test
  void aRedeliveredFileIsARecordedNoOpWithADqItem() {
    IngestFixture fx = new IngestFixture(root);
    byte[] csv = twoRows();
    fx.drop("a.csv", csv, 2, "20");
    BatchReport first = only(fx.run());
    IngestLedger.Result stored = fx.ledgerStore.ingestResult(first.batchId()).orElseThrow();

    fx.drop("a.csv", csv, 2, "20");
    BatchReport second = only(fx.run());

    assertThat(second.batchId()).isEqualTo(first.batchId());
    assertThat(second.redelivery()).isTrue();
    assertThat(fx.ledgerStore.ingestResult(first.batchId()).orElseThrow().entries())
        .isEqualTo(stored.entries());
    assertThat(fx.ledgerStore.pendingOutbox(OutboxKind.DECIDE_BATCH, 10)).hasSize(1);
    List<Dq> redelivered = items(fx, Kind.FILE_REDELIVERED);
    assertThat(redelivered).hasSize(1);
    assertThat(redelivered.getFirst().scope()).isEqualTo(hexOfName("a.csv"));
    assertThat(fx.names(fx.inbox.inbox)).isEmpty();

    // a second redelivery is the same key and the same bytes: nothing new anywhere
    int objects = fx.keys("").size();
    int manifest = fx.manifest("").size();
    int retention = fx.retention("").size();
    fx.drop("a.csv", csv, 2, "20");
    assertThat(only(fx.run()).redelivery()).isTrue();
    assertThat(fx.keys("")).hasSize(objects);
    assertThat(fx.manifest("")).hasSize(manifest);
    assertThat(fx.retention("")).hasSize(retention);
    assertThat(items(fx, Kind.FILE_REDELIVERED)).hasSize(1);
  }

  @Test
  void aReplayAtALaterInstantAddsNoObjectNoManifestRowAndNoRetentionRow() {
    replayAtALaterInstant(new IngestFixture(root));
  }

  static void replayAtALaterInstant(IngestFixture fx) {
    byte[] csv = twoRows();
    fx.drop("a.csv", csv, 2, "20");
    BatchReport first = only(fx.run());
    EvidenceKey admitted = admittedKey(first.batchId()).evidenceKey();
    List<Integer> before = fx.countsOutsideDq();
    byte[] admittedBytes = fx.evidenceStore.get(admitted).orElseThrow().bytes();
    List<RetentionRow> retentionBefore =
        fx.retention("").stream()
            .filter(r -> !r.claim().artifact().path().startsWith("dq/"))
            .toList();

    fx.clock.advance(Duration.ofHours(1));
    fx.drop("a.csv", csv, 2, "20");
    BatchReport again = only(fx.run());

    assertThat(again.redelivery()).isTrue();
    assertThat(fx.countsOutsideDq()).isEqualTo(before);
    // the stored admitted object (the first instant) stays; its retention date does not move
    assertThat(fx.evidenceStore.get(admitted).orElseThrow().bytes()).isEqualTo(admittedBytes);
    assertThat(
            fx.retention("").stream()
                .filter(r -> !r.claim().artifact().path().startsWith("dq/"))
                .toList())
        .isEqualTo(retentionBefore);
    assertThat(
            fx.claims.stream()
                .filter(c -> c.artifact().equals(admitted))
                .map(c -> c.retainUntil())
                .distinct())
        .hasSize(1);
  }

  @Test
  void aCrashAfterTheLandingPutLeavesAnOrphanAndTheRedeliveryCompletes() {
    byte[] csv = twoRows();
    BatchId id = idOf(csv);

    // crash 1: the landing object is stored, its manifest row is not
    IngestFixture one = new IngestFixture(root.resolve("one"));
    one.drop("a.csv", csv, 2, "20");
    one.crashOnRecordObjects = true;
    assertThatThrownBy(one::run).isInstanceOf(SimulatedCrash.class);
    assertThat(one.keys("landing/")).containsExactly(landingKey(csv).path());
    assertThat(one.manifest("")).isEmpty();
    assertThat(one.keys("journal/")).isEmpty();
    assertThat(one.ledgerStore.ingestResult(id)).isEmpty();
    assertThat(one.names(one.inbox.inbox)).containsExactly("a.csv", "a.csv.done");
    BatchReport done = only(one.run());
    assertThat(done.verdict()).isEqualTo(Verdict.PROCESSED);
    assertThat(one.keys("landing/")).hasSize(1);
    assertThat(one.ledgerStore.manifestRow(landingKey(csv)).orElseThrow().entry().sha256())
        .isEqualTo(Sha256.of(csv));
    assertThat(one.retention(landingKey(csv).path())).hasSize(1);
    assertThat(one.keys("journal/")).hasSize(1);
    assertThat(one.ledgerStore.ingestResult(id)).isPresent();
    assertThat(one.names(one.inbox.processed)).containsExactly("a.csv", "a.csv.done");

    // crash 2: the landing object is complete, the admitted object is not stored
    IngestFixture two = new IngestFixture(root.resolve("two"));
    two.drop("a.csv", csv, 2, "20");
    two.crashOnPut = "journal/";
    assertThatThrownBy(two::run).isInstanceOf(SimulatedCrash.class);
    assertThat(two.keys("landing/")).hasSize(1);
    assertThat(two.manifest("landing/")).hasSize(1);
    assertThat(two.keys("journal/")).isEmpty();
    assertThat(two.ledgerStore.ingestResult(id)).isEmpty();
    assertThat(only(two.run()).verdict()).isEqualTo(Verdict.PROCESSED);
    assertThat(two.keys("landing/")).hasSize(1);
    assertThat(two.keys("journal/")).hasSize(1);
    assertThat(two.manifest("journal/")).hasSize(1);
    assertThat(two.ledgerStore.ingestResult(id)).isPresent();

    // crash 3: the admitted object is stored, its manifest row and retention claim are not
    IngestFixture three = new IngestFixture(root.resolve("three"));
    three.drop("a.csv", csv, 2, "20");
    three.crashOnNthRecordObjects = 2; // the first is the landing object
    assertThatThrownBy(three::run).isInstanceOf(SimulatedCrash.class);
    String admittedPath = admittedKey(id).evidenceKey().path();
    assertThat(three.keys("journal/")).containsExactly(admittedPath);
    assertThat(three.manifest("journal/")).isEmpty();
    assertThat(three.retention(admittedPath)).isEmpty();
    assertThat(three.manifest("landing/")).hasSize(1);
    assertThat(three.ledgerStore.ingestResult(id)).isEmpty();
    assertThat(three.names(three.inbox.inbox)).containsExactly("a.csv", "a.csv.done");
    byte[] admittedBytes =
        three.evidenceStore.get(admittedKey(id).evidenceKey()).orElseThrow().bytes();
    three.clock.advance(Duration.ofHours(3));
    assertThat(only(three.run()).verdict()).isEqualTo(Verdict.PROCESSED);
    // the redelivery completes the row and the claim from the stored object, and puts no new one
    assertThat(three.keys("journal/")).containsExactly(admittedPath);
    assertThat(three.evidenceStore.get(admittedKey(id).evidenceKey()).orElseThrow().bytes())
        .isEqualTo(admittedBytes);
    assertThat(three.manifest("journal/")).hasSize(1);
    assertThat(three.retention(admittedPath)).hasSize(1);
    assertThat(three.ledgerStore.ingestResult(id)).isPresent();
    assertThat(three.names(three.inbox.processed)).containsExactly("a.csv", "a.csv.done");
  }

  // ------------------------------------------------------------------ whole-file refusals

  @Test
  void aNonSyntheticMarkerRejectsTheFileAndLandsNothing() {
    IngestFixture fx = new IngestFixture(root);
    fx.dropSample("trades-day5-notsynth.csv");

    BatchReport batch = only(fx.run());

    assertThat(batch.verdict()).isEqualTo(Verdict.REJECTED);
    assertThat(batch.ingested()).isZero();
    assertThat(fx.keys("landing/")).isEmpty();
    assertThat(fx.keys("journal/")).isEmpty();
    // the only object, manifest row and retention row are those of the one dq item
    assertThat(fx.keys("")).hasSize(1).allMatch(k -> k.startsWith("dq/"));
    assertThat(fx.manifest("")).hasSize(1);
    assertThat(fx.manifest("").getFirst().entry().key().path()).startsWith("dq/");
    assertThat(fx.retention("")).hasSize(1);
    List<Dq> rejected = items(fx, Kind.BATCH_REJECTED);
    assertThat(rejected).hasSize(1);
    assertThat(rejected.getFirst().code()).isEqualTo("NOT_SYNTHETIC");
    String stored =
        new String(
            fx.evidenceStore.get(new EvidenceKey(fx.keys("").getFirst())).orElseThrow().bytes(),
            UTF_8);
    assertThat(stored)
        .doesNotContain("trades-day5")
        .doesNotContain("T-013")
        .doesNotContain("EX-013");
    // an empty batch with the checkpoint is committed so that the file is not read again
    assertThat(fx.ledgerStore.ingestResult(batch.batchId()).orElseThrow().entries()).isEmpty();
    assertThat(fx.ledgerStore.latestCheckpoint(IngestFixture.SOURCE, "trades")).isPresent();
    assertThat(fx.names(fx.inbox.rejected))
        .containsExactly("trades-day5-notsynth.csv", "trades-day5-notsynth.csv.done");
    assertThat(fx.names(fx.inbox.inbox)).isEmpty();

    // the same file again is a recorded no-op: nothing is landed, the file is refused again
    fx.dropSample("trades-day5-notsynth.csv");
    BatchReport again = only(fx.run());
    assertThat(again.redelivery()).isTrue();
    assertThat(again.batchId()).isEqualTo(batch.batchId());
    assertThat(fx.keys("landing/")).isEmpty();
    assertThat(fx.keys("journal/")).isEmpty();
    assertThat(fx.names(fx.inbox.inbox)).isEmpty();
    assertThat(fx.names(fx.inbox.rejected)).hasSize(2);
  }

  @Test
  void aManifestThatDoesNotMatchTheBytesRejectsTheFileBeforeLanding() {
    IngestFixture fx = new IngestFixture(root);
    fx.drop("a.csv", twoRows(), 2, "20");
    Sha256 wrong = Sha256.ofUtf8("not the bytes");
    ReadRequest request = new ReadRequest(Optional.empty(), new ReadLimits(100, 1_000_000L));

    IngestReport report;
    try (BatchStream real = fx.connector.read(request)) {
      BatchStream lying =
          new BatchStream() {
            @Override
            public List<Batch> next(int credits) {
              return real.next(credits).stream()
                  .map(
                      b ->
                          new Batch(
                              b.stream(),
                              b.seq(),
                              new BatchPayload(
                                  b.payload().bytes(),
                                  b.payload().mediaType(),
                                  new Manifest(
                                      b.payload().manifest().recordCount(),
                                      wrong,
                                      b.payload().manifest().controlTotals())),
                              b.sourceCoordinates(),
                              b.checkpoint()))
                  .toList();
            }

            @Override
            public void acknowledge(Checkpoint checkpoint) {
              real.acknowledge(checkpoint);
            }

            @Override
            public boolean exhausted() {
              return real.exhausted();
            }

            @Override
            public void close() {
              // the try block closes the real stream
            }
          };
      report = fx.ingest.ingest(lying, fx.connector, 10);
    }

    assertThat(only(report).verdict()).isEqualTo(Verdict.REJECTED);
    assertThat(items(fx, Kind.BATCH_REJECTED))
        .extracting(Dq::code)
        .containsExactly("MANIFEST_MISMATCH");
    assertThat(fx.keys("landing/")).isEmpty();
    assertThat(fx.keys("journal/")).isEmpty();
    assertThat(fx.names(fx.inbox.rejected)).containsExactly("a.csv", "a.csv.done");
  }

  @Test
  void theLimitsOfTheCsvReaderRefuseTheFileWithTheirCoordinates() {
    IngestFixture fx = new IngestFixture(root);
    // 10,001 data rows: one more than the limit; the cells are one character
    StringBuilder many = new StringBuilder(InboxFixture.HEADER);
    String shortRow = String.join(",", Collections.nCopies(18, "x")) + "\n";
    for (int i = 0; i < 10_001; i++) {
      many.append(shortRow);
    }
    fx.drop("many.csv", many.toString().getBytes(UTF_8), 10_001, "0");
    // a cell of 257 characters: one more than the limit of a key text
    Map<String, String> wide = IngestFixture.cells("T-1", 1, "EX-1", "10");
    wide.put("account", "A".repeat(257));
    fx.drop("wide.csv", IngestFixture.csv(IngestFixture.line(wide)), 1, "10");

    IngestReport report = fx.run();

    assertThat(report.batches()).hasSize(2).allMatch(b -> b.verdict() == Verdict.REJECTED);
    assertThat(items(fx, Kind.BATCH_REJECTED))
        .extracting(Dq::code, Dq::line)
        .containsExactlyInAnyOrder(tuple("TOO_MANY_ROWS", 10_002), tuple("TOO_LONG_CELL", 2));
    assertThat(fx.names(fx.inbox.rejected)).hasSize(4);
  }

  @Test
  void aMarkerWhoseDataClassIsNotExactlySyntheticRejectsTheFile() {
    IngestFixture fx = new IngestFixture(root);
    List<String> members =
        List.of(
            "\"data_class\":\"synthetic\"",
            "\"data_class\":\"SYNTHETIC \"",
            "\"data_class\":\"REAL\"",
            "\"data_class\":true",
            "\"data_class\":1",
            "");
    for (int i = 0; i < members.size(); i++) {
      byte[] csv = IngestFixture.csv(IngestFixture.row("T-" + i, 1, "EX-" + i, "10"));
      fx.dropWithDataClass("f" + i + ".csv", csv, 1, members.get(i));
    }

    IngestReport report = fx.run();

    assertThat(report.batches()).hasSize(members.size());
    assertThat(report.batches()).allMatch(b -> b.verdict() == Verdict.REJECTED);
    assertThat(items(fx, Kind.BATCH_REJECTED))
        .hasSize(members.size())
        .allMatch(d -> d.code().equals("NOT_SYNTHETIC"));
    assertThat(fx.keys("landing/")).isEmpty();
    assertThat(fx.keys("journal/")).isEmpty();
  }

  @Test
  void aCorrectedMarkerOnTheSameBytesIsIngestedNormally() {
    IngestFixture fx = new IngestFixture(root);
    byte[] csv = twoRows();
    fx.dropWithDataClass("a.csv", csv, 2, "");
    BatchReport rejected = only(fx.run());
    assertThat(rejected.verdict()).isEqualTo(Verdict.REJECTED);

    // the producer corrects the marker and drops the same bytes again: not a redelivery
    fx.drop("a.csv", csv, 2, "20");
    BatchReport good = only(fx.run());

    assertThat(good.verdict()).isEqualTo(Verdict.PROCESSED);
    assertThat(good.redelivery()).isFalse();
    assertThat(good.ingested()).isEqualTo(2);
    assertThat(good.batchId()).isEqualTo(idOf(csv));
    assertThat(rejected.batchId()).isNotEqualTo(good.batchId());
  }

  @Test
  void anOversizedFileGivesOneBatchRejectedItemAndStaysInTheInbox() {
    IngestFixture fx = new IngestFixture(root, 1024L);
    byte[] big = Arrays.copyOf(twoRows(), 2048);
    Arrays.fill(big, twoRows().length, big.length, (byte) ' ');
    fx.drop("big.csv", big, 2, "20");

    IngestReport report = fx.run();

    assertThat(report.batches()).isEmpty();
    List<Dq> rejected = items(fx, Kind.BATCH_REJECTED);
    assertThat(rejected).hasSize(1);
    assertThat(rejected.getFirst().code()).isEqualTo("OVERSIZED");
    assertThat(rejected.getFirst().scope()).isEqualTo(hexOfName("big.csv"));
    assertThat(fx.names(fx.inbox.inbox)).containsExactly("big.csv", "big.csv.done");
    assertThat(fx.keys("landing/")).isEmpty();
    // a replay writes nothing new
    int objects = fx.keys("").size();
    int manifest = fx.manifest("").size();
    fx.run();
    assertThat(fx.keys("")).hasSize(objects);
    assertThat(fx.manifest("")).hasSize(manifest);
    assertThat(items(fx, Kind.BATCH_REJECTED)).hasSize(1);
  }

  @Test
  void aBrokenHeaderRejectsAndNamesTheColumn() {
    brokenHeader(new IngestFixture(root));
  }

  static void brokenHeader(IngestFixture fx) {
    fx.dropSample("trades-day4-broken.csv");

    BatchReport batch = only(fx.run());

    assertThat(batch.verdict()).isEqualTo(Verdict.REJECTED);
    assertThat(batch.ingested()).isZero();
    List<Dq> rejected = items(fx, Kind.BATCH_REJECTED);
    assertThat(rejected).hasSize(1);
    assertThat(rejected.getFirst().code()).isEqualTo("MISSING_COLUMN");
    assertThat(rejected.getFirst().column()).isEqualTo("price");
    assertThat(rejected.getFirst().rule()).isEqualTo("RELEASE_NEEDS_NEW_BYTES");
    // landed (it was a file), journalled, committed empty, moved to rejected/
    byte[] csv = IngestFixture.sample("trades-day4-broken.csv");
    assertThat(fx.evidenceStore.get(landingKey(csv))).isPresent();
    assertThat(fx.evidenceStore.get(admittedKey(batch.batchId()).evidenceKey())).isPresent();
    assertThat(fx.ledgerStore.ingestResult(batch.batchId()).orElseThrow().entries()).isEmpty();
    assertThat(fx.names(fx.inbox.rejected))
        .containsExactly("trades-day4-broken.csv", "trades-day4-broken.csv.done");
    assertThat(fx.names(fx.inbox.processed)).isEmpty();
  }

  @Test
  void aCsvErrorRejectsTheFileWithItsCoordinates() {
    IngestFixture fx = new IngestFixture(root);
    String ragged = InboxFixture.HEADER + IngestFixture.row("T-1", 1, "EX-1", "10") + "T-2,1,NEW\n";
    fx.drop("a.csv", ragged.getBytes(UTF_8), 2, "10");

    BatchReport batch = only(fx.run());

    assertThat(batch.verdict()).isEqualTo(Verdict.REJECTED);
    Dq item = items(fx, Kind.BATCH_REJECTED).getFirst();
    assertThat(item.code()).isEqualTo("RAGGED_ROW");
    assertThat(item.line()).isEqualTo(3);
    assertThat(item.column()).isEqualTo("3");
    assertThat(fx.names(fx.inbox.rejected)).containsExactly("a.csv", "a.csv.done");
  }

  @Test
  void aControlTotalMismatchQuarantinesTheBatch() {
    IngestFixture fx = new IngestFixture(root);
    fx.dropSample("trades-day3-badtotals.csv");

    BatchReport batch = only(fx.run());

    assertThat(batch.verdict()).isEqualTo(Verdict.QUARANTINED);
    assertThat(batch.declaredRecords()).isEqualTo(3);
    assertThat(batch.ingested()).isZero();
    assertThat(batch.newVersions()).isEmpty();
    Dq held = items(fx, Kind.BATCH_QUARANTINED).getFirst();
    assertThat(held.code()).isEqualTo("CONTROL_TOTAL");
    assertThat(held.rule()).isEqualTo("RELEASE_NEEDS_NEW_BYTES");
    assertThat(held.column()).isEqualTo("records");
    // landed and committed empty: no record of a held batch reaches the ledger
    assertThat(fx.keys("landing/")).hasSize(1);
    assertThat(fx.ledgerStore.ingestResult(batch.batchId()).orElseThrow().entries()).isEmpty();
    assertThat(fx.names(fx.inbox.quarantine))
        .containsExactly("trades-day3-badtotals.csv", "trades-day3-badtotals.csv.done");
  }

  @Test
  void aSumThatDoesNotMatchQuarantinesTheBatchAndASameValueInOtherScaleDoesNot() {
    IngestFixture fx = new IngestFixture(root);
    byte[] csv = twoRows();
    fx.drop("wrong.csv", csv, 2, "25");
    BatchReport wrong = only(fx.run());
    assertThat(wrong.verdict()).isEqualTo(Verdict.QUARANTINED);
    assertThat(items(fx, Kind.BATCH_QUARANTINED).getFirst().column()).isEqualTo("sum_quantity");

    // 20.000 equals 20: compared as numbers, not as text
    byte[] other =
        IngestFixture.csv(
            IngestFixture.row("T-3", 1, "EX-3", "10"), IngestFixture.row("T-4", 1, "EX-4", "10"));
    fx.drop("scale.csv", other, 2, "20.000");
    BatchReport scale = only(fx.run());
    assertThat(scale.verdict()).isEqualTo(Verdict.PROCESSED);

    // a marker without a sum states only the count
    byte[] third = IngestFixture.csv(IngestFixture.row("T-5", 1, "EX-5", "10"));
    fx.drop("nosum.csv", third, 1, null);
    assertThat(only(fx.run()).verdict()).isEqualTo(Verdict.PROCESSED);
  }

  // ------------------------------------------------------------------ records

  @Test
  void aSumIgnoresWhyARowWasQuarantined() {
    IngestFixture fx = new IngestFixture(root);
    Map<String, String> bad = IngestFixture.cells("T-2", 1, "EX-2", "20");
    bad.put("client_type", "BOGUS");
    byte[] csv =
        IngestFixture.csv(IngestFixture.row("T-1", 1, "EX-1", "10"), IngestFixture.line(bad));
    fx.drop("a.csv", csv, 2, "30");

    BatchReport batch = only(fx.run());

    // the quantity of the quarantined row still counts: the batch is not held
    assertThat(batch.verdict()).isEqualTo(Verdict.PROCESSED);
    assertThat(batch.ingested()).isEqualTo(1);
    assertThat(batch.quarantined()).isEqualTo(1);
    Dq item = items(fx, Kind.RECORD_QUARANTINED).getFirst();
    assertThat(item.line()).isEqualTo(3);
    assertThat(item.column()).isEqualTo("client_type");
    assertThat(item.code()).isEqualTo("ENUM");
  }

  @Test
  void aBadRecordQuarantinesOnlyThatRecord() {
    IngestFixture fx = new IngestFixture(root);
    byte[] csv =
        IngestFixture.csv(
            IngestFixture.row("T-1", 1, "EX-1", "10"),
            IngestFixture.row("T-2", 1, "EX-2", "abc"),
            IngestFixture.row("T-3", 1, "EX-3", "10"));
    fx.drop("a.csv", csv, 3, "20");

    BatchReport batch = only(fx.run());

    assertThat(batch.verdict()).isEqualTo(Verdict.PROCESSED);
    assertThat(batch.ingested()).isEqualTo(2);
    assertThat(batch.quarantined()).isEqualTo(1);
    assertThat(batch.newVersions()).hasSize(2);
    Dq item = items(fx, Kind.RECORD_QUARANTINED).getFirst();
    assertThat(item.line()).isEqualTo(3);
    assertThat(item.column()).isEqualTo("quantity");
    assertThat(item.code()).isEqualTo("DECIMAL");
    Dq summary = items(fx, Kind.BATCH_SUMMARY).getFirst();
    assertThat(summary.code()).isEqualTo("UNPROVEN_COMPLETENESS");
    assertThat(summary.rule()).isEqualTo("control_total_records=3;ingested=2;quarantined=1");
    assertThat(summary.line()).isZero();
    assertThat(fx.names(fx.inbox.processed)).containsExactly("a.csv", "a.csv.done");
  }

  @Test
  void aControlCharacterQuarantinesTheRecordNotTheBatch() {
    IngestFixture fx = new IngestFixture(root);
    byte[] csv =
        IngestFixture.csv(
            IngestFixture.row("T-1", 1, "EX-1", "10"),
            IngestFixture.row("T-2", 1, "EX-\u0001", "10"));
    fx.drop("a.csv", csv, 2, "20");

    BatchReport batch = only(fx.run());

    assertThat(batch.verdict()).isEqualTo(Verdict.PROCESSED);
    assertThat(batch.ingested()).isEqualTo(1);
    Dq item = items(fx, Kind.RECORD_QUARANTINED).getFirst();
    assertThat(item.code()).isEqualTo("KEY_TEXT");
    assertThat(item.column()).isEqualTo("exec_id");
    assertThat(
            new String(
                fx.evidenceStore
                    .get(new EvidenceKey("dq/" + item.scope() + "/RECORD_QUARANTINED-3-KEY_TEXT"))
                    .orElseThrow()
                    .bytes(),
                UTF_8))
        .doesNotContain("EX-");
  }

  @Test
  void aDuplicateKeyInOneFileQuarantinesTheLaterRowOnly() {
    duplicateKey(new IngestFixture(root));
  }

  static void duplicateKey(IngestFixture fx) {
    byte[] csv =
        IngestFixture.csv(
            IngestFixture.row("T-1", 1, "EX-1", "10"),
            IngestFixture.row("T-2", 1, "EX-2", "10"),
            IngestFixture.row("T-1", 1, "EX-3", "10"));
    fx.drop("a.csv", csv, 3, "30");

    BatchReport batch = only(fx.run());

    assertThat(batch.ingested()).isEqualTo(2);
    assertThat(batch.quarantined()).isEqualTo(1);
    Dq item = items(fx, Kind.RECORD_QUARANTINED).getFirst();
    assertThat(item.code()).isEqualTo("DUPLICATE_IN_FILE");
    assertThat(item.line()).isEqualTo(4);
    // the ledger never sees a duplicate key in one commit
    IngestLedger.Commit commit = fx.commits.getFirst();
    assertThat(commit.entries()).extracting(IngestLedger.Entry::key).doesNotHaveDuplicates();
    assertThat(commit.entries()).hasSize(2);
    // the earlier row is the one that is ingested
    assertThat(batch.newVersions())
        .extracting(a -> a.record().execution().venueExecutionId())
        .containsExactly("EX-1", "EX-2");
  }

  @Test
  void aMapperDefectRejectsTheFileAndTheNextFileIsStillRead() {
    mapperDefect(root, FileIngest.Flaw.NONE);
  }

  static void mapperDefect(Path root, FileIngest.Flaw flaw) {
    Mapper real = new Mapper(IngestFixture.sampleMapping());
    FileIngest.RowMapper poisoned =
        (line, header, cells) -> {
          if (cells.getFirst().equals("T-BAD")) {
            throw new IllegalStateException("secret value 4711");
          }
          return real.map(line, header, cells);
        };
    IngestFixture fx = new IngestFixture(root, flaw, 1_048_576L, poisoned);
    fx.drop("a.csv", IngestFixture.csv(IngestFixture.row("T-BAD", 1, "EX-9", "10")), 1, "10");
    fx.drop("b.csv", IngestFixture.csv(IngestFixture.row("T-1", 1, "EX-1", "10")), 1, "10");

    IngestReport report = fx.run();

    assertThat(report.batches()).hasSize(2);
    assertThat(report.batches().get(0).verdict()).isEqualTo(Verdict.REJECTED);
    assertThat(report.batches().get(1).verdict()).isEqualTo(Verdict.PROCESSED);
    assertThat(report.batches().get(1).ingested()).isEqualTo(1);
    // the report names the exception class and nothing of its message
    assertThat(report.defects()).containsExactly("java.lang.IllegalStateException");
    assertThat(report.toString()).doesNotContain("secret").doesNotContain("4711");
    Dq item = items(fx, Kind.BATCH_REJECTED).getFirst();
    assertThat(item.code()).isEqualTo("INGEST_DEFECT");
    assertThat(item.toString()).doesNotContain("secret");
    assertThat(fx.names(fx.inbox.rejected)).containsExactly("a.csv", "a.csv.done");
    assertThat(fx.names(fx.inbox.processed)).containsExactly("b.csv", "b.csv.done");
  }

  // ------------------------------------------------------------------ what the ledger says

  @Test
  void aLowerVersionAfterAHigherOneIsStaleAndJournalled() {
    IngestFixture fx = new IngestFixture(root);
    fx.drop("a.csv", IngestFixture.csv(IngestFixture.row("T-1", 2, "EX-1", "10")), 1, "10");
    assertThat(only(fx.run()).newVersions()).hasSize(1);

    fx.drop("b.csv", IngestFixture.csv(IngestFixture.row("T-1", 1, "EX-1", "10")), 1, "10");
    BatchReport stale = only(fx.run());

    assertThat(stale.newVersions()).isEmpty();
    assertThat(stale.verdict()).isEqualTo(Verdict.PROCESSED);
    Dq item = items(fx, Kind.STALE_VERSION).getFirst();
    assertThat(item.line()).isEqualTo(2);
    assertThat(item.code()).isEqualTo("STALE");
    assertThat(item.scope()).isEqualTo(stale.batchId().landingDigest().hex());
    // the ledger holds the verdict of the commit; the stale version is not stored
    assertThat(fx.ledgerStore.ingestResult(stale.batchId()).orElseThrow().entries())
        .extracting(IngestLedger.EntryResult::disposition)
        .containsExactly(Disposition.STALE);
    assertThat(fx.ledgerStore.findIngest(new IngestKey(IngestFixture.SOURCE, "T-1", 1))).isEmpty();
  }

  @Test
  void aSameVersionWithOtherContentWritesAVersionConflictItem() {
    IngestFixture fx = new IngestFixture(root);
    fx.drop("a.csv", IngestFixture.csv(IngestFixture.row("T-1", 1, "EX-1", "10")), 1, "10");
    fx.run();

    fx.drop("b.csv", IngestFixture.csv(IngestFixture.row("T-1", 1, "EX-1", "11")), 1, "11");
    BatchReport conflict = only(fx.run());

    assertThat(conflict.newVersions()).isEmpty();
    Dq item = items(fx, Kind.VERSION_CONFLICT).getFirst();
    assertThat(item.line()).isEqualTo(2);
    assertThat(item.code()).isEqualTo("CONFLICT_SAME_VERSION");
  }

  @Test
  void aSameEventFromAnotherRecordIdWithOtherContentIsAVersionConflictToo() {
    IngestFixture fx = new IngestFixture(root);
    fx.drop("a.csv", IngestFixture.csv(IngestFixture.row("T-1", 1, "EX-1", "10")), 1, "10");
    fx.run();

    // the same execution and version under another record id, with other content
    fx.drop("b.csv", IngestFixture.csv(IngestFixture.row("T-9", 1, "EX-1", "12")), 1, "12");
    BatchReport conflict = only(fx.run());

    assertThat(conflict.newVersions()).isEmpty();
    assertThat(items(fx, Kind.VERSION_CONFLICT)).hasSize(1);
  }

  // ------------------------------------------------------------------ evidence and values

  @Test
  void everyLandedObjectHasAManifestRowAndARetentionRow() {
    IngestFixture fx = new IngestFixture(root);
    fx.dropSample("trades-day1.csv");
    fx.dropSample("trades-day2.csv");
    fx.dropSample("trades-day3-badtotals.csv");
    fx.dropSample("trades-day4-broken.csv");
    fx.dropSample("trades-day5-notsynth.csv");
    fx.dropSampleAs("trades-day1.csv", "trades-day1-copy.csv");
    fx.drop("wait.csv", twoRows(), 2, "20");
    deleteFile(fx.inbox.inbox.resolve("wait.csv.done"));
    fx.run();
    fx.clock.advance(Duration.ofHours(2));
    fx.run();

    List<String> keys = fx.keys("");
    assertThat(keys).isNotEmpty();
    for (String path : keys) {
      EvidenceKey key = new EvidenceKey(path);
      StoredObject stored = fx.evidenceStore.get(key).orElseThrow();
      assertThat(fx.ledgerStore.manifestRow(key)).as("manifest row of " + path).isPresent();
      assertThat(fx.ledgerStore.manifestRow(key).orElseThrow().entry().sha256())
          .as("manifest hash of " + path)
          .isEqualTo(stored.sha256());
      assertThat(fx.retention(path)).as("retention row of " + path).isNotEmpty();
      RetentionRow row = fx.retention(path).getFirst();
      assertThat(row.claim().artifact()).isEqualTo(key);
      assertThat(row.claim().retainUntil()).contains(stored.retention().retainUntil());
    }
    // the classes by namespace
    assertThat(fx.manifest("landing/"))
        .allMatch(r -> r.entry().objectClass() == ObjectClass.LANDING_BATCH);
    assertThat(fx.manifest("journal/"))
        .allMatch(r -> r.entry().objectClass() == ObjectClass.JOURNAL_A);
    assertThat(fx.manifest("dq/")).allMatch(r -> r.entry().objectClass() == ObjectClass.OTHER);
  }

  private static void deleteFile(Path path) {
    try {
      Files.delete(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void noDqItemHoldsAValue() throws IOException {
    IngestFixture fx = new IngestFixture(root);
    List<String> files =
        List.of(
            "trades-day1.csv",
            "trades-day2.csv",
            "trades-day3-badtotals.csv",
            "trades-day4-broken.csv",
            "trades-day5-notsynth.csv");
    Set<String> values = new TreeSet<>();
    for (String file : files) {
      fx.dropSample(file);
      addValuesOf(values, file);
    }
    fx.drop(
        "odd_name_1.csv",
        IngestFixture.csv(IngestFixture.row("T-ODD", 1, "EX-ODD", "abc")),
        1,
        "0");
    values.add("T-ODD");
    values.add("EX-ODD");
    fx.run();

    assertThat(fx.dq.list()).isNotEmpty();
    for (String path : fx.keys("dq/")) {
      String text =
          new String(fx.evidenceStore.get(new EvidenceKey(path)).orElseThrow().bytes(), UTF_8);
      for (String value : values) {
        assertThat(text)
            .as(path + " must not hold " + value.length() + " characters of a value")
            .doesNotContain(value);
        assertThat(path).doesNotContain(value);
      }
      // fixed key shape: dq/<scope>/<KIND>-<line>-<code>
      assertThat(path).matches("dq/[0-9a-f]{64}/[A-Z_]+-[0-9]+-[A-Za-z0-9_.-]+");
    }
    for (Dq item : fx.dq.list()) {
      assertThat(item.toString()).doesNotContain("T-0").doesNotContain("EX-0");
    }
  }

  /** Adds the file name (with and without extension) and every data cell of a sample. */
  private static void addValuesOf(Set<String> values, String sample) {
    String[] lines = new String(IngestFixture.sample(sample), UTF_8).split("\n");
    for (int i = 1; i < lines.length; i++) {
      for (String cell : lines[i].split(",", -1)) {
        if (cell.length() >= 4) {
          values.add(cell);
        }
      }
    }
    addNames(values, sample);
  }

  private static void addNames(Set<String> values, String file) {
    values.add(file);
    values.add(file.substring(0, file.indexOf('.')));
  }

  @Test
  void noDqItemOfAnyKindTheStageWritesHoldsAValue() {
    // a state in which the stage has written every kind of item it can write, the ones about a
    // file name (redelivered, completion pending, oversized) and the ones about a record included
    IngestFixture fx = new IngestFixture(root, 2048L);
    Set<String> values = new TreeSet<>();

    fx.dropSample("trades-day1.csv");
    addValuesOf(values, "trades-day1.csv");
    fx.run();
    fx.dropSample("trades-day1.csv"); // the redelivery
    fx.run();
    for (String file :
        List.of(
            "trades-day2.csv",
            "trades-day3-badtotals.csv",
            "trades-day4-broken.csv",
            "trades-day5-notsynth.csv")) {
      fx.dropSample(file);
      addValuesOf(values, file);
    }
    fx.run();

    // a bad cell, a stale version and a conflicting one, and an event of another source
    fx.drop(
        "garbled-quantity.csv",
        IngestFixture.csv(IngestFixture.row("T-GARB", 1, "EX-GARB", "qty-garbled")),
        1,
        "0");
    fx.run();
    fx.drop(
        "stale-first.csv",
        IngestFixture.csv(IngestFixture.row("T-STALE", 2, "EX-STALE", "10")),
        1,
        "10");
    fx.run();
    fx.drop(
        "stale-second.csv",
        IngestFixture.csv(IngestFixture.row("T-STALE", 1, "EX-STALE", "10")),
        1,
        "10");
    fx.run();
    fx.drop(
        "conflict-first.csv",
        IngestFixture.csv(IngestFixture.row("T-CONF", 1, "EX-CONF", "10")),
        1,
        "10");
    fx.run();
    fx.drop(
        "conflict-second.csv",
        IngestFixture.csv(IngestFixture.row("T-CONF", 1, "EX-CONF", "11")),
        1,
        "11");
    fx.run();
    fx.drop(
        "othersource.csv",
        IngestFixture.csv(IngestFixture.row("T-OTHER", 1, "EX-CONF", "10")),
        1,
        "10");
    fx.run(fx.otherSource("other-csv"));
    for (String name :
        List.of(
            "garbled-quantity.csv",
            "stale-first.csv",
            "stale-second.csv",
            "conflict-first.csv",
            "conflict-second.csv",
            "othersource.csv",
            "waiting-file.csv",
            "overlarge-file.csv")) {
      addNames(values, name);
    }
    values.addAll(
        List.of(
            "T-GARB",
            "EX-GARB",
            "qty-garbled",
            "T-STALE",
            "EX-STALE",
            "T-CONF",
            "EX-CONF",
            "T-OTHER"));

    // a file that waits past the deadline and a file over the cap, both in the inbox for good
    fx.inbox.write("waiting-file.csv", twoRows());
    byte[] big = Arrays.copyOf(twoRows(), 4096);
    Arrays.fill(big, twoRows().length, big.length, (byte) ' ');
    fx.drop("overlarge-file.csv", big, 2, "20");
    fx.run();
    fx.clock.advance(Duration.ofHours(2));
    fx.run();

    assertThat(fx.dq.list())
        .extracting(Dq::kind)
        .containsAll(
            List.of(
                Kind.BATCH_REJECTED,
                Kind.BATCH_QUARANTINED,
                Kind.BATCH_SUMMARY,
                Kind.COMPLETION_PENDING,
                Kind.RECORD_QUARANTINED,
                Kind.FILE_REDELIVERED,
                Kind.STALE_VERSION,
                Kind.VERSION_CONFLICT,
                Kind.SOURCE_CONFLICT));
    assertThat(fx.dq.list())
        .extracting(Dq::code)
        .contains("OVERSIZED", "NO_MARKER", "REDELIVERED", "STALE", "SOURCE_CONFLICT");
    assertThat(items(fx, Kind.FILE_REDELIVERED).getFirst().scope())
        .isEqualTo(hexOfName("trades-day1.csv"));
    assertThat(items(fx, Kind.COMPLETION_PENDING).getFirst().scope())
        .isEqualTo(hexOfName("waiting-file.csv"));

    List<String> paths = fx.keys("dq/");
    assertThat(paths).hasSize(fx.dq.list().size());
    for (String path : paths) {
      String text =
          new String(fx.evidenceStore.get(new EvidenceKey(path)).orElseThrow().bytes(), UTF_8);
      for (String value : values) {
        assertThat(text).as(path + " must not hold a value").doesNotContain(value);
        assertThat(path).as(path + " must not hold a value").doesNotContain(value);
      }
      assertThat(path).matches("dq/[0-9a-f]{64}/[A-Z_]+-[0-9]+-[A-Za-z0-9_.-]+");
    }
    for (Dq item : fx.dq.list()) {
      for (String value : values) {
        assertThat(item.toString()).doesNotContain(value);
      }
    }
  }

  // ------------------------------------------------------------------ waiting files

  @Test
  void aWaitingFileGivesOneCompletionPendingItemAfterTheDeadline() {
    IngestFixture fx = new IngestFixture(root);
    byte[] csv = twoRows();
    fx.inbox.write("late.csv", csv);

    fx.run();
    assertThat(items(fx, Kind.COMPLETION_PENDING)).isEmpty();
    fx.clock.advance(Duration.ofMinutes(59));
    fx.run();
    assertThat(items(fx, Kind.COMPLETION_PENDING)).isEmpty();
    // exactly at the deadline is not past it
    fx.clock.advance(Duration.ofMinutes(1));
    fx.run();
    assertThat(items(fx, Kind.COMPLETION_PENDING)).isEmpty();
    fx.clock.advance(Duration.ofMinutes(1));
    fx.run();

    List<Dq> pending = items(fx, Kind.COMPLETION_PENDING);
    assertThat(pending).hasSize(1);
    assertThat(pending.getFirst().scope()).isEqualTo(hexOfName("late.csv"));
    assertThat(pending.getFirst().code()).isEqualTo("NO_MARKER");
    // once: later ticks write nothing new
    int objects = fx.keys("").size();
    fx.clock.advance(Duration.ofHours(5));
    fx.run();
    assertThat(fx.keys("")).hasSize(objects);
    assertThat(items(fx, Kind.COMPLETION_PENDING)).hasSize(1);

    // the marker arrives: the file is ingested as any other
    fx.inbox.write(
        "late.csv.done", IngestFixture.markerFor("late.csv", csv, 2, "20", true).getBytes(UTF_8));
    assertThat(only(fx.run()).verdict()).isEqualTo(Verdict.PROCESSED);
  }

  // ------------------------------------------------------------------ the samples

  @Test
  void theFiveSamplesGiveTheIngestOutcomesOfTheScenarioTable() {
    IngestFixture fx = new IngestFixture(root);

    fx.dropSample("trades-day1.csv");
    BatchReport day1 = only(fx.run());
    assertThat(day1.verdict()).isEqualTo(Verdict.PROCESSED);
    assertThat(day1.declaredRecords()).isEqualTo(8);
    assertThat(day1.ingested()).isEqualTo(8);
    assertThat(day1.quarantined()).isZero();
    assertThat(day1.newVersions()).hasSize(8);
    assertThat(items(fx, Kind.RECORD_QUARANTINED)).isEmpty();
    assertThat(items(fx, Kind.BATCH_SUMMARY).getFirst().rule())
        .isEqualTo("control_total_records=8;ingested=8;quarantined=0");

    fx.dropSample("trades-day2.csv");
    BatchReport day2 = only(fx.run());
    assertThat(day2.declaredRecords()).isEqualTo(4);
    assertThat(day2.ingested()).isEqualTo(3);
    assertThat(day2.quarantined()).isEqualTo(1);
    assertThat(day2.newVersions())
        .extracting(a -> a.key().sourceRecordId())
        .containsExactly("T-001", "T-003", "T-010");
    assertThat(day2.newVersions())
        .extracting(a -> a.key().sourceVersion())
        .containsExactly(2L, 2L, 1L);
    Dq t009 = items(fx, Kind.RECORD_QUARANTINED).getFirst();
    assertThat(t009.line()).isEqualTo(4);
    assertThat(t009.column()).isEqualTo("quantity");

    fx.dropSample("trades-day1.csv");
    BatchReport again = only(fx.run());
    assertThat(again.redelivery()).isTrue();
    assertThat(again.batchId()).isEqualTo(day1.batchId());
    assertThat(items(fx, Kind.FILE_REDELIVERED)).hasSize(1);

    fx.dropSample("trades-day3-badtotals.csv");
    assertThat(only(fx.run()).verdict()).isEqualTo(Verdict.QUARANTINED);
    fx.dropSample("trades-day4-broken.csv");
    assertThat(only(fx.run()).verdict()).isEqualTo(Verdict.REJECTED);
    fx.dropSample("trades-day5-notsynth.csv");
    assertThat(only(fx.run()).verdict()).isEqualTo(Verdict.REJECTED);

    assertThat(fx.names(fx.inbox.inbox)).isEmpty();
    assertThat(fx.names(fx.inbox.rejected)).hasSize(4);
    assertThat(fx.names(fx.inbox.quarantine)).hasSize(2);
    // one PENDING DECIDE_BATCH item per distinct batch, rejected and held ones included; the
    // redelivery of day 1 adds none
    List<OutboxItem> pending = fx.ledgerStore.pendingOutbox(OutboxKind.DECIDE_BATCH, 100);
    assertThat(pending).hasSize(5).allMatch(i -> i.state() == OutboxState.PENDING);
  }

  // ------------------------------------------------------------------ parseLanded

  @Test
  void parseLandedGivesTheSameRecordsAsTheIngestAndTheirLines() {
    IngestFixture fx = new IngestFixture(root);
    byte[] csv = IngestFixture.sample("trades-day2.csv");

    FileIngest.ParsedBatch parsed = fx.ingest.parseLanded(csv);

    assertThat(parsed.refusal()).isEmpty();
    assertThat(parsed.rows()).isEqualTo(4);
    assertThat(parsed.sumQuantity()).isEqualByComparingTo("217");
    assertThat(parsed.accepted())
        .extracting(a -> a.key().sourceRecordId())
        .containsExactly("T-001", "T-003", "T-010");
    assertThat(parsed.accepted()).extracting(a -> parsed.lineOf(a.key())).containsExactly(2, 3, 5);
    assertThat(parsed.quarantined()).hasSize(1);
    assertThat(parsed.quarantined().getFirst().code()).isEqualTo("DECIMAL");

    fx.dropSample("trades-day2.csv");
    BatchReport batch = only(fx.run());
    assertThat(batch.newVersions()).isEqualTo(parsed.accepted());
    assertThat(fx.ingest.parseLanded(IngestFixture.sample("trades-day4-broken.csv")).refusal())
        .isPresent();
  }

  // ------------------------------------------------------------------ the ledger shapes the stage
  // needs

  @Test
  void anEmptyBatchWithACheckpointIsAcceptedAndAReplayIsANoOp() {
    IngestFixture fx = new IngestFixture(root);
    BatchId id = BatchId.of(Sha256.ofUtf8("an empty batch"), 0);
    Checkpoint checkpoint = new Checkpoint("trades", new byte[] {0, 0, 0, 0, 0, 0, 0, 7});
    IngestLedger.Commit commit =
        new IngestLedger.Commit(id, IngestFixture.SOURCE, List.of(), Optional.of(checkpoint));

    IngestLedger.Result first = fx.ledgerStore.commitIngest(commit);
    IngestLedger.Result replay = fx.ledgerStore.commitIngest(commit);

    assertThat(first.redelivery()).isFalse();
    assertThat(first.entries()).isEmpty();
    assertThat(first.outboxId()).isPresent();
    assertThat(fx.ledgerStore.latestCheckpoint(IngestFixture.SOURCE, "trades"))
        .contains(checkpoint);
    assertThat(replay.redelivery()).isTrue();
    assertThat(replay.entries()).isEmpty();
    assertThat(replay.outboxId()).isEqualTo(first.outboxId());
    // one DECIDE_BATCH item, still pending: the decide stage has to close it with an empty commit
    assertThat(fx.ledgerStore.pendingOutbox(OutboxKind.DECIDE_BATCH, 10)).hasSize(1);
    fx.ledgerStore.commitDecisions(new DecisionLedger.BatchCommit(List.of(), first.outboxId()));
    assertThat(fx.ledgerStore.outboxItem(first.outboxId().orElseThrow()).orElseThrow().state())
        .isEqualTo(OutboxState.DONE);
    assertThat(fx.ledgerStore.pendingOutbox(OutboxKind.DECIDE_BATCH, 10)).isEmpty();
  }

  @Test
  void theConstructorRefusesWhatItCannotUse() {
    IngestFixture fx = new IngestFixture(root);
    assertThatThrownBy(
            () ->
                new FileIngest(
                    fx.clock,
                    fx.writer,
                    fx.ledger,
                    fx.mapping,
                    IngestFixture.PACK_DIGEST,
                    " ",
                    fx.dq,
                    IngestFixture.DEADLINE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new FileIngest(
                    fx.clock,
                    fx.writer,
                    fx.ledger,
                    fx.mapping,
                    IngestFixture.PACK_DIGEST,
                    IngestFixture.SOURCE,
                    fx.dq,
                    Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new ArrayList<>(fx.log)).isEmpty();
  }
}
