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
| AP-7 | The schema design `docs/ledger/schema-design.md`, recorded with its SHA-256 | 2026-10-18 | hans | given in chat ("Approve as it stands (Recommended)") at the T2 stop, for the document of commit b9e3b85 with SHA-256 `5ed500918349603304a7552fbc00ab4ef3ce6adc3ac442e028b5e68488ea1792` (`sed 's/\r$//' docs/ledger/schema-design.md \| sha256sum`); the owner answered the Decision sheet as: accept all eight recommendations (rows 1 to 4 and 6 to 9) and E2 for row 5; any later change of the document needs a new approval. The lock levels of ATTACH PARTITION and PARTITION OF are NOT CONFIRMED from the PostgreSQL 18 manual (the implementer could not read it; the controller states them from memory: ATTACH takes SHARE UPDATE EXCLUSIVE on the parent, PARTITION OF takes ACCESS EXCLUSIVE); CP-2 measures `creatingAPartitionDoesNotBlockInsertsIntoOtherPartitions` |
| AP-8 | Each manual dispatch of the capacity job and the owner's download of its artefact | | | open (T21) |

## Owner inputs

Answered on 2026-10-18 where the owner answered; "default accepted" means the default of the plan stands until the task that decides it.

| Id | Question | Default | Answer |
|---|---|---|---|
| OI-1 | Plan approval and A1 to A13 | proceed on AP-1 | AP-1 given |
| OI-2 | INTERP-3: circuit epoch in the decision context (A), ordinal in the key (B), reason code in the key (C) | A | **A, given by the owner in chat** (T3 implements it) |
| OI-3 | D-1, the L-4 consequence | sizing and tuning input; decided after the T21 read-out | default accepted; decided after T21 |
| OI-4 | Partition granularity of `decision` | daily on the UTC date of `as_at` (D-S1) | **daily, decided by the owner at the T2 stop (sheet row 1)** |
| OI-5 | Ingest layout | one unpartitioned table with a global key (D-S5) | **one big table, decided by the owner at the T2 stop (sheet row 2)**; the cross-source clash (row 3) is accepted and documented, no extra table |
| OI-6 | Growth and retention of `decision_head`, `ingest`, `report_subject` | no purge in release 1 (D-S14) | **keep everything in release 1, decided by the owner at the T2 stop (sheet row 4)**; the archive decision is due before the first production quarter; the whole ledger is about 91 GB a day without report bodies |
| OI-7 | Decision body encoding | E2 of A13 (deflated canonical JSON), size measured before the stop | **E2, self-contained, decided by the owner at the T2 stop (sheet row 5)**; the owner acknowledges that it is about 800 B a row, outside the spec 5.6 band of 300 to 500 B (40 GB a day, 73 TB over 5 years for decisions alone); a version byte lets a smaller form serve new rows later |
| OI-8 | Micro-batch N for sizing and the probe | 5,000 and 10,000 measured | default accepted; decided at T21 |
| OI-9 | Number of reporting entities; incumbent; RSV-1 | one; unknown; reserved | **accepted by the owner in chat** ("Yes, accept these defaults") |
| OI-10 | TRN derivation: regime in the HMAC message, 32 characters | as stated | default accepted; decided at T6 |
| OI-11 | Appendix C dispositions in the T4 table | as in the table | default accepted; decided at T4 |
| OI-12 | Wire `regpack-postgres` into `regpack-app` now | no (A9) | default accepted |
| OI-13 | A PostgreSQL 18 server reachable from the development machine | none: CI only (A3) | **none available, accepted by the owner in chat** |
| OI-14 | Name P3a to P3d in the spec 16 table; `closes_in` per slice | `closes_in` stays as the spec; proposal presented at T23 | default accepted; decided at T22 and T23 |
| OI-15 | The owner's reading and deciding time (minutes) at T1, T2, T21 and T23 | estimated by the owner | T1 and T2 (plan summary, approvals and the decision sheet, without the full reading of the schema document): about 30 minutes (owner, chat 2026-10-18); T21 and T23 follow |

approved-schema-sha256: 5ed500918349603304a7552fbc00ab4ef3ce6adc3ac442e028b5e68488ea1792

## Pins created

| Name | Value | Task | Reason |
|---|---|---|---|

## Owner decisions at the schema stop (T2, 2026-10-18)

Sheet rows of `docs/ledger/schema-design.md` (document SHA-256 above): 1 daily partitions of `decision` (OI-4, D-S1); 2 one unpartitioned `ingest` table (OI-5, D-S5); 3 the cross-source clash is accepted and documented (a check query would find it afterwards; it is not in the plan and would cost one extra method in T19, open question 9 of the document); 4 keep every row of the uncut tables in release 1 (OI-6, D-S14); 5 E2 for the decision body (OI-7, D-S6); 6 four fixed columns on `decision` (outcome, circuit-open flag, entity id, body-format code; D-S17); 7 chain linearity across pieces by the application check plus a check query, no trigger (D-S4); 8 idempotency key inside each piece (D-S3); 9 insert-only triggers on `decision`, `report_version`, `trn_assignment` (D-S16); 10 micro-batch size has no schema effect (OI-8, decided at T21).

Plan defect carried to T10 and T11: the plan text of T10 (V001, around the sequences) lists a sequence for `outbox_id`, while D-S11 and the approved document use `GENERATED ALWAYS AS IDENTITY` for `outbox_id`; the approved document governs V001. Unconfirmed premise: see AP-7 (lock levels of attaching a partition).

## Deviations from the plan

None yet.

## Limits recorded by tasks

- **T3 (OI-2 option A, D-L1): the circuit epoch is process-local.** `RuleCircuit.epoch()` lives in the `RuleCircuit` object, so after a restart it is 0 again: a close before the restart is invisible to the context and the re-evaluation of an `EXEC_CIRCUIT_OPEN` decision is a `NOOP_REDELIVERY` again. A pack rollback (P9b) has the same hole. P2a open item 1 therefore stays open until the epoch is durable: the per-pack-digest table `pack_circuit (pack_digest PK, epoch)`, read when the `RuleCircuit` is created and incremented in the transaction that records the close, is a P3d table. A batch pinned at an older epoch is refused as stale by `PackRegime` and must be pinned again (the batch driver is P7a).

## Chunk record: T2 to T9 (base 6647436, head eec98c7)

T2: `docs/ledger/schema-design.md` (b9e3b85; 1 fix round; approved above). T3 to T9: 7 commits (4efdf4a 0c35034 3305039 b7e1664 8b18d27 81c55d7 eec98c7), 63 tests added, 16 subagent runs, 2,232,924 subagent tokens, 513 tool calls, 1 fix round (T7); the controller re-ran the full `verify` (BUILD SUCCESS: model 78, ports 16, inmemory 155, engine 334 and 11 IT, other modules unchanged) and `check-gates.sh` (all gates passed); the toy digests `70382303...` and `b7996d2a...` are unchanged.

- **T3 (OI-2 A).** `DecisionContext.circuitEpoch` (tenth component; the nine-argument constructor means 0; written to the canonical form only when positive, so no existing context id changes); `RuleCircuit.epoch()` (close of an open rule adds 1); `PackRegime.decisionContext()` returns the base context with the current epoch; `evaluate` and `evaluateTraced` refuse a caller context with another epoch as a stale decision context. The replacement of `aReEvaluationAfterCloseHasTheSameContextAndDigest...` by `aReEvaluationAfterCloseCarriesANewEpochSoTheLedgerSupersedes` proves the supersession on the in-memory ledger. Limits (D-L1): the epoch is process-local, so P2a open item 1 stays open until P3d adds the durable epoch; a pack rollback has the same hole. Hand-offs for the T22 list: `MicroBatchPipeline` keeps one context and is refused after a close until it is re-pinned (a P7a acceptance test); `evaluateTraced` also refuses a stale epoch; a close racing an evaluation in flight may stamp the old epoch. Reviewer minors left: the order of the context checks is not pinned by a test.
- **T4 (OI-11).** `EventIdentity` is stricter; `BatchId.landingDigest()` and `slice()` accept only the canonical form (64 lowercase hex, a colon, a decimal slice without sign or leading zero, fitting an int); `OutboxLedger.PAGE_LIMIT`; Javadoc-only edits of `IngestLedger`, `LeaseLedger`, `DeliveryLedger`. **For T15:** the PostgreSQL adapter must apply the same disposition order as the in-memory one: exact key, then (event, source, version) by hash, then source conflict, then stale; and two entries of one commit with the same event, source and version are decided against each other in order (first NEW_VERSION, second NOOP or CONFLICT).
- **T5.** The decoders are strict (unknown keys refused; `bodyFromMap` also accepts the raw map of `bodyToMap`); the decision-context dictionary is kept by the ledger; a context id that differs from the body's is refused by the adapter (not by the `NewDecision` constructor). The new context tests impose behaviour on the PostgreSQL adapter that first runs at CP-2 (`aRedeliveryWithAStaleExpectedHeadIsNoopNotAConflict`, `sameEventContextAndDigestUnderTwoRegimesInsertTwoDecisions`).
- **T6 (OI-10).** `TrnDerivation` (regime id in the HMAC message, 32 base32 characters by default). Known-answer pin: a fake `Kms` whose `hmac` returns the 32 bytes 0x00 to 0x1F gives a base32 string starting `AAAQEAYE`, checked against an independent routine in the test. If the owner later declines OI-10, drop `regime.id()` from the message and delete `TrnDerivationTest#differentRegimesGiveDifferentTrns` and `theMessageIsTheRegimeIdentityAndLegSeparatedByNewlines`.
- **T7.** `ReportLedger` port, in-memory adapter and contract (1 fix round). Reviewer minors left: the crash-recovery case of completing an outbox item on a redelivery of a unit committed earlier without `completesOutbox` is not pinned; the flaws for the new tests belong to T9.
- **T8.** `ExitFixturesContract` and `FencingScenarioContract` on the in-memory adapter (`FencedSender` with accessors `lastAttempt()` and `token()`); the concurrent tests use real threads with 20 s timeouts and are linearizable only because the in-memory adapter has one lock; on PostgreSQL the first race round assumes exactly one grant (CP-2). The takeover sweep (the zombie's SENDING delivery becomes IN_DOUBT when the new holder acquires) and the restore scenario (the new holder gets (1, 1)) follow the `LeaseLedger.acquire` Javadoc and must be confirmed by the real-store run at CP-2.
- **T9.** Eight flaws and their mutant classes. The plan's T9 table is wrong for `IGNORE_LEASE_TOKEN`: the SENDING-to-IN_DOUBT move at takeover masks the token check at the marker, so `takeoverDuringAnInProgressUploadAbortsTheOldSenderAtTheGate` and `stallAfterTokenCheckResumesAfterTakeoverAndTheMarkerCasFails` are killed by `NO_TAKEOVER_IN_DOUBT`; three other scenarios fail under `IGNORE_LEASE_TOKEN`. A compound flaw is a P3d or T17 decision. The new `Flaw` constants live in the main sources of `InMemoryLedgerStore`, like the P0 flaws (the controller note said test sources; the pattern of the existing flaws was followed). A module test of `regpack-inmemory` needs `-am` (the local repository holds a stale testkit jar).
