package dev.regpack.ports.security;

import java.util.Objects;
import java.util.Set;

/**
 * A reference to a secret, resolved at run time and never written to the evidence log. The URI form
 * is {@code scheme://path}.
 */
public record SecretRef(String scheme, String path) {
  private static final Set<String> SCHEMES =
      Set.of("vault", "openbao", "awssm", "azkv", "gcpsm", "file", "env");

  public SecretRef {
    Objects.requireNonNull(scheme, "scheme");
    Objects.requireNonNull(path, "path");
    if (!SCHEMES.contains(scheme)) {
      throw new IllegalArgumentException("unknown secret scheme: " + scheme);
    }
    if (path.isEmpty()) {
      throw new IllegalArgumentException("empty secret path");
    }
  }

  /** Parses {@code scheme://path}; {@code "env://NAME"} gives {@code ("env", "NAME")}. */
  public static SecretRef parse(String uri) {
    Objects.requireNonNull(uri, "uri");
    int separator = uri.indexOf("://");
    if (separator <= 0) {
      throw new IllegalArgumentException("malformed secret reference: " + uri);
    }
    return new SecretRef(uri.substring(0, separator), uri.substring(separator + 3));
  }

  /** The {@code scheme://path} form. */
  public String uri() {
    return scheme + "://" + path;
  }
}
