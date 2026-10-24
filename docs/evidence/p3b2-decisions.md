# P3b2 decisions and records

Kept by the controller (the main session). Plan: [P3b2](../superpowers/plans/2026-10-23-p3b2-worm-s3.md). Style of [decisions.md](decisions.md) (the P3b1 records). "In chat" means in the session conversation; where a quoted option label is given, the owner selected it in the question dialog. Times are UTC; the owner's local time is UTC-5 (inferred from commit offsets).

## Baseline (T1 step 2, 2026-10-23, local time 20:56)

Branch `p3b2/worm-s3` from `main` at `525e126` (P0, P1a, P2a, P3a and P3b1 merged; tags `p3a-ledger-core`, `p3b1-evidence-sealed`, `p2a-rule-pack-core`); the plan is committed as `810ea31` (first draft) and `ea7f79f` (after the second review round). Maven 3.9.16, Java 25.0.4.1. `./mvnw -B -ntp verify` ends `BUILD SUCCESS` with the totals, in reactor order: tools 245 and 1 IT, model 96, ports 104, pack 871 (2 skipped), testkit 80, bench 343 (1 skipped), inmemory 230, evidence 418 (6 skipped), postgres 306 unit and 289 IT (288 skipped: no server), engine 335 and 11 IT, app 102 (2 skipped) and 6 IT: 3,437 tests, the same as the P3b1 exit record. `bash scripts/check-gates.sh` ends `check-gates: all gates passed`. `./mvnw -B -ntp -DskipTests -DskipITs -Dspotless.check.skip=true install` and `./mvnw -B -ntp -f spikes/pom.xml verify` end `BUILD SUCCESS` (the plan text of T1 step 2 gives `-DskipTests` only; failsafe 3.6.0 ignores it, so `-DskipITs` was added; a plan defect of one word). `docs/spikes/freeze-record.md` has `status: PENDING`, so the first departure of the plan is in force.

## Approvals

| Id | What | Date | Name | State |
|---|---|---|---|---|
| AP-1 | The plan at commit `ea7f79f` (15 tasks, 105 KB, 3 pushes planned) together with the items the question named: the four departures ((1) the plan builds on the journal contracts before the spec freeze is recorded; (2) no store is called P-WORM or compliance mode in production before the SP-O spike, the grade says "governance (unverified)"; (3) compliance mode per retention class is not built, one lock mode per store; (4) the manifest conditions of the send gate stay P3c, so I1 is reported for the ledger and evidence-durability conditions only), the assumptions A1 to A11, the decisions D-W1 to D-W14, the port changes (`EvidenceStore.list` promises full pages, a non-additive change; `EpochMarker` and `JournalWriter` read to an empty page; three new contract methods; adapter-only limits), the reading that "both evidence profiles" applies to I1 and I10, and the traceability label `REALWORM` with mode `worm` (`check-gates.sh` unchanged) | 2026-10-24 01:52 (2026-10-23 20:52 local; asked 01:50) | hans | "Approve the plan and these items (Recommended)" (label). Not named by the question, so not covered: the exact text of any CI job (AP-3, AP-6), the image (OI-3), the effort estimate, group V of `docs/exit/P3b.md`, OI-5 to OI-8 |
| AP-2 | Every push of `p3b2/worm-s3` (CP-1, CP-2, CP-3) | - | - | asked at each push, by commit name; none yet |
| AP-3 | Edits of `.github/workflows/ci.yml` | 2026-10-24 01:52 | hans | the push filter, one line `branches: [main, 'p0/**', 'p1a/**', 'p2a/**', 'p3a/**', 'p3b/**', 'p3b2/**']`: "Approve both (Recommended)" (label; the question covered the push filter and the module together). The job `evidence-worm` version 1 (T4) and version 2 (T11) are not approved: each is shown as exact text before its commit |
| AP-4 | Merge, tag `p3b2-worm-s3` and the sign-offs of `docs/exit/P3b2.md` | - | - | open (T14), owner only |
| AP-5 | The new module `dev.regpack:regpack-evidence-s3:0.1.0-SNAPSHOT`: no third-party dependency, not a dependency of `regpack-app`, later a test dependency of `regpack-postgres`; one `<module>` line and one `dependencyManagement` line in the root `pom.xml` | 2026-10-24 01:52 | hans | "Approve both (Recommended)" (label). The question did not name the edit of the `regpack-postgres` pom that adds `worm` to `excludedGroups`; it is shown again before T11 |
| AP-6 | The S3 service image as a `services:` entry of `evidence-worm` on `127.0.0.1` only, throwaway credentials as literals in the job `env`, no docker verb, no `sudo`, no script file | - | - | open: asked with the exact job text at T4 (version 1) and T11 (version 2) |

## Owner inputs

| Id | Question | Default | Answer |
|---|---|---|---|
| OI-1 | Plan approval, A1 to A11, D-W1 to D-W14 | none | given as AP-1 |
| OI-3 (of the P3b plan) | The S3-compatible CI service, the client, the licence position for a copyleft test-only image | CloudServer; hand-written client; no copyleft image | "Zenko CloudServer; look up the digest (Recommended)" (label), 2026-10-24 01:52. The option read: hand-written client, no copyleft image; the digest is read from the registry with read-only HTTP requests, no image pull, nothing downloaded; the exact reference is shown before it enters any CI text; the CI probe still has to confirm object-lock support. The lookup (right after the answer, 2026-10-24 about 01:52 to 01:54 UTC): the Docker Hub repository `zenko/cloudserver` was last updated in 2023 (newest tags `8.2.7` and `latest-0.0.0`, 2023-01-10); the project is now called Scality CloudServer and publishes `ghcr.io/scality/cloudserver`, whose newest release on the GitHub API is `9.5.0` (published 2026-10-16); the OCI index of `ghcr.io/scality/cloudserver:9.5.0` has the digest `sha256:652d50d0d92f5ef948b8c3aae92251b5707a70892d886835f904a069f60a0727` (one `linux/amd64` manifest and one attestation manifest); the licence field of the GitHub API for `scality/cloudserver` is `Apache-2.0`. Object-lock support was not found in the README or the release notes of 9.5.0 (a keyword search): it stays recollection until the probe. The image reference is a proposal; the owner approves it as part of the job text (AP-6, T4) |
| OI-5 (of the P3b plan) | A disposable Windows Server VM for a manual P-SEALED run (an SP-W question) | no | not asked; the default stands (a default is an answer nobody gave) |
| OI-6 | Retention class names and periods | `journal`, `sealed`, `epoch`, 1,826 days (tests and tools; Legal replaces them at G8) | default stated, not asked (the question of T1 step 4 did not include it) |
| OI-7 | The owner's reading and deciding minutes | about 45 (assumption of the plan) | not asked yet; asked at T14 |
| OI-8 | Only if probe item P4 fails: narrow the retain-until rule to whole seconds in every adapter, or use another image | the whole-second rule | not asked; decided at T7 step 1 if P4 fails |
| Group V of `docs/exit/P3b.md` (reviewer follow-ups of P3b1) | Assign to a slice or drop | no owner | not asked; the plan does not take them |

approved-job-v1-sha256:
approved-job-v2-sha256:

## Pins created

None yet.

## Deviations

None yet.

## CI checkpoints

None yet.
