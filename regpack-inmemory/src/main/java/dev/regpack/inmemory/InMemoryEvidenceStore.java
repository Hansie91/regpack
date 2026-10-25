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
import java.util.Locale;
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
  private static final Instant FIRST_YEAR_0000 = Instant.parse("0000-01-01T00:00:00Z");
  private static final Instant LAST_YEAR_9999 = Instant.parse("9999-12-31T23:59:59.999999999Z");

  /** Deliberate faults for ContractSuiteTeethTest; {@code NONE} is the correct adapter. */
  public enum Flaw {
    NONE,
    OVERWRITE_ON_DUPLICATE,
    ALLOW_RETENTION_SHORTENING,
    CASE_FOLDED_KEYS,
    LIST_IGNORES_AFTER,
    SILENT_MISSING_KEY,
    ACCEPT_ANY_RETAIN_UNTIL,
    SHORT_PAGES
  }

  private final Clock clock;
  private final Flaw flaw;
  private final Object lock = new Object();
  private final TreeMap<String, StoredObject> objects = new TreeMap<>();
  private volatile boolean down;

  public InMemoryEvidenceStore(Clock clock) {
    this(clock, Flaw.NONE);
  }

  /** Test double only: an adapter with a deliberate {@link Flaw}. */
  public InMemoryEvidenceStore(Clock clock, Flaw flaw) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.flaw = Objects.requireNonNull(flaw, "flaw");
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
    requireStorable(request.retention().retainUntil());
    byte[] bytes = request.bytes();
    Sha256 digest = Sha256.of(bytes);
    synchronized (lock) {
      StoredObject existing = objects.get(mapKey(request.key()));
      // test double only: deliberate flaw for ContractSuiteTeethTest
      if (existing == null
          || (flaw == Flaw.OVERWRITE_ON_DUPLICATE && !existing.sha256().equals(digest))) {
        objects.put(
            mapKey(request.key()),
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
      return Optional.ofNullable(objects.get(mapKey(key)));
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
    // test double only: the deliberate flaw SHORT_PAGES answers at most max(1, limit / 2) keys
    int cap = flaw == Flaw.SHORT_PAGES ? Math.min(limit, Math.max(1, limit / 2)) : limit;
    synchronized (lock) {
      for (var entry : objects.tailMap(prefix, true).entrySet()) {
        if (result.size() >= cap || !entry.getKey().startsWith(prefix)) {
          break;
        }
        // test double only: deliberate flaw for ContractSuiteTeethTest
        if (after.isEmpty()
            || flaw == Flaw.LIST_IGNORES_AFTER
            || entry.getKey().compareTo(after.get().path()) > 0) {
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
    requireStorable(newRetainUntil);
    synchronized (lock) {
      if (missingIsSilent(key)) {
        return;
      }
      StoredObject existing = require(key);
      // test double only: deliberate flaw for ContractSuiteTeethTest
      if (flaw != Flaw.ALLOW_RETENTION_SHORTENING
          && newRetainUntil.isBefore(existing.retention().retainUntil())) {
        throw new EvidenceRetentionException(
            "retention can only be extended: "
                + key.path()
                + " until "
                + existing.retention().retainUntil()
                + ", requested "
                + newRetainUntil);
      }
      objects.put(
          mapKey(key),
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
      if (missingIsSilent(key)) {
        return;
      }
      StoredObject existing = require(key);
      objects.put(
          mapKey(key),
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

  /**
   * The rule of the port (SPEC-7): a retain-until has at most microsecond precision and a year from
   * 0000 to 9999, so that every store can hold what another holds. A pure check, before any state
   * changes. The deliberate flaw ACCEPT_ANY_RETAIN_UNTIL (test double only) skips it.
   */
  private void requireStorable(Instant retainUntil) {
    if (flaw == Flaw.ACCEPT_ANY_RETAIN_UNTIL) {
      return;
    }
    if (retainUntil.getNano() % 1000 != 0) {
      throw new IllegalArgumentException("a retain-until has at most microsecond precision");
    }
    // compared as instants: Instant.MIN and Instant.MAX have no calendar year to read
    if (retainUntil.isBefore(FIRST_YEAR_0000) || retainUntil.isAfter(LAST_YEAR_9999)) {
      throw new IllegalArgumentException("a retain-until must be in the years 0000 to 9999");
    }
  }

  /** Test double only: the deliberate flaw SILENT_MISSING_KEY ignores a never-put key. */
  private boolean missingIsSilent(EvidenceKey key) {
    return flaw == Flaw.SILENT_MISSING_KEY && !objects.containsKey(mapKey(key));
  }

  /** The map key of {@code key}; the deliberate flaw CASE_FOLDED_KEYS lower-cases it. */
  private String mapKey(EvidenceKey key) {
    return flaw == Flaw.CASE_FOLDED_KEYS ? key.path().toLowerCase(Locale.ROOT) : key.path();
  }

  private StoredObject require(EvidenceKey key) {
    StoredObject existing = objects.get(mapKey(key));
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
