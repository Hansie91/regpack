package dev.regpack.pack.model;

import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import dev.regpack.pack.PackFormat;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.yaml.YamlMap;
import dev.regpack.pack.yaml.YamlNode;
import dev.regpack.pack.yaml.YamlScalar;
import dev.regpack.ports.expr.ValueType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The typed canonical input model of a pack ({@code model/canonical-input.yaml}; companion
 * contracts section 2): the paths an expression may read, each with its type, an enumeration name
 * for ENUM, a source (EVENT, or STATE for the read-only {@code state.*} inputs of BRK-04) and an
 * optional flag. The pack's {@code input_schema} is the SHA-256 of the typed IR of this model
 * ({@link #toIr()}, {@link #digest()}), so a change of any variable changes it.
 *
 * <pre>
 * variables:
 *   event.kind: {type: ENUM, enum: LifecycleKind}
 *   client.lei: {type: STRING, optional: "true"}
 *   state.status: {type: STRING, source: STATE}
 * </pre>
 *
 * <p>Problem codes of {@link #parse}: {@code INPUT_SHAPE}, {@code INPUT_KEY_UNKNOWN}, {@code
 * INPUT_KEY_MISSING}, {@code INPUT_PATH_INVALID}, {@code INPUT_TYPE_UNKNOWN}, {@code
 * INPUT_ENUM_INVALID}, {@code INPUT_SOURCE_INVALID} and {@code INPUT_OPTIONAL_INVALID}.
 *
 * @param variables the declared variables by path, in sorted order
 */
public record CanonicalInputSpec(Map<String, Variable> variables) {
  /** The pack path of the model. */
  public static final String FILE = "model/canonical-input.yaml";

  /**
   * One declared input.
   *
   * @param type the static type
   * @param enumName the enumeration of an ENUM variable, absent otherwise
   * @param source whether the value comes from the event or from ledger state (read-only)
   * @param optional whether the value may be unbound
   */
  public record Variable(
      ValueType type, Optional<String> enumName, Source source, boolean optional) {
    /** Where a variable's value comes from. */
    public enum Source {
      EVENT,
      STATE
    }

    public Variable {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(enumName, "enumName");
      Objects.requireNonNull(source, "source");
    }
  }

  private static final Pattern PATH = Pattern.compile("[a-z][A-Za-z0-9_]*(\\.[a-z][A-Za-z0-9_]*)*");
  private static final Pattern ENUM_NAME = Pattern.compile("[A-Z][A-Za-z0-9]{0,63}");
  private static final int MAX_PATH_CHARS = 128;

  public CanonicalInputSpec {
    variables = Collections.unmodifiableMap(new TreeMap<>(variables));
  }

  /**
   * Reads the model from a parsed file. The problems found are appended to {@code problems} (sorted
   * among themselves, each naming {@link #FILE}); the result is empty when this call found any
   * problem.
   */
  public static Optional<CanonicalInputSpec> parse(YamlNode root, List<PackProblem> problems) {
    Reader reader = new Reader();
    Optional<CanonicalInputSpec> spec = reader.read(root);
    Collections.sort(reader.found);
    problems.addAll(reader.found);
    return reader.found.isEmpty() ? spec : Optional.empty();
  }

  /**
   * The typed IR: {@code {"formatVersion":1,"variables":{path:{type, enum?, source, optional}}}},
   * canonical-JSON-ready (the digest ignores comments and the order of the file).
   */
  public Map<String, Object> toIr() {
    Map<String, Object> vars = new LinkedHashMap<>();
    for (Map.Entry<String, Variable> e : variables.entrySet()) {
      Variable v = e.getValue();
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("type", v.type().name());
      v.enumName().ifPresent(name -> m.put("enum", name));
      m.put("source", v.source().name());
      m.put("optional", v.optional());
      vars.put(e.getKey(), m);
    }
    Map<String, Object> ir = new LinkedHashMap<>();
    ir.put("formatVersion", (long) PackFormat.VERSION);
    ir.put("variables", vars);
    return ir;
  }

  /** The SHA-256 of the canonical JSON of {@link #toIr()}: the value of {@code input_schema}. */
  public Sha256 digest() {
    return Sha256.ofUtf8(Json.canonical(toIr()));
  }

  // ---------------------------------------------------------------------------------------------

  private static final class Reader {
    final List<PackProblem> found = new ArrayList<>();

    private void problem(String code, int line, String message) {
      found.add(new PackProblem(code, FILE, line, message));
    }

    Optional<CanonicalInputSpec> read(YamlNode root) {
      if (!(root instanceof YamlMap top)) {
        problem("INPUT_SHAPE", root.line(), "the input model must be a mapping with 'variables'");
        return Optional.empty();
      }
      for (Map.Entry<String, YamlNode> e : top.entries().entrySet()) {
        if (!e.getKey().equals("variables")) {
          problem(
              "INPUT_KEY_UNKNOWN",
              e.getValue().line(),
              "unknown key '" + PackProblem.excerpt(e.getKey()) + "' in the input model");
        }
      }
      YamlNode node = top.entries().get("variables");
      if (node == null) {
        problem("INPUT_KEY_MISSING", top.line(), "missing key 'variables'");
        return Optional.empty();
      }
      if (!(node instanceof YamlMap vars)) {
        problem("INPUT_SHAPE", node.line(), "variables must be a mapping of path to declaration");
        return Optional.empty();
      }
      Map<String, Variable> out = new TreeMap<>();
      for (Map.Entry<String, YamlNode> e : vars.entries().entrySet()) {
        String path = e.getKey();
        Variable variable = variable(path, e.getValue());
        boolean pathOk = validPath(path, e.getValue().line());
        if (variable != null && pathOk) {
          out.put(path, variable);
        }
      }
      return Optional.of(new CanonicalInputSpec(out));
    }

    private boolean validPath(String path, int line) {
      if (path.length() > MAX_PATH_CHARS || !PATH.matcher(path).matches()) {
        problem(
            "INPUT_PATH_INVALID",
            line,
            "'" + PackProblem.excerpt(path) + "' is not a dotted input path like 'client.lei'");
        return false;
      }
      return true;
    }

    private Variable variable(String path, YamlNode node) {
      if (!(node instanceof YamlMap m)) {
        problem(
            "INPUT_SHAPE",
            node.line(),
            "the declaration of '" + PackProblem.excerpt(path) + "' must be a mapping");
        return null;
      }
      int before = found.size();
      Set<String> known = Set.of("type", "enum", "source", "optional");
      for (Map.Entry<String, YamlNode> e : m.entries().entrySet()) {
        if (!known.contains(e.getKey())) {
          problem(
              "INPUT_KEY_UNKNOWN",
              e.getValue().line(),
              "unknown key '" + PackProblem.excerpt(e.getKey()) + "' in a variable");
        }
      }
      if (!m.entries().containsKey("type")) {
        problem("INPUT_KEY_MISSING", m.line(), "missing key 'type' in a variable");
      }
      ValueType type = type(m.entries().get("type"));
      String enumName = enumName(m.entries().get("enum"), type, m.line());
      Variable.Source source = source(m.entries().get("source"));
      Boolean optional = optional(m.entries().get("optional"));
      if (found.size() > before || type == null) {
        return null;
      }
      return new Variable(
          type,
          Optional.ofNullable(enumName),
          source == null ? Variable.Source.EVENT : source,
          optional != null && optional);
    }

    private String text(YamlNode node, String what) {
      if (node instanceof YamlScalar s) {
        return s.value();
      }
      problem("INPUT_SHAPE", node.line(), what + " must be a single value");
      return null;
    }

    private ValueType type(YamlNode node) {
      if (node == null) {
        return null;
      }
      String value = text(node, "type");
      if (value == null) {
        return null;
      }
      for (ValueType t : ValueType.values()) {
        if (t.name().equals(value)) {
          return t;
        }
      }
      problem(
          "INPUT_TYPE_UNKNOWN",
          node.line(),
          "'"
              + PackProblem.excerpt(value)
              + "' is not a type (BOOL INT DECIMAL STRING TIMESTAMP DATE ENUM LIST)");
      return null;
    }

    private String enumName(YamlNode node, ValueType type, int line) {
      if (node == null) {
        if (type == ValueType.ENUM) {
          problem("INPUT_ENUM_INVALID", line, "an ENUM variable needs an 'enum' name");
        }
        return null;
      }
      String value = text(node, "enum");
      if (value == null) {
        return null;
      }
      if (type != null && type != ValueType.ENUM) {
        problem("INPUT_ENUM_INVALID", node.line(), "'enum' is allowed only on an ENUM variable");
        return null;
      }
      if (!ENUM_NAME.matcher(value).matches()) {
        problem(
            "INPUT_ENUM_INVALID",
            node.line(),
            "'" + PackProblem.excerpt(value) + "' is not an enumeration name like 'LifecycleKind'");
        return null;
      }
      return value;
    }

    private Variable.Source source(YamlNode node) {
      if (node == null) {
        return null;
      }
      String value = text(node, "source");
      if (value == null) {
        return null;
      }
      for (Variable.Source s : Variable.Source.values()) {
        if (s.name().equals(value)) {
          return s;
        }
      }
      problem(
          "INPUT_SOURCE_INVALID",
          node.line(),
          "source must be EVENT or STATE, got '" + PackProblem.excerpt(value) + "'");
      return null;
    }

    private Boolean optional(YamlNode node) {
      if (node == null) {
        return null;
      }
      String value = text(node, "optional");
      if (value == null) {
        return null;
      }
      if (value.equals("true")) {
        return Boolean.TRUE;
      }
      if (value.equals("false")) {
        return Boolean.FALSE;
      }
      problem(
          "INPUT_OPTIONAL_INVALID",
          node.line(),
          "optional must be true or false, got '" + PackProblem.excerpt(value) + "'");
      return null;
    }
  }
}
