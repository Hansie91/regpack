# P3a ledger notes: the ledger as built, the gates, the limits and the hand-offs

Written in task T22 of the [P3a plan](../superpowers/plans/2026-10-17-p3a-ledger-core.md) on branch `p3a/ledger-core`. The approvals, owner inputs, pins and checkpoint records are in [decisions.md](decisions.md); the reviewed schema is [schema-design.md](schema-design.md) (SHA-256 recorded in decisions.md). This file describes what exists after T21 and what the later slices (P3b to P3d) and later plans must pick up. It changes no code and no specification text.

## 1. The ledger as built

### 1.1 Modules and dependencies

- `regpack-postgres` (package `dev.regpack.postgres`) is the only module with third-party runtime dependencies: `org.postgresql:postgresql:42.7.14` and `org.checkerframework:checker-qual:3.55.1` (AP-5). No connection pool and no migration tool (A4): the adapter takes a `javax.sql.DataSource`.
- `regpack-app` does not depend on `regpack-postgres` (A9, OI-12), so the distribution stays JDK-only. `ThirdPartyBoundaryTest` and `DistributionLayoutIT` guard this.
- Real-store tests are `*IT` classes that skip when `REGPACK_PG_URL` is unset and fail instead of skipping when `REGPACK_PG_REQUIRED=true` (CI). Tagged `mutant` and `capacity` classes are excluded from `verify` by the module property `excludedGroups`.

### 1.2 Ports and model (additive only)

- `DecisionContext.circuitEpoch` (tenth component, written to the canonical form only when positive); `EventIdentity` and `BatchId` stricter; `DecisionCodec.bodyFromMap` and `contextFromMap`.
- `regpack-ports`, package `dev.regpack.ports.ledger`: `LedgerStore` extends `IngestLedger`, `OutboxLedger`, `DecisionLedger`, `LeaseLedger`, `DeliveryLedger`, `ContextLedger` and `ReportLedger`. `NewDecision` has an optional context; `OutboxKind.SEQUENCE`; `OutboxLedger.PAGE_LIMIT` (1,000); `TrnConflictException` and `WithdrawalRequiredException` extend `LedgerException`.
- Both implementers (`InMemoryLedgerStore`, `PgLedgerStore`) are bound to the same contract suites in `regpack-testkit`; the in-memory binding runs in every build, the PostgreSQL binding (`PgLedgerStoreContractIT`) in the CI job `ledger-pg`.

### 1.3 Tables (V001, 18 tables)

`schema_migration`, `regime`, `source`, `endpoint`, `decision_context`, `decision` (range-partitioned by day), `decision_head`, `ingest`, `ingest_batch`, `ingest_checkpoint`, `outbox`, `report_subject`, `trn_assignment`, `report_version`, `delivery`, `attempt`, `lease`, `restore_epoch`. Sequences `decision_id_seq`, `delivery_id_seq`, `attempt_id_seq` use `CACHE 1`. `V001__core.sql` and `schema-design.md` are pinned by SHA-256 in decisions.md; `SchemaDocMatchesMigrationsTest` compares the document with the script, and an applied script is never edited (a change is a new script).

Constraints the adapter relies on (every constraint and index has an explicit name, Convention 6; the adapter maps a violation by SQLState and constraint name, never by message text):

| Constraint or index | Meaning |
|---|---|
| `decision_pk (decision_id, as_at)`, `decision_idem_key (regime_id, event_identity, context_id, input_digest, as_at)` | decision identity and the idempotency key inside each daily piece (an `ON CONFLICT` arbiter) |
| `decision_head_pk (regime_id, event_identity)` | the chain head; the compare-and-set is the only cross-piece linearity (D-S4, no trigger) |
| `ingest_pk (source_id, source_record_id, source_version)`, `ingest_event_version_key (event_identity, source_id, source_version)` | the ingest keys of spec 10 |
| `report_subject_key (regime_id, event_identity, leg)`, `trn_assignment_lei_trn_key (executing_lei, trn)` | one subject per leg; one subject per (executing LEI, TRN) |
| `report_version_pk (subject_id, version_seq)`, `report_version_decision_subject_key`, `report_version_decision_fk` | gapless versions per subject; the foreign key to the partitioned `decision` |
| `delivery_in_flight (endpoint_id, business_key) WHERE state IN (4, 5, 11) AND NOT gate_released` | at most one in-flight delivery per business key (partial unique index) |
| `delivery_key`, `delivery_version_fk`, `delivery_sending_idx` | delivery identity, link to the version, the takeover sweep index |
| `lease_pk (entity_id, endpoint_id)`, singleton `restore_epoch` | the fenced lease token pair |
| `outbox_kind_subject_key (kind, subject_id)`, `outbox_pending_idx` | one outbox item per kind and subject |
| triggers `decision_immutable`, `report_version_immutable`, `trn_assignment_immutable` | insert-only tables (D-S16, SQLState 55000) |

The six constraint mutants of `PgSchemaTeethIT` (`decision_idem_key`, `decision_head_pk`, `ingest_pk`, `report_subject_key`, `trn_assignment_lei_trn_key`, `delivery_in_flight`) show that the named contract tests fail when the constraint is missing, so the adapter does not pre-check in Java what the schema enforces (I2, LF1).

### 1.4 The decision body, the context dictionary and the stored forms

- `DecisionBodyCodec` v1 (A13, "E2"): a version byte, then deflate (level 6, a fixed preset dictionary) of the canonical JSON of the body. The compressed bytes are not a contract; the readers dispatch on the version byte and inflate. The dictionary is frozen with `VERSION` 1 (a change is version 2; stored rows are never rewritten). The decoder is strict and refuses a decoded body above 16 MiB.
- Fixed columns of `decision`: `outcome`, `circuit_open` (true exactly when the body flags contain `circuit=open`), `entity_id`, `body_encoding` (1), `body`. `decision_head` carries `outcome`.
- `decision_context` is a dictionary keyed by the SHA of the context id; a row with `body IS NULL` is a stub and `ContextLedger.decisionContext` returns empty for it; the stub fill is the only UPDATE on that table.
- `ingest_batch.result` stores the disposition and the key of every entry in input order (`IngestResultCodec` v1; about 25 B an entry, about 1 GB a day at 10,000 batches a day). The owner chose this form over the slimmer form of the schema document; a version byte allows a slimmer form later.
- `report_version.body` is plain canonical JSON (`body_encoding` 0); 1 is reserved for the encrypted form of P3c.
- State and kind codes are pinned by `Codes` and `CodesTest` (Outcome, Leg, DeliveryState 1 to 16 in declaration order, OutboxKind, OutboxState, VersionOrigin).

### 1.5 Partitions

`decision` is partitioned by day on `as_at`, a `date` holding the UTC date of the decision's asAt instant (`Partitions.dateOf`, range 2000-01-01 to 2200-01-01). A partition is created on demand as a table like `decision` plus `ATTACH PARTITION` on a separate connection in one transaction, under `pg_advisory_xact_lock` (purpose 2); a failed creation fails the unit closed. `decision_id` is unique across pieces only by its sequence, so every reference carries `as_at`. `report_version_decision_fk` references the partitioned parent, so retention of an old piece is `DETACH PARTITION` plus `DROP ... CASCADE` (hand-off P4 and deployment below).

### 1.6 The migrator

`migrate.Migrator` is plain-SQL and forward-only: scripts listed in `db/migration/index.txt`, SHA-256 per script in `schema_migration`, transaction-level advisory lock (purpose 1, keyed by the schema), every script in one transaction. It refuses drift (a changed applied script), a gap and a database newer than the code. `MigrationException` carries the script name and version, never SQL text.

### 1.7 Units of work and errors

Every write unit starts through `Unit.begin(Connection, Config)`: autocommit off, `READ COMMITTED`, `SET LOCAL TimeZone = UTC`, lock and statement timeouts, `synchronous_commit = on` (only the capacity variant C turns it off). Rows are locked in a canonical order (heads by regime and event identity, subjects by regime, event identity and leg, deliveries by endpoint and business key); results come back in input order. `PgErrors` maps `40P01` and `40001` (on a head update to `DecisionHeadConflictException`, otherwise `LedgerException`) and connection-class codes (always a `LedgerException`, never `false`, `STATE_MISMATCH` or an empty `Optional`). No SQL text, bind value, server detail or report body reaches an exception message, log line or annotation.

### 1.8 Integrity queries

`LedgerIntegrity` (T19, read-only, `REPEATABLE READ`, a day range, at most 1,000 violations per kind): `DUPLICATE_DECISION_SEQ`, head mismatch, decision without a context (a stub), version gap. It is the base of P4 `verify`. Blind spots: a twin outside the scanned range, and a head naming a nonexistent decision for a chain with no decision in the range (the full scan is a P4 batch job). The cross-source clash of `ingest` (two sources landing the same new event identity in the same instant) is accepted by the owner and has no check.

## 2. The nine rules of `commitReportable`

Normative text in the plan section "Port and model changes"; the contract tests of T7 (`ReportLedgerContract`) are the oracle on both adapters.

1. All or nothing, including the decisions, which follow `commitDecisions` exactly (idempotency key, head compare-and-set, supersedes link, `completesOutbox`).
2. A `NewVersion` is accepted only for a decision with outcome REPORT, for a leg in that decision's `legs`, at most once per (decision, leg); the subject's regime and event identity must equal the body's; otherwise `IllegalArgumentException` before any write.
3. For a `NOOP_REDELIVERY` decision a requested version that exists returns `EXISTING` and writes nothing; one that does not exist is created only if the decision is still the head (else `DecisionHeadConflictException`).
4. For an `INSERTED` decision, per `NewVersion`: get-or-create the subject; apply rule 5; apply rule 7; create the version with `versionSeq` = last + 1; create the delivery in PREPARED.
5. TRN: a subject without a TRN needs a proposal and the pair (executing LEI, TRN) must be unused (else `TrnConflictException`, nothing written); a subject with a TRN keeps the stored one (a present proposal must equal it, else `IllegalArgumentException`); the first key id is kept; the TRN, key id and assignment instant never change.
6. `versionSeq` is gapless per subject, assigned under the subject's row lock.
7. Suppression: if the latest version whose delivery is not in the dead states {REJECTED, IN_REPAIR, WITHDRAWN, WRITTEN_OFF} has the same `action` and `logicalHash`, the result is `SUPPRESSED_UNCHANGED` and nothing is written in P3a (the journal entry is P3d).
8. Withdrawal guard: an `INSERTED` decision whose superseded decision has subjects with versions, and whose outcome is not REPORT or whose legs omit a leg that has versions, throws `WithdrawalRequiredException` and writes nothing; `commitDecisions` applies the same rule (D-L4).
9. If `sequenceBatch` is present and at least one version is `CREATED`, one outbox item of kind `SEQUENCE` with the batch id as subject exists afterwards (an existing one is left).

## 3. The gate commands

Local, from the repository root (Git Bash; PowerShell forms as in the P0 Conventions):

- `./mvnw -B -ntp verify` (all modules; the `*IT` classes of `regpack-postgres` skip without `REGPACK_PG_URL`).
- `bash scripts/check-gates.sh` ends `check-gates: all gates passed`. Its traceability call uses `--realstore skip`, so the REALSTORE evidence lines are counted as deferred locally.
- Unit tests of one module: `bash scripts/t.sh regpack-postgres '*Test'`; integration tests: `bash scripts/it.sh <module> '<selector>'`.

CI (job `ledger-pg`, a service container of the pinned image, `REGPACK_PG_REQUIRED=true`):

- `./mvnw verify -pl regpack-postgres` runs every real-store suite on PostgreSQL 18.6.
- `java -cp regpack-tools/target/classes dev.regpack.tools.Gates traceability --file docs/traceability.md --root . --scan regpack-postgres/target --realstore only` counts only REALSTORE lines whose test passed (a skipped test never counts).
- `Gates annotate --scan regpack-postgres/target` re-emits the test tallies and failures as workflow annotations, the failure channel that needs no sign-in (D-L6). Since T22 the `tally-total` notice is emitted first, then one `tally` notice per class, because GitHub keeps only about ten notices per level and step and the job has more than ten test classes (at CP-3 the total was cut). Failures are emitted as `error` annotations (at most ten, the first failed case of each class first).
- Job `ledger-capacity` (manual dispatch only, inputs `capacity` and `confirm_downloads`; AP-8) runs the probe `LedgerBurstProbeIT`; its figures are information, not a verdict.

## 4. Limits

- **`report_version.body` is plain canonical JSON until P3c removes the plain form.** The PostgreSQL adapter must not be wired to real data before that: no personal data may enter it (departures table, "natural-person columns encrypted"). A9 and OI-12 keep it out of `regpack-app` in P3a.
- **The circuit epoch is process-local (D-L1).** `RuleCircuit.epoch()` lives in the `RuleCircuit` object, so after a restart it is 0 again: a close before the restart is invisible to the context, and the re-evaluation of an `EXEC_CIRCUIT_OPEN` decision is a `NOOP_REDELIVERY` again. A pack rollback (P9b) has the same hole. P2a open item 1 therefore stays open until P3d adds the durable per-pack-digest epoch (`pack_circuit (pack_digest PK, epoch)`, read when the `RuleCircuit` is created, incremented in the transaction that records the close).
- A batch pinned at an older epoch is refused as stale by `PackRegime` (`evaluate` and `evaluateTraced` both refuse a stale epoch) and must be pinned again; a close racing an evaluation in flight may stamp the old epoch.
- **Withdrawal fails closed** (D-L4): `WithdrawalRequiredException` until P3d builds the WITHDRAWN transaction.
- **Chain linearity across the daily pieces** rests on the head compare-and-set only (D-S4); the integrity query finds a violation after the fact, in a bounded range.
- **Lock order is not total in one case.** A unit whose wave mixes a redelivered chain and a new chain can deadlock with another such unit in the opposite arrangement; the result is a `DecisionHeadConflictException` (`40P01` mapped), never a wrong write. A fix needs a canonical lock pass in `PgDecisions.writeWave`.
- **`decision_id_seq` gaps.** A redelivery consumes one sequence value per skipped row; only gaps, no duplicates. P4 resume above the maximum must know.
- **Retention.** `report_version_decision_fk` makes a plain `DROP TABLE` of an old daily piece fail; an operator must `DETACH PARTITION` and `DROP ... CASCADE`, and DETACH itself fails while any `report_version` row references a decision of that piece. This needs a retention design before the first production quarter (the owner chose no purge in release 1).
- **Size.** The per-entry `ingest_batch.result` form is about 1 GB a day at 10,000 batches a day (owner's choice). The decision body is about 800 B a row, outside the spec 5.6 band of 300 to 500 B, acknowledged by the owner (OI-7).
- **Real-store evidence is CI-only.** No PostgreSQL ran on the development machine (OI-13); the first run of every real-store suite is a checkpoint run. Not run at all in P3a: a quorum synchronous standby and the durability gate (P3b), a real-store run on Windows, the hot path at 50 million records a day.
- L-4 is open: the capacity probe gives sizing input (decided after T21, OI-3).

## 5. The `closes_in` position (OI-14)

`closes_in` stays as the specification has it (P3) for every row, and T22 edits none; `exited_plans` is the owner's and is not edited (P1a is not signed). The proposal, to be presented to the owner at T23 as a specification edit (every line of a row carries the same value, otherwise `CLOSES_IN_MISMATCH`):

| Row | Today | Proposed |
|---|---|---|
| I2, I9, R12 | P3 | P3a |
| I1 | P3 | P3b, only when the durability-gate run of P3b exists |
| I10 | P3,P4 | P3b,P4 |
| I6 | P3,P4,P8 | P3d,P4,P8 |
| R4 | P3,P4 | P3b,P3c,P4 |

The P3a REALSTORE lines of T20 already sit under the present value P3; the proposal changes the value on the lines of a row together with the row.

## 6. Hand-offs

### P3b

- `AttemptIntent` trailing components, `journal_seq`, `object_manifest`, M28, M33.
- The durability gate as the ledger half of I1 (`synchronous_commit`, `pg_stat_replication`, "synchronous" defined as `on` or `remote_apply` only, `LedgerDurability` and its test, `PgClock` with its first consumer) on a real PostgreSQL with a quorum synchronous standby, in a CI job that starts primary and standby from the pinned image as `spikes/scripts/pg-up.sh` and `pg-basebackup.sh` do (needs a new approval for the docker steps).

### P3c

- M82; `body_encoding` 1 (P3c removes the plain report body); timer rows; the manifest cadence.

### P3d

- The WITHDRAWN transaction replacing `WithdrawalRequiredException`.
- The dead-state set {REJECTED, IN_REPAIR, WITHDRAWN, WRITTEN_OFF} (the pin `NOT IN (9, 13, 15, 16)`) and `FILE_REJECTED` confirmed against the 9.3 edge list; the `gate_released` path of the in-flight index.
- Parked orphans (`IngestLedger.Entry` needs a trailing lifecycle kind); the outbox claim.
- **The durable per-pack-digest circuit epoch (`pack_circuit`)**, a blocker for closing P2a open item 1.
- The data-quality item and queue for `CONFLICT_SAME_VERSION_DIFFERENT_HASH` and `SOURCE_CONFLICT`.
- The journal entry of a suppression: the completeness job must be able to tell a suppressed REPORT decision (journalled) from a lost one, so `commitReportable` gains the journal write, no port change.
- The `endpoint_epoch` registry; BRK-04 state supply and the state fold; the `unsigned` stamp is P9b.
- The canonical lock pass in `PgDecisions.writeWave` that makes the lock order total for a unit whose wave mixes a redelivered chain and a new chain (section 4).
- The definitions of HEAD_MISMATCH and VERSION_GAP in `LedgerIntegrity` are the implementer's reading of the plan; confirm them.

### P4

- `LedgerIntegrity` as the base of `verify`; excluded-and-counted `EXEC_CIRCUIT_OPEN`.
- `attempt_id` and the other sequences resume above the maximum found in the evidence tier (`setval`; P3a creates them with `CACHE 1`); `decision_id_seq` has gaps after a redelivery, so resume above the maximum of the stored ids, never assume density.
- The full integrity scan as a batch job (the range-bounded blind spots of section 1.8).
- The retention design of the daily pieces (`report_version_decision_fk`, section 4), before the first production quarter.

### P5

- `HOLD` release adds `commitVersions` additively.

### P6b

- The `business_key` definition (today `executingLei|trn`, A12) and the pack-declared TRN policy from `lifecycle.yaml`; the leg code of a new `Leg` constant (a bit position below 63 in the withdrawal guard).

### P7a

- The `PAGE_LIMIT` sweep loop, one pump per source, the identifier-index writers at version time, the M115/M116 poison policy, the UNDETERMINED decision with a reason code for the conflict dispositions.
- **The batch driver that re-pins on an epoch change.** `MicroBatchPipeline` keeps one `DecisionContext` and is refused after `RuleCircuit.close` until it is re-pinned. Acceptance test: after a close the re-pinned redelivery of a batch supersedes only the records whose stored decision was `EXEC_CIRCUIT_OPEN` and is a no-op for every other record; look up the stored decision by event identity and input digest first.

### P8

- M63, M68.

### P9b

- M130, M166, the `unsigned` stamp, activation; the pack-rollback epoch hole of D-L1.

### P12

- First user of the BRK-04 state component.

### Deployment (the first runtime consumer: P7a or the AT-1 harness)

- Roles, least privilege and the partition-creation privilege are deployment design; the insert-only triggers of D-S16 do not protect against an owner or a superuser (tamper evidence is the evidence tier, R4).
- Per-entry `ingest_batch.result` growth and the partition-drop procedure (section 4).

## 7. P2a items consumed

- `attributes` and `engine_build` are stored in the decision body and the context dictionary.
- The closed leg type: the codes are pinned by `CodesTest`.
- INTERP-3 (T3): the mechanism and the ledger contract exist; the open item stays open until the epoch is durable (P3d).
