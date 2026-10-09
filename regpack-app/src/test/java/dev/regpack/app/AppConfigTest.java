package dev.regpack.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppConfigTest {
  @Test
  void defaultsAreLoopbackPort8080AllRoles() {
    AppConfig config = AppConfig.parse(List.of(), Map.of());
    assertThat(config.roles()).isEqualTo(RoleSet.defaultRoles());
    assertThat(config.bind()).isEqualTo("127.0.0.1");
    assertThat(config.port()).isEqualTo(8080);
  }

  @Test
  void flagsOverrideEnvironment() {
    Map<String, String> env =
        Map.of(
            "REGPACK_ROLES", "ingest",
            "REGPACK_HTTP_BIND", "0.0.0.0",
            "REGPACK_HTTP_PORT", "9000");
    AppConfig fromEnv = AppConfig.parse(List.of(), env);
    assertThat(fromEnv.roles()).isEqualTo(RoleSet.parse("ingest"));
    assertThat(fromEnv.bind()).isEqualTo("0.0.0.0");
    assertThat(fromEnv.port()).isEqualTo(9000);

    AppConfig flags =
        AppConfig.parse(List.of("--roles", "engine", "--bind", "::1", "--port", "0"), env);
    assertThat(flags.roles()).isEqualTo(RoleSet.parse("engine"));
    assertThat(flags.bind()).isEqualTo("::1");
    assertThat(flags.port()).isZero();
  }

  @Test
  void aFlagWithoutAValueIsRejected() {
    assertThatThrownBy(() -> AppConfig.parse(List.of("--port"), Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--port")
        .hasMessageContaining("needs a value");
    assertThatThrownBy(() -> AppConfig.parse(List.of("--bind", "::1", "--roles"), Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--roles");
  }

  @Test
  void anUnknownFlagIsRejected() {
    assertThatThrownBy(() -> AppConfig.parse(List.of("--bogus", "x"), Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown flag --bogus");
  }

  @Test
  void invalidPortIsRejected() {
    assertThatThrownBy(() -> AppConfig.parse(List.of("--port", "70000"), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AppConfig.parse(List.of("--port", "abc"), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AppConfig.parse(List.of(), Map.of("REGPACK_HTTP_PORT", "-1")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
