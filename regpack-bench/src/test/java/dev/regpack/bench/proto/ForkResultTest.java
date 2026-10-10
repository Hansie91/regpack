package dev.regpack.bench.proto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

class ForkResultTest {
  private static ForkResult sample() {
    return new ForkResult(
        4,
        true,
        false,
        "",
        List.of(1500.25, 1499.75, 1502.5),
        List.of(310.5, 309.25),
        List.of(9000.125, 8999.5),
        List.of(2500.0, 2400.0),
        0.0125,
        -1234567890123L);
  }

  @Test
  void statisticIsTheMedianOfTheWindowRates() {
    assertThat(sample().statistic()).isEqualTo(1500.25);
    assertThatThrownBy(
            () ->
                new ForkResult(
                        0,
                        false,
                        true,
                        "HARNESS_ERROR: x",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        0.0,
                        0)
                    .statistic())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aResultLineRoundTrips() {
    ForkResult original = sample();
    String line = original.toLine();
    assertThat(line).startsWith("FORK-RESULT {").doesNotContain("\n");
    ForkResult parsed = ForkResult.parseLine(line);
    assertThat(parsed).isEqualTo(original);
  }

  @Test
  void aResultLineHoldsNoExponentNotation() {
    ForkResult big =
        new ForkResult(
            0, false, false, "", List.of(1.5e12, 2.0e-3), List.of(), List.of(), List.of(), 0.0, 1);
    assertThat(big.toLine()).doesNotContainPattern("[0-9][eE][+-]?[0-9]");
    assertThat(ForkResult.parseLine(big.toLine()).windowRates().get(0))
        .isEqualTo(1.5e12, within(1e-6));
  }

  @Test
  void ratesAreRoundedToNineDecimalsInTheLine() {
    ForkResult r =
        new ForkResult(
            0, false, false, "", List.of(1.0 / 3.0), List.of(), List.of(), List.of(), 0.0, 1);
    assertThat(ForkResult.parseLine(r.toLine()).windowRates().get(0)).isEqualTo(0.333333333);
  }

  @Test
  void aMalformedLineIsRejected() {
    assertThatThrownBy(() -> ForkResult.parseLine("something else"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ForkResult.parseLine("FORK-RESULT {not json"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ForkResult.parseLine("FORK-RESULT {\"index\":1}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ForkResult.parseLine("FORK-RESULT []"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aDiscardNeedsAReasonAndAKeptForkHasNone() {
    assertThatThrownBy(
            () ->
                new ForkResult(
                    0, false, true, "", List.of(), List.of(), List.of(), List.of(), 0.0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ForkResult(
                    0, false, false, "why", List.of(1.0), List.of(), List.of(), List.of(), 0.0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aKeptForkNeedsAtLeastOneWindowRate() {
    assertThatThrownBy(
            () ->
                new ForkResult(
                    0, false, false, "", List.of(), List.of(), List.of(), List.of(), 0.0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("window");
    // a discarded fork may have none, and the line of a kept fork without one is malformed
    String line =
        "FORK-RESULT {\"canaryCpu\":[],\"canaryDisk\":[],\"canaryMemory\":[],\"checksum\":1,"
            + "\"discardReason\":\"\",\"discarded\":false,\"index\":0,"
            + "\"stealFraction\":0.000000000,\"unstable\":false,\"windowRates\":[]}";
    assertThatThrownBy(() -> ForkResult.parseLine(line))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void withIndexReplacesOnlyTheIndex() {
    ForkResult moved = sample().withIndex(9);
    assertThat(moved.index()).isEqualTo(9);
    assertThat(moved.windowRates()).isEqualTo(sample().windowRates());
    assertThat(moved.checksum()).isEqualTo(sample().checksum());
  }
}
