package dev.regpack.engine.pack;

import static dev.regpack.ports.ledger.DecisionLedger.CommitStatus.INSERTED;
import static dev.regpack.ports.ledger.DecisionLedger.CommitStatus.NOOP_REDELIVERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.engine.expr.Rulings;
import dev.regpack.engine.packci.PackExplainer;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.model.Citation;
import dev.regpack.model.CitationRole;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.Sha256;
import dev.regpack.model.canonical.AssetClass;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.model.canonical.PartyKind;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.InputDigest;
import dev.regpack.model.decision.PathStep;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.pack.source.PackSource;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.ExecutionResult;
import dev.regpack.ports.exec.Executor;
import dev.regpack.ports.exec.RuleProgram;
import dev.regpack.ports.ledger.DecisionLedger;
import dev.regpack.ports.regime.RegimeProvider.EvaluationContext;
import dev.regpack.testkit.clock.ManualClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The I5 suite: the sample pack runs from its tables to decision records. Every budget in here is a
 * number of cost units; no clock is read.
 */
class PackScopeEngineTest {
  private static final Instant TRADED_AT = Instant.parse("2026-10-09T09:30:00.123456Z");
  private static final Instant EVALUATED_AT = Instant.parse("2026-10-09T10:00:00Z");
  private static final IngestKey INGEST_KEY = new IngestKey("toy-source", "rec-1", 1);
  private static final Citation CATCH_ALL_CITATION =
      new Citation("regpack-spec", "I5", CitationRole.GUIDANCE, Optional.empty());

  private final PackRegime regime =
      PackRegime.create(SamplePacks.load(), "sample.s0", new MeteredExecutor(), Rulings.load());

  private static CanonicalExecution execution(
      LifecycleKind kind, AssetClass assetClass, boolean tradedOnVenue, PartyKind clientKind) {
    return execution(kind, assetClass, tradedOnVenue, clientKind, "ZZ0000000001");
  }

  private static CanonicalExecution execution(
      LifecycleKind kind,
      AssetClass assetClass,
      boolean tradedOnVenue,
      PartyKind clientKind,
      String isin) {
    return new CanonicalExecution(
        "LEI-FIRM-0001",
        "XPAR",
        "EX-1",
        kind,
        TRADED_AT,
        assetClass,
        isin,
        tradedOnVenue,
        clientKind,
        clientKind == PartyKind.LEGAL_ENTITY ? Optional.of("LEI-CLIENT-0001") : Optional.empty(),
        new BigDecimal("100"),
        new BigDecimal("12.25"),
        "EUR");
  }

  private static CanonicalExecution equityOnVenue() {
    return execution(LifecycleKind.NEW, AssetClass.EQUITY, true, PartyKind.LEGAL_ENTITY);
  }

  private static EvaluationContext context(PackRegime provider, long budget) {
    return new EvaluationContext(
        provider.decisionContext(), INGEST_KEY, EVALUATED_AT, new CostBudget(budget));
  }

  private static DecisionBody decide(PackRegime provider, CanonicalExecution e, long budget) {
    return provider.evaluate(e, context(provider, budget)).decision();
  }

  private DecisionBody decide(CanonicalExecution e) {
    return decide(regime, e, 100);
  }

  private static PackRegime regimeOf(LoadedPack pack) {
    return PackRegime.create(pack, "sample.s0", new MeteredExecutor(), Rulings.load());
  }

  @Test
  void novationIsExcludedAndStopsAtTheFirstRow() {
    DecisionBody body =
        decide(execution(LifecycleKind.NOVATION, AssetClass.EQUITY, true, PartyKind.LEGAL_ENTITY));

    assertThat(body.outcome()).isEqualTo(Outcome.DO_NOT_REPORT);
    assertThat(body.reasonCode()).isEqualTo(new ReasonCode("SAMPLE.N03.EXCLUDED"));
    assertThat(body.node()).isEqualTo("N03");
    assertThat(body.ruleIdsHit()).containsExactly("S-N03-novation");
    assertThat(body.path()).containsExactly(new PathStep("S-N03-novation", true, 8));
    assertThat(body.legs()).isEmpty();
    assertThat(body.flags()).isEmpty();
    assertThat(body.attributes()).isEmpty();
  }

  @Test
  void naturalPersonIsUndeterminedNeverDoNotReport() {
    DecisionBody body =
        decide(execution(LifecycleKind.NEW, AssetClass.EQUITY, true, PartyKind.NATURAL_PERSON));

    assertThat(body.outcome()).isEqualTo(Outcome.UNDETERMINED);
    assertThat(body.reasonCode()).isEqualTo(new ReasonCode("SAMPLE.N13.NATURAL_PERSON"));
    assertThat(body.ruleIdsHit()).containsExactly("S-D14-natural-person");
    assertThat(body.path())
        .containsExactly(
            new PathStep("S-N03-novation", false, 8),
            new PathStep("S-D14-natural-person", true, 4));
    assertThat(body.legs()).isEmpty();
  }

  @Test
  void equityOnVenueIsReportedWithMarketLeg() {
    DecisionBody body = decide(equityOnVenue());

    assertThat(body.outcome()).isEqualTo(Outcome.REPORT);
    assertThat(body.reasonCode()).isEqualTo(new ReasonCode("SAMPLE.N08.IN_SCOPE"));
    assertThat(body.node()).isEqualTo("N08");
    assertThat(body.ruleIdsHit()).containsExactly("S-N08-equity-on-venue");
    assertThat(body.path())
        .containsExactly(
            new PathStep("S-N03-novation", false, 8),
            new PathStep("S-D14-natural-person", false, 4),
            new PathStep("S-N08-equity-on-venue", true, 6));
    assertThat(body.legs()).containsExactly(Leg.MARKET);
    assertThat(body.attributes()).isEmpty();
    assertThat(body.citations()).hasSize(1);
    assertThat(body.citations().get(0).sourceId()).isEqualTo("sample-law");
  }

  @Test
  void unmatchedInstrumentFallsToTheCatchAll() {
    DecisionBody body =
        decide(execution(LifecycleKind.NEW, AssetClass.DEBT, true, PartyKind.LEGAL_ENTITY));

    assertThat(body.outcome()).isEqualTo(Outcome.UNDETERMINED);
    assertThat(body.reasonCode()).isEqualTo(new ReasonCode("SAMPLE.DEFAULT.NO_RULE_HIT"));
    assertThat(body.node()).isEqualTo("DEFAULT");
    assertThat(body.ruleIdsHit()).containsExactly("S-DEFAULT-catch-all");
    assertThat(body.path())
        .containsExactly(
            new PathStep("S-N03-novation", false, 8),
            new PathStep("S-D14-natural-person", false, 4),
            new PathStep("S-N08-equity-on-venue", false, 5),
            new PathStep("S-DEFAULT-catch-all", true, 2));
  }

  @Test
  void exclusionPrecedesThePartyRule() {
    DecisionBody body =
        decide(
            execution(LifecycleKind.NOVATION, AssetClass.EQUITY, true, PartyKind.NATURAL_PERSON));

    assertThat(body.outcome()).isEqualTo(Outcome.DO_NOT_REPORT);
    assertThat(body.ruleIdsHit()).containsExactly("S-N03-novation");
  }

  @Test
  void budgetExhaustionYieldsUndeterminedExecBudget() {
    DecisionBody body = decide(regime, equityOnVenue(), 10);

    assertThat(body.outcome()).isEqualTo(Outcome.UNDETERMINED);
    assertThat(body.reasonCode()).isEqualTo(new ReasonCode("EXEC_BUDGET"));
    assertThat(body.node()).isEqualTo("EXEC");
    assertThat(body.flags())
        .containsExactly("rule_id=S-D14-natural-person", "cost_units=10", "limit=10");
    assertThat(body.ruleIdsHit()).isEmpty();
    assertThat(body.path()).containsExactly(new PathStep("S-N03-novation", false, 8));
    assertThat(body.legs()).isEmpty();
    assertThat(body.citations()).containsExactly(CATCH_ALL_CITATION);
  }

  @Test
  void everyFailureModeIsUndeterminedWithReasonAndCitation() {
    Map<String, String> whens =
        Map.of(
            "ABSENT", "client.lei == 'X'",
            "DIV_ZERO", "1 / 0 == 1",
            "TYPE", "1 + 1");
    for (Map.Entry<String, String> mode : whens.entrySet()) {
      PackRegime provider =
          regimeOf(
              PackBuilder.sample().scope(PackBuilder.Rule.of("R-FAIL", mode.getValue())).load());

      DecisionBody body =
          decide(
              provider,
              execution(LifecycleKind.NEW, AssetClass.EQUITY, true, PartyKind.NATURAL_PERSON),
              100);

      assertThat(body.outcome()).as(mode.getKey()).isEqualTo(Outcome.UNDETERMINED);
      assertThat(body.outcome()).isNotEqualTo(Outcome.DO_NOT_REPORT).isNotEqualTo(Outcome.REPORT);
      assertThat(body.reasonCode()).isEqualTo(new ReasonCode("EXEC_ERROR"));
      assertThat(body.node()).isEqualTo("EXEC");
      assertThat(body.ruleIdsHit()).isEmpty();
      assertThat(body.citations()).containsExactly(CATCH_ALL_CITATION);
      assertThat(body.flags()).contains("error_kind=" + mode.getKey(), "rule_id=R-FAIL");
    }
  }

  @Test
  void aFailureInTheSecondTableCitesTheLastCatchAll() {
    // two tables whose catch-alls cite different provisions; the first passes through, the second
    // fails. The EXEC decision cites the catch-all of the LAST table (companion 5.6, I5).
    PackSource built =
        PackBuilder.sample()
            .chain(
                List.of(PackBuilder.Rule.of("R-NOVATION", "event.kind == 'NOVATION'")),
                List.of(PackBuilder.Rule.of("R-FAIL", "1 + 1")))
            .source();
    Map<String, String> files = new TreeMap<>();
    for (String path : built.paths()) {
      if (!path.equals(PackSource.CHECKSUMS) && !path.equals(PackSource.ATTESTATION)) {
        files.put(path, built.text(path));
      }
    }
    String lastTable = "scope/second-table.yaml";
    String guidance = "cite: [{source_id: regpack-spec, provision_path: I5, role: GUIDANCE}]";
    assertThat(files.get(lastTable)).contains(guidance);
    files.put(
        lastTable,
        files
            .get(lastTable)
            .replace(
                guidance,
                "cite: [{source_id: sample-law, provision_path: sample/1, role: BASIS}]"));
    PackRegime provider = regimeOf(SamplePacks.loadSealed(files));

    DecisionBody body = decide(provider, equityOnVenue(), 100);

    assertThat(body.reasonCode()).isEqualTo(new ReasonCode("EXEC_ERROR"));
    assertThat(body.path())
        .extracting(PathStep::ruleId)
        .containsExactly("R-NOVATION", "S-DEFAULT-catch-all");
    assertThat(body.citations())
        .extracting(Citation::sourceId, Citation::provisionPath)
        .containsExactly(tuple("sample-law", "sample/1"));
  }

  @Test
  void setIsStoredInAttributes() {
    PackRegime provider =
        regimeOf(
            PackBuilder.sample()
                .scope(PackBuilder.Rule.of("R-SET", "event.kind == 'NEW'").set("action: NEWT"))
                .load());

    DecisionBody body = decide(provider, equityOnVenue(), 100);

    assertThat(body.ruleIdsHit()).containsExactly("R-SET");
    assertThat(body.attributes()).isEqualTo(Map.of("action", "NEWT"));
  }

  @Test
  void citationsCarryTheQuoteHashOfTheRegistry() {
    DecisionBody excluded =
        decide(execution(LifecycleKind.NOVATION, AssetClass.EQUITY, true, PartyKind.LEGAL_ENTITY));
    assertThat(excluded.citations())
        .containsExactly(
            new Citation(
                "sample-law",
                "sample/1",
                CitationRole.BASIS,
                Optional.of(
                    new Sha256(
                        "ef008e0c36ec6f5e6a2866208eb972bc5dbfc6ccb6bd891c06e3fa61da07efef"))));

    DecisionBody natural =
        decide(execution(LifecycleKind.NEW, AssetClass.EQUITY, true, PartyKind.NATURAL_PERSON));
    assertThat(natural.citations())
        .containsExactly(
            new Citation("regpack-spec", "D14", CitationRole.INTERPRETATION, Optional.empty()));
  }

  @Test
  void sameInputGivesTheSameReplayDigestOneThousandTimes() {
    DecisionBody first = decide(equityOnVenue());
    for (int i = 0; i < 1000; i++) {
      DecisionBody again = decide(equityOnVenue());
      assertThat(again.replayDigest()).isEqualTo(first.replayDigest());
      assertThat(again).isEqualTo(first);
    }
  }

  @Test
  void decisionIsStampedWithContextIdAndIngestKeyDigest() {
    CanonicalExecution execution = equityOnVenue();

    DecisionBody body = decide(execution);

    assertThat(body.decisionContextId()).isEqualTo(regime.decisionContext().id());
    assertThat(body.inputDigest()).isEqualTo(InputDigest.of(execution, INGEST_KEY));
    assertThat(body.eventIdentity()).isEqualTo(execution.eventIdentity());
    assertThat(body.regime()).isEqualTo(regime.regime());
    assertThat(body.regime().id()).isEqualTo("sample.s0");
    assertThat(body.asAt()).isEqualTo(TRADED_AT);
    assertThat(body.evaluatedAt()).isEqualTo(EVALUATED_AT);
  }

  @Test
  void execFlagsNeverEchoRecordValues() {
    PackRegime provider =
        regimeOf(
            PackBuilder.sample()
                .scope(PackBuilder.Rule.of("R-ISIN", "instrument.isin + 1"))
                .load());

    CanonicalExecution secret =
        execution(
            LifecycleKind.NEW, AssetClass.EQUITY, true, PartyKind.LEGAL_ENTITY, "SECRET-ISIN-7");

    DecisionBody body = decide(provider, secret, 100);

    assertThat(body.reasonCode()).isEqualTo(new ReasonCode("EXEC_ERROR"));
    // exactly the keys of the contract (companion section 5.6), in this order
    assertThat(body.flags().stream().map(f -> f.substring(0, f.indexOf('='))).toList())
        .containsExactly("rule_id", "error_class", "error_kind", "cost_units");
    // every flag VALUE of an EXEC_ERROR decision is a rule id, a class name, a kind or a count
    assertThat(body.flags())
        .hasSize(4)
        .anyMatch(f -> f.equals("rule_id=R-ISIN"))
        .anyMatch(f -> f.equals("error_class=ExprException"))
        .anyMatch(f -> f.equals("error_kind=TYPE"))
        .anyMatch(f -> f.matches("cost_units=[0-9]+"));
    assertThat(body.toString()).doesNotContain("SECRET-ISIN-7");
    assertThat(String.join("|", body.flags())).doesNotContain("SECRET");
    assertThat(body.path()).extracting(PathStep::ruleId).noneMatch(id -> id.contains("SECRET"));
    assertThat(List.copyOf(body.citations()).toString()).doesNotContain("SECRET");
    // the explanation (Tier 1 text and the full trace) does not echo the value either
    String explained = PackExplainer.explainEvent(provider, secret, INGEST_KEY, EVALUATED_AT);
    assertThat(explained)
        .contains("Reason: EXEC_ERROR (node EXEC)")
        .contains("R-ISIN: ERROR TYPE")
        .doesNotContain("SECRET-ISIN-7")
        .doesNotContain("SECRET");
  }

  @Test
  void execErrorFlagValuesNeverEchoARecordValueWhateverTheFailure() {
    // a record value reaches an error through each of these rules; no flag value may contain it
    Map<String, String> whens =
        Map.of(
            "TYPE", "instrument.isin + 1",
            "TYPE_COMPARE", "instrument.isin < 5",
            "ABSENT", "client.lei == instrument.isin");
    for (Map.Entry<String, String> mode : whens.entrySet()) {
      PackRegime provider =
          regimeOf(
              PackBuilder.sample().scope(PackBuilder.Rule.of("R-LEAK", mode.getValue())).load());
      CanonicalExecution secret =
          new CanonicalExecution(
              "LEI-FIRM-0001",
              "XPAR",
              "EX-1",
              LifecycleKind.NEW,
              TRADED_AT,
              AssetClass.EQUITY,
              "SECRET-ISIN-7",
              true,
              PartyKind.NATURAL_PERSON,
              Optional.empty(),
              new BigDecimal("100"),
              new BigDecimal("12.25"),
              "EUR");

      DecisionBody body = decide(provider, secret, 100);

      assertThat(body.reasonCode()).as(mode.getKey()).isEqualTo(new ReasonCode("EXEC_ERROR"));
      assertThat(body.flags()).as(mode.getKey()).hasSize(4);
      for (String flag : body.flags()) {
        assertThat(flag.substring(flag.indexOf('=') + 1))
            .as(mode.getKey() + " " + flag)
            .matches("[A-Za-z][A-Za-z0-9_.-]*|[0-9]+")
            .doesNotContain("SECRET");
      }
      assertThat(PackExplainer.explainEvent(provider, secret, INGEST_KEY, EVALUATED_AT))
          .as(mode.getKey())
          .doesNotContain("SECRET");
    }
  }

  // ---- T17: the poison circuit (spec 7.3, 14.1); every number here is a count, never a time ----

  private static final String BIG_LIST_WHEN =
      "instrument.isin in ['a','b','c','d','e','f','g','h']";

  private static PackRegime regimeOf(LoadedPack pack, RuleCircuit circuit) {
    return PackRegime.create(
        pack, "sample.s0", new MeteredExecutor(), Rulings.load(), Optional.of(circuit));
  }

  private static LoadedPack overBudgetPack() {
    return PackBuilder.sample()
        .limits("record_budget: 6")
        .scope(PackBuilder.Rule.of("R-BIG", BIG_LIST_WHEN))
        .load();
  }

  /** Reason and flags of five records of the seeded over-budget rule, with a fresh circuit. */
  private static List<String> fiveOverBudgetRecords() {
    PackRegime provider = regimeOf(overBudgetPack(), new RuleCircuit(4, 50));
    List<String> seen = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      DecisionBody body = decide(provider, equityOnVenue(), provider.recordBudget().maxUnits());
      seen.add(body.reasonCode().value() + " " + body.flags());
    }
    return seen;
  }

  @Test
  void seededOverBudgetRuleOpensItsCircuitDeterministically() {
    List<String> first = fiveOverBudgetRecords();

    assertThat(first.subList(0, 4))
        .allSatisfy(line -> assertThat(line).startsWith("EXEC_BUDGET [rule_id=R-BIG, cost_units="));
    assertThat(first.get(4)).isEqualTo("EXEC_CIRCUIT_OPEN [rule_id=R-BIG, circuit=open]");
    // a second run with a fresh regime and circuit gives the identical sequence
    assertThat(fiveOverBudgetRecords()).isEqualTo(first);
  }

  @Test
  void aClosedCircuitEvaluatesTheRuleAgain() {
    RuleCircuit circuit = new RuleCircuit(4, 50);
    PackRegime provider = regimeOf(overBudgetPack(), circuit);
    long budget = provider.recordBudget().maxUnits();
    for (int i = 0; i < 4; i++) {
      assertThat(decide(provider, equityOnVenue(), budget).reasonCode())
          .isEqualTo(new ReasonCode("EXEC_BUDGET"));
    }
    assertThat(decide(provider, equityOnVenue(), budget).reasonCode())
        .isEqualTo(new ReasonCode("EXEC_CIRCUIT_OPEN"));

    circuit.close("R-BIG");

    DecisionBody again = decide(provider, equityOnVenue(), budget);
    assertThat(again.reasonCode()).isEqualTo(new ReasonCode("EXEC_BUDGET"));
    assertThat(again.flags()).hasSize(3).first().isEqualTo("rule_id=R-BIG");
    assertThat(again.flags()).last().isEqualTo("limit=6");
    assertThat(circuit.isOpen("R-BIG")).isFalse();
  }

  @Test
  void anOpenCircuitIsReportedAsExecCircuitOpenNotExecError() {
    RuleCircuit circuit = new RuleCircuit(1, 1);
    PackRegime provider =
        regimeOf(
            PackBuilder.sample().scope(PackBuilder.Rule.of("R-FAIL", "1 / 0 == 1")).load(),
            circuit);

    DecisionBody failing = decide(provider, equityOnVenue(), 100);
    DecisionBody open = decide(provider, equityOnVenue(), 100);

    assertThat(failing.reasonCode()).isEqualTo(new ReasonCode("EXEC_ERROR"));
    assertThat(circuit.isOpen("R-FAIL")).isTrue();
    assertThat(open.reasonCode()).isEqualTo(new ReasonCode("EXEC_CIRCUIT_OPEN"));
    assertThat(open.outcome()).isEqualTo(Outcome.UNDETERMINED);
    assertThat(open.node()).isEqualTo("EXEC");
    assertThat(open.ruleIdsHit()).isEmpty();
    assertThat(open.legs()).isEmpty();
    assertThat(open.attributes()).isEmpty();
    assertThat(open.citations()).containsExactly(CATCH_ALL_CITATION);
    assertThat(open.flags()).containsExactly("rule_id=R-FAIL", "circuit=open");
    assertThat(open.path()).as("no charge and no path step for the open rule").isEmpty();
  }

  @Test
  void theFlagNamesTheOpenRuleNotThePreviousRow() {
    RuleCircuit circuit = new RuleCircuit(1, 1);
    PackRegime provider =
        regimeOf(
            PackBuilder.sample()
                .scope(
                    PackBuilder.Rule.of("R-FIRST", "event.kind == 'NOVATION'"),
                    PackBuilder.Rule.of("R-SECOND", "1 / 0 == 1"))
                .load(),
            circuit);
    decide(provider, equityOnVenue(), 100);
    assertThat(circuit.openRules()).containsExactly("R-SECOND");

    DecisionBody open = decide(provider, equityOnVenue(), 100);

    assertThat(open.reasonCode()).isEqualTo(new ReasonCode("EXEC_CIRCUIT_OPEN"));
    assertThat(open.flags()).containsExactly("rule_id=R-SECOND", "circuit=open");
    // the first row ran and missed; the open row left no step
    assertThat(open.path()).hasSize(1);
    assertThat(open.path().get(0).ruleId()).isEqualTo("R-FIRST");
  }

  @Test
  void aRecordThatDoesNotReachTheOpenRuleIsDecidedNormally() {
    RuleCircuit circuit = new RuleCircuit(1, 1);
    PackRegime provider =
        regimeOf(
            PackBuilder.sample()
                .scope(
                    PackBuilder.Rule.of("R-NOVATION", "event.kind == 'NOVATION'")
                        .outcome("N03", "DO_NOT_REPORT", "SAMPLE.N03.EXCLUDED"),
                    PackBuilder.Rule.of("R-FAIL", "1 / 0 == 1"))
                .load(),
            circuit);
    decide(provider, equityOnVenue(), 100);
    assertThat(circuit.isOpen("R-FAIL")).isTrue();

    DecisionBody body =
        decide(
            provider,
            execution(LifecycleKind.NOVATION, AssetClass.EQUITY, true, PartyKind.LEGAL_ENTITY),
            100);

    assertThat(body.reasonCode()).isEqualTo(new ReasonCode("SAMPLE.N03.EXCLUDED"));
    assertThat(body.ruleIdsHit()).containsExactly("R-NOVATION");
  }

  @Test
  void successfulEvaluationsAreRecordedAndSkippedRowsAreNot() {
    // n = 2 in m = 3: a failure every third record never puts two failures in one window
    RuleCircuit circuit = new RuleCircuit(2, 3);
    PackRegime provider =
        regimeOf(
            PackBuilder.sample()
                .scope(
                    PackBuilder.Rule.of("R-FUTURE", "1 / 0 == 1").validFrom("2030-01-01"),
                    PackBuilder.Rule.of("R-MIXED", "event.kind == 'NEW' ? 1 / 0 == 1 : true"))
                .load(),
            circuit);
    CanonicalExecution failing = equityOnVenue();
    CanonicalExecution passing =
        execution(LifecycleKind.NOVATION, AssetClass.EQUITY, true, PartyKind.LEGAL_ENTITY);
    for (int i = 0; i < 4; i++) {
      assertThat(decide(provider, failing, 100).reasonCode())
          .isEqualTo(new ReasonCode("EXEC_ERROR"));
      decide(provider, passing, 100);
      decide(provider, passing, 100);
    }

    assertThat(circuit.openEvents()).isZero();
    assertThat(circuit.isOpen("R-FUTURE")).isFalse();

    // two failures in a row put two failures in one window
    decide(provider, failing, 100);
    decide(provider, failing, 100);
    assertThat(circuit.openRules()).containsExactly("R-MIXED");
  }

  @Test
  void anOpenCircuitIsRecognisedWhateverNameTheExecutorGivesTheErrorClass() {
    // The Executor port does not define how an executor names an error class: a second executor
    // may report the fully qualified class name. The mapping must not depend on the simple name.
    Executor qualified =
        new Executor() {
          private final MeteredExecutor inner = new MeteredExecutor();

          @Override
          public String id() {
            return inner.id();
          }

          @Override
          public <I, O> ExecutionResult<O> execute(
              RuleProgram<I, O> program, I input, CostBudget budget) {
            ExecutionResult<O> result = inner.execute(program, input, budget);
            if (result instanceof ExecutionResult.Failed<O> failed) {
              return new ExecutionResult.Failed<>(
                  failed.ruleId(),
                  "dev.regpack.somewhere." + failed.errorClass(),
                  failed.costUnits(),
                  failed.path());
            }
            return result;
          }
        };
    RuleCircuit circuit = new RuleCircuit(1, 1);
    PackRegime provider =
        PackRegime.create(
            PackBuilder.sample().scope(PackBuilder.Rule.of("R-FAIL", "1 / 0 == 1")).load(),
            "sample.s0",
            qualified,
            Rulings.load(),
            Optional.of(circuit));

    DecisionBody failing = decide(provider, equityOnVenue(), 100);
    DecisionBody open = decide(provider, equityOnVenue(), 100);

    assertThat(failing.reasonCode()).isEqualTo(new ReasonCode("EXEC_ERROR"));
    assertThat(open.reasonCode()).isEqualTo(new ReasonCode("EXEC_CIRCUIT_OPEN"));
    assertThat(open.flags()).containsExactly("rule_id=R-FAIL", "circuit=open");
  }

  @Test
  void aReEvaluationAfterCloseHasTheSameContextAndDigestSoTheLedgerTreatsItAsARedelivery() {
    // P3 must resolve this deliberately (circuit epoch in the context or an ordinal in the key);
    // see docs/pack-format/p2a-format-notes.md hand-offs. This test pins the model as built: it
    // does NOT supersede a decision made under an open circuit.
    RuleCircuit circuit = new RuleCircuit(1, 50);
    PackRegime provider =
        regimeOf(
            PackBuilder.sample()
                .scope(
                    PackBuilder.Rule.of(
                            "R-MIXED",
                            "event.kind == 'NEW' ? 1 / 0 == 1 : event.kind == 'NOVATION'")
                        .outcome("N03", "DO_NOT_REPORT", "SAMPLE.N03.EXCLUDED"))
                .load(),
            circuit);
    CanonicalExecution poison = equityOnVenue();
    CanonicalExecution innocent =
        execution(LifecycleKind.NOVATION, AssetClass.EQUITY, true, PartyKind.LEGAL_ENTITY);
    assertThat(decide(provider, poison, 100).reasonCode()).isEqualTo(new ReasonCode("EXEC_ERROR"));
    assertThat(circuit.isOpen("R-MIXED")).isTrue();

    DecisionBody underOpenCircuit = decide(provider, innocent, 100);
    assertThat(underOpenCircuit.reasonCode()).isEqualTo(new ReasonCode("EXEC_CIRCUIT_OPEN"));
    InMemoryLedgerStore ledger =
        new InMemoryLedgerStore(new ManualClock(Instant.parse("2026-10-09T08:00:00Z")));
    List<DecisionLedger.Committed> first =
        ledger.commitDecisions(
            new DecisionLedger.BatchCommit(
                List.of(new DecisionLedger.NewDecision(underOpenCircuit, 0)), Optional.empty()));
    assertThat(first).extracting(DecisionLedger.Committed::status).containsExactly(INSERTED);

    circuit.close("R-MIXED");
    DecisionBody afterClose = decide(provider, innocent, 100);
    assertThat(afterClose.reasonCode()).isEqualTo(new ReasonCode("SAMPLE.N03.EXCLUDED"));

    // nothing that the ledger keys on changed between the two decisions
    assertThat(afterClose.decisionContextId()).isEqualTo(underOpenCircuit.decisionContextId());
    assertThat(afterClose.inputDigest()).isEqualTo(underOpenCircuit.inputDigest());
    List<DecisionLedger.Committed> second =
        ledger.commitDecisions(
            new DecisionLedger.BatchCommit(
                List.of(new DecisionLedger.NewDecision(afterClose, 1)), Optional.empty()));
    assertThat(second)
        .extracting(DecisionLedger.Committed::status)
        .containsExactly(NOOP_REDELIVERY);
    assertThat(ledger.currentDecision(provider.regime(), innocent.eventIdentity()))
        .hasValueSatisfying(
            current ->
                assertThat(current.body().reasonCode())
                    .isEqualTo(new ReasonCode("EXEC_CIRCUIT_OPEN")));
  }

  @Test
  void aProviderWithoutACircuitNeverOpensOne() {
    PackRegime provider =
        regimeOf(PackBuilder.sample().scope(PackBuilder.Rule.of("R-FAIL", "1 / 0 == 1")).load());

    for (int i = 0; i < 100; i++) {
      assertThat(decide(provider, equityOnVenue(), 100).reasonCode())
          .isEqualTo(new ReasonCode("EXEC_ERROR"));
    }
  }
}
