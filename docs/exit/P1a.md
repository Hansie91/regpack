# P1a exit record: spec-freeze spikes (SP-R, SP-G, SP-L, SP-X)

Date: 2026-10-11 (owner local time). Branch: `p1a/spikes` (from `p0/foundation`, 41 commits at the time of writing). Plan: [P1a plan](../superpowers/plans/2026-10-10-p1a-spec-freeze-spikes.md). Spec: [design specification](../superpowers/specs/2026-10-08-regulatory-reporting-platform-design.md). Reports: `docs/spikes/`.

## Verdict

P1a built the shared measurement harness and ran four spikes. **All four reports exist with RAW evidence**, so the spec-freeze entry wiring is satisfied (`Gates entry --target spec-freeze`). The reports do not all pass:

| Spike | Status | Evidence | Run | What the verdict rests on |
|---|---|---|---|---|
| SP-R | PASS | RAW | [38094901948](https://github.com/Hansie91/regpack/actions/runs/38094901948), one dispatch | the in-house expression layer passes the throughput gate; `cel-java` as adapted fails it. The importer derives the in-house layer; the choice and five DECIDE vectors are the owner's |
| SP-G | PASS | RAW (local run) | none (document spike) | every register row dispositioned by the owner: 16 ACCEPTED (15 spec wordings and BRK-18 as a P12 item), 3 REJECTED_NOT_NEEDED |
| SP-X | PASS | RAW | [38108080155](https://github.com/Hansie91/regpack/actions/runs/38108080155), one dispatch | per-record XSD of every record (policy ALL) meets the gate with large headroom on the synthetic schema; sampling fails the modelled failure path |
| SP-L | **FAIL** | RAW | [38175053709](https://github.com/Hansie91/regpack/actions/runs/38175053709), one full dispatch | L-4: the memory-limited runs did not sustain 3,470 records/s (2 of 2); seven criteria INCONCLUSIVE; cause of the failure not established |

The PASS of SP-R and SP-X is the passing alternative group (the other group failed); the table with every criterion and label is [results.md](../spikes/results.md). **The owner has not recorded the spec freeze.** [freeze-record.md](../spikes/freeze-record.md) is the template, `status: PENDING`.

## What each report decides for the next plans

- **P2a (rule-pack core):** SP-R and SP-G reports exist (entry satisfied). The expression layer is the owner's choice (in-house subset, to be built and maintained, or `cel-java` with a faster executor or lazy adapter, which was not measured); the DECIDE vectors D13, A04, A07, E06 and L01 need rulings before the layer's absence and error behaviour is implemented; the 15 accepted SP-G wordings are applied to the specification before the pack format is built. No faster executor is required on this evidence (the in-house interpreter passes with 2.7 times the bare gate on the lower bound, single VM, 200 rules, worst-case shape, interpreter-only). Measure the background `EXEC_*` rate in P2a shadow runs before the poison defaults (N = 4 in M = 50) are relied on.
- **P3 (ledger):** the SP-L report exists (entry `P3-schema-sizing` is satisfied by the report's existence, not by a pass). **L-4 is a FAIL whose consequence is the owner's**; the P3 schema inputs are in SP-L.md (partition granularity with `as_at` in `decision_head`, growth and retention of `decision_head` and `ingest_registry`, encoding E1 at 335 bytes per decision row, 48.8 GB of ledger a day, the tail of the synchronous commit).
- **P7a and P8:** SP-X supplies the per-record XSD policy and the stage latencies; SP-M, SP-S and SP-E are missing (P1b; SP-E gated by G3), so the `P7a-microbatch` and `P8` entries stay unsatisfied. N and T: the SP-L knee (10,000 for four writers) and the latency budget; T is final with SP-M.
- **P12:** the SP-G report exists (entry satisfied); BRK-03, 05, 17 and 18 are P12 items.
- **The spec freeze:** inputs in [freeze-record.md](../spikes/freeze-record.md); open decisions in [open-items.md](../spikes/open-items.md).

## Entry wiring (`Gates entry --target <target>`, 2026-10-11)

```
P2a: SP-R REPORT_EXISTS OK; SP-G REPORT_EXISTS OK
P3-schema-sizing: SP-L REPORT_EXISTS OK
P7a-microbatch: SP-L OK; SP-M MISSING; SP-X OK
P8: SP-S MISSING; SP-E MISSING; SP-X OK; G3 GATE_CLEARED NOT_CLEARED
P12: SP-G REPORT_EXISTS OK
spec-freeze: SP-R OK; SP-G OK; SP-L OK; SP-X OK
P5-xsd-or-derived-content: G3 GATE_CLEARED NOT_CLEARED
SP-E: G3 GATE_CLEARED NOT_CLEARED
```

A red line that names a P1b or gated spike is the wiring working, not a failure. `REPORT_EXISTS` accepts a PASS, FAIL or INCONCLUSIVE report: the existence of SP-L's FAIL report satisfies `P3-schema-sizing` without answering L-4.

## The partial latency budget

[latency-budget.md](../spikes/latency-budget.md): verdict PARTIAL, four of eight intervals pending with owners: landed to enriched (SP-N, P1b), decided to validated (P5: no spike measures it, so the owner accepts P5 ownership or amends spec 5.6 and the spec 16 P1 row), assembled to sealed (SP-W, P1b), sealed to sent (SP-S, P1b). The measured intervals sum to 11,447 ms, dominated (about 96%) by one shared figure, the pooled commit p99 of the cache-hot SP-L runs (9,582 ms; its criterion L-2 is INCONCLUSIVE). The sum is information, never a verdict.

## Hand-off findings

The CI-to-disk path was proven before the first real run (selftest 38093544725: the sealed artefact, the tamper check with one byte changed giving `HASH_MISMATCH`, the pasted job-summary channel, `import --dry-run`). Every real artefact matched the digest GitHub reports; extraction used a checking script (no unsafe path, no link). Runner: GitHub-hosted `ubuntu-24.04`, image `ubuntu24` version 20261004.327.1 on every run (closing the P0 gap "runner image names and versions were not captured"); CPU models varied between runs (AMD EPYC 7763, Intel Xeon Platinum 8370C, AMD EPYC 9V45, Intel Xeon Platinum 8573C, AMD EPYC 9V74), 4 vCPU and 16 GB each, root disk 154.9 GB with 92 GB available.

## Time-boxes and the answer to Q4

Final ledger values (agent working time; CI and owner waits are not counted, a day is 8 hours of agent work): HARNESS 166 of 480 minutes, SP-R 117 of 480, SP-G 43 of 240, SP-X 104 of 480, SP-L 176 of 960. No box was hit. The time-box lines inside the generated reports are the import-time values (SP-R 105, SP-G 35, SP-X 95, SP-L 157), because a passing report is never re-imported. Controller time for the owner-interaction steps of T8 was partly not logged (about 20 minutes).

## Approvals actually given

| Id | What | Date | By |
|---|---|---|---|
| A-1 | the pinned `postgres:18.6-trixie` image pulled on the runner (`confirm_downloads` ticked): smoke 38153007292 and full 38175053709 (the first smoke dispatch 38148408892 was not ticked and failed in 4 s, the guard working) | 2026-10-11 | hans |
| A-2 | licence mappings "Go License" and "BSD 3-clause" to BSD-3-Clause in the spike POM only (re2j 1.8, threeten-extra 1.10.0) | 2026-10-10 | hans |
| A-3 | pushes of `main` (merge of `p0/foundation` and the stub workflow), `p0/foundation`, and `p1a/spikes` (four pushes) | 2026-10-10, 2026-10-11 | hans |
| A-4 | workflow dispatches: stub 38093437651, selftest 38093544725, sp-r full 38094901948, sp-x full 38108080155, sp-l smoke 38148408892 and 38153007292, sp-l full 38175053709; artefact downloads | 2026-10-10, 2026-10-11 | hans |
| A-5 | merge of `p0/foundation` into `main` and the stub `spikes-run.yml` on `main` | 2026-10-10 | hans |
| A-6 | repository settings (Dependabot alerts and security updates, GitHub-owned actions only, read-only default workflow permissions) | **open** | hans said on 2026-10-10 they would do it before dispatching; not confirmed |
| scope exception | R1 may add the job id `spikes` to the exact job list of `PackagingFilesTest` (`regpack-app`) | 2026-10-10 | hans |
| SP-G dispositions | 16 ACCEPTED, 3 REJECTED_NOT_NEEDED; BRK-17 and BRK-18 re-rated P12_ONLY | 2026-10-10 | hans |
| SP-L | write the report now, no further run | 2026-10-11 | hans |
| criteria lock | `docs/spikes/criteria.lock`, digest d85eca73..., unchanged since | 2026-10-10 | hans |

## Re-runs and imports compared with the Actions listing

Raw directories per spike: SP-R 1, SP-X 1, SP-L 1 (three parts of one run), SP-G 1 (local), and one dispatch each in the Actions listing (`spikes-run <spike> full`). Re-runs: none. The two smoke dispatches of SP-L and the selftest are not evidence for any verdict.

## Exit criterion deviations

- The only file outside the allowed list that changed is `regpack-app/src/test/java/dev/regpack/app/PackagingFilesTest.java` (one job id added to the exact CI job list, owner-approved, recorded in `docs/spikes/decisions.md`). No release module changed otherwise.
- Plan text that the work found wrong or incomplete, recorded where the task found it (decisions, reports): the record element `Tx` of the plan against its own naming test (the synthetic schema uses `transaction_record`); the plan's L-9 and latency-budget formulas were extended (a loaded figure beside the unloaded lower bound; a shared ledger measurement counted once); SP-L sampler rows were not retained in the result; the importer counts one run id as one VM and pools the canary of all parts (SP-L open items H-1 to H-4).
- P1a does not exit on all-PASS: SP-L is a FAIL and seven of its criteria are INCONCLUSIVE. The plan's sign-off lists the deviations below for the owner.
- Effort for the spec section 15 calibration (agent side): 11 workflows with 70 subagents plus one fixer agent, about 12.7 million subagent tokens (implementers and reviewers for T2 to T8, R1 to R4, X1 to X3, G1 to G2, L1 to L4 and C1, and the independent checks of the four reports), 7 `spikes-run` dispatches (one failed at the approval guard), 0 re-runs; the owner adds hours.

## Sign-offs (owner)

- [ ] spec freeze recorded in docs/spikes/freeze-record.md (status: RECORDED)
- [ ] criteria placeholders confirmed (docs/spikes/criteria.lock)
- [ ] extrapolated criteria acknowledged: L-4, L-6, L-7, L-8
- [ ] latency budget is PARTIAL; pending intervals owned by P1b: landed to enriched, assembled to sealed, sealed to sent; decided to validated by P5 (no P1b spike measures it; the owner accepts P5 ownership, or amends spec 5.6 and the 16 P1 row)
- [ ] SP-M, SP-S, SP-W, SP-N deferred to P1b; SP-E gated by G3; SP-O, SP-V, SP-D conditional
- [ ] R1 closes only when P1b has exited
- [ ] the L-4 FAIL consequence recorded (freeze-record.md, section 5)
- [ ] repository settings (A-6) done or deferred

Signed: <owner>, <date>
