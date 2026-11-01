package dev.regpack.flow.validate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.flow.validate.Validator.Flaw;
import org.junit.jupiter.api.Test;

/**
 * The teeth of the validator (plan Convention 3, SF6, SF12, SF15): each one-line flaw, switched on
 * through the package-private seam, must make the named scenario of {@link ValidatorTest} fail, and
 * the same scenario with {@link Flaw#NONE} passes, so the flaw and nothing else is what the
 * scenario catches.
 */
class ValidatorMutantsTest {
  @Test
  void theUnflawedValidatorPassesEveryNamedScenario() {
    ValidatorTest.resendOfARejectedNewt(new ValidatorFixture(Flaw.NONE));
    ValidatorTest.badIsinIsRejected(new ValidatorFixture(Flaw.NONE));
    ValidatorTest.lapsedClientIsAFlagNotAFailure(new ValidatorFixture(Flaw.NONE));
    ValidatorTest.tradeAtTheLimitOfThreeYears(new ValidatorFixture(Flaw.NONE));
    ValidatorTest.staleSnapshot(new ValidatorFixture(Flaw.NONE));
    ValidatorTest.lookupsTakeTheTradeDate(new ValidatorFixture(Flaw.NONE));
  }

  @Test
  void countingARejectedReportAsLiveFailsTheResendScenario() {
    assertThatThrownBy(
            () ->
                ValidatorTest.resendOfARejectedNewt(new ValidatorFixture(Flaw.DEAD_COUNTS_AS_LIVE)))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void lettingABlockerPassFailsTheBadIsinScenario() {
    assertThatThrownBy(
            () -> ValidatorTest.badIsinIsRejected(new ValidatorFixture(Flaw.BLOCKER_PASSES)))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void failingTheRecordOnAWarningFailsTheLapsedClientScenario() {
    assertThatThrownBy(
            () ->
                ValidatorTest.lapsedClientIsAFlagNotAFailure(
                    new ValidatorFixture(Flaw.WARNING_FAILS_RECORD)))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void makingTheAgeLimitExclusiveFailsTheBoundaryScenario() {
    assertThatThrownBy(
            () ->
                ValidatorTest.tradeAtTheLimitOfThreeYears(
                    new ValidatorFixture(Flaw.AGE_LIMIT_EXCLUSIVE)))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void droppingTheStaleFlagFailsTheStaleSnapshotScenario() {
    assertThatThrownBy(() -> ValidatorTest.staleSnapshot(new ValidatorFixture(Flaw.NO_STALE_FLAG)))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void countingAnInDoubtReportAsDeadFailsTheLiveAndDeadScenario() {
    ValidatorTest.everyDeliveryStateIsLiveOrDead(new ValidatorFixture(Flaw.NONE));
    assertThatThrownBy(
            () ->
                ValidatorTest.everyDeliveryStateIsLiveOrDead(
                    new ValidatorFixture(Flaw.IN_DOUBT_COUNTS_AS_DEAD)))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void theBoundaryScenariosPassOnTheUnflawedValidatorAndDropTheFlagWithoutIt() {
    ValidatorTest.snapshotAgeBoundary(new ValidatorFixture(Flaw.NONE));
    ValidatorTest.v11JudgesTheSnapshotByItsLoadDate(new ValidatorFixture(Flaw.NONE));
    assertThatThrownBy(
            () -> ValidatorTest.snapshotAgeBoundary(new ValidatorFixture(Flaw.NO_STALE_FLAG)))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void anchoringLookupsToTheReceiptDateFailsTheTradeDateScenario() {
    assertThatThrownBy(
            () ->
                ValidatorTest.lookupsTakeTheTradeDate(new ValidatorFixture(Flaw.SUBMISSION_ANCHOR)))
        .isInstanceOf(AssertionError.class);
  }
}
