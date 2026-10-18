package dev.regpack.postgres.migrate;

/**
 * A refused or failed migration. The message names the kind, the script and its version and, for a
 * failed script, the number of the failing statement and the SQLSTATE; it never carries SQL text, a
 * server message or a value (Convention 12).
 */
public final class MigrationException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Why the migrator stopped. */
  public enum Kind {
    /** An applied script's stored checksum differs from the script in the code. */
    DRIFT,
    /** The stored versions are not 1, 2, 3 ... without a hole. */
    GAP,
    /** The database holds a version that the code does not know. */
    NEWER_DATABASE,
    /** A script, or the connection while a script was applied, failed. */
    SCRIPT_FAILED
  }

  private final Kind kind;
  private final String script;
  private final int version;

  /** A refusal without an underlying exception. */
  MigrationException(Kind kind, String script, int version) {
    super(message(kind, script, version, ""));
    this.kind = kind;
    this.script = script;
    this.version = version;
  }

  /** A failure; {@code detail} is fixed text such as {@code statement=3 sqlstate=42P01}. */
  MigrationException(Kind kind, String script, int version, String detail, Throwable cause) {
    super(message(kind, script, version, detail), cause);
    this.kind = kind;
    this.script = script;
    this.version = version;
  }

  private static String message(Kind kind, String script, int version, String detail) {
    return "migration refused: kind="
        + kind.name()
        + " script="
        + script
        + " version="
        + version
        + (detail.isEmpty() ? "" : " " + detail);
  }

  public Kind kind() {
    return kind;
  }

  /** The script file name, or {@code none} when the failure is not tied to a script. */
  public String script() {
    return script;
  }

  /** The version of the script, or 0 when the failure is not tied to a script. */
  public int version() {
    return version;
  }
}
