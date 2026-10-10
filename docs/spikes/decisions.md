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
- Q8 (who dispositions the SP-G register): the owner (hans). Default, OPEN, asked at G2.

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

Written at T8.

## Scope exceptions approved by the owner

- 2026-10-10, hans: R1 may add the job id spikes to the exact job list of PackagingFilesTest (regpack-app), because the plan adds a spikes job to ci.yml.
