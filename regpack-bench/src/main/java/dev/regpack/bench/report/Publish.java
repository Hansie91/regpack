package dev.regpack.bench.report;

import dev.regpack.bench.eval.Criteria;
import dev.regpack.bench.eval.MeasurementSet;
import dev.regpack.bench.eval.ResultsEvaluator;
import dev.regpack.bench.eval.SpikeResult;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The last step of a CI job (and of a local run): evaluates {@code measurements.json} in the result
 * directory, writes {@code summary.json} and {@code report.md}, seals the directory (the manifest
 * is the last write), and appends the result and the {@code summary.json} to the job summary.
 *
 * <p>The commit that is recorded is {@code GITHUB_SHA} on a runner; anywhere else it is {@code git
 * rev-parse HEAD} and a working tree that differs from HEAD is refused (the measurement would
 * describe a commit that does not exist). The time used is not known here: it is counted from the
 * time ledger when the result is imported.
 */
public final class Publish {
  private static final long MAX_MEASUREMENTS_BYTES = 2L * 1024 * 1024;
  private static final Pattern OS_LABEL = Pattern.compile("[A-Za-z0-9._-]{1,32}");
  private static final String MEASUREMENTS = "measurements.json";

  private Publish() {}

  /**
   * Publishes the directory.
   *
   * @param dir the result directory, holding {@code measurements.json}
   * @param os a label for the operating system, shown in the job summary
   * @param env the environment ({@code GITHUB_ACTIONS}, {@code GITHUB_SHA}, {@code
   *     GITHUB_STEP_SUMMARY})
   * @param repo the repository whose HEAD is measured when not on a runner
   * @return 0 when sealed, 1 with one problem line on {@code err} otherwise
   */
  public static int run(
      Path dir, String os, Map<String, String> env, Path repo, PrintStream out, PrintStream err) {
    try {
      if (!OS_LABEL.matcher(os).matches()) {
        return problem(err, "BAD_OS_LABEL");
      }
      Path file = dir.resolve(MEASUREMENTS);
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        return problem(err, "no measurements.json");
      }
      if (Files.size(file) > MAX_MEASUREMENTS_BYTES) {
        return problem(err, "measurements.json is larger than 2 MB");
      }
      if (Files.exists(dir.resolve(ResultSeal.MANIFEST), LinkOption.NOFOLLOW_LINKS)) {
        return problem(err, "the directory is already sealed");
      }
      MeasurementSet measured;
      try {
        measured = MeasurementSet.parse(Files.readString(file, StandardCharsets.UTF_8));
      } catch (IllegalArgumentException e) {
        return problem(err, "BAD_MEASUREMENTS " + e.getMessage());
      }
      Criteria criteria = Criteria.load();
      if (criteria.forSpike(measured.spike()).isEmpty()) {
        return problem(err, "BAD_SPIKE " + measured.spike());
      }

      Optional<String> sha = commit(env, repo, err);
      if (sha.isEmpty()) {
        return 1;
      }
      Map<String, Object> source = new LinkedHashMap<>(measured.source());
      source.put("gitSha", sha.get());
      MeasurementSet set =
          new MeasurementSet(
              measured.spike(),
              measured.configDigest(),
              measured.scope(),
              measured.series(),
              measured.canary(),
              measured.runs(),
              source,
              measured.env(),
              measured.decisions(),
              measured.open());
      Map<String, Object> timeBox = new LinkedHashMap<>();
      timeBox.put("usedMinutes", 0L);
      timeBox.put("boxMinutes", criteria.box(set.spike()).toMinutes());
      SpikeResult result;
      try {
        result = ResultsEvaluator.evaluate(set, criteria, "RAW", timeBox);
      } catch (IllegalArgumentException e) {
        return problem(err, "BAD_MEASUREMENTS " + e.getMessage());
      }
      String report = ReportWriter.render(result, criteria);
      ResultSeal.seal(dir, result, report);

      String stepSummary = env.get("GITHUB_STEP_SUMMARY");
      if (stepSummary != null && !stepSummary.isBlank()) {
        appendResult(Path.of(stepSummary), result, os, report);
      }
      out.println(
          "publish: sealed "
              + dir.getFileName()
              + " "
              + result.spike()
              + " status="
              + result.status());
      return 0;
    } catch (FileAlreadyExistsException e) {
      return problem(err, "the directory is already sealed");
    } catch (IOException | RuntimeException e) {
      return problem(err, e.getClass().getSimpleName() + " " + e.getMessage());
    }
  }

  private static Optional<String> commit(Map<String, String> env, Path repo, PrintStream err) {
    if ("true".equals(env.get("GITHUB_ACTIONS"))) {
      String sha = env.get("GITHUB_SHA");
      if (sha == null || !Git.SHA.matcher(sha).matches()) {
        problem(err, "BAD_GIT_SHA GITHUB_SHA is not a 40-character commit id");
        return Optional.empty();
      }
      return Optional.of(sha);
    }
    Optional<String> head = Git.head(repo);
    if (head.isEmpty()) {
      problem(err, "NO_GIT_HEAD the repository has no commit to measure");
      return Optional.empty();
    }
    if (Git.dirty(repo)) {
      problem(err, "DIRTY_TREE the working tree differs from " + head.get());
      return Optional.empty();
    }
    return head;
  }

  /** The Result block and the summary.json, as Markdown for the job summary. */
  private static void appendResult(Path file, SpikeResult r, String os, String report)
      throws IOException {
    String json = r.toJson();
    StringBuilder text = new StringBuilder();
    text.append("\n## Result ").append(r.spike()).append(" (").append(os).append(")\n\n");
    // the report is already escaped for Markdown; its first lines are the nine header lines
    text.append(report).append('\n');
    text.append("### summary.json\n\n");
    String fence = "```";
    while (json.contains(fence)) {
      fence += "`";
    }
    text.append(fence).append("json\n").append(json).append('\n').append(fence).append('\n');
    Files.writeString(
        file,
        text.toString(),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  private static int problem(PrintStream err, String line) {
    err.println("publish: " + line);
    return 1;
  }
}
