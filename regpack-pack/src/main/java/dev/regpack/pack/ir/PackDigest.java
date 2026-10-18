package dev.regpack.pack.ir;

import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import dev.regpack.pack.model.LoadedPack;

/**
 * The content address of a pack (spec 6.1: SHA-256 of the canonical form): the SHA-256 of the
 * canonical JSON of {@link PackIr#toMap}. It covers parsed content only, so comments, key order,
 * row order, line endings and the operating system do not change it, and every evaluated field
 * does. {@code CHECKSUMS} and {@code ATTESTATION} are not part of it (provisional until a G-S3b
 * pass; P9b defines the signed digest).
 */
public final class PackDigest {
  private PackDigest() {}

  public static Sha256 of(LoadedPack pack) {
    return Sha256.ofUtf8(Json.canonical(PackIr.toMap(pack)));
  }
}
