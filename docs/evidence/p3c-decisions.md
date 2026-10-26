# P3c1 decisions and records

Kept by the controller (the main session). Plan: [P3c1](../superpowers/plans/2026-10-26-p3c-keys-manifests.md). Style of [p3b2-decisions.md](p3b2-decisions.md). "In chat" means in the session conversation; where a quoted option label is given, the owner selected it in the question dialog. Times are UTC. A default is an answer nobody gave; where the owner was not asked, the table says so.

## Baseline (T1 step 2, 2026-10-26)

Branch `p3c/keys-manifests` from `main` at `38f8a18` (the merge of `p3b2/worm-s3`, tag `p3b2-worm-s3`; P0, P1a, P2a, P3a, P3b1 and P3b2 are merged); the plan is committed as `ee80914` (first draft after the first review round), `408655d` (merge of `main`) and `603f8a3` (after the second review round). Maven 3.9.16, Java 25.0.4.1. `./mvnw -B -ntp verify` (2026-10-26 11:51:10 to 11:54:49 UTC, total time 03:35 min) ends `BUILD SUCCESS` with the totals, in reactor order: tools 255 and 1 IT, model 96, ports 106, pack 871 (2 skipped), testkit 80, bench 343 (1 skipped), inmemory 233, evidence 423 (6 skipped), evidence-s3 309, postgres 316 unit and 289 IT (288 skipped: no server), engine 335 and 11 IT, app 106 (2 skipped) and 6 IT: 3,780 tests run, 299 skipped, the totals the plan gives for the controller's earlier local verify of the P3b2 branch. `bash scripts/check-gates.sh` ends `check-gates: all gates passed`. `./mvnw -B -ntp -DskipTests -DskipITs -Dspotless.check.skip=true install` exits 0. `docs/spikes/freeze-record.md` line 3 reads `status: PENDING` (the spec freeze is not recorded; Departure (1)). `git diff --stat main HEAD` lists only the plan file. The ITs that need a server (PostgreSQL, the S3 service) did not run here; they run in CI only.

## Approvals

| Id | What | Date | Name | State |
|---|---|---|---|---|
| AP-1 | The plan at commit `603f8a3` (16 tasks, 106,501 bytes, 3 pushes planned) together with the items the question named: Departures (1) to (8) (the question named (2): one KEK per UTC year where spec 11.6 says "one entity KEK until then"), assumptions A1 to A12, decisions D-K1 to D-K8 and "the port and model changes it lists"; A13 to A16 were put as a veto window only | asked 2026-10-25 22:23:20 UTC, answered 2026-10-26 11:38:43 UTC | hans | "Approve the plan and these items (Recommended)" (label). No veto of A13 to A16 was given (recorded as no veto, not as approval). The question did not name the reading of the P3 exit map nor the extension of the AP-7 document rule to V003, which AP-1 of the plan lists: the first is restated at the T2 stop, the second is exercised by T2 itself (no SQL before the owner approves the document by its SHA-256). The confirmation of Departure (3) was asked as OI-10 |
| AP-2 | Every push of `p3c/keys-manifests` (CP-1, CP-2, CP-3) | | hans | asked at each push, by commit name; none yet |
| AP-3 | The one edit of `.github/workflows/ci.yml`: the push filter line `branches: [main, 'p0/**', 'p1a/**', 'p2a/**', 'p3a/**', 'p3b/**', 'p3b2/**', 'p3c/**']` (line 5 only, no job change) | asked 2026-10-26 11:38:57 UTC, answered 11:50:35 UTC | hans | "Approve the exact line (Recommended)" (label); committed in T1 step 5 |
| AP-4 | Merge into `main`, tag `p3c1-keys-encryption` and the sign-offs of `docs/exit/P3c1.md` | | | not asked (T16) |
| AP-5 | One test-scope dependency in `regpack-postgres/pom.xml`: `<dependency><groupId>dev.regpack</groupId><artifactId>regpack-inmemory</artifactId><scope>test</scope></dependency>` (for `InMemoryKms` in `PgEncryptedSealScenarioIT`; no new module, no third-party coordinate) | asked 2026-10-26 11:38:57 UTC, answered 11:50:35 UTC | hans | "Approve the exact dependency (Recommended)" (label); used in T13 only |
| AP-7 | The schema document `docs/ledger/schema-design-p3c.md` by its SHA-256, before any SQL | | | not asked (T2 owner stop); the line `approved-schema-p3c-sha256:` below stays empty until then |

AP-6 is not needed (no image, no job).

## Owner inputs

| Id | Question | Default | Answer |
|---|---|---|---|
| OI-1 | Plan approval, A1 to A12; veto or no veto on A13 to A16; the move of the owner of record of the meta-file items (Scope check) | none | plan approval given as AP-1; no veto of A13 to A16 given; the move of the owner of record was not asked (D1), so the owner of record stays P3c |
| OI-2 | The split of P3c into P3c1 and P3c2 | split | "Split into P3c1 and P3c2 (Recommended)" (label), 2026-10-26 11:38:43 UTC |
| OI-3 | KEK granularity | per UTC year | "One KEK per UTC year (Recommended)" (label), 2026-10-26 11:38:43 UTC; the question said that one year is the shredding granularity and that an object stays readable until the last retention of its year ends. Departure (2) stands |
| OI-4 | The time anchor for P3c2 (A14) | A14 | not asked (D1); A14 stays a veto window for the P3c2 plan |
| OI-5 | The encrypted classes and the reading of the journal types (A5); the DPO's view if any | A5 | "Default A5 (Recommended)" (label), 2026-10-26 11:50:35 UTC: `sealed/`, `landing/`, `enrichment/` encrypted; `journal/` and `control/` plain. No DPO view was given. The question named the risk that journal objects have free-text fields and that no check shows they hold no personal data |
| OI-6 | Retention class names and periods | `journal`, `sealed`, `epoch`, `landing`, `enrichment`, 1,826 days | "Default placeholders (Recommended)" (label), 2026-10-26 11:50:35 UTC; Legal replaces them at G8 |
| OI-7 | The owner's minutes of this slice | none: the owner's own figure | not asked (D1); asked at the T2 stop and in T16 |
| OI-8 | Group V of `docs/exit/P3b.md` | not taken here | not asked (D1) |
| OI-9 | Whether AP-3 also carries the guard of CI-2 (fail `evidence-worm` on `skipped > 0`) | no | not asked (D1); no job text is edited |
| OI-10 | Departure (3): the key-destruction job is deferred to P4 (spec 17.4 cut order, item 3) | defer | "Defer to P4 (Recommended)" (label), 2026-10-26 11:38:43 UTC; the question said that no record yet says the calibration triggers that cut order |
| Worksheet for P3c at the end of `docs/exit/P3b2.md` | task-count estimate, runs, owner minutes, re-estimate decision | | not asked (D1); the blanks are the owner's and stay open |

approved-schema-p3c-sha256:

## Pins created

Added by the task that creates each pin, in its own commit (T16 step 1 checks that every pin named in the plan's "Pins created" paragraph has a row). The plan's vectors V1 to V9 are pinned by the tests that hold them.

| Pin | Value | Task | Reason |
|---|---|---|---|
| Push filter | `branches: [main, 'p0/**', 'p1a/**', 'p2a/**', 'p3a/**', 'p3b/**', 'p3b2/**', 'p3c/**']`, expected by `SpikeWorkflowTest#ciPushFilterNamesTheWorkBranches` | T1 | AP-3 |

## Deviations

| Id | Task | What | Why |
|---|---|---|---|
| D1 | T1 step 4 | The owner message named AP-1, OI-2, OI-3, OI-10, AP-3, AP-5, OI-5 and OI-6 (two dialogs). It did not carry the move of the owner of record of the meta-file items, OI-4, OI-7, OI-8, OI-9 or the blanks of the P3c worksheet | Only the items that block T3 and the push filter were asked now; the rest is asked at the T2 stop and in T16. The defaults stand and are not answers |
| D2 | T1 step 1 | The plan commit is `603f8a3` with the message "docs: revise the P3c1 implementation plan after the second review round (not approved)" (the plan text names a shorter message) | The commit follows the second review round and the final-reader fixes |

## CI checkpoints

| CP | Head and run | Run started / ended | Owner questions | Result |
|---|---|---|---|---|
