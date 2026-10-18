package dev.regpack.pack.csv;

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
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A seeded generator of valid decision tables (they pass {@code TableParser} and {@code
 * TableChecks}) whose free text, the {@code when} cell, is hostile for CSV and YAML: commas,
 * quotes, backslashes, brackets, colons, hash signs, leading and trailing spaces, accented letters,
 * CJK and a supplementary-plane letter. It uses a {@link SplittableRandom} with the seed the caller
 * gives, never the clock.
 */
final class RandomTables {
  private static final List<String> TOKENS =
      List.of(
          ",", "\"", "#", ":", "'", "[", "]", "{", "}", "\\", "a", "b", "e", "x", "7", ".", "_",
          "é", "ü", "ñ", "ç", "日", "本", "語", "𠀀", " ", " ", "\"\"", ", ");
  private static final List<String> SOURCES = List.of("sample-law", "regpack-spec", "src.A-1");
  private static final List<String> PATHS =
      List.of("sample/1", "art:3/par.2-a", "D14", "I5", "x_y.z", "a:b");
  private static final List<String> SET_NAMES = List.of("action", "a.b", "k1", "zeta", "m_n");
  private static final List<String> SET_VALUES =
      List.of("NEWT", "-5", "a:b", "x/y", "1.5", "A-B_c", "-", "0");
  private static final List<String> INPUTS =
      List.of("client.kind", "event.kind", "instrument.assetClass", "trade.price");

  private RandomTables() {}

  /** A small hand-made table with every optional element present once. */
  static DecisionTable sample() {
    TableRow novation =
        new TableRow(
            "S-N03-novation",
            "N03",
            300,
            "event.kind in ['NOVATION','ASSIGNMENT']",
            Outcome.DO_NOT_REPORT,
            new ReasonCode("SAMPLE.N03.EXCLUDED"),
            Optional.of("RULE-0001"),
            List.of(
                new CitationRef("sample-law", "sample/1", CitationRole.BASIS),
                new CitationRef("regpack-spec", "D14", CitationRole.INTERPRETATION)),
            "RAT-0001",
            Optional.of(
                new Validity(LocalDate.of(2018, 1, 3), Optional.of(LocalDate.of(2030, 1, 1)))),
            Map.of("action", "NEWT", "a.b", "x"),
            List.of());
    TableRow equity =
        new TableRow(
            "S-N08-equity-on-venue",
            "N08",
            800,
            "instrument.assetClass == 'EQUITY' && instrument.tradedOnVenue",
            Outcome.REPORT,
            new ReasonCode("SAMPLE.N08.IN_SCOPE"),
            Optional.empty(),
            List.of(new CitationRef("sample-law", "sample/2", CitationRole.BASIS)),
            "RAT-0003",
            Optional.of(new Validity(LocalDate.of(2018, 1, 3), Optional.empty())),
            Map.of(),
            List.of(Leg.MARKET, Leg.CLIENT));
    TableRow catchAll =
        new TableRow(
            "S-DEFAULT-catch-all",
            "DEFAULT",
            9999,
            "true",
            Outcome.UNDETERMINED,
            new ReasonCode("SAMPLE.DEFAULT.NO_RULE_HIT"),
            Optional.empty(),
            List.of(new CitationRef("regpack-spec", "I5", CitationRole.GUIDANCE)),
            "RAT-0004",
            Optional.empty(),
            Map.of(),
            List.of());
    return new DecisionTable(
        "scope/sample-scope.yaml",
        "sample.s0.scope",
        List.of("client.kind", "event.kind"),
        "S-DEFAULT-catch-all",
        List.of(catchAll, equity, novation));
  }

  /** One table of one to twelve rows; the last row is the catch-all. */
  static DecisionTable table(SplittableRandom rnd) {
    int total = 1 + rnd.nextInt(12);
    TreeSet<Integer> priorities = new TreeSet<>();
    while (priorities.size() < total - 1) {
      priorities.add(1 + rnd.nextInt(900_000_000));
    }
    List<TableRow> rows = new ArrayList<>();
    int i = 0;
    for (int priority : priorities) {
      rows.add(ordinary(rnd, i++, priority));
    }
    rows.add(catchAll(rnd, i));
    // the model sorts the rows; shuffle the order they are handed in
    List<TableRow> shuffled = new ArrayList<>(rows);
    for (int k = shuffled.size() - 1; k > 0; k--) {
      int j = rnd.nextInt(k + 1);
      TableRow t = shuffled.get(k);
      shuffled.set(k, shuffled.get(j));
      shuffled.set(j, t);
    }
    List<String> inputs = new ArrayList<>();
    for (String input : INPUTS) {
      if (rnd.nextBoolean()) {
        inputs.add(input);
      }
    }
    return new DecisionTable("scope/random.yaml", "random.scope", inputs, "R-CATCH-ALL", shuffled);
  }

  private static TableRow ordinary(SplittableRandom rnd, int index, int priority) {
    Outcome outcome = Outcome.values()[rnd.nextInt(Outcome.values().length)];
    String node = "N" + rnd.nextInt(100);
    LocalDate from = LocalDate.of(2018 + rnd.nextInt(12), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28));
    Optional<LocalDate> to =
        rnd.nextBoolean()
            ? Optional.of(from.plusDays(1 + rnd.nextInt(2000)))
            : Optional.<LocalDate>empty();
    return new TableRow(
        "R-" + index + "-" + pick(rnd, List.of("a", "B", "c.d", "e_f", "g-h")),
        node,
        priority,
        when(rnd),
        outcome,
        new ReasonCode("TST." + node + "." + pick(rnd, List.of("EXCLUDED", "IN_SCOPE", "Mixed1"))),
        rnd.nextBoolean() ? Optional.of("RULE-" + index) : Optional.empty(),
        cites(rnd),
        "RAT-" + (1000 + index),
        Optional.of(new Validity(from, to)),
        set(rnd),
        outcome == Outcome.REPORT ? legs(rnd) : List.of());
  }

  private static TableRow catchAll(SplittableRandom rnd, int index) {
    String when = pick(rnd, List.of("true", " true", "true ", "  true  "));
    return new TableRow(
        "R-CATCH-ALL",
        "DEFAULT",
        999_999_999,
        when,
        Outcome.UNDETERMINED,
        new ReasonCode("TST.DEFAULT.NO_RULE_HIT"),
        Optional.empty(),
        cites(rnd),
        "RAT-" + (1000 + index),
        Optional.empty(),
        set(rnd),
        List.of());
  }

  /** A hostile one-line text that is not blank and does not start with a formula character. */
  static String when(SplittableRandom rnd) {
    while (true) {
      StringBuilder body = new StringBuilder();
      String first = TOKENS.get(rnd.nextInt(TOKENS.size()));
      while (first.isBlank()) {
        first = TOKENS.get(rnd.nextInt(TOKENS.size()));
      }
      body.append(first);
      int more = rnd.nextInt(20);
      for (int k = 0; k < more; k++) {
        body.append(TOKENS.get(rnd.nextInt(TOKENS.size())));
      }
      String text = " ".repeat(rnd.nextInt(3)) + body + " ".repeat(rnd.nextInt(3));
      if (!text.strip().equals("true")) {
        return text;
      }
    }
  }

  private static List<CitationRef> cites(SplittableRandom rnd) {
    int n = 1 + rnd.nextInt(3);
    List<CitationRef> out = new ArrayList<>();
    for (int k = 0; k < n; k++) {
      out.add(
          new CitationRef(
              pick(rnd, SOURCES),
              pick(rnd, PATHS),
              CitationRole.values()[rnd.nextInt(CitationRole.values().length)]));
    }
    return out;
  }

  private static Map<String, String> set(SplittableRandom rnd) {
    Map<String, String> out = new TreeMap<>();
    int n = rnd.nextInt(4);
    for (int k = 0; k < n; k++) {
      out.put(pick(rnd, SET_NAMES), pick(rnd, SET_VALUES));
    }
    return out;
  }

  private static List<Leg> legs(SplittableRandom rnd) {
    List<Leg> all = new ArrayList<>(List.of(Leg.values()));
    List<Leg> out = new ArrayList<>();
    int n = 1 + rnd.nextInt(all.size());
    for (int k = 0; k < n; k++) {
      out.add(all.remove(rnd.nextInt(all.size())));
    }
    return out;
  }

  private static <T> T pick(SplittableRandom rnd, List<T> items) {
    return items.get(rnd.nextInt(items.size()));
  }
}
