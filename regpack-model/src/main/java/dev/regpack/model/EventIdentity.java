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
    // FP2: no control character, no unpaired surrogate, a bounded length (checked before any
    // message could echo the value)
    KeyText.require("EventIdentity", value, KeyText.MAX_EVENT_IDENTITY_CHARS);
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
      if (part == null) {
        throw new IllegalArgumentException("EventIdentity parts must not be null");
      }
      KeyText.require("EventIdentity part", part, KeyText.MAX_EVENT_IDENTITY_CHARS);
      if (part.isBlank() || part.indexOf('|') >= 0) {
        throw new IllegalArgumentException("EventIdentity parts must be non-blank and free of '|'");
      }
    }
    return new EventIdentity(String.join("|", parts));
  }
}
