package dev.regpack.evidence.s3;

import dev.regpack.evidence.s3.client.JdkTransport;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.evidence.s3.client.SigV4.Credentials;
import dev.regpack.evidence.s3.fake.FakeS3Server;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * The behaviour contract against the fake service. The same contract runs against the real service
 * in the job {@code evidence-worm} (T10); a behaviour the fake gets wrong fails there.
 */
class S3BehaviourFakeTest implements S3BehaviourContract {
  private static final String KEY_ID = "AKIAFAKEBEHAVIOUR01";
  private static final String SECRET = "fake-behaviour-secret-0123456789ab";

  private FakeS3Server server;
  private S3Client client;

  @BeforeEach
  void start() {
    server = FakeS3Server.start(KEY_ID, SECRET);
    client =
        new S3Client(
            server.endpoint(),
            "us-east-1",
            new Credentials(KEY_ID, SECRET),
            new ManualClock(Instant.parse("2040-03-01T00:00:00Z")),
            new JdkTransport(Duration.ofSeconds(10)));
  }

  @AfterEach
  void stop() {
    server.close();
  }

  @Override
  public S3Client client() {
    return client;
  }

  @Override
  public String newBucket() {
    String bucket = "rp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    client.createBucket(bucket, true);
    return bucket;
  }
}
