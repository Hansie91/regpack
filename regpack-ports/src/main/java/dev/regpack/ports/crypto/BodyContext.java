package dev.regpack.ports.crypto;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.Leg;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.ports.ledger.ReportLedger;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * What a report body is bound to: the report subject (regime, event identity, leg) and the logical
 * hash of the version. {@link BodyCipher} puts {@link #bytes()} into the authenticated data, so a
 * body copied to another subject or version fails to open.
 *
 * <p>{@code toString} shows no regime, event identity or hash.
 */
public record BodyContext(Regime regime, EventIdentity eventIdentity, Leg leg, Sha256 logicalHash) {
  public BodyContext {
    Objects.requireNonNull(regime, "regime");
    Objects.requireNonNull(eventIdentity, "eventIdentity");
    Objects.requireNonNull(leg, "leg");
    Objects.requireNonNull(logicalHash, "logicalHash");
  }

  /** The context of the body of a version request. */
  public static BodyContext of(ReportLedger.NewVersion version) {
    Objects.requireNonNull(version, "version");
    return new BodyContext(
        version.subject().regime(),
        version.subject().eventIdentity(),
        version.subject().leg(),
        version.logicalHash());
  }

  /**
   * {@code regpack/body/v1|<regime>|<event identity>|<leg>|<logical hash hex>} in UTF-8. The regime
   * has no {@code |}, the leg is a fixed name and the hash 64 hex digits, so the fields can only be
   * read one way.
   */
  public byte[] bytes() {
    return ("regpack/body/v1|"
            + regime.id()
            + "|"
            + eventIdentity.value()
            + "|"
            + leg.name()
            + "|"
            + logicalHash.hex())
        .getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public String toString() {
    return "BodyContext[hidden]";
  }
}
