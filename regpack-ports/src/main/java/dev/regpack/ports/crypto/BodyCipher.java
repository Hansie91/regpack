package dev.regpack.ports.crypto;

import dev.regpack.ports.Clock;
import dev.regpack.ports.security.Entropy;
import dev.regpack.ports.security.KeyRef;
import dev.regpack.ports.security.Kms;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Seals and opens report bodies (D-K2). One random 256-bit data key serves the bodies of one UTC
 * day under one KEK: it is wrapped once, and the wrapped key is embedded in every body of that day.
 * The key rotates when the UTC day or the KEK changes, and after {@link #MAX_SEALS_PER_KEY} seals
 * (NIST allows 2^32 random 96-bit nonces under one key [R: SP 800-38D 8.3]).
 *
 * <p>Each {@link #seal} reads the clock once; the day, the KEK and the rotation test use that one
 * instant. The wrap AAD is {@code regpack/dek/v1|BODY|<kek id>|body}; the context of the body is
 * {@link BodyContext#bytes()}, so a body copied to another subject or version fails to open.
 *
 * <p>{@link #open} asks the KMS to unwrap every time (there is no read cache) and zeroes the
 * unwrapped key in a {@code finally}. The sealing instance holds the current generation key in
 * memory until its UTC day or seal limit ends, and zeroes it at the next seal after that: a process
 * that sealed today can still seal with today's key if the KEK is destroyed in the middle of the
 * day. Zeroing is best effort; no record relies on it.
 *
 * <p>Failures are as for {@link ObjectCipher}: fixed texts without a cause, and no message holds a
 * key, a KEK id, a regime or an event identity.
 *
 * <p>Thread-safe: sealing is synchronized on the instance.
 */
public final class BodyCipher {
  /** The seals under one data key before it rotates. */
  public static final long MAX_SEALS_PER_KEY = 1L << 30;

  private static final String WRAP_CONTEXT = "body";

  /** The current data key with the day and the KEK it belongs to. */
  private static final class Generation {
    private final LocalDate day;
    private final String kekId;
    private final byte[] dataKey;
    private final Envelope.Header header;
    private long seals;

    Generation(LocalDate day, String kekId, byte[] dataKey, Envelope.Header header) {
      this.day = day;
      this.kekId = kekId;
      this.dataKey = dataKey;
      this.header = header;
    }

    boolean serves(LocalDate otherDay, String otherKekId, long limit) {
      return day.equals(otherDay) && kekId.equals(otherKekId) && seals < limit;
    }
  }

  private final Kms kms;
  private final KekSelector selector;
  private final Entropy entropy;
  private final Clock clock;
  private final long maxSealsPerKey;
  private Generation current; // guarded by this

  public BodyCipher(Kms kms, KekSelector selector, Entropy entropy, Clock clock) {
    this(kms, selector, entropy, clock, MAX_SEALS_PER_KEY);
  }

  /** The seal limit is a parameter for tests; production uses {@link #MAX_SEALS_PER_KEY}. */
  public BodyCipher(
      Kms kms, KekSelector selector, Entropy entropy, Clock clock, long maxSealsPerKey) {
    this.kms = Objects.requireNonNull(kms, "kms");
    this.selector = Objects.requireNonNull(selector, "selector");
    this.entropy = Objects.requireNonNull(entropy, "entropy");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (maxSealsPerKey < 1) {
      throw new IllegalArgumentException("the seal limit must be positive");
    }
    this.maxSealsPerKey = maxSealsPerKey;
  }

  /**
   * Seals a report body.
   *
   * @return an envelope of kind {@code BODY}
   * @throws KeyUnavailableException when the KEK cannot be chosen or the KMS cannot wrap the key
   */
  public synchronized byte[] seal(BodyContext context, byte[] plaintext) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(plaintext, "plaintext");
    Instant at = clock.now();
    KeyRef kek = DataKeys.select(selector, at);
    LocalDate day = at.atOffset(ZoneOffset.UTC).toLocalDate();
    if (current != null && !current.serves(day, kek.keyId(), maxSealsPerKey)) {
      DataKeys.zero(current.dataKey);
      current = null;
    }
    if (current == null) {
      current = newGeneration(day, kek);
    }
    byte[] nonce = new byte[DataKeys.NONCE_BYTES];
    entropy.fill(nonce);
    current.seals++;
    return Envelope.seal(current.header, current.dataKey, nonce, context.bytes(), plaintext);
  }

  /**
   * Opens a report body sealed for {@code context}, with the KEK that its header names.
   *
   * @throws IllegalArgumentException with the message {@code not an envelope} when the bytes are
   *     not an envelope
   * @throws Envelope.AuthenticationException when the envelope is of another kind, was copied to
   *     another subject or version, or was changed, or when the KMS rejects the wrapped key
   * @throws DataShreddedException when the KEK was destroyed
   * @throws KeyUnavailableException when the KMS cannot serve the request
   */
  public byte[] open(BodyContext context, byte[] envelope) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(envelope, "envelope");
    Envelope.Header header = Envelope.header(envelope);
    if (header.kind() != Envelope.Kind.BODY) {
      throw new Envelope.AuthenticationException();
    }
    byte[] dataKey =
        DataKeys.unwrap(
            kms, header, Envelope.wrapAad(Envelope.Kind.BODY, header.kekId(), WRAP_CONTEXT));
    try {
      return Envelope.open(envelope, dataKey, context.bytes());
    } finally {
      DataKeys.zero(dataKey);
    }
  }

  /** Draws the key first, then wraps it; a failure leaves no generation and no key in memory. */
  private Generation newGeneration(LocalDate day, KeyRef kek) {
    byte[] dataKey = new byte[DataKeys.KEY_BYTES];
    boolean kept = false;
    try {
      entropy.fill(dataKey);
      byte[] wrapped =
          DataKeys.wrap(
              kms, kek, dataKey, Envelope.wrapAad(Envelope.Kind.BODY, kek.keyId(), WRAP_CONTEXT));
      Generation generation =
          new Generation(
              day,
              kek.keyId(),
              dataKey,
              new Envelope.Header(Envelope.Kind.BODY, kek.keyId(), wrapped));
      kept = true;
      return generation;
    } finally {
      if (!kept) {
        DataKeys.zero(dataKey);
      }
    }
  }
}
