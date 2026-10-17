package dev.regpack.model;

/**
 * Identity of an engine build, {@code <major>.<minor>.<patch>+lib<N>}, and the replay-compatibility
 * rule of spec 6.3 (provisional until gate G-S3b; companion contracts section 5.1).
 *
 * <p>Two builds are replay-compatible iff they have the same {@code major} and the same {@code
 * libraryVersion}; {@code minor} and {@code patch} never matter. {@code major} is bumped by any
 * change that can alter a decision for the same pinned inputs (interpreter or table semantics, the
 * cost model, any expression construct, a ruling); {@code libraryVersion} by any change to a
 * built-in function's behaviour or cost.
 */
public record EngineBuild(int major, int minor, int patch, int libraryVersion) {
  /** Largest value of every component. */
  public static final int MAX_COMPONENT = 9999;

  private static final String LIB_MARKER = "+lib";

  public EngineBuild {
    check("major", major);
    check("minor", minor);
    check("patch", patch);
    check("libraryVersion", libraryVersion);
  }

  private static void check(String name, int value) {
    if (value < 0 || value > MAX_COMPONENT) {
      throw new IllegalArgumentException(name + " must be in 0.." + MAX_COMPONENT);
    }
  }

  /**
   * Parses {@code <major>.<minor>.<patch>+lib<N>}: ASCII digits only, no leading zeros, each
   * component at most 9999.
   */
  public static EngineBuild parse(String text) {
    if (text == null) {
      throw new IllegalArgumentException("engine build text must not be null");
    }
    int plus = text.indexOf(LIB_MARKER);
    if (plus < 0 || text.indexOf('+') != plus) {
      throw malformed(text);
    }
    String core = text.substring(0, plus);
    String lib = text.substring(plus + LIB_MARKER.length());
    int dot1 = core.indexOf('.');
    int dot2 = dot1 < 0 ? -1 : core.indexOf('.', dot1 + 1);
    if (dot1 < 0 || dot2 < 0 || core.indexOf('.', dot2 + 1) >= 0) {
      throw malformed(text);
    }
    return new EngineBuild(
        component(text, core.substring(0, dot1)),
        component(text, core.substring(dot1 + 1, dot2)),
        component(text, core.substring(dot2 + 1)),
        component(text, lib));
  }

  private static int component(String whole, String digits) {
    int length = digits.length();
    // at most four digits: the largest component is 9999
    if (length == 0 || length > 4 || (length > 1 && digits.charAt(0) == '0')) {
      throw malformed(whole);
    }
    int value = 0;
    for (int i = 0; i < length; i++) {
      char c = digits.charAt(i);
      if (c < '0' || c > '9') {
        throw malformed(whole);
      }
      value = value * 10 + (c - '0');
    }
    return value;
  }

  private static IllegalArgumentException malformed(String text) {
    // the text is untrusted: bound and escape it before echoing
    StringBuilder shown = new StringBuilder();
    int limit = Math.min(text.length(), 24);
    for (int i = 0; i < limit; i++) {
      char c = text.charAt(i);
      if (c >= 0x20 && c < 0x7f) {
        shown.append(c);
      } else {
        shown.append('?');
      }
    }
    if (text.length() > limit) {
      shown.append("...");
    }
    return new IllegalArgumentException(
        "engine build must look like <major>.<minor>.<patch>+lib<N>, got '" + shown + "'");
  }

  @Override
  public String toString() {
    return major + "." + minor + "." + patch + LIB_MARKER + libraryVersion;
  }

  /** Same major and same library version; symmetric; minor and patch never matter. */
  public boolean replayCompatibleWith(EngineBuild other) {
    return major == other.major && libraryVersion == other.libraryVersion;
  }

  /**
   * True iff this (the running engine) satisfies {@code engineMin}: same major, same library
   * version, and (minor, patch) not below the minimum's. A pack never runs on another major or
   * library version unless it is released again with a new {@code engine_min} (fail closed).
   */
  public boolean satisfies(EngineBuild engineMin) {
    if (!replayCompatibleWith(engineMin)) {
      return false;
    }
    return minor > engineMin.minor || (minor == engineMin.minor && patch >= engineMin.patch);
  }
}
