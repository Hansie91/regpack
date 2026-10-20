package dev.regpack.ports.evidence.seal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Sha256;
import dev.regpack.ports.evidence.seal.Sealing.DeliveryIntentPayload;
import dev.regpack.ports.evidence.seal.Sealing.SealOutcome;
import dev.regpack.ports.evidence.seal.Sealing.SealRequest;
import dev.regpack.ports.evidence.seal.Sealing.SealStatus;
import dev.regpack.ports.ledger.DeliveryLedger.AttemptId;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import dev.regpack.ports.ledger.LeaseLedger.LeaseToken;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The value types of the {@link Sealing} port: copies, non-null components, the outcome rule. */
class SealingRecordsTest {
  private static final EndpointKey ENDPOINT = new EndpointKey("TEST", "ARM", "LEI1", "NCA1");
  private static final LeaseToken TOKEN = new LeaseToken(0, 1);
  private static final DeliveryId DELIVERY = new DeliveryId(7);
  private static final byte[] BYTES = "<file/>".getBytes(StandardCharsets.UTF_8);

  private static DeliveryIntentPayload payload(List<Sha256> contextIds) {
    return new DeliveryIntentPayload(
        DELIVERY,
        "BK-1",
        "TRN1",
        1,
        "NEWT",
        Sha256.ofUtf8("logical"),
        Sha256.of(BYTES),
        "file.xml",
        contextIds);
  }

  private static SealRequest request(byte[] bytes) {
    return new SealRequest(
        ENDPOINT,
        TOKEN,
        DELIVERY,
        "file.xml",
        bytes,
        "application/xml",
        payload(List.of(Sha256.ofUtf8("c1"))));
  }

  @Test
  void aSealRequestCopiesItsBytesAndNeverHandsOutItsOwnArray() {
    byte[] mine = BYTES.clone();
    SealRequest request = request(mine);
    mine[0] = 'X';
    assertThat(request.bytes()).isEqualTo(BYTES);
    byte[] handedOut = request.bytes();
    handedOut[0] = 'Y';
    assertThat(request.bytes()).isEqualTo(BYTES);
  }

  @Test
  void aSealRequestRefusesANullComponent() {
    DeliveryIntentPayload intent = payload(List.of());
    assertThatThrownBy(
            () ->
                new SealRequest(
                    null, TOKEN, DELIVERY, "file.xml", BYTES, "application/xml", intent))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new SealRequest(
                    ENDPOINT, null, DELIVERY, "file.xml", BYTES, "application/xml", intent))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new SealRequest(
                    ENDPOINT, TOKEN, null, "file.xml", BYTES, "application/xml", intent))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new SealRequest(ENDPOINT, TOKEN, DELIVERY, null, BYTES, "application/xml", intent))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new SealRequest(
                    ENDPOINT, TOKEN, DELIVERY, "file.xml", null, "application/xml", intent))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new SealRequest(ENDPOINT, TOKEN, DELIVERY, "file.xml", BYTES, null, intent))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new SealRequest(
                    ENDPOINT, TOKEN, DELIVERY, "file.xml", BYTES, "application/xml", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aSealRequestIsEqualByContentAndItsTextShowsNeitherTheBytesNorTheNames() {
    SealRequest a = request(BYTES);
    SealRequest b = request(BYTES.clone());
    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    assertThat(a).isNotEqualTo(request("<other/>".getBytes(StandardCharsets.UTF_8)));
    assertThat(a.toString())
        .contains("7")
        .contains(Integer.toString(BYTES.length))
        .doesNotContain("file.xml")
        .doesNotContain("BK-1")
        .doesNotContain("<file/>");
  }

  @Test
  void anIntentPayloadCopiesItsContextIdsAndRefusesANullComponent() {
    List<Sha256> mine = new ArrayList<>(List.of(Sha256.ofUtf8("c1")));
    DeliveryIntentPayload payload = payload(mine);
    mine.add(Sha256.ofUtf8("c2"));
    assertThat(payload.contextIds()).containsExactly(Sha256.ofUtf8("c1"));
    assertThatThrownBy(() -> payload.contextIds().add(Sha256.ofUtf8("c3")))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> payload(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new DeliveryIntentPayload(
                    DELIVERY,
                    null,
                    "TRN1",
                    1,
                    "NEWT",
                    Sha256.ofUtf8("l"),
                    Sha256.of(BYTES),
                    "file.xml",
                    List.of()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aSealOutcomeHasAnAttemptIfAndOnlyIfItIsSealed() {
    SealOutcome sealed =
        new SealOutcome(SealStatus.SEALED, Optional.of(new AttemptId(3)), "sealed");
    assertThat(sealed.attempt()).contains(new AttemptId(3));
    for (SealStatus status : SealStatus.values()) {
      if (status == SealStatus.SEALED) {
        assertThatThrownBy(() -> new SealOutcome(status, Optional.empty(), "x"))
            .isInstanceOf(IllegalArgumentException.class);
      } else {
        assertThat(new SealOutcome(status, Optional.empty(), "x").attempt()).isEmpty();
        assertThatThrownBy(() -> new SealOutcome(status, Optional.of(new AttemptId(3)), "x"))
            .isInstanceOf(IllegalArgumentException.class);
      }
    }
    assertThatThrownBy(() -> new SealOutcome(SealStatus.GATE_CLOSED, Optional.empty(), null))
        .isInstanceOf(NullPointerException.class);
  }
}
