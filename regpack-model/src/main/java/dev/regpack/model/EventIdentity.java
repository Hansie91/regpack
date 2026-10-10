package dev.regpack.model;

/** The identity of a business event; the parts joined with '|'. */
public record EventIdentity(String value) {
  public EventIdentity {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("EventIdentity must not be blank");
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
