package dev.regpack.ports.evidence.journal;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import dev.regpack.model.json.JsonException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One journal object: an envelope and a payload (spec 11.2, D-E3). The bytes are the canonical JSON
 * {@code {"envelope": ..., "payload": ...}} in UTF-8. The envelope carries {@code endpoint} (the
 * scope text), {@code restore_epoch}, {@code type} (the wire name), {@code schema_version}, {@code
 * clock_instant} (the ledger event time, so that a re-ship is byte-identical) and {@code
 * payload_sha256}, plus {@code seq} (class b) or {@code subject_id} and {@code ordinal} (class a).
 *
 * <p>This constructor checks the key against the type (the type must allow the class of the key and
 * a class (a) key names its own type), the schema version (at least 1, and 1 for the schemas), the
 * clock instant (microsecond precision, year 0000 to 9999) and the shape of the payload (text,
 * boolean, integer, decimal, instant, list and text-keyed map only; integers widen to {@link
 * Long}). The shape check also refuses what {@link #toBytes()} could never write: text (and map
 * keys) with an unpaired surrogate, and an instant of more than microsecond precision or outside
 * the years 0000 to 9999, so no text or instant of a constructed object makes {@link #toBytes()}
 * fail. The size is the exception: the constructor does not bound it, and the lists of the schemas
 * have no count bound, so a list of very many elements (about a million hashes) can build an object
 * whose canonical bytes are over {@link #MAX_BYTES}; {@link #toBytes()} then throws {@link
 * IllegalArgumentException}. A producer that must know before it takes an effect calls {@code
 * toBytes()} first ({@code DurableSeal} does, in its validation). The payload schemas of the types
 * are checked in addition by {@link JournalSchemas}, so an object that breaks its schema cannot be
 * built. The payload is copied deeply and is immutable. Messages never echo a value.
 *
 * <p>The canonical bytes of an object are at most {@link #MAX_BYTES}: {@link #toBytes()} refuses
 * more and {@link #fromBytes} refuses longer input before it parses anything.
 *
 * <p>The payload read back by {@link #fromBytes} is the JSON data model: text, boolean, {@link
 * Long}, {@link BigDecimal}, list and map. A payload that was built with an {@link Instant} reads
 * back as text, except in the two instant-typed fields of the schemas ({@code observed_at} of a
 * {@code TransportResult}, {@code received_at} of a {@code FeedbackReceived}), which are converted
 * back by type; an integral {@link BigDecimal} reads back as a {@link Long}. The schemas narrow
 * this: no payload field accepts a {@link BigDecimal}, and those two are the only instants, so an
 * object that passes its schema equals what {@code fromBytes(toBytes())} returns.
 */
public record JournalObject(
    JournalKey key,
    JournalType type,
    int schemaVersion,
    Instant clockInstant,
    Map<String, Object> payload) {
  /**
   * The most bytes a journal object may have: 64 MiB. A {@code VersionAssigned} object of a
   * 10,000-record batch is about 1 MB, so the bound is far above any real object and far below the
   * size at which reading one would exhaust the memory of the reader.
   */
  public static final int MAX_BYTES = 67_108_864;

  private static final Instant FIRST_YEAR_0000 = Instant.parse("0000-01-01T00:00:00Z");
  private static final Instant LAST_YEAR_9999 = Instant.parse("9999-12-31T23:59:59.999999999Z");
  private static final Set<String> COMMON_ENVELOPE_KEYS =
      Set.of(
          "endpoint", "restore_epoch", "type", "schema_version", "clock_instant", "payload_sha256");
  private static final Set<String> CLASS_A_ENVELOPE_KEYS = withCommon("subject_id", "ordinal");
  private static final Set<String> CLASS_B_ENVELOPE_KEYS = withCommon("seq");

  public JournalObject {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(clockInstant, "clockInstant");
    Objects.requireNonNull(payload, "payload");
    if (!type.allows(key.journalClass())) {
      throw new IllegalArgumentException(
          type.wireName() + " is not a class " + key.journalClass() + " journal type");
    }
    if (key instanceof JournalKey.ClassA a && a.type() != type) {
      throw new IllegalArgumentException(
          "the type of a class a key must be the type of the object");
    }
    if (schemaVersion < 1) {
      throw new IllegalArgumentException("schemaVersion must be at least 1");
    }
    if (clockInstant.getNano() % 1000 != 0) {
      throw new IllegalArgumentException("clockInstant has sub-microsecond precision");
    }
    if (outsideYears0000To9999(clockInstant)) {
      throw new IllegalArgumentException("clockInstant year is outside 0000 to 9999");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> copy = (Map<String, Object>) copyValue(payload);
    payload = copy;
    JournalSchemas.validate(type, schemaVersion, key.journalClass(), key.scope(), payload);
  }

  /** The hash of the canonical payload text (UTF-8): the {@code payload_sha256} of the envelope. */
  public Sha256 payloadSha256() {
    return Sha256.ofUtf8(Json.canonical(payload));
  }

  /** The canonical bytes: sorted keys, no whitespace, instants with microseconds. */
  public byte[] toBytes() {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("endpoint", key.scope().value());
    envelope.put("restore_epoch", key.restoreEpoch());
    envelope.put("type", type.wireName());
    envelope.put("schema_version", (long) schemaVersion);
    envelope.put("clock_instant", clockInstant);
    envelope.put("payload_sha256", payloadSha256().hex());
    switch (key) {
      case JournalKey.ClassA a -> {
        envelope.put("subject_id", a.subjectId());
        envelope.put("ordinal", a.ordinal());
      }
      case JournalKey.ClassB b -> envelope.put("seq", b.seq());
    }
    Map<String, Object> object = new LinkedHashMap<>();
    object.put("envelope", envelope);
    object.put("payload", payload);
    byte[] bytes = Json.canonical(object).getBytes(UTF_8);
    if (bytes.length > MAX_BYTES) {
      throw new IllegalArgumentException(
          "journal object: the canonical bytes are over " + MAX_BYTES + " bytes");
    }
    return bytes;
  }

  /**
   * Reads canonical bytes. Only canonical text is accepted (the object is re-rendered and compared
   * byte for byte, so trailing bytes, whitespace, another key order and non-canonical numbers or
   * instants are refused), the envelope must have exactly the keys of its class, and {@code
   * payload_sha256} must be the hash of the canonical payload. A malformed text is an {@link
   * IllegalArgumentException} whose message never echoes the input and which has no cause.
   */
  public static JournalObject fromBytes(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes");
    if (bytes.length > MAX_BYTES) {
      throw malformed("the input is over " + MAX_BYTES + " bytes"); // before anything is parsed
    }
    try {
      String text =
          UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      Map<String, Object> top = asMap(Json.parse(text));
      if (!top.keySet().equals(Set.of("envelope", "payload"))) {
        throw malformed("the object needs exactly envelope and payload");
      }
      Map<String, Object> envelope = asMap(top.get("envelope"));
      Map<String, Object> payload = asMap(top.get("payload"));
      JournalScope scope = new JournalScope(string(envelope, "endpoint"));
      long epoch = number(envelope, "restore_epoch");
      JournalType type = JournalType.ofWire(string(envelope, "type"));
      payload = withInstants(type, payload);
      long version = number(envelope, "schema_version");
      if (version < 1 || version > Integer.MAX_VALUE) {
        throw malformed("schema_version is out of range");
      }
      Instant clock = Instant.parse(string(envelope, "clock_instant"));
      JournalKey key;
      if (envelope.keySet().equals(CLASS_A_ENVELOPE_KEYS)) {
        key =
            new JournalKey.ClassA(
                scope, epoch, type, string(envelope, "subject_id"), number(envelope, "ordinal"));
      } else if (envelope.keySet().equals(CLASS_B_ENVELOPE_KEYS)) {
        key = new JournalKey.ClassB(scope, epoch, number(envelope, "seq"));
      } else {
        throw malformed("the envelope keys are not those of class a or class b");
      }
      JournalObject object = new JournalObject(key, type, (int) version, clock, payload);
      if (!object.payloadSha256().hex().equals(string(envelope, "payload_sha256"))) {
        throw malformed("payload_sha256 is not the hash of the payload");
      }
      if (!Arrays.equals(object.toBytes(), bytes)) {
        throw malformed("the text is not canonical");
      }
      return object;
    } catch (CharacterCodingException | JsonException | DateTimeParseException e) {
      // the cause is dropped on purpose: parser messages can repeat parts of the input
      throw malformed("the text cannot be read");
    }
  }

  /** Compared as instants: Instant.MIN and Instant.MAX have no calendar year to read. */
  private static boolean outsideYears0000To9999(Instant instant) {
    return instant.isBefore(FIRST_YEAR_0000) || instant.isAfter(LAST_YEAR_9999);
  }

  private static void requireWritableText(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)
          && i + 1 < text.length()
          && Character.isLowSurrogate(text.charAt(i + 1))) {
        i++;
      } else if (Character.isSurrogate(c)) {
        throw new IllegalArgumentException("payload text must not have an unpaired surrogate");
      }
    }
  }

  /**
   * The parser yields text for an instant: the instant-typed payload fields of the type are
   * converted back (the canonical comparison at the end of {@code fromBytes} refuses any rendering
   * but the canonical one).
   */
  private static Map<String, Object> withInstants(JournalType type, Map<String, Object> payload) {
    String field =
        switch (type) {
          case TRANSPORT_RESULT -> "observed_at";
          case FEEDBACK_RECEIVED -> "received_at";
          default -> "";
        };
    if (field.isEmpty() || !(payload.get(field) instanceof String text)) {
      return payload;
    }
    Map<String, Object> converted = new LinkedHashMap<>(payload);
    converted.put(field, Instant.parse(text));
    return converted;
  }

  private static Set<String> withCommon(String... more) {
    List<String> all = new ArrayList<>(COMMON_ENVELOPE_KEYS);
    all.addAll(List.of(more));
    return Set.copyOf(all);
  }

  private static IllegalArgumentException malformed(String reason) {
    return new IllegalArgumentException("malformed journal object: " + reason);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    if (value instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    throw malformed("an object was expected");
  }

  private static String string(Map<String, Object> map, String name) {
    if (map.get(name) instanceof String s) {
      return s;
    }
    throw malformed(name + " must be text");
  }

  private static long number(Map<String, Object> map, String name) {
    if (map.get(name) instanceof Long n) {
      return n;
    }
    throw malformed(name + " must be an integer");
  }

  /**
   * A deep, immutable copy that keeps only the value types of canonical JSON, and only values that
   * {@link #toBytes()} can write: text without an unpaired surrogate (map keys too) and instants of
   * at most microsecond precision in the years 0000 to 9999.
   */
  private static Object copyValue(Object value) {
    return switch (value) {
      case null -> throw new IllegalArgumentException("a payload value must not be null");
      case String s -> {
        requireWritableText(s);
        yield s;
      }
      case Boolean b -> b;
      case Long n -> n;
      case Integer n -> Long.valueOf(n.longValue());
      case Short n -> Long.valueOf(n.longValue());
      case Byte n -> Long.valueOf(n.longValue());
      case BigDecimal d -> d;
      case Instant t -> {
        if (t.getNano() % 1000 != 0) {
          throw new IllegalArgumentException(
              "a payload instant must have at most microsecond precision");
        }
        if (outsideYears0000To9999(t)) {
          throw new IllegalArgumentException("a payload instant must be in the years 0000 to 9999");
        }
        yield t;
      }
      case Map<?, ?> map -> {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          if (!(entry.getKey() instanceof String name)) {
            throw new IllegalArgumentException("payload object keys must be text");
          }
          requireWritableText(name);
          copy.put(name, copyValue(entry.getValue()));
        }
        yield Collections.unmodifiableMap(copy);
      }
      case List<?> list -> {
        List<Object> copy = new ArrayList<>(list.size());
        for (Object element : list) {
          copy.add(copyValue(element));
        }
        yield Collections.unmodifiableList(copy);
      }
      default ->
          throw new IllegalArgumentException(
              "a payload value of type " + value.getClass().getName() + " is not representable");
    };
  }
}
