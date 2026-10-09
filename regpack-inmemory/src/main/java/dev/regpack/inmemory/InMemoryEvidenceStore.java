package dev.regpack.inmemory;

import dev.regpack.model.Sha256;
import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceRetentionException;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Test-only {@link EvidenceStore}: it survives nothing, so it claims grade P-MEMORY and {@code
 * Durability(false, 0)}. One lock guards a {@link TreeMap}, whose natural {@code String} order is
 * UTF-16 code-unit order.
 */
public final class InMemoryEvidenceStore implements EvidenceStore {
  private static final Grade GRADE = new Grade("P-MEMORY", "");
  private static final Durability DURABILITY = new Durability(false, 0);

  private final Clock clock;
  private final Object lock = new Object();
  private final TreeMap<String, StoredObject> objects = new TreeMap<>();
  private volatile boolean down;

  public InMemoryEvidenceStore(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Makes every call throw {@link EvidenceUnavailableException} while {@code down} is true. */
  public void simulateOutage(boolean down) {
    this.down = down;
  }

  @Override
  public Grade grade() {
    failIfDown();
    return GRADE;
  }

  @Override
  public Durability durability() {
    failIfDown();
    return DURABILITY;
  }

  @Override
  public PutOutcome put(PutRequest request) {
    failIfDown();
    Objects.requireNonNull(request, "request");
    byte[] bytes = request.bytes();
    Sha256 digest = Sha256.of(bytes);
    synchronized (lock) {
      StoredObject existing = objects.get(request.key().path());
      if (existing == null) {
        objects.put(
            request.key().path(),
            new StoredObject(
                request.key(),
                bytes,
                request.mediaType(),
                digest,
                clock.now(),
                request.retention(),
                false));
        return PutOutcome.CREATED;
      }
      if (existing.sha256().equals(digest)) {
        return PutOutcome.ALREADY_PRESENT_IDENTICAL;
      }
      throw new EvidenceIntegrityException(
          "different bytes for existing key " + request.key().path());
    }
  }

  @Override
  public Optional<StoredObject> get(EvidenceKey key) {
    failIfDown();
    Objects.requireNonNull(key, "key");
    synchronized (lock) {
      return Optional.ofNullable(objects.get(key.path()));
    }
  }

  @Override
  public List<EvidenceKey> list(String prefix, Optional<EvidenceKey> after, int limit) {
    failIfDown();
    Objects.requireNonNull(prefix, "prefix");
    Objects.requireNonNull(after, "after");
    if (limit < 0) {
      throw new IllegalArgumentException("limit must not be negative");
    }
    List<EvidenceKey> result = new ArrayList<>();
    synchronized (lock) {
      for (var entry : objects.tailMap(prefix, true).entrySet()) {
        if (result.size() >= limit || !entry.getKey().startsWith(prefix)) {
          break;
        }
        if (after.isEmpty() || entry.getKey().compareTo(after.get().path()) > 0) {
          result.add(entry.getValue().key());
        }
      }
    }
    return List.copyOf(result);
  }

  @Override
  public void extendRetention(EvidenceKey key, Instant newRetainUntil) {
    failIfDown();
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(newRetainUntil, "newRetainUntil");
    synchronized (lock) {
      StoredObject existing = require(key);
      if (newRetainUntil.isBefore(existing.retention().retainUntil())) {
        throw new EvidenceRetentionException(
            "retention can only be extended: "
                + key.path()
                + " until "
                + existing.retention().retainUntil()
                + ", requested "
                + newRetainUntil);
      }
      objects.put(
          key.path(),
          new StoredObject(
              existing.key(),
              existing.bytes(),
              existing.mediaType(),
              existing.sha256(),
              existing.writtenAt(),
              new Retention(existing.retention().retentionClass(), newRetainUntil),
              existing.legalHold()));
    }
  }

  @Override
  public void setLegalHold(EvidenceKey key, boolean hold) {
    failIfDown();
    Objects.requireNonNull(key, "key");
    synchronized (lock) {
      StoredObject existing = require(key);
      objects.put(
          key.path(),
          new StoredObject(
              existing.key(),
              existing.bytes(),
              existing.mediaType(),
              existing.sha256(),
              existing.writtenAt(),
              existing.retention(),
              hold));
    }
  }

  private StoredObject require(EvidenceKey key) {
    StoredObject existing = objects.get(key.path());
    if (existing == null) {
      throw new IllegalArgumentException("no such evidence key: " + key.path());
    }
    return existing;
  }

  private void failIfDown() {
    if (down) {
      throw new EvidenceUnavailableException("evidence store is unavailable");
    }
  }
}
