package dev.regpack.flow.ingest;

import dev.regpack.flow.ingest.MappingPack.Column;
import dev.regpack.model.KeyText;
import dev.regpack.model.canonical.AssetClass;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.model.canonical.PartyKind;
import dev.regpack.model.canonical.Side;
import dev.regpack.model.decision.AsAtRange;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Maps one row of a source file to a {@link MappedRecord} by a {@link MappingPack}, or says why the
 * row cannot be mapped. The row is one unit: the first column in mapping order that fails
 * quarantines the whole row, with the line, the name of the column and a code, and never the value
 * (SF4, SF8). {@link #map} does not throw for a bad value; a bad cell can only produce a {@link
 * Quarantined}.
 *
 * <p>Codes: {@code MISSING} (an empty or absent cell of a required field), {@code KEY_TEXT} (a text
 * that is blank, too long, holds a control character or an unpaired surrogate, or breaks the event
 * identity), {@code ENUM} (a value outside the closed set of its column), {@code DECIMAL}, {@code
 * BOOLEAN}, {@code INSTANT_FORMAT}, {@code AS_AT_RANGE} (an instant outside 2000 to 2199 UTC) and
 * {@code VERSION}.
 */
public final class Mapper {
  /** The result of mapping one row. */
  public sealed interface Result permits Mapped, Quarantined {}

  /**
   * A row that mapped.
   *
   * @param record the record
   * @param quantity the value of the column that the control total sums
   */
  public record Mapped(MappedRecord record, BigDecimal quantity) implements Result {
    public Mapped {
      Objects.requireNonNull(record, "record");
      Objects.requireNonNull(quantity, "quantity");
    }

    @Override
    public String toString() {
      return "Mapped[]";
    }
  }

  /**
   * A row that did not map.
   *
   * @param line the 1-based line of the row in the file
   * @param column the name of the column that failed
   * @param code why
   */
  public record Quarantined(int line, String column, String code) implements Result {
    public Quarantined {
      Objects.requireNonNull(column, "column");
      Objects.requireNonNull(code, "code");
    }
  }

  /** The conversion of one cell (internal). */
  sealed interface Conv permits Ok, Bad {}

  record Ok(Object value) implements Conv {
    /** No value (SF8). */
    @Override
    public String toString() {
      return "Ok[]";
    }
  }

  record Bad(String code) implements Conv {}

  private static final int MAX_DECIMAL_CHARS = 32;
  private static final int MAX_DECIMAL_DIGITS = 20;
  private static final int MAX_DECIMAL_FRACTION = 10;

  private final MappingPack pack;

  public Mapper(MappingPack pack) {
    this.pack = Objects.requireNonNull(pack, "pack");
  }

  /** The columns the mapping reads that the header does not have, in the mapping's order. */
  public List<String> missingColumns(List<String> header) {
    Objects.requireNonNull(header, "header");
    List<String> missing = new ArrayList<>();
    for (String column : pack.requiredColumns()) {
      if (!header.contains(column)) {
        missing.add(column);
      }
    }
    return missing;
  }

  /**
   * Maps one row.
   *
   * @param line the 1-based line of the row in the file, for the result
   * @param header the column names
   * @param cells the cells of the row, as many as the header
   */
  public Result map(int line, List<String> header, List<String> cells) {
    Objects.requireNonNull(header, "header");
    Objects.requireNonNull(cells, "cells");
    String recordId = pack.sourceRecordIdColumn();
    String idCell = cell(header, cells, recordId).orElse("");
    if (idCell.isEmpty()) {
      return new Quarantined(line, recordId, "MISSING");
    }
    try {
      KeyText.require("source record id", idCell);
    } catch (IllegalArgumentException e) {
      return new Quarantined(line, recordId, "KEY_TEXT");
    }
    if (idCell.isBlank()) {
      return new Quarantined(line, recordId, "KEY_TEXT");
    }
    String versionColumn = pack.sourceVersionColumn();
    String versionCell = cell(header, cells, versionColumn).orElse("");
    if (versionCell.isEmpty()) {
      return new Quarantined(line, versionColumn, "MISSING");
    }
    long version = version(versionCell);
    if (version < 0) {
      return new Quarantined(line, versionColumn, "VERSION");
    }
    Map<String, Object> values = new HashMap<>();
    Map<String, String> attributes = new TreeMap<>();
    Optional<BigDecimal> quantity = Optional.empty();
    for (Column column : pack.columns()) {
      String name = column.column().orElse(column.to());
      // a column the header lacks is MISSING even when optional; an empty cell only when required
      Optional<String> found =
          column.constant().isPresent() ? column.constant() : cell(header, cells, name);
      if (found.isEmpty() || (found.get().isEmpty() && !column.optional())) {
        return new Quarantined(line, name, "MISSING");
      }
      String text = found.get();
      if (text.isEmpty()) {
        continue;
      }
      Conv conv = convert(column, text);
      if (conv instanceof Bad bad) {
        return new Quarantined(line, name, bad.code());
      }
      Object value = ((Ok) conv).value();
      if (column.to().startsWith("attribute.")) {
        attributes.put(column.to().substring("attribute.".length()), (String) value);
      } else {
        values.put(column.to(), value);
      }
      if (column.column().equals(Optional.of(pack.controlTotals().sumColumn()))) {
        quantity = Optional.of((BigDecimal) value);
      }
    }
    try {
      CanonicalExecution execution = execution(values);
      // the whole identity is bounded too: the parts can each pass and the tuple still not
      execution.eventIdentity();
      return new Mapped(
          new MappedRecord(idCell, version, execution, attributes), quantity.orElseThrow());
    } catch (IllegalArgumentException e) {
      return new Quarantined(line, columnOf("venue.executionId"), "KEY_TEXT");
    }
  }

  private String columnOf(String target) {
    for (Column column : pack.columns()) {
      if (column.to().equals(target)) {
        return column.column().orElse(target);
      }
    }
    return target;
  }

  /** The cell of the column; empty when the header lacks the column or the row is short. */
  private static Optional<String> cell(List<String> header, List<String> cells, String name) {
    int index = header.indexOf(name);
    return index < 0 || index >= cells.size() ? Optional.empty() : Optional.of(cells.get(index));
  }

  @SuppressWarnings("unchecked")
  private static CanonicalExecution execution(Map<String, Object> v) {
    return new CanonicalExecution(
        (String) v.get("firm.lei"),
        (String) v.get("venue.mic"),
        (String) v.get("venue.executionId"),
        (LifecycleKind) v.get("event.kind"),
        (Instant) v.get("event.tradedAt"),
        (AssetClass) v.get("instrument.assetClass"),
        (String) v.get("instrument.isin"),
        (Boolean) v.get("instrument.tradedOnVenue"),
        (PartyKind) v.get("client.kind"),
        Optional.ofNullable((String) v.get("client.lei")),
        (BigDecimal) v.get("trade.quantity"),
        (BigDecimal) v.get("trade.price"),
        (String) v.get("trade.currency"),
        Optional.ofNullable((Side) v.get("trade.side")),
        Optional.ofNullable((String) v.get("trade.capacity")),
        Optional.ofNullable((String) v.get("trade.counterparty")),
        Optional.ofNullable((String) v.get("trade.executionWithin")));
  }

  // ------------------------------------------------------------------------------------------
  // The conversion of one cell. Shared with the mapping pack, which converts its constants.

  /** Reads {@code text}, not empty, as the value of {@code column}. */
  static Conv convert(Column column, String text) {
    return switch (column.cast()) {
      case STRING -> text(column, text);
      case ENUM -> enumeration(column, text);
      case DECIMAL -> decimal(text);
      case BOOLEAN ->
          text.equals("true")
              ? new Ok(Boolean.TRUE)
              : text.equals("false") ? new Ok(Boolean.FALSE) : new Bad("BOOLEAN");
      case INSTANT_UTC -> instant(text);
    };
  }

  private static Conv text(Column column, String text) {
    if (!column.allowed().isEmpty() && !column.allowed().contains(text)) {
      return new Bad("ENUM");
    }
    if (text.isBlank()) {
      return new Bad("KEY_TEXT");
    }
    try {
      KeyText.require("cell", text);
    } catch (IllegalArgumentException e) {
      return new Bad("KEY_TEXT");
    }
    // the separator of the event identity is not a character of a part of it; the mapping reader
    // converts its constants with this method too, so a constant is refused when it is read
    if (MappingPack.EVENT_IDENTITY.contains(column.to()) && text.indexOf('|') >= 0) {
      return new Bad("KEY_TEXT");
    }
    return new Ok(text);
  }

  private static Conv enumeration(Column column, String text) {
    if (!column.allowed().contains(text)) {
      return new Bad("ENUM");
    }
    return switch (column.to()) {
      case "event.kind" -> new Ok(LifecycleKind.valueOf(text));
      case "instrument.assetClass" -> new Ok(AssetClass.valueOf(text));
      case "client.kind" -> new Ok(PartyKind.valueOf(text));
      case "trade.side" -> new Ok(Side.valueOf(text));
      default -> new Bad("ENUM");
    };
  }

  /**
   * {@code -?digits(.digits)?}, at most 20 digits in all and 10 after the point, no exponent. The
   * text is checked against this by hand before {@code new BigDecimal} is called: the length first,
   * so that a cell of any size costs one comparison.
   */
  private static Conv decimal(String text) {
    int length = text.length();
    if (length > MAX_DECIMAL_CHARS) {
      return new Bad("DECIMAL");
    }
    int i = length > 0 && text.charAt(0) == '-' ? 1 : 0;
    int integerDigits = 0;
    while (i < length && isDigit(text.charAt(i))) {
      i++;
      integerDigits++;
    }
    if (integerDigits == 0) {
      return new Bad("DECIMAL");
    }
    int fractionDigits = 0;
    if (i < length) {
      if (text.charAt(i) != '.') {
        return new Bad("DECIMAL");
      }
      i++;
      while (i < length && isDigit(text.charAt(i))) {
        i++;
        fractionDigits++;
      }
      if (fractionDigits == 0 || i != length) {
        return new Bad("DECIMAL");
      }
    }
    if (integerDigits + fractionDigits > MAX_DECIMAL_DIGITS
        || fractionDigits > MAX_DECIMAL_FRACTION) {
      return new Bad("DECIMAL");
    }
    return new Ok(new BigDecimal(text));
  }

  /**
   * {@code yyyy-MM-ddTHH:mm:ss(.f{1,6})?Z} in upper case, checked by hand first; then {@link
   * Instant#parse}, which on its own accepts an offset, a lower-case designator, up to nine
   * fraction digits and 24:00; then the supported range of an {@code asAt}.
   */
  private static Conv instant(String text) {
    if (!instantShape(text)) {
      return new Bad("INSTANT_FORMAT");
    }
    Instant parsed;
    try {
      parsed = Instant.parse(text);
    } catch (DateTimeParseException e) {
      return new Bad("INSTANT_FORMAT");
    }
    if (!AsAtRange.contains(parsed)) {
      return new Bad("AS_AT_RANGE");
    }
    return new Ok(parsed);
  }

  private static boolean instantShape(String t) {
    int n = t.length();
    if (n < 20 || n > 27) {
      return false;
    }
    for (int i : new int[] {0, 1, 2, 3, 5, 6, 8, 9, 11, 12, 14, 15, 17, 18}) {
      if (!isDigit(t.charAt(i))) {
        return false;
      }
    }
    if (t.charAt(4) != '-'
        || t.charAt(7) != '-'
        || t.charAt(10) != 'T'
        || t.charAt(13) != ':'
        || t.charAt(16) != ':') {
      return false;
    }
    if (twoDigits(t, 11) > 23 || twoDigits(t, 14) > 59 || twoDigits(t, 17) > 59) {
      return false;
    }
    if (t.charAt(n - 1) != 'Z') {
      return false;
    }
    if (n == 20) {
      return true;
    }
    if (t.charAt(19) != '.' || n == 21) {
      return false;
    }
    for (int i = 20; i < n - 1; i++) {
      if (!isDigit(t.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static int twoDigits(String t, int at) {
    return (t.charAt(at) - '0') * 10 + (t.charAt(at + 1) - '0');
  }

  /** The version, or -1 when the text is not a non-negative number that fits a long. */
  private static long version(String text) {
    if (text.length() > 19) {
      return -1;
    }
    long value = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (!isDigit(c)) {
        return -1;
      }
      int digit = c - '0';
      if (value > (Long.MAX_VALUE - digit) / 10) {
        return -1;
      }
      value = value * 10 + digit;
    }
    return value;
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }
}
