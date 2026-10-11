# P1a decisions

Kept by the controller (the main session); every entry carries the date and the name of the person who decided.

## Questions and answers

Questions are those of Appendix B of the P1a plan. A reading marked "default" is the one the plan states; the owner accepted the five defaults on 2026-10-10 (hans).

- Q1 (T+1 margin): 4 h, so a day of assembly must fit at 3,472 records/s. Accepted by hans, 2026-10-10 (default).
- Q2 (RTO limit): 4 h for L-6 and L-7; backup interval 24 h (`backupIntervalHours`). Accepted by hans, 2026-10-10 (default).
- Q3 (SP-R gate reading): per thread with four vCPUs busy on the worst-case shape S2; the single-thread and per-physical-core readings are printed beside it. Accepted by hans, 2026-10-10 (default).
- Q4 (time-box accounting): CI waits and owner waits do not count against a box; a day is 8 h of agent work. Accepted by hans, 2026-10-10 (default).
- Q5 (larger runner): measure smaller restore sizes cold-cache and extrapolate, labelled as an extrapolation; no larger runner. Decided by hans, 2026-10-10.
- Criteria placeholders: every criterion whose source says PLAN is confirmed by hans in `docs/spikes/criteria.lock` before the first full run. Accepted by hans, 2026-10-10 (default). Q1 to Q3 are answered. Locked by hans on 2026-10-10 as generated, digest d85eca736beb3ba4826bdbd6b160bba9e40cd838f7227b41c97140730d5b9447 (`docs/spikes/criteria.lock`): the 17 placeholder rows and the L-11 assumptions (500 reports per file, 3 other events per second) are final; no threshold changes after this date. Noted at the lock and left unchanged: the SP-L rows use 3,470 records/s (SPEC 5.6) and the SP-X rows 3,472 (the 4 h margin).
- Q6 (rejected draft `reference/`): stays untracked (excluded locally in `.git/info/exclude`). Default, not yet questioned: OPEN until the owner says otherwise.
- Q7 (after the exit: keep `spikes/` frozen on `main` or delete): default is keep frozen. OPEN, asked at C2.
- Q8 (who dispositions the SP-G register): the owner (hans). Done 2026-10-10: 16 rows ACCEPTED (15 amendment-list wordings and BRK-18 as a P12 item), 3 REJECTED_NOT_NEEDED (BRK-03, BRK-05, BRK-17); BRK-17 and BRK-18 re-rated P12_ONLY by the owner. The acceptance is a design decision with gate G1 (named Compliance owner) still open, not a Compliance or Legal view of EMIR.

## T3 harness notes

Technical notes of the implementer and the fixer of T3, not owner decisions; they exist so that T4 to T6 build on the protocol consistently.

- Child JVM time limit: `ForkRunner.runForked` checks the deadline only before a fork starts, and a child has no limit of its own. A hung child (for instance one waiting on PostgreSQL) blocks the run until the CI job timeout, which is the only guard. A per-child watchdog derived from the protocol was considered and not added: a periodic workload (`cycleUnits` above 0) has no bound that the protocol states, so a derived limit could kill a legitimate fork and turn it into a harness discard. A later task that knows the length of its workload may add a limit.
- Canary disk: `runInProcess` has no parameter for a scratch path (the plan fixes the signature), so the disk probe writes to a temporary file in the OS temp directory and deletes it afterwards. It therefore measures the temp directory's disk. For SP-L this is not the data disk of the PostgreSQL container; the disk canary there is a health signal of the runner, not of the database volume.
- How a worker gets its `Protocol` and fork index: `runForked` passes neither. The worker receives what the caller puts into `workerArgs` (for example the name `full`, `informative` or `smoke`, which it maps to the static factories) and the `spike.*` system properties of the parent. The worker prints its result with any index; the parent replaces it with the position in the series (`withIndex`).
- Relative range in P-2 is `(max - min) / min` over the last `stableWindows` window rates; it is infinite when the smallest rate is not positive.
- FORK-RESULT line: `FORK-RESULT ` and the canonical JSON of the fields `index`, `unstable`, `discarded`, `discardReason`, `windowRates`, `canaryCpu`, `canaryMemory`, `canaryDisk`, `stealFraction`, `checksum`. Rates and the steal fraction are decimals of scale 9 (canonical JSON holds no double), `index` and `checksum` are integers. A child prints exactly one such line: none, several, a malformed one, or a not-discarded one without a window rate is a `HARNESS_ERROR` discard.
- A canary of zero length (`Protocol.canary()` is `Duration.ZERO`) means no probes and no scratch file; the canary lists of the fork are then empty. Only the protocols of the tests use it.

## Approvals ledger

| Id | What | Date | Name | State |
|---|---|---|---|---|
| A-1 | `postgres:18.6-trixie@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336` pulled on the runner by `spikes-run` | | | asked at each dispatch of `sp-l` |
| A-2 | Licence spellings in the spike POM only: re2j 1.8 "Go License" and threeten-extra 1.10.0 "BSD 3-clause", both mapped to BSD-3-Clause | 2026-10-10 | hans | approved; also recorded in `governance/licence-policy.md` |
| A-3 | Every push of `p1a/spikes` | | | asked at each push |
| A-4 | Every workflow dispatch or re-run, and the artefact download | | | asked at each run |
| A-5 | Merge `p0/foundation` into `main` and put `spikes-run.yml` on `main`, or push a stub | | | asked at T8 step 6 |
| A-6 | Repository settings (Dependabot alerts and security updates, GitHub-owned actions only, read-only default workflow permissions) | | | OPEN: not enabled when P0 was signed; to be asked again before T8 step 6 |

## Hand-off channels verified

Selftest run [38093544725](https://github.com/Hansie91/regpack/actions/runs/38093544725), 2026-10-10: `spikes-run` `selftest` `smoke`, dispatched by hans (`workflow_dispatch`) from `p1a/spikes` at `2a6247c`, attempt 1. The runs and jobs listings (read without sign-in) show the run and both jobs `success`; the steps of the run job ran in the planned order, `Seal the result directory (last write)` (step 10) followed only by the artefact upload (step 11).

- **Job-summary text channel: worked.** hans pasted the `summary.json` of the run; `bench import --spike SELFTEST --summary-text <file> --dry-run` accepted it (strict parse, criteria digest equal to `docs/spikes/criteria.lock`, spike id matches the registry) and printed `import: OK (dry run) SELFTEST status=PENDING evidence=SUMMARY computed=PASS`. By design this channel is PENDING and can never satisfy a gate; it is not the channel for a verdict. Its `timeBox.usedMinutes` is 0 because `bench publish` does not read the ledger; the importer computes the time from `docs/spikes/timebox/<ID>.tsv`.
- **Artefact channel: worked.** The artefact `spike-selftest-all-38093544725-1` (5,240 bytes, GitHub digest `sha256:5a2d5d209f674f31c3dadff3f271c76c9c42237aac4a724a8d75261537b68515`) was downloaded by hans; the zip's SHA-256 equals that digest. Its five entries (`manifest.sha256`, `measurements.json`, `report.md`, `runner.txt`, `summary.json`; no path outside the folder, no link) were extracted by a checking script into the new folder `spikes/inbox/38093544725/spike-selftest-all-38093544725-1/` (git-ignored), and `BENCH import --spike SELFTEST --dir <that folder> --dry-run` printed `import: OK (dry run) SELFTEST status=PASS evidence=RAW`. Negative control on a copy with one byte appended to `measurements.json`: `import: HASH_MISMATCH measurements.json`, exit 1. The unit test `refusesTamperedSummary` and the seal test cover the regenerated-manifest case. The hand-off CI seal, owner download, local import is therefore proven for RF4; `sp-r` may be dispatched.
- **Runner (read from `runner.txt` of the artefact; closes the P0 gap "runner image names and versions were not captured"):** GitHub-hosted `ubuntu-24.04`, image `ubuntu24` version `20261004.327.1`, kernel `6.17.0-1022-azure`; AMD EPYC 7763, 4 vCPU = 2 cores x 2 threads (so the SP-R "per physical core" reading is the 4-thread aggregate divided by 2); 16,766,414,848 bytes of memory, 3.2 GB swap; root filesystem `/dev/root` ext4, 154.9 GB of which 92.2 GB were available, and `/mnt` is on the same filesystem (SP-L must size its data and restore volumes to about 80 GB at most); Docker 28.0.4; Temurin 25.0.4.1+1; no cgroup `cpu.max`.
- **Harness time-box:** `docs/spikes/timebox/HARNESS.tsv` stopped at 166 of 480 minutes recorded (box not hit). The controller time of T8 steps 7 to 9 (roughly 20 minutes) was not logged because the ledger was paused for the owner waits; the total stays well under the box on either reading.

## Scope exceptions approved by the owner

- 2026-10-10, hans: R1 may add the job id spikes to the exact job list of PackagingFilesTest (regpack-app), because the plan adds a spikes job to ci.yml.
