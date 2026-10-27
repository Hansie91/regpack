package dev.regpack.ports.ledger;

import dev.regpack.model.Regime;
import dev.regpack.ports.evidence.EvidenceKey;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The ledger's {@code retention} rows (spec 11.6, {@code retention(artifact, regime, anchor_event,
 * retain_until, legal_hold)}): one row per (artifact, regime, anchor event), where the artifact is
 * an evidence key. The date of a row is extend-only, the legal hold is one flag per artifact, and
 * the expiry list names the rows that are due. Nothing here deletes a row (the expiry job is P4).
 *
 * <p>The ledger stores a row for any evidence key. The exemption of epoch markers ({@link
 * #EXEMPT_PREFIX}) is a rule of the expiry list ({@link #expiredRetention}) and of the engine that
 * registers claims, not of the storage.
 *
 * <p>Order. Keys are compared as text by UTF-16 code unit (byte order for the alphabet of an {@link
 * EvidenceKey}, as in {@code EvidenceStore#list}), regime ids by the same rule, and anchor events
 * by their codes (OBJECT_WRITTEN before TERMINATION); an adapter must not use a locale or collation
 * order.
 */
public interface RetentionLedger {
  /**
   * The most artifacts that one {@link #retentionRows} call and the most rows that one {@link
   * #expiredRetention} call return. (Not {@code MAX_ROWS_PER_CALL}: {@link ManifestLedger} has that
   * name and an adapter implements both.)
   */
  int MAX_RETENTION_ROWS_PER_CALL = 1_000;

  /** Keys that start with this prefix (epoch markers) are never in the expiry list. */
  String EXEMPT_PREFIX = "control/";

  /** The event that starts the retention period. Codes 1 and 2 in this order. */
  enum AnchorEvent {
    OBJECT_WRITTEN,
    TERMINATION
  }

  /**
   * What to record. {@code retainUntil} is empty while the anchor is not known (for example the
   * termination of an EMIR trade); a value has whole microseconds and a year from 0000 to 9999.
   * {@code retentionClass} is 1 to 100 characters of {@code [A-Za-z0-9._-]}.
   *
   * @throws IllegalArgumentException when a value is outside these rules; the message names the
   *     rule and holds no value
   */
  record RetentionClaim(
      EvidenceKey artifact,
      Regime regime,
      AnchorEvent anchor,
      Optional<Instant> retainUntil,
      String retentionClass) {
    private static final Instant FIRST_YEAR_0000 = Instant.parse("0000-01-01T00:00:00Z");
    private static final Instant LAST_YEAR_9999 = Instant.parse("9999-12-31T23:59:59.999999Z");
    private static final Pattern CLASS = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    public RetentionClaim {
      Objects.requireNonNull(artifact, "artifact");
      Objects.requireNonNull(regime, "regime");
      Objects.requireNonNull(anchor, "anchor");
      Objects.requireNonNull(retainUntil, "retainUntil");
      Objects.requireNonNull(retentionClass, "retentionClass");
      if (retainUntil.isPresent()) {
        requireStorable("retainUntil", retainUntil.get());
      }
      if (!CLASS.matcher(retentionClass).matches()) {
        throw new IllegalArgumentException(
            "retentionClass must be 1 to 100 characters of A-Z a-z 0-9 . _ -");
      }
    }

    /**
     * Refuses an instant that no store can hold: one with sub-microsecond precision, or one with a
     * year outside 0000 to 9999. Every adapter calls it, as the record does for a retain-until,
     * before it binds an instant that a caller supplied (the {@code asOf} of {@link
     * RetentionLedger#expiredRetention} among them; {@code PgRetention} too). The messages name
     * {@code field} and the rule and hold no value.
     *
     * @param field the name of the parameter, for the message
     * @return {@code instant}
     * @throws NullPointerException when {@code field} or {@code instant} is null
     * @throws IllegalArgumentException when the instant is outside these rules
     */
    public static Instant requireStorable(String field, Instant instant) {
      Objects.requireNonNull(field, "field");
      Objects.requireNonNull(instant, "instant");
      if (instant.getNano() % 1_000 != 0) {
        throw new IllegalArgumentException(field + " must have whole microseconds");
      }
      if (instant.isBefore(FIRST_YEAR_0000) || instant.isAfter(LAST_YEAR_9999)) {
        throw new IllegalArgumentException(field + " year must be from 0000 to 9999");
      }
      return instant;
    }
  }

  /**
   * A stored row: the claim as merged, the hold of its artifact and the ledger time of its birth.
   */
  record RetentionRow(RetentionClaim claim, boolean legalHold, Instant recordedAt) {
    public RetentionRow {
      Objects.requireNonNull(claim, "claim");
      Objects.requireNonNull(recordedAt, "recordedAt");
    }
  }

  /**
   * Records the claims in one unit, all or nothing. A claim for a stored (artifact, regime, anchor)
   * merges into the row: {@code retainUntil} becomes the later of the two (a value replaces an
   * empty one, an empty one never replaces a value), the class and {@code recordedAt} stay. A new
   * row takes the legal hold of its artifact (held when any row of the artifact is held) and has
   * {@code recordedAt} = the ledger clock. An empty list does nothing. A hold and a claim for the
   * same artifact racing are serialised: afterwards every row of the artifact has the hold.
   *
   * @throws IllegalArgumentException when two claims of the list have one (artifact, regime,
   *     anchor); nothing is stored
   */
  void recordRetention(List<RetentionClaim> claims);

  /**
   * Sets the legal hold of every row of {@code artifact}. Setting it to the value it has is not an
   * error.
   *
   * @throws IllegalArgumentException when the artifact has no row
   */
  void setRetentionHold(EvidenceKey artifact, boolean hold);

  /**
   * The rows of the artifacts whose key starts with {@code prefix} and sorts strictly after {@code
   * after}, in the order (artifact, regime, anchor): all the rows of at most {@code min(limit,
   * MAX_RETENTION_ROWS_PER_CALL)} artifacts, so that the rows of one artifact are never split
   * across two calls. The prefix need not end at a segment boundary and may be empty; {@code after}
   * is an artifact (not a row) and need not exist.
   *
   * <p>A prefix is the start of a key, not a part of it anywhere. {@code after} is strictly after,
   * also when it equals the prefix. A prefix that no key can have (U+0000, or a character outside
   * the alphabet of an {@link EvidenceKey}) lists nothing. {@code after} is a position (an
   * artifact), not a cursor: an artifact recorded meanwhile that sorts at or before it is not
   * returned.
   *
   * @throws IllegalArgumentException when {@code limit} is negative
   */
  List<RetentionRow> retentionRows(String prefix, Optional<EvidenceKey> after, int limit);

  /**
   * The rows that can expire as of {@code asOf}: {@code retainUntil <= asOf}, not held, the key not
   * under {@link #EXEMPT_PREFIX}. Per row, not per artifact: an artifact expires only when every
   * row of it is due and none is held or open (the job of P4 decides). Ordered by (retain-until,
   * artifact, regime, anchor), strictly after the row {@code after}, at most {@code min(limit,
   * MAX_RETENTION_ROWS_PER_CALL)} rows. A row with an empty date is never due.
   *
   * <p>A snapshot per call. Over a walk every row that is due and unchanged for the whole walk is
   * listed exactly once; a row recorded, extended or held meanwhile may be missed or listed twice.
   * The list is advisory: a caller that acts on a row irreversibly re-reads the rows of the
   * artifact ({@link #retentionRows}) and the hold of the store immediately before it acts.
   *
   * @throws IllegalArgumentException when {@code limit} is negative, when {@code asOf} has
   *     sub-microsecond precision or a year outside 0000 to 9999 (the rule of a retain-until, see
   *     {@link RetentionClaim#requireStorable}), or when {@code after} has an empty date (such a
   *     row is never in the list)
   */
  List<RetentionRow> expiredRetention(Instant asOf, Optional<RetentionRow> after, int limit);
}
