package dev.regpack.flow.ingest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.engine.pack.CanonicalBindings;
import dev.regpack.flow.ingest.MappingPack.MappingException;
import dev.regpack.model.Sha256;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class MappingPackTest {
  static final String SAMPLE = "/dev/regpack/flow/samples/mapping-csv-trades-v1.yaml";

  static String sampleText() throws IOException {
    try (InputStream in = MappingPackTest.class.getResourceAsStream(SAMPLE)) {
      assertThat(in).as("the sample mapping").isNotNull();
      return new String(in.readAllBytes(), UTF_8);
    }
  }

  static MappingPack sample() throws IOException {
    return MappingPack.parse(sampleText());
  }

  private static MappingException refused(String yaml) {
    try {
      MappingPack.parse(yaml);
    } catch (MappingException e) {
      return e;
    }
    throw new AssertionError("the mapping was accepted");
  }

  /** The sample with one line replaced. */
  private static String with(String from, String to) throws IOException {
    String text = sampleText();
    assertThat(text).as("the sample holds the text to replace").contains(from);
    return text.replace(from, to);
  }

  @Test
  void theSampleIsReadAndNamesItsColumns() throws IOException {
    MappingPack pack = sample();
    assertThat(pack.id()).isEqualTo("csv-trades-v1");
    assertThat(pack.sourceRecordIdColumn()).isEqualTo("record_id");
    assertThat(pack.sourceVersionColumn()).isEqualTo("version");
    assertThat(pack.controlTotals().recordsKey()).isEqualTo("records");
    assertThat(pack.controlTotals().sumColumn()).isEqualTo("quantity");
    assertThat(pack.controlTotals().sumKey()).isEqualTo("sum_quantity");
    assertThat(pack.requiredColumns())
        .containsExactlyInAnyOrder(
            "record_id",
            "version",
            "event",
            "exec_id",
            "venue_mic",
            "traded_at",
            "isin",
            "on_venue",
            "account",
            "client_lei",
            "client_type",
            "side",
            "capacity",
            "quantity",
            "price",
            "currency",
            "counterparty",
            "exec_within");
  }

  @Test
  void theDigestIsTheSha256OfTheCanonicalTreeAndTheVersionNamesIt() throws IOException {
    MappingPack pack = sample();
    assertThat(pack.digest()).isEqualTo(Sha256.ofUtf8(pack.canonicalJson()));
    assertThat(pack.version()).isEqualTo("csv-trades-v1@" + pack.digest().hex());
    // sorted keys, no whitespace
    assertThat(pack.canonicalJson())
        .startsWith("{\"absence\":{\"empty_cell\":\"absent\"},\"columns\":[{")
        .contains("\"mapping\":{\"id\":\"csv-trades-v1\"}")
        .doesNotContain(" ");
  }

  @Test
  void theDigestIgnoresCommentsAndKeyOrderButNotContent() throws IOException {
    String text = sampleText();
    String reordered =
        "# a comment\n"
            + text.lines()
                .filter(l -> l.startsWith("format:"))
                .findFirst()
                .orElseThrow()
                .concat("  # trailing comment\n")
            + text.lines().filter(l -> !l.startsWith("format:")).reduce("", (a, b) -> a + b + "\n");
    assertThat(reordered).isNotEqualTo(text);
    assertThat(MappingPack.parse(reordered).digest()).isEqualTo(sample().digest());
    // inside a column too
    String flipped =
        text.replace("{column: venue_mic, to: venue.mic}", "{to: venue.mic, column: venue_mic}");
    assertThat(MappingPack.parse(flipped).digest()).isEqualTo(sample().digest());
    // a content change changes it, and a change of the order of the columns is a change of content
    assertThat(MappingPack.parse(with("exec_id", "execution_id")).digest())
        .isNotEqualTo(sample().digest());
  }

  @Test
  void aMappingWithoutASourceVersionIsRefused() throws IOException {
    String yaml = with("source_version: version\n", "");
    assertThat(refused(yaml).code()).isEqualTo("MAPPING_KEY_MISSING");
    // an ordering is not built in this slice: it is an unknown key, not a substitute
    assertThat(refused(with("source_version: version\n", "ordering: version\n")).code())
        .isIn("MAPPING_KEY_UNKNOWN", "MAPPING_KEY_MISSING");
  }

  @Test
  void anEventIdentityOtherThanTheModelTupleIsRefused() throws IOException {
    assertThat(
            refused(
                    with(
                        "event_identity: [firm.lei, venue.mic, venue.executionId]",
                        "event_identity: [firm.lei, venue.executionId]"))
                .code())
        .isEqualTo("MAPPING_EVENT_IDENTITY");
    assertThat(
            refused(
                    with(
                        "event_identity: [firm.lei, venue.mic, venue.executionId]",
                        "event_identity: [venue.mic, firm.lei, venue.executionId]"))
                .code())
        .isEqualTo("MAPPING_EVENT_IDENTITY");
    assertThat(
            refused(
                    with(
                        "event_identity: [firm.lei, venue.mic, venue.executionId]",
                        "event_identity: [firm.lei, venue.mic, venue.executionId, instrument.isin]"))
                .code())
        .isEqualTo("MAPPING_EVENT_IDENTITY");
  }

  @Test
  void unknownKeysAndDuplicateKeysAreRefused() throws IOException {
    assertThat(refused(sampleText() + "extra: 1\n").code()).isEqualTo("MAPPING_KEY_UNKNOWN");
    assertThat(
            refused(
                    with(
                        "{column: venue_mic, to: venue.mic}",
                        "{column: venue_mic, to: venue.mic, x: 1}"))
                .code())
        .isEqualTo("MAPPING_KEY_UNKNOWN");
    assertThat(refused(with("format: csv\n", "format: csv\nformat: csv\n")).code())
        .isEqualTo("MAPPING_SYNTAX");
    assertThat(refused("not: [a, mapping").code()).isEqualTo("MAPPING_SYNTAX");
  }

  @Test
  void aTargetThatIsRequiredMustBeMappedAndAnUnknownTargetIsRefused() throws IOException {
    assertThat(refused(with("  - {column: venue_mic, to: venue.mic}\n", "")).code())
        .isEqualTo("MAPPING_TARGET_MISSING");
    assertThat(refused(with("to: venue.mic}", "to: venue.nowhere}")).code())
        .isEqualTo("MAPPING_TARGET_UNKNOWN");
    assertThat(refused(with("to: attribute.account}", "to: venue.mic}")).code())
        .isEqualTo("MAPPING_TARGET_DUPLICATE");
    // an optional target may be left out
    MappingPack pack =
        MappingPack.parse(with("  - {column: counterparty, to: trade.counterparty}\n", ""));
    assertThat(pack.requiredColumns()).doesNotContain("counterparty");
  }

  @Test
  void theOptionalTargetsAreExactlyThoseTheEngineBindsAsAbsent() throws IOException {
    // every path of the engine's bindings is a mappable target, and the five optional ones agree
    assertThat(MappingPack.TARGETS)
        .containsExactlyInAnyOrderElementsOf(CanonicalBindings.VARIABLES.keySet());
    assertThat(MappingPack.OPTIONAL_TARGETS)
        .containsExactlyInAnyOrder(
            "client.lei",
            "trade.side",
            "trade.capacity",
            "trade.counterparty",
            "trade.executionWithin");
  }

  @Test
  void castsAndEnumerationsMustFitTheirTarget() throws IOException {
    assertThat(
            refused(with("to: trade.quantity, cast: DECIMAL", "to: trade.quantity, cast: BOOLEAN"))
                .code())
        .isEqualTo("MAPPING_CAST");
    assertThat(refused(with("to: trade.quantity, cast: DECIMAL", "to: trade.quantity")).code())
        .isEqualTo("MAPPING_CAST");
    assertThat(refused(with("enum: [BUY, SELL]", "enum: [BUY, SELL, SHORT]")).code())
        .isEqualTo("MAPPING_ENUM");
    assertThat(refused(with("{constant: EQUITY,", "{constant: EQUITIES,")).code())
        .isEqualTo("MAPPING_CONSTANT");
    assertThat(refused(with("{constant: EQUITY,", "{constant: \"\",")).code())
        .isEqualTo("MAPPING_CONSTANT");
    assertThat(refused(with("cast: INSTANT_UTC", "cast: INSTANT")).code())
        .isEqualTo("MAPPING_CAST");
  }

  @Test
  void aColumnNeedsExactlyOneSource() throws IOException {
    assertThat(
            refused(
                    with(
                        "{column: venue_mic, to: venue.mic}",
                        "{column: venue_mic, constant: XPAR, to: venue.mic}"))
                .code())
        .isEqualTo("MAPPING_COLUMN_SOURCE");
    assertThat(refused(with("{column: venue_mic, to: venue.mic}", "{to: venue.mic}")).code())
        .isEqualTo("MAPPING_COLUMN_SOURCE");
  }

  @Test
  void theFixedDeclarationsAreRefusedWhenTheyDiffer() throws IOException {
    assertThat(refused(with("expected_population: none", "expected_population: count")).code())
        .isEqualTo("MAPPING_VALUE");
    assertThat(refused(with("format: csv", "format: json")).code()).isEqualTo("MAPPING_VALUE");
    assertThat(refused(with("{empty_cell: absent}", "{empty_cell: zero}")).code())
        .isEqualTo("MAPPING_VALUE");
    assertThat(
            refused(
                    with(
                        "{zone: UTC, format: iso-instant-micros}",
                        "{zone: CET, format: iso-instant-micros}"))
                .code())
        .isEqualTo("MAPPING_VALUE");
    assertThat(refused(with("sum_column: quantity", "sum_column: currency")).code())
        .isEqualTo("MAPPING_CONTROL_TOTALS");
    assertThat(refused(with("sum_column: quantity", "sum_column: nowhere")).code())
        .isEqualTo("MAPPING_CONTROL_TOTALS");
  }

  @Test
  void aMissingTopLevelKeyIsRefusedWithoutEchoingText() {
    MappingException e = refused("mapping: {id: SECRET-TEXT}\n");
    assertThat(e.code()).isEqualTo("MAPPING_KEY_MISSING");
    assertThat(e.getMessage()).doesNotContain("SECRET");
    assertThat(refused("- a\n- b\n").code()).isEqualTo("MAPPING_SHAPE");
    assertThat(List.of(e.line())).allSatisfy(l -> assertThat(l).isGreaterThanOrEqualTo(0));
    assertThatThrownBy(() -> MappingPack.parse("")).isInstanceOf(MappingException.class);
  }
}
