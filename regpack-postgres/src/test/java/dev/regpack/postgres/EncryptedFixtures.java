package dev.regpack.postgres;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.evidence.crypto.EncryptingEvidenceStore;
import dev.regpack.inmemory.InMemoryKms;
import dev.regpack.inmemory.InMemoryKms.WorkloadIdentity;
import dev.regpack.ports.Clock;
import dev.regpack.ports.crypto.KekSelector;
import dev.regpack.ports.crypto.ObjectCipher;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.security.Entropy;
import dev.regpack.ports.security.KeyPurpose;
import dev.regpack.ports.security.KeyRef;
import dev.regpack.ports.security.Kms;
import dev.regpack.testkit.crypto.CounterEntropy;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * What the tests of the encrypted evidence tier share (P3c1 T6; a copy for the module
 * regpack-postgres, whose tests cannot see those of regpack-evidence): an in-memory KMS with the
 * KEKs {@code kek-test} and {@code kek-2026} to {@code kek-2028} for the identity {@code
 * application}, the deterministic entropy of seed {@code regpack-test} (which remembers what it
 * handed out, so that a test can look for a key in a message), and the factories of the cipher and
 * of the decorator, which take the {@code Clock} of the binding so that cipher and store share one
 * clock. One instance serves one test: the KMS and the entropy are its own.
 */
final class EncryptedFixtures {
  /** The workload identity that may use the KEKs. */
  static final WorkloadIdentity APPLICATION = new WorkloadIdentity("application");

  /** The KEK of {@link #encrypt}: every object is wrapped under it. */
  static final String SINGLE_KEK = "kek-test";

  /** Every KEK the KMS of a fixture holds. */
  static final List<String> KEKS = List.of(SINGLE_KEK, "kek-2026", "kek-2027", "kek-2028");

  private static final byte[] SEED = "regpack-test".getBytes(UTF_8);

  private final InMemoryKms admin;
  private final Kms kms;
  private final RecordingEntropy entropy = new RecordingEntropy(new CounterEntropy(SEED));

  private EncryptedFixtures(InMemoryKms admin) {
    this.admin = admin;
    this.kms = admin.as(APPLICATION);
  }

  /** A fresh KMS with its KEKs and a fresh entropy stream. */
  static EncryptedFixtures create() {
    InMemoryKms admin = InMemoryKms.create();
    for (String id : KEKS) {
      admin.createKek(kek(id), Set.of(APPLICATION));
    }
    return new EncryptedFixtures(admin);
  }

  static KeyRef kek(String id) {
    return new KeyRef(id, KeyPurpose.ENVELOPE_KEK);
  }

  /** The policy of the shared suites: every key is encrypted. */
  static Predicate<EvidenceKey> everyKey() {
    return key -> true;
  }

  /** The administrative side of the KMS (destruction); not a method of the {@link Kms} port. */
  InMemoryKms admin() {
    return admin;
  }

  /** The KMS port as the application identity sees it. */
  Kms kms() {
    return kms;
  }

  Entropy entropy() {
    return entropy;
  }

  /** Destroys a KEK in the KMS: crypto-shredding of everything wrapped under it. */
  void destroy(String kekId) {
    admin.destroy(kek(kekId));
  }

  /** A cipher over any KMS and selector, with the entropy of this fixture. */
  ObjectCipher cipher(Kms over, KekSelector selector, Clock clock) {
    return new ObjectCipher(over, selector, entropy, clock);
  }

  /** A cipher that wraps every object under {@link #SINGLE_KEK}. */
  ObjectCipher singleKekCipher(Clock clock) {
    return cipher(kms, KekSelector.single(kek(SINGLE_KEK)), clock);
  }

  /** A cipher that wraps under {@code kek-<UTC year of the clock>}. */
  ObjectCipher perYearCipher(Clock clock) {
    return cipher(kms, KekSelector.perUtcYear("kek"), clock);
  }

  /** The decorator over {@code inner}: one KEK, every key encrypted. */
  EncryptingEvidenceStore encrypt(EvidenceStore inner, Clock clock) {
    return new EncryptingEvidenceStore(inner, singleKekCipher(clock), everyKey());
  }

  /** The decorator over {@code inner}: one KEK per UTC year of the clock, every key encrypted. */
  EncryptingEvidenceStore encryptPerYear(EvidenceStore inner, Clock clock) {
    return new EncryptingEvidenceStore(inner, perYearCipher(clock), everyKey());
  }

  /**
   * The hexadecimal text (lower case) of every array the entropy has handed out so far: the data
   * keys (32 bytes) and the nonces (12 bytes). A message that holds one of them leaks a key.
   */
  List<String> drawnHex() {
    HexFormat hex = HexFormat.of();
    return entropy.draws.stream().map(hex::formatHex).toList();
  }

  /** The entropy of the fixture, remembering every array it filled. */
  private static final class RecordingEntropy implements Entropy {
    final List<byte[]> draws = new CopyOnWriteArrayList<>();
    private final Entropy delegate;

    RecordingEntropy(Entropy delegate) {
      this.delegate = delegate;
    }

    @Override
    public void fill(byte[] out) {
      delegate.fill(out);
      draws.add(out.clone());
    }
  }
}
