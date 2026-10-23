package dev.regpack.evidence.sealed;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Test helper (T8): which operating system a test runs on, for the tests whose subject is a
 * platform-specific behaviour. The answer comes from the {@code os.name} system property, read
 * through a function that a test can replace (the seam), and never from the process environment. A
 * test that holds only on one platform asks this class, and has a counterpart that holds on the
 * other platform (its twin guards the opposite way), so that each platform runs one of the two.
 */
final class HostPlatform {
  private static final String PROPERTY = "os.name";

  private final String osName;

  private HostPlatform(String osName) {
    this.osName = osName;
  }

  /** The platform of this JVM: the system property {@code os.name}. */
  static HostPlatform current() {
    return of(System::getProperty);
  }

  /**
   * The platform that {@code properties} describes; a test passes a function of its own to see how
   * the guard reads a given name.
   *
   * @throws IllegalStateException when the function has no {@code os.name}: a platform is never
   *     guessed
   */
  static HostPlatform of(Function<String, String> properties) {
    Objects.requireNonNull(properties, "properties");
    String name = properties.apply(PROPERTY);
    if (name == null || name.isBlank()) {
      throw new IllegalStateException("the system property os.name is not set");
    }
    return new HostPlatform(name);
  }

  /** True for every name that starts with "Windows" in any case ({@code Locale.ROOT} folding). */
  boolean isWindows() {
    return osName.toLowerCase(Locale.ROOT).startsWith("windows");
  }

  String osName() {
    return osName;
  }
}
