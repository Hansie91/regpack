package dev.regpack.ports.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/**
 * The keyed hash of a data-subject identifier (D-K5): 64 lowercase hex digits of an HMAC-SHA-256.
 * It is a MAC, so it is compared with {@link MessageDigest#isEqual}. {@code toString} shows nothing
 * of it.
 */
public record SubjectHash(String hex) {
  private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");

  public SubjectHash {
    if (hex == null || !HEX.matcher(hex).matches()) {
      throw new IllegalArgumentException("a subject hash is 64 lowercase hex digits");
    }
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof SubjectHash that
        && MessageDigest.isEqual(
            hex.getBytes(StandardCharsets.US_ASCII), that.hex.getBytes(StandardCharsets.US_ASCII));
  }

  @Override
  public int hashCode() {
    return hex.hashCode();
  }

  @Override
  public String toString() {
    return "SubjectHash[hidden]";
  }
}
