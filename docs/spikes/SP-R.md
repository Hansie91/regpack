# SP-R report
spike: SP-R
status: PASS
scope: FULL
evidence_level: RAW
criteria_digest: d85eca736beb3ba4826bdbd6b160bba9e40cd838f7227b41c97140730d5b9447
source_git_sha: 2a6247c52669586a8b1a54152d1e798a7a67b16e
runs: 1 (vmCount 1)
time_box: used 105 of 480 minutes, hit: no

## Criteria

| Id | Verdict | Basis | Lower | Point | Upper | Threshold | Margin | Reason |
|---|---|---|---|---|---|---|---|---|
| R-1a | PASS | DERIVED | - | - | - | at most 0 | 0 | observed 0, at most 0 required |
| R-1b | PASS | DERIVED | - | - | - | at most 0 | 0 | observed 0, at most 0 required |
| R-2a | PASS (single VM) | MEASURED | 27250.390514 | 27795.748758 | 28215.246591 | at least 10000 | 0.295436678 | lower bound 27250.390513976 is at least 12954.366780000 (threshold 10000, margin 0.295436678) |
| R-2b | FAIL | MEASURED | 2819.484188 | 2835.69885 | 2892.872776 | at least 10000 | 0.295436678 | upper bound 2892.872775795 is below 7719.404714894 (threshold 10000, margin 0.295436678) |
| R-3 | PASS | MEASURED | - | - | - | - | 0 | recorded, 7 observations; value 55859.969050994 records/s |
| R-4 | PASS (single VM) | MEASURED | 0.037518 | 0.044853 | 0.060081 | at most 100 | 0.1 | upper bound 0.060081000 is at most 90.909090909 (threshold 100, margin 0.100000000) |
| R-5 | PASS | DERIVED | - | - | - | at most 0 | 0 | observed 0, at most 0 required |
| R-6a | PASS | DERIVED | - | - | - | at most 0 | 0 | observed 0, at most 0 required |
| R-6b | PASS | DERIVED | - | - | - | at most 0 | 0 | observed 0, at most 0 required |
| R-7 | PASS | MODELLED | - | - | - | at most 0 | 0 | observed 0, at most 0 required |
| R-8 | PASS | DERIVED | - | - | - | at most 0 | 0 | observed 0, at most 0 required |

## Warnings

- forks share one VM, bounds are optimistic

## Decisions

- budget.cel.cleanRecords: 1000000
- budget.cel.maxObservedCleanUnits: 2355
- budget.cel.staticWorstCaseUnits: 116275
- budget.cel.violations: 0
- budget.inhouse.cleanRecords: 1000000
- budget.inhouse.maxObservedCleanUnits: 1600
- budget.inhouse.staticWorstCaseUnits: 3433
- budget.inhouse.violations: 0
- budget.perRecordUnits: 6866
- budget.perRuleCeilingUnits: 64
- decide.usPerRecord.cel.200: 170.907
- decide.usPerRecord.cel.50: 41.9
- decide.usPerRecord.cel.500: 499.034
- decide.usPerRecord.cel.5000: 10496.912
- decide.usPerRecord.inhouse.200: 17.902
- decide.usPerRecord.inhouse.50: 4.58
- decide.usPerRecord.inhouse.500: 47.109
- decide.usPerRecord.inhouse.5000: 620.302
- decide.usPerRecordWorst: 17.902
- determinism.cel.inputs: 100
- determinism.cel.runsPerInput: 1000
- determinism.inhouse.inputs: 100
- determinism.inhouse.runsPerInput: 1000
- diff.inhouseVsCel.S1: 0
- diff.inhouseVsCel.S2: 0
- diff.records: 100000
- diff.skeleton: 0
- executorGate: "PASS"
- executorGateReadings: {"physCoreS2":55591.498,"t1S2":55859.969050994,"t4S2":27795.748757954}
- explain.tier1P99Ms: 0.045
- expressionLayer: "INHOUSE"
- expressionLayerReason: "the in-house layer passes R-1a, R-2a and R-6a; cel-java does not"
- hotswap.batchRecords: 1000
- hotswap.cel.200.lostRecords: 0
- hotswap.cel.200.mixedPackBatches: 0
- hotswap.cel.5000.lostRecords: 0
- hotswap.cel.5000.mixedPackBatches: 0
- hotswap.evaluatorThreads: 3
- hotswap.inhouse.200.lostRecords: 0
- hotswap.inhouse.200.mixedPackBatches: 0
- hotswap.inhouse.5000.lostRecords: 0
- hotswap.inhouse.5000.mixedPackBatches: 0
- hotswap.p99Ms.200: 50.894
- hotswap.p99Ms.5000: 865.243
- hotswap.swapsPerRun: 20
- poison.assumed.backgroundRate: "1e-4"
- poison.assumed.burst: "5000 consecutive records at 5%, once a day (informational)"
- poison.assumed.defectiveRate: "0.20"
- poison.assumed.recordsPerDay: 50000000
- poison.clusteredFalseOpenPerDay: 23.959226848
- poison.detectionProbability: 0.994343639
- poison.falseOpenPerDay: 0.000022945408
- poison.m: 50
- poison.n: 4
- vectors.cel.fail: 4
- vectors.cel.functional: 1
- vectors.cel.notPass: \["D13 DECIDE FUNCTIONAL via 'decEq(decAdd(dec(1), dec('0.5')), dec('1.5'))': Ok(T)","A04 DECIDE FAIL Ok(F)","A07 DECIDE FAIL Error(ABSENT)","E06 DECIDE FAIL Ok(T)","L01 DECIDE FAIL CompileError(undeclared variable x)"\]
- vectors.cel.pass: 42
- vectors.cel.unsupported: 0
- vectors.inhouse.fail: 1
- vectors.inhouse.functional: 0
- vectors.inhouse.notPass: \["L01 DECIDE FAIL CompileError(loop macro all is not supported)"\]
- vectors.inhouse.pass: 46
- vectors.inhouse.unsupported: 0

## Open

- DECIDE D13: in-house PASS (Ok(T)), cel-java FUNCTIONAL (via 'decEq(decAdd(dec(1), dec('0.5')), dec('1.5'))': Ok(T))
- DECIDE A04: in-house PASS (Error(ABSENT)), cel-java FAIL (Ok(F))
- DECIDE A07: in-house PASS (Ok(F)), cel-java FAIL (Error(ABSENT))
- DECIDE E06: in-house PASS (Error(DIV\_ZERO)), cel-java FAIL (Ok(T))
- DECIDE S07: in-house PASS (Ok(T)), cel-java PASS (Ok(T))
- DECIDE L01: in-house FAIL (CompileError(loop macro all is not supported)), cel-java FAIL (CompileError(undeclared variable x))

## Environment

- CPU model: Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz
- vCPUs: 4
- Physical cores: 2
- Memory (bytes): 16765378560
- Image version: 20261004.327.1

P-5 steal discard observable: no

## Runs

| Run | Attempt | Part | Git SHA | CPU model | Image |
|---|---|---|---|---|---|
| 38094901948 | 1 | all | 2a6247c52669586a8b1a54152d1e798a7a67b16e | Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz | 20261004.327.1 |

Git SHAs of the pooled runs: 2a6247c52669586a8b1a54152d1e798a7a67b16e

---

## Interpretation (hand-written; the part above the line is generated and recomputed)

Run 38094901948, one dispatch (attempt 1, nothing re-run), one VM (Intel Xeon Platinum 8370C, 4 vCPU = 2 cores, image 20261004.327.1). The run job took 81 minutes of runner time against a 320-minute deadline; the "105 of 480 minutes" in the header is the controller's active working time on SP-R at the moment of import (117 when the ledger was stopped), not runner time.

**How to read the status line.** The two expression layers are alternatives: the criteria carry a group (`layer:inhouse`, `layer:cel`) and the spike is PASS when one layer passes every required criterion of its group and every ungrouped required criterion passes. The PASS above is therefore the in-house layer passing; the required criterion R-2b is a FAIL and belongs to the `cel-java` group. It is not a statement that everything passed.

**What the numbers say**

- **`cel-java` 0.14.0 as wrapped by the spike's adapter** passes all 38 MUST vectors (R-1b PASS). The 47 vectors were authored by the controller together with the design of the in-house layer, in one commit before the run, and were not changed after authoring. They are not a neutral oracle: they draw on the spec text (6.2, I3, 6.6, 7.3, 6.8) where it speaks, but several expectations are the spike's own design and not derived from it, among them D07 (1/3 gives ERR:NON_TERMINATING), D09 (`string(dec('12.50'))` is '12.5'), D11 (a 38-digit scale limit), T03 and E01 (nanosecond input, the 64-bit integer range), T06 and the names of the spike's library functions, the exact error taxonomy (ERR:ABSENT, RANGE, SCALE and so on, where the spec says only that evaluation errors yield UNDETERMINED) and the cost-model vectors C01 to C03 (cost model v1). R-1a and R-1b therefore say that each layer matches the spike's expectations, not that it conforms to the specification. The cost units of 50 records, each run against both generated 200-rule packs (100 record-pack pairs), did not differ over 1,000 repeats (R-6b PASS); the adapter charges cost statically, so this covers cost units within one JVM and says nothing about outcomes. It passes only under the adapter's constraints (decimals as an opaque type with a specialised equality; double, null and unsigned literals rejected; comprehensions off) and differs from the in-house layer on the DECIDE vectors A04, A07 and E06 and passes D13 only in function syntax (below). **It fails the throughput gate (R-2b FAIL):** 2,836 records/s per thread with four threads busy (one-sided 95% order-statistic bounds of the median of 15 forks: 2,819 and 2,893) against the 10,000 gate, which is 3.5 times too slow. The other two readings are also below the gate; they are point estimates without bounds: about 5,851 for one thread alone (170.9 microseconds per record at 200 rules, `decide.usPerRecord.cel.200`, 7 one-thread forks) and about 5,671 per physical core (derived here: 2,835.7 x 4 threads / 2 cores; the report prints these two readings only for the chosen layer).
- **`cel-java` as adapted cannot meter what an expression builds.** The adapter charges the node count of the checked expression once, plus the size of the bound variables; nothing the expression itself constructs (string or list concatenation chains) is metered, so the deterministic per-record budget of spec 6.6 can be bypassed. In an independent probe during the final review (not part of the sealed results), `size(s + s + ... + s)` with 201 terms over a 100,000-character string and a budget of 10,000 units returned `Error[BUDGET]` in 57 ms from the in-house layer and `Ok(true)` after 12.6 s and about 20 million characters of allocation from the `cel-java` layer. Vector C03 (a chain of 3,000 `+` with a ceiling of 1,000, expecting COMPILE_ERROR) passes for `cel-java` only because its parser has a recursion limit of 250, not because the per-rule ceiling works; any left-associative chain of more than about 250 operators does not compile at any ceiling. The vectors have no case with an in-expression amplification below that limit, so R-1b does not show that `cel-java` can enforce the budget.
- **What the `cel-java` figure measures.** The adapter builds a fresh map activation for every evaluation, that is for every rule row, and converts every variable that row names (`CelJavaExpression.evaluate`), while the in-house evaluator reads lazily from per-record cached bindings. The 9.8 times gap between the layers is therefore a measure of the port plus the adapter as written, not of the `cel-java` engine; a lazy, resolver-based adapter was not tried and could narrow the gap by an amount that was not measured. If the owner rules on A04, A07 and E06 in favour of `cel-java`, such a probe belongs before the freeze.
- **The in-house layer** passes all 38 MUST vectors (R-1a PASS), has no cost-unit differences over the same repeats (R-6a PASS), and **passes the gate (R-2a PASS, single VM):** 27,796 records/s per thread with four threads busy (one-sided 95% bounds 27,250 and 28,215). The pass line including the noise margin is 12,954, so the lower bound is 2.1 times the pass line and 2.7 times the bare 10,000 gate. Two readings are independent measurements: four busy threads (27,796) and one thread alone (55,860); the per-core figure (55,591) is derived from the first. Four busy threads give 1.99 times one thread, so the two hyperthreads of a core add no throughput on this workload and the per-vCPU reading is the conservative one. All three readings clear 10,000, so the `executorGate` verdict does not depend on the reading.
- **Expression layer.** The importer derives `INHOUSE` mechanically (it prefers `cel-java` when `cel-java` passes, and falls to the in-house layer when it does not). The cause is throughput, not semantic inadequacy of `cel-java`. Spec 6.6 names a minimal in-house subset as the alternative to `cel-java`, and spec 16 gives "faster executor or in-house expression subset" as the SP-R fail outcome; this spike did not measure `cel-java` with a faster executor or a lazy adapter. The choice is therefore the owner's, recorded at the freeze; if the owner records the in-house layer, the Expressions row of spec 5.3 resolves to its stated fallback.
- **Applicable flip condition.** Spec 5.3: "Spike SP-R fails: add faster executors in this order: closure-compiled scalar, then chunked columnar". This report does not trigger it for the in-house layer (the gate is met at 200 rules, shape S2, one VM, interpreter only). It would apply if the owner chose `cel-java` as measured (R-2b FAIL), or if a measurement on the target hardware or at real pack sizes puts the in-house layer below the gate; the freeze would then fix the executor as "interpreter plus closure-compiled scalar" and P2a's scope grows, which is an owner decision.
- **Differential and determinism (R-8, informational).** On 100,000 generated records the in-house and `cel-java` layers give identical decisions on the generated 200-rule S1 and S2 packs (0 mismatches); in S2 every decision is the catch-all, so that shape tests agreement on errors only. The generated rules use seven templates over clean records and trigger none of the DECIDE differences, so this is not evidence of semantic equivalence. In-house against the four P0 skeleton rules: 0 mismatches.
- **Explain (R-4).** The Tier-1 render p99 is 0.045 ms (median over 15 forks of the per-fork p99; bounds up to 0.060 ms) against the 100 ms allowance, which is the plan's split of the spec's 1 s gate. This is the render half only (see Limits).
- **Hot swap (R-5).** In 20 swaps at 200 and at 5,000 rules, for both layers, with 3 evaluator threads pinning the pack once per batch of 1,000 records, 0 batches saw two packs and 0 records were lost, in the spike's in-memory pack holder; the ledger-backed activation, signer and rollback re-pin of spec 6.5 step 6 are not exercised. Activation time (signed bytes in memory to first record under the new pack) for the in-house layer is recorded, not gated: 50.9 ms at 200 rules and 865.2 ms at 5,000 rules, the p99 of 20 swaps, which is in effect the slowest swap. The activation time includes up to one batch of drain time: an evaluator sees the new pack only at the start of its next batch of 1,000 records, which at 5,000 rules of the S2 shape (620 microseconds per record) is about 620 ms, so most of the 865 ms is the batch size, a design parameter, and not the cost of swapping a pack (an independent probe during the final review put the parse plus compile of a 5,000-rule pack at about 145 to 216 ms warm and at 27 to 33 ms at 200 rules; it is not part of the sealed results). The count of lost records was zero by construction in the code that produced this run (the evaluated counter was increased by the batch size unconditionally and then compared with the submitted count), so the recorded 0 lost records is not evidence; the 0 mixed-pack batches is.
- **Per-record cost sweep (in-house / `cel-java`, one thread, S2, informational R-3):** 4.6 / 41.9 microseconds at 50 rules, 17.9 / 170.9 at 200, 47.1 / 499.0 at 500, 620.3 / 10,496.9 at 5,000. The per-row cost rises with the pack (89.5 ns at 200 rules, 124.1 ns at 5,000 for the in-house layer), so the 200-rule rate must not be extrapolated: one thread alone would manage about 21,200 records/s at 500 rules and about 1,600 at 5,000 rules (below the gate), before the excluded work listed under Limits.
- **Budget and poison defaults (R-7, MODELLED).** Per-record budget 6,866 units (the formula: twice the static worst case of 3,433 for this synthetic 200-rule in-house pack, so it must be recomputed for every pack; the largest clean record used 1,600 units and none of 1,000,000 clean records exceeded half the budget); per-rule ceiling 64 units (the plan's floor, because the largest generated rule is small; real derivations may need more); poison circuit N = 4 in M = 50 records, detection probability 0.994 for a rule that fails on 20% of the records reaching it, false opens 2.3e-5 per rule and day. These rest on the assumptions printed in the report (background `EXEC_*` rate 1e-4, defective-rule rate 20%, 50 million records a day, independent noise). The informational clustered scenario (one burst a day of 5,000 consecutive records at 5%) opens the circuit about 24 times a day; whether those opens are false depends on whether such a burst is a defect that should open it, and the model does not judge that. It is a question for the owner whether N = 4 in M = 50 is wanted when errors arrive in bursts.
- **`cel-java` budget is coarse:** its static worst case is 116,275 units against 2,355 observed on clean records (cost is charged statically and bindings are materialised eagerly; nothing the expression builds is metered, see above); units of the two layers are not comparable.

**For the owner at the freeze: DECIDE vectors.** For these six vectors the spec text does not fix the answer (spec 6.2 says only "explicit absence (`has(x)`)", "evaluation errors yield UNDETERMINED" and "no loops"). The expected value of each was authored by the controller with the in-house reading, except L01, whose expected value T is what a loop-capable CEL would give (spec 6.2 says no loops, so no spec-conforming layer passes L01 as authored), so "PASS" means "matches the expectation as authored", not "is correct", and a `cel-java` "FAIL" means it differs from that expectation.

| Vector | Expression | In-house | `cel-java` | The question |
|---|---|---|---|---|
| D13 | `1 + dec('0.5') == dec('1.5')` expecting T | PASS (Ok(T)) | FUNCTIONAL: only as `decEq(decAdd(dec(1), dec('0.5')), dec('1.5'))` | May an integer be mixed with a decimal in operator form? |
| A04 | `event.venue == 'XPAR' && has(event.venue)` with the venue absent | PASS (Error(ABSENT)) | FAIL (Ok(F)) | Comparing an absent value is an error by MUST vector A02. Does that error stay an error when the right side of `&&` is false, or is it absorbed into false? Same question as E06 for `\|\|`. |
| A07 | `has(client.parent.lei)` for a natural person | PASS (Ok(F)) | FAIL (Error(ABSENT)) | Does `has()` below an absent parent give false (in-house) or an error (`cel-java`)? |
| E06 | `(1 / 0 == 1) \|\| true` | PASS (Error(DIV_ZERO)) | FAIL (Ok(T)) | May an error on the left of `\|\|` be absorbed when the right side decides? |
| S07 | `event.kind == 'NEW'` for an enum value | PASS (Ok(T)) | PASS (Ok(T)) | none: both agree |
| L01 | `[1, 2, 3].all(x, x > 0)` | FAIL (CompileError: loop macro not supported) | FAIL (CompileError: undeclared variable x) | Spec 6.2 says no loops, so L01 as authored asks the spec to change, not a layer to behave: the owner may add bounded list quantifiers to 6.2 or drop L01. The in-house layer rejects loop macros by design; the `cel-java` failure comes from the spike adapter, which has comprehensions switched off, not from a library limit. |

In the in-house layer `&&` and `||` evaluate left to right and an error in an operand that is evaluated is not absorbed by the other operand (A04, E06); this is consistent with spec 6.2 ("evaluation errors yield UNDETERMINED") and I5, and I3 does not address it. The layer is not strict everywhere: `has()` on an absent parent gives false (A07), and `true || (1 / 0 == 1)` gives true because the right side is never evaluated (E05, a SHOULD vector), so the outcome depends on operand order, while `(1 / 0 == 1) || true` is an error. `cel-java` follows CEL: an error on one side of `&&` or `||` is absorbed when the other side decides (A04, E06), while `has()` on an absent parent raises (A07). The owner decides, per vector, which outcome becomes the contract of the pack language; the in-house outcomes are the controller's proposal, not evidence. The ruling should cover operand-order sensitivity as well, and every vector the owner rules on becomes a MUST vector of the conformance suite.

**Limits of this report**

- **Runner noise and one VM.** All forks share one VM in one dispatch, so the bounds are optimistic (the report warns of it and labels R-2a and R-4 "single VM"). Protocol: 15 fresh-JVM forks per gated series; each warms up in 2 s windows until three consecutive windows agree within 3% (60 s at most), then measures 15 windows of 2 s; the statistic is the median of the window rates, and the verdict uses one-sided order-statistic bounds of the median of the 15 fork statistics. No fork was discarded or flagged unstable; the fork-to-fork spread is 2.0% (R-2a) and 2.2% (R-2b) of the mean. The CPU-steal discard (P-5) could not act because steal is not observable on this runner, so the noise controls are the canary probes and the observed spread; the memory canary alone varied by 64% from its lowest to its highest of 202 probes. The noise margin is the larger of 10% and the canary spread (plan rule), here 29.5%: R-2a must clear 12,954 and R-2b is judged against 7,719. Hosted runners differ in hardware between runs (the selftest ran on an AMD EPYC 7763, this run on an Intel Xeon Platinum 8370C) and the spread across hardware was not measured. Against the bare gate the in-house lower bound is 2.7 times 10,000 (2.1 times the noise-adjusted line) and the `cel-java` upper bound is 3.5 times below it (2.7 times below its noise-adjusted fail line); whether that is enough on other hardware is not shown here, and a production sizing needs a measurement on the target hardware.
- **The worst-case shape S2 removes the first-hit exit, not the within-row short-circuit.** Every row of every table is evaluated for every record (none of the 190 non-catch-all rows ever matches, because each ends in a sentinel conjunct that is never true; each table's catch-all matches, so every outcome is UNDETERMINED), but a row still stops at its first false conjunct, so the cost of a row depends on the selectivity of the generated rule templates. The sentinel is the last conjunct and is reached only when the earlier conjuncts are true. Real packs (P6a) may cost more per row; how much more the gate tolerates was not quantified. The rate is measured at 200 rules in 10 tables of 20.
- **What the checksum proves.** The benchmark folds every decision into a checksum, which prevents the work being discarded. In S2 every record ends in the same decision, so the checksum cannot show that every row ran; that is shown by the test that checks 200 rows evaluated for every record and by the differential run. The outcome counts of the gated forks are not in the raw data.
- **Interpreter evaluation only.** The figures cover evaluating the rules to an outcome and a rule-hit id with the trace off, including the `MeteredExecutor` and per-row metering that both layers share (so the ratio of the two layers understates the ratio of the expression engines, and the in-house figure is an upper bound for expression evaluation alone). They exclude path encoding (`PathCodec`, SP-L), the `input_digest` SHA-256 and the decision-record assembly of spec 6.3, so the enriched-to-decided interval is a lower bound. `decide.usPerRecordWorst` (17.9 microseconds) is the cost of one thread alone in shape S2; with four threads busy on two physical cores it is 36.0 microseconds per record per thread, so dividing it by the thread count for a latency budget overstates the rate.
- **`executorGate`** in the summary is the R-2 (throughput) verdict of the chosen layer alone; the Tier-1 render is the separate criterion R-4. Together they are the part of the spec 6.6 gate this spike measures. The spec 6.6 bounded-memory gate is pending SP-M (P1b). R-4 is the render half of the explain gate (100 of 1,000 ms) and closes only together with L-9 in the SP-L report.
- **The poison defaults are modelled, not measured** (independent noise, tumbling windows, per rule), and the clustered scenario is an assumption.
- **Not measured:** S1 throughput (STRETCH; the dispatch ran with stretch unticked; the S1 shape ran only in the differential check) and explain with path lengths outside the generated range of 1 to 200.

**What this means for P2a**

- No faster executor is added on this evidence (see the flip condition above). That does not show that closure compilation is unnecessary: the sweep falls below the gate beyond a few hundred rules, and the excluded work lowers the rate further. Revisit with real pack sizes (P6a) and the spec 6.3 record assembly.
- If the owner records the in-house layer, P2a no longer integrates a bought expression layer (the wording of its row in spec 16): it builds, tests and maintains the lexer, parser, evaluator, decimal and date library and cost model itself, including the vetted built-in library of spec 6.2, which the spike library does not contain. The spike code is disposable (spec 16); the size of this change was not measured.
- Conformance suite: the 38 MUST and 3 SHOULD vectors are a starting suite; the six DECIDE vectors join it as the owner rules (L01 fails in both layers and contradicts spec 6.2 as authored).
- The grammar and IR are not frozen by the spec freeze: spec 6.8 freezes them only at a G-S3b pass, together with the SP-R and SP-G reports, and a fail does not freeze the expression grammar. The rulings on A04, A07, E06, D13 and L01 are needed before P2a implements the layer's absence and error behaviour (P2a entry: the SP-R and SP-G reports before the grammar freezes).
