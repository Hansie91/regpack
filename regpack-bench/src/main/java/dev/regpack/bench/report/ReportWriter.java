package dev.regpack.bench.report;

import dev.regpack.bench.eval.Basis;
import dev.regpack.bench.eval.Criteria;
import dev.regpack.bench.eval.Criterion;
import dev.regpack.bench.eval.CriterionResult;
import dev.regpack.bench.eval.MeasurementSet.RunInfo;
import dev.regpack.bench.eval.SpikeResult;
import dev.regpack.bench.eval.Verdict;
import dev.regpack.bench.stats.Estimate;
import dev.regpack.model.json.Json;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Renders the generated part of a spike report. It begins with exactly nine header lines, never
 * shows a verdict other than PASS as a pass, labels a noisy PASS from a single or an unknown number
 * of virtual machines and an extrapolated PASS, and ends with a line {@code ---}: the hand-written
 * interpretation goes below it. Text that comes from result fields is escaped for Markdown (pipes,
 * backticks, angle and square brackets, bangs, asterisks, underscores, a leading hash, line
 * breaks).
 */
public final class ReportWriter {
  private static final String PASS = "PASS";

  private ReportWriter() {}

  /**
   * @param r the result
   * @param c the criteria the result was computed against; its digest must equal the result's
   * @return the Markdown, LF line ends, ending with {@code ---} and a line feed
   * @throws IllegalArgumentException when the result was computed against other criteria
   */
  public static String render(SpikeResult r, Criteria c) {
    if (!r.criteriaDigest().equals(c.digest().hex())) {
      throw new IllegalArgumentException(
          "the result was computed against other criteria: "
              + r.criteriaDigest()
              + " and "
              + c.digest().hex());
    }
    StringBuilder out = new StringBuilder();
    boolean summary = r.evidenceLevel().equals("SUMMARY");
    out.append("# ").append(r.spike()).append(" report\n");
    out.append("spike: ").append(r.spike()).append('\n');
    out.append("status: ").append(summary ? "PENDING" : r.status().name()).append('\n');
    out.append("scope: ").append(r.scope()).append('\n');
    out.append("evidence_level: ").append(r.evidenceLevel()).append('\n');
    out.append("criteria_digest: ").append(r.criteriaDigest()).append('\n');
    out.append("source_git_sha: ").append(sourceSha(r)).append('\n');
    out.append("runs: ")
        .append(r.runs().size())
        .append(" (vmCount ")
        .append(r.vmCount())
        .append(")\n");
    out.append("time_box: used ")
        .append(plain(r.usedMinutes()))
        .append(" of ")
        .append(plain(r.boxMinutes()))
        .append(" minutes, hit: ")
        .append(SpikeResult.boxHit(r.timeBox()) ? "yes" : "no")
        .append('\n');
    out.append('\n');

    if (summary) {
      out.append("computed_status: ").append(r.status().name()).append("\n\n");
      out.append("Summary evidence: the verdicts below were computed from a pasted summary, ");
      out.append("not from the raw measurements. They cannot be verified, the status is ");
      out.append("PENDING and this report never satisfies a gate.\n\n");
    }

    out.append("## Criteria\n\n");
    out.append("| Id | Verdict | Basis | Lower | Point | Upper | Threshold | Margin | Reason |\n");
    out.append("|---|---|---|---|---|---|---|---|---|\n");
    boolean anyExtrapolatedPass = false;
    for (CriterionResult row : r.criteria()) {
      Optional<Criterion> criterion = c.byId(row.id());
      boolean noisy = criterion.map(Criterion::noisy).orElse(false);
      String verdict = verdictText(row, noisy, r.vmCount());
      anyExtrapolatedPass |= row.verdict() == Verdict.PASS && row.basis() == Basis.EXTRAPOLATED;
      Optional<Estimate> e = row.estimate();
      out.append("| ").append(cell(row.id()));
      out.append(" | ").append(verdict);
      out.append(" | ").append(row.basis().name());
      out.append(" | ").append(e.map(x -> num(x.lower())).orElse("-"));
      out.append(" | ").append(e.map(x -> num(x.point())).orElse("-"));
      out.append(" | ").append(e.map(x -> num(x.upper())).orElse("-"));
      out.append(" | ").append(threshold(row, criterion));
      out.append(" | ").append(plain(row.margin()));
      out.append(" | ").append(reasonCell(row));
      out.append(" |\n");
    }
    out.append('\n');
    if (anyExtrapolatedPass) {
      out.append("An EXTRAPOLATED PASS needs the owner's `ack:` line in the freeze record.\n\n");
    }

    out.append("## Warnings\n\n");
    List<String> warnings = warnings(r);
    if (warnings.isEmpty()) {
      out.append("- none\n");
    }
    for (String warning : warnings) {
      out.append("- ").append(warning).append('\n');
    }

    out.append("\n## Decisions\n\n");
    if (r.decisions().isEmpty()) {
      out.append("- none\n");
    }
    for (Map.Entry<String, Object> entry : new TreeMap<>(r.decisions()).entrySet()) {
      out.append("- ")
          .append(cell(entry.getKey()))
          .append(": ")
          .append(cell(Json.canonical(entry.getValue())))
          .append('\n');
    }

    out.append("\n## Open\n\n");
    if (r.open().isEmpty()) {
      out.append("- none\n");
    }
    for (String item : r.open()) {
      out.append("- ").append(cell(item)).append('\n');
    }

    out.append("\n## Environment\n\n");
    Map<String, Object> env = r.env();
    out.append("- CPU model: ").append(cell(envText(env.get("cpu.model")))).append('\n');
    out.append("- vCPUs: ").append(cell(envText(env.get("cpu.count")))).append('\n');
    out.append("- Physical cores: ")
        .append(cell(envText(env.get("cpu.physicalCores"))))
        .append('\n');
    out.append("- Memory (bytes): ").append(cell(envText(env.get("mem.totalBytes")))).append('\n');
    out.append("- Image version: ").append(cell(imageVersion(r))).append('\n');
    out.append("\nP-5 steal discard observable: ")
        .append(Boolean.TRUE.equals(env.get("steal.observable")) ? "yes" : "no")
        .append('\n');

    out.append("\n## Runs\n\n");
    out.append("| Run | Attempt | Part | Git SHA | CPU model | Image |\n");
    out.append("|---|---|---|---|---|---|\n");
    for (RunInfo run : r.runs()) {
      out.append("| ").append(cell(run.runId()));
      out.append(" | ").append(run.attempt());
      out.append(" | ").append(cell(run.part()));
      out.append(" | ").append(cell(run.gitSha()));
      out.append(" | ").append(cell(run.cpuModel()));
      out.append(" | ").append(cell(run.imageVersion()));
      out.append(" |\n");
    }
    out.append("\nGit SHAs of the pooled runs: ");
    TreeSet<String> shas = distinctShas(r);
    out.append(shas.isEmpty() ? "none" : cell(String.join(", ", shas))).append('\n');
    out.append("\n---\n");
    return out.toString();
  }

  private static List<String> warnings(SpikeResult r) {
    List<String> warnings = new ArrayList<>();
    if (r.vmCount() == 1) {
      warnings.add("forks share one VM, bounds are optimistic");
    }
    if (r.vmCount() == 0) {
      warnings.add("no run provenance is recorded: the number of virtual machines is unknown");
    }
    if (r.mixedHardware()) {
      warnings.add("mixed hardware: the pooled runs used more than one CPU model");
    }
    TreeSet<String> shas = distinctShas(r);
    if (shas.size() > 1) {
      warnings.add(
          "the pooled runs come from "
              + shas.size()
              + " different git SHAs: "
              + cell(String.join(", ", shas)));
    }
    return warnings;
  }

  private static TreeSet<String> distinctShas(SpikeResult r) {
    TreeSet<String> shas = new TreeSet<>();
    for (RunInfo run : r.runs()) {
      shas.add(run.gitSha());
    }
    return shas;
  }

  /** PASS gets its labels; any other verdict is plain text and never contains PASS. */
  private static String verdictText(CriterionResult row, boolean noisy, int vmCount) {
    if (row.verdict() != Verdict.PASS) {
      return row.verdict().name();
    }
    StringBuilder text = new StringBuilder(PASS);
    if (row.basis() == Basis.EXTRAPOLATED) {
      text.append(" (EXTRAPOLATED)");
    }
    if (noisy && vmCount == 1) {
      text.append(" (single VM)");
    } else if (noisy && vmCount < 1) {
      text.append(" (VMs unknown)");
    }
    return text.toString();
  }

  private static String reasonCell(CriterionResult row) {
    String text = cell(row.reason());
    if (row.verdict() != Verdict.PASS) {
      text = text.replace(PASS, "pass");
    }
    return text;
  }

  private static String threshold(CriterionResult row, Optional<Criterion> criterion) {
    if (row.threshold().isEmpty()) {
      return "-";
    }
    String number = plain(row.threshold().get());
    return criterion
        .map(
            c ->
                switch (c.direction()) {
                  case AT_LEAST -> "at least " + number;
                  case AT_MOST -> "at most " + number;
                  case MEASURED_ONLY -> "-";
                })
        .orElse(number);
  }

  private static String sourceSha(SpikeResult r) {
    Object sha = r.source().get("gitSha");
    if (sha instanceof String s && !s.isBlank()) {
      return headerValue(s);
    }
    TreeSet<String> shas = distinctShas(r);
    if (shas.size() == 1) {
      return headerValue(shas.first());
    }
    return shas.isEmpty() ? "unknown" : "mixed";
  }

  /** A single-line, unambiguous header value. */
  private static String headerValue(String text) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < text.length() && i < 64; i++) {
      char ch = text.charAt(i);
      boolean plain =
          (ch >= '0' && ch <= '9')
              || (ch >= 'a' && ch <= 'z')
              || (ch >= 'A' && ch <= 'Z')
              || ch == '.'
              || ch == '_'
              || ch == '-';
      out.append(plain ? ch : '?');
    }
    return out.length() == 0 ? "unknown" : out.toString();
  }

  private static String imageVersion(SpikeResult r) {
    TreeSet<String> versions = new TreeSet<>();
    for (RunInfo run : r.runs()) {
      versions.add(run.imageVersion());
    }
    if (!versions.isEmpty()) {
      return String.join(", ", versions);
    }
    Object env = r.env().get("env");
    if (env instanceof Map<?, ?> variables && variables.get("ImageVersion") instanceof String s) {
      return s;
    }
    return "unknown";
  }

  private static String envText(Object value) {
    return value == null ? "unknown" : Json.canonical(value).replaceAll("^\"|\"$", "");
  }

  private static String num(BigDecimal value) {
    return plain(value.setScale(6, RoundingMode.HALF_EVEN));
  }

  private static String plain(BigDecimal value) {
    return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
  }

  /** Escapes text from a result field for a Markdown table cell or list item. */
  public static String cell(String text) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      switch (ch) {
        case '\\' -> out.append("\\\\");
        case '|' -> out.append("\\|");
        case '`' -> out.append('\'');
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        // links, images and emphasis from CI are text, never Markdown
        case '[', ']', '!', '*', '_' -> out.append('\\').append(ch);
        // a heading marker matters only at the start (a list item or a cell)
        case '#' -> out.append(i == 0 ? "\\#" : "#");
        case '\r', '\n' -> out.append(' ');
        default -> out.append(ch);
      }
    }
    return out.toString();
  }
}
