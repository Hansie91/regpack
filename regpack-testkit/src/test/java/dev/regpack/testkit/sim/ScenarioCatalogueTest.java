package dev.regpack.testkit.sim;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.testkit.sim.ScenarioCatalogue.Entry;
import dev.regpack.testkit.sim.ScenarioCatalogue.Kind;
import dev.regpack.testkit.sim.ScenarioCatalogue.Status;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ScenarioCatalogueTest {
  @Test
  void catalogueNamesEverySection143Scenario() {
    List<Entry> all = ScenarioCatalogue.all();
    assertThat(all.stream().map(Entry::name))
        .containsExactly(
            "ACPT",
            "PDNG_THEN_ACPT",
            "PDNG_TURNS_RJCT_DAY8",
            "RJCT_WITH_CON_CODES",
            "RCVD",
            "FILE_RJCT",
            "FILE_CRPT",
            "FILE_INCF",
            "DUPLICATE_TRN",
            "SILENCE_NOT_RECEIVED",
            "SILENCE_RECEIVED_UNANSWERED",
            "FEEDBACK_ANOMALIES",
            "SKEWED_CLOCK",
            "FAULT_INJECTION");
    assertThat(names(all, e -> e.status() == Status.IMPLEMENTED))
        .isEqualTo(
            Set.of("ACPT", "SILENCE_RECEIVED_UNANSWERED", "SKEWED_CLOCK", "FAULT_INJECTION"));
    assertThat(names(all, e -> e.kind() == Kind.MECHANISM))
        .isEqualTo(Set.of("SKEWED_CLOCK", "FAULT_INJECTION"));
    // The MECHANISM entries say where the scenario content (P8) and the fault matrix (P4) land.
    assertThat(ScenarioCatalogue.byName("SKEWED_CLOCK").orElseThrow().specSource()).contains("P8");
    assertThat(ScenarioCatalogue.byName("FAULT_INJECTION").orElseThrow().specSource())
        .contains("P4");
    assertThat(all.stream().filter(e -> e.status() == Status.PLANNED))
        .isNotEmpty()
        .allSatisfy(e -> assertThat(e.owningPlan()).isEqualTo("P8"));
    assertThat(all.stream().filter(e -> e.status() == Status.IMPLEMENTED))
        .allSatisfy(e -> assertThat(e.owningPlan()).isEqualTo("P0"));
    assertThat(ScenarioCatalogue.byName("RCVD")).map(Entry::name).contains("RCVD");
    assertThat(ScenarioCatalogue.byName("NOPE")).isEmpty();
  }

  private static Set<String> names(List<Entry> all, Predicate<Entry> filter) {
    return all.stream().filter(filter).map(Entry::name).collect(Collectors.toSet());
  }
}
