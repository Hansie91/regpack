package dev.regpack.pack.ir;

import dev.regpack.model.Leg;
import dev.regpack.pack.PackFormat;
import dev.regpack.pack.model.CanonicalInputSpec;
import dev.regpack.pack.model.CitationRef;
import dev.regpack.pack.model.DecisionTable;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.pack.model.PackLimits;
import dev.regpack.pack.model.PackManifest;
import dev.regpack.pack.model.Provision;
import dev.regpack.pack.model.RationaleRegistry;
import dev.regpack.pack.model.ReasonEntry;
import dev.regpack.pack.model.ReasonRegistry;
import dev.regpack.pack.model.ReportDefinition;
import dev.regpack.pack.model.SourceEntry;
import dev.regpack.pack.model.SourceRegistry;
import dev.regpack.pack.model.TableRow;
import dev.regpack.pack.model.Validity;
import dev.regpack.pack.yaml.YamlList;
import dev.regpack.pack.yaml.YamlMap;
import dev.regpack.pack.yaml.YamlNode;
import dev.regpack.pack.yaml.YamlScalar;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The canonical intermediate representation of a loaded pack (IR format 1; provisional until a
 * G-S3b pass): a tree of {@code Map}, {@code List}, {@code String}, {@code Boolean} and {@code
 * Long} (and the {@code BigDecimal} of golden files) that {@code Json.canonical} writes byte-exact.
 *
 * <p>{@code {"formatVersion":1,"packId":...,"files":{<path>: <value>}}} covers every file of the
 * pack except {@code CHECKSUMS} and {@code ATTESTATION}. Typed files are written from their typed
 * model, so everything that does not change the pack's meaning is gone: comments, key order, row
 * order (rows ascend by priority; {@code set} and table {@code inputs} are sorted; registries are
 * maps by id; the reports are a map by id and their leg vocabulary is sorted), line ends and the
 * spelling of numbers and dates; absent optionals are omitted and the legs of a REPORT row are
 * explicit. The order of a report's {@code scope_tables}, of the citations of a row and of the legs
 * of a row is content and is kept. Opaque YAML files and golden files are generic trees.
 */
public final class PackIr {
  private PackIr() {}

  /** The canonical-JSON-ready IR of a loaded pack (its {@code digest} component is not used). */
  public static Map<String, Object> toMap(LoadedPack pack) {
    Map<String, Object> files = new TreeMap<>();
    files.put(PackManifest.FILE, manifest(pack.manifest()));
    files.put(CanonicalInputSpec.FILE, pack.input().toIr());
    pack.scopeTables().forEach((path, table) -> files.put(path, table(table)));
    files.put("reasons.yaml", reasons(pack.reasons()));
    files.put("sources.yaml", sources(pack.sources()));
    files.put("rationales.yaml", rationales(pack.rationales()));
    pack.opaque().forEach((path, node) -> files.put(path, generic(node)));
    pack.authorship().ifPresent(node -> files.put("tests/authorship.yaml", generic(node)));
    pack.goldenJson().forEach(files::put);

    Map<String, Object> ir = new LinkedHashMap<>();
    ir.put("formatVersion", (long) PackFormat.VERSION);
    ir.put("packId", pack.manifest().id());
    ir.put("files", files);
    return ir;
  }

  // -- pack.yaml --------------------------------------------------------------------------------

  private static Map<String, Object> manifest(PackManifest m) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", m.id());
    out.put("semver", m.version().major() + "." + m.version().minor() + "." + m.version().patch());
    out.put("regime", m.regime());
    out.put("jurisdiction", m.jurisdiction());
    Map<String, Object> legal = new LinkedHashMap<>();
    legal.put("from", m.validFrom().toString());
    m.validTo().ifPresent(to -> legal.put("to", to.toString()));
    out.put("valid", Map.of("legal", legal));
    out.put("as_at_field", m.asAtField());
    out.put("engine_min", m.engineMin().toString());
    out.put("input_schema", m.inputSchema());
    m.supersedes().ifPresent(s -> out.put("supersedes", s));
    out.put("limits", limits(m.limits()));
    Map<String, Object> reports = new LinkedHashMap<>();
    for (ReportDefinition r : m.reports()) {
      reports.put(r.id(), report(r));
    }
    out.put("reports", reports);
    return out;
  }

  private static Map<String, Object> limits(PackLimits l) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("rule_nodes", (long) l.ruleNodes());
    out.put("list_items", (long) l.listItems());
    out.put("string_code_points", (long) l.stringCodePoints());
    l.recordBudget().ifPresent(b -> out.put("record_budget", b));
    return out;
  }

  private static Map<String, Object> report(ReportDefinition r) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("input", r.input());
    out.put("scope_tables", new ArrayList<>(r.scopeTables()));
    out.put("derive_tables", new ArrayList<>(r.deriveTables()));
    out.put("validate", new ArrayList<>(r.validate()));
    r.lifecycle().ifPresent(l -> out.put("lifecycle", l));
    out.put("endpoint_profiles", new ArrayList<>(r.endpointProfiles()));
    List<String> legs = new ArrayList<>();
    for (Leg leg : r.legs()) {
      legs.add(leg.name());
    }
    legs.sort(String::compareTo);
    out.put("legs", legs);
    return out;
  }

  // -- scope tables -----------------------------------------------------------------------------

  /** The IR map of one decision table (public so that the CSV round-trip test can compare it). */
  public static Map<String, Object> table(DecisionTable t) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", t.id());
    out.put("catch_all", t.catchAllRowId());
    out.put("inputs", t.inputs().stream().distinct().sorted().toList());
    List<Object> rows = new ArrayList<>();
    for (TableRow row : t.rows()) {
      rows.add(row(row));
    }
    out.put("rows", rows);
    return out;
  }

  private static Map<String, Object> row(TableRow r) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", r.id());
    out.put("node", r.node());
    out.put("priority", (long) r.priority());
    out.put("when", r.when());
    out.put("outcome", r.outcome().name());
    out.put("reason", r.reason().value());
    r.ruleRef().ifPresent(ref -> out.put("rule_ref", ref));
    List<Object> cite = new ArrayList<>();
    for (CitationRef c : r.cite()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("source_id", c.sourceId());
      m.put("provision_path", c.provisionPath());
      m.put("role", c.role().name());
      cite.add(m);
    }
    out.put("cite", cite);
    out.put("rationale", r.rationale());
    r.valid().ifPresent(v -> out.put("valid", validity(v)));
    if (!r.set().isEmpty()) {
      out.put("set", new TreeMap<>(r.set()));
    }
    if (!r.legs().isEmpty()) {
      List<String> legs = new ArrayList<>();
      for (Leg leg : r.legs()) {
        legs.add(leg.name());
      }
      out.put("legs", legs);
    }
    return out;
  }

  private static Map<String, Object> validity(Validity v) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("from", v.from().toString());
    v.to().ifPresent(to -> out.put("to", to.toString()));
    return out;
  }

  // -- registries -------------------------------------------------------------------------------

  private static Map<String, Object> reasons(ReasonRegistry r) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("prefix", r.prefix());
    out.put("nodes", new ArrayList<>(r.nodes()));
    Map<String, Object> entries = new LinkedHashMap<>();
    for (Map.Entry<String, ReasonEntry> e : r.entries().entrySet()) {
      ReasonEntry entry = e.getValue();
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("code", entry.code().value());
      m.put("label", entry.label());
      m.put("category", entry.category().name());
      m.put("default_outcome", entry.defaultOutcome().name());
      m.put("owner", entry.owner());
      Map<String, Object> valid = new LinkedHashMap<>();
      valid.put("from", entry.validFrom().toString());
      entry.validTo().ifPresent(to -> valid.put("to", to.toString()));
      m.put("valid", valid);
      entries.put(e.getKey(), m);
    }
    out.put("reasons", entries);
    return out;
  }

  private static Map<String, Object> sources(SourceRegistry s) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, SourceEntry> e : s.sources().entrySet()) {
      SourceEntry source = e.getValue();
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("version", source.version());
      m.put("retrieved_at", source.retrievedAt().toString());
      m.put("content_sha256", source.contentSha256().hex());
      source.eli().ifPresent(eli -> m.put("eli", eli));
      Map<String, Object> provisions = new LinkedHashMap<>();
      for (Map.Entry<String, Provision> p : source.provisions().entrySet()) {
        Provision provision = p.getValue();
        Map<String, Object> pm = new LinkedHashMap<>();
        pm.put("role", provision.role().name());
        pm.put("status", provision.status().name());
        provision.quote().ifPresent(q -> pm.put("quote", q));
        provision.quoteSha256().ifPresent(h -> pm.put("quote_sha256", h.hex()));
        provisions.put(p.getKey(), pm);
      }
      m.put("provisions", provisions);
      out.put(e.getKey(), m);
    }
    return Map.of("sources", out);
  }

  private static Map<String, Object> rationales(RationaleRegistry r) {
    return Map.of("rationales", new LinkedHashMap<String, Object>(r.texts()));
  }

  // -- opaque files -----------------------------------------------------------------------------

  private static Object generic(YamlNode node) {
    return switch (node) {
      case YamlScalar s -> s.value();
      case YamlList l -> {
        List<Object> out = new ArrayList<>();
        for (YamlNode item : l.items()) {
          out.add(generic(item));
        }
        yield out;
      }
      case YamlMap m -> {
        Map<String, Object> out = new LinkedHashMap<>();
        m.entries().forEach((k, v) -> out.put(k, generic(v)));
        yield out;
      }
    };
  }
}
