package dev.regpack.evidence.verify;

import dev.regpack.evidence.seal.SealedKeys;
import dev.regpack.model.Sha256;
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.journal.JournalKey;
import dev.regpack.ports.evidence.journal.JournalObject;
import dev.regpack.ports.evidence.journal.JournalScope;
import dev.regpack.ports.evidence.journal.JournalType;
import dev.regpack.ports.ledger.DeliveryLedger;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.ManifestLedger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * {@code verify} over the evidence tier and the ledger's object manifest (assumption A13, spec 5.4,
 * 11.2 and the storage half of I10). It lists the evidence store and the manifest in key order, in
 * pages of 500 on both sides, walks the two lists side by side, reads every listed object through
 * {@link EvidenceStore#get} (except an unrecorded restore-epoch marker, see below) and reports what
 * it finds as {@link Finding}s of five classes, each with its own meaning:
 *
 * <ul>
 *   <li>{@link Kind#ORPHAN}: an object in the listing with no manifest row (a crash orphan, spec
 *       7.2; it is not an error of the writer, D-E8). The detail is {@code sha256=<hex>} of the
 *       stored bytes, {@code sha256 unknown} when the object cannot be read, and for a {@code
 *       DeliveryIntent} object that has neither a manifest row nor an attempt row {@value
 *       #IN_DOUBT}: the input of the in-doubt procedure of spec 11.5 B.5.
 *   <li>{@link Kind#MISSING}: a manifest row whose object is not in the listing, and a sealed file
 *       that a {@code DeliveryIntent} object names ({@code artifact_sha256}) and the tier does not
 *       hold, with the detail {@value #NAMED_ABSENT}, also when the ledger has no row for it (a
 *       database restored from a backup older than the seal). A sealed file that is missing for
 *       both reasons is one finding, the one of the manifest row.
 *   <li>{@link Kind#HASH_MISMATCH}: with the detail {@value #STORE_INTEGRITY} the store itself
 *       reports that the object is damaged ({@link EvidenceIntegrityException}, which includes
 *       {@link dev.regpack.ports.evidence.EvidenceCorruptionException}) or answers with a hash that
 *       is not the hash of its bytes; with the detail {@value #MANIFEST_HASH} the object is whole
 *       but its bytes (their SHA-256 or their length) are not those the manifest recorded at write
 *       time. The manifest hash is the hash of the object bytes as stored, never the {@code
 *       payload_sha256} of a journal object.
 *   <li>{@link Kind#SEQUENCE_GAP}: the class (b) objects of one scope and restore epoch in the
 *       listing must have the seq numbers 1 to their maximum; the first hole of each group is
 *       reported, with the key of the absent seq. A group is judged only when the prefix covers all
 *       of it.
 *   <li>{@link Kind#INTENT_MISMATCH}: a {@code DeliveryIntent} object that is not what its key and
 *       the ledger say: bytes that are no journal object, an envelope that names another key, a
 *       payload {@code attempt_id} that is not the key subject, and, where the attempt has a row in
 *       the ledger, another {@code artifact_sha256}, {@code file_name} or {@code delivery_id}, a
 *       key scope or restore epoch that is not the endpoint of its delivery and the epoch of its
 *       token, and, where the delivery has a row, another {@code business_key} or {@code
 *       version_seq}. ({@code trn}, {@code action} and {@code logical_hash} are not in the ledger
 *       and are not compared.) A missing attempt row or manifest row is an ORPHAN or MISSING and
 *       never an INTENT_MISMATCH (after a restore the evidence tier is ahead of the database).
 * </ul>
 *
 * <p>An outage ({@link dev.regpack.ports.evidence.EvidenceUnavailableException}, or a {@link
 * dev.regpack.ports.ledger.LedgerException}) aborts the run: an object that cannot be served is not
 * a missing object. A damaged object is never reported as MISSING. The keys under {@code
 * control/epoch/} (restore-epoch markers) are neither orphans nor missing: a marker is written
 * before the ledger knows the new epoch (spec 11.5 B.1). A marker that has no manifest row (in P3b1
 * none has one) is not read, so a damaged marker is not reported here: the epoch-marker coverage of
 * {@code verify} is P4 (plan scope check). There is no UNEXPLAINED class and no chain check until
 * signed manifests exist (P3c).
 *
 * <p>What {@code verify} does not see: a stored object is read through the port, so a damaged copy
 * that the other volume of a P-SEALED store masks is for {@code SealedEvidenceStore#scrub} to find,
 * and an edit that recomputes the seal of a P-SEALED file is not found before the signed manifests
 * of P3c. The run is for a tier that nobody writes to (an audit window, a restore): a write that
 * overlaps a run may show as an ORPHAN or a MISSING object, and a second run decides.
 *
 * <p>Findings carry key paths, hashes and fixed texts only, never file names, business keys or
 * payload text. They are sorted by key, kind and detail. The class holds no state between runs.
 */
public final class EvidenceVerifier {
  /** The detail of a HASH_MISMATCH that the store reports itself. */
  public static final String STORE_INTEGRITY = "store-integrity";

  /** The detail of a HASH_MISMATCH against the manifest row. */
  public static final String MANIFEST_HASH = "manifest-hash";

  /** The detail of an orphan intent object (spec 11.5 B.5). */
  public static final String IN_DOUBT = "in-doubt input (11.5 B.5)";

  /** The detail of a sealed file that an intent object names and the tier does not hold. */
  public static final String NAMED_ABSENT = "named by an intent, absent";

  private static final String ABSENT = "the object is absent from the evidence tier";
  private static final String HASH_UNKNOWN = "sha256 unknown";
  private static final String NOT_A_JOURNAL_OBJECT = "the object is not a journal object";
  private static final String OTHER_KEY = "the envelope names another key";
  private static final String OTHER_ATTEMPT = "attempt_id differs from the key subject";
  private static final String OTHER_ARTIFACT = "artifact_sha256 differs from the attempt row";
  private static final String OTHER_FILE = "file_name differs from the attempt row";
  private static final String OTHER_DELIVERY = "delivery_id differs from the attempt row";
  private static final String OTHER_BUSINESS_KEY = "business_key differs from the delivery row";
  private static final String OTHER_VERSION = "version_seq differs from the delivery row";
  private static final String OTHER_SCOPE = "the key scope is not the endpoint of the attempt";
  private static final String OTHER_EPOCH =
      "the key restore epoch is not the token epoch of the attempt";
  private static final String MARKER_PREFIX = "control/epoch/";
  private static final String JOURNAL_PREFIX = "journal/";
  private static final int PAGE = 500;

  /** What a finding says; the classes are those of assumption A13. */
  public enum Kind {
    HASH_MISMATCH,
    MISSING,
    ORPHAN,
    SEQUENCE_GAP,
    INTENT_MISMATCH
  }

  /** One fault: its class, the evidence key it is about and a fixed-text detail. */
  public record Finding(Kind kind, String key, String detail) {
    public Finding {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(detail, "detail");
    }
  }

  /**
   * The result of one run: the findings, the number of keys of the listing that were walked and the
   * number of manifest rows that were walked. Both counts include the keys that a prefix selects
   * and the epoch markers.
   */
  public record Report(List<Finding> findings, long objectsChecked, long manifestRowsChecked) {
    public Report {
      findings = List.copyOf(findings);
      if (objectsChecked < 0 || manifestRowsChecked < 0) {
        throw new IllegalArgumentException("the counts must not be negative");
      }
    }

    public boolean clean() {
      return findings.isEmpty();
    }
  }

  private final EvidenceStore store;
  private final LedgerStore ledger;

  public EvidenceVerifier(EvidenceStore store, LedgerStore ledger) {
    this.store = Objects.requireNonNull(store, "store");
    this.ledger = Objects.requireNonNull(ledger, "ledger");
  }

  /**
   * Verifies every key and every manifest row that starts with {@code prefix} (the empty prefix is
   * the whole tier; the prefix need not end at a segment boundary).
   *
   * @throws dev.regpack.ports.evidence.EvidenceUnavailableException the store cannot be read
   * @throws dev.regpack.ports.ledger.LedgerException the ledger cannot be read
   * @throws IllegalStateException the store or the ledger lists out of key order
   */
  public Report verify(String prefix) {
    Objects.requireNonNull(prefix, "prefix");
    return new Walk(prefix).run();
  }

  // ---------------------------------------------------------------------------------------------

  private sealed interface Read permits Present, Damaged, Absent {}

  /** The object was read and its bytes are the bytes that its hash names. */
  private record Present(byte[] bytes, Sha256 sha256) implements Read {}

  /** The store says the object is damaged. */
  private record Damaged() implements Read {}

  /** The store has no such object. */
  private record Absent() implements Read {}

  /** One run: the two cursors, the findings and the state of the class (b) group in progress. */
  private final class Walk {
    private final String prefix;
    private final List<Finding> findings = new ArrayList<>();
    private long objectsChecked;
    private long manifestRowsChecked;
    private String group = "";
    private boolean groupJudged;
    private long expectedSeq;
    private final Set<String> namedSealedFiles = new HashSet<>();

    Walk(String prefix) {
      this.prefix = prefix;
    }

    Report run() {
      Pages<ManifestLedger.ManifestRow> rows =
          new Pages<>(
              "the manifest",
              after -> ledger.manifestRows(prefix, after, PAGE),
              row -> row.entry().key());
      Pages<EvidenceKey> objects =
          new Pages<>("the evidence listing", after -> store.list(prefix, after, PAGE), key -> key);
      while (true) {
        // the manifest is read first: a row that is visible names an object that was put before it
        Optional<ManifestLedger.ManifestRow> row = rows.peek();
        Optional<EvidenceKey> listed = objects.peek();
        if (row.isEmpty() && listed.isEmpty()) {
          break;
        }
        int order =
            listed.isEmpty()
                ? 1
                : row.isEmpty()
                    ? -1
                    : listed.get().path().compareTo(row.get().entry().key().path());
        if (order < 0) {
          objectsChecked++;
          sequence(listed.get());
          listedOnly(listed.get());
          objects.advance();
        } else if (order > 0) {
          manifestRowsChecked++;
          rowOnly(row.get());
          rows.advance();
        } else {
          objectsChecked++;
          manifestRowsChecked++;
          sequence(listed.get());
          both(listed.get(), row.get());
          objects.advance();
          rows.advance();
        }
      }
      dropNamedAbsentWhereTheManifestSaysIt();
      findings.sort(
          Comparator.comparing(Finding::key)
              .thenComparing(Finding::kind)
              .thenComparing(Finding::detail));
      return new Report(findings, objectsChecked, manifestRowsChecked);
    }

    private void add(Kind kind, String key, String detail) {
      findings.add(new Finding(kind, key, detail));
    }

    /** A sealed file that is missing for both reasons is one finding: the manifest row's. */
    private void dropNamedAbsentWhereTheManifestSaysIt() {
      Set<String> byTheManifest = new HashSet<>();
      for (Finding f : findings) {
        if (f.kind() == Kind.MISSING && f.detail().equals(ABSENT)) {
          byTheManifest.add(f.key());
        }
      }
      findings.removeIf(
          f ->
              f.kind() == Kind.MISSING
                  && f.detail().equals(NAMED_ABSENT)
                  && byTheManifest.contains(f.key()));
    }

    /** A key in the listing and no row of the manifest. */
    private void listedOnly(EvidenceKey key) {
      if (key.path().startsWith(MARKER_PREFIX)) {
        return;
      }
      Read read = read(key);
      switch (read) {
        case Damaged _ -> {
          add(Kind.ORPHAN, key.path(), HASH_UNKNOWN);
          add(Kind.HASH_MISMATCH, key.path(), STORE_INTEGRITY);
        }
        case Absent _ -> {
          // gone between the listing and the read, and the ledger never knew it: nothing to say
        }
        case Present p -> {
          Optional<JournalKey.ClassA> intent = deliveryIntentKey(key);
          Optional<DeliveryLedger.Attempt> attempt = intent.flatMap(this::attemptOf);
          boolean inDoubt = intent.isPresent() && attempt.isEmpty();
          add(Kind.ORPHAN, key.path(), inDoubt ? IN_DOUBT : "sha256=" + p.sha256().hex());
          intent.ifPresent(k -> checkIntent(key, k, attempt, p.bytes()));
        }
      }
    }

    /** A row of the manifest and no key in the listing. */
    private void rowOnly(ManifestLedger.ManifestRow row) {
      String path = row.entry().key().path();
      if (!path.startsWith(MARKER_PREFIX)) {
        add(Kind.MISSING, path, ABSENT);
      }
    }

    /** A key in the listing and its row in the manifest. */
    private void both(EvidenceKey key, ManifestLedger.ManifestRow row) {
      Read read = read(key);
      switch (read) {
        case Damaged _ -> add(Kind.HASH_MISMATCH, key.path(), STORE_INTEGRITY);
        case Absent _ -> add(Kind.MISSING, key.path(), ABSENT);
        case Present p -> {
          ManifestLedger.ObjectEntry entry = row.entry();
          if (!p.sha256().equals(entry.sha256()) || p.bytes().length != entry.sizeBytes()) {
            add(Kind.HASH_MISMATCH, key.path(), MANIFEST_HASH);
          }
          Optional<JournalKey.ClassA> intent = deliveryIntentKey(key);
          intent.ifPresent(k -> checkIntent(key, k, attemptOf(k), p.bytes()));
        }
      }
    }

    /**
     * Reads the object. An integrity alarm of the store (also a corruption) is {@link Damaged}; an
     * outage is not caught. The hash is computed from the bytes: a store that answers with another
     * hash than its own bytes has is damaged as well.
     */
    private Read read(EvidenceKey key) {
      try {
        Optional<EvidenceStore.StoredObject> stored = store.get(key);
        if (stored.isEmpty()) {
          return new Absent();
        }
        byte[] bytes = stored.get().bytes();
        Sha256 actual = Sha256.of(bytes);
        return actual.equals(stored.get().sha256()) ? new Present(bytes, actual) : new Damaged();
      } catch (EvidenceIntegrityException e) {
        return new Damaged();
      }
    }

    private Optional<JournalKey.ClassA> deliveryIntentKey(EvidenceKey key) {
      if (!key.path().startsWith(JOURNAL_PREFIX)) {
        return Optional.empty();
      }
      try {
        if (JournalKey.parse(key) instanceof JournalKey.ClassA a
            && a.type() == JournalType.DELIVERY_INTENT) {
          return Optional.of(a);
        }
      } catch (IllegalArgumentException e) {
        // a key under journal/ that is not a journal key: it has no intent to compare
      }
      return Optional.empty();
    }

    /**
     * The attempt row that the key subject names, if the subject is an attempt id and has a row.
     */
    private Optional<DeliveryLedger.Attempt> attemptOf(JournalKey.ClassA key) {
      String subject = key.subjectId();
      for (int i = 0; i < subject.length(); i++) {
        if (subject.charAt(i) < '0' || subject.charAt(i) > '9') {
          return Optional.empty();
        }
      }
      return ledger.attempt(new DeliveryLedger.AttemptId(Long.parseLong(subject)));
    }

    /** The intent object against its own key and, where there is one, its attempt row. */
    private void checkIntent(
        EvidenceKey key,
        JournalKey.ClassA keyOfIntent,
        Optional<DeliveryLedger.Attempt> attempt,
        byte[] bytes) {
      JournalObject object;
      try {
        // an object over the bound of a DeliveryIntent (1 MiB) is refused here without being parsed
        object = JournalObject.fromBytes(keyOfIntent, bytes);
      } catch (IllegalArgumentException e) {
        add(Kind.INTENT_MISMATCH, key.path(), NOT_A_JOURNAL_OBJECT);
        return;
      }
      if (!object.key().evidenceKey().equals(key)) {
        add(Kind.INTENT_MISMATCH, key.path(), OTHER_KEY);
      }
      Map<String, Object> payload = object.payload();
      if (!Objects.equals(String.valueOf(payload.get("attempt_id")), keyOfIntent.subjectId())) {
        add(Kind.INTENT_MISMATCH, key.path(), OTHER_ATTEMPT);
      }
      namedSealedFile(payload.get("artifact_sha256"));
      if (attempt.isEmpty()) {
        return;
      }
      DeliveryLedger.Attempt row = attempt.get();
      if (!Objects.equals(payload.get("artifact_sha256"), row.artifactSha256().hex())) {
        add(Kind.INTENT_MISMATCH, key.path(), OTHER_ARTIFACT);
      }
      if (!Objects.equals(payload.get("file_name"), row.fileName())) {
        add(Kind.INTENT_MISMATCH, key.path(), OTHER_FILE);
      }
      if (!Objects.equals(payload.get("delivery_id"), row.delivery().value())) {
        add(Kind.INTENT_MISMATCH, key.path(), OTHER_DELIVERY);
      }
      if (keyOfIntent.restoreEpoch() != row.token().restoreEpoch()) {
        add(Kind.INTENT_MISMATCH, key.path(), OTHER_EPOCH);
      }
      Optional<DeliveryLedger.Delivery> delivery = ledger.delivery(row.delivery());
      if (delivery.isEmpty()) {
        return;
      }
      DeliveryLedger.NewDelivery identity = delivery.get().key();
      if (!Objects.equals(payload.get("business_key"), identity.businessKey())) {
        add(Kind.INTENT_MISMATCH, key.path(), OTHER_BUSINESS_KEY);
      }
      if (!Objects.equals(payload.get("version_seq"), identity.versionSeq())) {
        add(Kind.INTENT_MISMATCH, key.path(), OTHER_VERSION);
      }
      if (!Optional.of(keyOfIntent.scope()).equals(scopeOf(identity))) {
        add(Kind.INTENT_MISMATCH, key.path(), OTHER_SCOPE);
      }
    }

    /** The journal scope of the endpoint of a delivery; none when the endpoint has no scope. */
    private Optional<JournalScope> scopeOf(DeliveryLedger.NewDelivery identity) {
      try {
        return Optional.of(JournalScope.of(identity.endpoint()));
      } catch (IllegalArgumentException e) {
        return Optional.empty();
      }
    }

    /**
     * The sealed file that an intent object names must be in the tier, or its loss would be
     * invisible when the ledger has no row for it. Existence only, through the listing, so that
     * nothing large is read; once per file and run.
     */
    private void namedSealedFile(Object artifactSha256) {
      if (!(artifactSha256 instanceof String hex)) {
        return;
      }
      EvidenceKey sealed = SealedKeys.of(new Sha256(hex));
      if (!namedSealedFiles.add(sealed.path())) {
        return;
      }
      List<EvidenceKey> found = store.list(sealed.path(), Optional.empty(), 1);
      if (found.isEmpty() || !found.get(0).equals(sealed)) {
        add(Kind.MISSING, sealed.path(), NAMED_ABSENT);
      }
    }

    /**
     * Follows the class (b) keys of the listing, which come in key order and so group by (scope,
     * epoch): the seq numbers of a group must be 1, 2, 3 and so on. The first hole of a group is
     * the finding; a group that the prefix cuts is not judged.
     */
    private void sequence(EvidenceKey key) {
      String path = key.path();
      if (!path.startsWith(JOURNAL_PREFIX)) {
        return;
      }
      JournalKey.ClassB b;
      try {
        if (!(JournalKey.parse(key) instanceof JournalKey.ClassB parsed)) {
          return;
        }
        b = parsed;
      } catch (IllegalArgumentException e) {
        return;
      }
      // journal/<scope>/e<epoch>/b/: the first four segments of a class (b) key
      String[] segments = path.split("/", 5);
      String thisGroup = String.join("/", segments[0], segments[1], segments[2], segments[3]) + "/";
      if (!thisGroup.equals(group)) {
        group = thisGroup;
        groupJudged = thisGroup.startsWith(prefix);
        expectedSeq = 1;
      }
      if (!groupJudged) {
        return;
      }
      if (b.seq() == expectedSeq) {
        expectedSeq++;
        return;
      }
      add(
          Kind.SEQUENCE_GAP,
          new JournalKey.ClassB(b.scope(), b.restoreEpoch(), expectedSeq).evidenceKey().path(),
          "expected seq " + expectedSeq + ", found seq " + b.seq());
      groupJudged = false; // only the first hole of a group
    }
  }

  /** A list that is read in pages of 500, each after the last key of the one before. */
  private static final class Pages<T> {
    private final String what;
    private final Function<Optional<EvidenceKey>, List<T>> fetch;
    private final Function<T, EvidenceKey> keyOf;
    private List<T> page = List.of();
    private int index;
    private Optional<EvidenceKey> last = Optional.empty();
    private boolean exhausted;

    Pages(
        String what,
        Function<Optional<EvidenceKey>, List<T>> fetch,
        Function<T, EvidenceKey> keyOf) {
      this.what = what;
      this.fetch = fetch;
      this.keyOf = keyOf;
    }

    Optional<T> peek() {
      while (index >= page.size() && !exhausted) {
        List<T> next = fetch.apply(last);
        if (next.isEmpty()) {
          exhausted = true;
          page = List.of();
          index = 0;
          break;
        }
        requireKeyOrder(next);
        page = next;
        index = 0;
        last = Optional.of(keyOf.apply(next.get(next.size() - 1)));
      }
      return index < page.size() ? Optional.of(page.get(index)) : Optional.empty();
    }

    void advance() {
      index++;
    }

    /** Every key sorts strictly after the one before it and after the end of the last page. */
    private void requireKeyOrder(List<T> next) {
      Optional<String> previous = last.map(EvidenceKey::path);
      for (T item : next) {
        String path = keyOf.apply(item).path();
        if (previous.isPresent() && path.compareTo(previous.get()) <= 0) {
          throw new IllegalStateException(what + " is not in key order");
        }
        previous = Optional.of(path);
      }
    }
  }
}
