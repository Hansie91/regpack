package dev.regpack.spikes.x.xml;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Locale-free text forms of the XML schema date-time, written by hand. */
public final class XmlFormat {
  private XmlFormat() {}

  /** Microseconds since the epoch of an instant, truncated. */
  public static long micros(Instant instant) {
    return Math.addExact(
        Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000L);
  }

  /**
   * {@code yyyy-MM-ddTHH:mm:ss.ffffffZ}: UTC, always six fractional digits, also before the epoch.
   */
  public static String dateTime(long epochMicros) {
    long seconds = Math.floorDiv(epochMicros, 1_000_000L);
    int fraction = (int) Math.floorMod(epochMicros, 1_000_000L);
    LocalDateTime t = LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC);
    StringBuilder out = new StringBuilder(27);
    pad(out, t.getYear(), 4).append('-');
    pad(out, t.getMonthValue(), 2).append('-');
    pad(out, t.getDayOfMonth(), 2).append('T');
    pad(out, t.getHour(), 2).append(':');
    pad(out, t.getMinute(), 2).append(':');
    pad(out, t.getSecond(), 2).append('.');
    pad(out, fraction, 6).append('Z');
    return out.toString();
  }

  private static StringBuilder pad(StringBuilder out, int value, int width) {
    String digits = Integer.toString(value);
    for (int i = digits.length(); i < width; i++) {
      out.append('0');
    }
    return out.append(digits);
  }
}
