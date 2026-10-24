package dev.regpack.evidence.s3;

import dev.regpack.evidence.s3.client.S3Request;
import dev.regpack.evidence.s3.client.S3Response;
import dev.regpack.evidence.s3.client.S3Transport;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A test decorator around a transport that holds the first request it receives, before it reaches
 * the service, until the test lets it go. It makes a thread stand still inside a store call, so
 * that a test can show what a second call does meanwhile (the stripe locks of D-W3) without a sleep
 * and without a race. Every wait is bounded, so that a broken store fails the test and does not
 * hang it.
 */
final class GatedTransport implements S3Transport {
  private static final long WAIT_SECONDS = 30;

  private final S3Transport delegate;
  private final AtomicBoolean first = new AtomicBoolean(true);
  private final AtomicInteger sent = new AtomicInteger();
  private final CountDownLatch entered = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);

  GatedTransport(S3Transport delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /** Waits until the first request is held. */
  void awaitHeld() throws InterruptedException {
    if (!entered.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
      throw new AssertionError("no request reached the gate");
    }
  }

  /** Lets the held request go; later requests are never held. */
  void release() {
    release.countDown();
  }

  /** The number of requests received, the held one included. */
  int sent() {
    return sent.get();
  }

  @Override
  public S3Response send(S3Request request) throws IOException {
    sent.incrementAndGet();
    if (first.getAndSet(false)) {
      entered.countDown();
      try {
        if (!release.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
          throw new IOException("the gate was never released");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("interrupted at the gate");
      }
    }
    return delegate.send(request);
  }
}
