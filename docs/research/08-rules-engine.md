# 08 - Configurable reportability and classification rules with legal-basis traceability

Research date: 2026-10-08 (all "accessed" dates). Research only; not legal advice.
Markers: **[2025-26]** = introduced or amended in 2025-2026; **[FUTURE]** = scheduled or pending. Confidence: H / M / L.
Tooling note: the web-search quota ran out mid-session, so a few items are flagged "not verified" instead of being chased further.

---

## 1. Summary

- **No off-the-shelf engine meets every requirement.** The nearest model for provenance and process is ISDA DRR / Rune DSL (rule -> `docReference` -> provision, plus rationale and author; committed golden outputs; peer review). But DRR is consumed as compiled Java artefacts, not as data. It does not yet cover MiFIR (in progress, revised rules expected 2027). It also carries a bespoke licence that needs legal review.
- **Rules must be data, effective-dated, and tolerant of unscheduled activation.** The EU amendments to MiFIR Art 26 apply only "when the revised RTS 22 starts applying", with no date fixed. The UK replaces its regime on 3 April 2028 with a new schema. ESMA proposes "report once" by about H2 2031. At any time we will hold several concurrent versions per regime.
- **Recommendation: a purpose-built small DSL.** It combines decision tables (DMN-like hit policies) with a CEL-shaped, non-Turing-complete expression language. It compiles to one IR with two executors: a set-based columnar executor (generated SQL on an embedded engine, DuckDB by default) for millions of records, and a scalar reference interpreter for explanation and differential testing. Borrow DMN's table semantics and static gap/overlap analysis, Rune's citation model, OPA's signed-bundle activation, and Cedar/CEL-style formal diffing. Do not adopt a general engine wholesale.
- **Every decision emits** `{outcome, reason_code, decision_path, citations[], pack_digest}`. Reason codes are a first-class registry, each linked to provision paths in a Legal Source Registry that uses ELI/EUR-Lex-style identifiers.
- **Impact analysis is a CI gate.** Replay the candidate pack against the stored historical inputs and diff outcome and reason against the baseline pack. Publish a transition matrix and block merge until a named approver signs it.

---

## 2. Key facts (source, as-of, confidence)

| # | Fact | Source | As-of | Conf. |
|---|---|---|---|---|
| F1 | Rune `report` = authority + corpus(es) + timing + input type + `when` eligibility rules + output type. Eligibility rules return Boolean; reporting rules return values. `[docReference Body Corpus Segment... provision "..."]` also takes `rationale` and `rationale_author` (CFTC example: "DRR Peer Review Group - 03/12/21"). `[ruleReference X]` binds a field to its rule. | https://rune.finos.org/docs/modelling-components/reporting/ | 2026-10-08 | H |
| F2 | DRR timing is informational only and generates no executable code. Eligibility is a `when` function. Namespaces are Base / Enrichment / Ingest / Standards / Regulation / Projection. | https://drr-docs.isda.org/docs/using-drr/scope-and-structure/ , https://drr-docs.isda.org/docs/using-drr/drr-namespace-structure/ | 2026-10-08 | H |
| F3 | DRR test packs: FpML samples grouped in 12 packs; committed expected JSON outputs; 46 report pipelines / 145 report test-pack configs on `master`; regenerated via a Maven profile, so a model change shows as a reviewable diff. A rule is "fully developed" only once verified against test data. | https://drr-docs.isda.org/docs/for-developers/key-concepts/ , https://drr-docs.isda.org/docs/using-drr/drr-design-principles/ | 2026-10-08 | H |
| F4 | DRR is consumed as Java artefacts (`com.regnosys.drr`, hosted in ISDA's Maven repo) and a generated report function called with a `ReportableEvent`. A rule change is therefore a dependency/code release. Docs list versions 7.0.0 (current), 6.0.0, next. | https://drr-docs.isda.org/docs/using-drr/implement-drr/ , https://drr-docs.isda.org/docs/ | 2026-10-08 | H |
| F5 | DRR coverage: live for CFTC, EMIR (EU), UK EMIR, ASIC, MAS, JFSA, CSA (Canada, 25 Jul 2025 **[2025-26]**), HK (29 Sep 2025 **[2025-26]**). EU/UK MiFIR, SFTR and Switzerland are "in progress". ISDA press release 7 May 2025: MiFID/MiFIR extension with DTCC GTR ARM; revised MiFIR reporting expected over 2027 **[FUTURE]**. | https://www.isda.org/isda-solutions-infohub/isda-digital-regulatory-reporting/ , https://www.isda.org/a/7xfgE/ISDA-to-Extend-DRR-to-cover-MIFID-MIFIR-Reporting-and-Works-With-DTCC-to-Further-Adoption.pdf | 2026-10-08 / 2025-05-07 | H |
| F6 | ISDA DRR License v1.0 (Sept 2023): royalty-free, non-exclusive grant "without modifications and solely as part of a Larger Work"; indemnity to ISDA for Larger-Work distribution; NY law. Commercial use is neither expressly allowed nor prohibited. A third-party FAQ calls DRR "not open source". Needs legal review. | https://www.isda.org/isda-solutions-infohub/isda-digital-regulatory-reporting/isda-drr-1/ , https://www.isda.org/2023/09/19/isda-digital-regulatory-reporting-drr-license/ | 2026-10-08 | M |
| F7 | Rune DSL (FINOS): Apache-2.0; default generator is Java; latest release 10.15.1 dated "08 Oct" (year not shown; consistent with 2026) plus a 9.x backport line. PR #1381 (merged 4 Sep 2026) reformatted `label`/`ruleReference` annotations and allowed qualified names. **[2025-26]** | https://github.com/finos/rune-dsl , https://github.com/finos/rune-dsl/releases , https://github.com/finos/rune-dsl/pull/1381 | 2026-10-08 | M |
| F8 | MiFIR (Reg 600/2014) Art 26 as amended by Reg 2024/791: new 26(2)(d) (Art 8a(2) OTC derivatives reportable wherever traded) and a venue-only condition for other OTC derivatives. The amendments "apply when the revised RTS 22 starts applying"; until then the consolidated Art 26 (09/01/2024) and current RTS 22 apply. Art 26(11): ESMA report on integrating transaction reporting due 29 Mar 2028. **[FUTURE]** | https://www.esma.europa.eu/publications-and-data/interactive-single-rulebook/mifir/article-26-obligation-report-transactions | 2026-10-08 | H |
| F9 | Current RTS 22 (Del. Reg 2017/590) Art 2(5) lists exclusions (a)-(o): SFTs, clearing/settlement-only contracts, net settlement carry-forward, custodial, novation/assignment (e), portfolio compression (f), fund-unit creation/redemption by the administrator (g), embedded rights/conversion (h), predetermined/mandatory events (i), notional changes (j), index composition (k), DRIP (l), small employee-plan trades (m, EUR 1,000 / EUR 500 caps), exchange/tender offers (n), collateral transfers (o). Art 3(2): a firm that transmitted an order per Art 4 is not deemed to have executed. (FCA-hosted rendering of the EU text, page dated 01/01/2021; verify against EUR-Lex consolidated.) | https://www.handbook.fca.org.uk/techstandards/MIFID-MIFIR/2017/reg_del_2017_590_oj/006.html , .../007.html | 2026-10-08 | M |
| F10 | ESMA Final Report 2 Jul 2026 (ESMA12-1406959660-3235): recommendations to the EU legislator, not binding. Includes "report once" across MiFIR/EMIR/SFTR, integrated solution expected toward H2 2031, back-reporting horizon 5 -> 3 years (override up to 5), and targeted changes to RTS 22 Art 2(5)(g), (i), (n) and employee share plans. Level 1 changes needed for the long-term model. **[2025-26][FUTURE]** | https://www.esma.europa.eu/sites/default/files/2026-07/ESMA12-1406959660-3235_Final_Report_on_the_Call_for_Evidence_on_a_comprehensive_approach_for_the_simplification_of_financial_transaction_reporting.pdf (text extracted locally) | 2026-07-02 | H |
| F11 | UK FCA PS26/15 (3 Aug 2026): new regime in force 3 Apr 2028; fields 65 -> 52; about 7m EU-only instruments and FX derivatives leave scope; default back-reporting 5 -> 3 years; flexible supervision from 3 Aug 2026; draft schema and validation rules consultation planned Oct 2026. Secondary sources: new rules in MAR 14 with testing and reconciliation duties and an auditable incident record. **[2025-26][FUTURE]** | https://www.fca.org.uk/publications/policy-statements/ps26-15-improving-uk-transaction-reporting-regime , https://www.kaizenreporting.com/fca-ps26-15-mifir-transaction-reporting-reforms/ | 2026-08-03 | H (dates) / M (MAR detail) |
| F12 | DMN 1.6 is listed as Formal (Sept 2026) with 1.7 beta **[2025-26]**. DMN TCK (updated 2026-08-01, DMN 1.4 basis): jDMN 10.0.0 (Goldman Sachs, Apache-2.0) 3391/3391; Trisotech 3390; Apache KIE Drools 10.2.0 3388; Camunda 7.21 2741/3391 (385 not supported). Drools engine/editor support DMN 1.6 from KIE 10.2.0 (Apr 2026); KIE still "incubating"; Drools DMN does static gap/overlap analysis of decision tables. | https://www.omg.org/spec/DMN , https://dmn-tck.github.io/tck/ , https://kie.apache.org/drools/dmn/ | 2026-10-08 | H (TCK) / M (OMG status; vendor snapshots conflict) |
| F13 | CEL: non-Turing-complete, no mutation, linear-time evaluation, compile-once / evaluate-many, serialisable type-checked AST (protobuf). cel-go supports static cost estimation; Kubernetes enforces cost budgets (1,000,000 per call, 10,000,000 per resource). Evaluators are row-at-a-time; no vectorised CEL found. CEL Formal Verification Framework (Z3; equivalence and counterexamples) is in the CEL-Java repo (announced 2026-08 **[2025-26]**). | https://github.com/google/cel-spec , https://cel.dev/overview/cel-overview , https://godocs.io/k8s.io/apiserver/pkg/apis/cel | 2026-10-08 | H / M |
| F14 | OPA: Apache-2.0, CNCF graduated; core maintainers (Koponen, Hinrichs, Sandall) joined Apple Aug 2025 **[2025-26]**; v1.21.1 dated 2026-09-29. Bundles: manifest `revision` + `roots`; signed bundles via `.signatures.json` JWT with out-of-band verification key; failed verification keeps the active bundle; optional on-disk persistence. Guidance: rule indexing, ~45 us/op for an RBAC sample; raw JSON costs ~20x memory in OPA. | https://www.openpolicyagent.org/docs/management-bundles , https://www.openpolicyagent.org/docs/policy-performance , https://github.com/open-policy-agent/opa/releases | 2026-10-08 | H / M |
| F15 | Cedar: authorisation-specific (principal/action/resource/context); Lean-verified spec, differential testing, guaranteed termination ("free of side effects and general loops"; looping constructs linear); authors report 42.8-80.8x faster than Rego and 28.7-35.2x faster than OpenFGA on their workloads (2024 paper, self-reported). SMT-based equivalence/subsumption analysis. CNCF Sandbox (accepted Oct 2025; AWS post 2025-12-15 **[2025-26]**). Apache-2.0, Rust. | https://arxiv.org/html/2403.04651v2 , https://aws.amazon.com/blogs/opensource/cedar-joins-cncf-as-a-sandbox-project , https://github.com/cedar-policy/cedar | 2026-10-08 | H / M |
| F16 | DuckDB (MIT): 1.5.6 current (announced 2026-09-28), 1.4.5 LTS, v2.0 only as preview (blog posts Aug-Sep 2026) **[2025-26][FUTURE]**. Sandboxing settings: `enable_external_access=false`, `allowed_directories`, `memory_limit`, `threads`, `lock_configuration`; docs say these are defence-in-depth, not a substitute for OS isolation. ASOF join for as-at lookups. DuckLake v1.0 (2026-04-13): SQL-catalogued lakehouse format with snapshots/time travel, in DuckDB 1.5.2+. | https://duckdb.org/news/ , https://duckdb.org/install/ , https://duckdb.org/docs/current/operations_manual/securing_duckdb/overview.html , https://duckdb.org/docs/current/sql/query_syntax/from.html , https://ducklake.select/2026/04/13/ducklake-10/ | 2026-10-08 | H |
| F17 | Rust Datalog macros (`crepe` Apache-2.0/MIT, `ascent` MIT) fix rules at compile time, which is incompatible with data-only hot reload. Soufflé (UPL-1.0) compiles Datalog to C++ and lists provenance support. Nemo (Apache-2.0/MIT, Rust) is a runtime Datalog engine still described as unstable. | https://github.com/ekzhang/crepe , https://github.com/s-arash/ascent , https://github.com/souffle-lang/souffle , https://github.com/knowsys/nemo | 2026-10-08 | H |
| F18 | Starlark spec: "deterministic and hermetic"; "does not allow recursion or unbounded loops"; freezing enables parallel use. starlark-rust (Meta, Apache-2.0) adds recursion and top-level `for` as extensions. | https://raw.githubusercontent.com/bazelbuild/starlark/master/spec.md , https://github.com/facebook/starlark-rust | 2026-10-08 | H |
| F19 | Wasm sandbox limits: Wasmtime fuel is deterministic but slower; epoch interruption is wall-clock and non-deterministic; memory capped via `ResourceLimiter`; 2026 advisories touch fuel/epoch edge cases. Luau offers an interrupt handler and host memory caps but says it is not formally proven. | https://docs.wasmtime.dev/examples-interrupting-wasm.html (via search) , https://luau.org/sandbox | 2026-10-08 | M |
| F20 | Vectorised vs compiled execution (Kersten et al., VLDB 2018): both efficient; vectorisation hides cache-miss latency, compilation needs fewer instructions (cache-resident workloads). DataFusion CASE work (Feb 2026) shows mask-based short-circuiting; 63-71% less CPU on TPC-H orders SF100 vs DataFusion 50. | https://www.vldb.org/pvldb/vol11/p2209-kersten.pdf , https://datafusion.apache.org/blog/2026/02/02/datafusion_case/ | 2018 / 2026-02-02 | H |
| F21 | OpenFisca: vectorised (NumPy) rules-as-code; parameters are dated YAML values with per-value `reference` metadata; variables carry a legislative `reference`. Catala: DSL for statutes (default logic, general rule plus exceptions), Apache-2.0 compiler, Python/OCaml backends. | https://openfisca.readthedocs.io/en/latest/coding-the-legislation/25_vectorial_computing.html , https://openfisca.readthedocs.io/en/latest/openfisca-python-api/parameters.html , https://www.microsoft.com/en-us/research/publication/catala-a-programming-language-for-the-law/ | 2026-10-08 | M |
| F22 | ELI: EU template `http://data.europa.eu/eli/{type}/{year}/{number}/oj`; template components include point-in-time, version and subdivision. MiFIR's ELI is `.../eli/reg/2014/600/oj`. `.../eli/reg_del/2017/590/oj` redirects (HTTP 307) to EUR-Lex (our fetch tool cannot render EUR-Lex). ESMA's Interactive Single Rulebook offers article-level URLs. | https://en.wikipedia.org/wiki/European_Legislation_Identifier , http://data.europa.eu/eli/reg_del/2017/590/oj | 2026-10-08 | M |
| F23 | Sigstore cosign: keyless signing with Rekor transparency log; supports a signing config pointing at private Fulcio/Rekor/TSA. in-toto attestations bind a statement to subject digests with typed predicates (fits a custom "rule-pack approval" predicate). | https://docs.sigstore.dev/cosign/signing/overview/ , https://github.com/in-toto/attestation/blob/main/spec/README.md | 2026-10-08 | M |

---

## 3. Detailed findings

### 3.1 How DRR models eligibility, reporting rules and lineage

- **Structure (F1, F2).** A report definition is a declaration: authority, corpus(es), timing, input type, `when` eligibility rules, output type. The output type's attributes are the reportable fields; each carries `[ruleReference RuleName]`.
- **Rule types.** An `eligibility` rule returns a Boolean (should this event be reported?). A `reporting` rule returns a value (how is this field derived?). Both are pure functions over the input type.
- **Lineage.** `docReference` anchors a rule to a Body, Corpus and Segment path, plus a verbatim `provision` quote. `rationale` and `rationale_author` record *interpretive* decisions. Example: "Model only applicable for back-to-back schedules...", attributed to the DRR Peer Review Group with a date. The chain is field -> `ruleReference` -> rule -> `docReference` -> provision. The docs I read do not describe a lineage *viewer*. They also do not describe an effective-date construct; versioning appears to be the model release (7.0.0 / 6.0.0 / next). Both are absence of evidence (L).
- **Testing and governance (F3).** Test packs of samples, pipelines (TRANSLATE -> ENRICH -> REPORT -> PROJECTION), committed expected outputs, regeneration plus review-the-diff. ISDA's Peer Review group approves public-model changes (per a TradeHeader blog, vendor source, M).
- **Limits for us (F4-F6).** Derivatives-centric (CDM/FpML input). MiFIR not yet covered. Java-compiled. Regime applicability appears to be supplied as enriched input (`ReportableInformation`) (L). Licence needs review.
- **Reason codes plus legal references, modelled our way.** DRR has only Boolean eligibility, so add a `ReasonCode` registry:

```
ReasonCode { code, label, category: EXCLUSION|INCLUSION|OUT_OF_SCOPE|DATA_QUALITY|DEFAULT,
             default_outcome, valid_from, valid_to, owner }
Citation   { source_id, provision_path ("art/2/par/5/pt/e"), role: BASIS|EXCEPTION|INTERPRETATION|GUIDANCE,
             quote_sha256, retrieved_at }
Rationale  { id, text, author, approved_by, approved_at }      // mirrors rationale/rationale_author
```

A rule row references one `reason_code`, optional extra citations and an optional `rationale`. The Legal Source Registry maps `source_id` to an ELI (for example `http://data.europa.eu/eli/reg_del/2017/590/oj`) plus a point-in-time version and a hash of the provision text. Non-binding material (ESMA Q&A, Guidelines, NCA guidance) is a different `role`, so the decision output can distinguish "the law says" from "we interpret".

### 3.2 Legal-source churn drives the design (F8-F11)

| Item | Status | Design consequence |
|---|---|---|
| EU Art 26 amendments (Reg 2024/791) | Apply when revised RTS 22 applies; no date. A vendor article (Mondaq/TRAction, 21 Jul 2026) says the Commission will not adopt RTS 22 amendments before 1 Oct 2027; it cites no source (L). **[FUTURE]** | Activation can be **event-triggered**. Stage an approved pack in `pending` state and shadow-run it. |
| EU RTS 22 | ESMA's June 2025 report left the text unchanged; the 2 Jul 2026 report recommends targeted Art 2(5) changes and field deprioritisation. **[2025-26]** | Exclusion rules (Art 2(5)(a)-(o)) are the likeliest to change: the best regression-test and impact-analysis target. |
| UK | PS26/15; in force 3 Apr 2028; new schema; EU-only instruments and FX derivatives out of scope. **[2025-26][FUTURE]** | Separate UK pack lineage (not a fork of the EU pack); jurisdiction is a pack dimension. |
| Back-reporting | 5 -> 3 years (UK now by supervisory flexibility; EU recommended) | Replay storage horizon of at least 5 years remains (FCA: 5-year record retention). |
| DRR MiFIR | In progress; revised rules over 2027. **[FUTURE]** | Keep a pluggable "external rule provider" seam so a DRR-based module could later sit behind the same decision contract. |

The **selecting date** is a legal question, not an engineering one. Candidates are trade/execution date, report due date, submission date and correction date. Make it a per-regime manifest field (`as_at_field`) and ask Legal. Also note that the current test for OTC derivatives depends on matching reference data with venue-traded instruments (ESMA opinion as quoted in the 21 Jul 2026 Mondaq/TRAction article, M); the amended Art 26 removes that ToTV test for OTC derivatives. Inputs such as `instr.in_scope` therefore need as-at reference data (the FIRDS lookup is my assumption, unverified).

### 3.3 Engine evaluation

Ratings are my judgement from the evidence above. "Vectorised" means a columnar or batch path exists.

| Candidate | Fit for reportability + classification | Strengths | Weaknesses | Verdict |
|---|---|---|---|---|
| **DMN / FEEL** (Drools, jDMN, Camunda) | Good semantics (hit policies U/A/F/R/C; decision tables; `knowledgeSource` = authority for a decision) | Analyst tooling; TCK gives a public conformance score (F12); static gap/overlap analysis (Drools); DMN 1.6 formal 2026 | JVM-centric; row-at-a-time; no structured per-rule citation or reason code (annotation column only); engines diverge (Camunda 2741/3391); Drools KIE still incubating | **Borrow semantics, import/export; do not make the runtime.** Reasonable fallback if the platform is JVM and build capacity is short |
| **GoRules ZEN / JDM** | JSON decision graphs, tables, expressions, MIT, Rust, bindings | Data-driven, compiled once; fast per vendor claim (no figures given) | JDM is not DMN-compliant (spec compliance not claimed; L); no legal-citation model; "no external contributions" policy; function nodes (script) need sandbox review | Candidate for the **row-at-a-time lane** if we want to buy; unproven for this domain |
| **CEL** | Excellent expression layer: typed, side-effect-free, linear, cost-estimable, AST serialisable | Spec plus conformance tests; multi-language; Z3 verification (Java) | No tables, packaging or tracing; numeric types likely int/double, not decimal (not verified); no vectorised implementation | **Adopt its semantics/grammar for conditions**; verify decimal/date needs in spike |
| **JSON Logic** | Tiny, serialisable, safe, no loops | Trivial to embed; Rust impl claims ns-scale (self-reported) | No date/decimal; poor readability for nested logic; no tracing/citation | Not recommended as core; acceptable interchange for UI builders |
| **Rego / OPA** | Policy-shaped (allow/deny), JSON documents | Best-in-class bundle packaging, signing, decision logs, partial evaluation, tests | Memory-heavy JSON model (~20x); per-input evaluation; "undefined" semantics can mask failures; maintainer change (Apple, Aug 2025) | **Borrow governance mechanics, not the engine** |
| **Cedar** | Authorisation schema (principal/action/resource) | Lean-verified, terminating, SMT equivalence analysis; fast | Wrong domain model; no tables; no reason codes/citations | Borrow *analysis ideas* only |
| **Drools / DRL** | Forward-chaining rules; DMN support | Mature; DMN decision tables | Rule interplay (agenda/salience), JVM weight, KJAR deployment, no vectorisation; hard for analysts | Not for new build unless JVM-only |
| **Rune DSL (DRR)** | Best provenance model (F1) | Apache-2.0 DSL; industry-governed (ISDA peer review); test packs | Compiled Java; no effective-dating/reason codes found; MiFIR absent; licence | **Copy the model**; adopt as external provider where DRR covers a regime |
| **SQL / DuckDB** | Rules as set-based queries over canonical tables | Vectorised, multi-threaded, larger-than-memory; ASOF joins for as-at reference data; MIT; Parquet/DuckLake snapshots make replay cheap | Raw SQL is not analyst-friendly and has 3-valued NULL traps; SQL is a code-execution surface; no native citation model; 2.0 transition pending | **Best executor**, but generate SQL from a restricted DSL, never accept hand-written SQL in packs |
| **Datalog** (Soufflé, Crepe, ascent, Nemo) | Strong for recursive relations (entity hierarchies, linked orders) | Provenance (Soufflé); declarative | Rust macro engines are compile-time (F17); Soufflé needs a C++ toolchain; learning curve | Use selectively for **cross-reference (R11)**, not for the reportability path |
| **Starlark** | Deterministic, hermetic, no recursion/unbounded loops | Python-like for engineers; embeddable (Rust/Go) | Interpreted; analysts cannot author; not statically citation-aware | Optional **engineer lane** for rare derivations, signed and fuel-limited |
| **Lua / Wasm** | Maximum flexibility | Wasmtime fuel and memory limits; Luau interrupt handler | Opaque code defeats static analysis, citation and impact diff; supply-chain/CVE surface (F19) | Last resort; prefer a vetted built-in function library (LEI mod-97, ISIN check digit, date maths) shipped with the engine |
| **Purpose-built DSL** | Decision tables + CEL-shaped expressions + citation/reason-code first-class | Fits every requirement; engine-independent IR; vectorised path; static analysis; small attack surface | Build and maintain cost; authoring UI needed; must be conformance-tested | **Recommended** (section 4) |

Inspiration for a purpose-built DSL: **OpenFisca** (vectorised legislation-as-code; dated parameters with a per-value `reference`) and **Catala** (statute = general rule plus exceptions with explicit priority). The Catala point matters because MiFIR exclusions are "exceptions to the transaction definition". Row order in a FIRST table is fragile under edits. Our rules therefore carry explicit `priority` and optional `overrides: [ids]`, checked by static analysis for unreachable or shadowed rows.

### 3.4 A small MiFIR rule set in three notations

**Illustrative only; based on current (pre-amendment) Art 26(2)(a)-(c), RTS 22 Art 2(5)(a)/(e) and Art 3(2). Not validated by Compliance.** Upstream enrichment derives `instr.in_scope` = ToTV OR underlying ToTV OR index/basket of ToTV, using FIRDS as at execution date.

**A. DMN decision table (hit policy FIRST; "-" = any)**

| # | txn.kind | firm.role | instr.in_scope | outcome | reason | annotation |
|---|---|---|---|---|---|---|
| 1 | "SFT" | - | - | DO_NOT_REPORT | EXCL.SFT | RTS22 2(5)(a) |
| 2 | "NOVATION" | - | - | DO_NOT_REPORT | EXCL.NOVATION | RTS22 2(5)(e) |
| 3 | - | "TRANSMITTING" | - | DO_NOT_REPORT | TRANSMITTED | RTS22 3(2), Art 4 |
| 4 | - | - | true | REPORT | INSCOPE.TOTV | MiFIR 26(2)(a)-(c) |
| 5 | - | - | false | DO_NOT_REPORT | OOS.INSTRUMENT | MiFIR 26(2) |
| 6 | - | - | - | UNDETERMINED | DQ.SCOPE_UNKNOWN | no basis |

**B. Rune-style (shape follows the documented `<ruleType> rule ... [docReference ...]` template; segment declarations and the decision-type constructor are not verified)**

```
body Authority ESMA
corpus Regulation "Delegated Regulation (EU) 2017/590" RTS22
type ReportabilityDecision:
  outcome  ReportabilityOutcomeEnum (1..1)
  reason   string (1..1)

reporting rule Reportability from TxnInput:
  [docReference ESMA RTS22 article "2" paragraph "5" point "a"
     provision "securities financing transactions as defined in Article 3(11) of Regulation (EU) 2015/2365"
     rationale "SFTs are reported under SFTR" rationale_author "Compliance - <name>, <date>"]
  if kind = TxnKindEnum -> SFT then ReportabilityDecision { DoNotReport, "EXCL.SFT" }
  else if kind = TxnKindEnum -> Novation then ReportabilityDecision { DoNotReport, "EXCL.NOVATION" }
  ...
eligibility rule IsReportable from TxnInput:
  Reportability -> outcome = ReportabilityOutcomeEnum -> Report

report ESMA RTS22 in T+1 from TxnInput when IsReportable with type MifirTxnReport
```

**C. SQL on DuckDB (generated from the pack; evaluates to a small integer rule id, then joins metadata)**

```sql
CREATE TABLE decision AS
SELECT t.trade_id, t.trade_date,
  CASE WHEN t.kind = 'SFT'          THEN 10   -- R010 EXCL.SFT
       WHEN t.kind = 'NOVATION'     THEN 20   -- R020
       WHEN t.role = 'TRANSMITTING' THEN 30   -- R030
       WHEN t.in_scope IS TRUE      THEN 40   -- R040
       WHEN t.in_scope IS FALSE     THEN 50   -- R050
       ELSE 900 END AS rule_id                -- R900 UNDETERMINED (NULL scope)
FROM txn_canonical t;

SELECT d.trade_id, m.outcome, m.reason_code, m.citation_ids, m.pack_digest
FROM decision d JOIN rule_meta m ON m.rule_id = d.rule_id
 AND d.trade_date >= m.valid_from AND d.trade_date < COALESCE(m.valid_to, DATE '9999-12-31');
```

**D. Recommended canonical pack form (authoring and diff format; compiles to A-C equivalents)**

```yaml
pack: mifir.reportability        # semver + content digest recorded at publish
version: 2.3.0
valid: { from: 2018-01-03, to: null }          # RTS 22 applied from 3 Jan 2018 (M)
as_at_field: trade_date
table: scope                     # hit_policy: first, with explicit priority
rules:
  - { id: R010, when: "txn.kind == 'SFT'",          outcome: DO_NOT_REPORT, reason: MIFIR.EXCL.SFT,
      cite: [rts22#art/2/par/5/pt/a], rationale: RAT-0042 }
  - { id: R030, when: "firm.role == 'TRANSMITTING'", outcome: DO_NOT_REPORT, reason: MIFIR.TRANSMITTED,
      cite: [rts22#art/3/par/2, rts22#art/4] }
  - { id: R040, when: "instr.in_scope == true",     outcome: REPORT,        reason: MIFIR.INSCOPE.TOTV,
      cite: [mifir#art/26/par/2/pt/a, mifir#art/26/par/2/pt/b, mifir#art/26/par/2/pt/c] }
  - { id: R900, when: "true",                         outcome: UNDETERMINED,  reason: DQ.SCOPE_UNKNOWN }
future_rules:                    # staged, not active
  - { id: R041, when: "instr.is_otc_deriv && !instr.in_art8a2 && !txn.on_venue", outcome: DO_NOT_REPORT,
      valid: { from: { event: "rts22-revised-applies" } }, cite: [mifir#art/26/par/2/pt/a@amended] }
```

**Comparison (my judgement)**

| Criterion | A. DMN | B. Rune | C. SQL | D. Canonical DSL |
|---|---|---|---|---|
| Analyst readability | High (table) | Low-medium (code) | Low | High (table) with a code escape for conditions |
| Per-rule legal citation | Free-text annotation only | Native, structured, with rationale | Comments / metadata join | Native, structured, hash-checked |
| Reason code / decision path | Convention | Needs custom type | Rule id + join | First-class |
| NULL / unknown handling | FEEL null semantics (B-FEEL in 1.6) | Rune semantics | 3-valued SQL, easy to get wrong | Explicit UNDETERMINED policy enforced by the checker |
| Effective dating | Pack-level only | Model release | Join columns | Rule and pack level, plus event-triggered |
| Golden-test fit | Excellent (TCK precedent) | Excellent (DRR precedent) | Good | Excellent (table-driven cases) |
| Throughput (millions) | Row-at-a-time | Row-at-a-time (generated Java) | Vectorised, parallel | Vectorised via C; scalar for explain |
| Sandbox / termination | Engine-dependent | Java | Needs lock-down config | Non-Turing-complete by construction |

### 3.5 Performance reasoning (no benchmark claimed)

- **Two families.** Row-at-a-time interpreters (CEL, JSON Logic, ZEN, Rego, Cedar, DMN, Drools) pay interpretive overhead plus per-record object construction. Set-based columnar engines (DuckDB, DataFusion, Polars) amortise both. Kersten et al. (F20) show neither compiled nor vectorised execution dominates; the real differentiators are memory layout and avoiding per-record objects (OPA's ~20x JSON expansion is the warning sign).
- **Published numbers are claims, not comparisons**: CEL "nanoseconds to microseconds"; datalogic-rs self-reports 8.9 ns geomean; OPA RBAC sample ~45 us/op; Cedar 42.8-80.8x faster than Rego on its own workload.
- **Arithmetic illustration only**: at 5 us/record one core processes 10M records in about 50 s; at 45 us/record, about 7.5 min. A set-based engine should beat both per record, but that is unmeasured. Ordered rules over columns benefit from DataFusion-style mask short-circuiting (evaluate each rule only on still-unmatched rows).
- **Spike before committing**: 10M-100M synthetic MiFIR rows, 50/500/5,000 rules; measure rows/s/core, peak RSS, p99 single-record explain latency, pack hot-swap time. Candidates: DuckDB generated SQL, DataFusion, a CEL row evaluator, ZEN, Drools DMN.

---

## 4. Design implications for our platform

### 4.1 Architecture

1. **Legal Source Registry**: sources, provision paths, ELI identifiers, quoted-text hashes, `retrieved_at`, `status` (in force / amended / applies-from).
2. **Rule Pack**: tables, reason codes, citations, rationales, tests; **data, not code**.
3. **Compiler**: parse, type-check, static analysis (gaps, overlaps, unreachable rules, cost estimate), then an engine-independent IR.
4. **Executors**:
   - **Batch**: IR -> generated SQL on an embedded columnar engine (DuckDB by default; keep it behind an interface so DataFusion or a native executor can replace it).
   - **Scalar reference interpreter**: single-record explain and differential testing. Disagreement between executors fails the build.
5. **Decision log**: append-only records (R4); input snapshot and reference-data versions retained for replay.
6. **External-provider seam**: lets DRR-generated Java (or a vendor) answer the same contract for regimes where DRR is adopted.

Expression language: a **CEL-shaped subset** (same grammar and semantics, non-Turing-complete) plus a vetted built-in function library (LEI mod-97, ISIN check digit, dates, decimals). Packs cannot define functions. Adopt CEL itself only if the spike shows its numeric and date semantics suffice and CEL-AST -> SQL transpilation is practical (my inference; unverified).

### 4.2 Rule-pack packaging

```
mifir.reportability-2.3.0/
  manifest.yaml     # id, semver, jurisdiction, regime, valid{from,to|event}, as_at_field,
                    # engine_min, input_schema_ref+digest, imports[]{pack,digest}, supersedes
  tables/*.yaml|csv # decision tables (analyst-authored)
  reason_codes.yaml
  legal_sources.yaml# registry entries used (ELI, provision paths, text hashes)
  rationales.yaml
  tests/            # golden cases + property tests
  CHECKSUMS         # canonical-form digests
  ATTESTATION       # signed in-toto/DSSE statement: maker, checker, approver, CI run, impact report digest
```

Content-addressed (SHA-256 of canonical form); compiled artefacts cached by `(pack digest, engine version)`. Shared logic lives in `common` packs imported by pinned digest (mirrors DRR's `drr.regulation.common`).

### 4.3 Versioning and effective dating (bitemporal)

- **Valid time**: when the rule is legally applicable (`valid.from/to`, or a pending legal-event trigger). **Knowledge time**: when the pack was approved and activated (from the audit log).
- `resolve(regime, as_at, mode)`: `mode=AS_REPORTED` picks the pack that was actually active (what we told the regulator); `mode=AS_CORRECTED` picks the pack that should have applied given current knowledge.
- **Semver contract**, enforced by impact analysis: PATCH = zero outcome/reason diffs on the replay corpus; MINOR = new reason codes or reductions of UNDETERMINED only; MAJOR = any change of REPORT <-> DO_NOT_REPORT.
- Determinism: no wall-clock, randomness or locale in rules; UTC only; decimal arithmetic; built-ins pinned in `engine_min`.

### 4.4 Decision record

```
{ decision_id, record_key, regime, pack_id, pack_version, pack_digest, as_at, evaluated_at,
  outcome: REPORT | DO_NOT_REPORT | REPORT_BY_OTHER | UNDETERMINED,
  reason_code, rule_ids_hit[], path: [{table, rule_id, row_id}],   // compact; inputs hashed
  citations[], input_digest, executor, engine_version }
```

Batch mode stores rule ids plus bitmaps; "show your working" re-evaluates the single record through the scalar interpreter and renders inputs, conditions and quoted provision text. UNDETERMINED routes to the data-quality queue (R10) instead of silently becoming "do not report".

### 4.5 Testing

- **Golden packs** in DRR style: case = input row + expected outcome/reason/citations, committed, so a change is a reviewable diff. Sources: ESMA/NCA scenarios, regulator Q&A, anonymised real cases.
- **Coverage gates**: every rule row hit by a case; every reason code reachable; every cited provision exercised.
- **Static analysis**: gaps, overlaps, unreachable/shadowed rows (Drools DMN precedent) and cost estimate (cel-go precedent).
- **Differential**: scalar interpreter vs batch executor; optional SMT equivalence between pack versions (Cedar/CEL-Java precedent). **Mutation tests**: flipping a condition must fail a golden case. If CEL semantics are adopted, run the cel-spec conformance corpus.

### 4.6 Change control and hot reload

1. **Maker**: analyst or engineer opens a change (git PR) with tables, citations and rationale.
2. **CI**: schema check, static analysis, golden tests, **impact report**, build, digest.
3. **Checker**: a second, independent person reviews the diff and the impact report. A Compliance approver is required for any MAJOR change.
4. **Signing**: release key held in KMS/HSM and usable only by the pipeline after both approvals. Sigstore keyless is possible but needs the public Fulcio/Rekor unless a private instance is configured (F23).
5. **Promotion**: dev -> shadow/UAT -> prod promotes the **same digest**. Shadow runs a candidate alongside production and logs differences.
6. **Activation** (OPA model): the engine verifies the signature against an out-of-band trust root, validates compatibility, then swaps atomically **between batches**. A failed check keeps the current pack and raises an alert. A batch pins one pack digest end to end.
7. **Emergency path**: single-approver hot fix, auto-flagged for retrospective review within a fixed window.
8. **Rollback** = re-activate the previous signed digest; no rebuild.

### 4.7 Impact analysis (replay a rule change)

1. **Inputs**: baseline pack (active) vs candidate pack; a replay corpus of canonical inputs with reference data as at each trade (columnar snapshots; DuckLake/Iceberg-style time travel or partitioned Parquet).
2. **Run both packs set-based** over the corpus. Join to compute per record: old outcome/reason vs new outcome/reason.
3. **Report**:
   - transition matrix (REPORT -> DO_NOT_REPORT, and so on), counts by rule, reason code, provision, instrument class, entity, desk and period;
   - "reason changed, outcome same" list (affects evidence);
   - drill-down with both decision paths and citations.
4. **Translate to consequences**: newly reportable = potential **missed reports** (late or never reported, back-reporting scope, breach assessment); newly non-reportable but already reported = **over-reporting** (cancellations or error/omission handling).
5. **Forward mode**: run the staged pack on the current open population to size the effect before the activation date.
6. **Gate**: the PR cannot merge until a named approver signs the report; semver class is verified from the diff.
7. **Cost control**: full replay for packs touching exclusion rules; stratified sample plus a rule-hit index for narrow changes.
8. **"As-at" proof**: re-running a past date with the then-active pack must reproduce the stored decisions exactly. Any mismatch is a determinism or tamper alarm.

### 4.8 Safety and phasing

- **Safety**: CEL-shaped expressions only (no loops except bounded comprehensions); compile-time cost ceiling per rule and pack; no I/O, no clock; no raw SQL in packs. DuckDB hardening per F16 plus OS/container isolation. Engineer-lane Starlark/Wasm plugins are off by default, separately signed, fuel- and memory-limited.
- **Phasing**: (1) DSL + scalar interpreter + golden tests + Legal Source Registry; (2) SQL batch executor + differential test + impact-analysis tool; (3) signing, activation, shadow mode; (4) authoring UI / spreadsheet round-trip, optional DMN import/export, DRR external-provider adapter.

---

## 5. Risks and unknowns

- **DRR gaps**: MiFIR not live (revised rules expected 2027); licence wording ("without modifications", indemnity) unreviewed; I could not read the full licence PDF. DRR's exact eligibility/regime-applicability split and any lineage viewer are unverified (L).
- **Applicable-date semantics** (trade vs report vs submission date) must come from Legal.
- **Regulatory moving targets**: EU revised RTS 22 date unknown; UK schema/validation consultation due Oct 2026; ESMA recommendations non-binding; the "not before 1 Oct 2027" claim is unsourced (L).
- **Legal text ingestion**: EUR-Lex pages were not fetchable by our tooling, so a robust acquisition path (Publications Office APIs) and hash-drift monitoring are unverified. Article-level ELI fragment support on EUR-Lex is unverified.
- **Build risk**: no vectorised CEL exists; semantic divergence (NULL, errors, decimals) between the batch and scalar executors is the key technical risk; mitigated by differential testing and conformance corpora.
- **DuckDB**: v2.0 preview (Aug-Sep 2026) may bring breaking changes; one source says the 1.4 LTS end of life is September 2026 while the install page still lists 1.4.5 as LTS (conflict). Pin and test before upgrading.
- **Source reliability**: performance numbers are self-reported (Cedar, datalogic-rs, ZEN). DMN 1.6 "formal" status comes from one OMG page while other OMG/vendor snapshots still show beta. The FCA's MAR 13/14/15 replacement detail is secondary. The RTS 22 Art 2(5) list comes from an FCA-hosted rendering dated 2021.
- **Replay cost and retention**: keeping inputs and reference data for at least 5 years is a storage and data-protection question.
- **Authoring UX** for analysts is the likeliest adoption failure; budget for a table editor and spreadsheet round-trip.
- **Key custody and separation of duties** for pack signing must match the firm's control framework (not researched here).

---

## 6. Source list (accessed 2026-10-08 unless stated)

**DRR / Rune / FINOS**
- ISDA DRR hub: https://www.isda.org/isda-solutions-infohub/isda-digital-regulatory-reporting/
- ISDA press release 7 May 2025 (MiFID/MiFIR + DTCC): https://www.isda.org/a/7xfgE/ISDA-to-Extend-DRR-to-cover-MIFID-MIFIR-Reporting-and-Works-With-DTCC-to-Further-Adoption.pdf
- ISDA DRR licence: https://www.isda.org/isda-solutions-infohub/isda-digital-regulatory-reporting/isda-drr-1/ ; https://www.isda.org/2023/09/19/isda-digital-regulatory-reporting-drr-license/ (Sept 2023)
- DRR docs (v7.0.0): https://drr-docs.isda.org/docs/ ; /docs/using-drr/scope-and-structure/ ; /docs/using-drr/drr-design-principles/ ; /docs/using-drr/drr-namespace-structure/ ; /docs/using-drr/implement-drr/ ; /docs/for-developers/key-concepts/
- FpML news on ISDA DRR webinar (13 May 2026): https://www.fpml.org/latest_news/isda-drr-transforming-regulatory-reporting-through-industry-standards/
- TradeHeader DRR FAQ (vendor blog, 2026-02-10): https://www.tradeheader.com/blog/isda-digital-regulatory-reporting-drr-frequently-asked-questions-faq-for-2026
- Rune docs: https://rune.finos.org/docs/modelling-components/reporting/ ; https://rune.finos.org/docs/get-started/rune-keywords
- Rune DSL repo, releases, PR 1381: https://github.com/finos/rune-dsl ; https://github.com/finos/rune-dsl/releases ; https://github.com/finos/rune-dsl/pull/1381

**Legal sources**
- ESMA ISR MiFIR Art 26: https://www.esma.europa.eu/publications-and-data/interactive-single-rulebook/mifir/article-26-obligation-report-transactions
- FCA Handbook rendering of Reg (EU) 2017/590 Art 2 and Art 3: https://www.handbook.fca.org.uk/techstandards/MIFID-MIFIR/2017/reg_del_2017_590_oj/006.html ; .../007.html
- ESMA Final Report 2 Jul 2026: https://www.esma.europa.eu/sites/default/files/2026-07/ESMA12-1406959660-3235_Final_Report_on_the_Call_for_Evidence_on_a_comprehensive_approach_for_the_simplification_of_financial_transaction_reporting.pdf
- ESMA news 4 May 2026 (interim report): https://www.esma.europa.eu/press-news/esma-news/esma-advances-simplification-eu-reporting-frameworks-funds-and-transactions
- ESMA MiFIR reporting page (validation rules, XML schema v1.1.0): https://www.esma.europa.eu/data-reporting/mifir-reporting
- FCA PS26/15 (3 Aug 2026): https://www.fca.org.uk/publications/policy-statements/ps26-15-improving-uk-transaction-reporting-regime ; secondary: https://www.kaizenreporting.com/fca-ps26-15-mifir-transaction-reporting-reforms/
- Secondary: Mondaq/TRAction (21 Jul 2026): https://www.mondaq.com/uk/commoditiesderivativesstock-exchanges/1820324/are-rolling-spot-fx-and-rolling-spot-precious-metals-reportable-under-mifir ; Linklaters (July 2026): https://financialregulation.linklaters.com/post/102n7wz/esmas-final-report-proposes-annual-net-cost-savings-of-up-to-eur-1-billion-throu
- ELI: https://en.wikipedia.org/wiki/European_Legislation_Identifier ; http://data.europa.eu/eli/reg_del/2017/590/oj (307 to EUR-Lex)

**Engines and languages**
- OMG DMN: https://www.omg.org/spec/DMN ; DMN TCK: https://dmn-tck.github.io/tck/ ; Apache KIE DMN: https://kie.apache.org/drools/dmn/ ; jDMN: https://github.com/goldmansachs/jdmn ; Camunda DMN (hit policies, DRG): https://docs.camunda.org/manual/latest/reference/dmn/decision-table/hit-policy/ , https://docs.camunda.org/manual/latest/reference/dmn/drg/
- GoRules ZEN: https://github.com/gorules/zen ; JDM: https://docs.gorules.io/developers/jdm
- CEL: https://github.com/google/cel-spec ; https://cel.dev/overview/cel-overview ; https://github.com/cel-rust/cel-rust ; K8s cost constants: https://godocs.io/k8s.io/apiserver/pkg/apis/cel
- JSON Logic: https://jsonlogic.com/ ; https://jsonlogic.com/operations.html ; datalogic-rs: https://docs.rs/datalogic-rs
- OPA: https://github.com/open-policy-agent/opa ; https://www.openpolicyagent.org/docs/management-bundles ; https://www.openpolicyagent.org/docs/policy-performance ; https://www.openpolicyagent.org/docs/policy-language ; Apple move (secondary): https://www.heise.de/en/news/Apple-acquires-developer-of-the-Open-Policy-Agent-10574287.html
- Cedar: https://github.com/cedar-policy/cedar ; https://arxiv.org/abs/2403.04651 ; https://aws.amazon.com/blogs/opensource/cedar-joins-cncf-as-a-sandbox-project (2025-12-15)
- Datalog: https://github.com/souffle-lang/souffle ; https://github.com/s-arash/ascent ; https://github.com/ekzhang/crepe ; https://github.com/knowsys/nemo
- Starlark: https://raw.githubusercontent.com/bazelbuild/starlark/master/spec.md ; https://github.com/facebook/starlark-rust
- Sandboxing: https://docs.wasmtime.dev/examples-interrupting-wasm.html ; https://luau.org/sandbox ; https://github.com/extism/extism
- DuckDB: https://duckdb.org/news/ ; https://duckdb.org/install/ ; https://duckdb.org/docs/current/operations_manual/securing_duckdb/overview.html ; https://duckdb.org/docs/current/sql/query_syntax/from.html ; DuckLake: https://ducklake.select/2026/04/13/ducklake-10/
- DataFusion CASE: https://datafusion.apache.org/blog/2026/02/02/datafusion_case/ ; Kersten et al.: https://www.vldb.org/pvldb/vol11/p2209-kersten.pdf
- OpenFisca: https://openfisca.readthedocs.io/en/latest/coding-the-legislation/25_vectorial_computing.html ; https://openfisca.readthedocs.io/en/latest/openfisca-python-api/parameters.html ; Catala: https://www.microsoft.com/en-us/research/publication/catala-a-programming-language-for-the-law/
- Sigstore / in-toto: https://docs.sigstore.dev/cosign/signing/overview/ ; https://github.com/in-toto/attestation/blob/main/spec/README.md

---

## Independent verification (2026-10-08)

Method: independent re-fetch of primary sources on 2026-10-08 (as-of date of every row below). The WebSearch quota was exhausted, so every check is a direct fetch of the cited primary page or document; nothing below rests on search snippets. Twelve claims were re-verified (all load-bearing ones plus the lower-confidence ones that drive design). Not re-verified: F7 (Rune release numbers), F12 DMN/TCK, F15 Cedar, F17 Datalog, F18 Starlark, F19 Wasm/Luau, F20-F21, F23 Sigstore/in-toto. Verdict key: confirmed / corrected / refuted / unverifiable. Markers: **[2025-26]** = introduced or amended in 2025-2026; **[FUTURE]** = scheduled or pending.

| # | Claim (brief ref) | Verdict | Correction / notes | Evidence URL |
|---|---|---|---|---|
| 1 | Rune report = authority + corpus(es) + timing + input type + `when` eligibility rules + output type; eligibility returns Boolean, reporting rules return values; `docReference` with Body/Corpus/Segment, provision, rationale, rationale_author ("DRR Peer Review Group - 03/12/21"); `ruleReference` binds field to rule (F1) | confirmed | Page gives the template `report <Authority> <Corpus...> in <TimingRule> from <InputType> when <EligibilityRule...> with type <ReportType>`. CFTC example is `[docReference CFTC Part45 appendix "1" dataElement "33-35" field "Notional Amount Schedule" rationale ... rationale_author "DRR Peer Review Group - 03/12/21" provision ...]`. `rationale`/`rationale_author` appear only in that example; the page does not define them formally. `ruleReference` supports a nested attribute path, inheritance, and removal with `empty`. | https://rune.finos.org/docs/modelling-components/reporting/ |
| 2 | DRR is consumed as versioned Java artefacts (`com.regnosys.drr`, ISDA Maven repo), report function called on a `ReportableEvent`; docs list 7.0.0 (current), 6.0.0, next; timing informational, generates no code. Test packs: 12 packs, TRANSLATE/ENRICH/REPORT/PROJECTION, 46 report pipelines, 145 report test-pack configs, Maven-profile regeneration, "fully developed" only once verified against test data (F2, F3, F4) | corrected (minor) | Counts confirmed exactly: 12 test packs (rates, credit, commodity, equity, fx, etd, events, exotic, custom-scenarios, delegated-reporting, cftc-event-scenarios, pre-enrich); 46 report pipelines and 145 report test-pack configs on `master` (also 44 projection pipelines / 133 configs, 31 report types, ten regulators, 211 FpML samples). Regeneration: `mvn -pl tests -am clean install -Pupdate-expectations -DskipTests`. Quote confirmed on the design-principles page, not key-concepts: "A reporting rule is considered fully developed only once its logic has been verified against relevant test data." Timing quote ("does not generate any executable code") is on scope-and-structure, not implement-drr. **Corrections:** (a) the Maven example is groupId `com.regnosys.drr`, artifactId `rosetta-source`, version `LATEST` (unpinned in the docs example) plus an ISO 20022 `rosetta-source`; repo URL `https://europe-west1-maven.pkg.dev/production-208613/isda-maven`. (b) The full example does NOT call the report function on a `ReportableEvent`: `ReportableEvent` plus a `ReportingSide` go into `Create_TransactionReportInstruction`, and the resulting `TransactionReportInstruction` is passed to e.g. `CFTCPart45ReportFunction.evaluate(...)`; a shorter snippet on the same page shows `evaluate(reportableEvent)`, so the page is internally inconsistent. This supports the brief's low-confidence inference that regime applicability/side is supplied as enriched input. Docs versions 7.0.0 / 6.0.0 / next confirmed. | https://drr-docs.isda.org/docs/using-drr/implement-drr/ ; https://drr-docs.isda.org/docs/for-developers/key-concepts/ ; https://drr-docs.isda.org/docs/using-drr/scope-and-structure/ ; https://drr-docs.isda.org/docs/using-drr/drr-design-principles/ |
| 3 | DRR does not yet cover MiFIR; ISDA announced 7 May 2025 an EU/UK MiFID/MiFIR extension with DTCC GTR ARM, revised MiFIR reporting expected over 2027, DTCC UK submission from Q1 2026 subject to approvals; hub lists EU/UK MiFIR, SFTR, Switzerland as in progress; live regimes incl. CSA (25 Jul 2025), HK (29 Sep 2025); ISO 20022 or DTCC Harmonized XML output (F5) | corrected | Press release text read in full (PDF) and matches: dated 7 May 2025; "anticipated to be implemented in the EU and the UK over the course of 2027"; DTCC "from the first quarter of 2026, subject to regulatory approvals". Hub confirmed: CFTC (launched ahead of the 5 Dec 2022 rule changes), Australia 21 Oct 2024, Canada 25 Jul 2025 (DTCC Harmonized XML), EU EMIR 29 Apr 2024, Hong Kong 29 Sep 2025, Japan 1 Apr 2024, Singapore 21 Oct 2024, UK EMIR 30 Sep 2024; "in progress": EU and UK MiFIR, SFTR, Switzerland (no dates); ISO 20022 for the other jurisdictions. The hub names jurisdictions, not regulators (the brief's ASIC/MAS/JFSA/CSA labels are the researcher's mapping). **Corrections / additions:** (a) The DRR 7.0.0 key-concepts page shows a pipeline named "ESMA / MIFIR RTS22" ("a single report pipeline joined to ten test packs covering 186 samples"), so the model already contains an RTS 22 report; its maturity is not stated and the source repo (`github.com/rosetta-models/digital-regulatory-reporting`) returned 404 to us. "Does not yet cover MiFIR" should read: the hub lists MiFIR as in progress, but an RTS 22 pipeline with 186 samples exists in the 7.0.0 docs (status unverified). (b) The "over 2027" expectation is a May 2025 forecast and is now stale for the UK: FCA PS26/15 (3 Aug 2026) sets the UK regime for 3 Apr 2028 **[2025-26][FUTURE]**. Whether DTCC's UK ARM actually launched in Q1 2026 was not verified. (c) Newer hub items not in the brief: South Africa (Strate rules-as-code, reporting not commenced) and Canadian OSC/CSA Technical Manual updates (publication est. Aug 2026, UAT from mid-Apr 2027, "subject to change") **[FUTURE]**. | https://www.isda.org/a/7xfgE/ISDA-to-Extend-DRR-to-cover-MIFID-MIFIR-Reporting-and-Works-With-DTCC-to-Further-Adoption.pdf ; https://www.isda.org/isda-solutions-infohub/isda-digital-regulatory-reporting/ ; https://drr-docs.isda.org/docs/for-developers/key-concepts/ |
| 4 | ISDA DRR License v1.0 (Sept 2023): royalty-free non-exclusive, "without modifications and solely as part of a Larger Work", indemnity for Larger Work distribution, silent on commercial use, NY law; needs legal review (F6) | corrected | Grant, indemnity and NY-law terms confirmed by reading the licence PDF itself. **Corrections:** (a) Two ISDA documents exist and the brief merges them. "ISDA DRR License Version 1.0" is effective 7 Nov 2022 (c) 2021-2022 (isda-drr-1 page). The PDF linked from the 19 Sep 2023 post is titled "ISDA DRR License", effective 19 Sep 2023, (c) 2021-2023, and prints no version number. Section 2 grant (same text in both): "...use, reproduce, modify, display, perform, and distribute the ISDA DRR (or portions thereof) without modifications and solely as part of a Larger Work". (b) Indemnity (s4(b)) is broader than "distribution": "distribution, purchase, sale, reproduction, display or use of Your Larger Work". (c) Other terms: s6 perpetual licence for the specifications used; s5(b) forbids using "ISDA"/"ISDA DRR" as a trademark or implying endorsement; s8 terminates after a breach uncured for 30 days; s10 New York law, binding JAMS arbitration in NYC, SDNY/NY State courts. "ISDA DRR" is defined as the "methodology and documentation" at the DRR portal, not expressly the code. (d) "Silent on commercial use" holds for the PDF ("commercial" occurs only in the s9 damages exclusion). **But** the DRR docs home page (Governance) states "This product is distributed under a proprietary license ... For non-commercial use, this product is available for free" and links to `LICENSE.md` in `rosetta-models/digital-regulatory-reporting`, which returned 404 to unauthenticated fetch. The code licence may therefore differ from the ISDA PDF and may restrict commercial use. Not re-verified: the third-party "not open source" FAQ. | https://www.isda.org/a/PpugE/ISDA-DRR-License-September-2023.pdf ; https://www.isda.org/isda-solutions-infohub/isda-digital-regulatory-reporting/isda-drr-1/ ; https://www.isda.org/2023/09/19/isda-digital-regulatory-reporting-drr-license/ ; https://drr-docs.isda.org/docs/ |
| 5 | MiFIR Art 26 as amended by Reg (EU) 2024/791 (new 26(2)(d); venue-only condition for other OTC derivatives) applies only when revised RTS 22 applies; until then consolidated Art 26 of 09/01/2024 and current RTS 22; Art 26(11) ESMA report by 29 Mar 2028; vendor claim of no RTS 22 amendment before 1 Oct 2027 unsourced (F8) | confirmed (one stale label) | ESMA Interactive Single Rulebook note: "The amendments to Article 26 will apply when the revised version of Commission Delegated Regulation [2017/590] applies"; until then the consolidated version of 09/01/2024 and the current RTS 22 apply. 26(2)(a) keeps OTC derivatives other than those in Art 8a(2) in scope only if traded on a trading venue; 26(2)(d) covers Art 8a(2) OTC derivatives wherever executed; 26(11) text "By 29 March 2028, ESMA shall submit to the Commission a report" confirmed (also reproduced in Annex I of the ESMA Final Report). The ESMA page itself does not name Reg 2024/791 (the Mondaq article links it as the "MiFIR Review"). **Corrections:** (a) The Art 26(11) report is no longer purely future: the ESMA Final Report of 2 Jul 2026 says it is published "in line with its mandate under Article 26(11) of MiFIR", so the **[FUTURE]** label applies only to the legislative follow-up. (b) The "not before 1 October 2027" statement is in a Mondaq/TRAction article dated 21 Jul 2026 (author Quinn Perrott, TRAction contributor) as "The European Commission has confirmed it will not adopt amendments to RTS 22 before 1 October 2027"; that sentence carries no hyperlink and the article's other links (MiFIR, MiFIR Review, RTS 22, ESMA ToTV opinion, FCA Market Watch 74) do not support it. Remains **unverifiable**; keep activation event-triggered. | https://www.esma.europa.eu/publications-and-data/interactive-single-rulebook/mifir/article-26-obligation-report-transactions ; https://www.mondaq.com/uk/commoditiesderivativesstock-exchanges/1820324/are-rolling-spot-fx-and-rolling-spot-precious-metals-reportable-under-mifir |
| 6 | Current RTS 22 Art 2(5) excludes (a)-(o) (SFTs, clearing/settlement-only, novation/assignment, portfolio compression, fund units, employee plans EUR 1,000/500, exchange/tender offers, collateral); Art 3(2) transmitted order not deemed execution; source is FCA-hosted EU text dated 01/01/2021 (F9) | confirmed (date corrected) | All of (a)-(o) match the FCA Handbook rendering: (a) SFTs per Art 3(11) of Reg 2015/2365; (b) clearing/settlement-only; (c) net obligations carried forward; (d) custodial; (e) post-trade assignment or novation; (f) portfolio compression; (g) administrator creating/redeeming fund units; (h) embedded rights/conversion; (i) predetermined or mandatory events with no investment decision (not for IPOs, secondary offerings, placings, debt issuance); (j) notional changes; (k) index/basket composition; (l) DRIP; (m) employee share incentive plans etc. only if four conditions hold (predetermined published dates, no unilateral variation, at least ten business days between decision and execution, value cap EUR 1,000 one-off per investor per instrument or EUR 500 per calendar month cumulative); (n) exchange/tender offers on bonds/securitised debt; (o) collateral transfers. Art 3(2) confirmed: a firm transmitting an order per Art 4 "shall not be deemed to have executed a transaction". **Corrections:** the Art 2 page is dated 01/04/2022 ("was last updated on"); 01/01/2021 is the date of the Art 3 page. This is the pre-amendment text as hosted by the FCA; EUR-Lex consolidated text could not be read (EUR-Lex pages returned only Official Journal navigation to our tooling), so later amendments are not excluded. Treat (m) as a four-condition test, not just the caps, when writing rules. | https://www.handbook.fca.org.uk/techstandards/MIFID-MIFIR/2017/reg_del_2017_590_oj/006.html ; https://www.handbook.fca.org.uk/techstandards/MIFID-MIFIR/2017/reg_del_2017_590_oj/007.html |
| 7 | ESMA Final Report (2 Jul 2026, ESMA12-1406959660-3235): non-binding recommendations to the EU legislator; "report once" with integrated solution toward H2 2031; back-reporting 5 to 3 years (override up to 5); targeted Level 2 changes to RTS 22 Art 2(5)(g), (i), (n) and employee share plans; Level 1 needed for the long-term model (F10) | confirmed with nuance | Read the PDF text. Date 2 Jul 2026 and reference number confirmed. Long-term "report once" is Scenario 2a and needs Level 1 amendments to MiFIR, EMIR and SFTR. "Toward H2 2031" (para 154) is conditional: Level 1 changes by mid-2028 (para 152), integrated Level 2 template by mid-2029, IT build to mid-2030, plus 12-18 months participant lead time; ESMA flags the Level 1 process as the main uncertainty. Back-reporting (para 126, Table 5): default 5 to 3 years in MiFIR, NCAs may request beyond three years up to five; the 5-year record-keeping obligation is maintained. RTS 22 Art 2(5) (para 127, Table 6): (g) extended to fund managers, (i) and (n) expanded and consolidated into a corporate-actions exemption (IPOs, takeover bids, placings, debt issuance stay reportable), employee share plans to get a single threshold (illustrative "2,000 cumulative on a monthly basis", currency not stated) with aggregation under 2(5)(m). **Correction:** ESMA classifies back-reporting (#3) and the RTS 22 2(5) exemptions (#4) as independent measures that do NOT need Level 1 changes (Level 2/3, within ESMA/Commission remit), unlike delegated reporting and intragroup exemption (Level 1) and the long-term model. So "recommendations to the EU legislator, not binding" is only partly right: the exclusion-rule changes are the nearer-term, lower-hurdle items. No adoption date is given for them. | https://www.esma.europa.eu/sites/default/files/2026-07/ESMA12-1406959660-3235_Final_Report_on_the_Call_for_Evidence_on_a_comprehensive_approach_for_the_simplification_of_financial_transaction_reporting.pdf |
| 8 | FCA PS26/15 (3 Aug 2026): new UK regime in force 3 Apr 2028; fields 65 to 52; about 7m EU-only instruments and FX derivatives leave scope; back-reporting 5 to 3 years; flexible supervision from 3 Aug 2026; draft schema/validation-rule consultation Oct 2026; rules in MAR 14 with testing, reconciliation, auditable incident record (F11) | confirmed | Verified against the FCA page and the PS26/15 PDF itself (primary). Published 03/08/2026. Para 1.19: new regime "will come into force on 3 April 2028", flexible supervisory approach "from 3 August 2026 until this date". Para 1.8: fields "from 65 to 52"; "7 million financial instruments which are only tradeable on" EU trading venues; FX derivatives removed (over 400 UK firms); default back-reporting "from 5 to 3 years". Para 1.18: FCA "will publish a draft schema, validation rules and new guidelines in October 2026" (new Transaction Reporting User Pack); the consultation also covers transitional provisions and Handbook consequentials. MAR 14 confirmed as the Handbook chapter (e.g. MAR 14.2.4R exclusions, MAR 14.8 arrangements); the rule text requires "regular testing of their reporting process and regular reconciliation of their records", and a transaction reporting incident management framework (triage, assess, manage errors and omissions). The words "auditable record" come only from the Kaizen secondary source and were not found in the PS passages checked. Brief's confidence H holds; the MAR 14 detail can be upgraded from M to H. **[2025-26][FUTURE]** | https://www.fca.org.uk/publications/policy-statements/ps26-15-improving-uk-transaction-reporting-regime ; https://www.fca.org.uk/publication/policy/ps26-15.pdf ; https://www.kaizenreporting.com/fca-ps26-15-mifir-transaction-reporting-reforms/ |
| 9 | CEL non-Turing-complete, mutation-free, linear-time, compile-once/evaluate-many, serialisable type-checked AST; cel-go static cost estimation; Kubernetes budgets 1,000,000 per call and 10,000,000 per resource; no vectorised CEL found; Z3 CEL Formal Verification Framework in the CEL-Java repo (announced 2026-08) (F13) | confirmed | cel-spec: "CEL evaluates in linear time, is mutation free, and not Turing-complete"; it recommends serialising the type-checked expression as a protobuf; compile can happen ahead of time (control plane) or just before evaluation (the exact phrase "compile-once evaluate-many" is not in the README, the behaviour is). cel-go: `checker.Cost(checked, estimator, ...)` with `CostEstimator`, `CostEstimate` (Min/Max). Kubernetes source `apiserver/pkg/apis/cel/config.go`: `PerCallLimit = 1000000` ("roughly 0.1 second for each expression validation call") and `RuntimeCELCostBudget = 10000000` (per ValidatingAdmissionPolicyBinding or CustomResource, "roughly 1 seconds"); also `RuntimeCELCostBudgetMatchConditions = 2500000`. Verifier: exists at `github.com/cel-expr/cel-java/tree/main/verifier` (note: the `google/cel-java` page did not describe it); README: Z3 SMT solver, "Logical Equivalence", counterexample/witness generation, some stdlib functions treated as uninterpreted (results can be INCONCLUSIVE). No date is shown, so the "announced 2026-08" date is **unverified**. The negative claim (no vectorised CEL implementation) cannot be confirmed by fetch. | https://github.com/google/cel-spec ; https://pkg.go.dev/github.com/google/cel-go/checker ; https://raw.githubusercontent.com/kubernetes/kubernetes/master/staging/src/k8s.io/apiserver/pkg/apis/cel/config.go ; https://github.com/cel-expr/cel-java/tree/main/verifier |
| 10 | OPA: bundle manifest revision and roots; signed bundles via `.signatures.json` JWT with out-of-band key; failed verification keeps current bundle; ~20x memory for raw JSON, ~45 us/op RBAC sample; CNCF graduated, Apache-2.0; core maintainers joined Apple Aug 2025; v1.21.1 dated 2026-09-29 (F14) | confirmed (maintainer names softened) | Management-bundles docs: `revision` optional (example: Git commit hash); `roots` optional, default `[""]`, overlapping roots rejected; `.signatures.json` is an array of JWTs listing file names/hashes; keys configured out of band (`--verification-key` PEM or `keys` config, `kid` selects); OPA activates a bundle only if verification succeeds, otherwise "continues using its existing bundle"; `persist: true` stores activated bundles on disk. Policy-performance docs: raw JSON "approximately 20x more memory" than a compact serialised form; `opa bench` RBAC example prints 45032 ns/op (an illustrative doc output, not a guarantee). Releases page: v1.21.1 "Latest", 29 Sep 2026. Apple: OPA blog (20 Aug 2025) says "the creators of Open Policy Agent (along with many team members from Styra) have joined Apple" with "no changes to the project governance or licensing", CNCF graduated; heise (22 Aug 2025) names only Tim Hinrichs, adds Apache-2.0 and CNCF graduation in 2021. The text does not name Koponen and Sandall individually (inferred from the post title "Teemu, Tim and Torin"). Several Styra repos (EOPA, OPA Control Plane, SDKs, Regal) were proposed for the CNCF OPA organisation. | https://www.openpolicyagent.org/docs/management-bundles ; https://www.openpolicyagent.org/docs/policy-performance ; https://github.com/open-policy-agent/opa/releases ; https://www.openpolicyagent.org/blog/note-from-teemu-tim-and-torin-to-the-open-policy-agent-community-2dbbfe494371 ; https://www.heise.de/en/news/Apple-acquires-developer-of-the-Open-Policy-Agent-10574287.html |
| 11 | DuckDB (MIT): 1.5.6 current (2026-09-28), 1.4.5 LTS, v2.0 only preview; sandbox settings are defence-in-depth, not a substitute for OS isolation; ASOF joins. DuckLake v1.0 (2026-04-13, DuckDB 1.5.2+), SQL catalog (SQLite/PostgreSQL/DuckDB), Parquet, snapshots/time travel, backward-compatibility guarantee (F16) | confirmed, with new context | Install page: current stable 1.5.6, LTS 1.4.5, preview "2.0.0-dev" (less suitable for production). News page: "Announcing DuckDB 1.5.6" 2026-09-28; 1.4.5 LTS announced 2026-06-17; v2.0 preview posts 2026-08-17, 2026-08-20, 2026-09-02; no post announces 1.4 LTS end of life (the brief's conflicting "September 2026" EOL claim stays unverified). Security page lists `enable_external_access`, `allowed_directories`/`allowed_paths`, `disabled_filesystems`, `memory_limit`, `threads`, `max_temp_directory_size`, `lock_configuration`/`allowed_configs`, `autoload_known_extensions`/`autoinstall_known_extensions`, `allow_community_extensions`, safe mode; says they "are not a substitute for proper sandboxing" and cannot give complete protection. DuckLake: released 2026-04-13, described as "production-ready ... with guaranteed backward-compatibility", extension available in DuckDB v1.5.2 (the page states availability, not a "1.5.2+" floor), MIT licence; site lists SQLite, PostgreSQL, DuckDB (and also MySQL) catalogs, Parquet data, snapshots and time-travel queries. **New, not in the brief:** on 2026-08-26 DuckDB announced "DuckLabs to Join AWS, Projects to Remain Open Source": DuckLabs becomes an AWS subsidiary (effective early Sep 2026 per the post); DuckDB, DuckLake and extensions stay MIT; stewardship by the non-profit DuckDB Foundation with a stakeholder advisory board; no change to roadmap, licensing or governance announced **[2025-26]**. ASOF join support was not re-fetched. | https://duckdb.org/install/ ; https://duckdb.org/news/ ; https://duckdb.org/docs/current/operations_manual/securing_duckdb/overview.html ; https://duckdb.org/2026/08/26/ducklabs-to-join-aws.html ; https://ducklake.select/2026/04/13/ducklake-10/ ; https://ducklake.select/ |
| 12 | ELI template `http://data.europa.eu/eli/{type}/{year}/{number}/oj` with optional point-in-time, version, subdivision; MiFIR `/eli/reg/2014/600/oj`; `/eli/reg_del/2017/590/oj` redirects (HTTP 307) to EUR-Lex; ESMA ISR exposes article-level URLs (F22) | unverifiable (partly confirmed) | Confirmed: `http://data.europa.eu/eli/reg_del/2017/590/oj` returns 307 Temporary Redirect to `https://eur-lex.europa.eu/eli/reg_del/2017/590/oj`; the ESMA ISR Art 26 page is addressable at an article-level URL. Not confirmed: the EU-specific template and the "subdivision" component. Wikipedia's generic ELI page says "All the components are optional" and unordered, lists point in time, version, language and "Level 1 and deeper" (its only EU example is `dir/2020/1057/oj`); the Publications Office ELI page only says components can be arranged in any order and points to a PDF. EUR-Lex pages returned only Official Journal navigation, so the primary EU template, `/reg/2014/600/oj` and fragment (article-level ELI) support remain unverified. Keep confidence M/L. | https://en.wikipedia.org/wiki/European_Legislation_Identifier ; http://data.europa.eu/eli/reg_del/2017/590/oj ; https://op.europa.eu/en/web/eu-vocabularies/eli |

### Corrections that would change a design decision

1. **DRR licence is not settled and may restrict commercial use (row 4).** The DRR docs say the product is proprietary and free only for non-commercial use, and the licence they link (`rosetta-models/digital-regulatory-reporting` `LICENSE.md`) could not be read. The ISDA PDF grants use only "without modifications and solely as part of a Larger Work" and includes an uncapped indemnity. Do not plan to fork, adapt or embed DRR rule text or code until Legal has read the actual repo licence. Keep the DRR adapter as an optional later phase (phase 4), not a dependency of the core.
2. **DRR and MiFIR coverage is murkier than "not covered" (row 3).** The 7.0.0 docs already contain an "ESMA / MIFIR RTS22" report pipeline with ten test packs and 186 samples. If the licence allows, those samples are a ready-made differential/oracle corpus for our MiFIR pack; maturity must be confirmed with ISDA. The "revised MiFIR over 2027" expectation (May 2025) is stale for the UK (3 Apr 2028), so do not schedule the external-provider seam around 2027.
3. **RTS 22 Art 2(5) and the back-reporting horizon are Level 2/3 measures (row 7).** They do not wait for the long-term Level 1 "report once" timeline (H2 2031 at best, conditional on Level 1 by mid-2028). Exclusion rules are the most volatile content; plan for pending packs for Art 2(5)(g), (i), (m), (n) changes with unknown dates. The 5-year record-keeping obligation is explicitly maintained, so the replay-retention floor of 5 years in the brief stands.
4. **The EU activation trigger stays event-based (rows 5 and 7).** The Art 26 amendments still apply only when the revised RTS 22 applies; the "not before 1 Oct 2027" claim has no checkable source. The Art 26(11) report was delivered early (2 Jul 2026), so only the legislative follow-up is pending.
5. **Rule shape for (m) must be multi-condition (row 6).** Art 2(5)(m) has four cumulative conditions (published dates, no unilateral variation, 10-business-day gap, value caps) and (i) has an IPO/placing/debt-issuance carve-back; the sample pack in section 3.4 and the exclusion test cases should model these as structured conditions with citations per condition, not as one exclusion row.
6. **Executor vendor risk (row 11).** DuckLabs is joining AWS (effective early Sep 2026). MIT licensing and the Foundation stewardship are unchanged, so the DuckDB-by-default choice stands, but the existing advice to keep the executor behind an interface should be treated as required, not optional. The 1.4 LTS end-of-life date is still unverified; pin the version and re-check before adopting.
7. **Do not rely on ELI fragments (row 12).** Article-level ELI support and the EU ELI template are unverified; keep the Legal Source Registry's own provision-path scheme and quote hashes as the primary key, and treat ELI as a best-effort pointer.
8. **Minor, no design change:** the DRR call path is ReportableEvent plus reporting side, enriched to a TransactionReportInstruction, then the report function (this reinforces the brief's input-enrichment assumption); the FCA's MAR 14 testing/reconciliation/incident-framework duties are now primary-verified and can be cited as requirements for the pack test and decision-log design.
