package dev.regpack.postgres;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Makes a failing sequence short (delta debugging, Zeller and Hildebrandt): the shortest list of
 * steps, found by removing blocks of steps and then single steps, for which the test still fails,
 * and then each remaining step replaced by simpler ones while the test still fails. The result is
 * not the shortest possible one, but no single step can be removed from it and no step can be made
 * simpler. The test is run on a fresh state each time and must give the same answer for the same
 * list (the sequences are deterministic).
 */
final class DifferentialShrinker {
  private DifferentialShrinker() {}

  /**
   * The sublist of {@code items} that still {@code fails}, with no single item removable.
   *
   * @param fails the test; it must be true for {@code items} itself
   */
  static <T> List<T> minimize(List<T> items, Predicate<List<T>> fails) {
    List<T> current = new ArrayList<>(items);
    int parts = 2;
    while (current.size() >= 2) {
      boolean reduced = false;
      int size = current.size();
      int block = Math.max(1, (size + parts - 1) / parts);
      for (int start = 0; start < size && !reduced; start += block) {
        List<T> complement = new ArrayList<>(current);
        complement.subList(start, Math.min(size, start + block)).clear();
        if (!complement.isEmpty() && fails.test(complement)) {
          current = complement;
          parts = Math.max(parts - 1, 2);
          reduced = true;
        }
      }
      if (!reduced) {
        if (parts >= size) {
          break;
        }
        parts = Math.min(size, parts * 2);
      }
    }
    return current;
  }

  /**
   * {@code items} with each item replaced by one of its simpler variants as long as the test still
   * {@code fails}, until none can be.
   */
  static <T> List<T> simplify(
      List<T> items, Function<T, List<T>> simpler, Predicate<List<T>> fails) {
    List<T> current = new ArrayList<>(items);
    boolean changed = true;
    while (changed) {
      changed = false;
      for (int i = 0; i < current.size() && !changed; i++) {
        for (T candidate : simpler.apply(current.get(i))) {
          List<T> trial = new ArrayList<>(current);
          trial.set(i, candidate);
          if (fails.test(trial)) {
            current = trial;
            changed = true;
            break;
          }
        }
      }
    }
    return current;
  }
}
