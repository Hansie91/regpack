package dev.regpack.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import dev.regpack.inmemory.mutants.AbaAsNoopLedgerContractTest;
import dev.regpack.inmemory.mutants.IdempotencyIgnoresContextLedgerContractTest;
import dev.regpack.inmemory.mutants.IgnoreLeaseTokenLedgerContractTest;
import dev.regpack.inmemory.mutants.IgnoreRestoreEpochLedgerContractTest;
import dev.regpack.inmemory.mutants.NoInFlightGuardLedgerContractTest;
import dev.regpack.inmemory.mutants.NoTakeoverInDoubtLedgerContractTest;
import dev.regpack.inmemory.mutants.NoWithdrawalGuardLedgerContractTest;
import dev.regpack.inmemory.mutants.NonAtomicDecisionsLedgerContractTest;
import dev.regpack.inmemory.mutants.NonAtomicReportableLedgerContractTest;
import dev.regpack.inmemory.mutants.OverwriteOnDuplicateEvidenceContractTest;
import dev.regpack.inmemory.mutants.PageUnboundedLedgerContractTest;
import dev.regpack.inmemory.mutants.RedeliveryReingestsLedgerContractTest;
import dev.regpack.inmemory.mutants.ShortenRetentionEvidenceContractTest;
import dev.regpack.inmemory.mutants.SubjectNotRegimeScopedLedgerContractTest;
import dev.regpack.inmemory.mutants.SuppressAgainstAnyVersionLedgerContractTest;
import dev.regpack.inmemory.mutants.TakeoverTouchesOtherEndpointsLedgerContractTest;
import dev.regpack.inmemory.mutants.TrnNotUniquePerLeiLedgerContractTest;
import dev.regpack.inmemory.mutants.TrnReassignedPerVersionLedgerContractTest;
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
            "aSupersedingNonReportDecisionFailsClosedThroughCommitDecisionsToo"));
    expected.put(
        TakeoverTouchesOtherEndpointsLedgerContractTest.class,
        Set.of("takeoverMovesSendingDeliveriesToInDoubtAndFencesTheOldHolder"));
    expected.put(
        PageUnboundedLedgerContractTest.class, Set.of("pendingOutboxReturnsAtMostPageLimitItems"));

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
