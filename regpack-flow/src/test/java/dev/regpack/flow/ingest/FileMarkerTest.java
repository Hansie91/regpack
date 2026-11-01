package dev.regpack.flow.ingest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.Sha256;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FileMarkerTest {
  private static final String SHA =
      "1073c60eac2be3d69787d844c02035d06083628ceafde2562754968cde6f0a8a";

  private static String marker(String file, String sha, String extra) {
    return "{\"data_class\":\"SYNTHETIC\",\"file\":\""
        + file
        + "\",\"records\":8,\"sha256\":\""
        + sha
        + "\",\"size\":1399,\"sum_quantity\":\"540.5\""
        + extra
        + "}";
  }

  private static Optional<FileMarker> parse(String text, String csvName) {
    return FileMarker.parse(text.getBytes(UTF_8), csvName);
  }

  private static byte[] resource(String name) throws IOException {
    try (InputStream in =
        FileMarkerTest.class.getResourceAsStream("/dev/regpack/flow/samples/" + name)) {
      return in.readAllBytes();
    }
  }

  @Test
  void aValidMarkerIsReadAndCarriesItsControlTotals() {
    FileMarker m = parse(marker("trades-day1.csv", SHA, ""), "trades-day1.csv").orElseThrow();
    assertThat(m.file()).isEqualTo("trades-day1.csv");
    assertThat(m.records()).isEqualTo(8);
    assertThat(m.size()).isEqualTo(1399L);
    assertThat(m.sha256()).isEqualTo(new Sha256(SHA));
    assertThat(m.dataClass()).contains("SYNTHETIC");
    assertThat(m.sumQuantity()).contains("540.5");
    assertThat(m.controlTotals())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("data_class", "SYNTHETIC", "sum_quantity", "540.5"));
  }

  @Test
  void theCommittedSampleMarkersAreValid() throws IOException {
    for (String csv :
        new String[] {
          "trades-day1.csv",
          "trades-day2.csv",
          "trades-day3-badtotals.csv",
          "trades-day4-broken.csv",
          "trades-day5-notsynth.csv"
        }) {
      FileMarker m = FileMarker.parse(resource(csv + ".done"), csv).orElseThrow();
      assertThat(m.sha256()).isEqualTo(Sha256.of(resource(csv)));
      assertThat(m.size()).isEqualTo(resource(csv).length);
    }
    // the day-5 marker has no data class: absent, not a default
    FileMarker day5 =
        FileMarker.parse(resource("trades-day5-notsynth.csv.done"), "trades-day5-notsynth.csv")
            .orElseThrow();
    assertThat(day5.dataClass()).isEmpty();
    assertThat(day5.controlTotals()).doesNotContainKey("data_class");
  }

  @Test
  void aMarkerNamingAnotherFileOrAPathIsInvalid() {
    assertThat(parse(marker("other.csv", SHA, ""), "trades-day1.csv")).isEmpty();
    assertThat(parse(marker("TRADES-DAY1.CSV", SHA, ""), "trades-day1.csv")).isEmpty();
    for (String path :
        new String[] {
          "dir/trades-day1.csv",
          "../trades-day1.csv",
          "..\\\\trades-day1.csv",
          "/trades-day1.csv",
          "C:\\\\trades-day1.csv",
          "trades-day1.csv/",
          "./trades-day1.csv",
          "trades-day1.csv "
        }) {
      assertThat(parse(marker(path, SHA, ""), "trades-day1.csv")).as(path).isEmpty();
      // even when the file itself is named like the path
      assertThat(parse(marker(path, SHA, ""), path)).as(path + " as a name").isEmpty();
    }
  }

  @Test
  void anUppercaseOrShortShaIsInvalid() {
    assertThat(parse(marker("a.csv", SHA.toUpperCase(java.util.Locale.ROOT), ""), "a.csv"))
        .isEmpty();
    assertThat(parse(marker("a.csv", SHA.substring(1), ""), "a.csv")).isEmpty();
    assertThat(parse(marker("a.csv", SHA + "0", ""), "a.csv")).isEmpty();
    assertThat(parse(marker("a.csv", "g" + SHA.substring(1), ""), "a.csv")).isEmpty();
    assertThat(parse(marker("a.csv", SHA, ""), "a.csv")).isPresent();
  }

  @Test
  void unknownDuplicateAndMissingKeysAreInvalid() {
    assertThat(parse(marker("a.csv", SHA, ",\"note\":\"x\""), "a.csv")).isEmpty();
    assertThat(parse(marker("a.csv", SHA, ",\"size\":1399"), "a.csv")).isEmpty();
    for (String key : new String[] {"file", "records", "sha256", "size"}) {
      String json = marker("a.csv", SHA, "");
      String without =
          switch (key) {
            case "file" -> json.replace("\"file\":\"a.csv\",", "");
            case "records" -> json.replace("\"records\":8,", "");
            case "sha256" -> json.replace("\"sha256\":\"" + SHA + "\",", "");
            default -> json.replace("\"size\":1399,", "");
          };
      assertThat(without).isNotEqualTo(json);
      assertThat(parse(without, "a.csv")).as("without " + key).isEmpty();
    }
  }

  @Test
  void theNumbersAndTheTotalMustBeWellFormed() {
    String ok = marker("a.csv", SHA, "");
    assertThat(parse(ok.replace("\"records\":8", "\"records\":-1"), "a.csv")).isEmpty();
    assertThat(parse(ok.replace("\"records\":8", "\"records\":8.5"), "a.csv")).isEmpty();
    assertThat(parse(ok.replace("\"records\":8", "\"records\":\"8\""), "a.csv")).isEmpty();
    assertThat(parse(ok.replace("\"records\":8", "\"records\":2147483648"), "a.csv")).isEmpty();
    assertThat(parse(ok.replace("\"size\":1399", "\"size\":-5"), "a.csv")).isEmpty();
    assertThat(parse(ok.replace("\"size\":1399", "\"size\":1e3"), "a.csv")).isEmpty();
    assertThat(parse(ok.replace("\"540.5\"", "540.5"), "a.csv")).isEmpty();
    assertThat(parse(ok.replace("\"540.5\"", "\"5e2\""), "a.csv")).isEmpty();
    assertThat(parse(ok.replace("\"540.5\"", "\"\""), "a.csv")).isEmpty();
    assertThat(parse(ok.replace("\"540.5\"", "\"1\\n2\""), "a.csv")).isEmpty();
    assertThat(parse(ok.replace("\"540.5\"", "\"-0.25\""), "a.csv")).isPresent();
  }

  @Test
  void aDataClassThatIsNotAStringStaysVisibleAsSomethingElseThanSynthetic() {
    String ok = marker("a.csv", SHA, "");
    FileMarker number = parse(ok.replace("\"SYNTHETIC\"", "5"), "a.csv").orElseThrow();
    assertThat(number.dataClass()).contains("5");
    FileMarker bool = parse(ok.replace("\"SYNTHETIC\"", "true"), "a.csv").orElseThrow();
    assertThat(bool.dataClass()).contains("true");
    FileMarker lower = parse(ok.replace("SYNTHETIC", "synthetic"), "a.csv").orElseThrow();
    assertThat(lower.dataClass()).contains("synthetic");
    // a string cannot be mistaken for a non-string: the quoted form is not the plain word
    FileMarker quoted =
        parse(ok.replace("\"SYNTHETIC\"", "\"\\\"SYNTHETIC\\\"\""), "a.csv").orElseThrow();
    assertThat(quoted.dataClass()).isPresent();
    assertThat(quoted.dataClass().orElseThrow()).isNotEqualTo("SYNTHETIC");
    assertThat(number.controlTotals().get("data_class")).isNotEqualTo("SYNTHETIC");
  }

  @Test
  void garbageIsNoMarkerAndNeverAnException() {
    for (String text :
        new String[] {
          "",
          "   ",
          "[]",
          "\"x\"",
          "{",
          "{\"file\":null}",
          "{} trailing",
          "not json at all",
          "{\"a\":{\"b\":{\"c\":{}}}}"
        }) {
      assertThat(parse(text, "a.csv")).as(text).isEmpty();
    }
    assertThat(FileMarker.parse(new byte[] {'{', (byte) 0xFF, '}'}, "a.csv")).isEmpty();
    assertThat(FileMarker.parse(new byte[0], "a.csv")).isEmpty();
  }

  /** The valid marker of {@code a.csv} followed by JSON whitespace up to {@code total} bytes. */
  private static String padded(int total) {
    String json = marker("a.csv", SHA, "");
    assertThat(json.getBytes(UTF_8).length).isLessThan(total);
    return json + " ".repeat(total - json.getBytes(UTF_8).length);
  }

  @Test
  void aValidMarkerOfExactly4096BytesIsReadAndOneOf4097BytesIsNot() {
    // only blanks differ: the size is the one possible reason
    assertThat(padded(4096).getBytes(UTF_8)).hasSize(4096);
    assertThat(parse(padded(4096), "a.csv")).isPresent();
    assertThat(parse(padded(4097), "a.csv")).isEmpty();
    assertThat(parse(padded(5_000), "a.csv")).isEmpty();
    assertThat(parse(padded(1_000_000), "a.csv")).isEmpty();
    assertThat(parse(padded(200), "a.csv")).isPresent();
    assertThat(FileMarker.MAX_BYTES).isEqualTo(4096);
  }

  @Test
  void aNameIsValidUpToTheLimitAndOnlyWithTheExactLowerCaseCsvExtension() {
    String longest = "a".repeat(124) + ".csv";
    String tooLong = "a".repeat(125) + ".csv";
    assertThat(longest).hasSize(128);
    assertThat(tooLong).hasSize(129);
    assertThat(parse(marker(longest, SHA, ""), longest)).isPresent();
    assertThat(parse(marker(tooLong, SHA, ""), tooLong)).isEmpty();
    assertThat(FileMarker.validName(longest)).isTrue();
    assertThat(FileMarker.validName(tooLong)).isFalse();
    assertThat(FileMarker.validName("a.csv")).isTrue();
    for (String name :
        new String[] {
          "TRADES.CSV", "Trades.Csv", "trades.cSv", "a.csv.txt", "a.txt", "trades", "csv", ".csv"
        }) {
      assertThat(FileMarker.validName(name)).as(name).isFalse();
      assertThat(parse(marker(name, SHA, ""), name)).as(name).isEmpty();
    }
  }

  @Test
  void aTrailingNewlineIsAllowed() {
    assertThat(parse(marker("a.csv", SHA, "") + "\n", "a.csv")).isPresent();
  }
}
