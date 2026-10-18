package dev.regpack.bench.imp;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The time ledger of one spike ({@code docs/spikes/timebox/<ID>.tsv}): one line per event, {@code
 * <instant> TAB <EVENT> TAB <note>}. The time used is the active time, from a START or RESUME to
 * the next PAUSE or STOP, so a wait for an approval or an owner answer is not counted. The ledger
 * holds instants, never reads a clock: the caller passes {@code now}.
 *
 * <p>The sequence is strict: START first, then PAUSE and RESUME alternating, STOP once at the end,
 * and instants that never go back. A ledger that breaks this is refused when it is read. That is
 * all it guarantees: a file with a valid order can still be edited (a later START, an earlier
 * PAUSE), so the ledger is a record kept in good faith, not proof; {@code verify-committed} checks
 * a committed report against it, and the review of the file in git is what catches an edit.
 */
public final class TimeLedger {
  /** The events of a ledger. */
  public enum Event {
    START,
    PAUSE,
    RESUME,
    STOP
  }

  private record Entry(Instant at, Event event, String note) {}

  private final List<Entry> entries;

  private TimeLedger(List<Entry> entries) {
    this.entries = List.copyOf(entries);
  }

  /** A ledger without events. */
  public static TimeLedger empty() {
    return new TimeLedger(List.of());
  }

  /**
   * Parses the TSV text of a ledger.
   *
   * @throws IllegalArgumentException for a malformed line or an event that does not follow, naming
   *     the line
   */
  public static TimeLedger parse(String tsv) {
    List<Entry> entries = new ArrayList<>();
    String[] lines = tsv.split("\n", -1);
    int count = lines.length;
    if (count > 0 && lines[count - 1].isEmpty()) {
      count--;
    }
    for (int index = 0; index < count; index++) {
      int lineNumber = index + 1;
      String[] fields = lines[index].split("\t", -1);
      if (fields.length < 2 || fields.length > 3) {
        throw new IllegalArgumentException(
            "line " + lineNumber + ": expected instant, event, note");
      }
      Instant at;
      try {
        at = Instant.parse(fields[0]);
      } catch (DateTimeParseException e) {
        throw new IllegalArgumentException("line " + lineNumber + ": not an instant", e);
      }
      Event event;
      try {
        event = Event.valueOf(fields[1]);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("line " + lineNumber + ": unknown event", e);
      }
      String note = fields.length == 3 ? fields[2] : "";
      try {
        check(entries, at, event, note);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("line " + lineNumber + ": " + e.getMessage(), e);
      }
      entries.add(new Entry(at, event, note));
    }
    return new TimeLedger(entries);
  }

  /**
   * A ledger with one more event.
   *
   * @throws IllegalArgumentException when the event does not follow the ledger, the instant is
   *     before the last one, or the note holds a tab or a line break
   */
  public TimeLedger append(Instant at, Event event, String note) {
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(event, "event");
    Objects.requireNonNull(note, "note");
    check(entries, at, event, note);
    List<Entry> next = new ArrayList<>(entries);
    next.add(new Entry(at, event, note));
    return new TimeLedger(next);
  }

  private static void check(List<Entry> entries, Instant at, Event event, String note) {
    if (note.indexOf('\t') >= 0 || note.indexOf('\n') >= 0 || note.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("a note holds no tab and no line break");
    }
    if (entries.isEmpty()) {
      if (event != Event.START) {
        throw new IllegalArgumentException("the first event must be START, not " + event);
      }
      return;
    }
    Entry last = entries.get(entries.size() - 1);
    if (at.isBefore(last.at())) {
      throw new IllegalArgumentException(
          "the events are out of order: " + at + " after " + last.at());
    }
    boolean ok =
        switch (last.event()) {
          case START, RESUME -> event == Event.PAUSE || event == Event.STOP;
          case PAUSE -> event == Event.RESUME || event == Event.STOP;
          case STOP -> false;
        };
    if (!ok) {
      throw new IllegalArgumentException(event + " cannot follow " + last.event());
    }
  }

  /** The instant of the last event, or empty for a ledger without events. */
  public Optional<Instant> lastEventAt() {
    return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(entries.size() - 1).at());
  }

  /** True while the clock runs: the last event is START or RESUME. */
  public boolean isRunning() {
    if (entries.isEmpty()) {
      return false;
    }
    Event last = entries.get(entries.size() - 1).event();
    return last == Event.START || last == Event.RESUME;
  }

  /** One word for the state: not started, running, paused or stopped. */
  public String state() {
    if (entries.isEmpty()) {
      return "not started";
    }
    return switch (entries.get(entries.size() - 1).event()) {
      case START, RESUME -> "running";
      case PAUSE -> "paused";
      case STOP -> "stopped";
    };
  }

  /** The active time up to {@code now}; a running ledger counts up to {@code now}. */
  public Duration used(Instant now) {
    Duration total = Duration.ZERO;
    Instant since = null;
    for (Entry entry : entries) {
      switch (entry.event()) {
        case START, RESUME -> since = entry.at();
        case PAUSE, STOP -> {
          if (since != null) {
            total = total.plus(Duration.between(since, entry.at()));
            since = null;
          }
        }
      }
    }
    if (since != null && now.isAfter(since)) {
      total = total.plus(Duration.between(since, now));
    }
    return total;
  }

  /**
   * True when the active time has reached the box (at least the box, not only more than it): the
   * rule of {@code SpikeResult.boxHit}, so the status line and the report say the same.
   */
  public boolean overBox(Duration box, Instant now) {
    return used(now).compareTo(box) >= 0;
  }

  /** The TSV text: one line per event, LF, a final LF. */
  public String toTsv() {
    StringBuilder out = new StringBuilder();
    for (Entry entry : entries) {
      out.append(entry.at())
          .append('\t')
          .append(entry.event().name())
          .append('\t')
          .append(entry.note())
          .append('\n');
    }
    return out.toString();
  }
}
