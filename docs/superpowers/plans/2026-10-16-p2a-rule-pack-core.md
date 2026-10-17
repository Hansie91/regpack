# P2a Implementation Plan: Rule-Pack Core (increment S0)

**P2a implementation plan** for regpack (plan id `P2a`: the core slice of the spec 16 row "Rule-pack core"; it closes increment S0 and the exit criterion of that row). Written 2026-10-16 against the approved specification, the signed-off P0 and the built, not yet signed P1a. Saved as `docs/superpowers/plans/2026-10-16-p2a-rule-pack-core.md`; the shared contracts are in its companion `2026-10-16-p2a-contracts.md` (section numbers below refer to it).

**For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status.** DRAFT for owner approval (AP-1). Nothing is built; writing this plan created only this plan and its companion contracts file. Work branch `p2a/rule-pack-core` exists, created from `p1a/spikes` at `c4e1bfe` (the owner merges).

**Goal.** Replace the walking-skeleton constants by a real, strictly parsed, content-addressed **Regime Pack** that three placeholder scope rules and the mandatory catch-all run from, to a decision record with reason code and citations and to `explain`, on in-memory ports: a strict parser and typed model (spec 6.1 with the accepted SP-G wordings), decision tables, a production in-house expression layer with a conformance suite and a one-file rulings table, a reference interpreter with a deterministic budget and a poison circuit, pack CI with coverage gates, golden cases with enforced provenance, a lossless CSV round trip, the `engine_build` replay rule and `engine_min` check, the `pack` commands, a sample pack, a throughput information check and the exit record with the effort calibration of spec 15.

**Split decision (P2a now, P2a-2 later).** The spec 16 row has about fourteen deliverables. The core slice below is 27 tasks (the guideline is 28); adding the Firm Profile register, the overlay registry and interpretation pins (each effective-dated, stamped on every decision, approval-class aware) and the derive-table evaluation that increment S3b needs would add six to eight tasks. The seam is clean: `DecisionContext` has carried `overlayDigest`, `interpretationPins` and `firmProfileVersion` since P0 and P2a fills them with `none` and empty lists. **P2a** delivers what the S0 definition and the four clauses of the exit criterion need, so S0 closes at the P2a exit. **P2a-2** (section "Deferral"; the spec 16 table has no such row: A6, OI-9) delivers the rest of the row. All four clauses of the exit criterion are closed by P2a; the row is complete when P2a-2 has also exited (dependants: Deferral).

**Architecture summary.**

- One new release module `regpack-pack` (JDK-only): the strict YAML subset parser, pack source, typed pack and tables, registries, static checks, canonical IR and digest, CSV round trip and the golden harness (moved from `regpack-testkit`, which the application must not ship). `regpack-engine` gains `engine.expr` (the in-house expression layer), `engine.pack` (compiled pack, interpreter, `PackRegime`, circuit, budget) and `engine.packci` (coverage gates, tester, explainer); `regpack-app` gains the `pack` commands and runs `smoke` from a bundled sample pack. The P0 `skeleton` package stays, proven equivalent by a differential test.
- **Fail closed:** reserved slots are accepted only when empty and listed by `pack test` as "not interpreted by this engine build". **Determinism is structural** (as P0): bytecode scans, `tr_TR` and `Pacific/Kiritimati` test runs, a digest of parsed content only, a second toy-crash digest compared across operating systems. **No regime logic in engine code (I4):** a second-regime fixture runs through the same engine unchanged.

**Tech stack.** As P0 and P1a (Java 25, Maven 3.9.16 wrapper, JUnit 6.1.3, AssertJ 3.27.7, google-java-format 1.35.0, `-Xlint:all -Werror`, hostile test JVM, pinned actions). **No new third-party dependency, plugin or download** (D-P1): the enforcer rule, licence gate, SBOM and isolation gate need no change.

**Inputs.** The spec; the P0 [plan](2026-10-09-p0-foundation-and-walking-skeleton.md) and [exit record](../../exit/P0.md); the P1a [plan](2026-10-10-p1a-spec-freeze-spikes.md), [exit record](../../exit/P1a.md) and `docs/spikes/` (SP-R, SP-G, `open-items.md`, `freeze-record.md`, `sp-g/`). Spec 16 Entry (SP-R and SP-G reports exist) is satisfied: `Gates entry --target P2a` prints two `OK` lines (T1); the freeze record is `PENDING` (section "Departures"). **The grammar, IR and `engine_min` semantics are not frozen by P2a** (they freeze at a G-S3b pass); every format choice is provisional (section "Provisional contracts").

**P2a exit criterion:** the four clauses of the spec 16 line quoted under "Global constraints", plus the S0 definition (spec 15): three scope rules run to a decision record and `explain`, now from a real pack.

## Decisions already made (stated, not reopened)

| Source | Decision |
|---|---|
| Owner (P0) | The P0 stack and rules (Java 25 modular monolith, determinism, CI on Linux and Windows, SHA-pinned actions); licence allow-list Apache-2.0, MIT, BSD-2, BSD-3, EPL-2.0 (test and build only); EU MiFIR first, EMIR second |
| Owner (gates) | G3 `PENDING`: no ESMA XSD, workbook text or derived content in any pack, fixture or golden file. G4: no DRR artefact, even as an oracle. G1: no named Compliance owner, so **every golden case is labelled consistency-only** |
| Evidence (SP-R) | The in-house CEL-shaped subset over `BigDecimal` passed the throughput gate (27,796 records/s per thread, four threads busy, 200 rules; gate 10,000); `cel-java` as adapted failed (2,836) and cannot meter what an expression builds. The 38 MUST and 3 SHOULD vectors are a starting suite, **not a neutral oracle** (H-7). Defaults (budget 2 x the static worst case, rule ceiling max(64, 4 x largest node count), circuit N = 4 in M = 50) are MODELLED |
| Owner (SP-G, 2026-10-10) | 15 wordings ACCEPTED (companion section 1); BRK-18 accepted as a P12 item; BRK-03, 05, 17 rejected. **The specification text is not edited yet** (the owner does it); P2a implements the NEW wording of `docs/spikes/sp-g/register.md` and never edits the spec |

## Working assumptions pending the owner (not owner decisions)

| Id | Assumption | Cost to reverse |
|---|---|---|
| A1 | The expression layer is the in-house CEL-shaped subset over `BigDecimal`, re-implemented test-first as production code behind the `Expression` port. The spike code is design reading only: nothing is copied wholesale, no `dev.regpack.spikes` package or spike dependency enters a release module. Open item D-2 is a departure, see departures table row 2 | medium: T11 to T14 are replaced (about four tasks), and `CompiledPack`, `PackBudget` and `PackProgram` adapt to the replacement's static facts (`nodeCount`, `paths`, `staticWorstCaseUnits`), which the `Expression` port does not offer |
| A2 | The DECIDE vectors D13, A04, A07, E06, S07 take the in-house outcomes as a **provisional contract in one data file** (`rulings.tsv`, T14): a ruling is one line for D13, A04, E06 and A07 (a departure, see departures table row 3). S07 has no alternative: enum-with-string equality is by name until the grammar has an enum literal, and a `TYPE_ERROR` ruling is a plan amendment (grammar, packs, new engine major). L01 is not a vector (spec 6.2: no loops). Vectors that only record the spike's own design are SPIKE-DESIGN, not MUST (companion section 5.5) | low |
| A3 | The 15 accepted SP-G wordings are the amended pack-format text. Companion section 1 gives each register id: implemented, reserved or left. Where a task needs spec text changed it is an OWNER INPUT step with a default | none |
| A4 | Packs are unsigned until P9b (spec 6.5 step 6): `pack test` prints `UNSIGNED`; the loader never claims a signature | none |
| A5 | Activation, the ledger `pack_activation` row, hot swap and `resolve(regime, as_at, mode)` are P3 and P9b | none |
| A6 | The spec 16 P2a row is delivered by two plans: P2a (this plan, closes S0) and P2a-2 (Firm Profile register, overlay registry, interpretation pins, derive tables; written after the P2a exit) | Before execution: add 6 to 8 tasks and about 30 KB to this plan; after the P2a exit nothing is undone (the seam is the `DecisionContext` placeholders) |

## Departures from stated before-conditions (AP-1 approves each, or the named tasks wait)

| Source quote | What this plan does | If the owner declines |
|---|---|---|
| spec 16: "Until then P2a to P5 proceed on the parts the spikes do not touch"; `docs/spikes/freeze-record.md` `status: PENDING` | T6 to T10 and T11 to T14 are built before the freeze is recorded, on the SP-G NEW wording and the in-house layer; every choice provisional (Provisional contracts); nothing is frozen | T6 to T14 wait for the freeze record or the spec edit; T3, T4, T5 and the golden-harness move of T19 may proceed |
| `open-items.md` D-2, Before: P2a scope | builds the in-house layer as production code (A1; reversal cost stated there) | T11 to T14 are replaced by a re-plan (cel-java with a faster executor or lazy adapter) |
| `open-items.md` D-3, Before: before P2a implements absence and error behaviour | implements the in-house outcomes as five provisional lines of `rulings.tsv` guarded by `SemanticsFingerprintTest` (OI-5) | T12 to T14 wait for the rulings; only the MUST vectors are implemented meanwhile |
| `open-items.md` D-4, Before: before P2a builds the pack format | implements the NEW wording of `sp-g/register.md`; `AmendmentTableTest` keeps the SP-G table complete; the spec edit stays the owner's | T6 to T9 wait for the spec edit |

## Owner inputs and approvals

**Approvals (stop and ask; never work around).**

| Id | What | Before |
|---|---|---|
| AP-1 | This plan and its companion contracts file, with the departures table, the beyond-6.1 format elements, the Split decision, "Contract and layout changes", "Existing tests that change", D-P6 and A1 to A6 | T2 (asked in T1 step 4) |
| AP-2 | Every push of `p2a/rule-pack-core` and every CI run waited on | each push |
| AP-3 | The two edits of the pinned workflow `ci.yml` (branch filter `p2a/**`; second digest upload and compare); action SHAs unchanged | first push containing them (T2, T24) |
| AP-4 | Merge, tag and the sign-offs in `docs/exit/P2a.md` (owner only; the agent never ticks them) | T27 |

No download, no `docker`, no network other than Maven Central for artefacts already pinned, no repository-settings change. A needed new dependency is a stop.

**Owner inputs (each has a default, so nothing waits; answers go to `docs/pack-format/decisions.md`, T1).**

| Id | Question | Default until answered | T |
|---|---|---|---|
| OI-1 | Plan approval and A1 to A6 | proceed on AP-1 | T1 |
| OI-2 | Source of `legs[]` of a REPORT decision (the accepted row shape has no `legs` key) | optional `legs:` list on REPORT rows, default `[MARKET]`, vocabulary MARKET, CLIENT, ALLOCATION | T7, T16 |
| OI-3 | How several scope tables per report combine | SP-R benchmark rule: tables in order; the first table whose FIRST hit is not its catch-all decides; if all end in their catch-all, UNDETERMINED from the last catch-all | T16 |
| OI-4 | When an open poison circuit closes | only by explicit `close(ruleId)` or a new pack digest (spec 7.3 says only "when the circuit closes") | T17 |
| OI-5 | Rulings on D13, A04, A07, E06 (S07: see A2) | in-house outcomes, provisional; a departure, see departures table row 3; one line of `rulings.tsv` each | T14 |
| OI-6 | Keep the P0 `skeleton` package | yes, until the frozen spike code is removed (Q7, D-8) | T23 |
| OI-7 | Where the reason-code prefix and node list are declared (BRK-11 says "the pack declares") | `reasons.yaml` header | T8 |
| OI-8 | Calibration read-out before P3 is planned | stop at T27 | T27 |
| OI-9 | Accept the split and add a row P2a-2 to the spec 16 table | default: the notes of Deferral stand in until the owner edits the spec | T1 |
| OI-10 | Optional CI checkpoint: push `p2a/rule-pack-core` after T16 and wait for the run (AP-2; AP-3 for the `ci.yml` filter edit of T2) | recommended; if not pushed, the first run is T27 step 4 | after T16 |

## Decisions made by this plan (reversible, with reasons)

- **D-P1 In-house strict YAML subset parser, no library.** SnakeYAML 2.x and SnakeYAML Engine 2.x (Apache-2.0, on the allow-list) implement full YAML (anchors, aliases, tags, merge keys, implicit typing, unsafe construction), which the pack format must refuse, and a library ends the JDK-only runtime classpath for a parser whose purpose is to be restrictive (D10). The subset is small and SP-G's `MiniYaml` parsed all six example files. Risk: bugs on untrusted input, mitigated by a closed grammar, hard limits, a rejection matrix and seeded hostile tests (T4, T5). Reversal: the parser sits behind `StrictYaml.parse(String)`; a library is one adapter plus a licence-policy line and an enforcer change.
- **D-P2 `regpack-pack` is a new module; the golden harness moves into it.** Only `RepositoryGoldenAuditTest` and `GoldenAuditTest` use it (checked).
- **D-P3 The skeleton regime stays**: `spikes/spike-r` compiles against `WalkingSkeletonRegime` and `WalkingSkeletonRules`; deleting them would edit frozen spike code. So the P0 toy digest `70382303f446476321d38d50817bdd8d541e0dcb61d8f33d92435c676bc116c9` stays valid for `ToyCrashIT`, and a **new, separate** pin is created for the pack-driven pipeline (T24).
- **D-P4 `attributes{}` (BRK-10) is implemented in the model**: a ledger contract (`BLOCKS_FREEZE`) that P3 sizes the decision schema on. `DecisionBody` gains a trailing `Map<String,String> attributes` and keeps its old constructor; the codec emits the key only when the map is not empty, so every existing digest is unchanged (T15).
- **D-P5 `EngineBuild` and the replay rule live in `regpack-model`**; `DecisionContext.engineBuild` stays a `String`.
- **D-P6 Pack goldens must state their provenance.** Spec 14.2 O1 says a case without provenance is labelled consistency-only; the P0 lenient loader keeps doing that for the skeleton goldens. For pack goldens the strict loader refuses the file (`GOLDEN_NO_PROVENANCE`): the exit criterion says the harness enforces provenance fields, so a forgotten field must fail CI, not get a quiet label. Reversal: one line in `PackGoldenLoader`.

## Global constraints (copied verbatim from the approved specification)

Each quoted line is an exact fragment of one line of the specification (a line may be a table cell or a sentence of a longer line); T27 step 3 re-checks every one by exact substring search. Where a task seems to conflict with one of them, the specification wins and the task is wrong. Two documented exceptions, both void unless AP-1 approves them: (1) the row words "bought expression layer" are replaced by the in-house layer of A1 (spec 6.6 names a minimal in-house subset as the fallback; SP-R shows cel-java failing R-2b); (2) the row items "interpretation pins" and "Firm Profile register and overlay registry" are delivered by plan P2a-2 (Split decision). No other task may differ from a quoted line.

**Section 4 (I3 to I5)**

> | I3 | **Reproducible.** Every stage is a deterministic function of pinned inputs: pack digest, overlay, interpretation pins, reference-data snapshots, enrichment responses, mapping version, Firm Profile version and engine build. Rules use no wall clock, randomness or locale; UTC and decimal arithmetic only.
> | I4 | **Regulation is data.** Rules, reason codes, citations, validation catalogues, destination profiles and schema pins are signed, versioned data. Regime logic found in engine code is a design alarm. |
> | I5 | **Every candidate gets a decision with a reason and citation, negatives included.** UNDETERMINED never silently becomes DO_NOT_REPORT. |

**6.1 Pack anatomy, 6.2 Rule language**

> A pack is versioned (semver), content-addressed (SHA-256 of canonical form), signed and immutable once published.
> **Release 1 pack-schema scope.** Release 1 implements the `legal` validity window and `AS_REPORTED` resolution.
> YAML decision tables plus a CEL-shaped, non-Turing-complete expression subset: bool, int, scaled decimal, string, UTC timestamp, date, enum, list; explicit absence (`has(x)`); evaluation errors yield UNDETERMINED; no loops, I/O, clock or user-defined functions; a per-rule cost ceiling; a vetted built-in library (LEI mod 97-10, ISIN Luhn, CFI, MIC, dates, decimals) versioned and pinned through `engine_min`.
> CI always enforces a mandatory catch-all row per table (default outcome UNDETERMINED, I5), unique priorities and the coverage gates; full static analysis for gaps, overlaps, unreachable and shadowed rows (plan P2b) is built if gate G-S3b passes (§6.8).

**6.3 Decision record, 6.4 Legal Source Registry, 6.5 Activation**

> Every candidate gets exactly one *current* decision, negatives included:
> Tier 2 (full re-evaluation trace) runs on the current engine when its `engine_build` is replay-compatible with the stored one (same major version and built-in library version; P2a defines the rule), otherwise in a short-lived subprocess started from the archived build.
> CI proves that citations resolve and quote hashes match; it cannot prove the law was read correctly
> a pack may then be activated unsigned only in SIMULATOR or CONFORMANCE profiles (§9.7)

**6.6 Execution, 6.8 Freeze, 7.3 Faults, 7.4 Canonical values**

> Release 1 has **one executor behind an `Executor` port: the reference interpreter**, a tree-walking evaluator with an optional full trace; it is the executable specification, used by production, by `explain` and by golden tests. Before the S3b gate it evaluates the YAML tables directly.
> The expression layer is `cel-java` if SP-R shows its numeric, date, null and error behaviour is adequate for scaled decimals and timestamps, otherwise a minimal in-house subset; both sit behind the `Expression` port.
> Each record runs under a per-record executor budget counted in deterministic cost units (interpreter steps plus list-length and string-size limits) enforced inside the interpreter, so the same input always gives the same outcome. A wall-clock watchdog may raise an alarm but never changes a decision.
> Proposed gates (placeholders): at least 10,000 records/s/core on a 200-rule pack, bounded memory at 50M records, p99 `explain` under one second.
> **Pack format freeze (on a pass only).** G-S3b passed, together with the SP-R and SP-G reports, freezes the pack format (grammar, IR, `engine_min` semantics)
> Decision is UNDETERMINED (`EXEC_ERROR` / `EXEC_BUDGET`) carrying (rule_id, error class, cost units, §6.6); a poison counter per rule; after N `EXEC_*` outcomes in M records (defaults set by SP-R) the rule's circuit opens, and affected records get reason `EXEC_CIRCUIT_OPEN` and a data-quality item (dashboard and notification hook), and are re-evaluated by a superseding decision when the circuit closes
> Canonical values: scaled decimals, UTC timestamps, explicit enumerations, explicit absence (no three-valued NULL).

**14.1 and 14.2 Testing; 15 Increment S0; 16 The P2a row and the freeze; 17.1 Gates G1, G3, G4**

> Executor budget tests count cost units (§6.6), never wall-clock time: the same input hits the same budget on every run, and a seeded over-budget rule gives `EXEC_BUDGET` and opens its circuit deterministically.
> **O1 Provenance.** Every golden case records its source
> A case without provenance is labelled `consistency-only`.
> **O3** Consistency-only cases (regenerated expected outputs) cannot satisfy a traceability row.
> | S0 | Walking skeleton on in-memory ports: ledger, store, clock, transport; destination simulator skeleton; CI on Linux and Windows Server; packaging skeleton for the three targets; three scope rules run to a decision record and `explain` | P0, P2a |
> After P0 and S0 the owner records owner-hours and agent effort and re-estimates the remaining plans before committing to P3
> Rule-pack core: pack format and parser, YAML decision tables, bought expression layer, reference interpreter, pack CI checks (schema, catch-all and unique-priority, and coverage gates), Legal Source Registry, reason-code registry, decision record, `explain`, interpretation pins, golden harness with provenance, `engine_min`, Firm Profile register and overlay registry (versioned, effective-dated, stamped on every decision), CSV import and export of decision tables (the AT-8 round-trip)
> `pack test` and `pack explain` run on a sample pack; the golden harness enforces provenance fields; a decision table survives a CSV export and re-import unchanged; the replay-compatibility rule for `engine_build` (§6.3) is defined
> P0. Entry: SP-R and SP-G reports before the grammar freezes
> The expression grammar and IR are not frozen by the spec freeze; they freeze only at a G-S3b pass (§6.8), and a fail leaves them unfrozen.
> | G1 | Before P6a and increment S3 start | A named Compliance owner for rule content, Firm Profile, interpretation pins and MAJOR pack approvals, and the human reviewers of golden cases (§14.2) |
> | G3 | Before any XSD is fetched, or any XSD- or workbook-derived content (facets, rule text, golden files) enters a pack, test fixture or repository (before P5 and increment S4b) | Legal sign-off on SWIFT-licensed ESMA XSD terms and on shipping derived facet metadata or workbook-derived text inside packs (fallback: packs reference the XSD by hash and the facets are generated at deploy) |
> | G4 | Before any DRR use | Legal reading of the DRR licence: commercial use, whether code artefacts are covered, the indemnity, and use as a test oracle |

## Review focus: failure modes the spec implies but no existing check covers

Each row names the check that owns the failure mode; a reviewer who finds one that is missing adds a row and a test. P0 rows RF1 to RF25 and P1a rows RF1 to RF19 stay in force. Ids are stable: PF2, PF4, PF13, PF17, PF18, PF19, PF23, PF24 were cut for size; their tests stay named in T6, T7, T15, T23 to T26.

| # | Failure mode | Owning check (task) |
|---|---|---|
| PF1 | The parser accepts a forbidden YAML feature (anchor, alias, tag, merge key, tab, duplicate key, block scalar), implicit typing (the Norway problem: `NO`, `null`, `1e3`, `2018-01-03`), invisible, control or default-ignorable characters (one `PackText` policy for YAML, table cells and golden strings), hostile input (size, depth, byte soup), or echoes untrusted text without bound | `StrictYamlRejectionTest`, `StrictYamlLimitsTest`, `StrictYamlHostileInputTest`, `PackSyntaxExceptionTest` (T5); `StrictYamlTest#everyScalarIsAString` (T4); `TableParserTest#numbersAndDatesNeedTheirExactForm`, `#everyCellMustBeOneLineOfCleanText` (T7); `PackGoldenStrictnessTest` (T19) |
| PF3 | The pack digest depends on comments, key order, row order, line endings, locale or OS, or fails to change when an evaluated field changes | `PackDigestTest` (T9); the CI job `determinism-compare` (T24) |
| PF5 | A reason code is unknown, undeclared by prefix and node, disagrees with the outcome the registry gives it; a citation does not resolve or its quote hash does not match | `RegistryChecksTest` (T8) |
| PF6 | A reserved slot (`imports`, `interpretations.yaml`, report `schema`, leg vocabulary beyond MiFIR, validity windows other than legal) is silently accepted with content | `ReservedSlotsTest` (T9) |
| PF7 | **UNDETERMINED silently becomes DO_NOT_REPORT or REPORT**: absent path, expression error, non-bool `when`, budget exhaustion, open circuit | `PackScopeEngineTest#everyFailureModeIsUndeterminedWithReasonAndCitation` (T16) |
| PF8 | The expression layer drifts from the spec or the evidence; a ruling or library change alters decisions without a version bump | `ConformanceSuiteTest`, `SemanticsFingerprintTest` (T14); `EngineBuildTest` (T3) |
| PF9 | A budget test is secretly time-based, or an amplification allocates before it is charged | `CostTest`, `AmplificationTest` (T12); `ExecutorBudgetTestsAreWallClockFreeTest` (extended in T16) |
| PF10 | Locale dependence: `upper`, `lower`, digits and dates under `tr_TR`; non-ASCII digits | conformance vector T07 and `LibraryTest#upperAndLowerIgnoreTheDefaultLocale` (T13); `NoWallClockRandomLocaleTest` in `regpack-pack` and `regpack-engine` (T2, T11) |
| PF11 | The replay rule is wrong (compatible across a major or a library version), or `engine_min` is satisfied by another major | `EngineBuildTest` (T3); `PackRegimeTest#refusesAPackWhoseEngineMinIsNotSatisfied` (T16) |
| PF12 | The poison circuit is nondeterministic, opens on the wrong rule, never opens, or hides `EXEC_CIRCUIT_OPEN` as `EXEC_ERROR` | `RuleCircuitTest`, `PackScopeEngineTest#seededOverBudgetRuleOpensItsCircuitDeterministically`, `#anOpenCircuitIsReportedAsExecCircuitOpenNotExecError` (T17) |
| PF14 | A golden case silently downgrades: no `provenance` read as consistency-only, partial provenance, unregistered reviewer, case author equal to rule author | `PackGoldenStrictnessTest` (T19) |
| PF15 | The coverage gate is vacuous: no cases passes, a failing case counts as an exercise, consistency-only cases count as closing a traceability row | `CoverageGatesTest` (T20) |
| PF16 | The CSV round trip is lossy (quotes, commas, newlines, edge spaces, non-ASCII, empty cells, several citations), an import bypasses the table checks, or a cell acts as a spreadsheet formula | `TableCsvTest` (including `#importRefusesLfCrTabAndInvisibleCharactersInAnyCell`), `TableCsvRoundTripPropertyTest` (T21); `TableChecksTest#whenMayNotStartWithAFormulaCharacter` (T7) |
| PF20 | The throughput check measures nothing (rows skipped, dead-code elimination) or is read as a verdict | `PackThroughputGuardTest` (runs in `verify`); the output of `PackThroughputCheck` says "information, not a verdict" (T22) |
| PF21 | `pack test` exits 0 on a failed check, a failing golden case or an unregistered reviewer; the unsigned state is hidden | `PackCommandsTest` (T25) |
| PF22 | The sample pack, a fixture or a golden file contains regulatory content, ESMA workbook text or a DRR artefact (G3, G4, G1) | `Gates g3` (check-gates; names and namespaces only) and `EveryCommittedPackIsSyntheticTest` (T10) |

## Scope check: out of scope for P2a

Nothing of P2b (conditional on G-S3b), P3, P5, P6, P7, P8, P9 or P10 is planned here.

| Out of scope | Owning plan |
|---|---|
| Firm Profile register, overlay registry, interpretation pins, their effective dating, approval classes and stamping; **derive tables** (row shape, checks, evaluation; P2a only strict-parses and digests `derive/`) | P2a-2 |
| Type checker, full static analysis, IR canonicalisation as a language tool | P2b (only if G-S3b passes) |
| Ledger use of the pack (`lifecycle.yaml` semantics, the `attributes` column, an open leg type, `pack_activation`) | P3 |
| Identifier library (P2a ships the version mechanism only), validation layers, `validate/` rows, `ENRICH_MISS`, reference data, report schemas | P5 |
| Real MiFIR content and any real provision text | P6a, P6b; gate G1 |
| Mapping packs; endpoint profiles, `correlation`, `confirmation`, `restamp`, `volatile` fields | P7a; P8 |
| General CLI shell, API; signing, activation, `resolve`, semver classification, archived engine builds, the Tier-2 subprocess | P9a; P9b |
| Pack viewer, analyst editor; EMIR content, `Leg.SIDE`, `common` imports, BRK-03, 05, 17, 18; gates G2 to G9 | P10b, P11; P12; their own plans |

## File structure

Paths relative to the repository root; "T" is the task that creates or changes the file.

| Path | Responsibility | T |
|---|---|---|
| `pom.xml`, `regpack-pack/pom.xml`, `.github/workflows/ci.yml` | Module wiring; CI branch filter and second digest | T2, T24 |
| `regpack-model/.../EngineBuild.java`; `decision/DecisionBody.java`, `DecisionCodec.java` | Replay rule; trailing `attributes` | T3, T15 |
| `regpack-pack/.../` `PackFormat`, `PackText`, `yaml/` | Limits and hard caps; the character policy; the strict parser | T2, T4, T5 |
| `regpack-pack/.../` `source/`, `model/`, `check/`, `ir/` | File tree and CHECKSUMS; typed pack, tables, registries, loader; problems and static checks; canonical IR and digest | T6 to T9 |
| `regpack-pack/.../` `csv/`, `golden/` | CSV round trip; golden harness (four moved classes, `PackGoldenLoader`) | T21, T19 |
| `packs/sample.s0/**` | The sample pack (synthetic) | T10 |
| `regpack-engine/.../expr/`, resource `rulings.tsv` | The expression layer | T11 to T14 |
| `regpack-engine/.../pack/` | Interpreter and regime | T14 to T17 |
| `regpack-engine/.../explain/Tier1Explainer.java`, `.../packci/` | Explain, CI orchestration | T18, T20 |
| `regpack-engine/src/test/resources/conformance/`, `packs/` | Conformance data, fixture packs | T14, T23 |
| `regpack-app/.../PackCommands.java`, `Main.java`, `SmokeCommand.java`, `pom.xml`; `scripts/pack.sh`, `pack.ps1` | CLI, bundled pack, launchers | T25 |
| `scripts/check-gates.sh`, `docs/gates/G1.md`, `docs/spikes/entry-wiring.tsv`, `docs/traceability.md` | Gate step, record, wiring, evidence | T26 |
| `docs/superpowers/plans/2026-10-16-p2a-contracts.md`; `docs/pack-format/decisions.md`, `p2a-format-notes.md`; `docs/exit/P2a.md` | Shared contracts; owner answers and pins; implemented format; exit record | T1, T26, T27 |

## Conventions (read before Task 1)

The P0 Conventions 1 to 11 and the P1a Conventions 1 to 3 and 7 to 9 hold (shorthand `MVN`, `T <module> <selector>`, `IT <module> <selector>`, `FMT`, `COMMIT "<message>"`; Bash and PowerShell forms). Deltas:

1. **Branch and commits.** `p2a/rule-pack-core`; one commit per task; `COMMIT "<message>"` is `FMT`, `git add -A`, `git commit -m "<message>" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`. Never skip hooks. The owner merges.
2. **Environment.** Every new shell sets `JAVA_HOME` (Bash: `export JAVA_HOME=/c/dev/jdk/jdk-25.0.4.1+1; export PATH="$JAVA_HOME/bin:$PATH"`; PowerShell: `$env:JAVA_HOME='C:\dev\jdk\jdk-25.0.4.1+1'; $env:Path="$env:JAVA_HOME\bin;$env:Path"`). PowerShell 5.1 has no `&&`; quote arguments containing `#`, `,` or `=`.
3. **Task steps.** Unless a task says otherwise: (1) write the named tests (a name is a method; the text after it is its assertion); (2) run them red and see the stated failure; (3) implement the minimum; (4) run green (same command, then the stated wider run); (5) commit. A test class lives in the package of the class it tests unless stated.
4. **Design reading, never copying.** Spike classes named in a task are read for design; tests come first from this plan, the implementation is fresh. No import of `dev.regpack.spikes`, no edit under `spikes/`. The spike vector file may be copied as **data** with the changes of companion section 5.5.
5. **Restricted packages.** `regpack-pack` and the new engine packages follow P0 Convention 8: no wall clock, randomness or default-locale call in main code (`ForbiddenCallScan` flags `String.format`, `toUpperCase()` without `Locale.ROOT`, `DateTimeFormatter` without a locale). Tests may use `SplittableRandom` with a constant seed, never the clock.
6. **Problems are tokens.** Checks return `PackProblem(code, path, line, message)`; tests assert the upper-case ASCII code; the `PackProblem` constructor bounds message and path and escapes non-ASCII (T6) and callers wrap untrusted text in `PackProblem.excerpt` (24 characters); results are sorted by (path, line, code).
7. **Pack files** are UTF-8 without BOM, LF; CHECKSUMS hashes bytes after CRLF-to-LF, so a Windows checkout verifies. Test names are evidence references (P0 Convention 9).
8. **Effort ledger.** The controller appends per task (implementer and reviewer runs, fix rounds, agent minutes without owner and CI waits, subagent tokens (from each result), owner minutes (start and end of each OWNER INPUT or STOP; ask the owner for an estimate of reading and deciding time at T1 step 4 and T27 step 8), tests added, commit) to the untracked `.superpowers/p2a-ledger.md`, copied into the exit record (T27). No time-box is set.
9. **Stop conditions** (stop and ask): P0 Convention 10 and P1a Convention 9, plus a new third-party dependency or plugin; any edit under `spikes/`; any change to a port signature in `regpack-ports` (none planned); a MUST vector whose expected value you doubt; anything gate G3 or G4 forbids; weakening a check to make a test green; a push or a CI dispatch.
10. **Code hygiene.** Every new exception class declares `private static final long serialVersionUID = 1L;` (javac `-Xlint:serial` under `-Werror`). `ExprException` gets its missing stack trace by overriding `public synchronized Throwable fillInStackTrace() { return this; }`, because `ExpressionException` has no suppress-stack-trace constructor. A new `.sh` gets `git update-index --add --chmod=+x`; `.sh` and `.ps1` files are ASCII-only (`EolCheck`). Integer fields are matched against an explicit `[0-9]` pattern before `Integer`/`Long` parse; never `Character.isDigit`.

## Contract and layout changes for owner review

AP-1 approves these with the plan; everything else is additive new code.

1. **New module** `regpack-pack` (root POM; `regpack-engine` and `regpack-app` depend on it; `regpack-engine` gets `regpack-bench` in **test** scope, which the isolation gate allows).
2. **`regpack-model`**: new `EngineBuild` (additive). `DecisionBody` gets a trailing component `Map<String,String> attributes` and keeps its 14-argument constructor; `DecisionCodec.bodyToMap` writes `attributes` only when not empty. `DecisionContext`, `Outcome`, `Leg`, `ReasonCode` are unchanged. **`regpack-ports`: no change** (static facts about a compiled expression are exposed by the engine class `InHouseExpression.Compiled`).
3. **Layout**: `dev.regpack.testkit.golden` (four classes and `GoldenAuditTest`) moves to `dev.regpack.pack.golden`.
4. **CI**: `ci.yml` push filter adds `'p2a/**'`; the toy-digest upload and compare steps also carry `pack-toy-digest.txt`. Action SHAs and permissions are unchanged.
5. **Application**: `regpack smoke` runs from the sample pack that the distribution bundles; new `pack` commands.
6. **`docs/spikes/entry-wiring.tsv`** (a P1a file) gains two lines (P6a G1, P6a-workbook-text G3); no existing line changes; the owner re-confirms the freeze-record line "entry-wiring re-checked against spec 16".

**Existing tests that change.** `DistributionLayoutIT` (`JARS` gains `lib/regpack-pack.jar` AND the expected set in `manifestClassPathNamesSiblingJarsWithoutVersions` becomes regpack-model.jar, regpack-ports.jar, regpack-pack.jar, regpack-inmemory.jar, regpack-engine.jar; asserts the bundled pack files); `SpikeWorkflowTest#ciPushFilterNamesP1aBranches` (a P1a test in `regpack-bench`, not spike code); `PackagingFilesTest#ciWorkflowHasTheSixJobsReadOnlyPermissionsAndMatchingArtefactNames` (assertions for the second digest); `RepositoryGoldenAuditTest` (import); `GoldenAuditTest` (moves); `DecisionTypesTest`, `SmokeCommandTest`, `MainTest` (tests added; existing assertions hold, the decision lines now name regime `sample.s0`); `ExecutorBudgetTestsAreWallClockFreeTest` (scans the new budget test classes); `docs/traceability.md` (evidence lines). **Pins that do not change:** the P0 toy digest `70382303...` and `ToyCrashIT`; the skeleton goldens and the pinned text of `Tier1ExplainerTest` (an `Attributes:` line is printed only when attributes exist); every digest literal in `DecisionTypesTest`; the P1a reports. **New pins** (value and reason under "Pins created" in `docs/pack-format/decisions.md`): the sample pack digest (T10), the semantics fingerprint (T14), the pack-pipeline toy digest (T24).

## Provisional contracts (they freeze only at a G-S3b pass)

PackYaml/1 (T4); the decision-table shape and CSV columns (T7, T21); IR format 1 and the digest (T9); the expression grammar, function names, cost model v1 and library version 1 (T11 to T13); the `engine_min` and replay rule (T3); the table-chain rule (OI-3); the engine default limits `rule_nodes` 512, `list_items` 256, `string_code_points` 4096 (plan defaults, not SP-R output; `pack test` prints the SP-R formula max(64, 4 x largest node count) beside the enforced `rule_nodes`) and the hard caps of `PackFormat` (T2); spec 6.2's per-rule cost ceiling is implemented as the `rule_nodes` node ceiling plus the per-record budget (the factor 2 and N = 4 in M = 50 stay MODELLED); the optional `legs` keys (OI-2); the circuit policy (OI-4). A change to any of them is a new engine `major` or library version when it can change a decision (T3), otherwise a plan amendment. Format elements beyond spec 6.1 and the 15 wordings, offered to the owner as inputs to the pack-format freeze: `pack.yaml` `limits`, `tests/authorship.yaml`, the file name `model/canonical-input.yaml`, the CHECKSUMS line format, `input_schema` as the digest of the typed IR, row-level `legs`, the quote length cap.

## Companion contracts (`2026-10-16-p2a-contracts.md`)

Section 1, SP-G amendments (the 15 accepted wordings, the OWNER INPUT sentence); section 2, the pack file contract (T6 to T10, T19); section 3, the expression language contract (T11 to T14); section 4, the hand-offs to later plans; section 5, the formats and rules that T3, T4, T7, T9, T14, T16 and T21 apply. T26 copies sections 1 to 5.

## Tasks

Task order is a dependency order (see the handoff for what may run in parallel). Each task ends in one commit; the five-step pattern is Convention 3. A step marked OWNER INPUT or OWNER STOP waits for the owner. Class names are in the module and package of the File structure table unless a path is given.

### Task 1 (T1): Baseline, decisions record and the owner's answers [OWNER INPUT]

**Files:** Create `docs/pack-format/decisions.md`.

- [ ] **Step 1: Branch.** `git branch --show-current` prints `p2a/rule-pack-core`; `git status --short` shows only this plan and its companion untracked. Commit both alone: `git add docs/superpowers/plans/2026-10-16-p2a-rule-pack-core.md docs/superpowers/plans/2026-10-16-p2a-contracts.md`, `git commit -m "docs: add the P2a implementation plan and its contracts" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`.
- [ ] **Step 2: Baseline.** Set `JAVA_HOME`. `MVN -v` (Maven 3.9.16, Java 25.0.4.1). `MVN -B -ntp verify` ends `BUILD SUCCESS`; totals when this plan was written (2026-10-16): tools 145 + 1 IT, model 47, ports 14, testkit 86, bench 343 (1 skipped), inmemory 105, engine 34 + 5 IT, app 43 + 5 IT. `bash scripts/check-gates.sh` ends `check-gates: all gates passed` (traceability `rows=24 closed=1 open=23`). `java -cp regpack-tools/target/classes dev.regpack.tools.Gates entry --target P2a --root .` prints two `OK` lines (SP-R, SP-G). `grep -n '^status:' docs/spikes/freeze-record.md` (expected `PENDING`): record the line in `decisions.md` (departures table row 1). A red baseline is a stop: report it, do not fix P0 or P1a here.
- [ ] **Step 3: Record.** `docs/pack-format/decisions.md`: *Approvals* (AP-1 to AP-4: id, what, date, name, state), *Owner inputs* (OI-1 to OI-10: question, default, answer), *Pins created* (name, value, task, reason), *Deviations from the plan*; style of `docs/spikes/decisions.md`.
- [ ] **Step 4: OWNER INPUT.** Present AP-1 (with the departures table) and OI-1 to OI-10 with their defaults, and ask for an estimate of the owner's reading and deciding time (ledger, Convention 8); record each answer or "default accepted" with date and name. T2 does not start before AP-1 is recorded as given. **Step 5:** `COMMIT "docs(p2a): record the baseline and the owner's answers"`.

### Task 2 (T2): The `regpack-pack` module, wiring and CI branch filter

**Files:** Create `regpack-pack/pom.xml` (shape of `regpack-ports/pom.xml`: `regpack-model`, `regpack-ports`, test `regpack-tools`), `regpack-pack/src/main/java/dev/regpack/pack/PackFormat.java`, tests `PackFormatTest`, `NoWallClockRandomLocaleTest` (package `dev.regpack.pack`). Modify `pom.xml` (module after `regpack-ports`; `dependencyManagement`), `regpack-engine/pom.xml` (compile `regpack-pack`; test `regpack-bench`), `regpack-app/pom.xml` (compile `regpack-pack`), `DistributionLayoutIT` (`JARS` gains `lib/regpack-pack.jar` AND the expected set in `manifestClassPathNamesSiblingJarsWithoutVersions` becomes regpack-model.jar, regpack-ports.jar, regpack-pack.jar, regpack-inmemory.jar, regpack-engine.jar), `.github/workflows/ci.yml`, `regpack-bench/src/test/java/dev/regpack/bench/cli/SpikeWorkflowTest.java`.

```java
public final class PackFormat {            // dev.regpack.pack
  public static final int VERSION = 1;     // PackYaml/1 and IR format 1
  public static final int MAX_FILE_BYTES = 1 << 20, MAX_FILES = 256, MAX_TOTAL_BYTES = 16 << 20;
  public static final int MAX_LINE_CHARS = 8192, MAX_SCALAR_CHARS = 8192, MAX_KEY_CHARS = 128;
  public static final int MAX_DEPTH = 32, MAX_NODES = 100_000;
  // engine hard caps for pack-declared limits (only an engine build raises them)
  public static final int MAX_RULE_NODES = 4_096, MAX_LIST_ITEMS = 4_096, MAX_STRING_CODE_POINTS = 65_536;
  public static final long MAX_RECORD_BUDGET = 10_000_000L;
}
```
**Tests.** `PackFormatTest#limitsArePinned` (each constant equals the value above, the four caps included; a limit changes only by a deliberate edit). `NoWallClockRandomLocaleTest#packMainClassesAreClean` (same body as the engine's: `ForbiddenCallScan.scan(Path.of(System.getProperty("regpack.classes.dir")), EnumSet.allOf(RuleSet.class))` is empty and `PackFormat.class` exists in that directory).
**Run.** Red `T regpack-pack 'PackFormatTest,NoWallClockRandomLocaleTest'` (expected: `Could not find the selected project in the reactor: regpack-pack`). In `ci.yml` set `branches: [main, 'p0/**', 'p1a/**', 'p2a/**']` and change the one expected line of `SpikeWorkflowTest#ciPushFilterNamesP1aBranches` to match (rename it `ciPushFilterNamesTheWorkBranches`; `grep` shows `docs/traceability.md` does not cite it). Green: the same command, `T regpack-bench 'SpikeWorkflowTest'`, `IT regpack-app 'DistributionLayoutIT'`, `MVN -B -ntp verify`, `bash scripts/check-gates.sh` (`pins: OK`, `isolation: OK`). Commit `COMMIT "build: add the regpack-pack module and run CI on p2a branches"` (AP-3 at the first push).

### Task 3 (T3): `EngineBuild` and the replay-compatibility rule

**Files:** Create `regpack-model/src/main/java/dev/regpack/model/EngineBuild.java`, `.../EngineBuildTest.java`.

```java
public record EngineBuild(int major, int minor, int patch, int libraryVersion) {   // each component 0..9999
  public static EngineBuild parse(String text);       // "<major>.<minor>.<patch>+lib<N>", ASCII digits, no leading zeros
  @Override public String toString();
  public boolean replayCompatibleWith(EngineBuild other);   // same major AND same libraryVersion; symmetric
  public boolean satisfies(EngineBuild engineMin);    // this is the running engine
}
```
**The rule (spec 6.3, "P2a defines the rule"):** companion section 5.1.
**Tests.** `parseAndToStringRoundTrip` ("1.0.0+lib1", "12.34.56+lib789"); `rejectsMalformedText` ("", "p0", "1.0.0", "1.0.0+lib", "1.0.0+lib01", "01.0.0+lib1", "1.0.0+LIB1", leading or trailing space, "-1.0.0+lib1", "1.0.0+lib1+x", an Arabic-Indic digit, a fullwidth digit, "10000.0.0+lib1": `IllegalArgumentException`); `sameMajorAndLibraryAreReplayCompatible` (1.0.0+lib1 with 1.7.3+lib1); `differentMajorIsNotReplayCompatible`; `differentLibraryVersionIsNotReplayCompatible`; `replayCompatibilityIsSymmetric`; `satisfiesMeansSameMajorSameLibraryAndNotOlder` (running 1.2.0+lib1 satisfies 1.0.0+lib1, 1.2.0+lib1 and 1.1.5+lib1, not 1.2.1+lib1, 1.3.0+lib1, 2.0.0+lib1, 1.0.0+lib2); `constructorRejectsNegativeAndOversizedComponents`.
**Run.** Red `T regpack-model 'EngineBuildTest'` (`cannot find symbol ... EngineBuild`); green: same, plus `NoWallClockRandomLocaleTest`. Commit `COMMIT "feat(model): add EngineBuild with the replay-compatibility rule"`.

### Task 4 (T4): The strict YAML subset parser, accepting side

**PackYaml/1:** companion section 5.2.

**Files:** Create in `regpack-pack/src/main/java/dev/regpack/pack/yaml/`: `YamlNode`, `YamlScalar`, `YamlList`, `YamlMap`, `PackSyntaxException`, `StrictYaml`; test `StrictYamlTest`.

```java
public sealed interface YamlNode permits YamlScalar, YamlList, YamlMap { int line(); }   // 1-based
public record YamlScalar(String value, int line) implements YamlNode {}
public record YamlList(List<YamlNode> items, int line) implements YamlNode {}            // unmodifiable
public record YamlMap(Map<String, YamlNode> entries, int line) implements YamlNode {}    // unmodifiable, file order
public final class PackSyntaxException extends RuntimeException {                        // serialVersionUID
  public PackSyntaxException(String code, int line, String excerpt);                     // "line <n>: <code>: <excerpt>"
  public String code(); public int line();
}
public final class StrictYaml {
  public record Limits(int maxChars, int maxLineChars, int maxScalarChars, int maxKeyChars, int maxDepth, int maxNodes) { public static Limits defaults(); } // from PackFormat
  public static YamlNode parse(String text);  public static YamlNode parse(String text, Limits limits);
}
```
**Tests.** `parsesBlockMappingsSequencesAndFlowCollections` (one document with every construct; exact tree and `line()`); `everyScalarIsAString` (`NO yes null ~ 1e3 0o17 2018-01-03 .inf 0x1F y true 007` come back as the exact text); `quotedScalarsKeepTheirTextAndEscapes` (`"a: b # c"`, `'it''s'`, `"q\"uote\\"`, `"line\nbreak"`, `''`); `commentsAreCutOutsideQuotesOnly` (`a: b # c` is `b`; `a: b#c` is `b#c`); `fileOrderIsKeptAndTheTreeIsImmutable` (also in flow collections); `crlfAndLfParseToEqualTrees`; `sequenceItemMappingMayStartOnTheDashLine` (and `-` alone with an indented block); `flowCollectionsNestOnOneLine` (`{a: [1, {b: c}], d: [], e: {}}`); `plainScalarsMayContainBracketsQuotesAndCommasInBlockContext` (`when: event.kind in ['A','B']`); `keysMayBeDottedOrQuoted`; `emptyAndCommentOnlyDocumentsAreEmptyMaps`; `lineNumbersAreOneBased`.
**Run.** Red `T regpack-pack 'StrictYamlTest'` (`cannot find symbol ... StrictYaml`). Implement a line-based parser: one pass that normalises line ends and records (line, indent, text) after comment stripping, then recursive descent by indentation with an explicit depth counter; no backtracking regex. Codes in this task: `YAML_SYNTAX`, `YAML_BAD_INDENT`, `YAML_DUPLICATE_KEY`, `YAML_UNTERMINATED`, `YAML_EMPTY_VALUE`, `YAML_BAD_KEY`. Green: same command. Commit `COMMIT "feat(pack): add the strict YAML subset parser, accepting side"`.

### Task 5 (T5): The YAML parser: rejections, limits and hostile input

**Files:** Modify `StrictYaml.java`, `PackSyntaxException.java`; create `dev/regpack/pack/PackText.java` and tests `StrictYamlRejectionTest`, `StrictYamlLimitsTest`, `StrictYamlHostileInputTest`, `PackSyntaxExceptionTest` (package `dev.regpack.pack.yaml`).

**Codes:** `YAML_TAB`, `YAML_BAD_CHARACTER`, `YAML_ANCHOR`, `YAML_ALIAS`, `YAML_TAG`, `YAML_MERGE_KEY`, `YAML_DIRECTIVE`, `YAML_DOCUMENT_MARKER`, `YAML_BLOCK_SCALAR`, `YAML_COMPLEX_KEY`, `YAML_NESTED_SEQUENCE`, `YAML_MULTILINE_SCALAR`, `YAML_BAD_ESCAPE`, `YAML_TOO_LARGE`, `YAML_TOO_DEEP`, `YAML_TOO_MANY_NODES`. **The character policy** is one public static method in the new class `dev.regpack.pack.PackText` (name and signature free, e.g. `firstBad(String text, boolean allowLf)` returning the first offending code point and index, or empty), used by `StrictYaml` (whole text, first), `TableParser` (every cell, T7) and `PackGoldenLoader` (every decoded JSON string, T19). It refuses by code point, locale independent, using `Character.getType` and explicit ranges: the general categories Cc (LF only when `allowLf`; CR only inside the CRLF of whole-file YAML text; TAB never), Cf, Cs, Co, Zl, Zp; noncharacters (U+FDD0 to U+FDEF, U+xFFFE and U+xFFFF); and the default-ignorable code points that are not Cf: U+034F, U+115F to U+1160, U+17B4 to U+17B5, U+180B to U+180F, U+3164, U+FE00 to U+FE0F, U+FFA0, U+E0000 to U+E0FFF. `StrictYaml` keeps its codes: `YAML_TAB` for a tab wherever it appears, `YAML_BAD_CHARACTER` otherwise.
**Tests.** `StrictYamlRejectionTest` (parameterised on input and code; each also asserts `line()`): `tabsAnywhere` (indentation, plain, quoted, after the colon); `badCharacters` (BOM, NEL, LS, PS, U+202E, U+200B, U+2060, a lone high surrogate, BEL, DEL, NUL, a lone CR, U+E0041, U+FE0F, U+00AD, U+034F, U+3164, U+206A, U+FFF9, U+E000); `forbiddenConstructs` (`&a x`, `*a`, `!!str x`, `!x`, `!<t> x`, `<<: {a: b}`, `%YAML 1.2`, `---`, `...`, `a: |`, `a: >`, `? a`, `- - x`, `a: b: c`); `duplicateKeysInBlockFlowAndNestedMaps` (keys differing only in case are allowed); `emptyValueIsRejected`; `multiLineScalarsAreRejected`; `badEscapes` (`"\x41"`, a backslash-u escape with four hex digits, `"\t"`, `"\q"`); `unterminatedQuoteAndFlow`. `StrictYamlLimitsTest`: each limit rejects one past it and accepts exactly at it (file, line, scalar, key, depth in block and flow, node count). `StrictYamlHostileInputTest`: `randomMutationsOnlyEverRaisePackSyntaxException` (seed 20261016; 20,000 insert, delete and swap mutations over six valid documents with an alphabet of `& * ! | > % ? - : [ ] { } " ' # \ ,`, a tab, LF, CR, a BOM and a multi-byte character; every outcome is a tree or a `PackSyntaxException`); `deeplyNestedFlowFailsFastWithTooDeep` (10,000 `[`); `tenThousandShortKeysStayWithinTheNodeLimit`. `PackSyntaxExceptionTest#messagesAreBoundedAndEscaped` (a 10,000-character line with control and bidirectional characters: message at most 160 characters, excerpt at most 24, no raw control or bidirectional character).
**Run.** Red `T regpack-pack 'StrictYamlRejectionTest,StrictYamlLimitsTest,StrictYamlHostileInputTest,PackSyntaxExceptionTest,StrictYamlTest'` (failures naming the missing codes; `StrictYamlTest` still green). Implement the character pass, the limits (file size first; node count while building) and the refusals. Commit `COMMIT "feat(pack): refuse every non-subset YAML construct, bound the input and test hostile input"`.

### Task 6 (T6): Pack source, CHECKSUMS, sealing and the manifest

**Files:** Create in `regpack-pack/src/main/java/dev/regpack/pack/`: `source/PackSource`, `source/Checksums`, `source/Seal`, `check/PackProblem`, `model/SemVer`, `model/PackLimits`, `model/ReportDefinition`, `model/PackManifest`; tests `ChecksumsTest`, `PackSourceTest` (package `source`), `PackProblemTest` (package `check`), `ManifestTest` (package `model`).

```java
public record PackProblem(String code, String path, int line, String message) implements Comparable<PackProblem> {   // order: path, line, code
  public PackProblem { /* code must match [A-Z][A-Z0-9_]{2,47}; path cut to 128, message to 160; every char outside printable ASCII becomes \uXXXX */ }
  public static String excerpt(String untrusted);                // callers wrap untrusted text: escaped, cut to 24
}
public final class PackSource {
  public static PackSource of(Map<String, byte[]> files);        // validates names, counts, sizes; copies
  public static PackSource fromDirectory(Path dir);              // regular files only, '/' separators; limits checked before any read
  public static PackSource fromClasspath(Class<?> anchor, String root);   // root + "/CHECKSUMS", then each listed file
  public Set<String> paths();  public boolean has(String path);  public byte[] bytes(String path);
  public String text(String path);                               // strict UTF-8, no BOM, CRLF -> LF
}
public final class Checksums {
  public static String render(PackSource source);                // "<sha256 hex>  <path>\n", sorted; not CHECKSUMS or ATTESTATION; hashes LF-normalised bytes
  public static List<PackProblem> verify(PackSource source);
}
public final class Seal { public static int seal(Path dir); public static void main(String[] args); }   // rewrites <dir>/CHECKSUMS, prints "sealed <n> files"
public record SemVer(int major, int minor, int patch) { public static SemVer parse(String text); }       // plain major.minor.patch only
public record PackLimits(int ruleNodes, int listItems, int stringCodePoints, OptionalLong recordBudget) { public static PackLimits defaults(); } // 512, 256, 4096, empty
public record ReportDefinition(String id, String input, List<String> scopeTables, List<String> deriveTables,
    List<String> validate, Optional<String> lifecycle, List<String> endpointProfiles, List<Leg> legs) {}
public record PackManifest(String id, SemVer version, String regime, String jurisdiction, LocalDate validFrom,
    Optional<LocalDate> validTo, String asAtField, EngineBuild engineMin, String inputSchema,
    Optional<String> supersedes, PackLimits limits, List<ReportDefinition> reports) {
  public static Optional<PackManifest> parse(PackSource source, List<PackProblem> problems);   // empty on any problem
}
```
File rules: segments `[A-Za-z0-9_.-]+` without a leading dot, no `..`, backslash or empty segment; extension `.yaml` or `.json`, or exactly `CHECKSUMS` or `ATTESTATION`; at most `MAX_FILES` files, `MAX_FILE_BYTES` each, `MAX_TOTAL_BYTES` together; no two paths equal ignoring case; a base name (before the first dot) equal ignoring case to CON, PRN, AUX, NUL, COM1 to COM9 or LPT1 to LPT9 is `PATH_RESERVED_NAME`. `fromDirectory` walks with `NOFOLLOW_LINKS`, stops with `PACK_TOO_MANY_FILES` at `MAX_FILES`+1 entries, requires a regular file by `Files.readAttributes(NOFOLLOW_LINKS)` (else `PACK_NOT_REGULAR`; covers symlinks and other reparse points), checks `attrs.size()` and the running total against the limits BEFORE reading, and reads at most `MAX_FILE_BYTES`+1 bytes. `fromClasspath` validates every name listed in CHECKSUMS with the segment rules before any `getResourceAsStream` and reads at most `MAX_FILE_BYTES`+1 bytes. Problem codes are upper-case tokens named by the tests, for example `CHECKSUM_MISMATCH`, `CHECKSUM_UNLISTED_FILE`, `IMPORTS_RESERVED`, `REPORT_KEY_RESERVED`, `LEG_VOCABULARY_RESERVED`, `VALIDITY_WINDOW_RESERVED`.
`pack.yaml` (companion section 2): required `id` (the `Regime` shape), `semver`, `regime` (family, `Regime` shape), `jurisdiction`, `valid` (only `legal`, else `VALIDITY_WINDOW_RESERVED`, RSV-6), `as_at_field`, `engine_min` (an `EngineBuild` text), `input_schema` (64 hex), `reports` (at least one); optional `supersedes`, `limits` (ASCII decimal integers from 1 to the `PackFormat` cap, else `LIMITS_OUT_OF_RANGE`), `imports` (absent or `[]`: BRK-13, RSV-6). Report keys: `id` (the `Regime` of its decisions: BRK-14; unique), `input`, `scope_tables`; optional `derive_tables`, `validate`, `lifecycle`, `endpoint_profiles`, `legs` (closed vocabulary, BRK-12); `schema` is `REPORT_KEY_RESERVED` (report schemas need the XSD: P5, P8; gate G3). Dates use `LocalDate.parse`; the scalar `null` is not a date.
**Tests.** `ChecksumsTest`: `renderIsSortedAndLfNormalised`; `verifyAcceptsRenderedChecksums`; `aChangedByteIsAMismatch`; `anUnlistedFileIsAProblem`; `aMissingFileIsAProblem`; `aDuplicateEntryIsAProblem`; `aCrlfCheckoutVerifiesLikeLf`; `attestationAndChecksumsAreNotListed`; `malformedLinesAreRejected` (63 hex digits, upper-case hex, one space). `PackSourceTest`: `rejectsTraversalAbsoluteHiddenAndBackslashNames`; `rejectsCaseCollisions`; `rejectsUnknownExtensions`; `rejectsBomAndInvalidUtf8`; `enforcesFileCountAndSizeLimits`; `fromDirectoryUsesForwardSlashes`; `fromDirectoryRejectsSymlinks` (`PACK_NOT_REGULAR`; `assumeTrue` the platform can make one); `fromDirectoryChecksSizeBeforeReading` (a sparse file larger than the limit made with `RandomAccessFile.setLength`); `fromClasspathReadsOnlyTheFilesTheChecksumsList` (a small test resource folder); `fromClasspathRefusesTraversalNamesInChecksums`; `reservedDeviceNamesAreRefused`; `sealRewritesChecksumsAndIsIdempotent` (temporary directory). `PackProblemTest#messageAndPathAreBoundedAndEscapedWhateverTheCaller`. `ManifestTest`: `aMinimalManifestParses`; `unknownAndMissingKeysAreNamed`; `semverMustBePlain` (`1.2.3-rc1`, `1.2`, Arabic-Indic and fullwidth digits refused); `regimeAndReportIdsMustHaveTheRegimeShape`; `duplicateReportIdsAreRejected`; `importsMustBeEmpty`; `reportSchemaKeyIsReserved`; `legVocabularyBeyondMifirIsReserved`; `reportLegsDefaultToTheMifirVocabulary`; `packValidityWindowsOtherThanLegalAreReserved`; `limitsDefaultsAndBounds` (accepted at the cap and at 1; refused at 0, cap+1, a 20-digit number, an Arabic-Indic and a fullwidth digit string); `validToMustBeAfterFrom`; `datesNeedTheIsoForm`; `engineMinMustParse`.
**Run.** Red `T regpack-pack 'ChecksumsTest,PackSourceTest,ManifestTest'` (missing types); green: same. Commit `COMMIT "feat(pack): add the pack source, CHECKSUMS, sealing and the manifest"`.

### Task 7 (T7): Decision tables: typed model, parser and static checks

**Files:** Create in `regpack-pack/src/main/java/dev/regpack/pack/`: `model/DecisionTable`, `TableRow`, `CitationRef`, `Validity`, `TableParser`; `check/TableChecks`; tests `TableParserTest` (package `model`), `TableChecksTest` (package `check`).

```java
public record CitationRef(String sourceId, String provisionPath, CitationRole role) {}
public record Validity(LocalDate from, Optional<LocalDate> to) {}                      // [from, to)
public record TableRow(String id, String node, int priority, String when, Outcome outcome, ReasonCode reason,
    Optional<String> ruleRef, List<CitationRef> cite, String rationale, Optional<Validity> valid,
    Map<String, String> set, List<Leg> legs) {}
public record DecisionTable(String path, String id, List<String> inputs, String catchAllRowId, List<TableRow> rows) {}  // rows ascending by priority
public final class TableParser { public static Optional<DecisionTable> parse(String path, YamlNode root, List<PackProblem> problems); }
public final class TableChecks { public static List<PackProblem> check(DecisionTable table); }
```
Table shape, cell rules and problem codes: the table block of companion section 2 and section 5.3.
**Tests.** `TableParserTest`: `parsesTheSampleTableShape`; `numbersAndDatesNeedTheirExactForm` (priority `0300`, `+5`, `3e2`, ` 5`, `99999999999`, an Arabic-Indic digit string; date `2018-1-3`, `2018-02-30`); `sourceIdAndRuleRefShapes`; `supervisoryAndEventWindowsAreReserved`; `everyCellMustBeOneLineOfCleanText`; `unknownRowKeyIsRejected`; `hitPolicyMustBeFirstAndPriorityExplicit`; `outcomeMustBeAnExactName`; `legsOnlyOnReportRowsAndDefaultToMarket`; `setNamesAndValuesAreRestricted`; `provisionPathCharset`; `rowsAreSortedByPriorityNotFileOrder` (numerically: 9 before 10); `reasonCodeShapeIsChecked`. `TableChecksTest`: `catchAllMissing`; `catchAllNotLast` (the catch-all is found by its flag, not by its `when` text); `catchAllNotTrue`; `catchAllNotUndetermined`; `catchAllHasValidity`; `aSecondTrueRowIsRefused`; `duplicatePriority`; `duplicateRowId`; `everyRowNeedsACitationTheCatchAllToo` (I5); `nonCatchAllRowNeedsValidity`; `validityInverted`; `whenMayNotStartWithAFormulaCharacter` (`=`, `+`, `-`, `@`, also after a leading space; `(-x) > 1` passes; no other cell can start with a formula character because of these shapes and the id, node, priority and date charsets); `emptyTable`; `aCleanTablePasses`.
**Run.** Red `T regpack-pack 'TableParserTest,TableChecksTest'`; green: same. Commit `COMMIT "feat(pack): add typed decision tables with the shape and static checks of spec 6.1 and 6.2"`.

### Task 8 (T8): The reason-code registry, the Legal Source Registry and rationales

**Files:** Create in `regpack-pack/src/main/java/dev/regpack/pack/`: `model/ReasonEntry`, `ReasonRegistry`, `Provision`, `SourceEntry`, `SourceRegistry`, `RationaleRegistry`, `RegistryParser`; `check/RegistryChecks`; tests `RegistryParserTest` (package `model`), `RegistryChecksTest` (package `check`).

```java
public record ReasonEntry(ReasonCode code, String label, Category category, Outcome defaultOutcome, String owner,
    LocalDate validFrom, Optional<LocalDate> validTo) { public enum Category { EXCLUSION, INCLUSION, OUT_OF_SCOPE, DATA_QUALITY, DEFAULT } }
public record ReasonRegistry(String prefix, Set<String> nodes, Map<String, ReasonEntry> entries) {}     // keyed by code
public record Provision(String path, CitationRole role, Status status, Optional<String> quote, Optional<Sha256> quoteSha256) { public enum Status { IN_FORCE, REPEALED, PROPOSED } }
public record SourceEntry(String sourceId, String version, LocalDate retrievedAt, Sha256 contentSha256, Optional<String> eli, Map<String, Provision> provisions) {}
public record SourceRegistry(Map<String, SourceEntry> sources) { public Optional<Provision> resolve(String sourceId, String provisionPath); }
public record RationaleRegistry(Map<String, String> texts) {}
public final class RegistryParser {   // each returns empty and adds problems on malformed input
  public static Optional<ReasonRegistry> reasons(YamlNode root, List<PackProblem> problems);
  public static Optional<SourceRegistry> sources(YamlNode root, List<PackProblem> problems);
  public static Optional<RationaleRegistry> rationales(YamlNode root, List<PackProblem> problems);
}
public final class RegistryChecks { public static List<PackProblem> check(ReasonRegistry r, SourceRegistry s, RationaleRegistry t, List<DecisionTable> tables); }
```
Spec 6.3 and BRK-11: a reason code is `<PREFIX>.<node>.<label>[.<provision>]`; `prefix` and `nodes` are declared in the `reasons.yaml` header (OI-7); the registry stores the outcome, not the code. `EXEC_ERROR`, `EXEC_BUDGET`, `EXEC_CIRCUIT_OPEN` and `ENRICH_MISS` are engine-reserved and may not be declared. Spec 6.4: a provision's `role` is BASIS or EXCEPTION (the law), INTERPRETATION or GUIDANCE; `quote_sha256` is the SHA-256 of the quote as parsed; `quote` is optional, at most 240 characters (plan limit, provisional; keeps pasted text short); `source_id` has the shape `[A-Za-z][A-Za-z0-9_.-]{0,63}`. Codes are named by the tests, for example `REASON_OUTCOME_MISMATCH`, `REASON_RESERVED`, `PROVISION_NOT_IN_FORCE`, `CITATION_ROLE_MISMATCH`, `QUOTE_HASH_MISMATCH` (its message carries `expected <64 hex>`).
**Tests.** `RegistryParserTest`: `parsesTheThreeRegistries`; `unknownAndMissingKeysAreNamed`; `sourceIdsNeedTheSharedShape`; `hashesMustBe64LowerCaseHex`; `datesNeedTheIsoForm`; `categoryAndStatusAreExactNames`. `RegistryChecksTest`: `aReasonMustMatchPrefixNodeAndShape`; `engineReservedReasonsCannotBeDeclared`; `aRowReasonMustExist`; `aRowOutcomeMustEqualTheReasonDefaultOutcome` (a checker comparing only the code fails this); `aRowNodeMustBeDeclared`; `aCitationMustResolveToASourceAndAProvision`; `aCitedProvisionMustBeInForce`; `aCitationRoleMustEqualTheProvisionRole`; `aQuoteNeedsItsHashAndTheHashMustMatch`; `aLongQuoteIsRefused`; `aRationaleMustExist`; `duplicateIdsAreRefused`; `aCleanSetPasses`.
**Run.** Red `T regpack-pack 'RegistryParserTest,RegistryChecksTest'`; green: same. Commit `COMMIT "feat(pack): add the reason-code registry, the Legal Source Registry and rationales with their checks"`.

### Task 9 (T9): The pack loader, the canonical IR and digest, reserved slots

**Files:** Create in `regpack-pack/src/main/java/dev/regpack/pack/`: `model/CanonicalInputSpec`, `LoadedPack`, `PackLoader`; `ir/PackIr`, `PackDigest`; tests `CanonicalInputSpecTest`, `PackLoaderTest`, `ReservedSlotsTest` (package `model`), `PackDigestTest` (package `ir`), helper `PackFixtures` (test sources: a small valid pack from text blocks; `sealed()` adds CHECKSUMS).

```java
public record CanonicalInputSpec(Map<String, Variable> variables) {
  public record Variable(ValueType type, Optional<String> enumName, Source source, boolean optional) { public enum Source { EVENT, STATE } }
}
public record LoadedPack(PackManifest manifest, CanonicalInputSpec input, Map<String, DecisionTable> scopeTables,
    ReasonRegistry reasons, SourceRegistry sources, RationaleRegistry rationales,
    Map<String, YamlNode> opaque, Map<String, Object> goldenJson, Optional<YamlNode> authorship, PackSource source,
    Sha256 digest, List<String> notes) { public boolean signed() { return false; } }          // A4
public record LoadResult(Optional<LoadedPack> pack, List<PackProblem> problems) {}
public final class PackLoader { public static LoadResult load(PackSource source); }
public final class PackIr { public static Map<String, Object> toMap(LoadedPack pack); }       // canonical-JSON-ready
public final class PackDigest { public static Sha256 of(LoadedPack pack); }                   // SHA-256 of Json.canonical(PackIr.toMap(pack))
```
**Loader order, reserved slots, canonical IR and digest (fail closed: a pack is returned only when `problems` is empty):** companion section 5.4.
**Tests.** `CanonicalInputSpecTest`: `parsesTypesEnumsSourcesAndOptional`; `unknownTypeIsRejected`; `stateVariablesAreAccepted`; `pathsMustBeDottedIdentifiers`. `PackLoaderTest`: `loadsTheFixturePack`; `missingChecksumsIsAProblem`; `inputSchemaDigestMustMatch`; `unreferencedTableFileIsAProblem`; `unknownRootFileIsAProblem`; `unknownFilesUnderAllowedDirectoriesAreProblems`; `aRowLegOutsideTheReportVocabularyIsAProblem`; `aDuplicateRuleRefIsAProblem`; `mismatchMessagesCarryTheExpectedHash` (input schema and quote hash); `asAtFieldMustBeATimestampVariable`; `reportFilesMustExist`; `noPackIsReturnedWhenAnyProblemExists`; `problemsAreSortedAndStable`; `signedIsAlwaysFalse`. `PackDigestTest`: `commentsKeyOrderRowOrderAndLineEndingsDoNotChangeTheDigest`; `everyEvaluatedFieldChangesTheDigest` (table-driven: a row's `when`, `outcome`, `reason`, `priority`, `node`, a citation path, a citation role, `rationale`, `valid.from`, a `set` value, `legs`, a reason label, a source version, a variable type, `limits.record_budget`, `limits.rule_nodes`, `engine_min`, `as_at_field`, `valid.legal.to` (pack and row), the table `catch_all`, the table `inputs`, `rule_ref`, a reason `default_outcome`, `category` and `owner`, a `quote` and its `quote_sha256`, a provision `status`, `retrieved_at`, the order of `scope_tables`); `digestIgnoresChecksums`; `digestOfTheFixtureIsPinned` (literal captured at the first green run, recorded under Pins created). `ReservedSlotsTest`: `importsMustBeEmpty` (BRK-13); `interpretationsMustBeEmpty`; `reportSchemaKeyIsReserved`; `legVocabularyBeyondMifirIsReserved` (BRK-12); `validityWindowsOtherThanLegalAreReserved` (RSV-6); `propertyTestsAreReserved`; `lifecycleKeysAreChecked` (BRK-16, a named clock accepted); `opaqueSectionsAreStrictParsed` (a tab in `lifecycle.yaml` surfaces as `YAML_TAB` with its line); `notInterpretedSectionsAreListed`.
**Run.** Red `T regpack-pack 'CanonicalInputSpecTest,PackLoaderTest,PackDigestTest,ReservedSlotsTest'`; green: same, then `T regpack-pack '*Test'`. Commit `COMMIT "feat(pack): load a whole pack with cross-file checks, reserved slots, canonical IR and digest"`.

### Task 10 (T10): The sample pack S0 (synthetic) and its pin

The same four rows as the P0 walking skeleton (three scope rules and the catch-all), as pack files. It is **synthetic**: no regulatory text, no ESMA or DRR artefact; reason prefix `SAMPLE`, regime `sample.s0`, sources `sample-law` and `regpack-spec`.

**Files:** Create `packs/sample.s0/`: `pack.yaml`, `CHECKSUMS`, `model/canonical-input.yaml`, `scope/sample-scope.yaml`, `reasons.yaml`, `sources.yaml`, `rationales.yaml`, `tests/authorship.yaml`, `tests/golden/{GC-N03-1,GC-N13-1,GC-N08-1,GC-DEFAULT-1}.json`. Tests `SamplePackTest`, `EveryCommittedPackIsSyntheticTest` in `regpack-pack/src/test/java/dev/regpack/pack/` (pack root `<regpack.repo.root>/packs/sample.s0`).

- `pack.yaml`: `id: sample.s0`, `semver: 0.1.0`, `regime: sample.s0`, `jurisdiction: EU`, `valid: {legal: {from: 2018-01-03}}`, `as_at_field: event.tradedAt` (a Legal decision, spec 6.4: the value only shows the shape), `engine_min: 1.0.0+lib1`, `input_schema: ` followed by 64 zeros first (the message of the first load gives the value), `reports: [{id: sample.s0, input: model/canonical-input.yaml, scope_tables: [scope/sample-scope.yaml]}]`.
- `model/canonical-input.yaml` declares exactly the 13 paths that `CanonicalBindings` (T15) binds: `event.kind` ENUM LifecycleKind, `event.tradedAt` TIMESTAMP, `venue.mic`, `venue.executionId`, `firm.lei`, `instrument.isin` STRING, `instrument.assetClass` ENUM AssetClass, `instrument.tradedOnVenue` BOOL, `client.kind` ENUM PartyKind, `client.lei` STRING optional, `trade.quantity`, `trade.price` DECIMAL, `trade.currency` STRING. `input_schema` is the digest named in the `INPUT_SCHEMA_DIGEST_MISMATCH` message of the first load.
- `scope/sample-scope.yaml`: table `sample.s0.scope`, `inputs: [client.kind, event.kind, instrument.assetClass, instrument.tradedOnVenue]`, `catch_all: S-DEFAULT-catch-all`; the first three rows carry `valid: {legal: {from: 2018-01-03}}`, the catch-all none:

| id | node | priority | `when` | outcome | reason | cite (source, path, role) | rationale |
|---|---|---|---|---|---|---|---|
| `S-N03-novation` | N03 | 300 | `event.kind in ['NOVATION','ASSIGNMENT']` | DO_NOT_REPORT | `SAMPLE.N03.EXCLUDED` | `sample-law`, `sample/1`, BASIS | RAT-0001 |
| `S-D14-natural-person` | N13 | 400 | `client.kind == 'NATURAL_PERSON'` | UNDETERMINED | `SAMPLE.N13.NATURAL_PERSON` | `regpack-spec`, `D14`, INTERPRETATION | RAT-0002 |
| `S-N08-equity-on-venue` | N08 | 800 | `instrument.assetClass == 'EQUITY' && instrument.tradedOnVenue` | REPORT, `legs: [MARKET]` | `SAMPLE.N08.IN_SCOPE` | `sample-law`, `sample/2`, BASIS | RAT-0003 |
| `S-DEFAULT-catch-all` | DEFAULT | 9999 | `true` | UNDETERMINED | `SAMPLE.DEFAULT.NO_RULE_HIT` | `regpack-spec`, `I5`, GUIDANCE | RAT-0004 |

- `reasons.yaml`: `prefix: SAMPLE`, `nodes: [N03, N08, N13, DEFAULT]`, four entries (categories EXCLUSION, OUT_OF_SCOPE, INCLUSION, DEFAULT; default outcomes equal the rows'; `owner: "agent:p2a-sample (Compliance owner not named, gate G1)"`; `valid.from: 2018-01-03`). `sources.yaml`: `sample-law` (`version: sample-1`, `retrieved_at: 2026-10-16`, `content_sha256` = SHA-256 of the text `sample-law sample-1`, computed in Bash with `printf '%s' '<text>' | sha256sum` (as are the quote hashes), provisions `sample/1` and `sample/2`, BASIS, IN_FORCE, quotes `SYNTHETIC SAMPLE TEXT 1, not law` and `... 2, not law` with their `quote_sha256`) and `regpack-spec` (`version: 2026-10-08 draft 3`, provisions `D14` INTERPRETATION and `I5` GUIDANCE, IN_FORCE, no quote). `rationales.yaml`: four short synthetic texts. `tests/authorship.yaml`: the four row ids, all `agent:p2a-implementer`.
- Golden cases (P0 JSON format, `author: agent:golden-author`, `"provenance": {"kind": "CONSISTENCY_ONLY"}`), base input `firmLei LEI-FIRM-0001`, `venueMic XPAR`, `eventKind NEW`, `tradedAt 2026-10-09T09:30:00.123456Z`, `assetClass EQUITY`, `isin ZZ0000000001`, `tradedOnVenue true`, `clientKind LEGAL_ENTITY`, `clientLei LEI-CLIENT-0001`, `quantity 100`, `price 12.25`, `currency EUR`, `venueExecutionId` EX-1 to EX-4: `GC-N03-1` (node N03, `eventKind NOVATION`) expects DO_NOT_REPORT, `SAMPLE.N03.EXCLUDED`, `[S-N03-novation]`; `GC-N13-1` (N13, `clientKind NATURAL_PERSON`, no `clientLei`) UNDETERMINED, `SAMPLE.N13.NATURAL_PERSON`, `[S-D14-natural-person]`; `GC-N08-1` (N08, base input) REPORT, `SAMPLE.N08.IN_SCOPE`, `[S-N08-equity-on-venue]`; `GC-DEFAULT-1` (DEFAULT, `assetClass DEBT`) UNDETERMINED, `SAMPLE.DEFAULT.NO_RULE_HIT`, `[S-DEFAULT-catch-all]`.

**Tests.** `SamplePackTest`: `loadsWithoutProblems`; `hasFourRowsFourReasonsTwoSourcesAndFourRationales`; `checksumsAreCurrent` (`Checksums.render(PackSource.fromDirectory(root))` equals the committed `CHECKSUMS`); `digestIsPinned` (a literal captured at the first green run, recorded under Pins created). `EveryCommittedPackIsSyntheticTest#noCommittedPackHasRegulatoryTextAndEveryCaseIsConsistencyOnly` loops over every directory under `<regpack.repo.root>/packs/` and every directory under `regpack-engine/src/test/resources/packs/` (the second-regime fixture exists from T23; the test loops over what exists) and asserts: source ids only from `sample-law`, `regpack-spec`, `fixture-law`; every quote starts with `SYNTHETIC`; every golden case is `CONSISTENCY_ONLY`; no file contains (ignoring case) `europa.eu`, `rts 22`, `mifir` or `drr`, and no file name contains `workbook`. Failure message: "a real pack needs gate G1 and G3 decisions first; the owner edits this allow-list".
**Run.** Red `T regpack-pack 'SamplePackTest,EveryCommittedPackIsSyntheticTest'` (pack directory missing). Create the files, run the first load for the `input_schema` digest, then seal (Bash, repository root, after `MVN -B -ntp -q -pl regpack-pack -am compile`): `java -cp "regpack-pack/target/classes;regpack-model/target/classes;regpack-ports/target/classes" dev.regpack.pack.source.Seal packs/sample.s0` (PowerShell: the same; use `:` as separator on Linux); expected `sealed 11 files`. Green: same test command. Commit `COMMIT "feat(packs): add the synthetic sample pack S0"`.

### Task 11 (T11): The expression layer, compile side: lexer, parser, tree, static analysis

**Files:** Create in `regpack-engine/src/main/java/dev/regpack/engine/expr/`: `ErrorKind`, `ExprException`, `Lexer`, `Ast`, `Parser`, `Library` (function table only), `InHouseExpression`; tests `LexerTest`, `ParserTest`, `StaticAnalysisTest`. The existing `dev.regpack.engine.NoWallClockRandomLocaleTest` scans the new classes.

```java
public enum ErrorKind { ABSENT, TYPE, OVERFLOW, DIV_ZERO, NON_TERMINATING, PARSE, RANGE, SCALE, LOSSY, OTHER }
public class ExprException extends ExpressionException { public ExprException(ErrorKind kind, String message); public ErrorKind kind(); } // no stack trace
public final class InHouseExpression implements Expression {
  public static final String ID = "inhouse-cel-subset-1";
  public record Limits(int maxNodes, int maxListItems, int maxStringCodePoints) { public static Limits defaults(); }  // 512, 256, 4096; maxNodes is the parse-work cap (T15 passes 16 x rule_nodes)
  public InHouseExpression();  public InHouseExpression(Limits limits);
  @Override public Compiled compile(String source, Map<String, ValueType> variables);    // ExprException(PARSE) on any compile error
  public static final class Compiled implements CompiledExpression {
    public String source(); public Value evaluate(Bindings bindings, CostMeter meter);   // T12; UnsupportedOperationException until then
    public int nodeCount(); public SortedSet<String> paths(); public long staticWorstCaseUnits();
  }
}
```
`Library` here is the enum of functions (name, arity, global or method) from companion section 3; behaviour arrives in T13. Build the parser as precedence climbing with loops for left-associative chains (no recursion proportional to chain length); count nodes while building and fail at `maxNodes` before the tree grows past it; track nesting (100) and tree depth (256: a recursive evaluator must fit a small worker stack).
**Tests.** `LexerTest`: `decimalLiteralKeepsScale` (`12.50` has scale 2); `integerOutOfRangeIsRejected`; `exponentAndHexLiteralsAreRejected`; `unterminatedStringIsRejected`; `onlyTheListedEscapesAreAccepted`; `nonAsciiIdentifierIsRejected` (the Turkish dotted capital I); `nonAsciiTextInsideAStringIsAccepted`; `whitespaceIsAsciiOnly` (no-break space refused). `ParserTest`: `precedenceAndAssociativity` (`1 + 2 * 3`; `a - b - c` groups left; `!a && b`); `ternaryIsRightAssociativeAndLowest`; `nullLiteralIsRefused`; `loopMacrosAreRefused` (each of the five); `unknownFunctionAndMethodAreRefused`; `arityIsChecked`; `anUndeclaredPathIsACompileError` (also inside `has`); `fieldAccessOnANonPathIsRefused`; `methodCallOnAnyExpressionIsAccepted` (`'abc'.startsWith('a')`); `methodCallOnADottedDeclaredPath` (`instrument.isin.startsWith('ZZ')` with declared `instrument.isin` compiles; `instrument.isin.nosuch('x')` is `PARSE`); `nodeCeilingIsEnforcedWhileParsing` (a list literal of 3,000 items with `maxNodes` 1,000: the message names 1,000); `nestingAndDepthLimits` (nesting 100, tree depth 256); `aLongLeftChainFailsCleanlyNotWithStackOverflow` (50,000 terms with `maxNodes` 100,000: `PARSE`, never `StackOverflowError`); `emptySourceAndTrailingTokensAreRefused`; `everyCompileErrorIsKindParse`. `StaticAnalysisTest`: `nodeCountAndPathsAreReported` (sorted; `has` paths included; the path argument of `has(...)` is not a node: `has(a.b)` has `nodeCount` 1 and worst case 1, and `paths()` still contains `a.b`); `worstCaseOfALiteralIsOne`; `worstCaseChargesListAndStringSurchargesAtTheLimits` (limits 256 and 4,096: `x in y` is 3 + 256 = 259; `s + t` is 3 + 2 x 4096 / 256 + 256 + 256 = 547, the bound adds every possible surcharge of the operator); `aListLiteralUsesItsOwnLength`; `bothBranchesOfATernaryAreCounted`.
**Run.** Red `T regpack-engine 'LexerTest,ParserTest,StaticAnalysisTest'` (`cannot find symbol ... InHouseExpression`); green: same, then `T regpack-engine 'NoWallClockRandomLocaleTest'`. Commit `COMMIT "feat(engine): add the expression layer compile side with static analysis"`.

### Task 12 (T12): Expression evaluation, errors, cost and the ruling switches

**Files:** Create `Evaluator`, `Rulings` in `dev.regpack.engine.expr`; modify `InHouseExpression` (constructor `InHouseExpression(Limits, Rulings)`, `evaluate`); tests `EvaluatorTest`, `CostTest`, `AmplificationTest`, `EvaluatorRulingsTest`, `ExprExceptionTest`.

```java
public record Rulings(IntDecimalMix intDecimalMix, AndLeftError andLeftError, OrLeftError orLeftError,
                      HasBelowAbsent hasBelowAbsent) {
  public enum IntDecimalMix { ALLOW, DENY }                       // D13: 1 + dec('0.5')
  public enum AndLeftError { PROPAGATE, ABSORB }                  // A04: error && false
  public enum OrLeftError { PROPAGATE, ABSORB }                   // E06: error || true
  public enum HasBelowAbsent { FALSE, ERROR_IF_ANCESTOR_ABSENT }  // A07
  public static Rulings provisional();                            // ALLOW, PROPAGATE, PROPAGATE, FALSE
}
```
`ABSORB` for `&&`: when the left operand raised an error, the right is evaluated and if it is `false` the result is `false`, otherwise the error propagates; `||` mirrors it with `true`. `ERROR_IF_ANCESTOR_ABSENT`: `has(p)` raises `ABSENT` when a declared path that is a strict dotted prefix of `p` is unbound. S07 (enum with string) has no switch: it is equality by name (A2). Semantics, cost model and error kinds are in companion section 3; every charge is made **before** the work it pays for.
**Tests.** `EvaluatorTest`: `decimalArithmeticIsExact` (`0.1 + 0.2 == 0.3`); `decimalEqualityIgnoresScale`; `integerOverflowIsAnError` (`+ - *`, negating the minimum); `divisionRules` (int truncates; `1 / 3` is `NON_TERMINATING`; `1 / 0` and `0.5 / 0.0` are `DIV_ZERO`); `absenceIsAnErrorUnlessGuarded`; `andOrShortCircuitLeftToRightAndErrorsPropagate` (`true || (1 / 0 == 1)` is true; `(1 / 0 == 1) || true` is `DIV_ZERO`; `false && x` never reads `x`); `ternaryNeedsABool`; `inAndContains`; `typeClashesAreTypeErrors` (`1 < 'a'`, `1 == 'a'`, `!1`, `true + 1`); `enumsCompareByNameWithStrings`; `listsAndStringsConcatenate`; `sizeCountsCodePointsNotUtf16` (U+1F600 is 1); `resultsOverTheLimitAreRangeErrors` (three lists of 256 items: `RANGE` at the first `+`); `deepestAllowedTreeEvaluatesOnASmallStack` (a left chain and a right-nested ternary of maximal allowed depth, each evaluated in `new Thread(null, runnable, "deep", 256 * 1024)`: a result or an `ExprException`, never `StackOverflowError`; `PackProgram` does not catch it). `CostTest`: `costIsOneUnitPerNodeAndDeterministic` (`1 + 2` is 3 units on 1,000 repeats); `listScanChargesTheListLength` (`x in [1, 2, 3]` is 9: 6 nodes plus 3 scanned); `aTernaryChargesOnlyTheTakenBranch`; `chargeComesBeforeTheWork` (budget 2 for `1 + 2`: `BudgetExceededException`, `meter.used()` is 2); `budgetExceptionNamesTheEnteredRule`; `runtimeCostNeverExceedsTheStaticWorstCase` (property, seed 7, 2,000 generated expressions and bindings within the limits; the generator produces `+` chains of up to 6 operands with operands at exactly the limit). `AmplificationTest`: `concatenationOverTheBudgetFailsFastWithBudgetExceeded` (`s + s + ... + s`, 201 terms, `s` of 100,000 characters, budget 10,000: `BudgetExceededException` after at most 8 concatenations; the layer is built with `Limits(512, 256, 1_000_000)` so that the budget, not the cap, fires); `aConcatenationLongerThanTheLimitIsRange`. `EvaluatorRulingsTest`: one test per switch (four), provisional and alternative value, with the vector expressions. `ExprExceptionTest#carriesAKindAndNoStackTrace`.
**Run.** Red `T regpack-engine 'EvaluatorTest,CostTest,AmplificationTest,EvaluatorRulingsTest,ExprExceptionTest'` (`UnsupportedOperationException` from `evaluate`); green: same. `Rulings` is read at construction into a final field (no static state). Commit `COMMIT "feat(engine): add expression evaluation with deterministic cost units and the ruling switches"`.

### Task 13 (T13): The expression library

**Files:** Modify `Library.java`, `Evaluator.java`; tests `LibraryTest`, `LibraryCostTest`. Add `public static final int VERSION = 1;` to `Library` (published as `EngineInfo.LIBRARY_VERSION`). Functions and costs are in companion section 3; formatting is written by hand; `upper` and `lower` use `Locale.ROOT`. `dec(text)` accepts `[+-]?digits[.digits]` only (an exponent is `PARSE`); more than 38 digits is `SCALE`.
**Tests.** `LibraryTest`: `decKeepsScaleAndAcceptsInts` (`dec('12.50')` has scale 2); `decRejectsTextThatIsNotADecimal` (`'abc'`, `'1e5'`, `''`, `' 1'`: `PARSE`; 40 digits: `SCALE`); `roundModesAndRange` (`round(dec('2.5'),0,'HALF_EVEN')` is 2, `HALF_UP` 3, `round(dec('1.005'),2,'HALF_UP')` is 1.01; places outside 0 to 38 `RANGE`; another mode `OTHER`); `stringFormsAreCanonical` (`string(dec('12.50'))` is `12.5`, `string(dec('0.00'))` is `0`; a list is `TYPE`); `intConversions` (an exact decimal fits, `dec('1.5')` is `LOSSY`, `'abc'` `PARSE`, above 64 bits `OVERFLOW`); `timestampForms` (`Z` and offsets normalised; seconds and zone mandatory; nanoseconds `RANGE`; `23:59:60` `PARSE`); `dateForms` (`2026-02-29` `PARSE`); `isoMicrosIsFixedWidth` (years 0000 to 9999); `dayOfWeekNamesAreEnglishUpperCase`; `upperAndLowerIgnoreTheDefaultLocale` (under `tr_TR`: `upper('title')` is `TITLE`, `lower('TITLE')` is `title`); `sizeAndContains`; `startsWithAndEndsWith`; `libraryVersionIsOne`. `LibraryCostTest#everyFunctionChargesItsDocumentedUnits` (one assertion per cost-model row with strings of 0, 255, 256 and 512 code points; string `contains` charges the product `(cp(x) / 256 + 1) x (cp(y) / 256 + 1) - 1`, 288 for two strings of 4,096 code points).
**Run.** Red `T regpack-engine 'LibraryTest,LibraryCostTest'`; green: same, then `T regpack-engine 'EvaluatorTest,CostTest'`. Commit `COMMIT "feat(engine): add the expression library (decimal, text, timestamp, date) at library version 1"`.

### Task 14 (T14): Conformance suite, the one rulings file, engine info and the semantics fingerprint [OWNER INPUT]

**Files:** Create `regpack-engine/src/main/resources/dev/regpack/engine/expr/rulings.tsv`, `regpack-engine/src/main/java/dev/regpack/engine/pack/EngineInfo.java`; modify `Rulings.java` (`load()`); create in `src/test/java/dev/regpack/engine/expr/conformance/`: `Vector`, `VectorFile`, `ConformanceHarness`, tests `VectorFileTest`, `ConformanceSuiteTest`, `RulingsFileTest`, `RulingsSwitchTest`, `SemanticsFingerprintTest`; `src/test/java/dev/regpack/engine/pack/EngineInfoTest.java`; resources `src/test/resources/conformance/vectors.tsv`, `cost-fixtures.tsv`.

```java
public final class EngineInfo {   // dev.regpack.engine.pack
  public static final int SEMANTICS_MAJOR = 1, LIBRARY_VERSION = Library.VERSION;
  public static final EngineBuild BUILD = new EngineBuild(SEMANTICS_MAJOR, 0, 0, LIBRARY_VERSION);
  public static final String EXECUTOR_ID = MeteredExecutor.ID;
}
```
**Conformance data** (the vector file format, the four classes with their rows, `rulings.tsv` and `cost-fixtures.tsv`): companion section 5.5.
**Tests.** `VectorFileTest`: `fileHasUniqueIdsKnownClassesAndPinnedCounts` (46; 12, 19, 5, 10); `mustAndShouldRowsUseNoErrorKinds`; `decideRowsPointAtRulings`; `everyMustRowCitesItsBasis`. `ConformanceSuiteTest`: `everyVectorPassesOnTheProductionLayer` (prints counts per class); `theHarnessHasTeeth` (a layer that always answers `false` fails every row not expecting `F`; an unsupported layer fails all); `errorKindsAreComparedNotMessages`; `oneOfAcceptsEitherOutcome`; `expansionOfGenAndChain`. `RulingsFileTest`: `loadsFiveProvisionalRows`; `provisionalRowsHaveNoRulingMetadataAndRuledRowsHaveBoth`; `unknownSwitchOrValueIsRefused` (covers an S07 value other than `BY_NAME`); `loadedRulingsEqualProvisional`. `RulingsSwitchTest#eachOfTheFourSwitchesHasAnAlternativeThatTheEvaluatorImplements`. `EngineInfoTest#buildIsParseableAndTracksTheLibraryVersion`. `SemanticsFingerprintTest#fingerprintMatchesThePinnedValue`: SHA-256 over `rulings.tsv`, `vectors.tsv` and `cost-fixtures.tsv` (LF-normalised, each preceded by its file name and a newline) equals `PINNED`, and `EngineInfo.SEMANTICS_MAJOR` and `Library.VERSION` equal the pair stored beside it (1 and 1); the failure message says: "a ruling, a vector or a cost fixture changed: bump EngineInfo.SEMANTICS_MAJOR (decisions can change) or Library.VERSION (a library function changed), then update these pinned values in the same commit".
**Run.** Red `T regpack-engine 'VectorFileTest,ConformanceSuiteTest,RulingsFileTest,RulingsSwitchTest,EngineInfoTest,SemanticsFingerprintTest'` (missing `VectorFile`, `rulings.tsv`, `EngineInfo`); compute the pinned fingerprint at the first green run and record it under Pins created. **OWNER INPUT (a departure, see departures table row 3):** show the owner the five `rulings.tsv` rows and the "For the owner at the freeze" table of `SP-R.md`; a ruling arrives as a one-line change plus a bump, in its own commit. Commit `COMMIT "test(engine): add the conformance suite, the one rulings file, engine info and the semantics fingerprint"`.

### Task 15 (T15): `attributes{}` on the decision, canonical bindings, the compiled pack and the budget

**Files:** Modify `regpack-model/src/main/java/dev/regpack/model/decision/DecisionBody.java`, `DecisionCodec.java`, test `DecisionTypesTest.java`. Create in `regpack-engine/src/main/java/dev/regpack/engine/pack/`: `CanonicalBindings`, `CompiledPack`, `PackBudget`; tests `CanonicalBindingsTest`, `CompiledPackTest`, `PackBudgetTest`; test helper `src/test/java/dev/regpack/engine/pack/SamplePacks.java` (reads `packs/sample.s0` under `regpack.repo.root` into a map of file to text; `sealed(Map<String,String> overrides)` replaces file texts, recomputes `CHECKSUMS` with `Checksums.render` and returns a `PackSource`; every later engine test that needs a variant pack uses it).

```java
// regpack-model (CHANGE, BRK-10): one trailing component; the 14-argument constructor stays
public record DecisionBody(/* the 14 components */, Map<String, String> attributes) {
  public DecisionBody(/* the 14 old components */) { this(/* ... */, Map.of()); }
}   // DecisionCodec.bodyToMap puts "attributes" (a sorted copy) only when it is not empty
// regpack-engine
public final class CanonicalBindings implements Bindings {                 // over CanonicalExecution
  public static final Map<String, ValueType> VARIABLES;                    // the 13 paths of the sample pack's model/canonical-input.yaml
  public CanonicalBindings(CanonicalExecution execution);
  public Optional<Value> get(String path);                                 // unknown path: empty
  public static List<PackProblem> checkSupported(CanonicalInputSpec spec); // INPUT_PATH_UNSUPPORTED, INPUT_TYPE_MISMATCH
}
public final class CompiledPack {
  public record Row(TableRow source, InHouseExpression.Compiled when, boolean catchAll) {}
  public record Table(String id, List<Row> rows) {}                        // rows ascending by priority
  public record CompileResult(Optional<CompiledPack> pack, List<PackProblem> problems) {}
  public static CompileResult compile(LoadedPack pack, String reportId, Rulings rulings);
  public String reportId(); public Regime regime(); public String asAtField();
  public List<Table> tables(); public Table lastTable(); public PackBudget budget();
}
public record PackBudget(long staticWorstCaseUnits, long perRecordUnits, long largestRuleNodes, long recommendedRuleCeiling) {
  public static PackBudget of(List<CompiledPack.Table> tables, PackLimits limits);
}
```
`CanonicalBindings`: `event.kind`, `client.kind`, `instrument.assetClass` are `EnumValue("LifecycleKind" | "PartyKind" | "AssetClass", name)`; `event.tradedAt` a `TimestampValue`; `trade.quantity`, `trade.price` `DecimalValue` with the input's scale; `instrument.tradedOnVenue` a `BoolValue`; `client.lei` a `StringValue` or empty. `CompiledPack.compile` builds the `InHouseExpression` with `Limits(16 x limits.rule_nodes, limits.list_items, limits.string_code_points)` (the parse-work cap), compiles every `when` against the declared variables and returns `REPORT_UNKNOWN`, `WHEN_DOES_NOT_COMPILE` (names the row; only a rule beyond 16 x the ceiling), `INPUT_UNDECLARED` and `INPUT_UNUSED` (the table header `inputs` must equal the set of paths its rows read) and `RULE_OVER_CEILING <rowId>` (`nodeCount()` above `limits.rule_nodes`). `PackBudget` uses `Math.addExact` and `multiplyExact` (a comment states that with the `PackFormat` caps no sum overflows a long): `staticWorstCaseUnits` = sum over rows of 1 + the row's static worst case; `perRecordUnits` = `limits.record_budget` if declared, else 2 x that sum (SP-R formula, MODELLED); `recommendedRuleCeiling` = max(64, 4 x largest), printed for information.
**Tests.** Step 1a: on the unchanged model, a throwaway test prints `Json.canonical(DecisionCodec.bodyToMap(body, true))` and `body.replayDigest().hex()` for the fixed body of `DecisionTypesTest`; copy the two literals and delete the test. `DecisionTypesTest` additions: `oldConstructorGivesEmptyAttributes`; `emptyAttributesLeaveEveryExistingDigestUnchanged` (the two literals); `attributesAreInTheReplayDigest` (adding `{action=NEWT}` changes it; `evaluatedAt` still does not); `attributesAppearSortedInTheCanonicalJson`; `attributesAreCopiedAndImmutable`. `CanonicalBindingsTest`: `bindsEveryCataloguePathWithItsType`; `decimalsKeepTheScaleOfTheInput`; `anAbsentClientLeiIsEmpty`; `anUnknownPathIsEmpty`; `enumsCarryTheirTypeName`; `checkSupportedFlagsAnUnknownPathAndAWrongType`; `catalogueEqualsTheSamplePackInput`. `CompiledPackTest`: `compilesTheSamplePack` (4 rows, the last the catch-all, `regime()` is `sample.s0`); `anUnknownReportIsAProblem`; `aWhenThatDoesNotCompileNamesTheRow`; `inputsHeaderMustEqualTheReadPaths` (both codes); `ruleOverCeilingWhenLimitsAreLow` (`rule_nodes: 3` gives `RULE_OVER_CEILING`; a rule far beyond 16 x `rule_nodes` gives `WHEN_DOES_NOT_COMPILE`, not `RULE_OVER_CEILING`); `aCompileErrorMessageIsBoundedAndEscaped` (a 5,000-character `when` with an unknown identifier); `compilationIsDeterministic`. `PackBudgetTest`: `sampleBudgetIsFortyUnits` (static worst case 20, per record 40, largest rule 5 nodes, recommended ceiling 64); `aDeclaredRecordBudgetWins`; `theBudgetGrowsWithTheNumberOfRows`.
**Run.** Red `T regpack-model 'DecisionTypesTest'` then `T regpack-engine 'CanonicalBindingsTest,CompiledPackTest,PackBudgetTest'` (missing `attributes()` and types); green: both, then `T regpack-model '*Test'`, `T regpack-testkit '*Test'`, `T regpack-inmemory '*Test'`. Commit `COMMIT "feat: add decision attributes, canonical bindings, the compiled pack and its budget"`.

### Task 16 (T16): The table interpreter and `PackRegime`: pack to decision record

**Files:** Create in `regpack-engine/.../pack/`: `PackProgram`, `PackRegime`; tests `PackProgramTest`, `PackRegimeTest`, `PackScopeEngineTest`; helper `PackBuilder` (test sources: a pack variant from the sample with replaced scope-table rows, reasons and limits given as text, via `SamplePacks.sealed`). Modify `ExecutorBudgetTestsAreWallClockFreeTest` (also copy and scan `PackScopeEngineTest*`, `PackProgramTest*`, `CostTest*`, `AmplificationTest*` and, from T17, `RuleCircuitTest*`, and assert each list is non-empty).

```java
public final class PackProgram implements RuleProgram<Bindings, PackProgram.Decision> {
  public record Decision(TableRow row, String tableId) {}
  public record RowTrace(String ruleId, String when, boolean matched, long units, Optional<ErrorKind> error) {}
  public PackProgram(CompiledPack pack, boolean fullTrace);                // one instance per evaluation; not thread-safe
  public String id();  public Decision run(Bindings input, CostMeter meter, Trace trace);
  public Optional<ErrorKind> lastErrorKind();  public List<RowTrace> rowTraces();
}
public final class PackRegime implements RegimeProvider {
  public static PackRegime create(LoadedPack pack, String reportId, Executor executor, Rulings rulings);   // IAE listing the problems
  public Regime regime();  public CostBudget recordBudget();  public DecisionContext decisionContext();
  public CompiledPack compiled();
  public Descriptor describe();  public Result evaluate(CanonicalEvent event, EvaluationContext context);
}
```
**Interpretation (OI-3, provisional), `create` and `evaluate`:** companion section 5.6.
**Tests.** `PackRegimeTest`: `describeNamesTheReportRegimeAndEngineMin`; `decisionContextPinsTheDigestBuildAndExecutor`; `refusesAPackWhoseEngineMinIsNotSatisfied` (another major, another library version, a higher minor); `refusesAPackWhoseCanonicalInputIsNotSupported`; `refusesAPackThatDoesNotCompile`; `createsOneRegimePerReportDefinition` (BRK-14, a builder pack with two reports). `PackProgramTest`: `validityWindowSelectsRowsByAsAt` (a row with `valid.legal.from` 2026-10-10 is skipped at 2026-10-09T23:59:59.999999Z and decides at 2026-10-10T00:00:00Z; a row with `to` 2026-10-10 still decides at 2026-10-09T23:59:59.999999Z and is skipped at 2026-10-10T00:00:00Z, so a default-zone bug under `Pacific/Kiritimati` shows; a skipped row leaves no path step and costs nothing); `aMissingAsAtFieldIsExecError`; `tableChainRule` (two tables: a first-table hit decides; a first-table catch-all falls through; both catch-alls give UNDETERMINED from the last catch-all's row); `anErrorNamesTheRowAndRemembersItsKind`; `fullTraceListsEveryEvaluatedRow`. `PackScopeEngineTest` (the I5 suite; budget 100 unless stated): `novationIsExcludedAndStopsAtTheFirstRow` (path `[(S-N03-novation, true, 8)]`, legs empty); `naturalPersonIsUndeterminedNeverDoNotReport` (path costs 8, 4); `equityOnVenueIsReportedWithMarketLeg` (costs 8, 4, 6; legs `[MARKET]`; attributes empty); `unmatchedInstrumentFallsToTheCatchAll` (DEBT: costs 8, 4, 5, 2); `exclusionPrecedesThePartyRule`; `budgetExhaustionYieldsUndeterminedExecBudget` (the REPORT case with budget 10: flags exactly `[rule_id=S-D14-natural-person, cost_units=10, limit=10]`, path `[(S-N03-novation, false, 8)]`); `everyFailureModeIsUndeterminedWithReasonAndCitation` (builder packs whose one rule is `client.lei == 'X'` with no lei bound: `ABSENT`; `1 / 0 == 1`: `DIV_ZERO`; `1 + 1`: `TYPE`; each gives UNDETERMINED, `EXEC_ERROR`, node `EXEC`, empty rule hits, the catch-all's citations, flag `error_kind=<KIND>`, never DO_NOT_REPORT); `setIsStoredInAttributes` (builder row `set: {action: NEWT}`); `citationsCarryTheQuoteHashOfTheRegistry`; `sameInputGivesTheSameReplayDigestOneThousandTimes`; `decisionIsStampedWithContextIdAndIngestKeyDigest`; `execFlagsNeverEchoRecordValues` (bind `instrument.isin` to `SECRET-ISIN-7` and a rule `instrument.isin + 1`: no flag, path step, citation or `PackExplainer.explainEvent` line contains `SECRET-ISIN-7` (the `explainEvent` assertion is added in T18, which creates the class); the flags are exactly the keys listed in companion section 5.6).
**Run.** Red `T regpack-engine 'PackProgramTest,PackRegimeTest,PackScopeEngineTest'` (`cannot find symbol ... PackProgram`). Implement `PackProgram` first (run `PackProgramTest`), then `PackRegime`. Green: the three, `ExecutorBudgetTestsAreWallClockFreeTest`, `NoWallClockRandomLocaleTest`, then `MVN -B -ntp verify` (the model change touches every module) and `bash scripts/check-gates.sh`. Commit `COMMIT "feat(engine): add the table interpreter and the pack-driven regime provider"`.

### Task 17 (T17): The poison circuit

**Files:** Create `RuleCircuit.java` (`regpack-engine/.../pack/`), test `RuleCircuitTest`; modify `PackProgram`, `PackRegime`, `PackScopeEngineTest`.

```java
public final class RuleCircuit {                                     // spec 7.3; defaults N = 4 in M = 50 (SP-R, MODELLED)
  public RuleCircuit(int n, int m);  public static RuleCircuit defaults();   // 1 <= n <= m
  public boolean isOpen(String ruleId);
  public void record(String ruleId, boolean failed);                 // one evaluation of the rule; tumbling windows of m evaluations
  public void close(String ruleId);                                  // explicit only (OI-4)
  public int openEvents();  public Set<String> openRules();
}
// in PackProgram: public static final class CircuitOpenException extends RuntimeException { public String ruleId(); }
```
Overloads: `PackRegime.create(..., Optional<RuleCircuit>)` and `PackProgram(CompiledPack, boolean fullTrace, Optional<RuleCircuit>)` (the existing signatures mean "no circuit"). A rule whose window holds `n` failures opens; while open, any record that reaches the rule gets UNDETERMINED with reason `EXEC_CIRCUIT_OPEN`, node `EXEC`, flags `rule_id=<id>` and `circuit=open`, the last catch-all's citations, and is **excluded from the nightly determinism sample and counted** (spec 6.5 step 8, 7.3). `PackProgram` calls `meter.enter(row.id())` first, then `if (circuit.isOpen(row.id())) throw new RuleEvaluationException(row.id(), new CircuitOpenException(row.id()))` (no charge, no path step), so the executor names the open rule, not the previous row; it records every row evaluation (`failed` on `ExprException` or a budget overrun in that row). `PackRegime` maps a `Failed` whose `errorClass()` equals `"CircuitOpenException"` to the `EXEC_CIRCUIT_OPEN` outcome above; any other `Failed` stays `EXEC_ERROR`. A budget overrun is blamed on the row running when the budget ran out (with a budget of 2 x the static worst case this only happens for inputs beyond the declared list and string limits). Outcomes under concurrent evaluation depend on interleaving and are outside I3. The data-quality item and the superseding re-evaluation are P3 work (companion section 4).
**Tests.** `RuleCircuitTest`: `opensAfterNFailuresInOneWindow` (4 in 50); `doesNotOpenWhenFailuresAreSpreadOverWindows` (three in each of two windows); `windowsTumblePerRule`; `rulesAreIndependent`; `closingResetsTheWindow`; `openEventsAreCounted`; `theSameSequenceGivesTheSameResult`; `concurrentRecordsNeverLoseACount` (8 threads each call `record("r", true)` 1,000 times on `new RuleCircuit(8000, 8000)`: afterwards `isOpen("r")` is true and `openEvents()` is 1; a second run with 7,999 calls in total leaves the rule closed); `rejectsNonPositiveOrInvertedParameters`. `PackScopeEngineTest#seededOverBudgetRuleOpensItsCircuitDeterministically` (spec 14.1): a builder pack with `limits: {record_budget: 6}` and a rule `instrument.isin in ['a','b','c','d','e','f','g','h']` exhausts its budget for every record; with `RuleCircuit(4, 50)` the first four records give `EXEC_BUDGET` and the fifth `EXEC_CIRCUIT_OPEN`; a second run with a fresh regime and circuit gives the identical sequence of reasons and flags; `PackScopeEngineTest#aClosedCircuitEvaluatesTheRuleAgain`; `#anOpenCircuitIsReportedAsExecCircuitOpenNotExecError`; `#theFlagNamesTheOpenRuleNotThePreviousRow` (open the circuit on the SECOND row of a two-row builder pack).
**Run.** Red `T regpack-engine 'RuleCircuitTest,PackScopeEngineTest'`; green: same, then `T regpack-engine 'PackProgramTest,PackRegimeTest'`. Commit `COMMIT "feat(engine): add the per-rule poison circuit with EXEC_CIRCUIT_OPEN"`.

### Task 18 (T18): `explain`: attributes in Tier 1, the Tier-2 rule and `PackExplainer`

**Files:** Modify `regpack-engine/.../explain/Tier1Explainer.java`, `Tier1ExplainerTest.java`, `.../pack/PackScopeEngineTest.java`. Create `.../packci/Tier2Plan.java`, `PackExplainer.java`; tests `Tier2PlanTest`, `PackExplainerTest`.

```java
public enum Tier2Plan { CURRENT_ENGINE, ARCHIVED_BUILD_REQUIRED;
  public static Tier2Plan decide(String storedEngineBuild, EngineBuild current);   // CURRENT_ENGINE iff the stored text parses and is replay-compatible; fail closed otherwise
}
public final class PackExplainer {
  public static String explainRule(LoadedPack pack, String ruleId);                // static view of one row
  public static String explainEvent(PackRegime regime, CanonicalExecution event, IngestKey key, Instant evaluatedAt);   // Tier 1 text plus a full trace
}
```
`Tier1Explainer` prints `Attributes: name=value, ...` (names sorted) after the `Legs:` line **only when attributes exist**, so the pinned text of `rendersPinnedText` is unchanged. `explainRule` prints the row (id, table, priority, `when`, outcome, node, `valid`), its reason entry (category, owner), each citation resolved (source version, provision status and role, quote and hash if present), the rationale text and the golden cases of the pack that hit it; an unknown id is an `IllegalArgumentException`. `explainEvent` runs a `PackProgram` with `fullTrace` and prints the Tier-1 text of the decision (`Decision 0 (seq 1) ... (not stored)`), then `Trace (full):` with `  <n>. <id>: MATCH|no match|ERROR <KIND> (<units> units): <when>` per evaluated row, then `Tier 2: re-evaluated on the current engine <build> (stored build <build>: replay-compatible)`.
**Tests.** `Tier1ExplainerTest#rendersAttributesOnlyWhenPresent` (the pinned test stays green). `Tier2PlanTest`: `compatibleBuildsReplayOnTheCurrentEngine`; `aDifferentMajorNeedsTheArchivedBuild`; `aDifferentLibraryVersionNeedsTheArchivedBuild`; `anUnparsableStoredBuildFailsClosed` (`p0`). `PackExplainerTest`: `explainsAnEventWithAFullTrace` (the NATURAL_PERSON case: two lines with the units of T16); `traceShowsTheErrorKindOfAFailingRow`; `explainsARuleWithReasonCitationsAndRationale`; `anUnknownRuleIsAnError`; `explainIsDeterministic`; the `explainEvent` assertion of `PackScopeEngineTest#execFlagsNeverEchoRecordValues` (T16).
**Run.** Red `T regpack-engine 'Tier1ExplainerTest,Tier2PlanTest,PackExplainerTest,PackScopeEngineTest'`; green: same. Commit `COMMIT "feat(engine): add explain for packs, the Tier-2 replay decision and attributes in Tier 1"`.

### Task 19 (T19): The golden harness in the pack module, with enforced provenance

**Files:** `git mv` `regpack-testkit/src/main/java/dev/regpack/testkit/golden/{GoldenCase,GoldenCaseLoader,ReviewerRegistry,GoldenAudit}.java` to `regpack-pack/src/main/java/dev/regpack/pack/golden/` (package `dev.regpack.pack.golden`) and `.../GoldenAuditTest.java` to `regpack-pack/src/test/java/dev/regpack/pack/golden/`. Modify `regpack-engine/src/test/java/dev/regpack/engine/skeleton/RepositoryGoldenAuditTest.java` (imports). Create `PackGoldenLoader.java`, test `PackGoldenStrictnessTest`.

The P0 loader stays lenient (no `provenance` means consistency-only) because the skeleton goldens rely on it. **The pack path is strict (deliberately stricter than O1, D-P6):**
```java
public final class PackGoldenLoader {
  public record Loaded(List<GoldenCase> cases, Map<String, String> authorByRuleId, List<PackProblem> problems) {}
  public static Loaded load(PackSource source);        // tests/golden/*.json and tests/authorship.yaml
}
```
**Codes:** the `provenance` object is mandatory (`GOLDEN_NO_PROVENANCE`); `CONSISTENCY_ONLY` must not carry `source`, `reviewer` or `reviewedOn` (`GOLDEN_CONSISTENCY_WITH_FIELDS`); any other kind needs all three (`GOLDEN_NO_SOURCE`, `GOLDEN_NO_REVIEWER`, `GOLDEN_NO_REVIEW_DATE`); the file is `<id>.json` (`GOLDEN_ID_FILE_MISMATCH`); ids unique (`GOLDEN_ID_DUPLICATE`); `node` declared in `reasons.yaml` (`GOLDEN_NODE_UNDECLARED`, checked by the tester); malformed JSON or an unknown key `GOLDEN_JSON_INVALID`; every decoded JSON string, keys and values, goes through `PackText` (`TEXT_BAD_CHARACTER`); `tests/authorship.yaml` must name every row id and no other (`AUTHORSHIP_MISSING_FILE`, `AUTHOR_MISSING`, `AUTHOR_UNKNOWN_RULE`). A claimed real kind whose reviewer is not in `governance/golden-reviewers.txt`, or whose author equals a rule author or the reviewer, is reported by `GoldenAudit` and becomes a problem in `PackTester` (T20): the harness **enforces** the provenance fields and never downgrades silently.
**Tests.** The moved `GoldenAuditTest` (15 tests, unchanged; `regpack-testkit` goes from 86 to 71 tests and `regpack-pack` gains them). `PackGoldenStrictnessTest`: `aCaseWithoutProvenanceIsRefusedEvenThoughTheLenientLoaderAcceptsIt`; `escapedControlAndBidiCharactersInGoldenStringsAreRefused` (a case with a description decoded from the JSON escape of U+001B and one decoded from the JSON escape of U+202E); `consistencyOnlyMustNotCarryReviewFields`; `aRealKindNeedsSourceReviewerAndDate`; `fileNameMustEqualTheCaseId`; `duplicateIdsAreRefused`; `authorshipMustCoverEveryRowAndNoOther`; `auditCountsOnlyFullyReviewedCasesWithRegisteredReviewers`; `separationOfAuthorsIsReported` (`SEPARATION:<rule>`, `REVIEWER_IS_AUTHOR`); `theSampleGoldensLoadAndAreAllConsistencyOnly`.
**Run.** After the `git mv` and package renames: red `T regpack-pack 'GoldenAuditTest,PackGoldenStrictnessTest'` (`cannot find symbol ... PackGoldenLoader`) and `T regpack-engine 'RepositoryGoldenAuditTest'` (fails to compile until its imports change). Green: both, `T regpack-pack 'NoWallClockRandomLocaleTest'` (the moved classes are now scanned: fix any flagged call, for example `toLowerCase()` without `Locale.ROOT`, without changing behaviour), `T regpack-testkit '*Test'`, `grep -r "testkit.golden" --include=*.java .` finds nothing, then `MVN -B -ntp verify` and `bash scripts/check-gates.sh` (the traceability gate reads the golden audit file that `RepositoryGoldenAuditTest` writes). Commit `COMMIT "refactor: move the golden harness to regpack-pack and enforce provenance fields on pack goldens"`.

### Task 20 (T20): Coverage gates and `PackTester`

**Files:** Create `regpack-engine/.../packci/CoverageGates.java`, `PackTester.java`, `PackTestReport.java`; tests `CoverageGatesTest`, `PackTesterTest`.

```java
public record CaseOutcome(String caseId, boolean passed, List<String> actualRuleIds, String actualReason, boolean counts) {}
public record Coverage(int rowsTotal, int rowsHit, int reasonsTotal, int reasonsReached, int provisionsCited,
                       int provisionsExercised, int countingRowsHit) {}
public record CoverageResult(Coverage coverage, List<PackProblem> problems) {}
public final class CoverageGates { public static CoverageResult evaluate(LoadedPack pack, List<CaseOutcome> outcomes); }
public record PackTestReport(List<String> lines, List<PackProblem> problems) { public boolean passed(); public String render(); }
public final class PackTester { public static PackTestReport run(PackSource source, ReviewerRegistry reviewers); }
```
**Coverage (spec 6.5 step 2).** A case exercises a row only if it **passed** (outcome, reason and rule hits equal the expectation) and its *actual* hits contain the row; a reason is reached when a passed case produced it and some row uses it; a provision is exercised when a passed case hit a row that cites it. Codes: `COVERAGE_NO_CASES`, `COVERAGE_ROW_NOT_HIT <id>`, `COVERAGE_REASON_NOT_REACHED <code>`, `COVERAGE_PROVISION_NOT_EXERCISED <source/path>`. The gate fails on coverage by all cases; coverage by **counting** cases only (O3) is reported and never gates (0 while G1 is open). **`PackTester.run`:** load (`PackLoader`); on problems print them and stop; `CanonicalBindings.checkSupported` and `CompiledPack.compile` (exactly one report: `MULTI_REPORT_NOT_SUPPORTED_BY_PACK_TEST`, since golden cases are per pack in P2a); `PackRegime.create(pack, report, new MeteredExecutor(), Rulings.load())`; golden load (T19) and each case through `PackRegime.evaluate` with `evaluatedAt` the constant 2026-01-01T00:00:00Z, ingest key `golden|<id>|1` and the regime's record budget; coverage; audit with `GoldenAudit` (each problem of a non-consistency-only case becomes `GOLDEN_AUDIT <token>`); budget line. Output for the sample pack:
```
pack test: sample.s0 0.1.0 digest <64 hex>
files: 11 (CHECKSUMS verified)
signature: UNSIGNED (an ATTESTATION is not verified before P9b)
static checks: 0 problems
engine: 1.0.0+lib1 satisfies engine_min 1.0.0+lib1
golden: 4 of 4 pass
coverage: rows 4/4, reasons 4/4, provisions 4/4 (all cases); rows 0/4 (counting cases)
audit: 0 of 4 counting, 4 consistency-only; reviewers registered: 0 (gate G1: no named Compliance owner)
budget: record budget 40 units (static worst case 20); largest rule 5 nodes; rule ceiling 512; SP-R recommended 64
pack test: PASS
```
A problem prints as `problem <CODE> <path>[:<line>] <message>` before the last line, which then reads `pack test: FAIL (<n> problems)`; sections the engine does not interpret add `not interpreted by this engine build: <path>` lines.
**Tests.** `CoverageGatesTest`: `emptyCorpusFails`; `aFailingCaseExercisesNothing`; `aRowNoCaseHitsFails`; `aReasonNoCaseReachesFails`; `aProvisionNoCaseExercisesFails`; `consistencyOnlyCasesExerciseButNeverCount`; `fullCoverageHasNoProblems`. `PackTesterTest`: `theSamplePackPassesWithTheDocumentedLines` (exact text, digest equal to the pinned one); `aFailingGoldenCaseFailsThePack`; `aStaticProblemStopsBeforeEvaluation` (a duplicate priority: only load problems printed); `multipleReportsAreRefused`; `unsignedIsAlwaysPrinted`; `notInterpretedSectionsAreListed` (a `lifecycle.yaml` added to the sample); `aClaimedReviewWithAnUnregisteredReviewerFails` (kind `ESMA_QA`, reviewer not in the registry); `aClaimedReviewWithARegisteredReviewerOtherThanTheAuthorCounts` (one registered reviewer: `audit: 1 of 4 counting`, still passing); `theOutputIsDeterministic`.
**Run.** Red `T regpack-engine 'CoverageGatesTest,PackTesterTest'`; green: same. Commit `COMMIT "feat(engine): add coverage gates and the pack tester"`.

### Task 21 (T21): CSV export and import of decision tables, and the canonical table YAML

**Files:** Create `regpack-pack/src/main/java/dev/regpack/pack/csv/TableCsv.java`, `TableYamlWriter.java`; tests `TableCsvTest`, `TableYamlWriterTest`, `TableCsvRoundTripPropertyTest`; helper `RandomTables` (test sources: seeded generator of valid tables).

```java
public final class TableCsv {
  public static String export(DecisionTable table);
  public static Optional<DecisionTable> importRows(String csv, DecisionTable header, List<PackProblem> problems);
}
public final class TableYamlWriter { public static String write(DecisionTable table); }
```
**CSV format and table YAML (provisional; the AT-8 round trip):** companion section 5.7.
**Tests.** `TableCsvTest`: `exportHasTheDocumentedHeaderAndAscendingRows`; `exportQuotesOnlyWhenNeeded`; `exportIsLfUtf8WithoutBom`; `importAcceptsCrlfBomAndAMissingFinalNewline`; `importAcceptsReorderedColumns`; `importRejectsUnknownMissingAndDuplicateColumns`; `importRejectsAnUnterminatedQuote`; `importRejectsARowWithTheWrongNumberOfCells`; `importRunsTheSameTypingAndTableChecks` (a duplicate priority and an unknown outcome give the same codes as from YAML); `importRefusesLfCrTabAndInvisibleCharactersInAnyCell` (a quoted cell with an embedded LF, a CR, a TAB, U+202E, U+200B, NUL: `CELL_BAD_CHARACTER`, no table); `anEditedCellChangesOnlyThatRow` (change one `when` in the CSV text, import, write: exactly one row's lines differ). `TableYamlWriterTest`: `writerOutputParsesToTheSameTable`; `writerIsIdempotent` (byte for byte); `quotingRules` (a `when` with a double quote, a backslash, a leading and a trailing space survives); `commentsAreNotPreserved`. `TableCsvRoundTripPropertyTest#randomTablesSurviveExportAndImportUnchanged` (seed 7; 500 tables of 1 to 12 rows with strings drawn from `, " space # : ' [ ] { }`, accented letters and CJK, leading and trailing spaces (no LF, CR, TAB); for each: `importRows(export(t))` equals `t`, `write` of both are equal, and the IR table maps are equal).
**Run.** Red `T regpack-pack 'TableCsvTest,TableYamlWriterTest,TableCsvRoundTripPropertyTest'`; green: same. **Normalise the sample:** rewrite `packs/sample.s0/scope/sample-scope.yaml` with `TableYamlWriter`, run `Seal` on `packs/sample.s0`, and run `T regpack-pack 'SamplePackTest'`: `digestIsPinned` must stay green with the **same literal**, because the digest depends on parsed content, not layout (a changed digest is a bug in `PackIr`, not a reason to re-pin). Commit `COMMIT "feat(pack): add the CSV round trip and the canonical table YAML for decision tables"`.

### Task 22 (T22): Production throughput check (information, not a verdict)

SP-R measured a disposable interpreter; this measures the production one on the same shape with the existing `regpack-bench` harness. It is **not a gate**: 10,000 records/s per thread is printed beside the result and nothing asserts on the rate (SP-R's caveats apply).

**Needs T9, T15, T16** (it runs after T16, see the handoff). **Files:** (`regpack-bench` is a test dependency of the engine since T2.) Create in `regpack-engine/src/test/java/dev/regpack/engine/pack/`: `GeneratedPackSource` (turns a `GeneratedPack` into a sealed pack; it writes each table's YAML itself from a text template, so it needs no `TableYamlWriter`; 200 reasons, one synthetic source, one rationale, the 19 variables of `TradeRecord.fieldPaths()` typed as in `spikes/spike-r/.../TradeBindings.VARIABLES`; it sets each table's `inputs` to the sorted union of the paths of its compiled rows, declares the enum names `LifecycleKind`, `AssetClass`, `PartyKind`, marks `client.lei` and `trader.id` optional, and gives each non-catch-all row `valid: {legal: {from: 2000-01-01}}`), `TradeRecordBindings` (a `Bindings` over `TradeRecord`, cached per path ordinal, one instance per thread), `PackThroughputCheck` (not named `*Test`, so surefire skips it; run with `-Dtest=PackThroughputCheck`; it keeps only the timed run) and `PackThroughputGuardTest` (the four guards below; runs in `verify`, 1,000 records, no timing).

`RulePackGen.generate(1, 200, S2_FULL_SCAN_WORST_CASE)` (10 tables of 20 rows; every row is evaluated for every record) is built into a pack; the load plus compile time is taken (`System.nanoTime`, test code); then `MeteredExecutor` + `PackProgram` run over a ring of 262,144 `TradeRecordGen(1)` records with the budget `CompiledPack.budget().perRecordUnits()`, trace off, through `ForkRunner.runInProcess` (one fork, 2 s windows, 3 stable windows within 3%, 10 measured windows, no canary) for 1 thread and for 4 threads (a small parallel workload in the test sources; one program and one bindings object per thread; each thread starts at another ring position). It writes `<regpack.build.dir>/pack-throughput.txt`, six lines: the header `pack-throughput (information, not a verdict; one in-process fork, one VM, interpreter only, worst-case shape S2)`; rules, tables, ring and budget per record; load plus compile ms; the 1-thread and 4-thread-busy records/s per thread; `SP-R reference, spike interpreter: 55,860 (1 thread), 27,796 (4 threads busy, per thread); placeholder gate 10,000 (spec: per core; shown per thread, the conservative SP-R reading): ABOVE|BELOW`; the EXEC_* count of the clean corpus and the checksum.
**Tests (guards against a vacuous benchmark, in `PackThroughputGuardTest`).** `everyRecordEvaluatesAllTwoHundredRows` (1,000 records with `fullTrace`: 200 `RowTrace` entries each, every decision UNDETERMINED from the last catch-all); `noExecOutcomesOnTheCleanCorpus` (0 `Failed` and 0 `BudgetExhausted` over the first 1,000 ring records; a smoke datum on a synthetic corpus, 0 by construction; not the SP-R shadow-run measurement); `checksumChangesWithThePack` (seed 2 gives another checksum); `theGeneratedPackPassesPackChecks` (loads with no problem).
**Run.** Red `T regpack-engine 'PackThroughputGuardTest,PackThroughputCheck'` (`cannot find symbol ... GeneratedPackSource`); green: the same command; read `regpack-engine/target/pack-throughput.txt` and copy it into `docs/exit/P2a.md` in T27. If the 4-thread figure is below 10,000 per thread, do not tune: record it, tell the owner, and note SP-R's open items (record assembly, `input_digest`, path encoding and real pack sizes are not in the figure). Commit `COMMIT "test(engine): add the production throughput information check on a generated 200-rule pack"`.

### Task 23 (T23): Equivalence with the skeleton, a second regime, and no regime logic in the engine

**Files:** Create in `regpack-engine/src/test/java/dev/regpack/engine/pack/`: `PackRegimeMatchesSkeletonTest`, `SecondRegimeFixtureTest`, `NoRegimeLogicInEngineTest`; resources `src/test/resources/packs/second-regime/` (sealed with `Seal`, loaded with `PackSource.fromDirectory` from the test class path).

- **`PackRegimeMatchesSkeletonTest`** proves the replacement changes nothing the decision means: over the grid of 6 event kinds x 4 asset classes x 2 client kinds x 2 `tradedOnVenue` values (96 executions) the sample pack through `PackRegime` and `WalkingSkeletonRegime.standard()` give equal outcome, node, rule hits, legs, the same (rule id, matched) path steps, reason codes equal under an explicit four-pair map inside the test (`SAMPLE.N03.EXCLUDED` to `MIFIR.N03.EXCLUDED`; `SAMPLE.N13.NATURAL_PERSON` to `MIFIR.N13.NATURAL_PERSON`; `SAMPLE.N08.IN_SCOPE` to `MIFIR.N08.IN_SCOPE_26_2_A`; `SAMPLE.DEFAULT.NO_RULE_HIT` to `DEFAULT.NO_RULE_HIT`, since the skeleton catch-all has no prefix; a test asserts that the map keys equal exactly the four codes of `reasons.yaml`), and citations equal after the path mapping `sample-law`/`sample/1` to `mifir-placeholder`/`art/2/par/5/pt/PLACEHOLDER`, `sample-law`/`sample/2` to `mifir-placeholder`/`art/26/par/2/pt/a`, `regpack-spec` unchanged, and ignoring `quoteSha256` (the skeleton's is empty); further assertions: the two `sample-law` citations carry a present hash equal to the registry's `quote_sha256` and the two `regpack-spec` citations have none. The documented differences, asserted to be exactly these four: cost units per step (8, 4, 6, 2 against 3, 2, 3, 1), the regime id, the decision context and the presence of `quoteSha256`; the code and path mappings are mappings, not differences.
- **`SecondRegimeFixtureTest`** evidences generality (R7) and I4: a pack `emir.eu` (prefix `EMIR`, node `ACT`, `state.status` with `source: STATE`, `event.kind` STRING) written by hand from the SP-G example `docs/spikes/sp-g/emir-pack/derive/action-type.yaml` (rows D-ACT-01 to D-ACT-08 and the catch-all as one scope table, `set: {action_type: NEWT}` and so on, citations to a synthetic `fixture-law`; no EMIR text; G1 labels apply) is loaded, compiled with `CompiledPack.compile` and run with `PackProgram` under `MeteredExecutor` over a map-based `Bindings` (the seven SP-G examples ACT-EX-01 to ACT-EX-07, status supplied by the test): `theEmirActionTypeTableRunsOnTheSameEngine`; `attributesCarryTheActionType` (NEWT, MODI, TERM, EROR, REVI, VALU read from the deciding row's `set`); `stateInputIsReadThroughBindings` (BRK-04, reserved); `reasonPrefixAndNodesComeFromThePack` (BRK-11); `theCatchAllIsUndeterminedWithAReason` (ACT-EX-07, I5). REPORT rows use the default leg `MARKET` as a stand-in for the EMIR `SIDE` leg (reserved: BRK-12; P3, P12); the test says so.
- **`NoRegimeLogicInEngineTest#engineAndPackSourcesCarryNoRegimeLiterals`** reads every `.java` under `regpack-engine/src/main/java` (except the `skeleton` package) and `regpack-pack/src/main/java` (via `regpack.repo.root`) and asserts that none matches (case-sensitive) `\bMIFIR\b|\bEMIR\b|RTS ?22|\bN(0[1-9]|1[0-9])\b`; a fixture string proves the scan can fail.

**Run.** Write the tests; red `T regpack-engine 'PackRegimeMatchesSkeletonTest,SecondRegimeFixtureTest,NoRegimeLogicInEngineTest'` (fixtures missing); create and seal the fixture pack (the `Seal` line of T10); green: same. A real difference found by the equivalence test means the interpreter or the sample is wrong, not the test: fix it and say so in the commit message. Commit `COMMIT "test(engine): prove the pack regime matches the skeleton and runs a second regime without engine changes"`.

### Task 24 (T24): The pack-driven pipeline: crash determinism and the second digest in CI

**Files:** Create `regpack-engine/src/test/java/dev/regpack/engine/pipeline/PackToyCrashIT.java`. Modify `.github/workflows/ci.yml` (the toy-digest upload `path` lists `regpack-engine/target/toy-digest.txt` and `regpack-engine/target/pack-toy-digest.txt`; the compare step compares both files between the two operating systems) and `regpack-app/src/test/java/dev/regpack/app/PackagingFilesTest.java` (assertions for the second file name in upload and compare).

A port of `ToyCrashIT` (design reading: `ToyCrashIT`, `ToyBatches`; **do not refactor `ToyCrashIT`**, its method names are cited by the traceability file) that builds `PackRegime` from `packs/sample.s0`, uses `regime.recordBudget()` and `regime.decisionContext()`, reads chains with `regime.regime()`, and runs the same fixture batches, 16 crash plans and 25 seeded plans. Tests: `everyCrashPointConvergesToTheUncrashedBaseline`; `sameFaultPlanGivesIdenticalTraceAndDigest` (also writes `<regpack.build.dir>/pack-toy-digest.txt`, one line); `acknowledgementFollowsIngestCommit`; `redeliveryAfterAckLossIsNoop`; `seededPlansAlsoConverge`; `theBaselineDigestDiffersFromTheSkeletonDigest` (compares with the literal `70382303f446476321d38d50817bdd8d541e0dcb61d8f33d92435c676bc116c9` as a constant `SKELETON_TOY_DIGEST`; another regime id and context give another digest, so the two toy tests cannot silently run the same thing).
**Run.** Red `IT regpack-engine 'PackToyCrashIT'` (compile error until written); green: same (failsafe `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`; the digest file is 64 hex characters); `IT regpack-engine 'ToyCrashIT'` is still green, then `cat regpack-engine/target/toy-digest.txt` (PowerShell `Get-Content regpack-engine\target\toy-digest.txt`) must print exactly `70382303f446476321d38d50817bdd8d541e0dcb61d8f33d92435c676bc116c9`; `T regpack-app 'PackagingFilesTest'`. **Pin created:** the pack-pipeline digest, recorded under Pins created with the reason "new pin, not a change of an old one"; after the push (AP-3) the CI job `determinism-compare` checks it across Linux and Windows. Commit `COMMIT "test(engine): add the pack-driven toy crash test and compare its digest across operating systems in CI"`.

### Task 25 (T25): The `pack` commands, the smoke from the bundled pack, the distribution

**Files:** Create `regpack-app/src/main/java/dev/regpack/app/PackCommands.java`, `scripts/pack.sh`, `scripts/pack.ps1` (ASCII-only LF files, `EolCheck`; run `git update-index --add --chmod=+x scripts/pack.sh`, and `git ls-files -s scripts/pack.sh` must start with `100755`), test `PackCommandsTest`. Modify `Main.java` (usage, dispatch), `SmokeCommand.java`, `regpack-app/pom.xml` (resource `${maven.multiModuleProjectDirectory}/packs/sample.s0` with `targetPath` `packs/sample.s0` and `<filtering>false</filtering>` stated explicitly, because the bytes must equal CHECKSUMS; next to `src/main/resources`), `SmokeCommandTest`, `MainTest`, `DistributionLayoutIT`.

```
regpack pack test <dir> [--reviewers <file>]          PackTester; exit 0 PASS, 1 FAIL, 2 usage
regpack pack explain <dir> --rule <id>                PackExplainer.explainRule
regpack pack explain <dir> --event <execution.json>   PackExplainer.explainEvent (canonical execution JSON; ingest key pack|explain|1; evaluatedAt 2026-01-01T00:00:00Z)
regpack pack seal <dir>                               Seal.seal; prints "sealed <n> files"
regpack pack table export <dir> <table-path> [--out <file>]
regpack pack table import <dir> <table-path> <csv> [--write]    checks; prints "rows +a -b ~c"; --write rewrites the table file and says to run pack seal
```
`<table-path>` must pass the `PackSource` name rules, exist in the pack and be listed in a report's `scope_tables`, else exit 2 (`TABLE_PATH_INVALID`); with `--write` the table file is written (temp file in the same directory, then atomic move) only when the import produced no problem, otherwise exit 1 and nothing changes. `--reviewers` defaults to `governance/golden-reviewers.txt` when it exists, else an empty registry. `scripts/pack.sh` runs `dev.regpack.app.Main pack "$@"` with the `target/classes` of model, ports, pack, engine, inmemory and app (separator `;` under Git Bash on Windows, `:` elsewhere, as `scripts/bench.sh`); `scripts/pack.ps1` is the PowerShell form. `SmokeCommand` builds the regime from `PackSource.fromClasspath(SmokeCommand.class, "/packs/sample.s0")`, `PackLoader.load` and `PackRegime.create`, uses `regime.regime()`, `decisionContext()` and `recordBudget()`, and no longer imports the skeleton.
**Tests.** `PackCommandsTest` (through `Main.run` with captured streams): `testPassesOnTheSamplePackAndPrintsTheDocumentedLines`; `testExitsOneOnAFailingGoldenCase` (a temp copy with one expected outcome changed, re-sealed); `testExitsOneWhenAProvenanceClaimHasNoReviewer` (kind `ESMA_QA` without `reviewer`: `GOLDEN_NO_REVIEWER` printed); `testExitsOneForAnUnregisteredReviewer`; `testExitsTwoOnUsageErrors`; `testReportsUnsignedAndNotInterpretedSections`; `explainRulePrintsReasonCitationsAndRationale`; `explainEventPrintsTierOneAndTheTrace`; `sealRewritesChecksumsAndTestThenPasses`; `tableExportThenImportLeavesThePackUnchanged` (**the AT-8 clause**: export, import with `--write`, re-seal; the directory is byte-identical to a sample already in canonical layout); `tableImportOfAnEditedCellChangesExactlyOneRow` (`rows +0 -0 ~1`); `tableImportWithoutWriteChangesNothing`; `tableImportRejectsAPathOutsideTheScopeTables`; `tableImportWithProblemsWritesNothingEvenWithWrite`. `MainTest#helpListsThePackCommands`. `SmokeCommandTest#smokeRunsFromTheBundledSamplePack` (output contains `under sample.s0` and `"reasonCode":"SAMPLE.N08.IN_SCOPE"`, the outcomes REPORT, DO_NOT_REPORT, UNDETERMINED in file order, last line `regpack smoke: OK decisions=3`; the four existing smoke tests stay green). `DistributionLayoutIT` additions: the app jar contains `packs/sample.s0/CHECKSUMS` and `pack.yaml`; the distribution lists no third-party jar.
**Run.** Red `T regpack-app 'PackCommandsTest,MainTest,SmokeCommandTest'`; green: same, `MVN -B -ntp verify` (the distribution is assembled, `DistributionLayoutIT` runs), then `scripts/pack.sh test packs/sample.s0` (PowerShell `scripts\pack.ps1 test packs\sample.s0`) prints `pack test: PASS`, exit 0. Commit `COMMIT "feat(app): add the pack commands and run the smoke from the bundled sample pack"`.

### Task 26 (T26): Traceability lines, gate additions and the format notes

**Files:** Modify `docs/traceability.md`, `scripts/check-gates.sh`, `docs/spikes/entry-wiring.tsv`. Create `docs/gates/G1.md`, `docs/pack-format/p2a-format-notes.md`, `regpack-pack/src/test/java/dev/regpack/pack/AmendmentTableTest.java`.

- **Traceability.** `exited_plans` stays `P0` until the owner signs P2a. Add evidence lines (kind `TEST`, store `INMEMORY`, ci `BUILD`, note `in-memory evidence`): I5 (closes P2a, P6a): `dev.regpack.engine.pack.PackScopeEngineTest#everyFailureModeIsUndeterminedWithReasonAndCitation`, `#naturalPersonIsUndeterminedNeverDoNotReport`, `dev.regpack.pack.check.TableChecksTest#catchAllNotUndetermined`. I3 (P5, P8): `dev.regpack.pack.ir.PackDigestTest#commentsKeyOrderRowOrderAndLineEndingsDoNotChangeTheDigest`, `dev.regpack.engine.pipeline.PackToyCrashIT#sameFaultPlanGivesIdenticalTraceAndDigest`, `dev.regpack.model.EngineBuildTest#differentMajorIsNotReplayCompatible`. I4 (P9b, P12): `dev.regpack.engine.pack.NoRegimeLogicInEngineTest#engineAndPackSourcesCarryNoRegimeLiterals`, `dev.regpack.engine.pack.SecondRegimeFixtureTest#theEmirActionTypeTableRunsOnTheSameEngine`. R7 (P2a, P12): the same `SecondRegimeFixtureTest` line. R8 (P9b): `dev.regpack.pack.csv.TableCsvRoundTripPropertyTest#randomTablesSurviveExportAndImportUnchanged` (partial evidence toward AT-8). No consistency-only golden case is cited (O3).
- **Gates.** `scripts/check-gates.sh` gains, before the final `echo`: for each `packs/*/` directory `bash scripts/pack.sh test "$dir"` must exit 0, else `check-gates: FAILED: pack test of <dir>`; it prints `pack test <dir>: OK`. `docs/gates/G1.md` (status `PENDING`, wording pattern of `G3.md`: the owner alone sets `CLEARED` with `cleared_by:` and `cleared_on:`). `entry-wiring.tsv` gains `P6a	G1	GATE_CLEARED	spec 17.1 G1: before P6a and increment S3 start` and `P6a-workbook-text	G3	GATE_CLEARED	spec 17.1 G3: a golden case that quotes workbook text`.
- **Format notes** `docs/pack-format/p2a-format-notes.md`, the five sections of the companion corrected to as-built behaviour (the SP-G table with columns `id`, `disposition` IMPLEMENTED, RESERVED or LEFT, `where`), the provisional contracts with the sentence on format elements beyond spec 6.1, and every problem code with one line.
- **`AmendmentTableTest#everyAcceptedWordingHasADisposition`** parses `docs/spikes/sp-g/register.md` (rows with status `ACCEPTED` and a `proposed_fix` other than `-`: exactly 15) and the notes, and asserts that each id appears in the notes' table with a disposition, that BRK-18 does not appear as a wording, and that the notes cite no id the register lacks.

**Run.** Red `T regpack-pack 'AmendmentTableTest'` (notes missing), then after `MVN -B -ntp verify` `bash scripts/check-gates.sh` fails until the lines name tests that exist in a fresh report. Green: `T regpack-pack 'AmendmentTableTest'`; `MVN -B -ntp verify cyclonedx:makeAggregateBom license:aggregate-add-third-party -DincludeTestScope=true`; `bash scripts/check-gates.sh` ends `pack test packs/sample.s0: OK`, `check-gates: all gates passed`; `java -cp regpack-tools/target/classes dev.regpack.tools.Gates entry --target P6a --root .` prints `entry: P6a: G1 GATE_CLEARED NOT_CLEARED` and exits 1 (the wiring works; a red target is the point). Commit `COMMIT "docs: add P2a traceability evidence, the pack gate step, the G1 record and the format notes"`.

### Task 27 (T27): CI, exit evidence, effort calibration and the exit record [OWNER STOP]

**Files:** Create `docs/exit/P2a.md`. Modify `docs/pack-format/decisions.md` (pins, deviations).

- [ ] **Step 1: Final local checks.** From a clean tree: `MVN -B -ntp clean verify cyclonedx:makeAggregateBom license:aggregate-add-third-party -DincludeTestScope=true`, `bash scripts/check-gates.sh`, `IT regpack-engine 'PackToyCrashIT,ToyCrashIT'`, `T regpack-engine 'PackThroughputCheck'` (copy `pack-throughput.txt` into the exit record), `git status --short` clean; then, as CI does, `MVN -DskipTests -Dspotless.check.skip=true install` and `MVN -f spikes/pom.xml verify license:aggregate-add-third-party -DincludeTestScope=true` (the frozen spike code still compiles).
- [ ] **Step 2: Exit-criterion evidence.** (a) `scripts/pack.sh test packs/sample.s0` prints the lines of T20 and `pack test: PASS`; `scripts/pack.sh explain packs/sample.s0 --rule S-N03-novation`; `... --event <file>` with the `input` object of `GC-N08-1`. (b) Provenance: copy the sample to a temp directory, change one golden case to `"provenance":{"kind":"ESMA_QA"}`, `seal` and `test` the copy: exit 1 with `GOLDEN_NO_SOURCE`, `GOLDEN_NO_REVIEWER`, `GOLDEN_NO_REVIEW_DATE`. (c) Round trip: `table export packs/sample.s0 scope/sample-scope.yaml --out <temp>/t.csv`, `table import ... <temp>/t.csv --write`, `seal`, then `git diff --exit-code packs/sample.s0` exits 0 (normalised in T21). (d) Replay rule: `EngineBuildTest`, `Tier2PlanTest`, `SemanticsFingerprintTest` green; the rule text is companion section 5.1 and the format notes.
- [ ] **Step 3: Verbatim constraints.** From the repository root in Bash, expect `0 missing`: `n=0; bad=0; while IFS= read -r l; do n=$((n+1)); grep -qF -- "$l" docs/superpowers/specs/2026-10-08-regulatory-reporting-platform-design.md || { echo "MISSING: $l"; bad=$((bad+1)); }; done < <(sed -n '/^## Global constraints/,/^## Review focus/p' docs/superpowers/plans/2026-10-16-p2a-rule-pack-core.md | grep '^> ' | sed 's/^> //'); echo "$n checked, $bad missing"`.
- [ ] **Step 4: OWNER STOP (AP-2, AP-3): push and CI.** Ask the owner to push `p2a/rule-pack-core` (the `p2a/**` filter starts the workflow) and report the run. Expected: all six jobs green (seven runs: the build job runs on two systems) (`build` on both systems, `determinism-compare` with both digests, `spikes`, the three smokes). The agent reads run and job listings only (logs need a sign-in). A red job is a fix round, never a gate change. When the OI-10 checkpoint was taken, report the second run.
- [ ] **Step 5: Write `docs/exit/P2a.md`.** *Verdict* (the four clauses and the S0 definition, each with its step-2 evidence); *The CI run* (link, jobs, both toy digests equal); *Pins* (new: sample pack digest, semantics fingerprint, pack-pipeline digest, each with value, task, reason; unchanged: those of "Existing tests that change"); *Throughput information* (T22 file and caveats); *DECIDE vectors* (the five provisional rows, any rulings); `EXEC_* count on the generated corpus (0 by construction)`; *Open items* (the SP-R measurement of the background EXEC_* rate stays open for a shadow run on real records (P6a, P7a); N = 4 in M = 50 stay MODELLED and are not relied on until then; open question for the owner: SP-R's clustered-burst scenario gives about 24 opens a day at 5,000 records at 5%); *Deferred* (section "Deferral"); *Deviations from the plan*; *Effort for the spec section 15 calibration*; *Sign-offs (owner)*, all unticked: AP-4 merge and tag; exit criterion accepted; contract and layout changes accepted; DECIDE rulings recorded or deferred; spec amendments (D-4) applied or deferred; the `entry-wiring.tsv` edit accepted and the freeze-record line "entry-wiring re-checked against spec 16" re-confirmed; traceability `exited_plans: P0, P2a`; calibration read-out done and the P3 re-estimate decision recorded (spec 15); `Signed: <owner>, <date>`.
- [ ] **Step 6: Effort calibration (spec 15; OI-8).** Copy the ledger, add the git counts (`git rev-list --count p1a/spikes..HEAD`, `git diff --shortstat p1a/spikes...HEAD`), compare with P0 (33 tasks, 77 agent runs) and P1a (23 tasks, 70 subagents), print a worksheet for P2a-2 and every later plan (task-count estimate blank; owner minutes from the ledger). The lever is the cut order of spec 17.4.
- [ ] **Step 7: Final whole-branch review.** Independent reviewers over `git diff p1a/spikes...HEAD` (lenses: spec fidelity, buildability and test teeth, safety and gates, scope); every finding above minor is re-checked by a second reviewer who tries to refute it; fixes go in separate reviewed commits listed in the exit record.
- [ ] **Step 8: OWNER STOP: sign-off and calibration.** Ask for the owner's estimate of reading and deciding time (ledger). The agent never ticks a box. P3 planning starts only after the owner records the calibration read-out and the re-estimate decision (continue, or the cut order of spec 17.4). The tag for the owner to set is `p2a-rule-pack-core` (not set, not pushed). **Step 9:** `COMMIT "docs: record the P2a exit evidence and the effort calibration"`.

## Deferral: P2a-2 and everything else

**P2a-2 (written after the P2a exit and the calibration read-out; the rest of the spec 16 row).** Scope: the Firm Profile register (versioned, effective-dated, stamped as `firm_profile_version`), the overlay registry (three declared MiFIR options, parameters, source-mapping hooks; RSV-6), interpretation pins (`INT-xxxx`: rationale, approver, date; signed overlay records on every affected decision), their effective dating and the approval classes of spec 6.5 step 4 as data hooks (the workflow is P9b); real digests and versions in place of the `none` and empty-list placeholders of `PackRegime.decisionContext()`; the options a pack declares in `interpretations.yaml`; **derive tables** (the row shape with `field` and `value`, its checks and evaluation to `DerivedField` for the S3b slice) with the five derivation kinds of spec 6.8 (decimal rounding, microsecond timestamp formatting, conditional blanking, GBX-to-major-unit conversion, string building) as library and conformance work. Six to eight tasks. **Entry wiring:** the P2a exit record signed; the D-4 spec amendments applied or the format notes accepted in their place; the machinery has no gate but its **content** (a Firm Profile, a pin) needs a named Compliance owner (G1, wired to `P6a`). P2a-2 must exit before P6a rows that read an overlay option or a pin, before the S3b derivation slice and gate G-S3b, and before P9b approves Firm Profile, overlay or pin changes. S3b, S8 (Firm Profile, overlay and pin approvals), P6a and P9b depend on P2a-2 as well as on P2a (spec 15, 16).

**Hand-offs** to P3, P5, P6a, P8, P9b, P12, P10b and P11 are companion section 4; T26 copies them into the format notes.

## Self-review

**Checks run (2026-10-16).** Every element of the spec 16 P2a row maps to a task or to the deferral; the four exit clauses map to T25, T19, T20, T21, T3, T18 and are re-run in T27 step 2; the 15 SP-G wordings have one row each; every Global constraint line matched the specification by exact substring (T27 step 3 repeats it). Facts were read from the repository on 2026-10-16; the baseline `verify` and gates were green. No TODO, TBD, emoji or regulatory text. **Residual risks.** The strict parser is new security-relevant code (closed grammar, limits, hostile tests). The cost model and the function names are the spike's design. The spec amendments are not in the spec text yet. The throughput figure covers the interpreter only. `DecisionBody.attributes` is a ledger-contract change that P3 must see (AP-1).

## Cut order if effort overruns

Never cut: the parser's rejections, the catch-all and priority checks, the I5 tests, the round trip, the replay rule, provenance enforcement. Cut in this order: the alternative branches of the four ruling switches (keep the file and the vectors); the full trace and the rule view of `pack explain` (keep Tier 1); the second-regime fixture; the throughput check (T22). Cutting the second-regime fixture also removes the I4 and R7 evidence lines of T26 and changes BRK-04 in the SP-G table to Reserved, not proved. Cutting T22 also removes the throughput section of the exit record. Cutting T24 also removes the second `ci.yml` edit (AP-3) and the `PackagingFilesTest` assertions.

## Execution handoff

**Recommended: subagent-driven development** (a fresh implementer per task, its prompt including the companion contracts file; an independent reviewer after each with the Review focus table as checklist; the controller keeps `docs/pack-format/decisions.md` and the effort ledger). **Owner stops:** T1 step 4; T14 (rulings, a departure); the optional CI checkpoint after T16 (OI-10); T27 steps 4 and 8. **Order:** T1 to T16, then T22, then T17 to T21 and T23 to T27 (task numbers unchanged). After T2 the pack side (T3 to T10) and the expression side (T11 to T14) are independent (T10 needs T9; T14 needs T12 and T13). T15 needs T9, T10, T12 (hence T11); T16 needs T14, T15; T17 and T18 need T16; T19 needs T9, T10; T20 needs T16, T19; T21 needs T7, T9; T22 needs T9, T15, T16; T23 and T24 need T16; T25 needs T18 to T21; T26 needs T23, T24 and T25; T27 needs all.
