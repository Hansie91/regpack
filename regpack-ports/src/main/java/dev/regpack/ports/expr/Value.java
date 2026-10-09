package dev.regpack.ports.expr;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** A value of the expression language. */
public sealed interface Value {
  record BoolValue(boolean value) implements Value {}

  record IntValue(long value) implements Value {}

  record DecimalValue(BigDecimal value) implements Value {
    public DecimalValue {
      Objects.requireNonNull(value, "value");
    }
  }

  record StringValue(String value) implements Value {
    public StringValue {
      Objects.requireNonNull(value, "value");
    }
  }

  record TimestampValue(Instant value) implements Value {
    public TimestampValue {
      Objects.requireNonNull(value, "value");
    }
  }

  record DateValue(LocalDate value) implements Value {
    public DateValue {
      Objects.requireNonNull(value, "value");
    }
  }

  record EnumValue(String type, String name) implements Value {
    public EnumValue {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(name, "name");
    }
  }

  record ListValue(List<Value> items) implements Value {
    public ListValue {
      items = List.copyOf(items);
    }
  }
}
