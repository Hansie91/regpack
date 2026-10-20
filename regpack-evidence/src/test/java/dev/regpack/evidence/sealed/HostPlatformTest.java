package dev.regpack.evidence.sealed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * T8: the guard of the platform-specific tests (see {@link HostPlatform}). The tests run under the
 * hostile default locale of the build (Turkish), where a case fold without {@code Locale.ROOT}
 * would miss "WINDOWS".
 */
class HostPlatformTest {
  private static HostPlatform platformCalled(String osName) {
    Map<String, String> properties = new HashMap<>();
    properties.put("os.name", osName);
    return HostPlatform.of(properties::get);
  }

  @Test
  void everyWindowsNameIsRecognisedInAnyCase() {
    for (String name :
        List.of("Windows 11", "Windows Server 2025", "WINDOWS 10", "windows 7", "Windows 95")) {
      assertThat(platformCalled(name).isWindows()).as(name).isTrue();
    }
  }

  @Test
  void theOtherNamesAreNotWindows() {
    for (String name : List.of("Linux", "Mac OS X", "FreeBSD", "SunOS", "AIX", "Darwin")) {
      assertThat(platformCalled(name).isWindows()).as(name).isFalse();
    }
  }

  @Test
  void aPlatformIsNeverGuessed() {
    assertThatIllegalStateException().isThrownBy(() -> HostPlatform.of(key -> null));
    assertThatIllegalStateException().isThrownBy(() -> platformCalled(""));
    assertThatIllegalStateException().isThrownBy(() -> platformCalled("   "));
  }

  @Test
  void theGuardReadsOnlyTheOsNameProperty() {
    List<String> asked = new ArrayList<>();
    HostPlatform.of(
        key -> {
          asked.add(key);
          return "Linux";
        });

    assertThat(asked).containsExactly("os.name");
  }

  @Test
  void theGuardAgreesWithWhatTheFileSystemOfThisMachineCanDo() {
    HostPlatform platform = HostPlatform.current();
    boolean posixView = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    // a wrong answer here would make a platform-specific test skip its own platform silently
    assertThat(platform.isWindows()).as("os.name is %s", platform.osName()).isEqualTo(!posixView);
    assertThat(FileOps.real().supportsDirectorySync()).isEqualTo(!platform.isWindows());
  }
}
