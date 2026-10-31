package dev.regpack.evidence.seal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import dev.regpack.testkit.contract.SealScenarioContract;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/**
 * Proves that the I1 scenarios of {@link SealScenarioContract} and the scenarios of the P-SEALED
 * binding have teeth (EF2, D-E10): each deliberately wrong {@link DurableSeal} (a subclass that
 * overrides one template method, or the gate method) or evidence store makes the named scenarios
 * fail, the failure is an assertion of the scenario and not a stray exception, every scenario is
 * the tooth of at least one mutant, and a mutant binding runs the whole suite (so a mutant that is
 * caught by nothing cannot pass unnoticed).
 */
class SealingTeethTest {
  private static final String ORDER =
      "noByteReachesTheDestinationBeforeBothObjectsAndTheSendingCommitAreDurable";
  private static final String MANIFEST =
      "afterSealingTheManifestHoldsBothObjectsWithTheirWriteTimeHashes";
  private static final String OUTAGE_FIRST =
      "anEvidenceOutageBeforeAnyPutFailsClosedAndLeavesTheLedgerUntouched";
  private static final String OUTAGE_SECOND =
      "anOutageAfterTheSealedBytesLeavesAnOrphanSealedObjectOnly";
  private static final String CRASH_SEALED = "aCrashAfterTheSealedBytesLeavesOneOrphanObject";
  private static final String CRASH_INTENT =
      "aCrashAfterTheIntentPutLeavesAnOrphanIntentAndNoSendingRow";
  private static final String CRASH_COMMIT =
      "aCrashAfterTheSendingCommitLeavesSendingWithoutMarkerAndNoByte";
  private static final String CRASH_MARKER = "aCrashAfterTheMarkerLeavesTheMarkerAndNoByte";
  private static final String CRASH_BYTE =
      "aCrashAfterTheFirstByteLeavesTheFileDeliveredTheMarkerSetAndBothObjectsDurable";
  private static final String RACE = "concurrentSealsOfTheSameDeliveryHaveOneWinner";
  private static final String STALE = "aStaleLeaseAfterThePutsLeavesOrphansAndSendsNothing";
  private static final String GATE = "aClosedGateStopsBeforeAnyPut";
  private static final String EPOCH_DIFFERS =
      "theEpochMarkerThatDiffersRefusesTheSendAndSetsNoMarker";
  private static final String EPOCH_BELOW = "aMarkerBelowTheTokensEpochRefusesTheSend";
  private static final String EPOCH_DAMAGED = "anUnreadableEpochMarkerFailsClosed";
  private static final String EPOCH_DOWN = "anUnreachableEvidenceTierAtTheGateFailsClosed";
  private static final String EPOCH_SAME = "aMarkerOfTheTokensEpochLetsTheSendThrough";
  private static final String TAKEOVER = "aTakeoverBetweenSealAndMarkerRefusesTheMarker";
  private static final String SHARED = "theSameBytesSealedForTwoAttemptsShareOneSealedObject";
  private static final String BARE_ATTEMPT =
      "aBareAttemptBegunThroughTheLedgerWithoutSealingIsRefusedAtTheGate";
  private static final String OTHER_ENDPOINT = "aTokenOfAnotherEndpointIsRefusedAtTheGate";
  private static final String OTHER_STATE =
      "anAttemptWhoseDeliveryLeftSendingIsRefusedBeforeTheMarkerStatement";
  private static final String UNCONFIRMED_MARKER =
      "aMarkerCommitThatNoStandbyConfirmedIsNeverClearAndNoByteLeaves";
  private static final String UNCONFIRMED_SENDING =
      "aSendingCommitThatNoStandbyConfirmedIsNeverSealed";
  private static final String SHARED_RETENTION =
      "theSameBytesSealedLaterExtendTheSharedObjectAndKeepOneRow";
  // the scenarios that only the P-SEALED binding has (they need real directories)
  private static final String SURVIVES_LOSS_OF_A =
      "whatTheSealAcknowledgedSurvivesTheLossOfVolumeA";
  private static final String SURVIVES_LOSS_OF_B =
      "whatTheSealAcknowledgedSurvivesTheLossOfVolumeB";
  private static final String LOST_VOLUME_STOPS_THE_SEAL =
      "aLostVolumeStopsTheSealBeforeTheLedgerChanges";
  private static final String LOST_VOLUME_CLOSES_THE_GATE =
      "aLostVolumeClosesAGateThatAsksForADurableAcknowledgement";

  /** The mutant binding and the scenarios that must fail for it, in the order of the plan. */
  private static Map<Class<?>, Set<String>> expected() {
    Map<Class<?>, Set<String>> expected = new LinkedHashMap<>();
    // the six mutants of the plan
    expected.put(
        CommitBeforePutsScenarioTest.class,
        Set.of(ORDER, CRASH_SEALED, CRASH_INTENT, STALE, OUTAGE_FIRST, OUTAGE_SECOND));
    expected.put(
        SkipIntentPutScenarioTest.class, Set.of(ORDER, MANIFEST, CRASH_INTENT, RACE, SHARED));
    expected.put(SwallowEvidenceOutageScenarioTest.class, Set.of(OUTAGE_FIRST, OUTAGE_SECOND));
    expected.put(
        SkipEpochCheckScenarioTest.class,
        Set.of(EPOCH_DIFFERS, EPOCH_BELOW, EPOCH_DAMAGED, EPOCH_DOWN));
    expected.put(IgnoreGateScenarioTest.class, Set.of(GATE));
    expected.put(ManifestAfterCommitScenarioTest.class, Set.of(MANIFEST, CRASH_COMMIT));
    // the scenarios that none of the six reaches
    expected.put(IgnoreLedgerRefusalScenarioTest.class, Set.of(RACE, STALE));
    expected.put(
        AlwaysClearScenarioTest.class,
        Set.of(
            CRASH_MARKER,
            CRASH_BYTE,
            EPOCH_DIFFERS,
            EPOCH_BELOW,
            EPOCH_DAMAGED,
            EPOCH_DOWN,
            TAKEOVER,
            STALE,
            GATE,
            BARE_ATTEMPT,
            OTHER_ENDPOINT,
            OTHER_STATE,
            UNCONFIRMED_MARKER));
    expected.put(
        MarkerAlwaysAbsentScenarioTest.class,
        Set.of(EPOCH_DIFFERS, EPOCH_DAMAGED, EPOCH_DOWN, EPOCH_SAME));
    expected.put(AttemptScopedSealedKeyScenarioTest.class, Set.of(MANIFEST, SHARED));
    // the finer mutants of the gate in front of the first byte: AlwaysClear is caught by "no gate
    // at all"; these two keep the rest of the gate and drop one check each
    expected.put(IgnoreMarkerAlreadySetScenarioTest.class, Set.of(CRASH_MARKER, CRASH_BYTE));
    expected.put(LeaseCheckWithoutTokenScenarioTest.class, Set.of(TAKEOVER));
    // the gate checks the attempt it is given: one mutant for each check that it makes
    expected.put(IgnoreAttemptManifestScenarioTest.class, Set.of(BARE_ATTEMPT));
    expected.put(IgnoreDeliveryEndpointScenarioTest.class, Set.of(OTHER_ENDPOINT));
    expected.put(IgnoreDeliveryStateScenarioTest.class, Set.of(OTHER_STATE));
    // a commit that no standby confirmed is never reported as durable (DS-V and DS-W of the review)
    expected.put(
        SwallowLedgerWarningScenarioTest.class, Set.of(UNCONFIRMED_MARKER, UNCONFIRMED_SENDING));
    // the intent object states the identity of the delivery (I10): a constant for either field
    expected.put(HardCodedVersionScenarioTest.class, Set.of(ORDER, CRASH_BYTE, RACE));
    expected.put(HardCodedActionScenarioTest.class, Set.of(ORDER, CRASH_BYTE, RACE));
    // SEAL-1 (P3c1 D-K4): a shared sealed object keeps the retention of its first put and no
    // retention row exists when the sealer registers nothing
    expected.put(ForgetsSharedRetentionScenarioTest.class, Set.of(SHARED_RETENTION));
    // P-SEALED on real directories: a store that acknowledges and loses the intent object, and a
    // volume that acknowledges and keeps nothing (each of the two, one scenario of the loss of a
    // volume), and the two wrong sealers in front of a store that has lost a volume
    expected.put(LostIntentWriteOnSealedScenarioTest.class, Set.of(ORDER, MANIFEST, CRASH_INTENT));
    expected.put(ForgetsSecondVolumeOnSealedScenarioTest.class, Set.of(SURVIVES_LOSS_OF_A));
    expected.put(ForgetsFirstVolumeOnSealedScenarioTest.class, Set.of(SURVIVES_LOSS_OF_B));
    expected.put(
        SwallowEvidenceOutageOnSealedScenarioTest.class, Set.of(LOST_VOLUME_STOPS_THE_SEAL));
    expected.put(IgnoreGateOnSealedScenarioTest.class, Set.of(LOST_VOLUME_CLOSES_THE_GATE));
    return expected;
  }

  private record Run(Map<String, Throwable> failures, long found) {}

  @Test
  void everyMutantIsCaughtByItsNamedScenariosWithAnAssertion() {
    List<String> misses = new ArrayList<>();
    expected()
        .forEach(
            (binding, names) -> {
              Run run = run(binding);
              for (String name : new TreeSet<>(names)) {
                Throwable failure = run.failures().get(name);
                if (failure == null) {
                  misses.add(binding.getSimpleName() + " was not caught by " + name);
                } else if (!(failure instanceof AssertionError)) {
                  misses.add(
                      binding.getSimpleName()
                          + " made "
                          + name
                          + " fail with "
                          + failure.getClass().getSimpleName()
                          + " and not with an assertion");
                }
              }
            });
    assertThat(misses).isEmpty();
  }

  @Test
  void everyMutantBindingRunsTheWholeSuiteAndNamesOnlyRealScenarios() {
    // not every scenario fails for a mutant, but a binding that discovers less than the suite, or
    // a name that is no scenario (a renamed method), would leave a tooth unchecked
    List<String> problems = new ArrayList<>();
    expected()
        .forEach(
            (binding, names) -> {
              Set<String> scenarios = scenariosOf(binding);
              long found = run(binding).found();
              if (found != scenarios.size()) {
                problems.add(
                    binding.getSimpleName()
                        + " discovered "
                        + found
                        + " scenarios and the suite has "
                        + scenarios.size());
              }
              for (String name : names) {
                if (!scenarios.contains(name)) {
                  problems.add(binding.getSimpleName() + " names no scenario: " + name);
                }
              }
            });
    assertThat(problems).isEmpty();
  }

  @Test
  void everyScenarioOfBothSuitesIsTheToothOfAtLeastOneMutant() {
    Set<String> covered = new TreeSet<>();
    expected().values().forEach(covered::addAll);
    Set<String> uncovered = new TreeSet<>(contractScenarios());
    uncovered.addAll(sealedOnlyScenarios());
    uncovered.removeAll(covered);
    assertThat(uncovered).as("scenarios that no mutant makes fail").isEmpty();
  }

  /** The names of the {@code @Test} methods that {@code type} declares itself. */
  private static Set<String> declaredTests(Class<?> type) {
    Set<String> names = new TreeSet<>();
    for (Method method : type.getDeclaredMethods()) {
      if (method.isAnnotationPresent(Test.class)) {
        names.add(method.getName());
      }
    }
    return names;
  }

  /** The scenarios of the shared suite: the {@code @Test} defaults of the interface. */
  private static Set<String> contractScenarios() {
    return declaredTests(SealScenarioContract.class);
  }

  /** The scenarios of the P-SEALED binding beyond the shared suite. */
  private static Set<String> sealedOnlyScenarios() {
    return declaredTests(SealScenariosOnSealedTest.class);
  }

  /** What a binding runs: the shared suite, and the P-SEALED scenarios for a P-SEALED binding. */
  private static Set<String> scenariosOf(Class<?> binding) {
    Set<String> names = new TreeSet<>(contractScenarios());
    if (SealScenariosOnSealedTest.class.isAssignableFrom(binding)) {
      names.addAll(sealedOnlyScenarios());
    }
    return names;
  }

  private static final Map<Class<?>, Run> RUNS = new ConcurrentHashMap<>();

  private static Run run(Class<?> binding) {
    return RUNS.computeIfAbsent(binding, SealingTeethTest::execute);
  }

  private static Run execute(Class<?> binding) {
    var request = LauncherDiscoveryRequestBuilder.request().selectors(selectClass(binding)).build();
    var listener = new SummaryGeneratingListener();
    LauncherFactory.create().execute(request, listener);
    Map<String, Throwable> failures = new TreeMap<>();
    for (var failure : listener.getSummary().getFailures()) {
      if (failure.getTestIdentifier().getSource().orElse(null) instanceof MethodSource source) {
        failures.put(source.getMethodName(), failure.getException());
      }
    }
    return new Run(failures, listener.getSummary().getTestsFoundCount());
  }
}
