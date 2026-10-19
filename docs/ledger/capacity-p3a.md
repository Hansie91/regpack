# P3a capacity information (task T21)

**Information, not a verdict.** This note records what the capacity probe printed in one dispatch of the job `ledger-capacity` and what it does and does not explain. It decides nothing: the consequence of the L-4 failure of SP-L (open item D-1, owner input OI-3) is the owner's, and the agent proposes none. The probe drives the production adapter (`PgLedgerStore`, layout V001) at the load shape of SP-L; it is not SP-L (see "Fixture differences"). In this note "commit time" means the time of the two write units of a batch (`commitIngest` and `commitReportable`: about 20,000 or 40,000 statements plus the COMMIT), not the wait of a commit flush.

## The run

| | |
|---|---|
| Run | [39177466330](https://github.com/Hansie91/regpack/actions/runs/39177466330), workflow `ci`, `workflow_dispatch` with `capacity` and `confirm_downloads` ticked by the owner |
| Dispatched | 2026-10-18 about 23:40 UTC, from branch `p3a/ledger-core` at commit `6b33bf1` |
| Code measured | `6b33bf1`: T1 to T21 and the CP-3 record, **before** the final-review fixes FP1 to FP5 (so with the dictionary rows still created inside the write unit, review finding SQL-1, and with the probe raising `lock_timeout` to 60 s) |
| Variants | A: no memory limit, `shared_buffers` 2GB, `synchronous_commit` on. B: container limit 1363 MiB (no swap), `shared_buffers` 340MB, on. C: as B with `synchronous_commit` off (a diagnostic). Each variant on its own GitHub-hosted `ubuntu-24.04` VM (4 CPUs; the runner memory of 15,988 MiB was printed for A), each job about 42 minutes (the probe step about 40 minutes) |
| Load | open-loop arrivals at 3,470 records/s for 1,200 s, which the probe schedules as whole batches: 4,160,000 scheduled records covering about 1,198.8 s; four writers, four sources, each batch one `commitIngest` and one `commitReportable` (half REPORT with one version each); batches of 5,000 and then 10,000 records, in sequence on one server |
| What was read | the headline `::notice` annotations of the three jobs through the unauthenticated check-run listing. The sampler files and the artefacts were **not** downloaded (they need a sign-in), so the per-second WAL, checkpoint and I/O rows have not been looked at |

## Figures

Records a second over 19 sixty-second windows, completed fraction of the 4,160,000 scheduled records, commit time and the largest backlog:

| Variant | Batch | Median | Minimum | Maximum | Completed | Commit time p50 / p99 / max (s) | Largest backlog (s) | Batches never started |
|---|---|---|---|---|---|---|---|---|
| A | 5,000 | 3,416.7 | 3,166.7 | 3,833.3 | 100.0 % | 3.7 / 6.6 / 10.3 | 7.2 | 0 |
| A | 10,000 | 3,500.0 | 3,000.0 | 4,000.0 | 100.0 % | 7.5 / 13.1 / 13.8 | 9.3 | 0 |
| B | 5,000 | 2,333.3 | 2,000.0 | 3,666.7 | 74.3 % | 7.9 / 12.4 / 12.8 | 308.4 | 214 |
| B | 10,000 | 2,666.7 | 2,000.0 | 3,333.3 | 78.4 % | 14.8 / 21.0 / 21.0 | 256.5 | 90 |
| C | 5,000 | 2,000.0 | 1,333.3 | 3,333.3 | 58.9 % | 8.9 / 18.0 / 18.4 | 492.8 | 342 |
| C | 10,000 | 2,000.0 | 1,333.3 | 3,333.3 | 58.4 % | 20.9 / 29.9 / 30.1 | 495.7 | 173 |

The window rates are quantised by whole batches: one batch moves a window by 83.3 records/s (5,000-record batches) or 166.7 records/s (10,000-record batches), which is 2 to 8 percent of the rates in the table. Completed records (including the 4 batches in each of B and C that finished after the slice) divided by 1,200 s give 2,717 (B, 10,000) and 2,575 (B, 5,000) records/s for B and 2,025 and 2,042 records/s for C.

| Fact | Value |
|---|---|
| Decision row (E2, the form the owner chose) | 797 bytes (measured with `pg_total_relation_size` per row), **outside the spec 5.6 band of 300 to 500 bytes**; the owner chose E2 at the schema stop knowing that it is about 800 bytes and outside the band (OI-7, the schema document estimated about 800) |
| All ledger tables per record | 1,896 to 1,903 bytes, so about 94.8 to 95.2 GB (decimal) for a 50,000,000-record day. This figure **includes** the report bodies of the REPORT half of the records (about 400 bytes each, about 10 GB a day), which the schema document's 91 GB a day left out ("perhaps +20 GB a day"); like for like that is about 101 GB estimated against about 95 GB measured, inside the document's stated plus or minus 40 percent. SP-L measured 969 bytes per candidate |
| JDBC executions per batch | 20,022 (5,000-record batch) and 40,022 (10,000-record batch), so about 4 per record; 0 batched rows; this is the per-version path of `commitReportable` (about 8 statements per created version, half the records are REPORT) |
| Table-to-memory ratio | A 0.47 against the runner memory (no container limit; not comparable to SP-L). B and C 5.52 to 5.54 against 1363 MiB (equal to a 16 GiB node holding a 50,000,000-record day at this size per record). This ratio is **nominal**: bytes per completed record times the offered 4,160,000 records over the memory, the same definition as SP-L's 2.82 (969 bytes); the tables really reached 3.2 to 4.3 times the memory at the end of the B and C runs because only 58 to 78 percent of the records completed. The probe prints "comparable to SP-L: no" for B and C |

## Reading against SP-L

- **Cache-hot:** variant A kept the offered rate (median 3,417 to 3,500 against 3,470, 100 percent completed, largest backlog under 10 s). SP-L's cache-hot runs also sustained 3,470. This was on a VM with no standby and no replica; the load generator (the writer threads of the probe) and Maven shared the 4 CPUs with the server, as the generator did in SP-L.
- **Memory-limited:** variants B and C did not keep up. B completed 74 to 78 percent (2,575 to 2,717 records/s on average); SP-L's two memory-limited runs completed 72 and 73 percent (2,500 and 2,529 records/s). So the outcome, a memory-limited primary that does not sustain 3,470 records/s, **reproduced without SP-L's standby and replica**; the generator still shared the VM and the production adapter replaced SP-L's sketch, so it is not explained by SP-L's standby and replica alone. It is **not** a like-for-like repeat: the same 1363 MiB holds a ledger of 1,899 bytes per record (nominal ratio 5.5) where SP-L's held 969 bytes (2.82). To reach SP-L's nominal ratio of 2.82 at this size per record the memory would have to be about 2,670 MiB for the slice, a node of about 31 GiB for a 50,000,000-record day (computed, assuming that bytes per record scale linearly from the slice to a day, not measured). **SP-L's own runs at 2.82 also did not keep up**, so this is the memory that reproduces SP-L's ratio, not a memory shown to sustain the load.
- **Switching the commit wait off did not help in this run:** variant C (no synchronous commit) was slower than B, not faster: median 2,000 against 2,333 (5,000-record batches) and 2,667 (10,000-record batches), that is 14 to 25 percent lower, with longer commit times as well (p50 8.9 against 7.9 s and 20.9 against 14.8 s). That is not what the simple reading "the commit flush is the bottleneck" predicts and fits statement or CPU work better than the flush, but there is one run per variant on three different VMs, and SP-L's disk probe read 57 to 4,176 synchronous operations a second across its three VMs (57 to 3,028 on the ingest VM alone, the low readings right after long write runs), so a difference of this size between two VMs cannot be attributed to a setting. The same holds for A against B and C: `shared_buffers`, the container limit and the VM differ together, so the memory attribution is no firmer than the commit-wait one.
- **Statement count:** the per-version path costs about 4 JDBC executions per record. On 4 CPUs shared by the server and four writers, the two write units of a 10,000-record batch took a median 7.5 s even without memory pressure (variant A). Whether the round trips or the server work bound this is not separated by the probe. The review finding SQL-2 (about 8 statements per created version) was refuted by the skeptic because the plan allows this per-version path in P3a; this figure is the evidence a decision on a set-based version path would use.

## What this does and does not explain

It shows that on this adapter and layout (a) a cache-hot primary on a 4-vCPU runner sustains the offered rate, (b) a primary whose container memory holds a nominal ratio of about 5.5 of the day's data does not, in two variants, and (c) switching off the synchronous commit did not raise the throughput in this run (one run, on another VM). It does not establish the cause: the sampler rows (WAL writes, fsync counts, checkpoints, I/O) were not read, the variants ran on different VMs, the probe cannot separate adapter cost from server limit, there is no quorum synchronous standby, and the run measured the code before the fixes FP1 to FP5. The quorum synchronous standby and the durability gate belong to P3b; the Windows run and the 50-million-record day have no slice yet.

## Levers (without a recommendation)

Memory per ledger node (the arithmetic above is the memory that reproduces SP-L's ratio, not a target shown to work); the `synchronous_commit` class of non-REPORT decisions that spec 7.8 allows to be asynchronous (the port offers no durability class; the C variant gives no evidence that it would help); a set-based version path (statements per record); `COPY` for the ingest and decision inserts; `wal_compression`; checkpoint settings (`max_wal_size` 8GB and `checkpoint_timeout` 15min were used here); faster storage; and the spec 5.3 flip row (offload old partitions, then shard by endpoint), which is the owner's decision at the freeze together with the L-4 acknowledgement in `docs/spikes/freeze-record.md`.

## Fixture differences (from the probe's notice, the workflow and the code at 6b33bf1)

One server, no standby, no replica, no WAL archiving (the probe's notice); the production adapter and the P3a layout instead of the SP-L sketch; the generator (the writer threads) and Maven on the same 4-CPU VM as the server, as in SP-L; swap disabled for B and C; the three variants on three VMs; the two batch sizes in sequence on one server per variant; the probe raised `lock_timeout` to 60 s in this dispatch (removed by FP1), which could hide the waits of SQL-1; the service container data directory on the container's default storage of the runner (which disk is inferred, not recorded: the mount was not recorded in this dispatch).

## Next

A second dispatch on the head that contains the fixes would add the new fixture records (CPU model, mount, network mode, an fsync canary, the cgroup memory data) and show the effect of FP1, on three new VMs; it is not a comparison with these three VMs. It costs about 125 runner minutes (about 42 minutes of wall-clock), and the owner decides whether it is worth it. The owner's L-4 consequence is open (OI-3).
