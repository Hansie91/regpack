package dev.regpack.evidence.s3.client;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * A fully built and signed request. Header names are lower case; the map holds every header the
 * client set, among them {@code host} and {@code authorization}; a transport sends all of them
 * except the ones the HTTP stack sets itself ({@code host}, {@code content-length}). The record is
 * immutable (the map and the body are copied) and its {@code toString} holds the method, the path,
 * the header names and the length of the body, never a header value, the host, the query or the
 * body.
 */
public record S3Request(String method, URI uri, Map<String, String> headers, byte[] body) {

  public S3Request {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(uri, "uri");
    Objects.requireNonNull(headers, "headers");
    Objects.requireNonNull(body, "body");
    if (!SigV4.METHOD.matcher(method).matches()) {
      throw new IllegalArgumentException("method must be upper-case letters");
    }
    if (uri.getRawPath() == null || uri.getHost() == null) {
      throw new IllegalArgumentException("uri must be hierarchical and name a host");
    }
    for (Map.Entry<String, String> h : headers.entrySet()) {
      if (!SigV4.HEADER_NAME.matcher(Objects.requireNonNull(h.getKey(), "header name")).matches()) {
        throw new IllegalArgumentException("header names are lower-case tokens");
      }
      Objects.requireNonNull(h.getValue(), "header value");
    }
    headers = Map.copyOf(headers);
    body = body.clone();
  }

  @Override
  public byte[] body() {
    return body.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof S3Request r
        && method.equals(r.method)
        && uri.equals(r.uri)
        && headers.equals(r.headers)
        && Arrays.equals(body, r.body);
  }

  @Override
  public int hashCode() {
    return Objects.hash(method, uri, headers, Arrays.hashCode(body));
  }

  @Override
  public String toString() {
    return "S3Request[method="
        + method
        + ", path="
        + uri.getRawPath()
        + ", headers="
        + new TreeSet<>(headers.keySet())
        + ", bodyLength="
        + body.length
        + "]";
  }
}
