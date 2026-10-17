package dev.regpack.pack.check;

import dev.regpack.model.Sha256;
import dev.regpack.pack.model.CitationRef;
import dev.regpack.pack.model.DecisionTable;
import dev.regpack.pack.model.Provision;
import dev.regpack.pack.model.RationaleRegistry;
import dev.regpack.pack.model.ReasonEntry;
import dev.regpack.pack.model.ReasonRegistry;
import dev.regpack.pack.model.RegistryParser;
import dev.regpack.pack.model.SourceEntry;
import dev.regpack.pack.model.SourceRegistry;
import dev.regpack.pack.model.TableRow;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The cross-checks between the reason-code registry, the Legal Source Registry, the rationales and
 * the scope tables (spec 6.3, 6.4 and BRK-11; plan task T8, review focus PF5): a declared reason
 * matches the prefix and the nodes of the header and the label of its code and is not engine
 * reserved; a table row uses a declared reason whose default outcome is the row's outcome, a
 * declared node and a declared rationale; every citation resolves to a provision that is in force
 * and has the cited role; a stored quote is at most {@value #MAX_QUOTE_CODE_POINTS} code points (a
 * plan limit, provisional) and its hash is the SHA-256 of the quote as parsed.
 *
 * <p>CI proves that citations resolve and that quote hashes match; it cannot prove the law was read
 * correctly (spec 6.4).
 *
 * <p>Problem codes: {@code REGISTRY_KEY_MISMATCH}, {@code PREFIX_RESERVED}, {@code
 * REASON_RESERVED}, {@code REASON_PREFIX_MISMATCH}, {@code REASON_SHAPE_INVALID}, {@code
 * REASON_NODE_UNDECLARED}, {@code REASON_LABEL_MISMATCH}, {@code REASON_VALIDITY_INVERTED}, {@code
 * QUOTE_TOO_LONG}, {@code QUOTE_HASH_MISSING}, {@code QUOTE_HASH_MISMATCH} (its message carries
 * {@code expected <64 hex>}), {@code TABLE_ID_DUPLICATE}, {@code ROW_REASON_UNKNOWN}, {@code
 * REASON_OUTCOME_MISMATCH}, {@code ROW_NODE_UNDECLARED}, {@code ROW_RATIONALE_UNKNOWN}, {@code
 * CITATION_SOURCE_UNKNOWN}, {@code CITATION_PROVISION_UNKNOWN}, {@code PROVISION_NOT_IN_FORCE} and
 * {@code CITATION_ROLE_MISMATCH}. Registry problems name the registry file and table problems the
 * table file; the models carry no source lines, so every problem has line 0. A hash stored without
 * a quote is a pin with nothing to compare, and is accepted.
 */
public final class RegistryChecks {
  /** The longest stored quote, in code points. */
  public static final int MAX_QUOTE_CODE_POINTS = 240;

  /** Reason codes that only the engine produces; a pack may not declare them. */
  public static final Set<String> ENGINE_RESERVED =
      Set.of("EXEC_ERROR", "EXEC_BUDGET", "EXEC_CIRCUIT_OPEN", "ENRICH_MISS");

  private RegistryChecks() {}

  /**
   * The problems of the registries and of the way the tables use them, sorted; empty when the set
   * is consistent.
   */
  public static List<PackProblem> check(
      ReasonRegistry r, SourceRegistry s, RationaleRegistry t, List<DecisionTable> tables) {
    List<PackProblem> found = new ArrayList<>();
    reasons(r, found);
    sources(s, found);
    tables(r, s, t, tables, found);
    Collections.sort(found);
    return found;
  }

  private static void add(List<PackProblem> found, String code, String path, String message) {
    found.add(new PackProblem(code, path, 0, message));
  }

  // -- reasons.yaml ----------------------------------------------------------------------------

  private static void reasons(ReasonRegistry r, List<PackProblem> found) {
    String file = RegistryParser.REASONS_FILE;
    if (ENGINE_RESERVED.contains(r.prefix())) {
      add(
          found,
          "PREFIX_RESERVED",
          file,
          "the prefix " + PackProblem.excerpt(r.prefix()) + " is an engine-reserved reason code");
    }
    for (Map.Entry<String, ReasonEntry> e : r.entries().entrySet()) {
      ReasonEntry entry = e.getValue();
      String code = entry.code().value();
      String shown = PackProblem.excerpt(code);
      if (!e.getKey().equals(code)) {
        add(
            found,
            "REGISTRY_KEY_MISMATCH",
            file,
            "the reason stored under " + PackProblem.excerpt(e.getKey()) + " is " + shown);
      }
      if (ENGINE_RESERVED.contains(code)) {
        add(found, "REASON_RESERVED", file, "reason " + shown + " is engine-reserved");
      } else {
        reasonShape(r, entry, shown, found);
      }
      if (entry.validTo().isPresent() && !entry.validTo().get().isAfter(entry.validFrom())) {
        add(
            found,
            "REASON_VALIDITY_INVERTED",
            file,
            "reason " + shown + " has a validity whose end is not after its start");
      }
    }
  }

  /** The code must be {@code <prefix>.<node>.<label>[.<provision>]} of the registry's header. */
  private static void reasonShape(
      ReasonRegistry r, ReasonEntry entry, String shown, List<PackProblem> found) {
    String file = RegistryParser.REASONS_FILE;
    String[] parts = entry.code().value().split("\\.", -1);
    if (parts.length < 3 || parts.length > 4) {
      add(
          found,
          "REASON_SHAPE_INVALID",
          file,
          "reason " + shown + " is not <PREFIX>.<node>.<label>[.<provision>]");
      return;
    }
    if (!parts[0].equals(r.prefix())) {
      add(
          found,
          "REASON_PREFIX_MISMATCH",
          file,
          "reason "
              + shown
              + " does not start with the declared prefix "
              + PackProblem.excerpt(r.prefix()));
    }
    if (!r.nodes().contains(parts[1])) {
      add(
          found,
          "REASON_NODE_UNDECLARED",
          file,
          "reason "
              + shown
              + " names node "
              + PackProblem.excerpt(parts[1])
              + ", which is not declared");
    }
    if (!parts[2].equals(entry.label())) {
      add(
          found,
          "REASON_LABEL_MISMATCH",
          file,
          "reason "
              + shown
              + " has label segment "
              + PackProblem.excerpt(parts[2])
              + " but label "
              + PackProblem.excerpt(entry.label()));
    }
  }

  // -- sources.yaml ----------------------------------------------------------------------------

  private static void sources(SourceRegistry s, List<PackProblem> found) {
    String file = RegistryParser.SOURCES_FILE;
    for (Map.Entry<String, SourceEntry> se : s.sources().entrySet()) {
      SourceEntry source = se.getValue();
      String sourceShown = PackProblem.excerpt(source.sourceId());
      if (!se.getKey().equals(source.sourceId())) {
        add(
            found,
            "REGISTRY_KEY_MISMATCH",
            file,
            "the source stored under " + PackProblem.excerpt(se.getKey()) + " is " + sourceShown);
      }
      for (Map.Entry<String, Provision> pe : source.provisions().entrySet()) {
        Provision p = pe.getValue();
        String where = sourceShown + " " + PackProblem.excerpt(p.path());
        if (!pe.getKey().equals(p.path())) {
          add(
              found,
              "REGISTRY_KEY_MISMATCH",
              file,
              "the provision stored under " + PackProblem.excerpt(pe.getKey()) + " is " + where);
        }
        quote(p, where, found);
      }
    }
  }

  private static void quote(Provision p, String where, List<PackProblem> found) {
    String file = RegistryParser.SOURCES_FILE;
    if (p.quote().isEmpty()) {
      return;
    }
    String quote = p.quote().get();
    if (quote.codePointCount(0, quote.length()) > MAX_QUOTE_CODE_POINTS) {
      add(
          found,
          "QUOTE_TOO_LONG",
          file,
          "the quote of " + where + " is longer than " + MAX_QUOTE_CODE_POINTS + " characters");
    }
    Sha256 expected = Sha256.ofUtf8(quote);
    if (p.quoteSha256().isEmpty()) {
      add(
          found,
          "QUOTE_HASH_MISSING",
          file,
          "the quote of " + where + " has no quote_sha256; expected " + expected.hex());
    } else if (!p.quoteSha256().get().equals(expected)) {
      add(
          found,
          "QUOTE_HASH_MISMATCH",
          file,
          "expected " + expected.hex() + " for the quote of " + where);
    }
  }

  // -- tables ----------------------------------------------------------------------------------

  private static void tables(
      ReasonRegistry r,
      SourceRegistry s,
      RationaleRegistry t,
      List<DecisionTable> tables,
      List<PackProblem> found) {
    Map<String, Integer> ids = new HashMap<>();
    for (DecisionTable table : tables) {
      ids.merge(table.id(), 1, Integer::sum);
    }
    List<String> reported = new ArrayList<>();
    for (DecisionTable table : tables) {
      if (ids.get(table.id()) > 1 && !reported.contains(table.id())) {
        reported.add(table.id());
        add(
            found,
            "TABLE_ID_DUPLICATE",
            table.path(),
            "table id "
                + PackProblem.excerpt(table.id())
                + " is used by "
                + ids.get(table.id())
                + " tables");
      }
      for (TableRow row : table.rows()) {
        row(r, s, t, table, row, found);
      }
    }
  }

  private static void row(
      ReasonRegistry r,
      SourceRegistry s,
      RationaleRegistry t,
      DecisionTable table,
      TableRow row,
      List<PackProblem> found) {
    String path = table.path();
    String id = PackProblem.excerpt(row.id());
    ReasonEntry reason = r.entries().get(row.reason().value());
    if (reason == null) {
      add(
          found,
          "ROW_REASON_UNKNOWN",
          path,
          "row " + id + " uses the undeclared reason " + PackProblem.excerpt(row.reason().value()));
    } else if (reason.defaultOutcome() != row.outcome()) {
      add(
          found,
          "REASON_OUTCOME_MISMATCH",
          path,
          "row "
              + id
              + " has outcome "
              + row.outcome()
              + " but its reason has "
              + reason.defaultOutcome());
    }
    if (!r.nodes().contains(row.node())) {
      add(
          found,
          "ROW_NODE_UNDECLARED",
          path,
          "row " + id + " has node " + PackProblem.excerpt(row.node()) + ", which is not declared");
    }
    if (!t.texts().containsKey(row.rationale())) {
      add(
          found,
          "ROW_RATIONALE_UNKNOWN",
          path,
          "row " + id + " has the unknown rationale " + PackProblem.excerpt(row.rationale()));
    }
    for (CitationRef cite : row.cite()) {
      citation(s, path, id, cite, found);
    }
  }

  private static void citation(
      SourceRegistry s, String path, String rowId, CitationRef cite, List<PackProblem> found) {
    String where = "row " + rowId + " cites ";
    if (!s.sources().containsKey(cite.sourceId())) {
      add(
          found,
          "CITATION_SOURCE_UNKNOWN",
          path,
          where + "the unknown source " + PackProblem.excerpt(cite.sourceId()));
      return;
    }
    Optional<Provision> provision = s.resolve(cite.sourceId(), cite.provisionPath());
    if (provision.isEmpty()) {
      add(
          found,
          "CITATION_PROVISION_UNKNOWN",
          path,
          where
              + "the unknown provision "
              + PackProblem.excerpt(cite.provisionPath())
              + " of "
              + PackProblem.excerpt(cite.sourceId()));
      return;
    }
    Provision p = provision.get();
    if (p.status() != Provision.Status.IN_FORCE) {
      add(
          found,
          "PROVISION_NOT_IN_FORCE",
          path,
          where
              + "provision "
              + PackProblem.excerpt(cite.provisionPath())
              + ", which is "
              + p.status());
    }
    if (p.role() != cite.role()) {
      add(
          found,
          "CITATION_ROLE_MISMATCH",
          path,
          where
              + PackProblem.excerpt(cite.provisionPath())
              + " as "
              + cite.role()
              + " but the registry says "
              + p.role());
    }
  }
}
