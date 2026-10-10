package dev.regpack.spikes.r.inhouse;

import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.DecimalValue;
import dev.regpack.ports.expr.Value.IntValue;
import dev.regpack.ports.expr.Value.ListValue;
import dev.regpack.ports.expr.Value.StringValue;
import dev.regpack.spikes.r.inhouse.Ast.BinOp;
import dev.regpack.spikes.r.inhouse.Ast.Node;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Tree-walking evaluator over the P0 {@link Value} types (cost model v1: one unit per node
 * evaluated, charged before the node does its work; surcharges are in {@link Library} and here).
 * {@code &&} and {@code ||} short-circuit left to right and an error on the left is never absorbed.
 * A path that is not bound is an ABSENT error unless guarded by {@code has}.
 */
final class Evaluator {
  private static final BoolValue TRUE = new BoolValue(true);
  private static final BoolValue FALSE = new BoolValue(false);

  private Evaluator() {}

  static Value eval(Node node, Bindings bindings, CostMeter meter) {
    meter.charge(1);
    return switch (node) {
      case Ast.Lit lit -> lit.value();
      case Ast.Path p ->
          bindings
              .get(p.path())
              .orElseThrow(() -> Library.error("ABSENT", "unbound path " + p.path()));
      case Ast.Has h -> bindings.get(h.path()).isPresent() ? TRUE : FALSE;
      case Ast.ListLit l -> {
        List<Value> items = new ArrayList<>(l.items().size());
        for (Node item : l.items()) {
          items.add(eval(item, bindings, meter));
        }
        yield new ListValue(items);
      }
      case Ast.Unary u -> unary(u, eval(u.operand(), bindings, meter));
      case Ast.Ternary t -> {
        if (bool(eval(t.cond(), bindings, meter))) {
          yield eval(t.whenTrue(), bindings, meter);
        }
        yield eval(t.whenFalse(), bindings, meter);
      }
      case Ast.Call c -> {
        Value[] args = new Value[c.args().size()];
        for (int i = 0; i < args.length; i++) {
          args[i] = eval(c.args().get(i), bindings, meter);
        }
        yield Library.apply(c.fn(), args, meter);
      }
      case Ast.Binary b -> binary(b, bindings, meter);
    };
  }

  private static boolean bool(Value v) {
    if (v instanceof BoolValue(boolean b)) {
      return b;
    }
    throw Library.error("TYPE", "expected a bool");
  }

  private static Value unary(Ast.Unary u, Value operand) {
    return switch (u.op()) {
      case NOT -> bool(operand) ? FALSE : TRUE;
      case NEG -> {
        if (operand instanceof IntValue(long n)) {
          if (n == Long.MIN_VALUE) {
            throw Library.error("OVERFLOW", "integer overflow");
          }
          yield new IntValue(-n);
        }
        if (operand instanceof DecimalValue(BigDecimal d)) {
          yield new DecimalValue(d.negate());
        }
        throw Library.error("TYPE", "cannot negate this type");
      }
    };
  }

  private static Value binary(Ast.Binary b, Bindings bindings, CostMeter meter) {
    BinOp op = b.op();
    if (op == BinOp.AND) {
      if (!bool(eval(b.left(), bindings, meter))) {
        return FALSE;
      }
      return bool(eval(b.right(), bindings, meter)) ? TRUE : FALSE;
    }
    if (op == BinOp.OR) {
      if (bool(eval(b.left(), bindings, meter))) {
        return TRUE;
      }
      return bool(eval(b.right(), bindings, meter)) ? TRUE : FALSE;
    }
    Value l = eval(b.left(), bindings, meter);
    Value r = eval(b.right(), bindings, meter);
    return switch (op) {
      case ADD -> add(l, r, meter);
      case SUB -> sub(l, r);
      case MUL -> mul(l, r);
      case DIV -> div(l, r, meter);
      case EQ -> Library.equal(l, r) ? TRUE : FALSE;
      case NE -> Library.equal(l, r) ? FALSE : TRUE;
      case LT -> Library.compare(l, r) < 0 ? TRUE : FALSE;
      case LE -> Library.compare(l, r) <= 0 ? TRUE : FALSE;
      case GT -> Library.compare(l, r) > 0 ? TRUE : FALSE;
      case GE -> Library.compare(l, r) >= 0 ? TRUE : FALSE;
      case IN -> in(l, r, meter);
      case AND, OR -> throw new IllegalStateException("handled above");
    };
  }

  private static boolean numeric(Value v) {
    return v instanceof IntValue || v instanceof DecimalValue;
  }

  private static Value overflow() {
    throw Library.error("OVERFLOW", "integer overflow");
  }

  private static Value add(Value l, Value r, CostMeter meter) {
    if (l instanceof IntValue(long a) && r instanceof IntValue(long b)) {
      long sum = a + b;
      if (((a ^ sum) & (b ^ sum)) < 0) {
        return overflow();
      }
      return new IntValue(sum);
    }
    if (numeric(l) && numeric(r)) {
      return new DecimalValue(Library.checked(Library.decimalOf(l).add(Library.decimalOf(r))));
    }
    if (l instanceof StringValue(String a) && r instanceof StringValue(String b)) {
      meter.charge(
          (a.codePointCount(0, a.length()) + (long) b.codePointCount(0, b.length())) / 256);
      return new StringValue(a + b);
    }
    if (l instanceof ListValue(List<Value> a) && r instanceof ListValue(List<Value> b)) {
      meter.charge((long) a.size() + b.size());
      List<Value> joined = new ArrayList<>(a.size() + b.size());
      joined.addAll(a);
      joined.addAll(b);
      return new ListValue(joined);
    }
    throw Library.error("TYPE", "cannot add these types");
  }

  private static Value sub(Value l, Value r) {
    if (l instanceof IntValue(long a) && r instanceof IntValue(long b)) {
      long diff = a - b;
      if (((a ^ b) & (a ^ diff)) < 0) {
        return overflow();
      }
      return new IntValue(diff);
    }
    if (numeric(l) && numeric(r)) {
      return new DecimalValue(Library.checked(Library.decimalOf(l).subtract(Library.decimalOf(r))));
    }
    throw Library.error("TYPE", "cannot subtract these types");
  }

  private static Value mul(Value l, Value r) {
    if (l instanceof IntValue(long a) && r instanceof IntValue(long b)) {
      long hi = Math.multiplyHigh(a, b);
      long lo = a * b;
      if ((hi == 0 && lo >= 0) || (hi == -1 && lo < 0)) {
        return new IntValue(lo);
      }
      return overflow();
    }
    if (numeric(l) && numeric(r)) {
      return new DecimalValue(Library.checked(Library.decimalOf(l).multiply(Library.decimalOf(r))));
    }
    throw Library.error("TYPE", "cannot multiply these types");
  }

  private static Value div(Value l, Value r, CostMeter meter) {
    if (l instanceof IntValue(long a) && r instanceof IntValue(long b)) {
      if (b == 0) {
        throw Library.error("DIV_ZERO", "division by zero");
      }
      if (a == Long.MIN_VALUE && b == -1) {
        return overflow();
      }
      return new IntValue(a / b);
    }
    if (numeric(l) && numeric(r)) {
      meter.charge(1);
      BigDecimal divisor = Library.decimalOf(r);
      if (divisor.signum() == 0) {
        throw Library.error("DIV_ZERO", "division by zero");
      }
      try {
        return new DecimalValue(Library.checked(Library.decimalOf(l).divide(divisor)));
      } catch (ArithmeticException e) {
        throw Library.error("NON_TERMINATING", "the quotient has no finite decimal expansion");
      }
    }
    throw Library.error("TYPE", "cannot divide these types");
  }

  private static Value in(Value needle, Value haystack, CostMeter meter) {
    if (!(haystack instanceof ListValue(List<Value> items))) {
      throw Library.error("TYPE", "'in' needs a list on the right");
    }
    meter.charge(items.size());
    for (Value item : items) {
      if (Library.equal(needle, item)) {
        return TRUE;
      }
    }
    return FALSE;
  }
}
