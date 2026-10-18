package dev.regpack.pack.model;

import static dev.regpack.pack.PackFixtures.PACK;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.pack.PackFixtures;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.check.TableChecks;
import dev.regpack.pack.csv.TableCsv;
import dev.regpack.pack.source.Checksums;
import dev.regpack.pack.source.PackSource;
import dev.regpack.pack.yaml.StrictYaml;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Hostile input must not exhaust memory with problems (final review PACKMOD-2, TEETH-7): a file
 * reports at most 100 problems and a load at most 1,000, each followed by one {@code
 * PROBLEMS_TRUNCATED} marker. The assertions are counts and codes, never time or memory.
 */
class ProblemCapTest {
  private static final String MARKER = "PROBLEMS_TRUNCATED";
  private static final int BAD_ROWS = 49_000;

  private static final String TABLE_HEAD =
      "table: {id: x.scope, hit_policy: FIRST, priority: explicit, inputs: [event.kind],"
          + " catch_all: X-DEFAULT}\nrows:\n";

  /** A table file of {@code rows} rows that each lack six required keys. */
  private static String badTable(int rows) {
    StringBuilder text = new StringBuilder(TABLE_HEAD.length() + rows * 13);
    text.append(TABLE_HEAD);
    for (int i = 0; i < rows; i++) {
      text.append("  - {id: R1}\n");
    }
    return text.toString();
  }

  private static List<String> codes(List<PackProblem> problems) {
    return problems.stream().map(PackProblem::code).toList();
  }

  private static PackFixtures packWithBadTables(int tables) {
    PackFixtures fixture = PackFixtures.valid();
    StringBuilder list = new StringBuilder("scope_tables: [scope/a.yaml, scope/b.yaml");
    for (int i = 0; i < tables; i++) {
      list.append(", scope/x").append(i).append(".yaml");
      fixture.put("scope/x" + i + ".yaml", badTable(BAD_ROWS));
    }
    list.append(']');
    fixture.replace(PACK, "scope_tables: [scope/a.yaml, scope/b.yaml]", list.toString());
    return fixture;
  }

  @Test
  void aTableWithManyBadRowsReportsAtMostTheFileCapAndTheMarker() {
    List<PackProblem> problems = new ArrayList<>();
    Optional<DecisionTable> table =
        TableParser.parse("scope/x.yaml", StrictYaml.parse(badTable(BAD_ROWS)), problems);

    assertThat(table).isEmpty();
    assertThat(problems).hasSize(101);
    assertThat(problems.get(100).code()).isEqualTo(MARKER);
    assertThat(problems.get(100).path()).isEqualTo("scope/x.yaml");
    assertThat(problems.subList(0, 100)).allMatch(p -> p.code().equals("TABLE_KEY_MISSING"));
  }

  @Test
  void aTableBelowTheCapReportsExactlyWhatItDidBefore() {
    List<PackProblem> problems = new ArrayList<>();
    TableParser.parse("scope/x.yaml", StrictYaml.parse(badTable(16)), problems);

    assertThat(problems).hasSize(16 * 6);
    assertThat(codes(problems)).doesNotContain(MARKER);
  }

  @Test
  void theFirstProblemsAreTheOnesOfTheFirstRows() {
    List<PackProblem> problems = new ArrayList<>();
    TableParser.parse("scope/x.yaml", StrictYaml.parse(badTable(BAD_ROWS)), problems);

    // 100 problems are six per row: the sixteenth row is the last one that is reached
    assertThat(problems.get(0).line()).isEqualTo(3);
    assertThat(problems.subList(0, 100)).allMatch(p -> p.line() <= 19);
  }

  @Test
  void aLoadOfOneHugeTableReportsAtMostOneThousandAndOneProblems() {
    LoadResult result = PackLoader.load(packWithBadTables(1).source());

    assertThat(result.pack()).isEmpty();
    List<PackProblem> problems = result.problems();
    assertThat(problems).hasSizeLessThanOrEqualTo(1001);
    assertThat(problems.stream().filter(p -> p.path().equals("scope/x0.yaml"))).hasSize(101);
    assertThat(problems.get(problems.size() - 1).code()).isEqualTo(MARKER);
  }

  @Test
  void aPackOfTwelveHugeTablesLoadsWithoutRunningOutOfMemory() {
    PackSource source = packWithBadTables(12).source();

    LoadResult result = PackLoader.load(source);

    assertThat(result.pack()).isEmpty();
    List<PackProblem> problems = result.problems();
    assertThat(problems).hasSizeLessThanOrEqualTo(1001);
    assertThat(problems.get(problems.size() - 1).code()).isEqualTo(MARKER);
    assertThat(problems.stream().filter(p -> p.code().equals(MARKER)).count()).isGreaterThan(1);
    assertThat(problems.stream().filter(p -> p.path().equals("pack.yaml")).map(PackProblem::code))
        .contains(MARKER);
  }

  @Test
  void theLoadIsStillDeterministicAboveTheCap() {
    List<PackProblem> first = PackLoader.load(packWithBadTables(3).source()).problems();
    List<PackProblem> second = PackLoader.load(packWithBadTables(3).source()).problems();

    assertThat(second).isEqualTo(first);
  }

  @Test
  void aLoadBelowTheCapHasNoMarker() {
    LoadResult result =
        PackLoader.load(
            PackFixtures.valid().replace(PACK, "jurisdiction: EU", "jurisdiction: eu").source());

    assertThat(result.problems()).isNotEmpty();
    assertThat(codes(result.problems())).doesNotContain(MARKER);
  }

  @Test
  void aTableThatParsesButFailsTheChecksOnEveryRowReportsAtMostTheFileCap() {
    StringBuilder text = new StringBuilder(TABLE_HEAD);
    for (int i = 1; i <= 5_000; i++) {
      text.append("  - {id: R")
          .append(i)
          .append(", node: N03, priority: ")
          .append(i)
          .append(", when: \"a == 1\", outcome: REPORT, reason: FIX.N03.EXCLUDED,")
          .append(" rationale: RAT-0001, cite: []}\n");
    }
    List<PackProblem> parsing = new ArrayList<>();
    Optional<DecisionTable> table =
        TableParser.parse("scope/x.yaml", StrictYaml.parse(text.toString()), parsing);
    assertThat(parsing).isEmpty();

    List<PackProblem> problems = TableChecks.check(table.orElseThrow());

    assertThat(problems).hasSize(101);
    assertThat(problems.get(100).code()).isEqualTo(MARKER);
    assertThat(problems.get(100).path()).isEqualTo("scope/x.yaml");
  }

  @Test
  void aChecksumsFileOfHalfAMillionBadLinesReportsAtMostTheLoadCap() {
    StringBuilder lines = new StringBuilder();
    for (int i = 0; i < 400_000; i++) {
      lines.append("x\n");
    }
    PackSource source = PackFixtures.valid().put(PackSource.CHECKSUMS, lines.toString()).unsealed();

    List<PackProblem> problems = Checksums.verify(source);

    assertThat(problems).hasSize(1001);
    assertThat(problems.get(1000).code()).isEqualTo(MARKER);
    assertThat(problems.get(0).code()).isEqualTo("CHECKSUMS_MALFORMED");
  }

  // -- CSV ----------------------------------------------------------------------------------------

  private static final String CSV_HEADER =
      "id,node,priority,when,outcome,reason,rule_ref,cite,rationale,valid_from,valid_to,set,legs";

  private static DecisionTable header() {
    return new DecisionTable("scope/t.yaml", "t.scope", List.of("event.kind"), "S-CA", List.of());
  }

  @Test
  void aCsvOfHalfAMillionOneCellLinesReportsAtMostTheFileCapAndTheMarker() {
    StringBuilder csv = new StringBuilder(CSV_HEADER.length() + 2 * 520_000 + 1);
    csv.append(CSV_HEADER).append('\n');
    for (int i = 0; i < 520_000; i++) {
      csv.append("x\n");
    }
    assertThat(csv.toString().getBytes(UTF_8).length).isLessThan(1 << 20);

    List<PackProblem> problems = new ArrayList<>();
    Optional<DecisionTable> table = TableCsv.importRows(csv.toString(), header(), problems);

    assertThat(table).isEmpty();
    assertThat(problems).hasSize(101);
    assertThat(problems.get(100).code()).isEqualTo(MARKER);
    assertThat(problems.subList(0, 100)).allMatch(p -> p.code().equals("CSV_CELL_COUNT"));
    assertThat(problems.get(0).line()).isEqualTo(2);
  }

  @Test
  void aCsvWithHalfAMillionColumnsReportsAtMostTheFileCapAndTheMarker() {
    StringBuilder csv = new StringBuilder(CSV_HEADER);
    for (int i = 0; i < 260_000; i++) {
      csv.append(",x");
    }
    csv.append('\n');

    List<PackProblem> problems = new ArrayList<>();
    Optional<DecisionTable> table = TableCsv.importRows(csv.toString(), header(), problems);

    assertThat(table).isEmpty();
    assertThat(problems).hasSize(101);
    assertThat(problems.get(100).code()).isEqualTo(MARKER);
    assertThat(problems.subList(0, 100)).allMatch(p -> p.code().equals("CSV_COLUMNS"));
  }

  @Test
  void aCsvBelowTheCapHasNoMarker() {
    StringBuilder csv = new StringBuilder(CSV_HEADER).append('\n');
    for (int i = 0; i < 30; i++) {
      csv.append("x\n");
    }

    List<PackProblem> problems = new ArrayList<>();
    TableCsv.importRows(csv.toString(), header(), problems);

    assertThat(problems).hasSize(30);
    assertThat(codes(problems)).doesNotContain(MARKER);
  }

  // -- the other readers --------------------------------------------------------------------------

  @Test
  void aRegistryWithManyBadEntriesReportsAtMostTheFileCapAndTheMarker() {
    StringBuilder reasons = new StringBuilder("prefix: FIX\nnodes: [DEF]\nreasons:\n");
    StringBuilder sources = new StringBuilder("sources:\n");
    StringBuilder rationales = new StringBuilder("rationales:\n");
    for (int i = 0; i < 20_000; i++) {
      reasons.append("  - {}\n");
      sources.append("  - {}\n");
      rationales.append("  - {}\n");
    }

    List<PackProblem> r = new ArrayList<>();
    List<PackProblem> s = new ArrayList<>();
    List<PackProblem> t = new ArrayList<>();
    assertThat(RegistryParser.reasons(StrictYaml.parse(reasons.toString()), r)).isEmpty();
    assertThat(RegistryParser.sources(StrictYaml.parse(sources.toString()), s)).isEmpty();
    assertThat(RegistryParser.rationales(StrictYaml.parse(rationales.toString()), t)).isEmpty();

    for (List<PackProblem> problems : List.of(r, s, t)) {
      assertThat(problems).hasSize(101);
      assertThat(problems.get(100).code()).isEqualTo(MARKER);
    }
  }

  @Test
  void anInputModelWithManyBadVariablesReportsAtMostTheFileCapAndTheMarker() {
    StringBuilder input = new StringBuilder("variables:\n");
    for (int i = 0; i < 20_000; i++) {
      input.append("  a").append(i).append(": {}\n");
    }

    List<PackProblem> problems = new ArrayList<>();
    assertThat(CanonicalInputSpec.parse(StrictYaml.parse(input.toString()), problems)).isEmpty();

    assertThat(problems).hasSize(101);
    assertThat(problems.get(100).code()).isEqualTo(MARKER);
  }

  @Test
  void aManifestWithManyUnknownKeysReportsAtMostTheFileCapAndTheMarker() {
    StringBuilder pack = new StringBuilder(PackFixtures.valid().text(PACK));
    for (int i = 0; i < 20_000; i++) {
      pack.append("extra").append(i).append(": 1\n");
    }
    PackSource source = PackFixtures.valid().put(PACK, pack.toString()).unsealed();

    List<PackProblem> problems = new ArrayList<>();
    assertThat(PackManifest.parse(source, problems)).isEmpty();

    assertThat(problems).hasSize(101);
    assertThat(problems.get(100).code()).isEqualTo(MARKER);
  }
}
