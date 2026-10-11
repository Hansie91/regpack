package dev.regpack.spikes.l.encoding;

import java.io.ByteArrayOutputStream;

/**
 * Encoding E1 of the stored decision path: the evaluated rows of the deciding table only, as a
 * varint-packed byte string (a step is the rule ordinal as a zigzag delta to the previous step, a
 * matched bit and the cost units). Layout: {@code count}, then per step {@code (zigzag(delta) << 1
 * | matched)} and {@code cost}, every number an unsigned LEB128 varint. A path of 50 steps with
 * small costs is about 100 bytes, against several kilobytes for the verbose JSON of E3.
 */
public final class PathCodec {
  /** A decoded path: three arrays of the same length. */
  public record Decoded(int[] ruleOrdinals, boolean[] matched, int[] costUnits) {}

  private PathCodec() {}

  /**
   * Packs a path.
   *
   * @throws IllegalArgumentException if the arrays differ in length or a number is negative
   */
  public static byte[] encode(int[] ruleOrdinals, boolean[] matched, int[] costUnits) {
    int n = ruleOrdinals.length;
    if (matched.length != n || costUnits.length != n) {
      throw new IllegalArgumentException(
          "the arrays differ in length: " + n + ", " + matched.length + ", " + costUnits.length);
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream(2 * n + 4);
    writeVarint(out, n);
    long previous = 0;
    for (int i = 0; i < n; i++) {
      if (ruleOrdinals[i] < 0) {
        throw new IllegalArgumentException("negative rule ordinal at step " + i);
      }
      if (costUnits[i] < 0) {
        throw new IllegalArgumentException("negative cost at step " + i);
      }
      long delta = ruleOrdinals[i] - previous;
      long zigzag = (delta << 1) ^ (delta >> 63);
      writeVarint(out, (zigzag << 1) | (matched[i] ? 1L : 0L));
      writeVarint(out, costUnits[i]);
      previous = ruleOrdinals[i];
    }
    return out.toByteArray();
  }

  /**
   * Unpacks a path.
   *
   * @throws IllegalArgumentException if the bytes are truncated, malformed or have trailing bytes
   */
  public static Decoded decode(byte[] bytes) {
    int[] position = {0};
    long count = readVarint(bytes, position);
    if (count < 0 || count > bytes.length) {
      throw new IllegalArgumentException("implausible step count: " + count);
    }
    int n = (int) count;
    int[] ordinals = new int[n];
    boolean[] matched = new boolean[n];
    int[] costs = new int[n];
    long previous = 0;
    for (int i = 0; i < n; i++) {
      long packed = readVarint(bytes, position);
      matched[i] = (packed & 1L) == 1L;
      long zigzag = packed >>> 1;
      long delta = (zigzag >>> 1) ^ -(zigzag & 1L);
      long ordinal = previous + delta;
      if (ordinal < 0 || ordinal > Integer.MAX_VALUE) {
        throw new IllegalArgumentException("rule ordinal out of range at step " + i);
      }
      ordinals[i] = (int) ordinal;
      previous = ordinal;
      long cost = readVarint(bytes, position);
      if (cost < 0 || cost > Integer.MAX_VALUE) {
        throw new IllegalArgumentException("cost out of range at step " + i);
      }
      costs[i] = (int) cost;
    }
    if (position[0] != bytes.length) {
      throw new IllegalArgumentException("trailing bytes after the path");
    }
    return new Decoded(ordinals, matched, costs);
  }

  private static void writeVarint(ByteArrayOutputStream out, long value) {
    long v = value;
    while ((v & ~0x7FL) != 0) {
      out.write((int) ((v & 0x7F) | 0x80));
      v >>>= 7;
    }
    out.write((int) v);
  }

  private static long readVarint(byte[] bytes, int[] position) {
    long result = 0;
    int shift = 0;
    while (true) {
      if (position[0] >= bytes.length) {
        throw new IllegalArgumentException("truncated path");
      }
      if (shift > 63) {
        throw new IllegalArgumentException("malformed varint");
      }
      int b = bytes[position[0]++] & 0xFF;
      result |= (long) (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
  }
}
