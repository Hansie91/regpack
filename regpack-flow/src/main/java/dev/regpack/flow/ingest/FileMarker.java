package dev.regpack.flow.ingest;

import dev.regpack.model.KeyText;
import dev.regpack.model.Sha256;
import dev.regpack.model.json.Json;
import dev.regpack.model.json.JsonException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The completion marker of a source file: the file {@code <name>.csv.done}, a JSON object that says
 * the producer has finished writing {@code <name>.csv} and what the file is. Keys (a closed set; an
 * unknown or a repeated key makes the marker invalid): {@code file} (the name of the CSV, no
 * separator), {@code size} (bytes), {@code sha256} (64 lowercase hex), {@code records} (the number
 * of data rows the producer declares), and optionally {@code data_class} and {@code sum_quantity}
 * (a plain decimal text). The marker is a label the producer writes, not a control: it says what
 * the file claims to be, and the stages after it compare the claim with the bytes.
 *
 * @param file the name of the CSV the marker belongs to
 * @param records the declared number of data rows
 * @param size the declared size of the CSV in bytes
 * @param sha256 the declared digest of the CSV
 * @param dataClass the {@code data_class} when the marker has the key: the string itself, or for a
 *     value that is not a string its canonical JSON text, which is never a bare word
 * @param sumQuantity the declared sum of the quantities, a plain decimal text, when present
 */
public record FileMarker(
    String file,
    int records,
    long size,
    Sha256 sha256,
    Optional<String> dataClass,
    Optional<String> sumQuantity) {
  /** The largest marker that is read: a larger one is no valid marker. */
  public static final int MAX_BYTES = 4096;

  /** The name of the control total key of the quantity sum in the manifest. */
  public static final String SUM_QUANTITY = "sum_quantity";

  /** The name of the key of the data class in the manifest. */
  public static final String DATA_CLASS = "data_class";

  private static final Set<String> KEYS =
      Set.of("file", "size", "sha256", "records", DATA_CLASS, SUM_QUANTITY);
  private static final int MAX_FILE_NAME = 128;
  private static final String CSV_EXTENSION = ".csv";
  private static final int MAX_DECIMAL_CHARS = 40;

  public FileMarker {
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(sha256, "sha256");
    Objects.requireNonNull(dataClass, "dataClass");
    Objects.requireNonNull(sumQuantity, "sumQuantity");
  }

  /**
   * The control totals of the manifest of the batch this marker describes: {@code data_class} and
   * {@code sum_quantity} where the marker has them.
   */
  public Map<String, String> controlTotals() {
    Map<String, String> totals = new LinkedHashMap<>();
    dataClass.ifPresent(v -> totals.put(DATA_CLASS, v));
    sumQuantity.ifPresent(v -> totals.put(SUM_QUANTITY, v));
    return Map.copyOf(totals);
  }

  /**
   * True when {@code name} is a file name the source accepts (and a marker may name): at most 128
   * characters, ASCII letters, digits, dot, underscore and hyphen (none of the last three first),
   * no two dots in a row, and the extension exactly {@code .csv} in lower case (a name such as
   * {@code TRADES.CSV} is a name the source does not accept, so that it is reported as waiting and
   * not ignored).
   */
  static boolean validName(String name) {
    if (name.isEmpty() || name.length() > MAX_FILE_NAME || !name.endsWith(CSV_EXTENSION)) {
      return false;
    }
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      boolean alnum = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9';
      if (!(alnum || i > 0 && (c == '.' || c == '_' || c == '-'))) {
        return false;
      }
    }
    return !name.contains("..");
  }

  /**
   * Reads a marker.
   *
   * @param bytes the content of the marker file
   * @param csvName the name of the CSV the marker must name
   * @return the marker, or empty when the bytes are no valid marker for that file: not UTF-8, over
   *     {@link #MAX_BYTES}, not one JSON object, an unknown, repeated or missing key, a value of
   *     another type or form, or a {@code file} other than {@code csvName}. Never throws for the
   *     content of {@code bytes}.
   */
  public static Optional<FileMarker> parse(byte[] bytes, String csvName) {
    Objects.requireNonNull(bytes, "bytes");
    Objects.requireNonNull(csvName, "csvName");
    if (bytes.length == 0 || bytes.length > MAX_BYTES || !validName(csvName)) {
      return Optional.empty();
    }
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException e) {
      return Optional.empty();
    }
    Object parsed;
    try {
      parsed = Json.parse(text);
    } catch (JsonException | IllegalArgumentException e) {
      return Optional.empty();
    }
    if (!(parsed instanceof Map<?, ?> map) || !KEYS.containsAll(map.keySet())) {
      return Optional.empty();
    }
    Object file = map.get("file");
    Object records = map.get("records");
    Object size = map.get("size");
    Object sha = map.get("sha256");
    if (!csvName.equals(file)
        || !(records instanceof Long r)
        || r < 0
        || r > Integer.MAX_VALUE
        || !(size instanceof Long s)
        || s < 0
        || !(sha instanceof String hex)
        || !lowerHex64(hex)) {
      return Optional.empty();
    }
    Optional<String> sum = Optional.empty();
    if (map.containsKey(SUM_QUANTITY)) {
      if (!(map.get(SUM_QUANTITY) instanceof String plain) || !plainDecimal(plain)) {
        return Optional.empty();
      }
      sum = Optional.of(plain);
    }
    Optional<String> dataClass = Optional.empty();
    if (map.containsKey(DATA_CLASS)) {
      Object value = map.get(DATA_CLASS);
      String shown = value instanceof String str ? str : Json.canonical(value);
      try {
        KeyText.require("data_class", shown);
      } catch (IllegalArgumentException e) {
        return Optional.empty();
      }
      dataClass = Optional.of(shown);
    }
    return Optional.of(new FileMarker(csvName, r.intValue(), s, new Sha256(hex), dataClass, sum));
  }

  private static boolean lowerHex64(String hex) {
    if (hex.length() != 64) {
      return false;
    }
    for (int i = 0; i < hex.length(); i++) {
      char c = hex.charAt(i);
      if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) {
        return false;
      }
    }
    return true;
  }

  /** {@code -?digits(.digits)?}, at most {@value #MAX_DECIMAL_CHARS} characters. */
  static boolean plainDecimal(String text) {
    int length = text.length();
    if (length == 0 || length > MAX_DECIMAL_CHARS) {
      return false;
    }
    int i = text.charAt(0) == '-' ? 1 : 0;
    int integerStart = i;
    while (i < length && isDigit(text.charAt(i))) {
      i++;
    }
    if (i == integerStart) {
      return false;
    }
    if (i == length) {
      return true;
    }
    if (text.charAt(i) != '.') {
      return false;
    }
    i++;
    int fractionStart = i;
    while (i < length && isDigit(text.charAt(i))) {
      i++;
    }
    return i > fractionStart && i == length;
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  @Override
  public String toString() {
    return "FileMarker[records=" + records + ", size=" + size + "]";
  }
}
