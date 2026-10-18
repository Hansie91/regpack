package dev.regpack.spikes.r.table;

import static dev.regpack.spikes.r.table.TestRecords.record;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.bench.data.TradeRecord;
import dev.regpack.bench.data.TradeRecordGen;
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
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TradeBindingsTest {
  private static boolean hasType(Value value, ValueType type) {
    return switch (type) {
      case BOOL -> value instanceof BoolValue;
      case INT -> value instanceof IntValue;
      case DECIMAL -> value instanceof DecimalValue;
      case STRING -> value instanceof StringValue;
      case TIMESTAMP -> value instanceof TimestampValue;
      case DATE -> value instanceof DateValue;
      case ENUM -> value instanceof EnumValue;
      case LIST -> value instanceof ListValue;
    };
  }

  @Test
  void everyFieldPathHasADeclaredTypeAndABoundValueOfThatType() {
    assertThat(TradeBindings.VARIABLES.keySet())
        .containsExactlyInAnyOrderElementsOf(TradeRecord.fieldPaths());
    TradeBindings bindings = new TradeBindings();
    TradeRecordGen gen = new TradeRecordGen(5L);
    for (int i = 0; i < 200; i++) {
      TradeRecord record = gen.next();
      bindings.reset(record);
      for (String path : TradeRecord.fieldPaths()) {
        Optional<Value> value = bindings.get(path);
        boolean optional = path.equals("client.lei") || path.equals("trader.id");
        if (optional && record.valueAt(path).isEmpty()) {
          assertThat(value).as(path).isEmpty();
        } else {
          assertThat(value).as(path).isPresent();
          assertThat(hasType(value.get(), TradeBindings.VARIABLES.get(path))).as(path).isTrue();
        }
      }
    }
  }

  @Test
  void valuesCarryTheRecordContent() {
    TradeBindings bindings = new TradeBindings();
    TradeRecord record = record(9, "NOVATION", "EQUITY", true, "LEGAL_ENTITY", "XAMS");
    bindings.reset(record);

    assertThat(bindings.get("event.kind")).contains(new EnumValue("LifecycleKind", "NOVATION"));
    assertThat(bindings.get("instrument.assetClass"))
        .contains(new EnumValue("AssetClass", "EQUITY"));
    assertThat(bindings.get("client.kind")).contains(new EnumValue("PartyKind", "LEGAL_ENTITY"));
    assertThat(bindings.get("venue.mic")).contains(new StringValue("XAMS"));
    assertThat(bindings.get("instrument.tradedOnVenue")).contains(new BoolValue(true));
    assertThat(bindings.get("trade.price")).contains(new DecimalValue(new BigDecimal("12.25")));
    assertThat(bindings.get("event.sourceVersion")).contains(new IntValue(1));
    assertThat(bindings.get("client.lei")).contains(new StringValue("C0000000000000000001"));
  }

  @Test
  void absentOptionalsUnderlyingsAndUnknownPathsAreExplicitAbsence() {
    TradeBindings bindings = new TradeBindings();
    bindings.reset(record(1, "NEW", "EQUITY", true, "NATURAL_PERSON"));
    assertThat(bindings.get("client.lei")).isEmpty();
    assertThat(bindings.get("instrument.underlyings")).isEmpty();
    assertThat(bindings.get("client.parent.lei")).isEmpty();
    assertThat(bindings.get("")).isEmpty();
  }

  @Test
  void underlyingsAreAListWhenPresent() {
    TradeRecordGen gen = new TradeRecordGen(1L);
    TradeBindings bindings = new TradeBindings();
    boolean seen = false;
    for (int i = 0; i < 2_000 && !seen; i++) {
      TradeRecord record = gen.next();
      if (!record.underlyings().isEmpty()) {
        bindings.reset(record);
        Value value = bindings.get("instrument.underlyings").orElseThrow();
        assertThat(((ListValue) value).items()).hasSameSizeAs(record.underlyings());
        seen = true;
      }
    }
    assertThat(seen).isTrue();
  }

  @Test
  void aValueIsCachedUntilTheNextResetAndThenReflectsTheNewRecord() {
    TradeBindings bindings = new TradeBindings();
    bindings.reset(record(1, "NEW", "EQUITY", true, "LEGAL_ENTITY"));
    Optional<Value> first = bindings.get("event.kind");
    assertThat(bindings.get("event.kind")).isSameAs(first);

    bindings.reset(record(2, "AMEND", "DEBT", false, "LEGAL_ENTITY"));
    assertThat(bindings.get("event.kind")).contains(new EnumValue("LifecycleKind", "AMEND"));
    assertThat(bindings.get("instrument.tradedOnVenue")).contains(new BoolValue(false));

    // an absent value is cached as absent and does not leak into the next record
    bindings.reset(record(3, "NEW", "EQUITY", true, "NATURAL_PERSON"));
    assertThat(bindings.get("client.lei")).isEmpty();
    bindings.reset(record(4, "NEW", "EQUITY", true, "LEGAL_ENTITY"));
    assertThat(bindings.get("client.lei")).isPresent();
  }

  @Test
  void readingBeforeTheFirstResetIsAnError() {
    TradeBindings bindings = new TradeBindings();
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> bindings.get("event.kind"))
        .isInstanceOf(IllegalStateException.class);
  }
}
