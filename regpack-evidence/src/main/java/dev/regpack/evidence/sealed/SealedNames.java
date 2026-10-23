package dev.regpack.evidence.sealed;

/**
 * File-system names of the segments of an {@code EvidenceKey} (D-E5, review focus EF9). A segment
 * of {@code [A-Za-z0-9._=,+-]} becomes a name that is injective, unchanged by case folding, never
 * ends in a dot or a space and is never a Windows reserved device name:
 *
 * <ul>
 *   <li>every capital letter becomes '^' and the lower-case letter, so that two segments differing
 *       only in case get names that differ in more than case;
 *   <li>a final '.' becomes "^0", because Windows drops a trailing dot;
 *   <li>every other character is unchanged; '^' is outside the alphabet, so decoding is exact;
 *   <li>a directory name has the prefix "d-", an object file "o-" and a meta file "m-", so no name
 *       is a bare device name such as "con" or "nul".
 * </ul>
 *
 * A temporary name is a name followed by {@code ~tmp~<pid>~<counter>}; '~' is outside the alphabet,
 * so a temporary name never decodes and no encoded name looks temporary.
 */
public final class SealedNames {
  /** Raw characters of a segment; longer is refused (EvidenceKey already refuses it). */
  public static final int MAX_SEGMENT = 100;

  private static final String TEMPORARY_MARKER = "~tmp~";
  private static final char ESCAPE = '^';

  private SealedNames() {}

  /** The name of a directory for a segment: "d-" and the encoding. */
  public static String directory(String segment) {
    return "d-" + encode(segment);
  }

  /** The name of an object file for a segment: "o-" and the encoding. */
  public static String object(String segment) {
    return "o-" + encode(segment);
  }

  /** The name of a meta file for a segment: "m-" and the encoding. */
  public static String meta(String segment) {
    return "m-" + encode(segment);
  }

  /**
   * The segment of a name made by {@link #directory}, {@link #object} or {@link #meta}.
   *
   * @throws IllegalArgumentException for any other name, and for a temporary name
   */
  public static String decode(String name) {
    if (name == null || name.length() < 3 || isTemporary(name) || name.charAt(1) != '-') {
      throw new IllegalArgumentException("not a sealed name");
    }
    char kind = name.charAt(0);
    if (kind != 'd' && kind != 'o' && kind != 'm') {
      throw new IllegalArgumentException("not a sealed name");
    }
    String encoded = name.substring(2);
    StringBuilder segment = new StringBuilder(encoded.length());
    for (int i = 0; i < encoded.length(); i++) {
      char c = encoded.charAt(i);
      if (c != ESCAPE) {
        segment.append(c);
        continue;
      }
      if (i + 1 >= encoded.length()) {
        throw new IllegalArgumentException("not a sealed name");
      }
      char next = encoded.charAt(++i);
      if (next == '0') {
        segment.append('.');
      } else if (next >= 'a' && next <= 'z') {
        segment.append((char) (next - 'a' + 'A'));
      } else {
        throw new IllegalArgumentException("not a sealed name");
      }
    }
    String decoded = segment.toString();
    // one name per segment: whatever does not encode back to the same text is not ours
    if (decoded.length() > MAX_SEGMENT || !encode(decoded).equals(encoded)) {
      throw new IllegalArgumentException("not a sealed name");
    }
    return decoded;
  }

  /** True for a name that carries the temporary marker. */
  static boolean isTemporary(String name) {
    return name.contains(TEMPORARY_MARKER);
  }

  /** A temporary name for {@code name}: unique per process (pid) and call (counter). */
  static String temporary(String name, long pid, long counter) {
    return name + TEMPORARY_MARKER + pid + "~" + counter;
  }

  private static String encode(String segment) {
    if (segment == null || segment.isEmpty() || segment.length() > MAX_SEGMENT) {
      throw new IllegalArgumentException("a segment must have 1 to " + MAX_SEGMENT + " characters");
    }
    StringBuilder out = new StringBuilder(segment.length() + 4);
    int last = segment.length() - 1;
    for (int i = 0; i <= last; i++) {
      char c = segment.charAt(i);
      if (c >= 'A' && c <= 'Z') {
        out.append(ESCAPE).append((char) (c - 'A' + 'a'));
      } else if (c == '.' && i == last) {
        out.append(ESCAPE).append('0');
      } else if (isPlain(c)) {
        out.append(c);
      } else {
        throw new IllegalArgumentException("a segment has a character outside the key alphabet");
      }
    }
    return out.toString();
  }

  private static boolean isPlain(char c) {
    return (c >= 'a' && c <= 'z')
        || (c >= '0' && c <= '9')
        || c == '.'
        || c == '_'
        || c == '='
        || c == ','
        || c == '+'
        || c == '-';
  }
}
