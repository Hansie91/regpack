package dev.regpack.flow.enrich;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.flow.enrich.Enricher.Enriched;
import dev.regpack.flow.enrich.Enricher.EnrichedRecord;
import dev.regpack.flow.evidence.EvidenceWriter;
import dev.regpack.flow.evidence.TwoCopyEvidenceStore;
import dev.regpack.flow.ingest.FileIngest.Accepted;
import dev.regpack.flow.ingest.MappedRecord;
import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.model.BatchId;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.canonical.AssetClass;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.model.canonical.PartyKind;
import dev.regpack.model.json.Json;
import dev.regpack.pack.check.ProblemSink;
import dev.regpack.pack.report.EnrichmentPlan;
import dev.regpack.pack.yaml.StrictYaml;
import dev.regpack.ports.connector.CheckResult;
import dev.regpack.ports.connector.ConnectorSpec;
import dev.regpack.ports.connector.EnrichmentSource;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import dev.regpack.ports.evidence.RetentionPolicy;
import dev.regpack.ports.ledger.ManifestLedger;
import dev.regpack.ports.ledger.RetentionLedger.RetentionClaim;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.enrichment.ScriptedEnrichmentSource;
import dev.regpack.testkit.ident.SyntheticIds;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The enrichment stage (plan W1a, task T8, spec 7.5): what is looked up, what is filled, what is
 * never overridden, and the sealed snapshot that makes a replay call no source (SF1).
 */
class EnricherTest {
  private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
  private static final Regime REGIME = new Regime("demo.tr.s1");
  private static final BatchId BATCH = BatchId.of(Sha256.ofUtf8("w1a enrich batch"), 0);
  private static final String HEX = BATCH.landingDigest().hex();
  private static final EnrichmentSource.Limits LIMITS =
      new EnrichmentSource.Limits(10, Duration.ofSeconds(2), 0);

  private ManualClock clock;
  private InMemoryEvidenceStore store;
  private InMemoryLedgerStore ledger;
  private List<RetentionClaim> claims;
  private EvidenceWriter writer;

  @BeforeEach
  void setUp() {
    clock = new ManualClock(NOW);
    store = new InMemoryEvidenceStore(clock);
    ledger = new InMemoryLedgerStore(clock);
    claims = new ArrayList<>();
    writer =
        new EvidenceWriter(
            store,
            ledger,
            claim -> {
              claims.add(claim);
              ledger.recordRetention(List.of(claim));
            },
            RetentionPolicy.fixedDays(1826),
            clock,
            REGIME);
  }

  // ---- fixtures ---------------------------------------------------------------------------

  private static String sample(String name) {
    try (InputStream in =
        EnricherTest.class.getResourceAsStream("/dev/regpack/flow/samples/" + name)) {
      return new String(in.readAllBytes(), UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("missing sample", e);
    }
  }

  private StaticDataSource staticData() {
    return new StaticDataSource(sample("static-data.csv"), clock, LIMITS);
  }

  private static EnrichmentPlan plan(String entries) {
    ProblemSink sink = ProblemSink.forFile();
    EnrichmentPlan plan =
        EnrichmentPlan.parse(
                "enrich/plan.yaml",
                StrictYaml.parse(entries.isEmpty() ? "fields: []\n" : "fields:\n" + entries),
                sink)
            .orElseThrow();
    assertThat(sink.isEmpty()).isTrue();
    return plan;
  }

  private static EnrichmentPlan demoPlan(String maxStaleness) {
    return plan(
        "  - {field: client.lei, needed_by: SCOPE, sources: [{source: static-data, key: account,"
            + " max_staleness: "
            + maxStaleness
            + "}], on_miss: UNDETERMINED}\n");
  }

  private Enricher enricher(Map<String, EnrichmentSource> sources) {
    return new Enricher(clock, writer, sources);
  }

  private Enricher enricher(EnrichmentSource source) {
    return enricher(Map.of("static-data", source));
  }

  private static Accepted accepted(
      String id, String account, PartyKind kind, Optional<String> clientLei) {
    CanonicalExecution execution =
        new CanonicalExecution(
            SyntheticIds.FIRM_LEI,
            "XPAR",
            "EX-" + id,
            LifecycleKind.NEW,
            Instant.parse("2026-10-30T09:30:00.123456Z"),
            AssetClass.EQUITY,
            SyntheticIds.ISIN_1,
            true,
            kind,
            clientLei,
            new BigDecimal("100"),
            new BigDecimal("12.25"),
            "EUR");
    Map<String, String> attributes = new LinkedHashMap<>();
    if (account != null) {
      attributes.put("account", account);
    }
    return new Accepted(
        new MappedRecord(id, 1, execution, attributes),
        new IngestKey("demo-csv", id, 1),
        Sha256.ofUtf8(id));
  }

  private static Accepted legal(String id, String account) {
    return accepted(id, account, PartyKind.LEGAL_ENTITY, Optional.empty());
  }

  private static Optional<String> lei(EnrichedRecord record) {
    return record.record().execution().clientLei();
  }

  private ScriptedEnrichmentSource.Builder scripted(String name) {
    return ScriptedEnrichmentSource.builder(clock, name).limits(LIMITS);
  }

  /** A test-scope decorator: the inner source answers, then the script may change the answer. */
  private record Rewriting(
      EnrichmentSource inner,
      BiFunction<EnrichmentSource.Request, EnrichmentSource.Result, EnrichmentSource.Result> script)
      implements EnrichmentSource {
    @Override
    public ConnectorSpec spec() {
      return inner.spec();
    }

    @Override
    public CheckResult check() {
      return inner.check();
    }

    @Override
    public Limits limits() {
      return inner.limits();
    }

    @Override
    public Result lookup(Request request) {
      return script.apply(request, inner.lookup(request));
    }
  }

  /** The lookups of the sealed snapshot, as parsed JSON, in snapshot order. */
  private List<Map<String, Object>> snapshotLookups() {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> fields = (List<Map<String, Object>>) snapshot().get("fields");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> lookups = (List<Map<String, Object>>) fields.get(0).get("lookups");
    return lookups;
  }

  private String snapshotText() {
    return new String(store.get(Enricher.snapshotKey(BATCH)).orElseThrow().bytes(), UTF_8);
  }

  private Map<String, Object> snapshot() {
    EvidenceStore.StoredObject stored =
        store.get(Enricher.snapshotKey(BATCH)).orElseThrow(() -> new AssertionError("no snapshot"));
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed =
        (Map<String, Object>) Json.parse(new String(stored.bytes(), UTF_8));
    return parsed;
  }

  // ---- filling ----------------------------------------------------------------------------

  @Test
  void fillsAnAbsentClientLeiFromStaticDataAndKeepsProvenance() {
    Accepted in = legal("T-002", "ACC-2");

    Enriched out = enricher(staticData()).enrich(BATCH, List.of(in), demoPlan("P30D"));

    EnrichedRecord record = out.records().get(0);
    assertThat(record.accepted()).isSameAs(in);
    assertThat(lei(record)).contains(SyntheticIds.CLIENT2_LEI);
    // everything else of the record is the record that came in
    assertThat(record.record().sourceRecordId()).isEqualTo("T-002");
    assertThat(record.record().sourceVersion()).isEqualTo(1);
    assertThat(record.record().attributes()).isEqualTo(Map.of("account", "ACC-2"));
    assertThat(record.record().execution())
        .isEqualTo(
            new CanonicalExecution(
                SyntheticIds.FIRM_LEI,
                "XPAR",
                "EX-T-002",
                LifecycleKind.NEW,
                Instant.parse("2026-10-30T09:30:00.123456Z"),
                AssetClass.EQUITY,
                SyntheticIds.ISIN_1,
                true,
                PartyKind.LEGAL_ENTITY,
                Optional.of(SyntheticIds.CLIENT2_LEI),
                new BigDecimal("100"),
                new BigDecimal("12.25"),
                "EUR"));
    // provenance per value: source, as-of, request key, response hash
    byte[] canonical = Json.canonical(Map.of("lei", SyntheticIds.CLIENT2_LEI)).getBytes(UTF_8);
    assertThat(record.provenance())
        .containsExactly(
            new Enricher.Provenance(
                "client.lei", "static-data", "ACC-2", NOW, Sha256.of(canonical)));
    assertThat(record.warnings()).isEmpty();
  }

  @Test
  void aSourceValueIsNeverOverriddenAndADisagreementIsAWarningWithBothValues() {
    // the record says client 1; the static data says client 2 for its account
    Accepted differs =
        accepted("T-A", "ACC-2", PartyKind.LEGAL_ENTITY, Optional.of(SyntheticIds.CLIENT1_LEI));
    Accepted agrees =
        accepted("T-B", "ACC-1", PartyKind.LEGAL_ENTITY, Optional.of(SyntheticIds.CLIENT1_LEI));

    Enriched out = enricher(staticData()).enrich(BATCH, List.of(differs, agrees), demoPlan("P30D"));

    EnrichedRecord first = out.records().get(0);
    assertThat(lei(first)).as("the source value stays").contains(SyntheticIds.CLIENT1_LEI);
    assertThat(first.record()).isEqualTo(differs.record());
    assertThat(first.warnings()).containsExactly("ENRICH_DISAGREES");
    assertThat(first.provenance()).as("nothing was taken from the hit").isEmpty();
    EnrichedRecord second = out.records().get(1);
    assertThat(lei(second)).contains(SyntheticIds.CLIENT1_LEI);
    assertThat(second.warnings()).as("an agreeing value is no warning").isEmpty();

    // both values are in the snapshot, and only there
    String text = new String(store.get(Enricher.snapshotKey(BATCH)).orElseThrow().bytes(), UTF_8);
    assertThat(text).contains(SyntheticIds.CLIENT1_LEI).contains(SyntheticIds.CLIENT2_LEI);
    assertThat(first.toString())
        .doesNotContain(SyntheticIds.CLIENT1_LEI)
        .doesNotContain(SyntheticIds.CLIENT2_LEI);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> warnings = (List<Map<String, Object>>) snapshot().get("warnings");
    assertThat(warnings).hasSize(1);
    assertThat(warnings.get(0))
        .containsEntry("code", "ENRICH_DISAGREES")
        .containsEntry("field", "client.lei")
        .containsEntry("sourceValue", SyntheticIds.CLIENT1_LEI)
        .containsEntry("hitValue", SyntheticIds.CLIENT2_LEI);
  }

  @Test
  void aMissLeavesTheFieldAbsentSoScopeGivesUndetermined() {
    Accepted in = legal("T-009", "ACC-9");

    Enriched out = enricher(staticData()).enrich(BATCH, List.of(in), demoPlan("P30D"));

    EnrichedRecord record = out.records().get(0);
    assertThat(lei(record)).isEmpty();
    assertThat(record.record()).isEqualTo(in.record());
    assertThat(record.provenance()).isEmpty();
  }

  @Test
  void aStaleHitCountsAsAMiss() {
    Instant threeDaysAgo = NOW.minus(Duration.ofDays(3));
    ScriptedEnrichmentSource source =
        scripted("static-data")
            .staleHit("ACC-1", Map.of("lei", SyntheticIds.CLIENT1_LEI), threeDaysAgo)
            .build();
    Accepted in = legal("T-001", "ACC-1");

    // older than the staleness: a miss (each call is its own batch, so none replays a snapshot)
    BatchId other = BatchId.of(Sha256.ofUtf8("w1a stale batch"), 0);
    assertThat(lei(enricher(source).enrich(BATCH, List.of(in), demoPlan("P1D")).records().get(0)))
        .isEmpty();
    assertThat(lei(enricher(source).enrich(other, List.of(in), demoPlan("PT71H")).records().get(0)))
        .isEmpty();
  }

  @Test
  void aHitExactlyAsOldAsTheStalenessIsStillFresh() {
    Instant threeDaysAgo = NOW.minus(Duration.ofDays(3));
    ScriptedEnrichmentSource source =
        scripted("static-data")
            .staleHit("ACC-1", Map.of("lei", SyntheticIds.CLIENT1_LEI), threeDaysAgo)
            .build();

    Enriched out =
        enricher(source).enrich(BATCH, List.of(legal("T-001", "ACC-1")), demoPlan("P3D"));

    assertThat(lei(out.records().get(0))).contains(SyntheticIds.CLIENT1_LEI);
    assertThat(out.records().get(0).provenance().get(0).asOf()).isEqualTo(threeDaysAgo);
  }

  // ---- what is asked ----------------------------------------------------------------------

  @Test
  void oneRequestPerFieldOnDistinctKeys() {
    ScriptedEnrichmentSource scripted =
        scripted("static-data")
            .hit("ACC-1", Map.of("lei", SyntheticIds.CLIENT1_LEI))
            .hit("ACC-2", Map.of("lei", SyntheticIds.CLIENT2_LEI))
            .build();
    RecordingSource source = new RecordingSource(scripted);

    Enriched out =
        enricher(source)
            .enrich(
                BATCH,
                List.of(
                    legal("T-1", "ACC-2"),
                    legal("T-2", "ACC-2"),
                    legal("T-3", "ACC-1"),
                    accepted("T-4", "ACC-9", PartyKind.NATURAL_PERSON, Optional.empty()),
                    legal("T-5", null)),
                demoPlan("P30D"));

    assertThat(scripted.requestCount()).isEqualTo(1);
    assertThat(source.requestedKeys()).containsExactly(List.of("ACC-1", "ACC-2"));
    EnrichmentSource.Context context = source.requests().get(0).context();
    assertThat(context.field()).isEqualTo("client.lei");
    assertThat(context.asOf()).isEqualTo(NOW);
    assertThat(context.batchId()).isEqualTo(BATCH);
    assertThat(out.records()).hasSize(5);
    assertThat(out.records().stream().map(EnricherTest::lei).toList())
        .containsExactly(
            Optional.of(SyntheticIds.CLIENT2_LEI),
            Optional.of(SyntheticIds.CLIENT2_LEI),
            Optional.of(SyntheticIds.CLIENT1_LEI),
            Optional.empty(),
            Optional.empty());
  }

  @Test
  void aRequestLargerThanTheSourceLimitIsSplitOnSortedDistinctKeys() {
    EnrichmentSource.Limits two = new EnrichmentSource.Limits(2, Duration.ofSeconds(2), 0);
    ScriptedEnrichmentSource scripted =
        ScriptedEnrichmentSource.builder(clock, "static-data")
            .limits(two)
            .hit("ACC-5", Map.of("lei", SyntheticIds.CLIENT1_LEI))
            .build();
    RecordingSource source = new RecordingSource(scripted);

    Enriched out =
        enricher(source)
            .enrich(
                BATCH,
                List.of(
                    legal("T-1", "ACC-5"),
                    legal("T-2", "ACC-3"),
                    legal("T-3", "ACC-1"),
                    legal("T-4", "ACC-4"),
                    legal("T-5", "ACC-2")),
                demoPlan("P30D"));

    assertThat(source.requestedKeys())
        .containsExactly(List.of("ACC-1", "ACC-2"), List.of("ACC-3", "ACC-4"), List.of("ACC-5"));
    assertThat(lei(out.records().get(0))).contains(SyntheticIds.CLIENT1_LEI);
  }

  @Test
  void aNaturalPersonIsNeverLookedUp() {
    ScriptedEnrichmentSource scripted =
        scripted("static-data").hit("ACC-9", Map.of("lei", SyntheticIds.CLIENT1_LEI)).build();
    Accepted person = accepted("T-006", "ACC-9", PartyKind.NATURAL_PERSON, Optional.empty());

    Enriched out = enricher(scripted).enrich(BATCH, List.of(person), demoPlan("P30D"));

    assertThat(scripted.requestCount()).as("not even an empty request").isZero();
    assertThat(lei(out.records().get(0))).isEmpty();
    assertThat(out.records().get(0).record()).isEqualTo(person.record());
  }

  @Test
  void aSourceThatCannotBeReachedLeavesTheFieldAbsentAndIsRecordedAsAnError() {
    ScriptedEnrichmentSource scripted =
        scripted("static-data").hit("ACC-1", Map.of("lei", SyntheticIds.CLIENT1_LEI)).build();
    scripted.armTimeout();

    Enriched out =
        enricher(scripted).enrich(BATCH, List.of(legal("T-1", "ACC-1")), demoPlan("P30D"));

    assertThat(lei(out.records().get(0))).isEmpty();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> fields = (List<Map<String, Object>>) snapshot().get("fields");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> lookups = (List<Map<String, Object>>) fields.get(0).get("lookups");
    assertThat(lookups).hasSize(1);
    assertThat(lookups.get(0))
        .containsEntry("outcome", "ERROR")
        .containsEntry("errorCode", "TIMEOUT")
        .containsEntry("source", "static-data")
        .containsEntry("key", "ACC-1")
        .doesNotContainKey("value");
  }

  @Test
  void aHitThatHoldsNoLeiIsAnErrorAndNotAFill() {
    ScriptedEnrichmentSource scripted =
        scripted("static-data")
            .hit("ACC-1", Map.of("other", "x"))
            .hit("ACC-2", Map.of("lei", " "))
            .build();

    Enriched out =
        enricher(scripted)
            .enrich(BATCH, List.of(legal("T-1", "ACC-1"), legal("T-2", "ACC-2")), demoPlan("P30D"));

    assertThat(out.records().stream().map(EnricherTest::lei).toList())
        .containsExactly(Optional.empty(), Optional.empty());
  }

  @Test
  void aLaterSourceIsAskedOnlyForWhatTheEarlierOneDidNotAnswer() {
    EnrichmentPlan twoSources =
        plan(
            "  - {field: client.lei, needed_by: SCOPE, sources: [{source: first, key: account,"
                + " max_staleness: P1D}, {source: second, key: name, max_staleness: P1D}],"
                + " on_miss: UNDETERMINED}\n");
    RecordingSource first =
        new RecordingSource(
            scripted("first").hit("ACC-1", Map.of("lei", SyntheticIds.CLIENT1_LEI)).build());
    RecordingSource second =
        new RecordingSource(
            scripted("second")
                .hit("N-1", Map.of("lei", SyntheticIds.LAPSED_LEI))
                .hit("N-2", Map.of("lei", SyntheticIds.CLIENT2_LEI))
                .build());
    Accepted answeredByFirst =
        withAttributes(legal("T-1", "ACC-1"), Map.of("account", "ACC-1", "name", "N-1"));
    Accepted answeredBySecond =
        withAttributes(legal("T-2", "ACC-9"), Map.of("account", "ACC-9", "name", "N-2"));
    Accepted answeredByNone = withAttributes(legal("T-3", "ACC-8"), Map.of("account", "ACC-8"));

    Enriched out =
        enricher(Map.of("first", first, "second", second))
            .enrich(BATCH, List.of(answeredByFirst, answeredBySecond, answeredByNone), twoSources);

    assertThat(first.requestedKeys()).containsExactly(List.of("ACC-1", "ACC-8", "ACC-9"));
    assertThat(second.requestedKeys())
        .as("only for the one the first did not answer")
        .containsExactly(List.of("N-2"));
    assertThat(lei(out.records().get(0))).as("precedence").contains(SyntheticIds.CLIENT1_LEI);
    assertThat(out.records().get(0).provenance().get(0).source()).isEqualTo("first");
    assertThat(lei(out.records().get(1))).contains(SyntheticIds.CLIENT2_LEI);
    assertThat(out.records().get(1).provenance().get(0).source()).isEqualTo("second");
    assertThat(lei(out.records().get(2))).isEmpty();
  }

  private static Accepted withAttributes(Accepted in, Map<String, String> attributes) {
    return new Accepted(
        new MappedRecord(
            in.record().sourceRecordId(),
            in.record().sourceVersion(),
            in.record().execution(),
            attributes),
        in.key(),
        in.contentHash());
  }

  @Test
  void aPlanThatTheStageCannotRunIsRefusedBeforeAnyLookup() {
    ScriptedEnrichmentSource scripted = scripted("static-data").build();
    EnrichmentPlan otherField =
        plan(
            "  - {field: client.name, needed_by: SCOPE, sources: [{source: static-data, key:"
                + " account, max_staleness: P1D}], on_miss: UNDETERMINED}\n");
    EnrichmentPlan unknownSource =
        plan(
            "  - {field: client.lei, needed_by: SCOPE, sources: [{source: elsewhere, key:"
                + " account, max_staleness: P1D}], on_miss: UNDETERMINED}\n");

    assertThatThrownBy(
            () -> enricher(scripted).enrich(BATCH, List.of(legal("T-1", "ACC-1")), otherField))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> enricher(scripted).enrich(BATCH, List.of(legal("T-1", "ACC-1")), unknownSource))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(scripted.requestCount()).isZero();
    assertThat(store.get(Enricher.snapshotKey(BATCH))).isEmpty();
  }

  @Test
  void anEmptyPlanOrBatchStillSealsASnapshotForTheContext() {
    Enriched noPlan =
        enricher(staticData()).enrich(BATCH, List.of(legal("T-1", "ACC-1")), plan(""));
    assertThat(noPlan.records()).hasSize(1);
    assertThat(lei(noPlan.records().get(0))).isEmpty();
    assertThat(noPlan.snapshotId()).hasSize(64);
    assertThat(store.get(Enricher.snapshotKey(BATCH))).isPresent();
  }

  @Test
  void aHitDatedAfterTheRunIsNotTrustedAndCountsAsAMiss() {
    // a skewed clock or a future-dated row: one microsecond after the run as-of is already too late
    ScriptedEnrichmentSource source =
        scripted("static-data")
            .staleHit(
                "ACC-1", Map.of("lei", SyntheticIds.CLIENT1_LEI), NOW.plus(Duration.ofNanos(1_000)))
            .staleHit("ACC-2", Map.of("lei", SyntheticIds.CLIENT2_LEI), NOW)
            .build();

    Enriched out =
        enricher(source)
            .enrich(BATCH, List.of(legal("T-1", "ACC-1"), legal("T-2", "ACC-2")), demoPlan("P30D"));

    assertThat(lei(out.records().get(0))).isEmpty();
    assertThat(out.records().get(0).provenance()).isEmpty();
    assertThat(lei(out.records().get(1))).as("dated exactly at the run").isPresent();
  }

  @Test
  void aPlanWithTwoEntriesForTheFieldIsRefusedBeforeAnyLookup() {
    EnrichmentPlan.Source planned =
        new EnrichmentPlan.Source("static-data", "account", Duration.ofDays(1));
    EnrichmentPlan.Field entry =
        new EnrichmentPlan.Field(
            "client.lei", List.of(planned), EnrichmentPlan.OnMiss.UNDETERMINED);
    EnrichmentPlan twice = new EnrichmentPlan("enrich/plan.yaml", List.of(entry, entry));
    ScriptedEnrichmentSource scripted = scripted("static-data").build();

    assertThatThrownBy(
            () -> enricher(scripted).enrich(BATCH, List.of(legal("T-1", "ACC-1")), twice))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(scripted.requestCount()).isZero();
    assertThat(store.get(Enricher.snapshotKey(BATCH))).isEmpty();
  }

  // ---- fail closed on an answer that cannot be trusted ------------------------------------

  @Test
  void anAnswerWhoseHashIsNotTheHashOfItsBytesIsABadResponseAndNotAFillOrAMiss() {
    ScriptedEnrichmentSource scripted =
        scripted("static-data")
            .hit("ACC-1", Map.of("lei", SyntheticIds.CLIENT1_LEI))
            .miss("ACC-2")
            .build();
    Sha256 wrong = Sha256.ofUtf8("not the bytes");
    EnrichmentSource tampered =
        new Rewriting(
            scripted,
            (request, result) ->
                new EnrichmentSource.Result(
                    result.outcomes().stream()
                        .<EnrichmentSource.KeyOutcome>map(
                            o ->
                                o instanceof EnrichmentSource.KeyOutcome.Hit
                                    ? new EnrichmentSource.KeyOutcome.Hit(
                                        o.key(), o.asOf(), o.canonical(), wrong)
                                    : new EnrichmentSource.KeyOutcome.Miss(
                                        o.key(), o.asOf(), o.canonical(), wrong))
                        .toList()));

    Enriched out =
        enricher(tampered)
            .enrich(BATCH, List.of(legal("T-1", "ACC-1"), legal("T-2", "ACC-2")), demoPlan("P30D"));

    assertThat(out.records().stream().map(EnricherTest::lei).toList())
        .containsExactly(Optional.empty(), Optional.empty());
    assertThat(out.records().get(0).provenance()).isEmpty();
    assertThat(snapshotLookups()).hasSize(2);
    assertThat(snapshotLookups())
        .allSatisfy(
            lookup ->
                assertThat(lookup)
                    .containsEntry("outcome", "ERROR")
                    .containsEntry("errorCode", "BAD_RESPONSE")
                    .doesNotContainKey("value")
                    .doesNotContainKey("responseHash"));
    assertThat(snapshotText()).doesNotContain(SyntheticIds.CLIENT1_LEI);
  }

  @Test
  void aSourceThatOmitsARequestedKeyLeavesAnErrorNoAnswer() {
    ScriptedEnrichmentSource scripted =
        scripted("static-data")
            .hit("ACC-1", Map.of("lei", SyntheticIds.CLIENT1_LEI))
            .hit("ACC-2", Map.of("lei", SyntheticIds.CLIENT2_LEI))
            .build();
    EnrichmentSource forgetful =
        new Rewriting(
            scripted,
            (request, result) ->
                new EnrichmentSource.Result(
                    result.outcomes().stream().filter(o -> !o.key().equals("ACC-2")).toList()));

    Enriched out =
        enricher(forgetful)
            .enrich(BATCH, List.of(legal("T-1", "ACC-1"), legal("T-2", "ACC-2")), demoPlan("P30D"));

    assertThat(lei(out.records().get(0))).contains(SyntheticIds.CLIENT1_LEI);
    assertThat(lei(out.records().get(1))).isEmpty();
    assertThat(snapshotLookups())
        .filteredOn(lookup -> "ACC-2".equals(lookup.get("key")))
        .singleElement()
        .satisfies(
            lookup ->
                assertThat(lookup)
                    .containsEntry("outcome", "ERROR")
                    .containsEntry("errorCode", "NO_ANSWER")
                    .doesNotContainKey("value"));
  }

  @Test
  void aFailureOutcomeKeepsAWellFormedCodeAndNeverTheTextOfTheRecord() {
    String tooLong = "E" + "X".repeat(64);
    ScriptedEnrichmentSource scripted =
        scripted("static-data")
            .failure("ACC-1", "E_UPSTREAM", true)
            .failure("ACC-2", "e_lower_case", false)
            .failure("ACC-3", tooLong, false)
            .failure("ACC-4", "client " + SyntheticIds.CLIENT1_LEI, false)
            .build();

    Enriched out =
        enricher(scripted)
            .enrich(
                BATCH,
                List.of(
                    legal("T-1", "ACC-1"),
                    legal("T-2", "ACC-2"),
                    legal("T-3", "ACC-3"),
                    legal("T-4", "ACC-4")),
                demoPlan("P30D"));

    assertThat(out.records().stream().map(EnricherTest::lei))
        .allSatisfy(lei -> assertThat(lei).isEmpty());
    Map<Object, Object> codes = new LinkedHashMap<>();
    for (Map<String, Object> lookup : snapshotLookups()) {
      assertThat(lookup).containsEntry("outcome", "ERROR").doesNotContainKey("value");
      codes.put(lookup.get("key"), lookup.get("errorCode"));
    }
    assertThat(codes)
        .containsEntry("ACC-1", "E_UPSTREAM")
        .containsEntry("ACC-2", "ERROR")
        .containsEntry("ACC-3", "ERROR")
        .containsEntry("ACC-4", "ERROR");
    assertThat(snapshotText())
        .doesNotContain("e_lower_case")
        .doesNotContain(tooLong)
        .doesNotContain(SyntheticIds.CLIENT1_LEI);
  }

  @Test
  void aSourceThatHasBeenUnavailableIsNotAskedAgainInTheSameRun() {
    EnrichmentSource.Limits one = new EnrichmentSource.Limits(1, Duration.ofSeconds(2), 0);
    ScriptedEnrichmentSource scripted =
        ScriptedEnrichmentSource.builder(clock, "static-data")
            .limits(one)
            .hit("ACC-1", Map.of("lei", SyntheticIds.CLIENT1_LEI))
            .hit("ACC-2", Map.of("lei", SyntheticIds.CLIENT2_LEI))
            .hit("ACC-3", Map.of("lei", SyntheticIds.LAPSED_LEI))
            .rateLimitedOnRequest(1, Duration.ofSeconds(30))
            .build();
    RecordingSource source = new RecordingSource(scripted);

    Enriched out =
        enricher(source)
            .enrich(
                BATCH,
                List.of(legal("T-1", "ACC-1"), legal("T-2", "ACC-2"), legal("T-3", "ACC-3")),
                demoPlan("P30D"));

    assertThat(source.requests()).as("asked once, then left alone").hasSize(1);
    assertThat(out.records().stream().map(EnricherTest::lei))
        .allSatisfy(lei -> assertThat(lei).isEmpty());
    assertThat(snapshotLookups())
        .extracting(lookup -> lookup.get("key") + ":" + lookup.get("errorCode"))
        .containsExactly("ACC-1:RATE_LIMITED", "ACC-2:RATE_LIMITED", "ACC-3:RATE_LIMITED");
  }

  @Test
  void anUnavailableSourceDoesNotStopALaterSourceOfThePlan() {
    EnrichmentPlan twoSources =
        plan(
            "  - {field: client.lei, needed_by: SCOPE, sources: [{source: first, key: account,"
                + " max_staleness: P1D}, {source: second, key: account, max_staleness: P1D}],"
                + " on_miss: UNDETERMINED}\n");
    ScriptedEnrichmentSource down = scripted("first").build();
    down.armTimeout();
    ScriptedEnrichmentSource up =
        scripted("second").hit("ACC-1", Map.of("lei", SyntheticIds.CLIENT1_LEI)).build();

    Enriched out =
        enricher(Map.of("first", down, "second", up))
            .enrich(BATCH, List.of(legal("T-1", "ACC-1")), twoSources);

    assertThat(lei(out.records().get(0))).contains(SyntheticIds.CLIENT1_LEI);
    assertThat(out.records().get(0).provenance().get(0).source()).isEqualTo("second");
  }

  @Test
  void aSealedSnapshotThatCannotBeReadFailsWithAFixedTextAndCallsNoSource() {
    String version2 =
        "{\"asOf\":\"2026-10-31T12:00:00Z\",\"batch\":\"b\",\"fields\":[],\"version\":2,"
            + "\"warnings\":[]}";
    String version1 = version2.replace("\"version\":2", "\"version\":1");
    List<String> unreadable =
        List.of(
            "\u0000not json at all SYNT00-secret",
            "[1,2]",
            version2,
            version1.replace("2026-10-31T12:00:00Z", "yesterday SYNT00-secret"),
            version1.replace("\"fields\":[]", "\"fields\":[{\"lookups\":[{\"source\":\"s\"}]}]"));
    for (int i = 0; i < unreadable.size(); i++) {
      BatchId batch = BatchId.of(Sha256.ofUtf8("w1a unreadable " + i), 0);
      writer.put(
          Enricher.snapshotKey(batch),
          unreadable.get(i).getBytes(UTF_8),
          "application/json",
          "enrichment",
          ManifestLedger.ObjectClass.ENRICHMENT_RESPONSE);
      RecordingSource asked =
          new RecordingSource(
              scripted("static-data")
                  .hit("ACC-1", Map.of("lei", SyntheticIds.CLIENT1_LEI))
                  .build());

      assertThatThrownBy(
              () -> enricher(asked).enrich(batch, List.of(legal("T-1", "ACC-1")), demoPlan("P30D")))
          .as("variant %d", i)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("the sealed enrichment snapshot cannot be read")
          .hasNoCause();
      assertThat(asked.requests()).isEmpty();
    }
  }

  // ---- the sealed snapshot ----------------------------------------------------------------

  @Test
  void theSnapshotIsPutBeforeTheMethodReturns() {
    Enriched out =
        enricher(staticData()).enrich(BATCH, List.of(legal("T-002", "ACC-2")), demoPlan("P30D"));

    EvidenceKey key = Enricher.snapshotKey(BATCH);
    EvidenceStore.StoredObject stored = store.get(key).orElseThrow();
    assertThat(stored.mediaType()).isEqualTo("application/json");
    // its id is the SHA-256 of its canonical JSON, which is what is stored
    assertThat(out.snapshotId()).isEqualTo(Sha256.of(stored.bytes()).hex());
    assertThat(new String(stored.bytes(), UTF_8))
        .isEqualTo(Json.canonical(Json.parse(new String(stored.bytes(), UTF_8))));
    // the manifest row and the retention claim are there too
    var row = ledger.manifestRow(key).orElseThrow();
    assertThat(row.entry().objectClass()).isEqualTo(ManifestLedger.ObjectClass.ENRICHMENT_RESPONSE);
    assertThat(row.entry().sha256()).isEqualTo(Sha256.of(stored.bytes()));
    assertThat(claims).hasSize(1);
    assertThat(claims.get(0).artifact()).isEqualTo(key);
    assertThat(claims.get(0).retentionClass()).isEqualTo("enrichment");
  }

  @Test
  void aStoreOutageFailsTheCallAndTheNextCallFetchesAgain() {
    ScriptedEnrichmentSource scripted =
        scripted("static-data").hit("ACC-1", Map.of("lei", SyntheticIds.CLIENT1_LEI)).build();
    Enricher enricher = enricher(scripted);
    store.simulateOutage(true);

    assertThatThrownBy(
            () -> enricher.enrich(BATCH, List.of(legal("T-1", "ACC-1")), demoPlan("P30D")))
        .isInstanceOf(EvidenceUnavailableException.class);

    store.simulateOutage(false);
    Enriched out = enricher.enrich(BATCH, List.of(legal("T-1", "ACC-1")), demoPlan("P30D"));
    assertThat(lei(out.records().get(0))).contains(SyntheticIds.CLIENT1_LEI);
    assertThat(store.get(Enricher.snapshotKey(BATCH))).isPresent();
  }

  @Test
  void aReplayReusesTheSnapshotAndCallsNoSource() {
    ScriptedEnrichmentSource firstRun =
        scripted("static-data")
            .hit("ACC-1", Map.of("lei", SyntheticIds.CLIENT1_LEI))
            .staleHit(
                "ACC-2", Map.of("lei", SyntheticIds.CLIENT2_LEI), NOW.minus(Duration.ofDays(1)))
            .build();
    List<Accepted> batch =
        List.of(
            legal("T-1", "ACC-1"),
            legal("T-2", "ACC-2"),
            legal("T-3", "ACC-9"),
            accepted(
                "T-4", "ACC-1", PartyKind.LEGAL_ENTITY, Optional.of(SyntheticIds.CLIENT2_LEI)));
    EnrichmentPlan plan = demoPlan("P2D");
    Enriched first = enricher(firstRun).enrich(BATCH, batch, plan);
    int objects = store.list("", Optional.empty(), 100).size();
    int manifestRows = ledger.manifestRows("", Optional.empty(), 100).size();
    int retentionRows = ledger.retentionRows("", Optional.empty(), 100).size();
    RetentionClaim sealedClaim = claims.get(0);

    // three days later, in another process, against a source that now answers otherwise
    clock.advance(Duration.ofDays(3));
    ScriptedEnrichmentSource answersOtherwise =
        scripted("static-data").hit("ACC-1", Map.of("lei", SyntheticIds.LAPSED_LEI)).build();
    RecordingSource asked = new RecordingSource(answersOtherwise);
    Enriched replay = enricher(asked).enrich(BATCH, batch, plan);

    assertThat(asked.requests())
        .as("the live source is never called for a sealed snapshot")
        .isEmpty();
    assertThat(answersOtherwise.requestCount()).isZero();
    assertThat(replay.snapshotId()).isEqualTo(first.snapshotId());
    assertThat(replay.records()).isEqualTo(first.records());
    // a hit that was fresh when it was sealed is still the hit the decision cites
    assertThat(lei(replay.records().get(1))).contains(SyntheticIds.CLIENT2_LEI);
    // nothing new: no object, no manifest row, no retention claim, not even another date
    assertThat(store.list("", Optional.empty(), 100)).hasSize(objects);
    assertThat(ledger.manifestRows("", Optional.empty(), 100)).hasSize(manifestRows);
    assertThat(ledger.retentionRows("", Optional.empty(), 100)).hasSize(retentionRows);
    // the sink may be asked again; it is asked for the same claim, with the date of the first write
    assertThat(claims).allSatisfy(claim -> assertThat(claim).isEqualTo(sealedClaim));
  }

  @Test
  void theSnapshotKeyHoldsNoAccountId() {
    Enriched out =
        enricher(staticData())
            .enrich(
                BATCH,
                List.of(legal("T-1", "ACC-1"), legal("T-2", "ACC-2"), legal("T-3", "ACC-3")),
                demoPlan("P30D"));

    assertThat(out.records()).hasSize(3);
    assertThat(Enricher.snapshotKey(BATCH).path()).isEqualTo("enrichment/" + HEX + "/1");
    assertThat(store.list("", Optional.empty(), 100))
        .extracting(EvidenceKey::path)
        .containsExactly("enrichment/" + HEX + "/1");
    for (RetentionClaim claim : claims) {
      assertThat(claim.artifact().path()).doesNotContain("ACC-").doesNotContain("SYNT00");
    }
    for (var row : ledger.manifestRows("", Optional.empty(), 100)) {
      assertThat(row.entry().key().path()).doesNotContain("ACC-").doesNotContain("SYNT00");
    }
  }

  @Test
  void aSnapshotThatOnlyOneCopyHoldsIsCompletedByTheNextCallAndNoSourceIsAsked() {
    TwoCopyEvidenceStore tier = new TwoCopyEvidenceStore(clock);
    EvidenceWriter twoCopyWriter =
        new EvidenceWriter(
            tier,
            ledger,
            claim -> {
              claims.add(claim);
              ledger.recordRetention(List.of(claim));
            },
            RetentionPolicy.fixedDays(1826),
            clock,
            REGIME);
    EvidenceKey key = Enricher.snapshotKey(BATCH);
    List<Accepted> batch = List.of(legal("T-002", "ACC-2"));
    // the first call seals the snapshot on the first copy and then the tier fails: no row, no claim
    tier.halfWriteNextPut(key.path());
    assertThatThrownBy(
            () ->
                new Enricher(clock, twoCopyWriter, Map.of("static-data", staticData()))
                    .enrich(BATCH, batch, demoPlan("P30D")))
        .isInstanceOf(EvidenceUnavailableException.class);
    assertThat(tier.copies(key)).isEqualTo(1);
    assertThat(ledger.manifestRow(key)).isEmpty();
    assertThat(claims).isEmpty();
    EvidenceStore.StoredObject first = tier.firstVolume().get(key).orElseThrow();

    // the next call, at a later instant, finds the snapshot and must complete it before it returns
    clock.advance(Duration.ofHours(3));
    RecordingSource asked = new RecordingSource(staticData());
    Enriched out =
        new Enricher(clock, twoCopyWriter, Map.of("static-data", asked))
            .enrich(BATCH, batch, demoPlan("P30D"));

    assertThat(asked.requests()).as("the sealed snapshot answers, not the source").isEmpty();
    assertThat(out.snapshotId()).isEqualTo(Sha256.of(first.bytes()).hex());
    assertThat(tier.copies(key)).isEqualTo(2);
    EvidenceStore.StoredObject second = tier.secondVolume().get(key).orElseThrow();
    assertThat(second.bytes()).isEqualTo(first.bytes());
    assertThat(second.retention()).isEqualTo(first.retention());
    assertThat(ledger.manifestRow(key).orElseThrow().entry().sha256())
        .isEqualTo(Sha256.of(first.bytes()));
    assertThat(claims).hasSize(1);
    assertThat(claims.getFirst().retainUntil()).contains(first.retention().retainUntil());
  }

  @Test
  void noToStringHoldsAFieldValue() {
    Enriched out =
        enricher(staticData()).enrich(BATCH, List.of(legal("T-002", "ACC-2")), demoPlan("P30D"));
    EnrichedRecord record = out.records().get(0);

    for (String text :
        List.of(
            out.toString(),
            record.toString(),
            record.provenance().get(0).toString(),
            record.provenance().toString())) {
      assertThat(text)
          .doesNotContain("ACC-2")
          .doesNotContain(SyntheticIds.CLIENT2_LEI)
          .doesNotContain(record.provenance().get(0).responseHash().hex());
    }
  }
}
