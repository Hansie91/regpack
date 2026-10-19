package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Citation;
import dev.regpack.model.CitationRole;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionCodec;
import dev.regpack.model.decision.PathStep;
import dev.regpack.model.json.Json;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/**
 * A13, D-S6: the storage form of a decision body is a version byte and the deflated canonical JSON.
 * The test rebuilds the body from the constructors (the model test's builder is private).
 */
class DecisionBodyCodecTest {
  /** SHA-256 of the UTF-8 canonical JSON that version 1 compresses for {@link #fixedBody()}. */
  private static final String GOLDEN_CANONICAL_SHA256 =
      "fdda5b9e973526cb17ef4a59a103fcb8ecd45789edd96e1ccd560a59b08ae075";

  /** A stored value of version 1, produced once; version 1 must decode it for ever. */
  private static final String GOLDEN_STORED_HEX =
      "0178bb9723ccb48d945b4f43210c807fd18ea58502be79cff116b369321f7c80"
          + "02c9e2e5616ec6c4f9df2573996e67733e41086d4afb7d7cac20aaa007fe16fc"
          + "3ecda7a090b4e12ea2a1de8ff5f4b1ae6fbb610de30972875897c9084aa25898"
          + "b58a29424630102d7b4289113303e900290511957cccc161e0188c2d56ca0f81"
          + "87078376b04adff3a88cc61bc19d17b3a776f2bbc8b005e21cacf518908b0693"
          + "31642f75eb25788ab1d66d59721008aa88a1e448238b4db660f2646dd69b2558"
          + "69bf6ad86842b54902a33d01a88bf3dbb63d3b72f7aaaf7036bc39e8cf4e863d"
          + "b555919ae85d9ea6a992d3b1453c0b2713b34be082c26ca391242a22d852bb8e"
          + "9ac05b2d8e1dd41739afd89a6cb924ef51107ed9b6f06a69da8f602f40ff9782"
          + "fe70623e9d66acd6dde0dd31b81ee3b7fbb408a1ae5757ed69db6fae819acacf"
          + "e5ddf1fc8f589af61d97a7cd78f28ad8f16e99f6e1f30b473c0f86";

  private static DecisionBody body(
      Outcome outcome, int steps, List<Leg> legs, Map<String, String> attributes) {
    List<PathStep> path = new ArrayList<>();
    List<String> hit = new ArrayList<>();
    for (int i = 1; i <= steps; i++) {
      path.add(new PathStep("mifir.r" + i, i % 3 == 0, 3L * i));
      if (i % 3 == 0) {
        hit.add("mifir.r" + i);
      }
    }
    return new DecisionBody(
        EventIdentity.of("5493001KJTIIGC8Y1R12", "XPAR", "EX-1"),
        new Regime("mifir.eu.rts22"),
        Sha256.ofUtf8("context"),
        Instant.parse("2026-10-09T09:30:00.123456Z"),
        Instant.parse("2026-10-09T09:30:01.654321Z"),
        outcome,
        List.of("circuit=open", "excluded"),
        new ReasonCode("MIFIR.N03.EXCLUDED"),
        "n03",
        hit,
        path,
        List.of(
            new Citation("mifir", "art26", CitationRole.BASIS, Optional.of(Sha256.ofUtf8("q"))),
            new Citation("mifir", "art26/1", CitationRole.EXCEPTION, Optional.empty())),
        legs,
        Sha256.ofUtf8("input"),
        attributes);
  }

  private static DecisionBody fixedBody() {
    return body(Outcome.REPORT, 3, List.of(Leg.MARKET, Leg.CLIENT), Map.of("k", "v", "a", "b"));
  }

  private static String canonicalJsonSha256(DecisionBody body) {
    return Sha256.ofUtf8(Json.canonical(DecisionCodec.bodyToMap(body, true))).hex();
  }

  @Test
  void roundTripsEveryOutcomeWithAttributesAndCitations() {
    for (Outcome outcome : Outcome.values()) {
      for (Map<String, String> attributes : List.of(Map.<String, String>of(), Map.of("x", "y"))) {
        DecisionBody original = body(outcome, 5, List.of(Leg.MARKET), attributes);
        byte[] stored = DecisionBodyCodec.encode(original);
        DecisionBody decoded = DecisionBodyCodec.decode(stored);
        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.replayDigest()).isEqualTo(original.replayDigest());
        assertThat(decoded.citations()).hasSize(2);
        assertThat(stored[0]).isEqualTo((byte) DecisionBodyCodec.VERSION);
      }
    }
    DecisionBody noSteps = body(Outcome.DO_NOT_REPORT, 0, List.of(), Map.of());
    assertThat(DecisionBodyCodec.decode(DecisionBodyCodec.encode(noSteps))).isEqualTo(noSteps);
  }

  @Test
  void goldenCanonicalJsonOfTheFixedBodyIsPinned() {
    // any change of this digest or of the version byte needs VERSION 2: stored rows are never
    // rewritten
    assertThat(DecisionBodyCodec.VERSION).isEqualTo(1);
    assertThat(canonicalJsonSha256(fixedBody())).isEqualTo(GOLDEN_CANONICAL_SHA256);
    assertThat(DecisionBodyCodec.encode(fixedBody())[0]).isEqualTo((byte) 0x01);
  }

  @Test
  void goldenStoredBlobStillDecodes() {
    byte[] stored = HexFormat.of().parseHex(GOLDEN_STORED_HEX);
    DecisionBody decoded = DecisionBodyCodec.decode(stored);
    assertThat(decoded).isEqualTo(fixedBody());
    assertThat(decoded.replayDigest()).isEqualTo(fixedBody().replayDigest());
  }

  @Test
  void unknownVersionTruncationAndTrailingBytesAreRejected() {
    byte[] stored = DecisionBodyCodec.encode(fixedBody());

    byte[] otherVersion = stored.clone();
    otherVersion[0] = 2;
    assertThatThrownBy(() -> DecisionBodyCodec.decode(otherVersion))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] versionZero = stored.clone();
    versionZero[0] = 0;
    assertThatThrownBy(() -> DecisionBodyCodec.decode(versionZero))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> DecisionBodyCodec.decode(new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DecisionBodyCodec.decode(new byte[] {1}))
        .isInstanceOf(IllegalArgumentException.class);
    for (int cut : new int[] {2, 3, stored.length / 2, stored.length - 1}) {
      byte[] truncated = Arrays.copyOf(stored, cut);
      assertThatThrownBy(() -> DecisionBodyCodec.decode(truncated))
          .as("truncated to %d of %d bytes", cut, stored.length)
          .isInstanceOf(IllegalArgumentException.class);
    }

    byte[] trailing = Arrays.copyOf(stored, stored.length + 1);
    assertThatThrownBy(() -> DecisionBodyCodec.decode(trailing))
        .isInstanceOf(IllegalArgumentException.class);

    byte[] corrupt = stored.clone();
    corrupt[corrupt.length - 1] ^= 0x55;
    assertThatThrownBy(() -> DecisionBodyCodec.decode(corrupt))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> DecisionBodyCodec.decode(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void validCompressionOfSomethingThatIsNotABodyIsRejected() {
    // the codec's own deflate form around text that is not a decision body: IAE, not JsonException
    assertThatThrownBy(() -> DecisionBodyCodec.decode(storedFormOf("{not json")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DecisionBodyCodec.decode(storedFormOf("[]")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DecisionBodyCodec.decode(storedFormOf("{\"outcome\":\"REPORT\"}")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aPlainZlibStreamWithoutTheFixedDictionaryIsRejected() {
    // the canonical JSON of a real body, framed with version 1 but deflated without the preset
    // dictionary: without the dictionary check this would decode to the body
    byte[] json =
        Json.canonical(DecisionCodec.bodyToMap(fixedBody(), true)).getBytes(StandardCharsets.UTF_8);
    Deflater deflater = new Deflater(6);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(DecisionBodyCodec.VERSION);
    try {
      deflater.setInput(json);
      deflater.finish();
      byte[] buffer = new byte[1024];
      while (!deflater.finished()) {
        int n = deflater.deflate(buffer);
        out.write(buffer, 0, n);
      }
    } finally {
      deflater.end();
    }
    byte[] plain = out.toByteArray();
    assertThatThrownBy(() -> DecisionBodyCodec.decode(plain))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not use the dictionary");
  }

  @Test
  void aStreamThatInflatesBeyondSixteenMiBIsRejectedBeforeItIsParsed() {
    // 17 MiB of zero bytes deflate to a few tens of KiB: a decompression bomb in miniature. Without
    // the limit this would be refused only later, as "not JSON"; the message pins the limit.
    byte[] bomb = DecisionBodyCodec.compress(new byte[17 * 1024 * 1024]);
    assertThat(bomb.length).isLessThan(1024 * 1024);
    assertThatThrownBy(() -> DecisionBodyCodec.decode(bomb))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("beyond the limit");
  }

  /** Builds a version-1 stored value around arbitrary text, with the same dictionary. */
  private static byte[] storedFormOf(String text) {
    return DecisionBodyCodec.compress(text.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void sizeOfDecisionsWithThreeTwentyOneAndFortyStepsIsReported() {
    // SP-L: the row is about 291 bytes plus 2 per step in E1; this prints E2 and asserts nothing
    // about the size
    for (int steps : new int[] {3, 21, 40}) {
      DecisionBody body = body(Outcome.REPORT, steps, List.of(Leg.MARKET), Map.of());
      int stored = DecisionBodyCodec.encode(body).length;
      int canonical =
          Json.canonical(DecisionCodec.bodyToMap(body, true))
              .getBytes(StandardCharsets.UTF_8)
              .length;
      System.out.println(
          "DecisionBodyCodec v1: "
              + steps
              + " steps: "
              + stored
              + " bytes stored, "
              + canonical
              + " bytes canonical JSON");
      assertThat(stored).isPositive();
    }
  }

  @Test
  void deflateOutputIsIdenticalAcrossRuns() {
    // same-JVM stability only; the exact compressed bytes are not asserted anywhere
    byte[] first = DecisionBodyCodec.encode(fixedBody());
    byte[] second = DecisionBodyCodec.encode(fixedBody());
    assertThat(second).isEqualTo(first);
  }
}
