package dev.regpack.ports.crypto;

import dev.regpack.ports.security.KeyPurpose;
import dev.regpack.ports.security.KeyRef;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Chooses the envelope KEK that wraps the data keys of a write made at a given instant. The KEK is
 * the unit of crypto-shredding: everything wrapped under one KEK becomes unreadable together when
 * the KEK is destroyed.
 *
 * <p>A selector never names a KEK in a message: the refusals below carry fixed texts.
 */
@FunctionalInterface
public interface KekSelector {
  /** KEK ids are limited to this alphabet and length (the envelope header carries the id). */
  Pattern KEK_ID = Pattern.compile("[A-Za-z0-9._-]{1,100}");

  Pattern PREFIX = Pattern.compile("[A-Za-z0-9._-]{1,90}");

  /** The first and last UTC year {@link #perUtcYear} accepts: a year has four digits. */
  int FIRST_YEAR = 1000;

  int LAST_YEAR = 9999;

  /**
   * The KEK for a write at {@code at}.
   *
   * @throws IllegalArgumentException if the selector has no KEK for that instant
   */
  KeyRef kekAt(Instant at);

  /** One KEK for every instant: all-or-nothing destruction. */
  static KekSelector single(KeyRef kek) {
    Objects.requireNonNull(kek, "kek");
    if (kek.purpose() != KeyPurpose.ENVELOPE_KEK) {
      throw new IllegalArgumentException("a KEK selector needs an ENVELOPE_KEK key");
    }
    if (!KEK_ID.matcher(kek.keyId()).matches()) {
      throw new IllegalArgumentException("the KEK id is not valid for an envelope header");
    }
    return at -> kek;
  }

  /**
   * One KEK per UTC calendar year of the instant: {@code prefix + "-" + year}, for example {@code
   * kek-2026}. The next year's KEK must exist in the KMS before 1 January UTC.
   */
  static KekSelector perUtcYear(String prefix) {
    Objects.requireNonNull(prefix, "prefix");
    if (!PREFIX.matcher(prefix).matches()) {
      throw new IllegalArgumentException("the KEK prefix is not valid for an envelope header");
    }
    return at -> {
      int year = at.atZone(ZoneOffset.UTC).getYear();
      if (year < FIRST_YEAR || year > LAST_YEAR) {
        throw new IllegalArgumentException("the instant has no four-digit UTC year");
      }
      return new KeyRef(prefix + "-" + year, KeyPurpose.ENVELOPE_KEK);
    };
  }
}
