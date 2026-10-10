package dev.regpack.spikes.r.diff;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.CompiledExpression;
import dev.regpack.ports.expr.Expression;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.ValueType;
import dev.regpack.spikes.r.cel.CelJavaExpression;
import dev.regpack.spikes.r.inhouse.InHouseExpression;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkeletonDifferentialTest {
  @Test
  void inHouseAgreesWithTheSkeletonOnTheFourGoldenCases() {
    assertThat(SkeletonDifferential.disagreements(new InHouseExpression())).isEmpty();
  }

  @Test
  void celJavaAgreesToo() {
    assertThat(SkeletonDifferential.disagreements(new CelJavaExpression())).isEmpty();
  }

  @Test
  void aLayerThatNeverMatchesDisagreesAndIsCounted() {
    Expression never =
        new Expression() {
          @Override
          public CompiledExpression compile(String source, Map<String, ValueType> variables) {
            return new CompiledExpression() {
              @Override
              public String source() {
                return source;
              }

              @Override
              public Value evaluate(Bindings bindings, CostMeter meter) {
                meter.charge(1);
                return new BoolValue(source.equals("true"));
              }
            };
          }
        };
    assertThat(SkeletonDifferential.disagreements(never)).isNotEmpty();
  }
}
