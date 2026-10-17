package dev.regpack.pack.check;

import dev.regpack.pack.PackFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A bounded collector of problems (final review PACKMOD-2, TEETH-7): hostile input may contain
 * hundreds of thousands of defects, so a reader keeps at most {@link #cap()} problems, counts the
 * rest and stops reading as soon as {@link #truncated()} is true. The list handed back by {@link
 * #sorted} is the kept problems in order followed by one {@value #TRUNCATED} marker that says how
 * many problems were dropped.
 *
 * <p>{@link #count()} counts every problem offered, kept or not, so a reader can still ask whether
 * a step found a problem (compare the count before and after). The number in the marker counts the
 * problems dropped before the reader stopped: it is a lower bound, not the total. The kept problems
 * are the first ones found, so an input below the cap reports exactly what it did before the cap
 * existed. Not thread-safe.
 */
public final class ProblemSink {
  /** The code of the marker problem that stands for the dropped ones. */
  public static final String TRUNCATED = "PROBLEMS_TRUNCATED";

  private final int cap;
  private final List<PackProblem> kept = new ArrayList<>();
  private int offered;
  private int dropped;

  /** A sink that keeps at most {@code cap} problems. */
  public ProblemSink(int cap) {
    if (cap < 1) {
      throw new IllegalArgumentException("the cap of a problem sink must be positive");
    }
    this.cap = cap;
  }

  /** A sink with the cap of one file, {@link PackFormat#MAX_PROBLEMS_PER_FILE}. */
  public static ProblemSink forFile() {
    return new ProblemSink(PackFormat.MAX_PROBLEMS_PER_FILE);
  }

  /** A sink with the cap of one load, {@link PackFormat#MAX_PROBLEMS_PER_LOAD}. */
  public static ProblemSink forLoad() {
    return new ProblemSink(PackFormat.MAX_PROBLEMS_PER_LOAD);
  }

  public int cap() {
    return cap;
  }

  /** Keeps the problem while there is room; once the cap is reached it only counts it. */
  public void add(PackProblem problem) {
    if (offered < Integer.MAX_VALUE) {
      offered++;
    }
    if (kept.size() < cap) {
      kept.add(problem);
    } else if (dropped < Integer.MAX_VALUE) {
      dropped++;
    }
  }

  public void addAll(Collection<PackProblem> problems) {
    for (PackProblem p : problems) {
      add(p);
    }
  }

  /** How many problems were offered, the dropped ones included. */
  public int count() {
    return offered;
  }

  /** Whether no problem was offered. */
  public boolean isEmpty() {
    return offered == 0;
  }

  /** Whether a problem was dropped: the reader stops, since the file or the load fails anyway. */
  public boolean truncated() {
    return dropped > 0;
  }

  /**
   * The kept problems sorted, then the marker if any problem was dropped. The marker names {@code
   * markerPath} (the file or the pack the cap applies to) and orders after every other problem.
   */
  public List<PackProblem> sorted(String markerPath) {
    List<PackProblem> out = new ArrayList<>(kept);
    out.sort(null);
    if (dropped > 0) {
      out.add(marker(markerPath, dropped));
    }
    return out;
  }

  /**
   * At most {@code max} of the problems (the first in sorted order), followed by one marker for the
   * rest; markers already in the list are kept. Used where a list is built by several stages and
   * printed.
   */
  public static List<PackProblem> cap(
      Collection<PackProblem> problems, int max, String markerPath) {
    List<PackProblem> sorted = new ArrayList<>(problems);
    sorted.sort(null);
    int real = 0;
    for (PackProblem p : sorted) {
      if (!p.code().equals(TRUNCATED)) {
        real++;
      }
    }
    if (real <= max) {
      return sorted;
    }
    List<PackProblem> out = new ArrayList<>();
    int kept = 0;
    for (PackProblem p : sorted) {
      if (p.code().equals(TRUNCATED) || kept++ < max) {
        out.add(p);
      }
    }
    out.add(marker(markerPath, real - max));
    out.sort(null);
    return out;
  }

  private static PackProblem marker(String path, int n) {
    return new PackProblem(TRUNCATED, path, 0, n + " further problems not shown");
  }
}
