package dev.regpack.flow.ingest;

import dev.regpack.model.json.Json;
import dev.regpack.ports.evidence.EvidenceKey;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A data-quality item (plan D-W3): a bad file, batch or record that must not vanish, with the place
 * where it was found and the reason, and never a value of the data (SF8). Every field is checked
 * against a pattern of characters and a length, and a message never echoes the field it refuses.
 * The patterns bound the characters and the length only: they are not closed vocabularies, and an
 * identifier, an account key or a record id fits them as well as a code does. What keeps a value
 * out of an item is the caller: every caller must pass fixed codes, column names taken from the
 * mapping and rule notes of fixed text, and never a text read from the data.
 *
 * <p>An item is stored as canonical JSON under {@code dq/<scope>/<kind>-<line>-<rule or code>}: the
 * rule id for a {@link Kind#VALIDATION_BLOCKER}, the code for every other kind. There is no
 * ordinal, counter or time in the key or the bytes, so the same item written twice is the same key
 * and the same bytes, and a replay writes nothing new.
 *
 * @param kind what the item reports
 * @param scope the batch digest in hex for ingest and decide items, the SHA-256 in hex of the
 *     answer bytes for feedback items, the SHA-256 in hex of the file name for waiting and
 *     redelivered-file items, {@code d} and the delivery id in ten digits for delivery items
 * @param line the 1-based line in the file, 0 where the item is not about a line
 * @param column the column name of the mapping (the number of the cell for a CSV syntax error),
 *     empty where there is none
 * @param code the reason as a fixed code
 * @param rule the rule id, or a fixed note or a count line ({@code a=1;b=2}); never a value
 * @param layer the stage or validation layer that found it, empty where there is none
 * @param severity a fixed word such as BLOCKER, WARNING or INFO
 */
public record Dq(
    Kind kind,
    String scope,
    int line,
    String column,
    String code,
    String rule,
    String layer,
    String severity) {

  /** The kinds of item (plan D-W3). */
  public enum Kind {
    BATCH_REJECTED,
    BATCH_QUARANTINED,
    BATCH_SUMMARY,
    COMPLETION_PENDING,
    RECORD_QUARANTINED,
    FILE_REDELIVERED,
    STALE_VERSION,
    SOURCE_CONFLICT,
    VERSION_CONFLICT,
    UNDETERMINED_DECISION,
    VALIDATION_BLOCKER,
    WITHDRAWAL_REQUIRED,
    L6_FAILED,
    UNMATCHED_FEEDBACK,
    FEEDBACK_ANOMALY,
    FEEDBACK_CONFLICT,
    SEND_ANOMALY,
    DECIDE_FAILED,
    VALIDATOR_DEFECT
  }

  /** The note of a batch that cannot be released with the same bytes. */
  public static final String RELEASE_NEEDS_NEW_BYTES = "RELEASE_NEEDS_NEW_BYTES";

  /** The media type of a stored item. */
  public static final String MEDIA_TYPE = "application/json";

  /** The retention class name of a stored item. */
  public static final String RETENTION_CLASS = "dq";

  private static final Pattern SCOPE = Pattern.compile("[0-9a-f]{64}|d[0-9]{10}");
  private static final Pattern COLUMN = Pattern.compile("[A-Za-z0-9_.-]{0,64}");
  private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
  private static final Pattern RULE = Pattern.compile("[A-Za-z0-9_.=;-]{0,128}");
  private static final Pattern RULE_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
  private static final Pattern WORD = Pattern.compile("[A-Z0-9_]{0,16}");
  private static final Pattern SEVERITY = Pattern.compile("[A-Z]{1,16}");

  public Dq {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(column, "column");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(rule, "rule");
    Objects.requireNonNull(layer, "layer");
    Objects.requireNonNull(severity, "severity");
    require(SCOPE.matcher(scope).matches(), "scope");
    if (line < 0) {
      throw new IllegalArgumentException("a DQ item line must not be negative");
    }
    require(COLUMN.matcher(column).matches(), "column");
    require(CODE.matcher(code).matches(), "code");
    require(RULE.matcher(rule).matches(), "rule");
    require(WORD.matcher(layer).matches(), "layer");
    require(SEVERITY.matcher(severity).matches(), "severity");
    if (kind == Kind.VALIDATION_BLOCKER) {
      require(RULE_ID.matcher(rule).matches(), "rule id of a blocker");
    }
    key(kind, scope, line, code, rule); // fails here and not at the first write
  }

  private static void require(boolean ok, String field) {
    if (!ok) {
      throw new IllegalArgumentException("a DQ item " + field + " is outside its alphabet");
    }
  }

  private static EvidenceKey key(Kind kind, String scope, int line, String code, String rule) {
    String name = kind.name() + "-" + line + "-" + (kind == Kind.VALIDATION_BLOCKER ? rule : code);
    return EvidenceKey.of("dq", scope, name);
  }

  /** The evidence key of the item. */
  public EvidenceKey key() {
    return key(kind, scope, line, code, rule);
  }

  /** The canonical JSON of the item, UTF-8. */
  public byte[] toBytes() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("kind", kind.name());
    fields.put("scope", scope);
    fields.put("line", (long) line);
    fields.put("column", column);
    fields.put("code", code);
    fields.put("rule", rule);
    fields.put("layer", layer);
    fields.put("severity", severity);
    return Json.canonical(fields).getBytes(StandardCharsets.UTF_8);
  }
}
