package dev.regpack.engine.packci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.engine.expr.Rulings;
import dev.regpack.engine.pack.PackBuilder;
import dev.regpack.engine.pack.PackRegime;
import dev.regpack.engine.pack.RuleCircuit;
import dev.regpack.engine.pack.SamplePacks;
import dev.regpack.model.EngineBuild;
import dev.regpack.model.IngestKey;
import dev.regpack.model.canonical.AssetClass;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.model.canonical.PartyKind;
import dev.regpack.model.decision.InputDigest;
import dev.regpack.pack.model.LoadedPack;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code explain} on the sample pack: the static view of a rule and the Tier-2 view of an event.
 */
class PackExplainerTest {
  /**
   * U+202E RIGHT-TO-LEFT OVERRIDE, built from its code point so no bidi control sits in the source.
   */
  private static final String RLO = new String(Character.toChars(0x202E));

  private static final Instant TRADED_AT = Instant.parse("2026-10-09T09:30:00.123456Z");
  private static final Instant EVALUATED_AT = Instant.parse("2026-10-09T10:00:00Z");
  private static final IngestKey KEY = new IngestKey("toy-source", "rec-1", 1);

  private static CanonicalExecution execution(
      LifecycleKind kind, AssetClass assetClass, PartyKind clientKind, String isin) {
    return new CanonicalExecution(
        "LEI-FIRM-0001",
        "XPAR",
        "EX-1",
        kind,
        TRADED_AT,
        assetClass,
        isin,
        true,
        clientKind,
        clientKind == PartyKind.LEGAL_ENTITY ? Optional.of("LEI-CLIENT-0001") : Optional.empty(),
        new BigDecimal("100"),
        new BigDecimal("12.25"),
        "EUR");
  }

  private static CanonicalExecution naturalPerson() {
    return execution(
        LifecycleKind.NEW, AssetClass.EQUITY, PartyKind.NATURAL_PERSON, "ZZ0000000001");
  }

  private static PackRegime regimeOf(LoadedPack pack) {
    return PackRegime.create(pack, "sample.s0", new MeteredExecutor(), Rulings.provisional());
  }

  private static PackRegime sampleRegime() {
    return regimeOf(SamplePacks.load());
  }

  // ---- explainEvent ---------------------------------------------------------------------------

  @Test
  void explainsAnEventWithAFullTrace() {
    PackRegime regime = sampleRegime();
    CanonicalExecution event = naturalPerson();

    String text = PackExplainer.explainEvent(regime, event, KEY, EVALUATED_AT);

    String expected =
        String.join(
            "\n",
            "Decision 0 (seq 1) for LEI-FIRM-0001|XPAR|EX-1 under sample.s0 (not stored)",
            "Outcome: UNDETERMINED",
            "Reason: SAMPLE.N13.NATURAL_PERSON (node N13)",
            "Rule hits: S-D14-natural-person",
            "Path:",
            "  1. S-N03-novation: no match (8 units)",
            "  2. S-D14-natural-person: MATCH (4 units)",
            "Citations:",
            "  - regpack-spec D14 [INTERPRETATION]",
            "Flags: none",
            "Legs: none",
            "Input digest: " + InputDigest.of(event, KEY).hex(),
            "Context: " + regime.decisionContext().id().hex(),
            "Trace (full):",
            "  1. S-N03-novation: no match (8 units): event.kind in ['NOVATION','ASSIGNMENT']",
            "  2. S-D14-natural-person: MATCH (4 units): client.kind == 'NATURAL_PERSON'",
            "Tier 2: re-evaluated on the current engine 1.0.0+lib1"
                + " (stored build 1.0.0+lib1: replay-compatible)");
    assertThat(text).isEqualTo(expected);
  }

  @Test
  void theTraceHasOneLinePerEvaluatedRowAndEndsAtTheDecidingRow() {
    String text =
        PackExplainer.explainEvent(
            sampleRegime(),
            execution(LifecycleKind.NEW, AssetClass.DEBT, PartyKind.LEGAL_ENTITY, "ZZ0000000001"),
            KEY,
            EVALUATED_AT);

    String trace = text.substring(text.indexOf("Trace (full):"), text.indexOf("Tier 2:"));
    assertThat(trace.lines().toList())
        .hasSize(5)
        .anyMatch(l -> l.startsWith("  4. S-DEFAULT-catch-all: MATCH (2 units): true"));
    assertThat(trace).doesNotContain("ERROR");
  }

  @Test
  void aSkippedRowIsNotInTheTrace() {
    LoadedPack pack =
        PackBuilder.sample()
            .scope(
                PackBuilder.Rule.of("R-FUTURE", "event.kind == 'NEW'").validFrom("2030-01-01"),
                PackBuilder.Rule.of("R-NOW", "event.kind == 'NEW'"))
            .load();

    String text =
        PackExplainer.explainEvent(
            regimeOf(pack),
            execution(LifecycleKind.NEW, AssetClass.EQUITY, PartyKind.LEGAL_ENTITY, "ZZ0000000001"),
            KEY,
            EVALUATED_AT);

    assertThat(text).contains("1. R-NOW: MATCH").doesNotContain("R-FUTURE");
  }

  @Test
  void traceShowsTheErrorKindOfAFailingRow() {
    PackRegime regime =
        regimeOf(
            PackBuilder.sample()
                .scope(
                    PackBuilder.Rule.of("R-FIRST", "event.kind == 'NOVATION'"),
                    PackBuilder.Rule.of("R-FAIL", "1 / 0 == 1"))
                .load());

    String text = PackExplainer.explainEvent(regime, naturalPerson(), KEY, EVALUATED_AT);

    assertThat(text)
        .contains("Reason: EXEC_ERROR (node EXEC)\n")
        .contains("Trace (full):\n  1. R-FIRST: no match (")
        .containsPattern("\n  2\\. R-FAIL: ERROR DIV_ZERO \\([0-9]+ units\\): 1 / 0 == 1\n")
        .endsWith("(stored build 1.0.0+lib1: replay-compatible)");
    // the last trace line is the failing row: nothing was evaluated after it
    assertThat(text).doesNotContain("S-DEFAULT-catch-all: MATCH");
  }

  @Test
  void theTraceNeverEchoesAValueOfTheRecord() {
    // a rule that fails on a string value of the record: the flags, the path, the citations, the
    // trace and every line of the explanation carry the rule text and the error kind, not the value
    PackRegime regime =
        regimeOf(
            PackBuilder.sample()
                .scope(PackBuilder.Rule.of("R-ISIN", "instrument.isin + 1"))
                .load());
    CanonicalExecution event =
        execution(LifecycleKind.NEW, AssetClass.EQUITY, PartyKind.LEGAL_ENTITY, "SECRET-ISIN-7");

    String text = PackExplainer.explainEvent(regime, event, KEY, EVALUATED_AT);

    assertThat(text).doesNotContain("SECRET-ISIN-7").doesNotContain("SECRET");
    assertThat(text)
        .contains("Reason: EXEC_ERROR (node EXEC)\n")
        .containsPattern("R-ISIN: ERROR TYPE \\([0-9]+ units\\): instrument\\.isin \\+ 1");
  }

  @Test
  void aBudgetOverrunIsExplainedByItsFlags() {
    PackRegime regime =
        regimeOf(
            PackBuilder.sample()
                .limits("record_budget: 6")
                .scope(
                    PackBuilder.Rule.of(
                        "R-BIG", "instrument.isin in ['a','b','c','d','e','f','g','h']"))
                .load());

    String text =
        PackExplainer.explainEvent(
            regime,
            execution(LifecycleKind.NEW, AssetClass.EQUITY, PartyKind.LEGAL_ENTITY, "ZZ0000000001"),
            KEY,
            EVALUATED_AT);

    assertThat(text)
        .contains("Reason: EXEC_BUDGET (node EXEC)\n")
        .contains("Flags: rule_id=R-BIG, cost_units=6, limit=6\n")
        .contains("Trace (full): none\n");
  }

  @Test
  void explainingDoesNotFeedThePoisonCircuit() {
    RuleCircuit circuit = new RuleCircuit(1, 1);
    PackRegime regime =
        PackRegime.create(
            PackBuilder.sample().scope(PackBuilder.Rule.of("R-FAIL", "1 / 0 == 1")).load(),
            "sample.s0",
            new MeteredExecutor(),
            Rulings.provisional(),
            Optional.of(circuit));

    String first = PackExplainer.explainEvent(regime, naturalPerson(), KEY, EVALUATED_AT);
    String second = PackExplainer.explainEvent(regime, naturalPerson(), KEY, EVALUATED_AT);

    assertThat(circuit.isOpen("R-FAIL")).isFalse();
    assertThat(circuit.openEvents()).isZero();
    // the explanation shows the real failure, whatever the state of the circuit
    assertThat(first).isEqualTo(second).contains("R-FAIL: ERROR DIV_ZERO");

    // an open circuit does not hide the evaluation either: Tier 2 re-evaluates the rule
    circuit.record("R-FAIL", true);
    assertThat(circuit.isOpen("R-FAIL")).isTrue();
    assertThat(PackExplainer.explainEvent(regime, naturalPerson(), KEY, EVALUATED_AT))
        .contains("Reason: EXEC_ERROR (node EXEC)")
        .contains("R-FAIL: ERROR DIV_ZERO");
    assertThat(circuit.openEvents()).isEqualTo(1);
  }

  @Test
  void theTier2LineFollowsTheReplayRule() {
    EngineBuild current = new EngineBuild(1, 0, 0, 1);

    assertThat(PackExplainer.tier2Line("1.4.2+lib1", current))
        .isEqualTo(
            "Tier 2: re-evaluated on the current engine 1.0.0+lib1"
                + " (stored build 1.4.2+lib1: replay-compatible)");
    assertThat(PackExplainer.tier2Line("2.0.0+lib1", current))
        .isEqualTo(
            "Tier 2: not re-evaluated, the archived build 2.0.0+lib1 is required"
                + " (current engine 1.0.0+lib1)");
    // an unparsable stored build text is echoed bounded and escaped
    String hostile = PackExplainer.tier2Line("x\n" + RLO + "y".repeat(500), current);
    assertThat(hostile).startsWith("Tier 2: not re-evaluated, the archived build ");
    assertThat(hostile).doesNotContain("\n").doesNotContain(RLO).hasSizeLessThan(200);
  }

  @Test
  void explainIsDeterministic() {
    PackRegime regime = sampleRegime();
    String first = PackExplainer.explainEvent(regime, naturalPerson(), KEY, EVALUATED_AT);
    String rule = PackExplainer.explainRule(SamplePacks.load(), "S-N03-novation");
    for (int i = 0; i < 200; i++) {
      assertThat(PackExplainer.explainEvent(sampleRegime(), naturalPerson(), KEY, EVALUATED_AT))
          .isEqualTo(first);
      assertThat(PackExplainer.explainRule(SamplePacks.load(), "S-N03-novation")).isEqualTo(rule);
    }
  }

  // ---- explainRule ----------------------------------------------------------------------------

  @Test
  void explainsARuleWithReasonCitationsAndRationale() {
    String text = PackExplainer.explainRule(SamplePacks.load(), "S-N03-novation");

    String expected =
        String.join(
            "\n",
            "Rule S-N03-novation (table sample.s0.scope, priority 300)",
            "When: event.kind in ['NOVATION','ASSIGNMENT']",
            "Outcome: DO_NOT_REPORT",
            "Node: N03",
            "Valid: from 2018-01-03, open ended",
            "Reason: SAMPLE.N03.EXCLUDED (category EXCLUSION, owner agent.p2a-sample)",
            "Citations:",
            "  - sample-law sample/1 [BASIS]: source version sample-1,"
                + " provision IN_FORCE, role BASIS",
            "    quote: \"SYNTHETIC SAMPLE TEXT 1, not law\"",
            "    quote sha256: ef008e0c36ec6f5e6a2866208eb972bc5dbfc6ccb6bd891c06e3fa61da07efef",
            "Rationale RAT-0001: A novation or assignment is not a new trade in this synthetic"
                + " sample",
            "Golden cases: GC-N03-1 (CONSISTENCY_ONLY)");
    assertThat(text).isEqualTo(expected);
  }

  @Test
  void aCitationWithoutAQuoteAndTheCatchAllAreExplained() {
    String text = PackExplainer.explainRule(SamplePacks.load(), "S-DEFAULT-catch-all");

    assertThat(text)
        .contains("Rule S-DEFAULT-catch-all (table sample.s0.scope, priority 9999)\n")
        .contains("When: true\n")
        .contains("Outcome: UNDETERMINED\n")
        .contains("Valid: none (the catch-all has no window)\n")
        .contains("  - regpack-spec I5 [GUIDANCE]: source version 2026-10-08-draft-3,")
        .contains("provision IN_FORCE, role GUIDANCE\n")
        .doesNotContain("quote:")
        .contains("Golden cases: GC-DEFAULT-1 (CONSISTENCY_ONLY)");
  }

  @Test
  void legsSetValidityEndAndRuleRefAreExplained() {
    LoadedPack pack =
        PackBuilder.sample()
            .scope(
                PackBuilder.Rule.of("R-SET", "event.kind == 'NEW'")
                    .set("action: NEWT")
                    .validTo("2030-01-01"))
            .load();

    String text = PackExplainer.explainRule(pack, "R-SET");

    assertThat(text)
        .contains("Outcome: REPORT\n")
        .contains("Valid: from 2018-01-03, until 2030-01-01 (exclusive)\n")
        .contains("Legs: MARKET\n")
        .contains("Set: action=NEWT\n")
        .contains("Golden cases: none");
  }

  @Test
  void anUnknownRuleIsAnError() {
    LoadedPack pack = SamplePacks.load();

    assertThatThrownBy(() -> PackExplainer.explainRule(pack, "S-NOPE"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("S-NOPE");
    // the id is untrusted text: bounded and escaped in the message
    assertThatThrownBy(() -> PackExplainer.explainRule(pack, "x\n" + RLO + "y".repeat(500)))
        .isInstanceOf(IllegalArgumentException.class)
        .satisfies(
            e -> {
              assertThat(e.getMessage()).doesNotContain("\n").doesNotContain(RLO);
              assertThat(e.getMessage()).hasSizeLessThan(200);
            });
    assertThatThrownBy(() -> PackExplainer.explainRule(pack, "s-n03-novation"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
