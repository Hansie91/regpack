package dev.regpack.engine.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.expr.ExpressionException;
import org.junit.jupiter.api.Test;

class ExprExceptionTest {
  @Test
  void carriesAKindAndNoStackTrace() {
    ExprException e = new ExprException(ErrorKind.DIV_ZERO, "division by zero");
    assertThat(e.kind()).isEqualTo(ErrorKind.DIV_ZERO);
    assertThat(e.getMessage()).isEqualTo("division by zero");
    assertThat(e.getStackTrace()).isEmpty();
    assertThat(e).isInstanceOf(ExpressionException.class);
    assertThatThrownBy(() -> new ExprException(null, "x")).isInstanceOf(NullPointerException.class);
  }
}
