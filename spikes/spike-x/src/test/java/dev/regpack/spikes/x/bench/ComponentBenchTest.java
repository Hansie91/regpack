package dev.regpack.spikes.x.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.spikes.x.TestSchemas;
import dev.regpack.spikes.x.validate.Bisect;
import dev.regpack.spikes.x.validate.PerRecordValidator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.validation.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.SAXException;

/** The component benchmarks of the SP-X runner: the per-record gate, the file gate, the bisect. */
class ComponentBenchTest {
  @Test
  void recordXsdBenchValidatesItsRingAndFoldsTheWork() throws SAXException, IOException {
    Schema schema = TestSchemas.txn();
    RecordXsdBench bench = new RecordXsdBench(schema, 3L, 50);
    assertThat(bench.runUnits(120)).as("more units than the ring: it wraps").isEqualTo(120);
    long first = bench.checksum();
    assertThat(first).isNotZero();
    RecordXsdBench again = new RecordXsdBench(schema, 3L, 50);
    again.runUnits(120);
    assertThat(again.checksum()).as("deterministic").isEqualTo(first);
    bench.runUnits(1);
    assertThat(bench.checksum()).isNotEqualTo(first);
  }

  @Test
  void recordXsdBenchFailsOnARecordThatIsNotValid() throws SAXException, IOException {
    Schema schema = TestSchemas.txn();
    PerRecordValidator wrapper = new PerRecordValidator(schema);
    byte[] bad = wrapper.wrap("<transaction_record xmlns=\"x\"/>".getBytes(StandardCharsets.UTF_8));
    RecordXsdBench bench = new RecordXsdBench(schema, List.of(bad));
    assertThatThrownBy(() -> bench.runUnits(1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not valid");
  }

  @Test
  void fileGateCycleRunsWholeFilesAndRejectsAnInvalidOne(@TempDir Path dir)
      throws SAXException, IOException {
    Schema schema = TestSchemas.txn();
    Path file = FileGateBench.generate(dir, 300, 5L);
    FileGateCycle cycle = new FileGateCycle(file, schema, 300);
    assertThat(cycle.cycleUnits()).isEqualTo(300);
    assertThat(cycle.runUnits(300)).isEqualTo(300);
    assertThat(cycle.runUnits(900)).isEqualTo(900);
    assertThat(cycle.runUnits(299)).as("no whole file").isZero();
    assertThat(cycle.checksum()).isNotZero();

    Path broken = dir.resolve("broken.xml");
    Files.writeString(broken, "<not-the-schema/>", StandardCharsets.UTF_8);
    FileGateCycle bad = new FileGateCycle(broken, schema, 1);
    assertThatThrownBy(() -> bad.runUnits(1)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void bisectBenchFindsExactlyTheDefectiveRecordWithTheRealSchema()
      throws SAXException, IOException {
    Schema schema = TestSchemas.txn();
    BisectBench bench = new BisectBench(schema, 256, 9L);
    assertThat(bench.cycleUnits()).isEqualTo(1);
    assertThat(bench.runUnits(1)).isEqualTo(1);
    assertThat(bench.lastValidations())
        .as("a bisect needs at least the whole file and at most 2 log2(n) + 3 validations")
        .isBetween(2L, 2L * 8 + 3);
    assertThat(bench.lastBytesValidated()).isPositive();
    assertThat(bench.offenderIndex()).isBetween(0, 255);
    long first = bench.checksum();
    bench.runUnits(1);
    assertThat(bench.checksum()).as("a second cycle does the same work").isNotEqualTo(first);
    // the same bench is deterministic
    BisectBench other = new BisectBench(schema, 256, 9L);
    assertThat(other.offenderIndex()).isEqualTo(bench.offenderIndex());
  }

  @Test
  void bisectValidationCountFollowsTheLogModelAndFindsEveryOffender() {
    for (int k : new int[] {1, 10, 100}) {
      Bisect.Result result = BisectBench.validationCount(4_096, k, 11L + k);
      assertThat(result.offenders()).hasSize(k);
      double bound = 2.0 * k * (Math.log(4_096.0 / k) / Math.log(2) + 1) + 1;
      assertThat((double) result.validations()).as("k = %d", k).isBetween((double) k, bound);
    }
    assertThat(BisectBench.validationCount(1_000, 0, 1L).validations())
        .as("a clean file is validated once")
        .isEqualTo(1);
    assertThatThrownBy(() -> BisectBench.validationCount(10, 11, 1L))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
