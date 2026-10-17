package dev.regpack.engine.pack;

import dev.regpack.bench.data.TradeRecord;
import dev.regpack.ports.expr.Bindings;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.DateValue;
import dev.regpack.ports.expr.Value.DecimalValue;
import dev.regpack.ports.expr.Value.EnumValue;
import dev.regpack.ports.expr.Value.IntValue;
import dev.regpack.ports.expr.Value.StringValue;
import dev.regpack.ports.expr.Value.TimestampValue;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@link Bindings} of one synthetic {@link TradeRecord} for the throughput check. A path is
 * resolved on first use after {@link #reset(TradeRecord)} and the answer is kept by the ordinal of
 * the path, so a path read by many rules costs one {@link Value} per record and no map is built.
 * The paths are the 19 of {@link TradeRecord#fieldPaths()}; any other path is explicit absence.
 *
 * <p>Not thread-safe: use one instance per evaluating thread.
 */
final class TradeRecordBindings implements Bindings {
  private static final String LIFECYCLE_KIND = "LifecycleKind";
  private static final String ASSET_CLASS = "AssetClass";
  private static final String PARTY_KIND = "PartyKind";
  private static final Optional<Value> ABSENT = Optional.empty();
  private static final int PATHS = TradeRecord.fieldPaths().size();

  private final Optional<Value>[] cache = newCache();
  private final int[] stamp = new int[PATHS];
  private int generation;
  private TradeRecord record;

  @SuppressWarnings("unchecked")
  private static Optional<Value>[] newCache() {
    return (Optional<Value>[]) new Optional<?>[PATHS];
  }

  /** Binds the next record; everything read for the previous record is forgotten. */
  void reset(TradeRecord next) {
    this.record = Objects.requireNonNull(next, "next");
    generation++;
    if (generation == 0) {
      Arrays.fill(stamp, 0);
      generation = 1;
    }
  }

  @Override
  public Optional<Value> get(String path) {
    if (record == null) {
      throw new IllegalStateException("reset(record) was not called");
    }
    int ordinal = ordinal(path);
    if (ordinal < 0) {
      return ABSENT;
    }
    if (stamp[ordinal] != generation) {
      cache[ordinal] = load(ordinal);
      stamp[ordinal] = generation;
    }
    return cache[ordinal];
  }

  /** The position of the path in {@link TradeRecord#fieldPaths()}, or -1. */
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
      default -> throw new IllegalStateException("no such ordinal " + ordinal);
    };
  }
}
