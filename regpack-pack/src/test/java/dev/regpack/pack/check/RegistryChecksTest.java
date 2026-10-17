package dev.regpack.pack.check;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.CitationRole;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.Sha256;
import dev.regpack.pack.model.CitationRef;
import dev.regpack.pack.model.DecisionTable;
import dev.regpack.pack.model.Provision;
import dev.regpack.pack.model.RationaleRegistry;
import dev.regpack.pack.model.ReasonEntry;
import dev.regpack.pack.model.ReasonRegistry;
import dev.regpack.pack.model.SourceEntry;
import dev.regpack.pack.model.SourceRegistry;
import dev.regpack.pack.model.TableRow;
import dev.regpack.pack.model.Validity;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The registry checks: reasons, citations, quote hashes and rationales (plan task T8; PF5). */
class RegistryChecksTest {
  private static final String QUOTE = "A short quoted sentence.";
  private static final Sha256 QUOTE_HASH = Sha256.ofUtf8(QUOTE);
  private static final LocalDate FROM = LocalDate.of(2018, 1, 3);

  private static ReasonEntry reason(String code, String label, Outcome outcome) {
    return new ReasonEntry(
        new ReasonCode(code),
        label,
        ReasonEntry.Category.EXCLUSION,
        outcome,
        "compliance",
        FROM,
        Optional.empty());
  }

  private static ReasonRegistry reasons(String prefix, ReasonEntry... entries) {
    Map<String, ReasonEntry> map = new LinkedHashMap<>();
    for (ReasonEntry e : entries) {
      map.put(e.code().value(), e);
    }
    return new ReasonRegistry(prefix, Set.of("N01", "N03", "DEF"), map);
  }

  private static ReasonRegistry goodReasons() {
    return reasons(
        "SAMPLE",
        reason("SAMPLE.N03.EXCLUDED", "EXCLUDED", Outcome.DO_NOT_REPORT),
        reason("SAMPLE.N01.IN_SCOPE", "IN_SCOPE", Outcome.REPORT),
        reason("SAMPLE.DEF.UNDETERMINED", "UNDETERMINED", Outcome.UNDETERMINED));
  }

  private static Provision provision(
      String path,
      CitationRole role,
      Provision.Status status,
      Optional<String> quote,
      Optional<Sha256> hash) {
    return new Provision(path, role, status, quote, hash);
  }

  private static SourceEntry source(String id, Provision... provisions) {
    Map<String, Provision> map = new LinkedHashMap<>();
    for (Provision p : provisions) {
      map.put(p.path(), p);
    }
    return new SourceEntry(
        id, "1", LocalDate.of(2026, 10, 10), Sha256.ofUtf8("doc"), Optional.empty(), map);
  }

  private static SourceRegistry sources(SourceEntry... entries) {
    Map<String, SourceEntry> map = new LinkedHashMap<>();
    for (SourceEntry e : entries) {
      map.put(e.sourceId(), e);
    }
    return new SourceRegistry(map);
  }

  private static SourceRegistry goodSources() {
    return sources(
        source(
            "sample-law",
            provision(
                "sample/1",
                CitationRole.BASIS,
                Provision.Status.IN_FORCE,
                Optional.of(QUOTE),
                Optional.of(QUOTE_HASH)),
            provision(
                "sample/2",
                CitationRole.EXCEPTION,
                Provision.Status.IN_FORCE,
                Optional.empty(),
                Optional.empty()),
            provision(
                "sample/old",
                CitationRole.BASIS,
                Provision.Status.REPEALED,
                Optional.empty(),
                Optional.empty()),
            provision(
                "sample/next",
                CitationRole.BASIS,
                Provision.Status.PROPOSED,
                Optional.empty(),
                Optional.empty())),
        source(
            "regpack-spec",
            provision(
                "I5",
                CitationRole.GUIDANCE,
                Provision.Status.IN_FORCE,
                Optional.empty(),
                Optional.empty())));
  }

  private static RationaleRegistry goodRationales() {
    return new RationaleRegistry(Map.of("RAT-0001", "Because.", "RAT-0002", "Because too."));
  }

  private static final CitationRef OK_CITE =
      new CitationRef("sample-law", "sample/1", CitationRole.BASIS);

  private static TableRow row(
      String id,
      String node,
      Outcome outcome,
      String reason,
      List<CitationRef> cite,
      String rationale) {
    return new TableRow(
        id,
        node,
        id.equals("S-DEFAULT") ? 900 : 100,
        id.equals("S-DEFAULT") ? "true" : "event.kind == 'X'",
        outcome,
        new ReasonCode(reason),
        Optional.empty(),
        cite,
        rationale,
        id.equals("S-DEFAULT")
            ? Optional.empty()
            : Optional.of(new Validity(FROM, Optional.empty())),
        Map.of(),
        outcome == Outcome.REPORT ? List.of(Leg.MARKET) : List.of());
  }

  private static TableRow goodRow() {
    return row(
        "S-N03", "N03", Outcome.DO_NOT_REPORT, "SAMPLE.N03.EXCLUDED", List.of(OK_CITE), "RAT-0001");
  }

  private static TableRow catchAll() {
    return row(
        "S-DEFAULT",
        "DEF",
        Outcome.UNDETERMINED,
        "SAMPLE.DEF.UNDETERMINED",
        List.of(new CitationRef("regpack-spec", "I5", CitationRole.GUIDANCE)),
        "RAT-0002");
  }

  private static DecisionTable table(String id, TableRow... rows) {
    return new DecisionTable(
        "scope/" + id + ".yaml", id, List.of("event.kind"), "S-DEFAULT", List.of(rows));
  }

  private static List<PackProblem> check(
      ReasonRegistry r, SourceRegistry s, RationaleRegistry t, DecisionTable... tables) {
    return RegistryChecks.check(r, s, t, List.of(tables));
  }

  private static List<String> codes(List<PackProblem> problems) {
    return problems.stream().map(PackProblem::code).toList();
  }

  /** The problems of the good registries and one table whose first row is {@code row}. */
  private static List<PackProblem> checkRow(TableRow row) {
    return check(goodReasons(), goodSources(), goodRationales(), table("scope", row, catchAll()));
  }

  // ---------------------------------------------------------------------------------------------

  @Test
  void aCleanSetPasses() {
    assertThat(checkRow(goodRow())).isEmpty();
    assertThat(check(goodReasons(), goodSources(), goodRationales())).isEmpty();
  }

  @Test
  void aReasonMustMatchPrefixNodeAndShape() {
    List<List<Object>> cases =
        List.of(
            List.of("OTHER.N03.EXCLUDED", "EXCLUDED", "REASON_PREFIX_MISMATCH"),
            List.of("SAMPLE.N99.EXCLUDED", "EXCLUDED", "REASON_NODE_UNDECLARED"),
            List.of("SAMPLE.N03", "N03", "REASON_SHAPE_INVALID"),
            List.of("SAMPLE", "SAMPLE", "REASON_SHAPE_INVALID"),
            List.of("SAMPLE.N03.OTHER", "EXCLUDED", "REASON_LABEL_MISMATCH"),
            List.of("SAMPLE.N03.EXCLUDED.Art_5", "OTHER", "REASON_LABEL_MISMATCH"));
    for (List<Object> c : cases) {
      ReasonRegistry r =
          reasons("SAMPLE", reason((String) c.get(0), (String) c.get(1), Outcome.DO_NOT_REPORT));

      assertThat(codes(check(r, goodSources(), goodRationales())))
          .as((String) c.get(0))
          .containsExactly((String) c.get(2));
    }

    // the optional provision segment is allowed
    ReasonRegistry withProvision =
        reasons("SAMPLE", reason("SAMPLE.N03.EXCLUDED.Art_5", "EXCLUDED", Outcome.DO_NOT_REPORT));
    assertThat(check(withProvision, goodSources(), goodRationales())).isEmpty();
  }

  @Test
  void aReasonValidityMustNotBeInverted() {
    ReasonEntry inverted =
        new ReasonEntry(
            new ReasonCode("SAMPLE.N03.EXCLUDED"),
            "EXCLUDED",
            ReasonEntry.Category.EXCLUSION,
            Outcome.DO_NOT_REPORT,
            "compliance",
            FROM,
            Optional.of(FROM));

    assertThat(codes(check(reasons("SAMPLE", inverted), goodSources(), goodRationales())))
        .containsExactly("REASON_VALIDITY_INVERTED");
  }

  @Test
  void engineReservedReasonsCannotBeDeclared() {
    for (String code : List.of("EXEC_ERROR", "EXEC_BUDGET", "EXEC_CIRCUIT_OPEN", "ENRICH_MISS")) {
      ReasonRegistry r = reasons("SAMPLE", reason(code, code, Outcome.UNDETERMINED));

      assertThat(codes(check(r, goodSources(), goodRationales())))
          .as(code)
          .containsExactly("REASON_RESERVED");
    }
    // a prefix equal to an engine-reserved code would make its own codes look reserved
    ReasonRegistry badPrefix =
        reasons("EXEC_ERROR", reason("EXEC_ERROR.N03.X", "X", Outcome.DO_NOT_REPORT));
    assertThat(codes(check(badPrefix, goodSources(), goodRationales())))
        .containsExactly("PREFIX_RESERVED");
  }

  @Test
  void aRowReasonMustExist() {
    TableRow row =
        row("S-N03", "N03", Outcome.DO_NOT_REPORT, "SAMPLE.N03.NOPE", List.of(OK_CITE), "RAT-0001");

    assertThat(codes(checkRow(row))).containsExactly("ROW_REASON_UNKNOWN");
    assertThat(checkRow(row).get(0).path()).isEqualTo("scope/scope.yaml");
  }

  @Test
  void aRowOutcomeMustEqualTheReasonDefaultOutcome() {
    // the code exists; only the outcome disagrees with the registry's default outcome
    TableRow row =
        row("S-N03", "N03", Outcome.REPORT, "SAMPLE.N03.EXCLUDED", List.of(OK_CITE), "RAT-0001");

    assertThat(codes(checkRow(row))).containsExactly("REASON_OUTCOME_MISMATCH");

    // and the other way round: a row UNDETERMINED under a code whose default is REPORT
    TableRow undetermined =
        row(
            "S-N01",
            "N01",
            Outcome.UNDETERMINED,
            "SAMPLE.N01.IN_SCOPE",
            List.of(OK_CITE),
            "RAT-0001");
    assertThat(codes(checkRow(undetermined))).containsExactly("REASON_OUTCOME_MISMATCH");
  }

  @Test
  void aRowNodeMustBeDeclared() {
    TableRow row =
        row(
            "S-N03",
            "N99",
            Outcome.DO_NOT_REPORT,
            "SAMPLE.N03.EXCLUDED",
            List.of(OK_CITE),
            "RAT-0001");

    assertThat(codes(checkRow(row))).containsExactly("ROW_NODE_UNDECLARED");
  }

  @Test
  void aCitationMustResolveToASourceAndAProvision() {
    TableRow noSource =
        row(
            "S-N03",
            "N03",
            Outcome.DO_NOT_REPORT,
            "SAMPLE.N03.EXCLUDED",
            List.of(new CitationRef("nope", "sample/1", CitationRole.BASIS)),
            "RAT-0001");
    assertThat(codes(checkRow(noSource))).containsExactly("CITATION_SOURCE_UNKNOWN");

    TableRow noProvision =
        row(
            "S-N03",
            "N03",
            Outcome.DO_NOT_REPORT,
            "SAMPLE.N03.EXCLUDED",
            List.of(new CitationRef("sample-law", "sample/99", CitationRole.BASIS)),
            "RAT-0001");
    assertThat(codes(checkRow(noProvision))).containsExactly("CITATION_PROVISION_UNKNOWN");

    // the provision of another source does not resolve
    TableRow crossed =
        row(
            "S-N03",
            "N03",
            Outcome.DO_NOT_REPORT,
            "SAMPLE.N03.EXCLUDED",
            List.of(new CitationRef("regpack-spec", "sample/1", CitationRole.BASIS)),
            "RAT-0001");
    assertThat(codes(checkRow(crossed))).containsExactly("CITATION_PROVISION_UNKNOWN");
  }

  @Test
  void aCitedProvisionMustBeInForce() {
    for (String path : List.of("sample/old", "sample/next")) {
      TableRow row =
          row(
              "S-N03",
              "N03",
              Outcome.DO_NOT_REPORT,
              "SAMPLE.N03.EXCLUDED",
              List.of(new CitationRef("sample-law", path, CitationRole.BASIS)),
              "RAT-0001");

      assertThat(codes(checkRow(row))).as(path).containsExactly("PROVISION_NOT_IN_FORCE");
    }
  }

  @Test
  void aCitationRoleMustEqualTheProvisionRole() {
    TableRow row =
        row(
            "S-N03",
            "N03",
            Outcome.DO_NOT_REPORT,
            "SAMPLE.N03.EXCLUDED",
            List.of(new CitationRef("sample-law", "sample/1", CitationRole.EXCEPTION)),
            "RAT-0001");

    assertThat(codes(checkRow(row))).containsExactly("CITATION_ROLE_MISMATCH");
  }

  @Test
  void aQuoteNeedsItsHashAndTheHashMustMatch() {
    // no hash for a stored quote
    SourceRegistry noHash =
        sources(
            source(
                "sample-law",
                provision(
                    "sample/1",
                    CitationRole.BASIS,
                    Provision.Status.IN_FORCE,
                    Optional.of(QUOTE),
                    Optional.empty())));
    assertThat(codes(check(goodReasons(), noHash, goodRationales())))
        .containsExactly("QUOTE_HASH_MISSING");

    // a hash of other text; the message carries the expected hash
    SourceRegistry wrong =
        sources(
            source(
                "sample-law",
                provision(
                    "sample/1",
                    CitationRole.BASIS,
                    Provision.Status.IN_FORCE,
                    Optional.of(QUOTE),
                    Optional.of(Sha256.ofUtf8("another quote")))));
    List<PackProblem> problems = check(goodReasons(), wrong, goodRationales());
    assertThat(codes(problems)).containsExactly("QUOTE_HASH_MISMATCH");
    assertThat(problems.get(0).message()).contains("expected " + QUOTE_HASH.hex());
    assertThat(problems.get(0).path()).isEqualTo("sources.yaml");

    // a one-character difference in the quote is a different hash
    SourceRegistry edited =
        sources(
            source(
                "sample-law",
                provision(
                    "sample/1",
                    CitationRole.BASIS,
                    Provision.Status.IN_FORCE,
                    Optional.of(QUOTE + "!"),
                    Optional.of(QUOTE_HASH))));
    assertThat(codes(check(goodReasons(), edited, goodRationales())))
        .containsExactly("QUOTE_HASH_MISMATCH");

    // a hash without a stored quote is a pin, with nothing to compare it to
    SourceRegistry hashOnly =
        sources(
            source(
                "sample-law",
                provision(
                    "sample/1",
                    CitationRole.BASIS,
                    Provision.Status.IN_FORCE,
                    Optional.empty(),
                    Optional.of(QUOTE_HASH))));
    assertThat(check(goodReasons(), hashOnly, goodRationales())).isEmpty();
  }

  @Test
  void aLongQuoteIsRefused() {
    for (int length : new int[] {241, 1000}) {
      String quote = "q".repeat(length);
      SourceRegistry s = sourceWithQuote(quote);

      assertThat(codes(check(goodReasons(), s, goodRationales())))
          .as("length " + length)
          .containsExactly("QUOTE_TOO_LONG");
    }
    // 240 characters are fine, counted in code points (a supplementary character is one)
    assertThat(check(goodReasons(), sourceWithQuote("q".repeat(240)), goodRationales())).isEmpty();
    assertThat(check(goodReasons(), sourceWithQuote("😀".repeat(240)), goodRationales())).isEmpty();
    assertThat(codes(check(goodReasons(), sourceWithQuote("😀".repeat(241)), goodRationales())))
        .containsExactly("QUOTE_TOO_LONG");
  }

  private static SourceRegistry sourceWithQuote(String quote) {
    return sources(
        source(
            "sample-law",
            provision(
                "sample/1",
                CitationRole.BASIS,
                Provision.Status.IN_FORCE,
                Optional.of(quote),
                Optional.of(Sha256.ofUtf8(quote)))));
  }

  @Test
  void aRationaleMustExist() {
    TableRow row =
        row(
            "S-N03",
            "N03",
            Outcome.DO_NOT_REPORT,
            "SAMPLE.N03.EXCLUDED",
            List.of(OK_CITE),
            "RAT-0077");

    assertThat(codes(checkRow(row))).containsExactly("ROW_RATIONALE_UNKNOWN");
  }

  @Test
  void duplicateIdsAreRefused() {
    // an entry stored under a key that is not its own identity (two entries claiming one id)
    Map<String, ReasonEntry> wrongKey = new LinkedHashMap<>();
    wrongKey.put(
        "SAMPLE.N01.IN_SCOPE", reason("SAMPLE.N03.EXCLUDED", "EXCLUDED", Outcome.DO_NOT_REPORT));
    ReasonRegistry r = new ReasonRegistry("SAMPLE", Set.of("N01", "N03", "DEF"), wrongKey);
    assertThat(codes(check(r, goodSources(), goodRationales())))
        .containsExactly("REGISTRY_KEY_MISMATCH");

    Map<String, SourceEntry> wrongSourceKey = new LinkedHashMap<>();
    wrongSourceKey.put("other", source("sample-law"));
    assertThat(codes(check(goodReasons(), new SourceRegistry(wrongSourceKey), goodRationales())))
        .containsExactly("REGISTRY_KEY_MISMATCH");

    Map<String, Provision> wrongPath = new LinkedHashMap<>();
    wrongPath.put(
        "sample/9",
        provision(
            "sample/1",
            CitationRole.BASIS,
            Provision.Status.IN_FORCE,
            Optional.empty(),
            Optional.empty()));
    SourceEntry entry =
        new SourceEntry(
            "sample-law",
            "1",
            LocalDate.of(2026, 10, 10),
            Sha256.ofUtf8("d"),
            Optional.empty(),
            wrongPath);
    assertThat(codes(check(goodReasons(), sources(entry), goodRationales())))
        .containsExactly("REGISTRY_KEY_MISMATCH");

    // two tables with one id
    List<String> twice =
        codes(
            check(
                goodReasons(),
                goodSources(),
                goodRationales(),
                table("scope", goodRow(), catchAll()),
                new DecisionTable(
                    "scope/other.yaml",
                    "scope",
                    List.of("event.kind"),
                    "S-DEFAULT",
                    List.of(goodRow(), catchAll()))));
    assertThat(twice).containsExactly("TABLE_ID_DUPLICATE");
  }

  @Test
  void problemsAreSortedAndBounded() {
    TableRow bad =
        row(
            "S-N03",
            "N99",
            Outcome.REPORT,
            "SAMPLE.N03.EXCLUDED",
            List.of(new CitationRef("x".repeat(60), "p".repeat(60), CitationRole.BASIS)),
            "RAT-0077");
    List<PackProblem> problems = checkRow(bad);

    assertThat(codes(problems))
        .containsExactlyInAnyOrder(
            "ROW_NODE_UNDECLARED",
            "REASON_OUTCOME_MISMATCH",
            "CITATION_SOURCE_UNKNOWN",
            "ROW_RATIONALE_UNKNOWN");
    assertThat(problems).isSorted();
    assertThat(problems).allSatisfy(p -> assertThat(p.message().length()).isLessThanOrEqualTo(160));
    assertThat(new ArrayList<>(problems)).isEqualTo(checkRow(bad));
  }
}
