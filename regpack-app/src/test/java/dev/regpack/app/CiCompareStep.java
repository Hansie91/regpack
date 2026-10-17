package dev.regpack.app;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the cross-operating-system determinism check of {@code ci.yml} by its meaning, as text (no
 * YAML library): which files each build uploads, and whether the compare step reads both digest
 * files of both operating systems, refuses an empty or missing one, and fails the step when two
 * differ. {@link #problems} is the one checker; {@code CiDeterminismCompareTest} runs it on the
 * real workflow, on deliberately broken copies of it, and executes the real step on directories it
 * builds.
 */
final class CiCompareStep {
  static final List<String> DIGEST_FILES = List.of("toy-digest.txt", "pack-toy-digest.txt");
  static final List<String> OPERATING_SYSTEMS = List.of("ubuntu-24.04", "windows-2025");

  private CiCompareStep() {}

  /** The lines of the step of a job: from its {@code - name:} line to the next step or job. */
  static List<String> stepLines(String ci, String jobId, String stepName) {
    List<String> lines = List.of(ci.split("\n", -1));
    int job = lines.indexOf("  " + jobId + ":");
    if (job < 0) {
      return List.of();
    }
    int jobEnd = lines.size();
    for (int i = job + 1; i < lines.size(); i++) {
      if (lines.get(i).matches("  [a-z][a-z-]*:")) {
        jobEnd = i;
        break;
      }
    }
    int start = -1;
    for (int i = job + 1; i < jobEnd; i++) {
      if (lines.get(i).equals("      - name: " + stepName)) {
        start = i;
        break;
      }
    }
    if (start < 0) {
      return List.of();
    }
    int end = jobEnd;
    for (int i = start + 1; i < jobEnd; i++) {
      if (lines.get(i).startsWith("      - ")) {
        end = i;
        break;
      }
    }
    return lines.subList(start, end);
  }

  /** The lines of the job, for settings that apply to all its steps. */
  static List<String> jobLines(String ci, String jobId) {
    List<String> lines = List.of(ci.split("\n", -1));
    int job = lines.indexOf("  " + jobId + ":");
    if (job < 0) {
      return List.of();
    }
    int end = lines.size();
    for (int i = job + 1; i < lines.size(); i++) {
      if (lines.get(i).matches("  [a-z][a-z-]*:")) {
        end = i;
        break;
      }
    }
    return lines.subList(job, end);
  }

  /** The shell text of a step's {@code run: |} block, de-indented; empty when it has none. */
  static String runBlock(List<String> step) {
    int run = -1;
    for (int i = 0; i < step.size(); i++) {
      if (step.get(i).equals("        run: |")) {
        run = i;
      }
    }
    if (run < 0) {
      return "";
    }
    StringBuilder text = new StringBuilder();
    for (int i = run + 1; i < step.size(); i++) {
      String line = step.get(i);
      if (!line.isBlank() && !line.startsWith("          ")) {
        break;
      }
      text.append(line.isBlank() ? "" : line.substring(10)).append('\n');
    }
    return text.toString();
  }

  /** Everything that makes the determinism check unable to fail; empty when it is sound. */
  static List<String> problems(String ci) {
    List<String> problems = new ArrayList<>();
    uploadProblems(ci, problems);
    compareProblems(ci, problems);
    return problems;
  }

  private static void uploadProblems(String ci, List<String> problems) {
    List<String> build = jobLines(ci, "build");
    if (build.stream().noneMatch(l -> l.equals("        os: [ubuntu-24.04, windows-2025]"))) {
      problems.add("the build job does not run on both " + OPERATING_SYSTEMS);
    }
    List<String> upload = stepLines(ci, "build", "Upload toy crash digest");
    if (upload.isEmpty()) {
      problems.add("no step 'Upload toy crash digest' in the build job");
      return;
    }
    if (!upload.contains("          name: toy-digest-${{ matrix.os }}")) {
      problems.add("the digest upload is not named toy-digest-<matrix os>");
    }
    int path = upload.indexOf("          path: |");
    List<String> listed = new ArrayList<>();
    for (int i = path < 0 ? upload.size() : path + 1; i < upload.size(); i++) {
      if (!upload.get(i).startsWith("            ")) {
        break;
      }
      listed.add(upload.get(i).strip());
    }
    for (String file : DIGEST_FILES) {
      if (!listed.contains("regpack-engine/target/" + file)) {
        problems.add("the digest upload does not list regpack-engine/target/" + file);
      }
    }
    if (!upload.contains("          if-no-files-found: error")) {
      problems.add("the digest upload does not fail when a file is missing");
    }
    if (upload.stream().anyMatch(l -> l.contains("continue-on-error"))) {
      problems.add("the digest upload may fail silently");
    }
  }

  private static void compareProblems(String ci, List<String> problems) {
    List<String> job = jobLines(ci, "determinism-compare");
    if (job.isEmpty()) {
      problems.add("no job determinism-compare");
      return;
    }
    if (job.stream().anyMatch(l -> l.contains("continue-on-error"))) {
      problems.add("the compare job may fail silently");
    }
    if (!job.contains("    needs: build")) {
      problems.add("the compare job does not need the build job");
    }
    if (!job.contains("          pattern: toy-digest-*")
        || !job.contains("          path: digests")) {
      problems.add("the compare job does not download every toy-digest-* artefact into digests");
    }
    List<String> step = stepLines(ci, "determinism-compare", "Compare toy crash digests");
    if (step.isEmpty()) {
      problems.add("no step 'Compare toy crash digests' in the compare job");
      return;
    }
    for (String line : step) {
      String stripped = line.strip();
      if (stripped.startsWith("continue-on-error") || stripped.startsWith("if:")) {
        problems.add("the compare step may be skipped or may fail silently: " + stripped);
      }
    }
    if (!step.contains("        shell: bash")) {
      problems.add("the compare step does not run under bash");
    }
    List<String> script =
        runBlock(step)
            .lines()
            .map(String::strip)
            .filter(l -> !l.isEmpty() && !l.startsWith("#"))
            .toList();
    if (script.isEmpty() || !script.get(0).equals("set -euo pipefail")) {
      problems.add("the compare step does not start with 'set -euo pipefail'");
    }
    for (String line : script) {
      if (line.contains("|| true")
          || line.contains("|| :")
          || line.contains("set +e")
          || line.contains("exit 0")) {
        problems.add("the compare step cannot fail: " + line);
      }
    }
    for (String file : DIGEST_FILES) {
      pairProblems(script, file, problems);
    }
  }

  private static void pairProblems(List<String> script, String file, List<String> problems) {
    String[] variables = new String[OPERATING_SYSTEMS.size()];
    int[] assignedAt = new int[OPERATING_SYSTEMS.size()];
    for (int os = 0; os < OPERATING_SYSTEMS.size(); os++) {
      Pattern assignment =
          Pattern.compile(
              "([a-z]+)=\\$\\(cat digests/toy-digest-"
                  + Pattern.quote(OPERATING_SYSTEMS.get(os))
                  + "/"
                  + Pattern.quote(file)
                  + "\\)");
      for (int i = 0; i < script.size(); i++) {
        Matcher m = assignment.matcher(script.get(i));
        if (m.matches()) {
          variables[os] = m.group(1);
          assignedAt[os] = i;
        }
      }
      if (variables[os] == null) {
        problems.add("the compare step does not read " + file + " of " + OPERATING_SYSTEMS.get(os));
        return;
      }
    }
    String a = variables[0];
    String b = variables[1];
    if (a.equals(b)) {
      problems.add("the two " + file + " values are read into the same variable");
      return;
    }
    int emptyAt = -1;
    int compareAt = -1;
    for (int i = 0; i < script.size(); i++) {
      String line = script.get(i);
      if (line.contains("[ -n \"$" + a + "\" ]")
          && line.contains("[ -n \"$" + b + "\" ]")
          && line.contains("|| {")
          && line.endsWith("exit 1; }")) {
        emptyAt = i;
      }
      if ((line.startsWith("[ \"$" + a + "\" = \"$" + b + "\" ] || {")
              || line.startsWith("[ \"$" + b + "\" = \"$" + a + "\" ] || {"))
          && line.endsWith("exit 1; }")) {
        compareAt = i;
      }
    }
    if (emptyAt < 0) {
      problems.add("the compare step does not refuse an empty " + file);
    } else if (emptyAt < Math.max(assignedAt[0], assignedAt[1])) {
      problems.add("the empty check of " + file + " comes before the files are read");
    }
    if (compareAt < 0) {
      problems.add("the compare step does not fail when the two " + file + " values differ");
    } else if (compareAt < Math.max(assignedAt[0], assignedAt[1])) {
      problems.add("the comparison of " + file + " comes before the files are read");
    }
  }
}
