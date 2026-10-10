package dev.regpack.ports.exec;

import dev.regpack.model.decision.PathStep;
import java.util.List;

/** The evaluation path of one run: one step per rule considered, in order. */
public interface Trace {
  void step(String ruleId, boolean matched, long costUnits);

  /** An immutable copy of the steps recorded so far. */
  List<PathStep> steps();
}
