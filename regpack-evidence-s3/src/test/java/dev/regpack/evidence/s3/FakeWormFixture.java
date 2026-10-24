package dev.regpack.evidence.s3;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.evidence.s3.client.JdkTransport;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.evidence.s3.client.S3Transport;
import dev.regpack.evidence.s3.client.SigV4.Credentials;
import dev.regpack.evidence.s3.fake.FakeS3Server;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore.Durability;
import dev.regpack.ports.evidence.EvidenceStore.PutRequest;
import dev.regpack.ports.evidence.EvidenceStore.Retention;
import dev.regpack.ports.evidence.EvidenceStore.RetentionClass;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What the adapter-specific {@code *Test} classes of P-WORM share: a fake service, one fresh
 * object-lock bucket, a manual data clock at {@code 2040-03-01T00:00:00Z}, a store over an {@link
 * OutageTransport} and the means to open more stores on the same bucket. Fault switches and request
 * counts exist only on the fake, so only {@code *Test} classes use this.
 */
final class FakeWormFixture implements AutoCloseable {
  final FakeWormBackend backend = new FakeWormBackend();
  final ManualClock dataClock = new ManualClock(AbstractWormContractTest.START);
  final OutageTransport transport = new OutageTransport(new JdkTransport(Duration.ofSeconds(30)));
  final String bucket = backend.newBucket();
  final WormConfig config =
      configWith(new Credentials(FakeWormBackend.KEY_ID, FakeWormBackend.SECRET));
  final WormEvidenceStore store =
      WormEvidenceStore.open(config, dataClock, backend.signingClock(), transport);

  /** The configuration of the fixture's bucket with other credentials. */
  WormConfig configWith(Credentials credentials) {
    return WormConfig.governance(
        backend.server().endpoint(),
        FakeWormBackend.REGION,
        bucket,
        credentials,
        new Durability(true, 1));
  }

  /** A store on the same bucket over another transport. */
  WormEvidenceStore openOn(S3Transport other) {
    return WormEvidenceStore.open(config, dataClock, backend.signingClock(), other);
  }

  /** A second store object on the same bucket: a second process, with its own stripe locks. */
  WormEvidenceStore secondStore() {
    return openOn(new JdkTransport(Duration.ofSeconds(30)));
  }

  FakeS3Server server() {
    return backend.server();
  }

  S3Client raw() {
    return backend.raw();
  }

  Retention retention() {
    return new Retention(
        new RetentionClass("landing"), dataClock.now().plus(Duration.ofDays(1826)));
  }

  PutRequest request(EvidenceKey key, String text) {
    return new PutRequest(key, utf8(text), "text/plain", retention());
  }

  static byte[] utf8(String text) {
    return text.getBytes(UTF_8);
  }

  /** The number of requests the service has received so far. */
  int mark() {
    return server().requestLines().size();
  }

  /** The kinds of the requests received since {@code mark}: versions, GET, PUT, HEAD, other. */
  List<String> kindsSince(int mark) {
    List<String> lines = server().requestLines();
    List<String> kinds = new ArrayList<>();
    for (String line : lines.subList(mark, lines.size())) {
      kinds.add(kind(line));
    }
    return kinds;
  }

  static String kind(String line) {
    int space = line.indexOf(' ');
    String method = line.substring(0, space);
    int query = line.indexOf('?');
    boolean versions =
        query >= 0 && Arrays.asList(line.substring(query + 1).split("&")).contains("versions");
    if (method.equals("GET")) {
      return versions ? "versions" : "GET";
    }
    return method;
  }

  @Override
  public void close() {
    backend.close();
  }
}
