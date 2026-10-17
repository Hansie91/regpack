package dev.regpack.pack.model;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * The legal validity window of a row: the UTC dates {@code [from, to)}; no {@code to} means open
 * ended. The constructor does not check the order of the two dates: the table checks report an
 * inverted window ({@code VALIDITY_INVERTED}).
 */
public record Validity(LocalDate from, Optional<LocalDate> to) {
  public Validity {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
  }
}
