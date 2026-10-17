package dev.regpack.engine.packci;

import dev.regpack.pack.check.PackProblem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The result of {@link PackTester#run}: the lines the {@code pack test} command prints and the
 * problems behind a failure.
 *
 * @param lines every output line in order, the problem lines and the final {@code pack test: PASS}
 *     or {@code pack test: FAIL (n problems)} line included; no line ends with a line break
 * @param problems every problem, sorted by path, line and code; the pack passes exactly when this
 *     is empty
 */
public record PackTestReport(List<String> lines, List<PackProblem> problems) {
  public PackTestReport {
    lines = List.copyOf(lines);
    List<PackProblem> sorted = new ArrayList<>(Objects.requireNonNull(problems, "problems"));
    Collections.sort(sorted);
    problems = List.copyOf(sorted);
  }

  /** Whether the pack passed every check. */
  public boolean passed() {
    return problems.isEmpty();
  }

  /** The lines joined by LF, without a final line break. */
  public String render() {
    return String.join("\n", lines);
  }
}
