package dev.regpack.evidence.s3;

import dev.regpack.evidence.s3.client.JdkTransport;
import dev.regpack.evidence.s3.client.S3Client;
import dev.regpack.evidence.s3.client.S3Client.VersionEntry;
import dev.regpack.evidence.s3.client.S3Client.VersionPage;
import dev.regpack.evidence.s3.client.S3Exception;
import dev.regpack.evidence.s3.client.S3Response;
import dev.regpack.evidence.s3.client.S3Transport;
import dev.regpack.model.Sha256;
import dev.regpack.ports.Clock;
import dev.regpack.ports.evidence.EvidenceCorruptionException;
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * P-WORM: an {@link EvidenceStore} on an S3-compatible object-lock service in governance mode, path
 * style, through the hand-written client of this module. Put, get and list are built here (T8a);
 * retention, legal hold and the durability check follow in T8b.
 *
 * <p><strong>Object model (D-W2).</strong> The object under an evidence key {@code k} is the S3
 * object {@code k} of the bucket. Content type is always {@code application/octet-stream}. The user
 * metadata, under lower-case names, is {@code regpack-sha256} (hex), {@code regpack-written-at} and
 * {@code regpack-retain-until} (ISO instants, plain; the retain-until is the exact value of the
 * put), and {@code regpack-class} and {@code regpack-media-type} (UTF-8, base64url without
 * padding). <strong>Invariant V: the object under a key is its oldest data version.</strong> {@code
 * get}, the existence test of a put and the retention methods use the version list of the exact key
 * (ListObjectVersions with the key as prefix, entries of other keys ignored) and the version id of
 * that version; the list is read to its end and the last data entry of the key's run, in list order
 * (newest first), is the oldest. Last-Modified is never consulted.
 *
 * <p><strong>Put (D-W3).</strong> Inside a stripe lock of the key (64 locks by key hash; the locks
 * belong to this object, so a second store object on the same bucket models a second process):
 * validate (pure), list versions; a key hidden by a delete marker is an {@link
 * EvidenceCorruptionException}; if a data version exists it is read with GET and its bytes are
 * hashed against the recorded hash (a mismatch or missing metadata is an {@link
 * EvidenceCorruptionException}, no PUT), and only then the recorded hash is compared with the
 * request's: equal is {@code ALREADY_PRESENT_IDENTICAL} with no PUT, different is an {@link
 * EvidenceIntegrityException}. Otherwise the retain-until must be after the data clock, the object
 * is put (the service verifies the payload hash and the MD5, probe result P10), and the versions
 * are listed again: the own version missing from the second listing is {@link
 * EvidenceUnavailableException}, never {@code CREATED}; the own version oldest is {@code CREATED};
 * another, older version decides as above and the own version stays as a harmless extra version.
 * <strong>The race that remains:</strong> two processes (not two threads of one store) putting
 * different bytes under one key at once can both find their own version oldest, because the rank of
 * a version is the server's [probe result P13]; both are told {@code CREATED}. {@code get} returns
 * the older-ranked version (invariant V), and if the server ranks by request start the answer of
 * {@code get} can change once while the slower upload is in flight. The ledger keeps the hash of
 * whichever writer reaches {@code recordObjects} first and fails the other seal closed, so when the
 * writer whose bytes {@code get} does not return is first, its seal succeeds and gates an effect on
 * bytes the port cannot return (they stay as a non-oldest locked version that the port does not
 * return; reading it needs the raw S3 API with its version id [R]); {@code verify} reports the key
 * afterwards ({@code HASH_MISMATCH}, {@code manifest-hash}). This is a stated residual hole of I10
 * for that attempt, not closed here: conditional writes (spec 5.2, 11.5 B.1; deferred) would close
 * it, and no other closure was examined.
 *
 * <p><strong>Get and list (D-W4).</strong> {@code get} hashes the bytes against the recorded hash
 * ({@link EvidenceCorruptionException} on a difference, on missing or unreadable metadata, on a
 * version that the list showed and the service does not have, and when the newest entry of the key
 * is a delete marker over a data version). {@code list} returns every key that has at least one
 * data version, a key whose newest entry is a delete marker included, in key order, skips keys that
 * hold only delete markers and keys that are not valid {@link EvidenceKey}s, and follows the
 * markers until {@code limit} distinct keys are collected or the listing ends: it never answers
 * short while keys remain (D-W10). A key that holds only delete markers has no data version and is
 * absent.
 *
 * <p><strong>Failures (D-W7).</strong> Every outcome that is not the documented success of a call
 * is {@link EvidenceUnavailableException}, with a message that holds the operation, the status and
 * the S3 code only: a transport failure, a timeout, an interrupt (the flag is restored), any status
 * the call does not expect, an unparsable answer, a missing version id, a listing that does not end
 * or does not advance. The one absence is a version list of the exact key, read to its end, that
 * holds no data version. No retries. This class issues no delete request and sends no bypass
 * header.
 */
public final class WormEvidenceStore implements EvidenceStore {
  private static final String PROFILE = "P-WORM";
  private static final String CONTENT_TYPE = "application/octet-stream";
  private static final int STRIPES = 64;
  private static final int PAGE_SIZE = 1_000;
  private static final int MAX_PAGES = 10_000;
  private static final int MAX_MEDIA_TYPE_CHARS = 255;
  private static final int MAX_CLASS_NAME_CHARS = 100;

  private static final String META = "x-amz-meta-";
  private static final String META_SHA256 = META + "regpack-sha256";
  private static final String META_WRITTEN_AT = META + "regpack-written-at";
  private static final String META_RETAIN_UNTIL = META + "regpack-retain-until";
  private static final String META_CLASS = META + "regpack-class";
  private static final String META_MEDIA_TYPE = META + "regpack-media-type";
  private static final String LOCK_MODE = "x-amz-object-lock-mode";
  private static final String LOCK_RETAIN_UNTIL = "x-amz-object-lock-retain-until-date";
  private static final String LEGAL_HOLD = "x-amz-object-lock-legal-hold";

  private static final Instant FIRST_YEAR_0000 = Instant.parse("0000-01-01T00:00:00Z");
  private static final Instant LAST_YEAR_9999 = Instant.parse("9999-12-31T23:59:59.999999999Z");
  private static final Pattern SIMPLE_NAME = Pattern.compile("[A-Za-z0-9]{1,64}");

  private final WormConfig config;
  private final Clock dataClock;
  private final S3Client client;
  private final String bucket;
  private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];

  private WormEvidenceStore(WormConfig config, Clock dataClock, S3Client client) {
    this.config = config;
    this.dataClock = dataClock;
    this.client = client;
    this.bucket = config.bucket();
    for (int i = 0; i < STRIPES; i++) {
      stripes[i] = new ReentrantLock();
    }
  }

  /**
   * A store on the real network.
   *
   * @param dataClock the time of {@code regpack-written-at} and of the retain-until check
   * @param signingClock the time of {@code X-Amz-Date}: real time, because a service refuses a
   *     request that is far off its own clock
   */
  public static WormEvidenceStore open(WormConfig config, Clock dataClock, Clock signingClock) {
    Objects.requireNonNull(config, "config");
    return open(config, dataClock, signingClock, new JdkTransport(config.requestTimeout()));
  }

  /** A store over the given transport; no request is made until the first call. */
  public static WormEvidenceStore open(
      WormConfig config, Clock dataClock, Clock signingClock, S3Transport transport) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(dataClock, "dataClock");
    Objects.requireNonNull(signingClock, "signingClock");
    Objects.requireNonNull(transport, "transport");
    S3Client client =
        new S3Client(
            config.endpoint(), config.region(), config.credentials(), signingClock, transport);
    return new WormEvidenceStore(config, dataClock, client);
  }

  // ---- grade and durability

  /**
   * {@code ("P-WORM", "governance")} or {@code "compliance"}, plus {@code " (unverified)"} for a
   * store that SP-O has not verified (D-W9). It makes the same first bucket read as the durability
   * check (the versioning request), so that it fails during an outage as the port says; it does not
   * judge the answer.
   */
  @Override
  public Grade grade() {
    s3("getBucketVersioning", () -> client.versioningEnabled(bucket));
    return new Grade(PROFILE, config.gradeMode());
  }

  @Override
  public Durability durability() {
    throw new UnsupportedOperationException("the durability check is built in T8b");
  }

  // ---- put

  @Override
  public PutOutcome put(PutRequest request) {
    Objects.requireNonNull(request, "request");
    Prepared prepared = prepare(request);
    ReentrantLock lock = stripe(prepared.key());
    acquire(lock);
    try {
      return putLocked(prepared);
    } finally {
      lock.unlock();
    }
  }

  private PutOutcome putLocked(Prepared p) {
    List<VersionEntry> versions = versionsOf(p.key());
    requireNotHidden(p.key(), versions);
    Optional<VersionEntry> oldest = oldestData(versions);
    if (oldest.isPresent()) {
      return decide(p, oldest.get());
    }
    Instant now = dataClock.now();
    if (!p.retainUntil().isAfter(now)) {
      throw new IllegalArgumentException("a new object needs a retain-until after the clock");
    }
    String ownVersion = putNew(p, now);
    List<VersionEntry> after = versionsOf(p.key());
    // our own version must be listed as a data version: absent, or shown as a delete marker, it is
    // missing, and that is never CREATED
    Optional<VersionEntry> own =
        after.stream().filter(e -> !e.deleteMarker() && e.versionId().equals(ownVersion)).findAny();
    if (own.isEmpty()) {
      throw unavailable("listObjectVersions", 200, "OwnVersionMissing");
    }
    // present, because our own data version is in the list
    VersionEntry first = oldestData(after).orElse(own.get());
    if (first.versionId().equals(ownVersion)) {
      return PutOutcome.CREATED;
    }
    // another writer's version ranks below ours: it decides, ours stays as an extra version
    return decide(p, first);
  }

  /** The stored oldest version against the request: the same bytes succeed, other bytes alarm. */
  private PutOutcome decide(Prepared p, VersionEntry oldest) {
    Stored stored = readData(p.key(), oldest);
    if (stored.sha256().equals(p.sha256())) {
      return PutOutcome.ALREADY_PRESENT_IDENTICAL;
    }
    throw new EvidenceIntegrityException("different bytes for existing key " + p.key().path());
  }

  /** PutObject with the lock headers and the metadata; returns the version id of the answer. */
  private String putNew(Prepared p, Instant now) {
    Map<String, String> headers = new TreeMap<>();
    headers.put("content-type", CONTENT_TYPE);
    headers.put(LOCK_MODE, config.mode().name());
    headers.put(LOCK_RETAIN_UNTIL, lockDate(p.retainUntil()).toString());
    headers.put(META_SHA256, p.sha256().hex());
    headers.put(META_WRITTEN_AT, now.toString());
    headers.put(META_RETAIN_UNTIL, p.retainUntil().toString());
    headers.put(META_CLASS, p.encodedClass());
    headers.put(META_MEDIA_TYPE, p.encodedMediaType());
    Optional<String> versionId =
        s3("putObject", () -> client.putObject(bucket, p.key().path(), p.bytes(), headers));
    return versionId.orElseThrow(() -> unavailable("putObject", 200, "NoVersionId"));
  }

  /**
   * The lock date of a retain-until (D-W5): rounded up to whole seconds, so that it is never
   * shorter than the record, and at most {@code maxLockDate}.
   */
  private Instant lockDate(Instant retainUntil) {
    long seconds = retainUntil.getEpochSecond() + (retainUntil.getNano() == 0 ? 0 : 1);
    Instant rounded = Instant.ofEpochSecond(seconds);
    return rounded.isAfter(config.maxLockDate()) ? config.maxLockDate() : rounded;
  }

  // ---- get

  @Override
  public Optional<StoredObject> get(EvidenceKey key) {
    Objects.requireNonNull(key, "key");
    List<VersionEntry> versions = versionsOf(key);
    Optional<VersionEntry> oldest = oldestData(versions);
    if (oldest.isEmpty()) {
      return Optional.empty();
    }
    requireNotHidden(key, versions);
    Stored stored = readData(key, oldest.get());
    return Optional.of(
        new StoredObject(
            key,
            stored.bytes(),
            stored.mediaType(),
            stored.sha256(),
            stored.writtenAt(),
            new Retention(new RetentionClass(stored.className()), stored.retainUntil()),
            stored.legalHold()));
  }

  // ---- list

  @Override
  public List<EvidenceKey> list(String prefix, Optional<EvidenceKey> after, int limit) {
    Objects.requireNonNull(prefix, "prefix");
    Objects.requireNonNull(after, "after");
    if (limit < 0) {
      throw new IllegalArgumentException("limit must not be negative");
    }
    if (limit == 0) {
      return List.of();
    }
    KeyCollector keys = new KeyCollector(limit);
    walk(prefix, after.map(EvidenceKey::path), keys::remaining, keys::accept);
    return keys.result();
  }

  /**
   * Collects distinct keys that have a data version, in the order of the listing. A key counts at
   * its first data entry, also when its newest entry is a delete marker or its entries span pages;
   * a key that is not a valid evidence key is passed over.
   */
  private static final class KeyCollector {
    private final int limit;
    private final List<EvidenceKey> keys = new ArrayList<>();
    private String current;
    private boolean counted;

    KeyCollector(int limit) {
      this.limit = limit;
    }

    int remaining() {
      return Math.min(limit - keys.size(), PAGE_SIZE);
    }

    boolean accept(VersionEntry entry) {
      if (!entry.key().equals(current)) {
        current = entry.key();
        counted = false;
      }
      if (counted || entry.deleteMarker()) {
        return true;
      }
      counted = true;
      validKey(entry.key()).ifPresent(keys::add);
      return keys.size() < limit;
    }

    List<EvidenceKey> result() {
      return List.copyOf(keys);
    }
  }

  private static Optional<EvidenceKey> validKey(String path) {
    try {
      return Optional.of(new EvidenceKey(path));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  // ---- retention and legal hold: built in T8b

  @Override
  public void extendRetention(EvidenceKey key, Instant newRetainUntil) {
    throw new UnsupportedOperationException("retention is built in T8b");
  }

  @Override
  public void setLegalHold(EvidenceKey key, boolean hold) {
    throw new UnsupportedOperationException("legal hold is built in T8b");
  }

  // ---- the version list of a key

  /**
   * The entries of the exact key, in list order (newest first), read to the end of the listing. A
   * prefix listing also holds longer keys that start with this key; their entries are ignored.
   */
  private List<VersionEntry> versionsOf(EvidenceKey key) {
    List<VersionEntry> run = new ArrayList<>();
    String path = key.path();
    walk(
        path,
        Optional.empty(),
        () -> PAGE_SIZE,
        entry -> {
          if (entry.key().equals(path)) {
            run.add(entry);
          }
          return true;
        });
    return run;
  }

  /** The oldest data version: the last data entry of the key's run. */
  private static Optional<VersionEntry> oldestData(List<VersionEntry> run) {
    for (int i = run.size() - 1; i >= 0; i--) {
      if (!run.get(i).deleteMarker()) {
        return Optional.of(run.get(i));
      }
    }
    return Optional.empty();
  }

  /** A delete marker as the newest entry over a data version hides the object (WF3). */
  private static void requireNotHidden(EvidenceKey key, List<VersionEntry> run) {
    if (!run.isEmpty()
        && run.get(0).deleteMarker()
        && run.stream().anyMatch(entry -> !entry.deleteMarker())) {
      throw new EvidenceCorruptionException(
          "evidence object is hidden by a delete marker: " + key.path());
    }
  }

  /**
   * Walks ListObjectVersions pages from after {@code startAfter} until the visitor says stop or the
   * listing ends. A truncated page that repeats the markers it was asked with, one without both
   * next markers, and more than {@link #MAX_PAGES} pages are {@link EvidenceUnavailableException}.
   */
  private void walk(
      String prefix,
      Optional<String> startAfter,
      IntSupplier maxKeys,
      Predicate<VersionEntry> visitor) {
    Optional<String> keyMarker = startAfter;
    Optional<String> versionMarker = Optional.empty();
    for (int pages = 0; pages < MAX_PAGES; pages++) {
      Optional<String> sentKey = keyMarker;
      Optional<String> sentVersion = versionMarker;
      int max = maxKeys.getAsInt();
      VersionPage page =
          s3(
              "listObjectVersions",
              () -> client.listVersions(bucket, prefix, sentKey, sentVersion, max));
      for (VersionEntry entry : page.entries()) {
        if (!visitor.test(entry)) {
          return;
        }
      }
      if (!page.truncated()) {
        return;
      }
      if (page.nextKeyMarker().isEmpty() || page.nextVersionIdMarker().isEmpty()) {
        throw unavailable("listObjectVersions", 200, "MissingMarkers");
      }
      // a correct service moves the markers on every truncated page, whatever the page holds; a
      // page that repeats them would otherwise be read again and again (duplicates as success)
      if (page.nextKeyMarker().equals(sentKey) && page.nextVersionIdMarker().equals(sentVersion)) {
        throw unavailable("listObjectVersions", 200, "NoProgress");
      }
      keyMarker = page.nextKeyMarker();
      versionMarker = page.nextVersionIdMarker();
    }
    throw unavailable("listObjectVersions", 200, "TooManyPages");
  }

  // ---- reading one version

  /** What the adapter keeps of a version: its bytes, checked, and the metadata it recorded. */
  private record Stored(
      byte[] bytes,
      Sha256 sha256,
      String mediaType,
      String className,
      Instant writtenAt,
      Instant retainUntil,
      boolean legalHold) {}

  /**
   * GET of one data version, its metadata read and its bytes hashed against the recorded hash. A
   * version that the list showed and the service answers 404 for, missing or unreadable metadata,
   * and bytes that do not match are {@link EvidenceCorruptionException}.
   */
  private Stored readData(EvidenceKey key, VersionEntry version) {
    Optional<S3Response> answer =
        s3("getObject", () -> client.get(bucket, key.path(), version.versionId()));
    if (answer.isEmpty()) {
      throw new EvidenceCorruptionException(
          "a listed version of an evidence object is not readable: " + key.path());
    }
    S3Response response = answer.get();
    Map<String, String> headers = response.headers();
    byte[] bytes = response.body();
    Sha256 recorded;
    String mediaType;
    String className;
    Instant writtenAt;
    Instant retainUntil;
    try {
      recorded = new Sha256(required(headers, META_SHA256));
      writtenAt = Instant.parse(required(headers, META_WRITTEN_AT));
      retainUntil = Instant.parse(required(headers, META_RETAIN_UNTIL));
      className = decodeText(required(headers, META_CLASS));
      mediaType = decodeText(required(headers, META_MEDIA_TYPE));
    } catch (IllegalArgumentException | DateTimeParseException | CharacterCodingException e) {
      // the cause is dropped on purpose: it may quote what the service stored
      throw new EvidenceCorruptionException(
          "the metadata of an evidence object is missing or unreadable: " + key.path());
    }
    if (!Sha256.of(bytes).equals(recorded)) {
      throw new EvidenceCorruptionException(
          "the stored bytes do not match their recorded hash: " + key.path());
    }
    boolean legalHold = "ON".equals(headers.get(LEGAL_HOLD));
    return new Stored(bytes, recorded, mediaType, className, writtenAt, retainUntil, legalHold);
  }

  private static String required(Map<String, String> headers, String name) {
    String value = headers.get(name);
    if (value == null) {
      throw new IllegalArgumentException("metadata is missing");
    }
    return value;
  }

  // ---- validation and the text codec

  /** A request, validated and encoded: nothing here has touched the service. */
  private record Prepared(
      EvidenceKey key,
      byte[] bytes,
      Sha256 sha256,
      Instant retainUntil,
      String encodedMediaType,
      String encodedClass) {}

  private static Prepared prepare(PutRequest request) {
    Instant retainUntil = request.retention().retainUntil();
    requireStorable(retainUntil);
    String mediaType = request.mediaType();
    String className = request.retention().retentionClass().name();
    if (mediaType.length() > MAX_MEDIA_TYPE_CHARS) {
      throw new IllegalArgumentException(
          "a media type is at most " + MAX_MEDIA_TYPE_CHARS + " characters in this store");
    }
    if (className.length() > MAX_CLASS_NAME_CHARS) {
      throw new IllegalArgumentException(
          "a retention class name is at most "
              + MAX_CLASS_NAME_CHARS
              + " characters in this store");
    }
    byte[] bytes = request.bytes();
    return new Prepared(
        request.key(),
        bytes,
        Sha256.of(bytes),
        retainUntil,
        encodeText(mediaType),
        encodeText(className));
  }

  /**
   * The rule of the port (SPEC-7): a retain-until has at most microsecond precision and a year from
   * 0000 to 9999.
   */
  private static void requireStorable(Instant retainUntil) {
    if (retainUntil.getNano() % 1000 != 0) {
      throw new IllegalArgumentException("a retain-until has at most microsecond precision");
    }
    // compared as instants: Instant.MIN and Instant.MAX have no calendar year to read
    if (retainUntil.isBefore(FIRST_YEAR_0000) || retainUntil.isAfter(LAST_YEAR_9999)) {
      throw new IllegalArgumentException("a retain-until must be in the years 0000 to 9999");
    }
  }

  /** UTF-8 then base64url without padding; an unpaired surrogate is an argument error. */
  private static String encodeText(String text) {
    ByteBuffer utf8;
    try {
      utf8 =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(text));
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException("text is not valid Unicode (unpaired surrogate)");
    }
    byte[] bytes = new byte[utf8.remaining()];
    utf8.get(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String decodeText(String value) throws CharacterCodingException {
    byte[] bytes = Base64.getUrlDecoder().decode(value);
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  // ---- locks and failures

  private ReentrantLock stripe(EvidenceKey key) {
    return stripes[Math.floorMod(key.path().hashCode(), STRIPES)];
  }

  private static void acquire(ReentrantLock lock) {
    try {
      lock.lockInterruptibly();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new EvidenceUnavailableException("interrupted while waiting for the key lock");
    }
  }

  /**
   * Runs one client call. An {@link S3Exception} (a status the call does not expect, or no answer
   * at all) becomes {@link EvidenceUnavailableException} with the message of the S3 exception,
   * which holds the operation, the status and the code only; an argument error passes on; any other
   * runtime failure of a transport is reduced to the simple class name.
   */
  private static <T> T s3(String operation, Supplier<T> call) {
    try {
      return call.get();
    } catch (S3Exception e) {
      if (e.status() == 0 && e.code().equals("InterruptedIOException")) {
        Thread.currentThread().interrupt();
      }
      throw new EvidenceUnavailableException(e.getMessage());
    } catch (IllegalArgumentException | NullPointerException e) {
      throw e;
    } catch (RuntimeException e) {
      String name = e.getClass().getSimpleName();
      throw unavailable(operation, 0, SIMPLE_NAME.matcher(name).matches() ? name : "invalid");
    }
  }

  private static EvidenceUnavailableException unavailable(
      String operation, int status, String code) {
    return new EvidenceUnavailableException(
        "S3 " + operation + " status=" + status + " code=" + code);
  }
}
