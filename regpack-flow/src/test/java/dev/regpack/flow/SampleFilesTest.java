package dev.regpack.flow;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.ident.Isin;
import dev.regpack.model.ident.Lei;
import dev.regpack.model.json.Json;
import dev.regpack.testkit.ident.SyntheticIds;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The sample files of the W1a slice (plan T2, the data set and scenarios): every marker states what
 * its file is, the two deliberately broken files are broken in exactly the stated way, and no
 * sample names a real identifier. Everything here is synthetic and NON-PRODUCTION.
 */
class SampleFilesTest {
  private static final String DIR = "/dev/regpack/flow/samples/";

  private static final List<String> COLUMNS =
      List.of(
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

  private static final List<String> TRADE_FILES =
      List.of(
          "trades-day1.csv",
          "trades-day2.csv",
          "trades-day3-badtotals.csv",
          "trades-day4-broken.csv",
          "trades-day5-notsynth.csv");

  private static final List<String> SEED_FILES =
      List.of("static-data.csv", "lei-status.csv", "mic.csv", "isin-mic.csv");

  private static final String MAPPING = "mapping-csv-trades-v1.yaml";

  private static final String DAY3 = "trades-day3-badtotals.csv";
  private static final String DAY4 = "trades-day4-broken.csv";
  private static final String DAY5 = "trades-day5-notsynth.csv";

  private static byte[] bytes(String name) throws IOException {
    try (InputStream in = SampleFilesTest.class.getResourceAsStream(DIR + name)) {
      assertThat(in).as("the sample resource " + name).isNotNull();
      return in.readAllBytes();
    }
  }

  private static String text(String name) throws IOException {
    return new String(bytes(name), UTF_8);
  }

  private static String sha256(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every Java platform");
    }
  }

  /** The marker of a CSV file, parsed (keys, then values; long for numbers, string otherwise). */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> marker(String csv) throws IOException {
    return (Map<String, Object>) Json.parse(text(csv + ".done"));
  }

  private static List<String> lines(String csv) throws IOException {
    String t = text(csv);
    assertThat(t).as(csv + " ends in one line feed").endsWith("\n").doesNotEndWith("\n\n");
    return List.of(t.substring(0, t.length() - 1).split("\n", -1));
  }

  private static List<String> header(String csv) throws IOException {
    return List.of(lines(csv).get(0).split(",", -1));
  }

  /** The data rows as lists of cells; the sample cells hold no comma, quote or line break. */
  private static List<List<String>> rows(String csv) throws IOException {
    List<List<String>> rows = new ArrayList<>();
    for (String line : lines(csv).subList(1, lines(csv).size())) {
      rows.add(List.of(line.split(",", -1)));
    }
    return rows;
  }

  /** The sum of the quantities that parse as a decimal; one that does not parse adds nothing. */
  private static BigDecimal sumOfQuantities(String csv) throws IOException {
    int quantity = header(csv).indexOf("quantity");
    assertThat(quantity).as("quantity column of " + csv).isGreaterThanOrEqualTo(0);
    BigDecimal sum = BigDecimal.ZERO;
    for (List<String> row : rows(csv)) {
      try {
        sum = sum.add(new BigDecimal(row.get(quantity)));
      } catch (NumberFormatException e) {
        // a quantity that does not parse is not in the control total
      }
    }
    return sum;
  }

  @Test
  void everyMarkerMatchesItsFile() throws IOException {
    for (String csv : TRADE_FILES) {
      byte[] data = bytes(csv);
      String markerText = text(csv + ".done");
      Map<String, Object> marker = marker(csv);

      // one line, keys sorted, no whitespace: the canonical form of the parsed marker plus one LF
      assertThat(markerText).as(csv + " marker form").isEqualTo(Json.canonical(marker) + "\n");
      assertThat(marker.get("file")).as(csv + " marker file").isEqualTo(csv);
      assertThat(marker.get("size")).as(csv + " marker size").isEqualTo((long) data.length);
      assertThat(marker.get("sha256")).as(csv + " marker sha256").isEqualTo(sha256(data));
      assertThat(new BigDecimal((String) marker.get("sum_quantity")))
          .as(csv + " marker sum_quantity")
          .isEqualByComparingTo(sumOfQuantities(csv));

      // the two exceptions: the count of day 3 and the data class of day 5 (own tests below)
      if (csv.equals(DAY3)) {
        assertThat(marker.get("records"))
            .as(csv + " marker records")
            .isNotEqualTo((long) rows(csv).size());
      } else {
        assertThat(marker.get("records"))
            .as(csv + " marker records")
            .isEqualTo((long) rows(csv).size());
      }
      if (csv.equals(DAY5)) {
        assertThat(marker).as(csv + " marker keys").doesNotContainKey("data_class");
      } else {
        assertThat(marker.get("data_class")).as(csv + " marker data_class").isEqualTo("SYNTHETIC");
      }
      assertThat(marker.keySet())
          .as(csv + " marker keys")
          .isSubsetOf("data_class", "file", "records", "sha256", "size", "sum_quantity")
          .contains("file", "records", "sha256", "size", "sum_quantity");
    }
    // the day-1 and day-2 totals the plan states
    assertThat(sumOfQuantities("trades-day1.csv")).isEqualByComparingTo("540.5");
    assertThat(rows("trades-day1.csv")).hasSize(8);
    assertThat(sumOfQuantities("trades-day2.csv")).isEqualByComparingTo("217");
    assertThat(rows("trades-day2.csv")).hasSize(4);
    // 540.5 and 540.5000 agree, 540.6 does not (compareTo, not equals)
    assertThat(new BigDecimal("540.5000")).isEqualByComparingTo(new BigDecimal("540.5"));
    assertThat(new BigDecimal("540.6")).isNotEqualByComparingTo(sumOfQuantities("trades-day1.csv"));
  }

  @Test
  void theSampleDirectoryHoldsExactlyTheFiveFilesTheirMarkersAndTheFourSeeds() throws IOException {
    Path dir = Path.of(System.getProperty("regpack.classes.dir") + DIR);
    Set<String> names = new TreeSet<>();
    try (Stream<Path> list = Files.list(dir)) {
      list.forEach(p -> names.add(p.getFileName().toString()));
    }
    Set<String> expected = new TreeSet<>(SEED_FILES);
    for (String csv : TRADE_FILES) {
      expected.add(csv);
      expected.add(csv + ".done");
    }
    // the mapping pack of T6 and any later sample join the set in their own task
    assertThat(names).containsAll(expected);
    assertThat(names.stream().filter(n -> n.startsWith("trades-")))
        .containsExactlyInAnyOrderElementsOf(
            expected.stream().filter(n -> n.startsWith("trades-")).toList());
  }

  @Test
  void everySampleIsPlainLfTextWithoutBom() throws IOException {
    List<String> all = new ArrayList<>(SEED_FILES);
    for (String csv : TRADE_FILES) {
      all.add(csv);
      all.add(csv + ".done");
    }
    for (String name : all) {
      byte[] data = bytes(name);
      assertThat(data.length).as(name).isGreaterThan(0);
      assertThat(data[0] == (byte) 0xEF && data.length > 2 && data[1] == (byte) 0xBB)
          .as(name + " has no byte order mark")
          .isFalse();
      String t = new String(data, UTF_8);
      assertThat(t).as(name + " has no carriage return").doesNotContain("\r");
      assertThat(t.chars().allMatch(c -> c == '\n' || (c >= 0x20 && c <= 0x7E)))
          .as(name + " is printable ASCII and line feeds")
          .isTrue();
    }
  }

  @Test
  void theTradeFilesCarryTheEighteenColumnsAndTheStatedConstants() throws IOException {
    for (String csv : TRADE_FILES) {
      List<String> expectedHeader = COLUMNS;
      if (csv.equals(DAY4)) {
        expectedHeader = COLUMNS.stream().filter(c -> !c.equals("price")).toList();
      }
      assertThat(header(csv)).as(csv + " header").isEqualTo(expectedHeader);
      for (List<String> row : rows(csv)) {
        assertThat(row).as(csv + " row width").hasSameSizeAs(expectedHeader);
        assertThat(row.get(header(csv).indexOf("traded_at")))
            .isEqualTo("2026-10-30T09:30:00.123456Z");
        assertThat(row.get(header(csv).indexOf("counterparty"))).isEqualTo("SYNT00CCP00000000194");
        assertThat(row.get(header(csv).indexOf("exec_within"))).isEqualTo("ALGO-1");
        assertThat(row.get(header(csv).indexOf("event"))).isIn("NEW", "CANCEL", "NOVATION");
      }
    }
  }

  @Test
  void day3MarkerSaysThreeRecordsAndTheFileHasTwo() throws IOException {
    assertThat(marker(DAY3).get("records")).isEqualTo(3L);
    assertThat(rows(DAY3)).hasSize(2);
    // everything else about the file is as its marker says
    assertThat(marker(DAY3).get("size")).isEqualTo((long) bytes(DAY3).length);
    assertThat(marker(DAY3).get("sha256")).isEqualTo(sha256(bytes(DAY3)));
    assertThat(marker(DAY3).get("data_class")).isEqualTo("SYNTHETIC");
  }

  @Test
  void day4LacksThePriceColumn() throws IOException {
    assertThat(header(DAY4)).doesNotContain("price").hasSize(17);
    assertThat(header(DAY4)).containsAll(COLUMNS.stream().filter(c -> !c.equals("price")).toList());
    assertThat(header("trades-day1.csv")).contains("price");
    assertThat(marker(DAY4).get("records")).isEqualTo(2L);
  }

  @Test
  void day5MarkerHasNoDataClass() throws IOException {
    assertThat(marker(DAY5)).doesNotContainKey("data_class");
    assertThat(marker(DAY5).get("records")).isEqualTo((long) rows(DAY5).size());
    assertThat(marker(DAY5).get("sha256")).isEqualTo(sha256(bytes(DAY5)));
  }

  @Test
  void theTriggerValuesOfTheScenariosAreUniquePerRow() throws IOException {
    int count99 = 0;
    int count77 = 0;
    int countAbc = 0;
    for (String csv : TRADE_FILES) {
      List<String> header = header(csv);
      for (List<String> row : rows(csv)) {
        if (header.contains("price") && row.get(header.indexOf("price")).equals("99.5")) {
          count99++;
        }
        if (row.get(header.indexOf("quantity")).equals("77")) {
          count77++;
        }
        if (row.get(header.indexOf("quantity")).equals("abc")) {
          countAbc++;
        }
      }
    }
    // only T-003 version 1 is rejected, only T-010 stays silent, only T-009 does not parse
    assertThat(count99).isEqualTo(1);
    assertThat(count77).isEqualTo(1);
    assertThat(countAbc).isEqualTo(1);
  }

  @Test
  void theThreeLastFilesDifferInBytesAndInBatchIdentity() throws IOException {
    List<String> shas = new ArrayList<>();
    List<String> execIds = new ArrayList<>();
    for (String csv : List.of(DAY3, DAY4, DAY5)) {
      shas.add(sha256(bytes(csv)));
      int exec = header(csv).indexOf("exec_id");
      rows(csv).forEach(row -> execIds.add(row.get(exec)));
    }
    assertThat(shas).doesNotHaveDuplicates();
    assertThat(execIds).containsExactly("EX-011", "EX-012", "EX-013", "EX-014", "EX-015", "EX-016");
  }

  private static final Pattern NOT_ALNUM = Pattern.compile("[^A-Za-z0-9]+");

  /**
   * The LEI- or ISIN-shaped tokens of a text that are not a V3 constant ({@code BAD_ISIN} counts
   * for an ISIN): the shape is the one of {@link Lei#hasShape} and {@link Isin#hasShape}, so a
   * token with a wrong check digit is found too.
   */
  private static List<String> unknownIdentifiers(String text) {
    List<String> found = new ArrayList<>();
    for (String token : NOT_ALNUM.split(text)) {
      if (Lei.hasShape(token) && !SyntheticIds.LEIS.contains(token)) {
        found.add(token);
      } else if (Isin.hasShape(token)
          && !SyntheticIds.ISINS.contains(token)
          && !SyntheticIds.BAD_ISIN.equals(token)) {
        found.add(token);
      }
    }
    return found;
  }

  @Test
  void theSamplesNameNoRealIdentifier() throws IOException {
    List<String> all = new ArrayList<>(SEED_FILES);
    for (String csv : TRADE_FILES) {
      all.add(csv);
      all.add(csv + ".done");
    }
    int shaped = 0;
    for (String name : all) {
      assertThat(unknownIdentifiers(text(name))).as(name).isEmpty();
      for (String token : NOT_ALNUM.split(text(name))) {
        if (Lei.hasShape(token)) {
          shaped++;
          assertThat(Lei.isValid(token)).as(name + " LEI check digits").isTrue();
        } else if (Isin.hasShape(token)) {
          shaped++;
          // the one invalid ISIN of the samples is the scenario's BAD_ISIN (T-004, V06)
          assertThat(Isin.isValid(token))
              .as(name + " ISIN check digit")
              .isEqualTo(!SyntheticIds.BAD_ISIN.equals(token));
        }
      }
    }
    assertThat(shaped).as("the scan sees the synthetic identifiers").isGreaterThan(40);

    // Convention 5 reaches the mapping, the demo pack and the test resources of this module too
    int scanned = 0;
    assertThat(unknownIdentifiers(text(MAPPING))).as(MAPPING).isEmpty();
    scanned++;
    Path root = Path.of(System.getProperty("regpack.repo.root"));
    for (Path dir :
        List.of(
            root.resolve("packs/demo.tr.s1"), root.resolve("regpack-flow/src/test/resources"))) {
      List<Path> files;
      try (Stream<Path> walk = Files.walk(dir)) {
        files = walk.filter(Files::isRegularFile).sorted().toList();
      }
      assertThat(files).as("the files under " + dir.getFileName()).isNotEmpty();
      for (Path file : files) {
        assertThat(unknownIdentifiers(Files.readString(file, UTF_8)))
            .as(root.relativize(file).toString())
            .isEmpty();
        scanned++;
      }
    }
    assertThat(scanned)
        .as("the mapping, the 24 pack files and the test resources")
        .isGreaterThan(25);

    // the scan can fail: the published examples (unit tests only) and a one-character change
    assertThat(unknownIdentifiers("a,US0378331005,b")).containsExactly("US0378331005");
    assertThat(unknownIdentifiers("x|529900T8BM49AURSDO55\n"))
        .containsExactly("529900T8BM49AURSDO55");
    assertThat(unknownIdentifiers("SYNT00CLIENT00000168")).containsExactly("SYNT00CLIENT00000168");
    assertThat(unknownIdentifiers("ZZ0000000016,ZZ0000000017,SYNT00FIRM0000000152")).isEmpty();
    assertThat(unknownIdentifiers("EX-001,ACC-1,2026-10-30T09:30:00.123456Z,NATURAL_PERSON"))
        .isEmpty();
  }
}
