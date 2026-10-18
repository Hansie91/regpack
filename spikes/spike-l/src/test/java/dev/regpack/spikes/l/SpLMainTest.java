package dev.regpack.spikes.l;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.bench.eval.Criteria;
import dev.regpack.bench.eval.Criterion;
import dev.regpack.spikes.l.SpLRunner.Part;
import dev.regpack.spikes.l.SpLRunner.Plan;
import dev.regpack.spikes.l.SpLRunner.Profile;
import dev.regpack.spikes.l.SpLRunner.Step;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The command line and the plan of SP-L. Nothing here starts PostgreSQL: a run needs the fixture of
 * the runner, so the property that carries its URL must make the run stop at once and loudly
 * (review focus RF11: a run that silently measures nothing).
 */
class SpLMainTest {
  private static String[] runArgs(Path out, String part) {
    return new String[] {
      "run",
      "--profile",
      "smoke",
      "--part",
      part,
      "--stretch",
      "false",
      "--deadline-minutes",
      "5",
      "--out",
      out.toString()
    };
  }

  private static PrintStream stream(ByteArrayOutputStream bytes) {
    return new PrintStream(bytes, true, StandardCharsets.UTF_8);
  }

  @Test
  void failsFastWithoutPgUrl(@TempDir Path tmp) throws Exception {
    String saved = System.getProperty("spike.pg.url");
    System.clearProperty("spike.pg.url");
    try {
      for (String part : List.of("ingest", "restore", "misc", "all")) {
        Path out = tmp.resolve("results-" + part);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = SpLMain.execute(runArgs(out, part), stream(stdout), stream(stderr));
        assertThat(exit).as(part).isEqualTo(2);
        assertThat(stderr.toString(StandardCharsets.UTF_8))
            .as(part)
            .contains("SpLMain: spike.pg.url is not set");
        assertThat(out).as("nothing is written without the fixture").doesNotExist();
      }
      // a blank property is the same as an absent one
      System.setProperty("spike.pg.url", "   ");
      ByteArrayOutputStream stderr = new ByteArrayOutputStream();
      int exit =
          SpLMain.execute(
              runArgs(tmp.resolve("results-blank"), "ingest"),
              stream(new ByteArrayOutputStream()),
              stream(stderr));
      assertThat(exit).isEqualTo(2);
      assertThat(stderr.toString(StandardCharsets.UTF_8))
          .contains("SpLMain: spike.pg.url is not set");
    } finally {
      if (saved == null) {
        System.clearProperty("spike.pg.url");
      } else {
        System.setProperty("spike.pg.url", saved);
      }
    }
  }

  @Test
  void aWrongCommandOrOptionIsAUsageError(@TempDir Path tmp) {
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    assertThat(SpLMain.execute(new String[0], stream(new ByteArrayOutputStream()), stream(stderr)))
        .isEqualTo(2);
    assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("usage");
    assertThat(
            SpLMain.execute(
                new String[] {"launch"},
                stream(new ByteArrayOutputStream()),
                stream(new ByteArrayOutputStream())))
        .isEqualTo(2);
    String saved = System.getProperty("spike.pg.url");
    System.setProperty("spike.pg.url", "jdbc:postgresql://127.0.0.1:1/postgres?user=postgres");
    try {
      ByteArrayOutputStream bad = new ByteArrayOutputStream();
      String[] args = runArgs(tmp.resolve("x"), "everything");
      assertThat(SpLMain.execute(args, stream(new ByteArrayOutputStream()), stream(bad)))
          .isEqualTo(2);
      assertThat(bad.toString(StandardCharsets.UTF_8)).contains("everything");
      assertThat(tmp.resolve("x")).doesNotExist();
    } finally {
      if (saved == null) {
        System.clearProperty("spike.pg.url");
      } else {
        System.setProperty("spike.pg.url", saved);
      }
    }
  }

  private static Set<String> ids(Plan plan, Part part) {
    return new TreeSet<>(plan.criteria(part));
  }

  @Test
  void smokePlanListsEverySeriesOfThePart() {
    Plan smoke = SpLRunner.plan(Profile.SMOKE, false);
    assertThat(ids(smoke, Part.INGEST)).containsExactly("L-1", "L-13", "L-2", "L-3", "L-4");
    assertThat(ids(smoke, Part.RESTORE)).containsExactly("L-6", "L-7", "L-8");
    assertThat(ids(smoke, Part.MISC))
        .containsExactly("L-10", "L-11", "L-12", "L-13", "L-14", "L-5", "L-9");
    // the stretch series is in the plan only when it is asked for
    Plan stretch = SpLRunner.plan(Profile.SMOKE, true);
    assertThat(ids(stretch, Part.MISC)).contains("L-15");
    assertThat(ids(smoke, Part.MISC)).doesNotContain("L-15");
  }

  @Test
  void everyRequiredCriterionHasASeriesInTheFullPlan() {
    Plan full = SpLRunner.plan(Profile.FULL, false);
    Criteria criteria = Criteria.load();
    for (Criterion c : criteria.forSpike("SP-L")) {
      if (c.required()) {
        assertThat(full.criteria()).as("required " + c.id()).contains(c.id());
      }
    }
    assertThat(SpLRunner.plan(Profile.FULL, true).criteria())
        .as("with the stretch flag the plan produces every criterion of the spike")
        .containsExactlyInAnyOrderElementsOf(
            criteria.forSpike("SP-L").stream().map(Criterion::id).toList());
  }

  @Test
  void theIngestPartRunsAThenTheClosedLoopThenTheMemoryLimitedRuns() {
    for (Profile profile : Profile.values()) {
      Plan plan = SpLRunner.plan(profile, false);
      assertThat(plan.steps(Part.INGEST))
          .extracting(Step::id)
          .containsExactly("baseline", "A1", "A2", "C1", "B1", "B2");
    }
    Plan full = SpLRunner.plan(Profile.FULL, false);
    assertThat(full.steps(Part.RESTORE).stream().filter(s -> s.id().startsWith("rebuild")))
        .as("the rebuild is measured at the two smallest sizes")
        .hasSize(2);
    assertThat(full.steps(Part.RESTORE).stream().filter(s -> s.id().startsWith("restore")))
        .hasSize(5);
  }

  @Test
  void everyPartEmitsAtLeastFiveCanarySamplesPerResource() {
    for (Profile profile : Profile.values()) {
      Plan plan = SpLRunner.plan(profile, false);
      for (Part part : Part.values()) {
        assertThat(plan.canarySamplesPerResource(part))
            .as(profile + " " + part)
            .isGreaterThanOrEqualTo(5);
      }
    }
    // a probe before and after each step that has one, never during a measured run
    Plan smoke = SpLRunner.plan(Profile.SMOKE, false);
    long probed = smoke.steps(Part.INGEST).stream().filter(Step::canary).count();
    assertThat(smoke.canarySamplesPerResource(Part.INGEST)).isEqualTo(2 * probed);
    assertThat(smoke.steps(Part.INGEST).stream().filter(s -> s.id().equals("baseline")).findFirst())
        .get()
        .extracting(Step::canary)
        .isEqualTo(false);
  }

  @Test
  void theConfigurationDigestIsOfTheWholePlan() {
    // the digest is of the whole plan and never of a part, so the three parts of a dispatch pool
    String smoke = SpLRunner.configDigest(SpLRunner.plan(Profile.SMOKE, false));
    assertThat(smoke)
        .hasSize(64)
        .isEqualTo(SpLRunner.configDigest(SpLRunner.plan(Profile.SMOKE, false)));
    assertThat(SpLRunner.configDigest(SpLRunner.plan(Profile.FULL, false))).isNotEqualTo(smoke);
    assertThat(SpLRunner.configDigest(SpLRunner.plan(Profile.SMOKE, true))).isNotEqualTo(smoke);
  }
}
