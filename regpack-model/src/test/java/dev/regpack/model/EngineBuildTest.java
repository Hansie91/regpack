package dev.regpack.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class EngineBuildTest {
  private static EngineBuild b(String text) {
    return EngineBuild.parse(text);
  }

  @Test
  void parseAndToStringRoundTrip() {
    for (String text :
        List.of("1.0.0+lib1", "12.34.56+lib789", "0.0.0+lib0", "9999.9999.9999+lib9999")) {
      EngineBuild parsed = b(text);
      assertThat(parsed.toString()).isEqualTo(text);
      assertThat(b(parsed.toString())).isEqualTo(parsed);
    }
    EngineBuild build = b("12.34.56+lib789");
    assertThat(build.major()).isEqualTo(12);
    assertThat(build.minor()).isEqualTo(34);
    assertThat(build.patch()).isEqualTo(56);
    assertThat(build.libraryVersion()).isEqualTo(789);
    assertThat(new EngineBuild(1, 0, 0, 1).toString()).isEqualTo("1.0.0+lib1");
  }

  @Test
  void rejectsMalformedText() {
    List<String> bad =
        List.of(
            "",
            "p0",
            "1.0.0",
            "1.0.0+lib",
            "1.0.0+lib01",
            "01.0.0+lib1",
            "1.00.0+lib1",
            "1.0.00+lib1",
            "1.0.0+LIB1",
            " 1.0.0+lib1",
            "1.0.0+lib1 ",
            "1.0.0+lib1\n",
            "-1.0.0+lib1",
            "1.0.0+lib-1",
            "1.0.0+lib1+x",
            "1.0+lib1",
            "1.0.0.0+lib1",
            "1..0+lib1",
            "١.0.0+lib1", // Arabic-Indic digit one
            "1.0.0+lib١",
            "１.0.0+lib1", // fullwidth digit one
            "1.0.0+lib１",
            "10000.0.0+lib1",
            "1.10000.0+lib1",
            "1.0.10000+lib1",
            "1.0.0+lib10000",
            "99999999999999999999.0.0+lib1");
    for (String text : bad) {
      assertThatThrownBy(() -> EngineBuild.parse(text))
          .as("parse(%s)", text.replace("\n", "\\n"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> EngineBuild.parse(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sameMajorAndLibraryAreReplayCompatible() {
    assertThat(b("1.0.0+lib1").replayCompatibleWith(b("1.7.3+lib1"))).isTrue();
    assertThat(b("1.0.0+lib1").replayCompatibleWith(b("1.0.0+lib1"))).isTrue();
  }

  @Test
  void differentMajorIsNotReplayCompatible() {
    assertThat(b("1.0.0+lib1").replayCompatibleWith(b("2.0.0+lib1"))).isFalse();
    assertThat(b("2.5.0+lib1").replayCompatibleWith(b("1.5.0+lib1"))).isFalse();
  }

  @Test
  void differentLibraryVersionIsNotReplayCompatible() {
    assertThat(b("1.0.0+lib1").replayCompatibleWith(b("1.0.0+lib2"))).isFalse();
    assertThat(b("1.3.4+lib2").replayCompatibleWith(b("1.3.4+lib1"))).isFalse();
  }

  @Test
  void replayCompatibilityIsSymmetric() {
    List<EngineBuild> builds =
        List.of(
            b("1.0.0+lib1"),
            b("1.7.3+lib1"),
            b("2.0.0+lib1"),
            b("1.0.0+lib2"),
            b("2.0.0+lib2"),
            b("2.1.0+lib2"));
    for (EngineBuild x : builds) {
      for (EngineBuild y : builds) {
        assertThat(x.replayCompatibleWith(y))
            .as("%s vs %s", x, y)
            .isEqualTo(y.replayCompatibleWith(x));
      }
    }
  }

  @Test
  void satisfiesMeansSameMajorSameLibraryAndNotOlder() {
    EngineBuild running = b("1.2.0+lib1");
    assertThat(running.satisfies(b("1.0.0+lib1"))).isTrue();
    assertThat(running.satisfies(b("1.2.0+lib1"))).isTrue();
    assertThat(running.satisfies(b("1.1.5+lib1"))).isTrue();
    assertThat(running.satisfies(b("1.2.1+lib1"))).isFalse();
    assertThat(running.satisfies(b("1.3.0+lib1"))).isFalse();
    assertThat(running.satisfies(b("2.0.0+lib1"))).isFalse();
    assertThat(running.satisfies(b("1.0.0+lib2"))).isFalse();
    // another major never satisfies, even when the running engine is newer in minor and patch
    assertThat(b("2.9.9+lib1").satisfies(b("1.0.0+lib1"))).isFalse();
  }

  @Test
  void constructorRejectsNegativeAndOversizedComponents() {
    assertThatThrownBy(() -> new EngineBuild(-1, 0, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EngineBuild(1, -1, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EngineBuild(1, 0, -1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EngineBuild(1, 0, 0, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EngineBuild(10_000, 0, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EngineBuild(1, 10_000, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EngineBuild(1, 0, 10_000, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EngineBuild(1, 0, 0, 10_000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new EngineBuild(0, 0, 0, 0)).isNotNull();
    assertThat(new EngineBuild(9999, 9999, 9999, 9999)).isNotNull();
  }

  @Test
  void parseNeverReturnsAnOutOfRangeBuild() {
    // the record constructor and parse agree on the range
    assertThat(b("9999.9999.9999+lib9999")).isEqualTo(new EngineBuild(9999, 9999, 9999, 9999));
  }
}
