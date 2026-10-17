package dev.regpack.engine.expr.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.engine.expr.Rulings;
import dev.regpack.engine.expr.conformance.ConformanceHarness.Result;
import dev.regpack.engine.expr.conformance.RulingsFile.Row;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The four real switches each have an alternative that the evaluator implements. */
class RulingsSwitchTest {
  @Test
  void eachOfTheFourSwitchesHasAnAlternativeThatTheEvaluatorImplements() {
    String text = RulingsFile.text();
    Map<String, Vector> byId =
        VectorFile.load().stream().collect(Collectors.toMap(Vector::id, v -> v));
    List<Row> switches =
        RulingsFile.load().stream().filter(r -> !r.altValue().equals("-")).toList();
    assertThat(switches).extracting(Row::id).containsExactly("D13", "A04", "E06", "A07");
    for (Row row : switches) {
      Vector vector = byId.get(row.id());
      Rulings alternative = Rulings.parse(RulingsFile.withValue(text, row.id(), row.altValue()));
      assertThat(alternative).as(row.id()).isNotEqualTo(Rulings.provisional());
      Map<String, String> expectation = Map.of(row.id(), row.alternativeExpectation());
      // under the alternative the vector gives the alternative outcome ...
      List<Result> under =
          ConformanceHarness.run(
              ConformanceHarness.production(alternative), List.of(vector), expectation);
      assertThat(under.get(0).result()).as(row.id() + " under " + row.altValue()).isEqualTo("PASS");
      // ... and under the in-house value it does not (the switch changes a decision)
      List<Result> inHouse =
          ConformanceHarness.run(
              ConformanceHarness.production(Rulings.provisional()), List.of(vector), expectation);
      assertThat(inHouse.get(0).result()).as(row.id() + " in-house").isEqualTo("FAIL");
      // and the in-house value passes its own expectation
      Map<String, String> own = Map.of(row.id(), row.expected());
      assertThat(
              ConformanceHarness.run(
                      ConformanceHarness.production(Rulings.provisional()), List.of(vector), own)
                  .get(0)
                  .result())
          .isEqualTo("PASS");
    }
  }
}
