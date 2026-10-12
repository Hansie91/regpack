package dev.regpack.bench.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.bench.eval.Criteria;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchTest {
  private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");

  @TempDir Path repo;
  @TempDir Path work;

  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

  @BeforeEach
  void gitRepository() throws Exception {
    git("init", "-q");
    git("commit", "-q", "--allow-empty", "-m", "one");
  }

  private String git(String... args) throws Exception {
    List<String> command =
        new ArrayList<>(
            List.of(
                "git",
                "-C",
                repo.toString(),
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.invalid",
                "-c",
                "commit.gpgsign=false"));
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0) {
      throw new IllegalStateException("git " + List.of(args) + " failed: " + output);
    }
    return output;
  }

  private int bench(Map<String, String> env, String... args) {
    outBytes.reset();
    errBytes.reset();
    return Bench.run(
        args,
        new PrintStream(outBytes, true, StandardCharsets.UTF_8),
        new PrintStream(errBytes, true, StandardCharsets.UTF_8),
        env,
        repo,
        NOW);
  }

  private int bench(String... args) {
    return bench(Map.of(), args);
  }

  private String out() {
    return outBytes.toString(StandardCharsets.UTF_8);
  }

  private String err() {
    return errBytes.toString(StandardCharsets.UTF_8);
  }

  @Test
  void criteriaWriteWritesTheMarkdownOfTheRegistry() throws Exception {
    Path file = repo.resolve("docs/criteria.md");
    assertThat(bench("criteria", "--write", file.toString())).as(err()).isEqualTo(0);
    assertThat(Files.readString(file, StandardCharsets.UTF_8))
        .isEqualTo(Criteria.load().toMarkdown());
  }

  @Test
  void criteriaLockWritesTheDigestAndNeverOverwrites() throws Exception {
    Path lock = repo.resolve("docs/criteria.lock");
    assertThat(
            bench("criteria", "--lock", lock.toString(), "--by", "A. Owner", "--on", "2026-10-11"))
        .as(err())
        .isEqualTo(0);
    assertThat(Files.readString(lock, StandardCharsets.UTF_8))
        .isEqualTo(
            "digest: "
                + Criteria.load().digest().hex()
                + "\nconfirmed_by: A. Owner\nconfirmed_on: 2026-10-11\n");

    // a lock is never rewritten
    assertThat(bench("criteria", "--lock", lock.toString(), "--by", "B", "--on", "2026-10-12"))
        .isEqualTo(1);
    assertThat(err()).contains("LOCK_EXISTS");

    // a date that is not a date, a name with a line break
    Path other = repo.resolve("docs/other.lock");
    assertThat(bench("criteria", "--lock", other.toString(), "--by", "A", "--on", "tomorrow"))
        .isEqualTo(2);
    assertThat(bench("criteria", "--lock", other.toString(), "--by", "A\nB", "--on", "2026-10-11"))
        .isEqualTo(2);
    assertThat(other).doesNotExist();
  }

  @Test
  void selftestPublishAndImportDryRunExerciseTheWholePipeline() throws Exception {
    Path dir = work.resolve("selftest");
    Map<String, String> env = Map.of("GITHUB_RUN_ID", "555", "GITHUB_RUN_ATTEMPT", "2");
    assertThat(bench(env, "selftest", "--out", dir.toString())).as(err()).isEqualTo(0);
    assertThat(dir.resolve("measurements.json")).exists();

    assertThat(bench(env, "publish", "--dir", dir.toString(), "--os", "linux"))
        .as(err())
        .isEqualTo(0);
    assertThat(dir.resolve("manifest.sha256")).exists();

    assertThat(
            bench(
                env,
                "import",
                "--spike",
                "SELFTEST",
                "--dir",
                dir.toString(),
                "--allow-no-lock",
                "--dry-run"))
        .as(err())
        .isEqualTo(0);
    assertThat(out()).contains("import: OK (dry run) SELFTEST status=PASS evidence=RAW");
    assertThat(repo.resolve("docs")).doesNotExist();
  }

  @Test
  void selftestIsDeterministicForOneEnvironment() throws Exception {
    Map<String, String> env = Map.of("GITHUB_RUN_ID", "555", "GITHUB_RUN_ATTEMPT", "1");
    Path a = work.resolve("a");
    Path b = work.resolve("b");
    assertThat(bench(env, "selftest", "--out", a.toString())).isEqualTo(0);
    assertThat(bench(env, "selftest", "--out", b.toString())).isEqualTo(0);
    assertThat(Files.readString(a.resolve("measurements.json")))
        .isEqualTo(Files.readString(b.resolve("measurements.json")));
  }

  @Test
  void importOfAMissingDirectoryPrintsOneProblemAndExitsOne() {
    assertThat(
            bench(
                "import", "--spike", "SP-R", "--dir", repo.resolve("spikes/inbox/none").toString()))
        .isEqualTo(1);
    assertThat(err().strip().split("\n")).hasSize(1);
  }

  @Test
  void timeboxLedgerFollowsTheEvents() throws Exception {
    Path file = repo.resolve("docs/spikes/timebox/HARNESS.tsv");
    assertThat(bench("timebox", "start", "HARNESS", "--at", "2026-10-10T16:57:00Z"))
        .as(err())
        .isEqualTo(0);
    assertThat(Files.readString(file, StandardCharsets.UTF_8))
        .isEqualTo("2026-10-10T16:57:00Z\tSTART\t\n");

    // a second start is refused and changes nothing
    assertThat(bench("timebox", "start", "HARNESS")).isEqualTo(1);
    assertThat(Files.readString(file, StandardCharsets.UTF_8))
        .isEqualTo("2026-10-10T16:57:00Z\tSTART\t\n");

    assertThat(
            bench(
                "timebox",
                "pause",
                "HARNESS",
                "--at",
                "2026-10-10T18:57:00Z",
                "--note",
                "waiting for the criteria lock"))
        .isEqualTo(0);
    assertThat(bench("timebox", "status", "HARNESS")).as(err()).isEqualTo(0);
    assertThat(out()).contains("HARNESS: used 120 of 480 minutes").contains("paused");

    assertThat(bench("timebox", "resume", "HARNESS", "--at", "2026-10-11T09:00:00Z")).isEqualTo(0);
    // the clock is 10:00 on the 11th: 120 + 60 minutes
    assertThat(bench("timebox", "status", "HARNESS")).isEqualTo(0);
    assertThat(out()).contains("used 180 of 480 minutes").contains("running");
    assertThat(bench("timebox", "stop", "HARNESS")).isEqualTo(0);
    assertThat(bench("timebox", "status", "HARNESS")).isEqualTo(0);
    assertThat(out()).contains("used 180 of 480 minutes").contains("stopped");
  }

  @Test
  void timeboxRefusesAnUnknownIdAndAnUnparsableInstant() {
    assertThat(bench("timebox", "start", "../x")).isEqualTo(2);
    assertThat(bench("timebox", "start", "SP-Q")).isEqualTo(2);
    assertThat(bench("timebox", "start", "HARNESS", "--at", "yesterday")).isEqualTo(2);
    assertThat(repo.resolve("docs")).doesNotExist();
  }

  @Test
  void timeboxStatusSaysBoxReachedAtExactlyTheBox() throws Exception {
    // eight hours before the clock: used 480 of 480 minutes
    assertThat(bench("timebox", "start", "HARNESS", "--at", "2026-10-11T02:00:00Z")).isEqualTo(0);
    assertThat(bench("timebox", "status", "HARNESS")).isEqualTo(0);
    assertThat(out()).contains("used 480 of 480 minutes").contains("box reached: yes");

    assertThat(bench("timebox", "pause", "HARNESS", "--at", "2026-10-11T09:57:00Z")).isEqualTo(0);
    assertThat(bench("timebox", "status", "HARNESS")).isEqualTo(0);
    assertThat(out()).contains("used 477 of 480 minutes").contains("box reached: no");
  }

  @Test
  void timeboxRefusesAnInstantAfterTheClockAndNotesOneWellBeforeIt() throws Exception {
    Path harness = repo.resolve("docs/spikes/timebox/HARNESS.tsv");
    assertThat(bench("timebox", "start", "HARNESS", "--at", "2026-10-11T10:00:01Z")).isEqualTo(1);
    assertThat(err()).contains("later than the clock");
    assertThat(harness).doesNotExist();

    // within a minute of the clock: no note
    assertThat(bench("timebox", "start", "HARNESS", "--at", "2026-10-11T09:59:30Z")).isEqualTo(0);
    assertThat(err()).isEmpty();

    Path ledger = repo.resolve("docs/spikes/timebox/SP-R.tsv");
    assertThat(bench("timebox", "start", "SP-R", "--at", "2026-10-11T07:30:00Z")).isEqualTo(0);
    assertThat(err()).contains("note").contains("150 minutes before the clock");
    assertThat(Files.readString(ledger, StandardCharsets.UTF_8))
        .isEqualTo("2026-10-11T07:30:00Z\tSTART\t\n");

    // a pause back-dated to hide active time is allowed by --at, but it is said out loud
    assertThat(bench("timebox", "pause", "SP-R", "--at", "2026-10-11T07:45:00Z")).isEqualTo(0);
    assertThat(err()).contains("135 minutes before the clock");
  }

  @Test
  void timeboxRefusesAnInstantBeforeTheLastEventAndWritesNothing() throws Exception {
    Path ledger = repo.resolve("docs/spikes/timebox/SP-R.tsv");
    assertThat(bench("timebox", "start", "SP-R", "--at", "2026-10-11T08:00:00Z")).isEqualTo(0);
    assertThat(bench("timebox", "pause", "SP-R", "--at", "2026-10-11T09:00:00Z")).isEqualTo(0);
    String before = Files.readString(ledger, StandardCharsets.UTF_8);

    // every event: an instant before the last one is one problem line, exit 1, no note, no write
    for (String action : List.of("start", "pause", "resume", "stop")) {
      assertThat(bench("timebox", action, "SP-R", "--at", "2026-10-11T08:30:00Z"))
          .as(action)
          .isEqualTo(1);
      assertThat(err().strip().split("\n")).as(action).hasSize(1);
      assertThat(err())
          .contains("bench: timebox SP-R: " + action + " at 2026-10-11T08:30:00Z")
          .contains("earlier than the last event")
          .contains("2026-10-11T09:00:00Z")
          .doesNotContain("note");
      assertThat(Files.readString(ledger, StandardCharsets.UTF_8)).isEqualTo(before);
    }

    // the very instant of the last event is not before it
    assertThat(bench("timebox", "resume", "SP-R", "--at", "2026-10-11T09:00:00Z")).isEqualTo(0);
  }

  @Test
  void unexpectedWordsAreUsageErrors() {
    // a typo that leaves --dir out must not be read as a different command
    assertThat(bench("import", "--spike", "SP-R", "somedir")).isEqualTo(2);
    assertThat(err()).contains("unexpected argument somedir");
    assertThat(bench("import", "--spike", "SP-R", "--dir", "x", "extra")).isEqualTo(2);
    assertThat(bench("publish", "somedir", "--os", "x")).isEqualTo(2);
    assertThat(err()).contains("unexpected argument somedir");
    assertThat(bench("selftest", "--out", "x", "y")).isEqualTo(2);
    assertThat(bench("criteria", "--write", "x", "y")).isEqualTo(2);
    assertThat(bench("verify-committed", "now")).isEqualTo(2);
    assertThat(bench("timebox", "status", "HARNESS", "extra")).isEqualTo(2);
  }

  @Test
  void usageErrorsExitTwo() {
    assertThat(bench()).isEqualTo(2);
    assertThat(bench("frobnicate")).isEqualTo(2);
    assertThat(bench("criteria")).isEqualTo(2);
    assertThat(bench("import", "--spike", "SP-R", "--bogus", "x")).isEqualTo(2);
    assertThat(bench("import", "--dir", "x")).isEqualTo(2);
    assertThat(err()).contains("bench:");
  }

  @Test
  void verifyCommittedWithNothingCommittedIsClean() {
    assertThat(bench("verify-committed")).as(err()).isEqualTo(0);
    assertThat(out()).contains("verify-committed: OK 0 report");
  }
}
