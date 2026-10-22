package dev.regpack.testkit.contract;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import dev.regpack.ports.connector.Transport;
import dev.regpack.ports.connector.Transport.OutboundFile;
import dev.regpack.ports.connector.Transport.SendResult;
import dev.regpack.ports.connector.Transport.SendStatus;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore.StoredObject;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import dev.regpack.ports.evidence.journal.JournalKey;
import dev.regpack.ports.evidence.journal.JournalObject;
import dev.regpack.ports.evidence.journal.JournalScope;
import dev.regpack.ports.evidence.journal.JournalType;
import dev.regpack.ports.evidence.seal.Sealing;
import dev.regpack.ports.evidence.seal.Sealing.DeliveryIntentPayload;
import dev.regpack.ports.evidence.seal.Sealing.PreSendStatus;
import dev.regpack.ports.evidence.seal.Sealing.SealOutcome;
import dev.regpack.ports.evidence.seal.Sealing.SealRequest;
import dev.regpack.ports.evidence.seal.Sealing.SealStatus;
import dev.regpack.ports.ledger.DeliveryLedger.Attempt;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptId;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptIntent;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.DeliveryLedger.SendingResult;
import dev.regpack.ports.ledger.DeliveryLedger.SendingStatus;
import dev.regpack.ports.ledger.DeliveryLedger.TransitionStatus;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseResult;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.ManifestLedger.ManifestRow;
import dev.regpack.ports.ledger.ManifestLedger.ObjectClass;
import dev.regpack.ports.ledger.ManifestLedger.ObjectEntry;
import dev.regpack.ports.ledger.ManifestLedger.Provenance;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.fault.FaultInjector;
import dev.regpack.testkit.fault.FaultPlan;
import dev.regpack.testkit.fault.SimulatedCrash;
import dev.regpack.testkit.sim.DestinationSimulator;
import dev.regpack.testkit.sim.Scenario;
import dev.regpack.testkit.sim.ScenarioContext;
import dev.regpack.testkit.sim.Scenarios;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The crash-point harness and the I1 scenarios (durable before effect) of spec 11.2 steps 2 to 4,
 * as a suite that every pair of ledger and evidence store binds (T14a): the call order of a seal,
 * what a seal leaves behind when the evidence store is out, when the process dies at one of the
 * five crash points of {@link CrashPoints}, when the lease or the restore epoch is no longer the
 * sender's, when the gate is closed, when the gate is handed an attempt that is not the lease's (a
 * bare attempt of the ledger, an attempt of another endpoint, a delivery that left SENDING), and
 * when a commit that the standby did not confirm is reported by the ledger. The ports are wrapped
 * in {@link FaultInjector} proxies, the destination is the P0 simulator and the sender is the pair
 * {@code seal} and {@code transport.send} with {@code beforeFirstByte} as its commit gate.
 *
 * <p>No crash point of {@link CrashPoints} may be placed on {@code EvidenceStore.list}, {@code
 * EvidenceStore.get}, {@code EvidenceStore.durability} or {@code LedgerDurability.durability}: the
 * sealer reads these fail-closed (every {@link RuntimeException} is "unreadable"), so an injected
 * crash would be swallowed and not end the run. Outages of those calls are staged with a {@code
 * CallHook} that throws an {@code EvidenceUnavailableException}, as {@code
 * anUnreachableEvidenceTierAtTheGateFailsClosed} does. A test of {@code CrashPoints} pins this.
 *
 * <p>The invariant is stated once, in {@code assertI1}: when the destination holds the file, the
 * evidence store holds the sealed object and the intent object, the manifest names both with the
 * hashes of the stored bytes, the attempt has its marker and the delivery is SENDING. The scenarios
 * that the order of the calls decides also compare the trace of the ports with the documented
 * order, because a state check alone cannot tell "put, then commit" from "commit, then put".
 *
 * <p>A binding supplies the stores and the {@link Sealing} implementation; the suite never reads
 * the process environment and uses no sleep (latches and barriers only). Run against the in-memory
 * ports these are building blocks of I1, not evidence for it (spec 14.1): the evidence is the run
 * on real PostgreSQL and P-SEALED.
 */
public interface SealScenarioContract {
  /** The ttl of every lease the scenarios take. */
  Duration TTL = Duration.ofSeconds(60);

  /** How long a latch or a barrier may take before the scenario fails. */
  long WAIT_SECONDS = 20;

  /** The number of threads of the race for one delivery. */
  int RACERS = 8;

  /** The attempt ids that a scenario looks at: 1 to this number. */
  int MAX_ATTEMPT_ID = 64;

  /**
   * A fresh, empty ledger for each test; the same instance for the whole test. The attempt ids of a
   * fresh ledger start at 1: the scenarios look for the attempts of a delivery among the ids 1 to
   * {@link #MAX_ATTEMPT_ID}.
   */
  LedgerStore ledger();

  /** A fresh, empty evidence store for each test; the same instance for the whole test. */
  EvidenceStore evidence();

  /** The clock both stores read; its start instant is the same for every test. */
  ManualClock clock();

  /**
   * The sealer over the given ports. The suite passes ports wrapped in fault-injecting proxies. The
   * binding builds a {@code DurableSeal} with the production send gate when {@code productionGate}
   * is true and with no requirement when it is false, and with a retention policy of 1,826 days.
   */
  Sealing sealing(LedgerStore ledger, EvidenceStore evidence, boolean productionGate);

  // ---------------------------------------------------------------------------------------------
  // fixtures, public so that a binding can add scenarios of its own

  /** The actions that the fixtures rotate over (the report actions of the intent object). */
  List<String> ACTIONS = List.of("NEWT", "CANC", "REPL");

  /**
   * The number that the fixtures take from a business key: the digits at its end ({@code BK-12} is
   * 12), else its length. It is what makes the version and the action differ from delivery to
   * delivery, so that a sealer that writes a constant for either field is seen.
   */
  static long numberIn(String businessKey) {
    int start = businessKey.length();
    while (start > 0
        && start > businessKey.length() - 9
        && Character.digit(businessKey.charAt(start - 1), 10) >= 0
        && businessKey.charAt(start - 1) < 128) {
      start--;
    }
    return start == businessKey.length()
        ? businessKey.length()
        : Long.parseLong(businessKey.substring(start));
  }

  /**
   * The {@code version_seq} of the fixture delivery of a business key: 1 plus {@link #numberIn}.
   * The ledger delivery and the intent object of {@link #request} both carry it.
   */
  static long versionOf(String businessKey) {
    return 1 + numberIn(businessKey);
  }

  /**
   * The action of the fixture delivery of a business key: {@link #ACTIONS} rotated by its number.
   */
  static String actionOf(String businessKey) {
    return ACTIONS.get((int) (numberIn(businessKey) % ACTIONS.size()));
  }

  /**
   * A queued delivery of the fixture business key, with the version of {@link #versionOf}, which is
   * the version that {@link #request} puts into the intent object (a seal refuses an intent that
   * names another one).
   */
  static DeliveryId queued(LedgerStore ledger, EndpointKey endpoint, String businessKey) {
    return LedgerFixtures.queuedDelivery(ledger, endpoint, businessKey, versionOf(businessKey));
  }

  /**
   * A request to seal the delivery: the file is {@code file-<businessKey>.xml}, the intent object
   * names the business key, the TRN {@code TRN-<businessKey>}, the version {@link #versionOf}, the
   * action {@link #actionOf} and one context id.
   */
  static SealRequest request(
      EndpointKey endpoint,
      LeaseToken token,
      DeliveryId delivery,
      String businessKey,
      byte[] bytes) {
    String fileName = "file-" + businessKey + ".xml";
    Sha256 sha = Sha256.of(bytes);
    DeliveryIntentPayload intent =
        new DeliveryIntentPayload(
            delivery,
            businessKey,
            "TRN-" + businessKey,
            versionOf(businessKey),
            actionOf(businessKey),
            Sha256.ofUtf8("logical-" + businessKey),
            sha,
            fileName,
            List.of(Sha256.ofUtf8("context-" + businessKey)));
    return new SealRequest(endpoint, token, delivery, fileName, bytes, "application/xml", intent);
  }

  /** The lease of {@code endpoint} for {@code holder}, for {@link #TTL}; fails if not granted. */
  static LeaseToken lease(LedgerStore ledger, EndpointKey endpoint, String holder) {
    LeaseResult result = ledger.acquire(endpoint, holder, TTL);
    if (!(result instanceof LeaseResult.Granted granted)) {
      throw new AssertionError("the lease was not granted: " + result);
    }
    return granted.grant().token();
  }

  /** The bytes of the report of a business key: {@code <report>BK</report>} in UTF-8. */
  static byte[] reportOf(String businessKey) {
    return ("<report>" + businessKey + "</report>").getBytes(UTF_8);
  }

  /** One queued delivery under a held lease, and the request that seals it. */
  record Case(
      EndpointKey endpoint,
      LeaseToken token,
      DeliveryId delivery,
      String businessKey,
      SealRequest request) {
    public Sha256 sha() {
      return request.intent().artifactSha256();
    }

    /** The file as the destination receives it. */
    public OutboundFile file() {
      return new OutboundFile(
          "file-" + businessKey, request.fileName(), request.bytes(), sha(), request.mediaType());
    }

    /** The same delivery and bytes under another lease token. */
    public Case withToken(LeaseToken other) {
      return new Case(
          endpoint,
          other,
          delivery,
          businessKey,
          SealScenarioContract.request(endpoint, other, delivery, businessKey, request.bytes()));
    }
  }

  /**
   * One run: the trace of the port calls, the destination, the transport and the sealer over the
   * wrapped ports. (The stores themselves are {@code ledger()} and {@code evidence()} of the
   * suite.)
   */
  record Wiring(
      FaultInjector faults,
      DestinationSimulator destination,
      Transport transport,
      Sealing sealing) {}

  // ---------------------------------------------------------------------------------------------
  // building blocks

  private DestinationSimulator destination() {
    return DestinationSimulator.withScenario(clock(), Scenarios.acpt(Duration.ofSeconds(1)));
  }

  private Wiring wire(FaultPlan plan, DestinationSimulator destination, boolean productionGate) {
    return wire(
        plan, destination, productionGate, UnaryOperator.identity(), UnaryOperator.identity());
  }

  /**
   * Wraps the stores of the test, first in the given shapes and then in a {@link FaultInjector}
   * with the plan, and builds the sealer over the result. Each call is a new process: a new trace
   * and a new sealer over the same stores and the same destination.
   */
  private Wiring wire(
      FaultPlan plan,
      DestinationSimulator destination,
      boolean productionGate,
      UnaryOperator<LedgerStore> ledgerShape,
      UnaryOperator<EvidenceStore> evidenceShape) {
    FaultInjector faults = new FaultInjector(plan, clock());
    LedgerStore wrappedLedger = faults.wrap(LedgerStore.class, ledgerShape.apply(ledger()));
    EvidenceStore wrappedEvidence =
        faults.wrap(EvidenceStore.class, evidenceShape.apply(evidence()));
    Transport transport = faults.wrap(Transport.class, destination);
    return new Wiring(
        faults, destination, transport, sealing(wrappedLedger, wrappedEvidence, productionGate));
  }

  /** A new endpoint named {@code name}, a lease for it and one queued delivery. */
  private Case newCase(String name, String businessKey) {
    EndpointKey endpoint = LedgerFixtures.endpoint(name);
    return newDelivery(
        endpoint, lease(ledger(), endpoint, "A"), businessKey, reportOf(businessKey));
  }

  private Case newDelivery(
      EndpointKey endpoint, LeaseToken token, String businessKey, byte[] bytes) {
    DeliveryId delivery = queued(ledger(), endpoint, businessKey);
    return new Case(
        endpoint,
        token,
        delivery,
        businessKey,
        request(endpoint, token, delivery, businessKey, bytes));
  }

  /** Seals through the wiring and requires success. */
  private static AttemptId sealed(Wiring wiring, Case c) {
    SealOutcome outcome = wiring.sealing().seal(c.request());
    assertThat(outcome.status()).as("the seal: " + outcome.detail()).isEqualTo(SealStatus.SEALED);
    return outcome.attempt().orElseThrow();
  }

  /** The sender's step: hand the file to the transport with the gate of the sealer. */
  private static SendResult send(Wiring wiring, Case c, AttemptId attempt) {
    return wiring
        .transport()
        .send(
            c.file(),
            () ->
                wiring.sealing().beforeFirstByte(c.endpoint(), attempt, c.token())
                    == PreSendStatus.CLEAR);
  }

  private static void assertCrashedAt(Throwable thrown, String point, int hit) {
    assertThat(thrown)
        .as("the process must die at " + point + " #" + hit)
        .isInstanceOfSatisfying(
            SimulatedCrash.class, crash -> assertThat(crash.point()).isEqualTo(point + "#" + hit));
  }

  /** A destination scenario that runs {@code atReceipt} at the instant the file arrives. */
  private static Scenario probe(Consumer<Scenario.ReceivedFile> atReceipt) {
    return new Scenario() {
      @Override
      public String name() {
        return "RECEIPT_PROBE";
      }

      @Override
      public Disposition onReceive(ReceivedFile file, ScenarioContext context) {
        atReceipt.accept(file);
        return Disposition.ACCEPT;
      }
    };
  }

  private static void awaitBarrier(CyclicBarrier barrier) {
    try {
      barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting for the other racers", e);
    } catch (BrokenBarrierException | TimeoutException e) {
      throw new AssertionError(
          "the racers did not all arrive within " + WAIT_SECONDS + " seconds", e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // what the stores hold (read through the raw ports, so that the trace stays the sealer's own)

  private List<String> evidenceKeys() {
    List<String> paths = new ArrayList<>();
    evidence().list("", Optional.empty(), 1_000).forEach(key -> paths.add(key.path()));
    return paths;
  }

  private List<ManifestRow> manifest() {
    return ledger().manifestRows("", Optional.empty(), 1_000);
  }

  /** The evidence keys that have no manifest row: the orphans of spec 7.2. */
  private List<String> unrecorded() {
    Set<String> recorded = new HashSet<>();
    manifest().forEach(row -> recorded.add(row.entry().key().path()));
    List<String> orphans = new ArrayList<>(evidenceKeys());
    orphans.removeAll(recorded);
    return orphans;
  }

  /** The key of an orphan that must be a journal object; anything else fails the scenario. */
  private static JournalKey journalKeyOf(String orphan) {
    assertThat(orphan).as("an orphan that is no journal object").startsWith("journal/");
    return JournalKey.parse(new EvidenceKey(orphan));
  }

  /** The manifest holds the two rows of one seal, the sealed file and the intent object. */
  private void assertTheRowsOfOneSeal() {
    assertThat(manifest())
        .extracting(row -> row.entry().objectClass())
        .containsExactlyInAnyOrder(ObjectClass.SEALED_FILE, ObjectClass.JOURNAL_A);
  }

  private DeliveryState stateOf(DeliveryId delivery) {
    return ledger().delivery(delivery).orElseThrow().state();
  }

  private List<Attempt> attemptsOf(DeliveryId delivery) {
    List<Attempt> attempts = new ArrayList<>();
    for (long id = 1; id <= MAX_ATTEMPT_ID; id++) {
      ledger()
          .attempt(new AttemptId(id))
          .filter(attempt -> attempt.delivery().equals(delivery))
          .ifPresent(attempts::add);
    }
    return attempts;
  }

  private static EvidenceKey intentKey(Case c, long attempt) {
    return new JournalKey.ClassA(
            JournalScope.of(c.endpoint()),
            c.token().restoreEpoch(),
            JournalType.DELIVERY_INTENT,
            Long.toString(attempt),
            1)
        .evidenceKey();
  }

  /**
   * The calls of the ports that decide the order of a seal, in the order in which they returned,
   * each as {@code <Interface>.<method>#<n>}.
   */
  private static List<String> completed(FaultInjector faults) {
    Set<String> decisive =
        Set.of(
            "LedgerStore.allocateAttemptId",
            "EvidenceStore.put",
            "LedgerStore.beginSending",
            "LedgerStore.recordObjects",
            "LedgerStore.markCommitIssued",
            "Transport.send");
    List<String> done = new ArrayList<>();
    for (String line : faults.trace()) {
      int at = line.indexOf(":AFTER#");
      if (at > 0 && decisive.contains(line.substring(0, at))) {
        done.add(line.substring(0, at) + "#" + line.substring(at + ":AFTER#".length()));
      }
    }
    return done;
  }

  /** The stored object of a manifest row; the row's hash and size are those of the stored bytes. */
  private StoredObject storedFor(ManifestRow row) {
    EvidenceKey key = row.entry().key();
    StoredObject stored =
        evidence()
            .get(key)
            .orElseThrow(
                () ->
                    new AssertionError(
                        "the manifest names an object the evidence store does not hold: "
                            + key.path()));
    assertThat(stored.sha256())
        .as("the stored hash of " + key.path())
        .isEqualTo(row.entry().sha256());
    assertThat(Sha256.of(stored.bytes()))
        .as("the hash of the bytes of " + key.path())
        .isEqualTo(row.entry().sha256());
    assertThat((long) stored.bytes().length)
        .as("the size of " + key.path())
        .isEqualTo(row.entry().sizeBytes());
    assertThat(stored.retention().retainUntil())
        .as("the retention of " + key.path())
        .isAfter(clock().now());
    return stored;
  }

  /** Nothing of the delivery has reached the ledger: queued, no attempt, no manifest row. */
  private void assertLedgerUntouched(Case c) {
    assertThat(stateOf(c.delivery())).as("state of the delivery").isEqualTo(DeliveryState.QUEUED);
    assertThat(attemptsOf(c.delivery())).as("attempts of the delivery").isEmpty();
    assertThat(manifest()).as("manifest rows").isEmpty();
  }

  /**
   * Everything that justifies a file that is at the destination, or is arriving there at this
   * instant: the delivery is SENDING under the token, its one attempt has the marker, the manifest
   * names the sealed file and the intent object of that attempt, and the evidence store holds both
   * with the hashes and sizes of the manifest.
   */
  private void assertJustified(Case c, Scenario.ReceivedFile received) {
    assertThat(received.sha256()).as("the file at the destination").isEqualTo(c.sha());
    assertThat(stateOf(c.delivery()))
        .as("the delivery of a file that left")
        .isEqualTo(DeliveryState.SENDING);
    List<Attempt> attempts = attemptsOf(c.delivery());
    assertThat(attempts).as("the attempts of a delivery whose file left").hasSize(1);
    Attempt attempt = attempts.get(0);
    assertThat(attempt.commitIssued()).as("the marker of the attempt").isTrue();
    assertThat(attempt.token()).isEqualTo(c.token());
    assertThat(attempt.artifactSha256()).isEqualTo(c.sha());
    assertThat(attempt.fileName()).isEqualTo(c.request().fileName());

    List<ManifestRow> rows = manifest();
    List<ManifestRow> sealedRows =
        rows.stream()
            .filter(
                row ->
                    row.entry().objectClass() == ObjectClass.SEALED_FILE
                        && row.entry().sha256().equals(c.sha()))
            .toList();
    assertThat(sealedRows).as("the manifest row of the sealed file").hasSize(1);
    assertThat(sealedRows.get(0).provenance()).isEqualTo(Provenance.WRITTEN);
    assertThat(storedFor(sealedRows.get(0)).bytes())
        .as("the sealed object holds the bytes that left")
        .isEqualTo(received.bytes());

    EvidenceKey intentKey = intentKey(c, attempt.id().value());
    List<ManifestRow> intentRows =
        rows.stream().filter(row -> row.entry().key().equals(intentKey)).toList();
    assertThat(intentRows).as("the manifest row of the intent object").hasSize(1);
    ManifestRow intentRow = intentRows.get(0);
    assertThat(intentRow.entry().objectClass()).isEqualTo(ObjectClass.JOURNAL_A);
    assertThat(intentRow.provenance()).isEqualTo(Provenance.WRITTEN);
    assertThat(intentRow.attempt()).contains(attempt.id());
    JournalObject intent =
        JournalObject.fromBytes(
            JournalKey.parse(intentRow.entry().key()), storedFor(intentRow).bytes());
    assertThat(intent.type()).isEqualTo(JournalType.DELIVERY_INTENT);
    DeliveryIntentPayload expected = c.request().intent();
    assertThat(intent.payload())
        .containsEntry("attempt_id", attempt.id().value())
        .containsEntry("delivery_id", c.delivery().value())
        .containsEntry("artifact_sha256", c.sha().hex())
        // the identity that restore rebuilds (I10): the fixtures make the version and the action
        // differ from delivery to delivery, so that a constant in the sealer is found
        .containsEntry("business_key", expected.businessKey())
        .containsEntry("trn", expected.trn())
        .containsEntry("version_seq", expected.versionSeq())
        .containsEntry("action", expected.action())
        .containsEntry("logical_hash", expected.logicalHash().hex())
        .containsEntry("file_name", expected.fileName());
    assertThat(expected.versionSeq())
        .as("the ledger delivery and the intent of the fixture agree on the version")
        .isEqualTo(ledger().delivery(c.delivery()).orElseThrow().key().versionSeq());
  }

  /** I1 at this moment: if the file of {@code c} is at the destination, it is justified. */
  private void assertI1(Case c, DestinationSimulator destination) {
    List<Scenario.ReceivedFile> files =
        destination.receivedFiles().stream()
            .filter(file -> file.fileId().equals(c.file().fileId()))
            .toList();
    assertThat(files)
        .as("copies of the file of one delivery at the destination")
        .hasSizeLessThanOrEqualTo(1);
    files.forEach(file -> assertJustified(c, file));
  }

  // ---------------------------------------------------------------------------------------------
  // the order

  @Test
  default void noByteReachesTheDestinationBeforeBothObjectsAndTheSendingCommitAreDurable() {
    Case c = newCase("ORDER", "BK-ORDER");
    AtomicReference<Wiring> wiring = new AtomicReference<>();
    List<List<String>> atReceipt = new CopyOnWriteArrayList<>();
    DestinationSimulator destination =
        DestinationSimulator.withScenario(
            clock(),
            probe(
                file -> {
                  // the instant the file arrives: what the sealer had completed, and what holds
                  atReceipt.add(completed(wiring.get().faults()));
                  assertJustified(c, file);
                }));
    Wiring w = wire(FaultPlan.none(), destination, false);
    wiring.set(w);

    AttemptId attempt = sealed(w, c);

    assertThat(destination.receivedFiles()).as("sealing sends nothing").isEmpty();
    assertThat(completed(w.faults()))
        .containsExactly(
            "LedgerStore.allocateAttemptId#1",
            "EvidenceStore.put#1",
            "EvidenceStore.put#2",
            "LedgerStore.beginSending#1");
    assertThat(send(w, c, attempt).status()).isEqualTo(SendStatus.DELIVERED);
    assertThat(atReceipt).hasSize(1);
    assertThat(atReceipt.get(0))
        .as("what had returned when the file arrived")
        .containsExactly(
            "LedgerStore.allocateAttemptId#1",
            "EvidenceStore.put#1",
            "EvidenceStore.put#2",
            "LedgerStore.beginSending#1",
            "LedgerStore.markCommitIssued#1");
    assertThat(completed(w.faults())).last().isEqualTo("Transport.send#1");
    assertI1(c, destination);
  }

  @Test
  default void afterSealingTheManifestHoldsBothObjectsWithTheirWriteTimeHashes() {
    Case c = newCase("MANIFEST", "BK-MANIFEST");
    Wiring w = wire(FaultPlan.none(), destination(), false);

    AttemptId attempt = sealed(w, c);

    List<ManifestRow> rows = manifest();
    assertThat(rows).hasSize(2);
    assertThat(rows)
        .extracting(row -> row.entry().objectClass())
        .containsExactlyInAnyOrder(ObjectClass.SEALED_FILE, ObjectClass.JOURNAL_A);
    for (ManifestRow row : rows) {
      StoredObject stored = storedFor(row);
      assertThat(row.provenance()).isEqualTo(Provenance.WRITTEN);
      assertThat(row.attempt()).contains(attempt);
      assertThat(row.writtenAt()).isEqualTo(clock().now());
      if (row.entry().objectClass() == ObjectClass.SEALED_FILE) {
        assertThat(stored.bytes()).isEqualTo(c.request().bytes());
        assertThat(row.entry().sha256()).isEqualTo(c.sha());
      } else {
        // the hash is that of the object as stored, not the payload hash of the envelope
        assertThat(row.entry().key()).isEqualTo(intentKey(c, attempt.value()));
        assertThat(row.entry().sha256())
            .isNotEqualTo(
                JournalObject.fromBytes(JournalKey.parse(row.entry().key()), stored.bytes())
                    .payloadSha256());
      }
    }
    // the rows went with the SENDING unit: the sealer made no ledger call of its own for them
    assertThat(w.faults().trace()).noneMatch(line -> line.startsWith("LedgerStore.recordObjects"));
    assertThat(completed(w.faults())).containsOnlyOnce("LedgerStore.beginSending#1");
    assertThat(stateOf(c.delivery())).isEqualTo(DeliveryState.SENDING);
  }

  // ---------------------------------------------------------------------------------------------
  // an outage of the evidence store

  @Test
  default void anEvidenceOutageBeforeAnyPutFailsClosedAndLeavesTheLedgerUntouched() {
    Case c = newCase("OUTAGE1", "BK-OUTAGE1");
    DestinationSimulator destination = destination();
    Wiring w =
        wire(
            FaultPlan.failAt(
                "EvidenceStore.put:BEFORE",
                1,
                new EvidenceUnavailableException("secret-host-down")),
            destination,
            false);

    SealOutcome outcome = w.sealing().seal(c.request());

    assertThat(outcome.status()).isEqualTo(SealStatus.EVIDENCE_UNAVAILABLE);
    assertThat(outcome.attempt()).isEmpty();
    assertThat(outcome.detail())
        .isNotBlank()
        .doesNotContain("secret-host-down")
        .doesNotContain(c.businessKey());
    assertThat(evidenceKeys()).isEmpty();
    assertLedgerUntouched(c);
    assertThat(destination.receivedFiles()).isEmpty();

    // the store is back (the failure was one call): the same request seals and sends
    AttemptId attempt = sealed(w, c);
    assertThat(send(w, c, attempt).status()).isEqualTo(SendStatus.DELIVERED);
    assertI1(c, destination);
  }

  @Test
  default void anOutageAfterTheSealedBytesLeavesAnOrphanSealedObjectOnly() {
    Case c = newCase("OUTAGE2", "BK-OUTAGE2");
    DestinationSimulator destination = destination();
    Wiring w =
        wire(
            FaultPlan.failAt(
                "EvidenceStore.put:BEFORE",
                2,
                new EvidenceUnavailableException("secret-host-down")),
            destination,
            false);

    SealOutcome outcome = w.sealing().seal(c.request());

    assertThat(outcome.status()).isEqualTo(SealStatus.EVIDENCE_UNAVAILABLE);
    assertThat(outcome.attempt()).isEmpty();
    assertThat(outcome.detail()).isNotBlank().doesNotContain("secret-host-down");
    List<String> keys = evidenceKeys();
    assertThat(keys).as("only the sealed object was acknowledged").hasSize(1);
    assertThat(evidence().get(new EvidenceKey(keys.get(0))).orElseThrow().bytes())
        .isEqualTo(c.request().bytes());
    assertLedgerUntouched(c);
    assertThat(destination.receivedFiles()).isEmpty();

    // the retry finds the sealed object, writes the intent object and leaves no orphan
    AttemptId attempt = sealed(w, c);
    assertTheRowsOfOneSeal();
    assertThat(unrecorded()).isEmpty();
    assertThat(send(w, c, attempt).status()).isEqualTo(SendStatus.DELIVERED);
    assertI1(c, destination);
  }

  // ---------------------------------------------------------------------------------------------
  // the crash points

  @Test
  default void aCrashAfterTheSealedBytesLeavesOneOrphanObject() {
    Case c = newCase("CRASH1", "BK-CRASH1");
    DestinationSimulator destination = destination();
    Wiring w = wire(FaultPlan.crashAt(CrashPoints.AFTER_SEALED_PUT, 1), destination, false);

    Throwable crash = catchThrowable(() -> w.sealing().seal(c.request()));

    assertCrashedAt(crash, CrashPoints.AFTER_SEALED_PUT, 1);
    List<String> keys = evidenceKeys();
    assertThat(keys).as("the sealed object alone").hasSize(1);
    assertThat(evidence().get(new EvidenceKey(keys.get(0))).orElseThrow().bytes())
        .isEqualTo(c.request().bytes());
    assertLedgerUntouched(c);
    assertThat(destination.receivedFiles()).isEmpty();
    assertI1(c, destination);

    // a fresh process seals again: one sealed file row, one intent row, no orphan left
    Wiring restarted = wire(FaultPlan.none(), destination, false);
    AttemptId attempt = sealed(restarted, c);
    assertTheRowsOfOneSeal();
    assertThat(unrecorded()).as("evidence without a manifest row").isEmpty();
    assertThat(send(restarted, c, attempt).status()).isEqualTo(SendStatus.DELIVERED);
    assertI1(c, destination);
  }

  @Test
  default void aCrashAfterTheIntentPutLeavesAnOrphanIntentAndNoSendingRow() {
    Case c = newCase("CRASH2", "BK-CRASH2");
    DestinationSimulator destination = destination();
    Wiring w = wire(FaultPlan.crashAt(CrashPoints.AFTER_INTENT_PUT, 2), destination, false);

    Throwable crash = catchThrowable(() -> w.sealing().seal(c.request()));

    assertCrashedAt(crash, CrashPoints.AFTER_INTENT_PUT, 2);
    assertThat(evidenceKeys()).as("the sealed object and the intent object").hasSize(2);
    assertLedgerUntouched(c);
    assertThat(destination.receivedFiles()).isEmpty();
    assertI1(c, destination);

    // a fresh process seals again; the old intent object is the only evidence without a row
    Wiring restarted = wire(FaultPlan.none(), destination, false);
    AttemptId attempt = sealed(restarted, c);
    assertTheRowsOfOneSeal();
    List<String> orphans = unrecorded();
    assertThat(orphans).as("evidence without a manifest row").hasSize(1);
    assertThat(journalKeyOf(orphans.get(0)))
        .isInstanceOfSatisfying(
            JournalKey.ClassA.class,
            key -> {
              assertThat(key.type()).isEqualTo(JournalType.DELIVERY_INTENT);
              assertThat(key.subjectId()).isNotEqualTo(Long.toString(attempt.value()));
            });
    assertThat(send(restarted, c, attempt).status()).isEqualTo(SendStatus.DELIVERED);
    assertI1(c, destination);
  }

  @Test
  default void aCrashAfterTheSendingCommitLeavesSendingWithoutMarkerAndNoByte() {
    Case c = newCase("CRASH3", "BK-CRASH3");
    DestinationSimulator destination = destination();
    Wiring w = wire(FaultPlan.crashAt(CrashPoints.AFTER_SENDING_COMMIT, 1), destination, false);

    Throwable crash = catchThrowable(() -> w.sealing().seal(c.request()));

    assertCrashedAt(crash, CrashPoints.AFTER_SENDING_COMMIT, 1);
    assertThat(stateOf(c.delivery())).isEqualTo(DeliveryState.SENDING);
    List<Attempt> attempts = attemptsOf(c.delivery());
    assertThat(attempts).hasSize(1);
    assertThat(attempts.get(0).commitIssued()).as("the marker").isFalse();
    // the manifest rows were committed with the state change, in one unit
    List<ManifestRow> rows = manifest();
    assertThat(rows).as("both rows, committed with SENDING").hasSize(2);
    rows.forEach(
        row -> {
          storedFor(row);
          assertThat(row.attempt()).contains(attempts.get(0).id());
        });
    assertThat(evidenceKeys()).hasSize(2);
    assertThat(destination.receivedFiles()).isEmpty();
    assertI1(c, destination);

    // a fresh process cannot seal it again, and no manifest row is added
    Wiring restarted = wire(FaultPlan.none(), destination, false);
    SealOutcome second = restarted.sealing().seal(c.request());
    assertThat(second.status()).isIn(SealStatus.STATE_MISMATCH, SealStatus.IN_FLIGHT_CONFLICT);
    assertThat(manifest()).isEqualTo(rows);
    assertThat(attemptsOf(c.delivery())).hasSize(1);
    assertThat(destination.receivedFiles()).isEmpty();
  }

  @Test
  default void aCrashAfterTheMarkerLeavesTheMarkerAndNoByte() {
    Case c = newCase("CRASH4", "BK-CRASH4");
    DestinationSimulator destination = destination();
    Wiring w = wire(FaultPlan.crashAt(CrashPoints.AFTER_MARKER, 1), destination, false);
    AttemptId attempt = sealed(w, c);

    Throwable crash = catchThrowable(() -> send(w, c, attempt));

    assertCrashedAt(crash, CrashPoints.AFTER_MARKER, 1);
    assertThat(destination.receivedFiles()).as("no byte has left").isEmpty();
    assertThat(stateOf(c.delivery())).isEqualTo(DeliveryState.SENDING);
    assertThat(ledger().attempt(attempt).orElseThrow().commitIssued()).as("the marker").isTrue();
    assertTheRowsOfOneSeal();
    List<ManifestRow> rows = manifest();
    assertI1(c, destination);

    // a fresh process can neither seal again nor pass the gate again: the delivery is in doubt
    Wiring restarted = wire(FaultPlan.none(), destination, false);
    assertThat(restarted.sealing().seal(c.request()).status())
        .isIn(SealStatus.STATE_MISMATCH, SealStatus.IN_FLIGHT_CONFLICT);
    assertThat(manifest()).isEqualTo(rows);
    assertThat(restarted.sealing().beforeFirstByte(c.endpoint(), attempt, c.token()))
        .isEqualTo(PreSendStatus.MARKER_REFUSED);
    assertThat(send(restarted, c, attempt).status()).isEqualTo(SendStatus.ABORTED_BY_GATE);
    assertThat(destination.receivedFiles()).isEmpty();
    assertI1(c, destination);
  }

  @Test
  default void aCrashAfterTheFirstByteLeavesTheFileDeliveredTheMarkerSetAndBothObjectsDurable() {
    Case c = newCase("CRASH5", "BK-CRASH5");
    DestinationSimulator destination = destination();
    Wiring w = wire(FaultPlan.crashAt(CrashPoints.AFTER_FIRST_BYTE, 1), destination, false);
    AttemptId attempt = sealed(w, c);

    Throwable crash = catchThrowable(() -> send(w, c, attempt));

    assertCrashedAt(crash, CrashPoints.AFTER_FIRST_BYTE, 1);
    assertThat(destination.receivedFiles()).as("the file is at the destination").hasSize(1);
    assertThat(ledger().attempt(attempt).orElseThrow().commitIssued()).as("the marker").isTrue();
    assertTheRowsOfOneSeal();
    List<ManifestRow> rows = manifest();
    assertI1(c, destination);

    // a fresh process cannot seal it again, cannot pass the gate again and sends no second copy
    Wiring restarted = wire(FaultPlan.none(), destination, false);
    assertThat(restarted.sealing().seal(c.request()).status())
        .isIn(SealStatus.STATE_MISMATCH, SealStatus.IN_FLIGHT_CONFLICT);
    assertThat(manifest()).isEqualTo(rows);
    assertThat(restarted.sealing().beforeFirstByte(c.endpoint(), attempt, c.token()))
        .isEqualTo(PreSendStatus.MARKER_REFUSED);
    assertThat(send(restarted, c, attempt).status()).isEqualTo(SendStatus.ABORTED_BY_GATE);
    assertThat(destination.receivedFiles()).hasSize(1);
    assertI1(c, destination);
  }

  // ---------------------------------------------------------------------------------------------
  // concurrency, the lease and the gate

  @Test
  default void concurrentSealsOfTheSameDeliveryHaveOneWinner() throws Exception {
    Case c = newCase("RACE", "BK-RACE");
    DestinationSimulator destination = destination();
    // every racer has passed the validation, the gate and the check of the state before any of
    // them takes an attempt id: the ledger alone decides who wins
    CyclicBarrier allValidated = new CyclicBarrier(RACERS);
    Wiring w =
        wire(
            FaultPlan.none(),
            destination,
            false,
            l ->
                CallHook.before(
                    LedgerStore.class, l, "allocateAttemptId", hit -> awaitBarrier(allValidated)),
            UnaryOperator.identity());
    ExecutorService pool = Executors.newFixedThreadPool(RACERS);
    List<SealOutcome> outcomes = new ArrayList<>();
    try {
      List<Future<SealOutcome>> racers = new ArrayList<>();
      for (int i = 0; i < RACERS; i++) {
        racers.add(pool.submit(() -> w.sealing().seal(c.request())));
      }
      for (Future<SealOutcome> racer : racers) {
        outcomes.add(racer.get(WAIT_SECONDS, TimeUnit.SECONDS));
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(outcomes).extracting(SealOutcome::status).containsOnlyOnce(SealStatus.SEALED);
    assertThat(outcomes)
        .filteredOn(outcome -> outcome.status() != SealStatus.SEALED)
        .extracting(SealOutcome::status)
        .hasSize(RACERS - 1)
        .isSubsetOf(SealStatus.STATE_MISMATCH, SealStatus.IN_FLIGHT_CONFLICT);
    AttemptId winner =
        outcomes.stream()
            .filter(outcome -> outcome.status() == SealStatus.SEALED)
            .findFirst()
            .orElseThrow()
            .attempt()
            .orElseThrow();
    assertThat(stateOf(c.delivery())).isEqualTo(DeliveryState.SENDING);
    assertThat(attemptsOf(c.delivery())).extracting(Attempt::id).containsExactly(winner);
    assertThat(manifest()).as("the winner's two rows").hasSize(2);
    assertTheRowsOfOneSeal();
    // what the losers wrote is one orphan intent object each and nothing else: the barrier makes
    // every racer take an attempt id and put both objects before any of them commits
    List<String> orphans = unrecorded();
    assertThat(orphans).as("one orphan intent object per loser").hasSize(RACERS - 1);
    for (String orphan : orphans) {
      assertThat(journalKeyOf(orphan))
          .isInstanceOfSatisfying(
              JournalKey.ClassA.class,
              key -> assertThat(key.type()).isEqualTo(JournalType.DELIVERY_INTENT));
    }
    assertThat(send(w, c, winner).status()).isEqualTo(SendStatus.DELIVERED);
    assertThat(destination.receivedFiles()).hasSize(1);
    assertI1(c, destination);
  }

  @Test
  default void aStaleLeaseAfterThePutsLeavesOrphansAndSendsNothing() {
    Case c = newCase("STALE", "BK-STALE");
    DestinationSimulator destination = destination();
    AtomicReference<LeaseToken> newHolder = new AtomicReference<>();
    // another holder takes the lease over right after the intent object is acknowledged
    Wiring w =
        wire(
            FaultPlan.none(),
            destination,
            false,
            UnaryOperator.identity(),
            e ->
                CallHook.after(
                    EvidenceStore.class,
                    e,
                    "put",
                    hit -> {
                      if (hit == 2) {
                        clock().advance(TTL);
                        newHolder.set(lease(ledger(), c.endpoint(), "B"));
                      }
                    }));

    SealOutcome outcome = w.sealing().seal(c.request());

    assertThat(outcome.status()).isEqualTo(SealStatus.STALE_LEASE);
    assertThat(outcome.attempt()).isEmpty();
    assertThat(newHolder.get()).isNotNull().isNotEqualTo(c.token());
    assertThat(evidenceKeys()).as("the sealed object and the intent object, as orphans").hasSize(2);
    assertLedgerUntouched(c);
    assertThat(destination.receivedFiles()).isEmpty();
    assertI1(c, destination);
    // the old holder cannot send, whatever attempt it names
    assertThat(w.sealing().beforeFirstByte(c.endpoint(), new AttemptId(1), c.token()))
        .isNotEqualTo(PreSendStatus.CLEAR);

    // the new holder seals the same delivery: the sealed object is shared, the old intent stays
    Case again = c.withToken(newHolder.get());
    Wiring second = wire(FaultPlan.none(), destination, false);
    AttemptId attempt = sealed(second, again);
    assertTheRowsOfOneSeal();
    assertThat(unrecorded()).as("the intent object of the lost attempt").hasSize(1);
    assertThat(send(second, again, attempt).status()).isEqualTo(SendStatus.DELIVERED);
    assertI1(again, destination);
  }

  @Test
  default void aClosedGateStopsBeforeAnyPut() {
    // the binding must be a pair that the production gate refuses: a ledger without a synchronous
    // standby or an evidence store with fewer than two failure domains
    Case c = newCase("GATE", "BK-GATE");
    DestinationSimulator destination = destination();
    Wiring closed = wire(FaultPlan.none(), destination, true);

    SealOutcome outcome = closed.sealing().seal(c.request());

    assertThat(outcome.status()).isEqualTo(SealStatus.GATE_CLOSED);
    assertThat(outcome.attempt()).isEmpty();
    assertThat(outcome.detail()).isNotBlank().doesNotContain(c.businessKey());
    assertThat(evidenceKeys()).isEmpty();
    assertLedgerUntouched(c);
    assertThat(completed(closed.faults())).as("no put, no attempt id, no commit").isEmpty();

    // the same gate stands in front of the first byte
    Wiring open = wire(FaultPlan.none(), destination, false);
    AttemptId attempt = sealed(open, c);
    assertThat(closed.sealing().beforeFirstByte(c.endpoint(), attempt, c.token()))
        .isEqualTo(PreSendStatus.GATE_CLOSED);
    assertThat(send(closed, c, attempt).status()).isEqualTo(SendStatus.ABORTED_BY_GATE);
    assertThat(destination.receivedFiles()).isEmpty();
    assertThat(ledger().attempt(attempt).orElseThrow().commitIssued()).isFalse();
    assertI1(c, destination);
  }

  // ---------------------------------------------------------------------------------------------
  // the gate checks the attempt it is given, not only the lease (I1 is a never-cut control)

  /** The key of a sealed file, spelled out here (D-E1): {@code sealed/<two hex>/<64 hex>}. */
  private static EvidenceKey sealedKeyOf(Sha256 sha) {
    return EvidenceKey.of("sealed", sha.hex().substring(0, 2), sha.hex());
  }

  /**
   * An attempt that was begun through the ledger alone (the P3a path that stays legal): the objects
   * of its unit are those that {@code objectsOf} names for the attempt id.
   */
  private AttemptId beganThroughTheLedger(
      Case c, Function<AttemptId, List<ObjectEntry>> objectsOf) {
    AttemptId attempt = ledger().allocateAttemptId();
    SendingResult result =
        ledger()
            .beginSending(
                c.delivery(),
                attempt,
                c.token(),
                new AttemptIntent(c.sha(), c.request().fileName(), objectsOf.apply(attempt)));
    assertThat(result.status()).as("the bare attempt").isEqualTo(SendingStatus.APPLIED);
    return attempt;
  }

  @Test
  default void aBareAttemptBegunThroughTheLedgerWithoutSealingIsRefusedAtTheGate() {
    DestinationSimulator destination = destination();
    Wiring w = wire(FaultPlan.none(), destination, false);
    // three attempts of three deliveries: no object at all, the sealed file only, the intent
    // object only; none has the two rows that the SENDING unit of a seal records
    for (int variant = 0; variant < 3; variant++) {
      Case c = newCase("BARE" + variant, "BK-BARE" + variant);
      int which = variant;
      AttemptId attempt =
          beganThroughTheLedger(
              c,
              id ->
                  switch (which) {
                    case 0 -> List.of();
                    case 1 ->
                        List.of(
                            new ObjectEntry(
                                sealedKeyOf(c.sha()), ObjectClass.SEALED_FILE, c.sha(), 1));
                    default ->
                        List.of(
                            new ObjectEntry(
                                intentKey(c, id.value()),
                                ObjectClass.JOURNAL_A,
                                Sha256.ofUtf8("an intent object"),
                                1));
                  });

      assertThat(w.sealing().beforeFirstByte(c.endpoint(), attempt, c.token()))
          .as("the gate for a bare attempt, variant " + variant)
          .isEqualTo(PreSendStatus.MARKER_REFUSED);
      assertRefusedAtTheGate(w, c, attempt);
      assertThat(stateOf(c.delivery())).isEqualTo(DeliveryState.SENDING);
    }
    assertThat(destination.receivedFiles()).isEmpty();
  }

  @Test
  default void aTokenOfAnotherEndpointIsRefusedAtTheGate() {
    EndpointKey first = LedgerFixtures.endpoint("TOKEN1");
    EndpointKey second = LedgerFixtures.endpoint("TOKEN2");
    LeaseToken firstToken = lease(ledger(), first, "A");
    LeaseToken secondToken = lease(ledger(), second, "A");
    // a token is (restore epoch, counter) and not bound to an endpoint: the first lease of every
    // endpoint is the same pair, and the fence of the marker statement compares numbers only
    assertThat(secondToken).as("the first token of every endpoint").isEqualTo(firstToken);
    Case a = newDelivery(first, firstToken, "BK-TOKEN1", reportOf("BK-TOKEN1"));
    Case b = newDelivery(second, secondToken, "BK-TOKEN2", reportOf("BK-TOKEN2"));
    DestinationSimulator destination = destination();
    Wiring w = wire(FaultPlan.none(), destination, false);
    AttemptId ofFirst = sealed(w, a);
    AttemptId ofSecond = sealed(w, b);

    // the holder of the first endpoint names the attempt of the second one
    assertThat(w.sealing().beforeFirstByte(first, ofSecond, firstToken))
        .isEqualTo(PreSendStatus.MARKER_REFUSED);
    assertThat(w.sealing().beforeFirstByte(second, ofFirst, secondToken))
        .isEqualTo(PreSendStatus.MARKER_REFUSED);

    assertThat(ledger().attempt(ofFirst).orElseThrow().commitIssued()).isFalse();
    assertThat(ledger().attempt(ofSecond).orElseThrow().commitIssued()).isFalse();
    assertThat(w.faults().trace())
        .as("the marker statement is never reached")
        .noneMatch(line -> line.startsWith("LedgerStore.markCommitIssued"));
    assertThat(destination.receivedFiles()).isEmpty();
    // each attempt goes out under its own endpoint
    assertThat(send(w, a, ofFirst).status()).isEqualTo(SendStatus.DELIVERED);
    assertThat(send(w, b, ofSecond).status()).isEqualTo(SendStatus.DELIVERED);
    assertI1(a, destination);
    assertI1(b, destination);
  }

  @Test
  default void anAttemptWhoseDeliveryLeftSendingIsRefusedBeforeTheMarkerStatement() {
    Case c = newCase("STATE", "BK-STATE");
    DestinationSimulator destination = destination();
    Wiring w = wire(FaultPlan.none(), destination, false);
    AttemptId attempt = sealed(w, c);
    // the delivery moved on (an acknowledgement state) with the lease, the epoch and the gate all
    // in order: the ledger's own fence would refuse the marker too, but the gate does not ask
    assertThat(ledger().transition(c.delivery(), DeliveryState.SENDING, DeliveryState.AWAITING_ACK))
        .isEqualTo(TransitionStatus.APPLIED);

    assertThat(w.sealing().beforeFirstByte(c.endpoint(), attempt, c.token()))
        .isEqualTo(PreSendStatus.MARKER_REFUSED);

    assertRefusedAtTheGate(w, c, attempt);
    assertThat(stateOf(c.delivery())).isEqualTo(DeliveryState.AWAITING_ACK);
  }

  // ---------------------------------------------------------------------------------------------
  // a commit that no standby confirmed is never reported as durable (exit criterion, spec 11.2)

  /** The text of the exception of a unit whose COMMIT returned with the standby-wait warning. */
  String UNCONFIRMED = "commit not confirmed by the synchronous standby (sqlstate=01000)";

  @Test
  default void aMarkerCommitThatNoStandbyConfirmedIsNeverClearAndNoByteLeaves() {
    Case c = newCase("UNCONF1", "BK-UNCONF1");
    DestinationSimulator destination = destination();
    // the call is made and the unit is committed locally; the caller is told it is not durable
    Wiring w =
        wire(
            FaultPlan.failAt(CrashPoints.AFTER_MARKER, 1, new LedgerException(UNCONFIRMED)),
            destination,
            false);
    AttemptId attempt = sealed(w, c);

    Throwable thrown = catchThrowable(() -> send(w, c, attempt));

    assertThat(thrown)
        .as("the gate must throw: CLEAR here would let a byte leave on an unconfirmed marker")
        .isInstanceOf(LedgerException.class)
        .hasMessageContaining("commit not confirmed by the synchronous standby");
    assertThat(destination.receivedFiles()).as("the transport gets no file").isEmpty();
    // the marker is there (committed locally) and the delivery is in doubt for the next process
    assertThat(ledger().attempt(attempt).orElseThrow().commitIssued()).isTrue();
    assertThat(stateOf(c.delivery())).isEqualTo(DeliveryState.SENDING);
    assertTheRowsOfOneSeal();
    assertI1(c, destination);
    // the retry is refused: the marker is set, whatever the standby says
    assertThat(w.sealing().beforeFirstByte(c.endpoint(), attempt, c.token()))
        .isEqualTo(PreSendStatus.MARKER_REFUSED);
    assertThat(send(w, c, attempt).status()).isEqualTo(SendStatus.ABORTED_BY_GATE);
    assertThat(destination.receivedFiles()).isEmpty();
    assertI1(c, destination);
  }

  @Test
  default void aSendingCommitThatNoStandbyConfirmedIsNeverSealed() {
    Case c = newCase("UNCONF2", "BK-UNCONF2");
    DestinationSimulator destination = destination();
    Wiring w =
        wire(
            FaultPlan.failAt(CrashPoints.AFTER_SENDING_COMMIT, 1, new LedgerException(UNCONFIRMED)),
            destination,
            false);

    Throwable thrown = catchThrowable(() -> w.sealing().seal(c.request()));

    assertThat(thrown)
        .as("the seal must throw: SEALED here would hand out an attempt that is not durable")
        .isInstanceOf(LedgerException.class)
        .hasMessageContaining("commit not confirmed by the synchronous standby");
    // the unit is committed locally, as after a crash after the commit: SENDING, one attempt
    // without a marker, both manifest rows, both objects, and not a byte at the destination
    assertThat(stateOf(c.delivery())).isEqualTo(DeliveryState.SENDING);
    List<Attempt> attempts = attemptsOf(c.delivery());
    assertThat(attempts).hasSize(1);
    assertThat(attempts.get(0).commitIssued()).isFalse();
    assertTheRowsOfOneSeal();
    List<ManifestRow> rows = manifest();
    rows.forEach(row -> storedFor(row));
    assertThat(destination.receivedFiles()).isEmpty();
    assertI1(c, destination);
    // a second seal is refused and adds nothing
    assertThat(w.sealing().seal(c.request()).status()).isEqualTo(SealStatus.STATE_MISMATCH);
    assertThat(manifest()).isEqualTo(rows);
    assertThat(attemptsOf(c.delivery())).hasSize(1);
    assertThat(destination.receivedFiles()).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // the restore-epoch marker of the evidence tier (written here by hand: control/epoch/e<12>)

  private static EvidenceKey markerKey(long epoch) {
    String digits = Long.toString(epoch);
    return EvidenceKey.of("control", "epoch", "e" + "0".repeat(12 - digits.length()) + digits);
  }

  private void putMarker(EvidenceKey key, byte[] bytes) {
    evidence()
        .put(
            new EvidenceStore.PutRequest(
                key,
                bytes,
                "application/vnd.regpack.epoch+json",
                new EvidenceStore.Retention(
                    new EvidenceStore.RetentionClass("epoch"),
                    clock().now().plus(Duration.ofDays(1826)))));
  }

  /** Declares a restore epoch in the evidence tier, as the restore procedure does. */
  private void declareEpoch(long epoch) {
    putMarker(
        markerKey(epoch), Json.canonical(Map.<String, Object>of("epoch", epoch)).getBytes(UTF_8));
  }

  /** The send of an attempt that the gate refuses: no byte, no marker, no further ledger change. */
  private void assertRefusedAtTheGate(Wiring w, Case c, AttemptId attempt) {
    assertThat(send(w, c, attempt).status()).isEqualTo(SendStatus.ABORTED_BY_GATE);
    assertThat(w.destination().receivedFiles()).as("no byte has left").isEmpty();
    assertThat(ledger().attempt(attempt).orElseThrow().commitIssued()).as("the marker").isFalse();
    assertThat(w.faults().trace())
        .as("the marker statement is never reached")
        .noneMatch(line -> line.startsWith("LedgerStore.markCommitIssued"));
    assertI1(c, w.destination());
  }

  @Test
  default void theEpochMarkerThatDiffersRefusesTheSendAndSetsNoMarker() {
    Case c = newCase("EPOCH1", "BK-EPOCH1");
    Wiring w = wire(FaultPlan.none(), destination(), false);
    AttemptId attempt = sealed(w, c);
    // a restore declared a later epoch while this sender stood between the seal and the send
    declareEpoch(7);

    assertThat(w.sealing().beforeFirstByte(c.endpoint(), attempt, c.token()))
        .isEqualTo(PreSendStatus.EPOCH_MARKER_DIFFERS);
    assertRefusedAtTheGate(w, c, attempt);
    assertThat(stateOf(c.delivery())).isEqualTo(DeliveryState.SENDING);
  }

  @Test
  default void aMarkerBelowTheTokensEpochRefusesTheSend() {
    ledger().declareRestoreEpoch(2);
    Case c = newCase("EPOCH2", "BK-EPOCH2");
    assertThat(c.token().restoreEpoch()).isEqualTo(2);
    Wiring w = wire(FaultPlan.none(), destination(), false);
    AttemptId attempt = sealed(w, c);
    declareEpoch(1);

    assertThat(w.sealing().beforeFirstByte(c.endpoint(), attempt, c.token()))
        .isEqualTo(PreSendStatus.EPOCH_MARKER_DIFFERS);
    assertRefusedAtTheGate(w, c, attempt);
  }

  @Test
  default void anUnreadableEpochMarkerFailsClosed() {
    Case c = newCase("EPOCH3", "BK-EPOCH3");
    Wiring w = wire(FaultPlan.none(), destination(), false);
    AttemptId attempt = sealed(w, c);
    // the object under the key of epoch 5 says epoch 6; with the token at epoch 0 a marker that
    // is taken for "none" would let the send through
    putMarker(markerKey(5), "{\"epoch\":6}".getBytes(UTF_8));

    assertThat(w.sealing().beforeFirstByte(c.endpoint(), attempt, c.token()))
        .isEqualTo(PreSendStatus.EPOCH_MARKER_UNREADABLE);
    assertRefusedAtTheGate(w, c, attempt);
  }

  @Test
  default void anUnreachableEvidenceTierAtTheGateFailsClosed() {
    Case c = newCase("EPOCH4", "BK-EPOCH4");
    DestinationSimulator destination = destination();
    // every listing fails while the switch is on; the switch is set after the seal, so the
    // scenario does not depend on how many listings a seal or a gate makes
    AtomicBoolean outage = new AtomicBoolean();
    Wiring w =
        wire(
            FaultPlan.none(),
            destination,
            false,
            UnaryOperator.identity(),
            e ->
                CallHook.before(
                    EvidenceStore.class,
                    e,
                    "list",
                    hit -> {
                      if (outage.get()) {
                        throw new EvidenceUnavailableException("down");
                      }
                    }));
    AttemptId attempt = sealed(w, c);
    outage.set(true);

    // an outage is not "no marker": with the token at epoch 0 that mistake would be CLEAR
    assertThat(w.sealing().beforeFirstByte(c.endpoint(), attempt, c.token()))
        .isEqualTo(PreSendStatus.EPOCH_MARKER_UNREADABLE);
    assertRefusedAtTheGate(w, c, attempt);

    // the refusal was the outage's: the tier is back, nothing was marked, the same send goes out
    outage.set(false);
    assertThat(send(w, c, attempt).status()).isEqualTo(SendStatus.DELIVERED);
    assertI1(c, destination);
  }

  @Test
  default void aMarkerOfTheTokensEpochLetsTheSendThrough() {
    ledger().declareRestoreEpoch(2);
    Case c = newCase("EPOCH5", "BK-EPOCH5");
    assertThat(c.token().restoreEpoch()).isEqualTo(2);
    DestinationSimulator destination = destination();
    Wiring w = wire(FaultPlan.none(), destination, false);
    AttemptId attempt = sealed(w, c);
    // the older marker does not matter: the highest one is the current epoch
    declareEpoch(1);
    declareEpoch(2);

    assertThat(send(w, c, attempt).status()).isEqualTo(SendStatus.DELIVERED);
    assertThat(ledger().attempt(attempt).orElseThrow().commitIssued()).isTrue();
    assertI1(c, destination);
  }

  @Test
  default void aTakeoverBetweenSealAndMarkerRefusesTheMarker() {
    Case c = newCase("TAKEOVER", "BK-TAKEOVER");
    DestinationSimulator destination = destination();
    Wiring w = wire(FaultPlan.none(), destination, false);
    AttemptId attempt = sealed(w, c);
    clock().advance(TTL);
    LeaseToken other = lease(ledger(), c.endpoint(), "B");
    assertThat(other).isNotEqualTo(c.token());

    assertThat(w.sealing().beforeFirstByte(c.endpoint(), attempt, c.token()))
        .isEqualTo(PreSendStatus.STALE_LEASE);
    assertRefusedAtTheGate(w, c, attempt);
    // the takeover put what the old holder had in SENDING in doubt; the fence holds in the ledger
    assertThat(stateOf(c.delivery())).isEqualTo(DeliveryState.IN_DOUBT);
    assertThat(ledger().markCommitIssued(attempt, c.token())).isFalse();
    assertThat(destination.receivedFiles()).isEmpty();
    // and the new holder cannot seal what is in doubt
    Wiring second = wire(FaultPlan.none(), destination, false);
    assertThat(second.sealing().seal(c.withToken(other).request()).status())
        .isEqualTo(SealStatus.STATE_MISMATCH);
    assertThat(manifest()).hasSize(2);
  }

  @Test
  default void theSameBytesSealedForTwoAttemptsShareOneSealedObject() {
    EndpointKey endpoint = LedgerFixtures.endpoint("SHARED");
    LeaseToken token = lease(ledger(), endpoint, "A");
    byte[] bytes = reportOf("one report for two deliveries");
    Case a = newDelivery(endpoint, token, "BK-SHARED-A", bytes);
    Case b = newDelivery(endpoint, token, "BK-SHARED-B", bytes);
    DestinationSimulator destination = destination();
    Wiring w = wire(FaultPlan.none(), destination, false);

    AttemptId first = sealed(w, a);
    AttemptId second = sealed(w, b);

    assertThat(first).isNotEqualTo(second);
    assertThat(evidenceKeys()).as("one sealed object and two intent objects").hasSize(3);
    List<ManifestRow> rows = manifest();
    assertThat(rows).hasSize(3);
    List<ManifestRow> sealedRows =
        rows.stream().filter(row -> row.entry().objectClass() == ObjectClass.SEALED_FILE).toList();
    assertThat(sealedRows).as("one row for the shared sealed object").hasSize(1);
    assertThat(sealedRows.get(0).attempt()).as("the first row of an object wins").contains(first);
    assertThat(rows.stream().filter(row -> row.entry().objectClass() == ObjectClass.JOURNAL_A))
        .extracting(row -> row.attempt().orElseThrow())
        .containsExactlyInAnyOrder(first, second);
    assertThat(unrecorded()).isEmpty();
    assertThat(send(w, a, first).status()).isEqualTo(SendStatus.DELIVERED);
    assertThat(send(w, b, second).status()).isEqualTo(SendStatus.DELIVERED);
    assertI1(a, destination);
    assertI1(b, destination);
  }
}
