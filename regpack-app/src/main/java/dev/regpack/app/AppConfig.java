package dev.regpack.app;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Process configuration: flags win over environment variables, which win over defaults. Port 0 asks
 * the operating system for an ephemeral port (used by tests).
 */
public record AppConfig(RoleSet roles, String bind, int port) {
  static final String DEFAULT_BIND = "127.0.0.1";
  static final int DEFAULT_PORT = 8080;
  private static final int MAX_PORT = 65535;

  public AppConfig {
    Objects.requireNonNull(roles, "roles");
    Objects.requireNonNull(bind, "bind");
    if (bind.isBlank()) {
      throw new IllegalArgumentException("bind address must not be blank");
    }
    if (port < 0 || port > MAX_PORT) {
      throw new IllegalArgumentException("port must be 0..65535, got " + port);
    }
  }

  /**
   * Reads {@code --roles}, {@code --bind} and {@code --port} (each followed by its value) and the
   * environment variables {@code REGPACK_ROLES}, {@code REGPACK_HTTP_BIND} and {@code
   * REGPACK_HTTP_PORT}. An unknown flag, a flag without a value or an invalid value is an {@link
   * IllegalArgumentException}.
   */
  public static AppConfig parse(List<String> args, Map<String, String> env) {
    Objects.requireNonNull(args, "args");
    Objects.requireNonNull(env, "env");
    String roles = env.get("REGPACK_ROLES");
    String bind = env.get("REGPACK_HTTP_BIND");
    String port = env.get("REGPACK_HTTP_PORT");
    for (int i = 0; i < args.size(); i++) {
      String flag = args.get(i);
      if (!flag.equals("--roles") && !flag.equals("--bind") && !flag.equals("--port")) {
        throw new IllegalArgumentException("unknown flag " + flag);
      }
      if (i + 1 >= args.size()) {
        throw new IllegalArgumentException("flag " + flag + " needs a value");
      }
      String value = args.get(++i);
      switch (flag) {
        case "--roles" -> roles = value;
        case "--bind" -> bind = value;
        default -> port = value;
      }
    }
    return new AppConfig(
        roles == null ? RoleSet.defaultRoles() : RoleSet.parse(roles),
        bind == null ? DEFAULT_BIND : bind,
        port == null ? DEFAULT_PORT : parsePort(port));
  }

  private static int parsePort(String text) {
    try {
      return Integer.parseInt(text.strip());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("port is not a number: " + text, e);
    }
  }
}
