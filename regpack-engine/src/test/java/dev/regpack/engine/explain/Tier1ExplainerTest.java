package dev.regpack.engine.explain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.Citation;
import dev.regpack.model.CitationRole;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionId;
import dev.regpack.model.decision.DecisionRecord;
import dev.regpack.model.decision.PathStep;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class Tier1ExplainerTest {
  private static final Sha256 INPUT = new Sha256("a".repeat(64));
  private static final Sha256 CONTEXT = new Sha256("b".repeat(64));

  private static DecisionRecord record(
      Outcome outcome,
      String reason,
      String node,
      List<String> flags,
      List<String> hits,
      List<PathStep> path,
      List<Citation> citations,
      List<Leg> legs) {
    DecisionBody body =
        new DecisionBody(
            EventIdentity.of("LEI1", "XPAR", "EX-1"),
            new Regime("mifir.eu.rts22"),
            CONTEXT,
            Instant.parse("2026-10-09T09:30:00.123456Z"),
            Instant.parse("2026-10-09T10:00:00Z"),
            outcome,
            flags,
            new ReasonCode(reason),
            node,
            hits,
            path,
            citations,
            legs,
            INPUT);
    return new DecisionRecord(new DecisionId(7), 1, Optional.empty(), body);
  }

  @Test
  void rendersPinnedText() {
    DecisionRecord decision =
        record(
            Outcome.REPORT,
            "MIFIR.N08.IN_SCOPE_26_2_A",
            "N08",
            List.of(),
            List.of("S-N08-equity-on-venue"),
            List.of(
                new PathStep("S-N03-novation", false, 3),
                new PathStep("S-D14-natural-person", false, 2),
                new PathStep("S-N08-equity-on-venue", true, 3)),
            List.of(
                new Citation(
                    "mifir-placeholder",
                    "art/26/par/2/pt/a",
                    CitationRole.BASIS,
                    Optional.empty())),
            List.of(Leg.MARKET));

    String expected =
        String.join(
            "\n",
            "Decision 7 (seq 1) for LEI1|XPAR|EX-1 under mifir.eu.rts22",
            "Outcome: REPORT",
            "Reason: MIFIR.N08.IN_SCOPE_26_2_A (node N08)",
            "Rule hits: S-N08-equity-on-venue",
            "Path:",
            "  1. S-N03-novation: no match (3 units)",
            "  2. S-D14-natural-person: no match (2 units)",
            "  3. S-N08-equity-on-venue: MATCH (3 units)",
            "Citations:",
            "  - mifir-placeholder art/26/par/2/pt/a [BASIS]",
            "Flags: none",
            "Legs: MARKET",
            "Input digest: " + "a".repeat(64),
            "Context: " + "b".repeat(64));

    assertThat(Tier1Explainer.explain(decision)).isEqualTo(expected);
  }

  @Test
  void rendersFlagsAndNoLegsForUndetermined() {
    DecisionRecord decision =
        record(
            Outcome.UNDETERMINED,
            "EXEC_BUDGET",
            "EXEC",
            List.of("rule_id=S-N08-equity-on-venue", "cost_units=5", "limit=5"),
            List.of(),
            List.of(
                new PathStep("S-N03-novation", false, 3),
                new PathStep("S-D14-natural-person", false, 2)),
            List.of(new Citation("regpack-spec", "I5", CitationRole.GUIDANCE, Optional.empty())),
            List.of());

    String text = Tier1Explainer.explain(decision);

    assertThat(text)
        .contains("Outcome: UNDETERMINED\n")
        .contains("Reason: EXEC_BUDGET (node EXEC)\n")
        .contains("Flags: rule_id=S-N08-equity-on-venue, cost_units=5, limit=5\n")
        .contains("Legs: none\n")
        .contains("  - regpack-spec I5 [GUIDANCE]\n")
        .doesNotEndWith("\n");
  }
}
