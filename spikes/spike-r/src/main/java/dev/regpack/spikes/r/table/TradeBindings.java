package dev.regpack.spikes.r.table;

import dev.regpack.bench.data.TradeRecord;
import dev.regpack.ports.expr.Bindings;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The bindings of one {@link TradeRecord}. A path is resolved on first use after {@link
 * #reset(TradeRecord)} and the answer is kept by the path's ordinal, so a rule that reads the same
 * path twice, or many rules that read it, pay for one {@link Value} per record and no map is built.
 * Unknown paths are explicit absence. Not thread-safe: one instance per evaluating thread.
 */
public final class TradeBindings implements Bindings {
  /** The declared type of every readable field path, as the layers are compiled against them. */
  public static final Map<String, ValueType> VARIABLES =
      Map.ofEntries(
          Map.entry("event.kind", ValueType.ENUM),
          Map.entry("event.tradedAt", ValueType.TIMESTAMP),
          Map.entry("event.sourceVersion", ValueType.INT),
          Map.entry("venue.mic", ValueType.STRING),
          Map.entry("instrument.isin", ValueType.STRING),
          Map.entry("instrument.cfi", ValueType.STRING),
          Map.entry("instrument.assetClass", ValueType.ENUM),
          Map.entry("instrument.tradedOnVenue", ValueType.BOOL),
          Map.entry("trade.price", ValueType.DECIMAL),
          Map.entry("trade.quantity", ValueType.DECIMAL),
          Map.entry("trade.currency", ValueType.STRING),
          Map.entry("trade.side", ValueType.STRING),
          Map.entry("trade.capacity", ValueType.STRING),
          Map.entry("trade.tradeDate", ValueType.DATE),
          Map.entry("trade.settlementDate", ValueType.DATE),
          Map.entry("client.kind", ValueType.ENUM),
          Map.entry("client.lei", ValueType.STRING),
          Map.entry("firm.lei", ValueType.STRING),
          Map.entry("trader.id", ValueType.STRING));

  private static final String LIFECYCLE_KIND = "LifecycleKind";
  private static final String ASSET_CLASS = "AssetClass";
  private static final String PARTY_KIND = "PartyKind";
  private static final int PATH_COUNT = 20;

  private static final Optional<Value> ABSENT = Optional.empty();

  private final Object[] cache = new Object[PATH_COUNT];
  private final int[] stamp = new int[PATH_COUNT];
  private int generation;
  private TradeRecord record;

  /** Binds the next record; everything read for the previous record is forgotten. */
  public void reset(TradeRecord next) {
    this.record = Objects.requireNonNull(next, "record");
    generation++;
    if (generation == 0) {
      Arrays.fill(stamp, 0);
      generation = 1;
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public Optional<Value> get(String path) {
    if (record == null) {
      throw new IllegalStateException("reset(record) was not called");
    }
    int ordinal = ordinal(path);
    if (ordinal < 0) {
      return ABSENT;
    }
    if (stamp[ordinal] == generation) {
      return (Optional<Value>) cache[ordinal];
    }
    Optional<Value> value = load(ordinal);
    cache[ordinal] = value;
    stamp[ordinal] = generation;
    return value;
  }

  private static int ordinal(String path) {
    return switch (path) {
      case "event.kind" -> 0;
      case "event.tradedAt" -> 1;
      case "event.sourceVersion" -> 2;
      case "venue.mic" -> 3;
      case "instrument.isin" -> 4;
      case "instrument.cfi" -> 5;
      case "instrument.assetClass" -> 6;
      case "instrument.tradedOnVenue" -> 7;
      case "trade.price" -> 8;
      case "trade.quantity" -> 9;
      case "trade.currency" -> 10;
      case "trade.side" -> 11;
      case "trade.capacity" -> 12;
      case "trade.tradeDate" -> 13;
      case "trade.settlementDate" -> 14;
      case "client.kind" -> 15;
      case "client.lei" -> 16;
      case "firm.lei" -> 17;
      case "trader.id" -> 18;
      case "instrument.underlyings" -> 19;
      default -> -1;
    };
  }

  private Optional<Value> load(int ordinal) {
    TradeRecord r = record;
    return switch (ordinal) {
      case 0 -> Optional.of(new EnumValue(LIFECYCLE_KIND, r.eventKind()));
      case 1 -> Optional.of(new TimestampValue(r.tradedAt()));
      case 2 -> Optional.of(new IntValue(r.sourceVersion()));
      case 3 -> Optional.of(new StringValue(r.venueMic()));
      case 4 -> Optional.of(new StringValue(r.isin()));
      case 5 -> Optional.of(new StringValue(r.cfi()));
      case 6 -> Optional.of(new EnumValue(ASSET_CLASS, r.assetClass()));
      case 7 -> Optional.of(new BoolValue(r.tradedOnVenue()));
      case 8 -> Optional.of(new DecimalValue(r.price()));
      case 9 -> Optional.of(new DecimalValue(r.quantity()));
      case 10 -> Optional.of(new StringValue(r.currency()));
      case 11 -> Optional.of(new StringValue(r.side()));
      case 12 -> Optional.of(new StringValue(r.capacity()));
      case 13 -> Optional.of(new DateValue(r.tradeDate()));
      case 14 -> Optional.of(new DateValue(r.settlementDate()));
      case 15 -> Optional.of(new EnumValue(PARTY_KIND, r.clientKind()));
      case 16 -> r.clientLei().map(StringValue::new);
      case 17 -> Optional.of(new StringValue(r.firmLei()));
      case 18 -> r.traderId().map(StringValue::new);
      case 19 -> underlyings(r.underlyings());
      default -> throw new IllegalStateException("no such ordinal " + ordinal);
    };
  }

  private static Optional<Value> underlyings(List<String> items) {
    if (items.isEmpty()) {
      return ABSENT;
    }
    List<Value> values = new ArrayList<>(items.size());
    for (String item : items) {
      values.add(new StringValue(item));
    }
    return Optional.of(new ListValue(values));
  }
}
