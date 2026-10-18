package dev.regpack.engine.packci;

import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.engine.expr.Rulings;
import dev.regpack.engine.pack.CanonicalBindings;
import dev.regpack.engine.pack.CompiledPack;
import dev.regpack.engine.pack.EngineInfo;
import dev.regpack.engine.pack.PackBudget;
import dev.regpack.engine.pack.PackRegime;
import dev.regpack.engine.packci.CoverageGates.CaseOutcome;
import dev.regpack.engine.packci.CoverageGates.Coverage;
import dev.regpack.engine.packci.CoverageGates.CoverageResult;
import dev.regpack.model.IngestKey;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.CanonicalExecutionCodec;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.PathStep;
import dev.regpack.pack.PackFormat;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.check.ProblemSink;
import dev.regpack.pack.golden.GoldenAudit;
import dev.regpack.pack.golden.GoldenCase;
import dev.regpack.pack.golden.GoldenCase.ProvenanceKind;
import dev.regpack.pack.golden.PackGoldenLoader;
import dev.regpack.pack.golden.ReviewerRegistry;
import dev.regpack.pack.model.LoadResult;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.pack.model.PackLoader;
import dev.regpack.pack.model.PackManifest;
import dev.regpack.pack.source.PackSource;
import dev.regpack.ports.regime.RegimeProvider.EvaluationContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The pack-level test of spec 6.5 (plan T20): load and check the pack, run every golden case
 * through the pack-driven regime, apply the coverage gates and audit the provenance of the cases.
 * It is a pure function of the files: no clock, no environment, and the output is the same on every
 * run and operating system.
 *
 * <p>The pack is checked in this order, and the run stops at the first stage that has problems
 * (nothing is evaluated on a pack that did not pass the static stage): {@link PackLoader}
 * (CHECKSUMS and every static check); exactly one report ({@code
 * MULTI_REPORT_NOT_SUPPORTED_BY_PACK_TEST}, since golden cases belong to the pack, not to a report,
 * in P2a); {@code engine_min} ({@code ENGINE_MIN_NOT_SATISFIED}); the input model and the
 * expressions ({@link CanonicalBindings#checkSupported}, {@link CompiledPack#compile}); the golden
 * files ({@link PackGoldenLoader}, with the node of every case declared in {@code reasons.yaml}:
 * {@code GOLDEN_NODE_UNDECLARED}). Then each case is evaluated with {@code evaluatedAt} fixed at
 * {@value #EVALUATED_AT}, ingest key {@code golden|<id>|1} and the record budget of the regime. A
 * case passes only if outcome, reason, node and rule hits all equal the expectation ({@code
 * GOLDEN_CASE_FAILED} names each field that differs; {@code GOLDEN_INPUT_INVALID} for an input the
 * canonical codec refuses). Coverage failures are the codes of {@link CoverageGates}; each audit
 * problem of a case with a claimed real provenance is {@code GOLDEN_AUDIT} with the token of {@link
 * GoldenAudit} as message. The pack is never reported as signed: {@code signature: UNSIGNED} is
 * always printed (packs are unsigned until P9b).
 *
 * <p>No line echoes a value of a golden input; ids and texts that come from golden files go through
 * {@link PackProblem}, which bounds and escapes them.
 */
public final class PackTester {
  /** The fixed evaluation instant of golden cases. */
  public static final String EVALUATED_AT = "2026-01-01T00:00:00Z";

  /** The registry name of a run without a reviewer registry file. */
  public static final String NO_REGISTRY = "none (empty registry)";

  /** Longest registry name the audit line shows, after escaping. */
  static final int MAX_REGISTRY_CHARS = 240;

  private static final Instant EVALUATED_AT_INSTANT = Instant.parse(EVALUATED_AT);
  private static final String SIGNATURE_LINE =
      "signature: UNSIGNED (an ATTESTATION is not verified before P9b)";

  private PackTester() {}

  /**
   * Runs the test of one pack; see the class description. {@code registryName} says where the
   * reviewers came from (the path of the registry file, or {@link #NO_REGISTRY}); the audit line
   * prints it, escaped and cut to {@value #MAX_REGISTRY_CHARS} characters, so that a result can be
   * reproduced.
   */
  public static PackTestReport run(
      PackSource source, ReviewerRegistry reviewers, String registryName) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(reviewers, "reviewers");
    Objects.requireNonNull(registryName, "registryName");
    LoadResult loaded = PackLoader.load(source);
    if (loaded.pack().isEmpty()) {
      List<String> lines = new ArrayList<>();
      lines.add(SIGNATURE_LINE);
      lines.add(staticLine(loaded.problems().size()));
      return finish(lines, new ArrayList<>(loaded.problems()));
    }
    return new Run(source, loaded.pack().get(), reviewers, registryName).run();
  }

  private static String staticLine(int count) {
    return "static checks: " + count + " problems";
  }

  private static PackTestReport finish(List<String> lines, List<PackProblem> problems) {
    // the stages add problems in turn: bound what is printed (the loader bounds its own list)
    List<PackProblem> sorted =
        ProblemSink.cap(problems, PackFormat.MAX_PROBLEMS_PER_LOAD, PackManifest.FILE);
    List<String> out = new ArrayList<>(lines);
    for (PackProblem p : sorted) {
      out.add(
          "problem "
              + p.code()
              + " "
              + p.path()
              + (p.line() > 0 ? ":" + p.line() : "")
              + " "
              + p.message());
    }
    out.add(
        sorted.isEmpty() ? "pack test: PASS" : "pack test: FAIL (" + sorted.size() + " problems)");
    return new PackTestReport(out, sorted);
  }

  private static final class Run {
    private final PackSource source;
    private final LoadedPack pack;
    private final ReviewerRegistry reviewers;
    private final String registryName;
    private final List<PackProblem> problems = new ArrayList<>();
    private final List<String> lines = new ArrayList<>();

    Run(PackSource source, LoadedPack pack, ReviewerRegistry reviewers, String registryName) {
      this.source = source;
      this.pack = pack;
      this.reviewers = reviewers;
      this.registryName = PackProblem.bounded(registryName, MAX_REGISTRY_CHARS);
    }

    PackTestReport run() {
      PackManifest manifest = pack.manifest();
      boolean satisfied = EngineInfo.BUILD.satisfies(manifest.engineMin());
      Rulings rulings = Rulings.load();
      Optional<CompiledPack> compiled = staticStage(manifest, satisfied, rulings);
      // a refused multi-report pack stops here: its golden cases are not judged
      PackGoldenLoader.Loaded golden =
          manifest.reports().size() == 1
              ? PackGoldenLoader.load(source)
              : new PackGoldenLoader.Loaded(List.of(), Map.of(), List.of());
      problems.addAll(golden.problems());
      for (GoldenCase c : golden.cases()) {
        if (!pack.reasons().nodes().contains(c.node())) {
          problems.add(
              new PackProblem(
                  "GOLDEN_NODE_UNDECLARED",
                  PackGoldenLoader.GOLDEN_DIR + c.id() + ".json",
                  0,
                  "the node "
                      + PackProblem.excerpt(c.node())
                      + " is not declared in reasons.yaml"));
        }
      }

      int files = 0;
      for (String path : source.paths()) {
        if (!path.equals(PackSource.CHECKSUMS) && !path.equals(PackSource.ATTESTATION)) {
          files++;
        }
      }
      lines.add(
          "pack test: "
              + manifest.id()
              + " "
              + manifest.version()
              + " digest "
              + pack.digest().hex());
      lines.add("files: " + files + " (CHECKSUMS verified)");
      lines.add(SIGNATURE_LINE);
      lines.add(staticLine(problems.size()));
      lines.addAll(pack.notes());
      lines.add(
          "engine: "
              + EngineInfo.BUILD
              + (satisfied ? " satisfies " : " does not satisfy ")
              + "engine_min "
              + manifest.engineMin());
      if (!problems.isEmpty() || compiled.isEmpty()) {
        return finish(lines, problems);
      }
      evaluate(manifest, compiled.get(), golden, rulings);
      return finish(lines, problems);
    }

    /** Everything that can be said without evaluating a record; empty when it did not compile. */
    private Optional<CompiledPack> staticStage(
        PackManifest manifest, boolean satisfied, Rulings rulings) {
      if (!satisfied) {
        problems.add(
            new PackProblem(
                "ENGINE_MIN_NOT_SATISFIED",
                PackManifest.FILE,
                0,
                "this engine build "
                    + EngineInfo.BUILD
                    + " does not satisfy engine_min "
                    + manifest.engineMin()));
      }
      if (manifest.reports().size() != 1) {
        problems.add(
            new PackProblem(
                "MULTI_REPORT_NOT_SUPPORTED_BY_PACK_TEST",
                PackManifest.FILE,
                0,
                "the pack defines "
                    + manifest.reports().size()
                    + " reports; pack test runs the golden cases of a pack with exactly one"));
        return Optional.empty();
      }
      problems.addAll(CanonicalBindings.checkSupported(pack.input()));
      CompiledPack.CompileResult result =
          CompiledPack.compile(pack, manifest.reports().get(0).id(), rulings);
      problems.addAll(result.problems());
      return result.pack();
    }

    private void evaluate(
        PackManifest manifest,
        CompiledPack compiled,
        PackGoldenLoader.Loaded golden,
        Rulings rulings) {
      PackRegime regime =
          PackRegime.create(pack, manifest.reports().get(0).id(), new MeteredExecutor(), rulings);
      List<GoldenAudit.Row> audit =
          GoldenAudit.audit(golden.cases(), reviewers, golden.authorByRuleId());

      List<CaseOutcome> outcomes = new ArrayList<>();
      int passed = 0;
      for (int i = 0; i < golden.cases().size(); i++) {
        GoldenCase c = golden.cases().get(i);
        CaseOutcome outcome = evaluateCase(regime, c, audit.get(i).countsForTraceability());
        if (outcome.passed()) {
          passed++;
        }
        outcomes.add(outcome);
      }
      lines.add("golden: " + passed + " of " + golden.cases().size() + " pass");

      CoverageResult coverage = CoverageGates.evaluate(pack, outcomes);
      problems.addAll(coverage.problems());
      Coverage c = coverage.coverage();
      lines.add(
          "coverage: rows "
              + c.rowsHit()
              + "/"
              + c.rowsTotal()
              + ", reasons "
              + c.reasonsReached()
              + "/"
              + c.reasonsTotal()
              + ", provisions "
              + c.provisionsExercised()
              + "/"
              + c.provisionsCited()
              + " (all cases); rows "
              + c.countingRowsHit()
              + "/"
              + c.rowsTotal()
              + " (counting cases)");

      int counting = 0;
      int consistencyOnly = 0;
      for (GoldenAudit.Row row : audit) {
        if (row.countsForTraceability()) {
          counting++;
        }
        if (row.kind() == ProvenanceKind.CONSISTENCY_ONLY) {
          consistencyOnly++;
        } else {
          for (String token : row.problems()) {
            problems.add(
                new PackProblem(
                    "GOLDEN_AUDIT",
                    PackGoldenLoader.GOLDEN_DIR + row.caseId() + ".json",
                    0,
                    token));
          }
        }
      }
      lines.add(
          "audit: "
              + counting
              + " of "
              + audit.size()
              + " counting, "
              + consistencyOnly
              + " consistency-only; reviewers registered: "
              + reviewers.size()
              + " from "
              + registryName
              + (reviewers.size() == 0 ? " (gate G1: no named Compliance owner)" : ""));

      PackBudget budget = compiled.budget();
      lines.add(
          "budget: record budget "
              + budget.perRecordUnits()
              + " units (static worst case "
              + budget.staticWorstCaseUnits()
              + "); largest rule "
              + budget.largestRuleNodes()
              + " nodes; rule ceiling "
              + manifest.limits().ruleNodes()
              + "; SP-R recommended "
              + budget.recommendedRuleCeiling());
    }

    private CaseOutcome evaluateCase(PackRegime regime, GoldenCase c, boolean counts) {
      String path = PackGoldenLoader.GOLDEN_DIR + c.id() + ".json";
      CanonicalExecution execution;
      try {
        execution = CanonicalExecutionCodec.fromMap(c.input());
      } catch (RuntimeException e) {
        problems.add(
            new PackProblem(
                "GOLDEN_INPUT_INVALID",
                path,
                0,
                "the input is not a canonical execution: "
                    + PackProblem.excerpt(String.valueOf(e.getMessage()))));
        return new CaseOutcome(c.id(), false, List.of(), "", false);
      }
      EvaluationContext context =
          new EvaluationContext(
              regime.decisionContext(),
              new IngestKey("golden", c.id(), 1),
              EVALUATED_AT_INSTANT,
              regime.recordBudget());
      DecisionBody body;
      try {
        body = regime.evaluate(execution, context).decision();
      } catch (RuntimeException e) {
        // defence in depth: the codec refuses what cannot be canonicalised, so this is a case
        // that no decision can be built for. It is a reported problem, never a crash of the run.
        problems.add(
            new PackProblem(
                "GOLDEN_INPUT_INVALID",
                path,
                0,
                "no decision can be made for the input: "
                    + PackProblem.excerpt(String.valueOf(e.getMessage()))));
        return new CaseOutcome(c.id(), false, List.of(), "", false);
      }

      List<String> differences = new ArrayList<>();
      if (body.outcome() != c.expected().outcome()) {
        differences.add("outcome expected " + c.expected().outcome() + " got " + body.outcome());
      }
      if (!body.reasonCode().equals(c.expected().reasonCode())) {
        differences.add(
            "reason expected "
                + PackProblem.excerpt(c.expected().reasonCode().value())
                + " got "
                + PackProblem.excerpt(body.reasonCode().value()));
      }
      if (!body.node().equals(c.node())) {
        differences.add(
            "node expected "
                + PackProblem.excerpt(c.node())
                + " got "
                + PackProblem.excerpt(body.node()));
      }
      if (!body.ruleIdsHit().equals(c.expected().ruleIdsHit())) {
        differences.add(
            "rule hits expected "
                + PackProblem.excerpt(String.valueOf(c.expected().ruleIdsHit()))
                + " got "
                + PackProblem.excerpt(String.valueOf(body.ruleIdsHit())));
      }
      boolean passed = differences.isEmpty();
      if (!passed) {
        problems.add(
            new PackProblem("GOLDEN_CASE_FAILED", path, 0, String.join("; ", differences)));
      }
      // the golden compares the DECIDING row; coverage credits every row that matched on the way
      // to it, so a catch-all that was passed through to the next table counts as exercised
      return new CaseOutcome(
          c.id(), passed, matchedRows(body), body.reasonCode().value(), passed && counts);
    }

    private static List<String> matchedRows(DecisionBody body) {
      Set<String> rows = new LinkedHashSet<>(body.ruleIdsHit());
      for (PathStep step : body.path()) {
        if (step.matched()) {
          rows.add(step.ruleId());
        }
      }
      return List.copyOf(rows);
    }
  }
}
