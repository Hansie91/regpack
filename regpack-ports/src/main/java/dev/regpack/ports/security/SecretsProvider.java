package dev.regpack.ports.security;

/** Resolves {@link SecretRef}s. Credentials are references, never stored values. */
public interface SecretsProvider {
  boolean supports(String scheme);

  /**
   * Resolves the reference.
   *
   * @throws SecretNotFoundException if no secret is stored under the reference
   */
  SecretValue resolve(SecretRef ref);
}
