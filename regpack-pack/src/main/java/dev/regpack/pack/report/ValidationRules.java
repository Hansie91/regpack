package dev.regpack.pack.report;

import dev.regpack.pack.PackText;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.check.ProblemSink;
import dev.regpack.pack.yaml.YamlList;
import dev.regpack.pack.yaml.YamlMap;
import dev.regpack.pack.yaml.YamlNode;
import dev.regpack.pack.yaml.YamlScalar;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The validation rules of a report (plan W1a, task T9, spec 8.1; the file {@code
 * validate/rules.yaml} of a pack): an ordered list of rules, each in one of five layers, with a
 * severity, a disposition, one check of a closed vocabulary and its arguments. The reader checks
 * the shape, the closed vocabularies and the arguments of each check; it does not know the
 * canonical model or the reference datasets of a deployment (the validator checks the field names
 * against the model when it is built).
 *
 * <pre>
 * rules:
 *   - {id: V01, layer: L1, severity: BLOCKER, disposition: QUARANTINE, check: DECIMAL_RANGE,
 *      args: {fields: [trade.quantity], min_exclusive: 0, max_fraction_digits: 4},
 *      regulator_id: null}
 * </pre>
 *
 * <p>A BLOCKER rule has the disposition QUARANTINE: a failing record is quarantined. A WARNING or
 * INFO rule has the disposition PASS: a failing record is flagged and goes on. The reader refuses
 * any other pairing, so a severity never says one thing and a disposition another. {@code
 * regulator_id} is the identifier of the regulator's own rule that this rule mirrors, or {@code
 * null}; it is never read by the validator.
 *
 * <p>Problem codes of {@link #parse}: {@code VALIDATE_SHAPE}, {@code VALIDATE_KEY_UNKNOWN}, {@code
 * VALIDATE_KEY_MISSING}, {@code VALIDATE_RULES_EMPTY}, {@code VALIDATE_RULE_ID_INVALID}, {@code
 * VALIDATE_RULE_ID_DUPLICATE}, {@code VALIDATE_LAYER_UNKNOWN}, {@code VALIDATE_SEVERITY_UNKNOWN},
 * {@code VALIDATE_DISPOSITION_UNKNOWN}, {@code VALIDATE_DISPOSITION_MISMATCH}, {@code
 * VALIDATE_CHECK_UNKNOWN}, {@code VALIDATE_ARGS_UNKNOWN}, {@code VALIDATE_ARGS_MISSING}, {@code
 * VALIDATE_ARGS_INVALID}, {@code VALIDATE_REGULATOR_ID_INVALID} and {@code VALIDATE_BAD_CHARACTER}.
 *
 * @param path the pack path of the file, kept in every problem
 * @param rules the rules in file order, at least one
 */
public record ValidationRules(String path, List<Rule> rules) {
  /** The five validation layers of spec 8.1, in the order they run. */
  public enum Layer {
    L1,
    L2,
    L3,
    L4,
    L5
  }

  /** How serious a failing rule is. */
  public enum Severity {
    BLOCKER,
    WARNING,
    INFO
  }

  /** What happens to a record whose rule fails. */
  public enum Disposition {
    /** The record goes on, with the finding. */
    PASS,
    /** The record is quarantined. */
    QUARANTINE
  }

  /** The kind of value an argument holds. */
  private enum ArgKind {
    /** A list of field names: a canonical path, or {@code report.} and a derived field. */
    FIELDS(true, Pattern.compile("[a-z][A-Za-z0-9_]{0,63}(\\.[a-z][A-Za-z0-9_]{0,63}){0,3}")),
    /** A list of upper-case words. */
    WORDS(true, Pattern.compile("[A-Z][A-Z0-9_]{0,31}")),
    /** One name. */
    NAME(false, Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}")),
    /** One non-negative plain decimal. */
    DECIMAL(false, Pattern.compile("[0-9]{1,18}(\\.[0-9]{1,18})?")),
    /** One whole number from 0 to 99. */
    COUNT(false, Pattern.compile("[0-9]{1,2}"));

    private final boolean list;
    private final Pattern shape;

    ArgKind(boolean list, Pattern shape) {
      this.list = list;
      this.shape = shape;
    }
  }

  /**
   * The checks the validator knows (a closed vocabulary; the validator holds the code of each).
   * Each check lists the arguments it takes and which are required. Checks that read fields apply
   * to every named field that is present, and a field that is absent passes.
   */
  public enum Check {
    /**
     * A decimal field: strictly above {@code min_exclusive}, at most {@code max_integer_digits}
     * digits before and {@code max_fraction_digits} after the point (zeros at the end do not
     * count).
     */
    DECIMAL_RANGE(
        args("fields", ArgKind.FIELDS)
            .and("min_exclusive", ArgKind.DECIMAL)
            .and("max_integer_digits", ArgKind.COUNT)
            .and("max_fraction_digits", ArgKind.COUNT),
        Set.of("fields")),
    /** A legal entity identifier: its shape and its check digits. */
    LEI(args("fields", ArgKind.FIELDS), Set.of("fields")),
    /** A securities identifier: its shape and its check digit. */
    ISIN(args("fields", ArgKind.FIELDS), Set.of("fields")),
    /** The shape of a market identifier code. */
    MIC_SHAPE(args("fields", ArgKind.FIELDS), Set.of("fields")),
    /** The shape of a currency code. */
    CURRENCY_SHAPE(args("fields", ArgKind.FIELDS), Set.of("fields")),
    /** A currency code that is not one of {@code values}. */
    CURRENCY_NOT_IN(
        args("fields", ArgKind.FIELDS).and("values", ArgKind.WORDS), Set.of("fields", "values")),
    /**
     * A reference status at the trade date: the key is the field value, the dataset row's {@code
     * attribute} must be one of {@code allowed}; one of {@code warn_values} passes with the warning
     * {@code warn_code} (both together or neither).
     */
    REF_STATUS(
        args("dataset", ArgKind.NAME)
            .and("fields", ArgKind.FIELDS)
            .and("attribute", ArgKind.NAME)
            .and("allowed", ArgKind.WORDS)
            .and("warn_values", ArgKind.WORDS)
            .and("warn_code", ArgKind.NAME),
        Set.of("dataset", "fields", "attribute", "allowed")),
    /**
     * A listing at the trade date: the key is the values of all the fields joined by a bar. A miss
     * is a warning, never a failure.
     */
    REF_LISTED(
        args("dataset", ArgKind.NAME).and("fields", ArgKind.FIELDS), Set.of("dataset", "fields")),
    /** A key known at the trade date: the key is the value of each field. */
    REF_KNOWN(
        args("dataset", ArgKind.NAME).and("fields", ArgKind.FIELDS), Set.of("dataset", "fields")),
    /** A time that is not after the time the batch was received. */
    TIME_NOT_AFTER_RECEIPT(args("fields", ArgKind.FIELDS), Set.of("fields")),
    /** A time that is not before the time of receipt minus {@code years} calendar years. */
    TIME_NOT_OLDER(
        args("fields", ArgKind.FIELDS).and("years", ArgKind.COUNT), Set.of("fields", "years")),
    /**
     * The fit of the event in the chain of reports already committed for the same subject: a cancel
     * needs a live report with the action {@code new_action}, a new event needs none or a live
     * report with the action {@code cancel_action}.
     */
    CHAIN_FIT(
        args("new_action", ArgKind.NAME).and("cancel_action", ArgKind.NAME),
        Set.of("new_action", "cancel_action"));

    private final Map<String, ArgKind> kinds;
    private final Set<String> required;

    Check(ArgSet args, Set<String> required) {
      this.kinds = args.kinds;
      this.required = required;
    }

    private static ArgSet args(String name, ArgKind kind) {
      return new ArgSet().and(name, kind);
    }
  }

  /** A small builder for the argument table of a check. */
  private static final class ArgSet {
    private final Map<String, ArgKind> kinds = new LinkedHashMap<>();

    ArgSet and(String name, ArgKind kind) {
      kinds.put(name, kind);
      return this;
    }
  }

  /**
   * One rule.
   *
   * @param id the rule id, unique in the file
   * @param layer the layer
   * @param severity how serious a failure is
   * @param disposition what happens to a record that fails
   * @param check the check
   * @param args the arguments of the check: each value is a list, of one element for a single value
   * @param regulatorId the identifier of the regulator's rule this one mirrors, if any
   */
  public record Rule(
      String id,
      Layer layer,
      Severity severity,
      Disposition disposition,
      Check check,
      Map<String, List<String>> args,
      Optional<String> regulatorId) {
    public Rule {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(layer, "layer");
      Objects.requireNonNull(severity, "severity");
      Objects.requireNonNull(disposition, "disposition");
      Objects.requireNonNull(check, "check");
      Objects.requireNonNull(regulatorId, "regulatorId");
      Map<String, List<String>> copy = new LinkedHashMap<>();
      args.forEach((key, values) -> copy.put(key, List.copyOf(values)));
      args = Collections.unmodifiableMap(copy);
    }

    /** The values of an argument, empty if the rule does not give it. */
    public List<String> list(String name) {
      return args.getOrDefault(name, List.of());
    }

    /** The value of a single-valued argument, if the rule gives it. */
    public Optional<String> single(String name) {
      List<String> values = list(name);
      return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }
  }

  public ValidationRules {
    Objects.requireNonNull(path, "path");
    rules = List.copyOf(rules);
  }

  private static final Pattern RULE_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");
  private static final Pattern REGULATOR_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,31}");
  private static final String NO_REGULATOR_ID = "null";

  /**
   * Reads one validation rules file. The problems found are appended to {@code problems}; reading
   * stops once the sink is full. The result is empty when this call found any problem.
   *
   * @param path the pack path of the file
   * @param root the parsed file
   */
  public static Optional<ValidationRules> parse(String path, YamlNode root, ProblemSink problems) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(problems, "problems");
    int before = problems.count();
    Optional<ValidationRules> rules = new Reader(path, problems).read(root);
    return problems.count() == before ? rules : Optional.empty();
  }

  private static final class Reader {
    private final String path;
    private final ProblemSink found;

    Reader(String path, ProblemSink found) {
      this.path = path;
      this.found = found;
    }

    private void problem(String code, int line, String message) {
      found.add(new PackProblem(code, path, line, message));
    }

    Optional<ValidationRules> read(YamlNode root) {
      YamlMap top = map(root, "a validation rules file");
      if (top == null) {
        return Optional.empty();
      }
      checkKeys(top, "the validation rules file", Set.of("rules"));
      YamlNode node = top.entries().get("rules");
      if (node == null) {
        return Optional.empty();
      }
      YamlList list = list(node, "rules");
      if (list == null) {
        return Optional.empty();
      }
      if (list.items().isEmpty()) {
        problem("VALIDATE_RULES_EMPTY", node.line(), "a validation file needs at least one rule");
        return Optional.empty();
      }
      List<Rule> rules = new ArrayList<>();
      Set<String> ids = new HashSet<>();
      for (YamlNode item : list.items()) {
        if (found.truncated()) {
          break; // the file fails anyway; do not read the rest of a hostile file
        }
        YamlMap m = map(item, "a rule entry");
        if (m == null) {
          continue;
        }
        Rule rule = rule(m);
        if (rule != null) {
          if (!ids.add(rule.id())) {
            problem(
                "VALIDATE_RULE_ID_DUPLICATE",
                m.line(),
                "the rule id " + PackProblem.excerpt(rule.id()) + " is used twice");
          }
          rules.add(rule);
        }
      }
      return Optional.of(new ValidationRules(path, rules));
    }

    // -- generic helpers --------------------------------------------------------------------

    private YamlMap map(YamlNode node, String what) {
      if (node instanceof YamlMap m) {
        return m;
      }
      problem("VALIDATE_SHAPE", node.line(), what + " must be a mapping");
      return null;
    }

    private YamlList list(YamlNode node, String what) {
      if (node instanceof YamlList l) {
        return l;
      }
      problem("VALIDATE_SHAPE", node.line(), what + " must be a list");
      return null;
    }

    private void checkKeys(YamlMap m, String where, Set<String> required) {
      for (Map.Entry<String, YamlNode> e : m.entries().entrySet()) {
        if (found.truncated()) {
          return;
        }
        if (!required.contains(e.getKey())) {
          problem(
              "VALIDATE_KEY_UNKNOWN",
              e.getValue().line(),
              "unknown key '" + PackProblem.excerpt(e.getKey()) + "' in " + where);
        }
      }
      for (String key : new TreeSet<>(required)) {
        if (!m.entries().containsKey(key)) {
          problem("VALIDATE_KEY_MISSING", m.line(), "missing key '" + key + "' in " + where);
        }
      }
    }

    /** The clean one-line text of a scalar node, or null (with a problem). */
    private String clean(YamlNode node, String what) {
      if (!(node instanceof YamlScalar s)) {
        problem("VALIDATE_SHAPE", node.line(), what + " must be a single value");
        return null;
      }
      Optional<PackText.Bad> bad = PackText.firstBad(s.value(), false);
      if (bad.isPresent()) {
        problem(
            "VALIDATE_BAD_CHARACTER",
            node.line(),
            what
                + " must be one line of clean text; it has U+"
                + hex(bad.get().codePoint())
                + " at index "
                + bad.get().index());
        return null;
      }
      return s.value();
    }

    private static String hex(int codePoint) {
      String h = Integer.toHexString(codePoint).toUpperCase(Locale.ROOT);
      return "0".repeat(Math.max(0, 4 - h.length())) + h;
    }

    // -- rules ------------------------------------------------------------------------------

    private Rule rule(YamlMap m) {
      int before = found.count();
      checkKeys(
          m,
          "a rule entry",
          Set.of("id", "layer", "severity", "disposition", "check", "args", "regulator_id"));
      Map<String, YamlNode> e = m.entries();
      String id = e.containsKey("id") ? id(e.get("id")) : null;
      Layer layer = e.containsKey("layer") ? word(e.get("layer"), Layer.values(), "layer") : null;
      Severity severity =
          e.containsKey("severity") ? word(e.get("severity"), Severity.values(), "severity") : null;
      Disposition disposition =
          e.containsKey("disposition")
              ? word(e.get("disposition"), Disposition.values(), "disposition")
              : null;
      Check check = e.containsKey("check") ? word(e.get("check"), Check.values(), "check") : null;
      Map<String, List<String>> args =
          check != null && e.containsKey("args") ? args(check, e.get("args")) : null;
      Optional<String> regulatorId =
          e.containsKey("regulator_id") ? regulatorId(e.get("regulator_id")) : null;
      if (severity != null && disposition != null && !consistent(severity, disposition)) {
        problem(
            "VALIDATE_DISPOSITION_MISMATCH",
            e.get("disposition").line(),
            "a BLOCKER rule is QUARANTINE and a WARNING or INFO rule is PASS");
      }
      if (found.count() > before
          || id == null
          || layer == null
          || severity == null
          || disposition == null
          || check == null
          || args == null
          || regulatorId == null) {
        return null;
      }
      return new Rule(id, layer, severity, disposition, check, args, regulatorId);
    }

    private static boolean consistent(Severity severity, Disposition disposition) {
      return (severity == Severity.BLOCKER) == (disposition == Disposition.QUARANTINE);
    }

    private String id(YamlNode node) {
      String value = clean(node, "id");
      if (value == null) {
        return null;
      }
      if (!RULE_ID.matcher(value).matches()) {
        problem(
            "VALIDATE_RULE_ID_INVALID",
            node.line(),
            "a rule id must match [A-Za-z][A-Za-z0-9_.-]{0,63}, got '"
                + PackProblem.excerpt(value)
                + "'");
        return null;
      }
      return value;
    }

    /** One word of a closed vocabulary read by exact name, or null (with a problem). */
    private <E extends Enum<E>> E word(YamlNode node, E[] values, String what) {
      String value = clean(node, what);
      if (value == null) {
        return null;
      }
      for (E candidate : values) {
        if (candidate.name().equals(value)) {
          return candidate;
        }
      }
      String code =
          switch (what) {
            case "layer" -> "VALIDATE_LAYER_UNKNOWN";
            case "severity" -> "VALIDATE_SEVERITY_UNKNOWN";
            case "disposition" -> "VALIDATE_DISPOSITION_UNKNOWN";
            default -> "VALIDATE_CHECK_UNKNOWN";
          };
      problem(
          code,
          node.line(),
          what
              + " must be an exact word of the vocabulary, got '"
              + PackProblem.excerpt(value)
              + "'");
      return null;
    }

    private Optional<String> regulatorId(YamlNode node) {
      String value = clean(node, "regulator_id");
      if (value == null) {
        return null;
      }
      if (value.equals(NO_REGULATOR_ID)) {
        return Optional.empty();
      }
      if (!REGULATOR_ID.matcher(value).matches()) {
        problem(
            "VALIDATE_REGULATOR_ID_INVALID",
            node.line(),
            "regulator_id must be null or an identifier of at most 32 characters, got '"
                + PackProblem.excerpt(value)
                + "'");
        return null;
      }
      return Optional.of(value);
    }

    // -- arguments --------------------------------------------------------------------------

    /** The arguments of a check, or null on a problem. */
    private Map<String, List<String>> args(Check check, YamlNode node) {
      YamlMap m = map(node, "args");
      if (m == null) {
        return null;
      }
      int before = found.count();
      Map<String, List<String>> out = new LinkedHashMap<>();
      for (Map.Entry<String, YamlNode> e : m.entries().entrySet()) {
        if (found.truncated()) {
          break;
        }
        ArgKind kind = check.kinds.get(e.getKey());
        if (kind == null) {
          problem(
              "VALIDATE_ARGS_UNKNOWN",
              e.getValue().line(),
              "the check "
                  + check.name()
                  + " takes no argument '"
                  + PackProblem.excerpt(e.getKey())
                  + "'");
          continue;
        }
        List<String> values = values(e.getKey(), kind, e.getValue());
        if (values != null) {
          out.put(e.getKey(), values);
        }
      }
      for (String name : new TreeSet<>(check.required)) {
        if (!m.entries().containsKey(name)) {
          problem(
              "VALIDATE_ARGS_MISSING",
              m.line(),
              "the check " + check.name() + " needs the argument '" + name + "'");
        }
      }
      if (m.entries().containsKey("warn_values") != m.entries().containsKey("warn_code")) {
        problem("VALIDATE_ARGS_INVALID", m.line(), "warn_values and warn_code go together");
      }
      return found.count() == before ? out : null;
    }

    /** The values of one argument, or null (with a problem). */
    private List<String> values(String name, ArgKind kind, YamlNode node) {
      List<String> out = new ArrayList<>();
      if (kind.list) {
        YamlList items = node instanceof YamlList l ? l : null;
        if (items == null) {
          problem(
              "VALIDATE_ARGS_INVALID", node.line(), "the argument '" + name + "' must be a list");
          return null;
        }
        if (items.items().isEmpty()) {
          problem(
              "VALIDATE_ARGS_INVALID",
              node.line(),
              "the argument '" + name + "' needs at least one value");
          return null;
        }
        boolean ok = true;
        Set<String> seen = new HashSet<>();
        for (YamlNode item : items.items()) {
          String value = cell(name, kind, item);
          if (value == null) {
            ok = false;
          } else if (!seen.add(value)) {
            problem(
                "VALIDATE_ARGS_INVALID",
                item.line(),
                "the argument '" + name + "' names '" + PackProblem.excerpt(value) + "' twice");
            ok = false;
          } else {
            out.add(value);
          }
        }
        return ok ? out : null;
      }
      String value = cell(name, kind, node);
      return value == null ? null : List.of(value);
    }

    private String cell(String name, ArgKind kind, YamlNode node) {
      String value = clean(node, "the argument '" + name + "'");
      if (value == null) {
        return null;
      }
      if (!kind.shape.matcher(value).matches()) {
        problem(
            "VALIDATE_ARGS_INVALID",
            node.line(),
            "the argument '"
                + name
                + "' has an invalid form: '"
                + PackProblem.excerpt(value)
                + "'");
        return null;
      }
      return value;
    }
  }
}
