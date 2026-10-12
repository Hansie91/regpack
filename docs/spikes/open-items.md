# Open items after P1a

Written by hand from the four reports (their open lists and limits), the SP-G register, the owner decisions and the harness findings. An item stays here until a named plan or the owner closes it. "Before" names the work that must not rely on it being settled.

## Decisions for the owner at the freeze

| # | Item | Source | Before |
|---|---|---|---|
| D-1 | **SP-L L-4 FAIL: the consequence.** The memory-limited runs sustained about 2,500 records/s against 3,470 offered; the cause is not established. Spec 5.3 flip, more memory or faster storage on the reference node, another layout, or a fixture artefact? | SP-L.md, "The L-4 consequence is the owner's" | the freeze record, section 5 |
| D-2 | **Expression layer.** The importer derives the in-house layer (`cel-java` as adapted fails the throughput gate, by about 3.5 times; part of the gap is the adapter's per-row bindings and a lazy adapter was not tried). Adopt the in-house layer, or keep `cel-java` with a faster executor or a lazy adapter (probe before the freeze). | SP-R.md | P2a scope |
| D-3 | **DECIDE vectors D13, A04, A07, E06, L01** (S07: both layers agree). The in-house outcomes are the controller's proposal, not evidence. Rulings also cover operand-order sensitivity of `&&` and `\|\|`. | SP-R.md, table | before P2a implements absence and error behaviour |
| D-4 | **SP-G amendment list**: apply the 15 accepted wordings to the specification (6.1, 6.2, 6.3, 7.4, 9.2, 9.7, 10, 11.1) and the two edits no proposal contains (`legs[]` in 6.3; the `scope/` and `derive/` lines of the 6.1 tree). Dependencies: BRK-16 with 01, 02, 04, 07 and 08; BRK-09 needs BRK-01. | SP-G.md, register | before P2a builds the pack format |
| D-5 | **SP-X per-record policy**: ALL (derived), on the synthetic schema only. | SP-X.md | P8 entry |
| D-6 | **SP-L P3 inputs**: partition granularity (daily with `as_at` in `decision_head`, or monthly), the growth and retention policy of `decision_head` and `ingest_registry`, the encoding E1, the ledger size (48.8 GB a day), micro-batch N (knee 10,000 for four writers) and T. | SP-L.md | P3 schema design |
| D-7 | **Acknowledgements** of the non-PASS, INCONCLUSIVE, EXTRAPOLATED and single-VM criteria. | freeze-record.md, section 3 | recording the freeze |

## Findings about the harness that the owner should know (the numbers above rest on it)

| # | Item | Effect |
|---|---|---|
| H-1 | **The importer counts one run id as one VM.** SP-L ran three parts on three VMs (two CPU models); the report says "vmCount 1" and "forks share one VM". | the generated warnings are wrong for SP-L; bounds are optimistic within each part |
| H-2 | **The noise margin pools the canary readings of all three parts for every criterion**, so L-6 and L-7 (restore VM, own margin 0.26) are judged with the probes of the other VMs (margin 27.7). | seven SP-L criteria INCONCLUSIVE |
| H-3 | **The DISK canary runs right after heavy runs**, so it partly measures the run's own disk backlog (57 sync ops/s after A2). | inflates the margin; the cause is not isolated |
| H-4 | **The per-second sampler rows (CPU, WAL fsync, checkpoints) were collected but not kept**; only the violation count is in the result. The PostgreSQL logs in the artefacts are the last cluster only. | the cause of the L-4 failure and of the commit stalls cannot be established |
| H-5 | **The time-box lines inside the generated reports** are the import-time ledger values (SP-R 105, SP-G 35, SP-X 95, SP-L 157 minutes). The final values are in `docs/exit/P1a.md`. | cosmetic |
| H-6 | **A re-run cannot repair H-2 or H-3 under the plan's rules** (pooling only adds samples); the owner decided on 2026-10-11 not to re-run SP-L. | the L-4 FAIL stands as recorded |

Changing H-1 to H-4 would change the evaluation after the results were seen. If the owner wants the SP-L criteria re-evaluated per part, or the diagnostics kept, that is a recorded decision with a new run; nothing in the repository does it silently.

## From the four reports

| Spike | Open item |
|---|---|
| SP-R | The spec 6.6 bounded-memory gate is pending SP-M (`executorGate` covers throughput and the Tier-1 render only). The S2 worst case removes the first-hit exit but not the within-row short-circuit; real pack sizes (P6a) and the record assembly of spec 6.3 are not in the rate; at 500 and 5,000 rules the one-thread rate falls to about 21,000 and 1,600 records/s. The poison defaults (N = 4 in M = 50) are modelled; the clustered burst scenario gives about 24 opens a day. **Measure the background `EXEC_*` rate in P2a shadow runs before the poison defaults are relied on.** Hot swap was exercised on an in-memory pack holder only (ledger-backed activation and rollback re-pin not). |
| SP-G | No human-reviewed golden case exists (gate G1 open: a Compliance owner is not named); the exercise is one encoding of six action types from one research brief; CORR, POSC, MARU, the margin report and the report schema are not encoded; BRK-07, BRK-08 and BRK-16 rest on assumptions A-9, A-10 and A-1. P12 items: BRK-03, 05, 17, 18. |
| SP-X | The verdict holds for the declared synthetic shape only (RF9): re-run if SP-E, once G3 is cleared, finds a statistic differing by more than a factor of two. No durable write (fsync, evidence PUT) and no defect injection in the integrated rate; the stage latency is idle single-lane (about 1.9 times longer with four lanes busy); the JDK validator is XSD 1.0 only (X-5). |
| SP-L | L-15 (replay conflicts and lag under console load) not run: standby reads stay disallowed (spec 12.1). One full dispatch; windows are autocorrelated; the fixture is biased (three servers and the generator on one VM; zero-round-trip standby). A full day beyond run B was not measured. The 24-hour replay term of L-6 and L-7 is a model term. |

## The latency budget (see `latency-budget.md`)

PARTIAL: four of eight intervals are pending, each with an owner: landed to enriched (SP-N, P1b), decided to validated (P5; no spike measures it, so the owner accepts P5 ownership or amends spec 5.6 and the 16 P1 row), assembled to sealed (SP-W, P1b), sealed to sent (SP-S, P1b). The one dominating measured figure (9,582 ms, the pooled commit p99 of the cache-hot runs) carries the INCONCLUSIVE status of L-2.

## Deferred spikes

SP-M, SP-S, SP-W, SP-N (P1b), SP-E (gated by G3), SP-O, SP-V, SP-D (conditional): see `deferred.md`.

## From earlier plans and the repository (still open)

- Repository settings (approval A-6): Dependabot alerts and security updates, GitHub-owned actions only, read-only default workflow permissions. The owner said on 2026-10-10 they would do this before dispatching; Dependabot update jobs ran on `main` after the first push, so check which settings are in force.
- Gate G3 stays `PENDING` (Legal sign-off on the SWIFT-licensed ESMA XSD terms): no ESMA schema may be fetched; SP-E and P5 wait for it.
- The Appendix C owner notes of the P0 plan (about 15) need decisions before P3; the Compliance owner must be named before P6a; spec 17.4 items (incumbent solution, number of reporting entities, hardware-key approvers, release-1 cut lines).
- `main` carries a stub `spikes-run.yml`; the merge of `p1a/spikes` will conflict on that file: take the `p1a/spikes` version.
