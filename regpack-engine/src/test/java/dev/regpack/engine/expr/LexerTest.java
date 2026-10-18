package dev.regpack.engine.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.engine.expr.Lexer.Kind;
import dev.regpack.engine.expr.Lexer.Token;
import dev.regpack.ports.expr.Value.DecimalValue;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
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

  /**
   * The rejection of a hostile decimal literal must come from the linear pre-check on the source
   * indices, never from a {@code BigDecimal} built first (quadratic time: about 12 s for a million
   * digits). That is a property of the code, so it is asserted on the code, not on a clock: the
   * lexer class refers to no {@code BigDecimal} at all, the error is the digit-limit error at the
   * start of the literal, and the answer to a literal that would take minutes to build is a plain
   * return.
   */
  @Test
  void aHostileDecimalLiteralIsRejectedByTheLinearPreCheck() throws IOException {
    assertThat(classReferences("Lexer.class", "java/math/BigDecimal"))
        .as("the lexer builds no BigDecimal, so a hostile literal cannot cost more than its length")
        .isFalse();
    assertThat(classReferences("Parser.class", "java/math/BigDecimal"))
        .as("the probe finds a BigDecimal where one is built (the parser, after the lexer's check)")
        .isTrue();

    String digits = "1".repeat(1_000_000);
    for (String source :
        List.of(digits + ".5", "1." + digits, "0".repeat(1_000_000) + "." + digits)) {
      assertThatThrownBy(() -> Lexer.tokenize(source))
          .isInstanceOfSatisfying(
              ExprException.class,
              e -> {
                assertThat(e.kind()).isEqualTo(ErrorKind.PARSE);
                assertThat(e.getMessage()).contains("decimal literal exceeds 38 digits");
                assertThat(e.getMessage().length())
                    .as("the message holds no literal")
                    .isLessThan(200);
              });
    }
    // leading zeros are not significant digits: the limit counts precision, as the scale is exact
    assertThat(Lexer.tokenize("0".repeat(100_000) + "1.5").get(0).kind()).isEqualTo(Kind.DECIMAL);
    // and the parser, which builds the BigDecimal, accepts the same text
    assertThat(new InHouseExpression().compile("0".repeat(100_000) + "1.5", Map.of())).isNotNull();
    assertThat(Lexer.tokenize("0." + "0".repeat(37) + "1").get(0).kind()).isEqualTo(Kind.DECIMAL);
    assertParseError("0." + "0".repeat(38) + "1");
    assertThat(Lexer.tokenize("1".repeat(37) + ".0").get(0).kind()).isEqualTo(Kind.DECIMAL);
    assertParseError("1".repeat(38) + ".0");
  }

  /** Whether the constant pool of a class of this package refers to the named class. */
  private static boolean classReferences(String classFile, String internalName) throws IOException {
    try (InputStream in = Lexer.class.getResourceAsStream(classFile)) {
      assertThat(in).as(classFile).isNotNull();
      ClassModel model = ClassFile.of().parse(in.readAllBytes());
      for (PoolEntry entry : model.constantPool()) {
        if (entry instanceof ClassEntry c && c.asInternalName().equals(internalName)) {
          return true;
        }
      }
      return false;
    }
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
