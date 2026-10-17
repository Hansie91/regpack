package dev.regpack.engine.pack;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.canonical.AssetClass;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.model.canonical.PartyKind;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.model.CanonicalInputSpec;
import dev.regpack.pack.model.CanonicalInputSpec.Variable;
import dev.regpack.pack.model.CanonicalInputSpec.Variable.Source;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.Value.BoolValue;
import dev.regpack.ports.expr.Value.DecimalValue;
import dev.regpack.ports.expr.Value.EnumValue;
import dev.regpack.ports.expr.Value.StringValue;
import dev.regpack.ports.expr.Value.TimestampValue;
import dev.regpack.ports.expr.ValueType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class CanonicalBindingsTest {
  private static final Instant TRADED_AT = Instant.parse("2026-10-09T09:30:00.123456Z");

  private static CanonicalExecution execution(Optional<String> clientLei) {
    return new CanonicalExecution(
        "LEI-FIRM-0001",
        "XPAR",
        "EX-1",
        LifecycleKind.NOVATION,
        TRADED_AT,
        AssetClass.EQUITY,
        "ZZ0000000001",
        true,
        clientLei.isPresent() ? PartyKind.LEGAL_ENTITY : PartyKind.NATURAL_PERSON,
        clientLei,
        new BigDecimal("100.50"),
        new BigDecimal("12.250"),
        "EUR");
  }

  private static CanonicalBindings bindings() {
    return new CanonicalBindings(execution(Optional.of("LEI-CLIENT-0001")));
  }

  private static Variable variable(ValueType type, Source source) {
    return new Variable(
        type,
        type == ValueType.ENUM ? Optional.of("LifecycleKind") : Optional.empty(),
        source,
        false);
  }

  private static List<String> codes(List<PackProblem> problems) {
    return problems.stream().map(PackProblem::code).toList();
  }

  @Test
  void bindsEveryCataloguePathWithItsType() {
    CanonicalBindings bindings = bindings();
    assertThat(CanonicalBindings.VARIABLES).hasSize(13);
    for (Map.Entry<String, ValueType> entry : CanonicalBindings.VARIABLES.entrySet()) {
      Value value = bindings.get(entry.getKey()).orElseThrow();
      Class<? extends Value> expected =
          switch (entry.getValue()) {
            case BOOL -> BoolValue.class;
            case DECIMAL -> DecimalValue.class;
            case STRING -> StringValue.class;
            case TIMESTAMP -> TimestampValue.class;
            case ENUM -> EnumValue.class;
            default -> throw new AssertionError("unexpected catalogue type " + entry.getValue());
          };
      assertThat(value).as(entry.getKey()).isInstanceOf(expected);
    }
    assertThat(bindings.get("event.tradedAt")).contains(new TimestampValue(TRADED_AT));
    assertThat(bindings.get("instrument.tradedOnVenue")).contains(new BoolValue(true));
    assertThat(bindings.get("venue.mic")).contains(new StringValue("XPAR"));
    assertThat(bindings.get("venue.executionId")).contains(new StringValue("EX-1"));
    assertThat(bindings.get("firm.lei")).contains(new StringValue("LEI-FIRM-0001"));
    assertThat(bindings.get("instrument.isin")).contains(new StringValue("ZZ0000000001"));
    assertThat(bindings.get("trade.currency")).contains(new StringValue("EUR"));
    assertThat(bindings.get("client.lei")).contains(new StringValue("LEI-CLIENT-0001"));
  }

  @Test
  void decimalsKeepTheScaleOfTheInput() {
    CanonicalBindings bindings = bindings();
    DecimalValue quantity = (DecimalValue) bindings.get("trade.quantity").orElseThrow();
    DecimalValue price = (DecimalValue) bindings.get("trade.price").orElseThrow();
    assertThat(quantity.value()).isEqualTo(new BigDecimal("100.50"));
    assertThat(quantity.value().scale()).isEqualTo(2);
    assertThat(price.value()).isEqualTo(new BigDecimal("12.250"));
    assertThat(price.value().scale()).isEqualTo(3);
  }

  @Test
  void anAbsentClientLeiIsEmpty() {
    CanonicalBindings bindings = new CanonicalBindings(execution(Optional.empty()));
    assertThat(bindings.get("client.lei")).isEmpty();
    assertThat(bindings.get("client.kind")).isPresent();
  }

  @Test
  void anUnknownPathIsEmpty() {
    CanonicalBindings bindings = bindings();
    assertThat(bindings.get("client.nosuch")).isEmpty();
    assertThat(bindings.get("")).isEmpty();
    assertThat(bindings.get("state.status")).isEmpty();
    assertThat(bindings.get("EVENT.KIND")).isEmpty();
  }

  @Test
  void enumsCarryTheirTypeName() {
    CanonicalBindings bindings = bindings();
    assertThat(bindings.get("event.kind")).contains(new EnumValue("LifecycleKind", "NOVATION"));
    assertThat(bindings.get("client.kind")).contains(new EnumValue("PartyKind", "LEGAL_ENTITY"));
    assertThat(bindings.get("instrument.assetClass"))
        .contains(new EnumValue("AssetClass", "EQUITY"));
  }

  @Test
  void checkSupportedFlagsAnUnknownPathAndAWrongType() {
    Map<String, Variable> variables = new HashMap<>();
    variables.put("event.kind", variable(ValueType.ENUM, Source.EVENT));
    assertThat(CanonicalBindings.checkSupported(new CanonicalInputSpec(variables))).isEmpty();

    variables.put("event.unknown", variable(ValueType.STRING, Source.EVENT));
    variables.put("trade.price", variable(ValueType.STRING, Source.EVENT));
    variables.put("state.status", variable(ValueType.STRING, Source.STATE));
    List<PackProblem> problems =
        CanonicalBindings.checkSupported(new CanonicalInputSpec(variables));
    assertThat(codes(problems))
        .containsExactly("INPUT_PATH_UNSUPPORTED", "INPUT_PATH_UNSUPPORTED", "INPUT_TYPE_MISMATCH");
    assertThat(problems)
        .extracting(PackProblem::message)
        .anyMatch(m -> m.contains("event.unknown"));
    assertThat(problems).extracting(PackProblem::message).anyMatch(m -> m.contains("trade.price"));
    assertThat(problems).isSorted();

    // an ENUM variable bound to the wrong enumeration is a type mismatch too
    Map<String, Variable> wrongEnum =
        Map.of(
            "client.kind",
            new Variable(ValueType.ENUM, Optional.of("LifecycleKind"), Source.EVENT, false));
    assertThat(codes(CanonicalBindings.checkSupported(new CanonicalInputSpec(wrongEnum))))
        .containsExactly("INPUT_TYPE_MISMATCH");
  }

  @Test
  void catalogueEqualsTheSamplePackInput() {
    CanonicalInputSpec sample = SamplePacks.load().input();
    Map<String, ValueType> declared = new TreeMap<>();
    sample.variables().forEach((path, variable) -> declared.put(path, variable.type()));
    assertThat(CanonicalBindings.VARIABLES).isEqualTo(declared);
    assertThat(CanonicalBindings.checkSupported(sample)).isEmpty();
  }
}
