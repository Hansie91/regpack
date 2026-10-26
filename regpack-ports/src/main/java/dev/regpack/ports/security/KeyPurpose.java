package dev.regpack.ports.security;

/** What a KMS key may be used for. */
public enum KeyPurpose {
  PACK_SIGNING,
  MANIFEST_SIGNING,
  TRN_HMAC,
  ENVELOPE_KEK,
  /** HMAC-SHA-256 key of the data-subject index (keyed hashes of identifiers). */
  INDEX_HMAC
}
