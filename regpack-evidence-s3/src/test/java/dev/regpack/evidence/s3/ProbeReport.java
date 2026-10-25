package dev.regpack.evidence.s3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The read-out of the probe as one workflow command. Each item is {@code P<n>=<status>/<S3
 * Code>/<observed>}; the items are joined by an escaped line break into the single annotation
 * {@code ::warning title=probe-results::}, so that the service's answers reach the check-run
 * listing without a second command being possible.
 *
 * <p>Only three kinds of text get in: a numeric status (or the word {@code ERROR} for an item that
 * failed), an S3 code that matches {@code [A-Za-z0-9]{1,64}} (else {@code invalid-code}; none is
 * {@code none}), and an observed value reduced to {@code [A-Za-z0-9 ._:=,;/+-]} up to 120
 * characters, every other character becoming {@code ?}. No body, request header or Authorization
 * value is ever passed in, and what is reduced cannot hold a percent sign, a carriage return or a
 * line feed; the escape of those three is the last step of the line and the only place the
 * separator comes from. Each item is cut at 300 characters and the whole line is under 60,000.
 */
final class ProbeReport {
  static final String COMMAND = "::warning title=probe-results::";

  private static final Pattern CODE = Pattern.compile("[A-Za-z0-9]{1,64}");
  private static final int MAX_OBSERVED = 120;
  private static final int MAX_ITEM = 300;
  private static final int MAX_LINE = 60_000;

  private record Item(String status, String code, String observed) {}

  private final Map<Integer, Item> items = new TreeMap<>();

  /** Records item {@code number} (1 to 99) with a numeric HTTP status; once per number. */
  synchronized void add(int number, int status, String code, String observed) {
    if (status < 0) {
      throw new IllegalArgumentException("a status is not negative");
    }
    put(number, Integer.toString(status), code, observed);
  }

  /** Records an item that failed: status {@code ERROR}, the S3 code if there was one. */
  synchronized void addError(int number, String code, String observed) {
    put(number, "ERROR", code, observed);
  }

  private void put(int number, String status, String code, String observed) {
    if (number < 1 || number > 99) {
      throw new IllegalArgumentException("an item number is 1 to 99");
    }
    if (items.containsKey(number)) {
      throw new IllegalStateException("item P" + number + " is already recorded");
    }
    items.put(number, new Item(status, cleanCode(code), reduce(observed)));
  }

  /** The numbers of the recorded items, ascending. */
  synchronized List<Integer> recorded() {
    return List.copyOf(items.keySet());
  }

  /** The status of a recorded item: digits, or {@code ERROR}. */
  synchronized String status(int number) {
    Item item = items.get(number);
    if (item == null) {
      throw new IllegalStateException("item P" + number + " is not recorded");
    }
    return item.status();
  }

  /** The items as plain lines, in item order, each cut at 300 characters. */
  synchronized List<String> fileLines() {
    List<String> lines = new ArrayList<>();
    for (Map.Entry<Integer, Item> e : items.entrySet()) {
      Item item = e.getValue();
      String text =
          "P" + e.getKey() + "=" + item.status() + "/" + item.code() + "/" + item.observed();
      lines.add(text.length() > MAX_ITEM ? text.substring(0, MAX_ITEM) : text);
    }
    return lines;
  }

  /** The one workflow command: all items, one line. */
  synchronized String line() {
    String line = COMMAND + escape(String.join("\n", fileLines()));
    if (line.length() >= MAX_LINE) {
      throw new IllegalStateException("the probe line is too long");
    }
    return line;
  }

  private static String cleanCode(String code) {
    if (code == null) {
      return "none";
    }
    return CODE.matcher(code).matches() ? code : "invalid-code";
  }

  /**
   * Keeps {@code [A-Za-z0-9 ._:=,;/+-]}, turns every other character into {@code ?}, cuts at 120.
   */
  static String reduce(String observed) {
    String text = observed == null ? "" : observed;
    StringBuilder out = new StringBuilder();
    text.codePoints()
        .forEach(
            cp -> {
              if (out.length() < MAX_OBSERVED) {
                out.append(allowed(cp) ? (char) cp : '?');
              }
            });
    return out.toString();
  }

  private static boolean allowed(int cp) {
    return (cp >= 'A' && cp <= 'Z')
        || (cp >= 'a' && cp <= 'z')
        || (cp >= '0' && cp <= '9')
        || " ._:=,;/+-".indexOf(cp) >= 0;
  }

  /** The escape of a workflow command's data: percent first, then CR and LF. */
  private static String escape(String text) {
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
  }
}
