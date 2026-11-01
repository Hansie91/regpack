package dev.regpack.flow.validate;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.flow.enrich.RefDataSeed;
import dev.regpack.flow.validate.Validator.Flaw;
import dev.regpack.flow.validate.Validator.ValidationResult;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.inmemory.InMemoryReferenceData;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.canonical.AssetClass;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.model.canonical.PartyKind;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.ports.ledger.DecisionLedger.NewDecision;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.DeliveryLedger.TransitionStatus;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.ReportLedger.NewVersion;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommit;
import dev.regpack.ports.ledger.ReportLedger.ReportableDecision;
import dev.regpack.ports.ledger.ReportLedger.SubjectKey;
import dev.regpack.ports.ledger.ReportLedger.TrnProposal;
import dev.regpack.ports.ledger.ReportLedger.VersionOrigin;
import dev.regpack.ports.refdata.ReferenceData.DatasetId;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.ident.SyntheticIds;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The world of a validator test (plan W1a, task T9): a manual clock at 2026-10-31T12:00:00Z, the
 * three reference seeds of the slice loaded at that instant with a maximum age of 30 days, an empty
 * in-memory ledger, and the validator over the fixture rules. Chains are built with {@link
 * #commitVersion}.
 */
final class ValidatorFixture {
  static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
  static final Instant TRADED_AT = Instant.parse("2026-10-30T09:30:00.123456Z");
  static final Regime REGIME = new Regime("demo.tr.s1");
  static final EndpointKey ENDPOINT =
      new EndpointKey("SIMULATOR", "SELF", SyntheticIds.FIRM_LEI, "SIM");
  static final Duration MAX_AGE = Duration.ofDays(30);
  private static final String SAMPLES = "/dev/regpack/flow/samples/";

  final ManualClock clock = new ManualClock(NOW);
  final InMemoryReferenceData refs = new InMemoryReferenceData(clock);
  final InMemoryLedgerStore ledger = new InMemoryLedgerStore(clock);
  final Validator validator;
  private final Map<String, Long> heads = new HashMap<>();

  ValidatorFixture(Flaw flaw) {
    for (String dataset : List.of("lei-status", "mic", "isin-mic")) {
      refs.load(new DatasetId(dataset), RefDataSeed.parse(sample(dataset + ".csv")), MAX_AGE);
    }
    validator =
        new Validator(
            ValidationRulesTest.fixtureRules(),
            refs,
            ledger,
            ledger,
            REGIME,
            Leg.MARKET,
            clock,
            flaw);
  }

  static String sample(String name) {
    try (InputStream in = ValidatorFixture.class.getResourceAsStream(SAMPLES + name)) {
      if (in == null) {
        throw new AssertionError("the sample " + name + " is missing");
      }
      return new String(in.readAllBytes(), UTF_8);
    } catch (IOException e) {
      throw new AssertionError("the sample " + name + " could not be read");
    }
  }

  /** The values of an execution, with the defaults of trade T-001 of the day-1 file. */
  static final class Exec {
    String firmLei = SyntheticIds.FIRM_LEI;
    String mic = "XPAR";
    String execId = "EX-001";
    LifecycleKind kind = LifecycleKind.NEW;
    Instant tradedAt = TRADED_AT;
    String isin = SyntheticIds.ISIN_1;
    Optional<String> clientLei = Optional.of(SyntheticIds.CLIENT1_LEI);
    BigDecimal quantity = new BigDecimal("100");
    BigDecimal price = new BigDecimal("12.25");
    String currency = "EUR";
    Optional<String> counterparty = Optional.of(SyntheticIds.CCP_LEI);

    CanonicalExecution build() {
      return new CanonicalExecution(
          firmLei,
          mic,
          execId,
          kind,
          tradedAt,
          AssetClass.EQUITY,
          isin,
          true,
          PartyKind.LEGAL_ENTITY,
          clientLei,
          quantity,
          price,
          currency,
          Optional.empty(),
          Optional.empty(),
          counterparty,
          Optional.empty());
    }
  }

  /** The derived fields that the rules read: the executing LEI of the execution. */
  static Map<String, String> derivedOf(Exec e) {
    return Map.of("executing_lei", e.firmLei);
  }

  ValidationResult run(Exec e) {
    return validator.validate(e.build(), derivedOf(e));
  }

  /**
   * Commits a REPORT decision with one version (action {@code action}) of {@code execId} on the
   * MARKET leg, the next of the chain of that event, and returns its delivery in PREPARED.
   */
  DeliveryId commitVersion(String execId, String action) {
    long head = heads.getOrDefault(execId, 0L);
    EventIdentity identity = EventIdentity.of(SyntheticIds.FIRM_LEI, "XPAR", execId);
    DecisionBody body =
        new DecisionBody(
            identity,
            REGIME,
            Sha256.ofUtf8("context"),
            Instant.parse("2026-10-30T09:30:00Z"),
            NOW,
            Outcome.REPORT,
            List.of(),
            new ReasonCode("DEMO.N08.IN_SCOPE"),
            "N08",
            List.of(),
            List.of(),
            List.of(),
            List.of(Leg.MARKET),
            Sha256.ofUtf8("digest-" + execId + "-" + (head + 1)));
    NewVersion version =
        new NewVersion(
            new SubjectKey(REGIME, identity, Leg.MARKET),
            SyntheticIds.FIRM_LEI,
            Optional.of(new TrnProposal("TRN" + execId.replace("-", ""), "KEY-1")),
            action,
            Sha256.ofUtf8(action + "-" + (head + 1)),
            "body".getBytes(UTF_8),
            VersionOrigin.SOURCE,
            ENDPOINT,
            1);
    DeliveryId delivery =
        ledger
            .commitReportable(
                new ReportableCommit(
                    List.of(new ReportableDecision(new NewDecision(body, head), List.of(version))),
                    Optional.empty(),
                    Optional.empty()))
            .get(0)
            .versions()
            .get(0)
            .delivery()
            .orElseThrow();
    heads.put(execId, head + 1);
    return delivery;
  }

  /** Moves a delivery out of PREPARED (the ledger checks no edge). */
  void setState(DeliveryId delivery, DeliveryState state) {
    TransitionStatus status = ledger.transition(delivery, DeliveryState.PREPARED, state);
    if (status != TransitionStatus.APPLIED) {
      throw new AssertionError("the transition to " + state + " was not applied: " + status);
    }
  }

  /** A NEWT of {@code execId} in the given state. */
  void newt(String execId, DeliveryState state) {
    setState(commitVersion(execId, "NEWT"), state);
  }

  /** A CANC of {@code execId} in the given state. */
  void canc(String execId, DeliveryState state) {
    setState(commitVersion(execId, "CANC"), state);
  }
}
