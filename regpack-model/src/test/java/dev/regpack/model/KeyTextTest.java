package dev.regpack.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.decision.DecisionContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** FP2: the one rule for every text that becomes a key, and the value types that apply it. */
class KeyTextTest {
  private static String chars(int... codeUnits) {
    StringBuilder text = new StringBuilder();
    for (int unit : codeUnits) {
      text.append((char) unit);
    }
    return text.toString();
  }

  private static String euro() {
    return chars(0x20AC);
  }

  @Test
  void aPlainTextPassesAndIsReturnedAsItIs() {
    assertThat(KeyText.require("f", "LEI1|XPAR ok-1")).isEqualTo("LEI1|XPAR ok-1");
    assertThat(KeyText.require("f", "")).isEmpty();
    // a well-formed supplementary character is a pair of surrogates, not an unpaired one
    String pair = new String(Character.toChars(0x1F600));
    assertThat(pair).hasSize(2);
    assertThat(KeyText.require("f", "a" + pair + "b")).isEqualTo("a" + pair + "b");
  }

  @Test
  void everyControlCharacterAndDelIsRefused() {
    for (int c = 0; c < 0x20; c++) {
      String bad = "a" + chars(c) + "b";
      assertThatThrownBy(() -> KeyText.require("field", bad))
          .as("U+%04X", c)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("field must not contain a control character");
    }
    assertThatThrownBy(() -> KeyText.require("field", "a" + chars(0x7F)))
        .isInstanceOf(IllegalArgumentException.class);
    // C1 controls and the next characters are allowed: only C0 and DEL are refused
    assertThat(KeyText.require("f", chars(0x80, 0xA0, 0x20, 0x7E))).hasSize(4);
  }

  @Test
  void anUnpairedSurrogateIsRefusedAnywhere() {
    String[] bad = {
      chars(0xD800),
      chars(0xDC00),
      "a" + chars(0xD800) + "b",
      "a" + chars(0xDC00) + "b",
      "end" + chars(0xD83D),
      chars(0xDE00, 0xD83D),
      chars(0xD83D, 0xD83D, 0xDE00)
    };
    for (String text : bad) {
      assertThatThrownBy(() -> KeyText.require("field", text))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("field must not contain an unpaired surrogate");
    }
  }

  @Test
  void theLengthBoundIsInclusiveAndCountsUtf16Chars() {
    assertThat(KeyText.require("f", "x".repeat(KeyText.MAX_CHARS))).hasSize(256);
    assertThatThrownBy(() -> KeyText.require("field", "x".repeat(KeyText.MAX_CHARS + 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("field must be at most 256 characters, not 257");
    assertThat(KeyText.require("f", "x".repeat(10), 10)).hasSize(10);
    assertThatThrownBy(() -> KeyText.require("f", "x".repeat(11), 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theBoundsKeepTheWidestIndexRowsBelow2000Bytes() {
    // UTF-8 takes at most three bytes for a UTF-16 char (a surrogate pair: four for two chars)
    int perChar = 3;
    int fixedOverhead = 2 + 4 + 4 + 33 + 4 + 8 + 4; // ids, digest, day, tuple and varlena headers
    // decision_idem_key: regime id, event identity, context id, input digest, day
    int idemKey = KeyText.MAX_EVENT_IDENTITY_CHARS * perChar + fixedOverhead;
    // ingest_pk: source id (smallint), record id, version
    int ingestPk = KeyText.MAX_CHARS * perChar + fixedOverhead;
    // ingest_event_version_key: event identity, source id, version
    int eventVersion = KeyText.MAX_EVENT_IDENTITY_CHARS * perChar + fixedOverhead;
    // endpoint_key: four texts
    int endpointKey = 4 * KeyText.MAX_ENDPOINT_PART_CHARS * perChar + fixedOverhead;
    // delivery_key: endpoint id, epoch, business key, version
    int deliveryKey = KeyText.MAX_BUSINESS_KEY_CHARS * perChar + fixedOverhead;
    // trn_assignment_lei_trn_key: executing LEI and a TRN of at most 52 chars
    int trnKey = (KeyText.MAX_CHARS + 52) * perChar + fixedOverhead;
    for (int row : new int[] {idemKey, ingestPk, eventVersion, endpointKey, deliveryKey, trnKey}) {
      assertThat(row).isLessThan(2000);
    }
    // the same arithmetic on real UTF-8: the longest accepted event identity of euro signs
    String longest = euro().repeat(KeyText.MAX_EVENT_IDENTITY_CHARS);
    assertThat(longest.getBytes(StandardCharsets.UTF_8)).hasSize(1536);
  }

  @Test
  void aNullIsANullPointerExceptionNamingTheField() {
    assertThatThrownBy(() -> KeyText.require("someField", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("someField");
  }

  @Test
  void theMessagesNeverEchoTheValue() {
    String secret = "SECRET" + chars(0) + "VALUE";
    assertThatThrownBy(() -> KeyText.require("f", secret))
        .message()
        .doesNotContain("SECRET")
        .doesNotContain("VALUE");
    String longSecret = "SECRET".repeat(100);
    assertThatThrownBy(() -> KeyText.require("f", longSecret)).message().doesNotContain("SECRET");
  }

  @Test
  void eventIdentityRefusesHostileTextsAndBoundsTheWholeValue() {
    for (String bad :
        new String[] {"a" + chars(0) + "b", "a" + chars(0xD800) + "b", "x".repeat(513)}) {
      assertThatThrownBy(() -> EventIdentity.of("LEI1", "XPAR", bad))
          .isInstanceOf(IllegalArgumentException.class)
          .message()
          .doesNotContain(bad);
      assertThatThrownBy(() -> EventIdentity.of(bad))
          .isInstanceOf(IllegalArgumentException.class)
          .message()
          .doesNotContain(bad);
      assertThatThrownBy(() -> new EventIdentity(bad))
          .isInstanceOf(IllegalArgumentException.class)
          .message()
          .doesNotContain(bad);
    }
    // the whole value is bounded: 512 chars including the separators
    assertThat(EventIdentity.of("LEI1", "XPAR", "x".repeat(502)).value()).hasSize(512);
    assertThatThrownBy(() -> EventIdentity.of("LEI1", "XPAR", "x".repeat(503)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EventIdentity("a|" + "x".repeat(511)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ingestKeyRefusesHostileTextsInBothParts() {
    for (String bad :
        new String[] {
          "a" + chars(0) + "b", "a" + chars(9) + "b", "a" + chars(0xDC00), "x".repeat(257)
        }) {
      assertThatThrownBy(() -> new IngestKey("srcA", bad, 1))
          .isInstanceOf(IllegalArgumentException.class)
          .message()
          .doesNotContain(bad);
      assertThatThrownBy(() -> new IngestKey(bad, "r1", 1))
          .isInstanceOf(IllegalArgumentException.class)
          .message()
          .doesNotContain(bad);
    }
    assertThat(new IngestKey("s".repeat(256), "r".repeat(256), 1).sourceRecordId()).hasSize(256);
    // blank is still the blank error
    assertThatThrownBy(() -> new IngestKey("srcA", " ", 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be blank");
  }

  @Test
  void regimeBatchIdAndCheckpointStreamAreBounded() {
    assertThat(new Regime("a".repeat(128)).id()).hasSize(128);
    assertThatThrownBy(() -> new Regime("a".repeat(129)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new BatchId("b".repeat(256)).value()).hasSize(256);
    for (String bad : new String[] {"a" + chars(0), "a" + chars(0xD800), "x".repeat(257)}) {
      assertThatThrownBy(() -> new BatchId(bad)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new Checkpoint(bad, new byte[0]))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(new Checkpoint("trades", new byte[] {1}).stream()).isEqualTo("trades");
  }

  @Test
  void aDecisionContextRefusesAHostileEngineBuild() {
    for (String bad : new String[] {"b" + chars(0), "b" + chars(0xD800), "x".repeat(257)}) {
      assertThatThrownBy(() -> context(bad))
          .isInstanceOf(IllegalArgumentException.class)
          .message()
          .doesNotContain(bad);
    }
    assertThat(context("1.0.0+lib1").engineBuild()).isEqualTo("1.0.0+lib1");
  }

  private static DecisionContext context(String engineBuild) {
    return new DecisionContext(
        Sha256.ofUtf8("pack"),
        Optional.empty(),
        List.of(),
        List.of(),
        List.of(),
        "fp-1",
        "map-1",
        engineBuild,
        "interpreter");
  }
}
