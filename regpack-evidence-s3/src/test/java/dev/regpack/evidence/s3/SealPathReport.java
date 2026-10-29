package dev.regpack.evidence.s3;

import dev.regpack.evidence.s3.RecordingTransport.Kind;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The figure of OQ-1 as one workflow command per binding (P3c1 T15-2): the requests that one {@code
 * DurableSeal.seal} sends to the object store, by kind, and the elapsed time, in four cases (new
 * bytes and bytes that are already sealed, each without and with the retention registrar).
 *
 * <p>The line is {@code ::notice title=oq1-seal-path::binding=<binding>; case=<case> requests=<n>
 * ms=<n> kinds=<kind>:<n>,...; case=...}. The vocabulary is closed: a binding is {@code raw} or
 * {@code encrypted}, a case is one of the four names below, a kind is a label of {@link Kind} (the
 * kinds come in the order of that list, a kind without requests is left out, {@code none} when
 * there is no request at all), and everything else is a number of at most 6 digits (milliseconds: 9
 * digits). Any other text that is handed in becomes {@code unknown} (binding and case) or counts as
 * {@code other} (kind), so a key, a host, a header, a signature or a line break that reached a
 * caller's string cannot reach the line. The escape of {@code ProbeReport} is the last step:
 * percent first, then CR and LF. The message is cut at {@value #MAX_MESSAGE} characters, which the
 * four cases never reach (about 1,200 in the worst case): an annotation message is cut by GitHub
 * and a cut here keeps the bound ours.
 *
 * <p>A measurement that failed is the line {@code binding=<binding>; measurement=failed;
 * why=<reason>} with one of the four words of {@link #REASONS}, never the text of an exception. An
 * example line (the raw store, produced on the fake) is {@code ::notice
 * title=oq1-seal-path::binding=raw; case=new-without-registrar requests=6 ms=28
 * kinds=GET-versions:4,PUT-object:2; case=new-with-registrar ...}.
 */
final class SealPathReport {
  static final String COMMAND = "::notice title=oq1-seal-path::";
  static final int MAX_MESSAGE = 2_000;

  static final String NEW_WITHOUT = "new-without-registrar";
  static final String NEW_WITH = "new-with-registrar";
  static final String SHARED_WITHOUT = "shared-without-registrar";
  static final String SHARED_WITH = "shared-with-registrar";

  /** The four cases, in the order they are measured and printed. */
  static final List<String> CASES = List.of(NEW_WITHOUT, NEW_WITH, SHARED_WITHOUT, SHARED_WITH);

  /** The two bindings: the raw store, and the encrypting decorator over it. */
  static final List<String> BINDINGS = List.of("raw", "encrypted");

  /**
   * Why a measurement failed: a seal that did not end SEALED, a store that was unavailable, a
   * recorder that lost a request, any other failure.
   */
  static final List<String> REASONS = List.of("not-sealed", "unavailable", "recorder", "error");

  private static final String UNKNOWN = "unknown";
  private static final long MAX_COUNT = 999_999L;
  private static final long MAX_MILLIS = 999_999_999L;
  private static final Set<String> KIND_LABELS =
      Arrays.stream(Kind.values()).map(Kind::label).collect(Collectors.toUnmodifiableSet());

  private SealPathReport() {}

  /** One case: its name, the elapsed milliseconds and the number of requests by kind label. */
  record Figure(String name, long elapsedMillis, Map<String, Integer> byKind) {
    Figure {
      Objects.requireNonNull(name, "name");
      byKind = Map.copyOf(byKind);
    }
  }

  /**
   * The workflow command of one binding. An empty list is a measurement that failed and gives the
   * line of {@link #failedLine} with the reason {@code error}.
   */
  static String line(String binding, List<Figure> figures) {
    Objects.requireNonNull(binding, "binding");
    if (figures.isEmpty()) {
      return failedLine(binding, "error");
    }
    StringBuilder message = new StringBuilder("binding=").append(bindingWord(binding));
    for (Figure figure : figures) {
      Map<String, Long> kinds = kindsOf(figure);
      long total = kinds.values().stream().mapToLong(Long::longValue).sum();
      message
          .append("; case=")
          .append(CASES.contains(figure.name()) ? figure.name() : UNKNOWN)
          .append(" requests=")
          .append(Math.min(total, MAX_COUNT))
          .append(" ms=")
          .append(Math.max(0L, Math.min(figure.elapsedMillis(), MAX_MILLIS)))
          .append(" kinds=");
      boolean any = false;
      for (Map.Entry<String, Long> kind : kinds.entrySet()) {
        if (kind.getValue() > 0) {
          message
              .append(any ? "," : "")
              .append(kind.getKey())
              .append(':')
              .append(Math.min(kind.getValue(), MAX_COUNT));
          any = true;
        }
      }
      message.append(any ? "" : "none");
    }
    return command(message.toString());
  }

  /**
   * The line of a binding whose measurement failed: no number, and one of the four words of {@link
   * #REASONS} (any other text becomes {@code error}); never the text of an exception.
   */
  static String failedLine(String binding, String reason) {
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(reason, "reason");
    return command(
        "binding="
            + bindingWord(binding)
            + "; measurement=failed; why="
            + (REASONS.contains(reason) ? reason : "error"));
  }

  /** The counts by kind in the order of the vocabulary; a label outside it is counted as other. */
  private static Map<String, Long> kindsOf(Figure figure) {
    Map<String, Long> merged = new LinkedHashMap<>();
    for (Kind kind : Kind.values()) {
      merged.put(kind.label(), 0L);
    }
    for (Map.Entry<String, Integer> e : figure.byKind().entrySet()) {
      String label = KIND_LABELS.contains(e.getKey()) ? e.getKey() : Kind.OTHER.label();
      merged.merge(label, (long) Math.max(0, e.getValue()), Long::sum);
    }
    return merged;
  }

  private static String bindingWord(String binding) {
    return BINDINGS.contains(binding) ? binding : UNKNOWN;
  }

  /**
   * The command with the escape of a workflow command's data (as {@code ProbeReport}) and the
   * bound.
   */
  static String command(String message) {
    String escaped = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
    return COMMAND + (escaped.length() > MAX_MESSAGE ? escaped.substring(0, MAX_MESSAGE) : escaped);
  }
}
