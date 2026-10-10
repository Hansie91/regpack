package dev.regpack.bench.proto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The only production source of the P-5 steal discard rule: a wrong column, a wrong total or a
 * parser that always returns zeros would stop the 5% rule from ever firing on Linux.
 */
class StealSourceTest {
  /** user nice system idle iowait irq softirq steal guest guest_nice; every field is distinct. */
  private static final String CPU_LINE = "cpu  4705 150 1120 16250856 2300 0 23 99 0 0";

  private static final long TOTAL = 4705L + 150 + 1120 + 16250856 + 2300 + 0 + 23 + 99;

  @Test
  void parsesTheAggregateLineWithTheStealColumnAndTheEightCounterTotal() {
    assertThat(StealSource.parseCpuLine(CPU_LINE)).containsExactly(TOTAL, 99L);
  }

  @Test
  void theStealIsTheEighthCounterNotItsNeighbours() {
    // softirq 7, steal 8, guest 9: only the eighth may be taken as the steal
    assertThat(StealSource.parseCpuLine("cpu 1 1 1 1 1 1 7 8 9 9"))
        .containsExactly(1 * 6 + 7 + 8, 8L);
  }

  @Test
  void aLineWithoutTheStealColumnOrNotTheAggregateIsZero() {
    assertThat(StealSource.parseCpuLine("cpu0 4705 150 1120 16250856 2300 0 23 99 0 0"))
        .containsExactly(0L, 0L);
    assertThat(StealSource.parseCpuLine("intr 1 2 3 4 5 6 7 8 9 10")).containsExactly(0L, 0L);
    assertThat(StealSource.parseCpuLine("cpu 4705 150 1120 16250856 2300 0 23"))
        .containsExactly(0L, 0L);
    assertThat(StealSource.parseCpuLine("")).containsExactly(0L, 0L);
  }

  @Test
  void systemReadsTheFirstLineOfTheFile(@TempDir Path dir) throws IOException {
    Path stat = dir.resolve("stat");
    Files.writeString(
        stat, CPU_LINE + "\ncpu0 1 2 3 4 5 6 7 8 9 10\nintr 5\n", StandardCharsets.US_ASCII);
    assertThat(StealSource.fromFile(stat).cpuJiffies()).containsExactly(TOTAL, 99L);
  }

  @Test
  void aNonNumericFieldDoesNotEscapeFromTheSource(@TempDir Path dir) throws IOException {
    Path stat = dir.resolve("stat");
    Files.writeString(
        stat, "cpu  4705 150 oops 16250856 2300 0 23 99 0 0\n", StandardCharsets.US_ASCII);
    assertThat(StealSource.fromFile(stat).cpuJiffies()).containsExactly(0L, 0L);
  }

  @Test
  void aMissingOrEmptyFileIsZero(@TempDir Path dir) throws IOException {
    assertThat(StealSource.fromFile(dir.resolve("absent")).cpuJiffies()).containsExactly(0L, 0L);
    Path empty = dir.resolve("empty");
    Files.writeString(empty, "", StandardCharsets.US_ASCII);
    assertThat(StealSource.fromFile(empty).cpuJiffies()).containsExactly(0L, 0L);
  }

  @Test
  void theSystemSourceNeverThrows() {
    long[] jiffies = StealSource.system().cpuJiffies();
    assertThat(jiffies).hasSize(2);
    assertThat(jiffies[0]).isGreaterThanOrEqualTo(jiffies[1]).isGreaterThanOrEqualTo(0L);
  }
}
