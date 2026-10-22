package dev.regpack.ports.evidence.journal;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The payload schemas of the journal types (payload version 1, spec 11.2). A version 1 payload is
 * strict: exactly the required keys, no unknown key; integers are {@link Long} and not negative,
 * hashes are 64 lower-case hex characters, instants are {@link Instant} with at most microsecond
 * precision. Every text is bounded, in UTF-16 code units: {@code detail} and {@code reason} at
 * 4,096 and every other text (a list element too) at 1,024; the number of elements of a list is not
 * bounded here, only the size of the whole object is ({@link JournalType#maxBytes()}). Two rules go
 * beyond the shape: a {@code RecoveryDecision} says it authorises an effect exactly when it is
 * class (a), and the kind of an {@code OperatorAction} fixes its class and scope. Messages name the
 * schema field and the rule and never echo a value or an unknown key.
 */
public final class JournalSchemas {
  /** The only payload version there is. */
  public static final int V1 = 1;

  private enum Field {
    TEXT,
    COUNT,
    HASH,
    HASH_LIST,
    TEXT_LIST,
    INSTANT,
    FLAG,
    ASSIGNMENT_LIST
  }

  /** The kinds of {@code OperatorAction}; each fixes the class and whether the scope is control. */
  private enum Kind {
    APPROVAL(JournalClass.B, Scopes.EITHER),
    WRITE_OFF(JournalClass.A, Scopes.ENDPOINT),
    FORCED_RESEND(JournalClass.A, Scopes.ENDPOINT),
    ROUTE_CHANGE(JournalClass.A, Scopes.ENDPOINT),
    PACK_ACTIVATION(JournalClass.A, Scopes.CONTROL),
    PACK_ROLLBACK(JournalClass.A, Scopes.CONTROL),
    PACK_EXPIRY(JournalClass.A, Scopes.CONTROL);

    private final JournalClass journalClass;
    private final Scopes scopes;

    Kind(JournalClass journalClass, Scopes scopes) {
      this.journalClass = journalClass;
      this.scopes = scopes;
    }
  }

  private enum Scopes {
    ENDPOINT,
    CONTROL,
    EITHER
  }

  /** The longest {@code detail} or {@code reason}. */
  static final int MAX_LONG_TEXT = 4_096;

  /** The longest of every other text, and of each text in a list. */
  static final int MAX_TEXT = 1_024;

  private static final Instant FIRST_YEAR_0000 = Instant.parse("0000-01-01T00:00:00Z");
  private static final Instant LAST_YEAR_9999 = Instant.parse("9999-12-31T23:59:59.999999999Z");
  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
  private static final Map<JournalType, Map<String, Field>> FIELDS =
      new EnumMap<>(JournalType.class);
  private static final Map<String, Field> ASSIGNMENT =
      fields("subject", Field.TEXT, "trn", Field.TEXT, "version_seq", Field.COUNT);

  static {
    FIELDS.put(
        JournalType.DELIVERY_INTENT,
        fields(
            "attempt_id", Field.COUNT,
            "delivery_id", Field.COUNT,
            "business_key", Field.TEXT,
            "trn", Field.TEXT,
            "version_seq", Field.COUNT,
            "action", Field.TEXT,
            "logical_hash", Field.HASH,
            "artifact_sha256", Field.HASH,
            "file_name", Field.TEXT,
            "context_ids", Field.HASH_LIST));
    FIELDS.put(
        JournalType.BATCH_ADMITTED,
        fields(
            "batch_id", Field.TEXT, "pack_digest", Field.HASH, "context_digests", Field.HASH_LIST));
    FIELDS.put(
        JournalType.TRANSPORT_RESULT,
        fields(
            "attempt_id", Field.COUNT,
            "status", Field.TEXT,
            "detail", Field.TEXT,
            "observed_at", Field.INSTANT));
    FIELDS.put(
        JournalType.FEEDBACK_RECEIVED,
        fields(
            "feedback_sha256", Field.HASH,
            "received_at", Field.INSTANT,
            "file_ids", Field.TEXT_LIST));
    FIELDS.put(
        JournalType.RECOVERY_DECISION,
        fields(
            "delivery_id", Field.COUNT,
            "attempt_id", Field.COUNT,
            "intent_restore_epoch", Field.COUNT,
            "artifact_sha256", Field.HASH,
            "decision", Field.TEXT,
            "authorises_effect", Field.FLAG,
            "reason", Field.TEXT));
    FIELDS.put(
        JournalType.OPERATOR_ACTION,
        fields(
            "kind", Field.TEXT,
            "operator", Field.TEXT,
            "subject", Field.TEXT,
            "detail", Field.TEXT));
    FIELDS.put(
        JournalType.VERSION_ASSIGNED,
        fields("batch_id", Field.TEXT, "assignments", Field.ASSIGNMENT_LIST));
  }

  private JournalSchemas() {}

  private static Map<String, Field> fields(Object... namesAndTypes) {
    Map<String, Field> map = new LinkedHashMap<>();
    for (int i = 0; i < namesAndTypes.length; i += 2) {
      map.put((String) namesAndTypes[i], (Field) namesAndTypes[i + 1]);
    }
    return Map.copyOf(map);
  }

  /**
   * Checks a payload against the schema of its type, class and scope.
   *
   * @throws IllegalArgumentException the schema version is not 1, the type does not allow the
   *     class, a key is missing, unknown or mistyped, or a rule of the type is broken
   */
  public static void validate(
      JournalType type,
      int schemaVersion,
      JournalClass cls,
      JournalScope scope,
      Map<String, Object> payload) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(cls, "cls");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(payload, "payload");
    if (schemaVersion != V1) {
      throw refused(type, "the payload schema version is not " + V1);
    }
    if (!type.allows(cls)) {
      throw refused(type, "the type is not a class " + cls + " type");
    }
    checkFields(type, FIELDS.get(type), payload);
    switch (type) {
      case RECOVERY_DECISION -> {
        boolean flag = (Boolean) payload.get("authorises_effect");
        if (flag != (cls == JournalClass.A)) {
          throw refused(type, "authorises_effect must be true exactly when the object is class a");
        }
      }
      case OPERATOR_ACTION -> checkKind(type, cls, scope, (String) payload.get("kind"));
      default -> {}
    }
  }

  private static void checkKind(
      JournalType type, JournalClass cls, JournalScope scope, String kindText) {
    Kind kind = null;
    for (Kind candidate : Kind.values()) {
      if (candidate.name().equals(kindText)) {
        kind = candidate;
      }
    }
    if (kind == null) {
      throw refused(type, "kind is not one of the known kinds");
    }
    if (kind.journalClass != cls) {
      throw refused(type, "the kind " + kind.name() + " is class " + kind.journalClass);
    }
    boolean scopeOk =
        switch (kind.scopes) {
          case ENDPOINT -> !scope.isControl();
          case CONTROL -> scope.isControl();
          case EITHER -> true;
        };
    if (!scopeOk) {
      throw refused(
          type,
          "the kind "
              + kind.name()
              + " has "
              + (kind.scopes == Scopes.CONTROL ? "the control scope" : "an endpoint scope"));
    }
  }

  private static void checkFields(
      JournalType type, Map<String, Field> schema, Map<String, Object> values) {
    for (Map.Entry<String, Field> entry : schema.entrySet()) {
      String name = entry.getKey();
      if (!values.containsKey(name)) {
        throw refused(type, "payload field " + name + " is missing");
      }
      checkValue(type, name, entry.getValue(), values.get(name));
    }
    if (!schema.keySet().containsAll(values.keySet())) {
      throw refused(type, "the payload has a key that the schema does not know");
    }
  }

  private static void checkValue(JournalType type, String name, Field field, Object value) {
    boolean ok =
        switch (field) {
          case TEXT -> value instanceof String s && s.length() <= maxText(name);
          case COUNT -> value instanceof Long n && n >= 0;
          case HASH -> isHash(value);
          case HASH_LIST ->
              value instanceof List<?> list && list.stream().allMatch(JournalSchemas::isHash);
          case TEXT_LIST ->
              value instanceof List<?> list
                  && list.stream().allMatch(e -> e instanceof String s && s.length() <= MAX_TEXT);
          case INSTANT -> value instanceof Instant t && isMicrosecondInstant(t);
          case FLAG -> value instanceof Boolean;
          case ASSIGNMENT_LIST -> value instanceof List<?> list && assignmentsOk(type, list);
        };
    if (!ok) {
      throw refused(type, "payload field " + name + " must be " + describe(field, name));
    }
  }

  private static boolean assignmentsOk(JournalType type, List<?> list) {
    for (Object element : list) {
      if (!(element instanceof Map<?, ?> map)) {
        return false;
      }
      Map<String, Object> typed = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          return false;
        }
        typed.put(key, entry.getValue());
      }
      try {
        checkFields(type, ASSIGNMENT, typed);
      } catch (IllegalArgumentException e) {
        return false;
      }
    }
    return true;
  }

  private static boolean isHash(Object value) {
    return value instanceof String s && HASH.matcher(s).matches();
  }

  private static boolean isMicrosecondInstant(Instant t) {
    // compared as instants: Instant.MIN and Instant.MAX have no calendar year to read
    return t.getNano() % 1000 == 0 && !t.isBefore(FIRST_YEAR_0000) && !t.isAfter(LAST_YEAR_9999);
  }

  private static int maxText(String name) {
    return name.equals("detail") || name.equals("reason") ? MAX_LONG_TEXT : MAX_TEXT;
  }

  private static String describe(Field field, String name) {
    return switch (field) {
      case TEXT -> "text of at most " + maxText(name) + " characters";
      case COUNT -> "a non-negative integer";
      case HASH -> "64 lower-case hex characters";
      case HASH_LIST -> "a list of hashes (64 lower-case hex characters each)";
      case TEXT_LIST -> "a list of text of at most " + MAX_TEXT + " characters each";
      case INSTANT -> "an instant of at most microsecond precision";
      case FLAG -> "true or false";
      case ASSIGNMENT_LIST ->
          "a list of objects with exactly subject (text of at most "
              + MAX_TEXT
              + " characters), trn (text of at most "
              + MAX_TEXT
              + " characters) and version_seq (integer)";
    };
  }

  private static IllegalArgumentException refused(JournalType type, String reason) {
    return new IllegalArgumentException(type.wireName() + ": " + reason);
  }
}
