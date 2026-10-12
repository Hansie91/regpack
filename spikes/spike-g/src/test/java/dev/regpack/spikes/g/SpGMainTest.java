package dev.regpack.spikes.g;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.bench.eval.Criteria;
import dev.regpack.bench.eval.CriterionResult;
import dev.regpack.bench.eval.MeasurementSet;
import dev.regpack.bench.eval.MeasurementSet.CountValue;
import dev.regpack.bench.eval.ResultsEvaluator;
import dev.regpack.bench.eval.SpikeResult;
import dev.regpack.bench.eval.Verdict;
import dev.regpack.bench.imp.ImportCommand;
import dev.regpack.bench.report.Publish;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpGMainTest {
  private static final String HEADER =
      "| id | hypothesis | location | symptom | emir_need | proposed_fix | impact | severity |"
          + " status | owner_note |\n"
          + "|---|---|---|---|---|---|---|---|---|---|\n";
  private static final String[] PACK_FILES = {
    "derive/action-type.yaml", "validate/sequencing.yaml"
  };

  private static Path repo() {
    return Path.of(System.getProperty("regpack.repo.root"));
  }

  private static String text(ByteArrayOutputStream bytes) {
    return bytes.toString(StandardCharsets.UTF_8);
  }

  private static PrintStream stream(ByteArrayOutputStream bytes) {
    return new PrintStream(bytes, true, StandardCharsets.UTF_8);
  }

  private static String row(int n, String status, String fix) {
    return String.format(
        "| BRK-%02d | new | loc | symptom | need | %s | impact | P12_ONLY | %s | note |%n",
        n, fix, status);
  }

  /** A small repository: the two pack files of the real exercise and a register of our own. */
  private static Path miniRepo(Path tmp, String register) throws IOException {
    Path sp = tmp.resolve("docs/spikes/sp-g");
    for (String file : PACK_FILES) {
      Path target = sp.resolve("emir-pack").resolve(file);
      Files.createDirectories(target.getParent());
      Files.copy(repo().resolve("docs/spikes/sp-g/emir-pack").resolve(file), target);
    }
    Files.writeString(sp.resolve("register.md"), register, StandardCharsets.UTF_8);
    return tmp;
  }

  /**
   * A temporary git repository with one commit that holds what the run, the publish and the import
   * read: the lock of the criteria and the SP-G documents (pack files and register), copied from
   * the real repository. It has no {@code docs/spikes/raw}, no summary and no time ledger, and its
   * HEAD is its own commit, so what the import finds does not depend on the evidence committed in
   * the real repository. The criteria registry is a resource of the bench module, not a file.
   */
  private static Path isolatedRepo(Path dir) throws Exception {
    Files.createDirectories(dir);
    git(dir, "init", "-q");
    git(dir, "config", "user.name", "Test");
    git(dir, "config", "user.email", "test@example.invalid");
    git(dir, "config", "commit.gpgsign", "false");
    Path lock = Path.of("docs/spikes/criteria.lock");
    Files.createDirectories(dir.resolve(lock).getParent());
    Files.copy(repo().resolve(lock), dir.resolve(lock));
    Path documents = Path.of("docs/spikes/sp-g");
    try (Stream<Path> files = Files.walk(repo().resolve(documents))) {
      for (Path source : files.filter(Files::isRegularFile).toList()) {
        Path target = dir.resolve(documents).resolve(repo().resolve(documents).relativize(source));
        Files.createDirectories(target.getParent());
        Files.copy(source, target);
      }
    }
    git(dir, "add", "docs");
    git(dir, "commit", "-q", "-m", "the documents of SP-G");
    return dir;
  }

  private static String git(Path dir, String... args) throws Exception {
    List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
    command.addAll(List.of(args));
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    // a repository chosen by the environment of a hook must not replace the one we were given
    builder.environment().keySet().removeAll(List.of("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE"));
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0) {
      throw new IllegalStateException("git " + List.of(args) + " failed: " + output);
    }
    return output;
  }

  private static MeasurementSet run(Path repo, Path out) throws IOException {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    int exit = SpGMain.run(out, repo, Map.of(), stream(stdout));
    assertThat(exit).as(text(stdout)).isZero();
    return MeasurementSet.parse(Files.readString(out.resolve("measurements.json")));
  }

  private static SpikeResult evaluate(MeasurementSet set) {
    Criteria criteria = Criteria.load();
    return ResultsEvaluator.evaluate(
        set,
        criteria,
        "RAW",
        Map.of("usedMinutes", 0L, "boxMinutes", criteria.box("SP-G").toMinutes()));
  }

  private static Verdict verdict(SpikeResult result, String id) {
    return result.criteria().stream()
        .filter(c -> c.id().equals(id))
        .map(CriterionResult::verdict)
        .findFirst()
        .orElseThrow();
  }

  private static CountValue count(MeasurementSet set, String id) {
    return set.series().stream()
        .filter(s -> s.criterionId().equals(id))
        .map(CountValue.class::cast)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void runWritesCountSeriesForG1ToG4(@TempDir Path tmp) throws Exception {
    Path out = tmp.resolve("results-local");
    MeasurementSet set = run(repo(), out);

    assertThat(set.spike()).isEqualTo("SP-G");
    assertThat(set.scope())
        .as("documents and counts: no hardware sensitivity")
        .isEqualTo("DOCUMENT");
    assertThat(set.series().stream().map(s -> s.criterionId()))
        .containsExactly("G-1", "G-2", "G-3", "G-4");
    assertThat(set.series()).allSatisfy(s -> assertThat(s).isInstanceOf(CountValue.class));

    List<Register.Row> register =
        Register.parse(
            Files.readString(
                repo().resolve("docs/spikes/sp-g/register.md"), StandardCharsets.UTF_8));
    assertThat(count(set, "G-1")).isEqualTo(new CountValue("G-1", 6, 1));
    long open = Register.open(register);
    assertThat(count(set, "G-2")).isEqualTo(new CountValue("G-2", open, open == 0 ? 1 : 0));
    assertThat(count(set, "G-3")).isEqualTo(new CountValue("G-3", Register.unfixable(register), 1));
    assertThat(count(set, "G-4"))
        .as("failures of the coexistence check on the P0 in-memory ledger")
        .isEqualTo(new CountValue("G-4", 0, 1));
  }

  /**
   * The directory that {@code run} writes publishes, seals and imports with {@code --allow-no-lock}
   * off because a lock exists. It does so in a temporary git repository of its own, never in the
   * real one: the importer looks under {@code docs/spikes/raw/<ID>} of the repository it is given,
   * and the raw evidence the real repository has committed (another configuration, or this very
   * configuration already passing) is not what this test is about.
   */
  @Test
  void theWrittenDirectoryPublishesSealsAndImportsInARepositoryOfItsOwn(@TempDir Path tmp)
      throws Exception {
    Path isolated = isolatedRepo(tmp.resolve("repository"));
    assertThat(isolated.resolve("docs/spikes/raw"))
        .as("no committed raw evidence in the repository under test")
        .doesNotExist();
    assertThat(isolated.resolve("docs/spikes/SP-G.summary.json"))
        .as("no committed summary in the repository under test")
        .doesNotExist();
    Path out = tmp.resolve("results-local");
    MeasurementSet set = run(isolated, out);
    String head = git(isolated, "rev-parse", "HEAD").strip();
    assertThat(set.runs()).extracting(r -> r.gitSha()).containsExactly(head);

    // the status the criteria give for these measurements, whatever the register says
    Verdict expected = evaluate(set).status();
    List<Register.Row> register =
        Register.parse(
            Files.readString(
                isolated.resolve("docs/spikes/sp-g/register.md"), StandardCharsets.UTF_8));
    if (Register.open(register) > 0) {
      assertThat(expected).isEqualTo(Verdict.INCONCLUSIVE);
    } else if (Register.unfixable(register) > 0) {
      assertThat(expected).isEqualTo(Verdict.FAIL);
    }

    // the directory publishes; the lock exists, so --allow-no-lock stays off
    Map<String, String> runner = Map.of("GITHUB_ACTIONS", "true", "GITHUB_SHA", head);
    ByteArrayOutputStream published = new ByteArrayOutputStream();
    ByteArrayOutputStream problems = new ByteArrayOutputStream();
    int sealed = Publish.run(out, "local", runner, isolated, stream(published), stream(problems));
    assertThat(text(problems)).isEmpty();
    assertThat(sealed).isZero();
    assertThat(out.resolve("manifest.sha256")).exists();
    assertThat(text(published)).contains("status=" + expected);

    ByteArrayOutputStream imported = new ByteArrayOutputStream();
    ByteArrayOutputStream importProblems = new ByteArrayOutputStream();
    int exit =
        ImportCommand.run(
            new ImportCommand.Options("SP-G", List.of(out), Optional.empty(), false, true),
            isolated,
            Instant.parse("2026-10-11T00:00:00Z"),
            stream(imported),
            stream(importProblems));
    assertThat(text(importProblems)).isEmpty();
    assertThat(exit).isZero();
    assertThat(text(imported))
        .contains("import: OK (dry run) SP-G status=" + expected + " evidence=RAW");
    assertThat(isolated.resolve("docs/spikes/raw")).as("a dry run writes nothing").doesNotExist();
  }

  @Test
  void openRowsGiveInconclusiveNotFail(@TempDir Path tmp) throws Exception {
    String register =
        HEADER
            + row(1, "OPEN", "-")
            + row(2, "PROPOSED", "section 10: new wording")
            + row(3, "ACCEPTED", "-");
    MeasurementSet set = run(miniRepo(tmp.resolve("open"), register), tmp.resolve("out-open"));
    assertThat(count(set, "G-2"))
        .as("observed = open rows; no valid run while any row is open")
        .isEqualTo(new CountValue("G-2", 2, 0));
    SpikeResult result = evaluate(set);
    assertThat(verdict(result, "G-2")).isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(result.status()).isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(new TreeSet<>(set.open())).anyMatch(o -> o.contains("G-2"));
  }

  @Test
  void dispositionedRowsPassAndAnUnfixableRowFails(@TempDir Path tmp) throws Exception {
    String accepted = HEADER + row(1, "ACCEPTED", "-") + row(2, "REJECTED_NOT_NEEDED", "-");
    MeasurementSet passing = run(miniRepo(tmp.resolve("a"), accepted), tmp.resolve("out-a"));
    assertThat(count(passing, "G-2")).isEqualTo(new CountValue("G-2", 0, 1));
    SpikeResult pass = evaluate(passing);
    assertThat(verdict(pass, "G-2")).isEqualTo(Verdict.PASS);
    assertThat(verdict(pass, "G-3")).isEqualTo(Verdict.PASS);
    assertThat(pass.status()).isEqualTo(Verdict.PASS);

    String unfixable = HEADER + row(1, "ACCEPTED", "-") + row(2, "UNFIXABLE", "-");
    MeasurementSet failing = run(miniRepo(tmp.resolve("u"), unfixable), tmp.resolve("out-u"));
    assertThat(count(failing, "G-3")).isEqualTo(new CountValue("G-3", 1, 1));
    SpikeResult fail = evaluate(failing);
    assertThat(verdict(fail, "G-3")).isEqualTo(Verdict.FAIL);
    assertThat(fail.status()).isEqualTo(Verdict.FAIL);
  }

  @Test
  void aMissingRegisterIsAFailedRunNotAZeroCount(@TempDir Path tmp) throws Exception {
    Path repo = miniRepo(tmp.resolve("r"), HEADER + row(1, "ACCEPTED", "-"));
    Files.delete(repo.resolve("docs/spikes/sp-g/register.md"));
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    assertThat(SpGMain.run(tmp.resolve("out"), repo, Map.of(), stream(stdout))).isEqualTo(1);
    assertThat(tmp.resolve("out/measurements.json")).doesNotExist();
  }

  @Test
  void commandLineErrorsAreUsageErrors() {
    assertThat(SpGMain.execute(new String[] {})).isEqualTo(2);
    assertThat(SpGMain.execute(new String[] {"walk"})).isEqualTo(2);
    assertThat(SpGMain.execute(new String[] {"run"})).as("--out is required").isEqualTo(2);
    assertThat(SpGMain.execute(new String[] {"run", "--out"})).isEqualTo(2);
    assertThat(SpGMain.execute(new String[] {"run", "--bogus", "x"})).isEqualTo(2);
  }
}
