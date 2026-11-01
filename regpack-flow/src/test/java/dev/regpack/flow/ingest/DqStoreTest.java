package dev.regpack.flow.ingest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.flow.evidence.EvidenceWriter;
import dev.regpack.flow.ingest.Dq.Kind;
import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.model.Sha256;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DqStoreTest {
  private static final String SCOPE = Sha256.ofUtf8("a batch").hex();

  private ManualClock clock;
  private InMemoryEvidenceStore evidence;
  private InMemoryLedgerStore ledger;
  private DqStore store;

  @BeforeEach
  void setUp() {
    clock = new ManualClock(Instant.parse("2026-10-31T12:00:00Z"));
    evidence = new InMemoryEvidenceStore(clock);
    ledger = new InMemoryLedgerStore(clock);
    EvidenceWriter writer =
        new EvidenceWriter(
            evidence,
            ledger,
            claim -> ledger.recordRetention(List.of(claim)),
            IngestFixture.POLICY,
            clock,
            IngestFixture.REGIME);
    store = new DqStore(writer);
  }

  private static Dq record(int line, String column, String code) {
    return new Dq(Kind.RECORD_QUARANTINED, SCOPE, line, column, code, "", "INGEST", "BLOCKER");
  }

  private List<String> keys() {
    return evidence.list("", Optional.empty(), 1000).stream().map(EvidenceKey::path).toList();
  }

  @Test
  void everyKeyIsDeterministicAndAReplayWritesNothingNew() {
    Dq quarantined = record(7, "quantity", "DECIMAL");
    Dq summary =
        new Dq(
            Kind.BATCH_SUMMARY,
            SCOPE,
            0,
            "",
            "UNPROVEN_COMPLETENESS",
            "control_total_records=8;ingested=8;quarantined=0",
            "INGEST",
            "INFO");
    Dq blocker =
        new Dq(Kind.VALIDATION_BLOCKER, SCOPE, 3, "", "PRICE_RANGE", "V02", "L1", "BLOCKER");
    Dq delivery =
        new Dq(Kind.L6_FAILED, "d0000000012", 0, "", "SCHEMA_GATE", "", "OUTPUT", "BLOCKER");

    store.write(quarantined);
    store.write(summary);
    store.write(blocker);
    store.write(delivery);

    // the rule id names a blocker, the code every other kind; no ordinal, counter or time
    assertThat(keys())
        .containsExactly(
            "dq/" + SCOPE + "/BATCH_SUMMARY-0-UNPROVEN_COMPLETENESS",
            "dq/" + SCOPE + "/RECORD_QUARANTINED-7-DECIMAL",
            "dq/" + SCOPE + "/VALIDATION_BLOCKER-3-V02",
            "dq/d0000000012/L6_FAILED-0-SCHEMA_GATE");
    assertThat(quarantined.key().path()).isEqualTo("dq/" + SCOPE + "/RECORD_QUARANTINED-7-DECIMAL");
    assertThat(store.list()).hasSize(4);
    assertThat(store.list().stream().map(d -> d.key().path()).toList()).isSorted();

    // a replay, even at a later instant: the same key and bytes, nothing new anywhere
    List<String> before = keys();
    int manifest = ledger.manifestRows("", Optional.empty(), 1000).size();
    int retention = ledger.retentionRows("", Optional.empty(), 1000).size();
    byte[] bytes = evidence.get(quarantined.key()).orElseThrow().bytes();
    clock.advance(Duration.ofHours(3));
    store.write(quarantined);
    store.write(summary);
    store.write(blocker);
    store.write(delivery);
    assertThat(keys()).isEqualTo(before);
    assertThat(ledger.manifestRows("", Optional.empty(), 1000)).hasSize(manifest);
    assertThat(ledger.retentionRows("", Optional.empty(), 1000)).hasSize(retention);
    assertThat(evidence.get(quarantined.key()).orElseThrow().bytes()).isEqualTo(bytes);
    assertThat(store.list()).hasSize(4);
  }

  @Test
  void theBytesAreCanonicalJsonOfTheFixedFieldsOnly() {
    Dq item = record(7, "quantity", "DECIMAL");
    store.write(item);
    String json = new String(evidence.get(item.key()).orElseThrow().bytes(), UTF_8);
    assertThat(json)
        .isEqualTo(
            "{\"code\":\"DECIMAL\",\"column\":\"quantity\",\"kind\":\"RECORD_QUARANTINED\","
                + "\"layer\":\"INGEST\",\"line\":7,\"rule\":\"\",\"scope\":\""
                + SCOPE
                + "\",\"severity\":\"BLOCKER\"}");
    assertThat(evidence.get(item.key()).orElseThrow().mediaType()).isEqualTo("application/json");
  }

  @Test
  void aSecondItemOnTheKeyOfAFirstIsRefusedAndTheFirstStays() {
    Dq first = record(7, "quantity", "DECIMAL");
    store.write(first);
    Dq other = record(7, "price", "DECIMAL");

    assertThatThrownBy(() -> store.write(other))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("price")
        .hasMessageNotContaining("quantity");
    assertThat(store.list()).containsExactly(first);
  }

  @Test
  void anItemRefusesAnythingThatCouldHoldAValue() {
    assertThatThrownBy(() -> record(1, "a column with spaces", "X"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> record(1, "quantity", "has space"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> record(-1, "quantity", "X"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Dq(Kind.BATCH_REJECTED, "not-a-scope", 0, "", "X", "", "INGEST", "BLOCKER"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Dq(Kind.BATCH_REJECTED, SCOPE, 0, "", "X", "a value 4711", "INGEST", "BLOCKER"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("4711");
    // a blocker is keyed by its rule id, which must therefore be a key segment
    assertThatThrownBy(
            () -> new Dq(Kind.VALIDATION_BLOCKER, SCOPE, 1, "", "X", "a;b", "L1", "BLOCKER"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Dq(Kind.VALIDATION_BLOCKER, SCOPE, 1, "", "X", "", "L1", "BLOCKER"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theNineteenKindsOfTheDecisionD_W3Exist() {
    assertThat(Kind.values())
        .extracting(Kind::name)
        .containsExactly(
            "BATCH_REJECTED",
            "BATCH_QUARANTINED",
            "BATCH_SUMMARY",
            "COMPLETION_PENDING",
            "RECORD_QUARANTINED",
            "FILE_REDELIVERED",
            "STALE_VERSION",
            "SOURCE_CONFLICT",
            "VERSION_CONFLICT",
            "UNDETERMINED_DECISION",
            "VALIDATION_BLOCKER",
            "WITHDRAWAL_REQUIRED",
            "L6_FAILED",
            "UNMATCHED_FEEDBACK",
            "FEEDBACK_ANOMALY",
            "FEEDBACK_CONFLICT",
            "SEND_ANOMALY",
            "DECIDE_FAILED",
            "VALIDATOR_DEFECT");
  }
}
