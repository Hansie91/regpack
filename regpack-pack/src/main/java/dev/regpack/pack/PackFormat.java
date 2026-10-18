package dev.regpack.pack;

/**
 * Format version and hard limits of the Regime Pack (provisional until a G-S3b pass, plan P2a
 * "Provisional contracts"). A limit changes only by a deliberate edit of this class and its pinning
 * test.
 */
public final class PackFormat {
  /** PackYaml/1 and IR format 1. */
  public static final int VERSION = 1;

  public static final int MAX_FILE_BYTES = 1 << 20;
  public static final int MAX_FILES = 256;
  public static final int MAX_TOTAL_BYTES = 16 << 20;
  public static final int MAX_LINE_CHARS = 8192;
  public static final int MAX_SCALAR_CHARS = 8192;
  public static final int MAX_KEY_CHARS = 128;
  public static final int MAX_DEPTH = 32;
  public static final int MAX_NODES = 100_000;

  /**
   * Problems one file (or one CSV import) reports; further ones are replaced by one {@code
   * PROBLEMS_TRUNCATED}.
   */
  public static final int MAX_PROBLEMS_PER_FILE = 100;

  /** Problems one load reports in all; further ones are replaced by one marker. */
  public static final int MAX_PROBLEMS_PER_LOAD = 1_000;

  // Engine hard caps for pack-declared limits (only an engine build raises them).
  public static final int MAX_RULE_NODES = 4_096;
  public static final int MAX_LIST_ITEMS = 4_096;
  public static final int MAX_STRING_CODE_POINTS = 65_536;
  public static final long MAX_RECORD_BUDGET = 10_000_000L;

  private PackFormat() {}
}
