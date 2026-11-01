package dev.regpack.flow.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.flow.validate.Validator.Flaw;
import dev.regpack.flow.validate.Validator.ValidationResult;
import dev.regpack.flow.validate.ValidatorFixture.Exec;
import dev.regpack.model.Leg;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.pack.check.ProblemSink;
import dev.regpack.pack.report.ValidationRules;
import dev.regpack.pack.report.ValidationRules.Layer;
import dev.regpack.pack.report.ValidationRules.Rule;
import dev.regpack.pack.report.ValidationRules.Severity;
import dev.regpack.pack.yaml.StrictYaml;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.refdata.ReferenceData.DatasetId;
import dev.regpack.testkit.ident.SyntheticIds;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The validation layers L1 to L5 (plan W1a, task T9, spec 8.1): one pass and one fail per rule of
 * the content sheet, named {@code vNN<Accepts|Rejects|Fails|Allows|Flags>...}. The static scenario
 * methods take a fixture so that {@link ValidatorMutantsTest} can run them against a flawed
 * validator.
 */
class ValidatorTest {
  /** The first published LEI example of the research record (unit tests only). */
  private static final String REAL_LEI_1 = "529900T8BM49AURSDO55";

  private static final String REAL_LEI_2 = "506700GE1G29325QX363";
  private static final String REAL_ISIN = "US0378331005";

  private static Exec exec() {
    return new Exec();
  }

  private static ValidatorFixture world() {
    return new ValidatorFixture(Flaw.NONE);
  }

  /** The (rule, code) pairs of the findings, in order. */
  private static List<String> findings(ValidationResult r) {
    return r.findings().stream().map(f -> f.ruleId() + ":" + f.code()).toList();
  }

  private static void assertPasses(ValidationResult r) {
    assertThat(r.pass()).as("pass, findings %s", findings(r)).isTrue();
    assertThat(r.findings()).as("no finding").isEmpty();
    assertThat(r.flags()).as("no flag").isEmpty();
  }

  private static void assertFails(ValidationResult r, String... ruleAndCode) {
    assertThat(r.pass()).as("the record fails").isFalse();
    assertThat(findings(r)).contains(ruleAndCode);
  }

  // -- scenarios shared with the mutant tests -------------------------------------------------

  static void resendOfARejectedNewt(ValidatorFixture f) {
    // T-003: the first version is rejected by the destination and listed for repair
    f.newt("EX-003", DeliveryState.IN_REPAIR);
    Exec resend = exec();
    resend.execId = "EX-003";
    assertPasses(f.run(resend));
    // a rejected one (not yet in repair) is dead too
    f.newt("EX-004", DeliveryState.REJECTED);
    Exec second = exec();
    second.execId = "EX-004";
    assertPasses(f.run(second));
  }

  static void badIsinIsRejected(ValidatorFixture f) {
    Exec e = exec();
    e.isin = SyntheticIds.BAD_ISIN;
    assertFails(f.run(e), "V06:ISIN_CHECK_DIGIT");
  }

  static void lapsedClientIsAFlagNotAFailure(ValidatorFixture f) {
    Exec e = exec();
    e.clientLei = Optional.of(SyntheticIds.LAPSED_LEI);
    ValidationResult r = f.run(e);
    assertThat(r.pass()).isTrue();
    assertThat(findings(r)).containsExactly("V10:LEI_LAPSED");
    assertThat(r.findings().get(0).severity()).isEqualTo(Severity.WARNING);
    assertThat(r.flags()).containsExactly("LEI_LAPSED");
  }

  static void tradeAtTheLimitOfThreeYears(ValidatorFixture f) {
    Instant limit = ValidatorFixture.NOW.atZone(ZoneOffset.UTC).minusYears(3).toInstant();
    Exec exact = exec();
    exact.tradedAt = limit;
    assertPasses(f.run(exact));
    Exec older = exec();
    older.tradedAt = limit.minus(Duration.ofNanos(1_000));
    assertFails(f.run(older), "V14:TIME_TOO_OLD");
  }

  static void staleSnapshot(ValidatorFixture f) {
    assertPasses(f.run(exec()));
    f.clock.advance(Duration.ofDays(31));
    ValidationResult r = f.run(exec());
    assertThat(r.pass()).as("a stale snapshot still validates").isTrue();
    assertThat(r.findings()).isEmpty();
    assertThat(r.flags()).containsExactly("SNAPSHOT_STALE");
  }

  static void lookupsTakeTheTradeDate(ValidatorFixture f) {
    // the firm's LEI is ACTIVE on the trade date 2026-10-30 and no longer on 2026-10-31
    f.refs.load(
        new DatasetId("lei-status"),
        dev.regpack.flow.enrich.RefDataSeed.parse(
            "key,status,valid_from,valid_to\n"
                + SyntheticIds.FIRM_LEI
                + ",ACTIVE,2020-01-01,2026-10-31\n"
                + SyntheticIds.CCP_LEI
                + ",ACTIVE,2020-01-01,\n"
                + SyntheticIds.CLIENT1_LEI
                + ",ACTIVE,2020-01-01,\n"),
        ValidatorFixture.MAX_AGE);
    assertPasses(f.run(exec()));
  }

  /** The states a delivery can be in whose report no longer counts for V15 (plan V15, content). */
  private static final java.util.Set<DeliveryState> DEAD =
      java.util.EnumSet.of(
          DeliveryState.REJECTED,
          DeliveryState.IN_REPAIR,
          DeliveryState.WITHDRAWN,
          DeliveryState.WRITTEN_OFF);

  /**
   * D-03: for every delivery state, a NEWT of a fresh execution in that state. A dead state accepts
   * a NEW and refuses a CANCEL; every other state, SUPERSEDED and a version that was only committed
   * (PREPARED) included, refuses a NEW and accepts a CANCEL.
   */
  static void everyDeliveryStateIsLiveOrDead(ValidatorFixture f) {
    for (DeliveryState state : DeliveryState.values()) {
      String id = "EX-S" + state.ordinal();
      if (state == DeliveryState.PREPARED) {
        f.commitVersion(id, "NEWT"); // a committed version has the state PREPARED already
      } else if (state == DeliveryState.SENDING) {
        // the ledger lets a delivery into SENDING only through beginSending, under a lease
        var delivery = f.commitVersion(id, "NEWT");
        f.setState(delivery, DeliveryState.QUEUED);
        var granted =
            (dev.regpack.ports.ledger.LeaseLedger.LeaseResult.Granted)
                f.ledger.acquire(
                    ValidatorFixture.ENDPOINT, "validator-test", Duration.ofMinutes(5));
        var sending =
            f.ledger.beginSending(
                delivery,
                f.ledger.allocateAttemptId(),
                granted.grant().token(),
                new dev.regpack.ports.ledger.DeliveryLedger.AttemptIntent(
                    dev.regpack.model.Sha256.ofUtf8("sealed"), "demo-file.xml"));
        assertThat(sending.status())
            .isEqualTo(dev.regpack.ports.ledger.DeliveryLedger.SendingStatus.APPLIED);
      } else {
        f.newt(id, state);
      }
      if (DEAD.contains(state)) {
        assertThat(f.run(fresh(id)).pass()).as("a NEW over a NEWT in " + state).isTrue();
        assertFails(f.run(cancel(id)), "V15:CANCEL_WITHOUT_LIVE_NEWT");
      } else {
        assertFails(f.run(fresh(id)), "V15:NEWT_OVER_LIVE_NEWT");
        assertThat(f.run(cancel(id)).pass()).as("a CANCEL over a NEWT in " + state).isTrue();
      }
    }
  }

  /** D-06: a snapshot exactly as old as its maximum age is not stale; one microsecond more is. */
  static void snapshotAgeBoundary(ValidatorFixture f) {
    f.clock.advance(ValidatorFixture.MAX_AGE);
    assertPasses(f.run(exec()));
    f.clock.advance(Duration.ofNanos(1_000));
    ValidationResult r = f.run(exec());
    assertThat(r.pass()).isTrue();
    assertThat(r.flags()).containsExactly("SNAPSHOT_STALE");
  }

  /**
   * D-06: V11 judges the trade date against the day the snapshot was loaded, not against the day of
   * receipt. The seeds are loaded on 2026-10-31; the batch is received three days later.
   */
  static void v11JudgesTheSnapshotByItsLoadDate(ValidatorFixture f) {
    f.clock.advance(Duration.ofDays(3));
    Exec onTheLoadDay = exec();
    onTheLoadDay.isin = SyntheticIds.ISIN_6;
    onTheLoadDay.tradedAt = Instant.parse("2026-10-31T09:00:00Z");
    ValidationResult lag = f.run(onTheLoadDay);
    assertThat(findings(lag)).containsExactly("V11:REFDATA_LAG_EXPECTED");

    Exec theDayBefore = exec();
    theDayBefore.isin = SyntheticIds.ISIN_6;
    ValidationResult unknown = f.run(theDayBefore);
    assertThat(findings(unknown)).containsExactly("V11:REFDATA_UNKNOWN");
  }

  // -- L1 -------------------------------------------------------------------------------------

  @Test
  void v01AcceptsTwelveIntegerAndFourFractionDigits() {
    ValidatorFixture f = world();
    Exec e = exec();
    e.quantity = new BigDecimal("999999999999.9999");
    assertPasses(f.run(e));
    e.quantity = new BigDecimal("0.0001");
    assertPasses(f.run(e));
  }

  @Test
  void v01RejectsAnExtremeExponentThatWouldWrapTheDigitCount() {
    // D-09: no mapper output has this scale; a hand-built canonical value does
    ValidatorFixture f = world();
    for (int scale : List.of(Integer.MIN_VALUE + 1, Integer.MIN_VALUE, -2_000_000_000, -13)) {
      Exec e = exec();
      e.quantity = new BigDecimal(java.math.BigInteger.ONE, scale);
      ValidationResult r = f.run(e);
      assertThat(findings(r)).as("scale " + scale).contains("V01:DECIMAL_INTEGER_DIGITS");
      assertThat(r.pass()).isFalse();
    }
    Exec edge = exec();
    edge.quantity = new BigDecimal(java.math.BigInteger.ONE, -11); // 1E+11: twelve digits
    assertPasses(f.run(edge));
  }

  @Test
  void v01AcceptsZerosAfterTheFourthFractionDigit() {
    Exec e = exec();
    e.quantity = new BigDecimal("1.50000");
    assertPasses(world().run(e));
  }

  @Test
  void v01RejectsFiveFractionDigits() {
    Exec e = exec();
    e.quantity = new BigDecimal("1.00001");
    assertFails(world().run(e), "V01:DECIMAL_FRACTION_DIGITS");
  }

  @Test
  void v01RejectsThirteenIntegerDigits() {
    Exec e = exec();
    e.quantity = new BigDecimal("1000000000000");
    assertFails(world().run(e), "V01:DECIMAL_INTEGER_DIGITS");
  }

  @Test
  void v01RejectsZeroAndNegativeQuantities() {
    Exec e = exec();
    e.quantity = BigDecimal.ZERO;
    assertFails(world().run(e), "V01:DECIMAL_NOT_ABOVE_MIN");
    e.quantity = new BigDecimal("-5");
    assertFails(world().run(e), "V01:DECIMAL_NOT_ABOVE_MIN");
  }

  @Test
  void v02AcceptsSixFractionDigits() {
    Exec e = exec();
    e.price = new BigDecimal("0.123456");
    assertPasses(world().run(e));
  }

  @Test
  void v02RejectsSevenFractionDigitsAndAZeroPrice() {
    Exec e = exec();
    e.price = new BigDecimal("0.1234567");
    assertFails(world().run(e), "V02:DECIMAL_FRACTION_DIGITS");
    e.price = BigDecimal.ZERO;
    assertFails(world().run(e), "V02:DECIMAL_NOT_ABOVE_MIN");
  }

  @Test
  void v03AcceptsAThreeLetterCurrency() {
    assertPasses(world().run(exec()));
  }

  @Test
  void v03RejectsALowerCaseOrLongCurrency() {
    Exec e = exec();
    e.currency = "eur";
    assertFails(world().run(e), "V03:CURRENCY_SHAPE");
    e.currency = "EURO";
    assertFails(world().run(e), "V03:CURRENCY_SHAPE");
  }

  // -- L2 -------------------------------------------------------------------------------------

  @Test
  void v04AcceptsTheFirmLei() {
    assertPasses(world().run(exec()));
  }

  @Test
  void v04RejectsAChangedCheckDigitAndAShortLei() {
    Exec e = exec();
    e.firmLei = "SYNT00FIRM0000000153";
    assertFails(world().run(e), "V04:LEI_CHECK_DIGITS");
    e.firmLei = "SYNT00FIRM";
    assertFails(world().run(e), "V04:LEI_SHAPE");
  }

  @Test
  void v05AcceptsAnAbsentClientAndAnAbsentCounterparty() {
    Exec e = exec();
    e.clientLei = Optional.empty();
    e.counterparty = Optional.empty();
    assertPasses(world().run(e));
  }

  @Test
  void v05RejectsABadClientOrCounterpartyLei() {
    Exec e = exec();
    e.counterparty = Optional.of("SYNT00CCP00000000195");
    assertFails(world().run(e), "V05:LEI_CHECK_DIGITS");
    Exec shape = exec();
    shape.clientLei = Optional.of("short");
    assertFails(world().run(shape), "V05:LEI_SHAPE");
  }

  @Test
  void v06AcceptsAValidIsin() {
    assertPasses(world().run(exec()));
  }

  @Test
  void v06RejectsBadIsin() {
    badIsinIsRejected(world());
    Exec e = exec();
    e.isin = "ABC";
    assertFails(world().run(e), "V06:ISIN_SHAPE");
  }

  @Test
  void v07AcceptsAFourCharacterMic() {
    assertPasses(world().run(exec()));
  }

  @Test
  void v07RejectsALowerCaseMic() {
    Exec e = exec();
    e.mic = "xpar";
    assertFails(world().run(e), "V07:MIC_SHAPE");
  }

  @Test
  void v08AcceptsAnOrdinaryCurrency() {
    assertPasses(world().run(exec()));
  }

  @Test
  void v08RejectsXXX() {
    for (String code : List.of("XXX", "XAU", "XDR")) {
      Exec e = exec();
      e.currency = code;
      assertFails(world().run(e), "V08:CURRENCY_EXCLUDED");
    }
  }

  // -- L3 -------------------------------------------------------------------------------------

  @Test
  void v09AcceptsAnActiveExecutingLei() {
    assertPasses(world().run(exec()));
  }

  @Test
  void v09RejectsALapsedOrUnknownExecutingLei() {
    ValidatorFixture f = world();
    Exec lapsed = exec();
    lapsed.firmLei = SyntheticIds.LAPSED_LEI;
    assertFails(f.run(lapsed), "V09:REF_STATUS_NOT_ALLOWED");
    Exec unknown = exec();
    unknown.firmLei = REAL_LEI_1;
    assertFails(f.run(unknown), "V09:REF_NOT_FOUND");
  }

  @Test
  void v09RejectsAnExecutingLeiWhoseSeedRowHasNoStatus() {
    // F-7: an empty status cell leaves the attribute out; the check fails closed
    ValidatorFixture f = world();
    f.refs.load(
        new DatasetId("lei-status"),
        dev.regpack.flow.enrich.RefDataSeed.parse(
            "key,status,valid_from,valid_to\n"
                + SyntheticIds.FIRM_LEI
                + ",,2020-01-01,\n"
                + SyntheticIds.CCP_LEI
                + ",ACTIVE,2020-01-01,\n"
                + SyntheticIds.CLIENT1_LEI
                + ",ACTIVE,2020-01-01,\n"),
        ValidatorFixture.MAX_AGE);
    ValidationResult r = f.run(exec());
    assertFails(r, "V09:REF_STATUS_NOT_ALLOWED");
    assertThat(findings(r)).doesNotContain("V09:REF_NOT_FOUND");
  }

  @Test
  void v09ReadsTheDerivedExecutingLeiNotOnlyTheCanonicalOne() {
    ValidatorFixture f = world();
    ValidationResult r =
        f.validator.validate(exec().build(), Map.of("executing_lei", SyntheticIds.LAPSED_LEI));
    assertFails(r, "V09:REF_STATUS_NOT_ALLOWED");
  }

  @Test
  void v10AcceptsAnActiveClientAndAnActiveCounterparty() {
    assertPasses(world().run(exec()));
    Exec client2 = exec();
    client2.clientLei = Optional.of(SyntheticIds.CLIENT2_LEI);
    assertPasses(world().run(client2));
  }

  @Test
  void v10AllowsALapsedClientAndFlagsIt() {
    lapsedClientIsAFlagNotAFailure(world());
  }

  @Test
  void v10RejectsARealClientOrCounterpartyLei() {
    ValidatorFixture f = world();
    Exec client = exec();
    client.clientLei = Optional.of(REAL_LEI_2);
    assertFails(f.run(client), "V10:REF_NOT_FOUND");
    Exec counterparty = exec();
    counterparty.counterparty = Optional.of(REAL_LEI_1);
    assertFails(f.run(counterparty), "V10:REF_NOT_FOUND");
  }

  @Test
  void v11AcceptsAListedInstrumentOnItsVenue() {
    assertPasses(world().run(exec()));
  }

  @Test
  void v11FlagsAnUnlistedIsinAsUnknownWhenTheTradeDatePrecedesTheSnapshot() {
    Exec e = exec();
    e.isin = SyntheticIds.ISIN_6;
    ValidationResult r = world().run(e);
    assertThat(r.pass()).isTrue();
    assertThat(findings(r)).containsExactly("V11:REFDATA_UNKNOWN");
    assertThat(r.findings().get(0).severity()).isEqualTo(Severity.WARNING);
    assertThat(r.flags()).containsExactly("REFDATA_UNKNOWN");
  }

  @Test
  void v11FlagsAnUnlistedIsinAsLagExpectedOtherwise() {
    Exec e = exec();
    e.isin = SyntheticIds.ISIN_6;
    // the trade date is the snapshot date, 2026-10-31: the listing may not have arrived yet
    e.tradedAt = Instant.parse("2026-10-31T09:00:00Z");
    ValidationResult r = world().run(e);
    assertThat(r.pass()).isTrue();
    assertThat(findings(r)).containsExactly("V11:REFDATA_LAG_EXPECTED");
    assertThat(r.flags()).containsExactly("REFDATA_LAG_EXPECTED");
  }

  @Test
  void v11FlagsByTheDayTheSnapshotWasLoadedNotByTheDayOfReceipt() {
    v11JudgesTheSnapshotByItsLoadDate(world());
  }

  @Test
  void v11FlagsAListedIsinOnAnotherVenue() {
    Exec e = exec();
    e.mic = "XAMS"; // ISIN_1 is listed on XPAR only
    ValidationResult r = world().run(e);
    assertThat(r.pass()).isTrue();
    assertThat(findings(r)).containsExactly("V11:REFDATA_UNKNOWN");
  }

  @Test
  void v12AcceptsAKnownMic() {
    Exec e = exec();
    e.mic = "XAMS";
    e.isin = SyntheticIds.ISIN_3;
    assertPasses(world().run(e));
  }

  @Test
  void v12RejectsAnUnknownMic() {
    Exec e = exec();
    e.mic = "ZZZZ";
    assertFails(world().run(e), "V12:REF_NOT_FOUND");
  }

  // -- L4 -------------------------------------------------------------------------------------

  @Test
  void v13AcceptsATradeAtTheTimeOfReceipt() {
    Exec e = exec();
    e.tradedAt = ValidatorFixture.NOW;
    assertPasses(world().run(e));
  }

  @Test
  void v13RejectsATradeAfterReceipt() {
    Exec e = exec();
    e.tradedAt = ValidatorFixture.NOW.plus(Duration.ofNanos(1_000));
    assertFails(world().run(e), "V13:TIME_AFTER_RECEIPT");
  }

  @Test
  void v14AcceptsATradeOfYesterday() {
    assertPasses(world().run(exec()));
  }

  @Test
  void v14RejectsATradeOlderThanThreeYears() {
    tradeAtTheLimitOfThreeYears(world());
  }

  // -- L5 -------------------------------------------------------------------------------------

  private static Exec cancel(String execId) {
    Exec e = exec();
    e.kind = LifecycleKind.CANCEL;
    e.execId = execId;
    return e;
  }

  private static Exec fresh(String execId) {
    Exec e = exec();
    e.execId = execId;
    return e;
  }

  @Test
  void v15AcceptsACancelOfAnAcceptedNewt() {
    ValidatorFixture f = world();
    f.newt("EX-001", DeliveryState.ACCEPTED_PROVISIONAL);
    assertPasses(f.run(cancel("EX-001")));
    // a confirmed one and one that is still on its way are live as well
    f.newt("EX-002", DeliveryState.ACCEPTED_CONFIRMED);
    assertPasses(f.run(cancel("EX-002")));
    f.commitVersion("EX-005", "NEWT");
    assertPasses(f.run(cancel("EX-005")));
  }

  @Test
  void v15RejectsACancelOfAnUnknownSubject() {
    assertFails(world().run(cancel("EX-777")), "V15:CANCEL_WITHOUT_LIVE_NEWT");
  }

  @Test
  void v15RejectsASecondCancel() {
    ValidatorFixture f = world();
    f.newt("EX-001", DeliveryState.ACCEPTED_CONFIRMED);
    f.canc("EX-001", DeliveryState.ACCEPTED_CONFIRMED);
    assertFails(f.run(cancel("EX-001")), "V15:CANCEL_WITHOUT_LIVE_NEWT");
  }

  @Test
  void v15RejectsACancelWhoseOnlyNewtIsInRepair() {
    ValidatorFixture f = world();
    f.newt("EX-001", DeliveryState.IN_REPAIR);
    assertFails(f.run(cancel("EX-001")), "V15:CANCEL_WITHOUT_LIVE_NEWT");
    f.newt("EX-002", DeliveryState.REJECTED);
    assertFails(f.run(cancel("EX-002")), "V15:CANCEL_WITHOUT_LIVE_NEWT");
    f.newt("EX-003", DeliveryState.WITHDRAWN);
    assertFails(f.run(cancel("EX-003")), "V15:CANCEL_WITHOUT_LIVE_NEWT");
    f.newt("EX-004", DeliveryState.WRITTEN_OFF);
    assertFails(f.run(cancel("EX-004")), "V15:CANCEL_WITHOUT_LIVE_NEWT");
  }

  @Test
  void v15RejectsANewOverAnAcceptedNewt() {
    ValidatorFixture f = world();
    f.newt("EX-001", DeliveryState.ACCEPTED_PROVISIONAL);
    assertFails(f.run(fresh("EX-001")), "V15:NEWT_OVER_LIVE_NEWT");
    // also over a report that is still on its way
    f.commitVersion("EX-002", "NEWT");
    assertFails(f.run(fresh("EX-002")), "V15:NEWT_OVER_LIVE_NEWT");
  }

  @Test
  void v15AllowsTheResendOfARejectedNewt() {
    resendOfARejectedNewt(world());
  }

  @Test
  void v15AllowsANewAfterAnAcceptedCancel() {
    ValidatorFixture f = world();
    f.newt("EX-001", DeliveryState.ACCEPTED_CONFIRMED);
    f.canc("EX-001", DeliveryState.ACCEPTED_CONFIRMED);
    assertPasses(f.run(fresh("EX-001")));
  }

  @Test
  void v15AllowsANewForAnUnknownSubjectAndLeavesOtherEventKindsAlone() {
    ValidatorFixture f = world();
    assertPasses(f.run(fresh("EX-001")));
    f.newt("EX-002", DeliveryState.ACCEPTED_CONFIRMED);
    Exec amend = fresh("EX-002");
    amend.kind = LifecycleKind.AMEND;
    assertPasses(f.run(amend));
  }

  @Test
  void v15SplitsEveryDeliveryStateIntoLiveAndDead() {
    everyDeliveryStateIsLiveOrDead(world());
  }

  @Test
  void v15JudgesTheChainOfItsOwnRegimeAndLegOnly() {
    ValidatorFixture f = world();
    f.newt("EX-001", DeliveryState.ACCEPTED_CONFIRMED);
    // another leg of the same event has no report: the chain of this validator's leg decides
    Validator other =
        new Validator(
            ValidationRulesTest.fixtureRules(),
            f.refs,
            f.ledger,
            f.ledger,
            ValidatorFixture.REGIME,
            Leg.CLIENT,
            f.clock);
    ValidationResult r =
        other.validate(cancel("EX-001").build(), Map.of("executing_lei", SyntheticIds.FIRM_LEI));
    assertFails(r, "V15:CANCEL_WITHOUT_LIVE_NEWT");
  }

  // -- the whole validator --------------------------------------------------------------------

  @Test
  void aStaleSnapshotFlagsAndStillValidates() {
    staleSnapshot(world());
  }

  @Test
  void aSnapshotExactlyAsOldAsItsMaximumAgeIsNotStaleAndOneMicrosecondMoreIs() {
    snapshotAgeBoundary(world());
  }

  @Test
  void lookupsTakeTheTradeDateNotTheDateOfReceipt() {
    lookupsTakeTheTradeDate(world());
  }

  @Test
  void findingsAreInRuleOrderAndOnePerRuleAndCode() {
    Exec e = exec();
    e.quantity = new BigDecimal("-1.00001"); // two defects of one rule
    e.isin = SyntheticIds.BAD_ISIN;
    ValidationResult r = world().run(e);
    assertThat(findings(r))
        .containsExactly(
            "V01:DECIMAL_NOT_ABOVE_MIN",
            "V01:DECIMAL_FRACTION_DIGITS",
            "V06:ISIN_CHECK_DIGIT",
            "V11:REFDATA_UNKNOWN");
    assertThat(r.findings())
        .extracting(f -> f.layer())
        .containsExactly(Layer.L1, Layer.L1, Layer.L2, Layer.L3);
    assertThat(r.flags()).containsExactly("REFDATA_UNKNOWN");
  }

  @Test
  void findingsCarryNoValue() throws ReflectiveOperationException {
    Exec e = exec();
    e.isin = "ZZ9999999987";
    e.mic = "QQQQ";
    e.currency = "zzz";
    e.clientLei = Optional.of("REALCLIENT0000000099");
    e.quantity = new BigDecimal("-1234567.8912345");
    ValidationResult r = world().run(e);

    assertThat(r.pass()).isFalse();
    String text = r + " " + r.findings();
    for (String value :
        List.of(
            "ZZ9999999987",
            "QQQQ",
            "zzz",
            "REALCLIENT0000000099",
            "1234567",
            "EX-001",
            SyntheticIds.FIRM_LEI)) {
      assertThat(text).as("no value of the record in the result").doesNotContain(value);
    }
    // by construction: a finding is a rule id, a layer, a severity and a code, nothing else
    assertThat(Finding.class.getRecordComponents())
        .extracting(c -> c.getType().getSimpleName())
        .containsExactly("String", "Layer", "Severity", "String");
    for (var finding : r.findings()) {
      assertThat(finding.code()).matches("[A-Z][A-Z0-9_]{1,63}");
    }
  }

  @Test
  void aRealIdentifierIsQuarantinedByTheClosedWorldSeeds() {
    ValidatorFixture f = world();
    // SF15: the published examples are valid identifiers, and no seed knows them
    Exec lei = exec();
    lei.firmLei = REAL_LEI_1;
    ValidationResult executing = f.run(lei);
    assertThat(findings(executing))
        .contains("V09:REF_NOT_FOUND")
        .doesNotContain("V04:LEI_CHECK_DIGITS");
    assertThat(executing.pass()).isFalse();

    Exec client = exec();
    client.clientLei = Optional.of(REAL_LEI_1);
    assertFails(f.run(client), "V10:REF_NOT_FOUND");

    Exec counterparty = exec();
    counterparty.counterparty = Optional.of(REAL_LEI_2);
    assertFails(f.run(counterparty), "V10:REF_NOT_FOUND");

    Exec mic = exec();
    mic.mic = "XNAS";
    assertFails(f.run(mic), "V12:REF_NOT_FOUND");

    Exec isin = exec();
    isin.isin = REAL_ISIN;
    ValidationResult listed = f.run(isin);
    assertThat(listed.pass()).as("an unknown ISIN is flagged, not blocked").isTrue();
    assertThat(findings(listed)).containsExactly("V11:REFDATA_UNKNOWN");
  }

  @Test
  void aMissingDatasetIsADefectNotAPass() {
    ValidatorFixture f = world();
    dev.regpack.inmemory.InMemoryReferenceData empty =
        new dev.regpack.inmemory.InMemoryReferenceData(f.clock);
    Validator v =
        new Validator(
            ValidationRulesTest.fixtureRules(),
            empty,
            f.ledger,
            f.ledger,
            ValidatorFixture.REGIME,
            Leg.MARKET,
            f.clock);
    assertThatThrownBy(
            () -> v.validate(exec().build(), Map.of("executing_lei", SyntheticIds.FIRM_LEI)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reference dataset lei-status is not loaded")
        .hasMessageNotContaining(SyntheticIds.FIRM_LEI);
  }

  @Test
  void theConstructorRefusesAFieldTheModelDoesNotBindOrOfAnotherType() {
    ValidatorFixture f = world();
    for (String bad :
        List.of(
            "check: LEI, args: {fields: [firm.nope]}",
            "check: LEI, args: {fields: [trade.quantity]}",
            "check: DECIMAL_RANGE, args: {fields: [trade.currency]}",
            "check: TIME_NOT_AFTER_RECEIPT, args: {fields: [firm.lei]}",
            "check: REF_KNOWN, args: {dataset: mic, fields: [trade.price]}")) {
      ValidationRules rules =
          ValidationRules.parse(
                  "validate/rules.yaml",
                  StrictYaml.parse(
                      "rules:\n  - {id: X1, layer: L1, severity: BLOCKER, disposition: QUARANTINE, "
                          + bad
                          + ", regulator_id: null}\n"),
                  ProblemSink.forFile())
              .orElseThrow();
      assertThatThrownBy(
              () ->
                  new Validator(
                      rules,
                      f.refs,
                      f.ledger,
                      f.ledger,
                      ValidatorFixture.REGIME,
                      Leg.MARKET,
                      f.clock))
          .as(bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("rule X1");
    }
  }

  @Test
  void everyRuleOfTheContentSheetHasATest() {
    List<String> names = new ArrayList<>();
    for (Method m : ValidatorTest.class.getDeclaredMethods()) {
      names.add(m.getName());
    }
    Pattern pass = Pattern.compile("v%s(Accepts|Allows)[A-Z].*");
    Pattern fail = Pattern.compile("v%s(Rejects|Fails|Flags)[A-Z0-9].*");
    List<Rule> rules = ValidationRulesTest.fixtureRules().rules();
    assertThat(rules).hasSize(15);
    for (Rule rule : rules) {
      String number = rule.id().substring(1);
      Pattern p = Pattern.compile(pass.pattern().formatted(number));
      Pattern q = Pattern.compile(fail.pattern().formatted(number));
      assertThat(names).as("a pass test of " + rule.id()).anyMatch(n -> p.matcher(n).matches());
      assertThat(names).as("a fail test of " + rule.id()).anyMatch(n -> q.matcher(n).matches());
    }
  }
}
