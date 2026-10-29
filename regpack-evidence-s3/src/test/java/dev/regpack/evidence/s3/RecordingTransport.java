package dev.regpack.evidence.s3;

import dev.regpack.evidence.s3.client.S3Request;
import dev.regpack.evidence.s3.client.S3Response;
import dev.regpack.evidence.s3.client.S3Transport;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * A test decorator around a transport that counts the requests of marked segments by kind and
 * measures the elapsed time of each segment (P3c1 T15-2, the figure of OQ-1: the extra requests
 * that the seal path pays on P-WORM when {@code DurableSeal} is built with the retention
 * registrar).
 *
 * <p><b>What is kept.</b> Of a request only a {@link Kind} survives: a label from a closed list,
 * built from the method and from the NAMES of the query parameters, and from the fact that the path
 * names a key or not. The key, the host, the query values, the headers, the body and the signature
 * are looked at for that and dropped. A shape that the adapter does not send is {@link Kind#OTHER}.
 *
 * <p><b>What it never does.</b> It forwards every request to the delegate unchanged, returns its
 * answer or throws its exception unchanged, and does nothing else on the wire. A failure of its own
 * (the classifier throws) is caught and remembered ({@link #intact()} turns false for good); it
 * never reaches the caller, so a figure that cannot be taken never turns the test it sits in red.
 * Outside a segment the transport only forwards. Thread-safe.
 *
 * <p>The elapsed time of a segment is read from {@link System#nanoTime()} at {@link #mark} and at
 * the end of the segment. It is a measurement of this test run and decides nothing; the data clock
 * of the stores stays the {@code ManualClock}.
 */
final class RecordingTransport implements S3Transport {
  /** The closed list of request kinds; the label is what a report may print. */
  enum Kind {
    PUT_BUCKET("PUT-bucket"),
    GET_VERSIONING("GET-versioning"),
    GET_OBJECT_LOCK("GET-object-lock"),
    GET_VERSIONS("GET-versions"),
    PUT_OBJECT("PUT-object"),
    GET_OBJECT("GET-object"),
    HEAD_OBJECT("HEAD-object"),
    GET_TAGGING("GET-tagging"),
    PUT_RETENTION("PUT-retention"),
    PUT_TAGGING("PUT-tagging"),
    PUT_LEGAL_HOLD("PUT-legal-hold"),
    OTHER("other");

    private final String label;

    Kind(String label) {
      this.label = label;
    }

    String label() {
      return label;
    }
  }

  /** One ended segment: its name, the requests by kind and the elapsed time. */
  record Segment(String name, Map<Kind, Integer> counts, long elapsedNanos) {
    Segment {
      Objects.requireNonNull(name, "name");
      Map<Kind, Integer> copy = new EnumMap<>(Kind.class);
      copy.putAll(counts);
      counts = Collections.unmodifiableMap(copy);
    }

    /** The number of requests of the segment. */
    int requests() {
      int total = 0;
      for (int n : counts.values()) {
        total += n;
      }
      return total;
    }

    /** The counts by label, in the order of the vocabulary. */
    Map<String, Integer> byLabel() {
      Map<String, Integer> out = new LinkedHashMap<>();
      for (Map.Entry<Kind, Integer> e : counts.entrySet()) {
        out.put(e.getKey().label(), e.getValue());
      }
      return out;
    }
  }

  /** The segment that is open: where the counts go until it ends. */
  private static final class Open {
    private final String name;
    private final long startedNanos;
    private final Map<Kind, Integer> counts = new EnumMap<>(Kind.class);

    Open(String name, long startedNanos) {
      this.name = name;
      this.startedNanos = startedNanos;
    }
  }

  private final S3Transport delegate;
  private final Function<S3Request, Kind> classifier;
  private final Object lock = new Object();
  private final List<Segment> ended = new ArrayList<>();
  private Open open;
  private boolean intact = true;

  RecordingTransport(S3Transport delegate) {
    this(delegate, RecordingTransport::classify);
  }

  /** A recorder with a classifier of its own: for the test that shows a failing one. */
  RecordingTransport(S3Transport delegate, Function<S3Request, Kind> classifier) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.classifier = Objects.requireNonNull(classifier, "classifier");
  }

  /** Starts the segment {@code name}; a segment that is open ends first. */
  void mark(String name) {
    Objects.requireNonNull(name, "name");
    long now = System.nanoTime();
    synchronized (lock) {
      endOpen(now);
      open = new Open(name, now);
    }
  }

  /** Ends the open segment; nothing happens when none is open. */
  void stop() {
    long now = System.nanoTime();
    synchronized (lock) {
      endOpen(now);
    }
  }

  private void endOpen(long now) {
    if (open != null) {
      ended.add(new Segment(open.name, open.counts, now - open.startedNanos));
      open = null;
    }
  }

  /** The ended segments, in the order they were marked. */
  List<Segment> segments() {
    synchronized (lock) {
      return List.copyOf(ended);
    }
  }

  /** False once a record failed: the counts may then be short, and a report must not be made. */
  boolean intact() {
    synchronized (lock) {
      return intact;
    }
  }

  @Override
  public S3Response send(S3Request request) throws IOException {
    record(request);
    return delegate.send(request);
  }

  /** Counts the request in the open segment, if any; never throws. */
  private void record(S3Request request) {
    try {
      synchronized (lock) {
        if (open == null) {
          return;
        }
        // the classifier runs under the lock so that a count and a segment end are never torn
        Kind kind = Objects.requireNonNull(classifier.apply(request), "kind");
        open.counts.merge(kind, 1, Integer::sum);
      }
    } catch (RuntimeException e) {
      synchronized (lock) {
        intact = false;
      }
    }
  }

  /**
   * The kind of a request: the method, whether the path names a key (a second path segment after
   * the bucket) and the set of query parameter names, compared as they are on the wire. Nothing
   * else of the request is read.
   */
  static Kind classify(S3Request request) {
    String method = request.method();
    URI uri = request.uri();
    String path = uri.getRawPath();
    boolean onObject = path.indexOf('/', 1) > 0;
    Set<String> names = queryNames(uri.getRawQuery());
    if (!onObject) {
      return switch (method) {
        case "PUT" -> names.isEmpty() ? Kind.PUT_BUCKET : Kind.OTHER;
        case "GET" -> bucketGet(names);
        default -> Kind.OTHER;
      };
    }
    return switch (method) {
      case "PUT" -> objectPut(names);
      case "GET" -> objectGet(names);
      case "HEAD" -> names.equals(Set.of("versionId")) ? Kind.HEAD_OBJECT : Kind.OTHER;
      default -> Kind.OTHER;
    };
  }

  private static Kind bucketGet(Set<String> names) {
    if (names.equals(Set.of("versioning"))) {
      return Kind.GET_VERSIONING;
    }
    if (names.equals(Set.of("object-lock"))) {
      return Kind.GET_OBJECT_LOCK;
    }
    boolean listing =
        names.contains("versions")
            && Set.of("versions", "max-keys", "prefix", "key-marker", "version-id-marker")
                .containsAll(names);
    return listing ? Kind.GET_VERSIONS : Kind.OTHER;
  }

  private static Kind objectPut(Set<String> names) {
    if (names.isEmpty()) {
      return Kind.PUT_OBJECT;
    }
    if (names.equals(Set.of("retention", "versionId"))) {
      return Kind.PUT_RETENTION;
    }
    if (names.equals(Set.of("tagging", "versionId"))) {
      return Kind.PUT_TAGGING;
    }
    if (names.equals(Set.of("legal-hold", "versionId"))) {
      return Kind.PUT_LEGAL_HOLD;
    }
    return Kind.OTHER;
  }

  private static Kind objectGet(Set<String> names) {
    if (names.equals(Set.of("versionId"))) {
      return Kind.GET_OBJECT;
    }
    if (names.equals(Set.of("tagging", "versionId"))) {
      return Kind.GET_TAGGING;
    }
    return Kind.OTHER;
  }

  /** The names of the query parameters, as written; an empty query has none. */
  private static Set<String> queryNames(String rawQuery) {
    Set<String> names = new HashSet<>();
    if (rawQuery == null || rawQuery.isEmpty()) {
      return names;
    }
    for (String part : rawQuery.split("&", -1)) {
      int equals = part.indexOf('=');
      names.add(equals < 0 ? part : part.substring(0, equals));
    }
    return names;
  }
}
