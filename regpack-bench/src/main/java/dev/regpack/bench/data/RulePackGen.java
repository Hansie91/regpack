package dev.regpack.bench.data;

import dev.regpack.model.Outcome;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates synthetic rule packs. Both shapes of one seed draw the same templates; S2 only appends
 * a sentinel conjunct, so the two packs differ in nothing else.
 */
public final class RulePackGen {
  /** A currency that {@link TradeRecordGen} never generates. */
  public static final String SENTINEL_CURRENCY = "ZZZ";

  private static final String SENTINEL = " && trade.currency == '" + SENTINEL_CURRENCY + "'";
  private static final String CATCH_ALL = "true";
  private static final String BASE36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final List<String> NON_CATCH_ALL_OUTCOMES =
      List.of(Outcome.REPORT.name(), Outcome.DO_NOT_REPORT.name(), Outcome.REPORT_BY_OTHER.name());
  private static final int RULES_PER_TABLE = 20;

  private RulePackGen() {}

  public static GeneratedPack generate(long seed, int ruleCount, PackShape shape) {
    if (ruleCount < 1) {
      throw new IllegalArgumentException("ruleCount must be at least 1: " + ruleCount);
    }
    SplitMix64 rng = new SplitMix64(seed);
    int tableCount = Math.max(1, ruleCount / RULES_PER_TABLE);
    int base = ruleCount / tableCount;
    int extra = ruleCount % tableCount;
    List<List<GeneratedRule>> tables = new ArrayList<>(tableCount);
    for (int t = 0; t < tableCount; t++) {
      int rows = base + (t < extra ? 1 : 0);
      List<GeneratedRule> table = new ArrayList<>(rows);
      for (int r = 0; r < rows; r++) {
        String code = "SYN.T" + pad(t + 1, 2) + ".R" + pad(r + 1, 3);
        int priority = (r + 1) * 100;
        if (r == rows - 1) {
          table.add(
              new GeneratedRule(
                  code, t + 1, priority, CATCH_ALL, Outcome.UNDETERMINED.name(), code));
        } else {
          String when = template(rng);
          String outcome = NON_CATCH_ALL_OUTCOMES.get(rng.nextInt(NON_CATCH_ALL_OUTCOMES.size()));
          if (shape == PackShape.S2_FULL_SCAN_WORST_CASE) {
            when = when + SENTINEL;
          }
          table.add(new GeneratedRule(code, t + 1, priority, when, outcome, code));
        }
      }
      tables.add(table);
    }
    String id =
        "SYN-" + (shape == PackShape.S1_FIRST_HIT_REALISTIC ? "S1" : "S2") + "-" + ruleCount;
    return new GeneratedPack(id + "-" + seed, seed, shape, ruleCount, tables);
  }

  /** One rule condition: the weights are 35, 25, 15, 10, 5, 5 and 5 percent. */
  private static String template(SplitMix64 rng) {
    int draw = rng.nextInt(100);
    if (draw < 35) {
      return switch (rng.nextInt(3)) {
        case 0 -> "event.kind == '" + pick(rng, TradeRecordGen.EVENT_KINDS) + "'";
        case 1 -> "instrument.assetClass == '" + pick(rng, TradeRecordGen.ASSET_CLASSES) + "'";
        default -> "trade.capacity == '" + pick(rng, TradeRecordGen.CAPACITIES) + "'";
      };
    }
    if (draw < 60) {
      return "instrument.assetClass == '"
          + pick(rng, TradeRecordGen.ASSET_CLASSES)
          + "' && instrument.tradedOnVenue";
    }
    if (draw < 75) {
      int start = rng.nextInt(TradeRecordGen.VENUES.size() - 2);
      List<String> mics = TradeRecordGen.VENUES.subList(start, start + 3);
      return "venue.mic in ['" + mics.get(0) + "', '" + mics.get(1) + "', '" + mics.get(2) + "']";
    }
    if (draw < 85) {
      String[] prices = {"10", "50", "100", "500"};
      String[] quantities = {"1", "10", "100"};
      return "trade.price > dec('"
          + prices[rng.nextInt(prices.length)]
          + "') && trade.quantity >= dec('"
          + quantities[rng.nextInt(quantities.length)]
          + "')";
    }
    if (draw < 90) {
      return "instrument.isin.startsWith('ZZ"
          + BASE36.charAt(rng.nextInt(36))
          + "') && size(trade.currency) == 3";
    }
    if (draw < 95) {
      return "trade.settlementDate > trade.tradeDate";
    }
    String[] prefixes = {"C", "K"};
    return "has(client.lei) && client.lei.startsWith('" + prefixes[rng.nextInt(2)] + "')";
  }

  private static String pick(SplitMix64 rng, List<String> values) {
    return values.get(rng.nextInt(values.size()));
  }

  private static String pad(int value, int width) {
    String digits = Integer.toString(value);
    StringBuilder out = new StringBuilder(width);
    for (int i = digits.length(); i < width; i++) {
      out.append('0');
    }
    return out.append(digits).toString();
  }
}
