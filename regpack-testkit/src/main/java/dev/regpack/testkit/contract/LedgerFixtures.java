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
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Helpers shared by the ledger contract suites. */
public final class LedgerFixtures {
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
        outcome == Outcome.REPORT ? List.of(Leg.MARKET) : List.of(),
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
