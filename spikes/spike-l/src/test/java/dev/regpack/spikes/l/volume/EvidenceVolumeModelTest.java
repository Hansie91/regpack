package dev.regpack.spikes.l.volume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.spikes.l.volume.EvidenceVolumeModel.Inputs;
import dev.regpack.spikes.l.volume.EvidenceVolumeModel.Volume;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceVolumeModelTest {
  private static Inputs inputs(int microBatchN) {
    return new Inputs(
        50_000_000L,
        microBatchN,
        BigDecimal.valueOf(4),
        400L,
        500,
        new BigDecimal("0.01"),
        3,
        20_000L);
  }

  private static BigDecimal d(long value) {
    return BigDecimal.valueOf(value);
  }

  @Test
  void landingObjectsPerDayAreRecordsOverN() {
    Volume v = EvidenceVolumeModel.compute(inputs(5_000));
    assertThat(v.landingObjectsPerDay()).isEqualTo(10_000);
    // a day that does not divide by N rounds up: the last batch is a partial one
    Inputs odd =
        new Inputs(50_000_001L, 5_000, BigDecimal.valueOf(4), 400L, 500, BigDecimal.ZERO, 0, 0L);
    assertThat(EvidenceVolumeModel.compute(odd).landingObjectsPerDay()).isEqualTo(10_001);
  }

  @Test
  void burstPutPerSecondUsesTheBurstWindow() {
    Volume v = EvidenceVolumeModel.compute(inputs(5_000));
    BigDecimal objects = v.objectsPerDay();
    assertThat(objects).isPositive();
    // the day's objects are written inside the burst window: 4 h = 14,400 s against 86,400 s
    BigDecimal ratio =
        v.burstPutPerSecond().divide(v.averagePutPerSecond(), 6, java.math.RoundingMode.HALF_EVEN);
    assertThat(ratio).isEqualByComparingTo(new BigDecimal("6.000000"));
    assertThat(v.burstPutPerSecond().multiply(d(14_400)))
        .isCloseTo(objects, org.assertj.core.data.Offset.offset(new BigDecimal("0.01")));
    assertThat(v.averagePutPerSecond().multiply(d(86_400)))
        .isCloseTo(objects, org.assertj.core.data.Offset.offset(new BigDecimal("0.01")));
    // 50M records in 4 h is the 3,472 records/s burst of the plan
    assertThat(v.burstRecordsPerSecond().setScale(0, java.math.RoundingMode.HALF_EVEN))
        .isEqualByComparingTo(d(3_472));
  }

  @Test
  void everyJournalTypeOfSpec112IsCounted() {
    Volume v = EvidenceVolumeModel.compute(inputs(5_000));
    assertThat(v.journalObjectsPerDay().keySet())
        .containsExactlyElementsOf(
            List.of(
                "BatchAdmitted",
                "VersionAssigned",
                "DeliveryIntent",
                "TransportResult",
                "FeedbackReceived",
                "RecoveryDecision",
                "OperatorAction"));
    // one BatchAdmitted and one batched VersionAssigned object per micro-batch
    assertThat(v.journalObjectsPerDay().get("BatchAdmitted")).isEqualByComparingTo(d(10_000));
    assertThat(v.journalObjectsPerDay().get("VersionAssigned")).isEqualByComparingTo(d(10_000));
    // 50M reports at 500 per file are 100,000 files; a resend rate of 1% adds 1,000 attempts
    assertThat(v.sealedFilesPerDay()).isEqualTo(100_000);
    assertThat(v.journalObjectsPerDay().get("DeliveryIntent")).isEqualByComparingTo(d(101_000));
    assertThat(v.journalObjectsPerDay().get("TransportResult")).isEqualByComparingTo(d(101_000));
    assertThat(v.journalObjectsPerDay().get("FeedbackReceived")).isEqualByComparingTo(d(101_000));
    assertThat(v.journalObjectsPerDay().get("RecoveryDecision")).isEqualByComparingTo(d(1_000));
    assertThat(v.journalObjectsPerDay().get("OperatorAction"))
        .isGreaterThanOrEqualTo(BigDecimal.ZERO);
    assertThat(v.notes()).isNotEmpty();
  }

  @Test
  void doublingNHalvesLandingAndBatchAdmittedCounts() {
    Volume small = EvidenceVolumeModel.compute(inputs(5_000));
    Volume large = EvidenceVolumeModel.compute(inputs(10_000));
    assertThat(large.landingObjectsPerDay()).isEqualTo(small.landingObjectsPerDay() / 2);
    assertThat(large.journalObjectsPerDay().get("BatchAdmitted"))
        .isEqualByComparingTo(small.journalObjectsPerDay().get("BatchAdmitted").divide(d(2)));
    assertThat(large.journalObjectsPerDay().get("VersionAssigned"))
        .isEqualByComparingTo(small.journalObjectsPerDay().get("VersionAssigned").divide(d(2)));
    // what does not depend on N does not move
    assertThat(large.sealedFilesPerDay()).isEqualTo(small.sealedFilesPerDay());
    assertThat(large.journalObjectsPerDay().get("DeliveryIntent"))
        .isEqualByComparingTo(small.journalObjectsPerDay().get("DeliveryIntent"));
  }

  @Test
  void bytesPerDayAreRawInputPlusEnrichmentResponses() {
    Volume v = EvidenceVolumeModel.compute(inputs(5_000));
    assertThat(v.rawBytesPerDay()).isEqualTo(50_000_000L * 400L);
    // 10,000 batches x 3 phases are 30,000 objects; the bytes are per batch
    assertThat(v.enrichmentObjectsPerDay()).isEqualTo(30_000);
    assertThat(v.enrichmentBytesPerDay()).isEqualTo(10_000L * 20_000L);
  }

  @Test
  void invalidInputsAreRejected() {
    assertThatThrownBy(() -> inputs(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Inputs(1L, 5_000, BigDecimal.ZERO, 400L, 500, BigDecimal.ZERO, 0, 0L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Inputs(1L, 5_000, BigDecimal.valueOf(4), 400L, 0, BigDecimal.ZERO, 0, 0L))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
