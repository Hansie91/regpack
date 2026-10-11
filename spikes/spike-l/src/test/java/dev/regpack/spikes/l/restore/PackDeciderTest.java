package dev.regpack.spikes.l.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.GeneratedRule;
import dev.regpack.bench.data.PackShape;
import dev.regpack.bench.data.RulePackGen;
import dev.regpack.bench.data.TradeRecord;
import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.model.json.Json;
import dev.regpack.spikes.l.restore.PackDecider.Decision;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * The decider of the rebuild reads the rule conditions that {@link RulePackGen} writes. It is
 * checked against the record itself: for every kind of condition the decider must agree with the
 * same condition written directly in Java over the typed record.
 */
class PackDeciderTest {
  private static GeneratedPack onePack(String when, String outcome) {
    List<GeneratedRule> table =
        List.of(
            new GeneratedRule("SYN.T01.R001", 1, 100, when, outcome, "SYN.T01.R001"),
            new GeneratedRule("SYN.T01.R002", 1, 200, "true", "UNDETERMINED", "SYN.T01.R002"));
    return new GeneratedPack("test", 1L, PackShape.S1_FIRST_HIT_REALISTIC, 2, List.of(table));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parse(TradeRecord r) {
    return (Map<String, Object>) Json.parse(r.toNdjsonLine());
  }

  private static void agree(String when, Predicate<TradeRecord> expected) {
    PackDecider decider = new PackDecider(onePack(when, "REPORT"));
    TradeRecordGen gen = new TradeRecordGen(5);
    int hits = 0;
    for (int i = 0; i < 3_000; i++) {
      TradeRecord r = gen.next();
      Decision d = decider.decide(parse(r));
      boolean want = expected.test(r);
      assertThat(d.outcome()).as(when + " on #" + i).isEqualTo(want ? "REPORT" : "UNDETERMINED");
      assertThat(d.ruleId()).isEqualTo(want ? "SYN.T01.R001" : "SYN.T01.R002");
      assertThat(d.rowsEvaluated()).isEqualTo(want ? 1 : 2);
      if (want) {
        hits++;
      }
    }
    // a condition that never or always holds would prove nothing about the decider
    assertThat(hits).as("hits of " + when).isBetween(1, 2_999);
  }

  @Test
  void equalityOnAString() {
    TradeRecordGen probe = new TradeRecordGen(5);
    String kind = probe.next().eventKind();
    agree("event.kind == '" + kind + "'", r -> r.eventKind().equals(kind));
    String capacity = new TradeRecordGen(5).next().capacity();
    agree("trade.capacity == '" + capacity + "'", r -> r.capacity().equals(capacity));
  }

  @Test
  void aBareBooleanPathAndAConjunction() {
    String asset = new TradeRecordGen(5).next().assetClass();
    agree(
        "instrument.assetClass == '" + asset + "' && instrument.tradedOnVenue",
        r -> r.assetClass().equals(asset) && r.tradedOnVenue());
  }

  @Test
  void membershipOfAList() {
    TradeRecordGen gen = new TradeRecordGen(5);
    String a = gen.next().venueMic();
    String b = gen.next().venueMic();
    String c = gen.next().venueMic();
    agree(
        "venue.mic in ['" + a + "', '" + b + "', '" + c + "']",
        r -> r.venueMic().equals(a) || r.venueMic().equals(b) || r.venueMic().equals(c));
  }

  @Test
  void decimalComparisons() {
    agree(
        "trade.price > dec('50') && trade.quantity >= dec('10')",
        r ->
            r.price().compareTo(new BigDecimal("50")) > 0
                && r.quantity().compareTo(new BigDecimal("10")) >= 0);
  }

  @Test
  void prefixAndLength() {
    String prefix = new TradeRecordGen(5).next().isin().substring(0, 3);
    agree(
        "instrument.isin.startsWith('" + prefix + "') && size(trade.currency) == 3",
        r -> r.isin().startsWith(prefix) && r.currency().length() == 3);
  }

  @Test
  void aDateAgainstADate() {
    agree("trade.settlementDate > trade.tradeDate", r -> r.settlementDate().isAfter(r.tradeDate()));
  }

  @Test
  void presenceOfAnOptionalField() {
    agree(
        "has(client.lei) && client.lei.startsWith('C')",
        r -> r.clientLei().isPresent() && r.clientLei().get().startsWith("C"));
  }

  @Test
  void everyConditionOfTheGeneratedPacksCompilesAndTheWorstCaseScansEveryRow() {
    GeneratedPack s2 = RulePackGen.generate(20_261_010L, 200, PackShape.S2_FULL_SCAN_WORST_CASE);
    PackDecider decider = new PackDecider(s2);
    TradeRecordGen gen = new TradeRecordGen(9);
    for (int i = 0; i < 200; i++) {
      Decision d = decider.decide(parse(gen.next()));
      // the sentinel conjunct holds for no record: only the catch-all of the last table hits
      assertThat(d.outcome()).isEqualTo("UNDETERMINED");
      assertThat(d.rowsEvaluated()).isEqualTo(200);
      assertThat(d.tablesVisited()).isEqualTo(10);
    }
    GeneratedPack s1 = RulePackGen.generate(20_261_010L, 200, PackShape.S1_FIRST_HIT_REALISTIC);
    PackDecider realistic = new PackDecider(s1);
    TradeRecordGen records = new TradeRecordGen(9);
    List<String> outcomes = new ArrayList<>();
    long rows = 0;
    for (int i = 0; i < 2_000; i++) {
      Decision d = realistic.decide(parse(records.next()));
      outcomes.add(d.outcome());
      rows += d.rowsEvaluated();
      assertThat(d.decidingRow()).isBetween(1, 20);
    }
    // S1 hits early: far fewer than 200 rows on average, and more than one outcome occurs
    assertThat(rows / 2_000.0).isLessThan(100.0);
    assertThat(outcomes.stream().distinct().count()).isGreaterThan(1);
  }

  @Test
  void theSameRecordAlwaysGivesTheSameDecision() {
    PackDecider decider =
        new PackDecider(RulePackGen.generate(3L, 200, PackShape.S1_FIRST_HIT_REALISTIC));
    Map<String, Object> record = parse(new TradeRecordGen(1).next());
    Decision a = decider.decide(record);
    Decision b = decider.decide(record);
    assertThat(a).isEqualTo(b);
  }

  @Test
  void aConditionOutsideTheGeneratorGrammarIsRefused() {
    assertThatThrownBy(() -> new PackDecider(onePack("trade.price * 2 > dec('5')", "REPORT")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("trade.price * 2");
  }

  @Test
  void anAbsentFieldFailsClosedUnlessTheConditionGuardsItWithHas() {
    TradeRecord withoutLei = null;
    TradeRecordGen gen = new TradeRecordGen(5);
    for (int i = 0; i < 1_000 && withoutLei == null; i++) {
      TradeRecord r = gen.next();
      if (r.clientLei().isEmpty()) {
        withoutLei = r;
      }
    }
    assertThat(withoutLei).isNotNull();
    Map<String, Object> record = parse(withoutLei);
    // as the in-house layer does (I5): reading an absent field is an error, never a silent false
    PackDecider unguarded = new PackDecider(onePack("client.lei.startsWith('C')", "REPORT"));
    assertThatThrownBy(() -> unguarded.decide(record))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("client.lei");
    // the generator always guards it, and the conjunction stops at the first false conjunct
    PackDecider guarded =
        new PackDecider(onePack("has(client.lei) && client.lei.startsWith('C')", "REPORT"));
    assertThat(guarded.decide(record).outcome()).isEqualTo("UNDETERMINED");
  }
}
