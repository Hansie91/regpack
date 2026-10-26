package dev.regpack.inmemory;

import dev.regpack.ports.security.KeyPurpose;
import dev.regpack.ports.security.KeyRef;
import dev.regpack.ports.security.Kms;
import dev.regpack.ports.security.KmsAccessDeniedException;
import dev.regpack.ports.security.KmsKeyDestroyedException;
import dev.regpack.ports.security.KmsSignature;
import dev.regpack.ports.security.WrappedKey;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * In-memory KMS using JDK crypto only: Ed25519 signing keys, HmacSHA256 keys and AES-256-GCM
 * envelope keys (12-byte random IV prepended to the ciphertext, {@code aad} authenticated). Keys
 * are created through the {@code create*} methods; {@link #as(WorkloadIdentity)} yields a {@link
 * Kms} bound to one identity. Thread-safe.
 */
public final class InMemoryKms {
  private static final String SIGNATURE_ALGORITHM = "Ed25519";
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  /** A named workload identity that keys can be allowed to. */
  public record WorkloadIdentity(String name) {
    public WorkloadIdentity {
      Objects.requireNonNull(name, "name");
    }
  }

  private sealed interface Material permits Signing, Hmac, Envelope, Destroyed {}

  private record Signing(KeyPair pair) implements Material {}

  private record Hmac(SecretKey key) implements Material {}

  private record Envelope(SecretKey key) implements Material {}

  /** The tombstone of a destroyed KEK: no key material, the id stays taken. */
  private record Destroyed() implements Material {}

  private record Entry(KeyPurpose purpose, Material material, Set<WorkloadIdentity> allowed) {}

  private final Map<String, Entry> keys = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();

  private InMemoryKms() {}

  public static InMemoryKms create() {
    return new InMemoryKms();
  }

  /** Creates an Ed25519 key; the purpose of {@code key} must be a signing purpose. */
  public InMemoryKms createSigningKey(KeyRef key, Set<WorkloadIdentity> allowed) {
    requirePurpose(key, KeyPurpose.PACK_SIGNING, KeyPurpose.MANIFEST_SIGNING);
    try {
      KeyPair pair = KeyPairGenerator.getInstance(SIGNATURE_ALGORITHM).generateKeyPair();
      register(key, new Signing(pair), allowed);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Ed25519 unavailable", e);
    }
    return this;
  }

  /**
   * Creates an HmacSHA256 key from {@code material}; the purpose must be TRN_HMAC or INDEX_HMAC.
   */
  public InMemoryKms createHmacKey(KeyRef key, byte[] material, Set<WorkloadIdentity> allowed) {
    requirePurpose(key, KeyPurpose.TRN_HMAC, KeyPurpose.INDEX_HMAC);
    register(key, new Hmac(new SecretKeySpec(material, "HmacSHA256")), allowed);
    return this;
  }

  /** Creates an AES-256-GCM key; the purpose must be ENVELOPE_KEK. */
  public InMemoryKms createKek(KeyRef key, Set<WorkloadIdentity> allowed) {
    requirePurpose(key, KeyPurpose.ENVELOPE_KEK);
    try {
      KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256, random);
      register(key, new Envelope(generator.generateKey()), allowed);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES unavailable", e);
    }
    return this;
  }

  /**
   * Destroys an ENVELOPE_KEK: the key material is dropped and a tombstone keeps the id, so every
   * later {@code wrap} or {@code unwrap} throws {@link KmsKeyDestroyedException} (before the
   * identity is checked) and {@link #createKek} refuses the id. Destroying a destroyed KEK does
   * nothing. This is an administrative path, not a method of the {@link Kms} port, and a fake: it
   * models neither a pending-deletion window nor replicas or backups of the key.
   *
   * @throws IllegalArgumentException if the id is unknown or names a key that is not a KEK
   */
  public InMemoryKms destroy(KeyRef kek) {
    Objects.requireNonNull(kek, "kek");
    if (kek.purpose() != KeyPurpose.ENVELOPE_KEK) {
      throw new IllegalArgumentException("only an ENVELOPE_KEK can be destroyed");
    }
    Entry entry = keys.get(kek.keyId());
    if (entry == null || entry.purpose() != KeyPurpose.ENVELOPE_KEK) {
      throw new IllegalArgumentException("unknown KEK: " + kek.keyId());
    }
    keys.replace(kek.keyId(), entry, new Entry(entry.purpose(), new Destroyed(), Set.of()));
    return this;
  }

  /** The {@link Kms} port bound to one identity; {@code verify} is open to every identity. */
  public Kms as(WorkloadIdentity identity) {
    return new Bound(Objects.requireNonNull(identity, "identity"));
  }

  private static void requirePurpose(KeyRef key, KeyPurpose... accepted) {
    if (!Arrays.asList(accepted).contains(key.purpose())) {
      throw new IllegalArgumentException(
          "key " + key.keyId() + " cannot be created with purpose " + key.purpose());
    }
  }

  private void register(KeyRef key, Material material, Set<WorkloadIdentity> allowed) {
    Entry entry = new Entry(key.purpose(), material, Set.copyOf(allowed));
    if (keys.putIfAbsent(key.keyId(), entry) != null) {
      throw new IllegalArgumentException("key already exists: " + key.keyId());
    }
  }

  private final class Bound implements Kms {
    private final WorkloadIdentity identity;

    Bound(WorkloadIdentity identity) {
      this.identity = identity;
    }

    /**
     * Existing key (else IAE), not destroyed (else {@link KmsKeyDestroyedException}, before any
     * other check); requested purpose matches the stored one and fits (else denied).
     */
    private Entry lookup(KeyRef key, KeyPurpose... accepted) {
      Entry entry = keys.get(key.keyId());
      if (entry == null) {
        throw new IllegalArgumentException("unknown key: " + key.keyId());
      }
      if (entry.material() instanceof Destroyed) {
        throw new KmsKeyDestroyedException();
      }
      if (entry.purpose() != key.purpose() || !Arrays.asList(accepted).contains(entry.purpose())) {
        throw new KmsAccessDeniedException(
            "key " + key.keyId() + " is not usable for " + key.purpose() + " here");
      }
      return entry;
    }

    private Entry authorised(KeyRef key, KeyPurpose... accepted) {
      Entry entry = lookup(key, accepted);
      if (!entry.allowed().contains(identity)) {
        throw new KmsAccessDeniedException(
            "identity " + identity.name() + " may not use key " + key.keyId());
      }
      return entry;
    }

    @Override
    public KmsSignature sign(KeyRef key, byte[] message) {
      Entry entry = authorised(key, KeyPurpose.PACK_SIGNING, KeyPurpose.MANIFEST_SIGNING);
      try {
        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initSign(((Signing) entry.material()).pair().getPrivate());
        signature.update(message);
        return new KmsSignature(key.keyId(), SIGNATURE_ALGORITHM, signature.sign());
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException("signing failed", e);
      }
    }

    @Override
    public boolean verify(KeyRef key, byte[] message, KmsSignature signature) {
      Entry entry = lookup(key, KeyPurpose.PACK_SIGNING, KeyPurpose.MANIFEST_SIGNING);
      if (!signature.keyId().equals(key.keyId())
          || !signature.algorithm().equals(SIGNATURE_ALGORITHM)) {
        return false;
      }
      try {
        Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
        verifier.initVerify(((Signing) entry.material()).pair().getPublic());
        verifier.update(message);
        return verifier.verify(signature.value());
      } catch (SignatureException e) {
        return false;
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException("verification failed", e);
      }
    }

    @Override
    public byte[] hmac(KeyRef key, byte[] message) {
      Entry entry = authorised(key, KeyPurpose.TRN_HMAC, KeyPurpose.INDEX_HMAC);
      try {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(((Hmac) entry.material()).key());
        return mac.doFinal(message);
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException("hmac failed", e);
      }
    }

    @Override
    public WrappedKey wrap(KeyRef kek, byte[] dataKey, byte[] aad) {
      Entry entry = authorised(kek, KeyPurpose.ENVELOPE_KEK);
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      try {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
            Cipher.ENCRYPT_MODE,
            ((Envelope) entry.material()).key(),
            new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(aad);
        byte[] encrypted = cipher.doFinal(dataKey);
        byte[] out = new byte[IV_BYTES + encrypted.length];
        System.arraycopy(iv, 0, out, 0, IV_BYTES);
        System.arraycopy(encrypted, 0, out, IV_BYTES, encrypted.length);
        return new WrappedKey(kek.keyId(), out);
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException("wrap failed", e);
      }
    }

    @Override
    public byte[] unwrap(KeyRef kek, WrappedKey wrapped, byte[] aad) {
      Entry entry = authorised(kek, KeyPurpose.ENVELOPE_KEK);
      if (!wrapped.keyId().equals(kek.keyId())) {
        throw new IllegalArgumentException(
            "wrapped key belongs to " + wrapped.keyId() + ", not " + kek.keyId());
      }
      byte[] ciphertext = wrapped.ciphertext();
      if (ciphertext.length < IV_BYTES + TAG_BITS / 8) {
        throw new IllegalArgumentException("wrapped key too short");
      }
      try {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
            Cipher.DECRYPT_MODE,
            ((Envelope) entry.material()).key(),
            new GCMParameterSpec(TAG_BITS, ciphertext, 0, IV_BYTES));
        cipher.updateAAD(aad);
        return cipher.doFinal(ciphertext, IV_BYTES, ciphertext.length - IV_BYTES);
      } catch (AEADBadTagException e) {
        throw new IllegalArgumentException("unwrap failed: wrong aad or tampered key", e);
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException("unwrap failed", e);
      }
    }
  }
}
