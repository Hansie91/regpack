# P3a decisions and records

Kept by the controller (the main session). Plan: [P3a](../superpowers/plans/2026-10-17-p3a-ledger-core.md). Style of `docs/pack-format/decisions.md`.

## Baseline (T1 step 2, 2026-10-18)

Branch `p3a/ledger-core` from `main` at `e685b7f` (P0, P1a and P2a merged; tag `p2a-rule-pack-core`); plan committed as `93c4472`. `./mvnw -B -ntp verify` ends `BUILD SUCCESS` with the totals tools 190 and 1 IT, model 66, ports 14, pack 871 (2 skipped), testkit 71, bench 343 (1 skipped), inmemory 105, engine 317 and 11 IT, app 90 (2 skipped) and 6 IT. `scripts/check-gates.sh` ends `check-gates: all gates passed`. `Gates entry --target P3-schema-sizing` prints `SP-L REPORT_EXISTS OK` (the report exists; its FAIL is open item D-1). `./mvnw -DskipTests -Dspotless.check.skip=true install` and `./mvnw -f spikes/pom.xml verify` are `BUILD SUCCESS`. `docs/spikes/freeze-record.md` line 3: `status: PENDING` (the spec freeze is not recorded; departures table row 1). Maven 3.9.16, Java 25.0.4.1.

## Approvals

| Id | What | Date | Name | State |
|---|---|---|---|---|
| AP-1 | The plan, the departures, the split into P3a to P3d, the port and model changes, the schema decisions D-S1 to D-S17 as starting positions, A1 to A13 | 2026-10-18 | hans | given in chat ("Approve the plan (Recommended)") in answer to the controller's summary of the plan; the schema questions OI-4 to OI-8 are decided at the T2 stop with their defaults until then |
| AP-2 | Every push of `p3a/ledger-core` and every CI run waited on (CP-1 to CP-4) | | | asked at each push |
| AP-3 | Every edit of `.github/workflows/ci.yml` (branch filter `p3a/**`; jobs `ledger-pg` and `ledger-capacity`) and of `scripts/check-gates.sh` | 2026-10-18 | hans | given in chat ("Approve AP-3, AP-5 and AP-6 (Recommended)"); the exact text of `ledger-pg` is shown again at T13 and the exact YAML of `ledger-capacity` at T21 before its code commit |
| AP-4 | Merge, tag `p3a-ledger-core` and the sign-offs in `docs/exit/P3a.md` | | | owner only (T23) |
| AP-5 | The new dependencies `org.postgresql:postgresql:42.7.14` (BSD-2-Clause) and `org.checkerframework:checker-qual:3.55.1` (MIT) in `regpack-postgres` only; no pool, no migration tool | 2026-10-18 | hans | given in chat together with AP-3 and AP-6 |
| AP-6 | CI may pull `postgres:18.6-trixie@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336` as a service container on every push of `p3a/**` and `main` and every `pull_request` to `main` (trust authentication on the runner loopback, no credentials) | 2026-10-18 | hans | given in chat together with AP-3 and AP-5 |
| AP-7 | The schema design `docs/ledger/schema-design.md`, recorded with its SHA-256 | | | open (T2 owner stop) |
| AP-8 | Each manual dispatch of the capacity job and the owner's download of its artefact | | | open (T21) |

## Owner inputs

Answered on 2026-10-18 where the owner answered; "default accepted" means the default of the plan stands until the task that decides it.

| Id | Question | Default | Answer |
|---|---|---|---|
| OI-1 | Plan approval and A1 to A13 | proceed on AP-1 | AP-1 given |
| OI-2 | INTERP-3: circuit epoch in the decision context (A), ordinal in the key (B), reason code in the key (C) | A | **A, given by the owner in chat** (T3 implements it) |
| OI-3 | D-1, the L-4 consequence | sizing and tuning input; decided after the T21 read-out | default accepted; decided after T21 |
| OI-4 | Partition granularity of `decision` | daily on the UTC date of `as_at` (D-S1) | default accepted; decided at the T2 stop |
| OI-5 | Ingest layout | one unpartitioned table with a global key (D-S5) | default accepted; decided at the T2 stop |
| OI-6 | Growth and retention of `decision_head`, `ingest`, `report_subject` | no purge in release 1 (D-S14) | default accepted; decided at the T2 stop |
| OI-7 | Decision body encoding | E2 of A13 (deflated canonical JSON), size measured before the stop | default accepted; decided at the T2 stop |
| OI-8 | Micro-batch N for sizing and the probe | 5,000 and 10,000 measured | default accepted; decided at T21 |
| OI-9 | Number of reporting entities; incumbent; RSV-1 | one; unknown; reserved | **accepted by the owner in chat** ("Yes, accept these defaults") |
| OI-10 | TRN derivation: regime in the HMAC message, 32 characters | as stated | default accepted; decided at T6 |
| OI-11 | Appendix C dispositions in the T4 table | as in the table | default accepted; decided at T4 |
| OI-12 | Wire `regpack-postgres` into `regpack-app` now | no (A9) | default accepted |
| OI-13 | A PostgreSQL 18 server reachable from the development machine | none: CI only (A3) | **none available, accepted by the owner in chat** |
| OI-14 | Name P3a to P3d in the spec 16 table; `closes_in` per slice | `closes_in` stays as the spec; proposal presented at T23 | default accepted; decided at T22 and T23 |
| OI-15 | The owner's reading and deciding time (minutes) at T1, T2, T21 and T23 | estimated by the owner | not provided yet; asked at the T2 stop |

approved-schema-sha256:

## Pins created

| Name | Value | Task | Reason |
|---|---|---|---|

## Deviations from the plan

None yet.
