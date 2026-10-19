package dev.regpack.ports.evidence.journal;

import dev.regpack.ports.ledger.LeaseLedger;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The scope of a journal object: the word {@code control} (pack and batch objects that belong to no
 * endpoint) or the four parts of an endpoint, {@code <environment>,<route>,<lei>,<authority>}. Each
 * part is 1 to 24 characters of {@code [A-Za-z0-9._-]}, so the text is decodable, injective, at
 * most 99 characters and one {@code EvidenceKey} segment (assumption A9). The messages name the
 * field and the rule and never echo the value.
 */
public record JournalScope(String value) {
  private static final String CONTROL = "control";
  private static final int MAX_PART_CHARS = 24;
  private static final Pattern PART = Pattern.compile("[A-Za-z0-9._-]{1,24}");
  private static final String RULE =
      " must be 1 to " + MAX_PART_CHARS + " characters of [A-Za-z0-9._-]";

  public JournalScope {
    Objects.requireNonNull(value, "value");
    if (!value.equals(CONTROL)) {
      String[] parts = value.split(",", -1);
      if (parts.length != 4) {
        throw new IllegalArgumentException(
            "a journal scope is control or four comma-separated parts");
      }
      for (int i = 0; i < parts.length; i++) {
        requirePart("scope part " + (i + 1), parts[i]);
      }
    }
  }

  public static JournalScope control() {
    return new JournalScope(CONTROL);
  }

  /** The scope of an endpoint; a part outside the alphabet is refused and named. */
  public static JournalScope of(LeaseLedger.EndpointKey endpoint) {
    Objects.requireNonNull(endpoint, "endpoint");
    requirePart("environment", endpoint.environment());
    requirePart("submissionRoute", endpoint.submissionRoute());
    requirePart("submittingEntityLei", endpoint.submittingEntityLei());
    requirePart("destinationAuthority", endpoint.destinationAuthority());
    return new JournalScope(
        String.join(
            ",",
            endpoint.environment(),
            endpoint.submissionRoute(),
            endpoint.submittingEntityLei(),
            endpoint.destinationAuthority()));
  }

  public boolean isControl() {
    return value.equals(CONTROL);
  }

  private static void requirePart(String field, String part) {
    if (!PART.matcher(part).matches()) {
      throw new IllegalArgumentException(field + RULE);
    }
  }
}
