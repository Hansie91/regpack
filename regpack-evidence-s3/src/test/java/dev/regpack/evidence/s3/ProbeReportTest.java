package dev.regpack.evidence.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The probe-results line: all items in one workflow command, nothing in it can start another. */
class ProbeReportTest {
  private static final String COMMAND = "::warning title=probe-results::";

  @Test
  void theLineHoldsAllSeventeenItemsInOneAnnotation() {
    ProbeReport report = new ProbeReport();
    for (int n = 17; n >= 1; n--) {
      report.add(n, 200, "none", "observed value " + n);
    }
    String line = report.line();
    assertThat(line).startsWith(COMMAND);
    assertThat(line).doesNotContain("\n").doesNotContain("\r");
    assertThat(line.length()).isLessThan(60_000);
    List<String> items = List.of(line.substring(COMMAND.length()).split("%0A", -1));
    assertThat(items).hasSize(17);
    for (int n = 1; n <= 17; n++) {
      assertThat(items.get(n - 1)).isEqualTo("P" + n + "=200/none/observed value " + n);
    }
    // one command, one line
    assertThat(line.lines().count()).isEqualTo(1);
    assertThat(line.split("::warning", -1)).hasSize(2);
  }

  @Test
  void aValueWithANewlineAndAWorkflowCommandIsPrintedAsOneInertLine() {
    ProbeReport report = new ProbeReport();
    report.add(7, 204, "NoSuchKey", "first\n::error::x\r\n::warning::y 100% %0A %25 injected");
    String line = report.line();
    assertThat(line).startsWith(COMMAND);
    assertThat(line).doesNotContain("\n").doesNotContain("\r").doesNotContain("%");
    assertThat(line.lines().count()).isEqualTo(1);
    // the text after the command name cannot open a second command: no line break, no percent
    assertThat(line.substring(COMMAND.length())).doesNotStartWith("::");
    assertThat(line)
        .contains("P7=204/NoSuchKey/first?::error::x??::warning::y 100? ?0A ?25 injected");
    // two items are separated by the escaped line break and by nothing else
    report.add(8, 200, "none", "ok");
    assertThat(report.line()).contains("injected%0AP8=200/none/ok").doesNotContain("\n");
  }

  @Test
  void aCodeThatIsNotAnS3CodeIsReplaced() {
    ProbeReport report = new ProbeReport();
    report.add(1, 400, "Bad Code\n::error::", "x");
    report.add(2, 400, "", "x");
    report.add(3, 400, "A".repeat(65), "x");
    report.add(4, 400, "A".repeat(64), "x");
    report.add(5, 400, null, "x");
    assertThat(report.fileLines())
        .containsExactly(
            "P1=400/invalid-code/x",
            "P2=400/invalid-code/x",
            "P3=400/invalid-code/x",
            "P4=400/" + "A".repeat(64) + "/x",
            "P5=400/none/x");
  }

  @Test
  void anObservedValueIsReducedToTheAllowedCharactersAndCut() {
    ProbeReport report = new ProbeReport();
    report.add(1, 200, "none", "ab<c>\"d'e&fé€ AZ09 ._:=,;/+-");
    report.add(2, 200, "none", "x".repeat(500));
    assertThat(report.fileLines().get(0)).isEqualTo("P1=200/none/ab?c??d?e?f?? AZ09 ._:=,;/+-");
    String second = report.fileLines().get(1);
    assertThat(second).isEqualTo("P2=200/none/" + "x".repeat(120));
    assertThat(second.length()).isLessThanOrEqualTo(300);
  }

  @Test
  void aFailingItemIsRecordedAsErrorWithoutItsMessage() {
    ProbeReport report = new ProbeReport();
    report.addError(3, "AccessDenied", "S3Exception");
    report.addError(4, null, "bad\nvalue");
    assertThat(report.fileLines())
        .containsExactly("P3=ERROR/AccessDenied/S3Exception", "P4=ERROR/none/bad?value");
  }

  @Test
  void fileLinesAreInItemOrderAndAnItemIsRecordedOnce() {
    ProbeReport report = new ProbeReport();
    report.add(10, 200, "none", "b");
    report.add(2, 200, "none", "a");
    assertThat(report.fileLines()).containsExactly("P2=200/none/a", "P10=200/none/b");
    assertThatThrownBy(() -> report.add(2, 200, "none", "again"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> report.add(0, 200, "none", "x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> report.add(1, -1, "none", "x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theItemNumbersAreRecorded() {
    ProbeReport report = new ProbeReport();
    assertThat(report.recorded()).isEmpty();
    report.add(5, 200, "none", "x");
    report.addError(6, "none", "x");
    assertThat(report.recorded()).containsExactly(5, 6);
    assertThat(report.status(5)).isEqualTo("200");
    assertThat(report.status(6)).isEqualTo("ERROR");
  }
}
