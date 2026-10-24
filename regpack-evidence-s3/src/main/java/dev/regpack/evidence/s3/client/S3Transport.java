package dev.regpack.evidence.s3.client;

import java.io.IOException;

/**
 * The seam between the S3 client and the network: one signed request in, one answer out. An
 * implementation neither retries nor follows redirects, and throws {@link IOException} for any
 * failure that produced no HTTP answer (refused connection, reset, timeout, interrupt). Any answer
 * with a status, whatever the status, is returned.
 */
public interface S3Transport {
  S3Response send(S3Request request) throws IOException;
}
