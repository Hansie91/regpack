package dev.regpack.engine.pack;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.engine.expr.Rulings;
import dev.regpack.engine.skeleton.WalkingSkeletonRegime;
import dev.regpack.model.Citation;
import dev.regpack.model.IngestKey;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.Sha256;
import dev.regpack.model.canonical.AssetClass;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.model.canonical.PartyKind;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.PathStep;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.pack.model.Provision;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.regime.RegimeProvider;
import dev.regpack.ports.regime.RegimeProvider.EvaluationContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The replacement changes nothing the decision means (plan T23): the sample pack through {@link
 * PackRegime} and the P0 walking skeleton agree on every execution of a grid of 6 event kinds x 4
 * asset classes x 2 client kinds x 2 venue flags. The two regimes differ in exactly four documented
 * ways; the reason-code and citation-path mappings are mappings, not differences.
 */
class PackRegimeMatchesSkeletonTest {
  private static final Instant TRADED_AT = Instant.parse("2026-10-09T09:30:00.123456Z");
  private static final Instant EVALUATED_AT = Instant.parse("2026-10-09T10:00:00Z");
  private static final IngestKey INGEST_KEY = new IngestKey("toy-source", "rec-1", 1);
  private static final long BUDGET = 1_000;

  /** Reason codes of the sample pack to those of the skeleton (the skeleton catch-all has none). */
  private static final Map<String, String> REASONS =
      Map.of(
          "SAMPLE.N03.EXCLUDED", "MIFIR.N03.EXCLUDED",
          "SAMPLE.N13.NATURAL_PERSON", "MIFIR.N13.NATURAL_PERSON",
          "SAMPLE.N08.IN_SCOPE", "MIFIR.N08.IN_SCOPE_26_2_A",
          "SAMPLE.DEFAULT.NO_RULE_HIT", "DEFAULT.NO_RULE_HIT");

  /** Citation (source, path) of the sample pack to that of the skeleton. */
  private static final Map<String, String> CITATIONS =
      Map.of(
          "sample-law|sample/1", "mifir-placeholder|art/2/par/5/pt/PLACEHOLDER",
          "sample-law|sample/2", "mifir-placeholder|art/26/par/2/pt/a",
          "regpack-spec|D14", "regpack-spec|D14",
          "regpack-spec|I5", "regpack-spec|I5");

  /** The components of a decision body; a new component must be classified here on purpose. */
  private static final List<String> COMPONENTS =
      List.of(
          "eventIdentity",
          "regime",
          "decisionContextId",
          "asAt",
          "evaluatedAt",
          "outcome",
          "flags",
          "reasonCode",
          "node",
          "ruleIdsHit",
          "path",
          "citations",
          "legs",
          "inputDigest",
          "attributes");

  /** The components that differ by documented design, each compared by its own rule below. */
  private static final Set<String> COMPARED_SEPARATELY =
      Set.of("regime", "decisionContextId", "reasonCode", "path", "citations");

  private record Pair(CanonicalExecution event, DecisionBody pack, DecisionBody skeleton) {}

  private static final LoadedPack LOADED = SamplePacks.load();

  private static final List<Pair> GRID = grid();

  private static CanonicalExecution execution(
      LifecycleKind kind, AssetClass assetClass, PartyKind client, boolean onVenue) {
    return new CanonicalExecution(
        "LEI-FIRM-0001",
        "XPAR",
        "EX-1",
        kind,
        TRADED_AT,
        assetClass,
        "ZZ0000000001",
        onVenue,
        client,
        client == PartyKind.LEGAL_ENTITY ? Optional.of("LEI-CLIENT-0001") : Optional.empty(),
        new BigDecimal("100"),
        new BigDecimal("12.25"),
        "EUR");
  }

  private static DecisionBody decide(
      RegimeProvider provider,
      dev.regpack.model.decision.DecisionContext context,
      CanonicalExecution event) {
    EvaluationContext evaluation =
        new EvaluationContext(context, INGEST_KEY, EVALUATED_AT, new CostBudget(BUDGET));
    return provider.evaluate(event, evaluation).decision();
  }

  private static List<Pair> grid() {
    PackRegime pack =
        PackRegime.create(LOADED, "sample.s0", new MeteredExecutor(), Rulings.provisional());
    WalkingSkeletonRegime skeleton = WalkingSkeletonRegime.standard();
    List<Pair> pairs = new ArrayList<>();
    for (LifecycleKind kind : LifecycleKind.values()) {
      for (AssetClass assetClass : AssetClass.values()) {
        for (PartyKind client : PartyKind.values()) {
          for (boolean onVenue : new boolean[] {true, false}) {
            CanonicalExecution event = execution(kind, assetClass, client, onVenue);
            pairs.add(
                new Pair(
                    event,
                    decide(pack, pack.decisionContext(), event),
                    decide(skeleton, WalkingSkeletonRegime.skeletonContext(), event)));
          }
        }
      }
    }
    return pairs;
  }

  private static Object component(DecisionBody body, String name) {
    for (RecordComponent c : DecisionBody.class.getRecordComponents()) {
      if (c.getName().equals(name)) {
        try {
          return c.getAccessor().invoke(body);
        } catch (IllegalAccessException | InvocationTargetException e) {
          throw new IllegalStateException(e);
        }
      }
    }
    throw new IllegalArgumentException(name);
  }

  private static String key(Citation c) {
    return c.sourceId() + "|" + c.provisionPath();
  }

  private static String mapped(String sourceAndPath) {
    String target = CITATIONS.get(sourceAndPath);
    assertThat(target).as("citation mapping for " + sourceAndPath).isNotNull();
    return target;
  }

  @Test
  void theGridHasNinetySixExecutionsAndNoneIsLost() {
    assertThat(GRID).hasSize(96);
    assertThat(GRID.stream().map(p -> p.pack().outcome()).distinct().count())
        .as("the grid reaches every outcome the sample can give")
        .isEqualTo(3);
  }

  @Test
  void theDecisionBodyHasNoComponentThisTestDoesNotKnow() {
    List<String> names = new ArrayList<>();
    for (RecordComponent c : DecisionBody.class.getRecordComponents()) {
      names.add(c.getName());
    }
    assertThat(names).containsExactlyElementsOf(COMPONENTS);
  }

  @Test
  void everyComponentThatDoesNotDifferByDesignIsEqual() {
    for (Pair p : GRID) {
      for (String name : COMPONENTS) {
        if (!COMPARED_SEPARATELY.contains(name)) {
          assertThat(component(p.pack(), name))
              .as(name + " of " + p.event())
              .isEqualTo(component(p.skeleton(), name));
        }
      }
    }
  }

  @Test
  void outcomeNodeRuleHitsAndLegsAreTheMeaningOfTheDecision() {
    for (Pair p : GRID) {
      assertThat(p.pack().outcome())
          .as("outcome of " + p.event())
          .isEqualTo(p.skeleton().outcome());
      assertThat(p.pack().node()).as("node of " + p.event()).isEqualTo(p.skeleton().node());
      assertThat(p.pack().ruleIdsHit()).isEqualTo(p.skeleton().ruleIdsHit());
      assertThat(p.pack().legs()).isEqualTo(p.skeleton().legs());
      assertThat(p.pack().flags()).isEmpty();
      assertThat(p.skeleton().flags()).isEmpty();
      assertThat(p.pack().attributes()).isEmpty();
    }
  }

  @Test
  void theSamePathStepsAreTakenWithTheSameMatchResults() {
    for (Pair p : GRID) {
      List<String> pack =
          p.pack().path().stream().map(s -> s.ruleId() + ":" + s.matched()).toList();
      List<String> skeleton =
          p.skeleton().path().stream().map(s -> s.ruleId() + ":" + s.matched()).toList();
      assertThat(pack).as("path of " + p.event()).isEqualTo(skeleton);
    }
  }

  @Test
  void reasonCodesAreEqualUnderTheFourPairMap() {
    for (Pair p : GRID) {
      String expected = REASONS.get(p.pack().reasonCode().value());
      assertThat(expected).as("mapping of " + p.pack().reasonCode()).isNotNull();
      assertThat(p.skeleton().reasonCode()).isEqualTo(new ReasonCode(expected));
    }
  }

  @Test
  void theReasonMapKeysAreExactlyTheCodesOfReasonsYaml() {
    assertThat(REASONS.keySet()).isEqualTo(LOADED.reasons().entries().keySet());
  }

  @Test
  void citationsAreEqualAfterThePathMapping() {
    for (Pair p : GRID) {
      List<String> pack =
          p.pack().citations().stream().map(c -> mapped(key(c)) + "|" + c.role()).toList();
      List<String> skeleton =
          p.skeleton().citations().stream().map(c -> key(c) + "|" + c.role()).toList();
      assertThat(pack).as("citations of " + p.event()).isEqualTo(skeleton);
    }
  }

  @Test
  void sampleLawCitationsCarryTheRegistryQuoteHashAndTheSpecOnesNone() {
    int sampleLaw = 0;
    int spec = 0;
    for (Pair p : GRID) {
      for (Citation c : p.pack().citations()) {
        if (c.sourceId().equals("sample-law")) {
          sampleLaw++;
          Optional<Sha256> registry =
              LOADED
                  .sources()
                  .resolve(c.sourceId(), c.provisionPath())
                  .flatMap(Provision::quoteSha256);
          assertThat(registry).as("registry hash of " + key(c)).isPresent();
          assertThat(c.quoteSha256()).isEqualTo(registry);
        } else {
          spec++;
          assertThat(c.sourceId()).isEqualTo("regpack-spec");
          assertThat(c.quoteSha256()).as(key(c)).isEmpty();
        }
      }
      for (Citation c : p.skeleton().citations()) {
        assertThat(c.quoteSha256()).as("the skeleton has no hash: " + key(c)).isEmpty();
      }
    }
    assertThat(sampleLaw).isPositive();
    assertThat(spec).isPositive();
  }

  @Test
  void theDocumentedDifferencesAreExactlyTheseFour() {
    Set<String> found = new TreeSet<>();
    Set<String> unitPairs = new TreeSet<>();
    for (Pair p : GRID) {
      if (!Objects.equals(p.pack().regime(), p.skeleton().regime())) {
        found.add("regime id");
      }
      if (!p.pack().decisionContextId().equals(p.skeleton().decisionContextId())) {
        found.add("decision context");
      }
      for (int i = 0; i < p.pack().path().size(); i++) {
        PathStep a = p.pack().path().get(i);
        PathStep b = p.skeleton().path().get(i);
        if (a.costUnits() != b.costUnits()) {
          found.add("cost units per step");
        }
        unitPairs.add(
            a.ruleId() + " " + a.matched() + " " + a.costUnits() + " vs " + b.costUnits());
      }
      for (int i = 0; i < p.pack().citations().size(); i++) {
        Citation a = p.pack().citations().get(i);
        Citation b = p.skeleton().citations().get(i);
        if (!a.quoteSha256().equals(b.quoteSha256())) {
          found.add("quoteSha256");
        }
      }
    }
    assertThat(found)
        .containsExactlyInAnyOrder(
            "regime id", "decision context", "cost units per step", "quoteSha256");
    // The matched rows cost 8, 4, 6 and 2 units against 3, 2, 3 and 1 in the skeleton.
    assertThat(unitPairs)
        .contains(
            "S-N03-novation true 8 vs 3",
            "S-D14-natural-person true 4 vs 2",
            "S-N08-equity-on-venue true 6 vs 3",
            "S-DEFAULT-catch-all true 2 vs 1");
  }
}
