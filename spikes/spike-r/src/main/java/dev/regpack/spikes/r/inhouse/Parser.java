package dev.regpack.spikes.r.inhouse;

import dev.regpack.ports.expr.ExpressionException;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.DecimalValue;
import dev.regpack.ports.expr.Value.IntValue;
import dev.regpack.ports.expr.Value.StringValue;
import dev.regpack.spikes.r.inhouse.Ast.BinOp;
import dev.regpack.spikes.r.inhouse.Ast.Node;
import dev.regpack.spikes.r.inhouse.Ast.UnOp;
import dev.regpack.spikes.r.inhouse.Lexer.Kind;
import dev.regpack.spikes.r.inhouse.Lexer.Token;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Recursive-descent parser. Grammar, lowest precedence first: ternary, ||, &&, relations (== !=
 * &lt; &lt;= &gt; &gt;= in, left-associative), + -, * /, unary ! -, postfix (method call), primary.
 * The per-rule ceiling (the node count) is enforced while parsing, so a hostile source never builds
 * a large tree. Rejected on purpose: the null literal, loop macros (all, exists, exists_one, map,
 * filter), exponent literals, user functions.
 */
final class Parser {
  /** Deepest syntactic nesting (parentheses, lists, arguments, unary chains, ternaries). */
  private static final int MAX_NESTING = 100;

  /** Deepest tree; long left-associative chains are the usual way to reach it. */
  private static final int MAX_DEPTH = 1_000;

  private static final Set<String> LOOP_MACROS =
      Set.of("all", "exists", "exists_one", "map", "filter");

  private final List<Token> tokens;
  private final long ceiling;
  private int pos;
  private int nesting;
  private long nodes;

  private Parser(List<Token> tokens, long ceiling) {
    this.tokens = tokens;
    this.ceiling = ceiling;
  }

  /** The tree and its node count. */
  record Parsed(Node root, long nodeCount) {}

  static Parsed parse(String source, long ceiling) {
    Parser p = new Parser(Lexer.tokenize(source), ceiling);
    if (p.peek().kind() == Kind.EOF) {
      throw new ExpressionException("empty expression");
    }
    Node root = p.expression();
    if (p.peek().kind() != Kind.EOF) {
      throw p.unexpected("end of the expression");
    }
    return new Parsed(root, p.nodes);
  }

  // ---- helpers ------------------------------------------------------------------------------

  private Token peek() {
    return tokens.get(pos);
  }

  private Token peekAt(int offset) {
    return tokens.get(Math.min(pos + offset, tokens.size() - 1));
  }

  private Token next() {
    Token t = tokens.get(pos);
    if (t.kind() != Kind.EOF) {
      pos++;
    }
    return t;
  }

  private boolean isOp(Token t, String op) {
    return t.kind() == Kind.OP && t.text().equals(op);
  }

  private boolean acceptOp(String op) {
    if (isOp(peek(), op)) {
      pos++;
      return true;
    }
    return false;
  }

  private void expectOp(String op) {
    if (!acceptOp(op)) {
      throw unexpected("'" + op + "'");
    }
  }

  private ExpressionException unexpected(String wanted) {
    Token t = peek();
    String found = t.kind() == Kind.EOF ? "end of the expression" : "'" + t.text() + "'";
    return new ExpressionException("expected " + wanted + " but found " + found + " at " + t.pos());
  }

  /** Counts one node against the per-rule ceiling and the tree depth limit. */
  private <T extends Node> T made(T node) {
    nodes++;
    if (nodes > ceiling) {
      throw new ExpressionException(
          "expression exceeds the per-rule ceiling of " + ceiling + " nodes");
    }
    if (node.depth() > MAX_DEPTH) {
      throw new ExpressionException("expression tree is deeper than " + MAX_DEPTH);
    }
    return node;
  }

  private void enter() {
    if (++nesting > MAX_NESTING) {
      throw new ExpressionException("expression nesting is deeper than " + MAX_NESTING);
    }
  }

  private void leave() {
    nesting--;
  }

  // ---- grammar ------------------------------------------------------------------------------

  private Node expression() {
    enter();
    try {
      Node cond = or();
      if (acceptOp("?")) {
        Node a = expression();
        expectOp(":");
        Node b = expression();
        return made(Ast.ternary(cond, a, b));
      }
      return cond;
    } finally {
      leave();
    }
  }

  private Node or() {
    Node left = and();
    while (acceptOp("||")) {
      left = made(Ast.binary(BinOp.OR, left, and()));
    }
    return left;
  }

  private Node and() {
    Node left = relation();
    while (acceptOp("&&")) {
      left = made(Ast.binary(BinOp.AND, left, relation()));
    }
    return left;
  }

  private Node relation() {
    Node left = additive();
    while (true) {
      Optional<BinOp> op = relationOperator(peek());
      if (op.isEmpty()) {
        return left;
      }
      pos++;
      left = made(Ast.binary(op.get(), left, additive()));
    }
  }

  private static Optional<BinOp> relationOperator(Token t) {
    if (t.kind() == Kind.IDENT && t.text().equals("in")) {
      return Optional.of(BinOp.IN);
    }
    if (t.kind() != Kind.OP) {
      return Optional.empty();
    }
    return switch (t.text()) {
      case "==" -> Optional.of(BinOp.EQ);
      case "!=" -> Optional.of(BinOp.NE);
      case "<" -> Optional.of(BinOp.LT);
      case "<=" -> Optional.of(BinOp.LE);
      case ">" -> Optional.of(BinOp.GT);
      case ">=" -> Optional.of(BinOp.GE);
      default -> Optional.empty();
    };
  }

  private Node additive() {
    Node left = multiplicative();
    while (true) {
      if (acceptOp("+")) {
        left = made(Ast.binary(BinOp.ADD, left, multiplicative()));
      } else if (acceptOp("-")) {
        left = made(Ast.binary(BinOp.SUB, left, multiplicative()));
      } else {
        return left;
      }
    }
  }

  private Node multiplicative() {
    Node left = unary();
    while (true) {
      if (acceptOp("*")) {
        left = made(Ast.binary(BinOp.MUL, left, unary()));
      } else if (acceptOp("/")) {
        left = made(Ast.binary(BinOp.DIV, left, unary()));
      } else {
        return left;
      }
    }
  }

  private Node unary() {
    if (isOp(peek(), "!") || isOp(peek(), "-")) {
      enter();
      try {
        UnOp op = next().text().equals("!") ? UnOp.NOT : UnOp.NEG;
        return made(Ast.unary(op, unary()));
      } finally {
        leave();
      }
    }
    return postfix();
  }

  private Node postfix() {
    Node node = primary();
    while (isOp(peek(), ".")) {
      pos++;
      Token name = next();
      if (name.kind() != Kind.IDENT) {
        throw new ExpressionException("expected a method name at " + name.pos());
      }
      if (!isOp(peek(), "(")) {
        throw new ExpressionException(
            "field access is only allowed on a path of variables, at " + name.pos());
      }
      if (LOOP_MACROS.contains(name.text())) {
        throw new ExpressionException("loop macro " + name.text() + " is not supported");
      }
      Library.Fn fn =
          Library.method(name.text())
              .orElseThrow(() -> new ExpressionException("unknown method " + name.text()));
      List<Node> args = new ArrayList<>();
      args.add(node);
      args.addAll(arguments());
      node = call(fn, args, name);
    }
    return node;
  }

  private Node call(Library.Fn fn, List<Node> args, Token name) {
    if (args.size() != fn.arity()) {
      throw new ExpressionException(
          name.text()
              + " takes "
              + fn.arity()
              + " argument(s) but got "
              + args.size()
              + " at "
              + name.pos());
    }
    return made(Ast.call(fn, args));
  }

  /** Parses "(" [expression {"," expression}] ")" and returns the arguments. */
  private List<Node> arguments() {
    expectOp("(");
    enter();
    try {
      List<Node> args = new ArrayList<>();
      if (!isOp(peek(), ")")) {
        args.add(expression());
        while (acceptOp(",")) {
          args.add(expression());
        }
      }
      expectOp(")");
      return args;
    } finally {
      leave();
    }
  }

  private Node primary() {
    Token t = peek();
    switch (t.kind()) {
      case INT -> {
        pos++;
        return made(literal(new IntValue(Long.parseLong(t.text()))));
      }
      case DECIMAL -> {
        pos++;
        BigDecimal d = new BigDecimal(t.text());
        if (d.scale() > Library.MAX_DIGITS || d.precision() > Library.MAX_DIGITS) {
          throw new ExpressionException("decimal literal exceeds 38 digits at " + t.pos());
        }
        return made(literal(new DecimalValue(d)));
      }
      case STRING -> {
        pos++;
        return made(literal(new StringValue(t.text())));
      }
      case IDENT -> {
        return identifier();
      }
      case OP -> {
        if (acceptOp("(")) {
          enter();
          try {
            Node inner = expression();
            expectOp(")");
            return inner;
          } finally {
            leave();
          }
        }
        if (acceptOp("[")) {
          enter();
          try {
            List<Node> items = new ArrayList<>();
            if (!isOp(peek(), "]")) {
              items.add(expression());
              while (acceptOp(",")) {
                items.add(expression());
              }
            }
            expectOp("]");
            return made(Ast.list(items));
          } finally {
            leave();
          }
        }
        throw unexpected("an expression");
      }
      default -> throw unexpected("an expression");
    }
  }

  private Node identifier() {
    Token t = next();
    switch (t.text()) {
      case "true" -> {
        return made(literal(new BoolValue(true)));
      }
      case "false" -> {
        return made(literal(new BoolValue(false)));
      }
      case "null" ->
          throw new ExpressionException("the null literal is not supported at " + t.pos());
      case "in" -> throw new ExpressionException("'in' needs a left operand at " + t.pos());
      default -> {
        // fall through to a call or a path
      }
    }
    if (isOp(peek(), "(")) {
      if (t.text().equals("has")) {
        return hasMacro(t);
      }
      Optional<Library.Fn> fn = Library.global(t.text());
      if (fn.isEmpty()) {
        throw new ExpressionException("unknown function " + t.text() + " at " + t.pos());
      }
      return call(fn.get(), arguments(), t);
    }
    StringBuilder path = new StringBuilder(t.text());
    while (isOp(peek(), ".") && peekAt(1).kind() == Kind.IDENT && !isOp(peekAt(2), "(")) {
      pos++;
      path.append('.').append(next().text());
    }
    return made(new Ast.Path(path.toString()));
  }

  /** {@code has(a.b.c)}: the argument must be a path of variables. */
  private Node hasMacro(Token name) {
    expectOp("(");
    Token first = next();
    if (first.kind() != Kind.IDENT) {
      throw new ExpressionException("has() needs a path of variables at " + name.pos());
    }
    StringBuilder path = new StringBuilder(first.text());
    while (acceptOp(".")) {
      Token part = next();
      if (part.kind() != Kind.IDENT) {
        throw new ExpressionException("has() needs a path of variables at " + name.pos());
      }
      path.append('.').append(part.text());
    }
    if (!acceptOp(")")) {
      throw new ExpressionException("has() needs a path of variables at " + name.pos());
    }
    return made(new Ast.Has(path.toString()));
  }

  private static Ast.Lit literal(Value value) {
    return new Ast.Lit(value);
  }
}
