package dev.regpack.spikes.r.bench;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.GeneratedRule;
import dev.regpack.bench.data.PackShape;
import dev.regpack.model.json.Json;
import dev.regpack.model.json.JsonException;
import dev.regpack.ports.expr.Expression;
import dev.regpack.spikes.r.table.CompiledPack;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The active rule pack of a running evaluator and the one way to replace it (spec 6.5 step 6).
 * {@link #activate} verifies the Ed25519 signature of the IR bytes first, then parses, compiles and
 * checks the pack, and only then swaps it in with one atomic write; an evaluator reads {@link
 * #current()} once per batch and keeps that pack for the whole batch. A refused pack leaves the
 * active one untouched.
 */
public final class PackHolder {
  /** A compiled pack and its position in the sequence of activations (0 for the first). */
  public record Active(CompiledPack pack, long generation) {
    public Active {
      Objects.requireNonNull(pack, "pack");
    }
  }

  private final AtomicReference<Active> active;

  public PackHolder(CompiledPack initial) {
    this.active = new AtomicReference<>(new Active(initial, 0));
  }

  /** The pack to pin for one batch. */
  public Active current() {
    return active.get();
  }

  /**
   * Replaces the active pack with the signed one.
   *
   * @return true when the pack was activated; false (and nothing changed) when the signature does
   *     not verify, the bytes are not an IR document, or the pack does not compile or does not pass
   *     the pack check (unique priorities, one catch-all last in every table)
   */
  public boolean activate(byte[] irBytes, byte[] signature, PublicKey publicKey, Expression layer) {
    Objects.requireNonNull(irBytes, "irBytes");
    Objects.requireNonNull(signature, "signature");
    Objects.requireNonNull(publicKey, "publicKey");
    Objects.requireNonNull(layer, "layer");
    if (!verified(irBytes, signature, publicKey)) {
      return false;
    }
    CompiledPack compiled;
    try {
      compiled = CompiledPack.compile(parseIr(irBytes), layer);
    } catch (IllegalArgumentException e) {
      return false;
    }
    Active previous;
    Active next;
    do {
      previous = active.get();
      next = new Active(compiled, previous.generation() + 1);
    } while (!active.compareAndSet(previous, next));
    return true;
  }

  private static boolean verified(byte[] data, byte[] signature, PublicKey key) {
    try {
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(key);
      verifier.update(data);
      return verifier.verify(signature);
    } catch (GeneralSecurityException e) {
      return false;
    }
  }

  /**
   * Reads the canonical IR of {@link GeneratedPack#irJson()}. The shape is not part of the IR; the
   * pack that is returned says {@code S1_FIRST_HIT_REALISTIC} and its seed is 0.
   *
   * @throws IllegalArgumentException if the bytes are not a list of tables of well-formed rows
   */
  public static GeneratedPack parseIr(byte[] irBytes) {
    Object parsed;
    try {
      parsed = Json.parse(new String(irBytes, StandardCharsets.UTF_8));
    } catch (JsonException e) {
      throw new IllegalArgumentException("the pack IR is not JSON: " + e.getMessage(), e);
    }
    if (!(parsed instanceof List<?> tables) || tables.isEmpty()) {
      throw new IllegalArgumentException("the pack IR is not a non-empty list of tables");
    }
    List<List<GeneratedRule>> out = new ArrayList<>();
    int count = 0;
    for (Object table : tables) {
      if (!(table instanceof List<?> rows)) {
        throw new IllegalArgumentException("a table of the pack IR is not a list");
      }
      List<GeneratedRule> parsedRows = new ArrayList<>();
      for (Object row : rows) {
        if (!(row instanceof Map<?, ?> map)) {
          throw new IllegalArgumentException("a row of the pack IR is not an object");
        }
        parsedRows.add(
            new GeneratedRule(
                text(map, "id"),
                (int) number(map, "table"),
                (int) number(map, "priority"),
                text(map, "when"),
                text(map, "outcome"),
                text(map, "reason")));
        count++;
      }
      out.add(parsedRows);
    }
    return new GeneratedPack("ir", 0L, PackShape.S1_FIRST_HIT_REALISTIC, count, out);
  }

  private static String text(Map<?, ?> row, String key) {
    if (row.get(key) instanceof String s) {
      return s;
    }
    throw new IllegalArgumentException("the pack IR row has no text field " + key);
  }

  private static long number(Map<?, ?> row, String key) {
    if (row.get(key) instanceof Long n) {
      return n;
    }
    throw new IllegalArgumentException("the pack IR row has no integer field " + key);
  }
}
