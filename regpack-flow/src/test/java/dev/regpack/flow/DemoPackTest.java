package dev.regpack.flow;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.engine.expr.Rulings;
import dev.regpack.engine.pack.CanonicalBindings;
import dev.regpack.engine.pack.PackRegime;
import dev.regpack.engine.packci.PackTestReport;
import dev.regpack.engine.packci.PackTester;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Outcome;
import dev.regpack.model.Sha256;
import dev.regpack.model.canonical.CanonicalExecutionCodec;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.check.ProblemSink;
import dev.regpack.pack.golden.GoldenCase;
import dev.regpack.pack.golden.PackGoldenLoader;
import dev.regpack.pack.golden.ReviewerRegistry;
import dev.regpack.pack.model.CanonicalInputSpec;
import dev.regpack.pack.model.DecisionTable;
import dev.regpack.pack.model.LoadResult;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.pack.model.PackLoader;
import dev.regpack.pack.model.TableRow;
import dev.regpack.pack.report.DeriveTable;
import dev.regpack.pack.report.EnrichmentPlan;
import dev.regpack.pack.report.ValidationRules;
import dev.regpack.pack.source.Checksums;
import dev.regpack.pack.source.PackSource;
import dev.regpack.pack.yaml.StrictYaml;
import dev.regpack.ports.regime.RegimeProvider;
import dev.regpack.ports.regime.RegimeProvider.EvaluationContext;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The demo pack {@code packs/demo.tr.s1} (plan W1a, task T10): the pack test passes every gate, its
 * derive, validate and enrich files parse with their readers, its input declaration is one the
 * bindings support, and its validation file is the file the validator tests of T9 were written
 * against. Everything here is synthetic and NON-PRODUCTION; the goldens are consistency-only.
 */
class DemoPackTest {
  private static final String PACK_ID = "demo.tr.s1";
  private static final ReviewerRegistry NO_REVIEWERS = ReviewerRegistry.parse("# none\n");

  /**
   * The pins of the pack (docs/evidence/w1-decisions.md, "Pins created"): the SHA-256 of the file
   * {@code CHECKSUMS} and the digest of the loaded pack. A change of any pack file changes both;
   * re-pin them only together with a reason in that file.
   */
  private static final String CHECKSUMS_SHA256 =
      "249a5163a47bb2bf290ad30f745f3d44c85604dc10a82a0f6378e3861619f085";

  private static final String PACK_DIGEST =
      "fa15beaf6e066dbc84e2041694a37b59a33c8ea6d46fe648b10bcdd0a594eff9";

  private static Path root() {
    return Path.of(System.getProperty("regpack.repo.root")).resolve("packs").resolve(PACK_ID);
  }

  private static PackSource source() {
    return PackSource.fromDirectory(root());
  }

  private static LoadedPack load() {
    LoadResult result = PackLoader.load(source());
    List<String> problems = new ArrayList<>();
    for (PackProblem p : result.problems()) {
      problems.add(p.code() + " " + p.path() + ":" + p.line() + " " + p.message());
    }
    assertThat(problems).as("problems of the demo pack").isEmpty();
    return result.pack().orElseThrow();
  }

  private static PackTestReport run(PackSource source) {
    return PackTester.run(source, NO_REVIEWERS, PackTester.NO_REGISTRY);
  }

  @Test
  void packTestPassesEveryGate() {
    PackTestReport report = run(source());

    assertThat(report.problems()).as("problems of the pack test").isEmpty();
    assertThat(report.passed()).isTrue();
    List<String> lines = report.lines();
    assertThat(lines.get(lines.size() - 1)).isEqualTo("pack test: PASS");
    assertThat(lines)
        .contains(
            "signature: UNSIGNED (an ATTESTATION is not verified before P9b)",
            "static checks: 0 problems",
            "golden: 10 of 10 pass",
            "coverage: rows 8/8, reasons 7/7, provisions 4/4 (all cases); rows 0/8 (counting"
                + " cases)");
    assertThat(lines)
        .anyMatch(
            line ->
                line.startsWith(
                    "audit: 0 of 10 counting, 10 consistency-only; reviewers registered:"
                        + " 0 from "));
    assertThat(lines).anyMatch(line -> line.startsWith("pack test: " + PACK_ID + " 0.1.0 digest "));
  }

  @Test
  void aChangedGoldenCaseFailsThePackTest() throws IOException {
    PackSource original = source();
    Map<String, byte[]> files = new HashMap<>();
    for (String path : original.paths()) {
      if (!path.equals(PackSource.CHECKSUMS) && !path.equals(PackSource.ATTESTATION)) {
        files.put(path, original.bytes(path));
      }
    }
    String golden = "tests/golden/GC-N08-1.json";
    String text = new String(files.get(golden), UTF_8);
    assertThat(text).contains("\"outcome\": \"REPORT\"");
    files.put(
        golden,
        text.replace("\"outcome\": \"REPORT\"", "\"outcome\": \"UNDETERMINED\"").getBytes(UTF_8));
    files.put(PackSource.CHECKSUMS, Checksums.render(PackSource.of(files)).getBytes(UTF_8));

    PackTestReport report = run(PackSource.of(files));

    assertThat(report.passed()).as("a golden case that disagrees with the pack").isFalse();
    assertThat(report.problems()).extracting(PackProblem::code).contains("GOLDEN_CASE_FAILED");
  }

  @Test
  void deriveValidateAndEnrichFilesParseWithTheirReaders() {
    PackSource source = source();

    ProblemSink deriveProblems = ProblemSink.forFile();
    DeriveTable derive =
        DeriveTable.parse(
                "derive/report.yaml",
                StrictYaml.parse(source.text("derive/report.yaml")),
                deriveProblems)
            .orElseThrow();
    assertThat(deriveProblems.isEmpty()).isTrue();
    assertThat(derive.rows())
        .extracting(DeriveTable.Row::field)
        .containsExactly(
            "executing_lei",
            "submitting_lei",
            "buyer",
            "seller",
            "trading_datetime",
            "capacity",
            "quantity",
            "price",
            "price_currency",
            "venue",
            "instrument",
            "execution_within_firm",
            "transmission");
    assertThat(derive.rows().get(0).when()).as("when defaults to true").isEqualTo("true");
    assertThat(derive.rows().get(2).when()).isEqualTo("event.kind == 'NEW'");
    assertThat(derive.rows().get(11).when())
        .isEqualTo("event.kind == 'NEW' && has(trade.executionWithin)");

    ProblemSink rulesProblems = ProblemSink.forFile();
    ValidationRules rules =
        ValidationRules.parse(
                "validate/rules.yaml",
                StrictYaml.parse(source.text("validate/rules.yaml")),
                rulesProblems)
            .orElseThrow();
    assertThat(rulesProblems.isEmpty()).isTrue();
    assertThat(rules.rules())
        .extracting(ValidationRules.Rule::id)
        .containsExactly(
            "V01", "V02", "V03", "V04", "V05", "V06", "V07", "V08", "V09", "V10", "V11", "V12",
            "V13", "V14", "V15");
    assertThat(rules.rules())
        .allMatch(rule -> rule.regulatorId().isEmpty(), "no rule names a regulator rule id");

    ProblemSink planProblems = ProblemSink.forFile();
    EnrichmentPlan plan =
        EnrichmentPlan.parse(
                "enrich/plan.yaml", StrictYaml.parse(source.text("enrich/plan.yaml")), planProblems)
            .orElseThrow();
    assertThat(planProblems.isEmpty()).isTrue();
    assertThat(plan.fields()).hasSize(1);
    EnrichmentPlan.Field field = plan.fields().get(0);
    assertThat(field.field()).isEqualTo("client.lei");
    assertThat(field.onMiss()).isEqualTo(EnrichmentPlan.OnMiss.UNDETERMINED);
    assertThat(field.sources())
        .containsExactly(new EnrichmentPlan.Source("static-data", "account", Duration.ofDays(30)));
  }

  @Test
  void theInputDeclarationIsSupportedByTheBindings() {
    LoadedPack pack = load();
    CanonicalInputSpec input = pack.input();

    assertThat(CanonicalBindings.checkSupported(input)).isEmpty();
    assertThat(input.variables().keySet())
        .as("the pack declares every path the bindings bind")
        .isEqualTo(CanonicalBindings.VARIABLES.keySet());
    assertThat(input.variables()).hasSize(17);
    Set<String> optional = new java.util.TreeSet<>();
    input
        .variables()
        .forEach(
            (path, variable) -> {
              if (variable.optional()) {
                optional.add(path);
              }
            });
    assertThat(optional)
        .containsExactly(
            "client.lei",
            "trade.capacity",
            "trade.counterparty",
            "trade.executionWithin",
            "trade.side");
    assertThat(input.variables().get("trade.side").enumName()).contains("Side");
  }

  @Test
  void theValidationFixtureOfT9EqualsThePackFile() throws IOException {
    byte[] fixture;
    try (InputStream in = DemoPackTest.class.getResourceAsStream("/fixtures/rules.yaml")) {
      assertThat(in).as("the validation fixture of T9").isNotNull();
      fixture = in.readAllBytes();
    }

    assertThat(Files.readAllBytes(root().resolve("validate/rules.yaml"))).isEqualTo(fixture);
  }

  @Test
  void theScopeTableHasTheEightRowsOfTheContentSheet() {
    LoadedPack pack = load();

    assertThat(pack.manifest().id()).isEqualTo(PACK_ID);
    assertThat(pack.manifest().regime()).isEqualTo(PACK_ID);
    assertThat(pack.scopeTables()).containsOnlyKeys("scope/scope.yaml");
    DecisionTable table = pack.scopeTables().get("scope/scope.yaml");
    assertThat(table.id()).isEqualTo("demo.tr.s1.scope");
    assertThat(table.catchAllRowId()).isEqualTo("S-DEFAULT-catch-all");
    Map<String, String> rows = new TreeMap<>();
    for (TableRow row : table.rows()) {
      rows.put(
          row.id(),
          row.priority() + " " + row.node() + " " + row.outcome() + " " + row.reason().value());
    }
    assertThat(rows)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "S-N03-novation", "300 N03 DO_NOT_REPORT DEMO.N03.EXCLUDED",
                "S-N13-natural-person", "400 N13 UNDETERMINED DEMO.N13.NATURAL_PERSON",
                "S-N13-client-id-missing", "450 N13 UNDETERMINED DEMO.N13.CLIENT_ID_MISSING",
                "S-N18-amend", "500 N18 UNDETERMINED DEMO.N18.AMEND_NOT_IN_SLICE",
                "S-N11-capacity", "700 N11 UNDETERMINED DEMO.N11.CAPACITY_ABSENT_OR_NOT_IN_SLICE",
                "S-N08-new", "800 N08 REPORT DEMO.N08.IN_SCOPE",
                "S-N08-cancel", "810 N08 REPORT DEMO.N08.IN_SCOPE",
                "S-DEFAULT-catch-all", "9999 DEFAULT UNDETERMINED DEMO.DEFAULT.NO_RULE_HIT"));
    assertThat(table.rows())
        .filteredOn(row -> row.outcome() == Outcome.REPORT)
        .allSatisfy(row -> assertThat(row.legs()).hasSize(1));
    assertThat(
            table.rows().stream().filter(r -> r.id().equals("S-N08-new")).findFirst().get().set())
        .isEqualTo(Map.of("action", "NEWT"));
    assertThat(
            table.rows().stream()
                .filter(r -> r.id().equals("S-N08-cancel"))
                .findFirst()
                .get()
                .set())
        .isEqualTo(Map.of("action", "CANC"));
    assertThat(pack.sources().sources())
        .containsOnlyKeys("sample-law", "regpack-spec", "fixture-law");
  }

  /**
   * The result of a golden case's input with some keys replaced; a null override removes the key.
   * The input goes through the codec, so it is a real canonical execution.
   */
  private static RegimeProvider.Result evaluateGolden(
      PackRegime regime, String caseId, Map<String, Object> overrides) {
    GoldenCase golden =
        PackGoldenLoader.load(source()).cases().stream()
            .filter(c -> c.id().equals(caseId))
            .findFirst()
            .orElseThrow();
    Map<String, Object> input = new LinkedHashMap<>(golden.input());
    overrides.forEach(
        (key, value) -> {
          if (value == null) {
            input.remove(key);
          } else {
            input.put(key, value);
          }
        });
    return regime.evaluate(
        CanonicalExecutionCodec.fromMap(input),
        new EvaluationContext(
            regime.decisionContext(),
            new IngestKey("golden", caseId, 1),
            Instant.parse(PackTester.EVALUATED_AT),
            regime.recordBudget()));
  }

  private static Map<String, String> fieldsOf(RegimeProvider.Result result) {
    Map<String, String> fields = new LinkedHashMap<>();
    result.fields().forEach(field -> fields.put(field.name(), field.value()));
    return fields;
  }

  /** The fields a golden case derives, by name in row order. */
  private static Map<String, String> derivedFields(PackRegime regime, String caseId) {
    return derivedFields(regime, caseId, new HashMap<>());
  }

  private static Map<String, String> derivedFields(
      PackRegime regime, String caseId, Map<String, Object> overrides) {
    RegimeProvider.Result result = evaluateGolden(regime, caseId, overrides);
    assertThat(result.decision().outcome()).as(caseId + overrides).isEqualTo(Outcome.REPORT);
    return fieldsOf(result);
  }

  private static Map<String, String> ordered(String... namesAndValues) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < namesAndValues.length; i += 2) {
      map.put(namesAndValues[i], namesAndValues[i + 1]);
    }
    return map;
  }

  @Test
  void theReportGoldensDeriveTheFieldsOfTheirRowsAndACancelCarriesTheEntitiesOnly() {
    PackRegime regime = PackRegime.create(load(), PACK_ID, new MeteredExecutor(), Rulings.load());

    assertThat(derivedFields(regime, "GC-N08-1"))
        .as("a new report, own account on behalf of a client: the client is the buyer")
        .containsExactlyEntriesOf(
            ordered(
                "executing_lei", "SYNT00FIRM0000000152",
                "submitting_lei", "SYNT00FIRM0000000152",
                "buyer", "SYNT00CLIENT00000167",
                "seller", "SYNT00CCP00000000194",
                "trading_datetime", "2026-10-30T09:30:00.123456Z",
                "capacity", "AOTC",
                "quantity", "100",
                "price", "12.25",
                "price_currency", "EUR",
                "venue", "XPAR",
                "instrument", "ZZ0000000016",
                "execution_within_firm", "ALGO-1",
                "transmission", "false"));
    assertThat(derivedFields(regime, "GC-N08-2"))
        .as("a matched principal sale: the firm is the seller")
        .containsExactlyEntriesOf(
            ordered(
                "executing_lei", "SYNT00FIRM0000000152",
                "submitting_lei", "SYNT00FIRM0000000152",
                "buyer", "SYNT00CCP00000000194",
                "seller", "SYNT00FIRM0000000152",
                "trading_datetime", "2026-10-30T09:30:00.123456Z",
                "capacity", "MTCH",
                "quantity", "250.5",
                "price", "8.1",
                "price_currency", "EUR",
                "venue", "XPAR",
                "instrument", "ZZ0000000024",
                "execution_within_firm", "ALGO-1",
                "transmission", "false"));
    assertThat(derivedFields(regime, "GC-N08-3"))
        .as("a cancel: the executing and the submitting entity only")
        .containsExactlyEntriesOf(
            ordered(
                "executing_lei", "SYNT00FIRM0000000152",
                "submitting_lei", "SYNT00FIRM0000000152"));
  }

  private static final String FIRM = "SYNT00FIRM0000000152";
  private static final String CLIENT = "SYNT00CLIENT00000167";
  private static final String COUNTERPARTY = "SYNT00CCP00000000194";

  private static PackRegime demoRegime() {
    return PackRegime.create(load(), PACK_ID, new MeteredExecutor(), Rulings.load());
  }

  @Test
  void buyerAndSellerFollowSideAndCapacityInAllFourCombinations() {
    PackRegime regime = demoRegime();
    // rows D-BUYER and D-SELLER: the side that is the firm's own gives the client (AOTC) or the
    // firm (MTCH); the other side is the counterparty
    String[][] cases = {
      // side, capacity, buyer, seller
      {"BUY", "AOTC", CLIENT, COUNTERPARTY},
      {"BUY", "MTCH", FIRM, COUNTERPARTY},
      {"SELL", "AOTC", COUNTERPARTY, CLIENT},
      {"SELL", "MTCH", COUNTERPARTY, FIRM},
    };
    for (String[] c : cases) {
      Map<String, Object> overrides = new HashMap<>();
      overrides.put("side", c[0]);
      overrides.put("capacity", c[1]);

      Map<String, String> fields = derivedFields(regime, "GC-N08-1", overrides);

      assertThat(fields)
          .as(c[0] + "/" + c[1])
          .containsEntry("buyer", c[2])
          .containsEntry("seller", c[3]);
      assertThat(fields).containsEntry("capacity", c[1]);
      assertThat(fields.keySet())
          .as("the field list does not depend on side or capacity")
          .hasSize(13);
    }
  }

  @Test
  void quantityRoundsToFourPlacesAndPriceToSixHalfEvenAndKeepsExactValues() {
    PackRegime regime = demoRegime();
    String[][] cases = {
      // quantity, reported, price, reported
      {"12.34565", "12.3456", "1.2345665", "1.234566"}, // ties go to the even digit: down
      {"12.34575", "12.3458", "1.2345675", "1.234568"}, // and up
      {"12.34566", "12.3457", "1.2345666", "1.234567"},
      {"12.34564", "12.3456", "1.2345664", "1.234566"},
      {"12.3456", "12.3456", "1.234566", "1.234566"},
      {"250.5", "250.5", "8.1", "8.1"},
    };
    for (String[] c : cases) {
      Map<String, Object> overrides = new HashMap<>();
      overrides.put("quantity", new BigDecimal(c[0]));
      overrides.put("price", new BigDecimal(c[2]));

      Map<String, String> fields = derivedFields(regime, "GC-N08-1", overrides);

      assertThat(fields)
          .as(c[0] + " " + c[2])
          .containsEntry("quantity", c[1])
          .containsEntry("price", c[3]);
    }
  }

  @Test
  void aNewWithoutExecutionWithinReportsWithoutThatFieldAndWithoutError() {
    PackRegime regime = demoRegime();
    Map<String, Object> overrides = new HashMap<>();
    overrides.put("executionWithinFirm", null);

    RegimeProvider.Result result = evaluateGolden(regime, "GC-N08-1", overrides);

    assertThat(result.decision().outcome()).isEqualTo(Outcome.REPORT);
    assertThat(result.decision().reasonCode().value()).isEqualTo("DEMO.N08.IN_SCOPE");
    Map<String, String> with = derivedFields(regime, "GC-N08-1");
    Map<String, String> without = fieldsOf(result);
    assertThat(without).doesNotContainKey("execution_within_firm");
    Map<String, String> expected = new LinkedHashMap<>(with);
    expected.remove("execution_within_firm");
    assertThat(without).containsExactlyEntriesOf(expected);
  }

  @Test
  void aNewNotTradedOnAVenueFallsToTheDefaultAndNotToReport() {
    PackRegime regime = demoRegime();
    Map<String, Object> overrides = new HashMap<>();
    overrides.put("tradedOnVenue", false);

    RegimeProvider.Result result = evaluateGolden(regime, "GC-N08-1", overrides);

    assertThat(result.decision().outcome()).isEqualTo(Outcome.UNDETERMINED);
    assertThat(result.decision().reasonCode().value()).isEqualTo("DEMO.DEFAULT.NO_RULE_HIT");
    assertThat(result.decision().ruleIdsHit()).containsExactly("S-DEFAULT-catch-all");
    assertThat(result.fields()).isEmpty();
  }

  @Test
  void anAssignmentIsNotReportedAndACorrectionIsUndeterminedLikeAnAmend() {
    PackRegime regime = demoRegime();

    Map<String, Object> assignment = new HashMap<>();
    assignment.put("eventKind", "ASSIGNMENT");
    RegimeProvider.Result assigned = evaluateGolden(regime, "GC-N08-1", assignment);
    Map<String, Object> novation = new HashMap<>();
    novation.put("eventKind", "NOVATION");
    RegimeProvider.Result novated = evaluateGolden(regime, "GC-N08-1", novation);
    Map<String, Object> correction = new HashMap<>();
    correction.put("eventKind", "CORRECTION");
    RegimeProvider.Result corrected = evaluateGolden(regime, "GC-N08-1", correction);

    assertThat(assigned.decision().outcome()).isEqualTo(Outcome.DO_NOT_REPORT);
    assertThat(assigned.decision().ruleIdsHit()).containsExactly("S-N03-novation");
    assertThat(assigned.decision().reasonCode().value()).isEqualTo("DEMO.N03.EXCLUDED");
    assertThat(novated.decision().ruleIdsHit()).containsExactly("S-N03-novation");
    assertThat(corrected.decision().outcome()).isEqualTo(Outcome.UNDETERMINED);
    assertThat(corrected.decision().ruleIdsHit()).containsExactly("S-N18-amend");
    assertThat(corrected.decision().reasonCode().value()).isEqualTo("DEMO.N18.AMEND_NOT_IN_SLICE");
    assertThat(assigned.fields()).isEmpty();
    assertThat(corrected.fields()).isEmpty();
  }

  @Test
  void everyReportFieldAValidationRuleNamesIsTheFieldOfADeriveRowThatAppliesToNewAndCancel()
      throws IOException {
    PackSource source = source();
    DeriveTable derive =
        DeriveTable.parse(
                "derive/report.yaml",
                StrictYaml.parse(source.text("derive/report.yaml")),
                ProblemSink.forFile())
            .orElseThrow();
    Set<String> deriveFields = new java.util.TreeSet<>();
    derive.rows().forEach(row -> deriveFields.add(row.field()));
    String fixture;
    try (InputStream in = DemoPackTest.class.getResourceAsStream("/fixtures/rules.yaml")) {
      assertThat(in).as("the validation fixture of T9").isNotNull();
      fixture = new String(in.readAllBytes(), UTF_8);
    }
    PackRegime regime = demoRegime();
    Map<String, String> onNew = derivedFields(regime, "GC-N08-1");
    Map<String, String> onCancel = derivedFields(regime, "GC-N08-3");

    for (String text : List.of(source.text("validate/rules.yaml"), fixture)) {
      ValidationRules rules =
          ValidationRules.parse("rules.yaml", StrictYaml.parse(text), ProblemSink.forFile())
              .orElseThrow();
      Set<String> named = new java.util.TreeSet<>();
      for (ValidationRules.Rule rule : rules.rules()) {
        for (String field : rule.list("fields")) {
          if (field.startsWith("report.")) {
            named.add(field.substring("report.".length()));
          }
        }
      }
      assertThat(named).as("the derived fields the rules name").isNotEmpty();
      // a name that no derive row gives would be skipped by the validator and pass silently
      assertThat(deriveFields)
          .as("every report.<name> of the rules is a derive field")
          .containsAll(named);
      // V09 (the closed-world check of the executing LEI) must see its field on every report
      assertThat(named).contains("executing_lei");
      assertThat(onNew).containsKey("executing_lei");
      assertThat(onCancel).containsKey("executing_lei");
    }
  }

  @Test
  void theChecksumsFileAndThePackDigestArePinned() throws IOException {
    byte[] checksums = Files.readAllBytes(root().resolve(PackSource.CHECKSUMS));

    assertThat(Sha256.of(checksums).hex()).isEqualTo(CHECKSUMS_SHA256);
    assertThat(new String(checksums, UTF_8)).doesNotContain("\r");
    assertThat(load().digest().hex()).isEqualTo(PACK_DIGEST);
  }
}
