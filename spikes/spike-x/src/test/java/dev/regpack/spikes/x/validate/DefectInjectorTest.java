package dev.regpack.spikes.x.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.spikes.x.TestDocuments;
import dev.regpack.spikes.x.validate.DefectInjector.Kind;
import dev.regpack.spikes.x.xml.TxModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefectInjectorTest {
  private record Run(
      int defects, Map<Kind, Integer> kinds, List<Optional<Kind>> sequence, long bytesFold) {}

  private static Run run(long seed, double rate, List<TxModel> ring, int records) {
    DefectInjector injector = new DefectInjector(seed, rate);
    Map<Kind, Integer> kinds = new EnumMap<>(Kind.class);
    List<Optional<Kind>> sequence = new ArrayList<>(records);
    byte[][] out = new byte[1][];
    long fold = 17;
    int defects = 0;
    for (int i = 0; i < records; i++) {
      Optional<Kind> kind = injector.maybeDefect(ring.get(i % ring.size()), out);
      sequence.add(kind);
      assertThat(out[0]).as("the serialised record is always delivered").isNotEmpty();
      if (kind.isPresent()) {
        defects++;
        kinds.merge(kind.get(), 1, Integer::sum);
        fold = fold * 31 + Arrays.hashCode(out[0]);
      }
    }
    return new Run(defects, kinds, sequence, fold);
  }

  @Test
  void defectRateAndKindsAreSeeded() {
    List<TxModel> ring = TestDocuments.records(21, 100);
    Run first = run(77L, 0.01, ring, 100_000);
    Run same = run(77L, 0.01, ring, 100_000);
    Run other = run(78L, 0.01, ring, 10_000);
    assertThat(first.defects())
        .as("defects at rate 0.01 over 100,000 records")
        .isBetween(900, 1_100);
    assertThat(first.kinds().keySet()).containsExactlyInAnyOrder(Kind.values());
    assertThat(first.sequence()).isEqualTo(same.sequence());
    assertThat(first.bytesFold()).isEqualTo(same.bytesFold());
    assertThat(other.sequence())
        .as("another seed, the same first 10,000 records")
        .isNotEqualTo(first.sequence().subList(0, 10_000));
  }

  @Test
  void rateZeroNeverInjectsAndRateOneAlwaysDoes() {
    List<TxModel> ring = TestDocuments.records(22, 50);
    assertThat(run(1L, 0.0, ring, 2_000).defects()).isZero();
    assertThat(run(1L, 1.0, ring, 2_000).defects()).isEqualTo(2_000);
  }

  @Test
  void aRecordWithoutDefectIsDeliveredAsItsOwnSerialisation() {
    List<TxModel> ring = TestDocuments.records(23, 20);
    DefectInjector injector = new DefectInjector(5L, 0.0);
    byte[][] out = new byte[1][];
    for (TxModel model : ring) {
      assertThat(injector.maybeDefect(model, out)).isEmpty();
      assertThat(out[0]).isEqualTo(DefectInjector.serialise(model));
    }
  }

  @Test
  void everyKindChangesTheRecordInTheWayItsNameSays() {
    for (TxModel model : TestDocuments.records(24, 40)) {
      for (Kind kind : Kind.values()) {
        for (int variant = 0; variant < 4; variant++) {
          TxModel bad = DefectInjector.corrupt(model, kind, variant);
          assertThat(bad).as("%s variant %d", kind, variant).isNotEqualTo(model);
          if (kind == Kind.MISSING_MANDATORY) {
            assertThat(bad.size()).isEqualTo(model.size() - 1);
          } else {
            assertThat(bad.size()).isEqualTo(model.size());
          }
          if (kind == Kind.WRONG_ORDER) {
            assertThat(bad.names()).containsExactlyInAnyOrderElementsOf(model.names());
            assertThat(bad.names()).isNotEqualTo(model.names());
          }
        }
      }
    }
  }

  @Test
  void aRateOutsideZeroToOneIsRejected() {
    assertThatThrownBy(() -> new DefectInjector(1L, -0.1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DefectInjector(1L, 1.1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DefectInjector(1L, Double.NaN))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
