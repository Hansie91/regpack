package dev.regpack.bench.data;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.model.Sha256;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Digests of generated corpora, with a fixed byte layout that does not depend on the platform. */
public final class Digests {
  private Digests() {}

  /** SHA-256 over 8 big-endian bytes per value. */
  public static Sha256 ofLongs(long[] values) {
    ByteBuffer buffer = ByteBuffer.allocate(values.length * Long.BYTES).order(ByteOrder.BIG_ENDIAN);
    for (long value : values) {
      buffer.putLong(value);
    }
    return Sha256.of(buffer.array());
  }

  /** SHA-256 over the UTF-8 bytes of every line, each followed by one LF. */
  public static Sha256 ofLines(Iterable<String> lines) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (String line : lines) {
      byte[] bytes = line.getBytes(UTF_8);
      out.write(bytes, 0, bytes.length);
      out.write('\n');
    }
    return Sha256.of(out.toByteArray());
  }
}
