package dev.regpack.engine.pack;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.model.LoadResult;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.pack.model.PackLoader;
import dev.regpack.pack.source.PackSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test helper: a variant of the sample pack with other scope rows, a second report, other limits, a
 * different {@code engine_min} or a different canonical input. Everything is generated as text and
 * sealed through {@link SamplePacks#sealed}, so the loader checks the variant like any pack.
 *
 * <p>A table is written from its rules; the catch-all {@code S-DEFAULT-catch-all} is appended after
 * them and the header {@code inputs} is the set of declared paths the rules read, so the variant
 * satisfies the header check of {@link CompiledPack} without further ado.
 */
final class PackBuilder {
  private static final String MAIN_REPORT = "sample.s0";
  private static final String MAIN_FILE = "scope/sample-scope.yaml";
  private static final String PACK = "pack.yaml";
  private static final String INPUT = "model/canonical-input.yaml";
  private static final String SCHEMA_PREFIX = "input_schema: ";
  private static final Pattern QUOTED = Pattern.compile("'[^']*'");
  private static final Pattern EXPECTED = Pattern.compile("expected ([0-9a-f]{64})");

  /** One rule row; the defaults make a REPORT row that cites the first sample provision. */
  record Rule(
      String id,
      String when,
      String node,
      String outcome,
      String reason,
      String validFrom,
      String validTo,
      String set,
      int priority) {
    static Rule of(String id, String when) {
      return new Rule(
          id, when, "N08", "REPORT", "SAMPLE.N08.IN_SCOPE", "2018-01-03", null, null, 0);
    }

    Rule outcome(String node, String outcome, String reason) {
      return new Rule(id, when, node, outcome, reason, validFrom, validTo, set, priority);
    }

    Rule validFrom(String date) {
      return new Rule(id, when, node, outcome, reason, date, validTo, set, priority);
    }

    Rule validTo(String date) {
      return new Rule(id, when, node, outcome, reason, validFrom, date, set, priority);
    }

    /** The text of a flow mapping body, for example {@code action: NEWT}. */
    Rule set(String flowMappingBody) {
      return new Rule(
          id, when, node, outcome, reason, validFrom, validTo, flowMappingBody, priority);
    }

    Rule priority(int value) {
      return new Rule(id, when, node, outcome, reason, validFrom, validTo, set, value);
    }
  }

  private record Table(String file, String id, List<Rule> rules) {}

  private record Report(String id, List<String> files) {}

  private final Map<String, String> overrides = new TreeMap<>();
  private final Map<String, Table> tables = new LinkedHashMap<>();
  private final List<Report> reports = new ArrayList<>();
  private String limits;
  private String engineMin;
  private boolean reportsChanged;

  private PackBuilder() {}

  /** The sample pack as it is. */
  static PackBuilder sample() {
    return new PackBuilder();
  }

  /** Replaces the rows of the main scope table. */
  PackBuilder scope(Rule... rules) {
    tables.put(MAIN_FILE, new Table(MAIN_FILE, "sample.s0.scope", List.of(rules)));
    return this;
  }

  /** Adds a second report with its own scope table; its id is the id of its regime. */
  PackBuilder secondReport(String id, Rule... rules) {
    String file = "scope/" + id + ".yaml";
    tables.put(file, new Table(file, id + ".scope", List.of(rules)));
    reports.add(new Report(id, List.of(file)));
    reportsChanged = true;
    return this;
  }

  /** A pack with two scope tables in the first report, consulted in the given order. */
  PackBuilder chain(List<Rule> first, List<Rule> second) {
    String file = "scope/second-table.yaml";
    tables.put(MAIN_FILE, new Table(MAIN_FILE, "sample.s0.scope", first));
    tables.put(file, new Table(file, "sample.s0.scope2", second));
    reports.add(new Report(MAIN_REPORT, List.of(MAIN_FILE, file)));
    reportsChanged = true;
    return this;
  }

  /** The text after {@code limits: {...}}, for example {@code rule_nodes: 3}. */
  PackBuilder limits(String flowMappingBody) {
    this.limits = flowMappingBody;
    return this;
  }

  PackBuilder engineMin(String text) {
    this.engineMin = text;
    return this;
  }

  /** Replaces or adds the text of any file; null removes it. */
  PackBuilder file(String path, String text) {
    overrides.put(path, text);
    return this;
  }

  /** The sealed source, with {@code input_schema} recomputed when the canonical input changed. */
  PackSource source() {
    Map<String, String> all = new TreeMap<>(overrides);
    String pack = all.getOrDefault(PACK, SamplePacks.files().get(PACK));
    if (engineMin != null) {
      pack = pack.replace("engine_min: 1.0.0+lib1", "engine_min: " + engineMin);
    }
    if (limits != null) {
      pack = pack.replace("input_schema:", "limits: {" + limits + "}\ninput_schema:");
    }
    if (reportsChanged) {
      pack = pack.substring(0, pack.indexOf("reports:")) + reportsText();
    }
    all.put(PACK, pack);
    for (Table table : tables.values()) {
      all.put(table.file(), tableText(table));
    }
    PackSource first = SamplePacks.sealed(all);
    if (!all.containsKey(INPUT)) {
      return first;
    }
    LoadResult result = PackLoader.load(first);
    for (PackProblem problem : result.problems()) {
      Matcher m = EXPECTED.matcher(problem.message());
      if (problem.code().equals("INPUT_SCHEMA_DIGEST_MISMATCH") && m.find()) {
        all.put(PACK, replaceSchema(pack, m.group(1)));
        return SamplePacks.sealed(all);
      }
    }
    return first;
  }

  /** Loads the variant and fails the test on any problem. */
  LoadedPack load() {
    LoadResult result = PackLoader.load(source());
    List<String> found = new ArrayList<>();
    for (PackProblem p : result.problems()) {
      found.add(p.code() + " " + p.path() + ": " + p.message());
    }
    assertThat(found).as("problems of the pack variant").isEmpty();
    return result.pack().orElseThrow();
  }

  private static String replaceSchema(String pack, String hex) {
    int at = pack.indexOf(SCHEMA_PREFIX);
    int end = pack.indexOf('\n', at);
    return pack.substring(0, at) + SCHEMA_PREFIX + hex + pack.substring(end);
  }

  private String reportsText() {
    List<Report> all = new ArrayList<>();
    boolean mainListed = reports.stream().anyMatch(r -> r.id().equals(MAIN_REPORT));
    if (!mainListed) {
      all.add(new Report(MAIN_REPORT, List.of(MAIN_FILE)));
    }
    all.addAll(reports);
    StringBuilder text = new StringBuilder("reports:\n");
    for (Report report : all) {
      text.append("  - id: ").append(report.id()).append('\n');
      text.append("    input: model/canonical-input.yaml\n");
      text.append("    scope_tables: [").append(String.join(", ", report.files())).append("]\n");
    }
    return text.toString();
  }

  /**
   * The sample's own id for the main table, a derived id for any other (row ids are pack-unique).
   */
  private static String catchAllId(Table table) {
    return table.file().equals(MAIN_FILE) ? "S-DEFAULT-catch-all" : table.id() + "-catch-all";
  }

  private static String tableText(Table table) {
    TreeSet<String> inputs = new TreeSet<>();
    for (Rule rule : table.rules()) {
      String bare = QUOTED.matcher(rule.when()).replaceAll("");
      for (String path : CanonicalBindings.VARIABLES.keySet()) {
        if (bare.contains(path)) {
          inputs.add(path);
        }
      }
    }
    StringBuilder text = new StringBuilder();
    text.append("table: {id: ")
        .append(table.id())
        .append(", hit_policy: FIRST, priority: explicit, inputs: [")
        .append(String.join(", ", inputs))
        .append("], catch_all: ")
        .append(catchAllId(table))
        .append("}\nrows:\n");
    int next = 100;
    for (Rule rule : table.rules()) {
      int priority = rule.priority() > 0 ? rule.priority() : next;
      next = priority + 100;
      text.append("  - id: ").append(rule.id()).append('\n');
      text.append("    node: ").append(rule.node()).append('\n');
      text.append("    priority: ").append(priority).append('\n');
      text.append("    when: \"").append(rule.when()).append("\"\n");
      text.append("    outcome: ").append(rule.outcome()).append('\n');
      text.append("    reason: ").append(rule.reason()).append('\n');
      text.append("    cite: [{source_id: sample-law, provision_path: sample/2, role: BASIS}]\n");
      text.append("    rationale: RAT-0003\n");
      text.append("    valid: {legal: {from: ").append(rule.validFrom());
      if (rule.validTo() != null) {
        text.append(", to: ").append(rule.validTo());
      }
      text.append("}}\n");
      if (rule.set() != null) {
        text.append("    set: {").append(rule.set()).append("}\n");
      }
    }
    text.append("  - id: ").append(catchAllId(table)).append('\n');
    text.append("    node: DEFAULT\n");
    text.append("    priority: 9999\n");
    text.append("    when: \"true\"\n");
    text.append("    outcome: UNDETERMINED\n");
    text.append("    reason: SAMPLE.DEFAULT.NO_RULE_HIT\n");
    text.append("    cite: [{source_id: regpack-spec, provision_path: I5, role: GUIDANCE}]\n");
    text.append("    rationale: RAT-0004\n");
    return text.toString();
  }
}
