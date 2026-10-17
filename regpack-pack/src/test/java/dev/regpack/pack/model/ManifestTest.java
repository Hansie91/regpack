package dev.regpack.pack.model;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.EngineBuild;
import dev.regpack.model.Leg;
import dev.regpack.pack.PackFormat;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.source.PackSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** pack.yaml: the manifest (companion contracts section 2; plan task T6). */
class ManifestTest {
  private static final String SCHEMA =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  private static String yaml(String... lines) {
    return String.join("\n", lines) + "\n";
  }

  private static final String HEAD =
      yaml(
          "id: sample.s0",
          "semver: 0.1.0",
          "regime: sample.s0",
          "jurisdiction: EU",
          "valid: {legal: {from: 2018-01-03}}",
          "as_at_field: event.time",
          "engine_min: 1.0.0+lib1",
          "input_schema: " + SCHEMA);

  private static final String REPORTS =
      yaml(
          "reports:",
          "  - id: sample.s0",
          "    input: model/canonical-input.yaml",
          "    scope_tables: [scope/scope.yaml]");

  private static final class Result {
    final Optional<PackManifest> manifest;
    final List<PackProblem> problems = new ArrayList<>();

    Result(String yaml) {
      PackSource source = PackSource.of(Map.of("pack.yaml", yaml.getBytes(UTF_8)));
      this.manifest = PackManifest.parse(source, problems);
    }

    List<String> codes() {
      return problems.stream().map(PackProblem::code).toList();
    }
  }

  private static Result parse(String text) {
    return new Result(text);
  }

  /** The minimal manifest with one line replaced (by key prefix) or added. */
  private static String with(String key, String line) {
    List<String> out = new ArrayList<>();
    boolean replaced = false;
    for (String l : (HEAD + REPORTS).split("\n")) {
      if (l.startsWith(key + ":")) {
        replaced = true;
        if (line != null) {
          out.add(line);
        }
      } else {
        out.add(l);
      }
    }
    if (!replaced && line != null) {
      out.add(0, line);
    }
    return String.join("\n", out) + "\n";
  }

  private static void assertOnly(Result r, String code) {
    assertThat(r.codes()).containsExactly(code);
    assertThat(r.manifest).isEmpty();
  }

  @Test
  void aMinimalManifestParses() {
    Result r = parse(HEAD + REPORTS);
    assertThat(r.problems).isEmpty();
    PackManifest m = r.manifest.orElseThrow();
    assertThat(m.id()).isEqualTo("sample.s0");
    assertThat(m.version()).isEqualTo(new SemVer(0, 1, 0));
    assertThat(m.regime()).isEqualTo("sample.s0");
    assertThat(m.jurisdiction()).isEqualTo("EU");
    assertThat(m.validFrom()).isEqualTo(LocalDate.of(2018, 1, 3));
    assertThat(m.validTo()).isEmpty();
    assertThat(m.asAtField()).isEqualTo("event.time");
    assertThat(m.engineMin()).isEqualTo(new EngineBuild(1, 0, 0, 1));
    assertThat(m.inputSchema()).isEqualTo(SCHEMA);
    assertThat(m.supersedes()).isEmpty();
    assertThat(m.limits()).isEqualTo(PackLimits.defaults());
    assertThat(m.reports()).hasSize(1);
    ReportDefinition rep = m.reports().get(0);
    assertThat(rep.id()).isEqualTo("sample.s0");
    assertThat(rep.input()).isEqualTo("model/canonical-input.yaml");
    assertThat(rep.scopeTables()).containsExactly("scope/scope.yaml");
    assertThat(rep.deriveTables()).isEmpty();
    assertThat(rep.validate()).isEmpty();
    assertThat(rep.lifecycle()).isEmpty();
    assertThat(rep.endpointProfiles()).isEmpty();
  }

  @Test
  void aFullManifestParses() {
    String text =
        HEAD.replace(
                "valid: {legal: {from: 2018-01-03}}",
                "valid: {legal: {from: 2018-01-03, to: 2030-01-01}}")
            + yaml(
                "supersedes: sample.s0-0.0.9",
                "imports: []",
                "limits: {rule_nodes: 600, list_items: 300, string_code_points: 5000, record_budget: 100000}",
                "reports:",
                "  - id: sample.s0",
                "    input: model/canonical-input.yaml",
                "    scope_tables: [scope/a.yaml, scope/b.yaml]",
                "    derive_tables: [derive/d.yaml]",
                "    validate: [validate/v.yaml]",
                "    lifecycle: lifecycle.yaml",
                "    endpoint_profiles: [endpoints/e.yaml]",
                "    legs: [MARKET, CLIENT]",
                "  - id: sample.s0.second",
                "    input: model/canonical-input.yaml",
                "    scope_tables: [scope/c.yaml]");
    Result r = parse(text);
    assertThat(r.problems).isEmpty();
    PackManifest m = r.manifest.orElseThrow();
    assertThat(m.validTo()).contains(LocalDate.of(2030, 1, 1));
    assertThat(m.supersedes()).contains("sample.s0-0.0.9");
    assertThat(m.limits()).isEqualTo(new PackLimits(600, 300, 5000, OptionalLong.of(100_000)));
    ReportDefinition first = m.reports().get(0);
    assertThat(first.scopeTables()).containsExactly("scope/a.yaml", "scope/b.yaml");
    assertThat(first.deriveTables()).containsExactly("derive/d.yaml");
    assertThat(first.validate()).containsExactly("validate/v.yaml");
    assertThat(first.lifecycle()).contains("lifecycle.yaml");
    assertThat(first.endpointProfiles()).containsExactly("endpoints/e.yaml");
    assertThat(first.legs()).containsExactly(Leg.MARKET, Leg.CLIENT);
    assertThat(m.reports().get(1).id()).isEqualTo("sample.s0.second");
  }

  @Test
  void unknownAndMissingKeysAreNamed() {
    Result unknown = parse(with("zzz", "frobnicate: 1"));
    assertOnly(unknown, "MANIFEST_KEY_UNKNOWN");
    assertThat(unknown.problems.get(0).message()).contains("frobnicate");
    assertThat(unknown.problems.get(0).path()).isEqualTo("pack.yaml");

    for (String key :
        List.of(
            "id",
            "semver",
            "regime",
            "jurisdiction",
            "valid",
            "as_at_field",
            "engine_min",
            "input_schema")) {
      Result missing = parse(with(key, null));
      assertOnly(missing, "MANIFEST_KEY_MISSING");
      assertThat(missing.problems.get(0).message()).as(key).contains(key);
    }
    Result noReports = parse(HEAD);
    assertOnly(noReports, "MANIFEST_KEY_MISSING");
    assertThat(noReports.problems.get(0).message()).contains("reports");

    // unknown and missing keys inside a report, in valid and in limits are named too
    assertOnly(parse(HEAD + REPORTS + "    surprise: x\n"), "MANIFEST_KEY_UNKNOWN");
    assertOnly(
        parse(HEAD + "limits: {rule_nodes: 5, surprise: 1}\n" + REPORTS), "MANIFEST_KEY_UNKNOWN");
    assertOnly(
        parse(with("valid", "valid: {legal: {from: 2018-01-03, until: 2019-01-01}}")),
        "MANIFEST_KEY_UNKNOWN");
    assertOnly(
        parse(HEAD + "reports:\n  - id: sample.s0\n    input: model/c.yaml\n"),
        "MANIFEST_KEY_MISSING");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "1.2.3-rc1",
        "1.2",
        "1.2.3.4",
        "01.2.3",
        "1.2.3+build",
        "v1.2.3",
        "-1.2.3",
        "1.2.x",
        "١.٢.٣",
        "１.２.３",
        "1.2.3 ",
        "1000000000.0.0",
        ""
      })
  void semverMustBePlain(String text) {
    Result r = parse(with("semver", "semver: \"" + text + "\""));
    assertOnly(r, "SEMVER_INVALID");
    assertThatThrownBy(() -> SemVer.parse(text)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void semverParsesPlainTriples() {
    assertThat(SemVer.parse("0.0.0")).isEqualTo(new SemVer(0, 0, 0));
    assertThat(SemVer.parse("12.345.6789")).isEqualTo(new SemVer(12, 345, 6789));
    assertThat(new SemVer(1, 2, 3)).hasToString("1.2.3");
    assertThatThrownBy(() -> new SemVer(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SemVer.parse(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Sample",
        "sample_s0",
        "sample..s0",
        ".sample",
        "sample.",
        "1sample",
        "sample s0",
        "sample.S0",
        "",
        "sample.sé"
      })
  void regimeAndReportIdsMustHaveTheRegimeShape(String bad) {
    assertOnly(parse(with("id", "id: \"" + bad + "\"")), "REGIME_ID_INVALID");
    assertOnly(parse(with("regime", "regime: \"" + bad + "\"")), "REGIME_ID_INVALID");
    String report = HEAD + REPORTS.replace("- id: sample.s0", "- id: \"" + bad + "\"");
    assertOnly(parse(report), "REGIME_ID_INVALID");
  }

  @Test
  void duplicateReportIdsAreRejected() {
    String dup =
        HEAD
            + REPORTS
            + yaml(
                "  - id: sample.s0",
                "    input: model/canonical-input.yaml",
                "    scope_tables: [scope/other.yaml]");
    assertOnly(parse(dup), "REPORT_ID_DUPLICATE");
  }

  @Test
  void importsMustBeEmpty() {
    assertThat(parse(HEAD + "imports: []\n" + REPORTS).problems).isEmpty();
    assertOnly(parse(HEAD + "imports: [common.parties]\n" + REPORTS), "IMPORTS_RESERVED");
    assertOnly(
        parse(HEAD + "imports:\n  - {pack: common.parties, digest: abc}\n" + REPORTS),
        "IMPORTS_RESERVED");
    assertOnly(parse(HEAD + "imports: common\n" + REPORTS), "IMPORTS_RESERVED");
  }

  @Test
  void reportSchemaKeyIsReserved() {
    assertOnly(parse(HEAD + REPORTS + "    schema: report-schema.json\n"), "REPORT_KEY_RESERVED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"SIDE", "LEG1", "market", "TRADE"})
  void legVocabularyBeyondMifirIsReserved(String leg) {
    assertOnly(
        parse(HEAD + REPORTS + "    legs: [MARKET, " + leg + "]\n"), "LEG_VOCABULARY_RESERVED");
  }

  @Test
  void legListsAreChecked() {
    assertOnly(parse(HEAD + REPORTS + "    legs: [MARKET, MARKET]\n"), "LEG_DUPLICATE");
    assertOnly(parse(HEAD + REPORTS + "    legs: []\n"), "REPORT_LEGS_EMPTY");
    Result r = parse(HEAD + REPORTS + "    legs: [ALLOCATION]\n");
    assertThat(r.manifest.orElseThrow().reports().get(0).legs()).containsExactly(Leg.ALLOCATION);
  }

  @Test
  void reportLegsDefaultToTheMifirVocabulary() {
    Result r = parse(HEAD + REPORTS);
    assertThat(r.manifest.orElseThrow().reports().get(0).legs())
        .containsExactly(Leg.MARKET, Leg.CLIENT, Leg.ALLOCATION);
  }

  @ParameterizedTest
  @ValueSource(strings = {"supervisory", "event", "other"})
  void packValidityWindowsOtherThanLegalAreReserved(String window) {
    assertOnly(
        parse(
            with(
                "valid", "valid: {legal: {from: 2018-01-03}, " + window + ": {from: 2019-01-01}}")),
        "VALIDITY_WINDOW_RESERVED");
    assertOnly(
        parse(with("valid", "valid: {" + window + ": {from: 2019-01-01}}")),
        "VALIDITY_WINDOW_RESERVED");
  }

  @Test
  void limitsDefaultsAndBounds() {
    assertThat(PackLimits.defaults())
        .isEqualTo(new PackLimits(512, 256, 4096, OptionalLong.empty()));

    // accepted at the cap and at 1
    Result cap =
        parse(
            HEAD
                + "limits: {rule_nodes: 4096, list_items: 4096, string_code_points: 65536, record_budget: 10000000}\n"
                + REPORTS);
    assertThat(cap.problems).isEmpty();
    assertThat(cap.manifest.orElseThrow().limits())
        .isEqualTo(
            new PackLimits(
                PackFormat.MAX_RULE_NODES,
                PackFormat.MAX_LIST_ITEMS,
                PackFormat.MAX_STRING_CODE_POINTS,
                OptionalLong.of(PackFormat.MAX_RECORD_BUDGET)));
    Result one =
        parse(
            HEAD
                + "limits: {rule_nodes: 1, list_items: 1, string_code_points: 1, record_budget: 1}\n"
                + REPORTS);
    assertThat(one.manifest.orElseThrow().limits())
        .isEqualTo(new PackLimits(1, 1, 1, OptionalLong.of(1)));

    // a limit that is not declared keeps its default
    Result part = parse(HEAD + "limits: {list_items: 10}\n" + REPORTS);
    assertThat(part.manifest.orElseThrow().limits())
        .isEqualTo(new PackLimits(512, 10, 4096, OptionalLong.empty()));

    for (String bad :
        List.of(
            "0",
            "4097",
            "-1",
            "12345678901234567890",
            "١٢",
            "１２",
            "1e3",
            "0x10",
            " 5",
            "5 ",
            "007",
            "1.5",
            "abc",
            "")) {
      Result r = parse(HEAD + "limits: {rule_nodes: \"" + bad + "\"}\n" + REPORTS);
      assertThat(r.codes()).as(bad).containsExactly("LIMITS_OUT_OF_RANGE");
      assertThat(r.manifest).as(bad).isEmpty();
    }
    assertOnly(parse(HEAD + "limits: {list_items: 4097}\n" + REPORTS), "LIMITS_OUT_OF_RANGE");
    assertOnly(
        parse(HEAD + "limits: {string_code_points: 65537}\n" + REPORTS), "LIMITS_OUT_OF_RANGE");
    assertOnly(
        parse(HEAD + "limits: {record_budget: 10000001}\n" + REPORTS), "LIMITS_OUT_OF_RANGE");
    assertThatThrownBy(() -> new PackLimits(0, 1, 1, OptionalLong.empty()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PackLimits(1, 1, 1, OptionalLong.of(0)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validToMustBeAfterFrom() {
    assertThat(parse(with("valid", "valid: {legal: {from: 2018-01-03, to: 2018-01-04}}")).problems)
        .isEmpty();
    assertOnly(
        parse(with("valid", "valid: {legal: {from: 2018-01-03, to: 2018-01-03}}")),
        "VALID_RANGE_INVALID");
    assertOnly(
        parse(with("valid", "valid: {legal: {from: 2018-01-03, to: 2017-12-31}}")),
        "VALID_RANGE_INVALID");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2018-1-3",
        "20180103",
        "2018-01-03T00:00:00Z",
        "2018-02-30",
        "2018-13-01",
        "null",
        "~",
        "03.01.2018",
        "+12018-01-03",
        "２０１８-01-03",
        "2018-01-03 ",
        ""
      })
  void datesNeedTheIsoForm(String date) {
    assertOnly(parse(with("valid", "valid: {legal: {from: \"" + date + "\"}}")), "DATE_INVALID");
    assertOnly(
        parse(with("valid", "valid: {legal: {from: 2018-01-03, to: \"" + date + "\"}}")),
        "DATE_INVALID");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "1.0",
        "1.0.0",
        "1.0.0+lib",
        "01.0.0+lib1",
        "1.0.0+LIB1",
        "x",
        "",
        "1.0.0+lib1+x",
        "10000.0.0+lib1"
      })
  void engineMinMustParse(String text) {
    assertOnly(parse(with("engine_min", "engine_min: \"" + text + "\"")), "ENGINE_MIN_INVALID");
  }

  @Test
  void otherFieldsAreChecked() {
    assertOnly(
        parse(with("input_schema", "input_schema: " + SCHEMA.substring(1))),
        "INPUT_SCHEMA_INVALID");
    assertOnly(
        parse(with("input_schema", "input_schema: " + SCHEMA.toUpperCase(java.util.Locale.ROOT))),
        "INPUT_SCHEMA_INVALID");
    assertOnly(parse(with("jurisdiction", "jurisdiction: eu")), "JURISDICTION_INVALID");
    assertOnly(parse(with("jurisdiction", "jurisdiction: E")), "JURISDICTION_INVALID");
    assertOnly(parse(with("as_at_field", "as_at_field: Event.Time")), "AS_AT_FIELD_INVALID");
    assertOnly(parse(with("as_at_field", "as_at_field: \"event time\"")), "AS_AT_FIELD_INVALID");
    assertOnly(parse(with("supersedes", "supersedes: \"../x\"")), "SUPERSEDES_INVALID");
  }

  @Test
  void reportShapeIsChecked() {
    assertOnly(parse(HEAD + "reports: []\n"), "REPORTS_EMPTY");
    assertOnly(parse(HEAD + "reports: x\n"), "MANIFEST_SHAPE");
    assertOnly(parse(HEAD + "reports: [x]\n"), "MANIFEST_SHAPE");
    assertOnly(
        parse(HEAD + REPORTS.replace("[scope/scope.yaml]", "[]")), "REPORT_SCOPE_TABLES_EMPTY");
    assertOnly(
        parse(HEAD + REPORTS.replace("[scope/scope.yaml]", "scope/scope.yaml")), "MANIFEST_SHAPE");
    assertOnly(
        parse(HEAD + REPORTS.replace("[scope/scope.yaml]", "[../x.yaml]")), "REPORT_PATH_INVALID");
    assertOnly(
        parse(HEAD + REPORTS.replace("model/canonical-input.yaml", "/etc/passwd")),
        "REPORT_PATH_INVALID");
    assertOnly(parse(HEAD + REPORTS + "    lifecycle: [a.yaml]\n"), "MANIFEST_SHAPE");
    assertOnly(parse(with("id", "id: [a]")), "MANIFEST_SHAPE");
    assertOnly(parse("- a\n- b\n"), "MANIFEST_SHAPE");
  }

  @Test
  void aMissingOrBrokenFileIsAProblem() {
    List<PackProblem> problems = new ArrayList<>();
    assertThat(PackManifest.parse(PackSource.of(Map.of()), problems)).isEmpty();
    assertThat(problems.stream().map(PackProblem::code)).containsExactly("MANIFEST_MISSING");

    problems.clear();
    PackSource tab = PackSource.of(Map.of("pack.yaml", "id:\tx\n".getBytes(UTF_8)));
    assertThat(PackManifest.parse(tab, problems)).isEmpty();
    assertThat(problems).hasSize(1);
    assertThat(problems.get(0).code()).isEqualTo("YAML_TAB");
    assertThat(problems.get(0).line()).isEqualTo(1);
    assertThat(problems.get(0).path()).isEqualTo("pack.yaml");

    problems.clear();
    PackSource bom =
        PackSource.of(Map.of("pack.yaml", new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a'}));
    assertThat(PackManifest.parse(bom, problems)).isEmpty();
    assertThat(problems.get(0).code()).isEqualTo("PACK_BOM");
  }

  @Test
  void problemsAreSortedAndAppendedToTheCallersList() {
    String text = with("semver", "semver: 1.2").replace("engine_min: 1.0.0+lib1", "engine_min: x");
    List<PackProblem> problems = new ArrayList<>();
    problems.add(new PackProblem("EARLIER_PROBLEM", "a.yaml", 1, "kept"));
    Optional<PackManifest> m =
        PackManifest.parse(PackSource.of(Map.of("pack.yaml", text.getBytes(UTF_8))), problems);
    assertThat(m).isEmpty();
    assertThat(problems.get(0).code()).isEqualTo("EARLIER_PROBLEM");
    List<PackProblem> mine = problems.subList(1, problems.size());
    assertThat(mine).hasSize(2).isSorted();
  }
}
