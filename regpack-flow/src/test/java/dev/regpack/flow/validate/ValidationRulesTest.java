package dev.regpack.flow.validate;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.check.ProblemSink;
import dev.regpack.pack.report.ValidationRules;
import dev.regpack.pack.report.ValidationRules.Check;
import dev.regpack.pack.report.ValidationRules.Disposition;
import dev.regpack.pack.report.ValidationRules.Layer;
import dev.regpack.pack.report.ValidationRules.Rule;
import dev.regpack.pack.report.ValidationRules.Severity;
import dev.regpack.pack.yaml.StrictYaml;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The validation rules file: the typed model and the reader (plan W1a, task T9, spec 8.1), read
 * from the fixture that the demo pack test compares with the pack file.
 */
class ValidationRulesTest {
  private static final String PATH = "validate/rules.yaml";

  /** The text of the fixture: the 15 rules of the content sheet. */
  static String fixture() {
    try (InputStream in = ValidationRulesTest.class.getResourceAsStream("/fixtures/rules.yaml")) {
      assertThat(in).as("the fixture rules.yaml").isNotNull();
      return new String(in.readAllBytes(), UTF_8);
    } catch (IOException e) {
      throw new AssertionError("the fixture could not be read");
    }
  }

  /** The rules of the fixture. */
  static ValidationRules fixtureRules() {
    ProblemSink sink = ProblemSink.forFile();
    ValidationRules rules =
        ValidationRules.parse(PATH, StrictYaml.parse(fixture()), sink).orElseThrow();
    assertThat(sink.isEmpty()).isTrue();
    return rules;
  }

  private static String file(String rules) {
    return "rules:\n" + rules;
  }

  private static String rule(
      String id, String severity, String disposition, String check, String args, String rest) {
    return "  - {id: "
        + id
        + ", layer: L1, severity: "
        + severity
        + ", disposition: "
        + disposition
        + ", check: "
        + check
        + ", args: "
        + args
        + ", regulator_id: null"
        + rest
        + "}\n";
  }

  private static String lei(String id) {
    return rule(id, "BLOCKER", "QUARANTINE", "LEI", "{fields: [firm.lei]}", "");
  }

  private static List<PackProblem> problemsOf(String text) {
    ProblemSink sink = ProblemSink.forFile();
    Optional<ValidationRules> rules = ValidationRules.parse(PATH, StrictYaml.parse(text), sink);
    assertThat(rules.isPresent())
        .as("rules exactly when there is no problem")
        .isEqualTo(sink.isEmpty());
    return sink.sorted(PATH);
  }

  private static List<String> codes(String text) {
    return problemsOf(text).stream().map(PackProblem::code).toList();
  }

  @Test
  void parsesTheFifteenRulesAndRefusesAnUnknownCheckKeyOrLayer() {
    ValidationRules rules = fixtureRules();

    assertThat(rules.path()).isEqualTo(PATH);
    assertThat(rules.rules()).extracting(Rule::id).hasSize(15);
    assertThat(rules.rules().get(0).id()).isEqualTo("V01");
    assertThat(rules.rules().get(14).id()).isEqualTo("V15");
    assertThat(rules.rules())
        .extracting(r -> r.layer().name())
        .containsExactly(
            "L1", "L1", "L1", "L2", "L2", "L2", "L2", "L2", "L3", "L3", "L3", "L3", "L4", "L4",
            "L5");
    assertThat(rules.rules())
        .extracting(Rule::check)
        .containsExactly(
            Check.DECIMAL_RANGE,
            Check.DECIMAL_RANGE,
            Check.CURRENCY_SHAPE,
            Check.LEI,
            Check.LEI,
            Check.ISIN,
            Check.MIC_SHAPE,
            Check.CURRENCY_NOT_IN,
            Check.REF_STATUS,
            Check.REF_STATUS,
            Check.REF_LISTED,
            Check.REF_KNOWN,
            Check.TIME_NOT_AFTER_RECEIPT,
            Check.TIME_NOT_OLDER,
            Check.CHAIN_FIT);
    assertThat(rules.rules())
        .filteredOn(r -> r.severity() != Severity.BLOCKER)
        .extracting(Rule::id)
        .containsExactly("V11");
    assertThat(rules.rules())
        .filteredOn(r -> r.disposition() == Disposition.PASS)
        .extracting(Rule::id)
        .containsExactly("V11");
    assertThat(rules.rules()).allMatch(r -> r.regulatorId().isEmpty());
    Rule v01 = rules.rules().get(0);
    assertThat(v01.list("fields")).containsExactly("trade.quantity");
    assertThat(v01.single("min_exclusive")).contains("0");
    assertThat(v01.single("max_integer_digits")).contains("12");
    assertThat(v01.single("max_fraction_digits")).contains("4");
    assertThat(v01.single("years")).isEmpty();
    Rule v10 = rules.rules().get(9);
    assertThat(v10.list("fields")).containsExactly("client.lei", "trade.counterparty");
    assertThat(v10.list("warn_values")).containsExactly("LAPSED");
    assertThat(v10.single("warn_code")).contains("LEI_LAPSED");
    assertThat(rules.rules().get(7).list("values")).containsExactly("XAU", "XDR", "XXX");
    assertThat(rules.rules().get(14).single("new_action")).contains("NEWT");

    String text = fixture();
    assertThat(codes(text.replace("check: CHAIN_FIT", "check: NO_SUCH_CHECK")))
        .containsExactly("VALIDATE_CHECK_UNKNOWN");
    assertThat(codes(text.replace("check: CHAIN_FIT", "check: chain_fit")))
        .containsExactly("VALIDATE_CHECK_UNKNOWN");
    assertThat(codes(text.replace("layer: L5", "layer: L6")))
        .containsExactly("VALIDATE_LAYER_UNKNOWN");
    assertThat(
            codes(
                text.replace(
                    "regulator_id: null}\n  - {id: V02",
                    "regulator_id: null, x: 1}\n  - {id: V02")))
        .containsExactly("VALIDATE_KEY_UNKNOWN");
    assertThat(codes(text + "other: 1\n")).containsExactly("VALIDATE_KEY_UNKNOWN");
  }

  @Test
  void anEmptyOrMalformedFileIsRefused() {
    assertThat(codes("rules: []\n")).containsExactly("VALIDATE_RULES_EMPTY");
    assertThat(codes("other: 1\n"))
        .containsExactlyInAnyOrder("VALIDATE_KEY_UNKNOWN", "VALIDATE_KEY_MISSING");
    assertThat(codes("rules: x\n")).containsExactly("VALIDATE_SHAPE");
    assertThat(codes("- x\n")).containsExactly("VALIDATE_SHAPE");
    assertThat(codes(file("  - x\n"))).containsExactly("VALIDATE_SHAPE");
    assertThat(codes(file("  - {id: V1}\n")))
        .as("every missing key is told")
        .contains("VALIDATE_KEY_MISSING");
  }

  @Test
  void ruleIdsAreWellFormedAndUnique() {
    assertThat(codes(file(lei("V1") + lei("V2")))).isEmpty();
    assertThat(codes(file(lei("V1") + lei("V1")))).containsExactly("VALIDATE_RULE_ID_DUPLICATE");
    assertThat(codes(file(lei("1V")))).containsExactly("VALIDATE_RULE_ID_INVALID");
    assertThat(codes(file(lei("\"V 1\"")))).containsExactly("VALIDATE_RULE_ID_INVALID");
  }

  @Test
  void aSeverityAndADispositionThatDisagreeAreRefused() {
    String args = "{fields: [firm.lei]}";
    assertThat(codes(file(rule("V1", "WARNING", "PASS", "LEI", args, "")))).isEmpty();
    assertThat(codes(file(rule("V1", "INFO", "PASS", "LEI", args, "")))).isEmpty();
    assertThat(codes(file(rule("V1", "BLOCKER", "PASS", "LEI", args, ""))))
        .containsExactly("VALIDATE_DISPOSITION_MISMATCH");
    assertThat(codes(file(rule("V1", "WARNING", "QUARANTINE", "LEI", args, ""))))
        .containsExactly("VALIDATE_DISPOSITION_MISMATCH");
    assertThat(codes(file(rule("V1", "FATAL", "QUARANTINE", "LEI", args, ""))))
        .containsExactly("VALIDATE_SEVERITY_UNKNOWN");
    assertThat(codes(file(rule("V1", "BLOCKER", "DROP", "LEI", args, ""))))
        .containsExactly("VALIDATE_DISPOSITION_UNKNOWN");
  }

  @Test
  void argumentsAreCheckedAgainstTheirCheck() {
    // unknown and missing arguments
    assertThat(
            codes(
                file(rule("V1", "BLOCKER", "QUARANTINE", "LEI", "{fields: [firm.lei], x: 1}", ""))))
        .containsExactly("VALIDATE_ARGS_UNKNOWN");
    assertThat(codes(file(rule("V1", "BLOCKER", "QUARANTINE", "LEI", "{}", ""))))
        .containsExactly("VALIDATE_ARGS_MISSING");
    assertThat(
            codes(file(rule("V1", "BLOCKER", "QUARANTINE", "CHAIN_FIT", "{new_action: NEWT}", ""))))
        .containsExactly("VALIDATE_ARGS_MISSING");
    assertThat(codes(file(rule("V1", "BLOCKER", "QUARANTINE", "LEI", "[x]", ""))))
        .containsExactly("VALIDATE_SHAPE");
    // invalid forms
    for (String bad :
        List.of(
            "{fields: firm.lei}",
            "{fields: []}",
            "{fields: [Firm.lei]}",
            "{fields: [firm.lei, firm.lei]}",
            "{fields: [[firm.lei]]}")) {
      assertThat(codes(file(rule("V1", "BLOCKER", "QUARANTINE", "LEI", bad, ""))))
          .as(bad)
          .hasSize(1)
          .allMatch(c -> c.equals("VALIDATE_ARGS_INVALID") || c.equals("VALIDATE_SHAPE"));
    }
    String range = "DECIMAL_RANGE";
    for (String bad :
        List.of(
            "{fields: [trade.quantity], min_exclusive: -1}",
            "{fields: [trade.quantity], min_exclusive: 1e3}",
            "{fields: [trade.quantity], max_integer_digits: 123}",
            "{fields: [trade.quantity], max_fraction_digits: x}")) {
      assertThat(codes(file(rule("V1", "BLOCKER", "QUARANTINE", range, bad, ""))))
          .as(bad)
          .containsExactly("VALIDATE_ARGS_INVALID");
    }
    assertThat(
            codes(
                file(
                    rule(
                        "V1",
                        "BLOCKER",
                        "QUARANTINE",
                        "TIME_NOT_OLDER",
                        "{fields: [event.tradedAt], years: 100}",
                        ""))))
        .containsExactly("VALIDATE_ARGS_INVALID");
    // the warning pair goes together
    String status =
        "dataset: lei-status, fields: [client.lei], attribute: status, allowed: [ACTIVE]";
    assertThat(
            codes(
                file(
                    rule(
                        "V1",
                        "BLOCKER",
                        "QUARANTINE",
                        "REF_STATUS",
                        "{" + status + ", warn_values: [LAPSED]}",
                        ""))))
        .containsExactly("VALIDATE_ARGS_INVALID");
    assertThat(
            codes(
                file(
                    rule(
                        "V1",
                        "BLOCKER",
                        "QUARANTINE",
                        "REF_STATUS",
                        "{" + status + ", warn_code: LEI_LAPSED}",
                        ""))))
        .containsExactly("VALIDATE_ARGS_INVALID");
    assertThat(
            codes(
                file(
                    rule(
                        "V1",
                        "BLOCKER",
                        "QUARANTINE",
                        "REF_STATUS",
                        "{" + status + ", warn_values: [LAPSED], warn_code: LEI_LAPSED}",
                        ""))))
        .isEmpty();
  }

  private static List<String> warnCodeCodes(String warnCode) {
    String status =
        "dataset: lei-status, fields: [client.lei], attribute: status, allowed: [ACTIVE],"
            + " warn_values: [LAPSED], warn_code: ";
    return codes(
        file(rule("V1", "BLOCKER", "QUARANTINE", "REF_STATUS", "{" + status + warnCode + "}", "")));
  }

  @Test
  void aWarnCodeThatAFindingCouldNotCarryIsRefusedWhenThePackIsRead() {
    // D-02: the reader used to take any name here; Finding then threw at decide time
    for (String bad :
        List.of("lei-lapsed", "lei.lapsed", "L", "Lei_Lapsed", "1LAPSED", "_LAPSED")) {
      assertThat(warnCodeCodes(bad)).as(bad).containsExactly("VALIDATE_ARGS_INVALID");
    }
  }

  @Test
  void theWarnCodeFormEqualsTheFormOfAFindingCode() {
    // the two patterns are in two modules and must stay equal: every sample is judged alike
    List<String> samples =
        List.of(
            "LEI_LAPSED",
            "AB",
            "A1",
            "A_",
            "A",
            "a",
            "ab",
            "Ab",
            "1A",
            "_A",
            "A-B",
            "A.B",
            "A B",
            "A".repeat(64),
            "A".repeat(65),
            "A".repeat(63) + "9");
    for (String sample : samples) {
      boolean readerTakesIt = warnCodeCodes(sample).isEmpty();
      boolean findingTakesIt;
      try {
        new Finding("V1", Layer.L3, Severity.WARNING, sample);
        findingTakesIt = true;
      } catch (IllegalArgumentException e) {
        findingTakesIt = false;
      }
      assertThat(readerTakesIt)
          .as("reader and Finding agree on '" + sample + "'")
          .isEqualTo(findingTakesIt);
    }
  }

  @Test
  void theRegulatorIdIsNullOrAShortIdentifier() {
    ProblemSink sink = ProblemSink.forFile();
    String text =
        file(
            "  - {id: V1, layer: L1, severity: BLOCKER, disposition: QUARANTINE, check: LEI,"
                + " args: {fields: [firm.lei]}, regulator_id: R-1.2}\n");
    ValidationRules rules = ValidationRules.parse(PATH, StrictYaml.parse(text), sink).orElseThrow();
    assertThat(rules.rules().get(0).regulatorId()).contains("R-1.2");
    assertThat(codes(text.replace("R-1.2", "\"has space\"")))
        .containsExactly("VALIDATE_REGULATOR_ID_INVALID");
    assertThat(codes(text.replace("R-1.2", "a".repeat(33))))
        .containsExactly("VALIDATE_REGULATOR_ID_INVALID");
  }

  @Test
  void aCharacterThePackTextPolicyRefusesIsReported() {
    assertThat(codes(file(lei("\"V\\n1\"")))).contains("VALIDATE_BAD_CHARACTER");
    assertThat(
            codes(
                file(
                    rule("V1", "BLOCKER", "QUARANTINE", "LEI", "{fields: [\"firm\\n.lei\"]}", ""))))
        .contains("VALIDATE_BAD_CHARACTER");
  }

  @Test
  void aRuleHoldsNoMutableState() {
    Rule rule = fixtureRules().rules().get(0);
    Map<String, List<String>> args = rule.args();
    assertThatThrownBy(() -> args.put("x", List.of()))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> rule.list("fields").add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(Layer.values()).hasSize(5);
  }
}
