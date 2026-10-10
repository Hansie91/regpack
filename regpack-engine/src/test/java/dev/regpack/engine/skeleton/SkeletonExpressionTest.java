package dev.regpack.engine.skeleton;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.CompiledExpression;
import dev.regpack.ports.expr.ExpressionException;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.EnumValue;
import dev.regpack.ports.expr.ValueType;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SkeletonExpressionTest {
  private static final String NOVATION = "event.kind in ['NOVATION','ASSIGNMENT']";
  private static final String NATURAL = "client.kind == 'NATURAL_PERSON'";
  private static final String EQUITY =
      "instrument.assetClass == 'EQUITY' && instrument.tradedOnVenue";

  private final SkeletonExpression expression = new SkeletonExpression();

  private CompiledExpression compile(String source) {
    return expression.compile(source, ScopeProgram.VARIABLES);
  }

  private static Bindings bindings(
      String eventKind, String clientKind, String assetClass, boolean onVenue) {
    Map<String, Value> values =
        Map.of(
            "event.kind", new EnumValue("LifecycleKind", eventKind),
            "client.kind", new EnumValue("PartyKind", clientKind),
            "instrument.assetClass", new EnumValue("AssetClass", assetClass),
            "instrument.tradedOnVenue", new BoolValue(onVenue));
    return path -> Optional.ofNullable(values.get(path));
  }

  private static Value eval(CompiledExpression compiled, Bindings bindings) {
    return compiled.evaluate(bindings, new CostMeter(new CostBudget(100)));
  }

  @Test
  void evaluatesTheFourKnownSources() {
    Bindings novation = bindings("NOVATION", "LEGAL_ENTITY", "EQUITY", true);
    Bindings assignment = bindings("ASSIGNMENT", "LEGAL_ENTITY", "EQUITY", true);
    Bindings fresh = bindings("NEW", "NATURAL_PERSON", "EQUITY", true);
    Bindings debt = bindings("NEW", "LEGAL_ENTITY", "DEBT", true);
    Bindings offVenue = bindings("NEW", "LEGAL_ENTITY", "EQUITY", false);

    assertThat(eval(compile(NOVATION), novation)).isEqualTo(new BoolValue(true));
    assertThat(eval(compile(NOVATION), assignment)).isEqualTo(new BoolValue(true));
    assertThat(eval(compile(NOVATION), fresh)).isEqualTo(new BoolValue(false));
    assertThat(eval(compile(NATURAL), fresh)).isEqualTo(new BoolValue(true));
    assertThat(eval(compile(NATURAL), novation)).isEqualTo(new BoolValue(false));
    assertThat(eval(compile(EQUITY), fresh)).isEqualTo(new BoolValue(true));
    assertThat(eval(compile(EQUITY), debt)).isEqualTo(new BoolValue(false));
    assertThat(eval(compile(EQUITY), offVenue)).isEqualTo(new BoolValue(false));
    assertThat(eval(compile("true"), fresh)).isEqualTo(new BoolValue(true));
    assertThat(compile(EQUITY).source()).isEqualTo(EQUITY);
  }

  @Test
  void chargesDeclaredCostToTheMeter() {
    Bindings any = bindings("NEW", "LEGAL_ENTITY", "EQUITY", true);
    for (WalkingSkeletonRules.Row row : WalkingSkeletonRules.rows()) {
      CostMeter meter = new CostMeter(new CostBudget(100));
      compile(row.when()).evaluate(any, meter);
      assertThat(meter.used()).as(row.id()).isEqualTo(row.exprCost());
    }
    assertThat(WalkingSkeletonRules.rows())
        .extracting(WalkingSkeletonRules.Row::exprCost)
        .containsExactly(2, 1, 2, 0);
  }

  @Test
  void unknownSourceIsRejectedAtCompile() {
    assertThatThrownBy(() -> compile("event.kind == 'NEW'"))
        .isInstanceOf(ExpressionException.class);
    assertThatThrownBy(() -> compile("")).isInstanceOf(ExpressionException.class);
    assertThatThrownBy(() -> expression.compile(NATURAL, Map.of()))
        .isInstanceOf(ExpressionException.class);
    assertThatThrownBy(() -> expression.compile(NATURAL, Map.of("client.kind", ValueType.STRING)))
        .isInstanceOf(ExpressionException.class);
  }

  @Test
  void absentBindingThrowsExpressionException() {
    Bindings empty = path -> Optional.empty();
    for (String source : new String[] {NOVATION, NATURAL, EQUITY}) {
      CompiledExpression compiled = compile(source);
      assertThatThrownBy(() -> eval(compiled, empty)).isInstanceOf(ExpressionException.class);
    }
    assertThat(eval(compile("true"), empty)).isEqualTo(new BoolValue(true));
  }
}
