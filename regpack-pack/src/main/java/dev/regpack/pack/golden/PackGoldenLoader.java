package dev.regpack.pack.golden;

import dev.regpack.model.json.Json;
import dev.regpack.model.json.JsonException;
import dev.regpack.pack.PackText;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.golden.GoldenCase.ProvenanceKind;
import dev.regpack.pack.model.DecisionTable;
import dev.regpack.pack.model.TableParser;
import dev.regpack.pack.model.TableRow;
import dev.regpack.pack.source.PackSource;
import dev.regpack.pack.yaml.PackSyntaxException;
import dev.regpack.pack.yaml.StrictYaml;
import dev.regpack.pack.yaml.YamlMap;
import dev.regpack.pack.yaml.YamlNode;
import dev.regpack.pack.yaml.YamlScalar;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The strict reader of the golden cases of a pack (plan task T19, review focus PF14, decision
 * D-P6). The lenient {@link GoldenCaseLoader} keeps labelling a case without {@code provenance} as
 * consistency-only, which the skeleton goldens rely on; this loader is deliberately stricter than
 * spec 14.2 O1 for pack goldens: a forgotten provenance fails the pack instead of receiving a quiet
 * label, so the harness enforces the provenance fields and never downgrades a case silently.
 *
 * <p>It reads {@code tests/golden/*.json} and {@code tests/authorship.yaml} of a {@link
 * PackSource}. A file that has any problem contributes no case. Problem codes:
 *
 * <ul>
 *   <li>{@code GOLDEN_NO_PROVENANCE}: the {@code provenance} object is missing;
 *   <li>{@code GOLDEN_CONSISTENCY_WITH_FIELDS}: {@code CONSISTENCY_ONLY} carries {@code source},
 *       {@code reviewer} or {@code reviewedOn};
 *   <li>{@code GOLDEN_NO_SOURCE}, {@code GOLDEN_NO_REVIEWER}, {@code GOLDEN_NO_REVIEW_DATE}: any
 *       other kind lacks one of the three (blank counts as missing);
 *   <li>{@code GOLDEN_ID_FILE_MISMATCH}: the file is not {@code tests/golden/<id>.json};
 *   <li>{@code GOLDEN_ID_DUPLICATE}: an id occurs in an earlier file;
 *   <li>{@code GOLDEN_JSON_INVALID}: malformed JSON (duplicate keys and {@code null} included), a
 *       value of the wrong shape, an unknown key or an unknown enum name;
 *   <li>{@code TEXT_BAD_CHARACTER}: a decoded JSON string, key or value, that {@link PackText}
 *       refuses (control, direction-changing, invisible characters; LF is refused too);
 *   <li>{@code AUTHORSHIP_MISSING_FILE}: a pack with scope tables has no {@code
 *       tests/authorship.yaml}; {@code AUTHORSHIP_SHAPE}: the file is not {@code rules:} with one
 *       non-blank author per rule; {@code AUTHOR_MISSING}: a row id has no author; {@code
 *       AUTHOR_UNKNOWN_RULE}: an author is named for an id that is no row of the pack; plus any
 *       code of the YAML parser for a file that is not PackYaml/1.
 * </ul>
 *
 * Whether a case's {@code node} is declared in {@code reasons.yaml} ({@code
 * GOLDEN_NODE_UNDECLARED}), whether its reviewer is registered and whether author and reviewer are
 * separate from the rule authors are judged by the tester and {@link GoldenAudit}, which need the
 * registry of reviewers. {@code AUTHOR_UNKNOWN_RULE} is not reported when a scope table cannot be
 * parsed (its rows are unknown; the pack loader reports the table).
 *
 * <p>Problem messages never echo untrusted text beyond {@link PackProblem#excerpt(String)}.
 */
public final class PackGoldenLoader {
  /** Where the golden cases of a pack live. */
  public static final String GOLDEN_DIR = "tests/golden/";

  /** The author file of a pack. */
  public static final String AUTHORSHIP = "tests/authorship.yaml";

  private static final String SCOPE_DIR = "scope/";
  private static final String CONSISTENCY_ONLY = ProvenanceKind.CONSISTENCY_ONLY.name();
  private static final Set<String> REAL_KINDS = realKinds();

  private PackGoldenLoader() {}

  /**
   * The result of reading the goldens of a pack.
   *
   * @param cases the valid cases, ascending by file name
   * @param authorByRuleId the author of every rule (row) of the pack, from {@code
   *     tests/authorship.yaml}; empty when the file is absent or unreadable
   * @param problems every problem found, sorted by path, line and code; the pack is acceptable only
   *     when empty
   */
  public record Loaded(
      List<GoldenCase> cases, Map<String, String> authorByRuleId, List<PackProblem> problems) {
    public Loaded {
      cases = List.copyOf(cases);
      authorByRuleId = Collections.unmodifiableMap(new TreeMap<>(authorByRuleId));
      List<PackProblem> sorted = new ArrayList<>(problems);
      Collections.sort(sorted);
      problems = List.copyOf(sorted);
    }
  }

  private static Set<String> realKinds() {
    Set<String> names = new HashSet<>();
    for (ProvenanceKind kind : ProvenanceKind.values()) {
      if (kind != ProvenanceKind.CONSISTENCY_ONLY) {
        names.add(kind.name());
      }
    }
    return Set.copyOf(names);
  }

  /** Reads {@code tests/golden/*.json} and {@code tests/authorship.yaml} of a pack source. */
  public static Loaded load(PackSource source) {
    Objects.requireNonNull(source, "source");
    List<PackProblem> problems = new ArrayList<>();
    List<GoldenCase> cases = new ArrayList<>();
    Set<String> seenIds = new HashSet<>();
    for (String path : source.paths()) {
      if (isGoldenFile(path)) {
        readCase(source, path, seenIds, cases, problems);
      }
    }
    Map<String, String> authors = new TreeMap<>();
    authorship(source, authors, problems);
    return new Loaded(cases, authors, problems);
  }

  private static boolean isGoldenFile(String path) {
    return path.startsWith(GOLDEN_DIR)
        && path.endsWith(".json")
        && path.indexOf('/', GOLDEN_DIR.length()) < 0;
  }

  // ---------------------------------------------------------------------------------------------
  // cases

  private static void readCase(
      PackSource source,
      String path,
      Set<String> seenIds,
      List<GoldenCase> cases,
      List<PackProblem> problems) {
    int before = problems.size();
    String text;
    try {
      text = source.text(path);
    } catch (PackSyntaxException e) {
      problems.add(
          new PackProblem(e.code(), path, e.line(), "the file is not valid: " + e.excerpt()));
      return;
    }
    Object tree;
    try {
      tree = Json.parse(text);
    } catch (JsonException e) {
      problems.add(
          new PackProblem(
              "GOLDEN_JSON_INVALID",
              path,
              0,
              "the golden file is not valid JSON: "
                  + PackProblem.excerpt(String.valueOf(e.getMessage()))));
      return;
    }
    PackText.firstBadInTree(tree)
        .ifPresent(
            bad ->
                problems.add(
                    new PackProblem(
                        "TEXT_BAD_CHARACTER",
                        path,
                        0,
                        "a decoded string contains the refused character U+"
                            + hex(bad.codePoint())
                            + " at index "
                            + bad.index())));
    if (!(tree instanceof Map<?, ?> root)) {
      problems.add(
          new PackProblem("GOLDEN_JSON_INVALID", path, 0, "a golden case must be a JSON object"));
      return;
    }
    checkProvenance(root, path, problems);

    GoldenCase parsed = null;
    try {
      parsed = GoldenCaseLoader.parse(text);
    } catch (RuntimeException e) {
      problems.add(
          new PackProblem(
              "GOLDEN_JSON_INVALID",
              path,
              0,
              "the golden case is invalid: "
                  + PackProblem.excerpt(String.valueOf(e.getMessage()))));
    }
    if (parsed != null) {
      if (!path.equals(GOLDEN_DIR + parsed.id() + ".json")) {
        problems.add(
            new PackProblem(
                "GOLDEN_ID_FILE_MISMATCH",
                path,
                0,
                "the file name must be <id>.json but the id is "
                    + PackProblem.excerpt(parsed.id())));
      }
      if (!seenIds.add(parsed.id())) {
        problems.add(
            new PackProblem(
                "GOLDEN_ID_DUPLICATE",
                path,
                0,
                "the id " + PackProblem.excerpt(parsed.id()) + " is used by an earlier file"));
      }
    }
    if (parsed != null && problems.size() == before) {
      cases.add(parsed);
    }
  }

  /** The provenance rules of D-P6, on the decoded tree so that absence can be told from a value. */
  private static void checkProvenance(Map<?, ?> root, String path, List<PackProblem> problems) {
    if (!root.containsKey("provenance")) {
      problems.add(
          new PackProblem(
              "GOLDEN_NO_PROVENANCE",
              path,
              0,
              "a pack golden case must state its provenance (CONSISTENCY_ONLY while gate G1 is open)"));
      return;
    }
    if (!(root.get("provenance") instanceof Map<?, ?> provenance)
        || !(provenance.get("kind") instanceof String kind)) {
      return; // the case parser reports the wrong shape
    }
    if (kind.equals(CONSISTENCY_ONLY)) {
      if (provenance.containsKey("source")
          || provenance.containsKey("reviewer")
          || provenance.containsKey("reviewedOn")) {
        problems.add(
            new PackProblem(
                "GOLDEN_CONSISTENCY_WITH_FIELDS",
                path,
                0,
                "CONSISTENCY_ONLY must not carry source, reviewer or reviewedOn"));
      }
    } else if (REAL_KINDS.contains(kind)) {
      if (blank(provenance.get("source"))) {
        problems.add(
            new PackProblem("GOLDEN_NO_SOURCE", path, 0, "the provenance names no source"));
      }
      if (blank(provenance.get("reviewer"))) {
        problems.add(
            new PackProblem("GOLDEN_NO_REVIEWER", path, 0, "the provenance names no reviewer"));
      }
      if (blank(provenance.get("reviewedOn"))) {
        problems.add(
            new PackProblem("GOLDEN_NO_REVIEW_DATE", path, 0, "the provenance has no review date"));
      }
    }
    // an unknown kind is reported by the case parser
  }

  private static boolean blank(Object value) {
    return value == null || (value instanceof String text && text.isBlank());
  }

  private static String hex(int codePoint) {
    String digits = Integer.toHexString(codePoint).toUpperCase(Locale.ROOT);
    return digits.length() >= 4 ? digits : "0".repeat(4 - digits.length()) + digits;
  }

  // ---------------------------------------------------------------------------------------------
  // authorship

  private static void authorship(
      PackSource source, Map<String, String> authors, List<PackProblem> problems) {
    Set<String> rowIds = new TreeSet<>();
    boolean anyTable = false;
    boolean tablesComplete = true;
    for (String path : source.paths()) {
      if (path.startsWith(SCOPE_DIR) && path.endsWith(".yaml")) {
        anyTable = true;
        Optional<DecisionTable> table = scopeTable(source, path);
        if (table.isPresent()) {
          for (TableRow row : table.get().rows()) {
            rowIds.add(row.id());
          }
        } else {
          tablesComplete = false;
        }
      }
    }
    if (!source.has(AUTHORSHIP)) {
      if (anyTable) {
        problems.add(
            new PackProblem(
                "AUTHORSHIP_MISSING_FILE",
                AUTHORSHIP,
                0,
                "a pack with scope tables needs tests/authorship.yaml naming the author of every row"));
      }
      return;
    }
    YamlNode root;
    try {
      root = StrictYaml.parse(source.text(AUTHORSHIP));
    } catch (PackSyntaxException e) {
      problems.add(
          new PackProblem(e.code(), AUTHORSHIP, e.line(), "the file is not valid: " + e.excerpt()));
      return;
    }
    Optional<Map<String, YamlNode>> maybeRules = rulesOf(root);
    if (maybeRules.isEmpty()) {
      problems.add(
          new PackProblem(
              "AUTHORSHIP_SHAPE",
              AUTHORSHIP,
              root.line(),
              "the file must be 'rules:' with one author per rule id"));
      return;
    }
    Map<String, YamlNode> rules = maybeRules.get();
    for (Map.Entry<String, YamlNode> entry : rules.entrySet()) {
      if (entry.getValue() instanceof YamlScalar author && !author.value().isBlank()) {
        authors.put(entry.getKey(), author.value());
      } else {
        problems.add(
            new PackProblem(
                "AUTHORSHIP_SHAPE",
                AUTHORSHIP,
                entry.getValue().line(),
                "the author of "
                    + PackProblem.excerpt(entry.getKey())
                    + " must be a non-blank text"));
      }
    }
    for (String rowId : rowIds) {
      if (!rules.containsKey(rowId)) {
        problems.add(
            new PackProblem(
                "AUTHOR_MISSING",
                AUTHORSHIP,
                0,
                "no author is named for the row " + PackProblem.excerpt(rowId)));
      }
    }
    if (tablesComplete) {
      for (Map.Entry<String, YamlNode> entry : rules.entrySet()) {
        if (!rowIds.contains(entry.getKey())) {
          problems.add(
              new PackProblem(
                  "AUTHOR_UNKNOWN_RULE",
                  AUTHORSHIP,
                  entry.getValue().line(),
                  PackProblem.excerpt(entry.getKey()) + " is not a row of any scope table"));
        }
      }
    }
  }

  private static Optional<Map<String, YamlNode>> rulesOf(YamlNode root) {
    if (root instanceof YamlMap map
        && map.entries().size() == 1
        && map.entries().get("rules") instanceof YamlMap rules) {
      return Optional.of(rules.entries());
    }
    return Optional.empty();
  }

  /** The table of a scope file, or empty when it does not parse (the pack loader reports it). */
  private static Optional<DecisionTable> scopeTable(PackSource source, String path) {
    try {
      return TableParser.parse(path, StrictYaml.parse(source.text(path)), new ArrayList<>());
    } catch (PackSyntaxException e) {
      return Optional.empty();
    }
  }
}
