package dev.regpack.evidence.seal;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.model.json.Json;
import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.RetentionPolicy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The restore-epoch marker of the evidence tier (spec 11.2 step 4, 11.5 B.1): one write-once object
 * {@code control/epoch/e<epoch:012d>} per declared restore epoch, whose bytes are the canonical
 * JSON {@code {"epoch":n}} and nothing else. The sender reads the highest marker before the first
 * byte and refuses to send when it differs from the epoch of its own lease token, so a sender that
 * was restored from an older backup cannot send into a newer epoch. Zero padding makes the text
 * order of the listing the numeric order (epochs 9 and 10).
 *
 * <p>{@link #current()} reads the tier and is strict: a key under the prefix that is not a marker
 * key, and a marker whose bytes are not exactly the canonical text of the epoch its key names, are
 * {@link IllegalStateException} (the caller fails closed); an outage is {@link
 * dev.regpack.ports.evidence.EvidenceUnavailableException}, never "no marker". Messages name the
 * key path only, never stored content. The marker is declared before the ledger knows the new epoch
 * (spec 11.5 B.1); declaring is the restore procedure's, not the sender's.
 */
public final class EpochMarker {
  /** The media type of a marker object. */
  public static final String MEDIA_TYPE = "application/vnd.regpack.epoch+json";

  /** The retention class name of a marker (OI-6). */
  public static final String RETENTION_CLASS = "epoch";

  private static final String PREFIX = "control/epoch/";
  private static final int DIGITS = 12;
  private static final long MAX_EPOCH = 999_999_999_999L;
  private static final int PAGE = 1_000;

  private final EvidenceStore store;
  private final RetentionPolicy retention;
  private final Clock clock;

  /**
   * @param clock the source of the write time that the retention of a new marker is computed from
   */
  public EpochMarker(EvidenceStore store, RetentionPolicy retention, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.retention = Objects.requireNonNull(retention, "retention");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * The key of the marker of {@code epoch}.
   *
   * @throws IllegalArgumentException when the epoch is negative or has more than twelve digits
   */
  public static EvidenceKey key(long epoch) {
    if (epoch < 0 || epoch > MAX_EPOCH) {
      throw new IllegalArgumentException("epoch must be 0 to " + MAX_EPOCH + " (12 digits)");
    }
    String digits = Long.toString(epoch);
    return EvidenceKey.of("control", "epoch", "e" + "0".repeat(DIGITS - digits.length()) + digits);
  }

  /** The bytes of the marker of {@code epoch}: the canonical JSON of the epoch alone. */
  private static byte[] bytes(long epoch) {
    return Json.canonical(Map.<String, Object>of("epoch", epoch)).getBytes(UTF_8);
  }

  /**
   * Writes the marker of {@code epoch}; the same epoch again is a no-op success (the bytes carry no
   * time, so a second declaration is a put of identical bytes).
   *
   * @throws dev.regpack.ports.evidence.EvidenceIntegrityException the key holds other bytes
   * @throws dev.regpack.ports.evidence.EvidenceUnavailableException the store cannot acknowledge it
   */
  public void declare(long epoch) {
    EvidenceKey key = key(epoch);
    store.put(
        new EvidenceStore.PutRequest(
            key, bytes(epoch), MEDIA_TYPE, retention.retentionFor(RETENTION_CLASS, clock.now())));
  }

  /**
   * The highest declared epoch, 0 when no marker exists.
   *
   * @throws IllegalStateException a key under the marker prefix is no marker key, or the highest
   *     marker is not exactly the canonical text of the epoch of its key, or the listing does not
   *     advance
   * @throws dev.regpack.ports.evidence.EvidenceUnavailableException the tier cannot be read
   */
  public long current() {
    Optional<EvidenceKey> after = Optional.empty();
    Optional<EvidenceKey> highestKey = Optional.empty();
    long highest = 0;
    while (true) {
      List<EvidenceKey> page = store.list(PREFIX, after, PAGE);
      // read to an empty page: a page shorter than the limit is not taken for the end (D-W10)
      if (page.isEmpty()) {
        break;
      }
      // a non-empty page must start strictly after the previous page's last key, else the loop
      // would never end
      if (after.isPresent() && page.get(0).path().compareTo(after.get().path()) <= 0) {
        throw new IllegalStateException("evidence listing does not advance: " + PREFIX);
      }
      for (EvidenceKey listed : page) {
        long epoch = epochOf(listed);
        if (highestKey.isEmpty() || epoch > highest) {
          highestKey = Optional.of(listed);
          highest = epoch;
        }
      }
      after = Optional.of(page.get(page.size() - 1));
    }
    if (highestKey.isEmpty()) {
      return 0;
    }
    EvidenceKey key = highestKey.get();
    EvidenceStore.StoredObject stored =
        store.get(key).orElseThrow(() -> unreadable("a listed marker is missing", key));
    if (!Arrays.equals(stored.bytes(), bytes(highest))) {
      throw unreadable("the marker is not the text of the epoch of its key", key);
    }
    return highest;
  }

  /** The epoch of a marker key: {@code control/epoch/e} and exactly twelve digits. */
  private static long epochOf(EvidenceKey key) {
    String path = key.path();
    String name = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : "";
    if (name.length() != DIGITS + 1 || name.charAt(0) != 'e') {
      throw unreadable("a key under the marker prefix is no marker key", key);
    }
    long epoch = 0;
    for (int i = 1; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c < '0' || c > '9') {
        throw unreadable("a key under the marker prefix is no marker key", key);
      }
      epoch = epoch * 10 + (c - '0');
    }
    return epoch;
  }

  private static IllegalStateException unreadable(String reason, EvidenceKey key) {
    return new IllegalStateException(
        "restore-epoch marker is not readable: " + reason + ": " + key.path());
  }
}
