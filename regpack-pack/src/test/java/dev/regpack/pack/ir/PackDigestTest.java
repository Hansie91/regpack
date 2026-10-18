package dev.regpack.pack.ir;

import static dev.regpack.pack.PackFixtures.AUTHORSHIP;
import static dev.regpack.pack.PackFixtures.GOLDEN;
import static dev.regpack.pack.PackFixtures.INPUT;
import static dev.regpack.pack.PackFixtures.PACK;
import static dev.regpack.pack.PackFixtures.QUOTE;
import static dev.regpack.pack.PackFixtures.QUOTE_HASH;
import static dev.regpack.pack.PackFixtures.RATIONALES;
import static dev.regpack.pack.PackFixtures.REASONS;
import static dev.regpack.pack.PackFixtures.SOURCES;
import static dev.regpack.pack.PackFixtures.TABLE_A;
import static dev.regpack.pack.PackFixtures.TABLE_B;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import dev.regpack.pack.PackFixtures;
import dev.regpack.pack.model.LoadResult;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.pack.source.PackSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** The canonical IR and the pack digest (review focus PF3; plan task T9). */
class PackDigestTest {
  /** The digest of the fixture pack, captured at the first green run (recorded as a pin). */
  private static final String FIXTURE_DIGEST =
      "89d9e4331f6942bd41ad90b1c366acfcecbc722e5dce357ac2e5c731796fd4e1";

  private static LoadedPack load(PackFixtures fixture) {
    LoadResult result = fixture.load();
    assertThat(result.problems()).isEmpty();
    return result.pack().orElseThrow();
  }

  private static Sha256 digest(PackFixtures fixture) {
    return load(fixture).digest();
  }

  // -- invariances ------------------------------------------------------------------------------

  private static final String PACK_VARIANT =
      """
      # the manifest with its keys in another order
      reports:
        - scope_tables: [scope/a.yaml, scope/b.yaml]
          legs: [CLIENT, MARKET]
          input: model/canonical-input.yaml
          id: fix.pack
      limits:
        record_budget: 100000
        rule_nodes: 600
      input_schema: @INPUT_SCHEMA@
      engine_min: 1.0.0+lib1
      as_at_field: event.tradedAt
      valid:
        legal:
          from: 2018-01-03
      jurisdiction: EU
      regime: fix.pack
      semver: 1.0.0
      id: fix.pack
      """;

  private static final String TABLE_A_VARIANT =
      """
      # table a: another key order, the catch-all first, block citations, comments
      rows:
        - id: A-DEFAULT   # the catch-all
          rationale: RAT-0004
          cite:
            - source_id: regpack-spec
              provision_path: I5
              role: GUIDANCE
          reason: FIX.DEF.NO_RULE_HIT
          outcome: UNDETERMINED
          when: 'true'
          priority: 9999
          node: DEF
        - rationale: RAT-0003
          legs: [MARKET]
          valid: {legal: {from: 2018-01-03}}
          cite: [{role: BASIS, provision_path: fix/2, source_id: fix-law}]
          reason: FIX.N08.IN_SCOPE
          outcome: REPORT
          when: "client.kind == 'LEGAL_ENTITY'"
          priority: 800
          node: N08
          id: A-N08-equity
        - id: A-N03-novation
          node: N03
          priority: 300
          when: "event.kind in ['NOVATION','ASSIGNMENT']"   # a comment
          outcome: DO_NOT_REPORT
          reason: FIX.N03.EXCLUDED
          rule_ref: RULE-0001
          cite: [{source_id: fix-law, provision_path: fix/1, role: BASIS}]
          rationale: RAT-0001
          valid: {legal: {from: 2018-01-03}}
          set: {action: NEWT}
      table:
        catch_all: A-DEFAULT
        inputs: [event.kind]
        priority: explicit
        hit_policy: FIRST
        id: fix.a
      """;

  private static final String REASONS_VARIANT =
      """
      reasons:
        - {code: FIX.DEF.NO_RULE_HIT, label: NO_RULE_HIT, category: DEFAULT, default_outcome: UNDETERMINED, owner: compliance, valid: {from: 2018-01-03}}
        - code: FIX.N13.NATURAL_PERSON
          label: NATURAL_PERSON
          category: OUT_OF_SCOPE
          default_outcome: UNDETERMINED
          owner: compliance
          valid: {from: 2018-01-03}
        - {code: FIX.N08.IN_SCOPE, label: IN_SCOPE, category: INCLUSION, default_outcome: REPORT, owner: compliance, valid: {from: 2018-01-03}}
        - {code: FIX.N03.OTHER, label: OTHER, category: EXCLUSION, default_outcome: DO_NOT_REPORT, owner: compliance, valid: {from: 2018-01-03}}
        - {code: FIX.N03.EXCLUDED, label: EXCLUDED, category: EXCLUSION, default_outcome: DO_NOT_REPORT, owner: compliance, valid: {from: 2018-01-03}}
      nodes: [DEF, N13, N08, N03]
      prefix: FIX
      """;

  private static final String RATIONALES_VARIANT =
      """
      rationales:
        - {text: Nothing matched, id: RAT-0004}
        - {text: Equity on a venue is in scope, id: RAT-0003}
        - {text: Natural persons need a policy decision, id: RAT-0002}
        - {text: Novations are not new trades, id: RAT-0001}
      """;

  private static final String AUTHORSHIP_VARIANT =
      """
      rules:
        B-DEFAULT: "agent:author"
        B-N13-natural-person: "agent:author"
        A-DEFAULT: "agent:author"
        A-N08-equity: "agent:author"
        A-N03-novation: "agent:author"
      """;

  private static final String GOLDEN_VARIANT =
      "{ \"provenance\" : {\"kind\":\"CONSISTENCY_ONLY\"},\n"
          + "  \"input\": {\"price\": 12.250, \"clientKind\": \"LEGAL_ENTITY\"},\n"
          + "  \"author\": \"agent:golden\", \"id\": \"GC-1\" }\n";

  @Test
  void commentsKeyOrderRowOrderAndLineEndingsDoNotChangeTheDigest() {
    Sha256 base = digest(PackFixtures.valid());

    PackFixtures variant =
        PackFixtures.valid()
            .put(PACK, PACK_VARIANT)
            .put(TABLE_A, TABLE_A_VARIANT)
            .put(REASONS, REASONS_VARIANT)
            .put(RATIONALES, RATIONALES_VARIANT)
            .put(AUTHORSHIP, AUTHORSHIP_VARIANT)
            .put(GOLDEN, GOLDEN_VARIANT);
    assertThat(digest(variant)).isEqualTo(base);

    // the sources in another order, comments, and a reordered provision list
    String sources = PackFixtures.valid().text(SOURCES);
    int second = sources.indexOf("  - source_id: regpack-spec");
    String reordered =
        "# sources\nsources:\n"
            + sources.substring(second)
            + sources.substring("sources:\n".length(), second);
    assertThat(digest(PackFixtures.valid().put(SOURCES, reordered))).isEqualTo(base);

    // CRLF line ends in every file, also on top of the variant
    PackFixtures crlf = PackFixtures.valid();
    PackFixtures crlfVariant =
        PackFixtures.valid()
            .put(PACK, PACK_VARIANT)
            .put(TABLE_A, TABLE_A_VARIANT)
            .put(REASONS, REASONS_VARIANT)
            .put(GOLDEN, GOLDEN_VARIANT);
    for (String path :
        List.of(PACK, INPUT, TABLE_A, TABLE_B, REASONS, SOURCES, RATIONALES, AUTHORSHIP, GOLDEN)) {
      crlf.put(path, crlf.text(path).replace("\n", "\r\n"));
      crlfVariant.put(path, crlfVariant.text(path).replace("\n", "\r\n"));
    }
    assertThat(digest(crlf)).isEqualTo(base);
    assertThat(digest(crlfVariant)).isEqualTo(base);
  }

  @Test
  void settingsWrittenAtTheirDefaultsAreTheSameTypedPack() {
    // the digest is over the typed pack: a limit spelled out at its default or imports written as
    // an empty list change no content
    Sha256 base = digest(PackFixtures.valid());
    assertThat(
            digest(
                PackFixtures.valid()
                    .replace(PACK, "rule_nodes: 600", "rule_nodes: 600, list_items: 256")))
        .isEqualTo(base);
    assertThat(digest(PackFixtures.valid().replace(PACK, "reports:", "imports: []\nreports:")))
        .isEqualTo(base);
  }

  // -- every evaluated field --------------------------------------------------------------------

  private record Edit(String path, String from, String to, boolean all) {}

  private record Change(String name, List<Edit> edits) {}

  private static Change change(String name, Edit... edits) {
    return new Change(name, List.of(edits));
  }

  private static Edit edit(String path, String from, String to) {
    return new Edit(path, from, to, false);
  }

  private static Edit editAll(String path, String from, String to) {
    return new Edit(path, from, to, true);
  }

  private static List<Change> changes() {
    String otherQuote = "SYNTHETIC FIXTURE TEXT 1b, not law";
    return List.of(
        change(
            "a row's when",
            edit(
                TABLE_A,
                "\"event.kind in ['NOVATION','ASSIGNMENT']\"",
                "\"event.kind in ['NOVATION']\"")),
        change(
            "a row's outcome (with the default outcome of its reason)",
            edit(TABLE_A, "outcome: DO_NOT_REPORT", "outcome: REPORT_BY_OTHER"),
            edit(REASONS, "default_outcome: DO_NOT_REPORT", "default_outcome: REPORT_BY_OTHER")),
        change(
            "a row's reason", edit(TABLE_A, "reason: FIX.N03.EXCLUDED", "reason: FIX.N03.OTHER")),
        change("a row's priority", edit(TABLE_A, "priority: 300", "priority: 301")),
        change("a row's node", edit(TABLE_A, "node: N03", "node: N08")),
        change("a citation path", edit(TABLE_A, "provision_path: fix/1", "provision_path: fix/2")),
        change(
            "a citation role (with the role of its provision)",
            edit(
                TABLE_A,
                "provision_path: fix/2, role: BASIS",
                "provision_path: fix/2, role: EXCEPTION"),
            edit(SOURCES, "{path: fix/2, role: BASIS", "{path: fix/2, role: EXCEPTION")),
        change("a rationale", edit(TABLE_A, "rationale: RAT-0001", "rationale: RAT-0002")),
        change(
            "a rationale text",
            edit(RATIONALES, "Novations are not new trades", "Novations are no trades")),
        change(
            "a row's valid.from",
            edit(
                TABLE_A,
                "valid: {legal: {from: 2018-01-03}}",
                "valid: {legal: {from: 2018-01-04}}")),
        change(
            "a row's valid.to",
            edit(
                TABLE_A,
                "valid: {legal: {from: 2018-01-03}}",
                "valid: {legal: {from: 2018-01-03, to: 2030-01-01}}")),
        change("a set value", edit(TABLE_A, "set: {action: NEWT}", "set: {action: CANC}")),
        change("a set name", edit(TABLE_A, "set: {action: NEWT}", "set: {kind: NEWT}")),
        change("a row's legs", edit(TABLE_A, "legs: [MARKET]", "legs: [CLIENT]")),
        change("a row's rule_ref", edit(TABLE_A, "rule_ref: RULE-0001", "rule_ref: RULE-0003")),
        change(
            "the table's catch_all (with the id of its row)",
            editAll(TABLE_A, "A-DEFAULT", "A-FALLBACK")),
        change(
            "the table's inputs",
            edit(TABLE_A, "inputs: [event.kind]", "inputs: [event.kind, event.tradedAt]")),
        change(
            "a reason label (with the code that carries it)",
            edit(TABLE_A, "FIX.N03.EXCLUDED", "FIX.N03.OUTSIDE"),
            edit(
                REASONS,
                "code: FIX.N03.EXCLUDED, label: EXCLUDED",
                "code: FIX.N03.OUTSIDE, label: OUTSIDE")),
        change(
            "a reason default_outcome",
            edit(
                REASONS,
                "label: OTHER, category: EXCLUSION, default_outcome: DO_NOT_REPORT",
                "label: OTHER, category: EXCLUSION, default_outcome: UNDETERMINED")),
        change("a reason category", edit(REASONS, "category: EXCLUSION", "category: OUT_OF_SCOPE")),
        change("a reason owner", edit(REASONS, "owner: compliance", "owner: legal")),
        change(
            "a reason validity",
            edit(
                REASONS, "valid: {from: 2018-01-03}", "valid: {from: 2018-01-03, to: 2031-01-01}")),
        change(
            "a declared node",
            edit(REASONS, "nodes: [N03, N08, N13, DEF]", "nodes: [N03, N08, N13, DEF, N99]")),
        change("a source version", edit(SOURCES, "version: fix-1", "version: fix-2")),
        change(
            "a source content hash",
            edit(
                SOURCES,
                Sha256.ofUtf8("fix-law fix-1").hex(),
                Sha256.ofUtf8("fix-law fix-2").hex())),
        change(
            "a quote and its hash",
            edit(SOURCES, QUOTE, otherQuote),
            edit(SOURCES, QUOTE_HASH, Sha256.ofUtf8(otherQuote).hex())),
        change(
            "a provision status",
            edit(
                SOURCES,
                "{path: fix/3, role: GUIDANCE, status: PROPOSED}",
                "{path: fix/3, role: GUIDANCE, status: REPEALED}")),
        change(
            "a retrieved_at",
            edit(SOURCES, "retrieved_at: 2026-10-16", "retrieved_at: 2026-10-17")),
        change(
            "an eli",
            edit(
                SOURCES,
                "version: fix-1",
                "version: fix-1\n    eli: \"http://example.test/eli/1\"")),
        change(
            "a variable type",
            edit(INPUT, "client.kind: {type: STRING}", "client.kind: {type: DECIMAL}")),
        change("a variable enum name", edit(INPUT, "enum: LifecycleKind", "enum: LifecycleKind2")),
        change(
            "a variable optional flag", edit(INPUT, "optional: \"true\"", "optional: \"false\"")),
        change("a variable source", edit(INPUT, "source: STATE", "source: EVENT")),
        change(
            "a variable",
            edit(
                INPUT,
                "trade.price: {type: DECIMAL}",
                "trade.price: {type: DECIMAL}\n  trade.size: {type: INT}")),
        change(
            "limits.record_budget", edit(PACK, "record_budget: 100000", "record_budget: 100001")),
        change("limits.rule_nodes", edit(PACK, "rule_nodes: 600", "rule_nodes: 601")),
        change(
            "limits.string_code_points",
            edit(PACK, "rule_nodes: 600", "rule_nodes: 600, string_code_points: 4097")),
        change(
            "limits.list_items", edit(PACK, "rule_nodes: 600", "rule_nodes: 600, list_items: 257")),
        change("engine_min", edit(PACK, "engine_min: 1.0.0+lib1", "engine_min: 1.0.1+lib1")),
        change(
            "as_at_field",
            edit(PACK, "as_at_field: event.tradedAt", "as_at_field: event.otherTime")),
        change(
            "the pack's valid.legal.to",
            edit(PACK, "from: 2018-01-03}}", "from: 2018-01-03, to: 2030-01-01}}")),
        change(
            "the pack's valid.legal.from", edit(PACK, "from: 2018-01-03}}", "from: 2018-01-04}}")),
        change("semver", edit(PACK, "semver: 1.0.0", "semver: 1.0.1")),
        change("jurisdiction", edit(PACK, "jurisdiction: EU", "jurisdiction: GB")),
        change(
            "supersedes",
            edit(PACK, "jurisdiction: EU", "jurisdiction: EU\nsupersedes: fix-0.9.0")),
        change(
            "a report's leg vocabulary",
            edit(PACK, "legs: [MARKET, CLIENT]", "legs: [MARKET, CLIENT, ALLOCATION]")),
        change(
            "the order of scope_tables",
            edit(
                PACK,
                "scope_tables: [scope/a.yaml, scope/b.yaml]",
                "scope_tables: [scope/b.yaml, scope/a.yaml]")),
        change("a golden value", edit(GOLDEN, "12.25", "12.5")),
        change(
            "the authorship",
            edit(AUTHORSHIP, "A-DEFAULT: \"agent:author\"", "A-DEFAULT: \"agent:other\"")));
  }

  @Test
  void everyEvaluatedFieldChangesTheDigest() {
    Sha256 base = digest(PackFixtures.valid());
    Set<String> seen = new HashSet<>();
    seen.add(base.hex());
    List<String> unchanged = new ArrayList<>();
    List<String> collisions = new ArrayList<>();
    for (Change change : changes()) {
      PackFixtures fixture = PackFixtures.valid();
      for (Edit edit : change.edits()) {
        if (edit.all()) {
          fixture.replaceAll(edit.path(), edit.from(), edit.to());
        } else {
          fixture.replace(edit.path(), edit.from(), edit.to());
        }
      }
      LoadResult result = fixture.load();
      assertThat(result.problems()).as(change.name()).isEmpty();
      Sha256 digest = result.pack().orElseThrow().digest();
      if (digest.equals(base)) {
        unchanged.add(change.name());
      } else if (!seen.add(digest.hex())) {
        collisions.add(change.name());
      }
    }
    assertThat(unchanged).as("changes that left the digest alone").isEmpty();
    assertThat(collisions).as("changes whose digest equals another change's").isEmpty();
  }

  @Test
  void anOpaqueFileIsContent() {
    Sha256 base = digest(PackFixtures.valid());
    Sha256 one = digest(PackFixtures.valid().put("derive/d.yaml", "a: b\n"));
    Sha256 two = digest(PackFixtures.valid().put("derive/d.yaml", "a: c\n"));
    Sha256 moved = digest(PackFixtures.valid().put("derive/e.yaml", "a: b\n"));
    Sha256 pins = digest(PackFixtures.valid().put("interpretations.yaml", "pins: []\n"));
    assertThat(Set.of(base, one, two, moved, pins)).hasSize(5);
  }

  // -- the IR -----------------------------------------------------------------------------------

  @Test
  void digestIgnoresChecksums() {
    LoadedPack pack = load(PackFixtures.valid());
    Map<String, Object> ir = PackIr.toMap(pack);
    assertThat(ir.get("formatVersion")).isEqualTo(1L);
    assertThat(ir.get("packId")).isEqualTo("fix.pack");
    @SuppressWarnings("unchecked")
    Map<String, Object> files = (Map<String, Object>) ir.get("files");
    assertThat(files).doesNotContainKeys("CHECKSUMS", "ATTESTATION");
    // an attestation does not enter the digest either
    PackFixtures attested = PackFixtures.valid().put("ATTESTATION", "anything at all");
    assertThat(digest(attested)).isEqualTo(pack.digest());
    // the digest is the SHA-256 of the canonical IR
    assertThat(PackDigest.of(pack)).isEqualTo(pack.digest());
    assertThat(PackDigest.of(pack)).isEqualTo(Sha256.ofUtf8(Json.canonical(ir)));
  }

  @Test
  void theIrHasOneEntryPerFileExceptChecksumsAndAttestation() {
    PackFixtures fixture =
        PackFixtures.valid()
            .put("derive/d.yaml", "a: b\n")
            .put("lifecycle.yaml", "business_key: [a]\n")
            .put("interpretations.yaml", "pins: []\n")
            .put("ATTESTATION", "x");
    LoadedPack pack = load(fixture);
    @SuppressWarnings("unchecked")
    Map<String, Object> files = (Map<String, Object>) PackIr.toMap(pack).get("files");
    Set<String> expected = new TreeSet<>();
    for (String path : pack.source().paths()) {
      if (!path.equals(PackSource.CHECKSUMS) && !path.equals(PackSource.ATTESTATION)) {
        expected.add(path);
      }
    }
    assertThat(files.keySet()).containsExactlyInAnyOrderElementsOf(expected);
  }

  @Test
  void theIrIsCanonicalJsonReadyAndStable() {
    LoadedPack one = load(PackFixtures.valid());
    LoadedPack two = load(PackFixtures.valid());
    String a = Json.canonical(PackIr.toMap(one));
    assertThat(a).isEqualTo(Json.canonical(PackIr.toMap(two)));
    assertThat(a)
        .startsWith("{\"files\":{")
        .contains("\"formatVersion\":1")
        .contains("\"packId\":\"fix.pack\"");
    // typed rows: ascending by priority, absent optionals omitted, REPORT legs explicit
    assertThat(a).contains("\"id\":\"A-N08-equity\"").contains("\"legs\":[\"MARKET\"]");
    assertThat(a.indexOf("\"id\":\"A-N03-novation\""))
        .isLessThan(a.indexOf("\"id\":\"A-N08-equity\""));
    assertThat(a.indexOf("\"id\":\"A-N08-equity\"")).isLessThan(a.indexOf("\"id\":\"A-DEFAULT\""));
  }

  @Test
  void aReportWithoutLegsGetsTheExplicitVocabulary() {
    LoadedPack pack = load(PackFixtures.valid().replace(PACK, "\n    legs: [MARKET, CLIENT]", ""));
    String ir = Json.canonical(PackIr.toMap(pack));
    assertThat(ir).contains("\"legs\":[\"ALLOCATION\",\"CLIENT\",\"MARKET\"]");
    assertThat(
            digest(
                PackFixtures.valid()
                    .replace(PACK, "legs: [MARKET, CLIENT]", "legs: [MARKET, CLIENT, ALLOCATION]")))
        .isEqualTo(pack.digest());
  }

  @Test
  void digestOfTheFixtureIsPinned() {
    assertThat(digest(PackFixtures.valid()).hex()).isEqualTo(FIXTURE_DIGEST);
  }

  // -- fields that the table above cannot reach -------------------------------------------------

  private static PackFixtures withPointerFiles() {
    return PackFixtures.valid()
        .put("derive/d.yaml", "a: b\n")
        .put("validate/v.yaml", "a: b\n")
        .put("endpoints/e.yaml", "a: b\n")
        .put("lifecycle.yaml", "business_key: [a]\n");
  }

  @Test
  void everyReportPointerChangesTheDigest() {
    // the files are in the pack in every case, so only the pointer of the report differs
    Sha256 base = digest(withPointerFiles());
    Set<String> seen = new HashSet<>();
    seen.add(base.hex());
    for (String pointer :
        List.of(
            "derive_tables: [derive/d.yaml]",
            "validate: [validate/v.yaml]",
            "endpoint_profiles: [endpoints/e.yaml]",
            "lifecycle: lifecycle.yaml")) {
      Sha256 digest =
          digest(
              withPointerFiles()
                  .replace(
                      PACK, "legs: [MARKET, CLIENT]", "legs: [MARKET, CLIENT]\n    " + pointer));
      assertThat(digest).as(pointer).isNotEqualTo(base);
      assertThat(seen.add(digest.hex())).as(pointer + " is distinct from the others").isTrue();
    }
  }

  private static final String TWO_CITES_IN_ORDER =
      "cite: [{source_id: fix-law, provision_path: fix/1, role: BASIS},"
          + " {source_id: fix-law, provision_path: fix/2, role: BASIS}]";
  private static final String TWO_CITES_SWAPPED =
      "cite: [{source_id: fix-law, provision_path: fix/2, role: BASIS},"
          + " {source_id: fix-law, provision_path: fix/1, role: BASIS}]";

  @Test
  void theOrderOfARowsCitationsIsContent() {
    Sha256 inOrder =
        digest(
            PackFixtures.valid()
                .replace(
                    TABLE_A,
                    "cite: [{source_id: fix-law, provision_path: fix/1, role: BASIS}]",
                    TWO_CITES_IN_ORDER));
    Sha256 swapped =
        digest(
            PackFixtures.valid()
                .replace(
                    TABLE_A,
                    "cite: [{source_id: fix-law, provision_path: fix/1, role: BASIS}]",
                    TWO_CITES_SWAPPED));
    assertThat(swapped).isNotEqualTo(inOrder);
  }

  @Test
  void theOrderOfATablesInputsIsNotContentButTheSetIs() {
    Sha256 base = digest(PackFixtures.valid());
    Sha256 two =
        digest(
            PackFixtures.valid()
                .replace(TABLE_A, "inputs: [event.kind]", "inputs: [event.kind, event.tradedAt]"));
    Sha256 reordered =
        digest(
            PackFixtures.valid()
                .replace(TABLE_A, "inputs: [event.kind]", "inputs: [event.tradedAt, event.kind]"));
    assertThat(two).isNotEqualTo(base);
    assertThat(reordered).isEqualTo(two);
  }
}
