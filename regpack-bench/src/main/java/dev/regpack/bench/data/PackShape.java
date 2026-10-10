package dev.regpack.bench.data;

/** How a generated rule pack is hit by the synthetic records. */
public enum PackShape {
  /** The drawn templates as they are: a record usually hits an early row. */
  S1_FIRST_HIT_REALISTIC,
  /** A sentinel conjunct on every rule: only the catch-all hits, so every row is evaluated. */
  S2_FULL_SCAN_WORST_CASE
}
