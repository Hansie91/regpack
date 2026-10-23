package dev.regpack.evidence.seal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import dev.regpack.ports.ledger.LedgerDurability;
import dev.regpack.ports.ledger.LedgerException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The send gate of D-E11: the production requirement is a synchronous ledger, an evidence store
 * that acknowledges only durable writes, and two independent failure domains. A side that cannot be
 * read closes the gate with a fixed reason that does not repeat the exception text.
 */
class SendGateTest {
  private static final LedgerDurability.Status SYNCHRONOUS =
      new LedgerDurability.Status(true, 1, "one synchronous standby");
  private static final LedgerDurability.Status ASYNCHRONOUS =
      new LedgerDurability.Status(false, 0, "in-memory ledger");

  /** An evidence store whose only working method is {@code durability}; any other call fails. */
  private static EvidenceStore evidence(
      Supplier<EvidenceStore.Durability> durability, List<String> calls) {
    return (EvidenceStore)
        Proxy.newProxyInstance(
            SendGateTest.class.getClassLoader(),
            new Class<?>[] {EvidenceStore.class},
            (proxy, method, args) -> {
              calls.add(method.getName());
              if (method.getName().equals("durability")) {
                return durability.get();
              }
              throw new AssertionError("the gate called EvidenceStore." + method.getName());
            });
  }

  private static EvidenceStore evidence(boolean synchronous, int domains) {
    return evidence(
        () -> new EvidenceStore.Durability(synchronous, domains), new ArrayList<String>());
  }

  private static SendGate.Result check(
      SendGate.Requirement requirement,
      boolean ledgerSynchronous,
      boolean evidenceSynchronous,
      int domains) {
    LedgerDurability ledger = () -> ledgerSynchronous ? SYNCHRONOUS : ASYNCHRONOUS;
    return new SendGate(requirement).check(ledger, evidence(evidenceSynchronous, domains));
  }

  @Test
  void theProductionRequirementIsTwoDomainsAndSynchronousOnBothSides() {
    assertThat(SendGate.Requirement.production())
        .isEqualTo(new SendGate.Requirement(true, true, 2));
    assertThat(SendGate.Requirement.none()).isEqualTo(new SendGate.Requirement(false, false, 0));
    assertThatThrownBy(() -> new SendGate.Requirement(true, true, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void productionRequiresASynchronousLedgerASynchronousEvidenceStoreAndTwoDomains() {
    String ledgerReason = SendGate.LEDGER_NOT_SYNCHRONOUS;
    String acknowledgementReason = SendGate.EVIDENCE_NOT_SYNCHRONOUS;
    String domainsReason = SendGate.tooFewDomains(2);
    int opened = 0;
    for (int combination = 0; combination < 8; combination++) {
      boolean ledgerOk = (combination & 1) != 0;
      boolean acknowledgementOk = (combination & 2) != 0;
      boolean domainsOk = (combination & 4) != 0;
      SendGate.Result result =
          check(SendGate.Requirement.production(), ledgerOk, acknowledgementOk, domainsOk ? 2 : 1);
      List<String> expected = new ArrayList<>();
      if (!ledgerOk) {
        expected.add(ledgerReason);
      }
      if (!acknowledgementOk) {
        expected.add(acknowledgementReason);
      }
      if (!domainsOk) {
        expected.add(domainsReason);
      }
      assertThat(result.reasons()).as("combination " + combination).isEqualTo(expected);
      assertThat(result.open()).as("combination " + combination).isEqualTo(expected.isEmpty());
      if (result.open()) {
        opened++;
      }
    }
    assertThat(opened).isEqualTo(1);
  }

  @Test
  void theReasonsAreFixedTexts() {
    assertThat(SendGate.LEDGER_NOT_SYNCHRONOUS)
        .isEqualTo("the ledger commit is not acknowledged by a synchronous standby");
    assertThat(SendGate.EVIDENCE_NOT_SYNCHRONOUS)
        .isEqualTo("the evidence store does not acknowledge a write only when it is durable");
    assertThat(SendGate.tooFewDomains(2))
        .isEqualTo("the evidence store has fewer than 2 independent failure domains");
    assertThat(SendGate.LEDGER_UNREADABLE).isEqualTo("the ledger durability cannot be read");
    assertThat(SendGate.EVIDENCE_UNREADABLE)
        .isEqualTo("the evidence store durability cannot be read");
  }

  @Test
  void moreDomainsThanRequiredOpenTheGate() {
    assertThat(check(SendGate.Requirement.production(), true, true, 3).open()).isTrue();
    assertThat(check(SendGate.Requirement.production(), true, true, 2).reasons()).isEmpty();
  }

  @Test
  void aSynchronousStatusThatNamesNoStandbyIsClosed() {
    LedgerDurability noStandby = () -> new LedgerDurability.Status(true, 0, "claims it");
    SendGate.Result result =
        new SendGate(SendGate.Requirement.production()).check(noStandby, evidence(true, 2));
    assertThat(result.open()).isFalse();
    assertThat(result.reasons()).containsExactly(SendGate.LEDGER_NOT_SYNCHRONOUS);
  }

  @Test
  void aWeakerRequirementChecksOnlyWhatItAsksFor() {
    // the ledger only: the evidence store is never touched
    List<String> calls = new ArrayList<>();
    SendGate ledgerOnly = new SendGate(new SendGate.Requirement(true, false, 0));
    EvidenceStore untouched =
        evidence(
            () -> {
              throw new AssertionError("not asked for");
            },
            calls);
    assertThat(ledgerOnly.check(() -> SYNCHRONOUS, untouched).open()).isTrue();
    assertThat(ledgerOnly.check(() -> ASYNCHRONOUS, untouched).reasons())
        .containsExactly(SendGate.LEDGER_NOT_SYNCHRONOUS);
    assertThat(calls).isEmpty();

    // the evidence store only: the ledger is never touched
    SendGate evidenceOnly = new SendGate(new SendGate.Requirement(false, true, 1));
    LedgerDurability untouchedLedger =
        () -> {
          throw new AssertionError("not asked for");
        };
    assertThat(evidenceOnly.check(untouchedLedger, evidence(true, 1)).open()).isTrue();
    assertThat(evidenceOnly.check(untouchedLedger, evidence(false, 0)).reasons())
        .containsExactly(SendGate.EVIDENCE_NOT_SYNCHRONOUS, SendGate.tooFewDomains(1));

    // failure domains alone: a requirement that asks for domains asks the evidence store
    SendGate domainsOnly = new SendGate(new SendGate.Requirement(false, false, 2));
    assertThat(domainsOnly.check(untouchedLedger, evidence(false, 1)).reasons())
        .containsExactly(SendGate.tooFewDomains(2));
    assertThat(domainsOnly.check(untouchedLedger, evidence(false, 2)).open()).isTrue();
    // and it does not ask for the acknowledgement: a store of two domains that does not
    // acknowledge durably is enough here
    assertThat(domainsOnly.check(untouchedLedger, evidence(false, 3)).reasons()).isEmpty();
  }

  @Test
  void anExceptionWhileReadingEitherSideClosesTheGate() {
    String secret = "db-host-17.internal password=hunter2";
    List<RuntimeException> failures =
        List.of(
            new LedgerException(secret),
            new EvidenceUnavailableException(secret),
            new IllegalStateException(secret),
            new NullPointerException(secret));
    for (RuntimeException failure : failures) {
      SendGate gate = new SendGate(SendGate.Requirement.production());
      LedgerDurability failingLedger =
          () -> {
            throw failure;
          };
      SendGate.Result ledgerDown = gate.check(failingLedger, evidence(true, 2));
      assertThat(ledgerDown.open()).as(failure.getClass().getName()).isFalse();
      assertThat(ledgerDown.reasons()).containsExactly(SendGate.LEDGER_UNREADABLE);

      List<String> calls = new ArrayList<>();
      EvidenceStore failingEvidence =
          evidence(
              () -> {
                throw failure;
              },
              calls);
      SendGate.Result evidenceDown = gate.check(() -> SYNCHRONOUS, failingEvidence);
      assertThat(evidenceDown.open()).as(failure.getClass().getName()).isFalse();
      assertThat(evidenceDown.reasons()).containsExactly(SendGate.EVIDENCE_UNREADABLE);
      assertThat(calls).containsExactly("durability");

      SendGate.Result bothDown = gate.check(failingLedger, failingEvidence);
      assertThat(bothDown.open()).isFalse();
      assertThat(bothDown.reasons())
          .containsExactly(SendGate.LEDGER_UNREADABLE, SendGate.EVIDENCE_UNREADABLE);
      for (SendGate.Result result : List.of(ledgerDown, evidenceDown, bothDown)) {
        assertThat(String.join(" | ", result.reasons()))
            .doesNotContain("hunter2")
            .doesNotContain("db-host");
      }
    }
  }

  @Test
  void aSideThatAnswersNothingIsClosed() {
    SendGate gate = new SendGate(SendGate.Requirement.production());
    SendGate.Result ledgerNull = gate.check(() -> null, evidence(true, 2));
    assertThat(ledgerNull.reasons()).containsExactly(SendGate.LEDGER_UNREADABLE);
    SendGate.Result evidenceNull =
        gate.check(() -> SYNCHRONOUS, evidence(() -> null, new ArrayList<>()));
    assertThat(evidenceNull.reasons()).containsExactly(SendGate.EVIDENCE_UNREADABLE);
  }

  @Test
  void anErrorThatIsNotAnExceptionIsNotSwallowed() {
    // only RuntimeException closes the gate: an AssertionError of a test double is a bug, not a
    // closed gate
    SendGate gate = new SendGate(SendGate.Requirement.production());
    LedgerDurability broken =
        () -> {
          throw new AssertionError("a bug");
        };
    assertThatThrownBy(() -> gate.check(broken, evidence(true, 2)))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void noneOpensWithoutReadingAnything() {
    List<String> calls = new ArrayList<>();
    EvidenceStore untouched =
        evidence(
            () -> {
              throw new AssertionError("none() read the evidence store");
            },
            calls);
    LedgerDurability untouchedLedger =
        () -> {
          throw new AssertionError("none() read the ledger");
        };
    SendGate.Result result =
        new SendGate(SendGate.Requirement.none()).check(untouchedLedger, untouched);
    assertThat(result.open()).isTrue();
    assertThat(result.reasons()).isEmpty();
    assertThat(calls).isEmpty();
  }

  @Test
  void aResultIsOpenIfAndOnlyIfItHasNoReason() {
    assertThat(new SendGate.Result(true, List.of()).open()).isTrue();
    assertThat(new SendGate.Result(false, List.of("why")).reasons()).containsExactly("why");
    assertThatThrownBy(() -> new SendGate.Result(true, List.of("why")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SendGate.Result(false, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    List<String> mine = new ArrayList<>(List.of("a"));
    SendGate.Result copy = new SendGate.Result(false, mine);
    mine.add("b");
    assertThat(copy.reasons()).containsExactly("a");
  }

  @Test
  void nullsAreRefused() {
    assertThatThrownBy(() -> new SendGate(null)).isInstanceOf(NullPointerException.class);
    SendGate gate = new SendGate(SendGate.Requirement.none());
    assertThatThrownBy(() -> gate.check(null, evidence(true, 2)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> gate.check(() -> SYNCHRONOUS, null))
        .isInstanceOf(NullPointerException.class);
  }
}
