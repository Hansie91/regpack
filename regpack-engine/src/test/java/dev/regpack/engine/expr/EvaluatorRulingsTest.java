package dev.regpack.engine.expr;

import static dev.regpack.engine.expr.ExprTestSupport.bind;
import static dev.regpack.engine.expr.ExprTestSupport.bool;
import static dev.regpack.engine.expr.ExprTestSupport.eval;
import static dev.regpack.engine.expr.ExprTestSupport.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.engine.expr.ExprTestSupport.Recording;
import dev.regpack.engine.expr.InHouseExpression.Limits;
import dev.regpack.engine.expr.Rulings.AndLeftError;
import dev.regpack.engine.expr.Rulings.HasBelowAbsent;
import dev.regpack.engine.expr.Rulings.IntDecimalMix;
import dev.regpack.engine.expr.Rulings.OrLeftError;
import org.junit.jupiter.api.Test;

/**
 * One test per ruling switch (DECIDE vectors D13, A04, E06, A07): the provisional value and the
 * alternative.
 */
class EvaluatorRulingsTest {
  private static InHouseExpression with(Rulings rulings) {
    return new InHouseExpression(Limits.defaults(), rulings);
  }

  private static void assertError(
      InHouseExpression layer,
      ErrorKind kind,
      String source,
      Recording bindings,
      String... declared) {
    assertThatThrownBy(() -> eval(layer, source, bindings, declared))
        .isInstanceOfSatisfying(
            ExprException.class, e -> assertThat(e.kind()).as(source).isEqualTo(kind));
  }

  @Test
  void provisionalRulingsAreTheInHouseOutcomes() {
    Rulings provisional = Rulings.provisional();
    assertThat(provisional.intDecimalMix()).isEqualTo(IntDecimalMix.ALLOW);
    assertThat(provisional.andLeftError()).isEqualTo(AndLeftError.PROPAGATE);
    assertThat(provisional.orLeftError()).isEqualTo(OrLeftError.PROPAGATE);
    assertThat(provisional.hasBelowAbsent()).isEqualTo(HasBelowAbsent.FALSE);
    assertThatThrownBy(
            () -> new Rulings(null, AndLeftError.ABSORB, OrLeftError.ABSORB, HasBelowAbsent.FALSE))
        .isInstanceOf(NullPointerException.class);
    // the layer without an explicit ruling uses the provisional one
    assertThat(eval(new InHouseExpression(), "1 + 0.5 == 1.5", bind())).isEqualTo(bool(true));
  }

  @Test
  void d13IntDecimalMix() {
    String mixed = "1 + 0.5 == 1.5";
    assertThat(eval(with(Rulings.provisional()), mixed, bind())).isEqualTo(bool(true));
    Rulings deny =
        new Rulings(
            IntDecimalMix.DENY,
            AndLeftError.PROPAGATE,
            OrLeftError.PROPAGATE,
            HasBelowAbsent.FALSE);
    InHouseExpression layer = with(deny);
    for (String source : new String[] {"1 + 0.5", "0.5 - 1", "2 * 0.5", "1 / 0.5", "0.5 / 2"}) {
      assertError(layer, ErrorKind.TYPE, source, bind());
    }
    // same-type arithmetic and numeric equality or ordering are unaffected
    assertThat(eval(layer, "1 + 2 == 3 && 0.5 + 0.5 == 1.0", bind())).isEqualTo(bool(true));
    assertThat(eval(layer, "1 == 1.0 && 1 < 1.5", bind())).isEqualTo(bool(true));
  }

  @Test
  void a04AndLeftError() {
    String source = "event.venue == 'XPAR' && has(event.venue)";
    Recording absent = bind();
    assertError(with(Rulings.provisional()), ErrorKind.ABSENT, source, absent, "event.venue");
    InHouseExpression absorb =
        with(
            new Rulings(
                IntDecimalMix.ALLOW,
                AndLeftError.ABSORB,
                OrLeftError.PROPAGATE,
                HasBelowAbsent.FALSE));
    assertThat(eval(absorb, source, bind(), "event.venue")).isEqualTo(bool(false));
    // absorbing needs a right operand that decides: true, an error or a non-bool keep the error
    assertError(absorb, ErrorKind.ABSENT, "event.venue == 'XPAR' && true", bind(), "event.venue");
    assertError(
        absorb, ErrorKind.ABSENT, "event.venue == 'XPAR' && 1 / 0 == 1", bind(), "event.venue");
    assertError(absorb, ErrorKind.ABSENT, "event.venue == 'XPAR' && 1", bind(), "event.venue");
    // the right operand is not read when the left decided without an error
    assertThat(eval(absorb, "false && 1 / 0 == 1", bind())).isEqualTo(bool(false));
    // a non-bool left operand is not an evaluation error: it stays a type error
    assertError(absorb, ErrorKind.TYPE, "1 && false", bind());
    // the other operator is not affected
    assertError(absorb, ErrorKind.DIV_ZERO, "(1 / 0 == 1) || true", bind());
  }

  @Test
  void e06OrLeftError() {
    String source = "(1 / 0 == 1) || true";
    assertError(with(Rulings.provisional()), ErrorKind.DIV_ZERO, source, bind());
    InHouseExpression absorb =
        with(
            new Rulings(
                IntDecimalMix.ALLOW,
                AndLeftError.PROPAGATE,
                OrLeftError.ABSORB,
                HasBelowAbsent.FALSE));
    assertThat(eval(absorb, source, bind())).isEqualTo(bool(true));
    assertError(absorb, ErrorKind.DIV_ZERO, "(1 / 0 == 1) || false", bind());
    assertError(absorb, ErrorKind.DIV_ZERO, "(1 / 0 == 1) || 1 / 0 == 1", bind());
    assertThat(eval(absorb, "true || 1 / 0 == 1", bind())).isEqualTo(bool(true));
    assertError(absorb, ErrorKind.DIV_ZERO, "(1 / 0 == 1) && false", bind());
  }

  @Test
  void a07HasBelowAbsent() {
    String source = "has(client.parent.lei)";
    String[] declared = {"client.parent", "client.parent.lei", "client.kind"};
    Recording kindOnly = bind("client.kind", text("NATURAL_PERSON"));
    assertThat(eval(with(Rulings.provisional()), source, kindOnly, declared))
        .isEqualTo(bool(false));
    InHouseExpression strict =
        with(
            new Rulings(
                IntDecimalMix.ALLOW,
                AndLeftError.PROPAGATE,
                OrLeftError.PROPAGATE,
                HasBelowAbsent.ERROR_IF_ANCESTOR_ABSENT));
    assertError(strict, ErrorKind.ABSENT, source, kindOnly, declared);
    // an ancestor that is bound: the answer is the plain one
    Recording parentBound = bind("client.parent", text("P"));
    assertThat(eval(strict, source, parentBound, "client.parent.lei")).isEqualTo(bool(false));
    Recording bothBound = bind("client.parent", text("P"), "client.parent.lei", text("L"));
    assertThat(eval(strict, source, bothBound)).isEqualTo(bool(true));
    // an ancestor that is not declared is not looked at; so is a path with no ancestor
    assertThat(eval(strict, source, bind(), "client.parent.lei")).isEqualTo(bool(false));
    assertThat(eval(strict, "has(client.kind)", bind(), "client.kind")).isEqualTo(bool(false));
    // only a strict dotted prefix counts, not a path that shares a leading text
    assertThat(
            eval(strict, "has(client.parentx.lei)", bind(), "client.parent", "client.parentx.lei"))
        .isEqualTo(bool(false));
  }
}
