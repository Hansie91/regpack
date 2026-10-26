package dev.regpack.ports.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * The envelope format, version 1 (D-K1; all lengths big-endian): the bytes {@code RPEN}, the
 * version {@code 01}, the kind ({@code 01} object, {@code 02} body), the KEK id length (1 to 100),
 * the KEK id ({@code [A-Za-z0-9._-]}), the wrapped key length (2 bytes, 1 to 1,024), the wrapped
 * data key, a 12-byte nonce, and the ciphertext with its 16-byte tag.
 *
 * <p>The authenticated data is every byte before the nonce, then a 4-byte length and the context,
 * so an envelope whose KEK id or wrapped key was swapped, or that was moved to another context,
 * fails the tag. The wrapped key travels inside the envelope: the tier is readable with the KEKs
 * alone.
 *
 * <p>Nothing here holds a key, a KEK id or a context in a message or in {@code toString}.
 * Production code seals through {@code ObjectCipher} or {@code BodyCipher}, which draw the data key
 * and the nonce from an entropy source; {@link #seal} takes them as arguments for known-answer
 * tests and fixtures.
 */
public final class Envelope {
  /** What the envelope holds: one evidence object, or one report body. */
  public enum Kind {
    OBJECT(1),
    BODY(2);

    private final int code;

    Kind(int code) {
      this.code = code;
    }

    int code() {
      return code;
    }
  }

  /**
   * The readable part of an envelope: the kind, the id of the KEK that wrapped the data key, and
   * the wrapped data key. {@code equals} compares the wrapped key by content; {@code toString}
   * shows the kind and the length only.
   */
  public record Header(Kind kind, String kekId, byte[] wrappedKey) {
    public Header {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(kekId, "kekId");
      Objects.requireNonNull(wrappedKey, "wrappedKey");
      if (!isKekId(kekId) || wrappedKey.length < 1 || wrappedKey.length > MAX_WRAPPED_BYTES) {
        throw new IllegalArgumentException("invalid envelope header");
      }
      wrappedKey = wrappedKey.clone();
    }

    @Override
    public byte[] wrappedKey() {
      return wrappedKey.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Header that
          && kind == that.kind
          && kekId.equals(that.kekId)
          && MessageDigest.isEqual(wrappedKey, that.wrappedKey);
    }

    @Override
    public int hashCode() {
      return Objects.hash(kind, kekId, Arrays.hashCode(wrappedKey));
    }

    @Override
    public String toString() {
      return "Header[kind=" + kind + ", wrappedLength=" + wrappedKey.length + "]";
    }
  }

  /**
   * The tag did not verify: a wrong key, a wrong context, or changed bytes. The message is fixed.
   */
  public static final class AuthenticationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AuthenticationException() {
      super("the envelope could not be authenticated", null, false, true);
    }
  }

  private static final byte[] MAGIC = {'R', 'P', 'E', 'N'};
  private static final int VERSION = 1;
  private static final int MAX_KEK_ID_CHARS = 100;
  private static final int MAX_WRAPPED_BYTES = 1024;
  private static final int KEK_ID_OFFSET = MAGIC.length + 1 + 1 + 1; // magic, version, kind, length
  private static final int WRAPPED_LENGTH_BYTES = 2;
  private static final String NOT_AN_ENVELOPE = "not an envelope";

  private Envelope() {}

  /**
   * True exactly when {@link #header} would succeed on {@code b}: the magic, version 1, kind 1 or
   * 2, a KEK id of 1 to 100 characters of the allowed alphabet, a wrapped key of 1 to 1,024 bytes,
   * and a total length of at least the header, a nonce and a tag. It needs no key and does not
   * authenticate: a well-formed header over random bytes passes.
   */
  public static boolean isEnvelope(byte[] b) {
    Objects.requireNonNull(b, "b");
    return parse(b).isPresent();
  }

  /**
   * Reads the header of an envelope without a key.
   *
   * @throws IllegalArgumentException with the message {@code not an envelope} for any malformed
   *     header
   */
  public static Header header(byte[] envelope) {
    Objects.requireNonNull(envelope, "envelope");
    return parse(envelope).orElseThrow(Envelope::notAnEnvelope).header();
  }

  /**
   * Seals {@code plaintext} under {@code dataKey} (32 bytes) with {@code nonce} (12 bytes), binding
   * the header and {@code context} into the authenticated data. For known-answer tests and fixtures
   * only: the caller must never reuse a key and nonce pair.
   *
   * @throws IllegalArgumentException when the key or the nonce has the wrong size
   */
  public static byte[] seal(
      Header h, byte[] dataKey, byte[] nonce, byte[] context, byte[] plaintext) {
    Objects.requireNonNull(h, "h");
    Objects.requireNonNull(dataKey, "dataKey");
    Objects.requireNonNull(nonce, "nonce");
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(plaintext, "plaintext");
    byte[] header = encodeHeader(h);
    byte[] sealed = AesGcm.encrypt(dataKey, nonce, aad(header, header.length, context), plaintext);
    byte[] out = new byte[header.length + nonce.length + sealed.length];
    System.arraycopy(header, 0, out, 0, header.length);
    System.arraycopy(nonce, 0, out, header.length, nonce.length);
    System.arraycopy(sealed, 0, out, header.length + nonce.length, sealed.length);
    return out;
  }

  /**
   * Checks the tag and returns the plaintext.
   *
   * @throws IllegalArgumentException when {@code envelope} is not an envelope, or the key has the
   *     wrong size
   * @throws AuthenticationException when the tag does not verify
   */
  public static byte[] open(byte[] envelope, byte[] dataKey, byte[] context) {
    Objects.requireNonNull(envelope, "envelope");
    Objects.requireNonNull(dataKey, "dataKey");
    Objects.requireNonNull(context, "context");
    int headerLength = parse(envelope).orElseThrow(Envelope::notAnEnvelope).headerLength();
    byte[] nonce = Arrays.copyOfRange(envelope, headerLength, headerLength + AesGcm.NONCE_BYTES);
    byte[] sealed =
        Arrays.copyOfRange(envelope, headerLength + AesGcm.NONCE_BYTES, envelope.length);
    return AesGcm.decrypt(dataKey, nonce, aad(envelope, headerLength, context), sealed);
  }

  /**
   * The additional data that a KMS binds into a wrapped data key: {@code regpack/dek/v1|<kind>|<kek
   * id>|<context>} in UTF-8, with the kind as {@code OBJECT} or {@code BODY}.
   *
   * @throws IllegalArgumentException when the KEK id is outside the envelope alphabet (so that it
   *     cannot move the field boundary)
   */
  public static byte[] wrapAad(Kind kind, String kekId, String context) {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(kekId, "kekId");
    Objects.requireNonNull(context, "context");
    if (!isKekId(kekId)) {
      throw new IllegalArgumentException("invalid key id");
    }
    return ("regpack/dek/v1|" + kind.name() + "|" + kekId + "|" + context)
        .getBytes(StandardCharsets.UTF_8);
  }

  // ---- internals ---------------------------------------------------------------------------

  private record Parsed(Header header, int headerLength) {}

  private static IllegalArgumentException notAnEnvelope() {
    return new IllegalArgumentException(NOT_AN_ENVELOPE);
  }

  private static boolean isKekId(String id) {
    if (id.isEmpty() || id.length() > MAX_KEK_ID_CHARS) {
      return false;
    }
    for (int i = 0; i < id.length(); i++) {
      if (!isKekIdChar(id.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isKekIdChar(int c) {
    return (c >= 'A' && c <= 'Z')
        || (c >= 'a' && c <= 'z')
        || (c >= '0' && c <= '9')
        || c == '.'
        || c == '_'
        || c == '-';
  }

  /**
   * The single reading of the layout: {@code isEnvelope}, {@code header} and {@code open} use it.
   */
  private static Optional<Parsed> parse(byte[] b) {
    if (b.length < KEK_ID_OFFSET) {
      return Optional.empty();
    }
    for (int i = 0; i < MAGIC.length; i++) {
      if (b[i] != MAGIC[i]) {
        return Optional.empty();
      }
    }
    if ((b[4] & 0xff) != VERSION) {
      return Optional.empty();
    }
    Kind kind;
    switch (b[5] & 0xff) {
      case 1 -> kind = Kind.OBJECT;
      case 2 -> kind = Kind.BODY;
      default -> {
        return Optional.empty();
      }
    }
    int kekLength = b[6] & 0xff;
    if (kekLength < 1 || kekLength > MAX_KEK_ID_CHARS) {
      return Optional.empty();
    }
    int wrappedLengthAt = KEK_ID_OFFSET + kekLength;
    if (b.length < wrappedLengthAt + WRAPPED_LENGTH_BYTES) {
      return Optional.empty();
    }
    for (int i = KEK_ID_OFFSET; i < wrappedLengthAt; i++) {
      if (!isKekIdChar(b[i] & 0xff)) {
        return Optional.empty();
      }
    }
    int wrappedLength = ((b[wrappedLengthAt] & 0xff) << 8) | (b[wrappedLengthAt + 1] & 0xff);
    if (wrappedLength < 1 || wrappedLength > MAX_WRAPPED_BYTES) {
      return Optional.empty();
    }
    int wrappedAt = wrappedLengthAt + WRAPPED_LENGTH_BYTES;
    int headerLength = wrappedAt + wrappedLength;
    if (b.length < headerLength + AesGcm.NONCE_BYTES + AesGcm.TAG_BYTES) {
      return Optional.empty();
    }
    String kekId = new String(b, KEK_ID_OFFSET, kekLength, StandardCharsets.US_ASCII);
    byte[] wrapped = Arrays.copyOfRange(b, wrappedAt, headerLength);
    return Optional.of(new Parsed(new Header(kind, kekId, wrapped), headerLength));
  }

  private static byte[] encodeHeader(Header h) {
    byte[] kekId = h.kekId().getBytes(StandardCharsets.US_ASCII);
    byte[] wrapped = h.wrappedKey();
    ByteBuffer out =
        ByteBuffer.allocate(KEK_ID_OFFSET + kekId.length + WRAPPED_LENGTH_BYTES + wrapped.length);
    out.put(MAGIC);
    out.put((byte) VERSION);
    out.put((byte) h.kind().code());
    out.put((byte) kekId.length);
    out.put(kekId);
    out.putShort((short) wrapped.length);
    out.put(wrapped);
    return out.array();
  }

  /** The first {@code headerLength} bytes of {@code source}, a 4-byte length and the context. */
  private static byte[] aad(byte[] source, int headerLength, byte[] context) {
    ByteBuffer out = ByteBuffer.allocate(headerLength + Integer.BYTES + context.length);
    out.put(source, 0, headerLength);
    out.putInt(context.length);
    out.put(context);
    return out.array();
  }
}
