package dev.regpack.model;

import java.util.Objects;

/**
 * The one check for every text that becomes a key, an identity, a name, a holder, a business key,
 * an action or a file name of the ledger (FP2). A ledger adapter on PostgreSQL cannot store some
 * values that Java accepts, and the in-memory adapter must refuse exactly what the real store
 * refuses or alters, so the rule lives here and both adapters get it through the value types:
 *
 * <ul>
 *   <li>U+0000 is refused: PostgreSQL {@code text} cannot hold it (SQLSTATE 22021) and a batch that
 *       carries one would fail on every redelivery. All other C0 control characters (U+0001 to
 *       U+001F) and U+007F are refused with it: no identifier of a reporting record needs one, and
 *       the JSON reader refuses the raw ones in a body too.
 *   <li>An unpaired surrogate is refused: the driver encodes it as {@code ?}, so two distinct ids
 *       would collapse into one stored key.
 *   <li>A length above the bound is refused (counted in UTF-16 chars). A btree entry of PostgreSQL
 *       is limited to about 2,704 bytes (SQLSTATE 54000). One UTF-16 char is at most three bytes of
 *       UTF-8 (a surrogate pair is four bytes for two chars), so the bounds give, for the longest
 *       composite index rows: {@link #MAX_EVENT_IDENTITY_CHARS} chars is at most 1,536 bytes, and
 *       the row of {@code decision_idem_key} (regime id, event identity, context id, digest, day)
 *       is at most about 1,610 bytes; {@code ingest_pk} holds one text of {@link #MAX_CHARS} chars
 *       (768 bytes); {@code endpoint_key} holds four texts of {@link #MAX_ENDPOINT_PART_CHARS}
 *       chars (1,536 bytes); {@code delivery_key} holds one text of {@link #MAX_BUSINESS_KEY_CHARS}
 *       chars (1,536 bytes). Every row stays well below 2,000 bytes.
 * </ul>
 *
 * <p>The messages name the field and the rule and never echo the value.
 */
public final class KeyText {
  /** The bound of an ordinary key text: record id, source, stream, holder, LEI, action, file. */
  public static final int MAX_CHARS = 256;

  /** The bound of a whole event identity (all parts and the separators). */
  public static final int MAX_EVENT_IDENTITY_CHARS = 512;

  /** The bound of one field of an endpoint key (four of them make one index row). */
  public static final int MAX_ENDPOINT_PART_CHARS = 128;

  /** The bound of a business key of a delivery. */
  public static final int MAX_BUSINESS_KEY_CHARS = 512;

  /** The bound of a regime identifier. */
  public static final int MAX_REGIME_CHARS = 128;

  private KeyText() {}

  /** {@link #require(String, String, int)} with the bound {@link #MAX_CHARS}. */
  public static String require(String field, String value) {
    return require(field, value, MAX_CHARS);
  }

  /**
   * Returns {@code value} when it holds no control character, no unpaired surrogate and at most
   * {@code maxChars} UTF-16 chars.
   *
   * @throws NullPointerException if {@code value} is null: whether a null or a blank is an error of
   *     its own is for the caller to decide before it calls this
   * @throws IllegalArgumentException with a message that names {@code field} and the rule only
   */
  public static String require(String field, String value, int maxChars) {
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(value, field);
    if (value.length() > maxChars) {
      throw new IllegalArgumentException(
          field + " must be at most " + maxChars + " characters, not " + value.length());
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 0x20 || c == 0x7F) {
        throw new IllegalArgumentException(
            field + " must not contain a control character (found U+" + hex4(c) + ")");
      }
      if (Character.isHighSurrogate(c)
          && i + 1 < value.length()
          && Character.isLowSurrogate(value.charAt(i + 1))) {
        i++;
      } else if (Character.isSurrogate(c)) {
        throw new IllegalArgumentException(field + " must not contain an unpaired surrogate");
      }
    }
    return value;
  }

  /** Four upper-case hex digits without any locale-dependent formatting. */
  private static String hex4(char c) {
    String digits = "0123456789ABCDEF";
    return ""
        + digits.charAt(c >> 12 & 0xF)
        + digits.charAt(c >> 8 & 0xF)
        + digits.charAt(c >> 4 & 0xF)
        + digits.charAt(c & 0xF);
  }
}
