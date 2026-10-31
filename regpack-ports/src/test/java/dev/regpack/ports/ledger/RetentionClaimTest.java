package dev.regpack.ports.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Regime;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.ledger.RetentionLedger.AnchorEvent;
import dev.regpack.ports.ledger.RetentionLedger.RetentionClaim;
import dev.regpack.ports.ledger.RetentionLedger.RetentionRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * P3c1 T8, KF16 and Convention 3: the record {@link RetentionClaim} is where the retain-until range
 * and the class alphabet are checked, so that every adapter inherits the rules and none repeats
 * them. The messages name the rule and hold no value. (The ledger contract tests that refuse the
 * same values build the claim inside the call, so they pin the same record; the adapter-level proof
 * of the stored range is the round trip at both ends of it.)
 */
class RetentionClaimTest {
  private static final EvidenceKey KEY = new EvidenceKey("sealed/ab/x");
  private static final Regime REGIME = new Regime("mifir.eu.rts22");
  private static final Instant FIRST = Instant.parse("0000-01-01T00:00:00Z");
  private static final Instant LAST = Instant.parse("9999-12-31T23:59:59.999999Z");

  private static RetentionClaim claimWithDate(Instant date) {
    return new RetentionClaim(KEY, REGIME, AnchorEvent.OBJECT_WRITTEN, Optional.of(date), "sealed");
  }

  private static RetentionClaim claimOfClass(String retentionClass) {
    return new RetentionClaim(
        KEY, REGIME, AnchorEvent.OBJECT_WRITTEN, Optional.empty(), retentionClass);
  }

  @Test
  void theFirstAndTheLastStorableInstantAndAnOpenAnchorAreAccepted() {
    assertThat(claimWithDate(FIRST).retainUntil()).contains(FIRST);
    assertThat(claimWithDate(LAST).retainUntil()).contains(LAST);
    assertThat(claimOfClass("sealed").retainUntil()).isEmpty();
  }

  @Test
  void aRetainUntilNoStoreHoldsIsRefusedWithAMessageThatHoldsNoValue() {
    List<Instant> unstorable =
        List.of(
            Instant.parse("2030-01-01T00:00:00.000000001Z"),
            Instant.parse("2030-01-01T00:00:00.123456789Z"),
            Instant.parse("2030-01-01T00:00:00.000000999Z"),
            Instant.parse("+10000-01-01T00:00:00Z"),
            Instant.parse("-0001-12-31T23:59:59Z"),
            Instant.parse("-0001-12-31T23:59:59.999999Z"),
            Instant.MAX,
            Instant.MIN);
    for (Instant bad : unstorable) {
      assertThatThrownBy(() -> claimWithDate(bad))
          .as("retain-until %s", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .satisfies(
              e ->
                  assertThat(e.getMessage())
                      .isNotBlank()
                      .doesNotContain(bad.toString())
                      .doesNotContain(String.valueOf(bad.getEpochSecond())));
    }
  }

  @Test
  void aClassNameOutsideTheAlphabetIsRefusedWithAMessageThatHoldsNoValue() {
    // every value starts with 'secret', so a fixed text cannot hold it by accident
    List<String> invalid =
        List.of(
            "",
            "secret class",
            "secret/class",
            "secret,class",
            "secret=class",
            "secret" + (char) 0x00E9 + "class",
            "secret" + (char) 10 + "class",
            "x".repeat(101));
    for (String bad : invalid) {
      assertThatThrownBy(() -> claimOfClass(bad))
          .as("class with code points %s", bad.chars().boxed().toList())
          .isInstanceOf(IllegalArgumentException.class)
          .satisfies(
              e -> {
                assertThat(e.getMessage()).isNotBlank();
                if (!bad.isEmpty()) {
                  assertThat(e.getMessage()).doesNotContain(bad);
                }
              });
    }
    for (String valid : List.of("sealed", "A-Z_a.z-09", "x".repeat(100), "-", ".", "_")) {
      assertThat(claimOfClass(valid).retentionClass()).isEqualTo(valid);
    }
  }

  @Test
  void aMissingPartIsANullPointerException() {
    Optional<Instant> date = Optional.of(LAST);
    assertThatThrownBy(
            () -> new RetentionClaim(null, REGIME, AnchorEvent.OBJECT_WRITTEN, date, "sealed"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new RetentionClaim(KEY, null, AnchorEvent.OBJECT_WRITTEN, date, "sealed"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RetentionClaim(KEY, REGIME, null, date, "sealed"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new RetentionClaim(KEY, REGIME, AnchorEvent.OBJECT_WRITTEN, null, "sealed"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new RetentionClaim(KEY, REGIME, AnchorEvent.OBJECT_WRITTEN, date, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aRowNeedsItsClaimAndItsTime() {
    RetentionClaim claim = claimWithDate(LAST);
    assertThatThrownBy(() -> new RetentionRow(null, false, FIRST))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RetentionRow(claim, false, null))
        .isInstanceOf(NullPointerException.class);
    assertThat(new RetentionRow(claim, true, FIRST).legalHold()).isTrue();
  }

  @Test
  void requireStorableAcceptsTheBoundsReturnsTheInstantAndNamesTheFieldInItsRefusals() {
    assertThat(RetentionClaim.requireStorable("asOf", FIRST)).isSameAs(FIRST);
    assertThat(RetentionClaim.requireStorable("asOf", LAST)).isSameAs(LAST);

    Instant precise = Instant.parse("2030-01-01T00:00:00.000000001Z");
    assertThatThrownBy(() -> RetentionClaim.requireStorable("asOf", precise))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("asOf must have whole microseconds");
    for (Instant outside :
        List.of(
            Instant.parse("+10000-01-01T00:00:00Z"),
            Instant.parse("-0001-12-31T23:59:59.999999Z"),
            Instant.MIN)) {
      assertThatThrownBy(() -> RetentionClaim.requireStorable("asOf", outside))
          .as("asOf %s", outside)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("asOf year must be from 0000 to 9999");
    }
    // the same rule, with the field of the record, is the text of the record
    assertThatThrownBy(() -> claimWithDate(precise))
        .hasMessage("retainUntil must have whole microseconds");
    assertThatThrownBy(() -> claimWithDate(Instant.MIN))
        .hasMessage("retainUntil year must be from 0000 to 9999");

    assertThatThrownBy(() -> RetentionClaim.requireStorable(null, FIRST))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> RetentionClaim.requireStorable("asOf", null))
        .isInstanceOf(NullPointerException.class);
  }
}
