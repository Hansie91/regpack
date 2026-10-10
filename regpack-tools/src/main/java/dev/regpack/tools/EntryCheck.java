package dev.regpack.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The entry clauses of spec 16 as a check: a later plan may start only when the reports (or gates)
 * it needs exist. The wiring is a tab-separated file {@code docs/spikes/entry-wiring.tsv} with the
 * columns target, requires, kind and note; lines starting with {@code #} and blank lines are
 * skipped.
 */
public final class EntryCheck {
  private static final Set<String> KINDS = Set.of("REPORT_EXISTS", "STATUS_PASS", "GATE_CLEARED");
  private static final Pattern TARGET = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]*");
  private static final Pattern SPIKE_ID = Pattern.compile("SP-[A-Z]");
  private static final Pattern GATE_ID = Pattern.compile("G[0-9]+");
  private static final Set<String> REPORT_STATUSES = Set.of("PASS", "FAIL", "INCONCLUSIVE");

  private EntryCheck() {}

  /**
   * One row of the wiring.
   *
   * @param target the later plan or step that is blocked
   * @param requires a spike id {@code SP-<letter>} or a gate id {@code G<digits>}
   * @param kind {@code REPORT_EXISTS}, {@code STATUS_PASS} or {@code GATE_CLEARED}
   * @param note free text
   */
  public record Requirement(String target, String requires, String kind, String note) {}

  /**
   * The result of one target.
   *
   * @param satisfied true when every line is {@code OK}
   * @param lines one line per requirement: {@code entry: <target>: <requires> <kind> <state>}
   */
  public record Outcome(boolean satisfied, List<String> lines) {}

  /**
   * Parses the wiring file.
   *
   * @throws IllegalArgumentException on a malformed row, an unknown kind or an id that is not a
   *     spike id or gate id (an id is later used in a path)
   */
  public static List<Requirement> parse(String tsv) {
    List<Requirement> rows = new ArrayList<>();
    int number = 0;
    for (String line : tsv.lines().toList()) {
      number++;
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      String[] cells = line.split("\t", -1);
      if (cells.length < 3 || cells.length > 4) {
        throw new IllegalArgumentException(
            "entry wiring line " + number + ": expected 3 or 4 columns");
      }
      String target = cells[0].strip();
      String requires = cells[1].strip();
      String kind = cells[2].strip();
      String note = cells.length == 4 ? cells[3].strip() : "";
      if (!TARGET.matcher(target).matches()) {
        throw new IllegalArgumentException("entry wiring line " + number + ": bad target");
      }
      if (!KINDS.contains(kind)) {
        throw new IllegalArgumentException(
            "entry wiring line " + number + ": unknown kind " + kind);
      }
      Pattern id = kind.equals("GATE_CLEARED") ? GATE_ID : SPIKE_ID;
      if (!id.matcher(requires).matches()) {
        throw new IllegalArgumentException(
            "entry wiring line " + number + ": bad id for " + kind + ": " + requires);
      }
      rows.add(new Requirement(target, requires, kind, note));
    }
    return List.copyOf(rows);
  }

  /**
   * Checks one target against the repository.
   *
   * @param target the target name
   * @param all every row of the wiring
   * @param root the repository root
   * @return per requirement {@code OK}, {@code MISSING}, {@code SUMMARY_ONLY}, {@code NOT_PASS} or
   *     {@code NOT_CLEARED}
   * @throws IllegalArgumentException when no row has this target
   */
  public static Outcome check(String target, List<Requirement> all, Path root) {
    List<Requirement> wanted = all.stream().filter(r -> r.target().equals(target)).toList();
    if (wanted.isEmpty()) {
      throw new IllegalArgumentException("unknown target: " + target);
    }
    List<String> lines = new ArrayList<>();
    boolean satisfied = true;
    for (Requirement r : wanted) {
      String state = state(r, root);
      satisfied &= state.equals("OK");
      lines.add("entry: " + target + ": " + r.requires() + " " + r.kind() + " " + state);
    }
    return new Outcome(satisfied, List.copyOf(lines));
  }

  private static String state(Requirement r, Path root) {
    boolean gate = r.kind().equals("GATE_CLEARED");
    Path file =
        gate
            ? root.resolve("docs/gates/" + r.requires() + ".md")
            : root.resolve("docs/spikes/" + r.requires() + ".md");
    if (!Files.isRegularFile(file)) {
      return "MISSING";
    }
    Map<String, String> header;
    try {
      header = header(Files.readString(file, StandardCharsets.UTF_8), gate);
    } catch (IOException e) {
      return gate ? "NOT_CLEARED" : "NOT_PASS";
    }
    String status = header.getOrDefault("status", "");
    if (gate) {
      return status.equals("CLEARED") ? "OK" : "NOT_CLEARED";
    }
    if (!header.getOrDefault("evidence_level", "").equals("RAW")) {
      return "SUMMARY_ONLY";
    }
    boolean ok =
        r.kind().equals("STATUS_PASS") ? status.equals("PASS") : REPORT_STATUSES.contains(status);
    return ok ? "OK" : "NOT_PASS";
  }

  /**
   * The {@code key: value} lines of the header; the first occurrence of a key wins. A report's
   * header ends at the first blank line. A gate record has a heading before its status line, so its
   * header ends at the first {@code ##} heading or {@code ---} line. Text below the header (a
   * hand-written interpretation, the history) never counts.
   */
  private static Map<String, String> header(String text, boolean gateFile) {
    Map<String, String> values = new HashMap<>();
    for (String line : text.lines().toList()) {
      if (gateFile ? (line.startsWith("## ") || line.equals("---")) : line.isBlank()) {
        break;
      }
      int colon = line.indexOf(':');
      if (colon > 0) {
        values.putIfAbsent(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
      }
    }
    return values;
  }
}
