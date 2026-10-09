package dev.regpack.model.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.regpack.model.Outcome;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class JsonTest {
  private static final String BACKSLASH = "\\";
  private static final String E_ACUTE = String.valueOf((char) 0xE9);
  private static final String CTRL_1F = String.valueOf((char) 0x1F);

  @Test
  void canonicalOrdersKeysAndStripsDecimals() {
    Object parsed = Json.parse("{\"b\": 1.10, \"a\": \"x\", \"c\": [true, 0.00, 100E-2]}");
    assertEquals("{\"a\":\"x\",\"b\":1.1,\"c\":[true,0,1]}", Json.canonical(parsed));
  }

  @Test
  void canonicalOfJavaValues() {
    Map<String, Object> m = new TreeMap<>(Comparator.reverseOrder());
    m.put("n", new BigDecimal("1E+3"));
    m.put("t", Instant.parse("2026-10-09T10:15:30.123456Z"));
    m.put("d", LocalDate.of(2026, 10, 9));
    m.put("e", Outcome.REPORT);
    assertEquals(
        "{\"d\":\"2026-10-09\",\"e\":\"REPORT\",\"n\":1000,\"t\":\"2026-10-09T10:15:30.123456Z\"}",
        Json.canonical(m));
  }

  @Test
  void instantsHaveFixedMicrosecondForm() {
    assertEquals(
        "\"2026-10-09T10:15:30.000000Z\"", Json.canonical(Instant.parse("2026-10-09T10:15:30Z")));
    Instant subMicro = Instant.parse("2026-10-09T10:15:30.123456789Z");
    assertThrows(IllegalArgumentException.class, () -> Json.canonical(subMicro));
  }

  @Test
  void rejectsNullLiteral() {
    JsonException e = assertThrows(JsonException.class, () -> Json.parse("{\"a\":null}"));
    assertTrue(e.getMessage().contains("null"), e.getMessage());
  }

  @Test
  void rejectsDuplicateKeys() {
    assertThrows(JsonException.class, () -> Json.parse("{\"a\":1,\"a\":2}"));
  }

  @Test
  void rejectsFloatingPoint() {
    assertThrows(IllegalArgumentException.class, () -> Json.canonical(1.5d));
    assertThrows(IllegalArgumentException.class, () -> Json.canonical(1.5f));
  }

  @Test
  void rejectsTrailingGarbageAndLeadingZeros() {
    assertThrows(JsonException.class, () -> Json.parse("{} x"));
    assertThrows(JsonException.class, () -> Json.parse("[1] [2]"));
    assertThrows(JsonException.class, () -> Json.parse("[01]"));
    assertThrows(JsonException.class, () -> Json.parse("[NaN]"));
    assertThrows(JsonException.class, () -> Json.parse("[9223372036854775808]"));
  }

  @Test
  void integersParseAsLongAndOthersAsBigDecimal() {
    assertEquals(List.of(5L, new BigDecimal("5.0")), Json.parse("[5, 5.0]"));
  }

  @Test
  void parsesStringEscapes() {
    // JSON text: "a<bs>u00e9<bs>n<bs>""  where <bs> is a backslash
    String json = "\"a" + BACKSLASH + "u00e9" + BACKSLASH + "n" + BACKSLASH + "\"\"";
    assertEquals("a" + E_ACUTE + "\n\"", Json.parse(json));
  }

  @Test
  void unicodeIsEmittedRawAndControlCharsEscaped() {
    // The test JVM runs under tr_TR (parent POM test.argLine); no locale is set here.
    assertEquals("tr", Locale.getDefault().getLanguage());
    String expected = "\"" + E_ACUTE + BACKSLASH + "u001f" + "\"";
    assertEquals(expected, Json.canonical(E_ACUTE + CTRL_1F));
  }

  @Test
  void ndjsonSkipsBlankLinesAndReportsLineNumber() {
    assertEquals(2, Json.parseNdjson("{\"a\":1}\n\n{\"a\":2}\n").size());
    JsonException e =
        assertThrows(JsonException.class, () -> Json.parseNdjson("{\"a\":1}\n{bad}\n"));
    assertTrue(e.getMessage().contains("line 2"), e.getMessage());
  }

  @Test
  void canonicalEscapesQuoteBackslashAndNamedControls() {
    String q = "\"";
    String input =
        q + BACKSLASH + (char) 0x0A + (char) 0x0D + (char) 0x09 + (char) 0x08 + (char) 0x0C;
    String expected =
        q
            + (BACKSLASH + q)
            + (BACKSLASH + BACKSLASH)
            + (BACKSLASH + "n")
            + (BACKSLASH + "r")
            + (BACKSLASH + "t")
            + (BACKSLASH + "b")
            + (BACKSLASH + "f")
            + q;
    assertEquals(expected, Json.canonical(input));
    assertEquals(input, Json.parse(Json.canonical(input)));
  }

  @Test
  void rejectsRawControlCharacterInString() {
    assertThrows(JsonException.class, () -> Json.parse("\"" + (char) 0x0A + "\""));
    assertThrows(JsonException.class, () -> Json.parse("\"" + (char) 0x1F + "\""));
  }

  @Test
  void canonicalOfIntegralTypesAndRejectsUnsupported() {
    List<Object> values =
        List.of(
            Integer.valueOf(-7),
            Short.valueOf((short) 8),
            Byte.valueOf((byte) -9),
            new BigInteger("123456789012345678901234567890"));
    assertEquals("[-7,8,-9,123456789012345678901234567890]", Json.canonical(values));
    assertThrows(IllegalArgumentException.class, () -> Json.canonical(Optional.of(1)));
    List<Object> withNull = new ArrayList<>();
    withNull.add(null);
    assertThrows(IllegalArgumentException.class, () -> Json.canonical(withNull));
  }

  @Test
  void deepNestingIsRejected() {
    assertTrue(Json.parse("[".repeat(512) + "]".repeat(512)) instanceof List);
    JsonException e =
        assertThrows(JsonException.class, () -> Json.parse("[".repeat(513) + "]".repeat(513)));
    assertTrue(e.getMessage().contains("nesting"), e.getMessage());
  }

  @Test
  void hugeExponentsAreRejectedAtParseTime() {
    JsonException e = assertThrows(JsonException.class, () -> Json.parse("[1E+999999999]"));
    assertTrue(e.getMessage().contains("out of range"), e.getMessage());
    assertThrows(JsonException.class, () -> Json.parse("[1E-999999999]"));
    assertEquals("[1" + "0".repeat(1000) + "]", Json.canonical(Json.parse("[1E+1000]")));
  }

  @Test
  void unpairedSurrogatesAreRejected() {
    String lone = String.valueOf((char) 0xD800);
    assertThrows(IllegalArgumentException.class, () -> Json.canonical(lone));
    assertThrows(JsonException.class, () -> Json.parse("\"" + lone + "\""));
    assertThrows(JsonException.class, () -> Json.parse("\"" + BACKSLASH + "ud800\""));
    assertThrows(JsonException.class, () -> Json.parse("\"" + BACKSLASH + "udc00\""));
    String pair = new String(Character.toChars(0x1F600));
    assertEquals("\"" + pair + "\"", Json.canonical(pair));
    assertEquals(pair, Json.parse("\"" + BACKSLASH + "ud83d" + BACKSLASH + "ude00\""));
  }

  @Test
  void instantsOutsideYearRangeAreRejected() {
    Instant tooLate = Instant.parse("+10000-01-01T00:00:00Z");
    Instant tooEarly = Instant.parse("-0001-01-01T00:00:00Z");
    assertThrows(IllegalArgumentException.class, () -> Json.canonical(tooLate));
    assertThrows(IllegalArgumentException.class, () -> Json.canonical(tooEarly));
  }
}
