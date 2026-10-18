package dev.regpack.bench.data;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.ReasonCode;
import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RulePackGenTest {
  private static final String SENTINEL = " && trade.currency == 'ZZZ'";

  private static GeneratedPack pack(long seed, int rules, PackShape shape) {
    return RulePackGen.generate(seed, rules, shape);
  }

  @Test
  void hasRequestedRuleCountAndACatchAllPerTable() {
    for (PackShape shape : PackShape.values()) {
      GeneratedPack p = pack(7, 200, shape);
      assertThat(p.ruleCount()).isEqualTo(200);
      assertThat(p.tables()).hasSize(10);
      int total = 0;
      for (List<GeneratedRule> table : p.tables()) {
        assertThat(table).hasSize(20);
        total += table.size();
        GeneratedRule last = table.get(table.size() - 1);
        assertThat(last.when()).isEqualTo("true");
        assertThat(last.outcome()).isEqualTo("UNDETERMINED");
        for (GeneratedRule rule : table.subList(0, table.size() - 1)) {
          assertThat(rule.when()).isNotEqualTo("true");
          assertThat(rule.outcome()).isIn("REPORT", "DO_NOT_REPORT", "REPORT_BY_OTHER");
        }
      }
      assertThat(total).isEqualTo(200);
    }
    assertThat(pack(1, 50, PackShape.S1_FIRST_HIT_REALISTIC).tables()).hasSize(2);
    assertThat(pack(1, 5_000, PackShape.S1_FIRST_HIT_REALISTIC).tables()).hasSize(250);
    GeneratedPack tiny = pack(1, 7, PackShape.S2_FULL_SCAN_WORST_CASE);
    assertThat(tiny.tables()).hasSize(1);
    assertThat(tiny.tables().get(0)).hasSize(7);
  }

  @Test
  void prioritiesAreUniquePerTable() {
    GeneratedPack p = pack(3, 500, PackShape.S1_FIRST_HIT_REALISTIC);
    Set<String> ids = new HashSet<>();
    for (List<GeneratedRule> table : p.tables()) {
      Set<Integer> priorities = new HashSet<>();
      int expected = 100;
      for (GeneratedRule rule : table) {
        assertThat(priorities.add(rule.priority())).isTrue();
        assertThat(rule.priority()).isEqualTo(expected);
        expected += 100;
        assertThat(ids.add(rule.id())).isTrue();
        assertThat(new ReasonCode(rule.reason()).value()).startsWith("SYN.T");
      }
    }
    GeneratedRule first = p.tables().get(0).get(0);
    assertThat(first.reason()).isEqualTo("SYN.T01.R001");
    assertThat(first.table()).isEqualTo(1);
  }

  @Test
  void s2RulesCarryTheSentinelAndS1DoNot() {
    GeneratedPack s1 = pack(11, 200, PackShape.S1_FIRST_HIT_REALISTIC);
    GeneratedPack s2 = pack(11, 200, PackShape.S2_FULL_SCAN_WORST_CASE);
    for (int t = 0; t < s1.tables().size(); t++) {
      List<GeneratedRule> a = s1.tables().get(t);
      List<GeneratedRule> b = s2.tables().get(t);
      for (int r = 0; r < a.size(); r++) {
        if (r == a.size() - 1) {
          assertThat(a.get(r).when()).isEqualTo("true");
          assertThat(b.get(r).when()).isEqualTo("true");
        } else {
          assertThat(a.get(r).when()).doesNotContain("trade.currency == 'ZZZ'");
          assertThat(b.get(r).when()).isEqualTo(a.get(r).when() + SENTINEL);
        }
        assertThat(b.get(r).outcome()).isEqualTo(a.get(r).outcome());
        assertThat(b.get(r).reason()).isEqualTo(a.get(r).reason());
      }
    }
    assertThat(s1.digest()).isNotEqualTo(s2.digest());
  }

  @Test
  void sentinelCurrencyIsNeverGenerated() {
    assertThat(RulePackGen.SENTINEL_CURRENCY).isEqualTo("ZZZ");
    assertThat(TradeRecordGen.currencies()).doesNotContain(RulePackGen.SENTINEL_CURRENCY);
    TradeRecordGen gen = new TradeRecordGen(12);
    for (int i = 0; i < 50_000; i++) {
      assertThat(gen.next().currency()).isNotEqualTo(RulePackGen.SENTINEL_CURRENCY);
    }
  }

  @Test
  void templateMixMatchesWeights() {
    GeneratedPack p = pack(21, 5_000, PackShape.S1_FIRST_HIT_REALISTIC);
    int[] counts = new int[7];
    int total = 0;
    for (List<GeneratedRule> table : p.tables()) {
      for (GeneratedRule rule : table.subList(0, table.size() - 1)) {
        counts[classify(rule.when())]++;
        total++;
      }
    }
    double[] weights = {35, 25, 15, 10, 5, 5, 5};
    String[] names = {"enum", "conjunct", "list", "decimal", "string", "date", "presence"};
    for (int i = 0; i < weights.length; i++) {
      double share = 100.0 * counts[i] / total;
      assertThat(share).as(names[i]).isBetween(weights[i] - 5, weights[i] + 5);
    }
  }

  private static int classify(String when) {
    if (when.startsWith("has(client.lei)")) {
      return 6;
    }
    if (when.contains(".startsWith('ZZ")) {
      return 4;
    }
    if (when.contains("dec('")) {
      return 3;
    }
    if (when.contains(" in [")) {
      return 2;
    }
    if (when.equals("trade.settlementDate > trade.tradeDate")) {
      return 5;
    }
    if (when.contains(" && instrument.tradedOnVenue")) {
      return 1;
    }
    assertThat(when).contains(" == '");
    return 0;
  }

  @Test
  void irIsCanonicalAndParsesBack() {
    GeneratedPack p = pack(1, 200, PackShape.S1_FIRST_HIT_REALISTIC);
    String ir = p.irJson();
    assertThat(Json.canonical(Json.parse(ir))).isEqualTo(ir);
    assertThat(Json.parse(ir)).isInstanceOf(List.class);
    assertThat((List<?>) Json.parse(ir)).hasSize(10);
    assertThat(p.digest()).isEqualTo(Sha256.ofUtf8(ir));
    assertThat(pack(1, 200, PackShape.S1_FIRST_HIT_REALISTIC).irJson()).isEqualTo(ir);
    assertThat(pack(2, 200, PackShape.S1_FIRST_HIT_REALISTIC).irJson()).isNotEqualTo(ir);
  }
}
