package dev.regpack.ports.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.crypto.Envelope.AuthenticationException;
import dev.regpack.ports.crypto.Envelope.Header;
import dev.regpack.ports.crypto.Envelope.Kind;
import dev.regpack.ports.security.KeyPurpose;
import dev.regpack.ports.security.KeyRef;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The envelope format of D-K1: golden bytes (V3, V4, V9), refusals and the header rules. */
class EnvelopeTest {
  private static final HexFormat HEX = HexFormat.of();

  /**
   * V3, the whole golden OBJECT envelope; the first 77 bytes (up to and including 3b) are the
   * header.
   */
  private static final String GOLDEN_OBJECT_HEX =
      "5250454e0101086b656b2d32303236003c000102030405060708090a0b0c0d0e0f1011121314151617"
          + "18191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3ba0a1a2a3a4"
          + "a5a6a7a8a9aaab8e7d10412af72c2f31537439fe190424aad21a4c82";

  private static final String GOLDEN_OBJECT_SHA256 =
      "64cadc7ada87cad60da5f26beff486207a842c1f677a20826e9c898cb54094c1";
  private static final String GOLDEN_BODY_SHA256 =
      "9a03fa5f820f19660b6baee4e2bab4247460d98af9c5974391980480affbf59d";
  private static final String WRAP_AAD_HEX =
      "7265677061636b2f64656b2f76317c4f424a4543547c6b656b2d323032367c6c616e64696e672f6b31";

  // layout of the golden envelope: magic (4), version, kind, id length, id (8), wrapped length (2)
  private static final int KEK_ID_OFFSET = 7;
  private static final int WRAPPED_OFFSET = 17;

  private static final byte[] WRAPPED = sequence(0x00, 60);
  private static final byte[] DATA_KEY = sequence(0x00, 32);
  private static final byte[] NONCE = sequence(0xa0, 12);
  private static final byte[] CONTEXT = "landing/k1".getBytes(UTF_8);
  private static final byte[] HELLO = "hello".getBytes(UTF_8);

  private static byte[] sequence(int from, int count) {
    byte[] out = new byte[count];
    for (int i = 0; i < count; i++) {
      out[i] = (byte) (from + i);
    }
    return out;
  }

  private static byte[] goldenObject() {
    return Envelope.seal(
        new Header(Kind.OBJECT, "kek-2026", WRAPPED), DATA_KEY, NONCE, CONTEXT, HELLO);
  }

  private static byte[] bodyContext() {
    return ("regpack/body/v1|mifir.eu.rts22|FIRM1|EXEC9|MARKET|" + sha256Hex("x".getBytes(UTF_8)))
        .getBytes(UTF_8);
  }

  private static byte[] goldenBody() {
    return Envelope.seal(
        new Header(Kind.BODY, "kek-2026", WRAPPED),
        DATA_KEY,
        NONCE,
        bodyContext(),
        "{\"a\":1}".getBytes(UTF_8));
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Hand-built bytes, so that a malformed header can be written that the Header record refuses. */
  private static byte[] raw(
      int version, int kind, int kekLengthByte, byte[] kekId, int wrappedLength, byte[] wrapped) {
    return raw(version, kind, kekLengthByte, kekId, wrappedLength, wrapped, 12 + 16);
  }

  private static byte[] raw(
      int version,
      int kind,
      int kekLengthByte,
      byte[] kekId,
      int wrappedLength,
      byte[] wrapped,
      int tailLength) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {'R', 'P', 'E', 'N', (byte) version, (byte) kind});
    out.write(kekLengthByte);
    out.writeBytes(kekId);
    out.write(wrappedLength >> 8);
    out.write(wrappedLength);
    out.writeBytes(wrapped);
    out.writeBytes(new byte[tailLength]);
    return out.toByteArray();
  }

  private static byte[] wellFormed(String kekId, int wrappedLength) {
    byte[] id = kekId.getBytes(UTF_8);
    return raw(1, 1, id.length, id, wrappedLength, new byte[wrappedLength]);
  }

  /** Whether header succeeds; isEnvelope must give the same answer and must never throw. */
  private static boolean headerSucceeds(byte[] bytes) {
    boolean ok;
    try {
      Envelope.header(bytes);
      ok = true;
    } catch (IllegalArgumentException e) {
      ok = false;
    }
    assertThat(Envelope.isEnvelope(bytes)).as("isEnvelope agrees with header").isEqualTo(ok);
    return ok;
  }

  private static boolean accepts(Runnable action) {
    try {
      action.run();
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  // ---- golden bytes -------------------------------------------------------------------------

  @Test
  void theGoldenObjectEnvelopeHasThePinnedBytesAndSha256() {
    byte[] envelope = goldenObject();

    assertThat(envelope).hasSize(110);
    assertThat(HEX.formatHex(envelope)).isEqualTo(GOLDEN_OBJECT_HEX); // V3
    assertThat(sha256Hex(envelope)).isEqualTo(GOLDEN_OBJECT_SHA256);
    // the 77-byte header ends with the last wrapped-key byte 3b
    assertThat(HEX.formatHex(envelope, 0, 77)).endsWith("38393a3b");
    assertThat(HEX.formatHex(envelope, 77, 89)).isEqualTo("a0a1a2a3a4a5a6a7a8a9aaab");
  }

  @Test
  void theGoldenBodyEnvelopeHasThePinnedSha256() {
    byte[] envelope = goldenBody();

    assertThat(envelope).hasSize(112);
    assertThat(sha256Hex(envelope)).isEqualTo(GOLDEN_BODY_SHA256); // V4
    assertThat(envelope[5]).isEqualTo((byte) 2); // kind BODY
  }

  @Test
  void theWrapAadOfTheGoldenCaseIsPinned() {
    byte[] aad = Envelope.wrapAad(Kind.OBJECT, "kek-2026", "landing/k1");

    assertThat(HEX.formatHex(aad)).isEqualTo(WRAP_AAD_HEX); // V9
    assertThat(new String(aad, UTF_8)).isEqualTo("regpack/dek/v1|OBJECT|kek-2026|landing/k1");
    assertThat(new String(Envelope.wrapAad(Kind.BODY, "kek-2027", "body"), UTF_8))
        .isEqualTo("regpack/dek/v1|BODY|kek-2027|body");
  }

  @Test
  void wrapAadRefusesAKekIdThatCouldMoveTheFieldBoundary() {
    assertThatThrownBy(() -> Envelope.wrapAad(Kind.OBJECT, "kek|OBJECT", "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("OBJECT");
    assertThatThrownBy(() -> Envelope.wrapAad(Kind.OBJECT, "", "x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- open ---------------------------------------------------------------------------------

  @Test
  void openReturnsThePlaintext() {
    assertThat(Envelope.open(goldenObject(), DATA_KEY, CONTEXT)).isEqualTo(HELLO);
    assertThat(Envelope.open(goldenBody(), DATA_KEY, bodyContext()))
        .isEqualTo("{\"a\":1}".getBytes(UTF_8));

    byte[] empty =
        Envelope.seal(
            new Header(Kind.OBJECT, "k", new byte[] {7}),
            DATA_KEY,
            NONCE,
            new byte[0],
            new byte[0]);
    assertThat(empty).hasSize(4 + 3 + 1 + 2 + 1 + 12 + 16);
    assertThat(Envelope.open(empty, DATA_KEY, new byte[0])).isEmpty();
  }

  @Test
  void everySingleByteChangeOfTheGoldenEnvelopeIsRefused() {
    byte[] golden = goldenObject();
    assertThat(golden).hasSize(110);

    for (int position = 0; position < golden.length; position++) {
      for (int mask : new int[] {0x01, 0x80, 0xff}) {
        byte[] changed = golden.clone();
        changed[position] ^= (byte) mask;
        int at = position;
        int by = mask;
        assertThat(refused(changed, DATA_KEY, CONTEXT))
            .as("position %d, mask %02x", at, by)
            .isTrue();
      }
    }
  }

  /**
   * True when open throws IllegalArgumentException or AuthenticationException and gives nothing.
   */
  private static boolean refused(byte[] envelope, byte[] key, byte[] context) {
    try {
      Envelope.open(envelope, key, context);
      return false; // a returned plaintext is "not refused"
    } catch (IllegalArgumentException | AuthenticationException e) {
      return true;
    }
  }

  @Test
  void everyTruncationIsRefused() {
    byte[] golden = goldenObject();

    for (int length = 0; length < golden.length; length++) {
      byte[] cut = Arrays.copyOf(golden, length);
      int at = length;
      assertThat(refused(cut, DATA_KEY, CONTEXT)).as("length %d", at).isTrue();
    }
    byte[] longer = Arrays.copyOf(golden, golden.length + 1);
    assertThat(refused(longer, DATA_KEY, CONTEXT)).isTrue();
  }

  @Test
  void aWrongKeyOrContextIsRefused() {
    byte[] golden = goldenObject();
    byte[] otherKey = DATA_KEY.clone();
    otherKey[0] ^= 1;

    assertThatThrownBy(() -> Envelope.open(golden, otherKey, CONTEXT))
        .isInstanceOf(AuthenticationException.class);
    assertThatThrownBy(() -> Envelope.open(golden, DATA_KEY, "landing/k2".getBytes(UTF_8)))
        .isInstanceOf(AuthenticationException.class);
    assertThatThrownBy(() -> Envelope.open(golden, DATA_KEY, "landing/k1x".getBytes(UTF_8)))
        .isInstanceOf(AuthenticationException.class);
    assertThatThrownBy(() -> Envelope.open(golden, DATA_KEY, "landing/k".getBytes(UTF_8)))
        .isInstanceOf(AuthenticationException.class);
    assertThatThrownBy(() -> Envelope.open(golden, DATA_KEY, new byte[0]))
        .isInstanceOf(AuthenticationException.class);
    // a key of the wrong size is a caller error, not an authentication result
    assertThatThrownBy(() -> Envelope.open(golden, Arrays.copyOf(DATA_KEY, 31), CONTEXT))
        .isInstanceOf(IllegalArgumentException.class);
    // a body envelope does not open under the context of an object envelope and the reverse
    assertThatThrownBy(() -> Envelope.open(goldenBody(), DATA_KEY, CONTEXT))
        .isInstanceOf(AuthenticationException.class);
  }

  @Test
  void aSwappedKekIdOrWrappedKeyFailsTheTag() {
    // KF2: the header is part of the authenticated data
    byte[] golden = goldenObject();
    byte[] otherKek = golden.clone();
    otherKek[KEK_ID_OFFSET + 7] = '7'; // "kek-2026" -> "kek-2027"
    byte[] otherWrapped = golden.clone();
    otherWrapped[WRAPPED_OFFSET + 5] ^= 1;

    assertThat(Envelope.header(otherKek).kekId()).isEqualTo("kek-2027");
    assertThatThrownBy(() -> Envelope.open(otherKek, DATA_KEY, CONTEXT))
        .isInstanceOf(AuthenticationException.class);
    assertThat(Envelope.header(otherWrapped).wrappedKey()).isNotEqualTo(WRAPPED);
    assertThatThrownBy(() -> Envelope.open(otherWrapped, DATA_KEY, CONTEXT))
        .isInstanceOf(AuthenticationException.class);
  }

  // ---- header -------------------------------------------------------------------------------

  @Test
  void headerReadsKindKekIdAndWrappedKey() {
    Header object = Envelope.header(goldenObject());
    Header body = Envelope.header(goldenBody());

    assertThat(object.kind()).isEqualTo(Kind.OBJECT);
    assertThat(object.kekId()).isEqualTo("kek-2026");
    assertThat(object.wrappedKey()).isEqualTo(WRAPPED);
    assertThat(body.kind()).isEqualTo(Kind.BODY);
    assertThat(body).isNotEqualTo(object);
    assertThat(Envelope.header(goldenObject())).isEqualTo(object).hasSameHashCodeAs(object);
  }

  @Test
  void headerRefusesBadKekIdsLengthsAndAnOversizedWrappedKey() {
    // the helper builds an accepted envelope
    assertThat(Envelope.isEnvelope(wellFormed("kek-2026", 60))).isTrue();
    assertThat(headerSucceeds(wellFormed("kek-2026", 60))).isTrue();

    // wrapped key length: 1 and 1,024 pass; 0 and 1,025 do not
    assertThat(headerSucceeds(wellFormed("k", 1))).isTrue();
    assertThat(headerSucceeds(wellFormed("k", 1024))).isTrue();
    assertThat(headerSucceeds(wellFormed("k", 0))).isFalse();
    assertThat(headerSucceeds(wellFormed("k", 1025))).isFalse();
    assertThat(headerSucceeds(wellFormed("k", 65535))).isFalse();

    // KEK id length: 1 and 100 pass; 0 and 101 do not
    assertThat(headerSucceeds(wellFormed("a".repeat(100), 8))).isTrue();
    assertThat(headerSucceeds(wellFormed("a".repeat(101), 8))).isFalse();
    assertThat(headerSucceeds(raw(1, 1, 0, new byte[0], 8, new byte[8]))).isFalse();

    // KEK id alphabet: letters, digits, '.', '_' and '-' only
    assertThat(headerSucceeds(wellFormed("aZ09._-", 8))).isTrue();
    for (String bad :
        new String[] {
          "kek 2026", "kek/2026", "kek|2026", "kek:2026", "kék", "kek\u0000", "kek\n"
        }) {
      assertThat(headerSucceeds(wellFormed(bad, 8))).as("kek id %s", bad.length()).isFalse();
    }

    // the declared lengths must lie inside the array
    byte[] declaredTooLong = raw(1, 1, 3, "kek".getBytes(UTF_8), 60, new byte[10], 0);
    assertThat(headerSucceeds(declaredTooLong)).isFalse();
    assertThat(headerSucceeds(raw(1, 1, 200, "kek".getBytes(UTF_8), 1, new byte[1], 0))).isFalse();

    // total length: header + 12 + 16 passes, one byte less does not
    byte[] id = "kek-2026".getBytes(UTF_8);
    assertThat(headerSucceeds(raw(1, 1, id.length, id, 60, new byte[60], 28))).isTrue();
    assertThat(headerSucceeds(raw(1, 1, id.length, id, 60, new byte[60], 27))).isFalse();

    // version and kind
    assertThat(headerSucceeds(raw(0, 1, id.length, id, 60, new byte[60]))).isFalse();
    assertThat(headerSucceeds(raw(2, 1, id.length, id, 60, new byte[60]))).isFalse();
    assertThat(headerSucceeds(raw(1, 0, id.length, id, 60, new byte[60]))).isFalse();
    assertThat(headerSucceeds(raw(1, 3, id.length, id, 60, new byte[60]))).isFalse();
    assertThat(headerSucceeds(raw(1, 2, id.length, id, 60, new byte[60]))).isTrue();

    // the Header record applies the same rules
    assertThatThrownBy(() -> new Header(Kind.OBJECT, "", new byte[] {1}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Header(Kind.OBJECT, "a".repeat(101), new byte[] {1}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Header(Kind.OBJECT, "a b", new byte[] {1}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("a b");
    assertThatThrownBy(() -> new Header(Kind.OBJECT, "kek", new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Header(Kind.OBJECT, "kek", new byte[1025]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new Header(Kind.OBJECT, "kek", new byte[1024]).wrappedKey()).hasSize(1024);
  }

  @Test
  void theKekIdAlphabetIsTheSameInTheHeaderTheSelectorsAndTheWrapAad() {
    for (int c = 0; c < 256; c++) {
      boolean allowed =
          (c >= 'A' && c <= 'Z')
              || (c >= 'a' && c <= 'z')
              || (c >= '0' && c <= '9')
              || c == '.'
              || c == '_'
              || c == '-';
      String id = "k" + (char) c;
      int at = c;
      byte[] inHeader = raw(1, 1, 2, new byte[] {'k', (byte) c}, 8, new byte[8]);

      assertThat(headerSucceeds(inHeader)).as("header byte %d", at).isEqualTo(allowed);
      assertThat(accepts(() -> KekSelector.single(new KeyRef(id, KeyPurpose.ENVELOPE_KEK))))
          .as("single %d", at)
          .isEqualTo(allowed);
      assertThat(accepts(() -> KekSelector.perUtcYear(id)))
          .as("perUtcYear %d", at)
          .isEqualTo(allowed);
      assertThat(accepts(() -> Envelope.wrapAad(Kind.OBJECT, id, "x")))
          .as("wrapAad %d", at)
          .isEqualTo(allowed);
      assertThat(accepts(() -> new Header(Kind.OBJECT, id, new byte[] {1})))
          .as("Header %d", at)
          .isEqualTo(allowed);
    }
  }

  @Test
  void wrappedKeysOfEverySizeClassRoundTripThroughTheHeader() {
    for (Kind kind : Kind.values()) {
      for (int size :
          new int[] {1, 2, 127, 128, 129, 255, 256, 257, 384, 511, 512, 1000, 1023, 1024}) {
        byte[] wrapped = new byte[size];
        for (int i = 0; i < size; i++) {
          wrapped[i] = (byte) (i * 31 + 7);
        }
        byte[] envelope =
            Envelope.seal(new Header(kind, "kek-2026", wrapped), DATA_KEY, NONCE, CONTEXT, HELLO);

        assertThat(Envelope.isEnvelope(envelope)).as("%s %d", kind, size).isTrue();
        Header header = Envelope.header(envelope);
        assertThat(header.kind()).as("%s %d", kind, size).isEqualTo(kind);
        assertThat(header.wrappedKey()).as("%s %d", kind, size).isEqualTo(wrapped);
        assertThat(Envelope.open(envelope, DATA_KEY, CONTEXT))
            .as("%s %d", kind, size)
            .isEqualTo(HELLO);
      }
    }
  }

  @Test
  void sealAndOpenGiveTheGoldenResultFromManyThreads() throws Exception {
    byte[] golden = goldenObject();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Boolean>> futures = new ArrayList<>();
      for (int t = 0; t < 8; t++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  for (int i = 0; i < 200; i++) {
                    if (!Arrays.equals(goldenObject(), golden)
                        || !Arrays.equals(Envelope.open(golden, DATA_KEY, CONTEXT), HELLO)) {
                      return false;
                    }
                  }
                  return true;
                }));
      }
      start.countDown();
      for (Future<Boolean> future : futures) {
        assertThat(future.get(60, TimeUnit.SECONDS)).isTrue();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void headerFailuresUseOneFixedMessage() {
    byte[] bad = wellFormed("kek 2026", 8);

    assertThatThrownBy(() -> Envelope.header(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("not an envelope");
    assertThatThrownBy(() -> Envelope.header(new byte[0]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("not an envelope");
    assertThatThrownBy(() -> Envelope.header("{\"a\":1}".getBytes(UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("not an envelope");
    assertThatThrownBy(() -> Envelope.open(bad, DATA_KEY, CONTEXT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("not an envelope");
  }

  @Test
  void isEnvelopeTestsTheWholeHeader() {
    assertThat(Envelope.isEnvelope(new byte[] {'{'})).isFalse();
    assertThat(Envelope.isEnvelope("{\"a\":1}".getBytes(UTF_8))).isFalse();
    assertThat(Envelope.isEnvelope(new byte[0])).isFalse();
    assertThat(Envelope.isEnvelope("RPEN".getBytes(UTF_8))).isFalse(); // magic alone
    // magic with version 2
    assertThat(Envelope.isEnvelope(raw(2, 1, 8, "kek-2026".getBytes(UTF_8), 60, new byte[60])))
        .isFalse();
    // magic, then garbage where the header should be
    byte[] garbage = new byte[200];
    Arrays.fill(garbage, (byte) 0x7f);
    System.arraycopy("RPEN".getBytes(UTF_8), 0, garbage, 0, 4);
    assertThat(Envelope.isEnvelope(garbage)).isFalse();
    byte[] zeros = new byte[200];
    System.arraycopy("RPEN".getBytes(UTF_8), 0, zeros, 0, 4);
    assertThat(Envelope.isEnvelope(zeros)).isFalse();

    assertThat(Envelope.isEnvelope(goldenObject())).isTrue();
    assertThat(Envelope.isEnvelope(goldenBody())).isTrue();
  }

  @Test
  void aWrongMagicByteIsNotAnEnvelope() {
    byte[] golden = goldenObject();
    for (int position = 0; position < 4; position++) {
      for (int mask : new int[] {0x01, 0x20, 0x80}) {
        byte[] changed = golden.clone();
        changed[position] ^= (byte) mask;
        int at = position;
        assertThat(Envelope.isEnvelope(changed)).as("magic byte %d", at).isFalse();
        assertThatThrownBy(() -> Envelope.header(changed))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("not an envelope");
      }
    }
  }

  @Test
  void isEnvelopeAgreesWithHeaderOnEveryMutationAndTruncation() {
    byte[] golden = goldenObject();
    for (int position = 0; position < golden.length; position++) {
      for (int mask : new int[] {0x01, 0x02, 0x80, 0xff}) {
        byte[] changed = golden.clone();
        changed[position] ^= (byte) mask;
        int at = position;
        assertThat(Envelope.isEnvelope(changed))
            .as("position %d", at)
            .isEqualTo(headerSucceeds(changed));
      }
    }
    for (int length = 0; length <= golden.length; length++) {
      byte[] cut = Arrays.copyOf(golden, length);
      int at = length;
      assertThat(Envelope.isEnvelope(cut)).as("length %d", at).isEqualTo(headerSucceeds(cut));
    }
  }

  @Test
  void headerToStringHidesTheWrappedKey() {
    Header header = Envelope.header(goldenObject());

    assertThat(header.toString()).isEqualTo("Header[kind=OBJECT, wrappedLength=60]");
    assertThat(Envelope.header(goldenBody()).toString())
        .isEqualTo("Header[kind=BODY, wrappedLength=60]");
    assertThat(header.toString()).doesNotContain("kek").doesNotContain("2026");
  }

  @Test
  void aHeaderCopiesItsWrappedKeyOnTheWayInAndOut() {
    byte[] wrapped = WRAPPED.clone();
    Header header = new Header(Kind.OBJECT, "kek-2026", wrapped);

    wrapped[0] ^= 1;
    byte[] out = header.wrappedKey();
    out[1] ^= 1;

    assertThat(header.wrappedKey()).isEqualTo(WRAPPED);
    assertThat(header).isEqualTo(new Header(Kind.OBJECT, "kek-2026", WRAPPED));
    assertThat(header).isNotEqualTo(new Header(Kind.OBJECT, "kek-2027", WRAPPED));
    assertThat(header).isNotEqualTo(new Header(Kind.BODY, "kek-2026", WRAPPED));
    assertThat(header).isNotEqualTo(new Header(Kind.OBJECT, "kek-2026", wrapped));
  }

  // ---- seal ---------------------------------------------------------------------------------

  @Test
  void sealRefusesAWrongSizedKeyOrNonce() {
    Header header = new Header(Kind.OBJECT, "kek-2026", WRAPPED);

    for (int size : new int[] {0, 16, 31, 33, 64}) {
      byte[] key = new byte[size];
      assertThatThrownBy(() -> Envelope.seal(header, key, NONCE, CONTEXT, HELLO))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (int size : new int[] {0, 8, 11, 13, 16}) {
      byte[] nonce = new byte[size];
      assertThatThrownBy(() -> Envelope.seal(header, DATA_KEY, nonce, CONTEXT, HELLO))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void theSealDoesNotChangeItsArguments() {
    byte[] key = DATA_KEY.clone();
    byte[] nonce = NONCE.clone();
    byte[] context = CONTEXT.clone();
    byte[] plaintext = HELLO.clone();

    Envelope.seal(new Header(Kind.OBJECT, "kek-2026", WRAPPED), key, nonce, context, plaintext);

    assertThat(key).isEqualTo(DATA_KEY);
    assertThat(nonce).isEqualTo(NONCE);
    assertThat(context).isEqualTo(CONTEXT);
    assertThat(plaintext).isEqualTo(HELLO);
  }

  @Test
  void noMessageHoldsAKeyAKekIdOrAContext() {
    byte[] golden = goldenObject();
    byte[] wrongKey = DATA_KEY.clone();
    wrongKey[0] ^= 1;
    byte[] garbage = wellFormed("secret-kek", 8);
    garbage[4] = 9; // version 9

    String authentication = thrownText(() -> Envelope.open(golden, wrongKey, CONTEXT));
    String notAnEnvelope = thrownText(() -> Envelope.open(garbage, DATA_KEY, CONTEXT));
    String badSize = thrownText(() -> Envelope.open(golden, new byte[3], CONTEXT));

    for (String text : new String[] {authentication, notAnEnvelope, badSize}) {
      assertThat(text)
          .doesNotContain("kek-2026")
          .doesNotContain("secret-kek")
          .doesNotContain("landing")
          .doesNotContain("hello")
          .doesNotContain("00010203");
    }
    assertThat(authentication)
        .startsWith("dev.regpack.ports.crypto.Envelope$AuthenticationException");
  }

  private static String thrownText(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException e) {
      assertThat(e.getCause()).isNull();
      assertThat(e.getSuppressed()).isEmpty();
      return e.toString();
    }
    throw new AssertionError("expected an exception");
  }
}
