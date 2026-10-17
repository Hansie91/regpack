package dev.regpack.pack.model;

import dev.regpack.model.Leg;
import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import dev.regpack.model.json.JsonException;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.check.ProblemSink;
import dev.regpack.pack.check.RegistryChecks;
import dev.regpack.pack.check.TableChecks;
import dev.regpack.pack.ir.PackDigest;
import dev.regpack.pack.source.Checksums;
import dev.regpack.pack.source.PackSource;
import dev.regpack.pack.yaml.PackSyntaxException;
import dev.regpack.pack.yaml.StrictYaml;
import dev.regpack.pack.yaml.YamlList;
import dev.regpack.pack.yaml.YamlMap;
import dev.regpack.pack.yaml.YamlNode;
import dev.regpack.ports.expr.ValueType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Loads a whole pack from a {@link PackSource}: verifies CHECKSUMS, parses every file with the
 * strict parser, types and cross-checks them, applies the reserved-slot rules and computes the
 * canonical digest (companion contracts section 5.4; plan task T9). It fails closed: a {@link
 * LoadedPack} is returned only when there is no problem at all, and every problem found is reported
 * (sorted, without duplicates), not only the first, up to {@code PackFormat.MAX_PROBLEMS_PER_LOAD}:
 * then one {@code PROBLEMS_TRUNCATED} ends the list and the remaining tables are not parsed.
 *
 * <p>Order: CHECKSUMS; the allowed paths; {@code pack.yaml}; the input model and its digest against
 * {@code input_schema}; the {@code as_at_field}; the files each report lists and the scope tables
 * ({@link TableParser}, {@link TableChecks}); scope files no report lists; the three registries and
 * {@link RegistryChecks}; row ids and {@code rule_ref} values unique across the pack; row legs
 * within the report's vocabulary; reserved and not-interpreted files; golden files.
 *
 * <p>Problem codes of this class: {@code FILE_UNKNOWN}, {@code FILE_UNREFERENCED}, {@code
 * PROPERTY_TESTS_RESERVED}, {@code INTERPRETATIONS_RESERVED}, {@code REPORT_FILE_MISSING}, {@code
 * REPORT_PATH_INVALID}, {@code REPORT_INPUT_UNSUPPORTED}, {@code REGISTRY_FILE_MISSING}, {@code
 * INPUT_SCHEMA_DIGEST_MISMATCH} (the message carries {@code expected <64 hex>}), {@code
 * AS_AT_FIELD_INVALID}, {@code ROW_ID_DUPLICATE}, {@code RULE_REF_DUPLICATE}, {@code
 * ROW_LEG_NOT_IN_REPORT}, {@code LIFECYCLE_KEY_UNKNOWN}, {@code LIFECYCLE_SHAPE}, {@code
 * GOLDEN_JSON_INVALID} and {@code IR_NOT_CANONICAL}, plus every code of the parsers and checks it
 * calls and the code of a {@link PackSyntaxException} (for example {@code YAML_TAB}, {@code
 * PACK_BOM}) at the path and line it names.
 */
public final class PackLoader {
  private PackLoader() {}

  /** Loads a pack; see the class description. */
  public static LoadResult load(PackSource source) {
    return new Run(source).run();
  }

  private enum Kind {
    MANIFEST,
    REGISTRY,
    INTERPRETATIONS,
    OPAQUE,
    INPUT,
    SCOPE,
    AUTHORSHIP,
    GOLDEN,
    SEAL,
    PROPERTY,
    UNKNOWN
  }

  private static final class Run {
    private static final String INTERPRETATIONS = "interpretations.yaml";
    private static final String LIFECYCLE = "lifecycle.yaml";
    private static final String AUTHORSHIP = "tests/authorship.yaml";
    private static final String SCOPE_DIR = "scope/";
    private static final String GOLDEN_DIR = "tests/golden/";
    private static final String PROPERTIES_DIR = "tests/properties/";
    private static final List<String> OPAQUE_DIRS =
        List.of("derive/", "route/", "enrich/", "validate/", "endpoints/");
    private static final Set<String> OPAQUE_ROOT = Set.of(LIFECYCLE, "refdata.yaml");
    private static final Set<String> REGISTRIES =
        Set.of(
            RegistryParser.REASONS_FILE,
            RegistryParser.SOURCES_FILE,
            RegistryParser.RATIONALES_FILE);
    private static final Set<String> LIFECYCLE_KEYS =
        Set.of(
            "business_key",
            "record_key",
            "trn",
            "identifiers",
            "status_model",
            "corrections",
            "successor_gate",
            "due_at",
            "provenance",
            "examples");
    private static final Set<String> CLOCK_REQUIRED = Set.of("anchor", "offset");
    private static final Set<String> CLOCK_ALLOWED = Set.of("anchor", "offset", "on_expiry");

    private final PackSource source;
    private final ProblemSink problems = ProblemSink.forLoad();
    private final List<String> notes = new ArrayList<>();
    private final Map<String, YamlNode> opaque = new TreeMap<>();
    private final Map<String, Object> golden = new TreeMap<>();
    private final Map<String, DecisionTable> tables = new TreeMap<>();
    private YamlNode authorship;

    Run(PackSource source) {
      this.source = source;
    }

    private void problem(String code, String path, int line, String message) {
      problems.add(new PackProblem(code, path, line, message));
    }

    /**
     * Runs a reader that reports into a list and moves what it reported into the bounded sink of
     * the load.
     */
    private <T> T collecting(Function<List<PackProblem>, T> reader) {
      List<PackProblem> local = new ArrayList<>();
      T result = reader.apply(local);
      problems.addAll(local);
      return result;
    }

    private static Kind kind(String path) {
      switch (path) {
        case PackManifest.FILE:
          return Kind.MANIFEST;
        case INTERPRETATIONS:
          return Kind.INTERPRETATIONS;
        case AUTHORSHIP:
          return Kind.AUTHORSHIP;
        case CanonicalInputSpec.FILE:
          return Kind.INPUT;
        case PackSource.CHECKSUMS, PackSource.ATTESTATION:
          return Kind.SEAL;
        default:
          break;
      }
      if (REGISTRIES.contains(path)) {
        return Kind.REGISTRY;
      }
      if (OPAQUE_ROOT.contains(path)) {
        return Kind.OPAQUE;
      }
      if (path.startsWith(PROPERTIES_DIR)) {
        return Kind.PROPERTY;
      }
      if (path.startsWith(SCOPE_DIR) && path.endsWith(".yaml") && isDirectChild(path, SCOPE_DIR)) {
        return Kind.SCOPE;
      }
      if (path.startsWith(GOLDEN_DIR)
          && path.endsWith(".json")
          && isDirectChild(path, GOLDEN_DIR)) {
        return Kind.GOLDEN;
      }
      for (String dir : OPAQUE_DIRS) {
        if (path.startsWith(dir) && path.endsWith(".yaml")) {
          return Kind.OPAQUE;
        }
      }
      return Kind.UNKNOWN;
    }

    private static boolean isDirectChild(String path, String dir) {
      return path.indexOf('/', dir.length()) < 0;
    }

    /** Parses a pack file; a syntax problem is recorded and null returned. */
    private YamlNode yaml(String path) {
      try {
        return StrictYaml.parse(source.text(path));
      } catch (PackSyntaxException e) {
        problem(e.code(), path, e.line(), "the file is not valid: " + e.excerpt());
        return null;
      }
    }

    LoadResult run() {
      problems.addAll(Checksums.verify(source));
      paths();

      Optional<PackManifest> manifest = collecting(list -> PackManifest.parse(source, list));
      Optional<CanonicalInputSpec> input = input();
      manifest.ifPresent(m -> checkInputAgainstManifest(m, input));
      if (manifest.isPresent()) {
        reports(manifest.get());
      }
      YamlNode reasonsRoot = registryRoot(RegistryParser.REASONS_FILE);
      YamlNode sourcesRoot = registryRoot(RegistryParser.SOURCES_FILE);
      YamlNode rationalesRoot = registryRoot(RegistryParser.RATIONALES_FILE);
      Optional<ReasonRegistry> reasons =
          reasonsRoot == null
              ? Optional.empty()
              : collecting(list -> RegistryParser.reasons(reasonsRoot, list));
      Optional<SourceRegistry> sources =
          sourcesRoot == null
              ? Optional.empty()
              : collecting(list -> RegistryParser.sources(sourcesRoot, list));
      Optional<RationaleRegistry> rationales =
          rationalesRoot == null
              ? Optional.empty()
              : collecting(list -> RegistryParser.rationales(rationalesRoot, list));
      if (reasons.isPresent() && sources.isPresent() && rationales.isPresent()) {
        problems.addAll(
            RegistryChecks.check(
                reasons.get(), sources.get(), rationales.get(), new ArrayList<>(tables.values())));
      }
      uniqueIds();
      manifest.ifPresent(this::rowLegs);
      sealedAndOpaqueFiles();

      // sorted, the markers last; a duplicate is reported once
      List<PackProblem> found = problems.sorted(PackManifest.FILE).stream().distinct().toList();
      if (found.isEmpty()
          && (manifest.isEmpty()
              || input.isEmpty()
              || reasons.isEmpty()
              || sources.isEmpty()
              || rationales.isEmpty())) {
        found =
            List.of(
                new PackProblem("LOAD_INCOMPLETE", "pack.yaml", 0, "a required part is missing"));
      }
      if (!found.isEmpty()) {
        return new LoadResult(Optional.empty(), found);
      }
      return assemble(manifest.get(), input.get(), reasons.get(), sources.get(), rationales.get());
    }

    private LoadResult assemble(
        PackManifest manifest,
        CanonicalInputSpec input,
        ReasonRegistry reasons,
        SourceRegistry sources,
        RationaleRegistry rationales) {
      Sha256 placeholder = new Sha256("0".repeat(64));
      Optional<YamlNode> authorshipNode = Optional.ofNullable(authorship);
      LoadedPack provisional =
          new LoadedPack(
              manifest,
              input,
              tables,
              reasons,
              sources,
              rationales,
              opaque,
              golden,
              authorshipNode,
              source,
              placeholder,
              notes);
      Sha256 digest;
      try {
        digest = PackDigest.of(provisional);
      } catch (IllegalArgumentException e) {
        return new LoadResult(
            Optional.empty(),
            List.of(
                new PackProblem(
                    "IR_NOT_CANONICAL",
                    "pack.yaml",
                    0,
                    "the pack has no canonical form: "
                        + PackProblem.excerpt(String.valueOf(e.getMessage())))));
      }
      return new LoadResult(
          Optional.of(
              new LoadedPack(
                  manifest,
                  input,
                  tables,
                  reasons,
                  sources,
                  rationales,
                  opaque,
                  golden,
                  authorshipNode,
                  source,
                  digest,
                  notes)),
          List.of());
    }

    // -- allowed paths ----------------------------------------------------------------------

    /** The allowed-path check; the reserved property-test directory is reported first. */
    private void paths() {
      for (String path : source.paths()) {
        switch (kind(path)) {
          case PROPERTY ->
              problem(
                  "PROPERTY_TESTS_RESERVED",
                  path,
                  0,
                  "property tests are reserved and not accepted in this format");
          case UNKNOWN ->
              problem("FILE_UNKNOWN", path, 0, "the path is not part of the pack format");
          default -> {
            // allowed
          }
        }
      }
    }

    // -- input model ------------------------------------------------------------------------

    private Optional<CanonicalInputSpec> input() {
      if (!source.has(CanonicalInputSpec.FILE)) {
        return Optional.empty();
      }
      YamlNode root = yaml(CanonicalInputSpec.FILE);
      return root == null
          ? Optional.empty()
          : collecting(list -> CanonicalInputSpec.parse(root, list));
    }

    private void checkInputAgainstManifest(PackManifest m, Optional<CanonicalInputSpec> input) {
      if (input.isEmpty()) {
        return;
      }
      String expected = input.get().digest().hex();
      if (!m.inputSchema().equals(expected)) {
        problem(
            "INPUT_SCHEMA_DIGEST_MISMATCH",
            PackManifest.FILE,
            0,
            "input_schema is not the digest of the input model; expected " + expected);
      }
      CanonicalInputSpec.Variable asAt = input.get().variables().get(m.asAtField());
      if (asAt == null || asAt.type() != ValueType.TIMESTAMP) {
        problem(
            "AS_AT_FIELD_INVALID",
            PackManifest.FILE,
            0,
            "as_at_field "
                + PackProblem.excerpt(m.asAtField())
                + " is not a declared TIMESTAMP variable");
      }
    }

    // -- reports and tables -----------------------------------------------------------------

    private void reports(PackManifest manifest) {
      TreeSet<String> referenced = new TreeSet<>();
      for (ReportDefinition report : manifest.reports()) {
        String who = "report " + PackProblem.excerpt(report.id());
        exists(report.input(), who);
        if (!report.input().equals(CanonicalInputSpec.FILE)) {
          problem(
              "REPORT_INPUT_UNSUPPORTED",
              PackManifest.FILE,
              0,
              who + " names an input model other than " + CanonicalInputSpec.FILE);
        }
        for (String path : report.scopeTables()) {
          if (!path.startsWith(SCOPE_DIR) || !isDirectChild(path, SCOPE_DIR)) {
            problem(
                "REPORT_PATH_INVALID",
                PackManifest.FILE,
                0,
                who + " lists the scope table " + PackProblem.excerpt(path) + " outside scope/");
          } else if (exists(path, who)) {
            referenced.add(path);
          }
        }
        report.deriveTables().forEach(path -> exists(path, who));
        report.validate().forEach(path -> exists(path, who));
        report.lifecycle().ifPresent(path -> exists(path, who));
        report.endpointProfiles().forEach(path -> exists(path, who));
      }
      for (String path : referenced) {
        if (problems.truncated()) {
          break; // the load fails anyway; do not parse the remaining tables of a hostile pack
        }
        YamlNode root = yaml(path);
        if (root == null) {
          continue;
        }
        Optional<DecisionTable> table = collecting(list -> TableParser.parse(path, root, list));
        if (table.isPresent()) {
          problems.addAll(TableChecks.check(table.get()));
          tables.put(path, table.get());
        }
      }
      for (String path : source.paths()) {
        if (kind(path) == Kind.SCOPE && !referenced.contains(path)) {
          problem("FILE_UNREFERENCED", path, 0, "no report lists this scope table");
        }
      }
    }

    private boolean exists(String path, String who) {
      if (source.has(path)) {
        return true;
      }
      problem("REPORT_FILE_MISSING", path, 0, who + " lists a file that is not in the pack");
      return false;
    }

    // -- registries -------------------------------------------------------------------------

    /** The parsed tree of a registry file; null (with a problem) when it is absent or invalid. */
    private YamlNode registryRoot(String path) {
      if (!source.has(path)) {
        problem("REGISTRY_FILE_MISSING", path, 0, "the pack has no " + path);
        return null;
      }
      return yaml(path);
    }

    // -- cross-table uniqueness -------------------------------------------------------------

    private void uniqueIds() {
      Map<String, String> idPath = new HashMap<>();
      Map<String, String> refPath = new HashMap<>();
      for (DecisionTable table : tables.values()) {
        for (TableRow row : table.rows()) {
          if (problems.truncated()) {
            return;
          }
          String earlier = idPath.putIfAbsent(row.id(), table.path());
          if (earlier != null && !earlier.equals(table.path())) {
            problem(
                "ROW_ID_DUPLICATE",
                table.path(),
                0,
                "row id "
                    + PackProblem.excerpt(row.id())
                    + " is already used in "
                    + PackProblem.excerpt(earlier));
          }
          if (row.ruleRef().isPresent()) {
            String ref = row.ruleRef().get();
            String first = refPath.putIfAbsent(ref, table.path() + "#" + row.id());
            if (first != null) {
              problem(
                  "RULE_REF_DUPLICATE",
                  table.path(),
                  0,
                  "rule_ref "
                      + PackProblem.excerpt(ref)
                      + " of row "
                      + PackProblem.excerpt(row.id())
                      + " is used twice in the pack");
            }
          }
        }
      }
    }

    private void rowLegs(PackManifest manifest) {
      for (ReportDefinition report : manifest.reports()) {
        for (String path : report.scopeTables()) {
          DecisionTable table = tables.get(path);
          if (table == null) {
            continue;
          }
          for (TableRow row : table.rows()) {
            if (problems.truncated()) {
              return;
            }
            for (Leg leg : row.legs()) {
              if (!report.legs().contains(leg)) {
                problem(
                    "ROW_LEG_NOT_IN_REPORT",
                    path,
                    0,
                    "row "
                        + PackProblem.excerpt(row.id())
                        + " has the leg "
                        + leg
                        + ", which report "
                        + PackProblem.excerpt(report.id())
                        + " does not list");
              }
            }
          }
        }
      }
    }

    // -- reserved, not interpreted, golden, authorship --------------------------------------

    private void sealedAndOpaqueFiles() {
      boolean attestation = false;
      for (String path : source.paths()) {
        switch (kind(path)) {
          case INTERPRETATIONS -> interpretations();
          case OPAQUE -> opaqueFile(path);
          case AUTHORSHIP -> authorship = yaml(path);
          case GOLDEN -> goldenFile(path);
          case SEAL -> attestation |= path.equals(PackSource.ATTESTATION);
          default -> {
            // typed elsewhere, or already reported
          }
        }
      }
      if (attestation) {
        notes.add("attestation present, not verified (P9b)");
      }
    }

    private void interpretations() {
      YamlNode root = yaml(INTERPRETATIONS);
      if (root == null) {
        return;
      }
      boolean empty =
          root instanceof YamlMap m
              && m.entries().size() == 1
              && m.entries().get("pins") instanceof YamlList pins
              && pins.items().isEmpty();
      if (!empty) {
        problem(
            "INTERPRETATIONS_RESERVED",
            INTERPRETATIONS,
            root.line(),
            "interpretation pins are reserved and accepted only as 'pins: []' (P2a-2)");
      }
      opaque.put(INTERPRETATIONS, root);
    }

    private void opaqueFile(String path) {
      YamlNode root = yaml(path);
      if (root == null) {
        return;
      }
      opaque.put(path, root);
      notes.add("not interpreted by this engine build: " + path);
      if (path.equals(LIFECYCLE)) {
        lifecycle(root);
      }
    }

    /** BRK-16: the top-level keys of {@code lifecycle.yaml}; the content is not interpreted. */
    private void lifecycle(YamlNode root) {
      if (!(root instanceof YamlMap m)) {
        problem("LIFECYCLE_SHAPE", LIFECYCLE, root.line(), "lifecycle.yaml must be a mapping");
        return;
      }
      for (Map.Entry<String, YamlNode> e : m.entries().entrySet()) {
        if (!LIFECYCLE_KEYS.contains(e.getKey()) && !namedClock(e.getValue())) {
          problem(
              "LIFECYCLE_KEY_UNKNOWN",
              LIFECYCLE,
              e.getValue().line(),
              "'"
                  + PackProblem.excerpt(e.getKey())
                  + "' is neither a lifecycle key nor a named clock (anchor, offset)");
        }
      }
    }

    private static boolean namedClock(YamlNode node) {
      return node instanceof YamlMap clock
          && clock.entries().keySet().containsAll(CLOCK_REQUIRED)
          && CLOCK_ALLOWED.containsAll(clock.entries().keySet());
    }

    private void goldenFile(String path) {
      try {
        golden.put(path, Json.parse(source.text(path)));
      } catch (PackSyntaxException e) {
        problem(e.code(), path, e.line(), "the file is not valid: " + e.excerpt());
      } catch (JsonException e) {
        problem(
            "GOLDEN_JSON_INVALID",
            path,
            0,
            "the golden file is not valid JSON: "
                + PackProblem.excerpt(String.valueOf(e.getMessage())));
      }
    }
  }
}
