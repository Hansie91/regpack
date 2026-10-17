package dev.regpack.pack.model;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** The rationales of a pack ({@code rationales.yaml}): text by id ({@code RAT-nnnn}), sorted. */
public record RationaleRegistry(Map<String, String> texts) {
  public RationaleRegistry {
    texts = Collections.unmodifiableMap(new TreeMap<>(texts));
  }
}
