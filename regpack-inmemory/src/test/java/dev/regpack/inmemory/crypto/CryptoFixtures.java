package dev.regpack.inmemory.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.inmemory.InMemoryKms;
import dev.regpack.inmemory.InMemoryKms.WorkloadIdentity;
import dev.regpack.ports.Clock;
import dev.regpack.ports.crypto.Envelope;
import dev.regpack.ports.crypto.KeyUnavailableException;
import dev.regpack.ports.security.Entropy;
import dev.regpack.ports.security.KeyPurpose;
import dev.regpack.ports.security.KeyRef;
import dev.regpack.ports.security.Kms;
import dev.regpack.ports.security.KmsSignature;
import dev.regpack.ports.security.WrappedKey;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.crypto.CounterEntropy;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** What the cipher tests share: a KMS with the yearly KEKs, the V5 entropy and KMS doubles. */
final class CryptoFixtures {
  static final WorkloadIdentity APPLICATION = new WorkloadIdentity("application");
  static final WorkloadIdentity STRANGER = new WorkloadIdentity("stranger");
  static final byte[] SEED = "regpack-test".getBytes(UTF_8);
  static final HexFormat HEX = HexFormat.of();
  static final String KEY_UNAVAILABLE_MESSAGE = "the key service could not serve this request";
  static final String SHREDDED_MESSAGE = "the key that protects this data was destroyed";

  private CryptoFixtures() {}

  static KeyRef kek(int year) {
    return new KeyRef("kek-" + year, KeyPurpose.ENVELOPE_KEK);
  }

  /** A KMS with {@code kek-2026} to {@code kek-2028}, usable by {@link #APPLICATION} only. */
  static InMemoryKms kms() {
    InMemoryKms kms = InMemoryKms.create();
    for (int year = 2026; year <= 2028; year++) {
      kms.createKek(kek(year), Set.of(APPLICATION));
    }
    return kms;
  }

  static ManualClock clockAt(String instant) {
    return new ManualClock(Instant.parse(instant));
  }

  /** The V5 stream: the first bytes are the data key of a first seal, then its nonce. */
  static Entropy entropy() {
    return new CounterEntropy(SEED);
  }

  static byte[] stream(int length) {
    byte[] out = new byte[length];
    entropy().fill(out);
    return out;
  }

  static byte[] range(int from, int length) {
    byte[] out = new byte[length];
    for (int i = 0; i < length; i++) {
      out[i] = (byte) (from + i);
    }
    return out;
  }

  static String sha256Hex(byte[] bytes) {
    try {
      return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The 12 nonce bytes of an envelope: they follow the header. */
  static byte[] nonceOf(byte[] envelope) {
    Envelope.Header header = Envelope.header(envelope);
    int at = 7 + header.kekId().length() + 2 + header.wrappedKey().length;
    return Arrays.copyOfRange(envelope, at, at + 12);
  }

  /** The failure of a key service: the one type, the fixed text, no cause, nothing suppressed. */
  static void assertKeyUnavailable(Throwable thrown) {
    assertThat(thrown)
        .isInstanceOf(KeyUnavailableException.class)
        .hasMessage(KEY_UNAVAILABLE_MESSAGE)
        .hasNoCause();
    // the probe: a type that hardens itself against suppression keeps this empty
    thrown.addSuppressed(new RuntimeException("probe"));
    assertThat(thrown.getSuppressed()).isEmpty();
  }

  /** One call of {@code wrap}: the live array is the one the caller handed in. */
  record Wrap(KeyRef kek, byte[] dataKey, byte[] aad, byte[] live) {}

  /** Forwards to a real KMS and records what the cipher handed in and what it got back. */
  static final class RecordingKms implements Kms {
    final List<Wrap> wraps = new CopyOnWriteArrayList<>();
    final List<byte[]> unwrapped = new CopyOnWriteArrayList<>();
    final AtomicInteger unwraps = new AtomicInteger();
    private final Kms delegate;

    RecordingKms(Kms delegate) {
      this.delegate = delegate;
    }

    @Override
    public KmsSignature sign(KeyRef key, byte[] message) {
      return delegate.sign(key, message);
    }

    @Override
    public boolean verify(KeyRef key, byte[] message, KmsSignature signature) {
      return delegate.verify(key, message, signature);
    }

    @Override
    public byte[] hmac(KeyRef key, byte[] message) {
      return delegate.hmac(key, message);
    }

    @Override
    public WrappedKey wrap(KeyRef kek, byte[] dataKey, byte[] aad) {
      wraps.add(new Wrap(kek, dataKey.clone(), aad.clone(), dataKey));
      return delegate.wrap(kek, dataKey, aad);
    }

    @Override
    public byte[] unwrap(KeyRef kek, WrappedKey wrapped, byte[] aad) {
      unwraps.incrementAndGet();
      byte[] key = delegate.unwrap(kek, wrapped, aad);
      unwrapped.add(key);
      return key;
    }
  }

  /** Wraps to fixed bytes (the V6 stub) and refuses everything else. */
  static final class StubKms implements Kms {
    final List<Wrap> wraps = new CopyOnWriteArrayList<>();
    private final byte[] wrapped;

    StubKms(byte[] wrapped) {
      this.wrapped = wrapped.clone();
    }

    @Override
    public KmsSignature sign(KeyRef key, byte[] message) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean verify(KeyRef key, byte[] message, KmsSignature signature) {
      throw new UnsupportedOperationException();
    }

    @Override
    public byte[] hmac(KeyRef key, byte[] message) {
      throw new UnsupportedOperationException();
    }

    @Override
    public WrappedKey wrap(KeyRef kek, byte[] dataKey, byte[] aad) {
      wraps.add(new Wrap(kek, dataKey.clone(), aad.clone(), dataKey));
      return new WrappedKey(kek.keyId(), wrapped);
    }

    @Override
    public byte[] unwrap(KeyRef kek, WrappedKey wrapped, byte[] aad) {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Answers wrap with the given bytes and unwrap with the given array itself (so that its zeroing
   * is observable); keeps the data key array that the cipher handed to wrap.
   */
  static final class AnsweringKms implements Kms {
    private final byte[] wrapped;
    private final byte[] unwrapped;
    byte[] lastDataKey;

    AnsweringKms(byte[] wrapped, byte[] unwrapped) {
      this.wrapped = wrapped;
      this.unwrapped = unwrapped;
    }

    @Override
    public KmsSignature sign(KeyRef key, byte[] message) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean verify(KeyRef key, byte[] message, KmsSignature signature) {
      throw new UnsupportedOperationException();
    }

    @Override
    public byte[] hmac(KeyRef key, byte[] message) {
      throw new UnsupportedOperationException();
    }

    @Override
    public WrappedKey wrap(KeyRef kek, byte[] dataKey, byte[] aad) {
      lastDataKey = dataKey;
      return new WrappedKey(kek.keyId(), wrapped);
    }

    @Override
    public byte[] unwrap(KeyRef kek, WrappedKey wrapped, byte[] aad) {
      return unwrapped;
    }
  }

  /** Every call throws the given failure. */
  static final class FailingKms implements Kms {
    private final RuntimeException failure;

    FailingKms(RuntimeException failure) {
      this.failure = failure;
    }

    @Override
    public KmsSignature sign(KeyRef key, byte[] message) {
      throw failure;
    }

    @Override
    public boolean verify(KeyRef key, byte[] message, KmsSignature signature) {
      throw failure;
    }

    @Override
    public byte[] hmac(KeyRef key, byte[] message) {
      throw failure;
    }

    @Override
    public WrappedKey wrap(KeyRef kek, byte[] dataKey, byte[] aad) {
      throw failure;
    }

    @Override
    public byte[] unwrap(KeyRef kek, WrappedKey wrapped, byte[] aad) {
      throw failure;
    }
  }

  /** The first read gives {@code first}, every later one {@code later}; counts the reads. */
  static final class SteppingClock implements Clock {
    private final Instant first;
    private final Instant later;
    private final AtomicInteger reads = new AtomicInteger();

    SteppingClock(Instant first, Instant later) {
      this.first = first;
      this.later = later;
    }

    @Override
    public Instant now() {
      return reads.getAndIncrement() == 0 ? first : later;
    }

    @Override
    public long monotonicNanos() {
      return 0L;
    }

    int reads() {
      return reads.get();
    }
  }
}
