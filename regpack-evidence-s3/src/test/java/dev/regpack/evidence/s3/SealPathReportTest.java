package dev.regpack.evidence.s3;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.evidence.s3.RecordingTransport.Kind;
import dev.regpack.evidence.s3.SealPathReport.Figure;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The notice of OQ-1 (P3c1 T15-2): one workflow command per binding, closed vocabulary and numbers
 * only, nothing in it can start another command, and its length is bounded.
 */
class SealPathReportTest {
  private static final String COMMAND = "::notice title=oq1-seal-path::";

  /** Everything the message may consist of, spelled out: no free text is allowed in it. */
  private static final Pattern MESSAGE =
      Pattern.compile(
          "binding=(raw|encrypted|unknown)"
              + "(; measurement=failed; why=(not-sealed|unavailable|recorder|error)"
              + "|(; case=((new|shared)-(without|with)-registrar|unknown) requests=\\d{1,6}"
              + " ms=\\d{1,9} kinds=(none|([A-Za-z-]+:\\d{1,6})(,[A-Za-z-]+:\\d{1,6})*))+)");

  private static Figure figure(String name, long millis, Map<String, Integer> kinds) {
    return new Figure(name, millis, kinds);
  }

  private static Map<String, Integer> kinds(Object... pairs) {
    Map<String, Integer> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put((String) pairs[i], (Integer) pairs[i + 1]);
    }
    return map;
  }

  @Test
  void theLineNamesTheBindingAndEachCaseWithItsRequestsMillisecondsAndKinds() {
    String line =
        SealPathReport.line(
            "raw",
            List.of(
                figure(
                    SealPathReport.NEW_WITHOUT,
                    41,
                    kinds("PUT-object", 2, "GET-versions", 3, "GET-object", 1)),
                figure(
                    SealPathReport.SHARED_WITH, 7, kinds("PUT-tagging", 1, "PUT-retention", 1))));

    // the kinds come in the order of the vocabulary, not in the order they were given
    assertThat(line)
        .isEqualTo(
            COMMAND
                + "binding=raw; case=new-without-registrar requests=6 ms=41"
                + " kinds=GET-versions:3,PUT-object:2,GET-object:1"
                + "; case=shared-with-registrar requests=2 ms=7"
                + " kinds=PUT-retention:1,PUT-tagging:1");
    assertThat(line.substring(COMMAND.length())).matches(MESSAGE);
  }

  @Test
  void aCaseWithoutRequestsHasNoKindsAndAKindWithoutRequestsIsLeftOut() {
    String line =
        SealPathReport.line("encrypted", List.of(figure(SealPathReport.NEW_WITH, 0, kinds())));
    assertThat(line)
        .isEqualTo(
            COMMAND + "binding=encrypted; case=new-with-registrar requests=0 ms=0 kinds=none");
    assertThat(line.substring(COMMAND.length())).matches(MESSAGE);

    assertThat(
            SealPathReport.line(
                "raw",
                List.of(
                    figure(SealPathReport.NEW_WITH, 3, kinds("GET-object", 0, "PUT-object", 2)))))
        .endsWith("requests=2 ms=3 kinds=PUT-object:2");
  }

  @Test
  void hostileTextNeverReachesTheLine() {
    // what could come from a request or an answer: a key, a host, a signature, a line break
    String key = "sealed/ab/cd/abcdef0123456789?versionId=3sL4kqtJlcpXroDTDmJ+rmSpXd3dIbrHY";
    String host = "s3.internal.example:9000";
    String signature =
        "AWS4-HMAC-SHA256 Credential=REGPACKCIACCESSKEY1/20261029/us-east-1/s3/aws4_request";
    String injection = "100%\r\n::error title=x::injected %0A %25\n::warning::y";
    List<Figure> figures = new ArrayList<>();
    figures.add(figure(injection, 5, kinds(key, 3, host, 2, signature, 1, "PUT-object", 4)));
    figures.add(
        figure(
            SealPathReport.SHARED_WITH,
            9,
            kinds(injection, 7, "GET " + key, 1, "get-object", 1, "", 1)));

    String line = SealPathReport.line(injection, figures);

    assertThat(line).startsWith(COMMAND);
    assertThat(line).doesNotContain("\n").doesNotContain("\r").doesNotContain("%");
    assertThat(line.lines().count()).isEqualTo(1);
    assertThat(line).doesNotContain(key).doesNotContain("versionId").doesNotContain("sealed/");
    assertThat(line).doesNotContain(host).doesNotContain("9000");
    assertThat(line).doesNotContain("AWS4").doesNotContain("REGPACKCIACCESSKEY1");
    assertThat(line).doesNotContain("injected").doesNotContain("error").doesNotContain("warning");
    String message = line.substring(COMMAND.length());
    assertThat(message).as("no second command").doesNotContain("::");
    assertThat(message).matches(MESSAGE);
    // the unknown names became the fixed words, and the hostile kinds were counted as "other"
    assertThat(message)
        .startsWith("binding=unknown; case=unknown requests=10 ms=5 kinds=PUT-object:4,other:6")
        .endsWith("; case=shared-with-registrar requests=10 ms=9 kinds=other:10");
  }

  @Test
  void aNumberIsClampedSoThatNothingNegativeOrHugeIsPrinted() {
    String line =
        SealPathReport.line(
            "raw",
            List.of(
                figure(
                    SealPathReport.NEW_WITHOUT,
                    Long.MAX_VALUE,
                    kinds("PUT-object", Integer.MAX_VALUE, "GET-object", -5))));

    assertThat(line).endsWith("requests=999999 ms=999999999 kinds=PUT-object:999999");
    assertThat(line.substring(COMMAND.length())).matches(MESSAGE);
    assertThat(
            SealPathReport.line(
                "raw", List.of(figure(SealPathReport.NEW_WITH, -3, kinds("GET-object", 1)))))
        .contains("ms=0 ");
  }

  @Test
  void theLineIsBoundedInTheWorstCase() {
    Map<String, Integer> everyKind = new LinkedHashMap<>();
    for (Kind kind : Kind.values()) {
      everyKind.put(kind.label(), Integer.MAX_VALUE);
    }
    List<Figure> figures = new ArrayList<>();
    for (String name : SealPathReport.CASES) {
      figures.add(figure(name, Long.MAX_VALUE, everyKind));
    }

    String line = SealPathReport.line("encrypted", figures);

    // the four cases never reach the cut: the worst case is well under the bound
    assertThat(line.length() - COMMAND.length())
        .isLessThan(1_500)
        .isLessThan(SealPathReport.MAX_MESSAGE);
    assertThat(line.substring(COMMAND.length())).matches(MESSAGE);
    assertThat(line).contains("case=shared-with-registrar").contains("other:999999");
  }

  @Test
  void aLineOverTheBoundIsCutAndStillHoldsNothingButTheVocabulary() {
    // more figures than the four cases cannot come from the helper, but the bound is the bound
    List<Figure> figures = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      figures.add(figure(SealPathReport.SHARED_WITHOUT, 1, kinds("PUT-object", 1)));
    }

    String line = SealPathReport.line("raw", figures);

    assertThat(line.length()).isEqualTo(COMMAND.length() + SealPathReport.MAX_MESSAGE);
    assertThat(line.substring(COMMAND.length())).matches("[A-Za-z0-9 =;:,-]+");
  }

  @Test
  void aFailedMeasurementIsOneFixedLineWithOneOfFourReasonsAndAnEmptyListIsOne() {
    assertThat(SealPathReport.failedLine("raw", "not-sealed"))
        .isEqualTo(COMMAND + "binding=raw; measurement=failed; why=not-sealed");
    for (String reason : SealPathReport.REASONS) {
      assertThat(SealPathReport.failedLine("encrypted", reason).substring(COMMAND.length()))
          .matches(MESSAGE)
          .endsWith("why=" + reason);
    }
    // a reason or a binding that is not a word of the vocabulary becomes a fixed word
    assertThat(
            SealPathReport.failedLine(
                "enc\nrypted%0A::error::", "the key sealed/ab?versionId=1\n::error::"))
        .isEqualTo(COMMAND + "binding=unknown; measurement=failed; why=error");
    // no figure at all is a failure whose reason is not known to the report
    assertThat(SealPathReport.line("encrypted", List.of()))
        .isEqualTo(SealPathReport.failedLine("encrypted", "error"));
  }

  @Test
  void theEscapeIsTheOneOfTheProbeLinePercentFirstThenCarriageReturnAndLineFeed() {
    // the vocabulary holds none of the three, so the escape is the last line of defence
    assertThat(SealPathReport.command("a%b\r\nc\nd\re%0A"))
        .isEqualTo(COMMAND + "a%25b%0D%0Ac%0Ad%0De%250A");
    assertThat(SealPathReport.command("plain")).isEqualTo(COMMAND + "plain");
    assertThat(SealPathReport.command("x".repeat(SealPathReport.MAX_MESSAGE + 1)))
        .hasSize(COMMAND.length() + SealPathReport.MAX_MESSAGE);
  }

  @Test
  void theVocabularyIsTheFourCasesTwoBindingsAndTheKindsOfTheRecorder() {
    assertThat(SealPathReport.CASES)
        .containsExactly(
            "new-without-registrar",
            "new-with-registrar",
            "shared-without-registrar",
            "shared-with-registrar");
    assertThat(SealPathReport.BINDINGS).containsExactly("raw", "encrypted");
    assertThat(SealPathReport.REASONS)
        .containsExactly("not-sealed", "unavailable", "recorder", "error");
    assertThat(SealPathReport.COMMAND).isEqualTo(COMMAND);
    for (Kind kind : Kind.values()) {
      assertThat(kind.label()).matches("[A-Za-z]+(-[A-Za-z]+)*");
    }
  }
}
