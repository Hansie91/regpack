package dev.regpack.pack.model;

/** A plain {@code major.minor.patch} version (no pre-release, no build metadata). */
public record SemVer(int major, int minor, int patch) {
  /** Most digits of one component. */
  private static final int MAX_DIGITS = 9;

  public SemVer {
    if (major < 0 || minor < 0 || patch < 0) {
      throw new IllegalArgumentException("a version component must not be negative");
    }
  }

  /** ASCII digits only, no leading zeros, at most nine digits per component. */
  public static SemVer parse(String text) {
    if (text == null) {
      throw new IllegalArgumentException("a semantic version must not be null");
    }
    int dot1 = text.indexOf('.');
    int dot2 = dot1 < 0 ? -1 : text.indexOf('.', dot1 + 1);
    if (dot1 < 0 || dot2 < 0 || text.indexOf('.', dot2 + 1) >= 0) {
      throw malformed();
    }
    return new SemVer(
        component(text.substring(0, dot1)),
        component(text.substring(dot1 + 1, dot2)),
        component(text.substring(dot2 + 1)));
  }

  private static int component(String digits) {
    int length = digits.length();
    if (length == 0 || length > MAX_DIGITS || (length > 1 && digits.charAt(0) == '0')) {
      throw malformed();
    }
    int value = 0;
    for (int i = 0; i < length; i++) {
      char c = digits.charAt(i);
      if (c < '0' || c > '9') {
        throw malformed();
      }
      value = value * 10 + (c - '0');
    }
    return value;
  }

  private static IllegalArgumentException malformed() {
    return new IllegalArgumentException(
        "a semantic version looks like 1.2.3 (plain, ASCII digits)");
  }

  @Override
  public String toString() {
    return major + "." + minor + "." + patch;
  }
}
