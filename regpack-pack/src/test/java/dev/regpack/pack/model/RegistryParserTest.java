package dev.regpack.pack.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.CitationRole;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.Sha256;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.yaml.StrictYaml;
import dev.regpack.pack.yaml.YamlList;
import dev.regpack.pack.yaml.YamlMap;
import dev.regpack.pack.yaml.YamlNode;
import dev.regpack.pack.yaml.YamlScalar;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The three registries: reason codes, Legal Source Registry and rationales (companion section 2;
 * plan task T8).
 */
class RegistryParserTest {
  private static final String QUOTE = "A sample provision, quoted for the test.";
  private static final String QUOTE_HASH = Sha256.ofUtf8(QUOTE).hex();
  private static final String CONTENT_HASH = "a".repeat(64);

  private static final String REASONS =
      """
      prefix: SAMPLE
      nodes: [N01, N03, DEF]
      reasons:
        - code: SAMPLE.N03.EXCLUDED
          label: EXCLUDED
          category: EXCLUSION
          default_outcome: DO_NOT_REPORT
          owner: compliance
          valid: {from: 2018-01-03}
        - code: SAMPLE.DEF.UNDETERMINED
          label: UNDETERMINED
          category: DEFAULT
          default_outcome: UNDETERMINED
          owner: compliance
          valid: {from: 2018-01-03, to: 2030-01-01}
      """;

  private static final String SOURCES =
      """
      sources:
        - source_id: sample-law
          version: "1"
          retrieved_at: 2026-10-10
          content_sha256: %s
          eli: "http://data.europa.eu/eli/sample/1"
          provisions:
            - path: sample/1
              role: BASIS
              status: IN_FORCE
              quote: "%s"
              quote_sha256: %s
            - {path: sample/2, role: GUIDANCE, status: PROPOSED}
        - source_id: regpack-spec
          version: "2"
          retrieved_at: 2026-10-11
          content_sha256: %s
          provisions:
            - {path: I5, role: INTERPRETATION, status: REPEALED}
      """
          .formatted(CONTENT_HASH, QUOTE, QUOTE_HASH, "b".repeat(64));

  private static final String RATIONALES =
      """
      rationales:
        - id: RAT-0001
          text: Novations are not new trades.
        - {id: RAT-0009, text: Nothing matched}
      """;

  private static final class Result<T> {
    final Optional<T> value;
    final List<PackProblem> problems = new ArrayList<>();

    Result(Optional<T> value, List<PackProblem> problems) {
      this.value = value;
      this.problems.addAll(problems);
    }

    List<String> codes() {
      return problems.stream().map(PackProblem::code).toList();
    }
  }

  private static Result<ReasonRegistry> reasons(String yaml) {
    List<PackProblem> problems = new ArrayList<>();
    return new Result<>(RegistryParser.reasons(StrictYaml.parse(yaml), problems), problems);
  }

  private static Result<SourceRegistry> sources(String yaml) {
    List<PackProblem> problems = new ArrayList<>();
    return new Result<>(RegistryParser.sources(StrictYaml.parse(yaml), problems), problems);
  }

  private static Result<RationaleRegistry> rationales(String yaml) {
    List<PackProblem> problems = new ArrayList<>();
    return new Result<>(RegistryParser.rationales(StrictYaml.parse(yaml), problems), problems);
  }

  /** Replaces the first occurrence of {@code from} (which must occur) in {@code text}. */
  private static String swap(String text, String from, String to) {
    int at = text.indexOf(from);
    assertThat(at).as("'%s' occurs in the fixture", from).isGreaterThanOrEqualTo(0);
    return text.substring(0, at) + to + text.substring(at + from.length());
  }

  // ---------------------------------------------------------------------------------------------

  @Test
  void parsesTheThreeRegistries() {
    Result<ReasonRegistry> r = reasons(REASONS);
    assertThat(r.problems).isEmpty();
    ReasonRegistry reasons = r.value.orElseThrow();
    assertThat(reasons.prefix()).isEqualTo("SAMPLE");
    assertThat(reasons.nodes()).containsExactlyInAnyOrder("N01", "N03", "DEF");
    assertThat(reasons.entries())
        .containsOnlyKeys("SAMPLE.N03.EXCLUDED", "SAMPLE.DEF.UNDETERMINED");
    ReasonEntry excluded = reasons.entries().get("SAMPLE.N03.EXCLUDED");
    assertThat(excluded.code()).isEqualTo(new ReasonCode("SAMPLE.N03.EXCLUDED"));
    assertThat(excluded.label()).isEqualTo("EXCLUDED");
    assertThat(excluded.category()).isEqualTo(ReasonEntry.Category.EXCLUSION);
    assertThat(excluded.defaultOutcome()).isEqualTo(Outcome.DO_NOT_REPORT);
    assertThat(excluded.owner()).isEqualTo("compliance");
    assertThat(excluded.validFrom()).isEqualTo(LocalDate.of(2018, 1, 3));
    assertThat(excluded.validTo()).isEmpty();
    assertThat(reasons.entries().get("SAMPLE.DEF.UNDETERMINED").validTo())
        .contains(LocalDate.of(2030, 1, 1));

    Result<SourceRegistry> s = sources(SOURCES);
    assertThat(s.problems).isEmpty();
    SourceRegistry sources = s.value.orElseThrow();
    assertThat(sources.sources()).containsOnlyKeys("sample-law", "regpack-spec");
    SourceEntry law = sources.sources().get("sample-law");
    assertThat(law.version()).isEqualTo("1");
    assertThat(law.retrievedAt()).isEqualTo(LocalDate.of(2026, 10, 10));
    assertThat(law.contentSha256()).isEqualTo(new Sha256(CONTENT_HASH));
    assertThat(law.eli()).contains("http://data.europa.eu/eli/sample/1");
    assertThat(sources.sources().get("regpack-spec").eli()).isEmpty();
    Provision basis = law.provisions().get("sample/1");
    assertThat(basis.role()).isEqualTo(CitationRole.BASIS);
    assertThat(basis.status()).isEqualTo(Provision.Status.IN_FORCE);
    assertThat(basis.quote()).contains(QUOTE);
    assertThat(basis.quoteSha256()).contains(new Sha256(QUOTE_HASH));
    Provision guide = law.provisions().get("sample/2");
    assertThat(guide.status()).isEqualTo(Provision.Status.PROPOSED);
    assertThat(guide.quote()).isEmpty();
    assertThat(guide.quoteSha256()).isEmpty();
    assertThat(sources.resolve("sample-law", "sample/1")).contains(basis);
    assertThat(sources.resolve("sample-law", "nope")).isEmpty();
    assertThat(sources.resolve("nope", "sample/1")).isEmpty();
    assertThat(sources.resolve("regpack-spec", "I5").orElseThrow().status())
        .isEqualTo(Provision.Status.REPEALED);

    Result<RationaleRegistry> t = rationales(RATIONALES);
    assertThat(t.problems).isEmpty();
    assertThat(t.value.orElseThrow().texts())
        .containsOnly(
            Map.entry("RAT-0001", "Novations are not new trades."),
            Map.entry("RAT-0009", "Nothing matched"));
  }

  @Test
  void theRegistriesIterateInSortedOrderWhateverTheFileOrder() {
    String swapped =
        """
        prefix: SAMPLE
        nodes: [N03, DEF, N01]
        reasons:
          - {code: SAMPLE.N03.B, label: B, category: EXCLUSION, default_outcome: REPORT, owner: o, valid: {from: 2018-01-03}}
          - {code: SAMPLE.N01.A, label: A, category: INCLUSION, default_outcome: REPORT, owner: o, valid: {from: 2018-01-03}}
        """;

    ReasonRegistry r = reasons(swapped).value.orElseThrow();

    assertThat(r.entries().keySet()).containsExactly("SAMPLE.N01.A", "SAMPLE.N03.B");
    assertThat(r.nodes()).containsExactly("DEF", "N01", "N03");
  }

  @Test
  void unknownAndMissingKeysAreNamed() {
    Result<ReasonRegistry> header = reasons(REASONS + "extra: x\n");
    assertThat(header.codes()).containsExactly("REGISTRY_KEY_UNKNOWN");
    assertThat(header.value).isEmpty();

    Result<ReasonRegistry> noPrefix = reasons(swap(REASONS, "prefix: SAMPLE\n", ""));
    assertThat(noPrefix.codes()).containsExactly("REGISTRY_KEY_MISSING");
    assertThat(noPrefix.value).isEmpty();

    Result<ReasonRegistry> entryExtra =
        reasons(swap(REASONS, "    owner: compliance\n", "    owner: compliance\n    note: x\n"));
    assertThat(entryExtra.codes()).containsExactly("REGISTRY_KEY_UNKNOWN");

    Result<ReasonRegistry> entryMissing = reasons(swap(REASONS, "    label: EXCLUDED\n", ""));
    assertThat(entryMissing.codes()).containsExactly("REGISTRY_KEY_MISSING");

    Result<ReasonRegistry> validExtra =
        reasons(swap(REASONS, "valid: {from: 2018-01-03}", "valid: {from: 2018-01-03, event: x}"));
    assertThat(validExtra.codes()).containsExactly("REGISTRY_KEY_UNKNOWN");

    Result<SourceRegistry> sourceExtra =
        sources(swap(SOURCES, "    version: \"1\"\n", "    version: \"1\"\n    x: y\n"));
    assertThat(sourceExtra.codes()).containsExactly("REGISTRY_KEY_UNKNOWN");

    Result<SourceRegistry> sourceMissing = sources(swap(SOURCES, "    version: \"1\"\n", ""));
    assertThat(sourceMissing.codes()).containsExactly("REGISTRY_KEY_MISSING");

    Result<SourceRegistry> provisionMissing =
        sources(swap(SOURCES, "        status: IN_FORCE\n", ""));
    assertThat(provisionMissing.codes()).containsExactly("REGISTRY_KEY_MISSING");

    assertThat(sources("sources: []\nextra: x\n").codes()).containsExactly("REGISTRY_KEY_UNKNOWN");
    assertThat(sources("").codes()).containsExactly("REGISTRY_KEY_MISSING");

    assertThat(rationales("rationales: []\nextra: x\n").codes())
        .containsExactly("REGISTRY_KEY_UNKNOWN");
    assertThat(rationales("rationales:\n  - {id: RAT-0001}\n").codes())
        .containsExactly("REGISTRY_KEY_MISSING");

    // a wrong container is a shape problem, not a crash
    assertThat(reasons("prefix: SAMPLE\nnodes: N01\nreasons: []\n").codes())
        .containsExactly("REGISTRY_SHAPE");
    assertThat(sources("sources: x\n").codes()).containsExactly("REGISTRY_SHAPE");
    assertThat(rationales("- a\n").codes()).containsExactly("REGISTRY_SHAPE");
  }

  @Test
  void sourceIdsNeedTheSharedShape() {
    for (String bad :
        List.of("1abc", "-x", "=x", "+x", "@x", "a b", "a;b", "a,b", "a#b", "a".repeat(65), "äx")) {
      Result<SourceRegistry> r =
          sources(swap(SOURCES, "source_id: sample-law", "source_id: \"" + bad + "\""));
      assertThat(r.codes()).as(bad).containsExactly("SOURCE_ID_INVALID");
      assertThat(r.value).as(bad).isEmpty();
    }
    for (String good : List.of("a", "A1", "sample-law", "x.y_z-1", "a".repeat(64))) {
      Result<SourceRegistry> r =
          sources(swap(SOURCES, "source_id: sample-law", "source_id: \"" + good + "\""));
      assertThat(r.problems).as(good).isEmpty();
    }
  }

  @Test
  void hashesMustBe64LowerCaseHex() {
    for (String bad :
        List.of("a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64), "٠".repeat(64))) {
      Result<SourceRegistry> content =
          sources(
              swap(SOURCES, "content_sha256: " + CONTENT_HASH, "content_sha256: \"" + bad + "\""));
      assertThat(content.codes()).as(bad).containsExactly("HASH_INVALID");

      Result<SourceRegistry> quote =
          sources(swap(SOURCES, "quote_sha256: " + QUOTE_HASH, "quote_sha256: \"" + bad + "\""));
      assertThat(quote.codes()).as(bad).containsExactly("HASH_INVALID");
    }
  }

  @Test
  void datesNeedTheIsoForm() {
    for (String bad :
        List.of("2026-1-3", "2026-02-30", "26-01-03", "2026/01/03", "2026-01-03T00:00Z")) {
      assertThat(
              sources(swap(SOURCES, "retrieved_at: 2026-10-10", "retrieved_at: \"" + bad + "\""))
                  .codes())
          .as(bad)
          .containsExactly("DATE_INVALID");
      assertThat(
              reasons(swap(REASONS, "valid: {from: 2018-01-03}", "valid: {from: \"" + bad + "\"}"))
                  .codes())
          .as(bad)
          .containsExactly("DATE_INVALID");
      assertThat(reasons(swap(REASONS, "to: 2030-01-01", "to: \"" + bad + "\"")).codes())
          .as(bad)
          .containsExactly("DATE_INVALID");
    }
    // Arabic-Indic digits are not ASCII digits
    assertThat(
            sources(swap(SOURCES, "retrieved_at: 2026-10-10", "retrieved_at: \"٢٠٢٦-١٠-١٠\""))
                .codes())
        .containsExactly("DATE_INVALID");
  }

  @Test
  void categoryAndStatusAreExactNames() {
    for (String bad : List.of("exclusion", "Exclusion", "EXCLUSIONS", "OUT-OF-SCOPE", "")) {
      String value = bad.isEmpty() ? "''" : bad;
      assertThat(reasons(swap(REASONS, "category: EXCLUSION", "category: " + value)).codes())
          .as(bad)
          .containsExactly("CATEGORY_INVALID");
    }
    for (String ok : List.of("EXCLUSION", "INCLUSION", "OUT_OF_SCOPE", "DATA_QUALITY", "DEFAULT")) {
      assertThat(reasons(swap(REASONS, "category: EXCLUSION", "category: " + ok)).problems)
          .as(ok)
          .isEmpty();
    }
    for (String bad : List.of("report", "Do_Not_Report", "NONE")) {
      assertThat(
              reasons(swap(REASONS, "default_outcome: DO_NOT_REPORT", "default_outcome: " + bad))
                  .codes())
          .as(bad)
          .containsExactly("OUTCOME_INVALID");
    }
    for (String bad : List.of("in_force", "In_Force", "ACTIVE")) {
      assertThat(sources(swap(SOURCES, "status: IN_FORCE", "status: " + bad)).codes())
          .as(bad)
          .containsExactly("STATUS_INVALID");
    }
    for (String bad : List.of("basis", "LAW", "Guidance")) {
      assertThat(sources(swap(SOURCES, "role: BASIS", "role: " + bad)).codes())
          .as(bad)
          .containsExactly("ROLE_INVALID");
    }
  }

  @Test
  void prefixNodesAndCodesNeedTheirShape() {
    for (String bad : List.of("sample", "1SAMPLE", "SAM_PLE", "SAM.PLE", "A".repeat(17))) {
      assertThat(reasons(swap(REASONS, "prefix: SAMPLE", "prefix: \"" + bad + "\"")).codes())
          .as(bad)
          .containsExactly("PREFIX_INVALID");
    }
    for (String bad : List.of("n01", "1N", "N-1", "N".repeat(9))) {
      assertThat(reasons(swap(REASONS, "[N01, N03, DEF]", "[\"" + bad + "\", N03]")).codes())
          .as(bad)
          .containsExactly("NODE_INVALID");
    }
    for (String bad : List.of("sample.N03.X", "SAMPLE N03", "SAMPLE.N03.", "", "=SAMPLE.N03.X")) {
      String value = bad.isEmpty() ? "''" : "\"" + bad + "\"";
      assertThat(reasons(swap(REASONS, "code: SAMPLE.N03.EXCLUDED", "code: " + value)).codes())
          .as(bad)
          .containsExactly("REASON_CODE_INVALID");
    }
    for (String bad : List.of("a b", "A.B", "", "=x")) {
      String value = bad.isEmpty() ? "''" : "\"" + bad + "\"";
      assertThat(reasons(swap(REASONS, "label: EXCLUDED", "label: " + value)).codes())
          .as(bad)
          .containsExactly("LABEL_INVALID");
    }
    for (String bad : List.of("=owner", "a b", "", "a;b")) {
      String value = bad.isEmpty() ? "''" : "\"" + bad + "\"";
      assertThat(
              reasons(
                      swap(
                          REASONS,
                          "owner: compliance\n    valid: {from: 2018-01-03}\n",
                          "owner: " + value + "\n    valid: {from: 2018-01-03}\n"))
                  .codes())
          .as(bad)
          .containsExactly("OWNER_INVALID");
    }
  }

  @Test
  void provisionPathsQuotesAndEliAreChecked() {
    for (String bad : List.of("a b", "a;b", "=x", "", "a,b", "a#b")) {
      String value = bad.isEmpty() ? "''" : "\"" + bad + "\"";
      assertThat(sources(swap(SOURCES, "path: sample/1", "path: " + value)).codes())
          .as(bad)
          .containsExactly("PROVISION_PATH_INVALID");
    }
    assertThat(sources(swap(SOURCES, "quote: \"" + QUOTE + "\"", "quote: ''")).codes())
        .containsExactly("QUOTE_EMPTY");
    assertThat(sources(swap(SOURCES, "quote: \"" + QUOTE + "\"", "quote: '   '")).codes())
        .containsExactly("QUOTE_EMPTY");
    for (String bad : List.of("=x", "a b", "", "-x")) {
      String value = bad.isEmpty() ? "''" : "\"" + bad + "\"";
      assertThat(
              sources(swap(SOURCES, "eli: \"http://data.europa.eu/eli/sample/1\"", "eli: " + value))
                  .codes())
          .as(bad)
          .containsExactly("ELI_INVALID");
    }
    assertThat(sources(swap(SOURCES, "version: \"1\"", "version: \"=1\"")).codes())
        .containsExactly("VERSION_INVALID");
  }

  @Test
  void rationaleIdsAndTextsAreChecked() {
    for (String bad : List.of("RAT-1", "rat-0001", "RAT-00a1", "RAT0001", "RAT-٠٠٠١", "X-0001")) {
      assertThat(rationales(swap(RATIONALES, "id: RAT-0001", "id: \"" + bad + "\"")).codes())
          .as(bad)
          .containsExactly("RATIONALE_ID_INVALID");
    }
    assertThat(
            rationales(swap(RATIONALES, "text: Novations are not new trades.", "text: ''")).codes())
        .containsExactly("RATIONALE_TEXT_EMPTY");
    assertThat(
            rationales(swap(RATIONALES, "text: Novations are not new trades.", "text: '  '"))
                .codes())
        .containsExactly("RATIONALE_TEXT_EMPTY");
  }

  @Test
  void duplicateIdsAreRefused() {
    String twice =
        REASONS
            + """
              - code: SAMPLE.N03.EXCLUDED
                label: EXCLUDED
                category: EXCLUSION
                default_outcome: DO_NOT_REPORT
                owner: compliance
                valid: {from: 2018-01-03}
            """;
    assertThat(reasons(twice).codes()).containsExactly("REASON_DUPLICATE");
    assertThat(reasons(swap(REASONS, "[N01, N03, DEF]", "[N01, N03, N01]")).codes())
        .containsExactly("NODE_DUPLICATE");

    String sourceTwice =
        SOURCES
            + """
              - source_id: sample-law
                version: "3"
                retrieved_at: 2026-10-10
                content_sha256: %s
                provisions: []
            """
                .formatted(CONTENT_HASH);
    assertThat(sources(sourceTwice).codes()).containsExactly("SOURCE_DUPLICATE");
    assertThat(sources(swap(SOURCES, "{path: sample/2,", "{path: sample/1,")).codes())
        .containsExactly("PROVISION_DUPLICATE");

    assertThat(rationales(RATIONALES + "  - {id: RAT-0001, text: again}\n").codes())
        .containsExactly("RATIONALE_DUPLICATE");
  }

  @Test
  void aCellWithABadCharacterIsRefusedOnceAndNotChecked() {
    // a tree that did not come from the YAML parser (a CSV import, a test) still goes through the
    // character policy
    Map<String, YamlNode> item = new LinkedHashMap<>();
    item.put("id", new YamlScalar("RAT-0001", 3));
    item.put("text", new YamlScalar("line one\nline two", 4));
    YamlNode root =
        new YamlMap(Map.of("rationales", new YamlList(List.of(new YamlMap(item, 3)), 2)), 2);
    List<PackProblem> problems = new ArrayList<>();

    Optional<RationaleRegistry> r = RegistryParser.rationales(root, problems);

    assertThat(r).isEmpty();
    assertThat(problems).extracting(PackProblem::code).containsExactly("CELL_BAD_CHARACTER");
    assertThat(problems.get(0).path()).isEqualTo("rationales.yaml");
    assertThat(problems.get(0).line()).isEqualTo(4);
  }

  @Test
  void problemsNameTheirFileAndLineAndAreSorted() {
    List<PackProblem> problems = new ArrayList<>();
    String yaml =
        swap(
            swap(REASONS, "category: EXCLUSION", "category: nope"),
            "default_outcome: UNDETERMINED",
            "default_outcome: nope");

    RegistryParser.reasons(StrictYaml.parse(yaml), problems);

    assertThat(problems)
        .extracting(PackProblem::code)
        .containsExactly("CATEGORY_INVALID", "OUTCOME_INVALID");
    assertThat(problems).extracting(PackProblem::path).containsOnly("reasons.yaml");
    assertThat(problems.get(0).line()).isGreaterThan(0);
    assertThat(problems).isSorted();
  }
}
