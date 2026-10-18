package dev.regpack.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identifies one slice of a landed file: {@code <digest hex>:<slice>}.
 *
 * <p>The constructor accepts any non-blank text (an outbox subject id is stored as text and the
 * ledger contract suites use short tags); {@link #landingDigest()} and {@link #slice()} accept only
 * the structured form that {@link #of} writes: 64 lowercase hex characters, a colon and a
 * non-negative decimal slice without a sign and without leading zeros ({@link #slice()} also needs
 * it within the {@code int} range). Any other value throws {@link IllegalArgumentException}.
 */
public record BatchId(String value) {
  private static final Pattern STRUCTURED = Pattern.compile("([0-9a-f]{64}):(0|[1-9][0-9]*)");

  public BatchId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("BatchId must not be blank");
    }
  }

  public static BatchId of(Sha256 landingDigest, int slice) {
    if (slice < 0) {
      throw new IllegalArgumentException("slice must be >= 0");
    }
    return new BatchId(landingDigest.hex() + ":" + slice);
  }

  /** The digest of the landed file. */
  public Sha256 landingDigest() {
    return new Sha256(structured().group(1));
  }

  /** The slice number within the landed file. */
  public int slice() {
    String digits = structured().group(2);
    try {
      return Integer.parseInt(digits);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("BatchId slice is out of range: " + value, e);
    }
  }

  private Matcher structured() {
    Matcher matcher = STRUCTURED.matcher(value);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("BatchId is not '<64 hex>:<slice>': " + value);
    }
    return matcher;
  }
}
