package dev.regpack.flow.ingest;

import dev.regpack.engine.pack.CanonicalBindings;
import dev.regpack.model.Sha256;
import dev.regpack.model.canonical.AssetClass;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.model.canonical.PartyKind;
import dev.regpack.model.canonical.Side;
import dev.regpack.model.json.Json;
import dev.regpack.pack.yaml.PackSyntaxException;
import dev.regpack.pack.yaml.StrictYaml;
import dev.regpack.pack.yaml.YamlList;
import dev.regpack.pack.yaml.YamlMap;
import dev.regpack.pack.yaml.YamlNode;
import dev.regpack.pack.yaml.YamlScalar;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * A mapping pack (strict YAML, format provisional): where each canonical field of an execution
 * comes from in a source file, and how its text is read. It holds no business logic. Keys:
 *
 * <ul>
 *   <li>{@code mapping}: {@code {id: <name>}}. {@link #version()} is {@code <id>@<digest hex>}.
 *   <li>{@code format}: {@code csv}.
 *   <li>{@code source_record_id}, {@code source_version}: the names of the columns that give the
 *       record id and the version of the record (spec 10 rule 6). A mapping without a version is
 *       refused; an ordering column is not built.
 *   <li>{@code event_identity}: the tuple {@code [firm.lei, venue.mic, venue.executionId]}, the one
 *       the model hard-wires; any other is refused.
 *   <li>{@code control_totals}: {@code records_key} (the key of the marker that declares the number
 *       of rows), {@code sum_column} (a column with the cast {@code DECIMAL}) and {@code sum_key}
 *       (the key of the marker that declares the sum of that column).
 *   <li>{@code expected_population}: {@code none}.
 *   <li>{@code absence}: {@code {empty_cell: absent}}: an empty cell is an absent optional field
 *       and a missing required one.
 *   <li>{@code time}: {@code {zone: UTC, format: iso-instant-micros}}.
 *   <li>{@code columns}: a list of {@code {column | constant, to, enum, cast}}. {@code to} is a
 *       canonical path of the engine's bindings, or {@code attribute.<name>} for a value that the
 *       canonical event does not hold (a lookup key for enrichment). {@code cast} is {@code STRING}
 *       (the default for a text), {@code DECIMAL}, {@code BOOLEAN} or {@code INSTANT_UTC} and is
 *       required for a target of that type; the four enumeration targets take no cast and an
 *       optional {@code enum} list of their own names. {@code enum} on a text target is the closed
 *       set of values it may take.
 * </ul>
 *
 * <p>The digest is the SHA-256 of {@link Json#canonical} of the parsed tree, so comments and the
 * order of keys do not change it. Every refusal is a {@link MappingException} with a code and a
 * line, and no text of the file.
 */
public final class MappingPack {
  /** A mapping is refused: a code and the 1-based line, and nothing of the text. */
  public static final class MappingException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private final String code;
    private final int line;

    MappingException(String code, int line, String reason) {
      super("mapping line " + line + ": " + code + ": " + reason);
      this.code = code;
      this.line = line;
    }

    public String code() {
      return code;
    }

    public int line() {
      return line;
    }
  }

  /** How the text of a cell is read. */
  public enum Cast {
    STRING,
    DECIMAL,
    BOOLEAN,
    INSTANT_UTC,
    /** Implied for the four enumeration targets; never written in a file. */
    ENUM
  }

  /**
   * One column of the mapping.
   *
   * @param column the name of the source column, when the value comes from the file
   * @param constant the value, when it is the same on every row
   * @param to the target: a canonical path or {@code attribute.<name>}
   * @param allowed the closed set of values (empty: no restriction beyond the cast)
   * @param cast how the text is read
   * @param optional true when the target can be absent: an empty cell is then absent, not missing
   */
  public record Column(
      Optional<String> column,
      Optional<String> constant,
      String to,
      List<String> allowed,
      Cast cast,
      boolean optional) {
    public Column {
      Objects.requireNonNull(column, "column");
      Objects.requireNonNull(constant, "constant");
      Objects.requireNonNull(to, "to");
      allowed = List.copyOf(allowed);
      Objects.requireNonNull(cast, "cast");
    }
  }

  /** The control totals the mapping declares. */
  public record ControlTotals(String recordsKey, String sumColumn, String sumKey) {
    public ControlTotals {
      Objects.requireNonNull(recordsKey, "recordsKey");
      Objects.requireNonNull(sumColumn, "sumColumn");
      Objects.requireNonNull(sumKey, "sumKey");
    }
  }

  /** The canonical paths a column may give (the paths the engine binds). */
  public static final Set<String> TARGETS =
      Collections.unmodifiableSet(new TreeSet<>(CanonicalBindings.VARIABLES.keySet()));

  /** The targets that can be absent; every other target must be mapped. */
  public static final Set<String> OPTIONAL_TARGETS =
      Set.of(
          "client.lei",
          "trade.side",
          "trade.capacity",
          "trade.counterparty",
          "trade.executionWithin");

  /** The tuple of the identity of an event, the one the model hard-wires. */
  static final List<String> EVENT_IDENTITY = List.of("firm.lei", "venue.mic", "venue.executionId");

  private static final String ATTRIBUTE = "attribute.";
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
  private static final Pattern ID = Pattern.compile("[a-z][a-z0-9.-]{0,63}");
  private static final int MAX_COLUMNS = 64;
  private static final int MAX_ALLOWED = 64;

  private final String id;
  private final String sourceRecordIdColumn;
  private final String sourceVersionColumn;
  private final ControlTotals controlTotals;
  private final List<Column> columns;
  private final Set<String> requiredColumns;
  private final String canonicalJson;
  private final Sha256 digest;

  private MappingPack(
      String id,
      String sourceRecordIdColumn,
      String sourceVersionColumn,
      ControlTotals controlTotals,
      List<Column> columns,
      String canonicalJson) {
    this.id = id;
    this.sourceRecordIdColumn = sourceRecordIdColumn;
    this.sourceVersionColumn = sourceVersionColumn;
    this.controlTotals = controlTotals;
    this.columns = List.copyOf(columns);
    Set<String> required = new LinkedHashSet<>();
    required.add(sourceRecordIdColumn);
    required.add(sourceVersionColumn);
    for (Column column : columns) {
      column.column().ifPresent(required::add);
    }
    this.requiredColumns = Collections.unmodifiableSet(required);
    this.canonicalJson = canonicalJson;
    this.digest = Sha256.ofUtf8(canonicalJson);
  }

  public String id() {
    return id;
  }

  /** {@code <id>@<digest hex>}: the mapping version that a decision context carries. */
  public String version() {
    return id + "@" + digest.hex();
  }

  public Sha256 digest() {
    return digest;
  }

  /** The canonical JSON the digest covers. */
  public String canonicalJson() {
    return canonicalJson;
  }

  public String sourceRecordIdColumn() {
    return sourceRecordIdColumn;
  }

  public String sourceVersionColumn() {
    return sourceVersionColumn;
  }

  public ControlTotals controlTotals() {
    return controlTotals;
  }

  public List<Column> columns() {
    return columns;
  }

  /** Every source column the mapping reads, in the order it reads them: a header must hold all. */
  public Set<String> requiredColumns() {
    return requiredColumns;
  }

  @Override
  public String toString() {
    return "MappingPack[" + version() + "]";
  }

  /** The names of the enumeration of an enumeration target, or empty for any other target. */
  static List<String> enumNames(String target) {
    return switch (target) {
      case "event.kind" -> names(LifecycleKind.values());
      case "instrument.assetClass" -> names(AssetClass.values());
      case "client.kind" -> names(PartyKind.values());
      case "trade.side" -> names(Side.values());
      default -> List.of();
    };
  }

  private static List<String> names(Enum<?>[] values) {
    return Arrays.stream(values).map(Enum::name).toList();
  }

  /**
   * Reads a mapping.
   *
   * @throws MappingException when the text is not a mapping of this form
   */
  public static MappingPack parse(String yaml) {
    Objects.requireNonNull(yaml, "yaml");
    YamlNode root;
    try {
      root = StrictYaml.parse(yaml);
    } catch (PackSyntaxException e) {
      throw new MappingException("MAPPING_SYNTAX", e.line(), "not strict YAML (" + e.code() + ")");
    }
    return new Reader().read(root);
  }

  private static final class Reader {
    MappingPack read(YamlNode root) {
      YamlMap top = map(root, "the mapping");
      keys(
          top,
          Set.of(
              "mapping",
              "format",
              "source_record_id",
              "source_version",
              "event_identity",
              "control_totals",
              "expected_population",
              "absence",
              "time",
              "columns"),
          Set.of(
              "mapping",
              "format",
              "source_record_id",
              "source_version",
              "event_identity",
              "control_totals",
              "expected_population",
              "absence",
              "time",
              "columns"));
      YamlMap header = map(top.entries().get("mapping"), "mapping");
      keys(header, Set.of("id"), Set.of("id"));
      String id = scalar(header.entries().get("id"), "mapping id");
      if (!ID.matcher(id).matches()) {
        throw refused("MAPPING_VALUE", header.line(), "the mapping id has the wrong form");
      }
      fixed(top, "format", "csv");
      fixed(top, "expected_population", "none");
      fixedMap(top, "absence", Map.of("empty_cell", "absent"));
      fixedMap(top, "time", Map.of("zone", "UTC", "format", "iso-instant-micros"));
      String recordId = columnName(top, "source_record_id");
      String version = columnName(top, "source_version");
      YamlNode identity = top.entries().get("event_identity");
      List<String> tuple = new ArrayList<>();
      if (identity instanceof YamlList list) {
        for (YamlNode item : list.items()) {
          tuple.add(scalar(item, "an event identity part"));
        }
      }
      if (!(identity instanceof YamlList) || !tuple.equals(EVENT_IDENTITY)) {
        throw refused(
            "MAPPING_EVENT_IDENTITY",
            identity.line(),
            "the event identity must be the tuple the model hard-wires");
      }
      YamlMap totalsNode = map(top.entries().get("control_totals"), "control_totals");
      Set<String> totalKeys = Set.of("records_key", "sum_column", "sum_key");
      keys(totalsNode, totalKeys, totalKeys);
      ControlTotals totals =
          new ControlTotals(
              name(totalsNode, "records_key"),
              name(totalsNode, "sum_column"),
              name(totalsNode, "sum_key"));
      List<Column> columns = columns(top.entries().get("columns"));
      checkTargets(columns, top.entries().get("columns").line());
      boolean sumOk =
          columns.stream()
              .anyMatch(
                  c ->
                      c.column().equals(Optional.of(totals.sumColumn()))
                          && c.cast() == Cast.DECIMAL);
      if (!sumOk) {
        throw refused(
            "MAPPING_CONTROL_TOTALS",
            totalsNode.line(),
            "the sum column must be a mapped column with the cast DECIMAL");
      }
      return new MappingPack(id, recordId, version, totals, columns, Json.canonical(tree(root)));
    }

    private static void checkTargets(List<Column> columns, int line) {
      Set<String> mapped = new HashSet<>();
      for (Column column : columns) {
        mapped.add(column.to());
      }
      for (String target : TARGETS) {
        if (!OPTIONAL_TARGETS.contains(target) && !mapped.contains(target)) {
          throw refused(
              "MAPPING_TARGET_MISSING", line, "a required canonical target is not mapped");
        }
      }
    }

    private static List<Column> columns(YamlNode node) {
      if (!(node instanceof YamlList list) || list.items().isEmpty()) {
        throw refused("MAPPING_SHAPE", node.line(), "columns must be a non-empty list");
      }
      if (list.items().size() > MAX_COLUMNS) {
        throw refused("MAPPING_SHAPE", node.line(), "too many columns");
      }
      List<Column> columns = new ArrayList<>();
      Set<String> targets = new HashSet<>();
      for (YamlNode item : list.items()) {
        Column column = column(item);
        if (!targets.add(column.to())) {
          throw refused("MAPPING_TARGET_DUPLICATE", item.line(), "a target is mapped twice");
        }
        columns.add(column);
      }
      return columns;
    }

    private static Column column(YamlNode node) {
      YamlMap map = map(node, "a column");
      keys(map, Set.of("column", "constant", "to", "enum", "cast"), Set.of("to"));
      boolean fromFile = map.entries().containsKey("column");
      boolean constant = map.entries().containsKey("constant");
      if (fromFile == constant) {
        throw refused("MAPPING_COLUMN_SOURCE", map.line(), "a column needs a column or a constant");
      }
      String to = scalar(map.entries().get("to"), "to");
      boolean attribute = to.startsWith(ATTRIBUTE);
      if (attribute) {
        if (!NAME.matcher(to.substring(ATTRIBUTE.length())).matches()) {
          throw refused(
              "MAPPING_TARGET_UNKNOWN", map.line(), "an attribute name has the wrong form");
        }
      } else if (!TARGETS.contains(to)) {
        throw refused("MAPPING_TARGET_UNKNOWN", map.line(), "the target is not a canonical path");
      }
      List<String> enumNames = enumNames(to);
      Cast cast = castOf(map, to, !enumNames.isEmpty());
      List<String> allowed = allowed(map, enumNames, cast);
      Optional<String> columnName =
          fromFile ? Optional.of(name(map, "column")) : Optional.<String>empty();
      Optional<String> constantValue =
          constant
              ? Optional.of(scalar(map.entries().get("constant"), "constant"))
              : Optional.empty();
      Column column =
          new Column(
              columnName,
              constantValue,
              to,
              allowed,
              cast,
              attribute || OPTIONAL_TARGETS.contains(to));
      if (constantValue.isPresent()) {
        String text = constantValue.get();
        if (text.isEmpty() || !(Mapper.convert(column, text) instanceof Mapper.Ok)) {
          throw refused(
              "MAPPING_CONSTANT", map.line(), "the constant is not a value of its target");
        }
      }
      return column;
    }

    private static Cast castOf(YamlMap map, String to, boolean enumTarget) {
      Cast required = requiredCast(to);
      YamlNode node = map.entries().get("cast");
      if (node == null) {
        if (required == Cast.DECIMAL || required == Cast.BOOLEAN || required == Cast.INSTANT_UTC) {
          throw refused("MAPPING_CAST", map.line(), "the target needs an explicit cast");
        }
        return required;
      }
      String text = scalar(node, "cast");
      Cast cast =
          switch (text) {
            case "STRING" -> Cast.STRING;
            case "DECIMAL" -> Cast.DECIMAL;
            case "BOOLEAN" -> Cast.BOOLEAN;
            case "INSTANT_UTC" -> Cast.INSTANT_UTC;
            default -> throw refused("MAPPING_CAST", node.line(), "the cast is not known");
          };
      if (enumTarget || cast != required) {
        throw refused("MAPPING_CAST", node.line(), "the cast does not fit the target");
      }
      return cast;
    }

    private static Cast requiredCast(String to) {
      if (to.startsWith(ATTRIBUTE)) {
        return Cast.STRING;
      }
      return switch (CanonicalBindings.VARIABLES.get(to)) {
        case ENUM -> Cast.ENUM;
        case DECIMAL -> Cast.DECIMAL;
        case BOOL -> Cast.BOOLEAN;
        case TIMESTAMP -> Cast.INSTANT_UTC;
        default -> Cast.STRING;
      };
    }

    private static List<String> allowed(YamlMap map, List<String> enumNames, Cast cast) {
      YamlNode node = map.entries().get("enum");
      if (node == null) {
        return enumNames;
      }
      if (cast != Cast.STRING && cast != Cast.ENUM) {
        throw refused("MAPPING_ENUM", node.line(), "only a text or an enumeration takes an enum");
      }
      if (!(node instanceof YamlList list)
          || list.items().isEmpty()
          || list.items().size() > MAX_ALLOWED) {
        throw refused("MAPPING_ENUM", node.line(), "enum must be a non-empty list");
      }
      List<String> values = new ArrayList<>();
      for (YamlNode item : list.items()) {
        String value = scalar(item, "enum value");
        if (value.isBlank()
            || value.length() > 64
            || values.contains(value)
            || (!enumNames.isEmpty() && !enumNames.contains(value))) {
          throw refused("MAPPING_ENUM", item.line(), "an enum value is not allowed here");
        }
        values.add(value);
      }
      return values;
    }

    private static String columnName(YamlMap top, String key) {
      return name(top, key);
    }

    private static String name(YamlMap map, String key) {
      YamlNode node = map.entries().get(key);
      String value = scalar(node, key);
      if (!NAME.matcher(value).matches()) {
        throw refused("MAPPING_VALUE", node.line(), key + " has the wrong form");
      }
      return value;
    }

    private static void fixed(YamlMap top, String key, String expected) {
      YamlNode node = top.entries().get(key);
      if (!expected.equals(scalar(node, key))) {
        throw refused("MAPPING_VALUE", node.line(), key + " has a value this build does not read");
      }
    }

    private static void fixedMap(YamlMap top, String key, Map<String, String> expected) {
      YamlMap node = map(top.entries().get(key), key);
      keys(node, expected.keySet(), expected.keySet());
      for (Map.Entry<String, String> entry : expected.entrySet()) {
        if (!entry.getValue().equals(scalar(node.entries().get(entry.getKey()), key))) {
          throw refused(
              "MAPPING_VALUE", node.line(), key + " has a value this build does not read");
        }
      }
    }

    private static YamlMap map(YamlNode node, String what) {
      if (node instanceof YamlMap map) {
        return map;
      }
      throw refused("MAPPING_SHAPE", node == null ? 0 : node.line(), what + " must be a mapping");
    }

    private static String scalar(YamlNode node, String what) {
      if (node instanceof YamlScalar scalar) {
        return scalar.value();
      }
      throw refused("MAPPING_SHAPE", node == null ? 0 : node.line(), what + " must be a scalar");
    }

    private static void keys(YamlMap map, Set<String> allowed, Set<String> required) {
      for (String key : map.entries().keySet()) {
        if (!allowed.contains(key)) {
          throw refused("MAPPING_KEY_UNKNOWN", map.entries().get(key).line(), "an unknown key");
        }
      }
      for (String key : new TreeSet<>(required)) {
        if (!map.entries().containsKey(key)) {
          throw refused("MAPPING_KEY_MISSING", map.line(), "a required key is missing");
        }
      }
    }

    private static Object tree(YamlNode node) {
      return switch (node) {
        case YamlScalar scalar -> scalar.value();
        case YamlList list -> list.items().stream().map(Reader::tree).toList();
        case YamlMap map -> {
          Map<String, Object> out = new LinkedHashMap<>();
          map.entries().forEach((k, v) -> out.put(k, tree(v)));
          yield out;
        }
      };
    }

    private static MappingException refused(String code, int line, String reason) {
      return new MappingException(code, line, reason);
    }
  }
}
