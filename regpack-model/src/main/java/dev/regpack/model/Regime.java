package dev.regpack.model;

import java.util.regex.Pattern;

/** A reporting regime identifier, lower-case dotted, for example {@code mifir.eu.rts22}. */
public record Regime(String id) {
  private static final Pattern SHAPE = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z0-9]+)*");

  public Regime {
    if (id == null || !SHAPE.matcher(id).matches()) {
      throw new IllegalArgumentException("Regime id must be lower-case dotted: " + id);
    }
  }
}
