package dev.regpack.spikes.r.inhouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.expr.ExpressionException;
import dev.regpack.ports.expr.Value;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InHouseLexerTest {
  @Test
  void decimalLiteralKeepsScale() {
    List<Lexer.Token> tokens = Lexer.tokenize("12.50");
    assertThat(tokens.get(0).kind()).isEqualTo(Lexer.Kind.DECIMAL);
    assertThat(tokens.get(0).text()).isEqualTo("12.50");
    assertThat(new BigDecimal(tokens.get(0).text()).scale()).isEqualTo(2);

    Value value =
        new InHouseExpression()
            .compile("12.50", Map.of())
            .evaluate(
                path -> java.util.Optional.empty(),
                new dev.regpack.ports.exec.CostMeter(new dev.regpack.ports.exec.CostBudget(100)));
    assertThat(value).isInstanceOf(Value.DecimalValue.class);
    assertThat(((Value.DecimalValue) value).value().scale()).isEqualTo(2);
  }

  @Test
  void rejectsExponentLiterals() {
    assertThatThrownBy(() -> Lexer.tokenize("1e3"))
        .isInstanceOf(ExpressionException.class)
        .hasMessageContaining("exponent");
    assertThatThrownBy(() -> Lexer.tokenize("1.5E3")).isInstanceOf(ExpressionException.class);
    assertThatThrownBy(() -> new InHouseExpression().compile("1e3", Map.of()))
        .isInstanceOf(ExpressionException.class);
  }

  @Test
  void rejectsUnterminatedString() {
    assertThatThrownBy(() -> Lexer.tokenize("'abc"))
        .isInstanceOf(ExpressionException.class)
        .hasMessageContaining("unterminated");
    assertThatThrownBy(() -> Lexer.tokenize("\"abc'")).isInstanceOf(ExpressionException.class);
  }

  @Test
  void integerLiteralsMustFitInSixtyFourBits() {
    assertThat(Lexer.tokenize("9223372036854775807").get(0).kind()).isEqualTo(Lexer.Kind.INT);
    assertThatThrownBy(() -> Lexer.tokenize("9223372036854775808"))
        .isInstanceOf(ExpressionException.class);
  }

  @Test
  void tokenizesOperatorsPathsAndEscapes() {
    List<Lexer.Token> tokens = Lexer.tokenize("a.b >= 'x\\'y' && !c ? 1 : -2");
    assertThat(tokens).extracting(Lexer.Token::kind).contains(Lexer.Kind.STRING, Lexer.Kind.IDENT);
    assertThat(tokens)
        .filteredOn(t -> t.kind() == Lexer.Kind.STRING)
        .extracting(Lexer.Token::text)
        .containsExactly("x'y");
    assertThatThrownBy(() -> Lexer.tokenize("'a\\qb'")).isInstanceOf(ExpressionException.class);
    assertThatThrownBy(() -> Lexer.tokenize("a # b")).isInstanceOf(ExpressionException.class);
    assertThatThrownBy(() -> Lexer.tokenize("a & b")).isInstanceOf(ExpressionException.class);
  }
}
