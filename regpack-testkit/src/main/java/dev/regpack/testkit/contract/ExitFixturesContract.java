package dev.regpack.testkit.contract;

import static dev.regpack.model.Leg.ALLOCATION;
import static dev.regpack.model.Leg.CLIENT;
import static dev.regpack.model.Leg.MARKET;
import static dev.regpack.testkit.contract.LedgerFixtures.MIFIR_REGIME;
import static dev.regpack.testkit.contract.LedgerFixtures.batchId;
import static dev.regpack.testkit.contract.LedgerFixtures.body;
import static dev.regpack.testkit.contract.LedgerFixtures.checkpoint;
import static dev.regpack.testkit.contract.LedgerFixtures.commit;
import static dev.regpack.testkit.contract.LedgerFixtures.entry;
import static dev.regpack.testkit.contract.LedgerFixtures.newVersion;
import static dev.regpack.testkit.contract.LedgerFixtures.reportBody;
import static dev.regpack.testkit.contract.LedgerFixtures.reportDecision;
import static dev.regpack.testkit.contract.LedgerFixtures.subjectKey;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionRecord;
import dev.regpack.ports.ledger.DecisionLedger.BatchCommit;
import dev.regpack.ports.ledger.DecisionLedger.CommitStatus;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.IngestLedger.Disposition;
import dev.regpack.ports.ledger.IngestLedger.Result;
import dev.regpack.ports.ledger.IngestLedger.StoredIngest;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.OutboxLedger.OutboxKind;
import dev.regpack.ports.ledger.ReportLedger.NewVersion;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommit;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommitted;
import dev.regpack.ports.ledger.ReportLedger.ReportableDecision;
import dev.regpack.ports.ledger.ReportLedger.StoredVersion;
import dev.regpack.ports.ledger.ReportLedger.Subject;
import dev.regpack.ports.ledger.ReportLedger.SubjectKey;
import dev.regpack.ports.ledger.ReportLedger.VersionStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The fixtures of the P3 exit criterion (spec 16) that the ledger decides, driven through the ports
 * the way the pipeline drives them: ingest a record version, and only when it is {@code
 * NEW_VERSION} decide it and version the report, in one unit that completes the outbox item of the
 * batch. What the pipeline decides on the other dispositions (the UNDETERMINED decision and the
 * data-quality item of a same-version conflict) belongs to later plans; these tests assert only the
 * ledger side. All labels are synthetic fixture values (gate G3).
 */
public interface ExitFixturesContract {
  String FIXTURE_SOURCE = "SRC-A";

  /** A fresh, empty ledger for each test. */
  LedgerStore ledger();

  /** What happened to one record version: its ingest result and, if decided, the unit. */
  record Received(Result ingest, Optional<ReportableCommitted> reported) {
    Disposition disposition() {
      return ingest.entries().get(0).disposition();
    }
  }

  private static EventIdentity event(String name) {
    return EventIdentity.of("LEI1", "XPAR", name);
  }

  private static Regime mifir() {
    return new Regime(MIFIR_REGIME);
  }

  private static SubjectKey key(String recordId) {
    return subjectKey(MIFIR_REGIME, recordId, MARKET);
  }

  private long headSeq(String recordId) {
    return ledger()
        .currentDecision(mifir(), event(recordId))
        .map(DecisionRecord::decisionSeq)
        .orElse(0L);
  }

  /**
   * Ingests version {@code version} of {@code recordId} with {@code content} as one batch. A
   * NEW_VERSION is decided REPORT on top of the head and versioned with the logical hash of the
   * content, together with the completion of the batch's outbox item. The input digest of the
   * decision covers the content and the source version, so that A, B, A is three distinct inputs.
   */
  private Received receive(String recordId, long version, String content) {
    return receive(recordId + "@v" + version, recordId, version, content);
  }

  /** Like {@link #receive(String, long, String)} with an explicit batch tag (a new batch). */
  private Received receive(String batchTag, String recordId, long version, String content) {
    Result ingest =
        ledger()
            .commitIngest(
                commit(
                    batchTag,
                    FIXTURE_SOURCE,
                    checkpoint((int) version),
                    entry(FIXTURE_SOURCE, recordId, version, content)));
    if (ingest.entries().get(0).disposition() != Disposition.NEW_VERSION) {
      ledger().commitDecisions(new BatchCommit(List.of(), ingest.outboxId()));
      return new Received(ingest, Optional.empty());
    }
    ReportableDecision unit =
        new ReportableDecision(
            reportDecision(headSeq(recordId), recordId, "ctx", content + "@v" + version, MARKET),
            List.of(newVersion(recordId, MARKET, "EXEC1", "TRN" + recordId, "NEWT", content)));
    List<ReportableCommitted> committed =
        ledger()
            .commitReportable(
                new ReportableCommit(List.of(unit), ingest.outboxId(), Optional.empty()));
    return new Received(ingest, Optional.of(committed.get(0)));
  }

  private List<StoredVersion> versionsOf(String recordId) {
    Subject subject = ledger().subject(key(recordId)).orElseThrow();
    return ledger().versions(subject.id());
  }

  @Test
  default void staleVersionIsIgnoredAndCreatesNoDecisionNorVersion() {
    Received higher = receive("S1", 2, "A");
    Received lower = receive("S1", 1, "B");

    assertThat(higher.disposition()).isEqualTo(Disposition.NEW_VERSION);
    assertThat(lower.disposition()).isEqualTo(Disposition.STALE);
    assertThat(lower.reported()).isEmpty();
    // journalled as the batch result, but nothing of it is stored as a record version
    assertThat(ledger().ingestResult(lower.ingest().batchId())).contains(lower.ingest());
    assertThat(ledger().findIngest(new IngestKey(FIXTURE_SOURCE, "S1", 1))).isEmpty();
    assertThat(ledger().findIngest(new IngestKey(FIXTURE_SOURCE, "S1", 2))).isPresent();
    // no second decision and no second version: only the higher version was decided
    assertThat(ledger().decisionChain(mifir(), event("S1"))).hasSize(1);
    assertThat(ledger().subject(key("S1")).orElseThrow().lastVersionSeq()).isEqualTo(1);
    assertThat(versionsOf("S1")).extracting(StoredVersion::versionSeq).containsExactly(1L);
    assertThat(versionsOf("S1"))
        .extracting(StoredVersion::logicalHash)
        .containsExactly(Sha256.ofUtf8("A"));
    // the stale batch's outbox item was completed without a decision
    assertThat(ledger().pendingOutbox(OutboxKind.DECIDE_BATCH, 10)).isEmpty();
  }

  @Test
  default void lowerVersionAfterHigherIsStaleAndNeverReportedAfterTheHigher() {
    receive("L1", 3, "A");
    // even a lower version with the very same content is stale: order decides, not content
    Received lowerSame = receive("L1", 2, "A");
    Received lowest = receive("L1", 1, "C");

    assertThat(lowerSame.disposition()).isEqualTo(Disposition.STALE);
    assertThat(lowest.disposition()).isEqualTo(Disposition.STALE);
    assertThat(versionsOf("L1")).extracting(StoredVersion::versionSeq).containsExactly(1L);
    assertThat(versionsOf("L1"))
        .extracting(StoredVersion::logicalHash)
        .containsExactly(Sha256.ofUtf8("A"));
    assertThat(ledger().decisionChain(mifir(), event("L1"))).hasSize(1);

    // a higher version after the stale ones continues the chain and the versions
    Received next = receive("L1", 4, "B");
    assertThat(next.disposition()).isEqualTo(Disposition.NEW_VERSION);
    assertThat(versionsOf("L1")).extracting(StoredVersion::versionSeq).containsExactly(1L, 2L);
    assertThat(versionsOf("L1"))
        .extracting(StoredVersion::logicalHash)
        .containsExactly(Sha256.ofUtf8("A"), Sha256.ofUtf8("B"));
  }

  /**
   * Spec 10: the same source version with a different content hash is never a silent merge. The
   * UNDETERMINED decision with its data-quality item is made by the pipeline and queued by a later
   * plan; the ledger side is what is asserted here.
   */
  @Test
  default void
      sameVersionDifferentHashIsReportedAsConflictKeepsTheOriginalRowAndCreatesNoVersion() {
    Received original = receive("C1", 1, "A");
    Received conflict = receive("C1@v1-again", "C1", 1, "B");

    assertThat(original.disposition()).isEqualTo(Disposition.NEW_VERSION);
    assertThat(conflict.disposition()).isEqualTo(Disposition.CONFLICT_SAME_VERSION_DIFFERENT_HASH);
    assertThat(conflict.reported()).isEmpty();
    StoredIngest stored = ledger().findIngest(new IngestKey(FIXTURE_SOURCE, "C1", 1)).orElseThrow();
    assertThat(stored.contentHash()).isEqualTo(Sha256.ofUtf8("A"));
    assertThat(stored.batchId()).isEqualTo(batchId("C1@v1"));
    // no report_version beyond the original, and the original decision is still the head
    assertThat(versionsOf("C1")).extracting(StoredVersion::versionSeq).containsExactly(1L);
    assertThat(versionsOf("C1"))
        .extracting(StoredVersion::logicalHash)
        .containsExactly(Sha256.ofUtf8("A"));
    assertThat(ledger().decisionChain(mifir(), event("C1"))).hasSize(1);
    assertThat(ledger().pendingOutbox(OutboxKind.DECIDE_BATCH, 10)).isEmpty();
  }

  /**
   * Spec 10 and 16: the content of version 3 equals that of version 1, and it arrives with a later
   * ordering value, so it is a new version; no-op suppression compares only with the latest
   * non-rejected version, which is B.
   */
  @Test
  default void abaEndToEndThirdDecisionIsANewRowWhoseVersionReflectsA() {
    Received first = receive("A1", 1, "A");
    Received second = receive("A1", 2, "B");
    Received third = receive("A1", 3, "A");

    for (Received received : List.of(first, second, third)) {
      assertThat(received.disposition()).isEqualTo(Disposition.NEW_VERSION);
      assertThat(received.reported().orElseThrow().decision().status())
          .isEqualTo(CommitStatus.INSERTED);
    }
    assertThat(ledger().findIngest(new IngestKey(FIXTURE_SOURCE, "A1", 3))).isPresent();

    List<DecisionRecord> chain = ledger().decisionChain(mifir(), event("A1"));
    assertThat(chain).extracting(DecisionRecord::decisionSeq).containsExactly(1L, 2L, 3L);
    assertThat(chain.get(0).supersedesDecisionId()).isEmpty();
    assertThat(chain.get(1).supersedesDecisionId()).contains(chain.get(0).decisionId());
    assertThat(chain.get(2).supersedesDecisionId()).contains(chain.get(1).decisionId());
    assertThat(chain.get(2).decisionId()).isNotEqualTo(chain.get(0).decisionId());
    assertThat(chain.get(2).body().outcome()).isEqualTo(Outcome.REPORT);

    assertThat(third.reported().orElseThrow().versions().get(0).status())
        .isEqualTo(VersionStatus.CREATED);
    List<StoredVersion> versions = versionsOf("A1");
    assertThat(versions).extracting(StoredVersion::versionSeq).containsExactly(1L, 2L, 3L);
    assertThat(versions)
        .extracting(StoredVersion::logicalHash)
        .containsExactly(Sha256.ofUtf8("A"), Sha256.ofUtf8("B"), Sha256.ofUtf8("A"));
    // the third version reflects A: its body is that of the first and differs from the second
    assertThat(versions.get(2).body()).isEqualTo(versions.get(0).body());
    assertThat(versions.get(2).body()).isNotEqualTo(versions.get(1).body());
    // each version is linked to its own decision and has its own delivery in PREPARED
    assertThat(versions)
        .extracting(StoredVersion::decision)
        .containsExactly(
            chain.get(0).decisionId(), chain.get(1).decisionId(), chain.get(2).decisionId());
    assertThat(versions).extracting(StoredVersion::delivery).doesNotContainNull();
    for (StoredVersion version : versions) {
      assertThat(ledger().delivery(version.delivery().orElseThrow()).orElseThrow().state())
          .isEqualTo(DeliveryState.PREPARED);
    }
    assertThat(versions).extracting(v -> v.delivery().orElseThrow()).doesNotHaveDuplicates();
  }

  /**
   * The trading-capacity labels DEAL, MTCH and AOTC are synthetic values of the attribute {@code
   * capacity} (BRK-10); they assert nothing about any regulation (gate G3).
   */
  @Test
  default void tradingCapacityFixturesRoundTripWithAttributesLegsAndOutcome() {
    DecisionBody deal =
        withCapacity(reportBody(MIFIR_REGIME, "DEAL1", "ctx", "d1", MARKET), "DEAL");
    DecisionBody mtch =
        withCapacity(reportBody(MIFIR_REGIME, "MTCH1", "ctx", "d2", MARKET, CLIENT), "MTCH");
    DecisionBody aotc =
        withCapacity(reportBody(MIFIR_REGIME, "AOTC1", "ctx", "d3", ALLOCATION), "AOTC");
    DecisionBody doNotReport =
        body(
            "DNR1",
            MIFIR_REGIME,
            "ctx",
            "d4",
            Outcome.DO_NOT_REPORT,
            Instant.parse("2026-10-09T10:00:00Z"));
    assertThat(doNotReport.legs()).isEmpty();

    List<ReportableDecision> unit = new ArrayList<>();
    List<String> recordIds = List.of("DEAL1", "MTCH1", "AOTC1");
    List<DecisionBody> reportables = List.of(deal, mtch, aotc);
    for (int i = 0; i < reportables.size(); i++) {
      DecisionBody reportable = reportables.get(i);
      String recordId = recordIds.get(i);
      List<NewVersion> versions = new ArrayList<>();
      for (Leg leg : reportable.legs()) {
        versions.add(
            newVersion(
                recordId,
                leg,
                "EXEC1",
                "TRN" + recordId + leg.name(),
                "NEWT",
                reportable.attributes().get("capacity") + leg.name()));
      }
      unit.add(
          new ReportableDecision(LedgerFixtures.decision(reportable, 0), List.copyOf(versions)));
    }
    unit.add(new ReportableDecision(LedgerFixtures.decision(doNotReport, 0), List.of()));
    List<ReportableCommitted> committed =
        ledger().commitReportable(new ReportableCommit(unit, Optional.empty(), Optional.empty()));

    assertThat(committed)
        .extracting(c -> c.decision().status())
        .containsOnly(CommitStatus.INSERTED);
    for (DecisionBody expected : List.of(deal, mtch, aotc, doNotReport)) {
      DecisionRecord read =
          ledger().currentDecision(expected.regime(), expected.eventIdentity()).orElseThrow();
      assertThat(read.decisionSeq()).isEqualTo(1);
      assertThat(read.body()).isEqualTo(expected);
      assertThat(read.body().replayDigest()).isEqualTo(expected.replayDigest());
      assertThat(read.body().legs()).isEqualTo(expected.legs());
      assertThat(read.body().outcome()).isEqualTo(expected.outcome());
      assertThat(read.body().attributes()).isEqualTo(expected.attributes());
    }
    assertThat(ledger().currentDecision(mifir(), event("DEAL1")).orElseThrow().body().attributes())
        .isEqualTo(Map.of("capacity", "DEAL"));
    assertThat(ledger().currentDecision(mifir(), event("MTCH1")).orElseThrow().body().legs())
        .containsExactly(MARKET, CLIENT);
    // one subject per leg of a REPORT decision, none for DO_NOT_REPORT
    assertThat(ledger().subject(subjectKey(MIFIR_REGIME, "DEAL1", MARKET))).isPresent();
    assertThat(ledger().subject(subjectKey(MIFIR_REGIME, "MTCH1", MARKET))).isPresent();
    assertThat(ledger().subject(subjectKey(MIFIR_REGIME, "MTCH1", CLIENT))).isPresent();
    assertThat(ledger().subject(subjectKey(MIFIR_REGIME, "AOTC1", ALLOCATION))).isPresent();
    for (Leg leg : Leg.values()) {
      assertThat(ledger().subject(subjectKey(MIFIR_REGIME, "DNR1", leg))).isEmpty();
    }
  }

  private static DecisionBody withCapacity(DecisionBody body, String capacity) {
    return new DecisionBody(
        body.eventIdentity(),
        body.regime(),
        body.decisionContextId(),
        body.asAt(),
        body.evaluatedAt(),
        body.outcome(),
        body.flags(),
        body.reasonCode(),
        body.node(),
        body.ruleIdsHit(),
        body.path(),
        body.citations(),
        body.legs(),
        body.inputDigest(),
        Map.of("capacity", capacity));
  }
}
