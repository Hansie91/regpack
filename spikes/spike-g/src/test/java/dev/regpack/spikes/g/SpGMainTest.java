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
import dev.regpack.bench.report.Git;
import dev.regpack.bench.report.Publish;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
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

    // the directory publishes and imports; the lock exists, so --allow-no-lock stays off
    Optional<String> head = Git.head(repo());
    assertThat(head).as("the test runs inside the repository").isPresent();
    Map<String, String> runner = Map.of("GITHUB_ACTIONS", "true", "GITHUB_SHA", head.get());
    ByteArrayOutputStream published = new ByteArrayOutputStream();
    ByteArrayOutputStream problems = new ByteArrayOutputStream();
    int sealed = Publish.run(out, "local", runner, repo(), stream(published), stream(problems));
    assertThat(text(problems)).isEmpty();
    assertThat(sealed).isZero();
    assertThat(out.resolve("manifest.sha256")).exists();

    ByteArrayOutputStream imported = new ByteArrayOutputStream();
    ByteArrayOutputStream importProblems = new ByteArrayOutputStream();
    int exit =
        ImportCommand.run(
            new ImportCommand.Options("SP-G", List.of(out), Optional.empty(), false, true),
            repo(),
            Instant.parse("2026-10-11T00:00:00Z"),
            stream(imported),
            stream(importProblems));
    assertThat(text(importProblems)).isEmpty();
    assertThat(exit).isZero();
    assertThat(text(imported)).contains("import: OK (dry run) SP-G status=");
    if (open > 0) {
      assertThat(text(published)).contains("status=INCONCLUSIVE");
      assertThat(text(imported)).contains("status=INCONCLUSIVE");
    } else if (Register.unfixable(register) > 0) {
      assertThat(text(imported)).contains("status=FAIL");
    }
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
