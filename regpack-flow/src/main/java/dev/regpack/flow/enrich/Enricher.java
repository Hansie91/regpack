package dev.regpack.flow.enrich;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.flow.evidence.EvidenceWriter;
import dev.regpack.flow.ingest.FileIngest.Accepted;
import dev.regpack.flow.ingest.MappedRecord;
import dev.regpack.model.BatchId;
import dev.regpack.model.KeyText;
import dev.regpack.model.Sha256;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.PartyKind;
import dev.regpack.model.json.Json;
import dev.regpack.model.json.JsonException;
import dev.regpack.pack.report.EnrichmentPlan;
import dev.regpack.ports.Clock;
import dev.regpack.ports.connector.EnrichmentSource;
import dev.regpack.ports.connector.LookupUnavailableException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.ledger.ManifestLedger;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The enrichment stage between the mapping and the decision (plan W1a, task T8; spec 7.5): fills an
 * absent attribute of a record from the sources of the pack's enrichment plan, never overrides a
 * value the record already has, and seals what it learned as a snapshot that a replay reads instead
 * of calling the sources again.
 *
 * <p><b>What is asked.</b> One request per plan field and source on the distinct lookup keys of the
 * records of a legal entity that carry the key attribute (a natural person is never looked up),
 * split in key order at the source's limit; once a source has been unavailable (timeout, rate
 * limit) in a run, its remaining keys are recorded as errors without another call (retry-after is
 * not honoured in W1a); a later source is asked only for the keys that no earlier source answered
 * with a fresh hit. A record that already has the value is looked up too, so that a disagreement
 * can be seen. Only the field {@value #TARGET} is built.
 *
 * <p><b>What is filled.</b> The first fresh hit in the plan's precedence fills an absent value. A
 * miss, a failure, an unreachable source and a hit older than the source's {@code max_staleness}
 * (measured at the as-of of the snapshot) leave the value absent; so does a hit dated after that
 * as-of (a skewed clock or a future-dated row is not trusted). A value the record has stays; when a
 * fresh hit says otherwise the record gets the warning {@value #DISAGREES}, and the snapshot (only
 * the snapshot) holds both values. A record keeps provenance for every value taken from a source:
 * source, as-of, request key and response hash.
 *
 * <p><b>The snapshot</b> is the object {@code enrichment/<batch digest hex>/1}, written through the
 * {@link EvidenceWriter} before {@link #enrich} returns. It holds the as-of of the run and every
 * answer, so the fills are a function of the records, the plan and the snapshot. Its id is the
 * SHA-256 of its canonical JSON. When the key already holds a snapshot, that one is used, its
 * manifest and retention rows are completed, no source is called and nothing new is written, at any
 * later instant (SF1). The key holds no value of the data, only the digest of the batch.
 *
 * <p>Messages and {@code toString} carry no field value. Not thread-safe per batch.
 */
public final class Enricher {
  /** The warning of a source value that a fresh hit contradicts. */
  public static final String DISAGREES = "ENRICH_DISAGREES";

  private static final String TARGET = "client.lei";
  private static final String MEDIA_TYPE = "application/json";
  private static final String RETENTION_CLASS = "enrichment";
  private static final long SNAPSHOT_VERSION = 1;
  private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
  private static final String BAD_RESPONSE = "BAD_RESPONSE";
  private static final String NO_ANSWER = "NO_ANSWER";
  private static final String UNREADABLE = "the sealed enrichment snapshot cannot be read";

  /**
   * A value taken from a source.
   *
   * @param field the canonical field that was filled
   * @param source the name of the source
   * @param key the request key
   * @param asOf the as-of of the answer
   * @param responseHash the SHA-256 of the canonical bytes of the answer
   */
  public record Provenance(
      String field, String source, String key, Instant asOf, Sha256 responseHash) {
    public Provenance {
      Objects.requireNonNull(field, "field");
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(asOf, "asOf");
      Objects.requireNonNull(responseHash, "responseHash");
    }

    @Override
    public String toString() {
      return "Provenance[field=" + field + ", source=" + source + "]";
    }
  }

  /**
   * A record after enrichment.
   *
   * @param accepted the record as the ingest stage returned it, unchanged
   * @param record the record with the values the sources filled
   * @param provenance one entry for every value taken from a source, in plan order
   * @param warnings the codes of the warnings of this record, no value
   */
  public record EnrichedRecord(
      Accepted accepted, MappedRecord record, List<Provenance> provenance, List<String> warnings) {
    public EnrichedRecord {
      Objects.requireNonNull(accepted, "accepted");
      Objects.requireNonNull(record, "record");
      provenance = List.copyOf(provenance);
      warnings = List.copyOf(warnings);
    }

    @Override
    public String toString() {
      return "EnrichedRecord[provenance=" + provenance.size() + ", warnings=" + warnings + "]";
    }
  }

  /**
   * The result of one batch.
   *
   * @param snapshotId the id of the sealed snapshot: the SHA-256 in hex of its canonical JSON
   * @param records the records in the order they came in
   */
  public record Enriched(String snapshotId, List<EnrichedRecord> records) {
    public Enriched {
      Objects.requireNonNull(snapshotId, "snapshotId");
      records = List.copyOf(records);
    }

    @Override
    public String toString() {
      return "Enriched[snapshotId=" + snapshotId + ", records=" + records.size() + "]";
    }
  }

  private enum Outcome {
    HIT,
    MISS,
    ERROR
  }

  /** One answer, or the lack of one, as the snapshot holds it. */
  private record Lookup(
      String source,
      String key,
      Outcome outcome,
      Instant asOf,
      Optional<String> errorCode,
      Optional<Sha256> responseHash,
      Optional<String> value) {}

  private record Index(String source, String key) {}

  private record Warning(String source, String key, String sourceValue, String hitValue) {}

  private record Resolved(List<EnrichedRecord> records, List<Warning> warnings) {}

  private final Clock clock;
  private final EvidenceWriter writer;
  private final Map<String, EnrichmentSource> sources;

  /**
   * @param clock the clock whose reading is the as-of of a run
   * @param writer the door to the evidence tier
   * @param sources the sources by the names the plan uses
   */
  public Enricher(Clock clock, EvidenceWriter writer, Map<String, EnrichmentSource> sources) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.writer = Objects.requireNonNull(writer, "writer");
    this.sources = Map.copyOf(sources);
  }

  /** The key of the snapshot of a batch: {@code enrichment/<batch digest hex>/1}. */
  public static EvidenceKey snapshotKey(BatchId batch) {
    return EvidenceKey.of("enrichment", batch.landingDigest().hex(), "1");
  }

  /**
   * Enriches the records of one batch.
   *
   * @param batch the batch whose digest names the snapshot
   * @param accepted the records, in file order
   * @param plan the plan of the pack
   * @throws IllegalArgumentException the plan names a field other than the one built, or a source
   *     that was not configured (nothing is looked up or written)
   * @throws dev.regpack.ports.evidence.EvidenceUnavailableException the snapshot cannot be read or
   *     written: no record is returned, and the next call asks the sources again
   */
  public Enriched enrich(BatchId batch, List<Accepted> accepted, EnrichmentPlan plan) {
    Objects.requireNonNull(batch, "batch");
    accepted = List.copyOf(accepted);
    Objects.requireNonNull(plan, "plan");
    Optional<EnrichmentPlan.Field> field = field(plan);
    EvidenceKey key = snapshotKey(batch);

    Optional<EvidenceStore.StoredObject> sealed = writer.get(key);
    if (sealed.isPresent()) {
      return replay(key, sealed.get().bytes(), accepted, field);
    }
    Instant asOf = clock.now();
    Map<Index, Lookup> lookups =
        field.isPresent() ? fetch(batch, accepted, field.get(), asOf) : Map.of();
    Resolved resolved = resolve(accepted, field, asOf, lookups);
    byte[] bytes = write(batch, asOf, field, lookups, resolved.warnings());
    EvidenceStore.PutOutcome outcome =
        writer.put(
            key,
            bytes,
            MEDIA_TYPE,
            RETENTION_CLASS,
            ManifestLedger.ObjectClass.ENRICHMENT_RESPONSE);
    if (outcome != EvidenceStore.PutOutcome.CREATED) {
      // another run sealed the batch first: its snapshot stands, and is the one a decision cites
      return replay(key, writer.get(key).orElseThrow().bytes(), accepted, field);
    }
    return new Enriched(Sha256.of(bytes).hex(), resolved.records());
  }

  private Enriched replay(
      EvidenceKey key,
      byte[] bytes,
      List<Accepted> accepted,
      Optional<EnrichmentPlan.Field> field) {
    Snapshot snapshot = read(bytes);
    writer.recordStored(key, ManifestLedger.ObjectClass.ENRICHMENT_RESPONSE);
    Resolved resolved = resolve(accepted, field, snapshot.asOf(), snapshot.lookups());
    return new Enriched(Sha256.of(bytes).hex(), resolved.records());
  }

  /** The one field of the plan, if any; a plan the stage cannot run is refused. */
  private Optional<EnrichmentPlan.Field> field(EnrichmentPlan plan) {
    if (plan.fields().size() > 1) {
      throw new IllegalArgumentException("the plan names more than one field");
    }
    for (EnrichmentPlan.Field field : plan.fields()) {
      if (!field.field().equals(TARGET)) {
        throw new IllegalArgumentException("the plan names a field that the stage does not fill");
      }
      for (EnrichmentPlan.Source source : field.sources()) {
        if (!sources.containsKey(source.source())) {
          throw new IllegalArgumentException("the plan names a source that is not configured");
        }
      }
    }
    return plan.fields().stream().findFirst();
  }

  // ---- looking up --------------------------------------------------------------------------

  private Map<Index, Lookup> fetch(
      BatchId batch, List<Accepted> accepted, EnrichmentPlan.Field field, Instant asOf) {
    Map<Index, Lookup> lookups = new LinkedHashMap<>();
    for (EnrichmentPlan.Source planned : field.sources()) {
      EnrichmentSource source = sources.get(planned.source());
      TreeSet<String> keys = new TreeSet<>();
      for (Accepted a : accepted) {
        if (a.record().execution().clientKind() != PartyKind.LEGAL_ENTITY) {
          continue; // a natural person is never looked up
        }
        Optional<String> key = Optional.ofNullable(a.record().attributes().get(planned.key()));
        if (key.isPresent() && !answeredByAnEarlierSource(a, field, planned, lookups, asOf)) {
          keys.add(key.get());
        }
      }
      EnrichmentSource.Context context = new EnrichmentSource.Context(field.field(), asOf, batch);
      List<String> pending = new ArrayList<>(keys);
      int chunk = source.limits().maxKeysPerRequest();
      Optional<String> unavailable = Optional.empty();
      for (int from = 0; from < pending.size(); from += chunk) {
        List<String> keysOfRequest = pending.subList(from, Math.min(pending.size(), from + chunk));
        List<Lookup> answered;
        if (unavailable.isPresent()) {
          // the source said it cannot serve this run: it is not asked again (retry-after is not
          // honoured in W1a), so the snapshot never holds a mix of answers and errors of one cause
          answered = failed(planned, keysOfRequest, asOf, unavailable.get());
        } else {
          try {
            answered = request(source, planned, keysOfRequest, context);
          } catch (LookupUnavailableException e) {
            unavailable = Optional.of(e.kind().name());
            answered = failed(planned, keysOfRequest, asOf, unavailable.get());
          }
        }
        for (Lookup lookup : answered) {
          lookups.put(new Index(lookup.source(), lookup.key()), lookup);
        }
      }
    }
    return lookups;
  }

  private static boolean answeredByAnEarlierSource(
      Accepted accepted,
      EnrichmentPlan.Field field,
      EnrichmentPlan.Source current,
      Map<Index, Lookup> lookups,
      Instant asOf) {
    for (EnrichmentPlan.Source earlier : field.sources()) {
      if (earlier == current) {
        return false;
      }
      Optional<Lookup> lookup = lookupFor(accepted.record(), earlier, lookups);
      if (lookup.isPresent() && fresh(lookup.get(), earlier, asOf)) {
        return true;
      }
    }
    return false;
  }

  private static List<Lookup> request(
      EnrichmentSource source,
      EnrichmentPlan.Source planned,
      List<String> keys,
      EnrichmentSource.Context context) {
    EnrichmentSource.Result result = source.lookup(new EnrichmentSource.Request(keys, context));
    Map<String, EnrichmentSource.KeyOutcome> byKey = new HashMap<>();
    for (EnrichmentSource.KeyOutcome outcome : result.outcomes()) {
      byKey.putIfAbsent(outcome.key(), outcome);
    }
    List<Lookup> lookups = new ArrayList<>();
    for (String key : keys) {
      EnrichmentSource.KeyOutcome outcome = byKey.get(key);
      lookups.add(
          outcome == null
              ? error(planned, key, context.asOf(), NO_ANSWER)
              : lookup(planned, outcome));
    }
    return lookups;
  }

  private static List<Lookup> failed(
      EnrichmentPlan.Source planned, List<String> keys, Instant asOf, String errorCode) {
    List<Lookup> failed = new ArrayList<>();
    for (String key : keys) {
      failed.add(error(planned, key, asOf, errorCode));
    }
    return failed;
  }

  /** The answer the run holds for the record's key under this source, if it has the key. */
  private static Optional<Lookup> lookupFor(
      MappedRecord record, EnrichmentPlan.Source planned, Map<Index, Lookup> lookups) {
    return Optional.ofNullable(record.attributes().get(planned.key()))
        .map(key -> lookups.get(new Index(planned.source(), key)));
  }

  private static Lookup lookup(EnrichmentPlan.Source planned, EnrichmentSource.KeyOutcome outcome) {
    String key = outcome.key();
    if (!outcome.responseHash().equals(Sha256.of(outcome.canonical()))) {
      return error(planned, key, outcome.asOf(), BAD_RESPONSE);
    }
    return switch (outcome) {
      case EnrichmentSource.KeyOutcome.Hit hit -> {
        Optional<String> value = leiOf(hit.canonical());
        yield value.isPresent()
            ? new Lookup(
                planned.source(),
                key,
                Outcome.HIT,
                hit.asOf(),
                Optional.empty(),
                Optional.of(hit.responseHash()),
                value)
            : error(planned, key, hit.asOf(), BAD_RESPONSE);
      }
      case EnrichmentSource.KeyOutcome.Miss miss ->
          new Lookup(
              planned.source(),
              key,
              Outcome.MISS,
              miss.asOf(),
              Optional.empty(),
              Optional.of(miss.responseHash()),
              Optional.empty());
      case EnrichmentSource.KeyOutcome.Failure failure ->
          new Lookup(
              planned.source(),
              key,
              Outcome.ERROR,
              failure.asOf(),
              Optional.of(
                  ERROR_CODE.matcher(failure.errorCode()).matches()
                      ? failure.errorCode()
                      : "ERROR"),
              Optional.of(failure.responseHash()),
              Optional.empty());
    };
  }

  private static Lookup error(
      EnrichmentPlan.Source planned, String key, Instant asOf, String errorCode) {
    return new Lookup(
        planned.source(),
        key,
        Outcome.ERROR,
        asOf,
        Optional.of(errorCode),
        Optional.empty(),
        Optional.empty());
  }

  /** The value of a hit: the string {@code lei} of the canonical JSON object, or empty. */
  private static Optional<String> leiOf(byte[] canonical) {
    try {
      if (Json.parse(new String(canonical, UTF_8)) instanceof Map<?, ?> map
          && map.get("lei") instanceof String lei
          && !lei.isBlank()) {
        KeyText.require("lei", lei);
        return Optional.of(lei);
      }
    } catch (JsonException | IllegalArgumentException e) {
      // a source that answers with something else has not answered; the text of the library is not
      // kept
    }
    return Optional.empty();
  }

  // ---- filling -----------------------------------------------------------------------------

  /**
   * Whether a hit is a hit, is not dated after {@code asOf} (a skewed clock or a future-dated row
   * is not trusted) and is not older than the source allows, measured at {@code asOf}.
   */
  private static boolean fresh(Lookup lookup, EnrichmentPlan.Source planned, Instant asOf) {
    return lookup.outcome() == Outcome.HIT
        && !lookup.asOf().isAfter(asOf)
        && Duration.between(lookup.asOf(), asOf).compareTo(planned.maxStaleness()) <= 0;
  }

  private Resolved resolve(
      List<Accepted> accepted,
      Optional<EnrichmentPlan.Field> field,
      Instant asOf,
      Map<Index, Lookup> lookups) {
    List<EnrichedRecord> records = new ArrayList<>();
    TreeSet<String> seen = new TreeSet<>();
    List<Warning> found = new ArrayList<>();
    for (Accepted a : accepted) {
      records.add(resolveRecord(a, field, asOf, lookups, found, seen));
    }
    return new Resolved(records, List.copyOf(found));
  }

  private EnrichedRecord resolveRecord(
      Accepted accepted,
      Optional<EnrichmentPlan.Field> field,
      Instant asOf,
      Map<Index, Lookup> lookups,
      List<Warning> found,
      TreeSet<String> seen) {
    MappedRecord record = accepted.record();
    CanonicalExecution execution = record.execution();
    Optional<Lookup> hit = Optional.empty();
    Optional<EnrichmentPlan.Source> hitSource = Optional.empty();
    if (field.isPresent() && execution.clientKind() == PartyKind.LEGAL_ENTITY) {
      for (EnrichmentPlan.Source planned : field.get().sources()) {
        Optional<Lookup> lookup = lookupFor(record, planned, lookups);
        if (lookup.isPresent() && fresh(lookup.get(), planned, asOf)) {
          hit = lookup;
          hitSource = Optional.of(planned);
          break;
        }
      }
    }
    if (hit.isEmpty()) {
      return new EnrichedRecord(accepted, record, List.of(), List.of());
    }
    Lookup answer = hit.get();
    String value = answer.value().orElseThrow();
    if (execution.clientLei().isEmpty()) {
      Provenance provenance =
          new Provenance(
              field.get().field(),
              answer.source(),
              answer.key(),
              answer.asOf(),
              answer.responseHash().orElseThrow());
      return new EnrichedRecord(
          accepted, withClientLei(record, value), List.of(provenance), List.of());
    }
    String present = execution.clientLei().get();
    if (present.equals(value)) {
      return new EnrichedRecord(accepted, record, List.of(), List.of());
    }
    Warning warning = new Warning(answer.source(), answer.key(), present, value);
    if (seen.add(warning.source() + "\n" + warning.key() + "\n" + present + "\n" + value)) {
      found.add(warning);
    }
    return new EnrichedRecord(accepted, record, List.of(), List.of(DISAGREES));
  }

  private static MappedRecord withClientLei(MappedRecord record, String lei) {
    CanonicalExecution e = record.execution();
    CanonicalExecution filled =
        new CanonicalExecution(
            e.firmLei(),
            e.venueMic(),
            e.venueExecutionId(),
            e.eventKind(),
            e.tradedAt(),
            e.assetClass(),
            e.isin(),
            e.tradedOnVenue(),
            e.clientKind(),
            Optional.of(lei),
            e.quantity(),
            e.price(),
            e.currency(),
            e.side(),
            e.capacity(),
            e.counterparty(),
            e.executionWithinFirm());
    return new MappedRecord(
        record.sourceRecordId(), record.sourceVersion(), filled, record.attributes());
  }

  // ---- the snapshot ------------------------------------------------------------------------

  /** A snapshot read back: the as-of of the run and every answer. */
  private record Snapshot(Instant asOf, Map<Index, Lookup> lookups) {}

  private byte[] write(
      BatchId batch,
      Instant asOf,
      Optional<EnrichmentPlan.Field> field,
      Map<Index, Lookup> lookups,
      List<Warning> warnings) {
    Map<String, Object> top = new LinkedHashMap<>();
    top.put("version", SNAPSHOT_VERSION);
    top.put("batch", batch.value());
    top.put("asOf", asOf.toString());
    List<Object> fields = new ArrayList<>();
    if (field.isPresent()) {
      List<Object> entries = new ArrayList<>();
      for (Lookup lookup : lookups.values()) {
        entries.add(entry(lookup));
      }
      fields.add(Map.of("field", field.get().field(), "lookups", entries));
    }
    top.put("fields", fields);
    List<Object> warned = new ArrayList<>();
    warnings.stream()
        .sorted(
            Comparator.comparing(Warning::source)
                .thenComparing(Warning::key)
                .thenComparing(Warning::sourceValue)
                .thenComparing(Warning::hitValue))
        .forEach(
            w ->
                warned.add(
                    Map.of(
                        "code", DISAGREES,
                        "field", TARGET,
                        "source", w.source(),
                        "key", w.key(),
                        "sourceValue", w.sourceValue(),
                        "hitValue", w.hitValue())));
    top.put("warnings", warned);
    return Json.canonical(top).getBytes(UTF_8);
  }

  private static Map<String, Object> entry(Lookup lookup) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("source", lookup.source());
    entry.put("key", lookup.key());
    entry.put("outcome", lookup.outcome().name());
    entry.put("asOf", lookup.asOf().toString());
    lookup.errorCode().ifPresent(v -> entry.put("errorCode", v));
    lookup.responseHash().ifPresent(v -> entry.put("responseHash", v.hex()));
    lookup.value().ifPresent(v -> entry.put("value", v));
    return entry;
  }

  private static Snapshot read(byte[] bytes) {
    try {
      Object parsed = Json.parse(new String(bytes, UTF_8));
      Map<?, ?> top = (Map<?, ?>) parsed;
      if (!Long.valueOf(SNAPSHOT_VERSION).equals(top.get("version"))) {
        throw new IllegalStateException(UNREADABLE);
      }
      Instant asOf = Instant.parse((String) top.get("asOf"));
      Map<Index, Lookup> lookups = new LinkedHashMap<>();
      for (Object field : (List<?>) top.get("fields")) {
        for (Object item : (List<?>) ((Map<?, ?>) field).get("lookups")) {
          Lookup lookup = lookup((Map<?, ?>) item);
          lookups.put(new Index(lookup.source(), lookup.key()), lookup);
        }
      }
      return new Snapshot(asOf, lookups);
    } catch (JsonException
        | ClassCastException
        | NullPointerException
        | DateTimeParseException
        | IllegalArgumentException e) {
      // the text of the library names parts of the object: it is not kept and not chained
      throw new IllegalStateException(UNREADABLE);
    }
  }

  private static Lookup lookup(Map<?, ?> item) {
    return new Lookup(
        (String) item.get("source"),
        (String) item.get("key"),
        Outcome.valueOf((String) item.get("outcome")),
        Instant.parse((String) item.get("asOf")),
        Optional.ofNullable((String) item.get("errorCode")),
        Optional.ofNullable((String) item.get("responseHash")).map(Sha256::new),
        Optional.ofNullable((String) item.get("value")));
  }
}
