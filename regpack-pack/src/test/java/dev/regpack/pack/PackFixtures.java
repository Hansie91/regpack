package dev.regpack.pack;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.model.Sha256;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.model.CanonicalInputSpec;
import dev.regpack.pack.model.LoadResult;
import dev.regpack.pack.model.PackLoader;
import dev.regpack.pack.source.Checksums;
import dev.regpack.pack.source.PackSource;
import dev.regpack.pack.yaml.PackSyntaxException;
import dev.regpack.pack.yaml.StrictYaml;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A small valid pack made of text blocks, for the loader, digest and reserved-slot tests. It is
 * mutable: a test takes a fresh {@link #valid()} pack, changes files with {@link #put}, {@link
 * #replace} and {@link #remove}, and asks for the sealed {@link #source()} (CHECKSUMS added) or the
 * {@link #unsealed()} one.
 *
 * <p>The pack is synthetic: two scope tables (a: novation exclusion, equity report row and a
 * catch-all; b: natural person and a catch-all), a report with the legs MARKET and CLIENT, an input
 * model with seven variables, registries, an authorship file and one golden file. {@code pack.yaml}
 * carries the token {@link #SCHEMA_TOKEN}, which {@link #bytes()} replaces by the digest of the
 * typed IR of the current input model, so a test that changes a variable type still has a matching
 * {@code input_schema}.
 */
public final class PackFixtures {
  /** Replaced in every file text by the digest of the typed IR of {@code canonical-input.yaml}. */
  public static final String SCHEMA_TOKEN = "@INPUT_SCHEMA@";

  public static final String PACK = "pack.yaml";
  public static final String INPUT = "model/canonical-input.yaml";
  public static final String TABLE_A = "scope/a.yaml";
  public static final String TABLE_B = "scope/b.yaml";
  public static final String REASONS = "reasons.yaml";
  public static final String SOURCES = "sources.yaml";
  public static final String RATIONALES = "rationales.yaml";
  public static final String AUTHORSHIP = "tests/authorship.yaml";
  public static final String GOLDEN = "tests/golden/GC-1.json";

  /** The quote of provision {@code fix/1} and its hash (SHA-256 of the quote as parsed). */
  public static final String QUOTE = "SYNTHETIC FIXTURE TEXT 1, not law";

  public static final String QUOTE_HASH = Sha256.ofUtf8(QUOTE).hex();

  private static final String PACK_YAML =
      """
      id: fix.pack
      semver: 1.0.0
      regime: fix.pack
      jurisdiction: EU
      valid: {legal: {from: 2018-01-03}}
      as_at_field: event.tradedAt
      engine_min: 1.0.0+lib1
      input_schema: @INPUT_SCHEMA@
      limits: {rule_nodes: 600, record_budget: 100000}
      reports:
        - id: fix.pack
          input: model/canonical-input.yaml
          scope_tables: [scope/a.yaml, scope/b.yaml]
          legs: [MARKET, CLIENT]
      """;

  private static final String INPUT_YAML =
      """
      variables:
        event.kind: {type: ENUM, enum: LifecycleKind}
        event.tradedAt: {type: TIMESTAMP}
        event.otherTime: {type: TIMESTAMP}
        client.kind: {type: STRING}
        client.lei: {type: STRING, optional: "true"}
        trade.price: {type: DECIMAL}
        state.status: {type: STRING, source: STATE}
      """;

  private static final String TABLE_A_YAML =
      """
      table: {id: fix.a, hit_policy: FIRST, priority: explicit, inputs: [event.kind], catch_all: A-DEFAULT}
      rows:
        - id: A-N03-novation
          node: N03
          priority: 300
          when: "event.kind in ['NOVATION','ASSIGNMENT']"
          outcome: DO_NOT_REPORT
          reason: FIX.N03.EXCLUDED
          rule_ref: RULE-0001
          cite: [{source_id: fix-law, provision_path: fix/1, role: BASIS}]
          rationale: RAT-0001
          valid: {legal: {from: 2018-01-03}}
          set: {action: NEWT}
        - id: A-N08-equity
          node: N08
          priority: 800
          when: "client.kind == 'LEGAL_ENTITY'"
          outcome: REPORT
          reason: FIX.N08.IN_SCOPE
          cite: [{source_id: fix-law, provision_path: fix/2, role: BASIS}]
          rationale: RAT-0003
          valid: {legal: {from: 2018-01-03}}
          legs: [MARKET]
        - id: A-DEFAULT
          node: DEF
          priority: 9999
          when: "true"
          outcome: UNDETERMINED
          reason: FIX.DEF.NO_RULE_HIT
          cite: [{source_id: regpack-spec, provision_path: I5, role: GUIDANCE}]
          rationale: RAT-0004
      """;

  private static final String TABLE_B_YAML =
      """
      table: {id: fix.b, hit_policy: FIRST, priority: explicit, inputs: [client.kind], catch_all: B-DEFAULT}
      rows:
        - id: B-N13-natural-person
          node: N13
          priority: 400
          when: "client.kind == 'NATURAL_PERSON'"
          outcome: UNDETERMINED
          reason: FIX.N13.NATURAL_PERSON
          rule_ref: RULE-0002
          cite: [{source_id: regpack-spec, provision_path: D14, role: INTERPRETATION}]
          rationale: RAT-0002
          valid: {legal: {from: 2018-01-03}}
        - id: B-DEFAULT
          node: DEF
          priority: 9999
          when: "true"
          outcome: UNDETERMINED
          reason: FIX.DEF.NO_RULE_HIT
          cite: [{source_id: regpack-spec, provision_path: I5, role: GUIDANCE}]
          rationale: RAT-0004
      """;

  private static final String REASONS_YAML =
      """
      prefix: FIX
      nodes: [N03, N08, N13, DEF]
      reasons:
        - {code: FIX.N03.EXCLUDED, label: EXCLUDED, category: EXCLUSION, default_outcome: DO_NOT_REPORT, owner: compliance, valid: {from: 2018-01-03}}
        - {code: FIX.N03.OTHER, label: OTHER, category: EXCLUSION, default_outcome: DO_NOT_REPORT, owner: compliance, valid: {from: 2018-01-03}}
        - {code: FIX.N08.IN_SCOPE, label: IN_SCOPE, category: INCLUSION, default_outcome: REPORT, owner: compliance, valid: {from: 2018-01-03}}
        - {code: FIX.N13.NATURAL_PERSON, label: NATURAL_PERSON, category: OUT_OF_SCOPE, default_outcome: UNDETERMINED, owner: compliance, valid: {from: 2018-01-03}}
        - {code: FIX.DEF.NO_RULE_HIT, label: NO_RULE_HIT, category: DEFAULT, default_outcome: UNDETERMINED, owner: compliance, valid: {from: 2018-01-03}}
      """;

  private static final String SOURCES_YAML =
      """
      sources:
        - source_id: fix-law
          version: fix-1
          retrieved_at: 2026-10-16
          content_sha256: %s
          provisions:
            - path: fix/1
              role: BASIS
              status: IN_FORCE
              quote: "%s"
              quote_sha256: %s
            - {path: fix/2, role: BASIS, status: IN_FORCE}
            - {path: fix/3, role: GUIDANCE, status: PROPOSED}
        - source_id: regpack-spec
          version: "2026-10-08-draft-3"
          retrieved_at: 2026-10-16
          content_sha256: %s
          provisions:
            - {path: D14, role: INTERPRETATION, status: IN_FORCE}
            - {path: I5, role: GUIDANCE, status: IN_FORCE}
      """
          .formatted(
              Sha256.ofUtf8("fix-law fix-1").hex(),
              QUOTE,
              QUOTE_HASH,
              Sha256.ofUtf8("regpack-spec 2026-10-08-draft-3").hex());

  private static final String RATIONALES_YAML =
      """
      rationales:
        - {id: RAT-0001, text: Novations are not new trades}
        - {id: RAT-0002, text: Natural persons need a policy decision}
        - {id: RAT-0003, text: Equity on a venue is in scope}
        - {id: RAT-0004, text: Nothing matched}
      """;

  private static final String AUTHORSHIP_YAML =
      """
      rules:
        A-N03-novation: "agent:author"
        A-N08-equity: "agent:author"
        A-DEFAULT: "agent:author"
        B-N13-natural-person: "agent:author"
        B-DEFAULT: "agent:author"
      """;

  private static final String GOLDEN_JSON =
      "{\"id\":\"GC-1\",\"author\":\"agent:golden\",\"provenance\":{\"kind\":\"CONSISTENCY_ONLY\"},"
          + "\"input\":{\"clientKind\":\"LEGAL_ENTITY\",\"price\":12.25}}\n";

  private final Map<String, String> files = new TreeMap<>();

  private PackFixtures() {}

  /** A fresh copy of the valid fixture pack (without CHECKSUMS). */
  public static PackFixtures valid() {
    PackFixtures f = new PackFixtures();
    f.files.put(PACK, PACK_YAML);
    f.files.put(INPUT, INPUT_YAML);
    f.files.put(TABLE_A, TABLE_A_YAML);
    f.files.put(TABLE_B, TABLE_B_YAML);
    f.files.put(REASONS, REASONS_YAML);
    f.files.put(SOURCES, SOURCES_YAML);
    f.files.put(RATIONALES, RATIONALES_YAML);
    f.files.put(AUTHORSHIP, AUTHORSHIP_YAML);
    f.files.put(GOLDEN, GOLDEN_JSON);
    return f;
  }

  public PackFixtures put(String path, String text) {
    files.put(path, text);
    return this;
  }

  public PackFixtures remove(String path) {
    if (files.remove(path) == null) {
      throw new IllegalArgumentException("the fixture has no file " + path);
    }
    return this;
  }

  /** Replaces the first occurrence of {@code from}, which must occur in the file. */
  public PackFixtures replace(String path, String from, String to) {
    String text = text(path);
    int at = text.indexOf(from);
    if (at < 0) {
      throw new IllegalArgumentException("'" + from + "' does not occur in " + path);
    }
    files.put(path, text.substring(0, at) + to + text.substring(at + from.length()));
    return this;
  }

  /** Replaces every occurrence of {@code from}, which must occur in the file. */
  public PackFixtures replaceAll(String path, String from, String to) {
    String text = text(path);
    if (!text.contains(from)) {
      throw new IllegalArgumentException("'" + from + "' does not occur in " + path);
    }
    files.put(path, text.replace(from, to));
    return this;
  }

  public String text(String path) {
    String text = files.get(path);
    if (text == null) {
      throw new IllegalArgumentException("the fixture has no file " + path);
    }
    return text;
  }

  /** The digest of the typed IR of the current input model (zeros if it does not parse). */
  public String inputSchema() {
    String input = files.get(INPUT);
    if (input != null) {
      try {
        List<PackProblem> problems = new ArrayList<>();
        var spec = CanonicalInputSpec.parse(StrictYaml.parse(input), problems);
        if (spec.isPresent()) {
          return spec.get().digest().hex();
        }
      } catch (PackSyntaxException e) {
        // fall through
      }
    }
    return "0".repeat(64);
  }

  /** The files as bytes (token replaced, no CHECKSUMS). */
  public Map<String, byte[]> bytes() {
    String schema = inputSchema();
    Map<String, byte[]> out = new LinkedHashMap<>();
    files.forEach(
        (path, text) -> out.put(path, text.replace(SCHEMA_TOKEN, schema).getBytes(UTF_8)));
    return out;
  }

  /** The files without CHECKSUMS. */
  public PackSource unsealed() {
    return PackSource.of(bytes());
  }

  /** The files plus a current CHECKSUMS. */
  public PackSource source() {
    Map<String, byte[]> all = bytes();
    all.remove(PackSource.CHECKSUMS);
    String checksums = Checksums.render(PackSource.of(all));
    all.put(PackSource.CHECKSUMS, checksums.getBytes(UTF_8));
    return PackSource.of(all);
  }

  /** Loads the sealed pack. */
  public LoadResult load() {
    return PackLoader.load(source());
  }

  /** The problem codes of a result, in order. */
  public static List<String> codes(LoadResult result) {
    return result.problems().stream().map(PackProblem::code).toList();
  }
}
