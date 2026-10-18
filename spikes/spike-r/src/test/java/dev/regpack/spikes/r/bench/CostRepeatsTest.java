package dev.regpack.spikes.r.bench;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.PackShape;
import dev.regpack.bench.data.RulePackGen;
import dev.regpack.bench.data.TradeRecord;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.CompiledExpression;
import dev.regpack.ports.expr.Expression;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.ValueType;
import dev.regpack.spikes.r.cel.CelJavaExpression;
import dev.regpack.spikes.r.inhouse.InHouseExpression;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CostRepeatsTest {
  private static List<TradeRecord> inputs(int n) {
    List<TradeRecord> out = new ArrayList<>();
    new Corpus(3L, n).forEach(out::add);
    return out;
  }

  @Test
  void bothLayersChargeTheSameUnitsOnEveryRepeat() {
    for (PackShape shape : PackShape.values()) {
      GeneratedPack pack = RulePackGen.generate(1L, 40, shape);
      assertThat(CostRepeats.differingRuns(new InHouseExpression(), pack, inputs(10), 200))
          .as("in-house " + shape)
          .isZero();
      assertThat(CostRepeats.differingRuns(new CelJavaExpression(), pack, inputs(10), 200))
          .as("cel-java " + shape)
          .isZero();
    }
  }

  /** A layer whose every evaluation charges a different amount than the one before. */
  private static Expression drifting() {
    AtomicLong calls = new AtomicLong();
    return new Expression() {
      @Override
      public CompiledExpression compile(String source, Map<String, ValueType> variables) {
        return new CompiledExpression() {
          @Override
          public String source() {
            return source;
          }

          @Override
          public Value evaluate(Bindings bindings, CostMeter meter) {
            meter.charge(1 + calls.incrementAndGet() % 7);
            return new BoolValue(source.equals("true"));
          }
        };
      }
    };
  }

  @Test
  void aLayerWhoseCostDriftsIsCounted() {
    GeneratedPack pack = RulePackGen.generate(1L, 20, PackShape.S2_FULL_SCAN_WORST_CASE);
    assertThat(CostRepeats.differingRuns(drifting(), pack, inputs(5), 50)).isPositive();
  }

  @Test
  void aCorpusIsReadableTwiceWithTheSameRecords() {
    Corpus corpus = new Corpus(9L, 20);
    List<String> first = new ArrayList<>();
    List<String> second = new ArrayList<>();
    corpus.forEach(r -> first.add(r.executionId() + r.venueMic()));
    corpus.forEach(r -> second.add(r.executionId() + r.venueMic()));
    assertThat(first).hasSize(20).isEqualTo(second);
  }
}
