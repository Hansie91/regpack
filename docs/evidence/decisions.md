# P3b1 decisions and records

Kept by the controller (the main session). Plan: [P3b1](../superpowers/plans/2026-10-19-p3b-evidence-stores.md). Style of `docs/ledger/decisions.md`.

## Baseline (T1 step 2, 2026-10-19)

Branch `p3b/evidence-stores` from `main` at `5dcc63a` (P0, P1a, P2a and P3a merged; tag `p3a-ledger-core`); plan committed as `24fb90f`. `./mvnw -B -ntp verify` ends `BUILD SUCCESS` with the totals, in reactor order: tools 233 and 1 IT, model 96, ports 21, pack 871 (2 skipped), testkit 71, bench 343 (1 skipped), inmemory 181, postgres 227 and 235 IT (234 skipped, the real-store tests need `REGPACK_PG_URL` and run only in CI), engine 335 and 11 IT, app 95 (2 skipped) and 6 IT. `scripts/check-gates.sh` ends `check-gates: all gates passed`. `git tag --list p3a-ledger-core` prints the tag. `./mvnw -DskipTests -Dspotless.check.skip=true install` and `./mvnw -f spikes/pom.xml verify` are `BUILD SUCCESS`. `docs/spikes/freeze-record.md` line 3: `status: PENDING` (the spec freeze is not recorded; the first row of the plan's departures table stands). Maven 3.9.16, Java 25.0.4.1.

## Approvals

| Id | What | Date | Name | State |
|---|---|---|---|---|
| AP-1 | The plan, the departures, the split into P3b1 and P3b2, the port and model changes, D-E1 to D-E12 as starting positions, A1 to A14 | 2026-10-19 | hans | given in chat ("Approve the plan and the split (Recommended)") in answer to the controller's summary of the plan; the answer also covers the default of OI-6 (tests only) and leaves the V002 decisions OI-4 to the T9 stop |
| AP-2 | Every push of `p3b/evidence-stores` and every CI run waited on (CP-1a, CP-1, CP-2, CP-3) | 2026-10-19 | hans | asked at each push; none made yet |
| AP-3 | Every edit of `.github/workflows/ci.yml` and `scripts/check-gates.sh` | 2026-10-19 | hans | the one-line push filter (`'p3b/**'`) given in chat ("Approve, show exact text at T19 (Recommended)"); the exact YAML of `ledger-durability` and the text of both scripts are shown again at T19 before their commit (departure below); the semantics of the traceability gate (T18: the labels `REALFILES` and `REALSTANDBY` and the `STANDBY` mode) were not part of that question and are asked at T18 before the `check-gates.sh` edit is committed |
| AP-4 | Merge, tag `p3b1-evidence-sealed` and the sign-offs in `docs/exit/P3b.md` | - | - | open (T22; the sign-off boxes are the owner's) |
| AP-5 | The new module `regpack-evidence`: no third-party dependency, not a dependency of `regpack-app` | 2026-10-19 | hans | given in chat ("Approve, show exact text at T19 (Recommended)", the question named the module) |
| AP-6 | CI may run `docker pull`, `run`, `exec`, `stop`, `logs` and `rm` on `postgres:18.6-trixie@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336` in the job `ledger-durability` (primary and standby on the runner's host network, `trust` on `127.0.0.1`, no credentials, not privileged), plus exactly two `sudo install` lines for the data directories as the P1a spike did | 2026-10-19 | hans | given in principle in chat ("Approve, show exact text at T19 (Recommended)"); the job and both scripts come back for their exact text at T19, and nothing is committed before that |
| AP-7 | The V002 schema design `docs/ledger/schema-design-p3b.md`, recorded with its SHA-256 | - | - | open (T9 stop) |

## Owner inputs

Answered on 2026-10-19 where the owner answered; "default" means the default of the plan stands until the task that decides it.

| Id | Question | Default | Answer |
|---|---|---|---|
| OI-1 | Plan approval, A1 to A14, D-E1 to D-E12 | proceed on AP-1 | AP-1 given |
| OI-2 | The split into P3b1 and P3b2 | split | **split approved by the owner in chat** ("Approve the plan and the split (Recommended)") |
| OI-3 | For P3b2: the S3-compatible store in CI, the client, a licence position for a copyleft test-only image | CloudServer; hand-written client; no copyleft image | open, asked at T22 step 5 |
| OI-4 | The V002 decisions D1 to D5 of the T9 sheet | the recommendations | open, decided at T9 |
| OI-5 | A disposable Windows Server VM for a manual P-SEALED run (optional) | no | open, asked at T22 step 5 |
| OI-6 | Retention class names and periods for the journal, sealed files and the epoch marker | `journal`, `sealed`, `epoch`, 1,826 days, for tests and tools only | default accepted (inside the AP-1 answer); T3 uses it |
| OI-7 | The owner's reading and deciding minutes at T1, T9, the checkpoints and T22 | about 60 in all for P3b1 (assumption of the plan) | not asked at T1; the assumption stands and the owner's figure is asked at T9 and T22 |

approved-schema-p3b-sha256:

## Pins created

| Name | Value | Task | Reason |
|---|---|---|---|
| (none yet) | | | |

## Deviations from the plan

| Task | Deviation | Why |
|---|---|---|
| T1 step 4 | The exact YAML of `ledger-durability` and the text of `scripts/ci/pg-quorum-up.sh` and `scripts/ci/pg-init-replication.sh` are shown at T19, right before their commit, not at T1; the T18 semantics of the traceability gate are asked at T18 | the scripts do not exist yet, and the owner agreed to this basis (answer "Approve, show exact text at T19 (Recommended)", 2026-10-19); the plan's rule that T19 step 1 compares the committed text with the approved text still holds |
| T1 step 4 | The owner's reading estimate (OI-7) was not asked | the question block was kept to the approvals; asked at T9 and T22 |
