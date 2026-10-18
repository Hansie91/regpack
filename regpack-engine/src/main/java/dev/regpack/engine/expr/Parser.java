package dev.regpack.engine.expr;

import dev.regpack.engine.expr.Ast.BinOp;
import dev.regpack.engine.expr.Ast.Node;
import dev.regpack.engine.expr.Ast.UnOp;
import dev.regpack.engine.expr.InHouseExpression.Limits;
import dev.regpack.engine.expr.Lexer.Kind;
import dev.regpack.engine.expr.Lexer.Token;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.DecimalValue;
import dev.regpack.ports.expr.Value.IntValue;
import dev.regpack.ports.expr.Value.StringValue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Precedence-climbing parser. Grammar, lowest precedence first: ternary, {@code ||}, {@code &&},
 * relations ({@code == != < <= > >= in}, left-associative), {@code + -}, {@code * /}, unary {@code
 * ! -}, postfix method call, primary. Left-associative chains are loops, so no recursion is
 * proportional to the length of a chain. The node ceiling is enforced while the tree is built, so a
 * hostile source never builds a large tree; the syntactic nesting (100) and the tree depth (256)
 * are tracked as well. Rejected on purpose: the null literal, loop macros, exponent literals, user
 * functions, unknown functions and methods, wrong arity, field access on anything but a path of
 * declared variables.
 */
final class Parser {
  /** Deepest syntactic nesting: parentheses, lists, call arguments, unary chains, ternaries. */
  static final int MAX_NESTING = 100;

  /** Deepest tree: a recursive evaluator must fit a small worker stack. */
  static final int MAX_DEPTH = 256;

  /** Longest piece of source text a message may repeat. */
  private static final int EXCERPT = 24;

  private static final Set<String> LOOP_MACROS =
      Set.of("all", "exists", "exists_one", "map", "filter");

  /** The tree, its node count and the paths it reads (a {@code has} path included). */
  record Parsed(Node root, int nodeCount, SortedSet<String> paths) {}

  private final List<Token> tokens;
  private final Limits limits;
  private final Set<String> declared;
  private final SortedSet<String> paths = new TreeSet<>();
  private int pos;
  private int nesting;
  private int nodes;

  private Parser(List<Token> tokens, Limits limits, Set<String> declared) {
    this.tokens = tokens;
    this.limits = limits;
    this.declared = declared;
  }

  /**
   * Parses a source text.
   *
   * @param declared the variable paths a path or {@code has} may name
   * @throws ExprException of kind PARSE on any compile error
   */
  static Parsed parse(String source, Limits limits, Set<String> declared) {
    Parser parser = new Parser(Lexer.tokenize(source), limits, declared);
    if (parser.peek().kind() == Kind.EOF) {
      throw error("empty expression", 0);
    }
    Node root = parser.expression();
    if (parser.peek().kind() != Kind.EOF) {
      throw parser.unexpected("the end of the expression");
    }
    return new Parsed(root, parser.nodes, Collections.unmodifiableSortedSet(parser.paths));
  }

  // ---- helpers ------------------------------------------------------------------------------

  private static ExprException error(String what, int pos) {
    return new ExprException(ErrorKind.PARSE, what + " at " + pos);
  }

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

  private static boolean isOp(Token t, String op) {
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

  /** A short description of a token that never repeats more than an excerpt of the source. */
  private static String shown(Token t) {
    return switch (t.kind()) {
      case EOF -> "the end of the expression";
      case STRING -> "a string literal";
      default -> "'" + excerpt(t.text()) + "'";
    };
  }

  private static String excerpt(String text) {
    return text.length() <= EXCERPT ? text : text.substring(0, EXCERPT) + "...";
  }

  private ExprException unexpected(String wanted) {
    Token t = peek();
    return error("expected " + wanted + " but found " + shown(t), t.pos());
  }

  /** Counts one node against the node ceiling and the tree depth limit. */
  private <T extends Node> T made(T node, int at) {
    nodes++;
    if (nodes > limits.maxNodes()) {
      throw error("expression exceeds the ceiling of " + limits.maxNodes() + " nodes", at);
    }
    if (node.depth() > MAX_DEPTH) {
      throw error("expression tree is deeper than " + MAX_DEPTH, at);
    }
    return node;
  }

  private void enter() {
    if (++nesting > MAX_NESTING) {
      throw error("expression nesting is deeper than " + MAX_NESTING, peek().pos());
    }
  }

  private void leave() {
    nesting--;
  }

  // ---- grammar ------------------------------------------------------------------------------

  private Node expression() {
    Node cond = binary(1);
    Token question = peek();
    if (!acceptOp("?")) {
      return cond;
    }
    enter();
    Node whenTrue = expression();
    expectOp(":");
    Node whenFalse = expression();
    leave();
    return made(Ast.ternary(cond, whenTrue, whenFalse), question.pos());
  }

  /** Precedence of a binary operator token; 0 when the token is not one. */
  private static int precedence(Token t) {
    if (t.kind() == Kind.IDENT) {
      return t.text().equals("in") ? 3 : 0;
    }
    if (t.kind() != Kind.OP) {
      return 0;
    }
    return switch (t.text()) {
      case "||" -> 1;
      case "&&" -> 2;
      case "==", "!=", "<", "<=", ">", ">=" -> 3;
      case "+", "-" -> 4;
      case "*", "/" -> 5;
      default -> 0;
    };
  }

  private static BinOp operator(Token t) {
    if (t.kind() == Kind.IDENT) {
      return BinOp.IN;
    }
    return switch (t.text()) {
      case "||" -> BinOp.OR;
      case "&&" -> BinOp.AND;
      case "==" -> BinOp.EQ;
      case "!=" -> BinOp.NE;
      case "<" -> BinOp.LT;
      case "<=" -> BinOp.LE;
      case ">" -> BinOp.GT;
      case ">=" -> BinOp.GE;
      case "+" -> BinOp.ADD;
      case "-" -> BinOp.SUB;
      case "*" -> BinOp.MUL;
      default -> BinOp.DIV;
    };
  }

  /** Precedence climbing: every binary level is left-associative and a loop. */
  private Node binary(int minPrecedence) {
    Node left = unary();
    while (true) {
      Token op = peek();
      int precedence = precedence(op);
      if (precedence == 0 || precedence < minPrecedence) {
        return left;
      }
      pos++;
      Node right = binary(precedence + 1);
      left = made(Ast.binary(operator(op), left, right), op.pos());
    }
  }

  private Node unary() {
    Token t = peek();
    if (isOp(t, "!") || isOp(t, "-")) {
      pos++;
      enter();
      Node operand = unary();
      leave();
      return made(Ast.unary(t.text().equals("!") ? UnOp.NOT : UnOp.NEG, operand), t.pos());
    }
    return postfix();
  }

  private Node postfix() {
    Node node = primary();
    while (isOp(peek(), ".")) {
      pos++;
      Token name = next();
      if (name.kind() != Kind.IDENT) {
        throw error("expected a method name", name.pos());
      }
      if (!isOp(peek(), "(")) {
        throw error("field access is only allowed on a path of declared variables", name.pos());
      }
      if (LOOP_MACROS.contains(name.text())) {
        throw error("loop macro " + name.text() + " is not supported", name.pos());
      }
      Library fn =
          Library.method(name.text())
              .orElseThrow(() -> error("unknown method " + excerpt(name.text()), name.pos()));
      List<Node> args = new ArrayList<>();
      args.add(node);
      args.addAll(arguments());
      node = call(fn, args, name);
    }
    return node;
  }

  private Node call(Library fn, List<Node> args, Token name) {
    if (args.size() != fn.arity()) {
      throw error(
          fn.label() + " takes " + fn.arity() + " argument(s) but got " + args.size(), name.pos());
    }
    return made(Ast.call(fn, args), name.pos());
  }

  /** Parses {@code "(" [expression {"," expression}] ")"} and returns the arguments. */
  private List<Node> arguments() {
    expectOp("(");
    enter();
    List<Node> args = new ArrayList<>();
    if (!isOp(peek(), ")")) {
      args.add(expression());
      while (acceptOp(",")) {
        args.add(expression());
      }
    }
    expectOp(")");
    leave();
    return args;
  }

  private Node primary() {
    Token t = peek();
    switch (t.kind()) {
      case INT -> {
        pos++;
        return made(new Ast.Lit(new IntValue(Long.parseLong(t.text()))), t.pos());
      }
      case DECIMAL -> {
        pos++;
        return made(new Ast.Lit(new DecimalValue(new BigDecimal(t.text()))), t.pos());
      }
      case STRING -> {
        pos++;
        if (t.text().codePointCount(0, t.text().length()) > limits.maxStringCodePoints()) {
          throw error(
              "string literal is longer than " + limits.maxStringCodePoints() + " code points",
              t.pos());
        }
        return made(new Ast.Lit(new StringValue(t.text())), t.pos());
      }
      case IDENT -> {
        return identifier();
      }
      case OP -> {
        if (acceptOp("(")) {
          enter();
          Node inner = expression();
          expectOp(")");
          leave();
          return inner;
        }
        if (isOp(t, "[")) {
          return listLiteral();
        }
        throw unexpected("an expression");
      }
      default -> throw unexpected("an expression");
    }
  }

  private Node listLiteral() {
    Token open = next();
    enter();
    List<Node> items = new ArrayList<>();
    if (!isOp(peek(), "]")) {
      items.add(expression());
      while (acceptOp(",")) {
        items.add(expression());
      }
    }
    expectOp("]");
    leave();
    return made(Ast.list(items), open.pos());
  }

  private Node identifier() {
    Token t = next();
    switch (t.text()) {
      case "true" -> {
        return made(new Ast.Lit(new BoolValue(true)), t.pos());
      }
      case "false" -> {
        return made(new Ast.Lit(new BoolValue(false)), t.pos());
      }
      case "null" -> throw error("the null literal is not supported", t.pos());
      case "in" -> throw error("'in' needs a left operand", t.pos());
      default -> {
        // a call or a path
      }
    }
    if (isOp(peek(), "(")) {
      if (t.text().equals("has")) {
        return hasMacro(t);
      }
      if (LOOP_MACROS.contains(t.text())) {
        throw error("loop macro " + t.text() + " is not supported", t.pos());
      }
      Optional<Library> fn = Library.global(t.text());
      if (fn.isEmpty()) {
        throw error("unknown function " + excerpt(t.text()), t.pos());
      }
      return call(fn.get(), arguments(), t);
    }
    StringBuilder path = new StringBuilder(t.text());
    while (isOp(peek(), ".") && peekAt(1).kind() == Kind.IDENT && !isOp(peekAt(2), "(")) {
      pos++;
      path.append('.').append(next().text());
    }
    return made(new Ast.Path(declaredPath(path.toString(), t)), t.pos());
  }

  /** {@code has(a.b.c)}: one node; the argument must be a path of declared variables. */
  private Node hasMacro(Token name) {
    expectOp("(");
    Token first = next();
    if (first.kind() != Kind.IDENT) {
      throw error("has() needs a path of declared variables", name.pos());
    }
    StringBuilder path = new StringBuilder(first.text());
    while (acceptOp(".")) {
      Token part = next();
      if (part.kind() != Kind.IDENT) {
        throw error("has() needs a path of declared variables", name.pos());
      }
      path.append('.').append(part.text());
    }
    if (!acceptOp(")")) {
      throw error("has() needs a path of declared variables", name.pos());
    }
    return made(new Ast.Has(declaredPath(path.toString(), name)), name.pos());
  }

  private String declaredPath(String path, Token at) {
    if (!declared.contains(path)) {
      throw error("undeclared variable " + excerpt(path), at.pos());
    }
    paths.add(path);
    return path;
  }
}
