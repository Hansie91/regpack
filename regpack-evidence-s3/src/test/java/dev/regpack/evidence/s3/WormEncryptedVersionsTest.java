package dev.regpack.evidence.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.evidence.crypto.EncryptingEvidenceStore;
import dev.regpack.evidence.s3.client.JdkTransport;
import dev.regpack.evidence.s3.client.S3Client.VersionEntry;
import dev.regpack.evidence.s3.client.S3Request;
import dev.regpack.evidence.s3.client.S3Transport;
import dev.regpack.ports.crypto.DataShreddedException;
import dev.regpack.ports.crypto.Envelope;
import dev.regpack.ports.crypto.ObjectCipher;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore.PutOutcome;
import dev.regpack.ports.evidence.EvidenceStore.PutRequest;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P3c1 T13 (task note T13-1, decision D24): what the cross-process hole of P-WORM (D-W3) leaves
 * when the writers are two {@link EncryptingEvidenceStore}s. The hole needs a hook of the fake
 * service (a version ranked below another one), so this exists on the fake only; no service is
 * needed. The raw adapter's own case is {@code WormStoreVersionsTest}.
 */
class WormEncryptedVersionsTest {
  private static final EvidenceKey K = EvidenceKey.of("landing", "k1");

  private FakeWormFixture f;
  private EncryptedFixtures fixtures;

  @BeforeEach
  void start() {
    f = new FakeWormFixture();
    fixtures = EncryptedFixtures.create();
  }

  @AfterEach
  void stop() {
    f.close();
  }

  /** The PutObject of a key: a PUT with no sub-resource in the query. */
  private static boolean isObjectPut(S3Request request) {
    return request.method().equals("PUT")
        && request.uri().getRawQuery() == null
        && request.uri().getRawPath().endsWith("/" + K.path());
  }

  /**
   * Two decorated stores over one bucket, as two processes would be, each with a cipher whose clock
   * is on one side of 1 January UTC (so each wraps under the KEK of its own year). Store B has
   * listed (nothing there) and its PUT is about to be forwarded when store A does its whole put;
   * the service then ranks B's upload below A's version. Both writers are told {@code CREATED}, the
   * object is B's envelope, and A's envelope, a different envelope of the same plaintext wrapped
   * under the other year's KEK, stays as the newer locked version that the port never returns.
   * Destroying the KEK of the object (B's) shreds the object for every reader, and the extra
   * version still opens with A's KEK: the destruction of one KEK does not shred what a lost race
   * left under the other (D24; the job that destroys a cohort must count extra versions, P4).
   */
  @Test
  void aLostCrossProcessRaceLeavesTheLosersEnvelopeAsAnExtraVersion() {
    ManualClock clockOfA = new ManualClock(Instant.parse("2026-12-31T23:59:58Z"));
    ManualClock clockOfB = new ManualClock(Instant.parse("2027-01-01T00:00:02Z"));
    EncryptingEvidenceStore decoratedA =
        new EncryptingEvidenceStore(
            f.store, fixtures.perYearCipher(clockOfA), EncryptedFixtures.everyKey());
    PutRequest request = f.request(K, "one plaintext, two writers");
    JdkTransport real = new JdkTransport(Duration.ofSeconds(30));
    PutOutcome[] outcomeOfA = new PutOutcome[1];
    boolean[] interleaved = new boolean[1];
    S3Transport transportOfB =
        s3Request -> {
          if (!interleaved[0] && isObjectPut(s3Request)) {
            interleaved[0] = true;
            // B's PUT is about to leave: A does its whole put now, through its own decorator
            outcomeOfA[0] = decoratedA.put(request);
            // and the service ranks B's upload, which finishes later, below A's version
            f.server().rankNextPutBelowExisting(f.bucket, K.path());
          }
          return real.send(s3Request);
        };
    EncryptingEvidenceStore decoratedB =
        new EncryptingEvidenceStore(
            f.openOn(transportOfB), fixtures.perYearCipher(clockOfB), EncryptedFixtures.everyKey());

    PutOutcome outcomeOfB = decoratedB.put(request);

    assertThat(interleaved[0]).as("A's put ran between B's listing and B's PUT").isTrue();
    assertThat(outcomeOfA[0]).isEqualTo(PutOutcome.CREATED);
    assertThat(outcomeOfB).isEqualTo(PutOutcome.CREATED);
    // both readers get the plaintext, from B's envelope, the oldest data version
    assertThat(decoratedA.get(K).orElseThrow().bytes()).isEqualTo(request.bytes());
    assertThat(decoratedB.get(K).orElseThrow().bytes()).isEqualTo(request.bytes());
    assertThat(Envelope.header(f.store.get(K).orElseThrow().bytes()).kekId()).isEqualTo("kek-2027");

    // the bucket holds two versions: two different envelopes of the one plaintext
    List<VersionEntry> versions =
        f.raw().listVersions(f.bucket, K.path(), Optional.empty(), Optional.empty(), 10).entries();
    assertThat(versions).hasSize(2);
    byte[] extra =
        f.raw().get(f.bucket, K.path(), versions.get(0).versionId()).orElseThrow().body();
    byte[] object =
        f.raw().get(f.bucket, K.path(), versions.get(1).versionId()).orElseThrow().body();
    assertThat(Envelope.isEnvelope(extra)).isTrue();
    assertThat(Envelope.isEnvelope(object)).isTrue();
    assertThat(extra).as("the loser's envelope is not the winner's").isNotEqualTo(object);
    assertThat(Envelope.header(extra).kekId()).isEqualTo("kek-2026");
    assertThat(Envelope.header(object).kekId()).isEqualTo("kek-2027");
    assertThat(object).isEqualTo(f.store.get(K).orElseThrow().bytes());
    ObjectCipher cipherOfA = fixtures.perYearCipher(clockOfA);
    assertThat(cipherOfA.open(K, extra)).isEqualTo(request.bytes());
    assertThat(cipherOfA.open(K, object)).isEqualTo(request.bytes());

    // the KEK of the object is destroyed: the object is shredded for every reader and a put of it
    // does not revive it, while the extra version is not served by the port and opens with the
    // other KEK, which is still there
    fixtures.destroy("kek-2027");

    assertThatThrownBy(() -> decoratedA.get(K)).isInstanceOf(DataShreddedException.class);
    assertThatThrownBy(() -> decoratedB.get(K)).isInstanceOf(DataShreddedException.class);
    assertThatThrownBy(() -> decoratedA.put(request)).isInstanceOf(DataShreddedException.class);
    assertThatThrownBy(() -> cipherOfA.open(K, object)).isInstanceOf(DataShreddedException.class);
    assertThat(cipherOfA.open(K, extra))
        .as("the extra version still opens with the KEK of its own year")
        .isEqualTo(request.bytes());
    assertThat(
            f.raw()
                .listVersions(f.bucket, K.path(), Optional.empty(), Optional.empty(), 10)
                .entries())
        .as("nothing was deleted")
        .hasSize(2);
  }
}
