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
import java.util.HexFormat;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** D-E5: the object file ("RPSEAL02") and the meta file ("RPMETA02") of P-SEALED. */
class SealedFormatTest {
  private static final Instant WRITTEN = Instant.parse("2026-10-19T12:00:00Z");
  private static final Instant RETAIN = Instant.parse("2031-10-19T12:00:00Z");
  private static final byte[] HELLO = "hello".getBytes(UTF_8);
  private static final String GOLDEN_HEADER =
      "{\"key\":\"landing/k1\",\"length\":5,\"media_type\":\"text/plain\","
          + "\"retain_until\":\"2031-10-19T12:00:00.000000Z\",\"retention_class\":\"sealed\","
          + "\"sha256\":\"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824\","
          + "\"written_at\":\"2026-10-19T12:00:00.000000Z\"}";
  // Computed with printf, xxd and sha256sum (not with the code under test): see the report of FC-A.
  private static final String GOLDEN_OBJECT_TRAILER =
      "170025ff4444f8fd4eb4ef4c549945a90ebe0c05247be553ef12bd2be11594e5";
  private static final String GOLDEN_OBJECT_SHA256 =
      "6ba2ff133d8c5097ef0bdf16cc772a6a98669e098bbd68d0002a0dc78720308a";
  private static final String GOLDEN_META =
      "{\"legal_hold\":true,\"retain_until\":\"2031-10-19T12:00:00.000000Z\","
          + "\"retention_class\":\"sealed\",\"rev\":3}";
  private static final String GOLDEN_META_TRAILER =
      "fb7f581c186369759c2ff80f12882fa4f26c2b7c79ae7a5f461be4ebfab7598f";
  private static final String GOLDEN_META_SHA256 =
      "03b06654451ec4dd82b7f234894df1f096ae2175f8999d59811b0c2aa4e4a0a3";

  private static Header header(String key, String mediaType, byte[] payload) {
    return new Header(
        key, mediaType, Sha256.of(payload), payload.length, WRITTEN, "sealed", RETAIN);
  }

  private static byte[] golden() {
    return SealedFormat.encodeObject(header("landing/k1", "text/plain", HELLO), HELLO);
  }

  /** The bytes followed by the SHA-256 of the bytes: a file with a valid trailer. */
  private static byte[] sealed(byte[] body) {
    byte[] trailer = HexFormat.of().parseHex(Sha256.of(body).hex());
    byte[] file = Arrays.copyOf(body, body.length + trailer.length);
    System.arraycopy(trailer, 0, file, body.length, trailer.length);
    return file;
  }

  /**
   * A file with the given magic, header length field, header bytes and payload, and a valid
   * trailer, so that only the part under test is wrong.
   */
  private static byte[] raw(String magic, int headerLength, byte[] header, byte[] payload) {
    ByteBuffer buffer = ByteBuffer.allocate(8 + 4 + header.length + payload.length);
    buffer.put(magic.getBytes(StandardCharsets.US_ASCII));
    buffer.putInt(headerLength);
    buffer.put(header);
    buffer.put(payload);
    return sealed(buffer.array());
  }

  private static byte[] metaFile(String bodyText) {
    return sealed(("RPMETA02" + bodyText).getBytes(UTF_8));
  }

  private static void assertMetaCorrupt(byte[] file, Reason reason) {
    assertThatThrownBy(() -> SealedFormat.decodeMeta(file))
        .isInstanceOfSatisfying(
            CorruptException.class, e -> assertThat(e.reason()).isEqualTo(reason));
  }

  private static byte[] rawWithHeader(String headerText, byte[] payload) {
    byte[] header = headerText.getBytes(UTF_8);
    return raw("RPSEAL02", header.length, header, payload);
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
    assertThat(Arrays.copyOfRange(file, 0, 8)).isEqualTo("RPSEAL02".getBytes(UTF_8));
    assertThat(ByteBuffer.wrap(file, 8, 4).getInt()).isEqualTo(248);
    assertThat(new String(file, 12, 248, UTF_8)).isEqualTo(GOLDEN_HEADER);
    assertThat(Arrays.copyOfRange(file, 260, 265)).isEqualTo(HELLO);
    assertThat(HexFormat.of().formatHex(file, 265, file.length)).isEqualTo(GOLDEN_OBJECT_TRAILER);
    assertThat(Sha256.of(Arrays.copyOf(file, 265)).hex()).isEqualTo(GOLDEN_OBJECT_TRAILER);
    assertThat(file).hasSize(297);
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
    file[file.length - 33] ^= 1; // the last payload byte
    assertCorrupt(file, "landing/k1", Reason.HASH_MISMATCH);
  }

  @Test
  void aPayloadChangedUnderAValidTrailerIsStillAHashMismatchOfTheHeader() {
    byte[] file = golden();
    file[file.length - 33] ^= 1;
    byte[] resealed = sealed(Arrays.copyOf(file, file.length - 32));
    assertCorrupt(resealed, "landing/k1", Reason.HASH_MISMATCH);
  }

  @Test
  void aFlippedBitAnywhereInAnObjectFileIsFound() {
    byte[] file = golden();
    int found = 0;
    for (int bit = 0; bit < 8 * file.length; bit++) {
      byte[] damaged = file.clone();
      damaged[bit / 8] ^= (byte) (1 << (bit % 8));
      Reason expected = bit / 8 < 8 ? Reason.BAD_MAGIC : Reason.HASH_MISMATCH;
      assertCorrupt(damaged, "landing/k1", expected);
      found++;
    }
    assertThat(found).isEqualTo(8 * file.length).isEqualTo(8 * 297);
  }

  @Test
  void aDeliberateEditThatRecomputesTheTrailerIsNotFoundByTheFormat() {
    // The stated limit: the trailer finds damage, not a deliberate edit; only the signed
    // manifests of P3c do. This pins the limit so the Javadoc cannot drift from the code.
    byte[] file = golden();
    String edited = GOLDEN_HEADER.replace("\"2031-", "\"2021-");
    byte[] body = new byte[12 + edited.length() + HELLO.length];
    System.arraycopy(file, 0, body, 0, 12);
    System.arraycopy(edited.getBytes(UTF_8), 0, body, 12, edited.length());
    System.arraycopy(HELLO, 0, body, 12 + edited.length(), HELLO.length);
    Decoded decoded = SealedFormat.decodeObject(sealed(body), "landing/k1");
    assertThat(decoded.header().retainUntil()).isEqualTo(Instant.parse("2021-10-19T12:00:00Z"));
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
    byte[] file = raw("RPSEAL02", 65_537, new byte[0], new byte[0]);
    assertCorrupt(file, "landing/k1", Reason.HEADER_TOO_LARGE);
    assertCorrupt(
        raw("RPSEAL02", Integer.MAX_VALUE, new byte[0], HELLO),
        "landing/k1",
        Reason.HEADER_TOO_LARGE);
  }

  @Test
  void aWrongMagicIsBadMagic() {
    byte[] file = golden();
    file[0] = 'X';
    assertCorrupt(file, "landing/k1", Reason.BAD_MAGIC);
    assertCorrupt(
        raw("RPMETA02", 248, GOLDEN_HEADER.getBytes(UTF_8), HELLO), "landing/k1", Reason.BAD_MAGIC);
    assertCorrupt("not a sealed file at all".getBytes(UTF_8), "landing/k1", Reason.BAD_MAGIC);
  }

  @Test
  void aFileCutAtEveryOffsetBelowItsLengthIsCorrupt() {
    byte[] file = golden();
    for (int cut = 0; cut < file.length; cut++) {
      // below magic + length field + trailer (44 bytes) no object file can exist: TRUNCATED; above
      // it the last 32 bytes read as a trailer that does not seal what precedes them
      Reason expected = cut < 12 + 32 ? Reason.TRUNCATED : Reason.HASH_MISMATCH;
      assertCorrupt(Arrays.copyOf(file, cut), "landing/k1", expected);
    }
  }

  @Test
  void negativeHeaderLength() {
    assertCorrupt(
        raw("RPSEAL02", -1, GOLDEN_HEADER.getBytes(UTF_8), HELLO), "landing/k1", Reason.BAD_HEADER);
    assertCorrupt(
        raw("RPSEAL02", Integer.MIN_VALUE, new byte[0], HELLO), "landing/k1", Reason.BAD_HEADER);
  }

  @Test
  void zeroHeaderLength() {
    assertCorrupt(raw("RPSEAL02", 0, new byte[0], HELLO), "landing/k1", Reason.BAD_HEADER);
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
      "[".repeat(65_000),
      "{\"a\":".repeat(13_000),
    };
    for (String text : bad) {
      assertCorrupt(rawWithHeader(text, HELLO), "landing/k1", Reason.BAD_HEADER);
    }
    byte[] notUtf8 = GOLDEN_HEADER.getBytes(UTF_8);
    notUtf8[20] = (byte) 0xC3;
    notUtf8[21] = (byte) 0x28;
    assertCorrupt(raw("RPSEAL02", notUtf8.length, notUtf8, HELLO), "landing/k1", Reason.BAD_HEADER);
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
    // Instant.MIN has no calendar year to read: still an IllegalArgumentException
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Header("landing/k1", "t", hash, 1, WRITTEN, "sealed", Instant.MIN));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Meta(1, "sealed", Instant.MIN, false));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Meta(1, "sealed", Instant.parse("+10000-01-01T00:00:00Z"), false));
    assertThatThrownBy(() -> new Header("landing/k1", null, hash, 1, WRITTEN, "sealed", RETAIN))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void metaRoundTrips() {
    Meta meta = new Meta(3, "sealed", RETAIN, true);
    byte[] file = SealedFormat.encodeMeta(meta);
    assertThat(new String(file, 0, 8 + GOLDEN_META.length(), UTF_8))
        .isEqualTo("RPMETA02" + GOLDEN_META);
    assertThat(file).hasSize(8 + GOLDEN_META.length() + 32).hasSize(139);
    assertThat(HexFormat.of().formatHex(file, file.length - 32, file.length))
        .isEqualTo(GOLDEN_META_TRAILER);
    assertThat(Sha256.of(Arrays.copyOf(file, file.length - 32)).hex())
        .isEqualTo(GOLDEN_META_TRAILER);
    assertThat(Sha256.of(file).hex()).isEqualTo(GOLDEN_META_SHA256);
    assertThat(SealedFormat.decodeMeta(file)).isEqualTo(meta);
    Meta free = new Meta(0, "journal", WRITTEN, false);
    assertThat(SealedFormat.decodeMeta(SealedFormat.encodeMeta(free))).isEqualTo(free);
  }

  @Test
  void aCorruptMetaFileIsCorrupt() {
    byte[] file = SealedFormat.encodeMeta(new Meta(3, "sealed", RETAIN, true));
    for (int cut = 0; cut < file.length; cut++) {
      // below magic + trailer (40 bytes) no meta file can exist: TRUNCATED; above it the last 32
      // bytes read as a trailer that does not seal what precedes them
      Reason expected = cut < 8 + 32 ? Reason.TRUNCATED : Reason.HASH_MISMATCH;
      assertMetaCorrupt(Arrays.copyOf(file, cut), expected);
    }
    byte[] wrongMagic = file.clone();
    wrongMagic[0] = 'X';
    assertMetaCorrupt(wrongMagic, Reason.BAD_MAGIC);
    assertMetaCorrupt(metaFile(GOLDEN_META.replace("{", "{ ")), Reason.BAD_HEADER);
    assertMetaCorrupt(metaFile(GOLDEN_META.replace("\"rev\":3", "\"rev\":-3")), Reason.BAD_HEADER);
    assertMetaCorrupt(metaFile(""), Reason.BAD_HEADER);
    assertMetaCorrupt(metaFile(" ".repeat(65_537)), Reason.HEADER_TOO_LARGE);
    assertMetaCorrupt(metaFile("[".repeat(65_000)), Reason.BAD_HEADER);
    assertMetaCorrupt(metaFile("{\"a\":".repeat(13_000)), Reason.BAD_HEADER);
  }

  @Test
  void aFlippedBitAnywhereInAMetaFileIsFound() {
    byte[] file = SealedFormat.encodeMeta(new Meta(3, "sealed", RETAIN, true));
    int found = 0;
    for (int bit = 0; bit < 8 * file.length; bit++) {
      byte[] damaged = file.clone();
      damaged[bit / 8] ^= (byte) (1 << (bit % 8));
      assertMetaCorrupt(damaged, bit / 8 < 8 ? Reason.BAD_MAGIC : Reason.HASH_MISMATCH);
      found++;
    }
    assertThat(found).isEqualTo(8 * file.length).isEqualTo(8 * 139);
  }

  @Test
  void aDeliberateMetaEditThatRecomputesTheTrailerIsNotFoundByTheFormat() {
    Meta edited = SealedFormat.decodeMeta(metaFile(GOLDEN_META.replace("\"rev\":3", "\"rev\":4")));
    assertThat(edited).isEqualTo(new Meta(4, "sealed", RETAIN, true));
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
