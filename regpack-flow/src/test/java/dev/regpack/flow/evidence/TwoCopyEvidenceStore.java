package dev.regpack.flow.evidence;

import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Test helper: a store of two copies that behaves as the port says and as the sealed store does on
 * purpose (see the Javadoc of {@link EvidenceStore#put}). A put stores the first copy and then the
 * second, and returns after both; a put of a key that only the first copy holds publishes it to the
 * second, with the media type and the retention of the first. A read serves the first copy that
 * holds the key. {@link #halfWriteNextPut} makes one put store the first copy and then fail with an
 * {@link EvidenceUnavailableException}, which leaves what a crash between the two copies leaves: an
 * object that is served from one copy and that only a repeated put completes.
 */
public final class TwoCopyEvidenceStore implements EvidenceStore {
  private static final Grade GRADE = new Grade("P-TWOCOPY-TEST", "");
  private static final Durability DURABILITY = new Durability(false, 0);

  private final InMemoryEvidenceStore first;
  private final InMemoryEvidenceStore second;
  private String failAfterFirstCopy;

  public TwoCopyEvidenceStore(Clock clock) {
    this.first = new InMemoryEvidenceStore(clock);
    this.second = new InMemoryEvidenceStore(clock);
  }

  /**
   * The next put of a key whose path starts with {@code prefix} writes the first copy (a copy that
   * is there stays) and then throws an {@link EvidenceUnavailableException}; the second copy is not
   * written. Later puts are not affected.
   */
  public synchronized void halfWriteNextPut(String prefix) {
    this.failAfterFirstCopy = Objects.requireNonNull(prefix, "prefix");
  }

  /** The number of copies (0, 1 or 2) that hold the key. */
  public int copies(EvidenceKey key) {
    return (first.get(key).isPresent() ? 1 : 0) + (second.get(key).isPresent() ? 1 : 0);
  }

  /** The first copy, which every write reaches first (so it holds every key the store holds). */
  public InMemoryEvidenceStore firstVolume() {
    return first;
  }

  /** The second copy, which a write reaches last. */
  public InMemoryEvidenceStore secondVolume() {
    return second;
  }

  @Override
  public Grade grade() {
    return GRADE;
  }

  @Override
  public Durability durability() {
    return DURABILITY;
  }

  @Override
  public PutOutcome put(PutRequest request) {
    Objects.requireNonNull(request, "request");
    // other bytes than a stored copy are refused here, before the second copy is touched
    PutOutcome onFirst = first.put(request);
    if (armedFor(request.key())) {
      throw new EvidenceUnavailableException("the second copy cannot be written");
    }
    StoredObject kept = first.get(request.key()).orElseThrow();
    PutOutcome onSecond =
        second.put(new PutRequest(request.key(), kept.bytes(), kept.mediaType(), kept.retention()));
    return onFirst == PutOutcome.CREATED && onSecond == PutOutcome.CREATED
        ? PutOutcome.CREATED
        : PutOutcome.ALREADY_PRESENT_IDENTICAL;
  }

  private synchronized boolean armedFor(EvidenceKey key) {
    if (failAfterFirstCopy != null && key.path().startsWith(failAfterFirstCopy)) {
      failAfterFirstCopy = null;
      return true;
    }
    return false;
  }

  @Override
  public Optional<StoredObject> get(EvidenceKey key) {
    Optional<StoredObject> onFirst = first.get(key);
    return onFirst.isPresent() ? onFirst : second.get(key);
  }

  @Override
  public List<EvidenceKey> list(String prefix, Optional<EvidenceKey> after, int limit) {
    TreeMap<String, EvidenceKey> merged = new TreeMap<>();
    for (EvidenceKey key : first.list(prefix, after, limit)) {
      merged.put(key.path(), key);
    }
    for (EvidenceKey key : second.list(prefix, after, limit)) {
      merged.put(key.path(), key);
    }
    List<EvidenceKey> keys = new ArrayList<>(merged.values());
    keys.sort(Comparator.comparing(EvidenceKey::path));
    return List.copyOf(keys.subList(0, Math.min(limit, keys.size())));
  }

  @Override
  public void extendRetention(EvidenceKey key, Instant newRetainUntil) {
    first.extendRetention(key, newRetainUntil);
    if (second.get(key).isPresent()) {
      second.extendRetention(key, newRetainUntil);
    }
  }

  @Override
  public void setLegalHold(EvidenceKey key, boolean hold) {
    first.setLegalHold(key, hold);
    if (second.get(key).isPresent()) {
      second.setLegalHold(key, hold);
    }
  }
}
