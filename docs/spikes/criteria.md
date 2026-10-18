# Criteria of the four spikes

Digest: d85eca736beb3ba4826bdbd6b160bba9e40cd838f7227b41c97140730d5b9447

Rows whose source contains PLAN are placeholders: the owner confirms them in `criteria.lock` before the first full run.

| Id | Spike | Metric | Rule | Unit | Source | Req | Noise | Resources | Min runs | Basis | Group |
|---|---|---|---|---|---|---|---|---|---|---|---|
| R-2a | SP-R | Records/s per thread, 4 threads busy, worst-case shape S2 (every row of a 200-rule pack evaluated), in-house layer | at least 10000 | records/s per thread | SPEC 6.6; shape and per-vCPU reading PLAN (Q3) | yes | yes | CPU, MEMORY | 0 | MEASURED | layer:inhouse |
| R-2b | SP-R | Records/s per thread, 4 threads busy, worst-case shape S2 (every row of a 200-rule pack evaluated), cel-java layer | at least 10000 | records/s per thread | SPEC 6.6; shape and per-vCPU reading PLAN (Q3) | yes | yes | CPU, MEMORY | 0 | MEASURED | layer:cel |
| R-3 | SP-R | One-thread S2 rate at 200 rules; microseconds per record at 50, 500 and 5,000 rules, per layer | measured only | records/s, microseconds per record | PLAN | no | no | CPU | 0 | MEASURED |  |
| R-4 | SP-R | Tier-1 explain render p99 (L-9 gets 900; together 1 s) | at most 100 | ms | SPEC 6.6; split PLAN | yes | yes | CPU | 0 | MEASURED |  |
| R-7 | SP-R | Per-record budget and poison-circuit (N, M) defaults: consistency violations (values are recorded) | at most 0 | violations | SPEC 6.6, 7.3; formulas PLAN | yes | no | CPU | 0 | MODELLED |  |
| R-8 | SP-R | Differential disagreements: in-house against the P0 skeleton rules (4 rules, must be 0), and layer against layer on a 100,000-record sample (informational) | at most 0 | disagreements | PLAN | no | no | CPU | 0 | DERIVED |  |
| L-2 | SP-L | Synchronous micro-batch commit p99 per 60 s window at the reference N | at most 1000 | ms | PLAN | yes | yes | CPU, DISK | 0 | MEASURED |  |
| L-4 | SP-L | Run B (primary memory-limited to the day's table-to-memory ratio): failed runs | at most 0 | runs | PLAN (RF14) | yes | no | CPU | 2 | EXTRAPOLATED |  |
| L-6 | SP-L | Restore RTO (backup restore plus gap replay), cold cache, fsync on, at 10M keys, projected from at least 3 measured sizes; fewer sizes is NOT_RUN | at most 4 | hours | PLAN from SPEC 11.4 | yes | yes | CPU, DISK | 0 | EXTRAPOLATED |  |
| L-7 | SP-L | Restore RTO as L-6 at 100M keys, with the physical floor | at most 4 | hours | PLAN from SPEC 11.4 | yes | yes | CPU, DISK | 0 | EXTRAPOLATED |  |
| L-9 | SP-L | Primary-key fetch of a decision under the burst, p99 (with R-4: 1 s) | at most 900 | ms | PLAN (with R-4: 1 s) | yes | yes | CPU, DISK | 0 | MEASURED |  |
| L-10 | SP-L | Decision lookup p99 with 1,826 daily partitions; monthly granularity beside it | at most 50 | ms | PLAN | yes | yes | CPU, DISK | 0 | MEASURED |  |
| L-11 | SP-L | Class (b) seq counter row, synchronous commit, best writer count (10 times 9.94/s = 3,470 records/s / 500 reports per file + 3 other events/s; the 500 and the 3 are planning assumptions) | at least 100 | events/s | PLAN; SPEC 11.2 | yes | yes | CPU, DISK | 0 | MEASURED |  |
| X-1a | SP-X | Integrated 4-thread assemble stage (writer, per-record XSD under policy ALL, file write, SHA-256, whole-file gate; rate over whole file cycles) | at least 3472 | records/s | SPEC 16 SP-X; margin 4 h PLAN (Q1) | yes | yes | CPU, MEMORY | 0 | MEASURED | policy:ALL |
| X-1b | SP-X | Integrated 4-thread assemble stage as X-1a under policy SAMPLED_10 | at least 3472 | records/s | SPEC 16 SP-X; margin 4 h PLAN (Q1) | yes | yes | CPU, MEMORY | 0 | MEASURED | policy:S10 |
| X-3 | SP-X | Model against the integrated ALL run, relative deviation at defect rate 0 | at most 0.15 | relative deviation | PLAN | yes | no | CPU | 0 | DERIVED | policy:S10 |
| X-6 | SP-X | Synthetic-schema shape statistics outside their declared targets | at most 0 | statistics | PLAN (RF9) | yes | no | CPU | 0 | DERIVED |  |
| R-1a | SP-R | MUST semantic vectors failed or unsupported, in-house layer | at most 0 | vectors | SPEC 6.2, I3 (vectors from the spec text) | yes | no | CPU | 0 | DERIVED | layer:inhouse |
| R-1b | SP-R | MUST semantic vectors failed or unsupported, cel-java layer | at most 0 | vectors | SPEC 6.2, I3 (vectors from the spec text) | yes | no | CPU | 0 | DERIVED | layer:cel |
| R-5 | SP-R | Hot swap: batches that saw two packs plus lost records (activation time p99 is recorded only) | at most 0 | batches and records | SPEC 6.5 step 6 | yes | no | CPU | 0 | DERIVED |  |
| R-6a | SP-R | Runs whose cost units differ over 1,000 repeats of one input, in-house layer | at most 0 | runs | SPEC 6.6, 14.1 | yes | no | CPU | 0 | DERIVED | layer:inhouse |
| R-6b | SP-R | Runs whose cost units differ over 1,000 repeats of one input, cel-java layer | at most 0 | runs | SPEC 6.6, 14.1 | yes | no | CPU | 0 | DERIVED | layer:cel |
| G-1 | SP-G | The six EMIR action types encoded in the pack format | at least 6 | action types | SPEC 16 SP-G | yes | no | CPU | 0 | DOCUMENT |  |
| G-2 | SP-G | Register rows OPEN or PROPOSED (not dispositioned by the owner); INCONCLUSIVE until then | at most 0 | rows | SPEC 16 SP-G | yes | no | CPU | 1 | DOCUMENT |  |
| G-3 | SP-G | Register rows UNFIXABLE (a redesign, not a format fix) | at most 0 | rows | SPEC 16 SP-G | yes | no | CPU | 0 | DOCUMENT |  |
| G-4 | SP-G | Decision chains of one execution under two regimes coexist on the P0 in-memory ledger and the modelled (regime, event_identity, leg) report_subject keys behave regime-scoped: failures (the SQL-level form is L-12) | at most 0 | failures | SPEC 16 SP-G, 10 | yes | no | CPU | 0 | DERIVED |  |
| L-1 | SP-L | Open-loop runs at 3,470 records/s with a live synchronous standby that fail to sustain it | at most 0 | runs | SPEC 5.6, D6 | yes | no | CPU | 2 | MEASURED |  |
| L-3 | SP-L | Closed-loop capacity, same configuration | at least 3470 | records/s | SPEC 5.6 | yes | yes | CPU, DISK | 0 | MEASURED |  |
| L-5 | SP-L | Bytes per decision row with indexes | at most 500 | bytes | SPEC 5.6 | yes | no | CPU | 0 | MEASURED |  |
| L-8 | SP-L | Full rebuild from evidence: seconds per million keys at the measured sizes, projected hours at 10M and 100M | measured only | seconds per million keys, hours | SPEC 11.4, I10 | yes | no | CPU | 0 | EXTRAPOLATED |  |
| L-12 | SP-L | SQL-level constraint checks that fail (names of the P0 contract tests, L1) | at most 0 | checks | SPEC 6.3, 10, 14.1 | yes | no | CPU | 0 | DERIVED |  |
| L-13 | SP-L | Samples without a sync or quorum streaming standby during measured runs | at most 0 | samples | SPEC I1, 5.6 | yes | no | CPU | 0 | DERIVED |  |
| L-14 | SP-L | Ledger bytes per day by table; evidence objects per day and PUT/s at journal granularity; micro-batch N knee and commit p99 at N; decision_head growth | measured only | bytes, objects, ms | SPEC 5.6, 7.8, 11.2 | yes | no | CPU | 0 | MODELLED |  |
| L-15 | SP-L | Replay lag and cancelled queries of replica and synchronous standby under console load | measured only | seconds, queries | SPEC 16 SP-L | no | no | CPU | 0 | MEASURED |  |
| X-2a | SP-X | Modelled capacity at defect rates 0, 0.1% and 1% including bisect cost, policy ALL | at least 3472 | records/s | SPEC 8.2, 16 SP-X | yes | no | CPU | 0 | DERIVED | policy:ALL |
| X-2b | SP-X | Modelled capacity at defect rates 0, 0.1% and 1% including bisect cost, policy SAMPLED_10 | at least 3472 | records/s | SPEC 8.2, 16 SP-X | yes | no | CPU | 0 | DERIVED | policy:S10 |
| X-4 | SP-X | Writer MB/s and records/s; per-record XSD microseconds; whole-file gate seconds for 100,000 records (1 and 4 threads); bisect validations; stage latency for 1,000, 5,000, 25,000 and 100,000 records | measured only | MB/s, records/s, microseconds, seconds, ms | SPEC 16 SP-X, 5.6 | yes | no | CPU | 0 | MEASURED |  |
| X-5 | SP-X | A self-authored XSD 1.1 construct is rejected by the JDK validator | at least 1 | constructs | SPEC 5.3 | yes | no | CPU | 0 | DERIVED |  |
| ST-1 | HARNESS | Self-test count criterion | at most 0 | count | HARNESS self-test | yes | no | CPU | 0 | DERIVED |  |
| ST-2 | HARNESS | Self-test fork series | at least 1 | units/s | HARNESS self-test | yes | yes | CPU | 0 | MEASURED |  |

## Parameters

- availableMarginHours: 4
- backupIntervalHours: 24
- burstRecordsPerSecond: 3470
- recordsPerDay: 50000000
- rtoLimitHours: 4

## Time-boxes (minutes)

- HARNESS: 480
- SP-G: 240
- SP-L: 960
- SP-R: 480
- SP-X: 480
