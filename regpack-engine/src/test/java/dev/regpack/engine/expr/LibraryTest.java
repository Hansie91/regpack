package dev.regpack.engine.expr;

import static dev.regpack.engine.expr.ExprTestSupport.bind;
import static dev.regpack.engine.expr.ExprTestSupport.bool;
import static dev.regpack.engine.expr.ExprTestSupport.decimal;
import static dev.regpack.engine.expr.ExprTestSupport.eval;
import static dev.regpack.engine.expr.ExprTestSupport.integer;
import static dev.regpack.engine.expr.ExprTestSupport.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.engine.expr.ExprTestSupport.Recording;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.DateValue;
import dev.regpack.ports.expr.Value.DecimalValue;
import dev.regpack.ports.expr.Value.EnumValue;
import dev.regpack.ports.expr.Value.ListValue;
import dev.regpack.ports.expr.Value.StringValue;
import dev.regpack.ports.expr.Value.TimestampValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class LibraryTest {
  private static void assertError(ErrorKind kind, String source) {
    assertError(kind, source, bind());
  }

  private static void assertError(ErrorKind kind, String source, Recording bindings) {
    assertThatThrownBy(() -> eval(source, bindings))
        .isInstanceOfSatisfying(
            ExprException.class, e -> assertThat(e.kind()).as(source).isEqualTo(kind));
  }

  private static Instant instant(String source) {
    return ((TimestampValue) eval(source)).value();
  }

  @Test
  void decKeepsScaleAndAcceptsInts() {
    assertThat(((DecimalValue) eval("dec('12.50')")).value().scale()).isEqualTo(2);
    assertThat(eval("dec('12.50')")).isEqualTo(decimal("12.50"));
    assertThat(eval("dec('-0.5')")).isEqualTo(decimal("-0.5"));
    assertThat(eval("dec('+7')")).isEqualTo(decimal("7"));
    assertThat(eval("dec(42)")).isEqualTo(decimal("42"));
    assertThat(((DecimalValue) eval("dec(42)")).value().scale()).isZero();
    assertThat(eval("dec(1.50)")).isEqualTo(decimal("1.50"));
    // 38 digits are the most
    assertThat(eval("dec('" + "9".repeat(38) + "')")).isEqualTo(decimal("9".repeat(38)));
    assertThat(eval("dec('0.1') + dec('0.2') == dec('0.3')")).isEqualTo(bool(true));
  }

  @Test
  void decRejectsTextThatIsNotADecimal() {
    for (String bad :
        new String[] {"abc", "1e5", "1E5", "", " 1", "1 ", "1.", ".5", "+", "-", "1,5", "0x10"}) {
      assertError(ErrorKind.PARSE, "dec('" + bad + "')");
    }
    // fullwidth and Arabic-Indic digits are not ASCII digits
    assertError(ErrorKind.PARSE, "dec('１')");
    assertError(ErrorKind.PARSE, "dec('١')");
    assertError(ErrorKind.SCALE, "dec('" + "1".repeat(40) + "')");
    assertError(ErrorKind.SCALE, "dec('0." + "1".repeat(39) + "')");
    assertError(ErrorKind.TYPE, "dec(true)");
    assertError(ErrorKind.TYPE, "dec([1])");
  }

  @Test
  void roundModesAndRange() {
    assertThat(eval("round(dec('2.5'), 0, 'HALF_EVEN')")).isEqualTo(decimal("2"));
    assertThat(eval("round(dec('2.5'), 0, 'HALF_UP')")).isEqualTo(decimal("3"));
    assertThat(eval("round(dec('3.5'), 0, 'HALF_EVEN')")).isEqualTo(decimal("4"));
    assertThat(eval("round(dec('-2.5'), 0, 'HALF_UP')")).isEqualTo(decimal("-3"));
    assertThat(eval("round(dec('1.005'), 2, 'HALF_UP')")).isEqualTo(decimal("1.01"));
    assertThat(((DecimalValue) eval("round(dec('1.005'), 2, 'HALF_UP')")).value().scale())
        .isEqualTo(2);
    // rounding to more places pads the scale
    assertThat(((DecimalValue) eval("round(dec('1.5'), 3, 'HALF_UP')")).value().scale())
        .isEqualTo(3);
    assertThat(eval("round(7, 2, 'HALF_UP')")).isEqualTo(decimal("7.00"));
    assertThat(eval("round(dec('0.5'), 38, 'HALF_UP')")).isInstanceOf(DecimalValue.class);
    // 1.5 with 38 places has 39 digits
    assertError(ErrorKind.SCALE, "round(dec('1.5'), 38, 'HALF_UP')");
    assertError(ErrorKind.RANGE, "round(dec('1.5'), -1, 'HALF_UP')");
    assertError(ErrorKind.RANGE, "round(dec('1.5'), 39, 'HALF_UP')");
    assertError(ErrorKind.OTHER, "round(dec('1.5'), 0, 'HALF_DOWN')");
    assertError(ErrorKind.OTHER, "round(dec('1.5'), 0, 'half_up')");
    assertError(ErrorKind.TYPE, "round(dec('1.5'), 0, 1)");
    assertError(ErrorKind.TYPE, "round(dec('1.5'), dec('1'), 'HALF_UP')");
    assertError(ErrorKind.TYPE, "round('x', 0, 'HALF_UP')");
    // 38 digits of precision with two more places do not fit
    assertError(ErrorKind.SCALE, "round(dec('" + "9".repeat(38) + "'), 2, 'HALF_UP')");
  }

  @Test
  void stringFormsAreCanonical() {
    assertThat(eval("string(dec('12.50'))")).isEqualTo(text("12.5"));
    assertThat(eval("string(dec('0.00'))")).isEqualTo(text("0"));
    assertThat(eval("string(dec('100'))")).isEqualTo(text("100"));
    assertThat(eval("string(dec('1000.000'))")).isEqualTo(text("1000"));
    assertThat(eval("string(dec('-0.10'))")).isEqualTo(text("-0.1"));
    assertThat(eval("string(-5)")).isEqualTo(text("-5"));
    assertThat(eval("string(true)")).isEqualTo(text("true"));
    assertThat(eval("string('abc')")).isEqualTo(text("abc"));
    assertThat(eval("string(date('2026-03-01'))")).isEqualTo(text("2026-03-01"));
    assertThat(eval("string(timestamp('2026-03-01T10:00:00Z'))"))
        .isEqualTo(text("2026-03-01T10:00:00.000000Z"));
    assertThat(eval("string(e)", bind("e", new EnumValue("Action", "NEWT"))))
        .isEqualTo(text("NEWT"));
    assertError(ErrorKind.TYPE, "string([1, 2])");
  }

  @Test
  void intConversions() {
    assertThat(eval("int(7)")).isEqualTo(integer(7));
    assertThat(eval("int(dec('42'))")).isEqualTo(integer(42));
    assertThat(eval("int(dec('42.00'))")).isEqualTo(integer(42));
    assertThat(eval("int(dec('-3'))")).isEqualTo(integer(-3));
    assertThat(eval("int('123')")).isEqualTo(integer(123));
    assertThat(eval("int('-123')")).isEqualTo(integer(-123));
    assertThat(eval("int('9223372036854775807')")).isEqualTo(integer(Long.MAX_VALUE));
    assertThat(eval("int('-9223372036854775808')")).isEqualTo(integer(Long.MIN_VALUE));
    assertError(ErrorKind.LOSSY, "int(dec('1.5'))");
    assertError(ErrorKind.LOSSY, "int(dec('0.001'))");
    assertError(ErrorKind.PARSE, "int('abc')");
    assertError(ErrorKind.PARSE, "int('')");
    assertError(ErrorKind.PARSE, "int('-')");
    assertError(ErrorKind.PARSE, "int('1.5')");
    assertError(ErrorKind.PARSE, "int(' 1')");
    assertError(ErrorKind.PARSE, "int('١')");
    // above 64 bits
    assertError(ErrorKind.OVERFLOW, "int(dec('9223372036854775808'))");
    assertError(ErrorKind.OVERFLOW, "int(dec('-9223372036854775809'))");
    assertError(ErrorKind.OVERFLOW, "int('9223372036854775808')");
    assertError(ErrorKind.TYPE, "int(true)");
    assertError(ErrorKind.TYPE, "int([1])");
  }

  @Test
  void timestampForms() {
    Instant expected = Instant.parse("2026-03-01T10:00:00Z");
    assertThat(instant("timestamp('2026-03-01T10:00:00Z')")).isEqualTo(expected);
    assertThat(instant("timestamp('2026-03-01T12:00:00+02:00')")).isEqualTo(expected);
    assertThat(instant("timestamp('2026-03-01T05:30:00-04:30')")).isEqualTo(expected);
    assertThat(instant("timestamp('2026-03-01T10:00:00.250000Z')"))
        .isEqualTo(expected.plusNanos(250_000_000L));
    assertThat(instant("timestamp('2026-03-01T10:00:00.000001Z')"))
        .isEqualTo(expected.plusNanos(1_000L));
    // trailing zeros beyond a microsecond are not finer than a microsecond
    assertThat(instant("timestamp('2026-03-01T10:00:00.1000000Z')"))
        .isEqualTo(expected.plusNanos(100_000_000L));
    assertThat(eval("timestamp('2026-03-01T10:00:00Z') == timestamp('2026-03-01T12:00:00+02:00')"))
        .isEqualTo(bool(true));
    // seconds and a zone are mandatory
    assertError(ErrorKind.PARSE, "timestamp('2026-03-01T10:00Z')");
    assertError(ErrorKind.PARSE, "timestamp('2026-03-01T10:00:00')");
    assertError(ErrorKind.PARSE, "timestamp('2026-03-01 10:00:00Z')");
    assertError(ErrorKind.PARSE, "timestamp('2026-03-01T10:00:00z')");
    assertError(ErrorKind.PARSE, "timestamp('2026-03-01T10:00:00+0200')");
    assertError(ErrorKind.PARSE, "timestamp('2026-03-01T10:00:00.Z')");
    assertError(ErrorKind.PARSE, "timestamp('')");
    assertError(ErrorKind.PARSE, "timestamp('2026-03-01T10:00:00Z ')");
    assertError(ErrorKind.PARSE, "timestamp('2026-3-1T10:00:00Z')");
    assertError(ErrorKind.PARSE, "timestamp('2026-02-29T10:00:00Z')");
    assertError(ErrorKind.PARSE, "timestamp('2026-13-01T10:00:00Z')");
    assertError(ErrorKind.PARSE, "timestamp('2026-03-01T24:00:00Z')");
    assertError(ErrorKind.PARSE, "timestamp('2026-03-01T10:60:00Z')");
    assertError(ErrorKind.PARSE, "timestamp('2026-03-01T10:00:00+24:00')");
    assertError(ErrorKind.PARSE, "timestamp('2026-03-01T10:00:00+10:60')");
    // a leap second is refused
    assertError(ErrorKind.PARSE, "timestamp('2016-12-31T23:59:60Z')");
    // fullwidth digits are not digits
    assertError(ErrorKind.PARSE, "timestamp('２026-03-01T10:00:00Z')");
    // finer than a microsecond
    assertError(ErrorKind.RANGE, "timestamp('2026-03-01T10:00:00.0000001Z')");
    assertError(ErrorKind.RANGE, "timestamp('2026-03-01T10:00:00.123456789Z')");
    assertError(ErrorKind.TYPE, "timestamp(1)");
  }

  @Test
  void timestampRefusesAnInstantThatIsoMicrosCouldNotRender() {
    // pre-release amendment of library version 1: the normalised UTC instant must lie in the
    // years 0000 to 9999, the range isoMicros and string write
    assertError(ErrorKind.RANGE, "timestamp('0000-01-01T00:00:00+01:00')");
    assertError(ErrorKind.RANGE, "timestamp('9999-12-31T23:59:59-01:00')");
    assertError(ErrorKind.RANGE, "isoMicros(timestamp('0000-01-01T00:00:00+01:00'))");
    // the bounds themselves, reached through an offset, still work and render
    assertThat(instant("timestamp('0000-01-01T01:00:00+01:00')"))
        .isEqualTo(Instant.parse("0000-01-01T00:00:00Z"));
    assertThat(instant("timestamp('9999-12-31T23:59:59Z')"))
        .isEqualTo(Instant.parse("9999-12-31T23:59:59Z"));
    assertThat(instant("timestamp('9999-12-31T22:59:59-01:00')"))
        .isEqualTo(Instant.parse("9999-12-31T23:59:59Z"));
    assertThat(eval("isoMicros(timestamp('0000-01-01T00:00:00Z'))"))
        .isEqualTo(new StringValue("0000-01-01T00:00:00.000000Z"));
    assertThat(eval("string(timestamp('9999-12-31T23:59:59Z'))"))
        .isEqualTo(new StringValue("9999-12-31T23:59:59.000000Z"));
  }

  @Test
  void dateForms() {
    assertThat(eval("date('2026-03-01')")).isEqualTo(new DateValue(LocalDate.of(2026, 3, 1)));
    assertThat(eval("date('2024-02-29')")).isEqualTo(new DateValue(LocalDate.of(2024, 2, 29)));
    assertThat(eval("date('0001-01-01')")).isEqualTo(new DateValue(LocalDate.of(1, 1, 1)));
    assertError(ErrorKind.PARSE, "date('2026-02-29')");
    assertError(ErrorKind.PARSE, "date('2026-13-01')");
    assertError(ErrorKind.PARSE, "date('2026-00-10')");
    assertError(ErrorKind.PARSE, "date('2026-3-1')");
    assertError(ErrorKind.PARSE, "date('20260301')");
    assertError(ErrorKind.PARSE, "date('2026-03-01T00:00:00Z')");
    assertError(ErrorKind.PARSE, "date('+12026-03-01')");
    assertError(ErrorKind.PARSE, "date(' 2026-03-01')");
    assertError(ErrorKind.PARSE, "date('')");
    assertError(ErrorKind.PARSE, "date('٢026-03-01')");
    assertError(ErrorKind.TYPE, "date(20260301)");
    assertThat(eval("date('2026-03-01') < date('2026-03-02')")).isEqualTo(bool(true));
  }

  @Test
  void isoMicrosIsFixedWidth() {
    assertThat(eval("isoMicros(timestamp('2026-03-01T10:00:00Z'))"))
        .isEqualTo(text("2026-03-01T10:00:00.000000Z"));
    assertThat(eval("isoMicros(timestamp('2026-03-01T10:00:00.5Z'))"))
        .isEqualTo(text("2026-03-01T10:00:00.500000Z"));
    assertThat(eval("isoMicros(timestamp('0000-01-01T00:00:00Z'))"))
        .isEqualTo(text("0000-01-01T00:00:00.000000Z"));
    assertThat(eval("isoMicros(timestamp('0042-07-09T03:04:05.000007Z'))"))
        .isEqualTo(text("0042-07-09T03:04:05.000007Z"));
    assertThat(eval("isoMicros(timestamp('9999-12-31T23:59:59.999999Z'))"))
        .isEqualTo(text("9999-12-31T23:59:59.999999Z"));
    // an offset that moves the instant outside year 0000 to 9999 cannot be written
    assertError(ErrorKind.RANGE, "isoMicros(timestamp('0000-01-01T00:00:00+01:00'))");
    Recording far = bind("t", new TimestampValue(Instant.parse("+10000-01-01T00:00:00Z")));
    assertError(ErrorKind.RANGE, "isoMicros(t)", far);
    Recording fine = bind("t", new TimestampValue(Instant.parse("2026-03-01T10:00:00.123456789Z")));
    assertError(ErrorKind.RANGE, "isoMicros(t)", fine);
    assertError(ErrorKind.TYPE, "isoMicros('2026-03-01')");
  }

  @Test
  void dayOfWeekNamesAreEnglishUpperCase() {
    assertThat(eval("dayOfWeek(date('2026-03-01'))")).isEqualTo(text("SUNDAY"));
    assertThat(eval("dayOfWeek(date('2026-03-02'))")).isEqualTo(text("MONDAY"));
    assertThat(eval("dayOfWeek(date('2026-03-03'))")).isEqualTo(text("TUESDAY"));
    assertThat(eval("dayOfWeek(date('2026-03-04'))")).isEqualTo(text("WEDNESDAY"));
    assertThat(eval("dayOfWeek(date('2026-03-05'))")).isEqualTo(text("THURSDAY"));
    assertThat(eval("dayOfWeek(date('2026-03-06'))")).isEqualTo(text("FRIDAY"));
    assertThat(eval("dayOfWeek(date('2026-03-07'))")).isEqualTo(text("SATURDAY"));
    assertError(ErrorKind.TYPE, "dayOfWeek('2026-03-01')");
  }

  @Test
  void upperAndLowerIgnoreTheDefaultLocale() {
    Locale saved = Locale.getDefault();
    try {
      // the test JVM runs under tr_TR; set it explicitly so the test also fails elsewhere if the
      // implementation ever consults the default locale
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertThat(eval("upper('title')")).isEqualTo(text("TITLE"));
      assertThat(eval("lower('TITLE')")).isEqualTo(text("title"));
      assertThat(eval("lower('I')")).isEqualTo(text("i"));
      assertThat(eval("upper('i')")).isEqualTo(text("I"));
    } finally {
      Locale.setDefault(saved);
    }
    // a non-ASCII letter follows the root locale rules
    assertThat(eval("upper('straße')")).isEqualTo(text("STRASSE"));
    assertThat(eval("lower('İ')")).isEqualTo(text("i̇"));
    assertThat(eval("upper('')")).isEqualTo(text(""));
    assertError(ErrorKind.TYPE, "upper(1)");
    assertError(ErrorKind.TYPE, "lower(true)");
  }

  @Test
  void upperThatGrowsPastTheStringLimitIsRange() {
    InHouseExpression layer = new InHouseExpression(new InHouseExpression.Limits(512, 256, 8));
    Recording s = bind("s", text("ßßßßß"));
    // five letters, ten after upper: more than the limit of 8
    assertThatThrownBy(() -> ExprTestSupport.eval(layer, "upper(s)", s))
        .isInstanceOfSatisfying(
            ExprException.class, e -> assertThat(e.kind()).isEqualTo(ErrorKind.RANGE));
    assertThat(ExprTestSupport.eval(layer, "lower(s)", s)).isEqualTo(text("ß".repeat(5)));
  }

  @Test
  void sizeAndContains() {
    assertThat(eval("size('abc')")).isEqualTo(integer(3));
    assertThat(eval("size('')")).isEqualTo(integer(0));
    assertThat(eval("size([1, 2, 3])")).isEqualTo(integer(3));
    assertThat(eval("size('😀😀')")).isEqualTo(integer(2));
    assertError(ErrorKind.TYPE, "size(1)");
    assertThat(eval("contains('hello', 'ell')")).isEqualTo(bool(true));
    assertThat(eval("contains('hello', 'xyz')")).isEqualTo(bool(false));
    assertThat(eval("contains('hello', '')")).isEqualTo(bool(true));
    assertThat(eval("'hello'.contains('lo')")).isEqualTo(bool(true));
    assertThat(eval("contains([1, 2, 3], 2)")).isEqualTo(bool(true));
    assertThat(eval("contains([1, 2, 3], 2.0)")).isEqualTo(bool(true));
    assertThat(eval("contains([1, 2, 3], 4)")).isEqualTo(bool(false));
    assertThat(eval("[1, 2].contains(2)")).isEqualTo(bool(true));
    assertError(ErrorKind.TYPE, "contains('abc', 1)");
    assertError(ErrorKind.TYPE, "contains(1, 2)");
    assertError(ErrorKind.TYPE, "contains([1], 'a')");
  }

  @Test
  void startsWithAndEndsWith() {
    assertThat(eval("'abcdef'.startsWith('abc')")).isEqualTo(bool(true));
    assertThat(eval("'abcdef'.startsWith('bc')")).isEqualTo(bool(false));
    assertThat(eval("'abcdef'.endsWith('def')")).isEqualTo(bool(true));
    assertThat(eval("'abcdef'.endsWith('de')")).isEqualTo(bool(false));
    assertThat(eval("'abc'.startsWith('')")).isEqualTo(bool(true));
    assertThat(eval("''.endsWith('a')")).isEqualTo(bool(false));
    assertThat(eval("'abc'.startsWith('abcd')")).isEqualTo(bool(false));
    assertThat(eval("'ZZ1234'.startsWith(upper('zz'))")).isEqualTo(bool(true));
    assertError(ErrorKind.TYPE, "'abc'.startsWith(1)");
    assertError(ErrorKind.TYPE, "(1).endsWith('a')");
    assertError(ErrorKind.TYPE, "[1].startsWith([1])");
  }

  @Test
  void libraryVersionIsOne() {
    assertThat(Library.VERSION).isEqualTo(1);
  }

  @Test
  void everyLibraryFunctionIsImplemented() {
    // a function in the table that apply() does not know would fail with an unchecked exception
    // outside ExprException; evaluate one call of each with well-typed arguments
    Recording b =
        bind(
            "s", text("abc"),
            "d", new DecimalValue(new BigDecimal("1.5")),
            "l", new ListValue(List.of(integer(1))),
            "dt", new DateValue(LocalDate.of(2026, 3, 1)),
            "ts", new TimestampValue(Instant.parse("2026-03-01T10:00:00Z")));
    List<String> calls =
        List.of(
            "dec('1')",
            "round(d, 1, 'HALF_UP')",
            "string(d)",
            "int(1)",
            "timestamp('2026-03-01T10:00:00Z')",
            "date('2026-03-01')",
            "isoMicros(ts)",
            "dayOfWeek(dt)",
            "upper(s)",
            "lower(s)",
            "size(l)",
            "contains(l, 1)",
            "s.startsWith('a')",
            "s.endsWith('c')");
    assertThat(calls).hasSameSizeAs(Library.values());
    for (String source : calls) {
      assertThat(eval(source, b)).as(source).isNotNull();
    }
    assertThat(eval("upper(s)", b)).isEqualTo(new StringValue("ABC"));
  }

  private static Value decDirect(String s) {
    return Library.DEC.apply(
        new Value[] {text(s)},
        new CostMeter(new CostBudget(ExprTestSupport.BIG)),
        InHouseExpression.Limits.defaults());
  }

  @Test
  void decRefusesAWideTextBeforeBuildingADecimal() {
    // called directly, because the evaluator holds a string input to 4096 code points first; the
    // text is counted from its indices, so a million digits are refused in linear time
    for (String wide :
        List.of(
            "9".repeat(1_000_000),
            "-" + "9".repeat(1_000_000),
            "1." + "9".repeat(1_000_000),
            "9".repeat(500_000) + "." + "9".repeat(500_000),
            "0." + "0".repeat(1_000_000) + "1",
            "9".repeat(39),
            "0.".concat("0".repeat(38)).concat("1"))) {
      assertThatThrownBy(() -> decDirect(wide))
          .isInstanceOfSatisfying(
              ExprException.class,
              e -> {
                assertThat(e.kind()).isEqualTo(ErrorKind.SCALE);
                assertThat(e.getMessage()).hasSizeLessThan(100);
              });
    }
    // leading zeros do not count as precision, a bad character is still a PARSE error
    assertThat(decDirect("0".repeat(5_000) + "1.5")).isEqualTo(decimal("1.5"));
    assertThat(decDirect("0".repeat(5_000))).isEqualTo(decimal("0"));
    assertThat(decDirect("0." + "0".repeat(37) + "1"))
        .isEqualTo(decimal("0." + "0".repeat(37) + "1"));
    assertThatThrownBy(() -> decDirect("9".repeat(1_000_000) + "x"))
        .isInstanceOfSatisfying(
            ExprException.class, e -> assertThat(e.kind()).isEqualTo(ErrorKind.PARSE));
  }
}
