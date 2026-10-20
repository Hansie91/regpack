package dev.regpack.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import dev.regpack.inmemory.mutants.AbaAsNoopLedgerContractTest;
import dev.regpack.inmemory.mutants.AnyRetainUntilEvidenceContractTest;
import dev.regpack.inmemory.mutants.AsAtNotCheckedLedgerContractTest;
import dev.regpack.inmemory.mutants.CaseFoldedKeysEvidenceContractTest;
import dev.regpack.inmemory.mutants.ExactKeyNotFirstLedgerContractTest;
import dev.regpack.inmemory.mutants.HighestVersionIsMinLedgerContractTest;
import dev.regpack.inmemory.mutants.IdempotencyIgnoresContextLedgerContractTest;
import dev.regpack.inmemory.mutants.IdempotencyIgnoresDayLedgerContractTest;
import dev.regpack.inmemory.mutants.IgnoreLeaseTokenLedgerContractTest;
import dev.regpack.inmemory.mutants.IgnoreRestoreEpochLedgerContractTest;
import dev.regpack.inmemory.mutants.LeaseTtlUnboundedLedgerContractTest;
import dev.regpack.inmemory.mutants.ListIgnoresAfterEvidenceContractTest;
import dev.regpack.inmemory.mutants.NoInFlightGuardLedgerContractTest;
import dev.regpack.inmemory.mutants.NoTakeoverInDoubtLedgerContractTest;
import dev.regpack.inmemory.mutants.NoWithdrawalGuardLedgerContractTest;
import dev.regpack.inmemory.mutants.NonAtomicDecisionsLedgerContractTest;
import dev.regpack.inmemory.mutants.NonAtomicReportableLedgerContractTest;
import dev.regpack.inmemory.mutants.OutboxKindIgnoredLedgerContractTest;
import dev.regpack.inmemory.mutants.OverwriteOnDuplicateEvidenceContractTest;
import dev.regpack.inmemory.mutants.OverwriteOnDuplicateJournalContractTest;
import dev.regpack.inmemory.mutants.PageUnboundedLedgerContractTest;
import dev.regpack.inmemory.mutants.RedeliveryReingestsLedgerContractTest;
import dev.regpack.inmemory.mutants.ShortenRetentionEvidenceContractTest;
import dev.regpack.inmemory.mutants.SilentMissingKeyEvidenceContractTest;
import dev.regpack.inmemory.mutants.SubjectNotRegimeScopedLedgerContractTest;
import dev.regpack.inmemory.mutants.SuppressAgainstAnyVersionLedgerContractTest;
import dev.regpack.inmemory.mutants.TakeoverTouchesOtherEndpointsLedgerContractTest;
import dev.regpack.inmemory.mutants.TrnNotUniquePerLeiLedgerContractTest;
import dev.regpack.inmemory.mutants.TrnReassignedPerVersionLedgerContractTest;
import dev.regpack.inmemory.mutants.VersionOnAnyOutcomeLedgerContractTest;
import dev.regpack.inmemory.mutants.WithdrawalGuardFirstLegOnlyLedgerContractTest;
import dev.regpack.inmemory.mutants.WithdrawalGuardIgnoresOutcomeLedgerContractTest;
import dev.regpack.inmemory.mutants.WithdrawalGuardOnRedeliveryLedgerContractTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/**
 * Proves that the contract suites have teeth: each deliberately flawed adapter must make the named
 * contract tests fail (review focus RF1).
 */
class ContractSuiteTeethTest {
  @Test
  void everyFlawIsCaughtByItsNamedContractTests() {
    Map<Class<?>, Set<String>> expected = new LinkedHashMap<>();
    expected.put(
        OverwriteOnDuplicateEvidenceContractTest.class,
        Set.of("putSameKeyDifferentBytesIsIntegrityAlarmAndKeepsOriginal"));
    expected.put(ShortenRetentionEvidenceContractTest.class, Set.of("retentionCanOnlyBeExtended"));
    // the hardening of the EvidenceStore suite (P3b1, M28 and M33)
    expected.put(
        CaseFoldedKeysEvidenceContractTest.class, Set.of("keysDifferingOnlyInCaseAreDistinct"));
    expected.put(ListIgnoresAfterEvidenceContractTest.class, Set.of("listPagesWalkAllKeysOnce"));
    expected.put(
        SilentMissingKeyEvidenceContractTest.class,
        Set.of("extendRetentionOfAMissingKeyIsRejected", "setLegalHoldOfAMissingKeyIsRejected"));
    // SPEC-7: the retain-until range of the port (microsecond precision, years 0000 to 9999)
    expected.put(
        AnyRetainUntilEvidenceContractTest.class,
        Set.of(
            "putRefusesARetainUntilTheStoresCannotHoldAndStoresNothing",
            "extendRetentionRefusesARetainUntilTheStoresCannotHoldAndChangesNothing"));
    // the journal-format contract (P3b1): a store that overwrites on a duplicate key
    expected.put(
        OverwriteOnDuplicateJournalContractTest.class,
        Set.of("rewritingWithAnotherPayloadIsAnIntegrityAlarmAndKeepsTheFirst"));
    expected.put(
        IgnoreLeaseTokenLedgerContractTest.class,
        Set.of(
            "beginSendingRequiresCurrentLeaseAndQueuedState",
            "markCommitIssuedIsFencedCompareAndSet",
            "takeoverMovesSendingDeliveriesToInDoubtAndFencesTheOldHolder",
            // The two scenarios the plan names here (takeover during the upload, stall after the
            // token check) are not caught by this flaw: the takeover moves the SENDING delivery to
            // IN_DOUBT, so the marker fails on the state alone. They are caught by
            // NO_TAKEOVER_IN_DOUBT below. These three scenarios do catch a missing token fence.
            "aSenderThatLostItsLeaseBeforeBeginSendingSendsNothing",
            "twoConcurrentSendersOnlyOneReachesTheDestination",
            "restoreThenZombieSenderIsFencedAtTheGate"));
    expected.put(
        NoInFlightGuardLedgerContractTest.class,
        Set.of(
            "inFlightGuardAllowsOnlyOneDeliveryPerBusinessKey",
            "concurrentBeginSendingForSameBusinessKeyAppliesExactlyOnce"));
    expected.put(
        NonAtomicDecisionsLedgerContractTest.class,
        Set.of("staleExpectedHeadSeqIsRejectedAndRollsBackWholeBatch"));
    expected.put(
        RedeliveryReingestsLedgerContractTest.class,
        Set.of("redeliveredBatchIsNoopAndReturnsOriginalResult"));
    expected.put(
        NoTakeoverInDoubtLedgerContractTest.class,
        Set.of(
            "takeoverMovesSendingDeliveriesToInDoubtAndFencesTheOldHolder",
            "takeoverDuringAnInProgressUploadAbortsTheOldSenderAtTheGate",
            "stallAfterTokenCheckResumesAfterTakeoverAndTheMarkerCasFails"));
    expected.put(AbaAsNoopLedgerContractTest.class, Set.of("abaProducesThreeVersions"));
    expected.put(
        IgnoreRestoreEpochLedgerContractTest.class,
        Set.of(
            "restoreThenZombieStaleTokenIsFencedEvenBeforeExpiry",
            "restoreThenZombieSenderIsFencedAtTheGate"));
    expected.put(
        IdempotencyIgnoresContextLedgerContractTest.class,
        Set.of("sameEventSameDigestNewContextSupersedes"));
    // the flaws of the P3a suites (ReportLedger, exit fixtures, fencing scenarios, page limit)
    expected.put(
        TrnNotUniquePerLeiLedgerContractTest.class,
        Set.of("aSecondSubjectWithTheSameTrnAndLeiIsRejectedAtomically"));
    expected.put(
        TrnReassignedPerVersionLedgerContractTest.class,
        Set.of("trnIsAssignedOnceAndSharedByNewtCancAndRenewt"));
    expected.put(
        SuppressAgainstAnyVersionLedgerContractTest.class,
        Set.of(
            "logicalHashSuppressionComparesOnlyWithTheLatestNonRejectedVersion",
            "abaEndToEndThirdDecisionIsANewRowWhoseVersionReflectsA"));
    expected.put(
        NonAtomicReportableLedgerContractTest.class, Set.of("decisionAndVersionsCommitInOneUnit"));
    expected.put(
        SubjectNotRegimeScopedLedgerContractTest.class, Set.of("subjectKeyIsScopedByRegime"));
    expected.put(
        NoWithdrawalGuardLedgerContractTest.class,
        Set.of(
            "aSupersedingNonReportDecisionFailsClosedWhileVersionsExist",
            "aSupersedingNonReportDecisionFailsClosedThroughCommitDecisionsToo",
            "aNonReportSecondDecisionOfTheUnitFailsClosedAndWritesNothing",
            "abaWithADifferingOutcomeOnAChainWithAVersionFailsClosed"));
    expected.put(
        TakeoverTouchesOtherEndpointsLedgerContractTest.class,
        Set.of("takeoverMovesSendingDeliveriesToInDoubtAndFencesTheOldHolder"));
    expected.put(
        PageUnboundedLedgerContractTest.class, Set.of("pendingOutboxReturnsAtMostPageLimitItems"));
    // FP2: the values the real store cannot hold, and the day of the idempotency key
    expected.put(
        AsAtNotCheckedLedgerContractTest.class,
        Set.of("anAsAtOutsideTheSupportedRangeIsRejectedBeforeAnyWrite"));
    expected.put(
        IdempotencyIgnoresDayLedgerContractTest.class,
        Set.of("aSameKeyOnAnotherUtcDayIsANewDecisionNotARedelivery"));
    expected.put(
        LeaseTtlUnboundedLedgerContractTest.class,
        Set.of("aLeaseTtlBeyondTheMaximumIsRejectedAndTheMaximumIsGranted"));

    // FP3: the guards of rule 8 and rule 2 on every leg and outcome, the highest stored version,
    // the exact ingest key and the kind filter of the outbox
    expected.put(
        WithdrawalGuardFirstLegOnlyLedgerContractTest.class,
        Set.of("aSupersedingDecisionThatDropsALaterLegFailsClosed"));
    expected.put(
        WithdrawalGuardIgnoresOutcomeLedgerContractTest.class,
        Set.of("aNonReportDecisionThatStillNamesLegsFailsClosed"));
    expected.put(
        WithdrawalGuardOnRedeliveryLedgerContractTest.class,
        Set.of("aRedeliveryOfAnEarlierNonReportDecisionIsANoopWhenLaterVersionsExist"));
    expected.put(
        VersionOnAnyOutcomeLedgerContractTest.class,
        Set.of("aVersionOnANonReportDecisionThatNamesLegsIsRefused"));
    expected.put(
        HighestVersionIsMinLedgerContractTest.class,
        Set.of(
            "aLowerVersionBetweenTwoStoredVersionsIsStale",
            "aLowerVersionBetweenTwoVersionsOfOneCommitIsStale",
            "aVersionBetweenTwoStoredVersionsIsStaleAndNeverReported"));
    expected.put(
        ExactKeyNotFirstLedgerContractTest.class,
        Set.of("theExactKeyIsDecidedFirstEvenWhenTheEventIdentityDiffers"));
    expected.put(
        OutboxKindIgnoredLedgerContractTest.class,
        Set.of("oneSequenceOutboxItemPerBatchEvenOnRedelivery"));

    List<String> misses = new ArrayList<>();
    expected.forEach(
        (mutant, names) -> {
          Set<String> failed = failedMethodNames(mutant);
          for (String name : new TreeSet<>(names)) {
            if (!failed.contains(name)) {
              misses.add(mutant.getSimpleName() + " was not caught by " + name);
            }
          }
        });
    assertThat(misses).isEmpty();
  }

  private static Set<String> failedMethodNames(Class<?> mutant) {
    var request = LauncherDiscoveryRequestBuilder.request().selectors(selectClass(mutant)).build();
    var listener = new SummaryGeneratingListener();
    LauncherFactory.create().execute(request, listener);
    Set<String> failed = new TreeSet<>();
    for (var failure : listener.getSummary().getFailures()) {
      if (failure.getTestIdentifier().getSource().orElse(null) instanceof MethodSource source) {
        failed.add(source.getMethodName());
      }
    }
    return failed;
  }
}
