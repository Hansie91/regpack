package dev.regpack.engine.pack;

import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.model.CanonicalInputSpec;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.DecimalValue;
import dev.regpack.ports.expr.Value.EnumValue;
import dev.regpack.ports.expr.Value.StringValue;
import dev.regpack.ports.expr.Value.TimestampValue;
import dev.regpack.ports.expr.ValueType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The canonical input of this engine build as {@link Bindings}: the 13 paths of a flattened {@link
 * CanonicalExecution}. A pack declares the paths its rules may read in {@code
 * model/canonical-input.yaml}; {@link #checkSupported} refuses a declaration this class cannot bind
 * (a path it does not know, another type, another enumeration, or a ledger-state input).
 *
 * <p>An unknown path and an absent optional value are both empty (explicit absence, spec 6.2).
 * Decimals keep the scale of the input; enumerations carry the name of their type.
 */
public final class CanonicalBindings implements Bindings {
  private static final String FILE = CanonicalInputSpec.FILE;
  private static final String LIFECYCLE_KIND = "LifecycleKind";
  private static final String PARTY_KIND = "PartyKind";
  private static final String ASSET_CLASS = "AssetClass";

  /** The paths that can be absent in this engine build; every other path is always present. */
  private static final Set<String> OPTIONAL_PATHS = Set.of("client.lei");

  /** The paths this class binds, with their types, in path order. */
  public static final Map<String, ValueType> VARIABLES;

  /** The enumeration name of each ENUM path. */
  private static final Map<String, String> ENUM_TYPES;

  static {
    Map<String, ValueType> types = new TreeMap<>();
    types.put("event.kind", ValueType.ENUM);
    types.put("event.tradedAt", ValueType.TIMESTAMP);
    types.put("venue.mic", ValueType.STRING);
    types.put("venue.executionId", ValueType.STRING);
    types.put("firm.lei", ValueType.STRING);
    types.put("instrument.isin", ValueType.STRING);
    types.put("instrument.assetClass", ValueType.ENUM);
    types.put("instrument.tradedOnVenue", ValueType.BOOL);
    types.put("client.kind", ValueType.ENUM);
    types.put("client.lei", ValueType.STRING);
    types.put("trade.quantity", ValueType.DECIMAL);
    types.put("trade.price", ValueType.DECIMAL);
    types.put("trade.currency", ValueType.STRING);
    VARIABLES = Collections.unmodifiableMap(types);

    Map<String, String> enums = new TreeMap<>();
    enums.put("event.kind", LIFECYCLE_KIND);
    enums.put("instrument.assetClass", ASSET_CLASS);
    enums.put("client.kind", PARTY_KIND);
    ENUM_TYPES = Collections.unmodifiableMap(enums);
  }

  private final CanonicalExecution execution;

  public CanonicalBindings(CanonicalExecution execution) {
    this.execution = Objects.requireNonNull(execution, "execution");
  }

  @Override
  public Optional<Value> get(String path) {
    Objects.requireNonNull(path, "path");
    return switch (path) {
      case "event.kind" -> Optional.of(new EnumValue(LIFECYCLE_KIND, execution.eventKind().name()));
      case "event.tradedAt" -> Optional.of(new TimestampValue(execution.tradedAt()));
      case "venue.mic" -> Optional.of(new StringValue(execution.venueMic()));
      case "venue.executionId" -> Optional.of(new StringValue(execution.venueExecutionId()));
      case "firm.lei" -> Optional.of(new StringValue(execution.firmLei()));
      case "instrument.isin" -> Optional.of(new StringValue(execution.isin()));
      case "instrument.assetClass" ->
          Optional.of(new EnumValue(ASSET_CLASS, execution.assetClass().name()));
      case "instrument.tradedOnVenue" -> Optional.of(new BoolValue(execution.tradedOnVenue()));
      case "client.kind" -> Optional.of(new EnumValue(PARTY_KIND, execution.clientKind().name()));
      case "client.lei" -> execution.clientLei().map(StringValue::new);
      case "trade.quantity" -> Optional.of(new DecimalValue(execution.quantity()));
      case "trade.price" -> Optional.of(new DecimalValue(execution.price()));
      case "trade.currency" -> Optional.of(new StringValue(execution.currency()));
      default -> Optional.empty();
    };
  }

  /**
   * Checks that a pack's canonical input declaration is one this class can bind. Problems, sorted:
   * {@code INPUT_PATH_UNSUPPORTED} (a path this class does not bind, or a ledger-state input) and
   * {@code INPUT_TYPE_MISMATCH} (another type, or another enumeration for an ENUM path) and {@code
   * INPUT_OPTIONAL_MISMATCH} (an {@code optional} flag that contradicts the binding: only {@code
   * client.lei} can be absent).
   */
  public static List<PackProblem> checkSupported(CanonicalInputSpec spec) {
    Objects.requireNonNull(spec, "spec");
    List<PackProblem> problems = new ArrayList<>();
    for (Map.Entry<String, CanonicalInputSpec.Variable> entry : spec.variables().entrySet()) {
      String path = entry.getKey();
      CanonicalInputSpec.Variable variable = entry.getValue();
      ValueType bound = VARIABLES.get(path);
      if (bound == null || variable.source() != CanonicalInputSpec.Variable.Source.EVENT) {
        problems.add(
            new PackProblem(
                "INPUT_PATH_UNSUPPORTED",
                FILE,
                0,
                "this engine build does not bind '" + PackProblem.excerpt(path) + "'"));
      } else if (variable.type() != bound) {
        problems.add(
            new PackProblem(
                "INPUT_TYPE_MISMATCH",
                FILE,
                0,
                "'" + PackProblem.excerpt(path) + "' is " + bound + " in this engine build"));
      } else if (bound == ValueType.ENUM
          && !variable.enumName().equals(Optional.of(ENUM_TYPES.get(path)))) {
        problems.add(
            new PackProblem(
                "INPUT_TYPE_MISMATCH",
                FILE,
                0,
                "'"
                    + PackProblem.excerpt(path)
                    + "' is the enumeration "
                    + ENUM_TYPES.get(path)
                    + " in this engine build"));
      } else if (variable.optional() != OPTIONAL_PATHS.contains(path)) {
        problems.add(
            new PackProblem(
                "INPUT_OPTIONAL_MISMATCH",
                FILE,
                0,
                "'"
                    + PackProblem.excerpt(path)
                    + (OPTIONAL_PATHS.contains(path)
                        ? "' can be absent in this engine build and must be declared optional"
                        : "' is never absent in this engine build and must not be declared"
                            + " optional")));
      }
    }
    Collections.sort(problems);
    return problems;
  }
}
