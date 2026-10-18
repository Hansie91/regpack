package dev.regpack.pack.model;

import dev.regpack.pack.PackFormat;
import java.util.OptionalLong;

/**
 * The limits a pack declares for its expressions (plan defaults, not SP-R output; provisional until
 * a G-S3b pass). Each is at least 1 and at most the engine hard cap of {@link PackFormat}.
 *
 * @param ruleNodes most nodes of one expression (the per-rule ceiling of spec 6.2)
 * @param listItems most items of a list value
 * @param stringCodePoints most code points of a string value
 * @param recordBudget the per-record budget in cost units, if the pack declares one
 */
public record PackLimits(
    int ruleNodes, int listItems, int stringCodePoints, OptionalLong recordBudget) {
  public PackLimits {
    check("ruleNodes", ruleNodes, PackFormat.MAX_RULE_NODES);
    check("listItems", listItems, PackFormat.MAX_LIST_ITEMS);
    check("stringCodePoints", stringCodePoints, PackFormat.MAX_STRING_CODE_POINTS);
    if (recordBudget.isPresent()
        && (recordBudget.getAsLong() < 1
            || recordBudget.getAsLong() > PackFormat.MAX_RECORD_BUDGET)) {
      throw new IllegalArgumentException(
          "recordBudget must be in 1.." + PackFormat.MAX_RECORD_BUDGET);
    }
  }

  private static void check(String name, int value, int cap) {
    if (value < 1 || value > cap) {
      throw new IllegalArgumentException(name + " must be in 1.." + cap);
    }
  }

  /** 512 nodes, 256 list items, 4096 code points, no declared record budget. */
  public static PackLimits defaults() {
    return new PackLimits(512, 256, 4096, OptionalLong.empty());
  }
}
