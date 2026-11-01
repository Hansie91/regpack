package dev.regpack.pack.report;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.check.ProblemSink;
import dev.regpack.pack.yaml.StrictYaml;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The enrichment plan file: the typed model and the reader (plan W1a, task T8, spec 7.5). */
class EnrichmentPlanTest {
  private static final String PATH = "enrich/plan.yaml";

  /** The entry of the demo content sheet: one file-backed source, one scope field. */
  private static final String DEMO_ENTRY =
      "  - {field: client.lei, needed_by: SCOPE, sources: [{source: static-data, key: account,"
          + " max_staleness: P30D}], on_miss: UNDETERMINED}\n";

  private static String file(String entries) {
    return "fields:\n" + entries;
  }

  private static String entry(String neededBy, String onMiss) {
    return "  - {field: client.lei, needed_by: "
        + neededBy
        + ", sources: [{source: static-data, key: account, max_staleness: P1D}], on_miss: "
        + onMiss
        + "}\n";
  }

  private static List<PackProblem> problemsOf(String text) {
    ProblemSink sink = ProblemSink.forFile();
    Optional<EnrichmentPlan> plan = EnrichmentPlan.parse(PATH, StrictYaml.parse(text), sink);
    assertThat(plan.isPresent())
        .as("a plan exactly when there is no problem")
        .isEqualTo(sink.isEmpty());
    return sink.sorted(PATH);
  }

  private static List<String> codes(String text) {
    return problemsOf(text).stream().map(PackProblem::code).toList();
  }

  @Test
  void parsesTheExampleOfSpec7_5AndRefusesAScopeFieldWithFlagOrHold() {
    // the example of spec 7.5, with two sources in precedence order and the two durations
    String example =
        file(
            "  - {field: client.lei, needed_by: SCOPE, sources: [{source: static-data-db, key:"
                + " account_id, max_staleness: P1D}, {source: refdata-api, key: client_name_norm,"
                + " max_staleness: PT1H}], on_miss: UNDETERMINED}\n");
    ProblemSink sink = ProblemSink.forFile();

    EnrichmentPlan plan = EnrichmentPlan.parse(PATH, StrictYaml.parse(example), sink).orElseThrow();

    assertThat(sink.isEmpty()).isTrue();
    assertThat(plan.path()).isEqualTo(PATH);
    assertThat(plan.fields()).hasSize(1);
    EnrichmentPlan.Field field = plan.fields().get(0);
    assertThat(field.field()).isEqualTo("client.lei");
    assertThat(field.onMiss()).isEqualTo(EnrichmentPlan.OnMiss.UNDETERMINED);
    assertThat(field.sources())
        .containsExactly(
            new EnrichmentPlan.Source("static-data-db", "account_id", Duration.ofDays(1)),
            new EnrichmentPlan.Source("refdata-api", "client_name_norm", Duration.ofHours(1)));

    // the entry of the demo content sheet, and QUARANTINE, the other legal value
    EnrichmentPlan demo =
        EnrichmentPlan.parse(PATH, StrictYaml.parse(file(DEMO_ENTRY)), ProblemSink.forFile())
            .orElseThrow();
    assertThat(demo.fields().get(0).sources().get(0).maxStaleness()).isEqualTo(Duration.ofDays(30));
    assertThat(
            EnrichmentPlan.parse(
                    PATH,
                    StrictYaml.parse(file(entry("SCOPE", "QUARANTINE"))),
                    ProblemSink.forFile())
                .orElseThrow()
                .fields()
                .get(0)
                .onMiss())
        .isEqualTo(EnrichmentPlan.OnMiss.QUARANTINE);

    // FLAG and HOLD apply only to derive and validate fields (spec 7.5): refused for a scope field
    assertThat(codes(file(entry("SCOPE", "FLAG")))).containsExactly("ENRICH_PLAN_ON_MISS");
    assertThat(codes(file(entry("SCOPE", "HOLD")))).containsExactly("ENRICH_PLAN_ON_MISS");
    // and so is a word the vocabulary does not know, or one in the wrong case
    assertThat(codes(file(entry("SCOPE", "SHRUG")))).containsExactly("ENRICH_PLAN_ON_MISS");
    assertThat(codes(file(entry("SCOPE", "undetermined")))).containsExactly("ENRICH_PLAN_ON_MISS");
  }

  @Test
  void aDeriveOrValidateEntryIsRefused() {
    // the second enrichment phase (spec 7.5) is not built in the slice
    assertThat(codes(file(entry("DERIVE", "FLAG")))).containsExactly("ENRICH_PLAN_PHASE_NOT_BUILT");
    assertThat(codes(file(entry("VALIDATE", "HOLD"))))
        .containsExactly("ENRICH_PLAN_PHASE_NOT_BUILT");
    // the phase is refused whatever the miss behaviour is, and the miss behaviour of a phase that
    // is not built is not judged a second time
    assertThat(codes(file(entry("DERIVE", "UNDETERMINED"))))
        .containsExactly("ENRICH_PLAN_PHASE_NOT_BUILT");
    // a word that is no phase at all
    assertThat(codes(file(entry("LATER", "UNDETERMINED"))))
        .containsExactly("ENRICH_PLAN_NEEDED_BY_INVALID");
  }

  @Test
  void refusesAShapeAKeyAFormAndADuplicateWithTheirOwnCodes() {
    String ok = DEMO_ENTRY;
    // shape
    assertThat(codes("fields: x\n")).containsExactly("ENRICH_PLAN_SHAPE");
    assertThat(codes(file("  - x\n"))).containsExactly("ENRICH_PLAN_SHAPE");
    assertThat(codes("- x\n")).containsExactly("ENRICH_PLAN_SHAPE");
    // an unknown key, in the file and in an entry and in a source
    assertThat(codes(file(ok) + "other: 1\n")).containsExactly("ENRICH_PLAN_KEY_UNKNOWN");
    assertThat(codes(file(ok.replace("}\n", ", extra: x}\n"))))
        .containsExactly("ENRICH_PLAN_KEY_UNKNOWN");
    assertThat(codes(file(ok.replace("P30D}", "P30D, extra: x}"))))
        .containsExactly("ENRICH_PLAN_KEY_UNKNOWN");
    // a missing key
    assertThat(codes("other: 1\n")).contains("ENRICH_PLAN_KEY_MISSING");
    assertThat(codes(file("  - {field: client.lei}\n"))).contains("ENRICH_PLAN_KEY_MISSING");
    assertThat(codes(file(ok.replace(", on_miss: UNDETERMINED", ""))))
        .containsExactly("ENRICH_PLAN_KEY_MISSING");
    assertThat(codes(file(ok.replace(", max_staleness: P30D", ""))))
        .containsExactly("ENRICH_PLAN_KEY_MISSING");
    // an invalid field, source and lookup key name
    assertThat(codes(file(ok.replace("client.lei", "Client lei"))))
        .containsExactly("ENRICH_PLAN_FIELD_INVALID");
    assertThat(codes(file(ok.replace("static-data", "static data"))))
        .containsExactly("ENRICH_PLAN_FIELD_INVALID");
    assertThat(codes(file(ok.replace("key: account", "key: 1account"))))
        .containsExactly("ENRICH_PLAN_FIELD_INVALID");
    // an empty source list
    assertThat(
            codes(
                file(
                    ok.replace(
                        "[{source: static-data, key: account, max_staleness: P30D}]", "[]"))))
        .containsExactly("ENRICH_PLAN_SOURCES_EMPTY");
    // a field given twice
    assertThat(codes(file(ok + ok))).containsExactly("ENRICH_PLAN_FIELD_DUPLICATE");
    // a staleness that is no ISO duration, is zero, is negative or is a calendar period
    for (String bad : List.of("1 day", "PT0S", "-PT1H", "P1M", "P1Y", "", "PT", "p1d")) {
      String text = file(ok.replace("max_staleness: P30D", "max_staleness: \"" + bad + "\""));
      assertThat(codes(text))
          .as("staleness '%s'", bad)
          .containsExactly("ENRICH_PLAN_STALENESS_INVALID");
    }
    // a character the pack text policy refuses
    assertThat(codes(file(ok.replace("source: static-data", "source: \"static\\ndata\""))))
        .containsExactly("ENRICH_PLAN_BAD_CHARACTER");
  }

  @Test
  void anEmptyListOfFieldsIsAPlanThatEnrichesNothing() {
    EnrichmentPlan plan =
        EnrichmentPlan.parse(PATH, StrictYaml.parse("fields: []\n"), ProblemSink.forFile())
            .orElseThrow();

    assertThat(plan.fields()).isEmpty();
  }

  @Test
  void aProblemNamesThePathAndTheLineAndNeverEchoesAValueBeyondTheExcerpt() {
    String secret = "x".repeat(300);
    List<PackProblem> problems = problemsOf(file(DEMO_ENTRY.replace("P30D", "\"" + secret + "\"")));

    assertThat(problems).hasSize(1);
    PackProblem problem = problems.get(0);
    assertThat(problem.path()).isEqualTo(PATH);
    assertThat(problem.line()).isEqualTo(2);
    assertThat(problem.message()).doesNotContain(secret);
  }
}
