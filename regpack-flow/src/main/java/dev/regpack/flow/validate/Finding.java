package dev.regpack.flow.validate;

import dev.regpack.pack.report.ValidationRules.Layer;
import dev.regpack.pack.report.ValidationRules.Severity;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One result of a failed validation rule (plan W1a, task T9): which rule, in which layer, how
 * serious, and a fixed code. A finding holds no value of the record, ever: the record is found
 * again from the batch and the line, so a value need not travel with the finding (SF8).
 *
 * @param ruleId the id of the rule, as the pack gives it
 * @param layer the layer of the rule
 * @param severity BLOCKER for a failure that quarantines the record, WARNING for a flag, INFO for a
 *     note
 * @param code the reason as a fixed code of the check
 */
public record Finding(String ruleId, Layer layer, Severity severity, String code) {
  private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

  public Finding {
    Objects.requireNonNull(ruleId, "ruleId");
    Objects.requireNonNull(layer, "layer");
    Objects.requireNonNull(severity, "severity");
    if (code == null || !CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("a finding code is a fixed upper-case code");
    }
  }
}
