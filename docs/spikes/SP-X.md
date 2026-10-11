# SP-X report
spike: SP-X
status: PASS
scope: FULL
evidence_level: RAW
criteria_digest: d85eca736beb3ba4826bdbd6b160bba9e40cd838f7227b41c97140730d5b9447
source_git_sha: 833cba37e38be4fc7efe045887a1fe6e677dae4a
runs: 1 (vmCount 1)
time_box: used 95 of 480 minutes, hit: no

## Criteria

| Id | Verdict | Basis | Lower | Point | Upper | Threshold | Margin | Reason |
|---|---|---|---|---|---|---|---|---|
| X-1a | PASS (single VM) | MEASURED | 30376.313558 | 30875.296199 | 31171.298867 | at least 3472 | 0.285028873 | lower bound 30376.313558371 is at least 4461.620247056 (threshold 3472, margin 0.285028873) |
| X-1b | PASS (single VM) | MEASURED | 45194.139289 | 45716.638963 | 46551.079279 | at least 3472 | 0.285028873 | lower bound 45194.139289097 is at least 4461.620247056 (threshold 3472, margin 0.285028873) |
| X-2a | PASS | DERIVED | - | - | - | at least 3472 | 0 | observed 41505, at least 3472 required |
| X-2b | FAIL | DERIVED | - | - | - | at least 3472 | 0 | observed 105, at least 3472 required |
| X-3 | FAIL | DERIVED | - | - | - | at most 0.15 | 0 | observed 1, at most 0.15 required |
| X-4 | PASS | MEASURED | - | - | - | - | 0 | recorded, 7 observations; value 87716.925 records/s per thread (writer, four threads busy) |
| X-5 | PASS | DERIVED | - | - | - | at least 1 | 0 | observed 1, at least 1 required |
| X-6 | PASS | DERIVED | - | - | - | at most 0 | 0 | observed 0, at most 0 required |

## Warnings

- forks share one VM, bounds are optimistic

## Decisions

- assemble.latencyMs.1000: 104.02
- assemble.latencyMs.100000: 5866.03
- assemble.latencyMs.25000: 1576.724
- assemble.latencyMs.5000: 401.137
- assemble.latencyMs.ALL.1000: 104.02
- assemble.latencyMs.ALL.100000: 5866.03
- assemble.latencyMs.ALL.25000: 1576.724
- assemble.latencyMs.ALL.5000: 401.137
- assemble.latencyMs.SAMPLED\_10.1000: 74.796
- assemble.latencyMs.SAMPLED\_10.100000: 3519.651
- assemble.latencyMs.SAMPLED\_10.25000: 947.597
- assemble.latencyMs.SAMPLED\_10.5000: 324.974
- bisect.records: 100000
- bisect.secondsPerDefect: 4.037
- bisect.validationsPerDefect: 26
- bisect.validationsPerDefect.k10: 18.9
- bisect.validationsPerDefect.k100: 15.67
- gate.file100k.seconds.t1: 2.081
- gate.file100k.seconds.t4: 3.265
- gate.fileRecords: 100000
- integrated.recPerSec.ALL: 30875.296
- integrated.recPerSec.SAMPLED\_10: 45716.639
- integrated.threads: 4
- model.ALL.0.001.lowerBound: 41505.541
- model.ALL.0.001.point: 46060.389
- model.ALL.0.01.lowerBound: 41505.541
- model.ALL.0.01.point: 46060.389
- model.ALL.0.lowerBound: 41505.541
- model.ALL.0.point: 46060.389
- model.SAMPLED\_10.0.001.lowerBound: 1031.907
- model.SAMPLED\_10.0.001.point: 1073.547
- model.SAMPLED\_10.0.01.lowerBound: 105.911
- model.SAMPLED\_10.0.01.point: 109.805
- model.SAMPLED\_10.0.lowerBound: 69593.537
- model.SAMPLED\_10.0.point: 82771.602
- model.deviation.ALL.0: 0.49182
- model.minLowerBound.ALL: 41505
- model.minLowerBound.SAMPLED\_10: 105
- policy: "ALL"
- policyReason: "every criterion of policy:ALL passes (X-1a PASS, X-2a PASS)"
- schema.choices: 3
- schema.leafElementsUnderTx: 65
- schema.maxDepth: 5
- schema.maxEnumerationSize: 250
- schema.optionalShare: 0.452
- schema.outsideTargets: \[\]
- schema.patternAlternations: 3
- schema.unboundedGroups: 2
- xml.avgRecordBytes: 2775.8
- xml.write.mbPerSec: 243.485
- xml.write.mbPerSec.t1: 441.689
- xml.write.recPerSec.perThread: 87716.925
- xml.write.recPerSec.t1: 159121.437
- xsd.perRecord.usPerRecord: 42.796
- xsd.perRecord.usPerRecord.t1: 25.269

## Open

- none

## Environment

- CPU model: AMD EPYC 9V45 96-Core Processor
- vCPUs: 4
- Physical cores: unknown
- Memory (bytes): 16766414848
- Image version: 20261004.327.1

P-5 steal discard observable: no

## Runs

| Run | Attempt | Part | Git SHA | CPU model | Image |
|---|---|---|---|---|---|
| 38108080155 | 1 | all | 833cba37e38be4fc7efe045887a1fe6e677dae4a | AMD EPYC 9V45 96-Core Processor | 20261004.327.1 |

Git SHAs of the pooled runs: 833cba37e38be4fc7efe045887a1fe6e677dae4a

---

## Interpretation (hand-written; the part above the line is generated and recomputed)

Run 38108080155, one dispatch (attempt 1, nothing re-run), one VM (AMD EPYC 9V45, 4 vCPU; `lscpu` in the guest reports 2 cores x 2 threads, the physical cores are unknown; image 20261004.327.1), source `833cba3`. The run job took 40 minutes of runner time against a 275-minute deadline. The "95 of 480 minutes" in the header is the controller's active working time on SP-X from the start to the pause at 02:49Z (the wait for the dispatch is not counted, Q4); work after the resume at 05:10Z is not in it, and the final figure after the ledger stop is in `docs/spikes/timebox/SP-X.tsv`.

**How to read the status line.** The two per-record policies are alternatives: the criteria carry a group (`policy:ALL`, `policy:S10`), and the spike is PASS when one group passes and every ungrouped required criterion passes (X-4, X-5 and X-6 do). The PASS above is the policy ALL group (X-1a and X-2a). The SAMPLED_10 group fails: X-2b is a FAIL and X-3 (the model check, listed in that group) is a FAIL. It is not a statement that everything passed.

**What the numbers say**

- **Policy ALL meets the gate with large headroom (X-1a PASS, single VM).** The integrated assemble stage (writer, per-record XSD of every record, file write, SHA-256 and the whole-file XSD gate; four threads, each looping whole cycles of 10,000 records, about 28 MB per file) seals 30,875 records/s (one-sided 95% order-statistic bounds of the median of 15 forks: 30,376 and 31,171). The pass line including the noise margin is 4,462, so the lower bound is 6.8 times the pass line and 8.7 times the bare 3,472 gate. At that rate a day of 50 million records would take about 27 minutes of assembly, if all four vCPUs of this runner class did nothing else. This is an extrapolation from a sustained rate over whole file cycles (burst-equivalent, spec 16); no run assembled 50 million records. It is compared with the 4-hour margin that the owner accepted as the Q1 default (decisions.md, 2026-10-10; spec 5.6 gives four hours as the burst window). Whether the whole T+1 chain fits is not shown here: spec 5.6 requires the sum of all stages to fit, and the other stages are pending (C1).
- **Policy SAMPLED_10 passes the integrated measurement (X-1b PASS, single VM) but fails the modelled failure path (X-2b FAIL).** With 10% of records validated the integrated stage seals 45,717 records/s (bounds 45,194 and 46,551), 1.48 times policy ALL at defect rate 0. A defect that sampling misses fails the whole-file gate of a 100,000-record file and costs a bisect (26 validations, 4.04 s for one defect, measured on one thread on a 100,000-record file) and a rebuild. The modelled capacity on the component lower bounds is 69,594 records/s at defect rate 0, 1,032 at 0.1% and 105.9 at 1%; the minimum, rounded down to 105, is the X-2b observation. The X-2b FAIL does not depend on the model error below: the failure terms dominate (the point estimates give 1,074 and 110 records/s). The bisect price is charged at the one-defect cost for every defect (the measured validations per defect fall to 18.9 for 10 and 15.7 for 100 defects) and was measured on one thread alone; these two simplifications act in opposite directions, and with either correction the capacity stays far below 3,472 (about 1,750 and 180 records/s with the 100-defect price).
- **The capacity model is not validated (X-3 FAIL).** On the component point estimates the model gives 46,060 records/s for policy ALL at defect rate 0 (11.4 microseconds per record for the writer, 42.8 for the per-record XSD and 32.7 for the gate, 86.8 in total per thread); the integrated stage measured 30,875 (129.6 microseconds per record and thread), so the model overestimates by 49.2% (limit 15%), and 42.7 microseconds per record are not in the model. The model lacks the SHA-256 and the read-back of the file, the file write, the per-record flush, the capture and wrapping copies, allocation and garbage collection and the wait for the slowest lane at the end of each cycle; which of these causes the gap was not measured, and the small component rings are a second candidate. What the data show: the gap is as large under SAMPLED_10 (87.5 microseconds measured against 48.3 modelled, so 45,717 measured against 82,772 modelled, +81%), where only a tenth of the records is validated, so the validator is not the cause; and one thread alone shows a gap of only 6.3 microseconds (58.7 per record in the 100,000-record latency run against 52.4 from the one-thread components), so what the model lacks costs about 6 microseconds alone and about 43 with four lanes together, more than the SMT slow-down of the components explains. The mechanism of that growth is open. X-3 was checked for policy ALL at defect rate 0 only, where the model has no failure-path term; its bisect and rebuild terms have never been compared with a measurement.
- **What X-2a adds.** X-2a (minimum modelled capacity 41,505, rounded down from 41,505.5, equal at all three defect rates) is the model on the ALL components with no failure term: with every record validated no defect reaches the file gate and the model has no cost for catching and quarantining a record, so the equality across rates is by construction. Scaled by the X-3 deviation it would be about 27,800 records/s, still 8 times the gate. X-2a adds nothing to X-1a; the measured 30,875 is the evidence for policy ALL.
- **Per-record policy.** The importer derives `ALL` mechanically ("every criterion of policy:ALL passes"); the owner records the policy at the freeze. Policy ALL was measured integrated at defect rate 0 only (X-1a); at defect rates 0.1% and 1% it was only modelled (X-2a). The modelled SAMPLED_10 falls below the gate at 0.1% and 1%, and at defect rate 0 sampling buys 1.5 times. On this evidence ALL is the policy that meets the gate; the choice stays with the owner.
- **Component figures (X-4, informational).** The component figures are medians of 7 forks each (the informative protocol: 2 s windows, median of the window rates), published without bounds, and the four-thread figures are the aggregate of four threads divided by four; the integrated figures are medians of 15 forks. Writer: 159,121 records/s (441.7 MB/s) for one thread alone and 87,717 records/s per thread (243.5 MB/s) with four threads busy; the average serialised record is 2,775.8 bytes. Per-record XSD: 25.3 microseconds alone and 42.8 with four threads busy. Whole-file XSD gate on 100,000 records: 2.08 s for one thread alone and 3.27 s with four busy. Bisect validations per defect on 100,000 records: 26 for one defect, 18.9 per defect for 10, 15.7 per defect for 100.
- **Stage latency per file size, policy ALL (input for C1), one thread on an otherwise idle runner, one file per size per fork, median over 7 forks (a median, not a p99):** 104 ms for 1,000 records, 401 ms for 5,000, 1,577 ms for 25,000, 5,866 ms for 100,000 (policy SAMPLED_10: 75, 325, 948 and 3,520 ms). Per record this falls from 104 to 58.7 microseconds, because the small files include JIT warm-up and fixed costs (one untimed warm-up file of 2,000 records precedes the sizes, which run in ascending order in one JVM). These figures are not the latency under load: with four lanes busy a lane needs 129.6 microseconds per record, so a file of 10,000 records takes about 1.3 s there against about 0.7 s alone. The budget in C1 and the inputs for N and T must use the loaded figure, or say that they are unloaded. Input for the micro-batch N and T (spec 7.8; N and T are set together with SP-L and SP-M): assembly gives no reason for N below about 5,000 records and little gain above about 25,000 (the per-record cost is within 7.5% of the 100,000-record figure from 25,000 up), and a time cap T has to cover the latency of the chosen N (1.6 s unloaded for 25,000 records).
- **XSD 1.1 (X-5).** The JDK validator rejects the self-authored XSD 1.1 probe (one `xs:assert`; 1 of 1), consistent with the JDK validator being XSD 1.0 only; this is the evidence behind the spec 5.3 XSD 1.1 flip condition. Spec 5.3 treats the ESMA MiFIR schemas as XSD 1.0 (verified) and the EMIR auth.030 and auth.108 schemas as unverified until SP-E; no schema was read here (gate G3). If a destination requires XSD 1.1, the flip row applies (stay on the JVM with Xerces-J or Saxon-EE behind the validator port) and the costs above do not carry over.
- **Schema shape (X-6).** All shape statistics of the synthetic schema are inside their declared targets (0 outside; 65 leaf fields under the record element, depth 5, optional share 0.452, 3 choices, 2 unbounded groups, enumerations up to 250 values, 3 pattern alternations).

**Limits of this report**

- **The verdict holds for the declared synthetic shape only (RF9).** The schema is self-authored and invented (gate G3 stays PENDING; no ESMA schema or workbook was read). Real schemas may cost more per record through facet and pattern complexity, longer code lists, deeper structures, identity constraints (the synthetic schema has none; their cost in the file gate and the cross-record errors they catch are not measured) or XSD 1.1 constructs. Re-run SP-X if SP-E, once G3 is cleared, finds a statistic that differs from the declared one by more than a factor of two.
- **One VM, one dispatch, and what the protocol does for the integrated series.** All forks share one VM, so the bounds are optimistic (the report labels X-1a and X-1b "single VM"). Each integrated fork is a fresh JVM (`-Xms6g -Xmx6g`) with one untimed cycle (40,000 records, about 1.3 s) and then three timed cycles (about 3.9 s) whose records are divided by their elapsed time; the slowest lane sets the time of each cycle. For such cycle workloads the warm-up stability test of the protocol (three windows within 3%) does not run, so "no fork flagged unstable" says nothing about warm-up; any JIT compilation still going on in the timed cycles lowers the rate. No fork was discarded. The 15 fork values range from 28,686 to 33,084 records/s for X-1a (14% of the mean; population standard deviation 3.2%; the slowest and fastest are 7.0% below and 7.3% above the mean) and from 43,758 to 47,589 for X-1b (8.4%; 2.4%). The CPU-steal discard could not act because steal is not observable on this runner. Hosted runners differ in hardware between runs: this run used an AMD EPYC 9V45, the SP-R run an Intel Xeon Platinum 8370C and the selftest an AMD EPYC 7763; the spread across hardware was not measured. The noise margin is 28.5%. The headroom of 8.7 times is large against the effects that can be bounded here (spread, noise margin), but durability and the real schema are not bounded by this run.
- **Records are recycled in every series, and all of them are valid.** The writer series walks a ring of 5,000 records per thread and the per-record XSD series a ring of 2,000. The integrated stage does not generate records during the measurement either: each of its four lanes holds a ring of 10,997 pre-generated records (a file of 10,000 plus 997, so that a file does not start at the same record every cycle; 43,988 distinct records over the four lanes) and walks it round across the cycles, so in the four cycles of a fork (40,000 records per lane) each record is written and validated about 3.6 times. Recycled records can flatter the writer and the validator through cache and branch-prediction effects (RF1). The data bound that effect on validation only loosely: a validation costs about 46.7 microseconds in the integrated stage (the difference between the ALL and SAMPLED_10 runs divided by 0.9 of a record) against 42.8 in the component series, so the larger ring shows no flattering of the component figure beyond about 10%. No defective record is in any ring (defect rate 0). The integrated rate is the decisive number, but for this record set only.
- **What the integrated rate includes and excludes.** It includes the writer, the per-record validation under the policy, the file write, the SHA-256 and the whole-file gate, counted over whole file cycles. The file goes through the page cache of the runner's ext4 root volume (NVMe): it is closed without fsync, the SHA-256 read-back and the gate read it from memory (four files of 27.8 MB, 111 MB, against 16.8 GB of memory, so nothing is evicted) and the next cycle overwrites it. The rate therefore contains the CPU and system-call cost of writing and reading about 86 MB/s and 171 MB/s, but not device latency and not durability (fsync of the file, or the PUT into the evidence store); the cost of making the file durable is not measured in SP-X and is outside the 8.7 times headroom. The stage receives pre-built field models in memory; the projection from decision data to those models (spec 9.1, P8) is not costed. Also excluded: the other stages of the T+1 chain (ingest, decisions and the ledger (SP-L), the evidence store, transport and the destination). There was no defect injection in the integrated runs (defect rate 0): the quarantine of defective records, a rebuild and the bisect are not in the integrated rate. For policy ALL the model assumes the per-record validation catches every defect that the file gate would; the synthetic defects are all record-level (six kinds), so that holds by construction here and not necessarily for real files (a header or cross-record error is caught only by the file gate).
- **Cost model of the per-record gate.** Policy ALL validates the bytes of each record as the writer produced them, wrapped in the smallest valid file (a 219-byte header and trailer, 7.9% of an average record), with one reused JDK validator per lane built from a once-compiled schema (secure processing on, no external access, reset and re-secured before every record). A defective record fails the cycle (unit tests, six defect kinds). The run itself did not record the count of validated records; the difference between the ALL and SAMPLED_10 runs, 46.7 microseconds per validated record against 42.8 in the component series, is the indirect check. The error path (an error object and message for a defective record) is not in the rates. The whole-file gate runs with the JDK limits on the size of general entities switched off (files of this size exceed the default); external access and the entity expansion limit stay on, which is right for files the platform wrote itself and must not be copied to the validation of inbound files.
- **Not measured:** allocation per record (STRETCH), garbage-collection pauses and tail latency (the rate includes them; the tail does not appear), the stretch parts, and any heap size other than the fixed 6 GB of the forks.

**What this means for later plans.** For the synthetic shape, on one VM and at defect rate 0, the measured assemble stage does not call for a faster XSD validator or for sampling (spec 8.2 keeps per-record XSD on for every record). Whether it stays on for every record is the owner's decision at the freeze. P8's entry clause for the per-record XSD policy and the file-gate throughput (SP-X) is then covered; its other entries (SP-S, SP-E, gate G3) are not affected by this report. The latencies above feed the latency budget (C1) and, with SP-L and SP-M, the micro-batch inputs N and T (P7a).
