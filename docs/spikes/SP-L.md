# SP-L report
spike: SP-L
status: FAIL
scope: FULL
evidence_level: RAW
criteria_digest: d85eca736beb3ba4826bdbd6b160bba9e40cd838f7227b41c97140730d5b9447
source_git_sha: f1e63d5643404d857b0f737b0bbb17c61ff069a9
runs: 3 (vmCount 1)
time_box: used 157 of 960 minutes, hit: no

## Criteria

| Id | Verdict | Basis | Lower | Point | Upper | Threshold | Margin | Reason |
|---|---|---|---|---|---|---|---|---|
| L-1 | PASS | MEASURED | - | - | - | at most 0 | 0 | observed 0, at most 0 required |
| L-2 | INCONCLUSIVE | MEASURED | 984.379768 | 1076.87552 | 1247.852622 | at most 1000 | 27.697839962 | noise margin 27.697839962 is above the maximum 0.50 |
| L-3 | INCONCLUSIVE | MEASURED | 4333.333333 | 12000 | 13250 | at least 3470 | 27.697839962 | noise margin 27.697839962 is above the maximum 0.50 |
| L-4 | FAIL | EXTRAPOLATED | - | - | - | at most 0 | 0 | observed 2, at most 0 required |
| L-5 | PASS | MEASURED | - | - | - | at most 500 | 0 | observed 335, at most 500 required |
| L-6 | INCONCLUSIVE | EXTRAPOLATED | 0.406926 | 0.407534 | 0.408326 | at most 4 | 27.697839962 | noise margin 27.697839962 is above the maximum 0.50 |
| L-7 | INCONCLUSIVE | EXTRAPOLATED | 0.494726 | 0.502859 | 0.511876 | at most 4 | 27.697839962 | noise margin 27.697839962 is above the maximum 0.50 |
| L-8 | PASS (EXTRAPOLATED) | EXTRAPOLATED | - | - | - | - | 0 | recorded, 2 observations; value 20.536 seconds per million keys (the slowest cold rebuild) |
| L-9 | INCONCLUSIVE | MEASURED | 0.133453 | 0.142905 | 0.169716 | at most 900 | 27.697839962 | noise margin 27.697839962 is above the maximum 0.50 |
| L-10 | INCONCLUSIVE | MEASURED | 233.184362 | 254.620818 | 389.795758 | at most 50 | 27.697839962 | noise margin 27.697839962 is above the maximum 0.50 |
| L-11 | INCONCLUSIVE | MEASURED | 834.8 | 1015.5 | 1157 | at least 100 | 27.697839962 | noise margin 27.697839962 is above the maximum 0.50 |
| L-12 | PASS | DERIVED | - | - | - | at most 0 | 0 | observed 0, at most 0 required |
| L-13 | PASS | DERIVED | - | - | - | at most 0 | 0 | observed 0, at most 0 required |
| L-14 | PASS | MODELLED | - | - | - | - | 0 | recorded, 4 observations; value 48803840000 bytes per day (ledger tables of a 50M-record day) |
| L-15 | NOT_RUN | MEASURED | - | - | - | - | 0 | no measurements; reading from the synchronous standby remains not allowed (spec 12.1) |

An EXTRAPOLATED PASS needs the owner's `ack:` line in the freeze record.

## Warnings

- forks share one VM, bounds are optimistic
- mixed hardware: the pooled runs used more than one CPU model

## Decisions

- bytes.perCandidate.attempt: 0.082
- bytes.perCandidate.decision: 334.93
- bytes.perCandidate.decision\_head: 60.088
- bytes.perCandidate.delivery: 68.362
- bytes.perCandidate.delivery\_event: 0.041
- bytes.perCandidate.ingest: 174.899
- bytes.perCandidate.ingest\_checkpoint: 0.164
- bytes.perCandidate.ingest\_registry: 157.573
- bytes.perCandidate.lease: 0.041
- bytes.perCandidate.outbox: 0.205
- bytes.perCandidate.report\_subject: 103.547
- bytes.perCandidate.report\_version: 75.981
- bytes.perCandidate.seq\_counter: 0.041
- bytes.perCandidate.trn\_assignment: 0.123
- bytes.perDay.attempt: 4096000
- bytes.perDay.decision: 16746496000
- bytes.perDay.decision\_head: 3004416000
- bytes.perDay.delivery: 3418112000
- bytes.perDay.delivery\_event: 2048000
- bytes.perDay.ingest: 8744960000
- bytes.perDay.ingest\_checkpoint: 8192000
- bytes.perDay.ingest\_registry: 7878656000
- bytes.perDay.lease: 2048000
- bytes.perDay.outbox: 10240000
- bytes.perDay.report\_subject: 5177344000
- bytes.perDay.report\_version: 3799040000
- bytes.perDay.seq\_counter: 2048000
- bytes.perDay.total: 48803840000
- bytes.perDay.trn\_assignment: 6144000
- capacity.records: 6185000
- capacity.windows: 10
- decisionHead.bytesPerDay: 3004416000
- decisionHead.rowsPerDay: 24068500
- extrapolationNote: "L-1 and L-4 are counts over 20-minute open-loop slices (about 4.2M records, fewer when the disk of the runner caps them; the report prints the covered seconds against the 1,200 s design) at 3,470 records/s with a live quorum synchronous standby, not over a full day of 50M records. Run A is cache-hot; run B repeats it with the primary limited to the memory that gives the slice the table-to-memory ratio of a full 50M-record day on 16 GiB (derived from the measured bytes per record), and must also sustain. Restore RTO at 10M and 100M keys (L-6, L-7) is projected by a Theil-Sen line from at least three measured cold-cache sizes with fsync on, raised to a physical floor, with the replay of the gap added for the stated backup interval; the full rebuild (L-8) is projected linearly from its measured seconds per million keys. These readings are EXTRAPOLATED and need the owner's acknowledgement in the freeze record."
- fetch.decisionRows.firstBlock: 30000
- fetch.decisionRows.lastBlock: 270000
- fetch.schedule: "warm-up 9 s, then 15 blocks of 200 lookups every 5 s over a burst of 90 s, each on a fresh sample of the heads written so far"
- hotrow.bestWriters: 2
- hotrow.requiredEventsPerSecond: 9.94
- hotrow.w1.eventsPerSecond: 849.2
- hotrow.w16.eventsPerSecond: 906.05
- hotrow.w2.eventsPerSecond: 1116.45
- hotrow.w4.eventsPerSecond: 839.1
- hotrow.w8.eventsPerSecond: 773.2
- ingest.bytesPerCandidate: 969
- ingest.bytesPerCandidateMeasured: true
- ledger.commitP99Ms: 9582.423
- ledger.ingestCommitP99Ms: 9582.423
- ledger.kneeCommitP99Ms: 2777.579
- ledger.kneeN: 10000
- ledger.matrix.BATCHED\_INSERT.w1.kneeN: 25000
- ledger.matrix.BATCHED\_INSERT.w1.n10000.commitP99Ms: 1942.482
- ledger.matrix.BATCHED\_INSERT.w1.n10000.recordsPerSecond: 10954
- ledger.matrix.BATCHED\_INSERT.w1.n10000.valid: true
- ledger.matrix.BATCHED\_INSERT.w1.n2000.commitP99Ms: 571.492
- ledger.matrix.BATCHED\_INSERT.w1.n2000.recordsPerSecond: 11700
- ledger.matrix.BATCHED\_INSERT.w1.n2000.valid: true
- ledger.matrix.BATCHED\_INSERT.w1.n25000.commitP99Ms: 2440.799
- ledger.matrix.BATCHED\_INSERT.w1.n25000.recordsPerSecond: 14291
- ledger.matrix.BATCHED\_INSERT.w1.n25000.valid: true
- ledger.matrix.BATCHED\_INSERT.w1.n500.commitP99Ms: 396.624
- ledger.matrix.BATCHED\_INSERT.w1.n500.recordsPerSecond: 8243
- ledger.matrix.BATCHED\_INSERT.w1.n500.valid: true
- ledger.matrix.BATCHED\_INSERT.w1.n5000.commitP99Ms: 1389.547
- ledger.matrix.BATCHED\_INSERT.w1.n5000.recordsPerSecond: 11489
- ledger.matrix.BATCHED\_INSERT.w1.n5000.valid: true
- ledger.matrix.BATCHED\_INSERT.w4.kneeN: 25000
- ledger.matrix.BATCHED\_INSERT.w4.n10000.commitP99Ms: 3036.234
- ledger.matrix.BATCHED\_INSERT.w4.n10000.recordsPerSecond: 16828
- ledger.matrix.BATCHED\_INSERT.w4.n10000.valid: true
- ledger.matrix.BATCHED\_INSERT.w4.n2000.commitP99Ms: 2000.709
- ledger.matrix.BATCHED\_INSERT.w4.n2000.recordsPerSecond: 11338
- ledger.matrix.BATCHED\_INSERT.w4.n2000.valid: true
- ledger.matrix.BATCHED\_INSERT.w4.n25000.commitP99Ms: 7035.577
- ledger.matrix.BATCHED\_INSERT.w4.n25000.recordsPerSecond: 18089
- ledger.matrix.BATCHED\_INSERT.w4.n25000.valid: true
- ledger.matrix.BATCHED\_INSERT.w4.n500.commitP99Ms: 795.252
- ledger.matrix.BATCHED\_INSERT.w4.n500.recordsPerSecond: 11272
- ledger.matrix.BATCHED\_INSERT.w4.n500.valid: true
- ledger.matrix.BATCHED\_INSERT.w4.n5000.commitP99Ms: 1430.211
- ledger.matrix.BATCHED\_INSERT.w4.n5000.recordsPerSecond: 16177
- ledger.matrix.BATCHED\_INSERT.w4.n5000.valid: true
- ledger.matrix.COPY.w1.kneeN: 25000
- ledger.matrix.COPY.w1.n10000.commitP99Ms: 2002.627
- ledger.matrix.COPY.w1.n10000.recordsPerSecond: 11423
- ledger.matrix.COPY.w1.n10000.valid: true
- ledger.matrix.COPY.w1.n2000.commitP99Ms: 664.701
- ledger.matrix.COPY.w1.n2000.recordsPerSecond: 13184
- ledger.matrix.COPY.w1.n2000.valid: true
- ledger.matrix.COPY.w1.n25000.commitP99Ms: 1594.52
- ledger.matrix.COPY.w1.n25000.recordsPerSecond: 18095
- ledger.matrix.COPY.w1.n25000.valid: true
- ledger.matrix.COPY.w1.n500.commitP99Ms: 213.255
- ledger.matrix.COPY.w1.n500.recordsPerSecond: 15353
- ledger.matrix.COPY.w1.n500.valid: true
- ledger.matrix.COPY.w1.n5000.commitP99Ms: 661.967
- ledger.matrix.COPY.w1.n5000.recordsPerSecond: 15314
- ledger.matrix.COPY.w1.n5000.valid: true
- ledger.matrix.COPY.w4.kneeN: 10000
- ledger.matrix.COPY.w4.n10000.commitP99Ms: 2777.579
- ledger.matrix.COPY.w4.n10000.recordsPerSecond: 22900
- ledger.matrix.COPY.w4.n10000.valid: true
- ledger.matrix.COPY.w4.n2000.commitP99Ms: 679.497
- ledger.matrix.COPY.w4.n2000.recordsPerSecond: 20618
- ledger.matrix.COPY.w4.n2000.valid: true
- ledger.matrix.COPY.w4.n25000.commitP99Ms: 4140.275
- ledger.matrix.COPY.w4.n25000.recordsPerSecond: 21600
- ledger.matrix.COPY.w4.n25000.valid: true
- ledger.matrix.COPY.w4.n500.commitP99Ms: 561.467
- ledger.matrix.COPY.w4.n500.recordsPerSecond: 16463
- ledger.matrix.COPY.w4.n500.valid: true
- ledger.matrix.COPY.w4.n5000.commitP99Ms: 1680.619
- ledger.matrix.COPY.w4.n5000.recordsPerSecond: 21473
- ledger.matrix.COPY.w4.n5000.valid: true
- ledger.openLoopP99Ms: 24183.532
- ledger.peakBacklogSeconds: 30.259
- memory.bytesPerCandidate: 969
- memory.floored: false
- memory.limitMiB: 1363
- memory.ratioDay: 2.82
- memory.sharedBuffersMiB: 340
- memory.sliceRecords: 4160000
- partition.daily1826.detachMs: 9.061
- partition.daily1826.dropMs: 25.503
- partition.daily1826.headThenFetchP99Ms: 1.21
- partition.daily1826.lookupP99Ms: 254.621
- partition.daily30.detachMs: 5.082
- partition.daily30.dropMs: 11.942
- partition.daily30.headThenFetchP99Ms: 0.966
- partition.daily30.lookupP99Ms: 145.669
- partition.daily365.detachMs: 7.965
- partition.daily365.dropMs: 23.971
- partition.daily365.headThenFetchP99Ms: 1.892
- partition.daily365.lookupP99Ms: 104.078
- partition.l10Partitions: 1826
- partition.monthly60.detachMs: 5.375
- partition.monthly60.dropMs: 312.673
- partition.monthly60.headThenFetchP99Ms: 0.586
- partition.monthly60.lookupP99Ms: 0.721
- partitions.bytesPerRowAssumed: 396
- partitions.daily1826.rowsPerPopulatedDay: 300000
- partitions.daily30.rowsPerPopulatedDay: 300000
- partitions.daily365.rowsPerPopulatedDay: 300000
- partitions.monthly60.rowsPerPopulatedDay: 300000
- partitions.standby: "dropped for the study (pg-standby.sh drop): planner and probe overhead need no standby"
- rebuild.1000000.coldCache: true
- rebuild.1000000.contentEqual: true
- rebuild.1000000.copySeconds: 57.235
- rebuild.1000000.decideSeconds: 6.005
- rebuild.1000000.readSeconds: 18.701
- rebuild.1000000.secondsPerMillionKeys: 20.536
- rebuild.1000000.wallSeconds: 20.536
- rebuild.500000.coldCache: true
- rebuild.500000.contentEqual: true
- rebuild.500000.copySeconds: 26.047
- rebuild.500000.decideSeconds: 3.352
- rebuild.500000.readSeconds: 9.798
- rebuild.500000.secondsPerMillionKeys: 19.731
- rebuild.500000.wallSeconds: 9.866
- rebuild.projectedHours.100M: 0.57
- rebuild.projectedHours.10M: 0.057
- restore.1000000.analyzeSeconds: 4.645
- restore.1000000.copySeconds: 3.408
- restore.1000000.gapObjectsSeconds: 0.007
- restore.1000000.gapRecords: 1040000
- restore.1000000.quality: "VALID"
- restore.1000000.replaySeconds: 25.54
- restore.1000000.rtoSeconds: 34.565
- restore.1000000.syncSeconds: 2.143
- restore.1000000.verifySeconds: 0.965
- restore.10000000.analyzeSeconds: 5.979
- restore.10000000.copySeconds: 30.853
- restore.10000000.gapObjectsSeconds: 0.007
- restore.10000000.gapRecords: 1040000
- restore.10000000.quality: "VALID"
- restore.10000000.replaySeconds: 29.615
- restore.10000000.rtoSeconds: 71.581
- restore.10000000.syncSeconds: 5.411
- restore.10000000.verifySeconds: 5.127
- restore.2000000.analyzeSeconds: 4.948
- restore.2000000.copySeconds: 6.723
- restore.2000000.gapObjectsSeconds: 0.006
- restore.2000000.gapRecords: 1040000
- restore.2000000.quality: "VALID"
- restore.2000000.replaySeconds: 24.915
- restore.2000000.rtoSeconds: 37.975
- restore.2000000.syncSeconds: 4.092
- restore.2000000.verifySeconds: 1.383
- restore.500000.analyzeSeconds: 3.954
- restore.500000.copySeconds: 2.028
- restore.500000.gapObjectsSeconds: 0.013
- restore.500000.gapRecords: 1040000
- restore.500000.quality: "VALID"
- restore.500000.replaySeconds: 25.532
- restore.500000.rtoSeconds: 32.379
- restore.500000.syncSeconds: 1.324
- restore.500000.verifySeconds: 0.853
- restore.5000000.analyzeSeconds: 5.305
- restore.5000000.copySeconds: 15.859
- restore.5000000.gapObjectsSeconds: 0.006
- restore.5000000.gapRecords: 1040000
- restore.5000000.quality: "VALID"
- restore.5000000.replaySeconds: 27.68
- restore.5000000.rtoSeconds: 52.085
- restore.5000000.syncSeconds: 3.244
- restore.5000000.verifySeconds: 3.234
- restore.backupIntervalHours: 24
- restore.bytesPerKey: 555
- restore.bytesPerKeyBasis: "measured on the first restore point after the load of its keys and the checkpoint, before the gap ingest and the base backup, so the gap and its objects are not in the figure"
- restore.floorRateBytesPerSecond: 359765073
- restore.floorSeconds.100M: 308.535
- restore.floorSeconds.10M: 30.853
- restore.replaySeconds: 1423.779
- rowSize.E1.bytesPerRow: 335
- rowSize.E1.heapBytes: 33906688
- rowSize.E1.indexBytes: 33079296
- rowSize.E1.rows: 200000
- rowSize.E3.bytesPerRow: 983
- rowSize.E3.heapBytes: 163389440
- rowSize.E3.indexBytes: 33079296
- rowSize.E3.rows: 200000
- volume.assumptions: "400 raw bytes a record, 500 reports a file, 1% of files sent again, 3 enrichment phases of 20,000 bytes a micro-batch: planning assumptions, not measurements"
- volume.averagePutPerSecond: 5.37
- volume.burstPutPerSecond: 32.222
- volume.enrichmentObjectsPerDay: 30000
- volume.journal.BatchAdmitted: 10000
- volume.journal.DeliveryIntent: 101000
- volume.journal.FeedbackReceived: 101000
- volume.journal.OperatorAction: 0
- volume.journal.RecoveryDecision: 1000
- volume.journal.TransportResult: 101000
- volume.journal.VersionAssigned: 10000
- volume.landingObjectsPerDay: 10000
- volume.notes: \["every record is one report, so the files per day are records over reports per file: the upper bound","all objects of the day are written inside the burst window of 4 h","each attempt is answered by one feedback file","OperatorAction is operator-driven (approvals, write-offs, pack activations: a handful a day, spec 11.2) and is counted as 0; RecoveryDecision is one per resent file","TransportResult is per attempt (file) and VersionAssigned is one batched object per micro-batch, never per record"\]
- volume.objectsPerDay: 464000
- volume.rawBytesPerDay: 20000000000
- volume.sealedFilesPerDay: 100000

## Open

- A1: valid, stopped by DURATION, covered 1198 s of the 1200 s design, 4160000 of 4160000 scheduled records completed
- A2: valid, stopped by DURATION, covered 1198 s of the 1200 s design, 4160000 of 4160000 scheduled records completed
- C1: valid, stopped by DURATION, covered 603 s of the 1200 s design, 6185000 of 6185000 scheduled records completed
- B1: FAILED, stopped by DURATION, covered 1198 s of the 1200 s design, 2995000 of 4160000 scheduled records completed; only 2835000 of 4160000 scheduled records were completed one interval after the last arrival; 265 batches were still queued at the end; the backlog grew by 1159.548839164 records per second
- B2: FAILED, stopped by DURATION, covered 1198 s of the 1200 s design, 3030000 of 4160000 scheduled records completed; only 2870000 of 4160000 scheduled records were completed one interval after the last arrival; 258 batches were still queued at the end; the backlog grew by 1095.685299913 records per second
- L-4: run B at table-to-memory ratio 2.82 (50000000 records x 969 bytes over 16 GiB), the primary limited to 1363 MiB for a slice of 4160000 records; 2 of 2 counted runs failed (FAILED: the slice that sustains when cache-hot does not sustain under the day's memory pressure)
- L-6 and L-7 assume a backup every 24 hours: the replay of the gap is the measured replay rate scaled to that interval of 50000000 records a day

## Environment

- CPU model: INTEL(R) XEON(R) PLATINUM 8573C
- vCPUs: 4
- Physical cores: unknown
- Memory (bytes): 16765378560
- Image version: 20261004.327.1

P-5 steal discard observable: no

## Runs

| Run | Attempt | Part | Git SHA | CPU model | Image |
|---|---|---|---|---|---|
| 38175053709 | 1 | ingest | f1e63d5643404d857b0f737b0bbb17c61ff069a9 | INTEL(R) XEON(R) PLATINUM 8573C | 20261004.327.1 |
| 38175053709 | 1 | misc | f1e63d5643404d857b0f737b0bbb17c61ff069a9 | AMD EPYC 9V74 80-Core Processor | 20261004.327.1 |
| 38175053709 | 1 | restore | f1e63d5643404d857b0f737b0bbb17c61ff069a9 | INTEL(R) XEON(R) PLATINUM 8573C | 20261004.327.1 |

Git SHAs of the pooled runs: f1e63d5643404d857b0f737b0bbb17c61ff069a9

---

## Interpretation (hand-written; the part above the line is generated and recomputed)

Run 38175053709, the one full dispatch (attempt 1, nothing re-run; a smoke dispatch preceded it and is not evidence), source `f1e63d5`, three parts on three VMs of the same image (20261004.327.1): ingest and restore on an Intel Xeon Platinum 8573C, misc on an AMD EPYC 9V74, each 4 vCPU (2 cores with 2 threads each, per `runner.txt`) and 16 GB. The jobs took about 98 minutes (ingest), 50 (restore) and 38 (misc) of runner time. After reading the results the owner decided on 2026-10-11 to write this report now and not to run SP-L again, so it rests on this one dispatch. The generated header says "vmCount 1" and "forks share one VM": the importer counts one run id as one VM, but the three parts ran on three VMs and only the forks within a part share one.

**How to read the status line.** FAIL comes from one criterion, L-4. Seven criteria (L-2, L-3, L-6, L-7, L-9, L-10, L-11) are INCONCLUSIVE, and the reason the evaluator gives for each is the same: the noise margin is 27.7 (2,770%), above the maximum of 0.50 at which a verdict is allowed. That margin is the pooled DISK canary spread: the 95th over the 5th percentile of the 40 probe readings of all three VMs (3,655 over 127 synchronous operations a second, minus 1). The two lowest readings (57 and 127) were taken on the ingest VM right after runs A2 and C1, and the importer pools the readings of all three parts for every criterion, so L-6 and L-7, measured on the restore VM, are judged with the probes of the other two. The margins the evaluator gives each part alone are 52.6 (ingest), 3.4 (misc) and 0.26 (restore); the sealed restore report accordingly shows L-6 and L-7 as PASS (EXTRAPOLATED, single VM). The margin is not the whole story: read against the bounds alone with the floor margin of 0.10, the rule would give L-3, L-6, L-7, L-9 and L-11 PASS and L-10 FAIL (L-10 FAIL up to a margin of 3.7), while L-2 would stay INCONCLUSIVE at every margin because its bounds (984 to 1,248 ms) straddle the 1,000 ms threshold. That is a reading of the bounds, not a verdict: the rule refuses to call them and this report does not call them. L-15 is NOT_RUN (stretch not run). L-1, L-5, L-12, L-13 and L-14 are PASS and L-8 is PASS (EXTRAPOLATED), a recorded figure.

**What the numbers say**

- **Burst sustained when the data is cached (L-1 PASS, 2 of 2 runs valid).** Runs A1 and A2 (20 minutes of open-loop load at 3,470 records/s, micro-batches of 5,000, four writers, COPY, a live quorum synchronous standby on every sample; 4,160,000 records each, 1,198 of 1,200 s covered) completed everything, with 0 sync-standby violations (L-13). That is a slice, not a day: see Extrapolation.
- **The same load did not keep up with the memory limit of a full day (L-4 FAIL, 2 of 2 runs failed).** Runs B1 and B2 repeat A with the primary limited to 1,363 MiB (`shared_buffers` 340 MiB instead of 2 GB), the memory that gives the slice (4,160,000 records at 969 measured bytes per candidate) the table-and-index-to-memory ratio of a 50-million-record day on 16 GiB (2.82). They completed 2,995,000 and 3,030,000 of the 4,160,000 scheduled records (2,500 and 2,529 records/s over the 1,198 s covered), 265 and 258 batches were still queued at the end, and the backlog grew by 1,160 and 1,096 records/s, which implies a service rate of about 2,310 and 2,370 records/s against 3,470 offered. The closed-loop run C1 (cache-hot, 603 s, no memory limit, `shared_buffers` 2 GB) reached 12,000 records/s at the median of its ten 60 s windows (range 4,083 to 16,083; 10,257 on the mean over the whole run; L-3 INCONCLUSIVE). The ratio of the two figures is 4 to 5, but it compares a closed-loop run with open-loop runs, B also differs from A in `shared_buffers` and the container memory limit, and B1 and B2 ran after A1, A2 and C1 on the same VM, not interleaved. The disk probes taken before the runs were as high before B1 and B2 (2,824 and 3,028 operations a second) as before A1 and A2 (2,763 and 2,495), so there is no sign that B started on a slower disk. The report does not isolate memory pressure as the cause of the shortfall.
- **The cause of the L-4 failure is not established by the sealed data.** The sampler (per second: replication state, WAL writes and fsync counts, checkpoints, `/proc/stat`) ran during every run, but only its violation count was kept; the per-second rows are not in the result. The PostgreSQL logs in the artefacts are the complete logs of the last cluster of each part only (ingest: run B2), a few kilobytes each, and the one in the ingest part shows one timed checkpoint and no requested one. WAL fsync, index maintenance and CPU contention on the shared VM (see the fixture bias) are all candidates and none can be excluded.
- **Commit latency under the burst (L-2 INCONCLUSIVE).** The median of the 38 per-window p99 values of the synchronous micro-batch commit is 1,077 ms (one-sided 95% order-statistic bounds 984 to 1,248 ms) against the 1,000 ms placeholder: 7.7% above it at the median, with the bounds straddling it. Each window holds about 42 batches, so each window value is in effect the window maximum (plan P-3), and the median is a median of window maxima. The windows range from 499 to 17,150 ms. Over all batches of A1 and A2 the commit p99 is 9,582 ms and the open-loop latency (arrival to commit, including queueing) has p99 24,184 ms with a largest queue of 30.3 s of arrivals (`ledger.peakBacklogSeconds`). The burst is sustained on average but with multi-second stalls whose cause is not established (the sampler rows were not kept and the disk probe right after A2 read 57 operations a second, which shows a busy disk after that run but not what caused the stalls). The latency budget should carry the stall tail, not only the window median.
- **Restore RTO (L-6, L-7 INCONCLUSIVE; point estimates far inside the 4 h limit).** Five cold-cache restore points with fsync on (cache dropped, sync inside the timer, all quality VALID) were measured, including 10,000,000 keys directly. Projected RTO at 10M keys 0.4075 h (bounds 0.4069 to 0.4083), at 100M keys 0.5029 h (0.4947 to 0.5119). About 97% of the 10M figure (1,424 of 1,467 s) and about 79% of the 100M figure (1,424 of 1,810 s) is the replay of the gap: the measured replay rate of the 10M point (29.6 s for a gap of 1,040,000 records; 24.9 to 27.7 s at the other sizes) scaled linearly to 50 million records at a backup interval of 24 hours (Q2) is 1,424 s. The restore itself (copy including sync, analyze, verify) is 42.0 s at 10M keys; with the measured 29.6 s replay of its 1,040,000-record gap the RTO of that point is 71.6 s. The projection is the Theil-Sen line through the five points (RTO without the measured replay, plus the modelled replay term): about 5 s plus 3.8 microseconds per key (pairwise slopes 3.5 to 4.4) plus the 1,424 s of replay; at 100M keys the line gives 387 s before the replay term. It is checked against a physical floor of 308.5 s at 100M keys (30.9 s at 10M); the floor is not a measured disk limit (its rate, 360 MB/s, is the fastest copy observed, that of the 10M point, so the 10M floor equals that copy time and the 100M floor is ten times it) and it did not bind. The bounds quoted cover the slope of the part that grows with size only; they do not cover the replay term, a single linear scaling from a few minutes of gap to 24 hours. The 10M restore was measured directly; the 24-hour replay in L-6 is a model term and L-7 is a projection. L-6 and L-7 need the owner's `ack:` line in the freeze record. A shorter backup interval shortens the replay term proportionally.
- **Full rebuild from evidence (L-8, PASS EXTRAPOLATED, recorded).** Cold-cache rebuild of 500,000 and 1,000,000 keys took 19.7 and 20.5 seconds per million keys (content equal to the direct run); projected 0.057 h at 10M and 0.57 h at 100M keys (EXTRAPOLATED, needs the owner's `ack:` line like L-6 and L-7). Spec 16 asks for measured RTO at 10M and 100M keys, both backup plus gap replay and full rebuild; the restore was measured directly at 10M keys, the 100M figure and both rebuild figures (measured at 0.5M and 1M keys) are projected. The decide step of the rebuild uses a simplified decider, so for it the figure is a lower bound.
- **Hot-row counter (L-11 INCONCLUSIVE).** The class (b) `seq` counter row with a synchronous commit per event reaches 1,015 events/s at the best writer count (2 writers; median of fifteen 10 s windows; bounds 835 to 1,157; range 559 to 1,668) against the 100 events/s placeholder and the 9.94 events/s the burst needs (3,470 / 500 + 3). It is far from being a bottleneck on this fixture, and zero network round trip biases it towards PASS (below).
- **Constraints (L-12 PASS, observed 0).** The ten constraint checks passed against PostgreSQL 18.6 on the disposable sketch, including the in-flight partial unique index, the fenced compare-and-set of `commit_issued`, the `(epoch, counter)` pair compare, the head compare-and-set across partitions, the regime-scoped `report_subject` key and the `trn_assignment` key unique per executing LEI.

**Partitions (L-9, L-10).** L-10 measures the unpruned lookup by event identity over small indexes (30 populated days of 300,000 rows, 9 million rows, 1 to 30 September 2026, in every case), so it bounds planner and probe overhead only and a FAIL is decisive; L-9 (the head, then one pruned partition) is the decision-relevant path. L-10 with 1,826 daily partitions: p99 254.6 ms (bounds 233 to 390 ms; range of the 15 blocks 99 to 526 ms) against the 50 ms placeholder, a failing-level figure that the noise rule refused to call. The same lookup took 146 ms with 30 daily partitions, 104 ms with 365 and 255 ms with 1,826 (each with the same 30 populated days), and 0.72 ms with 60 monthly partitions, in which the 30 populated days fall into a single partition (September 2026, 9,000,000 rows; 59 partitions are empty). The three daily results do not grow with the partition count, and the monthly point differs in partition count, populated partitions (1 against 30), partition size and measurement order, so the study does not separate the cost of populated partitions from that of all partitions, and it does not show what 60 populated months or 1,826 populated days cost: the full ledger would probe more populated indexes than any case measured. L-9: p99 0.14 ms (median over 15 blocks of 200 lookups spread over a 90 s background burst at 3,470 records/s on the misc VM; bound 0.17 ms against the 900 ms placeholder; INCONCLUSIVE only by the noise margin), measured with prepared statements on recently written rows of a table that held 30,000 to 270,000 decision rows, all in cache: the cost of a cached lookup, not of a lookup in a day-sized or multi-year ledger with cold pages. The partition study measured the same two steps without a burst, with statements planned each time, on 9 million populated rows and found 0.97, 1.89, 1.21 and 0.59 ms (30, 365 and 1,826 daily and 60 monthly partitions); the two sets are not comparable and neither is a measurement at the size of a day. A daily partition detaches in 9 ms and drops in 26 ms at 1,826 partitions (300,000 rows); dropping the populated monthly partition (9,000,000 rows) took 313 ms. Recommended input for the P3 schema design, for the owner to adopt or change: partition `decision` by day and add `as_at` to `decision_head`, so that every lookup by event identity is the head and one pruned partition; monthly partitions are the alternative if unpruned lookups must remain possible, at the price of a slower drop of a populated partition and larger per-partition indexes.

**Ledger size (L-5 PASS, L-14; spec 5.6).** A decision row with indexes is 335 bytes in the compact encoding E1 (heap 33.9 MB plus indexes 33.1 MB for 200,000 rows), inside the 300 to 500 planning band of spec 5.6; the verbose encoding E3 (full evaluated path as `jsonb`) is 983 bytes. The decision rows alone are 16.7 GB a day and 30.6 TB over 1,826 calendar days (spec 5.6: 27 to 46 TB). All ledger tables together are 48.8 GB for a 50-million-record day (decision 16.7, ingest 8.7, ingest_registry 7.9, report_subject 5.2, report_version 3.8, delivery 3.4, decision_head 3.0 GB), about 2.9 times the decision-only figure, or about 89 TB over 1,826 calendar days before old partitions are offloaded. E3 would raise the decision rows alone to about 49 GB a day.

**Fixture and its bias (printed in every SP-L report).** CPU and disk contention between three servers (primary, quorum synchronous standby, asynchronous replica) and the load generator on one 4-vCPU VM bias L-1 to L-3 towards FAIL; zero network round trip biases only the small-transaction results (L-11, group commit) towards PASS; archiving I/O shares one disk, which makes the sustain result conservative. The loopback standby is faster than a remote standby (round trip p50 89 and p99 196 microseconds, measured on the ingest VM; the misc VM, where L-11 ran, has no such reading). The runner disk was fast when idle (`pg_test_fsync` fdatasync 136 microseconds per operation, measured once on the ingest VM), but the DISK probe taken right after each of the five ingest runs read 57 to 1,226 synchronous operations a second (0.8 to 18 ms each), so after sustained writing the disk was not faster than durable network storage and the direction of the disk bias is not established. The headroom below is arithmetic on the measured figures, not a statement about production. L-2 is the time of the two synchronous transactions of a micro-batch (statements and commit), so a latency added to each commit counts twice: the design would tolerate (1,000 ms minus the window p99) divided by two per commit, which is nothing at the median window p99 of 1,077 ms. For L-11, a counter at 1,015 events/s is a serial commit of about 1 ms; the criterion of 100 events/s tolerates up to 10 ms per serial commit (about 9 ms added), and the 9.94 events/s the burst needs would tolerate about 99 ms. Neither L-2 nor L-11 reached a verdict here.

**Findings for the P3 schema design**
- The ten constraint checks pass against the disposable sketch on PostgreSQL 18.6 (L-12 observed 0), so each constraint of spec 6.3, 10 and 11.2 that they cover is expressible; this is not a review of the P3 schema. Two gaps stay design work: `decision_head` is unpartitioned as in spec 11.1 and unbounded (24 million rows a day in the sketch; it carries no `as_at`, so "head then pruned fetch" needs `as_at` added to the head, which the partition study did in its own schema), and the unpartitioned `ingest_registry` that the sketch needs to catch cross-partition same-version-different-hash conflicts (7.9 GB a day; not a table of spec 11.1) needs a retention rule.
- A lookup by event identity should not probe every daily partition (104 to 255 ms p99 over 30 populated daily partitions at 30, 365 and 1,826 partitions); the options are the head with one pruned partition, or monthly granularity.
- Encoding: E1 (compact, 335 bytes per decision row) meets the 300 to 500 band; E3 (983 bytes) does not.
- The tail of the synchronous commit (p99 9.6 s, windows up to 17 s) and the 30 s peak backlog are inputs to the latency budget and to the micro-batch rules.

**Extrapolation.** The sustained runs are 20-minute slices (4,160,000 records) at 3,470 records/s, not a full day of 50 million records; keys are inserted in random order (the event identity is a hash), so inserts into the unique indexes are random, not right-edge appends. Run A is cache-hot. Run B limits the primary's memory so that the slice has the day's table-to-memory ratio (2.82 at 969 bytes per candidate over 16 GiB); it is the best available stand-in for a full day, and it failed. What a full day adds beyond run B (table and index growth past the slice, checkpoint and vacuum cycles over many hours, the fuller backup and archive load) was not measured. The largest queue in the cache-hot runs was 30.3 s of arrivals, against the margin that the latency budget allows (4 hours, Q1).

**Restore and rebuild.** Restore points (keys: copy including its sync, of which the sync; replay of the 1,040,000-record gap; analyze; verify; RTO of the run, all in seconds; the application of the gap objects took 0.006 to 0.013 s at every size and is in the RTO but not in the columns): 500,000: 2.0 (sync 1.3), 25.5, 4.0, 0.9, 32.4; 1,000,000: 3.4 (2.1), 25.5, 4.6, 1.0, 34.6; 2,000,000: 6.7 (4.1), 24.9, 4.9, 1.4, 38.0; 5,000,000: 15.9 (3.2), 27.7, 5.3, 3.2, 52.1; 10,000,000: 30.9 (5.4), 29.6, 6.0, 5.1, 71.6. The restore time grows by about 4 microseconds per key; the replay term (a gap of one backup interval) dominates. A full rebuild from the evidence tier alone is the degraded path of I10 and cannot recover what the evidence tier does not hold: queue assignments, comments, incident notes and access events. The restore time covers the base-backup copy, the WAL replay of the gap, the application of generated gap objects (no evidence store was read), analyze and verify; it does not cover reading the journal objects of a 24-hour gap from an evidence store, the trust-anchor, in-doubt and re-derivation steps of spec 11.5 B, or any growth of the replay rate with ledger size beyond the five measured sizes.

**Evidence volume (model, planning assumptions; spec 5.6 and 11.2).** With 400 raw bytes a record, 500 reports a file, 1% of files sent again and three enrichment phases that together return 20,000 bytes a micro-batch (assumptions, not measurements): 10,000 landing objects a day (50,000,000 records over N = 5,000), 30,000 enrichment-response objects, 100,000 sealed files and 324,000 journal objects (`BatchAdmitted` and `VersionAssigned` 10,000 each, `DeliveryIntent`, `TransportResult` and `FeedbackReceived` 101,000 each, `RecoveryDecision` 1,000, `OperatorAction` counted as 0), so 464,000 evidence objects in all. Raw bytes are 20 GB a day and enrichment responses 200 MB a day. PUT rates: 5.4 PUT/s on average (464,000 over 24 hours) and 32.2 PUT/s if every object lands inside the 4-hour burst window.

**Micro-batch N and T inputs (spec 7.8 placeholders).** Commit matrix (COPY; four writers; per-batch commit p99 and records/s): N = 500: 561 ms and 16,463; 2,000: 679 ms and 20,618; 5,000: 1,681 ms and 21,473; 10,000: 2,778 ms and 22,900; 25,000: 4,140 ms and 21,600. The knee (the smallest N whose fixed part of the commit is at most 10% of batch time) is N = 10,000 for four writers and 25,000 for one writer. `COPY` gives more records per second than batched inserts at every point of the matrix (its commit p99 is slightly higher at one writer and N = 10,000: 2,003 against 1,942 ms). At N = 5,000 (the default of the load model) the open-loop commit median p99 is 1.08 s. T is not a measured criterion: it is the largest time cap such that T plus `ledger.ingestCommitP99Ms` (9,582 ms measured p99 under the burst) plus `assemble.latencyMs` at N (SP-X: 401 ms for 5,000 records, unloaded) still fits the arrival-to-sealed share of the margin; SP-M may tighten it.

**Limits of this report**
- One full dispatch; the three parts ran on three VMs with two CPU models, and the importer pools their canary readings for every criterion, so a criterion measured on one VM is judged with the probes of the other two. The DISK probe (synchronous operations a second) ranged from 57 to 3,028 on the ingest VM, from 951 to 4,176 on the misc VM and from 2,641 to 3,337 on the restore VM (highest over lowest: about 54 times, 4.4 times and 1.3 times). On the ingest VM the five readings taken before a run (2,495 to 3,028) and the five taken right after one (57 to 1,226) do not overlap, so that spread is the difference between a quiet disk and a disk just after a long write load, not drift between runs; whether the low readings also reflect the runner's disk is not established. A further dispatch would add readings and could lower the pooled spread, but 16 of the 40 present readings are below 2,500 operations a second, so one more dispatch of the same size would probably not have brought it under 0.50.
- The windows of one run are autocorrelated and the forks of a part share one VM, so the bounds are optimistic. L-3 rests on one closed-loop run (ten windows); L-1 and L-4 on two runs each, executed one after the other on the same VM (A1, A2, C1, B1, B2), not interleaved.
- The fixture is biased as stated above; no larger runner was used (Q5, answered "no larger runner"), and the restore sizes came from the real disk plan (the runner had 92 GB free, so 10 million keys were measured directly).
- Not retained in the result: the per-second sampler rows (CPU, WAL fsync, checkpoints), the per-run commit-time series and the PostgreSQL logs of A1, A2, C1 and B1, so the bottleneck of the failed runs and the cause of the commit stalls are unknown.
- L-15 (replay conflicts and lag of a read replica under console load) was not run (stretch).

**The L-4 consequence is the owner's.** L-4 FAILed in 2 of 2 runs. The spec 5.3 flip row reads "PostgreSQL cannot hold the volume (SP-L): offload old partitions to immutable Parquet, then shard by endpoint". The plan (SP-L, What FAIL means) considers it only if the sampler shows WAL fsync or index maintenance as the limit, says the owner decides at the freeze, and first re-examines a CPU-bound failure on a larger runner. None of that evidence exists: the sampler rows were not kept, Q5 was answered "no larger runner", and on 2026-10-11 the owner decided not to run SP-L again. What the report supports is narrower: on a 4-vCPU, 16 GB VM that also hosts a quorum standby, a replica and the generator, the two memory-limited runs completed about 2,500 records/s against 3,470 offered, while the two cache-hot runs sustained 3,470. The cause is not established. Whether this triggers the spec 5.3 flip, calls for more memory, faster storage or another layout on the reference production node, or is explained by the fixture alone, is for the owner to decide at the freeze and to record as the consequence of L-4. The owner's `ack:` lines are needed in the freeze record for L-4 (FAIL), L-8, L-6 and L-7 (EXTRAPOLATED) and L-2, L-3, L-9, L-10 and L-11 (INCONCLUSIVE). Spec 16 and 14.5 already require that capacity is re-confirmed on the release candidate before go-live.
