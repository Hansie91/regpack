package dev.regpack.spikes.l.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.spikes.l.restore.RtoModel.Bounds;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RtoModelTest {
  private static List<BigDecimal> list(long... values) {
    return java.util.Arrays.stream(values).mapToObj(BigDecimal::valueOf).toList();
  }

  private static void assertEqualNumber(BigDecimal actual, long expected) {
    assertThat(actual)
        .as(actual.toPlainString())
        .isEqualByComparingTo(BigDecimal.valueOf(expected));
  }

  @Test
  void projectionUsesTheilSenAndTheFloor() {
    // seconds = 2 x (keys in millions) + 5: collinear points project exactly, the replay is added
    // after
    List<BigDecimal> keys = list(500_000, 1_000_000, 2_000_000, 5_000_000);
    List<BigDecimal> seconds =
        List.of(
            new BigDecimal("6"), new BigDecimal("7"), new BigDecimal("9"), new BigDecimal("15"));
    Bounds b =
        RtoModel.project(
            keys,
            seconds,
            BigDecimal.valueOf(10_000_000),
            BigDecimal.ZERO,
            BigDecimal.valueOf(100));
    assertEqualNumber(b.point(), 25 + 100);
    assertEqualNumber(b.lower(), 25 + 100);
    assertEqualNumber(b.upper(), 25 + 100);

    // a floor above the projection wins; the replay is still added on top of it
    Bounds floored =
        RtoModel.project(
            keys,
            seconds,
            BigDecimal.valueOf(10_000_000),
            BigDecimal.valueOf(400),
            BigDecimal.valueOf(100));
    assertEqualNumber(floored.point(), 500);
    assertEqualNumber(floored.lower(), 500);
    assertEqualNumber(floored.upper(), 500);
  }

  @Test
  void everyBoundIsRaisedToTheFloorAndOrderedLowerPointUpper() {
    // a noisy series: the bounds differ, none may fall below the floor
    List<BigDecimal> keys = list(1, 2, 3, 4, 5);
    List<BigDecimal> seconds = list(10, 18, 33, 38, 52);
    Bounds b =
        RtoModel.project(
            keys, seconds, BigDecimal.valueOf(100), BigDecimal.valueOf(300), BigDecimal.ZERO);
    assertThat(b.lower()).isLessThanOrEqualTo(b.point());
    assertThat(b.point()).isLessThanOrEqualTo(b.upper());
    assertThat(b.lower()).isGreaterThanOrEqualTo(BigDecimal.valueOf(300));
    Bounds noFloor =
        RtoModel.project(keys, seconds, BigDecimal.valueOf(100), BigDecimal.ZERO, BigDecimal.ZERO);
    assertThat(noFloor.lower()).isLessThan(noFloor.upper());
    assertThat(noFloor.point()).isBetween(noFloor.lower(), noFloor.upper());
  }

  @Test
  void fewerThanThreePointsIsRejected() {
    assertThatThrownBy(
            () ->
                RtoModel.project(
                    list(1, 2), list(10, 20), BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 3");
  }

  @Test
  void seriesOfDifferentLengthAreRejected() {
    assertThatThrownBy(
            () ->
                RtoModel.project(
                    list(1, 2, 3), list(10, 20), BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void floorIsTwiceTheSizeOverTheSlowerRate() {
    // 40 GB, read 400 MB/s, write 200 MB/s: 2 x 40e9 / 200e6 = 400 s
    assertEqualNumber(RtoModel.floorSeconds(40_000_000_000L, 400_000_000L, 200_000_000L), 400);
    // the slower of the two counts, whichever it is
    assertEqualNumber(RtoModel.floorSeconds(40_000_000_000L, 200_000_000L, 400_000_000L), 400);
  }

  @Test
  void aFloorNeedsPositiveRates() {
    assertThatThrownBy(() -> RtoModel.floorSeconds(1L, 0L, 1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void replayTermScalesWithTheBackupInterval() {
    // 60 s of replay for a gap of 1,000,000 records; a day holds 50,000,000 records
    BigDecimal sixHours =
        RtoModel.replaySeconds(60_000_000_000L, 1_000_000L, 50_000_000L, BigDecimal.valueOf(6));
    // 60 s per million x 50 million per day x 6/24 day = 750 s
    assertEqualNumber(sixHours, 750);
    BigDecimal twelveHours =
        RtoModel.replaySeconds(60_000_000_000L, 1_000_000L, 50_000_000L, BigDecimal.valueOf(12));
    assertThat(twelveHours).isEqualByComparingTo(sixHours.multiply(BigDecimal.valueOf(2)));
    BigDecimal day =
        RtoModel.replaySeconds(60_000_000_000L, 1_000_000L, 50_000_000L, BigDecimal.valueOf(24));
    assertEqualNumber(day, 3_000);
  }

  @Test
  void aReplayTermNeedsAGapAndAnInterval() {
    assertThatThrownBy(() -> RtoModel.replaySeconds(1L, 0L, 50_000_000L, BigDecimal.valueOf(6)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RtoModel.replaySeconds(1L, 1L, 50_000_000L, BigDecimal.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
