package dev.regpack.spikes.l;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Reads the text of the fixture scripts under {@code spikes/scripts/}: nothing here can run on the
 * owner machine, so the properties that protect the machine and the measurement (review focus RF11,
 * RF12, RF14) are checked on the text. The scripts run only on a GitHub-hosted runner.
 */
class PgScriptsTest {
  private static final String DIGEST =
      "sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336";
  private static final String PINNED = "postgres:18.6-trixie@" + DIGEST;
  private static final String GUARD =
      "if [ \"${GITHUB_ACTIONS:-}\" != \"true\" ] || [ \"${RUNNER_ENVIRONMENT:-}\" !="
          + " \"github-hosted\" ]; then echo \"refusing: runs only on a GitHub-hosted runner\""
          + " >&2; exit 1; fi";
  private static final Pattern MACHINE =
      Pattern.compile("(^|[\\s;&|(])(docker|sudo|rm|cp|mount|install)(\\s|$)");

  /** Runs inside the container as an init script of the image, where no runner variable exists. */
  private static final String IN_CONTAINER = "pg-init-replication.sh";

  private static Path scripts() {
    return Path.of(System.getProperty("regpack.repo.root")).resolve("spikes").resolve("scripts");
  }

  private static String read(String name) throws IOException {
    return Files.readString(scripts().resolve(name), StandardCharsets.UTF_8);
  }

  /** The names of every shell script under spikes/scripts. */
  private static List<String> allScripts() throws IOException {
    try (Stream<Path> files = Files.list(scripts())) {
      return files
          .map(p -> p.getFileName().toString())
          .filter(n -> n.endsWith(".sh"))
          .sorted()
          .toList();
    }
  }

  /** Every line that is neither blank nor a comment, in order, with its 0-based index kept. */
  private static List<String> code(String script) {
    List<String> lines = new ArrayList<>();
    for (String line : script.split("\n", -1)) {
      String t = line.strip();
      if (!t.isEmpty() && !t.startsWith("#")) {
        lines.add(t);
      }
    }
    return lines;
  }

  private static int indexOfFirst(List<String> lines, java.util.function.Predicate<String> p) {
    for (int i = 0; i < lines.size(); i++) {
      if (p.test(lines.get(i))) {
        return i;
      }
    }
    return -1;
  }

  @Test
  void theExpectedScriptsExist() throws IOException {
    assertThat(allScripts())
        .contains(
            "pg-up.sh",
            "pg-down.sh",
            "pg-restart.sh",
            "pg-standby.sh",
            "pg-init-replication.sh",
            "it-pg.sh",
            "pg-basebackup.sh",
            "pg-restore.sh",
            "pg-drop-caches.sh",
            "pg-baseline.sh",
            "runner-capture.sh");
  }

  @Test
  void scriptsAreAsciiOnlyAndUseLineFeeds() throws IOException {
    for (String name : allScripts()) {
      String text = read(name);
      assertThat(StandardCharsets.US_ASCII.newEncoder().canEncode(text))
          .as(name + " is ASCII only")
          .isTrue();
      assertThat(text).as(name + " has no carriage return").doesNotContain("\r");
    }
  }

  @Test
  void scriptsPinTheImageByDigest() throws IOException {
    String up = read("pg-up.sh");
    assertThat(code(up)).contains("IMAGE='" + PINNED + "'");
    for (String name : allScripts()) {
      String text = read(name);
      assertThat(text).as(name).doesNotContain(":latest");
      // every image reference in any script is the pinned one: no postgres:18, no
      // postgres:18-trixie
      Matcher m = Pattern.compile("postgres:[^\\s'\"]*").matcher(text);
      while (m.find()) {
        String ref = m.group();
        if (ref.startsWith("postgres:/")) {
          continue;
        }
        assertThat(ref).as(name + " image reference").isEqualTo(PINNED);
      }
      for (String line : code(text)) {
        if (line.contains("docker pull") || line.contains("docker run")) {
          assertThat(line).as(name + ": " + line).contains("\"$IMAGE\"");
        }
      }
    }
  }

  @Test
  void mountsAtVarLibPostgresqlAndChecksPgVersionFile() throws IOException {
    String up = read("pg-up.sh");
    for (String name : allScripts()) {
      assertThat(read(name)).as(name).doesNotContain("/var/lib/postgresql/data");
    }
    List<String> runs = code(up).stream().filter(l -> l.contains("docker run")).toList();
    assertThat(runs).isNotEmpty();
    Pattern mount = Pattern.compile("-v \\S+:/var/lib/postgresql(\\s|\")");
    List<String> serverRuns = runs.stream().filter(l -> l.contains("docker run -d")).toList();
    assertThat(serverRuns).hasSize(3);
    for (String run : serverRuns) {
      assertThat(mount.matcher(run).find()).as(run).isTrue();
    }
    List<String> backupRun = runs.stream().filter(l -> l.contains("pg_basebackup")).toList();
    assertThat(backupRun).hasSize(2);
    for (String run : backupRun) {
      assertThat(mount.matcher(run).find()).as(run).isTrue();
      assertThat(run).contains("-D /var/lib/postgresql/18/docker");
    }
    assertThat(up)
        .contains("SHOW data_directory")
        .contains("/var/lib/postgresql/18/docker")
        .contains("$ROOT/primary/18/docker/PG_VERSION");
    // the checks exit 1 on a mismatch, they do not only print
    int show = indexOfFirst(code(up), l -> l.contains("SHOW data_directory"));
    int pgVersion = indexOfFirst(code(up), l -> l.contains("PG_VERSION"));
    assertThat(show).isGreaterThanOrEqualTo(0);
    assertThat(pgVersion).isGreaterThanOrEqualTo(0);
    assertThat(String.join("\n", code(up)))
        .containsPattern("(?s)SHOW data_directory.*?exit 1")
        .containsPattern("(?s)PG_VERSION.*?exit 1");
  }

  @Test
  void everyMachineChangingScriptStartsWithTheRunnerGuard() throws IOException {
    for (String name : allScripts()) {
      if (name.equals("runner-capture.sh") || name.equals(IN_CONTAINER)) {
        continue;
      }
      List<String> lines = code(read(name));
      int guard = lines.indexOf(GUARD);
      int machine = indexOfFirst(lines, l -> MACHINE.matcher(l).find());
      assertThat(guard).as(name + " has the guard line").isGreaterThanOrEqualTo(0);
      assertThat(machine).as(name + " has a machine-changing line").isGreaterThanOrEqualTo(0);
      assertThat(guard).as(name + ": guard precedes " + lines.get(machine)).isLessThan(machine);
      // and nothing at all that is not a plain shell setting comes before the guard
      assertThat(lines.subList(0, guard)).allMatch(l -> l.startsWith("set -"));
    }
  }

  @Test
  void theInContainerInitScriptOnlyAppendsTheReplicationLine() throws IOException {
    List<String> lines = code(read(IN_CONTAINER));
    assertThat(indexOfFirst(lines, l -> MACHINE.matcher(l).find()))
        .as("no docker, sudo, rm, cp, mount or install in the in-container script")
        .isEqualTo(-1);
    assertThat(lines)
        .contains("echo \"host replication all 127.0.0.1/32 trust\" >> \"$PGDATA/pg_hba.conf\"");
    // outside the container PGDATA is unset and the append would go to /pg_hba.conf: the script
    // aborts before it
    int abort =
        indexOfFirst(
            lines,
            l ->
                l.equals(
                    ": \"${PGDATA:?PGDATA is not set: this script runs only inside the postgres"
                        + " container}\""));
    int append = indexOfFirst(lines, l -> l.contains(">> \"$PGDATA/pg_hba.conf\""));
    assertThat(lines).contains("set -euo pipefail");
    assertThat(abort).as("the PGDATA guard").isGreaterThanOrEqualTo(0);
    assertThat(abort).isLessThan(append);
  }

  @Test
  void theServersGetALargeSharedMemoryFileSystem() throws IOException {
    // docker's default /dev/shm of 64 MB is too small for the dynamic shared memory of parallel
    // queries over millions of rows
    List<String> serverRuns =
        code(read("pg-up.sh")).stream().filter(l -> l.contains("docker run -d")).toList();
    assertThat(serverRuns).hasSize(3);
    for (String run : serverRuns) {
      assertThat(run).as(run).contains("--shm-size=1g");
    }
  }

  @Test
  void theCanaryScratchDirectoryIsCreatedOnTheSpikeVolume() throws IOException {
    // L4's DISK probe writes under /mnt/spike-pg/scratch, on the volume of the clusters
    List<String> lines = code(read("pg-up.sh"));
    int guard = lines.indexOf(GUARD);
    int scratch = indexOfFirst(lines, l -> l.equals("sudo install -d -m 777 \"$ROOT/scratch\""));
    assertThat(scratch).isGreaterThan(guard);
    int firstRun = indexOfFirst(lines, l -> l.contains("docker run"));
    assertThat(scratch).isLessThan(firstRun);
  }

  @Test
  void machineChangesTouchOnlySpikePgAndTheResultsDirectory() throws IOException {
    for (String name : allScripts()) {
      if (name.equals("runner-capture.sh") || name.equals(IN_CONTAINER)) {
        continue;
      }
      for (String line : code(read(name))) {
        if (line.contains("rm -rf")) {
          assertThat(line).as(name + ": " + line).contains("\"$ROOT");
          assertThat(line).as(name + ": " + line).doesNotContain("rm -rf /");
        }
        if (line.contains("sudo install") || line.contains("sudo chown")) {
          assertThat(line).as(name + ": " + line).contains("\"$ROOT");
        }
      }
      String text = read(name);
      if (text.contains("ROOT=")) {
        assertThat(code(text)).contains("readonly ROOT=/mnt/spike-pg");
      }
    }
  }

  @Test
  void archiveModeOnlyInTheSpLModes() throws IOException {
    String up = read("pg-up.sh");
    List<String> lines = code(up);
    List<Integer> at = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains("archive_mode=on")) {
        at.add(i);
      }
    }
    assertThat(at).hasSize(1);
    // the archive arguments sit under the case branch of the three sp-l modes, never under verify
    int branch = at.get(0) - 1;
    while (branch >= 0 && !lines.get(branch).endsWith(")")) {
      branch--;
    }
    assertThat(branch).isGreaterThanOrEqualTo(0);
    assertThat(lines.get(branch)).isEqualTo("sp-l-ingest|sp-l-restore|sp-l-misc)");
    // the branch ends before anything else: ";;" follows the arguments directly
    assertThat(lines.get(at.get(0) + 1)).isEqualTo(";;");
    // the archive arguments are passed to the primary only
    List<String> runs = lines.stream().filter(l -> l.contains("docker run -d")).toList();
    assertThat(runs.stream().filter(l -> l.contains("ARCHIVE_ARGS")).toList()).hasSize(1);
    assertThat(runs.stream().filter(l -> l.contains("--name sp-primary")).toList())
        .allMatch(l -> l.contains("ARCHIVE_ARGS"));
    assertThat(up).contains("archive_command=test ! -f /archive/%f && cp %p /archive/%f");
  }

  @Test
  void smallDiskReducesWalSize() throws IOException {
    String up = read("pg-up.sh");
    assertThat(up)
        .contains("MAX_WAL_SIZE=8GB")
        .contains("MAX_WAL_SIZE=2GB")
        .contains("-c \"max_wal_size=$MAX_WAL_SIZE\"")
        .contains("df -B1 --output=avail /mnt/spike-pg")
        .contains("-lt $((40 * 1024 * 1024 * 1024))")
        .contains("-c \"shared_buffers=$SHARED_BUFFERS\"");
    // verify mode takes the small buffer; the option overrides either
    assertThat(String.join("\n", code(up)))
        .contains("verify) DEFAULT_SHARED_BUFFERS=256MB ;;")
        .contains("sp-l-ingest|sp-l-restore|sp-l-misc) DEFAULT_SHARED_BUFFERS=2GB ;;")
        .contains("--shared-buffers=*)")
        .contains("--memory=*)")
        .contains("\"${MEMORY_ARGS[@]}\" \"$IMAGE\"");
  }

  @Test
  void everyDockerRunIsFollowedByAReadinessPoll() throws IOException {
    String up = read("pg-up.sh");
    List<String> lines = code(up);
    // the poll helpers use the TCP check against the server, once a second, for at most 60 seconds
    assertThat(up).contains("pg_isready -h 127.0.0.1 -p").contains("seq 1 60").contains("sleep 1");

    int firstBackup =
        indexOfFirst(lines, l -> l.contains("docker run --rm") && l.contains("pg_basebackup"));
    int alter = indexOfFirst(lines, l -> l.contains("docker exec") && l.contains("ALTER SYSTEM"));
    int check = indexOfFirst(lines, l -> l.contains("pg_stat_replication"));
    int firstPoll = indexOfFirst(lines, l -> l.equals("wait_tcp 5432"));
    assertThat(firstPoll).isGreaterThanOrEqualTo(0);
    assertThat(firstBackup).isGreaterThan(firstPoll);
    assertThat(alter).isGreaterThan(firstPoll);
    // the first use of pg_stat_replication is the poll that follows the poll of the servers
    assertThat(check).isGreaterThan(firstPoll);
    assertThat(lines.get(check)).startsWith("wait_query");

    int runs = 0;
    for (int i = 0; i < lines.size(); i++) {
      if (!lines.get(i).contains("docker run -d")) {
        continue;
      }
      runs++;
      // the next line that starts a docker command, a backup or a query must be preceded by the
      // poll
      int j = i + 1;
      while (j < lines.size()
          && !lines.get(j).startsWith("wait_tcp ")
          && !lines.get(j).contains("docker ")
          && !lines.get(j).contains("pg_basebackup")
          && !lines.get(j).startsWith("wait_query")) {
        j++;
      }
      assertThat(j).as("a poll after: " + lines.get(i)).isLessThan(lines.size());
      assertThat(lines.get(j)).as("poll follows: " + lines.get(i)).startsWith("wait_tcp ");
    }
    assertThat(runs).isEqualTo(3);
    // each server port is polled, and the replication and recovery state before the hard checks
    assertThat(lines).contains("wait_tcp 5432", "wait_tcp 5433", "wait_tcp 5434");
    int streaming = indexOfFirst(lines, l -> l.startsWith("wait_query") && l.contains("streaming"));
    int dataDirectory = indexOfFirst(lines, l -> l.contains("SHOW data_directory"));
    assertThat(streaming).isGreaterThan(firstPoll);
    assertThat(dataDirectory).isGreaterThan(streaming);
    assertThat(String.join("\n", lines))
        .contains("pg_is_in_recovery()")
        .contains("pg_stat_replication");
  }

  @Test
  void theStandbyBecomesSynchronousOnlyAfterItStreams() throws IOException {
    List<String> lines = code(read("pg-up.sh"));
    int alter = indexOfFirst(lines, l -> l.contains("ALTER SYSTEM SET synchronous_standby_names"));
    int streaming = indexOfFirst(lines, l -> l.startsWith("wait_query") && l.contains("streaming"));
    int quorum = indexOfFirst(lines, l -> l.startsWith("wait_query") && l.contains("quorum"));
    assertThat(alter).isGreaterThan(streaming);
    assertThat(quorum).isGreaterThan(alter);
    // never set at start-up, where it would block the commits of the entrypoint's temporary server
    assertThat(
            lines.stream()
                .filter(l -> l.contains("synchronous_standby_names"))
                .filter(l -> l.contains("docker run"))
                .toList())
        .isEmpty();
    assertThat(String.join("\n", lines)).contains("ANY 1 (standby1)");
  }

  @Test
  void theStandbyCommandsDropTheSynchronousSettingBeforeRemovingTheStandby() throws IOException {
    List<String> lines = code(read("pg-standby.sh"));
    int reset = indexOfFirst(lines, l -> l.contains("RESET synchronous_standby_names"));
    int remove = indexOfFirst(lines, l -> l.contains("docker rm"));
    assertThat(reset).isGreaterThanOrEqualTo(0);
    assertThat(remove).isGreaterThan(reset);
    assertThat(String.join("\n", lines)).contains("stop)").contains("start)").contains("drop)");
  }

  @Test
  void itScriptMapsEveryPartToItsIntegrationTests() throws IOException {
    String it = read("it-pg.sh");
    assertThat(it)
        .contains("PgFixtureIT,BurstRunIT,CommitPathIT")
        .contains("ConstraintChecksIT,RowSizeIT,PartitionIT")
        .contains("RestoreIT,RebuildIT")
        .contains("-Dspike.pg.url='jdbc:postgresql://127.0.0.1:5432/postgres?user=postgres'")
        .contains("-Dtest=NoUnitTestsInThisRun")
        .contains("-Dfailsafe.failIfNoSpecifiedTests=false")
        .contains("-f spikes/pom.xml -pl spike-l verify")
        // the same pair as it.sh: surefire must also accept that its only selector matches nothing
        .contains("-Dsurefire.failIfNoSpecifiedTests=false")
        .contains("results/sp-l-$PART/it/");
  }
}
