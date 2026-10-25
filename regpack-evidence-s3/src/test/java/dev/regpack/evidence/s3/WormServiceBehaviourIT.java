package dev.regpack.evidence.s3;

import dev.regpack.evidence.s3.S3TestService.S3Settings;
import dev.regpack.evidence.s3.client.S3Client;
import org.junit.jupiter.api.Tag;

/**
 * T10: the behaviour contract ({@link S3BehaviourContract}) against the real S3-compatible
 * object-lock service of the job {@code evidence-worm}. The same contract runs against the fake in
 * {@link S3BehaviourFakeTest}: a behaviour the fake gets wrong fails here, at the checkpoint that
 * runs both (review focus WF15). Every method makes its own buckets; none deletes a version.
 */
@Tag("worm")
class WormServiceBehaviourIT implements S3BehaviourContract {
  private S3Settings settings;
  private S3Client client;

  private S3Settings settings() {
    if (settings == null) {
      settings = S3TestService.settings();
    }
    return settings;
  }

  @Override
  public S3Client client() {
    if (client == null) {
      client = S3TestService.rawClient(settings());
    }
    return client;
  }

  @Override
  public String newBucket() {
    return S3TestService.newBucket(client());
  }
}
