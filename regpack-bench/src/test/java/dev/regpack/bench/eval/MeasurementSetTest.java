package dev.regpack.bench.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.bench.eval.MeasurementSet.CountValue;
import dev.regpack.bench.eval.MeasurementSet.ForkStat;
import dev.regpack.bench.eval.MeasurementSet.ForkValues;
import dev.regpack.bench.eval.MeasurementSet.MeasuredOnly;
import dev.regpack.bench.eval.MeasurementSet.Projection;
import dev.regpack.bench.eval.MeasurementSet.RunInfo;
import dev.regpack.bench.eval.MeasurementSet.Series;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MeasurementSetTest {
  static final String DIGEST = "a".repeat(64);
  static final String OTHER_DIGEST = "b".repeat(64);

  static RunInfo run(String id, String part, String sha, String cpu) {
    return new RunInfo(id, 1, part, sha, cpu, "ubuntu24/20260901.1");
  }

  static ForkValues forks(String id, int count, String base, String scale) {
    List<ForkStat> list = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      list.add(new ForkStat(i, new BigDecimal(base).add(BigDecimal.valueOf(i)), false, "", false));
    }
    return new ForkValues(id, "records/s", new BigDecimal(scale), list);
  }

  static MeasurementSet set(String digest, List<RunInfo> runs, Series... series) {
    Map<Resource, List<BigDecimal>> canary = new EnumMap<>(Resource.class);
    canary.put(Resource.CPU, List.of(new BigDecimal("1000.5"), new BigDecimal("1001.25")));
    return new MeasurementSet(
        "SP-R",
        digest,
        "FULL",
        List.of(series),
        canary,
        runs,
        Map.of("gitSha", "abc1234"),
        Map.of("cpu.count", 4L),
        Map.of("executorGate", "PASS"),
        List.of());
  }

  @Test
  void roundTripsThroughCanonicalJsonWithoutDoubles() {
    Map<String, Object> decisions = new LinkedHashMap<>();
    decisions.put(
        "budget", Map.of("perRecordUnits", 12_000_000L, "ratio", new BigDecimal("0.0000125")));
    decisions.put("kneeN", 500L);
    MeasurementSet m =
        new MeasurementSet(
            "SP-L",
            DIGEST,
            "PARTIAL",
            List.of(
                forks("L-2", 3, "12000.123456789", "0.25"),
                new CountValue("L-1", 0, 2),
                new MeasuredOnly("L-14", true, 4, new BigDecimal("123456789012"), "bytes"),
                new Projection(
                    "L-6",
                    "hours",
                    new BigDecimal("0.000277777778"),
                    List.of(
                        new BigDecimal("1000000"),
                        new BigDecimal("2000000"),
                        new BigDecimal("3000000")),
                    List.of(new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("30")),
                    new BigDecimal("10000000"),
                    new BigDecimal("0.5"))),
            Map.of(
                Resource.CPU, List.of(new BigDecimal("1000"), new BigDecimal("1000.1")),
                Resource.DISK, List.of(new BigDecimal("5000"))),
            List.of(run("1", "a", "abc", "cpuA")),
            Map.of("gitSha", "abc"),
            Map.of("cpu.model", "cpuA"),
            decisions,
            List.of("restore at 10M keys not run"));
    String json = m.toJson();
    assertThat(json).doesNotContain("E+").doesNotContain(".0E").doesNotContain("E-");
    assertThat(json).startsWith("{").contains("\"schema\":\"regpack.bench.measurements/1\"");
    MeasurementSet back = MeasurementSet.parse(json);
    assertThat(back.toJson()).isEqualTo(json);
    assertThat(back.spike()).isEqualTo("SP-L");
    assertThat(back.series()).hasSize(4);
    assertThat(back.open()).containsExactly("restore at 10M keys not run");
    assertThat(back.canary().get(Resource.DISK)).hasSize(1);
  }

  @Test
  void parseIsStrict() {
    String good =
        set(
                DIGEST,
                List.of(run("1", "a", "abc", "cpu"), run("2", "a", "abc", "cpu")),
                forks("R-2a", 2, "1", "1"))
            .toJson();
    assertThat(MeasurementSet.parse(good).runs()).hasSize(2);
    assertThatThrownBy(
            () -> MeasurementSet.parse(good.replace("\"spike\"", "\"extra\":1,\"spike\"")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("extra");
    assertThatThrownBy(() -> MeasurementSet.parse(good.replace(DIGEST, "../../etc")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("configDigest");
    assertThatThrownBy(
            () -> MeasurementSet.parse(good.replace("regpack.bench.measurements/1", "x/1")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MeasurementSet.parse("{\"schema\":null}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MeasurementSet.parse(good.replace("\"FULL\"", "\"EVERYTHING\"")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aDoubleCannotEnterTheModel() {
    assertThatThrownBy(
            () ->
                new MeasurementSet(
                    "SP-R",
                    DIGEST,
                    "FULL",
                    List.of(),
                    Map.of(),
                    List.of(),
                    Map.of(),
                    Map.of(),
                    Map.of("rate", 1.5d),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void mergeRequiresEqualConfigDigest() {
    MeasurementSet a =
        set(DIGEST, List.of(run("1", "a", "s", "cpu")), forks("R-2a", 15, "100", "1"));
    MeasurementSet b =
        set(OTHER_DIGEST, List.of(run("2", "a", "s", "cpu")), forks("R-2a", 15, "100", "1"));
    assertThatThrownBy(() -> a.merge(b))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("configDigest");
  }

  @Test
  void mergePoolsForksAndKeepsRunProvenance() {
    MeasurementSet a =
        set(
            DIGEST,
            List.of(run("1", "a", "s1", "cpuA")),
            forks("R-2a", 15, "100", "1"),
            new CountValue("R-1a", 1, 1));
    MeasurementSet b =
        set(
            DIGEST,
            List.of(run("2", "a", "s1", "cpuA")),
            forks("R-2a", 15, "200", "1"),
            new CountValue("R-1a", 2, 1),
            new CountValue("R-5", 0, 1));
    MeasurementSet merged = a.merge(b);
    ForkValues pooled = (ForkValues) merged.series().get(0);
    assertThat(pooled.forks()).hasSize(30);
    assertThat(pooled.forks().stream().map(ForkStat::index).toList())
        .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 30).boxed().toList());
    assertThat(pooled.forks().get(15).statistic()).isEqualByComparingTo("200");
    CountValue count = (CountValue) merged.series().get(1);
    assertThat(count.observed()).isEqualTo(3);
    assertThat(count.validRuns()).isEqualTo(2);
    assertThat(merged.series()).hasSize(3);
    assertThat(merged.runs()).extracting(RunInfo::runId).containsExactly("1", "2");
    assertThat(merged.canary().get(Resource.CPU)).hasSize(4);
    assertThat(merged.vmCount()).isEqualTo(2);
  }

  private static MeasurementSet withDecisions(
      List<RunInfo> runs, Map<String, Object> decisions, List<String> open) {
    return new MeasurementSet(
        "SP-L",
        DIGEST,
        "FULL",
        List.of(new CountValue("L-1", 0, 1)),
        Map.of(),
        runs,
        Map.of(),
        Map.of(),
        decisions,
        open);
  }

  @Test
  void mergeKeepsTheFirstOfADifferingDecisionAndNotesTheConflict() {
    // measured decisions (a p99 of one dispatch) differ between the parts of one configuration:
    // pooling must still work, so the first value stays and the conflict is an open item
    MeasurementSet a =
        withDecisions(
            List.of(run("1", "a", "s", "cpu")),
            Map.of("ledger.commitP99Ms", 12L, "executorGate", "PASS"),
            List.of("first open"));
    MeasurementSet b =
        withDecisions(
            List.of(run("2", "b", "s", "cpu")),
            Map.of("ledger.commitP99Ms", 15L, "executorGate", "PASS", "extra", 1L),
            List.of());
    MeasurementSet merged = a.merge(b);
    assertThat(merged.decisions())
        .containsEntry("ledger.commitP99Ms", 12L)
        .containsEntry("executorGate", "PASS")
        .containsEntry("extra", 1L);
    assertThat(merged.open()).hasSize(2).first().isEqualTo("first open");
    assertThat(merged.open().get(1)).contains("ledger.commitP99Ms").contains("12").contains("15");
    // equal values are no conflict
    assertThat(a.merge(a).open()).containsExactly("first open");
  }

  @Test
  void forkIndexesAreUniqueAndAscending() {
    ForkStat first = new ForkStat(1, BigDecimal.ONE, false, "", false);
    ForkStat second = new ForkStat(2, BigDecimal.ONE, false, "", false);
    assertThatThrownBy(() -> new ForkValues("R-2a", "u", BigDecimal.ONE, List.of(second, first)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("R-2a");
    assertThatThrownBy(() -> new ForkValues("R-2a", "u", BigDecimal.ONE, List.of(first, first)))
        .isInstanceOf(IllegalArgumentException.class);
    // a parsed series is checked too
    String json = set(DIGEST, List.of(), forks("R-2a", 3, "1", "1")).toJson();
    assertThat(MeasurementSet.parse(json).series()).hasSize(1);
    assertThatThrownBy(() -> MeasurementSet.parse(json.replace("\"index\":1", "\"index\":0")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void mergeRefusesOtherSpikesAndMismatchedUnits() {
    MeasurementSet a = set(DIGEST, List.of(), forks("R-2a", 5, "100", "1"));
    MeasurementSet other =
        new MeasurementSet(
            "SP-L", DIGEST, "FULL", List.of(), Map.of(), List.of(), Map.of(), Map.of(), Map.of(),
            List.of());
    assertThatThrownBy(() -> a.merge(other)).isInstanceOf(IllegalArgumentException.class);
    MeasurementSet rescaled = set(DIGEST, List.of(), forks("R-2a", 5, "100", "0.25"));
    assertThatThrownBy(() -> a.merge(rescaled))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("R-2a");
  }

  @Test
  void vmCountAndMixedHardware() {
    MeasurementSet two =
        set(
            DIGEST,
            List.of(run("1", "a", "s", "AMD EPYC 7763"), run("2", "a", "s", "Intel Xeon 8370C")));
    assertThat(two.vmCount()).isEqualTo(2);
    assertThat(two.mixedHardware()).isTrue();
    MeasurementSet same =
        set(
            DIGEST,
            List.of(run("1", "a", "s", "AMD EPYC 7763"), run("2", "a", "s", "AMD EPYC 7763")));
    assertThat(same.vmCount()).isEqualTo(2);
    assertThat(same.mixedHardware()).isFalse();
    MeasurementSet one =
        set(
            DIGEST,
            List.of(run("1", "a", "s", "AMD EPYC 7763"), run("1", "b", "s", "AMD EPYC 7763")));
    assertThat(one.vmCount()).isEqualTo(1);
    assertThat(set(DIGEST, List.of()).vmCount()).isZero();
  }

  @Test
  void configDigestIgnoresGitShaAndPartButNotProfileOrStretch() {
    Map<String, Object> plan = new LinkedHashMap<>();
    plan.put("forks", 15L);
    plan.put("windowSeconds", 2L);
    plan.put("ruleCounts", List.of(50L, 200L, 500L));
    String base = MeasurementSet.configDigest("SP-R", "full", false, DIGEST, plan);
    assertThat(base).matches("[0-9a-f]{64}");

    Map<String, Object> withIdentity = new LinkedHashMap<>(plan);
    withIdentity.put("gitSha", "deadbeef");
    withIdentity.put("part", "b");
    withIdentity.put("runId", "99");
    withIdentity.put("attempt", 3L);
    withIdentity.put("env", Map.of("cpu.model", "x"));
    assertThat(MeasurementSet.configDigest("SP-R", "full", false, DIGEST, withIdentity))
        .isEqualTo(base);

    assertThat(MeasurementSet.configDigest("SP-R", "informative", false, DIGEST, plan))
        .isNotEqualTo(base);
    assertThat(MeasurementSet.configDigest("SP-R", "full", true, DIGEST, plan)).isNotEqualTo(base);
    assertThat(MeasurementSet.configDigest("SP-X", "full", false, DIGEST, plan)).isNotEqualTo(base);
    assertThat(MeasurementSet.configDigest("SP-R", "full", false, OTHER_DIGEST, plan))
        .isNotEqualTo(base);
    Map<String, Object> changed = new LinkedHashMap<>(plan);
    changed.put("forks", 7L);
    assertThat(MeasurementSet.configDigest("SP-R", "full", false, DIGEST, changed))
        .isNotEqualTo(base);
  }
}
