package dev.regpack.app;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/** A role a process can take on; roles are selected with {@code --roles} (spec 5.1). */
public enum Role {
  INGEST,
  ENGINE,
  ASSEMBLER,
  SENDER,
  FEEDBACK,
  TIMEKEEPER,
  CONTROL_PLANE,
  CONSOLE,
  SIGNER;

  /** Lower case with '_' replaced by '-', for example {@code control-plane}. */
  public String flag() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /** Parses a flag; an unknown value fails with a message listing every allowed flag. */
  public static Role parse(String flag) {
    Objects.requireNonNull(flag, "flag");
    String normalised = flag.strip().toLowerCase(Locale.ROOT);
    for (Role role : values()) {
      if (role.flag().equals(normalised)) {
        return role;
      }
    }
    throw new IllegalArgumentException("unknown role '" + flag + "'; allowed: " + allowedFlags());
  }

  static String allowedFlags() {
    return Arrays.stream(values()).map(Role::flag).collect(Collectors.joining(", "));
  }
}
