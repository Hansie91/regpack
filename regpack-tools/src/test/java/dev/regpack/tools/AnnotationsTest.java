package dev.regpack.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnnotationsTest {
  @TempDir Path dir;

  private void report(String className, String... cases) throws IOException {
    Path file = dir.resolve("target/surefire-reports/TEST-" + className + ".xml");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        "<testsuite name=\"" + className + "\">" + String.join("", cases) + "</testsuite>",
        StandardCharsets.UTF_8);
  }

  private static String pass(String className, String name) {
    return "<testcase classname=\"" + className + "\" name=\"" + name + "()\"/>";
  }

  private static String fail(String className, String name, String messageAttribute) {
    return "<testcase classname=\""
        + className
        + "\" name=\""
        + name
        + "()\"><failure message=\""
        + messageAttribute
        + "\" type=\"java.lang.AssertionError\">stack trace</failure></testcase>";
  }

  private static String error(String className, String name, String messageAttribute) {
    return "<testcase classname=\""
        + className
        + "\" name=\""
        + name
        + "()\"><error message=\""
        + messageAttribute
        + "\" type=\"java.lang.IllegalStateException\">stack</error></testcase>";
  }

  private static String skip(String className, String name) {
    return "<testcase classname=\""
        + className
        + "\" name=\""
        + name
        + "()\"><skipped message=\"no server\"/></testcase>";
  }

  private List<String> render() {
    return Annotations.render(List.of(dir));
  }

  private static List<String> withPrefix(List<String> lines, String prefix) {
    return lines.stream().filter(l -> l.startsWith(prefix)).toList();
  }

  @Test
  void failedAndErroredCasesBecomeErrorAnnotations() throws IOException {
    report(
        "a.B",
        pass("a.B", "m"),
        fail("a.B", "f", "expected 1 but was 2"),
        error("a.B", "e", "NPE here"),
        skip("a.B", "s"));
    List<String> lines = render();
    assertThat(lines)
        .containsExactly(
            "::notice title=tally-total::classes=1 run=4 failed=1 errors=1 skipped=1",
            "::error title=a.B#f::expected 1 but was 2",
            "::error title=a.B#e::NPE here",
            "::warning title=skipped::skipped=1 a.B#s",
            "::notice title=tally-classes::classes=1 class=a.B run=4 failed=1 errors=1 skipped=1");
  }

  @Test
  void aFailureWithoutAMessageAttributeUsesTheFirstLineOfItsText() throws IOException {
    report(
        "a.B",
        "<testcase classname=\"a.B\" name=\"f()\"><failure type=\"X\">first line\nsecond line"
            + "</failure></testcase>",
        "<testcase classname=\"a.B\" name=\"g()\"><error/></testcase>");
    assertThat(withPrefix(render(), "::error"))
        .containsExactly("::error title=a.B#f::first line", "::error title=a.B#g::");
  }

  @Test
  void messageIsCutAndEscaped() throws IOException {
    String longMessage = "50% " + "x".repeat(400) + "&#10;second line";
    report(
        "a.B",
        fail("a.B", "long", longMessage),
        fail("a.B", "pct", "a%0Ab&#13;c"),
        fail("a.B", "multi", "one&#10;two"));
    List<String> errors = withPrefix(render(), "::error");
    assertThat(errors).hasSize(3);
    assertThat(errors.get(0)).isEqualTo("::error title=a.B#long::50%25 " + "x".repeat(296));
    assertThat(errors.get(1)).isEqualTo("::error title=a.B#pct::a%250Ab");
    assertThat(errors.get(2)).isEqualTo("::error title=a.B#multi::one");
  }

  @Test
  void moreThanTenFailuresAreSummarisedInTheLastErrorNotInANotice() throws IOException {
    List<String> cases = new ArrayList<>();
    for (int i = 1; i <= 13; i++) {
      cases.add(fail("a.B", "f" + i, "m" + i));
    }
    report("a.B", cases.toArray(new String[0]));
    List<String> lines = render();
    List<String> errors = withPrefix(lines, "::error");
    // GitHub keeps about ten annotations of a level, and notices are cut first (CI-2): the names
    // of the cases that do not get an annotation of their own are the tenth error
    assertThat(errors).hasSize(10);
    assertThat(errors.get(8)).isEqualTo("::error title=a.B#f9::m9");
    assertThat(errors.get(9))
        .isEqualTo("::error title=more-failures::remaining=4 a.B#f10, a.B#f11, a.B#f12, a.B#f13");
    assertThat(withPrefix(lines, "::notice title=more-failures")).isEmpty();
    assertThat(lines)
        .contains(
            "::notice title=tally-classes::classes=1 class=a.B run=13 failed=13 errors=0"
                + " skipped=0");

    // eleven: nine cases and the summary of two
    List<String> eleven = new ArrayList<>(cases.subList(0, 11));
    report("a.B", eleven.toArray(new String[0]));
    List<String> elevenErrors = withPrefix(render(), "::error");
    assertThat(elevenErrors).hasSize(10);
    assertThat(elevenErrors.get(9))
        .isEqualTo("::error title=more-failures::remaining=2 a.B#f10, a.B#f11");

    // exactly ten: ten cases and no summary
    List<String> exactlyTen = new ArrayList<>(cases.subList(0, 10));
    report("a.B", exactlyTen.toArray(new String[0]));
    List<String> tenErrors = withPrefix(render(), "::error");
    assertThat(tenErrors).hasSize(10);
    assertThat(tenErrors.get(9)).isEqualTo("::error title=a.B#f10::m10");
    assertThat(String.join("\n", render())).doesNotContain("more-failures");
  }

  @Test
  void theRemainingFailuresErrorIsCutAt60000Characters() throws IOException {
    List<String> cases = new ArrayList<>();
    for (int i = 1; i <= 10; i++) {
      cases.add(fail("a.B", "f" + i, "m"));
    }
    for (int i = 0; i < 3; i++) {
      cases.add(fail("a.B", "n".repeat(30_000) + i, "m"));
    }
    report("a.B", cases.toArray(new String[0]));
    String error = withPrefix(render(), "::error title=more-failures").get(0);
    String message = error.substring("::error title=more-failures::".length());
    assertThat(message).hasSize(60_000).startsWith("remaining=4 a.B#f10, a.B#nnnn");
  }

  @Test
  void tenErrorsPickOneCasePerClassFirst() throws IOException {
    List<String> many = new ArrayList<>();
    for (int i = 1; i <= 12; i++) {
      many.add(fail("a.A", "f" + i, "a" + i));
    }
    report("a.A", many.toArray(new String[0]));
    report("b.B", pass("b.B", "ok"), fail("b.B", "g", "bee"));
    report("c.C", error("c.C", "h", "sea"));
    List<String> lines = render();
    List<String> titles =
        withPrefix(lines, "::error").stream()
            .map(l -> l.substring("::error title=".length(), l.indexOf("::", 10)))
            .toList();
    assertThat(titles)
        .containsExactly(
            "a.A#f1",
            "b.B#g",
            "c.C#h",
            "a.A#f2",
            "a.A#f3",
            "a.A#f4",
            "a.A#f5",
            "a.A#f6",
            "a.A#f7",
            "more-failures");
    assertThat(withPrefix(lines, "::error").get(9))
        .isEqualTo(
            "::error title=more-failures::remaining=5 a.A#f8, a.A#f9, a.A#f10, a.A#f11, a.A#f12");

    // more failing classes than the cap: the first nine classes, the rest in the summary
    for (int i = 0; i < 12; i++) {
      String cls = "k.C" + (i < 10 ? "0" : "") + i;
      report(cls, fail(cls, "x", "boom"));
    }
    List<String> second = render();
    assertThat(withPrefix(second, "::error")).hasSize(10);
    assertThat(withPrefix(second, "::error").get(9))
        .startsWith("::error title=more-failures::remaining=");
  }

  @Test
  void skippedCasesAreOneWarning() throws IOException {
    report("a.B", skip("a.B", "s1"), pass("a.B", "m"), skip("a.B", "s2"));
    report("c.D", skip("c.D", "s3"));
    List<String> lines = render();
    assertThat(withPrefix(lines, "::warning"))
        .containsExactly("::warning title=skipped::skipped=3 a.B#s1, a.B#s2, c.D#s3");
    assertThat(withPrefix(lines, "::error")).isEmpty();
    assertThat(lines)
        .containsExactly(
            "::notice title=tally-total::classes=2 run=4 failed=0 errors=0 skipped=3",
            "::warning title=skipped::skipped=3 a.B#s1, a.B#s2, c.D#s3",
            "::notice title=tally-classes::classes=2 class=a.B run=3 failed=0 errors=0 skipped=2;"
                + " class=c.D run=1 failed=0 errors=0 skipped=1");
  }

  @Test
  void aCleanReportPrintsOnlyTheTallyNotices() throws IOException {
    report("a.B", pass("a.B", "m"), pass("a.B", "n"));
    report("c.D", pass("c.D", "p"));
    assertThat(render())
        .containsExactly(
            "::notice title=tally-total::classes=2 run=3 failed=0 errors=0 skipped=0",
            "::notice title=tally-classes::classes=2 class=a.B run=2 failed=0 errors=0 skipped=0;"
                + " class=c.D run=1 failed=0 errors=0 skipped=0");
  }

  @Test
  void everyClassIsInOneNoticeAndTheTotalComesFirst() throws IOException {
    // GitHub keeps only about ten notices per level and step, and a job has more test classes than
    // that: one notice holds all of them, so none is cut (CI-2)
    for (int i = 1; i <= 12; i++) {
      String cls = "k.C" + (i < 10 ? "0" : "") + i;
      report(cls, pass(cls, "m"), skip(cls, "s"));
    }
    report("z.F", fail("z.F", "f", "boom"));
    List<String> lines = render();
    List<String> notices = withPrefix(lines, "::notice");
    assertThat(notices).hasSize(2);
    assertThat(notices.get(0))
        .isEqualTo("::notice title=tally-total::classes=13 run=25 failed=1 errors=0 skipped=12");
    assertThat(lines.get(0)).startsWith("::notice title=tally-total::");
    String classes = notices.get(1);
    assertThat(classes).startsWith("::notice title=tally-classes::classes=13 class=k.C01 run=2");
    for (int i = 1; i <= 12; i++) {
      assertThat(classes).contains("class=k.C" + (i < 10 ? "0" : "") + i + " run=2 failed=0");
    }
    assertThat(classes).endsWith("class=z.F run=1 failed=1 errors=0 skipped=0");
  }

  @Test
  void theClassTalliesAreCutAt60000Characters() throws IOException {
    for (int i = 0; i < 3; i++) {
      String cls = "n".repeat(30_000) + i;
      Path file = dir.resolve("target/surefire-reports/TEST-long" + i + ".xml");
      Files.createDirectories(file.getParent());
      Files.writeString(
          file, "<testsuite name=\"x\">" + pass(cls, "m") + "</testsuite>", StandardCharsets.UTF_8);
    }
    String notice = withPrefix(render(), "::notice title=tally-classes").get(0);
    String message = notice.substring("::notice title=tally-classes::".length());
    assertThat(message).hasSize(60_000).startsWith("classes=3 class=nnnn");
  }

  @Test
  void noReportsPrintOnlyAnEmptyTotal() {
    assertThat(render())
        .containsExactly("::notice title=tally-total::classes=0 run=0 failed=0 errors=0 skipped=0");
  }

  @Test
  void credentialsInAMessageAreRedacted() throws IOException {
    report(
        "a.B",
        fail(
            "a.B",
            "pw",
            "connect failed password=hunter2 for jdbc:postgresql://admin:s3cret@db.example/x "
                + "and Token: abc123 and API_KEY=k-9 and secret = zzz"),
        fail("a.B", "edge", "x".repeat(290) + " password=averylongsecretvalue"));
    List<String> errors = withPrefix(render(), "::error");
    String all = String.join("\n", errors);
    assertThat(all)
        .doesNotContain("hunter2", "s3cret", "abc123", "k-9", "zzz", "averylong", "secretvalue");
    assertThat(errors.get(0))
        .contains("connect failed [REDACTED]")
        .contains("jdbc:postgresql://[REDACTED]@db.example/x")
        .contains("and [REDACTED]");
    assertThat(errors.get(1)).endsWith(" [REDACTED");
  }

  @Test
  void everyCredentialKeywordIsRedacted() throws IOException {
    report(
        "a.B",
        fail(
            "a.B", "kw", "bad passwd=x1 and apikey: y2 and api_key=z3 and token=t4 and secret=s5"));
    String error = withPrefix(render(), "::error").get(0);
    assertThat(error).doesNotContain("x1", "y2", "z3", "t4", "s5").doesNotContain("passwd");
    assertThat(error).contains("bad [REDACTED] and [REDACTED] and [REDACTED]");
  }

  @Test
  void aCarriageReturnInANameOrAClassCannotStartAnotherCommandEither() throws IOException {
    report(
        "a.B",
        "<testcase classname=\"x&#13;::error title=forged::y\" name=\"t&#13;::error title=z::w\">"
            + "<failure message=\"m\"/></testcase>");
    List<String> lines = render();
    assertThat(lines).allSatisfy(l -> assertThat(l).doesNotContain("\r").doesNotContain("\n"));
    assertThat(lines)
        .anySatisfy(
            l ->
                assertThat(l)
                    .startsWith(
                        "::notice title=tally-classes::classes=1 class=x%0D::error title="));
    assertThat(lines).anySatisfy(l -> assertThat(l).startsWith("::error title=x%0D"));
    assertThat(withPrefix(lines, "::error")).hasSize(1);
  }

  @Test
  void theSkippedWarningIsCutAt60000Characters() throws IOException {
    List<String> cases = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      cases.add(skip("a.B", "n".repeat(30_000) + i));
    }
    report("a.B", cases.toArray(new String[0]));
    String warning = withPrefix(render(), "::warning title=skipped").get(0);
    String message = warning.substring("::warning title=skipped::".length());
    assertThat(message).hasSize(60_000).startsWith("skipped=3 a.B#nnnn");
  }

  @Test
  void aTestNameWithALineFeedCannotStartAnotherCommand() throws IOException {
    report(
        "a.B",
        "<testcase classname=\"a.B\" name=\"evil&#10;::error title=x::y,z%\">"
            + "<failure message=\"bad&#10;::error title=forged::message\"/></testcase>");
    List<String> lines = render();
    assertThat(lines).allSatisfy(l -> assertThat(l).doesNotContain("\n").doesNotContain("\r"));
    assertThat(lines).hasSize(3);
    assertThat(lines.get(1))
        .isEqualTo("::error title=a.B#evil%0A%3A%3Aerror title=x%3A%3Ay%2Cz%25::bad");
    assertThat(withPrefix(lines, "::error")).hasSize(1);
  }

  @Test
  void reportsAreCountedOnceWhateverTheRootsAndSkipVersionControlDirectories() throws IOException {
    report("a.B", pass("a.B", "m"));
    Path failsafe = dir.resolve("zmod/target/failsafe-reports");
    Files.createDirectories(failsafe);
    Files.writeString(
        failsafe.resolve("TEST-a.BIT.xml"),
        "<testsuite name=\"a.BIT\"><testcase classname=\"a.BIT\" name=\"it()\"/></testsuite>",
        StandardCharsets.UTF_8);
    Path git = dir.resolve(".git/x");
    Files.createDirectories(git);
    Files.writeString(
        git.resolve("TEST-g.H.xml"),
        "<testsuite><testcase classname=\"g.H\" name=\"m\"/></testsuite>",
        StandardCharsets.UTF_8);
    List<String> lines = Annotations.render(List.of(dir, dir.resolve("zmod"), dir));
    assertThat(lines)
        .containsExactly(
            "::notice title=tally-total::classes=2 run=2 failed=0 errors=0 skipped=0",
            "::notice title=tally-classes::classes=2 class=a.B run=1 failed=0 errors=0 skipped=0;"
                + " class=a.BIT run=1 failed=0 errors=0 skipped=0");
  }

  @Test
  void aMalformedOrDoctypeReportYieldsNothing() throws IOException {
    Path bad = dir.resolve("TEST-x.Y.xml");
    Files.writeString(
        bad,
        "<!DOCTYPE testsuite [<!ENTITY e \"v\">]><testsuite>"
            + "<testcase classname=\"x.Y\" name=\"m\"><failure message=\"&e;\"/></testcase>"
            + "</testsuite>",
        StandardCharsets.UTF_8);
    Files.writeString(dir.resolve("TEST-z.Z.xml"), "<testsuite><testcase", StandardCharsets.UTF_8);
    assertThat(render())
        .containsExactly("::notice title=tally-total::classes=0 run=0 failed=0 errors=0 skipped=0");
  }

  @Test
  void aLongHostileFirstLineIsRedactedInLinearTime() throws IOException {
    // "://" followed by "a:" repeated and no "@", "/" or white space made the user-info pattern
    // quadratic (CI-3): 20,000 pairs took 2.4 s and 100,000 pairs would take about a minute
    String hostile = "://" + "a:".repeat(100_000);
    report(
        "a.B", fail("a.B", "hostile", hostile), fail("a.B", "words", "password ".repeat(50_000)));
    List<String> errors =
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> withPrefix(render(), "::error"));
    assertThat(errors).hasSize(2);
    assertThat(errors.get(0)).isEqualTo("::error title=a.B#hostile::" + hostile.substring(0, 300));
  }

  @Test
  void userInfoIsStillRedactedWhateverItsShape() throws IOException {
    report(
        "a.B",
        fail("a.B", "one", "at jdbc:postgresql://user:pw@host/db"),
        fail("a.B", "colons", "at https://user:pa:ss@host/x and ftp://u:p@h"),
        fail("a.B", "none", "see http://host:8080/path and ://:"));
    List<String> errors = withPrefix(render(), "::error");
    assertThat(errors.get(0)).endsWith("jdbc:postgresql://[REDACTED]@host/db");
    assertThat(errors.get(1))
        .endsWith("https://[REDACTED]@host/x and ftp://[REDACTED]@h")
        .doesNotContain("pa:ss");
    assertThat(errors.get(2)).endsWith("see http://host:8080/path and ://:");
  }
}
