package dev.regpack.ports.crypto;

import dev.regpack.ports.security.KeyPurpose;
import dev.regpack.ports.security.KeyRef;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Chooses the envelope KEK that wraps the data keys of a write made at a given instant. The KEK is
 * the unit of crypto-shredding: everything wrapped under one KEK becomes unreadable together when
 * the KEK is destroyed.
 *
 * <p>A selector never names a KEK in a message: the refusals below carry fixed texts.
 */
@FunctionalInterface
public interface KekSelector {
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
    if (!Envelope.isKekId(kek.keyId())) {
      throw new IllegalArgumentException("the KEK id is not valid for an envelope header");
    }
    return at -> kek;
  }

  /**
   * One KEK per UTC calendar year of the instant: {@code prefix + "-" + year}, for example {@code
   * kek-2026}. The prefix has 1 to 90 characters of the KEK id alphabet. A year has four digits: an
   * instant outside the UTC years 1000 to 9999 has no KEK. The next year's KEK must exist in the
   * KMS before 1 January UTC.
   */
  static KekSelector perUtcYear(String prefix) {
    Objects.requireNonNull(prefix, "prefix");
    if (!isPrefix(prefix)) {
      throw new IllegalArgumentException("the KEK prefix is not valid for an envelope header");
    }
    return at -> {
      int year = at.atZone(ZoneOffset.UTC).getYear();
      if (year < 1000 || year > 9999) {
        throw new IllegalArgumentException("the instant has no four-digit UTC year");
      }
      return new KeyRef(prefix + "-" + year, KeyPurpose.ENVELOPE_KEK);
    };
  }

  /** A prefix leaves room for {@code -} and four digits in a KEK id of at most 100 characters. */
  private static boolean isPrefix(String prefix) {
    return prefix.length() <= 90 && Envelope.isKekId(prefix);
  }
}
