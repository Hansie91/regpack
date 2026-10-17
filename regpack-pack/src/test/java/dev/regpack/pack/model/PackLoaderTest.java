package dev.regpack.pack.model;

import static dev.regpack.pack.PackFixtures.AUTHORSHIP;
import static dev.regpack.pack.PackFixtures.GOLDEN;
import static dev.regpack.pack.PackFixtures.INPUT;
import static dev.regpack.pack.PackFixtures.PACK;
import static dev.regpack.pack.PackFixtures.QUOTE;
import static dev.regpack.pack.PackFixtures.QUOTE_HASH;
import static dev.regpack.pack.PackFixtures.REASONS;
import static dev.regpack.pack.PackFixtures.SCHEMA_TOKEN;
import static dev.regpack.pack.PackFixtures.SOURCES;
import static dev.regpack.pack.PackFixtures.TABLE_A;
import static dev.regpack.pack.PackFixtures.TABLE_B;
import static dev.regpack.pack.PackFixtures.codes;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.pack.PackFixtures;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.source.Checksums;
import dev.regpack.pack.source.PackSource;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** The loader: cross-file checks, fail closed (companion section 5.4; plan task T9). */
class PackLoaderTest {
  /** U+FEFF BYTE ORDER MARK, built from its code point. */
  private static final String BOM = new String(Character.toChars(0xFEFF));

  private static LoadResult load(PackFixtures fixture) {
    return fixture.load();
  }

  private static void assertOnly(LoadResult result, String... codes) {
    assertThat(new TreeSet<>(codes(result))).containsExactlyInAnyOrder(codes);
    assertThat(result.pack()).isEmpty();
  }

  @Test
  void loadsTheFixturePack() {
    LoadResult result = load(PackFixtures.valid());
    assertThat(result.problems()).isEmpty();
    LoadedPack pack = result.pack().orElseThrow();
    assertThat(pack.manifest().id()).isEqualTo("fix.pack");
    assertThat(pack.input().variables()).hasSize(7);
    assertThat(pack.scopeTables()).containsOnlyKeys(TABLE_A, TABLE_B);
    assertThat(pack.scopeTables().get(TABLE_A).rows()).hasSize(3);
    assertThat(pack.scopeTables().get(TABLE_B).rows()).hasSize(2);
    assertThat(pack.reasons().entries()).hasSize(5);
    assertThat(pack.sources().sources()).containsOnlyKeys("fix-law", "regpack-spec");
    assertThat(pack.rationales().texts()).hasSize(4);
    assertThat(pack.goldenJson()).containsOnlyKeys(GOLDEN);
    assertThat(pack.authorship()).isPresent();
    assertThat(pack.opaque()).isEmpty();
    assertThat(pack.notes()).isEmpty();
    assertThat(pack.source().paths()).contains(PackSource.CHECKSUMS, PACK);
    assertThat(pack.digest().hex()).matches("[0-9a-f]{64}");
  }

  @Test
  void missingChecksumsIsAProblem() {
    LoadResult result = PackLoader.load(PackFixtures.valid().unsealed());
    assertThat(codes(result)).contains("CHECKSUMS_MISSING");
    assertThat(result.pack()).isEmpty();
  }

  @Test
  void checksumProblemsAreReported() {
    Map<String, byte[]> files = PackFixtures.valid().bytes();
    String checksums = Checksums.render(PackSource.of(files));
    files.put(PackSource.CHECKSUMS, checksums.getBytes(UTF_8));
    files.put(TABLE_B, (PackFixtures.valid().text(TABLE_B) + "# changed\n").getBytes(UTF_8));
    LoadResult result = PackLoader.load(PackSource.of(files));
    assertThat(codes(result)).contains("CHECKSUM_MISMATCH");
    assertThat(result.pack()).isEmpty();
  }

  @Test
  void inputSchemaDigestMustMatch() {
    LoadResult wrong = load(PackFixtures.valid().replace(PACK, SCHEMA_TOKEN, "0".repeat(64)));
    assertOnly(wrong, "INPUT_SCHEMA_DIGEST_MISMATCH");

    // the input model changes while input_schema still carries the old digest
    PackFixtures fixture = PackFixtures.valid();
    String old = fixture.inputSchema();
    fixture.replace(INPUT, "trade.price: {type: DECIMAL}", "trade.price: {type: STRING}");
    fixture.replace(PACK, SCHEMA_TOKEN, old);
    assertOnly(load(fixture), "INPUT_SCHEMA_DIGEST_MISMATCH");
  }

  @Test
  void mismatchMessagesCarryTheExpectedHash() {
    PackFixtures schema = PackFixtures.valid();
    String expected = schema.inputSchema();
    LoadResult wrongSchema = load(schema.replace(PACK, SCHEMA_TOKEN, "1".repeat(64)));
    PackProblem problem = wrongSchema.problems().get(0);
    assertThat(problem.code()).isEqualTo("INPUT_SCHEMA_DIGEST_MISMATCH");
    assertThat(problem.message()).contains("expected " + expected);

    LoadResult wrongQuote = load(PackFixtures.valid().replace(SOURCES, QUOTE_HASH, "2".repeat(64)));
    assertThat(codes(wrongQuote)).containsExactly("QUOTE_HASH_MISMATCH");
    assertThat(wrongQuote.problems().get(0).message()).contains("expected " + QUOTE_HASH);
    assertThat(QUOTE).startsWith("SYNTHETIC");
  }

  @Test
  void asAtFieldMustBeATimestampVariable() {
    assertOnly(
        load(
            PackFixtures.valid()
                .replace(PACK, "as_at_field: event.tradedAt", "as_at_field: client.kind")),
        "AS_AT_FIELD_INVALID");
    assertOnly(
        load(
            PackFixtures.valid()
                .replace(PACK, "as_at_field: event.tradedAt", "as_at_field: event.nope")),
        "AS_AT_FIELD_INVALID");
    assertThat(
            load(PackFixtures.valid()
                    .replace(PACK, "as_at_field: event.tradedAt", "as_at_field: event.otherTime"))
                .problems())
        .isEmpty();
  }

  @Test
  void reportFilesMustExist() {
    assertOnly(load(PackFixtures.valid().remove(TABLE_B)), "REPORT_FILE_MISSING");
    assertOnly(load(PackFixtures.valid().remove(INPUT)), "REPORT_FILE_MISSING");
    assertOnly(
        load(
            PackFixtures.valid()
                .replace(
                    PACK,
                    "scope_tables: [scope/a.yaml, scope/b.yaml]",
                    "scope_tables: [scope/a.yaml, scope/b.yaml]\n    lifecycle: lifecycle.yaml")),
        "REPORT_FILE_MISSING");
    assertOnly(
        load(
            PackFixtures.valid()
                .replace(
                    PACK,
                    "scope_tables: [scope/a.yaml, scope/b.yaml]",
                    "scope_tables: [scope/a.yaml, scope/b.yaml]\n"
                        + "    derive_tables: [derive/d.yaml]\n"
                        + "    validate: [validate/v.yaml]\n"
                        + "    endpoint_profiles: [endpoints/e.yaml]")),
        "REPORT_FILE_MISSING");
    // the same files present: accepted
    assertThat(
            load(PackFixtures.valid()
                    .replace(
                        PACK,
                        "scope_tables: [scope/a.yaml, scope/b.yaml]",
                        "scope_tables: [scope/a.yaml, scope/b.yaml]\n"
                            + "    lifecycle: lifecycle.yaml\n"
                            + "    derive_tables: [derive/d.yaml]")
                    .put("lifecycle.yaml", "business_key: [a]\n")
                    .put("derive/d.yaml", "tables: []\n"))
                .problems())
        .isEmpty();
  }

  @Test
  void reportInputMustBeTheCanonicalInputFile() {
    LoadResult result =
        load(
            PackFixtures.valid()
                .replace(PACK, "input: model/canonical-input.yaml", "input: model/other.yaml"));
    assertThat(codes(result)).contains("REPORT_FILE_MISSING", "REPORT_INPUT_UNSUPPORTED");
    assertThat(result.pack()).isEmpty();
  }

  @Test
  void scopeTablesMustLiveUnderScope() {
    LoadResult result =
        load(
            PackFixtures.valid()
                .replace(PACK, "scope/b.yaml]", "derive/x.yaml]")
                .put("derive/x.yaml", PackFixtures.valid().text(TABLE_B)));
    assertThat(codes(result)).contains("REPORT_PATH_INVALID", "FILE_UNREFERENCED");
    assertThat(result.pack()).isEmpty();
  }

  @Test
  void unreferencedTableFileIsAProblem() {
    LoadResult result =
        load(PackFixtures.valid().put("scope/c.yaml", PackFixtures.valid().text(TABLE_B)));
    assertOnly(result, "FILE_UNREFERENCED");
    assertThat(result.problems().get(0).path()).isEqualTo("scope/c.yaml");
  }

  @Test
  void unknownRootFileIsAProblem() {
    for (String path : List.of("extra.yaml", "notes.json", "report.json", "schema.yaml")) {
      LoadResult result = load(PackFixtures.valid().put(path, "a: b\n"));
      assertOnly(result, "FILE_UNKNOWN");
      assertThat(result.problems().get(0).path()).isEqualTo(path);
    }
  }

  @Test
  void unknownFilesUnderAllowedDirectoriesAreProblems() {
    for (String path :
        List.of(
            "scope/x.json",
            "scope/sub/x.yaml",
            "tests/golden/x.yaml",
            "tests/golden/sub/x.json",
            "tests/other.yaml",
            "tests/authorship.json",
            "derive/x.json",
            "route/x.json",
            "endpoints/x.json",
            "model/other.yaml",
            "model/schema.json",
            "model/sub/canonical-input.yaml")) {
      LoadResult result = load(PackFixtures.valid().put(path, "a: b\n"));
      assertOnly(result, "FILE_UNKNOWN");
      assertThat(result.problems().get(0).path()).as(path).isEqualTo(path);
    }
  }

  @Test
  void anyDepthUnderTheOpaqueDirectoriesIsAccepted() {
    LoadResult result =
        load(
            PackFixtures.valid()
                .put("derive/sub/x.yaml", "a: b\n")
                .put("validate/l1/l2/y.yaml", "a: b\n"));
    assertThat(result.problems()).isEmpty();
    assertThat(result.pack().orElseThrow().opaque())
        .containsOnlyKeys("derive/sub/x.yaml", "validate/l1/l2/y.yaml");
  }

  @Test
  void aRowLegOutsideTheReportVocabularyIsAProblem() {
    LoadResult result =
        load(PackFixtures.valid().replace(TABLE_A, "legs: [MARKET]", "legs: [ALLOCATION]"));
    assertOnly(result, "ROW_LEG_NOT_IN_REPORT");
    assertThat(result.problems().get(0).path()).isEqualTo(TABLE_A);
    // inside the vocabulary: accepted
    assertThat(
            load(PackFixtures.valid().replace(TABLE_A, "legs: [MARKET]", "legs: [CLIENT, MARKET]"))
                .problems())
        .isEmpty();
  }

  @Test
  void aDuplicateRuleRefIsAProblem() {
    assertOnly(
        load(PackFixtures.valid().replace(TABLE_B, "rule_ref: RULE-0002", "rule_ref: RULE-0001")),
        "RULE_REF_DUPLICATE");
    assertOnly(
        load(
            PackFixtures.valid()
                .replace(TABLE_A, "node: N08", "node: N08\n    rule_ref: RULE-0001")),
        "RULE_REF_DUPLICATE");
  }

  @Test
  void aRowIdUsedInTwoTablesIsAProblem() {
    LoadResult result =
        load(PackFixtures.valid().replaceAll(TABLE_B, "B-N13-natural-person", "A-N03-novation"));
    assertOnly(result, "ROW_ID_DUPLICATE");
    assertThat(result.problems().get(0).path()).isEqualTo(TABLE_B);
  }

  @Test
  void tableAndRegistryChecksRunOverTheWholePack() {
    LoadResult duplicatePriority =
        load(PackFixtures.valid().replace(TABLE_A, "priority: 800", "priority: 300"));
    assertOnly(duplicatePriority, "PRIORITY_DUPLICATE");
    assertThat(duplicatePriority.problems().get(0).path()).isEqualTo(TABLE_A);

    assertOnly(
        load(
            PackFixtures.valid()
                .replace(TABLE_B, "reason: FIX.N13.NATURAL_PERSON", "reason: FIX.N13.GONE")),
        "ROW_REASON_UNKNOWN");
    assertOnly(
        load(PackFixtures.valid().replace(TABLE_B, "provision_path: D14", "provision_path: D99")),
        "CITATION_PROVISION_UNKNOWN");
    assertOnly(
        load(PackFixtures.valid().replace(TABLE_B, "rationale: RAT-0002", "rationale: RAT-0099")),
        "ROW_RATIONALE_UNKNOWN");
  }

  @Test
  void syntaxErrorsBecomeProblemsWithTheirFileAndLine() {
    LoadResult tab = load(PackFixtures.valid().replace(REASONS, "nodes: [N03", "nodes:\t[N03"));
    assertOnly(tab, "YAML_TAB");
    assertThat(tab.problems().get(0).path()).isEqualTo(REASONS);
    assertThat(tab.problems().get(0).line()).isEqualTo(2);

    LoadResult bom =
        load(PackFixtures.valid().put(TABLE_B, BOM + PackFixtures.valid().text(TABLE_B)));
    assertThat(codes(bom)).contains("PACK_BOM");
    assertThat(bom.pack()).isEmpty();
  }

  @Test
  void invalidGoldenJsonIsAProblem() {
    LoadResult result = load(PackFixtures.valid().put(GOLDEN, "{\"a\": null}\n"));
    assertOnly(result, "GOLDEN_JSON_INVALID");
    assertThat(result.problems().get(0).path()).isEqualTo(GOLDEN);
  }

  @Test
  void requiredRegistryFilesMustExist() {
    assertThat(codes(load(PackFixtures.valid().remove(REASONS)))).contains("REGISTRY_FILE_MISSING");
    assertThat(codes(load(PackFixtures.valid().remove(SOURCES)))).contains("REGISTRY_FILE_MISSING");
    assertThat(codes(load(PackFixtures.valid().remove("rationales.yaml"))))
        .contains("REGISTRY_FILE_MISSING");
    assertThat(codes(load(PackFixtures.valid().remove(PACK)))).contains("MANIFEST_MISSING");
  }

  @Test
  void authorshipIsStrictParsed() {
    LoadResult result = load(PackFixtures.valid().put(AUTHORSHIP, "rules:\n\tA: b\n"));
    assertOnly(result, "YAML_TAB");
  }

  @Test
  void noPackIsReturnedWhenAnyProblemExists() {
    List<PackFixtures> broken =
        List.of(
            PackFixtures.valid().put("extra.yaml", "a: b\n"),
            PackFixtures.valid().remove(TABLE_B),
            PackFixtures.valid().replace(PACK, "semver: 1.0.0", "semver: 1.0"),
            PackFixtures.valid().replace(TABLE_A, "outcome: REPORT", "outcome: report"),
            PackFixtures.valid().put("tests/properties/p.yaml", "a: b\n"),
            PackFixtures.valid().put("interpretations.yaml", "pins: [x]\n"),
            PackFixtures.valid().replace(SOURCES, "status: IN_FORCE", "status: REPEALED"));
    for (PackFixtures fixture : broken) {
      LoadResult result = fixture.load();
      assertThat(result.problems()).isNotEmpty();
      assertThat(result.pack()).isEmpty();
    }
    LoadResult good = PackFixtures.valid().load();
    assertThat(good.problems()).isEmpty();
    assertThat(good.pack()).isPresent();
  }

  @Test
  void problemsAreSortedAndStable() {
    PackFixtures first = brokenInSeveralWays();
    PackFixtures second = brokenInSeveralWays();
    List<PackProblem> a = first.load().problems();
    List<PackProblem> b = second.load().problems();
    assertThat(a.size()).isGreaterThanOrEqualTo(5);
    assertThat(a).isSorted();
    assertThat(a).isEqualTo(b);
    assertThat(a).doesNotHaveDuplicates();
  }

  private static PackFixtures brokenInSeveralWays() {
    return PackFixtures.valid()
        .put("zzz.yaml", "a: b\n")
        .put("aaa.json", "{}\n")
        .put("scope/c.yaml", PackFixtures.valid().text(TABLE_B))
        .replace(TABLE_B, "rule_ref: RULE-0002", "rule_ref: RULE-0001")
        .replace(TABLE_A, "priority: 800", "priority: 300")
        .replace(SOURCES, "status: IN_FORCE", "status: REPEALED");
  }

  @Test
  void signedIsAlwaysFalse() {
    LoadedPack pack = PackFixtures.valid().load().pack().orElseThrow();
    assertThat(pack.signed()).isFalse();
    PackFixtures attested = PackFixtures.valid().put("ATTESTATION", "opaque bytes, not parsed");
    LoadedPack withAttestation = attested.load().pack().orElseThrow();
    assertThat(withAttestation.signed()).isFalse();
    assertThat(withAttestation.notes()).containsExactly("attestation present, not verified (P9b)");
  }
}
