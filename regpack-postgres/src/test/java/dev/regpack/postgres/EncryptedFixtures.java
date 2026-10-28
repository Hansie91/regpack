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
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What the tests of the encrypted evidence tier share (P3c1 T6; a copy for the module
 * regpack-postgres, whose tests cannot see those of regpack-evidence): an in-memory KMS with the
 * KEKs {@code kek-test} and {@code kek-2026} to {@code kek-2028} for the identity {@code
 * application}, the deterministic entropy of seed {@code regpack-test}, and the factory of the
 * decorator, which takes the {@code Clock} of the binding so that cipher and store share one clock.
 * One instance serves one test: the KMS and the entropy are its own.
 *
 * <p>This copy mirrors {@code dev.regpack.evidence.crypto.EncryptedFixtures} on purpose (open item
 * 24, duplicated test support) and holds only the members that the tests of this module call
 * ({@link #create} and {@link #encrypt}, with what they need): a later change is made in all three
 * copies (this one, the one in regpack-evidence-s3 and the original in regpack-evidence).
 */
final class EncryptedFixtures {
  /** The workload identity that may use the KEKs. */
  static final WorkloadIdentity APPLICATION = new WorkloadIdentity("application");

  /** The KEK of {@link #encrypt}: every object is wrapped under it. */
  static final String SINGLE_KEK = "kek-test";

  /** Every KEK the KMS of a fixture holds. */
  static final List<String> KEKS = List.of(SINGLE_KEK, "kek-2026", "kek-2027", "kek-2028");

  private static final byte[] SEED = "regpack-test".getBytes(UTF_8);

  private final Kms kms;
  private final Entropy entropy = new CounterEntropy(SEED);

  private EncryptedFixtures(InMemoryKms admin) {
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

  /** A cipher that wraps every object under {@link #SINGLE_KEK}. */
  ObjectCipher singleKekCipher(Clock clock) {
    return new ObjectCipher(kms, KekSelector.single(kek(SINGLE_KEK)), entropy, clock);
  }

  /** The decorator over {@code inner}: one KEK, every key encrypted. */
  EncryptingEvidenceStore encrypt(EvidenceStore inner, Clock clock) {
    return new EncryptingEvidenceStore(inner, singleKekCipher(clock), everyKey());
  }
}
