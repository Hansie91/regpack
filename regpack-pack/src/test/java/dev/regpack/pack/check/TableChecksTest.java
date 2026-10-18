package dev.regpack.pack.check;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.CitationRole;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.pack.model.CitationRef;
import dev.regpack.pack.model.DecisionTable;
import dev.regpack.pack.model.TableRow;
import dev.regpack.pack.model.Validity;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Decision tables: the static checks of spec 6.1 and 6.2 (companion section 5.3; plan task T7). */
class TableChecksTest {
  private static final List<CitationRef> CITE =
      List.of(new CitationRef("sample-law", "sample/1", CitationRole.BASIS));
  private static final Optional<Validity> VALID =
      Optional.of(new Validity(LocalDate.of(2018, 1, 3), Optional.empty()));

  private static TableRow row(
      String id,
      int priority,
      String when,
      Outcome outcome,
      List<CitationRef> cite,
      Optional<Validity> valid) {
    return new TableRow(
        id,
        "N01",
        priority,
        when,
        outcome,
        new ReasonCode("SAMPLE.N01.X"),
        Optional.empty(),
        cite,
        "RAT-0001",
        valid,
        Map.of(),
        outcome == Outcome.REPORT ? List.of(Leg.MARKET) : List.of());
  }

  private static TableRow scope(String id, int priority) {
    return row(id, priority, "event.kind == 'NEW'", Outcome.REPORT, CITE, VALID);
  }

  private static TableRow catchAll(String id, int priority) {
    return row(id, priority, "true", Outcome.UNDETERMINED, CITE, Optional.empty());
  }

  private static DecisionTable table(String catchAllId, TableRow... rows) {
    return new DecisionTable(
        "scope/scope.yaml", "sample.s0.scope", List.of("event.kind"), catchAllId, List.of(rows));
  }

  private static List<String> codes(DecisionTable table) {
    return TableChecks.check(table).stream().map(PackProblem::code).toList();
  }

  // ---------------------------------------------------------------------------------------------

  @Test
  void aCleanTablePasses() {
    DecisionTable t =
        table(
            "S-DEFAULT",
            scope("S-A", 100),
            row("S-B", 200, "(-x) > 1", Outcome.DO_NOT_REPORT, CITE, VALID),
            catchAll("S-DEFAULT", 900));

    assertThat(TableChecks.check(t)).isEmpty();
  }

  @Test
  void aCatchAllAloneIsAValidTable() {
    assertThat(TableChecks.check(table("S-DEFAULT", catchAll("S-DEFAULT", 1)))).isEmpty();
  }

  @Test
  void emptyTable() {
    assertThat(codes(table("S-DEFAULT"))).containsExactly("TABLE_EMPTY");
  }

  @Test
  void catchAllMissing() {
    // no row carries the declared catch-all id
    assertThat(codes(table("S-DEFAULT", scope("S-A", 100), scope("S-B", 200))))
        .containsExactly("CATCH_ALL_MISSING");

    // an always-true row under another id is not taken for the catch-all: it is refused too
    DecisionTable t = table("S-NOPE", scope("S-A", 100), catchAll("S-DEFAULT", 900));
    assertThat(codes(t))
        .containsExactly("CATCH_ALL_DUPLICATE", "CATCH_ALL_MISSING", "ROW_NO_VALIDITY");
  }

  @Test
  void catchAllNotLast() {
    // the catch-all is found by the declared id, and a row with a higher priority follows it
    DecisionTable t = table("S-DEFAULT", catchAll("S-DEFAULT", 100), scope("S-A", 200));

    assertThat(codes(t)).containsExactly("CATCH_ALL_NOT_LAST");

    // an always-true last row does not make another row the catch-all
    DecisionTable flagged =
        table(
            "S-FLAG",
            row("S-FLAG", 100, "true", Outcome.UNDETERMINED, CITE, Optional.empty()),
            scope("S-A", 200));
    assertThat(codes(flagged)).containsExactly("CATCH_ALL_NOT_LAST");

    // a tie with the catch-all is not "last" either (and is a duplicate priority)
    DecisionTable tie = table("S-DEFAULT", scope("S-A", 900), catchAll("S-DEFAULT", 900));
    assertThat(codes(tie)).contains("CATCH_ALL_NOT_LAST", "PRIORITY_DUPLICATE");
  }

  @Test
  void catchAllNotTrue() {
    for (String when : List.of("event.kind == 'NEW'", "True", "1 == 1", "!false", "true && true")) {
      DecisionTable t =
          table(
              "S-DEFAULT",
              scope("S-A", 100),
              row("S-DEFAULT", 900, when, Outcome.UNDETERMINED, CITE, Optional.empty()));

      assertThat(codes(t)).as(when).containsExactly("CATCH_ALL_NOT_TRUE");
    }
  }

  @Test
  void catchAllNotUndetermined() {
    for (Outcome outcome :
        List.of(Outcome.REPORT, Outcome.DO_NOT_REPORT, Outcome.REPORT_BY_OTHER)) {
      DecisionTable t =
          table(
              "S-DEFAULT",
              scope("S-A", 100),
              row("S-DEFAULT", 900, "true", outcome, CITE, Optional.empty()));

      assertThat(codes(t)).as(outcome.name()).containsExactly("CATCH_ALL_NOT_UNDETERMINED");
    }
  }

  @Test
  void catchAllHasValidity() {
    DecisionTable t =
        table(
            "S-DEFAULT",
            scope("S-A", 100),
            row("S-DEFAULT", 900, "true", Outcome.UNDETERMINED, CITE, VALID));

    assertThat(codes(t)).containsExactly("CATCH_ALL_HAS_VALIDITY");
  }

  @Test
  void aSecondTrueRowIsRefused() {
    for (String when : List.of("true", " true", "true ", "  true  ")) {
      DecisionTable t =
          table(
              "S-DEFAULT",
              row("S-TRUE", 100, when, Outcome.DO_NOT_REPORT, CITE, VALID),
              catchAll("S-DEFAULT", 900));

      assertThat(codes(t)).as("[" + when + "]").containsExactly("CATCH_ALL_DUPLICATE");
    }
  }

  @Test
  void duplicatePriority() {
    DecisionTable t =
        table(
            "S-DEFAULT",
            scope("S-A", 100),
            scope("S-B", 100),
            scope("S-C", 100),
            scope("S-D", 200),
            catchAll("S-DEFAULT", 900));

    List<PackProblem> problems = TableChecks.check(t);

    // one finding for the repeated value, not one per row
    assertThat(problems).hasSize(1);
    assertThat(problems.get(0).code()).isEqualTo("PRIORITY_DUPLICATE");
    assertThat(problems.get(0).path()).isEqualTo("scope/scope.yaml");
    assertThat(problems.get(0).message()).contains("100");
  }

  @Test
  void duplicateRowId() {
    DecisionTable t =
        table(
            "S-DEFAULT",
            scope("S-A", 100),
            scope("S-A", 200),
            scope("S-A", 300),
            catchAll("S-DEFAULT", 900));

    List<PackProblem> problems = TableChecks.check(t);

    assertThat(problems).hasSize(1);
    assertThat(problems.get(0).code()).isEqualTo("ROW_ID_DUPLICATE");
    assertThat(problems.get(0).message()).contains("S-A");
  }

  @Test
  void everyRowNeedsACitationTheCatchAllToo() {
    DecisionTable t =
        table(
            "S-DEFAULT",
            row("S-A", 100, "event.kind == 'NEW'", Outcome.REPORT, List.of(), VALID),
            scope("S-B", 200),
            row("S-DEFAULT", 900, "true", Outcome.UNDETERMINED, List.of(), Optional.empty()));

    List<PackProblem> problems = TableChecks.check(t);

    assertThat(problems.stream().map(PackProblem::code).toList())
        .containsExactly("ROW_NO_CITATION", "ROW_NO_CITATION");
    assertThat(problems.get(0).message() + problems.get(1).message())
        .contains("S-A")
        .contains("S-DEFAULT");
  }

  @Test
  void nonCatchAllRowNeedsValidity() {
    DecisionTable t =
        table(
            "S-DEFAULT",
            row("S-A", 100, "event.kind == 'NEW'", Outcome.REPORT, CITE, Optional.empty()),
            catchAll("S-DEFAULT", 900));

    assertThat(codes(t)).containsExactly("ROW_NO_VALIDITY");
  }

  @Test
  void validityInverted() {
    LocalDate d = LocalDate.of(2020, 1, 1);
    for (Validity v :
        List.of(
            new Validity(d, Optional.of(d)),
            new Validity(d, Optional.of(d.minusDays(1))),
            new Validity(d, Optional.of(LocalDate.of(2019, 6, 1))))) {
      DecisionTable t =
          table(
              "S-DEFAULT",
              row("S-A", 100, "event.kind == 'NEW'", Outcome.REPORT, CITE, Optional.of(v)),
              catchAll("S-DEFAULT", 900));

      assertThat(codes(t)).as(v.toString()).containsExactly("VALIDITY_INVERTED");
    }
    // a one-day window is fine
    DecisionTable ok =
        table(
            "S-DEFAULT",
            row(
                "S-A",
                100,
                "event.kind == 'NEW'",
                Outcome.REPORT,
                CITE,
                Optional.of(new Validity(d, Optional.of(d.plusDays(1))))),
            catchAll("S-DEFAULT", 900));
    assertThat(TableChecks.check(ok)).isEmpty();
  }

  @Test
  void whenMayNotStartWithAFormulaCharacter() {
    for (String when : List.of("=1+1", "+1 > 0", "-1 < x", "@x", " =1", "  +1", " -x > 1", " @a")) {
      DecisionTable t =
          table(
              "S-DEFAULT",
              row("S-A", 100, when, Outcome.REPORT, CITE, VALID),
              catchAll("S-DEFAULT", 900));

      assertThat(codes(t)).as("[" + when + "]").containsExactly("WHEN_FORMULA_CHARACTER");
    }
    // a parenthesis first, or a formula character inside the text, is fine
    for (String when : List.of("(-x) > 1", "x - 1 > 0", "x == '=1'", "!(x > 1)")) {
      DecisionTable t =
          table(
              "S-DEFAULT",
              row("S-A", 100, when, Outcome.REPORT, CITE, VALID),
              catchAll("S-DEFAULT", 900));

      assertThat(TableChecks.check(t)).as("[" + when + "]").isEmpty();
    }
    // the catch-all is checked like every row
    DecisionTable t =
        table(
            "S-DEFAULT",
            scope("S-A", 100),
            row("S-DEFAULT", 900, "=true", Outcome.UNDETERMINED, CITE, Optional.empty()));
    assertThat(codes(t)).contains("WHEN_FORMULA_CHARACTER");
  }

  @Test
  void resultsAreSortedAndNameTheTablePath() {
    DecisionTable t =
        table(
            "S-DEFAULT",
            row("S-A", 100, "=1", Outcome.REPORT, List.of(), Optional.empty()),
            row("S-A", 100, "x", Outcome.REPORT, CITE, VALID),
            row("S-DEFAULT", 900, "x", Outcome.REPORT, CITE, VALID));

    List<PackProblem> problems = TableChecks.check(t);
    List<PackProblem> sorted = new ArrayList<>(problems);
    java.util.Collections.sort(sorted);

    assertThat(problems).isEqualTo(sorted);
    assertThat(problems).isNotEmpty();
    assertThat(problems).allSatisfy(p -> assertThat(p.path()).isEqualTo("scope/scope.yaml"));
    assertThat(problems.stream().map(PackProblem::code))
        .contains(
            "WHEN_FORMULA_CHARACTER",
            "ROW_NO_CITATION",
            "ROW_NO_VALIDITY",
            "ROW_ID_DUPLICATE",
            "PRIORITY_DUPLICATE",
            "CATCH_ALL_NOT_TRUE",
            "CATCH_ALL_NOT_UNDETERMINED");
  }

  @Test
  void rowsAreKeptAscendingByPriorityByTheModel() {
    DecisionTable t =
        table("S-DEFAULT", catchAll("S-DEFAULT", 900), scope("S-B", 20), scope("S-A", 3));

    assertThat(t.rows().stream().map(TableRow::id).toList())
        .containsExactly("S-A", "S-B", "S-DEFAULT");
    assertThat(TableChecks.check(t)).isEmpty();
  }
}
