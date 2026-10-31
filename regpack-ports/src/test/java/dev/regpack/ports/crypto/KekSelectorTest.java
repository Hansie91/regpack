package dev.regpack.ports.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.security.KeyPurpose;
import dev.regpack.ports.security.KeyRef;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class KekSelectorTest {
  @Test
  void perUtcYearNamesTheUtcYear() {
    KekSelector selector = KekSelector.perUtcYear("kek");
    assertThat(selector.kekAt(Instant.parse("2026-12-31T23:59:59.999999Z")))
        .isEqualTo(new KeyRef("kek-2026", KeyPurpose.ENVELOPE_KEK));
    assertThat(selector.kekAt(Instant.parse("2027-01-01T00:00:00Z")))
        .isEqualTo(new KeyRef("kek-2027", KeyPurpose.ENVELOPE_KEK));
    assertThat(selector.kekAt(Instant.parse("2026-01-01T00:00:00Z")).keyId()).isEqualTo("kek-2026");
    assertThat(KekSelector.perUtcYear("a.b_c-D9").kekAt(Instant.parse("2026-06-01T00:00:00Z")))
        .isEqualTo(new KeyRef("a.b_c-D9-2026", KeyPurpose.ENVELOPE_KEK));
  }

  @Test
  void perUtcYearIsUtcInAnyDefaultZone() {
    TimeZone saved = TimeZone.getDefault();
    try {
      for (String zone : List.of("Pacific/Auckland", "America/Los_Angeles", "UTC")) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        KekSelector selector = KekSelector.perUtcYear("kek");
        assertThat(selector.kekAt(Instant.parse("2026-12-31T23:59:59.999999Z")).keyId())
            .as(zone)
            .isEqualTo("kek-2026");
        assertThat(selector.kekAt(Instant.parse("2027-01-01T00:00:00Z")).keyId())
            .as(zone)
            .isEqualTo("kek-2027");
      }
    } finally {
      TimeZone.setDefault(saved);
    }
  }

  @Test
  void singleReturnsItsKekAlways() {
    KeyRef kek = new KeyRef("kek-test", KeyPurpose.ENVELOPE_KEK);
    KekSelector selector = KekSelector.single(kek);
    assertThat(selector.kekAt(Instant.parse("2026-12-31T23:59:59Z"))).isEqualTo(kek);
    assertThat(selector.kekAt(Instant.parse("2031-01-01T00:00:00Z"))).isEqualTo(kek);
    assertThat(selector.kekAt(Instant.EPOCH)).isEqualTo(kek);
    assertThat(
            KekSelector.single(new KeyRef("k".repeat(100), KeyPurpose.ENVELOPE_KEK))
                .kekAt(Instant.EPOCH)
                .keyId())
        .hasSize(100);
  }

  @Test
  void badPrefixesAndPurposesAreRefused() {
    assertThatThrownBy(() -> KekSelector.perUtcYear(""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> KekSelector.perUtcYear("a".repeat(91)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> KekSelector.perUtcYear("has space"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> KekSelector.perUtcYear("pipe|"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> KekSelector.perUtcYear("kék"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            KekSelector.perUtcYear("a".repeat(90))
                .kekAt(Instant.parse("2026-06-01T00:00:00Z"))
                .keyId())
        .hasSize(95);

    assertThatThrownBy(() -> KekSelector.single(new KeyRef("kek", KeyPurpose.TRN_HMAC)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> KekSelector.single(new KeyRef("kek", KeyPurpose.INDEX_HMAC)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> KekSelector.single(new KeyRef("", KeyPurpose.ENVELOPE_KEK)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> KekSelector.single(new KeyRef("k".repeat(101), KeyPurpose.ENVELOPE_KEK)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> KekSelector.single(new KeyRef("a b", KeyPurpose.ENVELOPE_KEK)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aYearWithoutFourDigitsIsRefused() {
    KekSelector selector = KekSelector.perUtcYear("kek");
    assertThatThrownBy(() -> selector.kekAt(Instant.parse("0999-12-31T23:59:59Z")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> selector.kekAt(Instant.parse("+10000-01-01T00:00:00Z")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theFirstAndTheLastFourDigitYearAreAccepted() {
    KekSelector selector = KekSelector.perUtcYear("kek");

    assertThat(selector.kekAt(Instant.parse("1000-01-01T00:00:00Z")).keyId()).isEqualTo("kek-1000");
    assertThat(selector.kekAt(Instant.parse("9999-12-31T23:59:59Z")).keyId()).isEqualTo("kek-9999");
  }

  @Test
  void messagesNameNoPrefixOrKekId() {
    assertThatThrownBy(() -> KekSelector.perUtcYear("secret prefix"))
        .hasMessageNotContaining("secret");
    assertThatThrownBy(() -> KekSelector.single(new KeyRef("secret id", KeyPurpose.ENVELOPE_KEK)))
        .hasMessageNotContaining("secret");
  }
}
