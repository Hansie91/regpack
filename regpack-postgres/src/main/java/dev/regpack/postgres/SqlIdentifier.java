package dev.regpack.postgres;

import java.util.regex.Pattern;

/**
 * The only way a schema or table name reaches SQL text (Convention 12): every value is a bind
 * parameter, and the few identifiers that cannot be parameters pass through {@link #require}.
 */
public final class SqlIdentifier {
  private static final Pattern PATTERN = Pattern.compile("[a-z][a-z0-9_]{0,62}");

  private SqlIdentifier() {}

  /**
   * Returns the identifier unchanged when it matches {@code [a-z][a-z0-9_]{0,62}}.
   *
   * @throws IllegalArgumentException for anything else, including null; the message does not repeat
   *     the rejected text
   */
  public static String require(String identifier) {
    if (identifier == null || !PATTERN.matcher(identifier).matches()) {
      throw new IllegalArgumentException(
          "not a valid SQL identifier (lowercase letter, then lowercase letters, digits or"
              + " underscores, 63 characters at most)");
    }
    return identifier;
  }
}
