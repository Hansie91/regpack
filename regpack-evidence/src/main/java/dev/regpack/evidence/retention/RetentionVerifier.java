package dev.regpack.evidence.retention;

import dev.regpack.ports.crypto.DataShreddedException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.ledger.RetentionLedger;
import dev.regpack.ports.ledger.RetentionLedger.RetentionRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Compares the {@code retention} rows of the ledger with the evidence store (spec 11.6, P3c1 D-K4,
 * KF14). It is the P3c1 building block of the retention check that spec 11.6 places in {@code
 * verify} on P-SEALED, where a shortened retention is detectable only by looking (P-WORM enforces
 * it in the store); {@code verify} does not call it yet, and it sees only the objects that have a
 * row. In P3c1 only sealed objects get one (from the seal hook); journal, landing and enrichment
 * objects get theirs when their writers register them (P3d, P7a, P5), and the restriction to
 * manifest-covered objects and the call from {@code verify} are P3c2.
 *
 * <p>For each artifact that has rows it reads the stored object and reports, at most once per
 * artifact and kind:
 *
 * <ul>
 *   <li>{@link Kind#RETAIN_UNTIL_SHORTER}: the stored retain-until is earlier than the latest date
 *       of the rows of the artifact (a store whose retention was shortened or never extended, for
 *       example after a crash between the row and the extension of {@link RetentionEngine}). A
 *       store that retains longer than the rows is not a finding, and neither is a row without a
 *       date (an anchor not yet known).
 *   <li>{@link Kind#HOLD_MISSING}: a row of the artifact is held and the stored object is not (a
 *       hold that the store lost, or that never reached it).
 *   <li>{@link Kind#HOLD_NOT_RECORDED}: the stored object is held and a row of the artifact is not
 *       (a hold set on the store alone).
 * </ul>
 *
 * <p>An artifact that has a row and no object is {@code verify}'s MISSING and is not reported here.
 * An object whose KEK was destroyed ({@link DataShreddedException}; the store is the decorated one
 * over an encrypted tier) cannot be read, and its stored retention cannot be known through the
 * port: it is skipped, neither a finding nor an outage. A read of an object is a read of all of its
 * bytes (the port has no metadata read), which is the cost of the check. An outage or an alarm of
 * the store ({@link dev.regpack.ports.evidence.EvidenceUnavailableException}, {@link
 * dev.regpack.ports.evidence.EvidenceCorruptionException}) aborts the run and is not turned into a
 * finding: the damaged object is {@code EvidenceVerifier}'s finding.
 *
 * <p>Findings carry the evidence key and a fixed text, never a date, a regime or content. They are
 * sorted by key and kind. The class holds no state between runs.
 */
public final class RetentionVerifier {
  /** The detail of {@link Kind#RETAIN_UNTIL_SHORTER}. */
  public static final String DETAIL_RETAIN_UNTIL_SHORTER =
      "the stored retain-until is earlier than a retention row says";

  /** The detail of {@link Kind#HOLD_MISSING}. */
  public static final String DETAIL_HOLD_MISSING =
      "a retention row is held and the stored object is not";

  /** The detail of {@link Kind#HOLD_NOT_RECORDED}. */
  public static final String DETAIL_HOLD_NOT_RECORDED =
      "the stored object is held and a retention row is not";

  /** Artifacts per call of {@code retentionRows}. */
  private static final int PAGE = 500;

  /** What a finding says. */
  public enum Kind {
    RETAIN_UNTIL_SHORTER,
    HOLD_MISSING,
    HOLD_NOT_RECORDED
  }

  /** One fault: its kind, the artifact it is about and a fixed-text detail. */
  public record Finding(Kind kind, EvidenceKey artifact, String detail) {
    public Finding {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(artifact, "artifact");
      Objects.requireNonNull(detail, "detail");
    }
  }

  private final EvidenceStore store;
  private final RetentionLedger rows;

  public RetentionVerifier(EvidenceStore store, RetentionLedger rows) {
    this.store = Objects.requireNonNull(store, "store");
    this.rows = Objects.requireNonNull(rows, "rows");
  }

  /**
   * Checks every artifact that has a row and whose key starts with {@code prefix} (the empty prefix
   * is every artifact; the prefix need not end at a segment boundary).
   *
   * @throws dev.regpack.ports.evidence.EvidenceUnavailableException the store cannot be read
   * @throws dev.regpack.ports.evidence.EvidenceCorruptionException the store reports an alarm
   * @throws dev.regpack.ports.ledger.LedgerException the ledger cannot be read
   * @throws IllegalStateException the ledger lists out of key order
   */
  public List<Finding> verify(String prefix) {
    Objects.requireNonNull(prefix, "prefix");
    List<Finding> findings = new ArrayList<>();
    Optional<EvidenceKey> after = Optional.empty();
    while (true) {
      List<RetentionRow> page = rows.retentionRows(prefix, after, PAGE);
      if (page.isEmpty()) {
        break;
      }
      int start = 0;
      while (start < page.size()) {
        EvidenceKey artifact = page.get(start).claim().artifact();
        // the rows of one artifact are never split across pages, and they are adjacent
        int end = start + 1;
        while (end < page.size() && page.get(end).claim().artifact().equals(artifact)) {
          end++;
        }
        if (after.isPresent() && artifact.path().compareTo(after.get().path()) <= 0) {
          throw new IllegalStateException("the retention rows are not in key order");
        }
        judge(artifact, page.subList(start, end), findings);
        after = Optional.of(artifact);
        start = end;
      }
    }
    findings.sort(
        Comparator.comparing((Finding f) -> f.artifact().path())
            .thenComparing(Finding::kind)
            .thenComparing(Finding::detail));
    return List.copyOf(findings);
  }

  /** The rows of one artifact against the stored object. */
  private void judge(EvidenceKey artifact, List<RetentionRow> artifactRows, List<Finding> into) {
    Optional<EvidenceStore.StoredObject> found;
    try {
      found = store.get(artifact);
    } catch (DataShreddedException e) {
      return; // unreadable by design: not judged
    }
    if (found.isEmpty()) {
      return; // verify's MISSING
    }
    EvidenceStore.StoredObject stored = found.get();
    Optional<Instant> latest = Optional.empty();
    boolean anyHeld = false;
    boolean allHeld = true;
    for (RetentionRow row : artifactRows) {
      Optional<Instant> date = row.claim().retainUntil();
      if (date.isPresent() && (latest.isEmpty() || date.get().isAfter(latest.get()))) {
        latest = date;
      }
      anyHeld |= row.legalHold();
      allHeld &= row.legalHold();
    }
    if (latest.isPresent() && stored.retention().retainUntil().isBefore(latest.get())) {
      into.add(new Finding(Kind.RETAIN_UNTIL_SHORTER, artifact, DETAIL_RETAIN_UNTIL_SHORTER));
    }
    if (anyHeld && !stored.legalHold()) {
      into.add(new Finding(Kind.HOLD_MISSING, artifact, DETAIL_HOLD_MISSING));
    }
    if (stored.legalHold() && !allHeld) {
      into.add(new Finding(Kind.HOLD_NOT_RECORDED, artifact, DETAIL_HOLD_NOT_RECORDED));
    }
  }
}
