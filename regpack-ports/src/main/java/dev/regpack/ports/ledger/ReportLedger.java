package dev.regpack.ports.ledger;

import dev.regpack.model.BatchId;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.KeyText;
import dev.regpack.model.Leg;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.decision.DecisionId;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Report side of the ledger (spec 10, 11.1): report subjects, the TRN assigned once, report
 * versions and their delivery in {@code PREPARED}, committed in the unit of the decision.
 *
 * <p>Semantics of {@link #commitReportable} (normative; the contract suite is its oracle):
 *
 * <ol>
 *   <li>All or nothing, including the decisions, which follow {@link
 *       DecisionLedger#commitDecisions} exactly (idempotency key, head compare-and-set, supersedes
 *       link, {@code completesOutbox}).
 *   <li>A {@link NewVersion} is accepted only for a decision whose outcome is REPORT and for a leg
 *       in that decision's {@code legs}, at most once per (decision, leg); the regime and event
 *       identity of {@code NewVersion.subject} must equal those of the decision body. Otherwise
 *       {@link IllegalArgumentException} before any write.
 *   <li>For a decision that is {@code NOOP_REDELIVERY}, a requested version that already exists for
 *       (decision, subject) returns {@code EXISTING} and writes nothing; one that does not exist is
 *       created only if that decision is still the head of its chain (else {@link
 *       DecisionHeadConflictException}).
 *   <li>For an {@code INSERTED} decision, per {@code NewVersion}: get-or-create the subject by
 *       (regime, event identity, leg); apply rule 5; apply rule 7; create the version with {@code
 *       versionSeq} = last + 1; create the delivery in {@code PREPARED} with {@code
 *       NewDelivery(endpoint, endpointEpoch, executingLei + "|" + trn, versionSeq)}.
 *   <li><b>TRN.</b> A subject without a TRN needs a proposal; the pair (executing LEI, TRN) must be
 *       unused by every other subject, else {@link TrnConflictException} and nothing is written. A
 *       subject with a TRN takes the stored one: a present proposal must equal the stored TRN
 *       string and the executing LEI must equal the stored one (else {@link
 *       IllegalArgumentException}); a different key id in the proposal is ignored and the first key
 *       id is kept. The TRN, its key id and the assignment instant (the ledger clock) are stored in
 *       the same unit as the first version and never change.
 *   <li>{@code versionSeq} is gapless per subject (1, 2, 3 ...) and assigned under the subject's
 *       lock.
 *   <li><b>Suppression.</b> If the latest version of the subject whose delivery is not in the dead
 *       states {REJECTED, IN_REPAIR, WITHDRAWN, WRITTEN_OFF} has the same {@code action} and {@code
 *       logicalHash}, the result is {@code SUPPRESSED_UNCHANGED} and nothing is written in P3a; its
 *       {@code versionSeq} names that existing version and its {@code delivery} is empty (this call
 *       created none). The journal entry of the suppression (spec 10) is written by the delivery
 *       state machine plan in the same unit.
 *   <li><b>Withdrawal guard.</b> An {@code INSERTED} decision whose superseded decision has
 *       subjects with at least one version, and whose outcome is not REPORT or whose legs omit a
 *       leg that has versions, throws {@link WithdrawalRequiredException} and writes nothing;
 *       {@link DecisionLedger#commitDecisions} applies the same rule.
 *   <li>If {@code sequenceBatch} is present and at least one version is {@code CREATED}, one outbox
 *       item of kind {@code SEQUENCE} with {@code subjectId} = the batch id exists afterwards (an
 *       existing one is left; unique on kind and subject).
 *   <li><b>Bodies.</b> A body is opaque to the ledger and is stored and returned byte for byte. A
 *       body for which {@code Envelope.isEnvelope} holds (package {@code dev.regpack.ports.crypto};
 *       the magic alone is not enough) is recorded as an envelope body ({@code body_encoding} 1 in
 *       PostgreSQL). A ledger that is built to require envelopes (the production configuration)
 *       refuses every other body, the empty body and a body that only starts like an envelope
 *       included, with {@link IllegalArgumentException} before any write; the default ledger still
 *       accepts a plain body. The test needs no key and does not authenticate: a well-formed header
 *       over random bytes passes, so the guarantee is "not plain JSON", not "authentic". The
 *       message of the refusal holds nothing of the body.
 * </ol>
 */
public interface ReportLedger {
  record SubjectId(long value) {}

  /** The report subject: one leg of one event under one regime. */
  record SubjectKey(Regime regime, EventIdentity eventIdentity, Leg leg) {
    public SubjectKey {
      Objects.requireNonNull(regime, "regime");
      Objects.requireNonNull(eventIdentity, "eventIdentity");
      Objects.requireNonNull(leg, "leg");
    }
  }

  /**
   * A proposed TRN. {@code trn}: 1 to 52 characters (the MiFIR limit; the pack-declared TRN policy
   * owns it) of {@code [A-Za-z0-9]}; {@code keyId}: not blank and a key text ({@link KeyText}).
   */
  record TrnProposal(String trn, String keyId) {
    private static final Pattern TRN = Pattern.compile("[A-Za-z0-9]{1,52}");

    public TrnProposal {
      if (trn == null || !TRN.matcher(trn).matches()) {
        throw new IllegalArgumentException("a TRN is 1 to 52 characters of [A-Za-z0-9]");
      }
      if (keyId == null || keyId.isBlank()) {
        throw new IllegalArgumentException("keyId must not be blank");
      }
      KeyText.require("keyId", keyId);
    }
  }

  enum VersionOrigin {
    SOURCE,
    ARM,
    IMPORTED
  }

  /**
   * A requested report version. {@code body} is copied on the way in and on the way out; {@code
   * action} is the regime-owned vocabulary (for example NEWT, CANC) and not blank; {@code
   * executingLei} is not blank; both are key texts ({@link KeyText}: no control character, no
   * unpaired surrogate, at most {@link KeyText#MAX_CHARS} chars), else {@link
   * IllegalArgumentException}.
   */
  record NewVersion(
      SubjectKey subject,
      String executingLei,
      Optional<TrnProposal> trn,
      String action,
      Sha256 logicalHash,
      byte[] body,
      VersionOrigin origin,
      LeaseLedger.EndpointKey endpoint,
      long endpointEpoch) {
    public NewVersion {
      Objects.requireNonNull(subject, "subject");
      if (executingLei == null || executingLei.isBlank()) {
        throw new IllegalArgumentException("executingLei must not be blank");
      }
      Objects.requireNonNull(trn, "trn");
      if (action == null || action.isBlank()) {
        throw new IllegalArgumentException("action must not be blank");
      }
      KeyText.require("executingLei", executingLei);
      KeyText.require("action", action);
      Objects.requireNonNull(logicalHash, "logicalHash");
      body = Objects.requireNonNull(body, "body").clone();
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(endpoint, "endpoint");
    }

    @Override
    public byte[] body() {
      return body.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof NewVersion v
          && subject.equals(v.subject)
          && executingLei.equals(v.executingLei)
          && trn.equals(v.trn)
          && action.equals(v.action)
          && logicalHash.equals(v.logicalHash)
          && Arrays.equals(body, v.body)
          && origin == v.origin
          && endpoint.equals(v.endpoint)
          && endpointEpoch == v.endpointEpoch;
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          subject,
          executingLei,
          trn,
          action,
          logicalHash,
          Arrays.hashCode(body),
          origin,
          endpoint,
          endpointEpoch);
    }

    @Override
    public String toString() {
      return "NewVersion[subject="
          + subject
          + ", action="
          + action
          + ", logicalHash="
          + logicalHash
          + ", bodyLength="
          + body.length
          + "]";
    }
  }

  enum VersionStatus {
    CREATED,
    EXISTING,
    SUPPRESSED_UNCHANGED
  }

  /**
   * The outcome for one requested version. For {@code SUPPRESSED_UNCHANGED}, {@code versionSeq} is
   * the version whose content is unchanged and {@code delivery} is empty; otherwise {@code
   * delivery} is the delivery of the highest {@code endpoint_epoch} for the version.
   */
  record VersionResult(
      SubjectId subject,
      String trn,
      long versionSeq,
      VersionStatus status,
      Optional<DeliveryLedger.DeliveryId> delivery) {
    public VersionResult {
      Objects.requireNonNull(subject, "subject");
      Objects.requireNonNull(trn, "trn");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(delivery, "delivery");
    }
  }

  /** A decision and the versions requested for it; the versions are copied. */
  record ReportableDecision(DecisionLedger.NewDecision decision, List<NewVersion> versions) {
    public ReportableDecision {
      Objects.requireNonNull(decision, "decision");
      versions = List.copyOf(versions);
    }
  }

  /** One unit: the decisions are copied; see the class comment for the rules. */
  record ReportableCommit(
      List<ReportableDecision> decisions,
      Optional<OutboxLedger.OutboxId> completesOutbox,
      Optional<BatchId> sequenceBatch) {
    public ReportableCommit {
      decisions = List.copyOf(decisions);
      Objects.requireNonNull(completesOutbox, "completesOutbox");
      Objects.requireNonNull(sequenceBatch, "sequenceBatch");
    }
  }

  /** The result per decision, in input order; {@code versions} follow the requested order. */
  record ReportableCommitted(DecisionLedger.Committed decision, List<VersionResult> versions) {
    public ReportableCommitted {
      Objects.requireNonNull(decision, "decision");
      versions = List.copyOf(versions);
    }
  }

  record Subject(
      SubjectId id,
      SubjectKey key,
      Optional<String> executingLei,
      Optional<String> trn,
      Optional<String> trnKeyId,
      long lastVersionSeq) {
    public Subject {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(executingLei, "executingLei");
      Objects.requireNonNull(trn, "trn");
      Objects.requireNonNull(trnKeyId, "trnKeyId");
    }
  }

  /** A stored version; {@code body} is a copy; {@code delivery}: highest endpoint epoch. */
  record StoredVersion(
      SubjectId subject,
      long versionSeq,
      DecisionId decision,
      String action,
      Sha256 logicalHash,
      byte[] body,
      VersionOrigin origin,
      Optional<DeliveryLedger.DeliveryId> delivery) {
    public StoredVersion {
      Objects.requireNonNull(subject, "subject");
      Objects.requireNonNull(decision, "decision");
      Objects.requireNonNull(action, "action");
      Objects.requireNonNull(logicalHash, "logicalHash");
      body = Objects.requireNonNull(body, "body").clone();
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(delivery, "delivery");
    }

    @Override
    public byte[] body() {
      return body.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof StoredVersion v
          && subject.equals(v.subject)
          && versionSeq == v.versionSeq
          && decision.equals(v.decision)
          && action.equals(v.action)
          && logicalHash.equals(v.logicalHash)
          && Arrays.equals(body, v.body)
          && origin == v.origin
          && delivery.equals(v.delivery);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          subject,
          versionSeq,
          decision,
          action,
          logicalHash,
          Arrays.hashCode(body),
          origin,
          delivery);
    }

    @Override
    public String toString() {
      return "StoredVersion[subject="
          + subject
          + ", versionSeq="
          + versionSeq
          + ", action="
          + action
          + ", bodyLength="
          + body.length
          + "]";
    }
  }

  /**
   * Commits the decisions and the requested versions in one unit and returns one result per
   * decision, in input order.
   *
   * @throws DecisionHeadConflictException a decision's expected head is stale, or a version is
   *     requested for a redelivered decision that is no longer the head
   * @throws TrnConflictException the (executing LEI, TRN) pair belongs to another subject
   * @throws WithdrawalRequiredException a superseding decision would withdraw reported legs
   * @throws IllegalArgumentException a rule of the class comment is violated; nothing is written
   */
  List<ReportableCommitted> commitReportable(ReportableCommit commit);

  Optional<Subject> subject(SubjectKey key);

  /** The subject that holds {@code trn} under {@code executingLei}; empty under any other LEI. */
  Optional<Subject> subjectByTrn(String executingLei, String trn);

  /** The versions of a subject, ascending by {@code versionSeq}. */
  List<StoredVersion> versions(SubjectId subject);
}
