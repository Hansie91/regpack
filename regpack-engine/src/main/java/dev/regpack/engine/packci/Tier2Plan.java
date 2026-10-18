package dev.regpack.engine.packci;

import dev.regpack.model.EngineBuild;
import java.util.Objects;

/**
 * Where the Tier-2 explanation of a stored decision (the full re-evaluation trace) runs (spec 6.3):
 * on the current engine when the build that made the decision is replay-compatible with it, and
 * otherwise in a short-lived subprocess started from the archived build. P2a defines the rule
 * ({@link EngineBuild#replayCompatibleWith}); archived builds and the subprocess are P9b.
 *
 * <p>The decision fails closed: a stored build text that does not parse is never assumed to be
 * compatible.
 */
public enum Tier2Plan {
  /** The stored build is replay-compatible: re-evaluate on the running engine. */
  CURRENT_ENGINE,
  /** The stored build is not compatible, or not readable: only the archived build may replay. */
  ARCHIVED_BUILD_REQUIRED;

  /**
   * @param storedEngineBuild the {@code engine_build} text stored with the decision
   * @param current the running engine's build
   * @return {@link #CURRENT_ENGINE} iff the stored text parses and is replay-compatible
   */
  public static Tier2Plan decide(String storedEngineBuild, EngineBuild current) {
    Objects.requireNonNull(storedEngineBuild, "storedEngineBuild");
    Objects.requireNonNull(current, "current");
    try {
      return EngineBuild.parse(storedEngineBuild).replayCompatibleWith(current)
          ? CURRENT_ENGINE
          : ARCHIVED_BUILD_REQUIRED;
    } catch (IllegalArgumentException e) {
      return ARCHIVED_BUILD_REQUIRED;
    }
  }
}
