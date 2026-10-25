package dev.regpack.evidence.s3;

import dev.regpack.evidence.s3.client.S3Request;
import dev.regpack.evidence.s3.client.S3Response;
import dev.regpack.evidence.s3.client.S3Transport;
import java.io.IOException;
import java.util.Objects;

/**
 * A test decorator around a transport: while it is down every request fails with an {@link
 * IOException} (no HTTP answer), as a refused connection does, and nothing reaches the service. The
 * message of the exception is the caller's, so that a test can plant a host name or a signature in
 * it and check that no message of the adapter repeats it.
 */
final class OutageTransport implements S3Transport {
  private final S3Transport delegate;
  private final String failureMessage;
  private volatile boolean down;

  OutageTransport(S3Transport delegate) {
    this(delegate, "connection refused");
  }

  OutageTransport(S3Transport delegate, String failureMessage) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.failureMessage = Objects.requireNonNull(failureMessage, "failureMessage");
  }

  void setDown(boolean down) {
    this.down = down;
  }

  @Override
  public S3Response send(S3Request request) throws IOException {
    if (down) {
      throw new IOException(failureMessage);
    }
    return delegate.send(request);
  }
}
