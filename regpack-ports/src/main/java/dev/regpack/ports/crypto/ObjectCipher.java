package dev.regpack.ports.crypto;

import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.security.Entropy;
import dev.regpack.ports.security.KeyRef;
import dev.regpack.ports.security.Kms;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

/**
 * Seals and opens one evidence object (D-K2): a fresh random 256-bit data key per object, wrapped
 * by the KMS under the envelope KEK that the {@link KekSelector} names for the write instant, and
 * kept inside the envelope. The context is the evidence key path, so an envelope moved to another
 * key fails; the wrap AAD is {@code regpack/dek/v1|OBJECT|<kek id>|<key path>}, so a wrapped key
 * moved to another KEK or path fails at the KMS.
 *
 * <p>{@link #seal} draws the data key first and the nonce second from the {@link Entropy} (the
 * order is pinned by the known-answer vector V6) and then wraps the key. The data key array is
 * zeroed after use, on every path; this is best effort, because the copies inside the JCA and on
 * the heap are out of reach, and no record relies on it. Nothing is cached between calls: {@link
 * #open} asks the KMS to unwrap every time.
 *
 * <p>Failures are fixed texts without a cause: a KMS failure is a {@link KeyUnavailableException},
 * a destroyed KEK a {@link DataShreddedException}, and a wrapped key or tag that does not verify (a
 * missing KEK included, which the KMS port reports as it reports a tampered key) an {@link
 * Envelope.AuthenticationException}. No message holds a key, a KEK id or a path.
 *
 * <p>Thread-safe when the {@link Kms}, the {@link KekSelector}, the {@link Entropy} and the {@link
 * Clock} are.
 */
public final class ObjectCipher {
  private final Kms kms;
  private final KekSelector selector;
  private final Entropy entropy;
  private final Clock clock;

  public ObjectCipher(Kms kms, KekSelector selector, Entropy entropy, Clock clock) {
    this.kms = Objects.requireNonNull(kms, "kms");
    this.selector = Objects.requireNonNull(selector, "selector");
    this.entropy = Objects.requireNonNull(entropy, "entropy");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Seals {@code plaintext} for the evidence object at {@code key}. A RuntimeException of the
   * Entropy or the Clock passes through unchanged: it is a fault of the host, not of the key
   * service; nothing is wrapped and the drawn key is zeroed.
   *
   * @return an envelope of kind {@code OBJECT}
   * @throws KeyUnavailableException when the KEK cannot be chosen or the KMS cannot wrap the key
   */
  public byte[] seal(EvidenceKey key, byte[] plaintext) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(plaintext, "plaintext");
    Instant at = clock.now();
    KeyRef kek = DataKeys.select(selector, at);
    byte[] wrapAad = Envelope.wrapAad(Envelope.Kind.OBJECT, kek.keyId(), key.path());
    byte[] dataKey = new byte[AesGcm.KEY_BYTES];
    try {
      entropy.fill(dataKey);
      byte[] nonce = new byte[AesGcm.NONCE_BYTES];
      entropy.fill(nonce);
      byte[] wrapped = DataKeys.wrap(kms, kek, dataKey, wrapAad);
      return Envelope.seal(
          new Envelope.Header(Envelope.Kind.OBJECT, kek.keyId(), wrapped),
          dataKey,
          nonce,
          context(key),
          plaintext);
    } finally {
      DataKeys.zero(dataKey);
    }
  }

  /**
   * Opens an envelope sealed for the evidence object at {@code key}, with the KEK that its header
   * names.
   *
   * @throws IllegalArgumentException with the message {@code not an envelope} when the bytes are
   *     not an envelope
   * @throws Envelope.AuthenticationException when the envelope is of another kind, was moved to
   *     another key, or was changed, or when the KMS rejects the wrapped key
   * @throws DataShreddedException when the KEK was destroyed
   * @throws KeyUnavailableException when the KMS cannot serve the request
   */
  public byte[] open(EvidenceKey key, byte[] envelope) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(envelope, "envelope");
    Envelope.Header header = Envelope.header(envelope);
    if (header.kind() != Envelope.Kind.OBJECT) {
      throw new Envelope.AuthenticationException();
    }
    byte[] dataKey =
        DataKeys.unwrap(
            kms, header, Envelope.wrapAad(Envelope.Kind.OBJECT, header.kekId(), key.path()));
    try {
      return Envelope.open(envelope, dataKey, context(key));
    } finally {
      DataKeys.zero(dataKey);
    }
  }

  private static byte[] context(EvidenceKey key) {
    return key.path().getBytes(StandardCharsets.UTF_8);
  }
}
