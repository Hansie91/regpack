package dev.regpack.spikes.r.diff;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.GeneratedRule;
import dev.regpack.bench.data.PackShape;
import dev.regpack.bench.data.TradeRecord;
import dev.regpack.engine.skeleton.WalkingSkeletonRegime;
import dev.regpack.engine.skeleton.WalkingSkeletonRules;
import dev.regpack.model.IngestKey;
import dev.regpack.model.canonical.AssetClass;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.model.canonical.PartyKind;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.expr.Expression;
import dev.regpack.ports.regime.RegimeProvider.EvaluationContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * R-8, first half: a layer against the P0 walking skeleton. The four rows of the skeleton's rule
 * table run through the layer for the four golden cases of the P0 scope-engine test, and each
 * decision ({@code OUTCOME/ruleId}) is compared with what {@link WalkingSkeletonRegime} returns for
 * the same case. A disagreement is counted and described.
 */
public final class SkeletonDifferential {
  private static final Instant TRADED_AT = Instant.parse("2026-10-09T09:30:00.123456Z");

  private SkeletonDifferential() {}

  /** One golden case: the record for the table interpreter and the execution for the skeleton. */
  private record Golden(TradeRecord record, CanonicalExecution execution) {}

  /** The disagreements of the layer with the skeleton, one text per case; empty when it agrees. */
  public static List<String> disagreements(Expression layer) {
    WalkingSkeletonRegime regime = WalkingSkeletonRegime.standard();
    GeneratedPack pack = skeletonPack();
    List<String> out = new ArrayList<>();
    for (Golden g : goldenCases()) {
      DecisionBody body =
          regime
              .evaluate(
                  g.execution(),
                  new EvaluationContext(
                      WalkingSkeletonRegime.skeletonContext(),
                      new IngestKey("toy-source", "rec-1", 1),
                      Instant.parse("2026-10-09T10:00:00Z"),
                      new CostBudget(100)))
              .decision();
      String expected = body.outcome().name() + "/" + body.ruleIdsHit().get(0);
      String actual = DifferentialCheck.decisionOf(layer, pack, g.record());
      if (!expected.equals(actual)) {
        out.add("golden case " + g.record().seq() + ": skeleton " + expected + ", layer " + actual);
      }
    }
    return out;
  }

  private static List<Golden> goldenCases() {
    return List.of(
        golden(1, LifecycleKind.NOVATION, AssetClass.EQUITY, PartyKind.LEGAL_ENTITY),
        golden(2, LifecycleKind.NEW, AssetClass.EQUITY, PartyKind.NATURAL_PERSON),
        golden(3, LifecycleKind.NEW, AssetClass.EQUITY, PartyKind.LEGAL_ENTITY),
        golden(4, LifecycleKind.NEW, AssetClass.DEBT, PartyKind.LEGAL_ENTITY));
  }

  private static GeneratedPack skeletonPack() {
    List<GeneratedRule> rows = new ArrayList<>();
    for (WalkingSkeletonRules.Row row : WalkingSkeletonRules.rows()) {
      rows.add(
          new GeneratedRule(
              row.id(), 1, row.priority(), row.when(), row.outcome().name(), row.reason().value()));
    }
    return new GeneratedPack(
        "p0-skeleton", 0L, PackShape.S1_FIRST_HIT_REALISTIC, rows.size(), List.of(rows));
  }

  private static Golden golden(
      long seq, LifecycleKind kind, AssetClass assetClass, PartyKind clientKind) {
    TradeRecord record =
        new TradeRecord(
            seq,
            "EX-" + seq,
            1L,
            "SYNFIRM0000000000001",
            "XPAR",
            kind.name(),
            TRADED_AT,
            LocalDate.of(2026, 10, 9),
            LocalDate.of(2026, 10, 11),
            assetClass.name(),
            "ZZ0000000001",
            "ESVUFR",
            true,
            clientKind.name(),
            clientKind == PartyKind.LEGAL_ENTITY
                ? Optional.of("C0000000000000000001")
                : Optional.empty(),
            new BigDecimal("100"),
            new BigDecimal("12.25"),
            "EUR",
            "BUY",
            "PRINCIPAL",
            Optional.of("TRD001"),
            List.of());
    CanonicalExecution execution =
        new CanonicalExecution(
            "LEI-FIRM-0001",
            "XPAR",
            "EX-" + seq,
            kind,
            TRADED_AT,
            assetClass,
            "ZZ0000000001",
            true,
            clientKind,
            clientKind == PartyKind.LEGAL_ENTITY
                ? Optional.of("LEI-CLIENT-0001")
                : Optional.empty(),
            new BigDecimal("100"),
            new BigDecimal("12.25"),
            "EUR");
    return new Golden(record, execution);
  }
}
