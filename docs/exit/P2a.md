# P2a exit record: rule-pack core

Date: 2026-10-17 (owner local time). Branch: `p2a/rule-pack-core` (from `p1a/spikes`, 44 commits at the time of writing). Plan: [P2a plan](../superpowers/plans/2026-10-16-p2a-rule-pack-core.md) and its [contracts](../superpowers/plans/2026-10-16-p2a-contracts.md). Spec: [design specification](../superpowers/specs/2026-10-08-regulatory-reporting-platform-design.md). Records: [decisions.md](../pack-format/decisions.md) (approvals, pins, deviations, the review and its fixes), [p2a-format-notes.md](../pack-format/p2a-format-notes.md) (the format as built, 210 problem codes, hand-offs, 12 open questions), [P1a exit](P1a.md).

## Verdict

P2a built the pack format, the strict parser, decision tables, the registries, the in-house expression layer, the table interpreter and `PackRegime`, the poison circuit, `explain`, the golden harness with enforced provenance, the coverage gates, the CSV round trip and the `pack` commands, and ran them on a synthetic sample pack. **Each of the four exit clauses and the S0 definition reproduces from the command line on the final head.** Evidence below is from the controller's own runs on this Windows machine (local) and from the CI run of the final head (run 39089872984 on head 1d747ce).

| Clause (spec 16, P2a row; S0 of spec 15) | Evidence |
|---|---|
| `pack test` and `pack explain` run on a sample pack | `scripts/pack.sh test packs/sample.s0` prints `pack test: sample.s0 0.1.0 digest a421f125...`, `files: 11 (CHECKSUMS verified)`, `signature: UNSIGNED (an ATTESTATION is not verified before P9b)`, `static checks: 0 problems`, `engine: 1.0.0+lib1 satisfies engine_min 1.0.0+lib1`, `golden: 4 of 4 pass`, `coverage: rows 4/4, reasons 4/4, provisions 4/4 (all cases); rows 0/4 (counting cases)` (coverage by counting cases is 0 of 4 and never gates while G1 is open), `audit: 0 of 4 counting, 4 consistency-only; reviewers registered: 0 ... (gate G1: no named Compliance owner)`, `budget: record budget 40 units (static worst case 20) ...`, `pack test: PASS`, exit 0. `explain --rule S-N03-novation` prints the rule, outcome, node, validity, reason SAMPLE.N03.EXCLUDED, the cited source with its quote hash, the rationale RAT-0001 and the golden cases. `explain --event` with the input of `GC-N08-1` prints `Decision 0 (seq 1) ... (not stored)`, outcome REPORT, reason SAMPLE.N08.IN_SCOPE, the path (two non-matching rows of 8 and 4 units, then MATCH at 6 units), the citation, legs MARKET, the input digest and context, the full trace and the Tier-2 line. Tests: `PackCommandsTest` (41 methods), `SmokeCommandTest#smokeRunsFromTheBundledSamplePack`. |
| The golden harness enforces provenance fields | A copy of the sample with one golden case changed to `"provenance":{"kind":"ESMA_QA"}`, sealed and tested: exit 1 with `GOLDEN_NO_REVIEWER`, `GOLDEN_NO_REVIEW_DATE`, `GOLDEN_NO_SOURCE` and `pack test: FAIL (3 problems)`. A pack golden without any provenance is refused by the strict loader (`GOLDEN_NO_PROVENANCE`, decision D-P6; the P0 lenient loader still labels the skeleton goldens consistency-only). Tests: `PackGoldenStrictnessTest`, `GoldenAuditTest` (moved), `PackTesterTest`. **All four sample goldens are consistency-only** (gate G1: no named Compliance owner), so the harness proves the mechanism, not any case's correctness. |
| A decision table survives a CSV export and re-import unchanged | `table export packs/sample.s0 scope/sample-scope.yaml --out t.csv`, `table import ... t.csv --write` (`rows +0 -0 ~0`), `seal`, then `git diff --exit-code packs/sample.s0` exits 0: the pack is byte-identical. The sample is already in the canonical layout; an import rewrites a hand-formatted or commented table in the canonical layout and drops comments (stated in its output). Tests: `PackCommandsTest#tableExportThenImportLeavesThePackUnchanged`, `TableCsvRoundTripPropertyTest` (random tables). This is partial evidence toward AT-8 (the row closes in P9b). |
| The replay-compatibility rule for `engine_build` is defined | `EngineBuild.replayCompatibleWith` (same major and same built-in library version) in `regpack-model`; `Tier2Plan` decides between the current engine and the archived-build subprocess; rule text in companion section 5.1 and notes section 5.1. Tests: `EngineBuildTest` (9), `Tier2PlanTest` (5), `SemanticsFingerprintTest` (2), `EngineInfoTest`. The fingerprint guards only the three conformance data files (limitation stated in the notes). |
| S0: three scope rules run to a decision record and `explain`, now from a real pack | The sample pack has three rules and a catch-all; `PackRegimeMatchesSkeletonTest` shows the pack regime and the P0 walking skeleton agree on all 96 grid executions apart from the four documented differences; `PackToyCrashIT` runs the pipeline from the pack (crash determinism, 16 plans and 25 seeded plans); `SmokeCommand` runs from the pack bundled in the distribution. The pack is synthetic and unsigned (A4). |

This is evidence on a synthetic pack and consistency-only goldens. No committed pack contains regulatory rule text or regulator or third-party artefacts (gates G1, G3, G4 unchanged; `EveryCommittedPackIsSyntheticTest` is a vocabulary tripwire over the pack directories, not a proof). The P0 walking skeleton in main code keeps its MiFIR-named placeholders (`mifir-placeholder`, `MIFIR.N03.EXCLUDED`), and the repository holds the specification and research documents.

## The CI run

Pushed head `1d747ce` (2026-10-17, owner-approved, AP-2 and AP-3; documentation-only commits on top of the last code commit `a6343fa`; the approval named 22 commits and the push carried the commit that records the approval as the 23rd): [run 39089872984](https://github.com/Hansie91/regpack/actions/runs/39089872984), **all seven jobs `success`**: `build` on ubuntu-24.04 and on windows-2025, `determinism (linux against windows)`, `spikes (disposable P1a code)`, and the three smokes (linux systemd, windows server service, kubernetes with kind and Helm). The `Compare toy crash digests` step ran and passed; it compares the skeleton digest and, since T24, the pack-pipeline digest between the two operating systems and fails on a difference or an empty value (checked as text in `PackagingFilesTest` and by execution in `CiDeterminismCompareTest` where a working bash exists). The two artefacts `toy-digest-ubuntu-24.04` and `toy-digest-windows-2025` are 379 bytes each (zipped; one digest in P0 was 195 bytes), as two digest lines would be. Job logs need a sign-in, so the equality of the digest values is read from the success of that step, not from the values. The Windows value `b7996d2a...` of the pack-pipeline digest was captured here; the Linux build job also passed the Windows-captured pins `PackDigestTest#digestOfTheFixtureIsPinned` and `SamplePackTest#digestIsPinned`.

The earlier checkpoint after T16 (head 589b768, [run 39042439674](https://github.com/Hansie91/regpack/actions/runs/39042439674)) was also green on all seven jobs; it did not yet contain the second digest. This record, written after the run, is a documentation-only commit that has not been pushed or run.

## Pins

New (value, task and reason in [decisions.md](../pack-format/decisions.md), "Pins created", and in its T23 and T24 bullets): the sample pack digest `a421f125754b61c1682ab26021611b380e5c97409adb97f125175c1d4ac0b4f6` (T10), the semantics fingerprint `4ffe461683fad4b3fd2ea69bcd8bf24b199b06b53e26160487154408a4b908c2` (T14), the engine build `1.0.0+lib1`, the pack-pipeline toy digest `b7996d2acfd7966b592c3c3c36d42307f0f1cad50b155f9b20c8d8dfc2bf687d` (T24; a new recorded value, not a change of an old one; **no test asserts it**, the only automatic check is the Linux-against-Windows equality in the CI compare step; asserting the literal in `PackToyCrashIT` would make it a pin and is a cheap follow-up), the second-regime fixture `input_schema` digest `752801d1101cb30157940ca0101f0e3ae43e4db81ab656246a7f29bc6a55a391` (T23; checked by the loader, not a test pin), the circuit defaults 4 in 50 (T17), the problem caps 100 per file and 1000 per load (FX2), the JSON number bound of 1100 characters and the evaluator input hold (FX1), the hidden-character policy of the eol gate (FX6) and the pack-format shapes and limits of T6 to T9. All are **provisional until a G-S3b pass**.

Unchanged: the P0 skeleton toy digest `70382303f446476321d38d50817bdd8d541e0dcb61d8f33d92435c676bc116c9` (`ToyCrashIT` untouched, reproduced locally), the skeleton goldens, the pinned text of `Tier1ExplainerTest`, every digest literal in `DecisionTypesTest`, the P1a reports and `docs/spikes/**` (only `entry-wiring.tsv` gained its two planned lines).

## Existing tests that changed

The plan lists: `DistributionLayoutIT` (`lib/regpack-pack.jar`, the bundled pack), `SpikeWorkflowTest` (renamed `ciPushFilterNamesTheWorkBranches`), `PackagingFilesTest` (second digest; since FX6 it checks the meaning of the compare step), `RepositoryGoldenAuditTest` (imports), `GoldenAuditTest` (moved to `regpack-pack`), `DecisionTypesTest`, `SmokeCommandTest`, `MainTest` (tests added), `ExecutorBudgetTestsAreWallClockFreeTest`, `docs/traceability.md`. The review fixes also changed `GatesTest`, `ForbiddenCallScanTest` and `EolCheckTest` expectations (FX6: malformed UTF-8 in a `.java` file is now a `NOT_UTF8` problem; a `.cmd` file with U+2028 also reports `UNICODE_LINE_SEPARATOR`; a look-alike text lost its U+0084, now a refused control character; a listed path that does not exist is `NOT_FOUND`; the deny-list test was rewritten), each justified in its review, and `Tier1ExplainerTest`, `JsonTest` and `CanonicalExecutionCodecTest` gained tests without changing existing assertions. Engine tests that built a regime or compiled a pack now use `Rulings.load()` instead of `Rulings.provisional()` (a6343fa).

## Throughput information (T22; not a verdict)

`regpack-engine/target/pack-throughput.txt`, a generated pack of 200 rules in 10 tables, worst-case shape S2, interpreter only, one in-process fork on one VM, this Windows machine (12 logical processors, noisy):

```
pack-throughput (information, not a verdict; one in-process fork, one VM, interpreter only, worst-case shape S2)
rules 200, tables 10, ring 262,144 records, budget 6,866 units per record
load plus compile: 238 ms (one cold run)
interpreter, records/s per thread: 53,944 (1 thread), 27,321 (4 threads busy)
SP-R reference, spike interpreter: 55,860 (1 thread), 27,796 (4 threads busy, per thread); placeholder gate 10,000 (spec: per core; shown per thread, the conservative SP-R reading): ABOVE
EXEC_* outcomes on the clean corpus: 0 in 3,060,471 records (4 threads); checksum bc398c2288f99735
```

Reading: the figure excludes record assembly, `input_digest`, path encoding and real pack sizes (the SP-R open items). The machine is noisy: before the final review fixes the four-thread figure was 28,759 and 28,816 per thread (one thread 57,562 and 65,491); after them three runs gave 12,556, 22,112 and 27,321 per thread (one thread 43,937, 39,545 and 53,944). The evaluator now checks every bound value on entry (FX1), which costs some throughput; the spread between runs is larger than that cost can explain, so the repeated runs do not support a firmer statement. The placeholder gate of 10,000 per thread was reached in all runs, the lowest run only narrowly (12,556). The SP-R protocol (15 forks per series, order-statistic bounds) was not repeated for the production layer; `PackThroughputCheck` is skipped by `verify`. The EXPR-3 pricing issue (string `contains`) and the held-input scan are not in this figure's favour or disfavour in any way the run can show.

`EXEC_*` outcomes on the generated corpus: 0 (0 in 3,060,471 records on the last post-fix run, 0 in 2,061,250 on the first; 0 by construction, the corpus has no failing record).

## DECIDE vectors

The five provisional rows of `rulings.tsv` stay PROVISIONAL with the in-house outcomes: D13 ALLOW (`1 + dec('0.5')` is allowed), A04 PROPAGATE and E06 PROPAGATE (an error on the left of `&&` or `||` stays an error; CEL absorbs it), A07 FALSE (`has(client.parent.lei)` is false when the parent is absent; CEL raises ABSENT), S07 BY_NAME (no alternative). Owner answer on 2026-10-17: **keep provisional.** The rows therefore stay PROVISIONAL until the owner rules, expected at the spec freeze. A ruling is a one-cell change of `value`, the change of the status and ruler, a `SEMANTICS_MAJOR` bump and a re-pin, in its own commit. L01 (bounded list quantifiers) stays a spec question (spec 6.2 says no loops).

## Open items

For the owner, with the recommended handling; the complete list is notes section 9 and the end of decisions.md.

1. **Circuit close and the ledger (review INTERP-3, major).** A decision stamped `EXEC_CIRCUIT_OPEN` is not superseded when the circuit closes: the re-evaluation has the same `decision_context_id` and `input_digest`, the ledger treats it as a redelivery, and the record stays UNDETERMINED (spec 7.3 promises a superseding decision; I5, I7). A test pins the behaviour. P3 must choose before it freezes the decision schema: a circuit epoch in the decision context, or a re-evaluation ordinal (or the reason code) in the unique key.
2. **Per-rule cost ceiling.** Spec 6.2 says "a per-rule cost ceiling"; P2a implements a node-count ceiling (`rule_nodes`, `RULE_OVER_CEILING`). A rule of 128 nodes whose static bound is about 32,800 units passes `pack test`, and both printed figures ("rule ceiling 512" and "SP-R recommended") are derived from the node count (`max(64, 4 x the largest rule's nodes)`), whereas the SP-R figure of 64 was in cost units. This is a third exception to the quoted spec lines (the plan lists two); approve it with AP-1 or ask for a cost ceiling.
3. **OI-3 coverage semantics** (provisional, FX3): a golden's `ruleIdsHit` is the deciding row; coverage credits every row matched on the way, including the catch-all of a non-last table. Confirm before P6a.
4. **Goldens do not compare REPORT legs or the `set` attributes** (INTERP-7), which are part of the decision and its replay digest.
5. **EXPR-3**: string `contains` is priced about 1,000 times too cheap and nested-list equality and `in` are uncharged; with inputs held to 4,096 code points the worst case is about 10 ms for 155 units. A linear-time `contains` or a repricing is a library-version event to schedule before the first pack release.
6. **The semantics fingerprint** guards only `rulings.tsv`, `vectors.tsv` and `cost-fixtures.tsv`; a library or evaluator change that no vector exercises, or a re-pin without a bump, is not detected. The notes require a vector or cost fixture with every such change.
7. **Poison circuit parameters**: the SP-R measurement of the background `EXEC_*` rate stays open for a shadow run on real records (P6a, P7a); N = 4 in M = 50 stay MODELLED and are not relied on until then. Open question for the owner: SP-R's clustered-burst scenario gives about 24 opens a day at 5,000 records at 5%.
8. **Format choices left to the freeze** (notes section 9): the meaning of a reason `label`, the depth of the opaque directories, the report map and set IR choices, an empty `scope_tables` list, hash-only provisions, the library version 1 behaviours of T13, whether a golden case's node is compared (and U+2800 BRAILLE PATTERN BLANK, which is still allowed in text, notes section 5.2).
9. **The second regime runs on `CompiledPack` and `PackProgram`, not on the `PackRegime` provider** (which is tied to the 13 canonical paths of P0 and refuses a `STATE` variable). The R7 and I4 evidence says exactly this; P3 and P12 change it deliberately.
10. **Never run here**: the POSIX permission and symlink tests of FX4 and the Linux behaviour of the eol gate's file listing (FX6) were written and reviewed but skipped or reasoned on Windows; the Linux build job of the final CI run passed, where the assumptions of those tests hold, but per-test results are not visible without a sign-in, so whether each ran (rather than skipped) is not confirmed here.
11. **Smaller limitations recorded in decisions.md:** a poison circuit must be created per pack digest and nothing enforces it (T17); the rule id `as_at_field` is not reserved and a pack row of that name would share the circuit state of as-at read failures; the number of rows in a CSV import is uncapped and `CompiledPack.compile` still builds an uncapped problem list (about 2 per failing row) in memory (FX2).

## Deferred

P2a-2 (Firm Profile register, overlay registry, interpretation pins, derive tables; six to eight tasks, written after this exit and the calibration read-out), P2b (type checker and full static analysis, only if G-S3b passes), and the hand-offs to P3, P5, P6a, P8, P9b, P12, P10b and P11 (notes section 7). Spec section 16 has no P2a-2 row yet: the owner edits the spec (OI-9 default).

## Approvals actually given

| Id | What | Date | By |
|---|---|---|---|
| AP-1 | the plan, its contracts, the departures, the beyond-6.1 format elements, the split, D-P6, A1 to A6: given as "ok approved lets continue" in answer to the message that started P2a on the assumptions A1 to A5; the plan text, A6 and D-P6 did not yet exist and were summarised to the owner afterwards without a recorded reply; **the owner has not read the plan text as far as the records show** | 2026-10-16 | hans |
| OI-1 to OI-10 | defaults accepted by delegation ("make your own decision how to continue"); OI-5 asked again at T14 and answered "keep provisional" | 2026-10-16, 2026-10-17 | hans |
| AP-2 | push of `p2a/rule-pack-core` at the OI-10 checkpoint (head 589b768, CI run 39042439674) and again for the final head | 2026-10-17 | hans |
| AP-3 | the `p2a/**` branch filter of `ci.yml` and the second digest upload and compare | 2026-10-17 | hans |
| AP-4 | merge, tag and the sign-offs below | **open** | owner only |

## Departures and deviations from the plan

The departures table of the plan (building the format and the in-house layer before the spec freeze is recorded; the in-house layer instead of a bought one; the DECIDE rulings kept provisional; and the rest) stands as approved by AP-1 with the caveat above. Deviations that the work added are recorded task by task in decisions.md; the ones that matter to the owner: the third spec-row exception (open item 2), `PackRegime.evaluateTraced` (T18, kept), the problem caps and input holds of the review fixes, `EolCheck` widened to the `PackText` policy (a controller addition to T26 and FX6), the context-equality rule of `PackRegime` (FX3, to be relaxed by P2a-2), and the documented corrections to the companion text (STATE variables refused by `CanonicalBindings`, the 16 x `rule_nodes` parser cap, the `circuit=open` flag, the ternary bound of list literals). `ci.yml` was edited in three commits (b6bb562 the `p2a/**` branch filter, 48c7e8a the second digest, 326b84e the empty-value checks of the compare step), two logical edits under AP-3, and `PackagingFilesTest` changed with it.

## Final whole-branch review (plan T27 step 7)

Seven read-only lenses over `git diff p1a/spikes...HEAD` at fa09515 (spec fidelity, expression layer, interpreter and regime, pack module, app and CI and tools, test teeth, safety), 27 subagent runs; every finding above minor was re-checked by a skeptic. 20 findings above minor and 47 minor; the skeptics confirmed 8 as major (hostile-input cost of decimals, strings and long JSON numbers; unbounded problem lists; the coverage gate for table chains; crashes of `pack test` and `pack explain` on unrepresentable executions; the circuit-close supersession of open item 1), 10 as minor, and refuted 2. **No finding changed an exit clause.** Fixes FX1 to FX6 were made by separate implementers, each reviewed independently with no fix rounds, plus a controller commit: 37e5560 70e2bf7 8a010d5 4dbc9e7 42789f0 5bfe161 326b84e a6343fa. Not fixed and carried as open items: 1 (pinned by a test and handed to P3), 2, 4, 5 and 10; 6 is documented only. The reports of the review and of each fix are summarised in decisions.md.

## Effort for the spec section 15 calibration

Ledger (agent working time and tokens; owner reading and deciding time was not provided; CI waits not counted):

| Piece | Tasks | Subagent runs | Subagent tokens | Tool calls | Tests added (as reported by the implementers) | Fix rounds |
|---|---|---|---|---|---|---|
| Chunk A | T2 to T9 | 16 | 2,547,817 | 461 | 242 | 0 |
| Chunk B | T10 to T16 | 18 | 2,613,309 | 549 | 102 | 2 (T11, T12) |
| Chunk C | T22, T17 to T21 | 14 | 2,093,374 | 432 | 88 | 1 (T21) |
| Chunk D | T23 to T26 | 8 | 1,335,176 | 309 | 68 | 0 |
| Final review | T27 step 7 | 27 | 4,862,202 | 1,216 | 0 | - |
| Review fixes | FX1 to FX6 | 14 | 2,225,419 | 801 | about 188 | 0 |
| **Total execution** | 25 tasks, 6 fix tasks (FX5 in two parts) | **97** | **15,677,297** | **3,768** | **about 688** | |

The tests-added column is as the implementers reported it and is not reconciled with git: the static count of added `@Test`-family annotations is +718 (chunk A 242, B 172, C 111, D 68, the fixes about 125).

Wall-clock of the workflow runs (agents only, no owner or CI waits): chunk B 127 min, C 90 min, D 66 min, review 125 min, fixes 157 min (chunk A not recorded). Git counts: `git rev-list --count p1a/spikes..HEAD` is 43 before this record (44 with it); `git diff --shortstat p1a/spikes...HEAD` is 222 files changed, 36,245 insertions(+), 78 deletions(-) before this record. The planning of P2a (a four-lens review of the plan) is not in the table. Comparison: P0 had 33 tasks and 77 agent runs, P1a 23 tasks and 70 subagents (P1a.md counts its separate 22-agent final review and one fixer agent apart, so the 70 is not like-for-like with 97); P2a has 25 tasks executed by subagents (T2 to T26; the controller did T1 and T27), 6 fix tasks, a seven-lens review and 97 subagent runs in all. The review and its fixes were 41 of the 97 runs and about 45% of the tokens; the lever for the next plan is the review depth, then the cut order of spec 17.4.

Worksheet for P2a-2 and every later plan (the owner fills the blanks): task-count estimate ____ ; subagent runs expected (about 3.9 per task in this record, review and fixes included) ____ ; owner reading and deciding minutes for this record ____ ; re-estimate decision for P3 (continue, or the cut order of spec 17.4) ____ .

## Sign-offs (owner)

- [ ] AP-4: merge `p2a/rule-pack-core` into `main` and tag `p2a-rule-pack-core` (not set, not pushed)
- [ ] exit criterion accepted (the four clauses and S0, on a synthetic pack with consistency-only goldens)
- [ ] contract and layout changes accepted (`regpack-pack` module; `DecisionBody.attributes`; `EngineBuild`; golden harness move; `PackRegime` context rule)
- [ ] third spec-row exception accepted or a cost ceiling requested (open item 2)
- [ ] DECIDE rulings recorded, or deferred to the freeze (kept provisional on 2026-10-17)
- [ ] spec amendments (D-4, the 15 SP-G wordings) applied to the specification, or the format notes accepted in their place
- [ ] the `entry-wiring.tsv` edit accepted and the freeze-record line "entry-wiring re-checked against spec 16" re-confirmed
- [ ] traceability `exited_plans: P0, P2a` (it also needs P1a; the P1a sign-off is open)
- [ ] circuit-close supersession decision recorded for P3 (open item 1)
- [ ] calibration read-out done and the P3 re-estimate decision recorded (spec 15)

Signed: <owner>, <date>
