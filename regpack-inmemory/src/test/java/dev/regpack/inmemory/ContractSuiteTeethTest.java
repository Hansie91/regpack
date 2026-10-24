package dev.regpack.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import dev.regpack.inmemory.mutants.AbaAsNoopLedgerContractTest;
import dev.regpack.inmemory.mutants.AnyRetainUntilEvidenceContractTest;
import dev.regpack.inmemory.mutants.AsAtNotCheckedLedgerContractTest;
import dev.regpack.inmemory.mutants.BeginSendingNotAtomicWithManifestLedgerContractTest;
import dev.regpack.inmemory.mutants.CaseFoldedKeysEvidenceContractTest;
import dev.regpack.inmemory.mutants.ExactKeyNotFirstLedgerContractTest;
import dev.regpack.inmemory.mutants.HighestVersionIsMinLedgerContractTest;
import dev.regpack.inmemory.mutants.IdempotencyIgnoresContextLedgerContractTest;
import dev.regpack.inmemory.mutants.IdempotencyIgnoresDayLedgerContractTest;
import dev.regpack.inmemory.mutants.IgnoreLeaseTokenLedgerContractTest;
import dev.regpack.inmemory.mutants.IgnoreRestoreEpochLedgerContractTest;
import dev.regpack.inmemory.mutants.LeaseTtlUnboundedLedgerContractTest;
import dev.regpack.inmemory.mutants.ListIgnoresAfterEvidenceContractTest;
import dev.regpack.inmemory.mutants.ManifestOverwritesOnConflictLedgerContractTest;
import dev.regpack.inmemory.mutants.ManifestRecordedOnFailedBeginLedgerContractTest;
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
import dev.regpack.inmemory.mutants.ShortPagesEvidenceContractTest;
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
import dev.regpack.ports.evidence.EvidenceIntegrityException;
import dev.regpack.ports.ledger.WithdrawalRequiredException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
    // P3b2 (D-W10): a full page while keys remain
    expected.put(
        ShortPagesEvidenceContractTest.class, Set.of("listReturnsFullPagesWhileKeysRemain"));
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

    // P3b1 T10: the object manifest and its place in the SENDING unit
    expected.put(
        ManifestOverwritesOnConflictLedgerContractTest.class,
        Set.of(
            "recordingTheSameKeyWithAnotherHashIsAConflictAndStoresNothingOfTheCall",
            "concurrentRecordsOfOneKeyWithDifferentHashesLeaveOneRowAndOneConflict",
            "aConflictingObjectRollsTheSendingTransitionBack"));
    expected.put(
        ManifestRecordedOnFailedBeginLedgerContractTest.class,
        Set.of("aStaleLeaseOrAStateMismatchRecordsNoObject"));
    expected.put(
        BeginSendingNotAtomicWithManifestLedgerContractTest.class,
        Set.of("aConflictingObjectRollsTheSendingTransitionBack"));

    List<String> misses = new ArrayList<>();
    Set<String> usedExceptions = new TreeSet<>();
    expected.forEach(
        (mutant, names) -> {
          Map<String, Throwable> failures = failuresOf(mutant);
          for (String name : new TreeSet<>(names)) {
            Throwable failure = failures.get(name);
            String pair = mutant.getSimpleName() + "#" + name;
            if (failure == null) {
              misses.add(mutant.getSimpleName() + " was not caught by " + name);
            } else if (NOT_AN_ASSERTION.containsKey(pair)) {
              usedExceptions.add(pair);
              if (failure.getClass() != NOT_AN_ASSERTION.get(pair)) {
                misses.add(
                    pair
                        + " was expected to fail with "
                        + NOT_AN_ASSERTION.get(pair).getSimpleName()
                        + " and failed with "
                        + failure.getClass().getSimpleName());
              }
            } else if (!(failure instanceof AssertionError)) {
              misses.add(
                  mutant.getSimpleName()
                      + " made "
                      + name
                      + " fail with "
                      + failure.getClass().getSimpleName()
                      + " and not with an assertion");
            }
          }
        });
    // an entry of the registry must be a pair of the table above that still fails that way
    for (String pair : NOT_AN_ASSERTION.keySet()) {
      if (!usedExceptions.contains(pair)) {
        misses.add("the registry of exceptions names a pair that is not expected above: " + pair);
      }
    }
    assertThat(misses).isEmpty();
  }

  /**
   * TESTS-5: a mutant is caught by a contract test when the test fails with an assertion, not with
   * a stray exception of the adapter. The pairs below fail with the named exception instead: the
   * flawed adapter makes the in-memory store itself refuse the call, and the contract test does not
   * catch it. A change that makes any of them throw something else for an unrelated reason is seen,
   * as is an entry that is no longer needed.
   */
  private static final Map<String, Class<? extends Throwable>> NOT_AN_ASSERTION =
      Map.of(
          "CaseFoldedKeysEvidenceContractTest#keysDifferingOnlyInCaseAreDistinct",
          EvidenceIntegrityException.class,
          "SubjectNotRegimeScopedLedgerContractTest#subjectKeyIsScopedByRegime",
          IllegalArgumentException.class,
          "WithdrawalGuardOnRedeliveryLedgerContractTest"
              + "#aRedeliveryOfAnEarlierNonReportDecisionIsANoopWhenLaterVersionsExist",
          WithdrawalRequiredException.class);

  /** The failure of each contract method that failed when the mutant ran the suite. */
  private static Map<String, Throwable> failuresOf(Class<?> mutant) {
    var request = LauncherDiscoveryRequestBuilder.request().selectors(selectClass(mutant)).build();
    var listener = new SummaryGeneratingListener();
    LauncherFactory.create().execute(request, listener);
    Map<String, Throwable> failures = new TreeMap<>();
    for (var failure : listener.getSummary().getFailures()) {
      if (failure.getTestIdentifier().getSource().orElse(null) instanceof MethodSource source) {
        failures.put(source.getMethodName(), failure.getException());
      }
    }
    return failures;
  }
}
