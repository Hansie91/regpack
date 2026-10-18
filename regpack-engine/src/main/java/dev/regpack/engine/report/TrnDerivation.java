package dev.regpack.engine.report;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.Leg;
import dev.regpack.model.Regime;
import dev.regpack.ports.security.KeyRef;
import dev.regpack.ports.security.Kms;
import java.util.Objects;

/**
 * The TRN policy {@code derive} (spec 10): the HMAC-SHA256 of the regime, the event identity and
 * the leg under the {@code TRN_HMAC} key, written in RFC 4648 base32 (upper case, no padding) and
 * cut to the requested length.
 *
 * <p>The regime is part of the message for domain separation (OI-10): the TRN scope is the
 * executing LEI across regimes, and the spec formula has no regime. This class holds no regime
 * logic. It neither stores nor re-derives: a TRN is assigned once at the first report version and
 * the caller reads the stored one first (the rule of {@code ReportLedger}).
 */
public final class TrnDerivation {
  /** Number of base32 characters of a derived TRN (160 bits). */
  public static final int DEFAULT_LENGTH = 32;

  private static final int MIN_LENGTH = 1;
  private static final int MAX_LENGTH = 52;
  private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

  /** A derived TRN together with the id of the key that produced it. */
  public record Derived(String trn, String keyId) {
    public Derived {
      Objects.requireNonNull(trn, "trn");
      Objects.requireNonNull(keyId, "keyId");
    }
  }

  private TrnDerivation() {}

  /**
   * Derives the TRN of one leg of one event.
   *
   * @param length number of base32 characters, 1 to 52
   * @throws IllegalArgumentException if {@code length} is outside 1..52
   * @throws dev.regpack.ports.security.KmsAccessDeniedException if the key may not be used for the
   *     MAC (purpose or identity); the {@link Kms} port enforces it
   */
  public static Derived derive(
      Kms kms, KeyRef key, Regime regime, EventIdentity identity, Leg leg, int length) {
    Objects.requireNonNull(kms, "kms");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(regime, "regime");
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(leg, "leg");
    if (length < MIN_LENGTH || length > MAX_LENGTH) {
      throw new IllegalArgumentException("TRN length must be 1 to 52: " + length);
    }
    byte[] message = (regime.id() + "\n" + identity.value() + "\n" + leg.name()).getBytes(UTF_8);
    String encoded = base32(kms.hmac(key, message));
    if (encoded.length() < length) {
      throw new IllegalStateException("the MAC is too short for a TRN of length " + length);
    }
    return new Derived(encoded.substring(0, length), key.keyId());
  }

  /** RFC 4648 base32, upper case, no padding. */
  private static String base32(byte[] bytes) {
    StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
    int buffer = 0;
    int bits = 0;
    for (byte b : bytes) {
      buffer = (buffer << 8) | (b & 0xFF);
      bits += 8;
      while (bits >= 5) {
        out.append(ALPHABET[(buffer >> (bits - 5)) & 31]);
        bits -= 5;
      }
      buffer &= (1 << bits) - 1;
    }
    if (bits > 0) {
      out.append(ALPHABET[(buffer << (5 - bits)) & 31]);
    }
    return out.toString();
  }
}
