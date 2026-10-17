package dev.regpack.engine.pack;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.GeneratedRule;
import dev.regpack.bench.data.TradeRecord;
import dev.regpack.engine.expr.InHouseExpression;
import dev.regpack.engine.expr.Rulings;
import dev.regpack.model.Outcome;
import dev.regpack.model.Sha256;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.model.CanonicalInputSpec;
import dev.regpack.pack.model.LoadResult;
import dev.regpack.pack.model.PackLoader;
import dev.regpack.pack.source.Checksums;
import dev.regpack.pack.source.PackSource;
import dev.regpack.pack.yaml.StrictYaml;
import dev.regpack.ports.expr.ValueType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Test helper: turns a {@link GeneratedPack} of the benchmark generator into a sealed, loadable
 * pack, so that the throughput check runs the production loader, compiler and interpreter on the
 * shape SP-R measured. Everything is synthetic: one invented source, one rationale and one reason
 * per rule, none of it regulatory content.
 *
 * <p>Each table's YAML is written here from a text template, so the helper needs no table writer.
 * The pack declares the 19 variables of {@link TradeRecord#fieldPaths()}, typed as the SP-R spike
 * typed them; {@code client.lei} and {@code trader.id} are optional. The {@code inputs} of a table
 * are the sorted union of the paths of its compiled rows. Every row that is not a catch-all is
 * valid from 2000-01-01, so the record's as-at date (2026-10-09) always lies inside the window.
 */
final class GeneratedPackSource {
  /** The id of the pack's one report, which is the regime of its decisions. */
  static final String REPORT_ID = "syn.pack";

  private static final String SOURCE_ID = "syn-source";
  private static final String RATIONALE = "RAT-0001";
  private static final String VALID_FROM = "2000-01-01";
  private static final String PREFIX = "SYN";

  /** The declaration of each variable, in the order of {@link TradeRecord#fieldPaths()}. */
  private static final Map<String, String> DECLARATIONS = declarations();

  private GeneratedPackSource() {}

  private static Map<String, String> declarations() {
    Map<String, String> d = new LinkedHashMap<>();
    d.put("event.kind", "{type: ENUM, enum: LifecycleKind}");
    d.put("event.tradedAt", "{type: TIMESTAMP}");
    d.put("event.sourceVersion", "{type: INT}");
    d.put("venue.mic", "{type: STRING}");
    d.put("instrument.isin", "{type: STRING}");
    d.put("instrument.cfi", "{type: STRING}");
    d.put("instrument.assetClass", "{type: ENUM, enum: AssetClass}");
    d.put("instrument.tradedOnVenue", "{type: BOOL}");
    d.put("trade.price", "{type: DECIMAL}");
    d.put("trade.quantity", "{type: DECIMAL}");
    d.put("trade.currency", "{type: STRING}");
    d.put("trade.side", "{type: STRING}");
    d.put("trade.capacity", "{type: STRING}");
    d.put("trade.tradeDate", "{type: DATE}");
    d.put("trade.settlementDate", "{type: DATE}");
    d.put("client.kind", "{type: ENUM, enum: PartyKind}");
    d.put("client.lei", "{type: STRING, optional: true}");
    d.put("firm.lei", "{type: STRING}");
    d.put("trader.id", "{type: STRING, optional: true}");
    if (!List.copyOf(d.keySet()).equals(TradeRecord.fieldPaths())) {
      throw new IllegalStateException("the declarations must follow TradeRecord.fieldPaths()");
    }
    return Map.copyOf(d);
  }

  /** The sealed source of the pack. */
  static PackSource source(GeneratedPack generated) {
    Map<String, ValueType> types = types();
    InHouseExpression expression =
        new InHouseExpression(InHouseExpression.Limits.defaults(), Rulings.load());

    Map<String, String> files = new TreeMap<>();
    List<String> tableFiles = new ArrayList<>();
    List<GeneratedRule> all = new ArrayList<>();
    List<List<GeneratedRule>> tables = generated.tables();
    for (int t = 0; t < tables.size(); t++) {
      String file = "scope/t" + pad(t + 1, 2) + ".yaml";
      tableFiles.add(file);
      files.put(file, tableText(tables.get(t), t + 1, expression, types));
      all.addAll(tables.get(t));
    }
    String input = inputText();
    files.put("model/canonical-input.yaml", input);
    files.put("pack.yaml", manifestText(tableFiles, inputSchema(input)));
    files.put("reasons.yaml", reasonsText(all, tables.size()));
    files.put("sources.yaml", sourcesText());
    files.put(
        "rationales.yaml",
        "rationales:\n  - {id: " + RATIONALE + ", text: A synthetic rationale for the check}\n");

    Map<String, byte[]> bytes = new TreeMap<>();
    files.forEach((path, text) -> bytes.put(path, text.getBytes(UTF_8)));
    String checksums = Checksums.render(PackSource.of(bytes));
    bytes.put(PackSource.CHECKSUMS, checksums.getBytes(UTF_8));
    return PackSource.of(bytes);
  }

  /** Runs the production loader on the generated pack. */
  static LoadResult load(GeneratedPack generated) {
    return PackLoader.load(source(generated));
  }

  /** Loads and compiles the generated pack; any problem is an error naming it. */
  static CompiledPack compile(GeneratedPack generated) {
    LoadResult loaded = load(generated);
    if (loaded.pack().isEmpty()) {
      throw new IllegalStateException(
          "the generated pack has problems: " + show(loaded.problems()));
    }
    CompiledPack.CompileResult compiled =
        CompiledPack.compile(loaded.pack().get(), REPORT_ID, Rulings.load());
    if (compiled.pack().isEmpty()) {
      throw new IllegalStateException(
          "the generated pack does not compile: " + show(compiled.problems()));
    }
    return compiled.pack().get();
  }

  private static String show(List<PackProblem> problems) {
    List<String> out = new ArrayList<>();
    for (PackProblem p : problems.subList(0, Math.min(problems.size(), 8))) {
      out.add(p.code() + " " + p.path() + ": " + p.message());
    }
    return out + (problems.size() > 8 ? " (and " + (problems.size() - 8) + " more)" : "");
  }

  private static Map<String, ValueType> types() {
    Map<String, ValueType> types = new TreeMap<>();
    DECLARATIONS.forEach(
        (path, declaration) -> {
          int end = declaration.indexOf(',');
          if (end < 0) {
            end = declaration.indexOf('}');
          }
          types.put(path, ValueType.valueOf(declaration.substring("{type: ".length(), end)));
        });
    return types;
  }

  private static String inputText() {
    StringBuilder text = new StringBuilder("variables:\n");
    for (String path : TradeRecord.fieldPaths()) {
      text.append("  ").append(path).append(": ").append(DECLARATIONS.get(path)).append('\n');
    }
    return text.toString();
  }

  /** The SHA-256 of the typed IR of the input model, which {@code pack.yaml} must carry. */
  private static String inputSchema(String inputText) {
    List<PackProblem> problems = new ArrayList<>();
    CanonicalInputSpec spec =
        CanonicalInputSpec.parse(StrictYaml.parse(inputText), problems)
            .orElseThrow(() -> new IllegalStateException(show(problems)));
    return spec.digest().hex();
  }

  private static String manifestText(List<String> tableFiles, String inputSchema) {
    return "id: "
        + REPORT_ID
        + "\nsemver: 0.1.0\nregime: "
        + REPORT_ID
        + "\njurisdiction: SYN\nvalid: {legal: {from: "
        + VALID_FROM
        + "}}\nas_at_field: event.tradedAt\nengine_min: "
        + EngineInfo.BUILD
        + "\ninput_schema: "
        + inputSchema
        + "\nreports:\n  - id: "
        + REPORT_ID
        + "\n    input: model/canonical-input.yaml\n    scope_tables: ["
        + String.join(", ", tableFiles)
        + "]\n";
  }

  private static String reasonsText(List<GeneratedRule> rules, int tableCount) {
    List<String> nodes = new ArrayList<>();
    for (int t = 1; t <= tableCount; t++) {
      nodes.add(node(t));
    }
    StringBuilder text = new StringBuilder();
    text.append("prefix: ").append(PREFIX).append("\nnodes: [");
    text.append(String.join(", ", nodes)).append("]\nreasons:\n");
    for (GeneratedRule rule : rules) {
      String label = rule.reason().substring(rule.reason().lastIndexOf('.') + 1);
      text.append("  - {code: ")
          .append(rule.reason())
          .append(", label: ")
          .append(label)
          .append(", category: ")
          .append(category(rule.outcome()))
          .append(", default_outcome: ")
          .append(rule.outcome())
          .append(", owner: agent.synthetic, valid: {from: ")
          .append(VALID_FROM)
          .append("}}\n");
    }
    return text.toString();
  }

  private static String category(String outcome) {
    return switch (Outcome.valueOf(outcome)) {
      case REPORT -> "INCLUSION";
      case DO_NOT_REPORT -> "EXCLUSION";
      case REPORT_BY_OTHER -> "OUT_OF_SCOPE";
      case UNDETERMINED -> "DEFAULT";
    };
  }

  private static String sourcesText() {
    String hash = Sha256.ofUtf8(SOURCE_ID + " syn-1").hex();
    return "sources:\n  - source_id: "
        + SOURCE_ID
        + "\n    version: syn-1\n    retrieved_at: 2026-10-17\n    content_sha256: "
        + hash
        + "\n    provisions:\n      - {path: syn/1, role: BASIS, status: IN_FORCE}\n";
  }

  private static String tableText(
      List<GeneratedRule> rules,
      int tableNumber,
      InHouseExpression expression,
      Map<String, ValueType> types) {
    TreeSet<String> inputs = new TreeSet<>();
    for (GeneratedRule rule : rules) {
      inputs.addAll(expression.compile(rule.when(), types).paths());
    }
    String catchAll = rules.getLast().id();
    StringBuilder text = new StringBuilder();
    text.append("table: {id: syn.t")
        .append(pad(tableNumber, 2))
        .append(", hit_policy: FIRST, priority: explicit, inputs: [")
        .append(String.join(", ", inputs))
        .append("], catch_all: ")
        .append(catchAll)
        .append("}\nrows:\n");
    for (GeneratedRule rule : rules) {
      boolean last = rule.id().equals(catchAll);
      text.append("  - id: ").append(rule.id()).append('\n');
      text.append("    node: ").append(node(tableNumber)).append('\n');
      text.append("    priority: ").append(rule.priority()).append('\n');
      text.append("    when: \"").append(escape(rule.when())).append("\"\n");
      text.append("    outcome: ").append(rule.outcome()).append('\n');
      text.append("    reason: ").append(rule.reason()).append('\n');
      text.append("    cite: [{source_id: ")
          .append(SOURCE_ID)
          .append(", provision_path: syn/1, role: BASIS}]\n");
      text.append("    rationale: ").append(RATIONALE).append('\n');
      if (!last) {
        text.append("    valid: {legal: {from: ").append(VALID_FROM).append("}}\n");
      }
    }
    return text.toString();
  }

  private static String node(int tableNumber) {
    return "T" + pad(tableNumber, 2);
  }

  /** The escapes of a double-quoted scalar in PackYaml/1: backslash and double quote. */
  private static String escape(String when) {
    return when.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String pad(int value, int width) {
    String digits = Integer.toString(value);
    StringBuilder out = new StringBuilder(width);
    for (int i = digits.length(); i < width; i++) {
      out.append('0');
    }
    return out.append(digits).toString();
  }
}
