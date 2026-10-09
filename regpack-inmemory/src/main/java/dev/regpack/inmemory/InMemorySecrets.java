package dev.regpack.inmemory;

import dev.regpack.ports.security.SecretNotFoundException;
import dev.regpack.ports.security.SecretRef;
import dev.regpack.ports.security.SecretValue;
import dev.regpack.ports.security.SecretsProvider;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link SecretsProvider} over a map from URI to bytes. Thread-safe. */
public final class InMemorySecrets implements SecretsProvider {
  private static final Set<String> SCHEMES =
      Set.of("vault", "openbao", "awssm", "azkv", "gcpsm", "file", "env");

  private final Map<String, byte[]> secrets = new ConcurrentHashMap<>();

  /** Stores a copy of {@code value} under the canonical form of {@code uri}; returns this. */
  public InMemorySecrets put(String uri, byte[] value) {
    secrets.put(SecretRef.parse(uri).uri(), value.clone());
    return this;
  }

  @Override
  public boolean supports(String scheme) {
    return SCHEMES.contains(scheme);
  }

  @Override
  public SecretValue resolve(SecretRef ref) {
    Objects.requireNonNull(ref, "ref");
    byte[] stored = secrets.get(ref.uri());
    if (stored == null) {
      throw new SecretNotFoundException("no secret for " + ref.uri());
    }
    return SecretValue.of(stored);
  }
}
