package dev.regpack.flow.enrich;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.flow.ingest.CsvException;
import dev.regpack.model.BatchId;
import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import dev.regpack.ports.connector.ConnectorKind;
import dev.regpack.ports.connector.EnrichmentSource;
import dev.regpack.ports.connector.EnrichmentSource.Context;
import dev.regpack.ports.connector.EnrichmentSource.KeyOutcome;
import dev.regpack.ports.connector.EnrichmentSource.Request;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What {@link StaticDataSource} does beyond the shared lookup contract: the file format, the as-of
 * rule, the canonical bytes and the refusals.
 */
class StaticDataSourceTest {
  private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
  private static final EnrichmentSource.Limits LIMITS =
      new EnrichmentSource.Limits(5, Duration.ofSeconds(2), 0);
  private static final Context CONTEXT =
      new Context("client.lei", NOW, BatchId.of(Sha256.ofUtf8("batch"), 0));

  private final ManualClock clock = new ManualClock(NOW);

  private StaticDataSource source(String csv) {
    return new StaticDataSource(csv, clock, LIMITS);
  }

  private static List<KeyOutcome> lookup(EnrichmentSource source, String... keys) {
    return source.lookup(new Request(List.of(keys), CONTEXT)).outcomes();
  }

  @Test
  void aHitCarriesTheCanonicalLeiAndTheContextAsOfWhenTheRowGivesNone() {
    StaticDataSource source = source("key,lei\nACC-1,LEI-ONE\nACC-2,LEI-TWO\n");

    KeyOutcome hit = lookup(source, "ACC-2").get(0);

    byte[] canonical = Json.canonical(Map.of("lei", "LEI-TWO")).getBytes(UTF_8);
    assertThat(hit).isInstanceOf(KeyOutcome.Hit.class);
    assertThat(hit.canonical()).isEqualTo(canonical);
    assertThat(hit.responseHash()).isEqualTo(Sha256.of(canonical));
    assertThat(hit.asOf()).isEqualTo(NOW);
  }

  @Test
  void aRowAsOfWinsOverTheContextAndAnEmptyCellDoesNot() {
    Instant older = NOW.minus(Duration.ofDays(2));
    StaticDataSource source =
        source("key,lei,as_of\nACC-1,LEI-ONE," + older + "\nACC-2,LEI-TWO,\n");

    List<KeyOutcome> outcomes = lookup(source, "ACC-1", "ACC-2");

    assertThat(outcomes.get(0).asOf()).isEqualTo(older);
    assertThat(outcomes.get(1).asOf()).isEqualTo(NOW);
  }

  @Test
  void aKeyWithoutARowIsAMissWithTheCanonicalMissBytes() {
    KeyOutcome miss = lookup(source("key,lei\nACC-1,LEI-ONE\n"), "ACC-9").get(0);

    byte[] canonical = Json.canonical(Map.of("key", "ACC-9", "outcome", "MISS")).getBytes(UTF_8);
    assertThat(miss).isInstanceOf(KeyOutcome.Miss.class);
    assertThat(miss.canonical()).isEqualTo(canonical);
    assertThat(miss.responseHash()).isEqualTo(Sha256.of(canonical));
    assertThat(miss.asOf()).isEqualTo(NOW);
  }

  @Test
  void anAnswerOutlivesNothingTheCallerCanChange() {
    StaticDataSource source = source("key,lei\nACC-1,LEI-ONE\n");
    KeyOutcome hit = lookup(source, "ACC-1").get(0);
    hit.canonical()[0] = 'X';

    assertThat(lookup(source, "ACC-1").get(0).canonical())
        .isEqualTo(Json.canonical(Map.of("lei", "LEI-ONE")).getBytes(UTF_8));
  }

  @Test
  void declaresTheLookupKindAndItsLimitsAndChecksOut() {
    StaticDataSource source = source("key,lei\n");

    assertThat(source.spec().kind()).isEqualTo(ConnectorKind.LOOKUP);
    assertThat(source.spec().name()).isEqualTo("static-data");
    assertThat(source.limits()).isEqualTo(LIMITS);
    assertThat(source.check().ok()).isTrue();
  }

  @Test
  void refusesARequestOverTheLimitAndDuplicateKeysWithoutEchoingAKey() {
    StaticDataSource source = source("key,lei\nACC-1,LEI-ONE\n");

    assertThatThrownBy(() -> lookup(source, "A", "B", "C", "D", "E", "F"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> lookup(source, "ACC-SECRET", "ACC-SECRET"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("ACC-SECRET");
  }

  @Test
  void refusesAFileThatIsNotAStaticDataFileNamingNoValue() {
    // the header is key,lei with an optional as_of, in that order
    for (String csv :
        List.of(
            "lei,key\nLEI-ONE,ACC-1\n",
            "key\nACC-1\n",
            "key,lei,as_of,extra\nACC-1,LEI-ONE,,x\n",
            "key,lei,asof\nACC-1,LEI-ONE,\n")) {
      assertThatThrownBy(() -> source(csv))
          .as("file %s", csv)
          .isInstanceOf(IllegalArgumentException.class);
    }
    // a duplicate key, a blank key or lei, a bad or too precise as_of
    for (String row :
        List.of(
            "ACC-SECRET,LEI-ONE,\nACC-SECRET,LEI-TWO,",
            ",LEI-ONE,",
            "ACC-SECRET,,",
            "ACC-SECRET,LEI-ONE,yesterday",
            "ACC-SECRET,LEI-ONE,2026-10-31T12:00:00.1234567Z")) {
      assertThatThrownBy(() -> source("key,lei,as_of\n" + row + "\n"))
          .as("row %s", row)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageNotContaining("ACC-SECRET")
          .hasMessageNotContaining("LEI-ONE");
    }
    // a CSV that is no CSV is the reader's refusal
    assertThatThrownBy(() -> source("")).isInstanceOf(CsvException.class);
  }

  @Test
  void aSourceFileOfTheSliceLoads() throws Exception {
    byte[] bytes =
        StaticDataSourceTest.class
            .getResourceAsStream("/dev/regpack/flow/samples/static-data.csv")
            .readAllBytes();

    StaticDataSource source = source(new String(bytes, UTF_8));

    assertThat(lookup(source, "ACC-1", "ACC-2", "ACC-3", "ACC-9"))
        .extracting(o -> o.getClass().getSimpleName())
        .containsExactly("Hit", "Hit", "Hit", "Miss");
  }
}
