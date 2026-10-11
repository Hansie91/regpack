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
