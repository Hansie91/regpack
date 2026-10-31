package dev.regpack.ports.crypto;

import dev.regpack.ports.security.KeyPurpose;
import dev.regpack.ports.security.KeyRef;
import dev.regpack.ports.security.Kms;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Hashes data-subject identifiers for the index (D-K5): HMAC-SHA-256 under a KMS key of purpose
 * {@code INDEX_HMAC}, over {@code regpack/subject/v1} NUL kind NUL NFC(value), in UTF-8. The index
 * holds keyed hashes and references only, so a holder of the rows cannot recover an identifier
 * without the key.
 *
 * <p>The caller canonicalises the identifier before {@link #hash} (trim, upper-case with {@code
 * Locale.ROOT}, strip separators); this class only applies Unicode NFC, so that composed and
 * decomposed spellings of one text hash alike. A value that is not valid text (an unpaired
 * surrogate) is refused and not replaced, so two different values cannot collide on a replacement
 * character.
 *
 * <p>The value never reaches a message, a {@code toString} or an exception. A failure of the KMS is
 * a {@link KeyUnavailableException} (fixed message, no cause). The bytes that were handed to the
 * KMS are zeroed afterwards (best effort).
 *
 * <p>Thread-safe when the {@link Kms} is.
 */
public final class SubjectHasher {
  private static final Pattern KIND = Pattern.compile("[A-Z_]{1,32}");
  private static final byte[] DOMAIN = "regpack/subject/v1".getBytes(StandardCharsets.UTF_8);
  private static final int MAC_BYTES = 32;

  private final Kms kms;
  private final KeyRef indexKey;

  /**
   * @throws IllegalArgumentException when the key is not an {@code INDEX_HMAC} key
   */
  public SubjectHasher(Kms kms, KeyRef indexKey) {
    this.kms = Objects.requireNonNull(kms, "kms");
    this.indexKey = Objects.requireNonNull(indexKey, "indexKey");
    if (indexKey.purpose() != KeyPurpose.INDEX_HMAC) {
      throw new IllegalArgumentException("the subject hash needs an INDEX_HMAC key");
    }
  }

  /**
   * The keyed hash of {@code value} of the given {@code kind}.
   *
   * @param kind 1 to 32 characters of {@code [A-Z_]}, for example {@code NATIONAL_ID}
   * @throws IllegalArgumentException when the kind is malformed or the value is not valid text
   * @throws KeyUnavailableException when the KMS cannot serve the request
   */
  public SubjectHash hash(String kind, String value) {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(value, "value");
    if (!KIND.matcher(kind).matches()) {
      throw new IllegalArgumentException("invalid subject kind");
    }
    byte[] message = message(kind, Normalizer.normalize(value, Normalizer.Form.NFC));
    byte[] mac;
    try {
      mac = kms.hmac(indexKey, message);
    } catch (RuntimeException e) {
      throw new KeyUnavailableException();
    } finally {
      Arrays.fill(message, (byte) 0);
    }
    if (mac == null || mac.length != MAC_BYTES) {
      throw new KeyUnavailableException();
    }
    return new SubjectHash(HexFormat.of().formatHex(mac));
  }

  private static byte[] message(String kind, String normalised) {
    ByteBuffer encoded;
    try {
      encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(normalised));
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException("the value is not valid text");
    }
    byte[] kindBytes = kind.getBytes(StandardCharsets.US_ASCII);
    byte[] message = new byte[DOMAIN.length + 1 + kindBytes.length + 1 + encoded.remaining()];
    ByteBuffer out = ByteBuffer.wrap(message);
    out.put(DOMAIN).put((byte) 0).put(kindBytes).put((byte) 0).put(encoded);
    if (encoded.hasArray()) {
      Arrays.fill(encoded.array(), (byte) 0);
    }
    return message;
  }
}
