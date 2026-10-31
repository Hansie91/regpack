# W1a decisions and records

Kept by the controller (the main session). Plan: [W1a](../superpowers/plans/2026-10-31-w1-thin-slice.md). Style of [p3c-decisions.md](p3c-decisions.md). "In chat" means in the session conversation. Times are UTC. A default is an answer nobody gave; where the owner was not asked, the table says so, and silence is not consent.

## Baseline (T1 steps 1 and 2, 2026-10-31)

Branch `w1/thin-slice`, cut from `main` at `eca2ad3` (the merge of P3c1, tag `p3c1-keys-encryption`); the plan is committed as `5f3277d` (the plan file, 141,017 bytes, and the record of the push of `main` in `docs/evidence/p3c-decisions.md`, one commit; the message differs from the text of T1 step 1: D1). `origin/main` is `eca2ad3` (`git rev-parse --short origin/main`); `git reflog show --date=iso origin/main` gives the push of `eca2ad3` at 2026-10-31 12:18:59 -0500 (17:18:59 UTC) after `38f8a18`; the tag `p3c1-keys-encryption` was created on origin by that push (the push output said "[new tag]"), so the question about the tag in T1 step 4 message A is not needed. The baseline of this branch: `./mvnw -B -ntp verify` without server variables ends BUILD SUCCESS in 7:40 min (2026-10-31 22:12:51 to 22:20:39 UTC; the XML reports of the 13 modules sum to 4,534 tests, 0 failures, 0 errors, 418 skipped, the totals of the pin "Local test counts at `24c40b0`" of `p3c-decisions.md`); `bash scripts/check-gates.sh` ends `check-gates: all gates passed`; `./mvnw -B -ntp -DskipTests -DskipITs -Dspotless.check.skip=true install` exits 0 (22:21:13 UTC); `git status --short` is empty before and after. The reflog lines quoted above are `eca2ad3 refs/remotes/origin/main@{2026-10-31 12:18:59 -0500}: update by push` and `38f8a18 refs/remotes/origin/main@{2026-10-25 15:08:11 -0500}: update by push`.

## Approvals

| Id | What | Date | Name | State |
|---|---|---|---|---|
| AP-1 | The plan at commit `5f3277d` (141,017 bytes, T1 to T18) with the departures (1) to (21), the assumptions A1 to A12, the decisions D-W1 to D-W11, the port and model changes (one non-additive: the relaxed decision context), the estimate and its stop rule (central 128 runs and 2,665 active minutes, low 59 and 699, stop 166 runs and 3,465 minutes) and the first-run point (nothing runs end to end before T15, the 14th of 16 units) | asked 2026-10-31 21:17:29 UTC (a chat message with the scope, what the slice is not, the cost and the questions), answered 2026-10-31 22:12:14 UTC | hans | "I approve the plan" (the first words of his chat message; no dialog, no label). Read as the approval of the plan as written and of nothing the message did not name: it does not answer AP-3, AP-5, AP-9, AP-10 or the defaults of OI-4 to OI-13, which T1 step 4 asks with their exact text |
| AP-2 | Every push of `w1/thin-slice` (CP-1, CP-2), by commit name | | hans | asked at each push; the tag `p3c1-keys-encryption` is already on origin (see Baseline) |
| AP-3 | The exact hunks of `ci.yml` line 5 (`'w1/**'` added to the push filter), `SpikeWorkflowTest#ciPushFilterNamesTheWorkBranches` and the two edits of `NoRegimeLogicInEngineTest` (plan AP-3 (a), (b), (c)) | | | asked in T1 step 4 message A (see Owner inputs) |
| AP-4 | Merge into `main`, tag `w1a-thin-slice` and the sign-offs of `docs/exit/W1a.md` | | | not asked (T18) |
| AP-5 | Two new modules, no third-party coordinate: `dev.regpack:regpack-flow` and `dev.regpack:regpack-sandbox`, with their dependency lists and the root pom entries (plan AP-5) | | | asked in T1 step 4 message A |
| AP-9 | The G1 and G3 records (plan AP-9: exact lines) | | | asked in T1 step 4 message B; never blocks W1a |
| AP-10 | The download of the ESMA schema zip named in the plan (and, only if leg (c) of G3 is signed, the second file), each with its size from one approved HEAD request; the `G3Check` edit in W1b | | | asked in T1 step 4 message B; W1b only |

AP-6, AP-7 and AP-8 are not needed (no image or job, no migration, no PostgreSQL).

## Owner inputs

| Id | Question | Default | Answer |
|---|---|---|---|
| OI-1 | The split (S1) and the W1b statement | split | approved with the plan (AP-1, 2026-10-31 22:12:14 UTC): the plan he approved states the split |
| OI-2 | Where a firm without an ARM sends | W1a sends to the profile `demo.sim.file-drop` (SIMULATOR, SELF, authority SIM) | ANSWERED 2026-10-31 22:12:14 UTC, in his words: "the firm without an ARM, would sent it itself directly to the local or foreign NCA or a Trade Repository or just store or email the files to internal location or email adresses". Read as four kinds of destination for the endpoint profile and the `Transport` port, all with route SELF: (1) a local or foreign NCA, directly; (2) a Trade Repository (a TR belongs to the EMIR flow, plan P12, and not to MiFIR RTS 22); (3) a store at an internal location; (4) email to addresses. W1a builds the file drop only (A8), which is kind (3) in its simplest form; W1b chooses the transports for (1), (2) and (4) (SFTP or HTTPS, SMTP). Still not answered, and needed before W1b picks a channel: which NCAs, their channel and onboarding, and whether they take direct files (G6); the home NCA's technical specification before W1b (ii) fixes a file-name pattern or a head.001 content |
| OI-3 | Legal reading: does enrichment, validation and sending for the firm's own transactions make the platform an ARM (DRSP) needing authorisation | recorded unconfirmed; blocks go-live, not W1a | ANSWERED 2026-10-31 22:12:14 UTC, in his words: "No doing validation, enrichment etc does not make this an ARM, it is simply a helpful tool". Recorded as the owner's reading and decision. It is not a legal opinion of counsel (none is in the repository, and the agent gives none), so gates G2 and G9 stay as the specification states them; whether his statement is the Compliance-SME confirmation of G2 is for him to record when G2 is cleared |
| OI-4 | The owner's full name and role for `governance/golden-reviewers.txt` | no line until supplied; goldens stay consistency-only | asked in message B |
| OI-5 to OI-13 | Source columns, lifecycle (NEWT and CANC only), capacity (AOTC and MTCH), enrichment sources, `refdata_wait`, G-S3b, D16, the SYNTHETIC marker rule, the effort and the stop rule | the defaults of the plan | asked in message A |

## Pins created

(Added by the task that creates each pin, in its own commit.)

| Pin | Value | Task | Reason |
|---|---|---|---|

## Deviations

| Id | Task | What | Why |
|---|---|---|---|
| D1 | T1 step 1 | The plan was committed as `5f3277d` "docs: W1a plan for the thin end-to-end slice (not approved); record the push of main and its CI run (P3c1 AP-4)" and not as "docs: add the W1a thin-slice implementation plan"; the commit also holds the AP-4 record of P3c1 in `docs/evidence/p3c-decisions.md` | the push of `main` and its CI run (41223936757) had to be recorded and the planning workflows were reading the repository until the plan was final |

## CI checkpoints

| CP | Head and run | Run started / ended | Owner questions | Result |
|---|---|---|---|---|
