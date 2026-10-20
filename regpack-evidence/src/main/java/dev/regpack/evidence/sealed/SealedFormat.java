package dev.regpack.evidence.sealed;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import dev.regpack.model.json.JsonException;
import dev.regpack.ports.evidence.EvidenceKey;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The files of P-SEALED (D-E5). An object file is {@code "RPSEAL01"}, a big-endian int32 header
 * length, the canonical JSON header (UTF-8) and the payload. A meta file is {@code "RPMETA01"} and
 * the canonical JSON of the {@link Meta}. Both are strict: a header has exactly its fields and is
 * the canonical text of its own values, so there is one byte string per header and a changed byte
 * is found. The header carries the key, so a file copied under another name is found too. Errors
 * name a reason code only (Convention 8 of P3b1), never a key or a payload byte.
 */
public final class SealedFormat {
  /** The most bytes a header or a meta body may have; encode refuses what decode refuses. */
  static final int MAX_HEADER = 65_536;

  private static final byte[] OBJECT_MAGIC = "RPSEAL01".getBytes(UTF_8);
  private static final byte[] META_MAGIC = "RPMETA01".getBytes(UTF_8);
  private static final int MAGIC_LENGTH = 8;
  private static final int PREFIX_LENGTH = MAGIC_LENGTH + 4;

  private SealedFormat() {}

  /** Why a stored file is not acceptable. */
  public enum Reason {
    BAD_MAGIC,
    HEADER_TOO_LARGE,
    BAD_HEADER,
    KEY_MISMATCH,
    LENGTH_MISMATCH,
    HASH_MISMATCH,
    TRUNCATED
  }

  /** A stored file that is damaged or is not the file it should be; the message is the reason. */
  public static final class CorruptException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final Reason reason;

    CorruptException(Reason reason) {
      super(reason.name());
      this.reason = reason;
    }

    public Reason reason() {
      return reason;
    }
  }

  /** The header of an object file. */
  public record Header(
      String key,
      String mediaType,
      Sha256 sha256,
      long length,
      Instant writtenAt,
      String retentionClass,
      Instant retainUntil) {
    public Header {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(mediaType, "mediaType");
      Objects.requireNonNull(sha256, "sha256");
      Objects.requireNonNull(writtenAt, "writtenAt");
      Objects.requireNonNull(retentionClass, "retentionClass");
      Objects.requireNonNull(retainUntil, "retainUntil");
      new EvidenceKey(key); // IllegalArgumentException for anything that is not a key
      if (length < 0) {
        throw new IllegalArgumentException("length must not be negative");
      }
      requireStorable(writtenAt);
      requireStorable(retainUntil);
    }
  }

  /** The meta file of an object: the retention and the legal hold, with a revision. */
  public record Meta(long rev, String retentionClass, Instant retainUntil, boolean legalHold) {
    public Meta {
      Objects.requireNonNull(retentionClass, "retentionClass");
      Objects.requireNonNull(retainUntil, "retainUntil");
      if (rev < 0) {
        throw new IllegalArgumentException("rev must not be negative");
      }
      requireStorable(retainUntil);
    }
  }

  /** A decoded object file: the header and a private copy of the payload. */
  public record Decoded(Header header, byte[] payload) {
    public Decoded {
      Objects.requireNonNull(header, "header");
      payload = payload.clone();
    }

    @Override
    public byte[] payload() {
      return payload.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Decoded d
          && header.equals(d.header)
          && Arrays.equals(payload, d.payload);
    }

    @Override
    public int hashCode() {
      return Objects.hash(header, Arrays.hashCode(payload));
    }

    @Override
    public String toString() {
      return "Decoded[header=" + header + ", payload=" + payload.length + " bytes]";
    }
  }

  /**
   * The bytes of an object file. The header is written as given; the file is only as consistent as
   * the header (the decoder checks length and hash).
   *
   * @throws IllegalArgumentException when the header text is over 65,536 bytes
   */
  public static byte[] encodeObject(Header header, byte[] payload) {
    byte[] json = headerText(header).getBytes(UTF_8);
    if (json.length > MAX_HEADER) {
      throw new IllegalArgumentException("the header is over " + MAX_HEADER + " bytes");
    }
    ByteBuffer out = ByteBuffer.allocate(PREFIX_LENGTH + json.length + payload.length);
    out.put(OBJECT_MAGIC).putInt(json.length).put(json).put(payload);
    return out.array();
  }

  /**
   * Checks and splits an object file.
   *
   * @param expectedKey the key the file was read under
   * @throws CorruptException with the first reason found
   */
  public static Decoded decodeObject(byte[] file, String expectedKey) {
    checkMagic(file, OBJECT_MAGIC);
    if (file.length < PREFIX_LENGTH) {
      throw new CorruptException(Reason.TRUNCATED);
    }
    int headerLength = ByteBuffer.wrap(file, MAGIC_LENGTH, 4).getInt();
    if (headerLength > MAX_HEADER) {
      throw new CorruptException(Reason.HEADER_TOO_LARGE);
    }
    if (headerLength <= 0) {
      throw new CorruptException(Reason.BAD_HEADER);
    }
    if ((long) PREFIX_LENGTH + headerLength > file.length) {
      throw new CorruptException(Reason.TRUNCATED);
    }
    String text = utf8(file, PREFIX_LENGTH, headerLength);
    Header header = parseHeader(text);
    if (!header.key().equals(expectedKey)) {
      throw new CorruptException(Reason.KEY_MISMATCH);
    }
    int payloadStart = PREFIX_LENGTH + headerLength;
    long actual = (long) file.length - payloadStart;
    if (actual < header.length()) {
      throw new CorruptException(Reason.TRUNCATED);
    }
    if (actual > header.length()) {
      throw new CorruptException(Reason.LENGTH_MISMATCH);
    }
    byte[] payload = Arrays.copyOfRange(file, payloadStart, file.length);
    if (!Sha256.of(payload).equals(header.sha256())) {
      throw new CorruptException(Reason.HASH_MISMATCH);
    }
    return new Decoded(header, payload);
  }

  /** The bytes of a meta file. */
  public static byte[] encodeMeta(Meta meta) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("rev", meta.rev());
    fields.put("retention_class", meta.retentionClass());
    fields.put("retain_until", meta.retainUntil());
    fields.put("legal_hold", meta.legalHold());
    byte[] json = Json.canonical(fields).getBytes(UTF_8);
    if (json.length > MAX_HEADER) {
      throw new IllegalArgumentException("the meta text is over " + MAX_HEADER + " bytes");
    }
    ByteBuffer out = ByteBuffer.allocate(MAGIC_LENGTH + json.length);
    out.put(META_MAGIC).put(json);
    return out.array();
  }

  /**
   * Checks and reads a meta file.
   *
   * @throws CorruptException with the first reason found
   */
  public static Meta decodeMeta(byte[] file) {
    checkMagic(file, META_MAGIC);
    if (file.length - MAGIC_LENGTH > MAX_HEADER) {
      throw new CorruptException(Reason.HEADER_TOO_LARGE);
    }
    String text = utf8(file, MAGIC_LENGTH, file.length - MAGIC_LENGTH);
    Map<String, Object> fields = fieldsOf(text, 4);
    Meta meta;
    try {
      meta =
          new Meta(
              longField(fields, "rev"),
              stringField(fields, "retention_class"),
              instantField(fields, "retain_until"),
              booleanField(fields, "legal_hold"));
    } catch (IllegalArgumentException e) {
      throw new CorruptException(Reason.BAD_HEADER);
    }
    byte[] again = encodeMeta(meta);
    if (!Arrays.equals(again, file)) {
      throw new CorruptException(Reason.BAD_HEADER);
    }
    return meta;
  }

  // ---------------------------------------------------------------- helpers

  /** Magic check of a file that may be cut: a cut prefix of the magic is TRUNCATED. */
  private static void checkMagic(byte[] file, byte[] magic) {
    int common = Math.min(file.length, MAGIC_LENGTH);
    if (!Arrays.equals(file, 0, common, magic, 0, common)) {
      throw new CorruptException(Reason.BAD_MAGIC);
    }
    if (file.length < MAGIC_LENGTH) {
      throw new CorruptException(Reason.TRUNCATED);
    }
  }

  private static String headerText(Header header) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("key", header.key());
    fields.put("media_type", header.mediaType());
    fields.put("sha256", header.sha256().hex());
    fields.put("length", header.length());
    fields.put("written_at", header.writtenAt());
    fields.put("retention_class", header.retentionClass());
    fields.put("retain_until", header.retainUntil());
    return Json.canonical(fields);
  }

  private static Header parseHeader(String text) {
    Map<String, Object> fields = fieldsOf(text, 7);
    Header header;
    try {
      header =
          new Header(
              stringField(fields, "key"),
              stringField(fields, "media_type"),
              new Sha256(stringField(fields, "sha256")),
              longField(fields, "length"),
              instantField(fields, "written_at"),
              stringField(fields, "retention_class"),
              instantField(fields, "retain_until"));
    } catch (IllegalArgumentException e) {
      throw new CorruptException(Reason.BAD_HEADER);
    }
    if (!headerText(header).equals(text)) {
      throw new CorruptException(Reason.BAD_HEADER);
    }
    return header;
  }

  private static String utf8(byte[] file, int offset, int length) {
    try {
      return UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(file, offset, length))
          .toString();
    } catch (CharacterCodingException e) {
      throw new CorruptException(Reason.BAD_HEADER);
    }
  }

  private static Map<String, Object> fieldsOf(String text, int expectedFields) {
    Object parsed;
    try {
      parsed = Json.parse(text);
    } catch (JsonException e) {
      throw new CorruptException(Reason.BAD_HEADER);
    }
    if (!(parsed instanceof Map<?, ?> map) || map.size() != expectedFields) {
      throw new CorruptException(Reason.BAD_HEADER);
    }
    Map<String, Object> fields = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      fields.put((String) entry.getKey(), entry.getValue());
    }
    return fields;
  }

  private static String stringField(Map<String, Object> fields, String name) {
    if (fields.get(name) instanceof String s) {
      return s;
    }
    throw new CorruptException(Reason.BAD_HEADER);
  }

  private static long longField(Map<String, Object> fields, String name) {
    if (fields.get(name) instanceof Long n) {
      return n;
    }
    throw new CorruptException(Reason.BAD_HEADER);
  }

  private static boolean booleanField(Map<String, Object> fields, String name) {
    if (fields.get(name) instanceof Boolean b) {
      return b;
    }
    throw new CorruptException(Reason.BAD_HEADER);
  }

  private static Instant instantField(Map<String, Object> fields, String name) {
    try {
      return Instant.parse(stringField(fields, name));
    } catch (DateTimeParseException e) {
      throw new CorruptException(Reason.BAD_HEADER);
    }
  }

  private static void requireStorable(Instant instant) {
    if (instant.getNano() % 1000 != 0) {
      throw new IllegalArgumentException("an instant has at most microsecond precision");
    }
    int year = instant.atOffset(ZoneOffset.UTC).getYear();
    if (year < 0 || year > 9999) {
      throw new IllegalArgumentException("an instant must be in the years 0000 to 9999");
    }
  }
}
