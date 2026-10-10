package dev.regpack.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class HostileEnvironmentTest {
  @Test
  void testsRunUnderTurkishLocaleAndKiritimatiZone() {
    assertThat(Locale.getDefault().getLanguage()).isEqualTo("tr");
    assertThat(Locale.getDefault().getCountry()).isEqualTo("TR");
    assertThat(TimeZone.getDefault().getID()).isEqualTo("Pacific/Kiritimati");
    assertThat("TITLE".toLowerCase())
        .isEqualTo("tıtle"); // dotless i: default-locale casing is hostile
  }
}
