package dev.regpack.model.canonical;

import dev.regpack.model.EventIdentity;
import java.time.Instant;

/** A normalised business event that a regime decides on. */
public sealed interface CanonicalEvent permits CanonicalExecution {
  EventIdentity eventIdentity();

  Instant occurredAt();
}
