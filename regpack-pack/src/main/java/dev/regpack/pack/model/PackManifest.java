package dev.regpack.pack.model;

import dev.regpack.model.EngineBuild;
import dev.regpack.model.Leg;
import dev.regpack.model.Regime;
import dev.regpack.pack.PackFormat;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.check.ProblemSink;
import dev.regpack.pack.source.PackSource;
import dev.regpack.pack.yaml.PackSyntaxException;
import dev.regpack.pack.yaml.StrictYaml;
import dev.regpack.pack.yaml.YamlList;
import dev.regpack.pack.yaml.YamlMap;
import dev.regpack.pack.yaml.YamlNode;
import dev.regpack.pack.yaml.YamlScalar;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The typed {@code pack.yaml} (companion contracts section 2; spec 6.1 with the SP-G wordings).
 * Every format choice is provisional until a G-S3b pass.
 *
 * <p>Problem codes of {@link #parse}: {@code MANIFEST_MISSING}, {@code MANIFEST_SHAPE}, {@code
 * MANIFEST_KEY_UNKNOWN}, {@code MANIFEST_KEY_MISSING}, {@code REGIME_ID_INVALID}, {@code
 * SEMVER_INVALID}, {@code JURISDICTION_INVALID}, {@code DATE_INVALID}, {@code VALID_RANGE_INVALID},
 * {@code VALIDITY_WINDOW_RESERVED}, {@code AS_AT_FIELD_INVALID}, {@code ENGINE_MIN_INVALID}, {@code
 * INPUT_SCHEMA_INVALID}, {@code SUPERSEDES_INVALID}, {@code LIMITS_OUT_OF_RANGE}, {@code
 * IMPORTS_RESERVED}, {@code REPORTS_EMPTY}, {@code REPORT_ID_DUPLICATE}, {@code
 * REPORT_KEY_RESERVED}, {@code REPORT_SCOPE_TABLES_EMPTY}, {@code REPORT_PATH_INVALID}, {@code
 * LEG_VOCABULARY_RESERVED}, {@code LEG_DUPLICATE}, {@code REPORT_LEGS_EMPTY}, and the code of a
 * {@link PackSyntaxException} (for example {@code YAML_TAB}, {@code PACK_BOM}).
 *
 * @param id the pack id, of the {@link Regime} shape
 * @param version the pack version
 * @param regime the regime family, of the {@link Regime} shape
 * @param jurisdiction upper-case ASCII, 2 to 8 characters
 * @param validFrom first UTC date of the legal validity window (inclusive)
 * @param validTo end of the legal validity window (exclusive), if any
 * @param asAtField path of the input timestamp that selects the pack version
 * @param engineMin the oldest engine build that may run the pack
 * @param inputSchema SHA-256 of the typed IR of {@code model/canonical-input.yaml}
 * @param supersedes the pack this one supersedes, if any
 * @param limits expression limits
 * @param reports the report definitions, at least one
 */
public record PackManifest(
    String id,
    SemVer version,
    String regime,
    String jurisdiction,
    LocalDate validFrom,
    Optional<LocalDate> validTo,
    String asAtField,
    EngineBuild engineMin,
    String inputSchema,
    Optional<String> supersedes,
    PackLimits limits,
    List<ReportDefinition> reports) {
  /** The manifest's file name. */
  public static final String FILE = "pack.yaml";

  public PackManifest {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(regime, "regime");
    Objects.requireNonNull(jurisdiction, "jurisdiction");
    Objects.requireNonNull(validFrom, "validFrom");
    Objects.requireNonNull(validTo, "validTo");
    Objects.requireNonNull(asAtField, "asAtField");
    Objects.requireNonNull(engineMin, "engineMin");
    Objects.requireNonNull(inputSchema, "inputSchema");
    Objects.requireNonNull(supersedes, "supersedes");
    Objects.requireNonNull(limits, "limits");
    reports = List.copyOf(reports);
    if (reports.isEmpty()) {
      throw new IllegalArgumentException("a pack needs at least one report");
    }
  }

  /**
   * Reads {@code pack.yaml} of a source. The problems found are appended to {@code problems}
   * (sorted among themselves); the result is empty when this call found any problem.
   */
  public static Optional<PackManifest> parse(PackSource source, List<PackProblem> problems) {
    Reader reader = new Reader();
    Optional<PackManifest> manifest = reader.read(source);
    problems.addAll(reader.found.sorted(FILE));
    return reader.found.isEmpty() ? manifest : Optional.empty();
  }

  // ---------------------------------------------------------------------------------------------

  private static final class Reader {
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern JURISDICTION = Pattern.compile("[A-Z][A-Z0-9]{1,7}");
    private static final Pattern AS_AT =
        Pattern.compile("[a-z][A-Za-z0-9_]*(\\.[a-z][A-Za-z0-9_]*)*");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SUPERSEDES = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");
    private static final Pattern LIMIT = Pattern.compile("[1-9][0-9]{0,17}");
    private static final int MAX_ID_CHARS = 128;

    final ProblemSink found = ProblemSink.forFile();

    private void problem(String code, int line, String message) {
      found.add(new PackProblem(code, FILE, line, message));
    }

    Optional<PackManifest> read(PackSource source) {
      if (!source.has(FILE)) {
        problem("MANIFEST_MISSING", 0, "the pack has no pack.yaml");
        return Optional.empty();
      }
      YamlNode root;
      try {
        root = StrictYaml.parse(source.text(FILE));
      } catch (PackSyntaxException e) {
        problem(e.code(), e.line(), "pack.yaml is not valid: " + e.excerpt());
        return Optional.empty();
      }
      YamlMap top = map(root, "pack.yaml");
      if (top == null) {
        return Optional.empty();
      }
      checkKeys(
          top,
          "pack.yaml",
          Set.of(
              "id",
              "semver",
              "regime",
              "jurisdiction",
              "valid",
              "as_at_field",
              "engine_min",
              "input_schema",
              "reports"),
          Set.of("supersedes", "limits", "imports"));

      String id = regimeId(top, "id");
      SemVer version = semver(top);
      String regime = regimeId(top, "regime");
      String jurisdiction = jurisdiction(top);
      LocalDate[] window = validWindow(top); // [from, to or null]
      String asAt = asAtField(top);
      EngineBuild engineMin = engineMin(top);
      String inputSchema = inputSchema(top);
      Optional<String> supersedes = supersedes(top);
      PackLimits limits = limits(top);
      imports(top);
      List<ReportDefinition> reports = reports(top);

      if (!found.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(
          new PackManifest(
              id,
              version,
              regime,
              jurisdiction,
              window[0],
              Optional.ofNullable(window[1]),
              asAt,
              engineMin,
              inputSchema,
              supersedes,
              limits,
              reports));
    }

    // -- generic helpers --------------------------------------------------------------------

    private YamlMap map(YamlNode node, String what) {
      if (node instanceof YamlMap m) {
        return m;
      }
      problem("MANIFEST_SHAPE", node.line(), what + " must be a mapping");
      return null;
    }

    private YamlList list(YamlNode node, String what) {
      if (node instanceof YamlList l) {
        return l;
      }
      problem("MANIFEST_SHAPE", node.line(), what + " must be a list");
      return null;
    }

    private YamlScalar scalar(YamlNode node, String what) {
      if (node instanceof YamlScalar s) {
        return s;
      }
      problem("MANIFEST_SHAPE", node.line(), what + " must be a single value");
      return null;
    }

    private void checkKeys(YamlMap m, String where, Set<String> required, Set<String> optional) {
      for (Map.Entry<String, YamlNode> e : m.entries().entrySet()) {
        if (found.truncated()) {
          break;
        }
        String key = e.getKey();
        if (required.contains(key) || optional.contains(key)) {
          continue;
        }
        if (where.equals("report") && key.equals("schema")) {
          problem(
              "REPORT_KEY_RESERVED",
              e.getValue().line(),
              "'schema' is reserved: report schemas need the XSD (P5, P8; gate G3)");
        } else {
          problem(
              "MANIFEST_KEY_UNKNOWN",
              e.getValue().line(),
              "unknown key '" + PackProblem.excerpt(key) + "' in " + where);
        }
      }
      List<String> missing = new ArrayList<>(required);
      Collections.sort(missing);
      for (String key : missing) {
        if (!m.entries().containsKey(key)) {
          problem("MANIFEST_KEY_MISSING", m.line(), "missing key '" + key + "' in " + where);
        }
      }
    }

    /** The scalar value of a key, if the key is present and a scalar. */
    private String text(YamlMap m, String key, String where) {
      YamlNode node = m.entries().get(key);
      if (node == null) {
        return null;
      }
      YamlScalar s = scalar(node, where + " " + key);
      return s == null ? null : s.value();
    }

    private int lineOf(YamlMap m, String key) {
      YamlNode node = m.entries().get(key);
      return node == null ? m.line() : node.line();
    }

    // -- fields ------------------------------------------------------------------------------

    private boolean isRegimeShape(String value) {
      if (value.length() > MAX_ID_CHARS) {
        return false;
      }
      try {
        new Regime(value);
        return true;
      } catch (IllegalArgumentException e) {
        return false;
      }
    }

    private String regimeId(YamlMap m, String key) {
      String value = text(m, key, "pack.yaml");
      if (value == null) {
        return null;
      }
      if (!isRegimeShape(value)) {
        problem(
            "REGIME_ID_INVALID",
            lineOf(m, key),
            key
                + " must be lower-case dotted like 'mifir.eu.rts22', got '"
                + PackProblem.excerpt(value)
                + "'");
        return null;
      }
      return value;
    }

    private SemVer semver(YamlMap m) {
      String value = text(m, "semver", "pack.yaml");
      if (value == null) {
        return null;
      }
      try {
        return SemVer.parse(value);
      } catch (IllegalArgumentException e) {
        problem(
            "SEMVER_INVALID",
            lineOf(m, "semver"),
            "semver must be plain major.minor.patch, got '" + PackProblem.excerpt(value) + "'");
        return null;
      }
    }

    private String jurisdiction(YamlMap m) {
      String value = text(m, "jurisdiction", "pack.yaml");
      if (value == null) {
        return null;
      }
      if (!JURISDICTION.matcher(value).matches()) {
        problem(
            "JURISDICTION_INVALID",
            lineOf(m, "jurisdiction"),
            "jurisdiction must be 2 to 8 upper-case ASCII characters, got '"
                + PackProblem.excerpt(value)
                + "'");
        return null;
      }
      return value;
    }

    private LocalDate date(YamlNode node, String what) {
      YamlScalar s = scalar(node, what);
      if (s == null) {
        return null;
      }
      if (DATE.matcher(s.value()).matches()) {
        try {
          return LocalDate.parse(s.value());
        } catch (DateTimeParseException e) {
          // reported below
        }
      }
      problem(
          "DATE_INVALID",
          node.line(),
          what + " must be a date yyyy-mm-dd, got '" + PackProblem.excerpt(s.value()) + "'");
      return null;
    }

    /** Returns {from, to}; {@code to} is null when absent; both null on a problem. */
    private LocalDate[] validWindow(YamlMap top) {
      LocalDate[] window = new LocalDate[2];
      YamlNode node = top.entries().get("valid");
      if (node == null) {
        return window;
      }
      YamlMap valid = map(node, "valid");
      if (valid == null) {
        return window;
      }
      boolean reserved = false;
      for (Map.Entry<String, YamlNode> e : valid.entries().entrySet()) {
        if (found.truncated()) {
          break;
        }
        if (!e.getKey().equals("legal")) {
          reserved = true;
          problem(
              "VALIDITY_WINDOW_RESERVED",
              e.getValue().line(),
              "validity window '"
                  + PackProblem.excerpt(e.getKey())
                  + "' is reserved; release 1 implements 'legal' only (RSV-6)");
        }
      }
      YamlNode legalNode = valid.entries().get("legal");
      if (legalNode == null) {
        if (!reserved) {
          problem("MANIFEST_KEY_MISSING", valid.line(), "missing key 'legal' in valid");
        }
        return window;
      }
      YamlMap legal = map(legalNode, "valid.legal");
      if (legal == null) {
        return window;
      }
      checkKeys(legal, "valid.legal", Set.of("from"), Set.of("to"));
      LocalDate from =
          legal.entries().containsKey("from")
              ? date(legal.entries().get("from"), "valid.legal.from")
              : null;
      LocalDate to =
          legal.entries().containsKey("to")
              ? date(legal.entries().get("to"), "valid.legal.to")
              : null;
      if (from != null && to != null && !to.isAfter(from)) {
        problem(
            "VALID_RANGE_INVALID",
            legalNode.line(),
            "valid.legal.to must be after valid.legal.from (the window is [from, to))");
        return window;
      }
      window[0] = from;
      window[1] = to;
      return window;
    }

    private String asAtField(YamlMap m) {
      String value = text(m, "as_at_field", "pack.yaml");
      if (value == null) {
        return null;
      }
      if (value.length() > MAX_ID_CHARS || !AS_AT.matcher(value).matches()) {
        problem(
            "AS_AT_FIELD_INVALID",
            lineOf(m, "as_at_field"),
            "as_at_field must be a dotted input path, got '" + PackProblem.excerpt(value) + "'");
        return null;
      }
      return value;
    }

    private EngineBuild engineMin(YamlMap m) {
      String value = text(m, "engine_min", "pack.yaml");
      if (value == null) {
        return null;
      }
      try {
        return EngineBuild.parse(value);
      } catch (IllegalArgumentException e) {
        problem(
            "ENGINE_MIN_INVALID",
            lineOf(m, "engine_min"),
            "engine_min must look like 1.0.0+lib1, got '" + PackProblem.excerpt(value) + "'");
        return null;
      }
    }

    private String inputSchema(YamlMap m) {
      String value = text(m, "input_schema", "pack.yaml");
      if (value == null) {
        return null;
      }
      if (!HEX64.matcher(value).matches()) {
        problem(
            "INPUT_SCHEMA_INVALID",
            lineOf(m, "input_schema"),
            "input_schema must be 64 lower-case hex digits");
        return null;
      }
      return value;
    }

    private Optional<String> supersedes(YamlMap m) {
      String value = text(m, "supersedes", "pack.yaml");
      if (value == null) {
        return Optional.empty();
      }
      if (!SUPERSEDES.matcher(value).matches()) {
        problem(
            "SUPERSEDES_INVALID",
            lineOf(m, "supersedes"),
            "supersedes is not a pack reference: '" + PackProblem.excerpt(value) + "'");
        return Optional.empty();
      }
      return Optional.of(value);
    }

    private PackLimits limits(YamlMap top) {
      YamlNode node = top.entries().get("limits");
      PackLimits defaults = PackLimits.defaults();
      if (node == null) {
        return defaults;
      }
      YamlMap m = map(node, "limits");
      if (m == null) {
        return defaults;
      }
      checkKeys(
          m,
          "limits",
          Set.of(),
          Set.of("rule_nodes", "list_items", "string_code_points", "record_budget"));
      int ruleNodes = limit(m, "rule_nodes", PackFormat.MAX_RULE_NODES, defaults.ruleNodes());
      int listItems = limit(m, "list_items", PackFormat.MAX_LIST_ITEMS, defaults.listItems());
      int strings =
          limit(
              m,
              "string_code_points",
              PackFormat.MAX_STRING_CODE_POINTS,
              defaults.stringCodePoints());
      long budget = limit(m, "record_budget", PackFormat.MAX_RECORD_BUDGET, 0L);
      return new PackLimits(
          ruleNodes,
          listItems,
          strings,
          m.entries().containsKey("record_budget") && budget > 0
              ? OptionalLong.of(budget)
              : OptionalLong.empty());
    }

    private int limit(YamlMap m, String key, int cap, int fallback) {
      return (int) limit(m, key, (long) cap, (long) fallback);
    }

    private long limit(YamlMap m, String key, long cap, long fallback) {
      String value = text(m, key, "limits");
      if (value == null) {
        return fallback;
      }
      if (LIMIT.matcher(value).matches()) {
        long parsed = Long.parseLong(value);
        if (parsed <= cap) {
          return parsed;
        }
      }
      problem(
          "LIMITS_OUT_OF_RANGE",
          lineOf(m, key),
          "limit " + key + " must be a decimal integer from 1 to " + cap);
      return fallback;
    }

    private void imports(YamlMap top) {
      YamlNode node = top.entries().get("imports");
      if (node == null) {
        return;
      }
      if (!(node instanceof YamlList l) || !l.items().isEmpty()) {
        problem(
            "IMPORTS_RESERVED",
            node.line(),
            "imports are reserved and accepted only as an empty list (BRK-13, RSV-6)");
      }
    }

    // -- reports -----------------------------------------------------------------------------

    private List<ReportDefinition> reports(YamlMap top) {
      List<ReportDefinition> result = new ArrayList<>();
      YamlNode node = top.entries().get("reports");
      if (node == null) {
        return result;
      }
      YamlList list = list(node, "reports");
      if (list == null) {
        return result;
      }
      if (list.items().isEmpty()) {
        problem("REPORTS_EMPTY", node.line(), "a pack needs at least one report");
        return result;
      }
      Set<String> seen = new HashSet<>();
      for (YamlNode item : list.items()) {
        if (found.truncated()) {
          break;
        }
        YamlMap m = map(item, "a report");
        if (m == null) {
          continue;
        }
        ReportDefinition def = report(m, seen);
        if (def != null) {
          result.add(def);
        }
      }
      return result;
    }

    private ReportDefinition report(YamlMap m, Set<String> seenIds) {
      int before = found.count();
      checkKeys(
          m,
          "report",
          Set.of("id", "input", "scope_tables"),
          Set.of("derive_tables", "validate", "lifecycle", "endpoint_profiles", "legs"));
      String id = regimeId(m, "id");
      if (id != null && !seenIds.add(id)) {
        problem("REPORT_ID_DUPLICATE", lineOf(m, "id"), "report id '" + id + "' is used twice");
      }
      String input = path(m, "input");
      List<String> scope = paths(m, "scope_tables");
      if (m.entries().get("scope_tables") instanceof YamlList written
          && written.items().isEmpty()) {
        problem(
            "REPORT_SCOPE_TABLES_EMPTY",
            lineOf(m, "scope_tables"),
            "a report needs at least one scope table (I5)");
      }
      List<String> derive = paths(m, "derive_tables");
      List<String> validate = paths(m, "validate");
      String lifecycle = path(m, "lifecycle");
      List<String> endpoints = paths(m, "endpoint_profiles");
      List<Leg> legs = legs(m);
      if (found.count() > before || id == null || input == null || scope == null || legs == null) {
        return null;
      }
      return new ReportDefinition(
          id,
          input,
          scope,
          derive == null ? List.of() : derive,
          validate == null ? List.of() : validate,
          Optional.ofNullable(lifecycle),
          endpoints == null ? List.of() : endpoints,
          legs);
    }

    private String path(YamlMap m, String key) {
      String value = text(m, key, "report");
      if (value == null) {
        return null;
      }
      if (!PackSource.isValidPath(value)) {
        problem(
            "REPORT_PATH_INVALID",
            lineOf(m, key),
            key + " is not a valid pack file path: '" + PackProblem.excerpt(value) + "'");
        return null;
      }
      return value;
    }

    private List<String> paths(YamlMap m, String key) {
      YamlNode node = m.entries().get(key);
      if (node == null) {
        return key.equals("scope_tables") ? null : List.of();
      }
      YamlList list = list(node, "report " + key);
      if (list == null) {
        return null;
      }
      List<String> out = new ArrayList<>();
      for (YamlNode item : list.items()) {
        if (found.truncated()) {
          break;
        }
        YamlScalar s = scalar(item, "an item of " + key);
        if (s == null) {
          continue;
        }
        if (!PackSource.isValidPath(s.value())) {
          problem(
              "REPORT_PATH_INVALID",
              item.line(),
              key + " names an invalid pack file path: '" + PackProblem.excerpt(s.value()) + "'");
          continue;
        }
        out.add(s.value());
      }
      return out;
    }

    private List<Leg> legs(YamlMap m) {
      YamlNode node = m.entries().get("legs");
      if (node == null) {
        return List.of(Leg.MARKET, Leg.CLIENT, Leg.ALLOCATION);
      }
      YamlList list = list(node, "report legs");
      if (list == null) {
        return null;
      }
      if (list.items().isEmpty()) {
        problem("REPORT_LEGS_EMPTY", node.line(), "a report needs at least one leg");
        return null;
      }
      EnumSet<Leg> seen = EnumSet.noneOf(Leg.class);
      List<Leg> out = new ArrayList<>();
      Map<String, Leg> vocabulary = new LinkedHashMap<>();
      for (Leg leg : Leg.values()) {
        vocabulary.put(leg.name(), leg);
      }
      for (YamlNode item : list.items()) {
        if (found.truncated()) {
          break;
        }
        YamlScalar s = scalar(item, "a leg");
        if (s == null) {
          continue;
        }
        Leg leg = vocabulary.get(s.value());
        if (leg == null) {
          problem(
              "LEG_VOCABULARY_RESERVED",
              item.line(),
              "leg '"
                  + PackProblem.excerpt(s.value())
                  + "' is outside the closed vocabulary MARKET, CLIENT, ALLOCATION (BRK-12)");
        } else if (!seen.add(leg)) {
          problem("LEG_DUPLICATE", item.line(), "leg " + leg + " is listed twice");
        } else {
          out.add(leg);
        }
      }
      return out;
    }
  }
}
