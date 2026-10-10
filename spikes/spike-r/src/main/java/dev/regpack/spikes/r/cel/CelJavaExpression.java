package dev.regpack.spikes.r.cel;

import com.google.protobuf.Timestamp;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelErrorCode;
import dev.cel.common.CelException;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.exceptions.CelRuntimeException;
import dev.cel.common.navigation.CelNavigableAst;
import dev.cel.common.navigation.CelNavigableExpr;
import dev.cel.common.types.CelKind;
import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.OpaqueType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import dev.regpack.ports.exec.BudgetExceededException;
import dev.regpack.ports.exec.CostMeter;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.CompiledExpression;
import dev.regpack.ports.expr.Expression;
import dev.regpack.ports.expr.ExpressionException;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.DateValue;
import dev.regpack.ports.expr.Value.DecimalValue;
import dev.regpack.ports.expr.Value.EnumValue;
import dev.regpack.ports.expr.Value.IntValue;
import dev.regpack.ports.expr.Value.ListValue;
import dev.regpack.ports.expr.Value.StringValue;
import dev.regpack.ports.expr.Value.TimestampValue;
import dev.regpack.ports.expr.ValueType;
import dev.regpack.spikes.r.vectors.Outcome;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The {@code cel-java} 0.14.0 adapter behind the P0 {@link Expression} port (SP-R, R-1b).
 *
 * <p>CEL has no decimal type, nanosecond timestamps and error absorption, and {@code cel-java} has
 * no cost tracking. The adapter therefore (a) rejects at compile time what must never decide an
 * outcome (double, null and unsigned literals, and any node typed double, unsigned or null, found
 * by walking the checked AST), (b) carries decimals as the opaque type {@code regpack.decimal} with
 * {@link BigDecimal} values compared by value, with operator overloads and, beside them, the
 * function syntax {@code decAdd}, {@code decEq} and so on, (c) charges cost statically (the node
 * count of the checked AST once per evaluation, plus one unit per list item and one per 256 code
 * points of every bound string when the bindings are materialised), which is deterministic but
 * coarser than the in-house model, and (d) leaves every remaining difference to the conformance
 * harness, which records it as data.
 *
 * <p>Variables: every path that the expression reads as a select chain ({@code event.venue}) is
 * materialised under its root name, declared as {@code map<string, dyn>}; a bare identifier takes
 * its type from the {@code variables} argument of {@link #compile}. Absence is a missing map key.
 * Third-party types never appear in a public signature.
 */
public final class CelJavaExpression implements Expression {
  /** The node-count ceiling of {@code new CelJavaExpression()}. */
  public static final long DEFAULT_CEILING = 100_000L;

  /**
   * Largest precision and largest absolute scale of a decimal (digits), as in the in-house layer.
   */
  private static final int MAX_DIGITS = 38;

  private static final int MAX_SOURCE_CODE_POINTS = 100_000;
  private static final int MAX_CAUSE_DEPTH = 16;
  private static final int MAX_MESSAGE_CHARS = 200;
  private static final long MIN_EPOCH_SECOND = -62_167_219_200L; // 0000-01-01T00:00:00Z
  private static final long MAX_EPOCH_SECOND = 253_402_300_799L; // 9999-12-31T23:59:59Z

  private static final CelType DEC = OpaqueType.create("regpack.decimal");
  private static final CelType DATE = OpaqueType.create("regpack.date");
  private static final CelType DYN_LIST = ListType.create(SimpleType.DYN);
  private static final CelType DYN_MAP = MapType.create(SimpleType.STRING, SimpleType.DYN);

  private final long ceiling;
  private final CelCompiler base;
  private final CelRuntime runtime;

  /** An expression layer with the default node-count ceiling. */
  public CelJavaExpression() {
    this(DEFAULT_CEILING);
  }

  private CelJavaExpression(long ceiling) {
    if (ceiling < 1) {
      throw new IllegalArgumentException("ceiling must be >= 1");
    }
    this.ceiling = ceiling;
    // short-circuiting on, int wrap an error, comprehensions off (and the macros that need them
    // are not installed), unsigned off (the default of this builder), timestamp overflow checked,
    // the standard CEL equality (decimals bring their own by-value equals), size bounded
    CelOptions options =
        CelOptions.newBuilder()
            .enableShortCircuiting(true)
            .errorOnIntWrap(true)
            .enableComprehension(false)
            .enableTimestampOverflowCheck(true)
            .disableCelStandardEquality(false)
            .maxExpressionCodePointSize(MAX_SOURCE_CODE_POINTS)
            .build();
    this.base =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setOptions(options)
            .setStandardMacros(CelStandardMacro.HAS)
            .addFunctionDeclarations(declarations())
            .build();
    this.runtime =
        CelRuntimeFactory.standardCelRuntimeBuilder()
            .setOptions(options)
            .addFunctionBindings(bindings())
            .build();
  }

  /** An expression layer that refuses, at compile time, any source with more nodes than this. */
  public static CelJavaExpression withCeiling(long perRuleCeilingUnits) {
    return new CelJavaExpression(perRuleCeilingUnits);
  }

  @Override
  public Compiled compile(String source, Map<String, ValueType> variables) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(variables, "variables");
    if (source.isBlank()) {
      throw new ExpressionException("empty expression");
    }
    CelValidationResult parsed = base.parse(source);
    if (parsed.hasError()) {
      throw new ExpressionException("parse error: " + clip(parsed.getErrorString()));
    }
    CelAbstractSyntaxTree parsedAst = astOf(parsed);
    Paths paths = Paths.collect(parsedAst);

    CelCompilerBuilder declared = base.toCompilerBuilder();
    for (String root : paths.chainRoots()) {
      declared.addVar(root, DYN_MAP);
    }
    for (String name : paths.bare()) {
      ValueType type = variables.get(name);
      if (type == null) {
        throw new ExpressionException("undeclared variable " + name);
      }
      declared.addVar(name, celType(type));
    }
    CelValidationResult checked = declared.build().check(parsedAst);
    if (checked.hasError()) {
      throw new ExpressionException("type error: " + clip(checked.getErrorString()));
    }
    CelAbstractSyntaxTree ast = astOf(checked);
    rejectWhatMustNeverDecide(ast);
    long nodeCount = CelNavigableAst.fromAst(ast).getRoot().allNodes().count();
    if (nodeCount > ceiling) {
      throw new ExpressionException(
          "expression has " + nodeCount + " nodes, above the ceiling " + ceiling);
    }
    try {
      return new Compiled(source, runtime.createProgram(ast), nodeCount, paths);
    } catch (CelEvaluationException e) {
      throw new ExpressionException("cannot plan the expression: " + clip(e.getMessage()), e);
    }
  }

  // ---- compile-time rejections --------------------------------------------------------------

  private static CelAbstractSyntaxTree astOf(CelValidationResult result) {
    try {
      return result.getAst();
    } catch (CelValidationException e) {
      throw new ExpressionException("invalid expression: " + clip(e.getMessage()), e);
    }
  }

  /**
   * Double, null and unsigned values must never decide an outcome: a literal, a call of {@code
   * double} or {@code uint}, or any node that the checker typed double, unsigned or null.
   */
  private static void rejectWhatMustNeverDecide(CelAbstractSyntaxTree ast) {
    CelNavigableAst.fromAst(ast)
        .getRoot()
        .allNodes()
        .forEach(
            node -> {
              CelExpr expr = node.expr();
              switch (expr.getKind()) {
                case CONSTANT -> {
                  CelConstant.Kind kind = expr.constant().getKind();
                  if (kind == CelConstant.Kind.DOUBLE_VALUE) {
                    throw new ExpressionException("rejected: double literal");
                  }
                  if (kind == CelConstant.Kind.NULL_VALUE) {
                    throw new ExpressionException("rejected: null literal");
                  }
                  if (kind == CelConstant.Kind.UINT64_VALUE) {
                    throw new ExpressionException("rejected: unsigned literal");
                  }
                }
                case CALL -> {
                  String function = expr.call().function();
                  if (function.equals("double") || function.equals("uint")) {
                    throw new ExpressionException("rejected: " + function + "()");
                  }
                }
                default -> {}
              }
            });
    for (CelType type : ast.getTypeMap().values()) {
      CelKind kind = type.kind();
      if (kind == CelKind.DOUBLE || kind == CelKind.UINT || kind == CelKind.NULL_TYPE) {
        throw new ExpressionException("rejected: a " + kind + " typed value");
      }
    }
  }

  private static CelType celType(ValueType type) {
    return switch (type) {
      case BOOL -> SimpleType.BOOL;
      case INT -> SimpleType.INT;
      case DECIMAL -> DEC;
      case STRING, ENUM -> SimpleType.STRING;
      case TIMESTAMP -> SimpleType.TIMESTAMP;
      case DATE -> DATE;
      case LIST -> DYN_LIST;
    };
  }

  // ---- the paths an expression reads ----------------------------------------------------------

  /** A select chain such as {@code event.venue}: root name and the fields below it. */
  private record Chain(String path, String root, String[] fields) {}

  /** The variables an expression reads, found once at compile time. */
  private record Paths(List<Chain> chains, Set<String> chainRoots, Set<String> bare) {
    static Paths collect(CelAbstractSyntaxTree ast) {
      List<Chain> chains = new ArrayList<>();
      Set<String> roots = new LinkedHashSet<>();
      Set<String> bare = new LinkedHashSet<>();
      Set<String> seen = new LinkedHashSet<>();
      CelNavigableAst.fromAst(ast)
          .getRoot()
          .allNodes()
          .forEach(
              node -> {
                CelExpr expr = node.expr();
                boolean underSelect = isSelect(node.parent());
                if (expr.getKind() == CelExpr.ExprKind.Kind.IDENT && !underSelect) {
                  bare.add(expr.ident().name());
                } else if (expr.getKind() == CelExpr.ExprKind.Kind.SELECT && !underSelect) {
                  chainOf(expr)
                      .ifPresent(
                          chain -> {
                            if (seen.add(chain.path())) {
                              chains.add(chain);
                              roots.add(chain.root());
                            }
                          });
                }
              });
      bare.removeAll(roots);
      // the sets keep the discovery order, so declaration and materialisation order never vary
      return new Paths(List.copyOf(chains), roots, bare);
    }

    private static boolean isSelect(Optional<CelNavigableExpr> parent) {
      return parent.isPresent() && parent.get().getKind() == CelExpr.ExprKind.Kind.SELECT;
    }

    private static Optional<Chain> chainOf(CelExpr top) {
      List<String> reversed = new ArrayList<>();
      CelExpr current = top;
      while (current.getKind() == CelExpr.ExprKind.Kind.SELECT) {
        reversed.add(current.select().field());
        current = current.select().operand();
      }
      if (current.getKind() != CelExpr.ExprKind.Kind.IDENT) {
        return Optional.empty();
      }
      String root = current.ident().name();
      String[] fields = new String[reversed.size()];
      StringBuilder path = new StringBuilder(root);
      for (int i = 0; i < fields.length; i++) {
        fields[i] = reversed.get(reversed.size() - 1 - i);
        path.append('.').append(fields[i]);
      }
      return Optional.of(new Chain(path.toString(), root, fields));
    }
  }

  // ---- the compiled expression ----------------------------------------------------------------

  /** A compiled source; evaluation charges the static cost and the size of what it materialises. */
  public static final class Compiled implements CompiledExpression {
    private final String source;
    private final CelRuntime.Program program;
    private final long nodeCount;
    private final Paths paths;

    private Compiled(String source, CelRuntime.Program program, long nodeCount, Paths paths) {
      this.source = source;
      this.program = program;
      this.nodeCount = nodeCount;
      this.paths = paths;
    }

    @Override
    public String source() {
      return source;
    }

    /** The number of nodes of the checked AST: the static cost charged once per evaluation. */
    public long nodeCount() {
      return nodeCount;
    }

    /**
     * An upper bound of the units one evaluation can charge: the node count plus, for every
     * variable the expression materialises, the larger of the longest list and the most 256-code
     * point units of the longest string.
     */
    public long staticWorstCaseUnits(int maxListItems, int maxStringCodePoints) {
      long perBinding = Math.max(maxListItems, maxStringCodePoints / 256L);
      return nodeCount + (paths.chains().size() + paths.bare().size()) * perBinding;
    }

    @Override
    public Value evaluate(Bindings bindings, CostMeter meter) {
      meter.charge(nodeCount);
      Map<String, Object> environment = new HashMap<>();
      for (String name : paths.bare()) {
        Value value =
            bindings
                .get(name)
                .orElseThrow(() -> new EvalException("ABSENT", "unbound variable " + name));
        meter.charge(sizeUnits(value));
        environment.put(name, toCel(value));
      }
      for (String root : paths.chainRoots()) {
        environment.put(root, new HashMap<String, Object>());
      }
      for (Chain chain : paths.chains()) {
        Optional<Value> value = bindings.get(chain.path());
        if (value.isEmpty()) {
          continue;
        }
        meter.charge(sizeUnits(value.get()));
        insert(environment, chain, toCel(value.get()));
      }
      Object result;
      try {
        result = program.eval(environment);
      } catch (CelEvaluationException | RuntimeException e) {
        throw new EvalException(errorKind(e), clip(String.valueOf(e.getMessage())));
      }
      return fromCel(result);
    }

    @SuppressWarnings("unchecked")
    private static void insert(Map<String, Object> environment, Chain chain, Object value) {
      Map<String, Object> node = (Map<String, Object>) environment.get(chain.root());
      String[] fields = chain.fields();
      for (int i = 0; i < fields.length - 1; i++) {
        Object next = node.computeIfAbsent(fields[i], key -> new HashMap<String, Object>());
        if (!(next instanceof Map<?, ?>)) {
          throw new EvalException("OTHER", "path conflict at " + chain.path());
        }
        node = (Map<String, Object>) next;
      }
      String leaf = fields[fields.length - 1];
      if (node.get(leaf) instanceof Map<?, ?>) {
        throw new EvalException("OTHER", "path conflict at " + chain.path());
      }
      node.put(leaf, value);
    }

    /** One unit per list item, one per 256 code points of a string. */
    private static long sizeUnits(Value value) {
      return switch (value) {
        case StringValue s -> s.value().codePointCount(0, s.value().length()) / 256;
        case ListValue l -> l.items().size();
        default -> 0;
      };
    }
  }

  /** An evaluation error with one of the fixed kinds of {@link Outcome#ERROR_KINDS}. */
  public static final class EvalException extends ExpressionException {
    private static final long serialVersionUID = 1L;

    private final String kind;

    public EvalException(String kind, String message) {
      super(message);
      if (!Outcome.ERROR_KINDS.contains(kind)) {
        throw new IllegalArgumentException("unknown error kind " + kind);
      }
      this.kind = kind;
    }

    public String kind() {
      return kind;
    }

    /** Evaluation errors are expected outcomes, so they carry no stack trace. */
    @Override
    public synchronized Throwable fillInStackTrace() {
      return this;
    }
  }

  // ---- the one place that maps failures to error kinds ----------------------------------------

  /**
   * The error kind of a failure: the adapter's own kind, a budget breach, or the error code of a
   * {@code cel-java} exception; the cause chain is searched (a function that fails inside CEL
   * arrives wrapped), and anything unknown is OTHER, never a silent pass.
   */
  static String errorKind(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (current instanceof EvalException e) {
        return e.kind();
      }
      if (current instanceof BudgetExceededException) {
        return "BUDGET";
      }
      CelErrorCode code = null;
      if (current instanceof CelRuntimeException e) {
        code = e.getErrorCode();
      } else if (current instanceof CelException e) {
        code = e.getErrorCode();
      }
      if (code != null && code != CelErrorCode.INTERNAL_ERROR) {
        return kindOf(code);
      }
      current = current.getCause();
    }
    return "OTHER";
  }

  private static String kindOf(CelErrorCode code) {
    return switch (code) {
      case ATTRIBUTE_NOT_FOUND -> "ABSENT";
      case DIVIDE_BY_ZERO -> "DIV_ZERO";
      case NUMERIC_OVERFLOW -> "OVERFLOW";
      case BAD_FORMAT -> "PARSE";
      case OVERLOAD_NOT_FOUND, AMBIGUOUS_OVERLOAD, TYPE_NOT_FOUND -> "TYPE";
      case INDEX_OUT_OF_BOUNDS -> "RANGE";
      case ITERATION_BUDGET_EXCEEDED -> "BUDGET";
      default -> "OTHER";
    };
  }

  private static String clip(String text) {
    String flat = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    return flat.length() <= MAX_MESSAGE_CHARS ? flat : flat.substring(0, MAX_MESSAGE_CHARS) + "...";
  }

  // ---- values between the port and cel-java -----------------------------------------------------

  /** A decimal as the runtime sees it: equal when numerically equal, whatever the scale. */
  private record Dec(BigDecimal value) {
    @Override
    public boolean equals(Object other) {
      return other instanceof Dec d && value.compareTo(d.value) == 0;
    }

    @Override
    public int hashCode() {
      return value.stripTrailingZeros().hashCode();
    }
  }

  private static Object toCel(Value value) {
    return switch (value) {
      case BoolValue b -> b.value();
      case IntValue i -> i.value();
      case DecimalValue d -> new Dec(d.value());
      case StringValue s -> s.value();
      case TimestampValue t -> timestamp(t.value());
      case DateValue d -> d.value();
      case EnumValue e -> e.name();
      case ListValue l -> {
        List<Object> items = new ArrayList<>(l.items().size());
        for (Value item : l.items()) {
          items.add(toCel(item));
        }
        yield items;
      }
    };
  }

  private static Value fromCel(Object result) {
    return switch (result) {
      case Boolean b -> new BoolValue(b);
      case Long n -> new IntValue(n);
      case Dec d -> new DecimalValue(d.value());
      case String s -> new StringValue(s);
      case Timestamp t -> new TimestampValue(Instant.ofEpochSecond(t.getSeconds(), t.getNanos()));
      case LocalDate d -> new DateValue(d);
      case List<?> list -> {
        List<Value> items = new ArrayList<>(list.size());
        for (Object item : list) {
          items.add(fromCel(item));
        }
        yield new ListValue(items);
      }
      default ->
          throw new EvalException(
              "TYPE", "result of type " + result.getClass().getSimpleName() + " is not a value");
    };
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();
  }

  // ---- decimals --------------------------------------------------------------------------------

  /** Normalises a negative scale to 0 and refuses a scale or precision above 38 digits. */
  private static Dec checked(BigDecimal d) {
    BigDecimal r = d;
    if (r.scale() < 0) {
      if (r.scale() < -MAX_DIGITS) {
        throw new EvalException("SCALE", "decimal exponent out of range");
      }
      r = r.setScale(0);
    }
    if (r.scale() > MAX_DIGITS || r.precision() > MAX_DIGITS) {
      throw new EvalException("SCALE", "decimal exceeds 38 digits");
    }
    return new Dec(r);
  }

  private static boolean asciiNumeric(String s) {
    if (s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean ok =
          (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.' || c == 'e' || c == 'E';
      if (!ok) {
        return false;
      }
    }
    return true;
  }

  private static Dec decOf(String s) {
    if (!asciiNumeric(s)) {
      throw new EvalException("PARSE", "not a decimal");
    }
    try {
      return checked(new BigDecimal(s));
    } catch (NumberFormatException e) {
      throw new EvalException("PARSE", "not a decimal");
    }
  }

  private static Dec divide(Dec a, Dec b) {
    if (b.value().signum() == 0) {
      throw new EvalException("DIV_ZERO", "division by zero");
    }
    try {
      return checked(a.value().divide(b.value()));
    } catch (ArithmeticException e) {
      throw new EvalException("NON_TERMINATING", "non-terminating decimal expansion");
    }
  }

  private static Dec round(Dec value, long places, String mode) {
    if (places < 0 || places > MAX_DIGITS) {
      throw new EvalException("RANGE", "round scale out of range");
    }
    RoundingMode rounding =
        switch (mode) {
          case "HALF_UP" -> RoundingMode.HALF_UP;
          case "HALF_EVEN" -> RoundingMode.HALF_EVEN;
          default -> throw new EvalException("OTHER", "unknown rounding mode");
        };
    return checked(value.value().setScale((int) places, rounding));
  }

  // ---- dates, timestamps, strings
  // ----------------------------------------------------------------

  private static LocalDate date(String s) {
    try {
      return LocalDate.parse(s);
    } catch (DateTimeException e) {
      throw new EvalException("PARSE", "not an ISO date");
    }
  }

  /** The fixed form yyyy-MM-ddTHH:mm:ss.ffffffZ, written by hand (no formatter, no locale). */
  private static String isoMicros(Timestamp t) {
    if (t.getNanos() % 1000 != 0) {
      throw new EvalException("RANGE", "finer than microseconds");
    }
    if (t.getSeconds() < MIN_EPOCH_SECOND || t.getSeconds() > MAX_EPOCH_SECOND) {
      throw new EvalException("RANGE", "year outside 0000 to 9999");
    }
    LocalDateTime ldt = LocalDateTime.ofEpochSecond(t.getSeconds(), 0, ZoneOffset.UTC);
    StringBuilder out = new StringBuilder(27);
    pad(out, ldt.getYear(), 4).append('-');
    pad(out, ldt.getMonthValue(), 2).append('-');
    pad(out, ldt.getDayOfMonth(), 2).append('T');
    pad(out, ldt.getHour(), 2).append(':');
    pad(out, ldt.getMinute(), 2).append(':');
    pad(out, ldt.getSecond(), 2).append('.');
    pad(out, t.getNanos() / 1000, 6).append('Z');
    return out.toString();
  }

  private static StringBuilder pad(StringBuilder out, int value, int width) {
    String digits = Integer.toString(value);
    for (int i = digits.length(); i < width; i++) {
      out.append('0');
    }
    return out.append(digits);
  }

  // ---- declarations and bindings
  // -------------------------------------------------------------------

  private static CelOverloadDecl global(String id, CelType result, CelType... parameters) {
    return CelOverloadDecl.newGlobalOverload(id, result, parameters);
  }

  private static CelFunctionDecl function(String name, CelOverloadDecl... overloads) {
    return CelFunctionDecl.newFunctionDeclaration(name, overloads);
  }

  private static List<CelFunctionDecl> declarations() {
    CelType bool = SimpleType.BOOL;
    CelType string = SimpleType.STRING;
    return List.of(
        function("dec", global("dec_string", DEC, string), global("dec_int", DEC, SimpleType.INT)),
        // operators of the decimal type: the first thing tried
        function("_+_", global("add_dec", DEC, DEC, DEC)),
        function("_-_", global("sub_dec", DEC, DEC, DEC)),
        function("_*_", global("mul_dec", DEC, DEC, DEC)),
        function("_/_", global("div_dec", DEC, DEC, DEC)),
        function("-_", global("neg_dec", DEC, DEC)),
        function("_<_", global("lt_dec", bool, DEC, DEC), global("lt_date", bool, DATE, DATE)),
        function("_<=_", global("le_dec", bool, DEC, DEC), global("le_date", bool, DATE, DATE)),
        function("_>_", global("gt_dec", bool, DEC, DEC), global("gt_date", bool, DATE, DATE)),
        function("_>=_", global("ge_dec", bool, DEC, DEC), global("ge_date", bool, DATE, DATE)),
        // function syntax for the same operations (cel-rewrites.tsv): classified FUNCTIONAL
        function("decAdd", global("fn_dec_add", DEC, DEC, DEC)),
        function("decSub", global("fn_dec_sub", DEC, DEC, DEC)),
        function("decMul", global("fn_dec_mul", DEC, DEC, DEC)),
        function("decDiv", global("fn_dec_div", DEC, DEC, DEC)),
        function("decEq", global("fn_dec_eq", bool, DEC, DEC)),
        function("decLt", global("fn_dec_lt", bool, DEC, DEC)),
        function("round", global("round_dec", DEC, DEC, SimpleType.INT, string)),
        function("string", global("string_dec", string, DEC)),
        function("date", global("date_string", DATE, string)),
        function("dayOfWeek", global("day_of_week_date", string, DATE)),
        function("isoMicros", global("iso_micros_timestamp", string, SimpleType.TIMESTAMP)),
        function("upper", global("upper_string", string, string)),
        function("lower", global("lower_string", string, string)),
        function("contains", global("contains_list", bool, DYN_LIST, SimpleType.DYN)));
  }

  private static List<CelFunctionBinding> bindings() {
    List<CelFunctionBinding> all = new ArrayList<>();
    all.add(CelFunctionBinding.from("dec_string", String.class, CelJavaExpression::decOf));
    all.add(CelFunctionBinding.from("dec_int", Long.class, n -> checked(BigDecimal.valueOf(n))));
    binary(all, "add_dec", "fn_dec_add", (a, b) -> checked(a.value().add(b.value())));
    binary(all, "sub_dec", "fn_dec_sub", (a, b) -> checked(a.value().subtract(b.value())));
    binary(all, "mul_dec", "fn_dec_mul", (a, b) -> checked(a.value().multiply(b.value())));
    binary(all, "div_dec", "fn_dec_div", CelJavaExpression::divide);
    all.add(CelFunctionBinding.from("neg_dec", Dec.class, a -> new Dec(a.value().negate())));
    compare(all, "lt_dec", "lt_date", c -> c < 0);
    compare(all, "le_dec", "le_date", c -> c <= 0);
    compare(all, "gt_dec", "gt_date", c -> c > 0);
    compare(all, "ge_dec", "ge_date", c -> c >= 0);
    all.add(
        CelFunctionBinding.from(
            "fn_dec_eq", Dec.class, Dec.class, (a, b) -> a.value().compareTo(b.value()) == 0));
    all.add(
        CelFunctionBinding.from(
            "fn_dec_lt", Dec.class, Dec.class, (a, b) -> a.value().compareTo(b.value()) < 0));
    all.add(
        CelFunctionBinding.from(
            "round_dec",
            List.of(Dec.class, Long.class, String.class),
            args -> round((Dec) args[0], (Long) args[1], (String) args[2])));
    all.add(
        CelFunctionBinding.from(
            "string_dec", Dec.class, a -> a.value().stripTrailingZeros().toPlainString()));
    all.add(CelFunctionBinding.from("date_string", String.class, CelJavaExpression::date));
    all.add(
        CelFunctionBinding.from("day_of_week_date", LocalDate.class, d -> d.getDayOfWeek().name()));
    all.add(
        CelFunctionBinding.from(
            "iso_micros_timestamp", Timestamp.class, CelJavaExpression::isoMicros));
    all.add(CelFunctionBinding.from("upper_string", String.class, s -> s.toUpperCase(Locale.ROOT)));
    all.add(CelFunctionBinding.from("lower_string", String.class, s -> s.toLowerCase(Locale.ROOT)));
    all.add(
        CelFunctionBinding.from(
            "contains_list", List.class, Object.class, (list, item) -> list.contains(item)));
    return all;
  }

  private static void binary(
      List<CelFunctionBinding> all,
      String operatorId,
      String functionId,
      java.util.function.BinaryOperator<Dec> operation) {
    for (String id : List.of(operatorId, functionId)) {
      all.add(CelFunctionBinding.from(id, Dec.class, Dec.class, operation::apply));
    }
  }

  private static void compare(
      List<CelFunctionBinding> all, String decId, String dateId, Predicate<Integer> test) {
    all.add(
        CelFunctionBinding.from(
            decId, Dec.class, Dec.class, (a, b) -> test.test(a.value().compareTo(b.value()))));
    all.add(
        CelFunctionBinding.from(
            dateId, LocalDate.class, LocalDate.class, (a, b) -> test.test(a.compareTo(b))));
  }

  /** The values the in-house layer and this one share, for the layer's binding types. */
  static ValueType typeOf(Value value) {
    return switch (value) {
      case BoolValue _ -> ValueType.BOOL;
      case IntValue _ -> ValueType.INT;
      case DecimalValue _ -> ValueType.DECIMAL;
      case StringValue _ -> ValueType.STRING;
      case TimestampValue _ -> ValueType.TIMESTAMP;
      case DateValue _ -> ValueType.DATE;
      case EnumValue _ -> ValueType.ENUM;
      case ListValue _ -> ValueType.LIST;
    };
  }
}
