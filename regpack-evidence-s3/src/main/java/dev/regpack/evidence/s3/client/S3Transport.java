package dev.regpack.evidence.s3.client;

import java.io.IOException;

/**
 * The seam between the S3 client and the network: one signed request in, one answer out. An
 * implementation issues no retry of its own and does not follow redirects, and throws {@link
 * IOException} for any failure that produced no HTTP answer (refused connection, reset, timeout,
 * interrupt, a status outside 100 to 599). Any answer with a status of 100 to 599, whatever the
 * status, is returned.
 */
public interface S3Transport {
  S3Response send(S3Request request) throws IOException;
}
