package dev.regpack.testkit.contract;

import dev.regpack.model.BatchId;
import dev.regpack.model.Checkpoint;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionContext;
import dev.regpack.ports.ledger.DecisionLedger;
import dev.regpack.ports.ledger.DeliveryLedger;
import dev.regpack.ports.ledger.IngestLedger;
import dev.regpack.ports.ledger.LeaseLedger;
import dev.regpack.ports.ledger.ReportLedger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Helpers shared by the ledger contract suites. */
public final class LedgerFixtures {
  /** The regime that the report builders use unless a regime is given. */
  public static final String MIFIR_REGIME = "mifir.eu.rts22";

  /** A second regime for the tests of the regime scope. */
  public static final String EMIR_REGIME = "emir.eu";

  private LedgerFixtures() {}

  /**
   * An entry whose key is (sourceId, recordId, version), whose event identity is {@code
   * LEI1|XPAR|recordId} and whose hash is the SHA-256 of {@code content}.
   */
  public static IngestLedger.Entry entry(
      String sourceId, String recordId, long version, String content) {
    return new IngestLedger.Entry(
        new IngestKey(sourceId, recordId, version),
        EventIdentity.of("LEI1", "XPAR", recordId),
        Sha256.ofUtf8(content));
  }

  /** A commit whose batch id is {@code BatchId.of(Sha256.ofUtf8(batchTag), 0)}. */
  public static IngestLedger.Commit commit(
      String batchTag, String sourceId, Checkpoint cp, IngestLedger.Entry... entries) {
    return new IngestLedger.Commit(batchId(batchTag), sourceId, List.of(entries), Optional.of(cp));
  }

  /** The batch id that {@link #commit} derives from {@code batchTag}. */
  public static BatchId batchId(String batchTag) {
    return BatchId.of(Sha256.ofUtf8(batchTag), 0);
  }

  /** A checkpoint on stream {@code trades} whose state is the single byte {@code n}. */
  public static Checkpoint checkpoint(int n) {
    return new Checkpoint("trades", new byte[] {(byte) n});
  }

  /**
   * A decision body for event {@code LEI1|XPAR|event} with context id {@code SHA-256(contextTag)},
   * input digest {@code SHA-256(digestTag)}, reason {@code MIFIR.N08.IN_SCOPE_26_2_A}, node {@code
   * N08}, legs {@code [MARKET]} for {@code REPORT} and none otherwise, {@code asAt}
   * 2026-10-09T09:30:00Z and empty lists elsewhere.
   */
  public static DecisionBody body(
      String event,
      String regime,
      String contextTag,
      String digestTag,
      Outcome outcome,
      Instant evaluatedAt) {
    return bodyAt(
        event,
        regime,
        contextTag,
        digestTag,
        outcome,
        evaluatedAt,
        Instant.parse("2026-10-09T09:30:00Z"));
  }

  /** Like {@link #body} with an explicit {@code asAt} (the cross-partition case of spec 6.3). */
  public static DecisionBody bodyAt(
      String event,
      String regime,
      String contextTag,
      String digestTag,
      Outcome outcome,
      Instant evaluatedAt,
      Instant asAt) {
    return decisionBody(
        event,
        regime,
        contextTag,
        digestTag,
        outcome,
        evaluatedAt,
        asAt,
        outcome == Outcome.REPORT ? List.of(Leg.MARKET) : List.of());
  }

  /** The one place where the fixed components of a test decision body are spelled out. */
  private static DecisionBody decisionBody(
      String event,
      String regime,
      String contextTag,
      String digestTag,
      Outcome outcome,
      Instant evaluatedAt,
      Instant asAt,
      List<Leg> legs) {
    return new DecisionBody(
        EventIdentity.of("LEI1", "XPAR", event),
        new Regime(regime),
        Sha256.ofUtf8(contextTag),
        asAt,
        evaluatedAt,
        outcome,
        List.of(),
        new ReasonCode("MIFIR.N08.IN_SCOPE_26_2_A"),
        "N08",
        List.of(),
        List.of(),
        List.of(),
        legs,
        Sha256.ofUtf8(digestTag));
  }

  /** A new decision that expects the chain head to be at {@code expectedHeadSeq}. */
  public static DecisionLedger.NewDecision decision(DecisionBody body, long expectedHeadSeq) {
    return new DecisionLedger.NewDecision(body, expectedHeadSeq);
  }

  /** A new decision that carries its decision context (stored in the same unit). */
  public static DecisionLedger.NewDecision decision(
      DecisionBody body, long expectedHeadSeq, DecisionContext context) {
    return new DecisionLedger.NewDecision(body, expectedHeadSeq, Optional.of(context));
  }

  /**
   * A decision context whose engine build is {@code engineTag}; every other component is fixed.
   * Different tags give different context ids.
   */
  public static DecisionContext context(String engineTag) {
    return new DecisionContext(
        Sha256.ofUtf8("pack"),
        Optional.empty(),
        List.of("pin-1"),
        List.of("ref-1"),
        List.of(),
        "fp-1",
        "map-1",
        engineTag,
        "interpreter");
  }

  /** The same body with {@code decisionContextId} set to the id of {@code context}. */
  public static DecisionBody withContext(DecisionBody body, DecisionContext context) {
    return new DecisionBody(
        body.eventIdentity(),
        body.regime(),
        context.id(),
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
        body.attributes());
  }

  /**
   * A REPORT decision body for event {@code LEI1|XPAR|event} in {@code regime}, context id {@code
   * SHA-256(ctxTag)}, input digest {@code SHA-256(digestTag)} and exactly the given {@code legs};
   * everything else as {@link #body}.
   */
  public static DecisionBody reportBody(
      String regime, String event, String ctxTag, String digestTag, Leg... legs) {
    return decisionBody(
        event,
        regime,
        ctxTag,
        digestTag,
        Outcome.REPORT,
        Instant.parse("2026-10-09T10:00:00Z"),
        Instant.parse("2026-10-09T09:30:00Z"),
        List.of(legs));
  }

  /** A REPORT decision in {@link #MIFIR_REGIME} that expects an empty chain (head 0). */
  public static DecisionLedger.NewDecision reportDecision(
      String event, String ctxTag, String digestTag, Leg... legs) {
    return reportDecision(0, event, ctxTag, digestTag, legs);
  }

  /** A REPORT decision in {@link #MIFIR_REGIME} that expects the chain head {@code expected}. */
  public static DecisionLedger.NewDecision reportDecision(
      long expectedHeadSeq, String event, String ctxTag, String digestTag, Leg... legs) {
    return decision(reportBody(MIFIR_REGIME, event, ctxTag, digestTag, legs), expectedHeadSeq);
  }

  /** The key of the report subject of a leg of {@code LEI1|XPAR|event} in {@code regime}. */
  public static ReportLedger.SubjectKey subjectKey(String regime, String event, Leg leg) {
    return new ReportLedger.SubjectKey(
        new Regime(regime), EventIdentity.of("LEI1", "XPAR", event), leg);
  }

  /**
   * A version request in {@link #MIFIR_REGIME} for {@code leg} of {@code LEI1|XPAR|event} that
   * proposes {@code trn} under key id {@code KEY-1}, for the endpoint {@code endpoint("EP1")} in
   * epoch 1; the logical hash is {@code SHA-256(hashTag)} and the body the UTF-8 of {@code "body-"
   * + hashTag}.
   */
  public static ReportLedger.NewVersion newVersion(
      String event, Leg leg, String executingLei, String trn, String action, String hashTag) {
    return newVersion(
        subjectKey(MIFIR_REGIME, event, leg),
        executingLei,
        Optional.of(new ReportLedger.TrnProposal(trn, "KEY-1")),
        action,
        hashTag,
        endpoint("EP1"),
        1);
  }

  /** Like {@link #newVersion(String, Leg, String, String, String, String)} with a subject key. */
  public static ReportLedger.NewVersion newVersion(
      ReportLedger.SubjectKey subject,
      String executingLei,
      Optional<ReportLedger.TrnProposal> trn,
      String action,
      String hashTag) {
    return newVersion(subject, executingLei, trn, action, hashTag, endpoint("EP1"), 1);
  }

  /** The general form: every component of a version request is given. */
  public static ReportLedger.NewVersion newVersion(
      ReportLedger.SubjectKey subject,
      String executingLei,
      Optional<ReportLedger.TrnProposal> trn,
      String action,
      String hashTag,
      LeaseLedger.EndpointKey endpoint,
      long endpointEpoch) {
    return new ReportLedger.NewVersion(
        subject,
        executingLei,
        trn,
        action,
        Sha256.ofUtf8(hashTag),
        ("body-" + hashTag).getBytes(StandardCharsets.UTF_8),
        ReportLedger.VersionOrigin.SOURCE,
        endpoint,
        endpointEpoch);
  }

  /** The endpoint {@code ("SIMULATOR", "ARM", "LEI-" + name, "NCA-" + name)}. */
  public static LeaseLedger.EndpointKey endpoint(String name) {
    return new LeaseLedger.EndpointKey("SIMULATOR", "ARM", "LEI-" + name, "NCA-" + name);
  }

  /** The fixed attempt intent that {@link #begin} uses: a fixed digest and {@code file.xml}. */
  public static DeliveryLedger.AttemptIntent attemptIntent() {
    return new DeliveryLedger.AttemptIntent(Sha256.ofUtf8("artifact"), "file.xml");
  }

  /**
   * Creates a delivery for (endpoint, epoch 1, business key, version) and moves it from {@code
   * PREPARED} to {@code QUEUED}, asserting that the transition is applied.
   */
  public static DeliveryLedger.DeliveryId queuedDelivery(
      DeliveryLedger ledger, LeaseLedger.EndpointKey e, String businessKey, long versionSeq) {
    DeliveryLedger.DeliveryId id =
        ledger.createDelivery(new DeliveryLedger.NewDelivery(e, 1, businessKey, versionSeq));
    DeliveryLedger.TransitionStatus status =
        ledger.transition(
            id, DeliveryLedger.DeliveryState.PREPARED, DeliveryLedger.DeliveryState.QUEUED);
    if (status != DeliveryLedger.TransitionStatus.APPLIED) {
      throw new AssertionError("PREPARED -> QUEUED was not applied: " + status);
    }
    return id;
  }

  /** Allocates an attempt id, then calls {@code beginSending} with {@link #attemptIntent()}. */
  public static DeliveryLedger.SendingResult begin(
      DeliveryLedger ledger, DeliveryLedger.DeliveryId d, LeaseLedger.LeaseToken t) {
    DeliveryLedger.AttemptId id = ledger.allocateAttemptId();
    return ledger.beginSending(d, id, t, attemptIntent());
  }
}
