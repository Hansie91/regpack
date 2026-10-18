package dev.regpack.bench.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Sha256;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class CriteriaTest {
  private static final Criteria CRITERIA = Criteria.load();

  private static List<Criterion> all() {
    List<Criterion> all = new ArrayList<>();
    for (String spike : List.of("SP-R", "SP-G", "SP-L", "SP-X", "HARNESS")) {
      all.addAll(CRITERIA.forSpike(spike));
    }
    return all;
  }

  private static String resourceText() throws IOException {
    try (InputStream in = CriteriaTest.class.getResourceAsStream("/criteria/criteria.json")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void everyCriterionHasASourceAndASpikeBox() {
    assertThat(all()).isNotEmpty();
    for (Criterion c : all()) {
      assertThat(c.source()).as(c.id()).isNotBlank();
      assertThat(c.metric()).as(c.id()).isNotBlank();
      assertThat(CRITERIA.box(c.spike())).as(c.id()).isGreaterThan(Duration.ZERO);
    }
  }

  @Test
  void everyNonMeasuredOnlyCriterionHasAThreshold() {
    for (Criterion c : all()) {
      if (c.direction() == Direction.MEASURED_ONLY) {
        assertThat(c.threshold()).as(c.id()).isEmpty();
      } else {
        assertThat(c.threshold()).as(c.id()).isPresent();
      }
    }
  }

  @Test
  void theRegistryHoldsExactlyTheIdsOfTheCriteriaSection() {
    Set<String> expected =
        new TreeSet<>(
            List.of(
                "R-1a", "R-1b", "R-2a", "R-2b", "R-3", "R-4", "R-5", "R-6a", "R-6b", "R-7", "R-8",
                "G-1", "G-2", "G-3", "G-4", "L-1", "L-2", "L-3", "L-4", "L-5", "L-6", "L-7", "L-8",
                "L-9", "L-10", "L-11", "L-12", "L-13", "L-14", "L-15", "X-1a", "X-1b", "X-2a",
                "X-2b", "X-3", "X-4", "X-5", "X-6", "ST-1", "ST-2"));
    Set<String> actual = new TreeSet<>();
    for (Criterion c : all()) {
      assertThat(actual.add(c.id())).as("duplicate " + c.id()).isTrue();
    }
    assertThat(actual).isEqualTo(expected);
    assertThat(CRITERIA.byId("R-2a")).isPresent();
    assertThat(CRITERIA.byId("nope")).isEmpty();
  }

  @Test
  void thresholdsAndFlagsMatchTheCriteriaSection() {
    Criterion r2a = CRITERIA.byId("R-2a").orElseThrow();
    assertThat(r2a.direction()).isEqualTo(Direction.AT_LEAST);
    assertThat(r2a.threshold()).contains(new BigDecimal("10000"));
    assertThat(r2a.noisy()).isTrue();
    assertThat(r2a.required()).isTrue();
    assertThat(r2a.resources()).containsExactlyInAnyOrder(Resource.CPU, Resource.MEMORY);
    assertThat(CRITERIA.byId("L-9").orElseThrow().resources())
        .containsExactlyInAnyOrder(Resource.CPU, Resource.DISK);
    assertThat(CRITERIA.byId("R-4").orElseThrow().resources()).containsExactly(Resource.CPU);
    assertThat(CRITERIA.byId("X-1a").orElseThrow().threshold()).contains(new BigDecimal("3472"));
    assertThat(CRITERIA.byId("L-3").orElseThrow().threshold()).contains(new BigDecimal("3470"));
    assertThat(CRITERIA.byId("L-11").orElseThrow().threshold()).contains(new BigDecimal("100"));
    assertThat(CRITERIA.byId("X-3").orElseThrow().threshold()).contains(new BigDecimal("0.15"));
    assertThat(CRITERIA.byId("L-1").orElseThrow().minRuns()).isEqualTo(2);
    assertThat(CRITERIA.byId("L-4").orElseThrow().minRuns()).isEqualTo(2);
    assertThat(CRITERIA.byId("G-2").orElseThrow().minRuns()).isEqualTo(1);
    assertThat(CRITERIA.byId("R-1a").orElseThrow().minRuns()).isEqualTo(0);
    assertThat(CRITERIA.byId("L-6").orElseThrow().basis()).isEqualTo(Basis.EXTRAPOLATED);
    assertThat(CRITERIA.byId("L-4").orElseThrow().basis()).isEqualTo(Basis.EXTRAPOLATED);
    assertThat(CRITERIA.byId("G-1").orElseThrow().basis()).isEqualTo(Basis.DOCUMENT);
    assertThat(CRITERIA.byId("R-3").orElseThrow().required()).isFalse();
    assertThat(CRITERIA.byId("R-8").orElseThrow().required()).isFalse();
    assertThat(CRITERIA.byId("L-15").orElseThrow().required()).isFalse();
    assertThat(CRITERIA.byId("L-15").orElseThrow().consequenceIfNotRun())
        .contains("reading from the synchronous standby remains not allowed (spec 12.1)");
  }

  @Test
  void theSelftestPairExists() {
    Criterion st1 = CRITERIA.byId("ST-1").orElseThrow();
    assertThat(st1.direction()).isEqualTo(Direction.AT_MOST);
    assertThat(st1.threshold().orElseThrow()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(st1.noisy()).isFalse();
    Criterion st2 = CRITERIA.byId("ST-2").orElseThrow();
    assertThat(st2.direction()).isEqualTo(Direction.AT_LEAST);
    assertThat(st2.threshold().orElseThrow()).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(st2.noisy()).isTrue();
  }

  @Test
  void paramsAndBoxesAreAsDeclared() {
    assertThat(CRITERIA.param("availableMarginHours")).contains(new BigDecimal("4"));
    assertThat(CRITERIA.param("rtoLimitHours")).contains(new BigDecimal("4"));
    assertThat(CRITERIA.param("recordsPerDay")).contains(new BigDecimal("50000000"));
    assertThat(CRITERIA.param("burstRecordsPerSecond")).contains(new BigDecimal("3470"));
    assertThat(CRITERIA.param("backupIntervalHours")).contains(new BigDecimal("24"));
    assertThat(CRITERIA.param("nope")).isEmpty();
    assertThat(CRITERIA.box("HARNESS")).isEqualTo(Duration.ofMinutes(480));
    assertThat(CRITERIA.box("SP-R")).isEqualTo(Duration.ofMinutes(480));
    assertThat(CRITERIA.box("SP-G")).isEqualTo(Duration.ofMinutes(240));
    assertThat(CRITERIA.box("SP-L")).isEqualTo(Duration.ofMinutes(960));
    assertThat(CRITERIA.box("SP-X")).isEqualTo(Duration.ofMinutes(480));
    assertThatThrownBy(() -> CRITERIA.box("SP-Z")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void placeholderRowsAreListedFirstInTheMarkdown() {
    List<String> placeholderIds = new ArrayList<>();
    List<String> otherIds = new ArrayList<>();
    for (Criterion c : all()) {
      (c.source().contains("PLAN") ? placeholderIds : otherIds).add(c.id());
    }
    assertThat(placeholderIds.size()).isGreaterThanOrEqualTo(10);
    assertThat(otherIds).isNotEmpty();

    List<String> order = new ArrayList<>();
    for (String line : CRITERIA.toMarkdown().split("\n")) {
      if (!line.startsWith("| ") || line.startsWith("| Id ")) {
        continue;
      }
      order.add(line.substring(2, line.indexOf(" |", 2)));
    }
    assertThat(order).hasSize(all().size());
    int lastPlaceholder = -1;
    int firstOther = Integer.MAX_VALUE;
    for (int i = 0; i < order.size(); i++) {
      if (placeholderIds.contains(order.get(i))) {
        lastPlaceholder = i;
      } else {
        firstOther = Math.min(firstOther, i);
      }
    }
    assertThat(lastPlaceholder).isLessThan(firstOther);
    assertThat(order.subList(0, placeholderIds.size()))
        .containsExactlyInAnyOrderElementsOf(placeholderIds);
    assertThat(CRITERIA.toMarkdown()).contains(CRITERIA.digest().hex()).endsWith("\n");
  }

  @Test
  void markdownIsStable() {
    assertThat(Criteria.load().toMarkdown()).isEqualTo(CRITERIA.toMarkdown());
  }

  @Test
  void digestChangesWhenAThresholdChanges() throws IOException {
    String text = resourceText();
    assertThat(Criteria.parse(text).digest()).isEqualTo(CRITERIA.digest());
    assertThat(CRITERIA.digest()).isInstanceOf(Sha256.class);
    String changed = text.replace("\"10000\"", "\"10001\"");
    assertThat(changed).isNotEqualTo(text);
    assertThat(Criteria.parse(changed).digest()).isNotEqualTo(CRITERIA.digest());
    // whitespace is not part of the digest
    assertThat(Criteria.parse(text.replace("\n", " ")).digest()).isEqualTo(CRITERIA.digest());
  }

  @Test
  void groupsMatchTheCriteriaSection() {
    assertThat(idsOfGroup("layer:inhouse")).containsExactlyInAnyOrder("R-1a", "R-2a", "R-6a");
    assertThat(idsOfGroup("layer:cel")).containsExactlyInAnyOrder("R-1b", "R-2b", "R-6b");
    assertThat(idsOfGroup("policy:ALL")).containsExactlyInAnyOrder("X-1a", "X-2a");
    assertThat(idsOfGroup("policy:S10")).containsExactlyInAnyOrder("X-1b", "X-2b", "X-3");
    for (Criterion c : all()) {
      if (c.group().isPresent()) {
        assertThat(c.required()).as(c.id()).isTrue();
      }
    }
  }

  private static List<String> idsOfGroup(String group) {
    List<String> ids = new ArrayList<>();
    for (Criterion c : all()) {
      if (c.group().equals(Optional.of(group))) {
        ids.add(c.id());
      }
    }
    return ids;
  }

  @Test
  void parseRejectsMalformedRegistries() throws IOException {
    String text = resourceText();
    assertThatThrownBy(() -> Criteria.parse("{}")).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> Criteria.parse(text.replace("regpack.bench.criteria/1", "x/1")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Criteria.parse(text.replace("\"AT_LEAST\"", "\"SOMETIMES\"")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> Criteria.parse(text.replace("\"spike\": \"SP-R\"", "\"spike\": \"SP-Q\"")))
        .as("a spike without a box")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> Criteria.parse(text.replace("\"unit\": \"runs\"", "\"units\": \"runs\"")))
        .as("an unknown key")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> Criteria.parse(text.replace("\"threshold\": \"3472\"", "\"x\": \"3472\"")))
        .as("a gate without a threshold")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> Criteria.parse(text.replace("\"threshold\": \"3472\"", "\"threshold\": 3472")))
        .as("a number that is not written as a string")
        .isInstanceOf(IllegalArgumentException.class);
  }
}
