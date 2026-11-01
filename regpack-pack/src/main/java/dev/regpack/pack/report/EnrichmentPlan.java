package dev.regpack.pack.report;

import dev.regpack.pack.PackText;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.check.ProblemSink;
import dev.regpack.pack.yaml.YamlList;
import dev.regpack.pack.yaml.YamlMap;
import dev.regpack.pack.yaml.YamlNode;
import dev.regpack.pack.yaml.YamlScalar;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * An enrichment plan: for each target field the sources in precedence order, the lookup key of each
 * source, how old an answer may be, and what a miss means (plan W1a, task T8, spec 7.5; the file
 * {@code enrich/plan.yaml} of a pack). The reader checks the shape and the closed vocabularies; it
 * does not know which sources or fields a deployment has.
 *
 * <pre>
 * fields:
 *   - {field: client.lei, needed_by: SCOPE, on_miss: UNDETERMINED,
 *      sources: [{source: static-data, key: account, max_staleness: P30D}]}
 * </pre>
 *
 * <p>Only {@code needed_by: SCOPE} is built: the lookups of a scope field run before the decision.
 * A field needed by the derive or validate step would run after a decision to report, in a second
 * phase that does not exist yet, so the reader refuses it rather than ignore it. For a scope field
 * the miss behaviour is {@code UNDETERMINED} or {@code QUARANTINE}: no decision exists yet, so
 * nothing can be flagged or held.
 *
 * <p>Problem codes of {@link #parse}: {@code ENRICH_PLAN_SHAPE}, {@code ENRICH_PLAN_KEY_UNKNOWN},
 * {@code ENRICH_PLAN_KEY_MISSING}, {@code ENRICH_PLAN_FIELD_INVALID}, {@code
 * ENRICH_PLAN_FIELD_DUPLICATE}, {@code ENRICH_PLAN_NEEDED_BY_INVALID}, {@code
 * ENRICH_PLAN_PHASE_NOT_BUILT}, {@code ENRICH_PLAN_SOURCES_EMPTY}, {@code ENRICH_PLAN_ON_MISS},
 * {@code ENRICH_PLAN_STALENESS_INVALID} and {@code ENRICH_PLAN_BAD_CHARACTER}.
 *
 * @param path the pack path of the file, kept in every problem
 * @param fields the target fields in file order, possibly none
 */
public record EnrichmentPlan(String path, List<Field> fields) {
  /** What a miss means for a scope field. */
  public enum OnMiss {
    /** The decision is UNDETERMINED and waits in the data-quality queue. */
    UNDETERMINED,
    /** The record is quarantined. */
    QUARANTINE
  }

  /**
   * One source of a field.
   *
   * @param source the name of the source, as the deployment calls it
   * @param key the name of the attribute of the record that is the lookup key
   * @param maxStaleness how old the as-of of an answer may be, positive
   */
  public record Source(String source, String key, Duration maxStaleness) {
    public Source {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(maxStaleness, "maxStaleness");
      if (maxStaleness.isZero() || maxStaleness.isNegative()) {
        throw new IllegalArgumentException("maxStaleness must be positive");
      }
    }
  }

  /**
   * One target field.
   *
   * @param field the dotted name of the canonical field the plan fills
   * @param sources the sources in precedence order, at least one
   * @param onMiss what a miss, a failure or an over-stale answer means
   */
  public record Field(String field, List<Source> sources, OnMiss onMiss) {
    public Field {
      Objects.requireNonNull(field, "field");
      Objects.requireNonNull(onMiss, "onMiss");
      sources = List.copyOf(sources);
      if (sources.isEmpty()) {
        throw new IllegalArgumentException("a field needs at least one source");
      }
    }
  }

  public EnrichmentPlan {
    Objects.requireNonNull(path, "path");
    fields = List.copyOf(fields);
  }

  /** The phase a field is needed by (the word of {@code needed_by}). */
  private enum Phase {
    SCOPE,
    DERIVE,
    VALIDATE
  }

  private static final Pattern FIELD =
      Pattern.compile("[a-z][A-Za-z0-9]{0,31}(\\.[a-z][A-Za-z0-9]{0,31}){0,7}");
  private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");

  /**
   * Reads one enrichment plan file. The problems found are appended to {@code problems}; reading
   * stops once the sink is full. The result is empty when this call found any problem.
   *
   * @param path the pack path of the file
   * @param root the parsed file
   */
  public static Optional<EnrichmentPlan> parse(String path, YamlNode root, ProblemSink problems) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(problems, "problems");
    int before = problems.count();
    Optional<EnrichmentPlan> plan = new Reader(path, problems).read(root);
    return problems.count() == before ? plan : Optional.empty();
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

    Optional<EnrichmentPlan> read(YamlNode root) {
      YamlMap top = map(root, "an enrichment plan file");
      if (top == null) {
        return Optional.empty();
      }
      checkKeys(top, "the enrichment plan file", Set.of("fields"), Set.of());
      YamlNode node = top.entries().get("fields");
      if (node == null) {
        return Optional.empty();
      }
      YamlList list = list(node, "fields");
      if (list == null) {
        return Optional.empty();
      }
      List<Field> fields = new ArrayList<>();
      Set<String> names = new HashSet<>();
      for (YamlNode item : list.items()) {
        if (found.truncated()) {
          break; // the file fails anyway; do not read the rest of a hostile plan
        }
        YamlMap m = map(item, "a field entry");
        if (m == null) {
          continue;
        }
        Field field = field(m);
        if (field != null) {
          if (!names.add(field.field())) {
            problem(
                "ENRICH_PLAN_FIELD_DUPLICATE",
                m.line(),
                "the field " + PackProblem.excerpt(field.field()) + " is planned twice");
          }
          fields.add(field);
        }
      }
      return Optional.of(new EnrichmentPlan(path, fields));
    }

    // -- generic helpers --------------------------------------------------------------------

    private YamlMap map(YamlNode node, String what) {
      if (node instanceof YamlMap m) {
        return m;
      }
      problem("ENRICH_PLAN_SHAPE", node.line(), what + " must be a mapping");
      return null;
    }

    private YamlList list(YamlNode node, String what) {
      if (node instanceof YamlList l) {
        return l;
      }
      problem("ENRICH_PLAN_SHAPE", node.line(), what + " must be a list");
      return null;
    }

    private void checkKeys(YamlMap m, String where, Set<String> required, Set<String> optional) {
      for (Map.Entry<String, YamlNode> e : m.entries().entrySet()) {
        if (found.truncated()) {
          return;
        }
        String key = e.getKey();
        if (!required.contains(key) && !optional.contains(key)) {
          problem(
              "ENRICH_PLAN_KEY_UNKNOWN",
              e.getValue().line(),
              "unknown key '" + PackProblem.excerpt(key) + "' in " + where);
        }
      }
      for (String key : new TreeSet<>(required)) {
        if (!m.entries().containsKey(key)) {
          problem("ENRICH_PLAN_KEY_MISSING", m.line(), "missing key '" + key + "' in " + where);
        }
      }
    }

    /** The scalar text of a node (null if absent, or a problem if it is not a scalar). */
    private String text(YamlNode node, String what) {
      if (node == null) {
        return null;
      }
      if (node instanceof YamlScalar s) {
        return s.value();
      }
      problem("ENRICH_PLAN_SHAPE", node.line(), what + " must be a single value");
      return null;
    }

    /** The clean one-line text of a scalar node, or null (with a problem). */
    private String clean(YamlNode node, String what) {
      String value = text(node, what);
      if (value == null) {
        return null;
      }
      Optional<PackText.Bad> bad = PackText.firstBad(value, false);
      if (bad.isPresent()) {
        problem(
            "ENRICH_PLAN_BAD_CHARACTER",
            node.line(),
            what
                + " must be one line of clean text; it has U+"
                + hex(bad.get().codePoint())
                + " at index "
                + bad.get().index());
        return null;
      }
      return value;
    }

    private static String hex(int codePoint) {
      String h = Integer.toHexString(codePoint).toUpperCase(Locale.ROOT);
      return "0".repeat(Math.max(0, 4 - h.length())) + h;
    }

    /** A clean cell of the given shape, or null (with a problem). */
    private String cell(YamlNode node, String what, Pattern shape) {
      String value = clean(node, what);
      if (value == null) {
        return null;
      }
      if (!shape.matcher(value).matches()) {
        problem(
            "ENRICH_PLAN_FIELD_INVALID",
            node.line(),
            what + " has an invalid form: '" + PackProblem.excerpt(value) + "'");
        return null;
      }
      return value;
    }

    // -- fields -----------------------------------------------------------------------------

    private Field field(YamlMap m) {
      int before = found.count();
      checkKeys(m, "a field entry", Set.of("field", "needed_by", "sources", "on_miss"), Set.of());
      Map<String, YamlNode> e = m.entries();
      String name = e.containsKey("field") ? cell(e.get("field"), "field", FIELD) : null;
      Phase phase = e.containsKey("needed_by") ? phase(e.get("needed_by")) : null;
      List<Source> sources = e.containsKey("sources") ? sources(e.get("sources")) : null;
      // the miss behaviour of a phase that is not built is not judged: the phase is the problem
      boolean built = phase == Phase.SCOPE;
      OnMiss onMiss = e.containsKey("on_miss") && built ? onMiss(e.get("on_miss")) : null;
      if (found.count() > before
          || name == null
          || phase == null
          || sources == null
          || onMiss == null) {
        return null;
      }
      return new Field(name, sources, onMiss);
    }

    /** The phase, or null (with a problem; a phase that is not built is a problem too). */
    private Phase phase(YamlNode node) {
      String value = clean(node, "needed_by");
      if (value == null) {
        return null;
      }
      for (Phase p : Phase.values()) {
        if (p.name().equals(value)) {
          if (p != Phase.SCOPE) {
            problem(
                "ENRICH_PLAN_PHASE_NOT_BUILT",
                node.line(),
                "needed_by " + p.name() + " needs the second enrichment phase, which is not built");
          }
          return p;
        }
      }
      problem(
          "ENRICH_PLAN_NEEDED_BY_INVALID",
          node.line(),
          "needed_by must be an exact phase word, got '" + PackProblem.excerpt(value) + "'");
      return null;
    }

    /** The miss behaviour of a scope field, or null (with a problem). */
    private OnMiss onMiss(YamlNode node) {
      String value = clean(node, "on_miss");
      if (value == null) {
        return null;
      }
      for (OnMiss o : OnMiss.values()) {
        if (o.name().equals(value)) {
          return o;
        }
      }
      problem(
          "ENRICH_PLAN_ON_MISS",
          node.line(),
          "on_miss of a scope field must be UNDETERMINED or QUARANTINE, got '"
              + PackProblem.excerpt(value)
              + "'");
      return null;
    }

    /** The sources, or null on a problem. */
    private List<Source> sources(YamlNode node) {
      YamlList list = list(node, "sources");
      if (list == null) {
        return null;
      }
      if (list.items().isEmpty()) {
        problem("ENRICH_PLAN_SOURCES_EMPTY", node.line(), "a field needs at least one source");
        return null;
      }
      List<Source> out = new ArrayList<>();
      boolean ok = true;
      for (YamlNode item : list.items()) {
        if (found.truncated()) {
          break;
        }
        YamlMap m = map(item, "a source entry");
        if (m == null) {
          ok = false;
          continue;
        }
        int before = found.count();
        checkKeys(m, "a source entry", Set.of("source", "key", "max_staleness"), Set.of());
        Map<String, YamlNode> e = m.entries();
        String source = e.containsKey("source") ? cell(e.get("source"), "source", NAME) : null;
        String key = e.containsKey("key") ? cell(e.get("key"), "key", NAME) : null;
        Duration staleness =
            e.containsKey("max_staleness") ? staleness(e.get("max_staleness")) : null;
        if (found.count() > before || source == null || key == null || staleness == null) {
          ok = false;
        } else {
          out.add(new Source(source, key, staleness));
        }
      }
      return ok ? out : null;
    }

    /** A positive ISO-8601 duration of days, hours, minutes and seconds, or null (a problem). */
    private Duration staleness(YamlNode node) {
      String value = clean(node, "max_staleness");
      if (value == null) {
        return null;
      }
      try {
        Duration d = Duration.parse(value);
        if (!d.isZero() && !d.isNegative() && value.startsWith("P")) {
          return d;
        }
      } catch (DateTimeParseException e) {
        // reported below, without the text of the exception
      }
      problem(
          "ENRICH_PLAN_STALENESS_INVALID",
          node.line(),
          "max_staleness must be a positive ISO duration such as P1D or PT1H, got '"
              + PackProblem.excerpt(value)
              + "'");
      return null;
    }
  }
}
