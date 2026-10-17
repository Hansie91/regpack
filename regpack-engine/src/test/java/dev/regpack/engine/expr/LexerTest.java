package dev.regpack.engine.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.regpack.engine.expr.Lexer.Kind;
import dev.regpack.engine.expr.Lexer.Token;
import dev.regpack.ports.expr.Value.DecimalValue;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LexerTest {
  /** Builds hostile text from code points (the formatter rewrites printable escapes). */
  private static String cp(int... codePoints) {
    StringBuilder b = new StringBuilder();
    for (int c : codePoints) {
      b.appendCodePoint(c);
    }
    return b.toString();
  }

  private static void assertParseError(String source) {
    assertThatThrownBy(() -> Lexer.tokenize(source))
        .isInstanceOfSatisfying(
            ExprException.class, e -> assertThat(e.kind()).isEqualTo(ErrorKind.PARSE));
  }

  @Test
  void decimalLiteralKeepsScale() {
    List<Token> tokens = Lexer.tokenize("12.50");
    assertThat(tokens.get(0).kind()).isEqualTo(Kind.DECIMAL);
    assertThat(tokens.get(0).text()).isEqualTo("12.50");
    Ast.Node root = Parser.parse("12.50", InHouseExpression.Limits.defaults(), Set.of()).root();
    assertThat(root).isInstanceOf(Ast.Lit.class);
    DecimalValue value = (DecimalValue) ((Ast.Lit) root).value();
    assertThat(value.value().scale()).isEqualTo(2);
    assertThat(value.value().toPlainString()).isEqualTo("12.50");
  }

  @Test
  void decimalLiteralOverThirtyEightDigitsIsRejected() {
    assertThat(Lexer.tokenize("1." + "0".repeat(37)).get(0).kind()).isEqualTo(Kind.DECIMAL);
    assertParseError("1." + "0".repeat(38));
    assertParseError("1" + "0".repeat(38) + ".5");
  }

  @Test
  void aHostileDecimalLiteralIsRejectedPromptly() {
    String digits = "1".repeat(1_000_000);
    // 1,000,000 digits took about 12 s to reach the rejection when the BigDecimal came first
    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () -> {
          assertParseError(digits + ".5");
          assertParseError("1." + digits);
          assertParseError("0".repeat(1_000_000) + "." + digits);
        });
    // leading zeros are not significant digits: the limit counts precision, as the scale is exact
    assertThat(Lexer.tokenize("0".repeat(100_000) + "1.5").get(0).kind()).isEqualTo(Kind.DECIMAL);
    // and the parser, which builds the BigDecimal, is just as prompt on the same text
    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () -> new InHouseExpression().compile("0".repeat(100_000) + "1.5", Map.of()));
    assertThat(Lexer.tokenize("0." + "0".repeat(37) + "1").get(0).kind()).isEqualTo(Kind.DECIMAL);
    assertParseError("0." + "0".repeat(38) + "1");
    assertThat(Lexer.tokenize("1".repeat(37) + ".0").get(0).kind()).isEqualTo(Kind.DECIMAL);
    assertParseError("1".repeat(38) + ".0");
  }

  @Test
  void integerOutOfRangeIsRejected() {
    assertThat(Lexer.tokenize("9223372036854775807").get(0).kind()).isEqualTo(Kind.INT);
    assertParseError("9223372036854775808");
    assertParseError("99999999999999999999");
  }

  @Test
  void exponentAndHexLiteralsAreRejected() {
    for (String source : List.of("1e5", "1E5", "1.5e3", "0x10", "0X1F", "12abc", "1_000")) {
      assertParseError(source);
    }
  }

  @Test
  void unterminatedStringIsRejected() {
    for (String source : List.of("'abc", "\"abc", "'abc\\", "'a\nb'", "'a\rb'", "'")) {
      assertParseError(source);
    }
  }

  @Test
  void onlyTheListedEscapesAreAccepted() {
    List<Token> tokens = Lexer.tokenize("'\\\\ \\' \\\" \\n \\r \\t'");
    assertThat(tokens.get(0).kind()).isEqualTo(Kind.STRING);
    assertThat(tokens.get(0).text()).isEqualTo("\\ ' \" \n \r \t");
    assertThat(Lexer.tokenize("\"it's\"").get(0).text()).isEqualTo("it's");
    for (String source : List.of("'\\x'", "'\\u0041'", "'\\0'", "'\\/'", "'\\a'")) {
      assertParseError(source);
    }
  }

  @Test
  void nonAsciiIdentifierIsRejected() {
    String dottedCapitalI = cp(0x130);
    assertParseError(dottedCapitalI);
    assertParseError("a" + dottedCapitalI);
    assertParseError("tit" + cp(0x131) + "le");
    assertParseError(cp(0xFF21) + "BC");
  }

  @Test
  void nonAsciiTextInsideAStringIsAccepted() {
    String text = "caf" + cp(0xE9) + " " + cp(0x65E5, 0x672C) + " " + cp(0x1F600);
    List<Token> tokens = Lexer.tokenize("'" + text + "'");
    assertThat(tokens.get(0).kind()).isEqualTo(Kind.STRING);
    assertThat(tokens.get(0).text()).isEqualTo(text);
  }

  @Test
  void whitespaceIsAsciiOnly() {
    List<Token> tokens = Lexer.tokenize("1\t+\r\n 2");
    assertThat(tokens).extracting(Token::text).containsExactly("1", "+", "2", "");
    assertParseError("1" + cp(0xA0) + "+ 2");
    assertParseError("1 " + cp(0x2003) + "+ 2");
    assertParseError("1 " + cp(0xFEFF) + "+ 2");
    assertParseError("1" + cp(0x0B) + "+ 2");
  }

  @Test
  void assignmentAndSingleOperatorsAreRefused() {
    for (String source : List.of("a = 1", "a & b", "a | b", "a # b", "a @ b", "a ; b", "{")) {
      assertParseError(source);
    }
  }

  @Test
  void everyOperatorIsRecognised() {
    List<Token> tokens = Lexer.tokenize("+ - * / ( ) [ ] , . ? : ! != == < <= > >= && ||");
    assertThat(tokens)
        .extracting(Token::text)
        .containsExactly(
            "+", "-", "*", "/", "(", ")", "[", "]", ",", ".", "?", ":", "!", "!=", "==", "<", "<=",
            ">", ">=", "&&", "||", "");
    assertThat(tokens.get(tokens.size() - 1).kind()).isEqualTo(Kind.EOF);
  }
}
