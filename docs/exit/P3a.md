# P3a exit record: ledger core on PostgreSQL

Date: 2026-10-18 (owner local time). Branch: `p3a/ledger-core` (from `main` at `e685b7f`, 40 commits before this record). Plan: [P3a plan](../superpowers/plans/2026-10-17-p3a-ledger-core.md). Spec: [design specification](../superpowers/specs/2026-10-08-regulatory-reporting-platform-design.md). Records: [decisions.md](../ledger/decisions.md) (approvals, owner decisions, pins, deviations, the review and its fixes, every checkpoint), [schema-design.md](../ledger/schema-design.md) (owner-approved), [p3a-notes.md](../ledger/p3a-notes.md) (the ledger as built, limits, hand-offs), [capacity-p3a.md](../ledger/capacity-p3a.md) (the L-4 capacity information), [P2a exit](P2a.md).

## Verdict

P3a put the ledger on a real PostgreSQL 18 with its constraints proven by the real store, behind the same contract suites as the in-memory adapter: the owner-approved schema (18 tables, daily partitions of `decision`), the circuit epoch (INTERP-3), TRN derivation and assignment, the report ledger (subjects, versions, deliveries in PREPARED, outbox SEQUENCE), fenced leases, the partial unique in-flight index and the head compare-and-set, a plain-SQL migrator, integrity queries, six schema mutants, and the CI job `ledger-pg` that runs 462 tests (235 real-store tests and 227 unit tests) beside a digest-pinned PostgreSQL 18 service container (image tag 18.6) on every push. **The last CI run of the final code (CP-4b, [run 39192246624](https://github.com/Hansie91/regpack/actions/runs/39192246624), head `e106545`) is green on all jobs, and the `ledger-pg` job ran 44 test classes and 462 tests (235 real-store tests in 11 IT classes, 227 unit tests in 33 classes), 0 failures, 0 skipped.** Clauses of the P3 exit criterion that P3a claimed (plan split table):

| Clause | Result | Evidence |
|---|---|---|
| Constraints reject seeded duplicates, including a cross-partition duplicate `decision_seq` (the latter is rejected by the head compare-and-set: unique indexes cannot see across partitions) | **closed for the keys and foreign keys listed in [p3a-notes.md](../ledger/p3a-notes.md) section 4**, not for every one of the 48 named objects of the approved constraint block | `PgConstraintsIT` (34 tests), `PgDecisionsIT#crossPartitionDuplicateDecisionSeqIsRejectedByTheHead`, `PgSchemaTeethIT` (six mutants: dropping a constraint makes the named contract tests fail) |
| I2 (exactly-once) on in-memory and real PostgreSQL | closed for the P3a constraints; the `gate_released` path of the in-flight index comes with the state machine in P3d | 6 REALSTORE lines in `docs/traceability.md` (for example `PgLedgerStoreContractIT#redeliveredBatchIsNoopAndReturnsOriginalResult`, `#inFlightGuardAllowsOnlyOneDeliveryPerBusinessKey`, `PgConstraintsIT#secondInFlightDeliveryForOneBusinessKeyIsRejected`) |
| I9 (single sender, fencing) on in-memory and real PostgreSQL | closed for the lease and token protocol | 6 REALSTORE lines; four are the spec 14.4 scenarios (`PgLedgerStoreContractIT#twoConcurrentSendersOnlyOneReachesTheDestination`, `#takeoverDuringAnInProgressUploadAbortsTheOldSenderAtTheGate`, `#stallAfterTokenCheckResumesAfterTakeoverAndTheMarkerCasFails`, `#restoreThenZombieSenderIsFencedAtTheGate`) and `PgFencingIT#theTokenPairIsComparedAsAWholeAcrossEpochs` covers a stale token against a lease row restored to epoch 0 |
| Ledger half of I6 | **partial**: the partial unique in-flight index and the fenced marker only; the IN_DOUBT edges stay P3d | `PgLedgerStoreContractIT#inFlightGuardAlsoCoversAwaitingAckAndInDoubt` |
| Fixtures STALE, same-version-different-hash, lower-version, DEAL/MTCH/AOTC/DO_NOT_REPORT, a TRN shared by NEWT, CANC and re-NEWT with a second subject rejected | closed (ledger dispositions) | `ExitFixturesContract`, `IngestLedgerContract`, `ReportLedgerContract` on both adapters; `PgLedgerStoreContractIT` 126 tests |
| A-B-A (ingest and the decision fixture with its `report_version`) | **narrowed**: P3a proves that the third decision is a new row with `decision_seq` 3 whose `report_version` reflects A for decisions of the same outcome; an A-B-A chain with differing outcomes on a chain that has versions fails closed (`WithdrawalRequiredException`, pinned by `abaWithADifferingOutcomeOnAChainWithAVersionFailsClosed`) until the WITHDRAWN transaction of P3d | `abaProducesThreeVersions`, `abaEndToEndThirdDecisionIsANewRowWhoseVersionReflectsA` |

Open clauses and their slices: I1 and the storage half of I10 (P3b), the key hierarchy and signing (P3c), the delivery state machine, parked orphans and the completeness job (P3d). **The P3 row is complete only when P3b to P3d have also exited.** Everything above is evidence on a single PostgreSQL 18.6 server in a service container; the quorum synchronous standby and the durability gate (P3b), a Windows real-store run and the hot path at 50 million records a day were not run. Test totals of the final local `clean verify`: tools 233 and 1 IT, model 96, ports 21, pack 871 (2 skipped), testkit 71, bench 343 (1 skipped), inmemory 181, postgres 227 unit and 235 IT (234 skipped locally, run in CI), engine 335 and 11 IT, app 95 (2 skipped) and 6 IT; the P0 skeleton toy digest `70382303f446476321d38d50817bdd8d541e0dcb61d8f33d92435c676bc116c9` and the pack-pipeline digest `b7996d2acfd7966b592c3c3c36d42307f0f1cad50b155f9b20c8d8dfc2bf687d` are unchanged.

## The CI runs

| Checkpoint | Head | Run | Result |
|---|---|---|---|
| CP-1 | a53ecf9 | [39143560420](https://github.com/Hansie91/regpack/actions/runs/39143560420) | green; the migration V001, written without a server, applied on the first run (32 tests in 7 classes; 13 of them ran against the server: `MigratorIT` 8, `PgHarnessIT` 5) |
| CP-2 | 68ea4ec | [39155154276](https://github.com/Hansie91/regpack/actions/runs/39155154276) | red: 125 of 126 real-store tests passed, one test dropped a partition the way PostgreSQL forbids; fixed by `525faae` |
| CP-3 | 10d2bab | [39163580918](https://github.com/Hansie91/regpack/actions/runs/39163580918) | green; the six schema mutants were caught |
| CP-3b | 6b33bf1 | [39175576536](https://github.com/Hansie91/regpack/actions/runs/39175576536) | green; the capacity job skipped on a push, as designed |
| capacity dispatch (owner, AP-8) | 6b33bf1 | [39177466330](https://github.com/Hansie91/regpack/actions/runs/39177466330) | completed (informational: variants B and C did not sustain the offered rate); about 42 minutes each (see the capacity information) |
| CP-4 | 8662ea8 | [39191128036](https://github.com/Hansie91/regpack/actions/runs/39191128036) | `ledger-pg` green (462 tests, the first run of all review fixes); the two build jobs red: two `GatesTest` tests read `GITHUB_ACTIONS` from the process environment; fixed by `2545257` |
| CP-4b | e106545 | [39192246624](https://github.com/Hansie91/regpack/actions/runs/39192246624) | green on all jobs (the capacity job skipped) |

Six pushes against four planned; two red runs before green, both caused by CI-only differences (a partition drop on a real server; a test reading the CI environment). The annotation channel that the plan added (D-L6) worked for CP-2: the failing test and its message were read from the unauthenticated check-run listing without a sign-in. For CP-4 the annotations held only the traceability problem texts (`MISSING_ROW:I7`, `ARTEFACT_MISSING:R1:a.B#it1`) and the exit code, so the two `GatesTest` tests and the `GITHUB_ACTIONS` cause were identified from that text and a local reproduction.

## Pins

New (value, task and reason in [decisions.md](../ledger/decisions.md)): the SHA-256 of `V001__core.sql` `1fa65ca834cf60c72c0609cbc5f932dd5a41191621a880dab63a121869975769`; the approved schema document `5ed500918349603304a7552fbc00ab4ef3ce6adc3ac442e028b5e68488ea1792`; the state codes inside V001 (SENDING 4, AWAITING_ACK 5, IN_DOUBT 11, outbox PENDING 1) and the full code table of `Codes`; the decision body codec v1 (canonical JSON SHA-256 of the fixed body `fdda5b9e973526cb17ef4a59a103fcb8ecd45789edd96e1ccd560a59b08ae075`, a golden stored blob, the frozen preset dictionary); the `ingest_batch.result` layout v1; the TRN known-answer vector (a fake `Kms` returning the bytes 0x00 to 0x1F gives a base32 string that starts `AAAQEAYE`); the dead delivery states of the suppression rule (REJECTED, IN_REPAIR, WITHDRAWN, WRITTEN_OFF); the key text bounds, the as-at range `[2000-01-01, 2200-01-01)` UTC, `MAX_TTL` 36,500 days; the caps (1,000 integrity violations per kind, 366 days of partition pre-creation, 4 partition attempts with pauses of 100, 300 and 900 ms, the 16 MiB decoder limit, 10,000 cached dictionary ids). Unchanged: the P0 skeleton toy digest, the P2a pack-pipeline digest, the sample pack digest and every digest literal of `DecisionTypesTest` (epoch 0 is omitted from the canonical form). The spec freeze is not recorded (`docs/spikes/freeze-record.md`, status PENDING); V001 and the schema document are frozen by the tests that compare their hashes.

## Existing tests that changed

As the plan lists (`DecisionTypesTest` and the engine tests for the circuit epoch, the P0 contract suites for the T4 edge behaviours, `PackagingFilesTest` for the new jobs and, since FP3, the `ledger-pg` steps, `DistributionLayoutIT` for the two new prefixes) and, during the fixes: `GatesTest`, `TraceabilityCheckTest` and `PinCheckTest` (and the new `AnnotationsTest`) for the annotation order, environment independence and the docker rule; `PgDecisionsIT#aDroppedPartitionIsCreatedAgainAndTheCallThatFoundItMissingSucceeds` (renamed from `aDroppedPartitionFailsOnceAndIsCreatedAgain`; the first call after a drop now succeeds); two `PgDecisionsIT` tests that counted dictionary rows as zero after a failed unit (they now expect the orphan rows, FP1); `IngestResultCodecTest` and `PackCommandsTest#explainEventEscapesAndBoundsTheEventIdentity` (the new key text rules refuse values they used, FP2).

## Capacity information (L-4)

[capacity-p3a.md](../ledger/capacity-p3a.md), from the owner's dispatch on `6b33bf1` (before the review fixes). In short: a cache-hot primary on a 4-vCPU runner kept the offered 3,470 records/s (100 percent completed); a memory-limited primary (1363 MiB, a table-to-memory ratio of 5.5, harder than SP-L's 2.82 because this ledger is 1,899 bytes a record against 969) completed 74 to 78 percent, like SP-L's two memory-limited runs (72 and 73 percent); the outcome therefore **reproduced without SP-L's standby and replica** (the generator still shared the VM); switching the commit wait off did not help (it was slower on another VM, which cannot be attributed to the setting). The decision row measured 797 bytes against the 300 to 500 byte band. The cause is **not established** (the sampler files were not read) and **the owner's L-4 decision (OI-3, D-1) is open**; the agent proposes none. A second dispatch on the final head (with the review fixes and the fixture records CPU, disk, network and fsync canary) is available if the owner wants it. The fixture additions of CP-4b (CPU model, mount, network mode, an fsync canary, the cgroup directory) have never run: the capacity job was skipped on every push and the lines are guarded by `|| true` except the container id lookup; only a dispatch exercises them.

## Owner inputs answered

OI-1 plan approval (AP-1, 2026-10-18); OI-2 circuit epoch, option A; OI-4 daily partitions; OI-5 one ingest table; OI-6 keep every row in release 1; OI-7 E2 body (about 800 bytes, outside the spec band, acknowledged); OI-9 one reporting entity, incumbent unknown, RSV-1 reserved; OI-13 no local PostgreSQL; OI-15 about 30 minutes for T1 and T2 (more to come at this stop); the run-or-cut question of T21 ("Run T21 (Recommended)"). By default (no answer needed so far): OI-3 (open), OI-8, OI-10 to OI-12, OI-14 (proposal in p3a-notes.md section 5). Quoted in decisions.md.

## Open items

For the owner; the full list is the end of [decisions.md](../ledger/decisions.md) and [p3a-notes.md](../ledger/p3a-notes.md):

1. **L-4 consequence (OI-3, D-1)** after reading the capacity information; and whether to run a second dispatch on the final head.
2. **P2a open item 1 stays open**: the circuit epoch is process-local; the durable per-pack-digest epoch (`pack_circuit`) is a P3d table (D-L1). A restart loses a close; a pack rollback has the same hole.
3. **Retention**: `report_version_decision_fk` makes a plain `DROP` of an old daily piece fail (seen at CP-2); `DETACH PARTITION` followed by `DROP ... CASCADE` works on a piece that nothing references (CP-3, CP-4); that DETACH refuses a piece whose decisions are referenced is expected from PostgreSQL but was not run. The owner chose "no purge in release 1"; the design question is due before the first production quarter.
4. **Deviations from the approved schema document, owner-decided or recorded**: the per-entry `ingest_batch.result` (about 1 GB a day, owner chose it); the dictionary rows committed before the write unit (FP1; the document says "in the same unit"; orphan rows are harmless); the unused column `delivery.batch_id` (reserved for P3d).
5. **Partition creation in production**: `ensurePartitionsAhead` exists but nothing calls it; a P3d or P7a scheduler must pre-create two days ahead; the lock modes that attaching a partition takes on `report_version` and `decision_context` should be printed in the CP-4 and CP-4b logs (not read: the logs need a sign-in), and the premise of the approved schema document about those locks was stated from memory when the owner approved it and is "not contradicted", not confirmed; a plausibility window for the as-at is deferred (up to 73,000 partitions from source dates spread over the allowed range).
6. **Poison records**: the key text rules now refuse NUL, unpaired surrogates and over-long values with `IllegalArgumentException` before any write; the P7a pipeline must quarantine such a record instead of failing the batch. `pack explain --event` now refuses a C0 control and an identity over 512 characters.
7. **Statement count of the version path** (about 8 statements per created version, estimated; about 4 JDBC executions per record, measured by the probe): the evidence is in the capacity information; a set-based path is a lever, not a P3a task.
8. **Plain report bodies until P3c**: `report_version.body` is plain canonical JSON (flagged in the schema document); the adapter must not be wired to real data, and no personal data may enter it before P3c; callers must log `getMessage()` only, never the throwable (the cause can carry report content). The insert-only triggers do not protect against the table owner or a superuser; the accepted cross-source clash has no check query.
9. **Circuit and explain details**: `evaluateTraced` refuses a stale epoch, so Tier-2 replay of a decision stamped at an earlier epoch is impossible until the durable epoch of P3d; `RuleCircuit.close` raises the epoch before it clears the open flag, so an evaluation can pair the new epoch with a still-open rule (the opposite direction of the recorded limit). The definitions of `HEAD_MISMATCH` and `VERSION_GAP` in `LedgerIntegrity` are the implementer's reading of the plan; P3d confirms them.
10. Known limits recorded: a unit whose wave mixes a redelivered and a new chain can deadlock (the result is a `DecisionHeadConflictException`, never a wrong write); `decision_id_seq` gaps on redelivery (P4 resumes above the maximum); `DecisionHeadConflictException.currentHeadSeq` differs between the adapters inside a multi-decision unit; `SchemaDocMatchesMigrationsTest` is frozen to V001 (V002 in P3b needs a document per slice); the advisory-key derivations are not pinned (TEETH-8).

## Deferred

P3b (evidence stores and the durability gate), P3c (keys, signer, timekeeper), P3d (delivery state machine, queues, completeness, the durable epoch), and the P7a hand-offs (re-pinning batch driver, quarantine of refused records, pre-creation scheduler). The hand-off lists are in [p3a-notes.md](../ledger/p3a-notes.md) section 6.

## Approvals actually given

| Id | What | Date | By |
|---|---|---|---|
| AP-1 | the plan, departures, the split, the port and model changes, D-S1 to D-S17 as starting positions, A1 to A13: "Approve the plan (Recommended)" | 2026-10-18 | hans |
| AP-2 | pushes: CP-1, CP-2, CP-3, CP-3b, CP-4, CP-4b (each asked; the answer "Yes, push now (Recommended)" is quoted in decisions.md for CP-1 and CP-4b) | 2026-10-18 | hans |
| AP-3 | edits of `ci.yml` and `check-gates.sh`: the `ledger-pg` job text; the `ledger-capacity` job text with the concurrency edit; the fixture records of CP-4b | 2026-10-18 | hans |
| AP-5 | the PostgreSQL JDBC driver 42.7.14 and checker-qual 3.55.1 in `regpack-postgres` only | 2026-10-18 | hans |
| AP-6 | CI may pull the digest-pinned `postgres:18.6-trixie` as a service container | 2026-10-18 | hans |
| AP-7 | the schema design, SHA-256 `5ed50091...`, all eight sheet recommendations accepted and E2 chosen | 2026-10-18 | hans |
| AP-8 | the capacity dispatch, performed by the owner (run 39177466330, actor Hansie91, 23:39:58Z); no artefact was downloaded | 2026-10-18 | hans (the GitHub run actor) |
| AP-4 | merge, tag `p3a-ledger-core` and the sign-offs below | **open** | owner only |

## Deviations from the plan

Consolidated in decisions.md "Deviations from the plan". The ones that matter to the owner: the per-entry ingest result (owner-decided), the dictionary rows before the unit, the `Config` schema component and the `Unit.begin(Connection, Config)` signature, the annotation order, seven permanent flaws in the in-memory adapter's main sources, the smoke test of the capacity probe that runs in every `ledger-pg` verify, and the narrowing of two exit claims (A-B-A, seeded duplicates).

## Final whole-branch review (plan T23 step 4)

Seven read-only lenses over `git diff main...HEAD` at `88e8958`, 26 subagent runs, every finding above minor re-checked by a skeptic. 19 findings above minor and 36 minor. Confirmed as **major** by the skeptics: SQL-1 (dictionary rows created inside the long unit), SQL-4 with MODEL-1 and SPEC-4 (values PostgreSQL rejects or alters), TEETH-2 (the withdrawal rule tested only for the MARKET leg); the rest were downgraded, refuted (SQL-2) or left plausible. **No finding changed a claimed exit clause except the two narrowings above.** Fixes FP1 to FP5 (one implementer and one independent reviewer each, 10 subagent runs, no fix rounds) are commits `c7a9db1 d16d934 cfb5ae8 2a635e2 6cdb06d`; three controller commits followed from CP-4: `2545257` (the `GatesTest` fix), `468cda0` (the fixture additions of the capacity job, never run) and `e106545` (the record).

## Effort for the spec section 15 calibration

Ledger (agent working time and tokens; CI waits and owner time not counted):

| Piece | Subagent runs | Subagent tokens | Tool calls | Tests added | Wall-clock (min) |
|---|---|---|---|---|---|
| Planning (planner, 5 lenses, adjudicator, fixer, final check) and a cross-reference fixer | 10 | 2,838,668 | 541 | - | 95 |
| T2 schema document | 4 | 631,330 | 94 | 0 | 21 |
| T3 to T9 | 16 | 2,232,924 | 513 | 63 | 113 |
| T10 to T13 | 10 | 1,413,280 | 295 | 36 | 77 |
| T14 | 2 | 292,895 | 61 | 23 | 20 |
| T15 to T17 | 6 | 2,095,455 | 406 | 96 | 127 |
| CP-2 fix | 2 | 178,002 | 41 | 2 | 18 |
| T18 to T20 | 6 | 1,659,273 | 355 | 61 | 82 |
| T21 | 4 | 973,964 | 254 | 11 | 87 |
| T22 | 2 | 247,982 | 54 | 1 | 11 |
| Final review | 26 | 4,845,121 | 810 | 0 | 52 |
| Fixes FP1 to FP5 | 10 | 2,416,292 | 760 | 161 | 132 |
| **Total** | **98** | **19,825,186** | **4,184** | **about 454** | **about 835 (13.9 hours)** |

The count of tests added is as the implementers reported it (not reconciled with git; the Maven total grew from 2,085 at the P2a baseline to 2,726, which is +641, of which 235 are real-store tests skipped locally, and shared contract methods run once per adapter); two fact-check agents (this record and the capacity read-out) are not in the table. Git counts: `git rev-list --count main..HEAD` is 40 before this record (41 with it); `git diff --shortstat main...HEAD` is 183 files changed, 31,372 insertions(+), 180 deletions(-) before this record.

**Against the pre-registered estimate** (plan header): 23 tasks (22 done, T21 included; T23 is this record and the sign-off); about 90 subagent runs (98, plus 2 fact-checks); about 520 agent minutes (about 835); 9 owner stops (11 counted: T1 step 4, the schema sheet, the CP-1 push with the job text, the CP-2 push with the batch-result question, CP-3, the T21 run-or-cut question, the T21 job text with the CP-3b push, the capacity dispatch, CP-4, CP-4b with the fixture additions, and this sign-off); 4 planned CI pushes (6) with 5 to 8 fix rounds expected for CI-only failures (2 red runs, 1 fix commit each). **Against P2a**: 25 tasks and 6 fix tasks, 97 subagent runs, 15.7 million tokens, about 565 agent minutes, about 270 owner minutes. The overrun of agent time over the estimate (60 percent) is the final review and its fixes (26 + 10 runs, 184 minutes) and the extra checkpoints; the review again found three major defects that none of the per-task reviews had rated as major (a locking pattern, a cross-adapter divergence, a test gap), but it found them after the capacity dispatch, which therefore measured the code with SQL-1 (a second dispatch on the final head would show the effect of FP1). Owner minutes: about 30 recorded for T1 and T2; the owner's estimate for the later stops (CP-1 to CP-4b, the T21 approvals and dispatch, this record) is requested at the sign-off.

Worksheet for P3b to P3d (the owner fills the blanks): task-count estimate ____ ; subagent runs (about 4 per task in this record) ____ ; owner reading and deciding minutes for this record ____ ; re-estimate decision for P3b (continue, or the cut order of spec 17.4) ____ .

## Sign-offs (owner)

- [ ] AP-4: merge `p3a/ledger-core` into `main` and tag `p3a-ledger-core` (not set, not pushed)
- [ ] exit criterion accepted (the closed clauses and the two narrowings above, on a single PostgreSQL 18.6 service container)
- [ ] port and model changes accepted (`ContextLedger`, `ReportLedger`, `NewDecision.context`, `OutboxKind.SEQUENCE`, `OutboxLedger.PAGE_LIMIT`, `DecisionContext.circuitEpoch`, `AsAtRange`, `KeyText`)
- [ ] schema design approved (AP-7) and the deviations of open item 4 accepted
- [ ] INTERP-3 recorded (option A) and its limit (open item 2) accepted
- [ ] L-4 consequence recorded, or deferred to the spec freeze (OI-3, D-1)
- [ ] dependency approvals recorded (AP-5, AP-6)
- [ ] defaults accepted so far without a separate answer: OI-10 (TRN derivation with the regime in the HMAC message, 32 characters) and OI-11 (the Appendix C dispositions of the T4 table)
- [ ] traceability `closes_in` proposal accepted or edited (p3a-notes.md section 5; `exited_plans` is not changed by the agent)
- [ ] calibration read-out done and the P3b re-estimate decision recorded (spec 15)

Signed: <owner>, <date>
