package dev.regpack.pack.model;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The Legal Source Registry of a pack ({@code sources.yaml}; spec 6.4): the sources a decision may
 * cite, keyed by source id, in sorted order.
 */
public record SourceRegistry(Map<String, SourceEntry> sources) {
  public SourceRegistry {
    sources = Collections.unmodifiableMap(new TreeMap<>(sources));
  }

  /** The provision a citation names, if the source and the provision both exist. */
  public Optional<Provision> resolve(String sourceId, String provisionPath) {
    SourceEntry source = sources.get(sourceId);
    return source == null
        ? Optional.empty()
        : Optional.ofNullable(source.provisions().get(provisionPath));
  }
}
