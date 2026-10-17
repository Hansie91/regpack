package dev.regpack.pack.model;

import dev.regpack.model.CitationRole;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.Sha256;
import dev.regpack.pack.PackText;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.check.ProblemSink;
import dev.regpack.pack.yaml.YamlList;
import dev.regpack.pack.yaml.YamlMap;
import dev.regpack.pack.yaml.YamlNode;
import dev.regpack.pack.yaml.YamlScalar;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads the three registries of a pack from parsed {@code PackYaml/1} trees (companion contracts
 * section 2): {@code reasons.yaml}, {@code sources.yaml} and {@code rationales.yaml}. Every scalar
 * of the tree is a string; this class types each one explicitly and by an exact form (ASCII numbers
 * and dates, exact enumeration names, identifiers by an explicit character class). Every string
 * cell is one line of clean text ({@link PackText}, LF not allowed), so a tree that did not come
 * from the YAML parser goes through the same cell rules. A cell with a bad character is reported
 * once ({@code CELL_BAD_CHARACTER}) and its shape is not checked further.
 *
 * <p>{@code reasons.yaml}: {@code prefix}, {@code nodes} (the header of OI-7) and {@code reasons},
 * each {@code {code, label, category, default_outcome, owner, valid: {from[, to]}}}. The {@code
 * label} is the label segment of the code, which {@code RegistryChecks} compares with the code.
 * {@code sources.yaml}: {@code sources}, each {@code {source_id, version, retrieved_at,
 * content_sha256[, eli], provisions}} with provisions {@code {path, role, status[, quote,
 * quote_sha256]}}. {@code rationales.yaml}: {@code rationales}, each {@code {id, text}} with an id
 * of the form {@code RAT-} and four to nine ASCII digits.
 *
 * <p>Problem codes: {@code REGISTRY_SHAPE}, {@code REGISTRY_KEY_UNKNOWN}, {@code
 * REGISTRY_KEY_MISSING}, {@code CELL_BAD_CHARACTER}, {@code PREFIX_INVALID}, {@code NODE_INVALID},
 * {@code NODE_DUPLICATE}, {@code REASON_CODE_INVALID}, {@code REASON_DUPLICATE}, {@code
 * LABEL_INVALID}, {@code CATEGORY_INVALID}, {@code OUTCOME_INVALID}, {@code OWNER_INVALID}, {@code
 * DATE_INVALID}, {@code SOURCE_ID_INVALID}, {@code SOURCE_DUPLICATE}, {@code VERSION_INVALID},
 * {@code HASH_INVALID}, {@code ELI_INVALID}, {@code PROVISION_PATH_INVALID}, {@code
 * PROVISION_DUPLICATE}, {@code ROLE_INVALID}, {@code STATUS_INVALID}, {@code QUOTE_EMPTY}, {@code
 * RATIONALE_ID_INVALID}, {@code RATIONALE_DUPLICATE} and {@code RATIONALE_TEXT_EMPTY}. Whether the
 * registries are consistent with each other and with the tables is the business of {@code
 * RegistryChecks}; the quote length limit is checked there too.
 */
public final class RegistryParser {
  /** The pack path of the reason-code registry. */
  public static final String REASONS_FILE = "reasons.yaml";

  /** The pack path of the Legal Source Registry. */
  public static final String SOURCES_FILE = "sources.yaml";

  /** The pack path of the rationales. */
  public static final String RATIONALES_FILE = "rationales.yaml";

  private static final Pattern PREFIX = Pattern.compile("[A-Z][A-Z0-9]{0,15}");
  private static final Pattern NODE = Pattern.compile("[A-Z][A-Z0-9]{0,7}");
  private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9_]{1,64}");
  private static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");
  private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");
  private static final Pattern ELI = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/%+~=?&-]{0,255}");
  private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
  private static final Pattern PROVISION_PATH = Pattern.compile("[A-Za-z0-9_./:-]{1,256}");
  private static final Pattern RATIONALE_ID = Pattern.compile("RAT-[0-9]{4,9}");
  private static final int MAX_REASON_CHARS = 128;

  private RegistryParser() {}

  /**
   * Reads {@code reasons.yaml}. The problems found are appended to {@code problems} (sorted among
   * themselves); the result is empty when this call found any problem.
   */
  public static Optional<ReasonRegistry> reasons(YamlNode root, List<PackProblem> problems) {
    Reader reader = new Reader(REASONS_FILE);
    Optional<ReasonRegistry> result = reader.reasons(root);
    return reader.finish(result, problems);
  }

  /** Reads {@code sources.yaml}; same contract as {@link #reasons}. */
  public static Optional<SourceRegistry> sources(YamlNode root, List<PackProblem> problems) {
    Reader reader = new Reader(SOURCES_FILE);
    Optional<SourceRegistry> result = reader.sources(root);
    return reader.finish(result, problems);
  }

  /** Reads {@code rationales.yaml}; same contract as {@link #reasons}. */
  public static Optional<RationaleRegistry> rationales(YamlNode root, List<PackProblem> problems) {
    Reader reader = new Reader(RATIONALES_FILE);
    Optional<RationaleRegistry> result = reader.rationales(root);
    return reader.finish(result, problems);
  }

  private static final class Reader {
    private final String path;
    private final ProblemSink found = ProblemSink.forFile();

    Reader(String path) {
      this.path = path;
    }

    <T> Optional<T> finish(Optional<T> result, List<PackProblem> problems) {
      problems.addAll(found.sorted(path));
      return found.isEmpty() ? result : Optional.empty();
    }

    private void problem(String code, int line, String message) {
      found.add(new PackProblem(code, path, line, message));
    }

    // -- generic helpers --------------------------------------------------------------------

    private YamlMap map(YamlNode node, String what) {
      if (node instanceof YamlMap m) {
        return m;
      }
      problem("REGISTRY_SHAPE", node.line(), what + " must be a mapping");
      return null;
    }

    private YamlList list(YamlNode node, String what) {
      if (node instanceof YamlList l) {
        return l;
      }
      problem("REGISTRY_SHAPE", node.line(), what + " must be a list");
      return null;
    }

    private void checkKeys(YamlMap m, String where, Set<String> required, Set<String> optional) {
      for (Map.Entry<String, YamlNode> e : m.entries().entrySet()) {
        if (found.truncated()) {
          break;
        }
        String key = e.getKey();
        if (!required.contains(key) && !optional.contains(key)) {
          problem(
              "REGISTRY_KEY_UNKNOWN",
              e.getValue().line(),
              "unknown key '" + PackProblem.excerpt(key) + "' in " + where);
        }
      }
      List<String> missing = new ArrayList<>(required);
      Collections.sort(missing);
      for (String key : missing) {
        if (!m.entries().containsKey(key)) {
          problem("REGISTRY_KEY_MISSING", m.line(), "missing key '" + key + "' in " + where);
        }
      }
    }

    /** The scalar text of a node (null if absent; a problem if it is not a scalar). */
    private String text(YamlNode node, String what) {
      if (node == null) {
        return null;
      }
      if (node instanceof YamlScalar s) {
        return s.value();
      }
      problem("REGISTRY_SHAPE", node.line(), what + " must be a single value");
      return null;
    }

    /** Whether a cell is one line of clean text; reports {@code CELL_BAD_CHARACTER} if not. */
    private boolean clean(String value, String what, int line) {
      Optional<PackText.Bad> bad = PackText.firstBad(value, false);
      if (bad.isPresent()) {
        problem(
            "CELL_BAD_CHARACTER",
            line,
            what
                + " must be one line of clean text; it has U+"
                + hex(bad.get().codePoint())
                + " at index "
                + bad.get().index());
        return false;
      }
      return true;
    }

    private static String hex(int codePoint) {
      String h = Integer.toHexString(codePoint).toUpperCase(Locale.ROOT);
      return "0".repeat(Math.max(0, 4 - h.length())) + h;
    }

    /** A clean cell of the given shape, or null (a problem is reported unless it is absent). */
    private String cell(YamlNode node, String what, Pattern shape, String code) {
      String value = text(node, what);
      if (value == null || !clean(value, what, node.line())) {
        return null;
      }
      if (!shape.matcher(value).matches()) {
        problem(
            code, node.line(), what + " has an invalid form: '" + PackProblem.excerpt(value) + "'");
        return null;
      }
      return value;
    }

    /** A clean cell that is not blank, or null; {@code code} is reported for a blank one. */
    private String freeText(YamlNode node, String what, String code) {
      String value = text(node, what);
      if (value == null || !clean(value, what, node.line())) {
        return null;
      }
      if (value.isBlank()) {
        problem(code, node.line(), what + " must not be empty");
        return null;
      }
      return value;
    }

    private LocalDate date(YamlNode node, String what) {
      String value = text(node, what);
      if (value == null || !clean(value, what, node.line())) {
        return null;
      }
      if (DATE.matcher(value).matches()) {
        try {
          return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
          // reported below
        }
      }
      problem(
          "DATE_INVALID",
          node.line(),
          what + " must be a date yyyy-mm-dd, got '" + PackProblem.excerpt(value) + "'");
      return null;
    }

    private Sha256 hash(YamlNode node, String what) {
      String value = cell(node, what, HEX64, "HASH_INVALID");
      return value == null ? null : new Sha256(value);
    }

    /**
     * The enumeration constant with exactly this name (no case folding), or null with a problem.
     */
    private <E extends Enum<E>> E exact(YamlNode node, String what, Class<E> type, String code) {
      String value = text(node, what);
      if (value == null || !clean(value, what, node.line())) {
        return null;
      }
      for (E constant : type.getEnumConstants()) {
        if (constant.name().equals(value)) {
          return constant;
        }
      }
      problem(
          code,
          node.line(),
          what
              + " must be an exact "
              + type.getSimpleName()
              + " name, got '"
              + PackProblem.excerpt(value)
              + "'");
      return null;
    }

    /** The items of a required list key, or null (the key or the shape was already reported). */
    private List<YamlNode> items(YamlMap top, String key) {
      YamlNode node = top.entries().get(key);
      if (node == null) {
        return null;
      }
      YamlList list = list(node, key);
      return list == null ? null : list.items();
    }

    // -- reasons.yaml ------------------------------------------------------------------------

    Optional<ReasonRegistry> reasons(YamlNode root) {
      YamlMap top = map(root, REASONS_FILE);
      if (top == null) {
        return Optional.empty();
      }
      checkKeys(top, REASONS_FILE, Set.of("prefix", "nodes", "reasons"), Set.of());
      String prefix = cell(top.entries().get("prefix"), "prefix", PREFIX, "PREFIX_INVALID");
      Set<String> nodes = nodes(top.entries().get("nodes"));
      Map<String, ReasonEntry> entries = new LinkedHashMap<>();
      List<YamlNode> items = items(top, "reasons");
      if (items != null) {
        for (YamlNode item : items) {
          if (found.truncated()) {
            break;
          }
          YamlMap m = map(item, "a reason");
          if (m == null) {
            continue;
          }
          ReasonEntry entry = reason(m);
          if (entry == null) {
            continue;
          }
          if (entries.putIfAbsent(entry.code().value(), entry) != null) {
            problem(
                "REASON_DUPLICATE",
                m.line(),
                "reason " + PackProblem.excerpt(entry.code().value()) + " is declared twice");
          }
        }
      }
      if (!found.isEmpty() || prefix == null || nodes == null) {
        return Optional.empty();
      }
      return Optional.of(new ReasonRegistry(prefix, nodes, entries));
    }

    private Set<String> nodes(YamlNode node) {
      if (node == null) {
        return null;
      }
      YamlList list = list(node, "nodes");
      if (list == null) {
        return null;
      }
      Set<String> out = new LinkedHashSet<>();
      boolean ok = true;
      for (YamlNode item : list.items()) {
        if (found.truncated()) {
          break;
        }
        String value = cell(item, "a node", NODE, "NODE_INVALID");
        if (value == null) {
          ok = false;
        } else if (!out.add(value)) {
          problem("NODE_DUPLICATE", item.line(), "node " + value + " is declared twice");
          ok = false;
        }
      }
      return ok ? out : null;
    }

    private ReasonEntry reason(YamlMap m) {
      int before = found.count();
      checkKeys(
          m,
          "a reason",
          Set.of("code", "label", "category", "default_outcome", "owner", "valid"),
          Set.of());
      Map<String, YamlNode> e = m.entries();

      ReasonCode code = reasonCode(e.get("code"));
      String label = cell(e.get("label"), "label", LABEL, "LABEL_INVALID");
      ReasonEntry.Category category =
          exact(e.get("category"), "category", ReasonEntry.Category.class, "CATEGORY_INVALID");
      Outcome outcome =
          exact(e.get("default_outcome"), "default_outcome", Outcome.class, "OUTCOME_INVALID");
      String owner = cell(e.get("owner"), "owner", ID, "OWNER_INVALID");
      LocalDate[] window = window(e.get("valid"));

      if (found.count() > before
          || code == null
          || label == null
          || category == null
          || outcome == null
          || owner == null
          || window == null) {
        return null;
      }
      return new ReasonEntry(
          code, label, category, outcome, owner, window[0], Optional.ofNullable(window[1]));
    }

    private ReasonCode reasonCode(YamlNode node) {
      String value = text(node, "code");
      if (value == null || !clean(value, "code", node.line())) {
        return null;
      }
      if (value.length() <= MAX_REASON_CHARS) {
        try {
          return new ReasonCode(value);
        } catch (IllegalArgumentException e) {
          // reported below
        }
      }
      problem(
          "REASON_CODE_INVALID",
          node.line(),
          "code is not a reason code: '" + PackProblem.excerpt(value) + "'");
      return null;
    }

    /** The window {@code {from[, to]}} as {from, to or null}, or null on a problem. */
    private LocalDate[] window(YamlNode node) {
      if (node == null) {
        return null;
      }
      YamlMap m = map(node, "valid");
      if (m == null) {
        return null;
      }
      int before = found.count();
      checkKeys(m, "valid", Set.of("from"), Set.of("to"));
      LocalDate from = date(m.entries().get("from"), "valid.from");
      boolean hasTo = m.entries().containsKey("to");
      LocalDate to = hasTo ? date(m.entries().get("to"), "valid.to") : null;
      if (found.count() > before || from == null || (hasTo && to == null)) {
        return null;
      }
      return new LocalDate[] {from, to};
    }

    // -- sources.yaml ------------------------------------------------------------------------

    Optional<SourceRegistry> sources(YamlNode root) {
      YamlMap top = map(root, SOURCES_FILE);
      if (top == null) {
        return Optional.empty();
      }
      checkKeys(top, SOURCES_FILE, Set.of("sources"), Set.of());
      Map<String, SourceEntry> out = new LinkedHashMap<>();
      List<YamlNode> items = items(top, "sources");
      if (items != null) {
        for (YamlNode item : items) {
          if (found.truncated()) {
            break;
          }
          YamlMap m = map(item, "a source");
          if (m == null) {
            continue;
          }
          SourceEntry entry = source(m);
          if (entry == null) {
            continue;
          }
          if (out.putIfAbsent(entry.sourceId(), entry) != null) {
            problem(
                "SOURCE_DUPLICATE",
                m.line(),
                "source " + PackProblem.excerpt(entry.sourceId()) + " is declared twice");
          }
        }
      }
      return found.isEmpty() ? Optional.of(new SourceRegistry(out)) : Optional.empty();
    }

    private SourceEntry source(YamlMap m) {
      int before = found.count();
      checkKeys(
          m,
          "a source",
          Set.of("source_id", "version", "retrieved_at", "content_sha256", "provisions"),
          Set.of("eli"));
      Map<String, YamlNode> e = m.entries();

      String id = cell(e.get("source_id"), "source_id", ID, "SOURCE_ID_INVALID");
      String version = cell(e.get("version"), "version", VERSION, "VERSION_INVALID");
      LocalDate retrievedAt = date(e.get("retrieved_at"), "retrieved_at");
      Sha256 content = hash(e.get("content_sha256"), "content_sha256");
      String eli = e.containsKey("eli") ? cell(e.get("eli"), "eli", ELI, "ELI_INVALID") : null;
      Map<String, Provision> provisions = provisions(e.get("provisions"));

      if (found.count() > before
          || id == null
          || version == null
          || retrievedAt == null
          || content == null
          || provisions == null) {
        return null;
      }
      return new SourceEntry(
          id, version, retrievedAt, content, Optional.ofNullable(eli), provisions);
    }

    private Map<String, Provision> provisions(YamlNode node) {
      if (node == null) {
        return null;
      }
      YamlList list = list(node, "provisions");
      if (list == null) {
        return null;
      }
      Map<String, Provision> out = new LinkedHashMap<>();
      boolean ok = true;
      for (YamlNode item : list.items()) {
        if (found.truncated()) {
          break;
        }
        YamlMap m = map(item, "a provision");
        if (m == null) {
          ok = false;
          continue;
        }
        Provision p = provision(m);
        if (p == null) {
          ok = false;
        } else if (out.putIfAbsent(p.path(), p) != null) {
          problem(
              "PROVISION_DUPLICATE",
              m.line(),
              "provision " + PackProblem.excerpt(p.path()) + " is declared twice");
          ok = false;
        }
      }
      return ok ? out : null;
    }

    private Provision provision(YamlMap m) {
      int before = found.count();
      checkKeys(
          m, "a provision", Set.of("path", "role", "status"), Set.of("quote", "quote_sha256"));
      Map<String, YamlNode> e = m.entries();

      String provisionPath = cell(e.get("path"), "path", PROVISION_PATH, "PROVISION_PATH_INVALID");
      CitationRole role = exact(e.get("role"), "role", CitationRole.class, "ROLE_INVALID");
      Provision.Status status =
          exact(e.get("status"), "status", Provision.Status.class, "STATUS_INVALID");
      String quote =
          e.containsKey("quote") ? freeText(e.get("quote"), "quote", "QUOTE_EMPTY") : null;
      Sha256 quoteHash =
          e.containsKey("quote_sha256") ? hash(e.get("quote_sha256"), "quote_sha256") : null;

      if (found.count() > before || provisionPath == null || role == null || status == null) {
        return null;
      }
      return new Provision(
          provisionPath, role, status, Optional.ofNullable(quote), Optional.ofNullable(quoteHash));
    }

    // -- rationales.yaml ---------------------------------------------------------------------

    Optional<RationaleRegistry> rationales(YamlNode root) {
      YamlMap top = map(root, RATIONALES_FILE);
      if (top == null) {
        return Optional.empty();
      }
      checkKeys(top, RATIONALES_FILE, Set.of("rationales"), Set.of());
      Map<String, String> out = new LinkedHashMap<>();
      List<YamlNode> items = items(top, "rationales");
      if (items != null) {
        for (YamlNode item : items) {
          if (found.truncated()) {
            break;
          }
          YamlMap m = map(item, "a rationale");
          if (m == null) {
            continue;
          }
          int before = found.count();
          checkKeys(m, "a rationale", Set.of("id", "text"), Set.of());
          String id = cell(m.entries().get("id"), "id", RATIONALE_ID, "RATIONALE_ID_INVALID");
          String text = freeText(m.entries().get("text"), "text", "RATIONALE_TEXT_EMPTY");
          if (found.count() > before || id == null || text == null) {
            continue;
          }
          if (out.putIfAbsent(id, text) != null) {
            problem("RATIONALE_DUPLICATE", m.line(), "rationale " + id + " is declared twice");
          }
        }
      }
      return found.isEmpty() ? Optional.of(new RationaleRegistry(out)) : Optional.empty();
    }
  }
}
