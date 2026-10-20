package dev.regpack.evidence.sealed;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.evidence.sealed.SealedFormat.CorruptException;
import dev.regpack.evidence.sealed.SealedFormat.Decoded;
import dev.regpack.evidence.sealed.SealedFormat.Header;
import dev.regpack.evidence.sealed.SealedFormat.Meta;
import dev.regpack.evidence.sealed.SealedFormat.Reason;
import dev.regpack.model.Sha256;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** D-E5: the object file ("RPSEAL01") and the meta file ("RPMETA01") of P-SEALED. */
class SealedFormatTest {
  private static final Instant WRITTEN = Instant.parse("2026-10-19T12:00:00Z");
  private static final Instant RETAIN = Instant.parse("2031-10-19T12:00:00Z");
  private static final byte[] HELLO = "hello".getBytes(UTF_8);
  private static final String GOLDEN_HEADER =
      "{\"key\":\"landing/k1\",\"length\":5,\"media_type\":\"text/plain\","
          + "\"retain_until\":\"2031-10-19T12:00:00.000000Z\",\"retention_class\":\"sealed\","
          + "\"sha256\":\"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824\","
          + "\"written_at\":\"2026-10-19T12:00:00.000000Z\"}";
  private static final String GOLDEN_OBJECT_SHA256 =
      "8582af0639f5db6eb5cf3d3961a58947fdafa4d81b8a8d6f82063ef64db688e4";
  private static final String GOLDEN_META =
      "{\"legal_hold\":true,\"retain_until\":\"2031-10-19T12:00:00.000000Z\","
          + "\"retention_class\":\"sealed\",\"rev\":3}";
  private static final String GOLDEN_META_SHA256 =
      "a887fe6440154173cea75b21e6bab0e3966aaa290fd44b065964ce0091185020";

  private static Header header(String key, String mediaType, byte[] payload) {
    return new Header(
        key, mediaType, Sha256.of(payload), payload.length, WRITTEN, "sealed", RETAIN);
  }

  private static byte[] golden() {
    return SealedFormat.encodeObject(header("landing/k1", "text/plain", HELLO), HELLO);
  }

  /** A file with the given magic, header length field, header bytes and payload. */
  private static byte[] raw(String magic, int headerLength, byte[] header, byte[] payload) {
    ByteBuffer buffer = ByteBuffer.allocate(8 + 4 + header.length + payload.length);
    buffer.put(magic.getBytes(StandardCharsets.US_ASCII));
    buffer.putInt(headerLength);
    buffer.put(header);
    buffer.put(payload);
    return buffer.array();
  }

  private static byte[] rawWithHeader(String headerText, byte[] payload) {
    byte[] header = headerText.getBytes(UTF_8);
    return raw("RPSEAL01", header.length, header, payload);
  }

  private static void assertCorrupt(byte[] file, String key, Reason reason) {
    assertThatThrownBy(() -> SealedFormat.decodeObject(file, key))
        .isInstanceOfSatisfying(
            CorruptException.class, e -> assertThat(e.reason()).isEqualTo(reason))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void theHeaderOfAGoldenObjectHasThePinnedBytes() {
    byte[] file = golden();
    byte[] header = GOLDEN_HEADER.getBytes(UTF_8);
    assertThat(header).hasSize(248);
    assertThat(Arrays.copyOfRange(file, 0, 8)).isEqualTo("RPSEAL01".getBytes(UTF_8));
    assertThat(ByteBuffer.wrap(file, 8, 4).getInt()).isEqualTo(248);
    assertThat(new String(file, 12, 248, UTF_8)).isEqualTo(GOLDEN_HEADER);
    assertThat(Arrays.copyOfRange(file, 260, file.length)).isEqualTo(HELLO);
    assertThat(file).hasSize(265);
    assertThat(Sha256.of(file).hex()).isEqualTo(GOLDEN_OBJECT_SHA256);
  }

  @Test
  void roundTrips() {
    SplittableRandom random = new SplittableRandom(3L);
    byte[] big = new byte[70_000];
    for (int i = 0; i < big.length; i++) {
      big[i] = (byte) random.nextInt(256);
    }
    for (byte[] payload : new byte[][] {new byte[0], HELLO, big}) {
      Header header = header("a/B/c.d", "application/x-test; charset=\"é\"", payload);
      Decoded decoded =
          SealedFormat.decodeObject(SealedFormat.encodeObject(header, payload), "a/B/c.d");
      assertThat(decoded.header()).isEqualTo(header);
      assertThat(decoded.payload()).isEqualTo(payload);
    }
  }

  @Test
  void theDecodedPayloadIsACopy() {
    Decoded decoded = SealedFormat.decodeObject(golden(), "landing/k1");
    byte[] first = decoded.payload();
    first[0] = 'X';
    assertThat(decoded.payload()).isEqualTo(HELLO);
  }

  @Test
  void flippingOnePayloadByteIsAHashMismatch() {
    byte[] file = golden();
    file[file.length - 1] ^= 1;
    assertCorrupt(file, "landing/k1", Reason.HASH_MISMATCH);
  }

  @Test
  void anotherExpectedKeyIsAKeyMismatch() {
    assertCorrupt(golden(), "landing/k2", Reason.KEY_MISMATCH);
  }

  @Test
  void aLengthThatLiesIsALengthMismatchOrTruncated() {
    Header shorter =
        new Header("landing/k1", "text/plain", Sha256.of(HELLO), 4, WRITTEN, "sealed", RETAIN);
    assertCorrupt(SealedFormat.encodeObject(shorter, HELLO), "landing/k1", Reason.LENGTH_MISMATCH);
    Header longer =
        new Header("landing/k1", "text/plain", Sha256.of(HELLO), 6, WRITTEN, "sealed", RETAIN);
    assertCorrupt(SealedFormat.encodeObject(longer, HELLO), "landing/k1", Reason.TRUNCATED);
  }

  @Test
  void aHeaderLengthOver65536IsRefusedBeforeAnythingIsRead() {
    byte[] file = raw("RPSEAL01", 65_537, new byte[0], new byte[0]);
    assertCorrupt(file, "landing/k1", Reason.HEADER_TOO_LARGE);
    assertCorrupt(
        raw("RPSEAL01", Integer.MAX_VALUE, new byte[0], HELLO),
        "landing/k1",
        Reason.HEADER_TOO_LARGE);
  }

  @Test
  void aWrongMagicIsBadMagic() {
    byte[] file = golden();
    file[0] = 'X';
    assertCorrupt(file, "landing/k1", Reason.BAD_MAGIC);
    assertCorrupt(
        raw("RPMETA01", 248, GOLDEN_HEADER.getBytes(UTF_8), HELLO), "landing/k1", Reason.BAD_MAGIC);
    assertCorrupt("not a sealed file at all".getBytes(UTF_8), "landing/k1", Reason.BAD_MAGIC);
  }

  @Test
  void aFileCutAtEveryOffsetBelowItsLengthIsTruncated() {
    byte[] file = golden();
    for (int cut = 0; cut < file.length; cut++) {
      assertCorrupt(Arrays.copyOf(file, cut), "landing/k1", Reason.TRUNCATED);
    }
  }

  @Test
  void negativeHeaderLength() {
    assertCorrupt(
        raw("RPSEAL01", -1, GOLDEN_HEADER.getBytes(UTF_8), HELLO), "landing/k1", Reason.BAD_HEADER);
    assertCorrupt(
        raw("RPSEAL01", Integer.MIN_VALUE, new byte[0], HELLO), "landing/k1", Reason.BAD_HEADER);
  }

  @Test
  void zeroHeaderLength() {
    assertCorrupt(raw("RPSEAL01", 0, new byte[0], HELLO), "landing/k1", Reason.BAD_HEADER);
  }

  @Test
  void declaredLengthOfLongMaxValue() {
    String lying = GOLDEN_HEADER.replace("\"length\":5", "\"length\":9223372036854775807");
    assertCorrupt(rawWithHeader(lying, HELLO), "landing/k1", Reason.TRUNCATED);
  }

  @Test
  void aHeaderThatIsNotTheCanonicalHeaderOfThisFormatIsBadHeader() {
    String[] bad = {
      "not json at all",
      "[]",
      "{}",
      GOLDEN_HEADER.replace("{", "{ "),
      GOLDEN_HEADER.replace("\"length\":5", "\"length\":-1"),
      GOLDEN_HEADER.replace("\"length\":5", "\"length\":5.0"),
      GOLDEN_HEADER.replace("\"length\":5", "\"length\":\"5\""),
      GOLDEN_HEADER.replace("\"key\":\"landing/k1\"", "\"key\":\"landing//k1\""),
      GOLDEN_HEADER.replace("2cf24dba", "2CF24DBA"),
      GOLDEN_HEADER.replace("12:00:00.000000Z\",\"retention", "12:00:00Z\",\"retention"),
      GOLDEN_HEADER.replace("\"written_at\"", "\"written\""),
      GOLDEN_HEADER.substring(0, GOLDEN_HEADER.length() - 1) + ",\"extra\":1}",
    };
    for (String text : bad) {
      assertCorrupt(rawWithHeader(text, HELLO), "landing/k1", Reason.BAD_HEADER);
    }
    byte[] notUtf8 = GOLDEN_HEADER.getBytes(UTF_8);
    notUtf8[20] = (byte) 0xC3;
    notUtf8[21] = (byte) 0x28;
    assertCorrupt(raw("RPSEAL01", notUtf8.length, notUtf8, HELLO), "landing/k1", Reason.BAD_HEADER);
  }

  @Test
  void aHeaderThatEncodesAboveTheLimitIsRefusedByEncodeObject() {
    int atLimit = 65_536 - GOLDEN_HEADER.length() + "text/plain".length();
    String exactly = "m".repeat(atLimit);
    Header fits = header("landing/k1", exactly, HELLO);
    byte[] file = SealedFormat.encodeObject(fits, HELLO);
    assertThat(ByteBuffer.wrap(file, 8, 4).getInt()).isEqualTo(65_536);
    assertThat(SealedFormat.decodeObject(file, "landing/k1").header()).isEqualTo(fits);
    Header over = header("landing/k1", exactly + "m", HELLO);
    assertThatIllegalArgumentException().isThrownBy(() -> SealedFormat.encodeObject(over, HELLO));
    Header huge = header("landing/k1", "m".repeat(70_000), HELLO);
    assertThatIllegalArgumentException().isThrownBy(() -> SealedFormat.encodeObject(huge, HELLO));
  }

  @Test
  void aHeaderRefusesValuesTheFormatCannotHold() {
    Sha256 hash = Sha256.of(HELLO);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Header("landing/k1", "t", hash, -1, WRITTEN, "sealed", RETAIN));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Header("a//b", "t", hash, 1, WRITTEN, "sealed", RETAIN));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new Header("landing/k1", "t", hash, 1, WRITTEN.plusNanos(1), "sealed", RETAIN));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Header("landing/k1", "t", hash, 1, WRITTEN, "sealed", Instant.MAX));
    assertThatThrownBy(() -> new Header("landing/k1", null, hash, 1, WRITTEN, "sealed", RETAIN))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void metaRoundTrips() {
    Meta meta = new Meta(3, "sealed", RETAIN, true);
    byte[] file = SealedFormat.encodeMeta(meta);
    assertThat(new String(file, UTF_8)).isEqualTo("RPMETA01" + GOLDEN_META);
    assertThat(Sha256.of(file).hex()).isEqualTo(GOLDEN_META_SHA256);
    assertThat(SealedFormat.decodeMeta(file)).isEqualTo(meta);
    Meta free = new Meta(0, "journal", WRITTEN, false);
    assertThat(SealedFormat.decodeMeta(SealedFormat.encodeMeta(free))).isEqualTo(free);
  }

  @Test
  void aCorruptMetaFileIsCorrupt() {
    byte[] file = SealedFormat.encodeMeta(new Meta(3, "sealed", RETAIN, true));
    for (int cut = 0; cut < file.length; cut++) {
      int length = cut;
      assertThatThrownBy(() -> SealedFormat.decodeMeta(Arrays.copyOf(file, length)))
          .isInstanceOf(CorruptException.class);
    }
    byte[] wrongMagic = file.clone();
    wrongMagic[0] = 'X';
    assertThatThrownBy(() -> SealedFormat.decodeMeta(wrongMagic))
        .isInstanceOfSatisfying(
            CorruptException.class, e -> assertThat(e.reason()).isEqualTo(Reason.BAD_MAGIC));
    byte[] spaced = ("RPMETA01" + GOLDEN_META.replace("{", "{ ")).getBytes(UTF_8);
    assertThatThrownBy(() -> SealedFormat.decodeMeta(spaced))
        .isInstanceOfSatisfying(
            CorruptException.class, e -> assertThat(e.reason()).isEqualTo(Reason.BAD_HEADER));
    byte[] negative = ("RPMETA01" + GOLDEN_META.replace("\"rev\":3", "\"rev\":-3")).getBytes(UTF_8);
    assertThatThrownBy(() -> SealedFormat.decodeMeta(negative))
        .isInstanceOfSatisfying(
            CorruptException.class, e -> assertThat(e.reason()).isEqualTo(Reason.BAD_HEADER));
    byte[] huge = new byte[8 + 65_537];
    System.arraycopy(file, 0, huge, 0, 8);
    Arrays.fill(huge, 8, huge.length, (byte) ' ');
    assertThatThrownBy(() -> SealedFormat.decodeMeta(huge))
        .isInstanceOfSatisfying(
            CorruptException.class, e -> assertThat(e.reason()).isEqualTo(Reason.HEADER_TOO_LARGE));
  }

  @Test
  void aCorruptExceptionNamesItsReasonAndNothingElse() {
    byte[] file = golden();
    file[file.length - 1] ^= 1;
    assertThatThrownBy(() -> SealedFormat.decodeObject(file, "landing/k1"))
        .isInstanceOf(CorruptException.class)
        .hasMessage("HASH_MISMATCH");
  }
}
