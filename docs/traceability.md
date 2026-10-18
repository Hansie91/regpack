# Traceability (spec 14.4)

Maps requirements R1-R14 and invariants I1-I10 to named evidence. Checked by the gate
`Gates traceability` (`regpack-tools`), which CI runs after the build.

- A row is *due* when every plan in `closes_in` is listed in `exited_plans`; otherwise it is open and may have no evidence.
- A due row needs at least one counting evidence: the artefact exists, records a pass, is not `NONE`, and is not interim.
- Interim means: the row is I1, I2, I9 or I10 and the evidence `store` is not `REALSTORE`. Consistency-only golden cases never count.
- `REALSTORE` lines name tests that run only in the CI job `ledger-pg`, against a real PostgreSQL server. That job evaluates only these lines (`Gates traceability --realstore only`); the build jobs run `--realstore skip`, where they are not looked up and never count.
- `TEST` refs are `fully.qualified.Class#method` (the concrete test class of the adapter's module); renaming a cited test without updating this file fails the gate.
- The `closes_in` column is a proposal for the owner (invariants follow the spec 14.4 list, requirements follow the section 3 and 16 mapping).

exited_plans: P0

| row | closes_in | kind | ref | store | ci | note |
|---|---|---|---|---|---|---|
| R1 | P1a,P1b | NONE | - | NA | NA | no evidence yet |
| R1 | P1a,P1b | SPIKE | docs/spikes/SP-R.md | NA | NA | measured on a GitHub-hosted runner, see the report |
| R1 | P1a,P1b | SPIKE | docs/spikes/SP-X.md | NA | NA | measured on a GitHub-hosted runner, see the report |
| R2 | P9b | NONE | - | NA | NA | no evidence yet |
| R3 | P7a | NONE | - | NA | NA | no evidence yet |
| R4 | P3,P4 | NONE | - | NA | NA | no evidence yet |
| R5 | P8 | NONE | - | NA | NA | no evidence yet |
| R6 | P4,P8 | NONE | - | NA | NA | no evidence yet |
| R7 | P2a,P12 | TEST | dev.regpack.engine.pack.SecondRegimeFixtureTest#theEmirActionTypeTableRunsOnTheSameEngine | INMEMORY | BUILD | partial evidence: CompiledPack and PackProgram only, not the PackRegime provider |
| R8 | P9b | TEST | dev.regpack.pack.csv.TableCsvRoundTripPropertyTest#randomTablesSurviveExportAndImportUnchanged | INMEMORY | BUILD | partial evidence toward AT-8: unit-level round trip on random tables |
| R9 | P6a | NONE | - | NA | NA | no evidence yet |
| R10 | P5 | NONE | - | NA | NA | no evidence yet |
| R11 | P5 | NONE | - | NA | NA | no evidence yet |
| R12 | P3 | TEST | dev.regpack.inmemory.InMemoryLedgerStoreContractTest#abaProducesThreeVersions | INMEMORY | BUILD | in-memory contract test |
| R12 | P3 | TEST | dev.regpack.postgres.PgLedgerStoreContractIT#abaProducesThreeVersions | REALSTORE | BUILD | real PostgreSQL 18.6, job ledger-pg |
| R12 | P3 | TEST | dev.regpack.postgres.PgLedgerStoreContractIT#abaEndToEndThirdDecisionIsANewRowWhoseVersionReflectsA | REALSTORE | BUILD | real PostgreSQL 18.6, job ledger-pg |
| R13 | P4 | NONE | - | NA | NA | no evidence yet |
| R14 | P0 | REVIEW | docs/reviews/R14-process-checklist.md | NA | NA | owner signed 2026-10-09 |
| I1 | P3 | TEST | dev.regpack.inmemory.InMemoryLedgerStoreContractTest#beginSendingRequiresCurrentLeaseAndQueuedState | INMEMORY | BUILD | interim: does not close the row |
| I1 | P3 | TEST | dev.regpack.engine.pipeline.ToyCrashIT#acknowledgementFollowsIngestCommit | INMEMORY | BUILD | interim: does not close the row |
| I2 | P3 | TEST | dev.regpack.inmemory.InMemoryLedgerStoreContractTest#redeliveredBatchIsNoopAndReturnsOriginalResult | INMEMORY | BUILD | interim: does not close the row |
| I2 | P3 | TEST | dev.regpack.inmemory.InMemoryLedgerStoreContractTest#inFlightGuardAllowsOnlyOneDeliveryPerBusinessKey | INMEMORY | BUILD | interim: does not close the row |
| I2 | P3 | TEST | dev.regpack.inmemory.InMemoryLedgerStoreContractTest#staleExpectedHeadSeqIsRejectedAndRollsBackWholeBatch | INMEMORY | BUILD | interim: does not close the row |
| I2 | P3 | TEST | dev.regpack.postgres.PgLedgerStoreContractIT#redeliveredBatchIsNoopAndReturnsOriginalResult | REALSTORE | BUILD | real PostgreSQL 18.6, job ledger-pg |
| I2 | P3 | TEST | dev.regpack.postgres.PgLedgerStoreContractIT#inFlightGuardAllowsOnlyOneDeliveryPerBusinessKey | REALSTORE | BUILD | real PostgreSQL 18.6, job ledger-pg |
| I2 | P3 | TEST | dev.regpack.postgres.PgLedgerStoreContractIT#staleExpectedHeadSeqIsRejectedAndRollsBackWholeBatch | REALSTORE | BUILD | real PostgreSQL 18.6, job ledger-pg |
| I2 | P3 | TEST | dev.regpack.postgres.PgLedgerStoreContractIT#aSecondSubjectWithTheSameTrnAndLeiIsRejectedAtomically | REALSTORE | BUILD | real PostgreSQL 18.6, job ledger-pg |
| I2 | P3 | TEST | dev.regpack.postgres.PgDecisionsIT#crossPartitionDuplicateDecisionSeqIsRejectedByTheHead | REALSTORE | BUILD | real PostgreSQL 18.6, job ledger-pg |
| I2 | P3 | TEST | dev.regpack.postgres.PgConstraintsIT#secondInFlightDeliveryForOneBusinessKeyIsRejected | REALSTORE | BUILD | real PostgreSQL 18.6, job ledger-pg |
| I3 | P5,P8 | TEST | dev.regpack.engine.pipeline.ToyCrashIT#sameFaultPlanGivesIdenticalTraceAndDigest | INMEMORY | BUILD | in-memory evidence |
| I3 | P5,P8 | TEST | dev.regpack.model.NoWallClockRandomLocaleTest#modelMainClassesAreClean | INMEMORY | BUILD | in-memory evidence |
| I3 | P5,P8 | TEST | dev.regpack.engine.NoWallClockRandomLocaleTest#engineMainClassesAreClean | INMEMORY | BUILD | in-memory evidence |
| I3 | P5,P8 | TEST | dev.regpack.pack.ir.PackDigestTest#commentsKeyOrderRowOrderAndLineEndingsDoNotChangeTheDigest | INMEMORY | BUILD | in-memory evidence |
| I3 | P5,P8 | TEST | dev.regpack.engine.pipeline.PackToyCrashIT#sameFaultPlanGivesIdenticalTraceAndDigest | INMEMORY | BUILD | in-memory evidence |
| I3 | P5,P8 | TEST | dev.regpack.model.EngineBuildTest#differentMajorIsNotReplayCompatible | INMEMORY | BUILD | in-memory evidence |
| I4 | P9b,P12 | TEST | dev.regpack.engine.pack.NoRegimeLogicInEngineTest#engineAndPackSourcesCarryNoRegimeLiterals | INMEMORY | BUILD | in-memory evidence |
| I4 | P9b,P12 | TEST | dev.regpack.engine.pack.SecondRegimeFixtureTest#theEmirActionTypeTableRunsOnTheSameEngine | INMEMORY | BUILD | in-memory evidence |
| I5 | P2a,P6a | TEST | dev.regpack.engine.skeleton.ScopeEngineTest#naturalPersonIsUndeterminedNeverDoNotReport | INMEMORY | BUILD | in-memory evidence |
| I5 | P2a,P6a | TEST | dev.regpack.engine.skeleton.ScopeEngineTest#unmatchedInstrumentFallsToCatchAllUndetermined | INMEMORY | BUILD | in-memory evidence |
| I5 | P2a,P6a | TEST | dev.regpack.engine.pack.PackScopeEngineTest#everyFailureModeIsUndeterminedWithReasonAndCitation | INMEMORY | BUILD | in-memory evidence |
| I5 | P2a,P6a | TEST | dev.regpack.engine.pack.PackScopeEngineTest#naturalPersonIsUndeterminedNeverDoNotReport | INMEMORY | BUILD | in-memory evidence |
| I5 | P2a,P6a | TEST | dev.regpack.pack.check.TableChecksTest#catchAllNotUndetermined | INMEMORY | BUILD | in-memory evidence |
| I6 | P3,P4,P8 | TEST | dev.regpack.engine.pipeline.ToyCrashIT#redeliveryAfterAckLossIsNoop | INMEMORY | BUILD | in-memory evidence |
| I6 | P3,P4,P8 | TEST | dev.regpack.postgres.PgLedgerStoreContractIT#inFlightGuardAlsoCoversAwaitingAckAndInDoubt | REALSTORE | BUILD | real PostgreSQL 18.6, job ledger-pg; partial evidence: the in-flight states of the ledger half only |
| I7 | P4 | NONE | - | NA | NA | no evidence yet |
| I8 | P8 | NONE | - | NA | NA | no evidence yet |
| I9 | P3 | TEST | dev.regpack.inmemory.InMemoryLedgerStoreContractTest#takeoverMovesSendingDeliveriesToInDoubtAndFencesTheOldHolder | INMEMORY | BUILD | interim: does not close the row |
| I9 | P3 | TEST | dev.regpack.inmemory.InMemoryLedgerStoreContractTest#restoreEpochRaisesTokenPairAndNoOldTokenEqualsNewOne | INMEMORY | BUILD | interim: does not close the row |
| I9 | P3 | TEST | dev.regpack.inmemory.InMemoryLedgerStoreContractTest#restoreThenZombieStaleTokenIsFencedEvenBeforeExpiry | INMEMORY | BUILD | interim: does not close the row |
| I9 | P3 | TEST | dev.regpack.inmemory.InMemoryLedgerStoreContractTest#concurrentAcquireGrantsExactlyOneHolder | INMEMORY | BUILD | interim: does not close the row |
| I9 | P3 | TEST | dev.regpack.postgres.PgLedgerStoreContractIT#takeoverDuringAnInProgressUploadAbortsTheOldSenderAtTheGate | REALSTORE | BUILD | real PostgreSQL 18.6, job ledger-pg |
| I9 | P3 | TEST | dev.regpack.postgres.PgLedgerStoreContractIT#stallAfterTokenCheckResumesAfterTakeoverAndTheMarkerCasFails | REALSTORE | BUILD | real PostgreSQL 18.6, job ledger-pg |
| I9 | P3 | TEST | dev.regpack.postgres.PgLedgerStoreContractIT#restoreThenZombieSenderIsFencedAtTheGate | REALSTORE | BUILD | real PostgreSQL 18.6, job ledger-pg |
| I9 | P3 | TEST | dev.regpack.postgres.PgLedgerStoreContractIT#concurrentAcquireGrantsExactlyOneHolder | REALSTORE | BUILD | real PostgreSQL 18.6, job ledger-pg |
| I10 | P3,P4 | TEST | dev.regpack.inmemory.InMemoryEvidenceStoreContractTest#putSameKeyDifferentBytesIsIntegrityAlarmAndKeepsOriginal | INMEMORY | BUILD | interim: does not close the row |
