package dev.regpack.evidence.s3.client;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * An HTTP answer: status, headers with lower-case names and the body. Immutable (the map and the
 * body are copied); its {@code toString} holds the status, the header names and the length of the
 * body, never a header value or the body.
 */
public record S3Response(int status, Map<String, String> headers, byte[] body) {
  private static final Pattern HEADER_NAME = Pattern.compile("[a-z0-9!#$%&'*+.^_`|~-]+");

  public S3Response {
    Objects.requireNonNull(headers, "headers");
    Objects.requireNonNull(body, "body");
    if (status < 100 || status > 599) {
      throw new IllegalArgumentException("status must be 100 to 599");
    }
    for (Map.Entry<String, String> h : headers.entrySet()) {
      if (!HEADER_NAME.matcher(Objects.requireNonNull(h.getKey(), "header name")).matches()) {
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
    return other instanceof S3Response r
        && status == r.status
        && headers.equals(r.headers)
        && Arrays.equals(body, r.body);
  }

  @Override
  public int hashCode() {
    return Objects.hash(status, headers, Arrays.hashCode(body));
  }

  @Override
  public String toString() {
    return "S3Response[status="
        + status
        + ", headers="
        + new TreeSet<>(headers.keySet())
        + ", bodyLength="
        + body.length
        + "]";
  }
}
