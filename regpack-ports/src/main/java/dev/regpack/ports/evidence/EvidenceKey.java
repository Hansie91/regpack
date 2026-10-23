package dev.regpack.ports.evidence;

import java.util.regex.Pattern;

/**
 * Address of an evidence object: segments of {@code [A-Za-z0-9._=,+-]+} joined by '/'. Empty, "."
 * and ".." segments, segments over 100 characters and paths over 512 characters are rejected.
 */
public record EvidenceKey(String path) {
  private static final int MAX_LENGTH = 512;
  private static final int MAX_SEGMENT_LENGTH = 100;
  private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._=,+-]+");

  public EvidenceKey {
    if (path == null || path.isEmpty() || path.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("EvidenceKey path must be 1 to 512 characters");
    }
    for (String segment : path.split("/", -1)) {
      requireSegment(segment);
    }
  }

  public static EvidenceKey of(String... segments) {
    if (segments.length == 0) {
      throw new IllegalArgumentException("EvidenceKey needs at least one segment");
    }
    for (String segment : segments) {
      requireSegment(segment);
    }
    return new EvidenceKey(String.join("/", segments));
  }

  /** The first segment. */
  public String namespace() {
    int slash = path.indexOf('/');
    return slash < 0 ? path : path.substring(0, slash);
  }

  private static void requireSegment(String segment) {
    if (segment == null) {
      throw new IllegalArgumentException("invalid EvidenceKey segment: null");
    }
    if (segment.length() > MAX_SEGMENT_LENGTH) {
      throw new IllegalArgumentException(
          "EvidenceKey segment must be at most " + MAX_SEGMENT_LENGTH + " characters");
    }
    if (segment.equals(".") || segment.equals("..") || !SEGMENT.matcher(segment).matches()) {
      throw new IllegalArgumentException("invalid EvidenceKey segment: " + segment);
    }
  }
}
