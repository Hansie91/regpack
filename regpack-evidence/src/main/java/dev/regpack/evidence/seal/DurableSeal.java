package dev.regpack.evidence.seal;

import dev.regpack.model.Sha256;
import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.evidence.journal.JournalKey;
import dev.regpack.ports.evidence.journal.JournalObject;
import dev.regpack.ports.evidence.journal.JournalSchemas;
import dev.regpack.ports.evidence.journal.JournalScope;
import dev.regpack.ports.evidence.journal.JournalType;
import dev.regpack.ports.evidence.journal.JournalWriter;
import dev.regpack.ports.evidence.seal.Sealing;
import dev.regpack.ports.ledger.DeliveryLedger;
import dev.regpack.ports.ledger.LeaseLedger;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.ManifestLedger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable before effect (I1, spec 11.2 steps 2 to 4, D-E4). {@link #seal} runs in this order, and
 * nothing is reordered by a caller:
 *
 * <ol>
 *   <li>validate the request (the hash, the delivery, the file name, the media type, the endpoint
 *       and the texts of the intent object), before the gate, an attempt id and any put;
 *   <li>the send gate ({@link #gateResult()}); a closed gate writes nothing;
 *   <li>an attempt id from the ledger;
 *   <li>put the sealed bytes ({@link #putSealedBytes}), then the {@code DeliveryIntent} journal
 *       object ({@link #putIntentObject}); each is acknowledged by the evidence store before the
 *       next step (for P-SEALED: fsynced on two volumes);
 *   <li>one ledger unit ({@link #commitSending}): the delivery to {@code SENDING} under the lease
 *       token, the attempt row and the manifest rows of both objects.
 * </ol>
 *
 * <p>An outage of the evidence store is {@code EVIDENCE_UNAVAILABLE} and leaves the ledger as it
 * was. A stale lease or a refused transition after the puts leaves two orphan objects by design
 * (spec 7.2). An integrity alarm of the store and a {@link
 * dev.regpack.ports.ledger.ManifestConflictException} are not caught: the seal fails closed. The
 * manifest rows carry the hash and the size of the bytes as they were put; the rows are planned
 * before the first put, so that the one-line teeth of the shared suite (D-E10) can reorder the
 * steps. The class is not final for the same reason: the template methods are overridden by test
 * subclasses, one each.
 *
 * <p>{@link #beforeFirstByte} is the commit gate of the transport. The residual race of two
 * concurrent callers for one attempt is closed by the sender's in-flight guard (one attempt, one
 * thread); the marker statement of the ledger is idempotent, which is why a second gate for an
 * attempt whose marker is already set is refused here. This class never calls a transport.
 *
 * <p>The key of the intent object and its payload name the attempt by the same id, taken once. The
 * schema of the journal does not cross-check them, so this is the producer's duty ({@code verify}
 * flags a difference as INTENT_MISMATCH).
 */
public class DurableSeal implements Sealing {
  /** The retention class name of sealed files (OI-6). */
  public static final String SEALED_RETENTION_CLASS = "sealed";

  /** The longest media type text that a seal accepts, in characters. */
  private static final int MAX_MEDIA_TYPE_LENGTH = 255;

  private final LedgerStore ledger;
  private final EvidenceStore evidence;
  private final Clock clock;
  private final RetentionPolicy retention;
  private final SendGate gate;
  private final JournalWriter journal;
  private final EpochMarker marker;

  public DurableSeal(
      LedgerStore ledger,
      EvidenceStore evidence,
      Clock clock,
      RetentionPolicy retention,
      SendGate gate) {
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.evidence = Objects.requireNonNull(evidence, "evidence");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.retention = Objects.requireNonNull(retention, "retention");
    this.gate = Objects.requireNonNull(gate, "gate");
    this.journal = new JournalWriter(evidence, retention);
    this.marker = new EpochMarker(evidence, retention, clock);
  }

  /**
   * What one seal knows after its attempt id is taken: the request, the attempt, the token, the
   * manifest entries of the two objects (planned from the bytes that will be put, in the order
   * sealed file, intent object) and the intent object itself with its fixed clock instant.
   */
  protected record SealContext(
      SealRequest request,
      DeliveryLedger.AttemptId attempt,
      LeaseLedger.LeaseToken token,
      List<ManifestLedger.ObjectEntry> entries,
      JournalObject intent) {
    public SealContext {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(attempt, "attempt");
      Objects.requireNonNull(token, "token");
      entries = List.copyOf(entries);
      Objects.requireNonNull(intent, "intent");
    }
  }

  @Override
  public SealOutcome seal(SealRequest request) {
    Objects.requireNonNull(request, "request");
    Validated validated = validate(request);
    if (validated.delivery().state() != DeliveryLedger.DeliveryState.QUEUED) {
      // advisory: beginSending stays the judge of the state
      return new SealOutcome(
          SealStatus.STATE_MISMATCH, Optional.empty(), "the delivery is not queued");
    }
    SendGate.Result gateResult = gateResult();
    if (!gateResult.open()) {
      return new SealOutcome(
          SealStatus.GATE_CLOSED, Optional.empty(), String.join("; ", gateResult.reasons()));
    }
    DeliveryLedger.AttemptId attempt = ledger.allocateAttemptId();
    SealContext context = context(request, validated, attempt);
    try {
      putSealedBytes(context);
    } catch (EvidenceUnavailableException e) {
      // the text is dropped on purpose: it may name a path or a host
      return new SealOutcome(
          SealStatus.EVIDENCE_UNAVAILABLE,
          Optional.empty(),
          "the evidence store could not acknowledge the sealed bytes; the ledger is unchanged");
    }
    try {
      putIntentObject(context);
    } catch (EvidenceUnavailableException e) {
      return new SealOutcome(
          SealStatus.EVIDENCE_UNAVAILABLE,
          Optional.empty(),
          "the evidence store could not acknowledge the intent object; the ledger is unchanged");
    }
    DeliveryLedger.SendingResult result = commitSending(context);
    return switch (result.status()) {
      case APPLIED ->
          new SealOutcome(SealStatus.SEALED, result.attempt(), "sealed; the delivery is SENDING");
      case STALE_LEASE ->
          new SealOutcome(
              SealStatus.STALE_LEASE,
              Optional.empty(),
              "the lease token is not the current one; the written objects are orphans");
      case STATE_MISMATCH ->
          new SealOutcome(
              SealStatus.STATE_MISMATCH,
              Optional.empty(),
              "the delivery is no longer queued; the written objects are orphans");
      case IN_FLIGHT_CONFLICT ->
          new SealOutcome(
              SealStatus.IN_FLIGHT_CONFLICT,
              Optional.empty(),
              "another delivery of the business key is in flight; the written objects are orphans");
    };
  }

  @Override
  public PreSendStatus beforeFirstByte(
      LeaseLedger.EndpointKey endpoint,
      DeliveryLedger.AttemptId attempt,
      LeaseLedger.LeaseToken token) {
    Objects.requireNonNull(endpoint, "endpoint");
    Objects.requireNonNull(attempt, "attempt");
    Objects.requireNonNull(token, "token");
    if (!gateResult().open()) {
      return PreSendStatus.GATE_CLOSED;
    }
    Optional<LeaseLedger.LeaseGrant> lease = ledger.currentLease(endpoint);
    if (lease.isEmpty() || !lease.get().token().equals(token)) {
      return PreSendStatus.STALE_LEASE;
    }
    // the marker statement of the ledger is idempotent: a gate that was already passed for this
    // attempt must not pass again
    Optional<DeliveryLedger.Attempt> row = ledger.attempt(attempt);
    if (row.isEmpty() || row.get().commitIssued()) {
      return PreSendStatus.MARKER_REFUSED;
    }
    long epoch;
    try {
      epoch = readEpoch();
    } catch (RuntimeException e) {
      // an outage, a damaged marker and a foreign key are all "unreadable": the send fails closed.
      // The catch is broad on purpose and also swallows an injected crash, so no crash point of
      // the harness may be placed on EvidenceStore.list or EvidenceStore.get (see CrashPoints)
      return PreSendStatus.EPOCH_MARKER_UNREADABLE;
    }
    if (epoch != token.restoreEpoch()) {
      return PreSendStatus.EPOCH_MARKER_DIFFERS;
    }
    return ledger.markCommitIssued(attempt, token)
        ? PreSendStatus.CLEAR
        : PreSendStatus.MARKER_REFUSED;
  }

  // ---------------------------------------------------------------------------------------------
  // the template methods (D-E10): the teeth of the shared suite override one each

  /** The send gate over this ledger and this evidence store. */
  protected SendGate.Result gateResult() {
    return gate.check(ledger, evidence);
  }

  /**
   * Puts the sealed bytes under their content-addressed key. The same bytes put again are not an
   * error ({@code ALREADY_PRESENT_IDENTICAL}); other bytes under the key are an integrity alarm.
   */
  protected void putSealedBytes(SealContext c) {
    SealRequest request = c.request();
    evidence.put(
        new EvidenceStore.PutRequest(
            SealedKeys.of(request.intent().artifactSha256()),
            request.bytes(),
            request.mediaType(),
            retention.retentionFor(SEALED_RETENTION_CLASS, c.intent().clockInstant())));
  }

  /**
   * Puts the {@code DeliveryIntent} journal object. An object that is already stored under the key
   * with the same payload is accepted only when it is byte for byte the planned one: the manifest
   * row names the hash of the planned bytes, and a stored object of other bytes would make the row
   * false (an attempt id is taken once, so this is an anomaly such as a restored database).
   */
  protected void putIntentObject(SealContext c) {
    JournalWriter.Outcome outcome = journal.write(c.intent());
    if (outcome == JournalWriter.Outcome.ALREADY_PRESENT_SAME_PAYLOAD) {
      EvidenceKey key = c.intent().key().evidenceKey();
      Sha256 planned = Sha256.of(c.intent().toBytes());
      Optional<EvidenceStore.StoredObject> stored = evidence.get(key);
      if (stored.isEmpty() || !stored.get().sha256().equals(planned)) {
        throw new EvidenceIntegrityException(
            "the intent object of an attempt exists with other bytes: " + key.path());
      }
    }
  }

  /** The one ledger unit: SENDING under the token, the attempt row and the manifest rows. */
  protected DeliveryLedger.SendingResult commitSending(SealContext c) {
    SealRequest request = c.request();
    return ledger.beginSending(
        request.delivery(),
        c.attempt(),
        c.token(),
        new DeliveryLedger.AttemptIntent(
            request.intent().artifactSha256(), request.fileName(), c.entries()));
  }

  /** The highest restore-epoch marker of the evidence tier, 0 when there is none. */
  protected long readEpoch() {
    return marker.current();
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * Everything that can be refused without an effect, in the order of the plan (T13 step 1). The
   * dry runs build the intent object and the attempt intent once, so that a text the journal or the
   * ledger cannot hold is an {@link IllegalArgumentException} before an attempt id is taken.
   */
  private Validated validate(SealRequest request) {
    DeliveryIntentPayload intent = request.intent();
    byte[] bytes = request.bytes();
    if (!Sha256.of(bytes).equals(intent.artifactSha256())) {
      throw new IllegalArgumentException(
          "the artifact hash of the intent is not the hash of the bytes");
    }
    if (!intent.delivery().equals(request.delivery())) {
      throw new IllegalArgumentException("the intent names another delivery than the request");
    }
    if (!intent.fileName().equals(request.fileName())) {
      throw new IllegalArgumentException("the intent names another file than the request");
    }
    requireStorableMediaType(request.mediaType());
    JournalScope scope = JournalScope.of(request.endpoint());
    DeliveryLedger.Delivery delivery =
        ledger
            .delivery(request.delivery())
            .orElseThrow(() -> new IllegalArgumentException("the delivery is unknown"));
    if (!delivery.key().endpoint().equals(request.endpoint())) {
      throw new IllegalArgumentException("the delivery belongs to another endpoint");
    }
    // dry runs: a text that the journal or the ledger cannot hold, found before any effect
    intentObject(request, scope, new DeliveryLedger.AttemptId(1), Instant.EPOCH);
    new DeliveryLedger.AttemptIntent(intent.artifactSha256(), request.fileName());
    return new Validated(scope, delivery, bytes.length);
  }

  /**
   * A media type that an evidence store may be unable to hold (an over-long header, a control
   * character, an unpaired surrogate) is refused here, before an attempt id is taken, so that the
   * outcome does not depend on the adapter. The message names the field and echoes nothing.
   */
  private static void requireStorableMediaType(String mediaType) {
    boolean storable = !mediaType.isBlank() && mediaType.length() <= MAX_MEDIA_TYPE_LENGTH;
    for (int i = 0; storable && i < mediaType.length(); ) {
      int codePoint = mediaType.codePointAt(i);
      // a lone surrogate comes back from codePointAt as itself, a pair as one supplementary point
      boolean lone = codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE;
      storable = !Character.isISOControl(codePoint) && !lone;
      i += Character.charCount(codePoint);
    }
    if (!storable) {
      throw new IllegalArgumentException(
          "the media type of the request must be 1 to "
              + MAX_MEDIA_TYPE_LENGTH
              + " characters without a control character or an unpaired surrogate");
    }
  }

  /**
   * What the validation found out and the seal needs again: the scope, the delivery row and the
   * length.
   */
  private record Validated(JournalScope scope, DeliveryLedger.Delivery delivery, int length) {}

  private SealContext context(
      SealRequest request, Validated validated, DeliveryLedger.AttemptId attempt) {
    Instant now = clock.now();
    JournalObject intent = intentObject(request, validated.scope(), attempt, now);
    byte[] intentBytes = intent.toBytes();
    List<ManifestLedger.ObjectEntry> entries = new ArrayList<>();
    entries.add(
        new ManifestLedger.ObjectEntry(
            SealedKeys.of(request.intent().artifactSha256()),
            ManifestLedger.ObjectClass.SEALED_FILE,
            request.intent().artifactSha256(),
            validated.length()));
    entries.add(
        new ManifestLedger.ObjectEntry(
            intent.key().evidenceKey(),
            ManifestLedger.ObjectClass.JOURNAL_A,
            Sha256.of(intentBytes),
            intentBytes.length));
    return new SealContext(request, attempt, request.token(), entries, intent);
  }

  /**
   * The {@code DeliveryIntent} object of an attempt: the key subject and the payload field {@code
   * attempt_id} come from the one attempt id given here.
   */
  private static JournalObject intentObject(
      SealRequest request,
      JournalScope scope,
      DeliveryLedger.AttemptId attempt,
      Instant clockInstant) {
    DeliveryIntentPayload intent = request.intent();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("attempt_id", attempt.value());
    payload.put("delivery_id", intent.delivery().value());
    payload.put("business_key", intent.businessKey());
    payload.put("trn", intent.trn());
    payload.put("version_seq", intent.versionSeq());
    payload.put("action", intent.action());
    payload.put("logical_hash", intent.logicalHash().hex());
    payload.put("artifact_sha256", intent.artifactSha256().hex());
    payload.put("file_name", intent.fileName());
    payload.put("context_ids", intent.contextIds().stream().map(Sha256::hex).toList());
    return new JournalObject(
        new JournalKey.ClassA(
            scope,
            request.token().restoreEpoch(),
            JournalType.DELIVERY_INTENT,
            Long.toString(attempt.value()),
            1),
        JournalType.DELIVERY_INTENT,
        JournalSchemas.V1,
        clockInstant,
        payload);
  }
}
