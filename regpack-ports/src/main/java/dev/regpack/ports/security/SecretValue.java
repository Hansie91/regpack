package dev.regpack.ports.security;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A resolved secret. {@link #toString()} never shows the content; {@link #close()} zeroes the held
 * bytes, after which every accessor throws {@link IllegalStateException}.
 */
public final class SecretValue implements AutoCloseable {
  private byte[] bytes;

  private SecretValue(byte[] bytes) {
    this.bytes = bytes;
  }

  /** Takes a copy of {@code bytes}. */
  public static SecretValue of(byte[] bytes) {
    return new SecretValue(bytes.clone());
  }

  public synchronized byte[] copyBytes() {
    return held().clone();
  }

  public synchronized String utf8() {
    return new String(held(), StandardCharsets.UTF_8);
  }

  @Override
  public synchronized void close() {
    if (bytes != null) {
      Arrays.fill(bytes, (byte) 0);
      bytes = null;
    }
  }

  @Override
  public String toString() {
    return "SecretValue[REDACTED]";
  }

  private byte[] held() {
    if (bytes == null) {
      throw new IllegalStateException("secret value is closed");
    }
    return bytes;
  }
}
