package dev.regpack.pack.model;

import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One declared reason code (spec 6.3, BRK-11). The registry stores the outcome the code stands for,
 * not only the code: a table row that uses the code must have this outcome ({@code
 * REASON_OUTCOME_MISMATCH}).
 *
 * @param code the full code, {@code <PREFIX>.<node>.<label>[.<provision>]}
 * @param label the label segment of the code (the third segment)
 * @param category what kind of reason this is
 * @param defaultOutcome the outcome the code implies
 * @param owner who owns the reason (an identifier, not a name)
 * @param validFrom first UTC date the reason may be used (inclusive)
 * @param validTo end of the reason's use (exclusive), if any
 */
public record ReasonEntry(
    ReasonCode code,
    String label,
    Category category,
    Outcome defaultOutcome,
    String owner,
    LocalDate validFrom,
    Optional<LocalDate> validTo) {
  /** The kind of a reason; exact names in {@code reasons.yaml}. */
  public enum Category {
    EXCLUSION,
    INCLUSION,
    OUT_OF_SCOPE,
    DATA_QUALITY,
    DEFAULT
  }

  public ReasonEntry {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(defaultOutcome, "defaultOutcome");
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(validFrom, "validFrom");
    Objects.requireNonNull(validTo, "validTo");
  }
}
