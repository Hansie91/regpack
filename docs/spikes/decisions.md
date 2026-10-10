# P1a decisions

Kept by the controller (the main session); every entry carries the date and the name of the person who decided.

## Questions and answers

Questions are those of Appendix B of the P1a plan. A reading marked "default" is the one the plan states; the owner accepted the five defaults on 2026-10-10 (hans).

- Q1 (T+1 margin): 4 h, so a day of assembly must fit at 3,472 records/s. Accepted by hans, 2026-10-10 (default).
- Q2 (RTO limit): 4 h for L-6 and L-7; backup interval 24 h (`backupIntervalHours`). Accepted by hans, 2026-10-10 (default).
- Q3 (SP-R gate reading): per thread with four vCPUs busy on the worst-case shape S2; the single-thread and per-physical-core readings are printed beside it. Accepted by hans, 2026-10-10 (default).
- Q4 (time-box accounting): CI waits and owner waits do not count against a box; a day is 8 h of agent work. Accepted by hans, 2026-10-10 (default).
- Q5 (larger runner): measure smaller restore sizes cold-cache and extrapolate, labelled as an extrapolation; no larger runner. Decided by hans, 2026-10-10.
- Criteria placeholders: every criterion whose source says PLAN is confirmed by hans in `docs/spikes/criteria.lock` before the first full run. Accepted by hans, 2026-10-10 (default). Q1 to Q3 are answered, so T6 step 6 may lock once the placeholders are shown to the owner.
- Q6 (rejected draft `reference/`): stays untracked (excluded locally in `.git/info/exclude`). Default, not yet questioned: OPEN until the owner says otherwise.
- Q7 (after the exit: keep `spikes/` frozen on `main` or delete): default is keep frozen. OPEN, asked at C2.
- Q8 (who dispositions the SP-G register): the owner (hans). Default, OPEN, asked at G2.

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
