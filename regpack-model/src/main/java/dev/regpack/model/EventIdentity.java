package dev.regpack.model;

/**
 * The identity of a business event; the parts joined with '|'. No '|'-separated part may be empty
 * or blank, so a value read back from a row (a decoder calls the constructor) cannot be {@code
 * a||b}, {@code |a} or {@code a|}.
 */
public record EventIdentity(String value) {
  public EventIdentity {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("EventIdentity must not be blank");
    }
    int start = 0;
    while (true) {
      int bar = value.indexOf('|', start);
      String part = bar < 0 ? value.substring(start) : value.substring(start, bar);
      if (part.isBlank()) {
        throw new IllegalArgumentException("EventIdentity has an empty or blank part: " + value);
      }
      if (bar < 0) {
        break;
      }
      start = bar + 1;
    }
  }

  public static EventIdentity of(String... parts) {
    if (parts == null || parts.length == 0) {
      throw new IllegalArgumentException("EventIdentity needs at least one part");
    }
    for (String part : parts) {
      if (part == null || part.isBlank() || part.indexOf('|') >= 0) {
        throw new IllegalArgumentException(
            "EventIdentity parts must be non-blank and free of '|': " + part);
      }
    }
    return new EventIdentity(String.join("|", parts));
  }
}
