# P2a decisions and records

Kept by the controller (the main session). Plan: [P2a](../superpowers/plans/2026-10-16-p2a-rule-pack-core.md) and its [contracts](../superpowers/plans/2026-10-16-p2a-contracts.md). Style of `docs/spikes/decisions.md`.

## Baseline (T1 step 2, 2026-10-16)

Branch `p2a/rule-pack-core` from `p1a/spikes`; plan committed as `110db05`. `./mvnw -B -ntp verify` ends `BUILD SUCCESS` with the totals the plan names (tools 145 + 1 IT, model 47, ports 14, testkit 86, bench 343 with 1 skipped, inmemory 105, engine 34 + 5 IT, app 43 + 5 IT). `scripts/check-gates.sh` ends `check-gates: all gates passed` (including the P1a `verify-committed`, `results.md` and `latency-budget.md` checks). `Gates entry --target P2a` prints `SP-R REPORT_EXISTS OK` and `SP-G REPORT_EXISTS OK`. `docs/spikes/freeze-record.md` line 3: `status: PENDING` (the spec freeze is not recorded; departures table row 1).

## Approvals

| Id | What | Date | Name | State |
|---|---|---|---|---|
| AP-1 | The plan, its companion contracts file, the departures table, the beyond-6.1 format elements, the split decision, "Contract and layout changes", "Existing tests that change", D-P6 and A1 to A6 | 2026-10-16 | hans | given in chat as "ok approved lets continue", in answer to the controller's message that started P2a on the working assumptions A1 to A5 (it followed "ok let's continue development. make your own decision how to continue"). The plan text was written and reviewed after that message; the controller summarised it to the owner before executing and proceeds on this approval. **The owner has not read the plan text as far as this record knows**; the owner can stop or amend at any time. |
| AP-2 | Every push of `p2a/rule-pack-core` and every CI run waited on | | | asked at each push |
| AP-3 | The two edits of the pinned workflow `ci.yml` (T2 branch filter, T24 second digest) | | | asked at the first push that contains them |
| AP-4 | Merge, tag and the sign-offs in `docs/exit/P2a.md` | | | owner only (T27) |

## Owner inputs

All answered as "default accepted" on 2026-10-16 by the owner's delegation ("make your own decision how to continue"), except where the plan makes a task ask again (OI-5 at T14). A default is not an owner ruling on the question; it keeps work moving and is reversible at the cost the plan names.

| Id | Question | Default | Answer |
|---|---|---|---|
| OI-1 | Plan approval and A1 to A6 | proceed on AP-1 | default accepted (AP-1 given) |
| OI-2 | Source of `legs[]` of a REPORT decision | optional `legs:` list on REPORT rows, default `[MARKET]`, vocabulary MARKET, CLIENT, ALLOCATION | default accepted |
| OI-3 | How several scope tables per report combine | the SP-R benchmark rule | default accepted |
| OI-4 | When an open poison circuit closes | only by explicit `close(ruleId)` or a new pack digest | default accepted |
| OI-5 | Rulings on D13, A04, A07, E06 (S07: see A2) | in-house outcomes, provisional, one line each of `rulings.tsv` | default accepted for now; asked again at T14 |
| OI-6 | Keep the P0 `skeleton` package | yes, until the frozen spike code is removed (Q7, D-8) | default accepted |
| OI-7 | Where the reason-code prefix and node list are declared | `reasons.yaml` header | default accepted |
| OI-8 | Calibration read-out before P3 is planned | stop at T27 | default accepted |
| OI-9 | Accept the split and add a row P2a-2 to the spec 16 table | the notes of the Deferral section stand in until the owner edits the spec | default accepted; the spec is not edited |
| OI-10 | Optional CI checkpoint: push after T16 and wait for the run | recommended | asked at the time (AP-2) |

Owner reading and deciding time for the effort ledger (Convention 8): not provided.

## Pins created

| Name | Value | Task | Reason |
|---|---|---|---|

## Deviations from the plan

None yet.
