package dev.regpack.model;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/** A SHA-256 digest as 64 lowercase hex characters. */
public record Sha256(String hex) {
  private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");
  private static final char[] DIGITS = {
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
  };

  public Sha256 {
    if (hex == null || !HEX.matcher(hex).matches()) {
      throw new IllegalArgumentException("Sha256 needs 64 lowercase hex characters");
    }
  }

  public static Sha256 of(byte[] data) {
    byte[] digest;
    try {
      digest = MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", e);
    }
    char[] out = new char[digest.length * 2];
    for (int i = 0; i < digest.length; i++) {
      out[2 * i] = DIGITS[(digest[i] >> 4) & 0xF];
      out[2 * i + 1] = DIGITS[digest[i] & 0xF];
    }
    return new Sha256(new String(out));
  }

  public static Sha256 ofUtf8(String text) {
    return of(text.getBytes(UTF_8));
  }
}
