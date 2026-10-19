package dev.regpack.postgres;

import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionCodec;
import dev.regpack.model.json.Json;
import dev.regpack.model.json.JsonException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The storage form of a {@link DecisionBody} (A13, D-S6, "E2"): one version byte, then the zlib
 * stream (deflate, level 6, a fixed preset dictionary of the common keys) of the UTF-8 canonical
 * JSON of {@code DecisionCodec.bodyToMap(body, true)}.
 *
 * <p>The compressed bytes are not a contract and may differ between zlib builds; what is a contract
 * is that {@link #decode} reads every value that any version-1 encoder wrote. Anything that changes
 * the canonical JSON, the dictionary or the framing needs {@link #VERSION} 2, and stored rows are
 * never rewritten. The decoder is strict: an unknown version, a truncated stream, trailing bytes
 * and text that is not a decision body are all {@link IllegalArgumentException}s.
 */
public final class DecisionBodyCodec {
  public static final int VERSION = 1;

  private static final int LEVEL = 6;

  /** A decoded body larger than this is refused before it is parsed. */
  private static final int MAX_JSON_BYTES = 16 * 1024 * 1024;

  /**
   * The preset dictionary of version 1: the keys of the canonical form in their sorted order with
   * the values that repeat most, the most common last (zlib favours the end of the dictionary).
   * Frozen: a change is a new version.
   */
  private static final byte[] DICTIONARY =
      ("{\"asAt\":\"2026-01-01T00:00:00.000000Z\",\"attributes\":{\"\":\"\"},"
              + "\"citations\":[{\"provisionPath\":\"\",\"quoteSha256\":\"\",\"role\":\"GUIDANCE\","
              + "\"sourceId\":\"\"},{\"provisionPath\":\"\",\"role\":\"INTERPRETATION\","
              + "\"sourceId\":\"\"},{\"provisionPath\":\"\",\"role\":\"EXCEPTION\","
              + "\"sourceId\":\"\"}],\"decisionContextId\":\"\","
              + "\"evaluatedAt\":\"2026-01-01T00:00:00.000000Z\",\"eventIdentity\":\"\","
              + "\"flags\":[\"circuit=open\"],\"inputDigest\":\"\","
              + "\"legs\":[\"ALLOCATION\",\"CLIENT\",\"MARKET\"],\"node\":\"\","
              + "\"outcome\":\"UNDETERMINED\",\"outcome\":\"REPORT_BY_OTHER\","
              + "\"outcome\":\"DO_NOT_REPORT\",\"outcome\":\"REPORT\","
              + "\"path\":[{\"costUnits\":10,\"matched\":false,\"ruleId\":\"\"},"
              + "{\"costUnits\":1,\"matched\":true,\"ruleId\":\"\"}],"
              + "\"reasonCode\":\"\",\"regime\":\"\",\"ruleIdsHit\":[\"\"]}")
          .getBytes(StandardCharsets.UTF_8);

  private DecisionBodyCodec() {}

  /**
   * Returns the stored form of the body.
   *
   * @throws IllegalArgumentException if the body has no canonical JSON (an instant with
   *     sub-microsecond precision, for example)
   */
  public static byte[] encode(DecisionBody body) {
    Objects.requireNonNull(body, "body");
    String json = Json.canonical(DecisionCodec.bodyToMap(body, true));
    return compress(json.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The framing: the version byte, then the dictionary-primed zlib stream of the given bytes. The
   * input is whatever {@link #encode} produced as UTF-8 canonical JSON; the method is separate so
   * that a test can frame text that is not a body.
   */
  static byte[] compress(byte[] canonicalJson) {
    Deflater deflater = new Deflater(LEVEL);
    try {
      deflater.setDictionary(DICTIONARY);
      deflater.setInput(canonicalJson);
      deflater.finish();
      ByteArrayOutputStream out = new ByteArrayOutputStream(canonicalJson.length / 2 + 16);
      out.write(VERSION);
      byte[] buffer = new byte[1024];
      while (!deflater.finished()) {
        int n = deflater.deflate(buffer);
        out.write(buffer, 0, n);
      }
      return out.toByteArray();
    } finally {
      deflater.end();
    }
  }

  /**
   * Reads a stored body.
   *
   * @throws IllegalArgumentException for an unknown version, a truncated or corrupt stream,
   *     trailing bytes, text that is not UTF-8 JSON, or JSON that is not a decision body
   */
  public static DecisionBody decode(byte[] stored) {
    Objects.requireNonNull(stored, "stored");
    if (stored.length == 0) {
      throw new IllegalArgumentException("stored decision body is empty");
    }
    if (stored[0] != VERSION) {
      throw new IllegalArgumentException(
          "unknown decision body version: " + Byte.toUnsignedInt(stored[0]));
    }
    String json = utf8(inflate(stored));
    Object parsed;
    try {
      parsed = Json.parse(json);
    } catch (JsonException e) {
      throw new IllegalArgumentException("stored decision body is not JSON", e);
    }
    return DecisionCodec.bodyFromMap(asObject(parsed));
  }

  private static byte[] inflate(byte[] stored) {
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(stored, 1, stored.length - 1);
      ByteArrayOutputStream out = new ByteArrayOutputStream(stored.length * 3);
      byte[] buffer = new byte[4096];
      boolean primed = false;
      while (!inflater.finished()) {
        int n = inflater.inflate(buffer);
        if (n > 0) {
          out.write(buffer, 0, n);
          if (out.size() > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("stored decision body inflates beyond the limit");
          }
        } else if (inflater.needsDictionary()) {
          inflater.setDictionary(DICTIONARY);
          primed = true;
        } else if (inflater.needsInput()) {
          throw new IllegalArgumentException("stored decision body is truncated");
        } else {
          throw new IllegalArgumentException("stored decision body cannot be inflated");
        }
      }
      if (!primed) {
        throw new IllegalArgumentException("stored decision body does not use the dictionary");
      }
      if (inflater.getRemaining() > 0) {
        throw new IllegalArgumentException("stored decision body has trailing bytes");
      }
      return out.toByteArray();
    } catch (DataFormatException e) {
      throw new IllegalArgumentException("stored decision body is corrupt", e);
    } finally {
      inflater.end();
    }
  }

  private static String utf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException("stored decision body is not UTF-8", e);
    }
  }

  private static Map<String, Object> asObject(Object parsed) {
    if (!(parsed instanceof Map<?, ?> raw)) {
      throw new IllegalArgumentException("stored decision body is not a JSON object");
    }
    Map<String, Object> map = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : raw.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException("stored decision body has a key that is not a string");
      }
      map.put(key, entry.getValue());
    }
    return map;
  }
}
