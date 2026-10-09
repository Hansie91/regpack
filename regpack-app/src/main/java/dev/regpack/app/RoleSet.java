package dev.regpack.app;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The roles one process runs. {@code SIGNER} runs in its own process (spec 5.2), so it is only
 * valid alone and is never part of {@code all}.
 */
public record RoleSet(Set<Role> roles) {
  private static final String ALL = "all";

  public RoleSet {
    Objects.requireNonNull(roles, "roles");
    if (roles.isEmpty()) {
      throw new IllegalArgumentException("at least one role is required");
    }
    EnumSet<Role> copy = EnumSet.copyOf(roles);
    if (copy.contains(Role.SIGNER) && copy.size() > 1) {
      throw new IllegalArgumentException("role signer must run alone");
    }
    roles = Collections.unmodifiableSet(copy);
  }

  /**
   * Parses a comma separated list of role flags, or {@code all} (every role except SIGNER). Empty,
   * unknown and duplicate entries are rejected.
   */
  public static RoleSet parse(String csv) {
    Objects.requireNonNull(csv, "csv");
    String text = csv.strip();
    if (text.toLowerCase(Locale.ROOT).equals(ALL)) {
      return defaultRoles();
    }
    EnumSet<Role> parsed = EnumSet.noneOf(Role.class);
    for (String part : text.split(",", -1)) {
      if (part.isBlank()) {
        throw new IllegalArgumentException(
            "empty role in '" + csv + "'; allowed: " + Role.allowedFlags() + " or all");
      }
      Role role = Role.parse(part);
      if (!parsed.add(role)) {
        throw new IllegalArgumentException("duplicate role " + role.flag());
      }
    }
    return new RoleSet(parsed);
  }

  /** Every role except SIGNER. */
  public static RoleSet defaultRoles() {
    EnumSet<Role> all = EnumSet.allOf(Role.class);
    all.remove(Role.SIGNER);
    return new RoleSet(all);
  }
}
