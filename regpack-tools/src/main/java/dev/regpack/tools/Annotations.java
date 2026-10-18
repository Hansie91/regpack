package dev.regpack.tools;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Turns the {@code TEST-*.xml} reports of surefire and failsafe into GitHub workflow commands, so
 * that the counts and the failures of a run can be read from the unauthenticated check-run
 * annotation listing without downloading an artefact or reading a log (P3a plan, D-L6). The output
 * holds one {@code tally-total} notice first (GitHub keeps only about ten notices per level and
 * step, so the total must not be the one that is cut), then one {@code tally} notice per test
 * class, one {@code skipped} warning for all skipped cases, at most ten error annotations (the
 * first failed case of each distinct class first, then the rest in report order) and, when more
 * cases failed, one final notice with the names of the rest.
 *
 * <p>Only the first line of a failure message is used. Credentials are redacted before the message
 * is cut to 300 characters. Titles and messages are escaped as workflow command data, so a test
 * name or a message cannot start another command.
 */
public final class Annotations {
  private static final int MAX_ERRORS = 10;
  private static final int MAX_MESSAGE = 300;
  private static final int MAX_LIST = 60_000;
  private static final String REDACTED = "[REDACTED]";
  private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", "node_modules");
  private static final Pattern SECRET =
      Pattern.compile("(?i)(password|passwd|secret|token|apikey|api_key)\\s*[=:]\\s*\\S+");
  private static final Pattern USERINFO = Pattern.compile("://[^/\\s@]*:[^/\\s@]*@");
  private static final Pattern LINE_END = Pattern.compile("\\R");

  private Annotations() {}

  /** One test case of a report; {@code index} keeps cases of equal name apart. */
  private record Case(int index, String className, String name, Outcome outcome, String message) {
    String id() {
      return className + "#" + name;
    }
  }

  private enum Outcome {
    PASSED,
    FAILED,
    ERRORED,
    SKIPPED
  }

  /** The counts of one test class. */
  private static final class Tally {
    int run;
    int failed;
    int errors;
    int skipped;

    void add(Outcome outcome) {
      run++;
      switch (outcome) {
        case FAILED -> failed++;
        case ERRORED -> errors++;
        case SKIPPED -> skipped++;
        case PASSED -> {}
      }
    }

    String counts() {
      return "run=" + run + " failed=" + failed + " errors=" + errors + " skipped=" + skipped;
    }
  }

  /**
   * Renders the workflow commands for the reports found under the scan roots. A report that cannot
   * be read or parsed contributes nothing; a root listed twice (or nested in another) is read once.
   *
   * @return the command lines, in the order total, tallies, warning, errors, final notice
   */
  public static List<String> render(List<Path> scanRoots) {
    List<Case> cases = new ArrayList<>();
    for (Path file : reports(scanRoots)) {
      XmlReports.parse(file).ifPresent(doc -> readCases(doc, cases));
    }
    Map<String, Tally> perClass = new LinkedHashMap<>();
    Tally total = new Tally();
    for (Case c : cases) {
      perClass.computeIfAbsent(c.className(), k -> new Tally()).add(c.outcome());
      total.add(c.outcome());
    }
    List<String> lines = new ArrayList<>();
    // The total comes first: GitHub keeps only about ten notices per level and step, and a job
    // with more test classes than that would otherwise lose the total.
    lines.add("::notice title=tally-total::classes=" + perClass.size() + " " + total.counts());
    perClass.forEach(
        (className, tally) ->
            lines.add(
                "::notice title=tally::"
                    + escapeMessage("class=" + className + " " + tally.counts())));
    List<Case> skipped = cases.stream().filter(c -> c.outcome() == Outcome.SKIPPED).toList();
    if (!skipped.isEmpty()) {
      String names = String.join(", ", skipped.stream().map(Case::id).toList());
      lines.add(
          "::warning title=skipped::"
              + escapeMessage(cut("skipped=" + skipped.size() + " " + names, MAX_LIST)));
    }
    List<Case> failed =
        cases.stream()
            .filter(c -> c.outcome() == Outcome.FAILED || c.outcome() == Outcome.ERRORED)
            .toList();
    List<Case> shown = pickErrors(failed);
    for (Case c : shown) {
      lines.add("::error title=" + escapeTitle(c.id()) + "::" + escapeMessage(c.message()));
    }
    List<Case> rest = failed.stream().filter(c -> !shown.contains(c)).toList();
    if (!rest.isEmpty()) {
      String names = String.join(", ", rest.stream().map(Case::id).toList());
      lines.add(
          "::notice title=more-failures::"
              + escapeMessage(cut("remaining=" + rest.size() + " " + names, MAX_LIST)));
    }
    return List.copyOf(lines);
  }

  /** The first failed case of each distinct class, then the rest in report order, at most ten. */
  private static List<Case> pickErrors(List<Case> failed) {
    List<Case> picked = new ArrayList<>();
    Set<String> classes = new HashSet<>();
    for (Case c : failed) {
      if (picked.size() < MAX_ERRORS && classes.add(c.className())) {
        picked.add(c);
      }
    }
    for (Case c : failed) {
      if (picked.size() < MAX_ERRORS && !picked.contains(c)) {
        picked.add(c);
      }
    }
    return picked;
  }

  /** The report files under the roots, each once, in path order. */
  private static List<Path> reports(List<Path> scanRoots) {
    Set<Path> found = new TreeSet<>();
    for (Path root : scanRoots) {
      try {
        Files.walkFileTree(
            root,
            new SimpleFileVisitor<>() {
              @Override
              public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path name = dir.getFileName();
                boolean skipped =
                    !dir.equals(root)
                        && name != null
                        && SKIPPED_DIRECTORIES.contains(name.toString());
                return skipped ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (name.startsWith("TEST-") && name.endsWith(".xml")) {
                  found.add(file.toAbsolutePath().normalize());
                }
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
              }
            });
      } catch (IOException e) {
        // an unreadable scan root yields no reports
      }
    }
    return List.copyOf(found);
  }

  private static void readCases(Document doc, List<Case> out) {
    NodeList testcases = doc.getElementsByTagName("testcase");
    for (int i = 0; i < testcases.getLength(); i++) {
      Element testcase = (Element) testcases.item(i);
      String className = testcase.getAttribute("classname");
      if (className.isEmpty() && testcase.getParentNode() instanceof Element suite) {
        className = suite.getAttribute("name");
      }
      String name = testcase.getAttribute("name");
      if (name.endsWith("()")) {
        name = name.substring(0, name.length() - 2);
      }
      Outcome outcome = Outcome.PASSED;
      String message = "";
      for (Node child = testcase.getFirstChild(); child != null; child = child.getNextSibling()) {
        if (!(child instanceof Element element)) {
          continue;
        }
        Outcome found =
            switch (element.getTagName()) {
              case "failure" -> Outcome.FAILED;
              case "error" -> Outcome.ERRORED;
              case "skipped" -> Outcome.SKIPPED;
              default -> Outcome.PASSED;
            };
        if (found != Outcome.PASSED && outcome == Outcome.PASSED) {
          outcome = found;
          message = message(element);
        }
      }
      out.add(new Case(out.size(), className, name, outcome, message));
    }
  }

  /** The message attribute, else the text: its first line, redacted, then cut to 300 characters. */
  private static String message(Element failure) {
    String raw = failure.getAttribute("message");
    if (raw.isBlank()) {
      raw = failure.getTextContent();
    }
    if (raw == null) {
      raw = "";
    }
    String firstLine = LINE_END.split(raw.stripLeading(), 2)[0];
    return cut(redact(firstLine), MAX_MESSAGE);
  }

  private static String redact(String text) {
    String withoutUserinfo = USERINFO.matcher(text).replaceAll("://" + REDACTED + "@");
    return SECRET.matcher(withoutUserinfo).replaceAll(REDACTED);
  }

  private static String cut(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max);
  }

  /** Escapes a workflow command message: {@code %}, CR and LF. */
  private static String escapeMessage(String text) {
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
  }

  /** Escapes a workflow command property value: {@code %}, CR, LF, {@code :} and {@code ,}. */
  private static String escapeTitle(String text) {
    return escapeMessage(text).replace(":", "%3A").replace(",", "%2C");
  }
}
