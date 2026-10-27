package dev.regpack.evidence.seal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.evidence.retention.RetentionEngine;
import dev.regpack.evidence.retention.RetentionVerifier;
import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.evidence.seal.RetentionRegistrar;
import dev.regpack.ports.evidence.seal.Sealing.SealOutcome;
import dev.regpack.ports.evidence.seal.Sealing.SealRequest;
import dev.regpack.ports.evidence.seal.Sealing.SealStatus;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import dev.regpack.ports.ledger.LedgerException;
import dev.regpack.ports.ledger.RetentionLedger.RetentionRow;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.LedgerFixtures;
import dev.regpack.testkit.contract.SealScenarioContract;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P3c1 T9, SEAL-1 at the seam: where {@link DurableSeal} calls the {@link RetentionRegistrar}, with
 * what, and what a failure of it leaves behind. The retention rows themselves are the engine's
 * ({@code RetentionEngineTest}) and the shared scenario's.
 */
class DurableSealRetentionTest {
  private static final Instant START = Instant.parse("2026-10-20T08:00:00Z");
  private static final RetentionPolicy FIVE_YEARS = RetentionPolicy.fixedDays(1826);
  private static final EndpointKey EP = LedgerFixtures.endpoint("RETAIN2");
  private static final byte[] BYTES = SealScenarioContract.reportOf("BK-R1");
  private static final String REGISTRAR_OUTAGE_DETAIL =
      "the evidence store could not extend the retention of the sealed bytes;"
          + " the delivery stays queued and a retention row may have been recorded";

  private ManualClock clock;
  private InMemoryLedgerStore ledger;
  private InMemoryEvidenceStore store;
  private final List<String> events = new ArrayList<>();
  private TracingStore traced;

  @BeforeEach
  void setUp() {
    clock = new ManualClock(START);
    ledger = new InMemoryLedgerStore(clock);
    store = new InMemoryEvidenceStore(clock);
    traced = new TracingStore(store, events);
  }

  private DurableSeal sealer(RetentionRegistrar registrar) {
    return new DurableSeal(
        ledger, traced, clock, FIVE_YEARS, new SendGate(SendGate.Requirement.none()), registrar);
  }

  private DeliveryId queued() {
    return SealScenarioContract.queued(ledger, EP, "BK-R1");
  }

  private LeaseToken lease() {
    return SealScenarioContract.lease(ledger, EP, "A");
  }

  private SealOutcome seal(DurableSeal sealer, LeaseToken token, DeliveryId delivery) {
    return sealer.seal(SealScenarioContract.request(EP, token, delivery, "BK-R1", BYTES));
  }

  private static EvidenceKey sealedKey() {
    return SealedKeys.of(Sha256.of(BYTES));
  }

  private List<String> evidenceKeys() {
    return store.list("", Optional.empty(), 100).stream().map(EvidenceKey::path).toList();
  }

  @Test
  void theRegistrarRunsAfterTheSealedPutAndBeforeTheIntentPut() {
    LeaseToken token = lease();
    DeliveryId delivery = queued();
    List<EvidenceKey> keys = new ArrayList<>();
    List<EvidenceStore.Retention> retentions = new ArrayList<>();
    List<DeliveryState> statesSeen = new ArrayList<>();
    int[] manifestRowsSeen = {-1};
    RetentionRegistrar registrar =
        (key, putWith) -> {
          events.add("registrar:" + key.path());
          keys.add(key);
          retentions.add(putWith);
          statesSeen.add(ledger.delivery(delivery).orElseThrow().state());
          manifestRowsSeen[0] = ledger.manifestRows("", Optional.empty(), 100).size();
        };

    SealOutcome outcome = seal(sealer(registrar), token, delivery);

    assertThat(outcome.status()).isEqualTo(SealStatus.SEALED);
    String intentPath =
        evidenceKeys().stream()
            .filter(path -> path.startsWith("journal/"))
            .findFirst()
            .orElseThrow();
    assertThat(events)
        .as("the order of the evidence puts and the registrar")
        .containsExactly(
            "put:" + sealedKey().path(), "registrar:" + sealedKey().path(), "put:" + intentPath);
    assertThat(keys).containsExactly(sealedKey());
    // the Retention that the sealed put used, class and date
    assertThat(retentions).containsExactly(FIVE_YEARS.retentionFor("sealed", START));
    assertThat(retentions.get(0).retainUntil()).isEqualTo(START.plus(Duration.ofDays(1826)));
    // and the ledger was still as the seal found it: the SENDING commit comes after
    assertThat(statesSeen).containsExactly(DeliveryState.QUEUED);
    assertThat(manifestRowsSeen[0]).isZero();
    assertThat(ledger.delivery(delivery).orElseThrow().state()).isEqualTo(DeliveryState.SENDING);
  }

  @Test
  void theRegistrarIsCalledForASharedObjectWithTheRetentionOfTheLaterSeal() {
    LeaseToken token = lease();
    DeliveryId first = queued();
    DeliveryId second = SealScenarioContract.queued(ledger, EP, "BK-R2");
    List<EvidenceStore.Retention> retentions = new ArrayList<>();
    DurableSeal sealer = sealer((key, putWith) -> retentions.add(putWith));

    assertThat(seal(sealer, token, first).status()).isEqualTo(SealStatus.SEALED);
    clock.advance(Duration.ofDays(30));
    // the same bytes for another delivery: the put finds the object and keeps its retention, the
    // registrar still gets the retention of THIS seal
    SealOutcome again =
        sealer.seal(SealScenarioContract.request(EP, token, second, "BK-R2", BYTES));

    assertThat(again.status()).isEqualTo(SealStatus.SEALED);
    assertThat(retentions)
        .containsExactly(
            FIVE_YEARS.retentionFor("sealed", START),
            FIVE_YEARS.retentionFor("sealed", START.plus(Duration.ofDays(30))));
    assertThat(store.get(sealedKey()).orElseThrow().retention().retainUntil())
        .as("nothing but the registrar extends the object")
        .isEqualTo(START.plus(Duration.ofDays(1826)));
  }

  @Test
  void aRegistrarOutageFailsTheSealClosedAndLeavesTheDeliveryQueued() {
    LeaseToken token = lease();
    DeliveryId delivery = queued();
    boolean[] down = {true};
    DurableSeal sealer =
        sealer(
            (key, putWith) -> {
              if (down[0]) {
                throw new EvidenceUnavailableException("secret-detail");
              }
            });

    SealOutcome outcome = seal(sealer, token, delivery);

    assertThat(outcome.status()).isEqualTo(SealStatus.EVIDENCE_UNAVAILABLE);
    assertThat(outcome.attempt()).isEmpty();
    assertThat(outcome.detail())
        .as("a fixed text that keeps nothing of the exception's own")
        .isEqualTo(REGISTRAR_OUTAGE_DETAIL);
    assertThat(ledger.delivery(delivery).orElseThrow().state()).isEqualTo(DeliveryState.QUEUED);
    assertThat(ledger.attempt(new AttemptId(1))).as("no attempt row").isEmpty();
    assertThat(ledger.manifestRows("", Optional.empty(), 100)).as("no manifest row").isEmpty();
    assertThat(evidenceKeys())
        .as("the sealed object is an orphan, the intent object was not put")
        .containsExactly(sealedKey().path());

    // the registrar is back: the same request seals
    down[0] = false;
    assertThat(seal(sealer, token, delivery).status()).isEqualTo(SealStatus.SEALED);
    assertThat(ledger.delivery(delivery).orElseThrow().state()).isEqualTo(DeliveryState.SENDING);
  }

  @Test
  void anOutageBetweenTheRowAndTheExtensionFailsTheSealAndTheRetryRepairsIt() {
    LeaseToken token = lease();
    DeliveryId first = queued();
    DeliveryId second = SealScenarioContract.queued(ledger, EP, "BK-R2");
    DurableSeal sealer = sealer(new RetentionEngine(ledger, traced, new Regime("mifir.eu.rts22")));
    assertThat(seal(sealer, token, first).status()).isEqualTo(SealStatus.SEALED);
    clock.advance(Duration.ofDays(30));
    Instant later = clock.now().plus(Duration.ofDays(1826));
    Instant firstDate = START.plus(Duration.ofDays(1826));
    SealRequest again = SealScenarioContract.request(EP, token, second, "BK-R2", BYTES);

    // the engine records the row of the later seal and then fails to extend the store
    traced.failNextExtend = true;
    SealOutcome failed = sealer.seal(again);

    assertThat(failed.status()).isEqualTo(SealStatus.EVIDENCE_UNAVAILABLE);
    assertThat(failed.detail())
        .isEqualTo(REGISTRAR_OUTAGE_DETAIL)
        .doesNotContain("ledger is unchanged");
    assertThat(ledger.delivery(second).orElseThrow().state()).isEqualTo(DeliveryState.QUEUED);
    List<RetentionRow> rows = ledger.retentionRows("sealed/", Optional.empty(), 10);
    assertThat(rows).as("a retention row was written before the failure").hasSize(1);
    assertThat(rows.get(0).claim().retainUntil()).contains(later);
    assertThat(store.get(sealedKey()).orElseThrow().retention().retainUntil())
        .as("the store was not extended")
        .isEqualTo(firstDate);
    assertThat(new RetentionVerifier(store, ledger).verify(""))
        .as("the lag is what the verifier finds")
        .extracting(RetentionVerifier.Finding::kind)
        .containsExactly(RetentionVerifier.Kind.RETAIN_UNTIL_SHORTER);

    // the retry of the same request seals and repairs the store; the row stays one
    assertThat(sealer.seal(again).status()).isEqualTo(SealStatus.SEALED);
    assertThat(ledger.retentionRows("sealed/", Optional.empty(), 10)).hasSize(1);
    assertThat(store.get(sealedKey()).orElseThrow().retention().retainUntil()).isEqualTo(later);
    assertThat(new RetentionVerifier(store, ledger).verify("")).isEmpty();
  }

  @Test
  void aLedgerFailureOfTheRegistrarPropagates() {
    LeaseToken token = lease();
    DeliveryId delivery = queued();
    LedgerException failure = new LedgerException("the retention rows could not be written");
    DurableSeal sealer =
        sealer(
            (key, putWith) -> {
              throw failure;
            });

    assertThatThrownBy(() -> seal(sealer, token, delivery)).isSameAs(failure);

    assertThat(ledger.delivery(delivery).orElseThrow().state()).isEqualTo(DeliveryState.QUEUED);
    assertThat(ledger.manifestRows("", Optional.empty(), 100)).isEmpty();
    assertThat(evidenceKeys()).containsExactly(sealedKey().path());
  }

  @Test
  void aFailureOfTheRegistrarThatIsNotAnOutageIsNotCaught() {
    LeaseToken token = lease();
    DeliveryId delivery = queued();
    IllegalArgumentException failure = new IllegalArgumentException("not a retention class");
    DurableSeal sealer =
        sealer(
            (key, putWith) -> {
              throw failure;
            });

    assertThatThrownBy(() -> seal(sealer, token, delivery)).isSameAs(failure);

    assertThat(ledger.delivery(delivery).orElseThrow().state()).isEqualTo(DeliveryState.QUEUED);
  }

  @Test
  void theRegistrarIsNotCalledWhenTheSealedPutFails() {
    LeaseToken token = lease();
    DeliveryId delivery = queued();
    List<EvidenceKey> keys = new ArrayList<>();
    DurableSeal sealer = sealer((key, putWith) -> keys.add(key));
    store.simulateOutage(true);

    SealOutcome outcome = seal(sealer, token, delivery);

    store.simulateOutage(false);
    assertThat(outcome.status()).isEqualTo(SealStatus.EVIDENCE_UNAVAILABLE);
    assertThat(keys).as("nothing was put, so nothing is registered").isEmpty();
  }

  @Test
  void aRefusedRequestAndAClosedGateNeverReachTheRegistrar() {
    LeaseToken token = lease();
    DeliveryId delivery = queued();
    List<EvidenceKey> keys = new ArrayList<>();
    RetentionRegistrar registrar = (key, putWith) -> keys.add(key);
    DurableSeal closed =
        new DurableSeal(
            ledger,
            traced,
            clock,
            FIVE_YEARS,
            new SendGate(SendGate.Requirement.production()),
            registrar);

    assertThat(seal(closed, token, delivery).status()).isEqualTo(SealStatus.GATE_CLOSED);
    DurableSeal open = sealer(registrar);
    // a request for a delivery the ledger does not know is refused before any put
    assertThatThrownBy(
            () ->
                open.seal(
                    SealScenarioContract.request(EP, token, new DeliveryId(9_999), "BK-R1", BYTES)))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(keys).isEmpty();
    assertThat(evidenceKeys()).isEmpty();
  }

  @Test
  void theFiveArgumentConstructorRegistersNothing() {
    LeaseToken token = lease();
    DeliveryId delivery = queued();
    DurableSeal sealer =
        new DurableSeal(
            ledger, traced, clock, FIVE_YEARS, new SendGate(SendGate.Requirement.none()));

    assertThat(seal(sealer, token, delivery).status()).isEqualTo(SealStatus.SEALED);

    assertThat(ledger.retentionRows("", Optional.empty(), 100)).isEmpty();
    assertThat(events)
        .as("two puts and no extension of the retention")
        .allMatch(event -> event.startsWith("put:"))
        .hasSize(2);
  }

  @Test
  void aNullRegistrarIsRefused() {
    assertThatThrownBy(
            () ->
                new DurableSeal(
                    ledger,
                    traced,
                    clock,
                    FIVE_YEARS,
                    new SendGate(SendGate.Requirement.none()),
                    null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("registrar");
  }

  /** An evidence store that delegates and writes the keys of its puts and retention calls down. */
  private static final class TracingStore implements EvidenceStore {
    private final EvidenceStore delegate;
    private final List<String> events;
    boolean failNextExtend;

    TracingStore(EvidenceStore delegate, List<String> events) {
      this.delegate = delegate;
      this.events = events;
    }

    @Override
    public Grade grade() {
      return delegate.grade();
    }

    @Override
    public Durability durability() {
      return delegate.durability();
    }

    @Override
    public PutOutcome put(PutRequest request) {
      PutOutcome outcome = delegate.put(request);
      events.add("put:" + request.key().path());
      return outcome;
    }

    @Override
    public Optional<StoredObject> get(EvidenceKey key) {
      return delegate.get(key);
    }

    @Override
    public List<EvidenceKey> list(String prefix, Optional<EvidenceKey> after, int limit) {
      return delegate.list(prefix, after, limit);
    }

    @Override
    public void extendRetention(EvidenceKey key, Instant newRetainUntil) {
      events.add("extend:" + key.path());
      if (failNextExtend) {
        failNextExtend = false;
        throw new EvidenceUnavailableException("down");
      }
      delegate.extendRetention(key, newRetainUntil);
    }

    @Override
    public void setLegalHold(EvidenceKey key, boolean hold) {
      events.add("hold:" + key.path());
      delegate.setLegalHold(key, hold);
    }
  }
}
