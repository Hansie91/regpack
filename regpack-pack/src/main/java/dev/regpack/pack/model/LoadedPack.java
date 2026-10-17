package dev.regpack.pack.model;

import dev.regpack.model.Sha256;
import dev.regpack.pack.source.PackSource;
import dev.regpack.pack.yaml.YamlNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A pack that passed every check of the {@link PackLoader}: typed, cross-checked and content
 * addressed. It is immutable (the collections are unmodifiable copies; the golden trees are deeply
 * unmodifiable) and its maps iterate in path order.
 *
 * @param manifest the typed {@code pack.yaml}
 * @param input the typed canonical input model
 * @param scopeTables the scope decision tables by pack path
 * @param reasons the reason-code registry
 * @param sources the Legal Source Registry
 * @param rationales the rationales
 * @param opaque the strict-parsed files this engine build does not interpret, by pack path
 *     (including the empty {@code interpretations.yaml})
 * @param goldenJson the decoded golden files by pack path ({@code Map}, {@code List}, {@code
 *     String}, {@code Boolean}, {@code Long}, {@code BigDecimal})
 * @param authorship the parsed {@code tests/authorship.yaml}, if present
 * @param source the file tree the pack was loaded from
 * @param digest the SHA-256 of the canonical IR ({@code PackDigest.of})
 * @param notes lines such as {@code not interpreted by this engine build: lifecycle.yaml} (spec
 *     6.1; the {@code pack test} command prints them)
 */
public record LoadedPack(
    PackManifest manifest,
    CanonicalInputSpec input,
    Map<String, DecisionTable> scopeTables,
    ReasonRegistry reasons,
    SourceRegistry sources,
    RationaleRegistry rationales,
    Map<String, YamlNode> opaque,
    Map<String, Object> goldenJson,
    Optional<YamlNode> authorship,
    PackSource source,
    Sha256 digest,
    List<String> notes) {
  public LoadedPack {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(reasons, "reasons");
    Objects.requireNonNull(sources, "sources");
    Objects.requireNonNull(rationales, "rationales");
    Objects.requireNonNull(authorship, "authorship");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(digest, "digest");
    scopeTables = Collections.unmodifiableMap(new TreeMap<>(scopeTables));
    opaque = Collections.unmodifiableMap(new TreeMap<>(opaque));
    Map<String, Object> golden = new TreeMap<>();
    goldenJson.forEach((path, tree) -> golden.put(path, freeze(tree)));
    goldenJson = Collections.unmodifiableMap(golden);
    notes = List.copyOf(notes);
  }

  /**
   * Always false in P2a: packs are unsigned until P9b (assumption A4); the loader never claims a
   * signature.
   */
  public boolean signed() {
    return false;
  }

  private static Object freeze(Object tree) {
    if (tree instanceof Map<?, ?> map) {
      Map<String, Object> copy = new TreeMap<>();
      map.forEach((k, v) -> copy.put((String) k, freeze(v)));
      return Collections.unmodifiableMap(copy);
    }
    if (tree instanceof List<?> list) {
      List<Object> copy = new ArrayList<>(list.size());
      for (Object item : list) {
        copy.add(freeze(item));
      }
      return Collections.unmodifiableList(copy);
    }
    return tree;
  }
}
