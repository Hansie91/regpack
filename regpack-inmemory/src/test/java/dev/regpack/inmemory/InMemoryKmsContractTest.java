package dev.regpack.inmemory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.inmemory.InMemoryKms.WorkloadIdentity;
import dev.regpack.ports.security.KeyPurpose;
import dev.regpack.ports.security.KeyRef;
import dev.regpack.ports.security.Kms;
import dev.regpack.testkit.contract.KmsContract;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InMemoryKmsContractTest implements KmsContract {
  private static final WorkloadIdentity SIGNER = new WorkloadIdentity("signer");
  private static final WorkloadIdentity APPLICATION = new WorkloadIdentity("application");

  private final InMemoryKms kms =
      InMemoryKms.create()
          .createSigningKey(packKey(), Set.of(SIGNER))
          .createSigningKey(manifestKey(), Set.of(SIGNER))
          .createHmacKey(trnKey(), "trn-material-1".getBytes(UTF_8), Set.of(APPLICATION))
          .createHmacKey(trnKey2(), "trn-material-2".getBytes(UTF_8), Set.of(APPLICATION))
          .createKek(kek(), Set.of(APPLICATION))
          .createKek(kek2(), Set.of(SIGNER));

  @Override
  public Kms signer() {
    return kms.as(SIGNER);
  }

  @Override
  public Kms application() {
    return kms.as(APPLICATION);
  }

  @Override
  public KeyRef packKey() {
    return new KeyRef("pack-key", KeyPurpose.PACK_SIGNING);
  }

  @Override
  public KeyRef manifestKey() {
    return new KeyRef("manifest-key", KeyPurpose.MANIFEST_SIGNING);
  }

  @Override
  public KeyRef trnKey() {
    return new KeyRef("trn-key", KeyPurpose.TRN_HMAC);
  }

  @Override
  public KeyRef trnKey2() {
    return new KeyRef("trn-key-2", KeyPurpose.TRN_HMAC);
  }

  @Override
  public KeyRef kek() {
    return new KeyRef("kek-1", KeyPurpose.ENVELOPE_KEK);
  }

  @Override
  public KeyRef kek2() {
    return new KeyRef("kek-2", KeyPurpose.ENVELOPE_KEK);
  }

  @Test
  void hmacMatchesRfc4231TestCase1() {
    byte[] key = new byte[20];
    java.util.Arrays.fill(key, (byte) 0x0b);
    KeyRef rfc = new KeyRef("rfc-4231", KeyPurpose.TRN_HMAC);
    Kms bound = InMemoryKms.create().createHmacKey(rfc, key, Set.of(APPLICATION)).as(APPLICATION);
    assertThat(HexFormat.of().formatHex(bound.hmac(rfc, "Hi There".getBytes(UTF_8))))
        .isEqualTo("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7");
  }
}
